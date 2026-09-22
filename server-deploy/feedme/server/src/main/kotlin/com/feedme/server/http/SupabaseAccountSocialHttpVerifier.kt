package com.feedme.server.http

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.db.PgTransactions
import com.feedme.server.social.AccountSocialIdentityPolicy
import com.feedme.server.social.SocialFailure
import com.feedme.server.social.SocialFailureCode
import com.feedme.server.social.VerifiedSocialAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible

/** Real Supabase user-token verification followed by current app/device/profile admission.
 * Network verification completes before the database transaction. CirclesStore rechecks the
 * resulting bound principal, current Terms, eligibility and roles inside every actual read,
 * mutation and idempotent replay. Public invitation preview never calls this verifier.
 */
internal class SupabaseAccountSocialHttpVerifier(
    private val verifier: SupabaseUserAccessVerifier,
    private val transactions: PgTransactions,
    private val identity: AccountSocialIdentityPolicy,
    private val databaseDispatcher: CoroutineDispatcher,
) : SocialHttpVerifier {
    override suspend fun verify(bearer: SocialHttpBearer): PortResult<VerifiedSocialAccount> {
        val verified = verifier.verify(bearer.token)
        val subject = when (verified) {
            is PortResult.Failure -> return verified
            is PortResult.Value -> verified.value
        }
        return try {
            PortResult.Value(runInterruptible(databaseDispatcher) {
                transactions.run { connection -> identity.resolvePrincipal(connection, subject, bearer.deviceSessionId) }
            })
        } catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (failure: SocialFailure) {
            PortResult.Failure(if (failure.suppressed.isNotEmpty()) FailureReason.STORAGE_FAILURE else when (failure.code) {
                SocialFailureCode.UNAUTHENTICATED -> FailureReason.UNAUTHENTICATED
                SocialFailureCode.CIRCLE_UNAVAILABLE -> FailureReason.FORBIDDEN
                SocialFailureCode.NOT_CONFIGURED -> FailureReason.NOT_CONFIGURED
                else -> FailureReason.STORAGE_FAILURE
            })
        } catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
    }

    override fun toString() = "SupabaseAccountSocialHttpVerifier(<redacted>)"
}
