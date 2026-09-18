package com.feedme.server.identity

import com.feedme.server.auth.VerifiedSupabaseSubject
import java.sql.Connection
import java.time.Instant

/** One actual locked provider observation. Not a serializable/bearer grant, consent, account
 * eligibility or permission to replace any device. Valid only on its creating thread,
 * connection and transaction; the originating authority must re-read its current facts.
 * The private issuer seal prevents an internal structural construction becoming authority. */
class SupabasePasswordReauthenticationEvidence internal constructor(
    private val authority: SupabasePostgresAuthority,
    private val issuerSeal: Any,
    private val connection: Connection,
    private val thread: Thread,
    internal val transaction: Long,
    internal val subject: VerifiedSupabaseSubject,
    val sessionCreatedAt: Instant,
    val passwordAuthenticatedAt: Instant,
    val acceptedAt: Instant,
    val validUntil: Instant,
) {
    fun revalidate(connection: Connection) = authority.revalidatePassword(connection, this)
    internal fun requireBinding(owner: SupabasePostgresAuthority, seal: Any, c: Connection) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Password reauthentication interrupted")
        if (authority !== owner || issuerSeal !== seal || connection !== c || thread !== Thread.currentThread())
            throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
    }
    override fun toString() = "SupabasePasswordReauthenticationEvidence(<redacted>)"
}

/** Arithmetic only; these helpers cannot construct evidence or read/grant provider authority. */
internal fun passwordReauthenticationDeadline(created: Instant, authenticated: Instant, maximumAgeSeconds: Long,
    tokenExpiresAt: Instant, reviewExpiresAt: Instant, lockedProviderExpiresAt: Instant): Instant {
    require(maximumAgeSeconds in 1..900) { "Invalid password reauthentication policy" }
    return minOf(minOf(created.plusSeconds(maximumAgeSeconds), authenticated.plusSeconds(maximumAgeSeconds)),
        minOf(tokenExpiresAt, reviewExpiresAt, lockedProviderExpiresAt))
}
internal fun requirePasswordReauthenticationTime(created: Instant, authenticated: Instant, now: Instant, until: Instant) {
    if (created > authenticated || created > now || authenticated > now || now >= until)
        throw AccountFailure(AccountFailureCode.UNAUTHENTICATED)
}
