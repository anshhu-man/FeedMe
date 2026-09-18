package com.feedme.server.guest

/** Explicit HTTP metadata boundary, not a reusable authority, credential or lease. */
internal class GuestCurrentSessionReply(private val exact: String) {
    fun encodeForResponse(): ByteArray = exact.encodeToByteArray()
    override fun toString(): String = "GuestCurrentSessionReply(<redacted>)"
}
