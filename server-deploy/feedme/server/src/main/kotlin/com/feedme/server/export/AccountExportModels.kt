package com.feedme.server.export

import com.feedme.server.auth.VerifiedSupabaseSubject
import java.time.Instant
import java.util.UUID

internal class AccountExportPolicy(val enabled:Boolean, val policyRevision:String,
    val recentAuthSeconds:Int=300, val jobLifetimeSeconds:Int=86400, val downloadSeconds:Int=60,
    val pollAfterSeconds:Int=5, val maxResponseBytes:Int=16384) {
    init {
        require(policyRevision.isNotBlank() && policyRevision.length<=128 && policyRevision.none(Char::isISOControl))
        require(recentAuthSeconds in 1..900 && jobLifetimeSeconds in 60..86400 && downloadSeconds in 1..60 &&
            downloadSeconds<=jobLifetimeSeconds && pollAfterSeconds in 1..60 && maxResponseBytes in 4096..262144)
    }
    override fun toString()="AccountExportPolicy(<redacted>)"
}
internal enum class ExportFailureCode(val status:Int) {
    INPUT_INVALID(422), MEDIA_EXPORT_UNSUPPORTED(422), REAUTHENTICATION_REQUIRED(428), ACTIVE_EXPORT_EXISTS(409),
    JOB_UNAVAILABLE(404), EXPORT_EXPIRED(410), LEASE_LOST(409), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503)
}
internal class ExportFailure(val code:ExportFailureCode):RuntimeException("Account export unavailable: ${code.name}")
internal class ExportLease internal constructor(val environment:String,val jobId:UUID,val accountId:UUID,val principalId:UUID,
    val issuer:String,val providerSubject:UUID,val deviceSessionId:UUID,val providerSessionId:UUID,
    val token:UUID,val generation:Long,val expiresAt:Instant,val jobExpiresAt:Instant,val policyRevision:String) {
    override fun toString()="ExportLease(<redacted>)"
}
internal class ExportPreparedArtifact(val artifactId:UUID,val bucket:String,val objectKey:String,val keyId:String,
    wrappedKey:ByteArray,nonce:ByteArray,ciphertext:ByteArray,val plaintextSha256:String,val plaintextBytes:Int,
    val cipherSha256:String,val expiresAt:Instant) : AutoCloseable {
    private val key=wrappedKey.copyOf(); private val iv=nonce.copyOf(); private val bytes=ciphertext.copyOf()
    val wrappedKey get()=key.copyOf(); val nonce get()=iv.copyOf(); val ciphertext get()=bytes.copyOf()
    override fun close(){key.fill(0);iv.fill(0);bytes.fill(0)}
    override fun toString()="ExportPreparedArtifact(<redacted>)"
}
internal class ExportArtifact internal constructor(val environment:String,val jobId:UUID,val accountId:UUID,val principalId:UUID,
    val artifactId:UUID,val bucket:String,val objectKey:String,val keyId:String,wrappedKey:ByteArray?,nonce:ByteArray,
    ciphertext:ByteArray?,val plaintextSha256:String,val plaintextBytes:Int,val cipherSha256:String,
    val cipherBytes:Int,val expiresAt:Instant,val writeAttempted:Boolean,val ready:Boolean) : AutoCloseable {
    private val key=wrappedKey?.copyOf();private val iv=nonce.copyOf();private val bytes=ciphertext?.copyOf()
    val wrappedKey get()=key?.copyOf();val nonce get()=iv.copyOf();val ciphertext get()=bytes?.copyOf()
    val hasWrappedKey get()=key!=null
    override fun close(){key?.fill(0);iv.fill(0);bytes?.fill(0)}
    override fun toString()="ExportArtifact(<redacted>)"
}
internal class ExportDownloadCapability(val url:String,val expiresAt:Instant) {
    override fun toString()="ExportDownloadCapability(<redacted>)"
}
internal fun interface AccountExportDownloadIssuer {
    /** RAM-only capability creation; never storage I/O inside the caller transaction. */
    fun issue(subject:VerifiedSupabaseSubject,device:UUID,artifact:ExportArtifact,validUntil:Instant):ExportDownloadCapability
}
