package com.feedme.android

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.session.GoogleOAuthCredentials
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** One in-process browser transaction; never saved to Intents, preferences, logs or disk.
 * Losing its Activity/process retires consent and verifier: restart explicitly, never import
 * a callback into a fresh owner. Supabase owns upstream OAuth state. Our random fragment binds
 * the app return to this attempt, independently of PKCE's secret verifier. Pinned Auth v2.197.0
 * preserves the fragment in prepPKCERedirectURL and ignores it for redirect allowlist matching.
 * Thus the registered return URL remains an exact scheme/host/path, not a wildcard.
 * Provider errors that replace that fragment cannot complete this transaction; returning to
 * the app cancels it. A callback is only untrusted code material, never identity or consent.
 */
internal class GoogleOAuthBrowserOwner(
    private val now: () -> Long,
    private val entropy: () -> String = ::oauthRandomValue,
) {
    private val lock = Any()
    private var pending: GoogleOAuthBrowserAttempt? = null

    fun begin(issuer: String, redirect: String, onReturn: () -> Unit = {},
        isCurrent: () -> Boolean): PortResult<GoogleOAuthBrowserAttempt> = synchronized(lock) {
        if (pending != null) return@synchronized PortResult.Failure(FailureReason.CONFLICT)
        try {
            if (!isCurrent()) return@synchronized PortResult.Failure(FailureReason.STALE_SESSION)
            val created = now()
            require(created >= 0 && created <= Long.MAX_VALUE - LIFETIME_MILLIS)
            val verifier = entropy().also { require(it.matches(RANDOM)) }
            val flow = entropy().also { require(it.matches(RANDOM) && it != verifier) }
            val url = googleOAuthAuthorizationUrl(issuer, redirect, verifier, flow)
            val attempt = GoogleOAuthBrowserAttempt(redirect, flow, SecretText(verifier),
                SecretText(url), created, created + LIFETIME_MILLIS, isCurrent, onReturn)
            pending = attempt
            PortResult.Value(attempt)
        } catch (_: Exception) { PortResult.Failure(FailureReason.NOT_CONFIGURED) }
    }

    /** Only the exact outstanding fragment and callback shape can consume the pending code.
     * Invalid/unrelated callbacks do not cancel another person's current local attempt. */
    fun receive(raw: String): Boolean = synchronized(lock) {
        val selected = pending ?: return@synchronized false
        val time = try { now() } catch (_: Exception) { -1L }
        val current = try { selected.isCurrent() } catch (_: Exception) { false }
        if (!current || time < selected.lastSeen || time >= selected.expiresAt) {
            retire(selected, FailureReason.STALE_SESSION)
            return@synchronized false
        }
        selected.lastSeen = time
        val code = googleOAuthCallbackCode(raw, selected.redirect, selected.flow) ?: return@synchronized false
        val verifier = selected.verifier ?: return@synchronized false
        pending = null // Consume before delivery, including a reentrant completion.
        selected.verifier = null
        selected.authorizationUrl = null
        // Bring only the already-owned task forward. A browser callback must never create
        // another AccountActivity/attachment or transport credentials through an Intent.
        try { selected.onReturn() } catch (_: Exception) { /* Manual return remains possible. */ }
        selected.completion.complete(PortResult.Value(GoogleOAuthCredentials(code, verifier)))
    }

    fun cancel(selected: GoogleOAuthBrowserAttempt) = synchronized(lock) {
        if (pending !== selected) return@synchronized
        pending = null
        selected.verifier = null
        selected.authorizationUrl = null
        selected.completion.cancel(CancellationException("Google sign-in cancelled."))
    }

    fun unavailable(selected: GoogleOAuthBrowserAttempt) = synchronized(lock) {
        if (pending === selected) retire(selected, FailureReason.UNAVAILABLE)
    }

    private fun retire(selected: GoogleOAuthBrowserAttempt, reason: FailureReason) {
        pending = null
        selected.verifier = null
        selected.authorizationUrl = null
        selected.completion.complete(PortResult.Failure(reason))
    }

    override fun toString() = "GoogleOAuthBrowserOwner(<redacted>)"

    companion object {
        const val LIFETIME_MILLIS = 600_000L
        private val RANDOM = Regex("[0-9a-f]{64}")
    }
}

internal class GoogleOAuthBrowserAttempt internal constructor(
    internal val redirect: String,
    internal val flow: String,
    internal var verifier: SecretText?,
    internal var authorizationUrl: SecretText?,
    internal var lastSeen: Long,
    internal val expiresAt: Long,
    internal val isCurrent: () -> Boolean,
    internal val onReturn: () -> Unit,
) {
    internal val completion = CompletableDeferred<PortResult<GoogleOAuthCredentials>>()
    override fun toString() = "GoogleOAuthBrowserAttempt(<redacted>)"
}

internal fun googleOAuthAuthorizationUrl(issuer: String, redirect: String, verifier: String, flow: String): String {
    val uri = URI(issuer)
    require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.rawPath == "/auth/v1")
    require(redirect in OAUTH_REDIRECTS && flow.matches(Regex("[0-9a-f]{64}")))
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    val boundRedirect = "$redirect#feedme_flow=$flow"
    return issuer + "/authorize?provider=google&redirect_to=" + encode(boundRedirect) +
        "&code_challenge=" + googleOAuthChallenge(verifier) +
        "&code_challenge_method=s256&scopes=" + encode("openid email profile")
}

internal fun googleOAuthChallenge(verifier: String): String {
    require(verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")))
    val bytes = verifier.toByteArray(Charsets.US_ASCII)
    val digest = try { MessageDigest.getInstance("SHA-256").digest(bytes) } finally { bytes.fill(0) }
    return try { Base64.getUrlEncoder().withoutPadding().encodeToString(digest) } finally { digest.fill(0) }
}

/** The pinned Supabase Auth code is a UUID. Do not accept implicit tokens, recovery links,
 * duplicated/encoded keys, arbitrary URL variants or an upstream Google state/id_token. */
internal fun googleOAuthCallbackCode(raw: String, redirect: String, flow: String): SecretText? = try {
    require(redirect in OAUTH_REDIRECTS && raw.length <= 1024 && raw.all { it.code in 33..126 })
    val uri = URI(raw)
    val expected = URI(redirect)
    require(uri.scheme == expected.scheme && uri.rawAuthority == expected.rawAuthority &&
        uri.rawPath == expected.rawPath && uri.rawFragment == "feedme_flow=$flow")
    val query = requireNotNull(uri.rawQuery)
    require(query.matches(Regex("code=[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
    SecretText(query.removePrefix("code="))
} catch (_: Exception) { null }

private val OAUTH_REDIRECTS = setOf("com.anshhuman.feedme://auth/callback", "com.anshhuman.feedme.debug://auth/callback")

private fun oauthRandomValue(): String {
    val bytes = ByteArray(32)
    return try {
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    } finally { bytes.fill(0) }
}
