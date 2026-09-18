package com.feedme.server.media.processing

import java.time.Instant
import java.util.UUID

/** Private content-bound Supabase output operations, NOT the immutable-version provider port.
 *
 * The store owns each never-reused destination and commits one possible dispatch before create.
 * A failed/uncertain create is reconciled by inspecting that SAME destination, never by creating
 * another capability, retrying POST, overwriting, or replacing the key. inspect returns null only
 * for an authenticated, unambiguous missing object. Every non-null receipt describes a complete
 * bounded read whose actual type, length and digest match the exact persisted intent.
 *
 * Local call/deadline bounds do not prove that Supabase cannot accept a late write. This port
 * deliberately supplies no version ID, deletion, settlement, delivery or publication grant.
 * Implementations must bound admission, calls and buffers, preserve interruption/cancellation,
 * and must not accept caller credentials or discover configuration from the environment.
 */
interface SupabaseDerivativeObjects {
    val bucket: String
    fun inspect(intent: MediaDerivativeIntent): SupabaseDerivativeReceipt?
    fun create(intent: MediaDerivativeIntent, bytes: ByteArray)
}

/** Observation of actual private bytes, not provider immutability or permission to serve them. */
class SupabaseDerivativeReceipt(val bucket: String, val objectKey: String, val sha256: String,
    val bytes: Long, val contentType: String) {
    override fun toString() = "SupabaseDerivativeReceipt(<redacted>)"
}

/** Historical durable checkpoint. Media remains PROCESSING, with the worker held until a
 * separately implemented READY/delivery path is configured. It is never an access grant. */
class SupabasePrivateMaterialization internal constructor(val jobId: UUID, val mediaId: UUID,
    val recordedAt: Instant) {
    override fun toString() = "SupabasePrivateMaterialization(<redacted>)"
}
