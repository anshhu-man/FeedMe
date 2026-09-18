package com.feedme.server.catalog

import com.feedme.server.kitchen.*
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

/** Search-only owner/query/limit/publication/policy-bound keyset. No authority grant or raw
 * query is carried in its visible payload. Keys and expiry policy are explicitly configured. */
class IngredientSearchCursor(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { it.value.copyOf() }
    init { require(currentKeyId in keys && keys.size in 1..8); require(keys.all { (id, key) -> id.matches(Regex("[a-z0-9_-]{1,32}")) && key.size == 32 }) }
    internal fun encode(actor: VerifiedKitchenPrincipal, query: String?, limit: Int, revision: Long,
        mode: IngredientSearchMode, after: UUID, expires: Instant): String {
        val payload = "$currentKeyId.$revision.$after.${expires.epochSecond}"
        return "$payload.${mac(currentKeyId, actor, query, limit, mode, payload)}"
    }
    internal fun decode(actor: VerifiedKitchenPrincipal, query: String?, limit: Int, revision: Long,
        mode: IngredientSearchMode, cursor: String?, now: Instant): UUID? {
        if (cursor == null) return null
        fun invalid(): Nothing = throw KitchenFailure(KitchenFailureCode.CURSOR_INVALID)
        if (cursor.length !in 1..2048) invalid()
        val p = cursor.split('.')
        if (p.size != 5 || p[0] !in keys || !p[1].matches(Regex("[1-9][0-9]{0,18}")) ||
            !p[3].matches(Regex("[0-9]{1,18}")) || !p[4].matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
        val rev = p[1].toLongOrNull() ?: invalid()
        val after = try { UUID.fromString(p[2]).also { if (it.toString() != p[2]) invalid() } } catch (_: IllegalArgumentException) { invalid() }
        val expiry = p[3].toLongOrNull() ?: invalid()
        if (rev != revision || rev.toString() != p[1] || expiry.toString() != p[3]) invalid()
        if (!MessageDigest.isEqual(mac(p[0], actor, query, limit, mode, p.take(4).joinToString(".")).toByteArray(Charsets.US_ASCII), p[4].toByteArray(Charsets.US_ASCII))) invalid()
        if (expiry <= now.epochSecond) throw KitchenFailure(KitchenFailureCode.CURSOR_EXPIRED)
        return after
    }
    private fun mac(key: String, actor: VerifiedKitchenPrincipal, query: String?, limit: Int,
        mode: IngredientSearchMode, payload: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(keys.getValue(key), "HmacSHA256"))
        val exact = buildJsonArray {
            add("feedme.ingredient-search.v1"); add(actor.environment); add(actor.kind.name); add(actor.principalId.toString())
            add(query?.let(::JsonPrimitive) ?: JsonNull); add(limit); add(mode.wire); add(payload)
        }.toString().encodeToByteArray(throwOnInvalidSequence = true)
        Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(exact))
    }
    override fun toString() = "IngredientSearchCursor(<redacted>)"
}
