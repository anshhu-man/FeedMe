package com.feedme.server.export

import com.feedme.server.auth.VerifiedSupabaseSubject
import java.net.URI
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Same-backend, single-use short capability. No provider URL, plaintext cache or bearer
 * is retained. In a multi-instance deployment a token routed elsewhere returns unavailable.
 * The HTTP owner must use private/no-store, no-referrer, nosniff and attachment disposition;
 * it owns and clears the returned buffer after sending it. Never log path capabilities. */
internal class AccountExportDownloadService(private val store:AccountExportStore,
    private val objects:SupabaseExportObjects,private val encryption:AccountExportEncryption,
    private val publicOrigin:String,private val clock:Clock,private val downloadSeconds:Int,
    private val maxCapabilities:Int=32):AccountExportDownloadIssuer,AutoCloseable {
    private val random=SecureRandom()
    private val entries=LinkedHashMap<String,Entry>()
    private val deliveries=mutableSetOf<ExportDelivery>()
    private val admission=Semaphore(2)
    private val closed=AtomicBoolean()
    init {
        val u=URI(publicOrigin)
        require(publicOrigin.length<=512 && u.scheme=="https" && !u.host.isNullOrBlank() && u.host==u.host.lowercase() &&
            u.rawUserInfo==null && u.rawQuery==null && u.rawFragment==null && u.rawPath.isEmpty() &&
            u.port in setOf(-1,443) && u.toASCIIString()==publicOrigin && !publicOrigin.contains('%') && !publicOrigin.contains('\\'))
        require(downloadSeconds in 1..60 && maxCapabilities in 1..128)
    }
    @Synchronized override fun issue(subject:VerifiedSupabaseSubject,device:UUID,artifact:ExportArtifact,
        validUntil:Instant):ExportDownloadCapability {
        val at=clock.instant();prune(at)
        if(closed.get() || entries.size>=maxCapabilities || !artifact.ready)fail(ExportFailureCode.STORAGE_UNAVAILABLE)
        val until=minOf(validUntil,artifact.expiresAt,Instant.ofEpochSecond(subject.expiresAtEpochSeconds),at.plusSeconds(downloadSeconds.toLong()))
        if(until<=at)fail(ExportFailureCode.EXPORT_EXPIRED)
        // Only metadata/facts, not ciphertext/wrapped keys, are retained in the capability map.
        val bytes=ByteArray(32).also(random::nextBytes)
        val token=try{Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}finally{bytes.fill(0)}
        if(entries.containsKey(token))fail(ExportFailureCode.STORAGE_UNAVAILABLE)
        entries[token]=Entry(subject,device,artifact.environment,artifact.jobId,artifact.accountId,artifact.principalId,
            artifact.artifactId,artifact.cipherSha256,artifact.plaintextSha256,artifact.plaintextBytes,at,until,System.nanoTime())
        return ExportDownloadCapability("$publicOrigin/v1/account/export-downloads/$token",until)
    }
    /** A failed or interrupted attempt consumes the URL. The authenticated Job read can
     * issue another; no token alone can bypass current account/provider/session checks. */
    fun openDelivery(capability:String):ExportDelivery? {
        if(!TOKEN.matches(capability))return null
        if(!admission.tryAcquire())fail(ExportFailureCode.STORAGE_UNAVAILABLE)
        var plaintext:ByteArray?=null
        var transferred=false
        try {
            val entry=synchronized(this){prune(clock.instant());if(closed.get())null else entries.remove(capability)}?:return null
            current(entry)
            store.loadDownload(entry.subject,entry.device,entry.jobId).use { before ->
                exact(entry,before)
                objects.requirePrivateBucket()
                val cipher=objects.readVerified(before)?:fail(ExportFailureCode.STORAGE_UNAVAILABLE)
                try { plaintext=encryption.decrypt(before,cipher) } finally { cipher.fill(0) }
                store.loadDownload(entry.subject,entry.device,entry.jobId).use { after -> exact(entry,after) }
                current(entry)
            }
            val retained=checkNotNull(plaintext)
            val delivery=ExportDelivery(retained,entry.expiresAt,clock,
                { !closed.get() && live(entry,clock.instant()) },
                { finished -> synchronized(this){deliveries.remove(finished)};admission.release() })
            synchronized(this) {
                if(closed.get())fail(ExportFailureCode.EXPORT_EXPIRED)
                deliveries.add(delivery);transferred=true;plaintext=null
            }
            return delivery
        } finally { plaintext?.fill(0);if(!transferred)admission.release() }
    }
    private fun exact(e:Entry,a:ExportArtifact) {
        if(a.environment!=e.environment || a.jobId!=e.jobId || a.accountId!=e.accountId || a.principalId!=e.principalId ||
            a.artifactId!=e.artifactId || a.cipherSha256!=e.cipherSha256 || a.plaintextSha256!=e.plaintextSha256 ||
            a.plaintextBytes!=e.plaintextBytes || !a.ready || a.expiresAt<=clock.instant())fail(ExportFailureCode.EXPORT_EXPIRED)
        current(e)
    }
    @Synchronized private fun current(e:Entry) {
        if(closed.get() || !live(e,clock.instant()))fail(ExportFailureCode.EXPORT_EXPIRED)
        if(Thread.currentThread().isInterrupted)throw InterruptedException("Export download interrupted")
    }
    private fun prune(at:Instant){entries.entries.removeIf{!live(it.value,at)}}
    private fun live(e:Entry,at:Instant)=at>=e.started && at<e.expiresAt &&
        System.nanoTime()-e.nanoStarted in 0 until TimeUnit.SECONDS.toNanos(downloadSeconds.toLong())
    override fun close(){
        val owned=synchronized(this){closed.set(true);entries.clear();deliveries.toList().also{deliveries.clear()}}
        owned.forEach{it.close()}
    }
    override fun toString()="AccountExportDownloadService(<redacted>)"
    private class Entry(val subject:VerifiedSupabaseSubject,val device:UUID,val environment:String,val jobId:UUID,
        val accountId:UUID,val principalId:UUID,val artifactId:UUID,val cipherSha256:String,val plaintextSha256:String,
        val plaintextBytes:Int,val started:Instant,val expiresAt:Instant,val nanoStarted:Long)
    private companion object {
        val TOKEN=Regex("[A-Za-z0-9_-]{43}")
        fun fail(code:ExportFailureCode):Nothing=throw ExportFailure(code)
    }
}

/** Owns the short-lived verified plaintext only while the bounded HTTP response streams. */
internal class ExportDelivery internal constructor(private val bytes:ByteArray,val expiresAt:Instant,
    private val clock:Clock,private val ownerCurrent:()->Boolean,private val onClose:(ExportDelivery)->Unit):AutoCloseable {
    val byteCount:Int get()=bytes.size
    private val deadline=System.nanoTime()+Duration.between(clock.instant(),expiresAt).toNanos().coerceAtLeast(0)
    private var closed=false
    @Synchronized fun remainingMillis():Long = minOf((deadline-System.nanoTime())/1_000_000,
        Duration.between(clock.instant(),expiresAt).toMillis()).coerceAtLeast(0)
    @Synchronized fun isCurrent():Boolean = !closed && remainingMillis()>0 && ownerCurrent()
    @Synchronized fun copyChunk(offset:Int,count:Int):ByteArray? {
        require(offset>=0 && count in 1..65_536 && offset.toLong()+count<=bytes.size)
        return if(isCurrent())bytes.copyOfRange(offset,offset+count) else null
    }
    @Synchronized override fun close(){if(!closed){closed=true;bytes.fill(0);onClose(this)}}
    override fun toString()="ExportDelivery(<redacted>)"
}
