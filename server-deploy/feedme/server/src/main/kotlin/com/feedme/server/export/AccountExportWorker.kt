package com.feedme.server.export

import com.feedme.server.db.CommitOutcomeUnknown
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

internal enum class ExportWorkResult { IDLE, READY, AWAITING_INSPECTION, DEFERRED, BLOCKED, FAILED, LEASE_LOST, COMMIT_UNKNOWN }
internal class ExportExpiryResult(val keysExpiredOrAlreadyExpired:Int,val removalObservations:Int,val unavailable:Int)

/** Explicit bounded worker; construction/mount never claims, snapshots or uploads. Run on
 * the worker IO dispatcher, with real DB rights and the dedicated private object credentials.
 * READY means exact encrypted bytes verified and actual owned job committed, not media export
 * or physical erasure. Original ciphertext survives restart until cryptographic expiry. */
internal class AccountExportWorker(private val store:AccountExportStore,private val inventory:AccountExportInventory,
    private val objects:SupabaseExportObjects,private val encryption:AccountExportEncryption,
    private val clock:Clock,private val leaseSeconds:Int=120):AutoCloseable {
    private val busy=AtomicBoolean()
    private val closed=AtomicBoolean()
    init { require(leaseSeconds in 1..300) }

    fun runOne():ExportWorkResult {
        if(closed.get())return ExportWorkResult.BLOCKED
        if(!busy.compareAndSet(false,true))return ExportWorkResult.DEFERRED
        var lease:ExportLease?=null
        try {
            // Fail closed before claiming/consuming a write for a public/unavailable bucket.
            objects.requirePrivateBucket()
            lease=store.claim(UUID.randomUUID(),leaseSeconds)?:return ExportWorkResult.IDLE
            current(lease)
            var original=store.artifact(lease)
            if(original==null) {
                val plaintext=store.snapshot(lease,inventory::snapshot)
                try {
                    current(lease)
                    val id=UUID.randomUUID()
                    encryption.encrypt(lease.environment,lease.jobId,lease.accountId,id,plaintext).use { encrypted ->
                        ExportPreparedArtifact(id,objects.bucket,"exports/${lease.environment}/${lease.jobId}/$id",
                            encrypted.keyId,encrypted.wrappedKey,encrypted.nonce,encrypted.ciphertext,
                            encrypted.plaintextSha256,encrypted.plaintextBytes,encrypted.cipherSha256,lease.jobExpiresAt).use {
                            if(!store.prepareArtifact(lease,it))return ExportWorkResult.BLOCKED
                        }
                    }
                } finally { plaintext.fill(0) }
                // Lost prepare ACK exits via COMMIT_UNKNOWN; no second snapshot/write follows.
                original=store.artifact(lease)?:return ExportWorkResult.BLOCKED
            }
            original.use { retained ->
                current(lease)
                if(retained.ready)return ExportWorkResult.READY
                if(!retained.writeAttempted) {
                    objects.requirePrivateBucket();current(lease)
                    // Only a known committed true permits this invocation's sole POST.
                    if(store.markDispatched(lease)) {
                        store.artifact(lease)?.use { dispatched ->
                            if(dispatched.artifactId!=retained.artifactId || dispatched.cipherSha256!=retained.cipherSha256 ||
                                !dispatched.writeAttempted)return ExportWorkResult.BLOCKED
                            current(lease)
                            val cipher=dispatched.ciphertext?:return ExportWorkResult.BLOCKED
                            try { objects.create(dispatched,cipher) } finally { cipher.fill(0) }
                        }?:return ExportWorkResult.BLOCKED
                    }
                }
                current(lease)
                val observed=objects.readVerified(retained)
                if(observed==null) {
                    store.defer(lease,30)
                    return ExportWorkResult.AWAITING_INSPECTION
                }
                try {
                    current(lease)
                    if(!store.recordObject(lease,exportSha(observed)))return ExportWorkResult.LEASE_LOST
                } finally { observed.fill(0) }
                return ExportWorkResult.READY
            }
        } catch(e:CancellationException){throw e}
        catch(e:InterruptedException){Thread.currentThread().interrupt();throw e}
        catch(_:CommitOutcomeUnknown){return ExportWorkResult.COMMIT_UNKNOWN}
        catch(e:ExportWorkFailure){
            val code=when(e.issue) {
                ExportWorkIssue.LIMIT_EXCEEDED -> "EXPORT_TOO_LARGE"
                ExportWorkIssue.OBJECT_MISMATCH -> "EXPORT_OBJECT_MISMATCH"
                ExportWorkIssue.SOURCE_UNAVAILABLE -> "EXPORT_SOURCE_UNAVAILABLE"
                else -> null
            }
            if(lease!=null && code!=null)return terminalFailure(lease,code)
            lease?.let { postpone(it,30) }
            return if(e.issue==ExportWorkIssue.NOT_CONFIGURED)ExportWorkResult.BLOCKED else ExportWorkResult.DEFERRED
        } catch(e:ExportFailure){return if(e.code==ExportFailureCode.LEASE_LOST)ExportWorkResult.LEASE_LOST else ExportWorkResult.DEFERRED}
        finally { busy.set(false) }
    }

    /** Wrapped-key destruction commits before external deletion. Repeated bounded exact
     * deletes are cleanup observations, never a signed-upload/drain or settlement proof. */
    fun expire(limit:Int=20):ExportExpiryResult {
        require(limit in 1..100)
        if(closed.get() || !busy.compareAndSet(false,true))return ExportExpiryResult(0,0,0)
        try {
            val expired=store.expireArtifacts(limit)
            var removed=0;var unavailable=0
            try { for(original in expired) {
                if(closed.get()) { unavailable++;continue }
                try { if(objects.removeExpired(original))removed++ else unavailable++ }
                catch(e:CancellationException){throw e}
                catch(e:InterruptedException){Thread.currentThread().interrupt();throw e}
                catch(_:ExportWorkFailure){unavailable++}
            } } finally { expired.forEach{it.close()} }
            return ExportExpiryResult(expired.size,removed,unavailable)
        } finally { busy.set(false) }
    }
    private fun postpone(lease:ExportLease,seconds:Int) {
        try { store.defer(lease,seconds) }
        catch(e:CancellationException){throw e}
        catch(e:InterruptedException){Thread.currentThread().interrupt();throw e}
        catch(_:CommitOutcomeUnknown){/* No network action follows an uncertain schedule commit. */}
        catch(_:ExportFailure){/* Lease expiry/revocation remains authoritative. */}
    }
    private fun terminalFailure(lease:ExportLease,code:String):ExportWorkResult = try {
        if(store.failJob(lease,code))ExportWorkResult.FAILED else ExportWorkResult.LEASE_LOST
    } catch(_:CommitOutcomeUnknown){ExportWorkResult.COMMIT_UNKNOWN}
      catch(_:ExportFailure){ExportWorkResult.LEASE_LOST}
    private fun current(lease:ExportLease) {
        if(closed.get() || clock.instant()>=lease.expiresAt || clock.instant()>=lease.jobExpiresAt)throw ExportFailure(ExportFailureCode.LEASE_LOST)
        if(Thread.currentThread().isInterrupted)throw InterruptedException("Export worker interrupted")
    }
    override fun close(){closed.set(true)}
    override fun toString()="AccountExportWorker(<redacted>)"
}
