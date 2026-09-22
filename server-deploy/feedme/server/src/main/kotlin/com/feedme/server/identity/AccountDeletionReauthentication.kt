package com.feedme.server.identity

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.auth.VerifiedSupabaseSubject
import java.sql.Connection
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Signature and exact same-person binding only, not recent authentication or consent.
 * Verify outside the database transaction: key loading may perform bounded network I/O.
 * The ordinary app bearer/device stays bound to [original]; the separate proof must never
 * replace it or bootstrap/register another device. No raw proof token is retained here. */
internal class VerifiedAccountDeletionProof private constructor(
    val original: VerifiedSupabaseSubject,
    val fresh: VerifiedSupabaseSubject,
    internal val tokenSha256: String,
) {
    override fun toString() = "VerifiedAccountDeletionProof(<redacted>)"

    companion object {
        internal suspend fun verify(original: VerifiedSupabaseSubject, token: SecretText,
            verifier: SupabaseUserAccessVerifier): VerifiedAccountDeletionProof {
            currentCoroutineContext().ensureActive()
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Deletion verification interrupted")
            val result = try { verifier.verify(token) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
                catch (_: Exception) { throw AccountFailure(AccountFailureCode.NOT_CONFIGURED) }
            currentCoroutineContext().ensureActive()
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Deletion verification interrupted")
            val fresh = when (result) {
                is PortResult.Value -> result.value
                is PortResult.Failure -> throw AccountFailure(when (result.reason) {
                    FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION,
                    FailureReason.INVALID_DATA, FailureReason.NOT_FOUND -> AccountFailureCode.UNAUTHENTICATED
                    else -> AccountFailureCode.NOT_CONFIGURED
                })
            }
            if (fresh.issuer != original.issuer || fresh.subject != original.subject ||
                fresh.providerSessionId == original.providerSessionId)
                throw AccountFailure(AccountFailureCode.UNAUTHENTICATED)
            return VerifiedAccountDeletionProof(original, fresh, token.use(::deletionProofSha256))
        }
    }
}

internal fun deletionProofSha256(token: String): String {
    val bytes = token.encodeToByteArray()
    return try { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } }
    finally { bytes.fill(0) }
}

internal class AccountDeletionProofVerifier(private val verifier: SupabaseUserAccessVerifier) {
    suspend fun verify(original: VerifiedSupabaseSubject, token: SecretText): VerifiedAccountDeletionProof =
        VerifiedAccountDeletionProof.verify(original, token, verifier)
    override fun toString() = "AccountDeletionProofVerifier(<redacted>)"
}

/** Both provider sessions were current and the separate OAuth session was recent in the
 * same transaction. Not account/device ownership, deletion confirmation, a one-time grant,
 * or proof that Google prompted for a password/MFA. D3 must bind and consume this decision
 * with the durable command, original owned device and lifecycle fence before committing.
 * Revalidate after all command writes/waits; no token/refresh/JWT timestamp substitutes
 * for the locked provider session and AMR rows. */
internal class AccountDeletionReauthenticationEvidence internal constructor(
    private val authority: SupabasePostgresAuthority,
    private val issuerSeal: Any,
    private val connection: Connection,
    private val thread: Thread,
    internal val transaction: Long,
    internal val proof: VerifiedAccountDeletionProof,
    val sessionCreatedAt: Instant,
    val oauthAuthenticatedAt: Instant,
    val acceptedAt: Instant,
    val validUntil: Instant,
) {
    fun revalidate(connection: Connection) = authority.revalidateAccountDeletionOAuth(connection, this)
    internal fun requireBinding(owner: SupabasePostgresAuthority, seal: Any, c: Connection) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Deletion reauthentication interrupted")
        if (authority !== owner || issuerSeal !== seal || connection !== c || thread !== Thread.currentThread())
            throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
    }
    override fun toString() = "AccountDeletionReauthenticationEvidence(<redacted>)"
}
