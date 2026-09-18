package com.feedme.server.planning

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Required deployment key ring. Cursors never replace current principal/resource authorization. */
class PlanningCursors(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { it.value.copyOf() }
    init {
        require(currentKeyId in this.keys && this.keys.size in 1..8)
        require(this.keys.all { (id, bytes) -> id.matches(Regex("[a-z0-9_-]{1,32}")) && bytes.size == 32 })
    }
    internal fun explanation(binding: String, offset: Int): String = "$currentKeyId.$offset.${mac(currentKeyId, binding, offset)}"
    internal fun offset(binding: String, cursor: String?): Int {
        if (cursor == null) return 0
        if (cursor.length !in 1..2048) fail()
        val parts = cursor.split('.'); if (parts.size != 3 || parts[0] !in keys) fail()
        val offset = parts[1].toIntOrNull()?.takeIf { it in 1..128 && it.toString() == parts[1] } ?: fail()
        val expected = mac(parts[0], binding, offset).toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(expected, parts[2].toByteArray(Charsets.US_ASCII))) fail()
        return offset
    }
    private fun mac(key: String, binding: String, offset: Int): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(keys.getValue(key), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("feedme.plan.explanation.v1\u0000$binding\u0000$offset".toByteArray(Charsets.UTF_8)))
    }
    override fun toString() = "PlanningCursors(<redacted>)"
    private fun fail(): Nothing = throw PlanningServiceFailure(PlanningFailureCode.CURSOR_INVALID)
}
