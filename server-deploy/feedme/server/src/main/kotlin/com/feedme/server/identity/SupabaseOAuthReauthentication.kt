package com.feedme.server.identity

import com.feedme.server.auth.VerifiedSupabaseSubject
import java.sql.Connection
import java.time.Instant

/** One locked observation of an actual recent Supabase OAuth session and its OAuth AMR.
 * Not a Google-specific, PKCE, upstream credential-prompt or password assertion, and not
 * a bearer grant, consent or account/device authority. Only its issuing authority can
 * revalidate it on the creating thread, connection and transaction. */
class SupabaseOAuthReauthenticationEvidence internal constructor(
    private val authority: SupabasePostgresAuthority,
    private val issuerSeal: Any,
    private val connection: Connection,
    private val thread: Thread,
    internal val transaction: Long,
    internal val subject: VerifiedSupabaseSubject,
    val sessionCreatedAt: Instant,
    val oauthAuthenticatedAt: Instant,
    val acceptedAt: Instant,
    val validUntil: Instant,
) {
    fun revalidate(connection: Connection) = authority.revalidateOAuth(connection, this)
    internal fun requireBinding(owner: SupabasePostgresAuthority, seal: Any, c: Connection) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("OAuth reauthentication interrupted")
        if (authority !== owner || issuerSeal !== seal || connection !== c || thread !== Thread.currentThread())
            throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
    }
    override fun toString() = "SupabaseOAuthReauthenticationEvidence(<redacted>)"
}

/** Arithmetic only: no helper can issue evidence or substitute JWT/refresh time for AMR. */
internal fun oauthReauthenticationDeadline(created: Instant, authenticated: Instant, maximumAgeSeconds: Long,
    tokenExpiresAt: Instant, reviewExpiresAt: Instant, lockedProviderExpiresAt: Instant): Instant {
    require(maximumAgeSeconds in 1..900) { "Invalid OAuth reauthentication policy" }
    return minOf(minOf(created.plusSeconds(maximumAgeSeconds), authenticated.plusSeconds(maximumAgeSeconds)),
        minOf(tokenExpiresAt, reviewExpiresAt, lockedProviderExpiresAt))
}
internal fun requireOAuthReauthenticationTime(created: Instant, authenticated: Instant, now: Instant, until: Instant) {
    if (created > authenticated || created > now || authenticated > now || now >= until)
        throw AccountFailure(AccountFailureCode.UNAUTHENTICATED)
}
