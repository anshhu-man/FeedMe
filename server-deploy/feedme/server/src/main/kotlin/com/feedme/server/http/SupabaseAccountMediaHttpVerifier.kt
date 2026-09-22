package com.feedme.server.http

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.db.PgTransactions
import com.feedme.server.media.AccountMediaAuthority
import com.feedme.server.media.MediaFailure
import com.feedme.server.media.MediaFailureCode
import com.feedme.server.media.VerifiedMediaAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible

/** Actual Supabase bearer verification finishes outside the DB. Resolve only the current
 * registered app device/account, retaining the verified subject for transactional rechecks.
 * Upload eligibility/Terms are checked by the media authority, not imposed on status/cleanup. */
internal class SupabaseAccountMediaHttpVerifier(private val verifier: SupabaseUserAccessVerifier,
    private val transactions: PgTransactions, private val authority: AccountMediaAuthority,
    private val databaseDispatcher: CoroutineDispatcher) : MediaHttpVerifier {
    override suspend fun verify(bearer: MediaHttpBearer): PortResult<VerifiedMediaAccount> {
        val subject = when (val verified = verifier.verify(bearer.token)) {
            is PortResult.Failure -> return verified
            is PortResult.Value -> verified.value
        }
        return try {
            PortResult.Value(runInterruptible(databaseDispatcher) {
                transactions.run { connection -> authority.resolvePrincipal(connection, subject, bearer.deviceSessionId) }
            })
        } catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (failure: MediaFailure) {
            PortResult.Failure(if (failure.suppressed.isNotEmpty()) FailureReason.STORAGE_FAILURE else when (failure.code) {
                MediaFailureCode.UNAUTHENTICATED -> FailureReason.UNAUTHENTICATED
                MediaFailureCode.FORBIDDEN -> FailureReason.FORBIDDEN
                MediaFailureCode.NOT_CONFIGURED -> FailureReason.NOT_CONFIGURED
                else -> FailureReason.STORAGE_FAILURE
            })
        } catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
    }
    override fun toString() = "SupabaseAccountMediaHttpVerifier(<redacted>)"
}
