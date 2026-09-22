package com.feedme.server.ai

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.db.PgTransactions
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import com.feedme.server.identity.AccountProfileStore
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible

/** Actual persisted 18+ self-attestation, exact connected Terms, ready account and current
 * provider/device authority. This is not verified date of birth or permission inferred from
 * JWT metadata. Each short transaction closes before the provider call; service checks again
 * after the call. No account or consent is written, and no always-eligible fallback exists. */
internal class PostgresAdultMealIntentAudience(
    private val environment: String,
    private val transactions: PgTransactions,
    private val accounts: AccountProfileStore,
    private val databaseDispatcher: CoroutineDispatcher,
) : AccountMealIntentAudience {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    override suspend fun check(subject: VerifiedSupabaseSubject, deviceId: UUID): AccountMealIntentAdmission = try {
        runInterruptible(databaseDispatcher) {
            transactions.run { c ->
                val owner = accounts.lockAdultMealIntentAccount(c, subject, deviceId)
                if (owner.environment != environment) return@run AccountMealIntentAdmission.UNAUTHENTICATED
                val now = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
                    check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant()
                } }
                if (subject.expiresAtEpochSeconds <= now.epochSecond) AccountMealIntentAdmission.UNAUTHENTICATED
                else AccountMealIntentAdmission.ALLOWED
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
      catch (failure: AccountFailure) { when (failure.code) {
          AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> AccountMealIntentAdmission.UNAUTHENTICATED
          AccountFailureCode.POLICY_BLOCKED -> AccountMealIntentAdmission.FORBIDDEN
          AccountFailureCode.NOT_CONFIGURED -> AccountMealIntentAdmission.NOT_CONFIGURED
          else -> AccountMealIntentAdmission.UNAVAILABLE
      } }
      catch (_: Exception) { AccountMealIntentAdmission.UNAVAILABLE }

    override fun toString() = "PostgresAdultMealIntentAudience(<redacted>)"
}
