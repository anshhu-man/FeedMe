package com.feedme.server.social.posts

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.db.PgTransactions
import com.feedme.server.http.SocialHttpBearer
import com.feedme.server.http.SocialHttpVerifier
import com.feedme.server.social.VerifiedSocialAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible

/** Provider verification finishes before opening a database transaction. Resolving the
 * actual account/device is not posting eligibility: each real content/mutation callback
 * rechecks that in its own transaction. In particular, owner draft cleanup does not require
 * accepting new community Terms or enabling new uploads/publications. */
internal class SupabaseAccountPostHttpVerifier(private val verifier: SupabaseUserAccessVerifier,
    private val transactions: PgTransactions, private val authority: AccountPostContentAuthority,
    private val databaseDispatcher: CoroutineDispatcher) : SocialHttpVerifier {
    override suspend fun verify(bearer: SocialHttpBearer): PortResult<VerifiedSocialAccount> {
        val subject = when (val result = verifier.verify(bearer.token)) {
            is PortResult.Failure -> return result
            is PortResult.Value -> result.value
        }
        return try {
            PortResult.Value(runInterruptible(databaseDispatcher) {
                transactions.run { authority.resolvePrincipal(it, subject, bearer.deviceSessionId) }
            })
        } catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (failure: PostPublicationFailure) {
            PortResult.Failure(if (failure.suppressed.isNotEmpty()) FailureReason.STORAGE_FAILURE else when (failure.code) {
                PostPublicationFailureCode.UNAUTHENTICATED -> FailureReason.UNAUTHENTICATED
                PostPublicationFailureCode.FORBIDDEN -> FailureReason.FORBIDDEN
                PostPublicationFailureCode.NOT_CONFIGURED -> FailureReason.NOT_CONFIGURED
                else -> FailureReason.STORAGE_FAILURE
            })
        } catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
    }
    override fun toString() = "SupabaseAccountPostHttpVerifier(<redacted>)"
}
