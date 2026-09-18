package com.feedme.server.identity

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Explicit published-notice metadata, not legal approval, age eligibility or consent.
 * Operators must keep a published version's document contents and URLs immutable.
 * The runtime never fetches these URLs or fabricates a notice when configuration is absent. */
class AccountTermsNotice(val termsVersion: String, val termsUrl: String, val privacyUrl: String) {
    /** Exact descriptor binding, NOT a digest of remotely hosted document bytes. */
    val noticeSha256: String
    init {
        require(termsVersion.isNotBlank() && termsVersion.length <= 256 && termsVersion.none(Char::isISOControl))
        require(termsVersion.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == termsVersion)
        listOf(termsUrl, privacyUrl).forEach { value ->
            require(value.isNotBlank() && value.length <= 2048 && value.none { it.isWhitespace() || it.isISOControl() })
            val uri = URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.port in setOf(-1, 443) && uri.toASCIIString() == value)
        }
        val bytes = JsonArray(listOf(termsVersion, termsUrl, privacyUrl).map(::JsonPrimitive)).toString().toByteArray(Charsets.UTF_8)
        noticeSha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    override fun toString() = "AccountTermsNotice(<redacted>)"
}
