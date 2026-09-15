package com.feedme.server.media.processing

import java.io.InputStream
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** Mandatory worker-purpose authority, never an invented user/device or an accepting fallback.
 * DB-only callbacks share root/draft locks with actual account/policy/draft deletion writers.
 * CLEANUP locks the same roots but must allow exact cleanup after account/draft revocation.
 * No callback commits, logs private identifiers, or performs external I/O. Implementations must
 * configure bounded database lock/statement timeouts and propagate cancellation/interruption. */
interface MediaProcessingAuthority {
    fun lockPrincipal(connection: Connection, owner: MediaProcessingOwner, purpose: MediaWorkerPurpose)
    fun lockDraft(connection: Connection, owner: MediaProcessingOwner, draftId: UUID, generation: Long, purpose: MediaWorkerPurpose)
    fun requireProcessing(connection: Connection, source: MediaProcessingSource, policyRevision: String)
    fun requireSafety(connection: Connection, source: MediaProcessingSource, evidence: MediaSafetyEvidence, policyRevision: String, now: Instant)
}
/** External bounded immutable storage. No URL/prefix/bucket or public ACL is accepted.
 * create must conditionally create/reconcile this exact destination and refuse acceptance after its
 * deadline, including queued retries. A lost reply NEVER permits a replacement destination.
 * inspect must verify actual exact immutable bytes/hash, not trust mutable user metadata.
 * settle returns a COMPLETE exact-key inventory only when no late acceptance is possible.
 * deleteVersion is idempotent; success is an actual acknowledgement for precisely that version.
 * Every call, including InputStream read/close, requires explicit provider connection/read/overall
 * deadlines and interruption handling. The caller's checks between synchronous operations cannot
 * interrupt an uncooperative blocking stream. Neither a job lease nor the codec child timeout is
 * an end-to-end pipeline deadline. A timed-out write remains uncertain and keeps its exact intent. */
interface MediaProcessingObjects {
    fun read(source: MediaProcessingSource): InputStream
    fun inspect(intent: MediaDerivativeIntent): MediaDerivativeReceipt?
    fun create(intent: MediaDerivativeIntent, bytes: ByteArray): MediaDerivativeReceipt
    fun settle(cleanup: MediaCleanupLease): SettledMediaVersions
    fun deleteVersion(cleanup: MediaCleanupLease, versionId: String)
}
/** Both malware and moderation are mandatory. Pending/outage does not mean rejected or ready.
 * The adapter must bound each external assessment with explicit timeouts and propagate caller
 * cancellation/interruption; the codec timeout does not cover this independent provider call. */
fun interface MediaSafetyAssessment {
    fun assess(source: MediaProcessingSource, derivatives: List<EncodedPhotoVariant>): MediaSafetyResult
}
