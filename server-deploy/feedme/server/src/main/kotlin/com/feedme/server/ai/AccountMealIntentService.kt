package com.feedme.server.ai

import com.feedme.server.auth.VerifiedSupabaseSubject
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class AccountMealIntentFailure {
    NOT_CONFIGURED, FORBIDDEN, UNAUTHENTICATED, SOURCE_CHANGED,
    RATE_LIMITED, UNAVAILABLE, INVALID_INPUT, INVALID_RESPONSE,
}

internal enum class AccountMealIntentAdmission {
    ALLOWED, FORBIDDEN, UNAUTHENTICATED, NOT_CONFIGURED, RATE_LIMITED, UNAVAILABLE,
}

/** Explicit provider-data/audience eligibility, not an inferred permission from JWT claims.
 * Implementations must fail closed. There is deliberately no accepting default. */
internal fun interface AccountMealIntentAudience {
    suspend fun check(subject: VerifiedSupabaseSubject, deviceId: UUID): AccountMealIntentAdmission
}

/** Detached correlation for a bounded, actually authorized catalog read. These values are not
 * a grant. The adapter must resolve accountId itself, not copy a client-supplied account ID.
 * This object is never returned in the service response or passed to the hosted model. */
internal class AccountMealIntentSource(
    internal val accountId: UUID,
    internal val catalogRevision: String,
    options: List<MealIntentIngredientOption>,
) {
    internal val ingredientOptions: List<MealIntentIngredientOption>
    init {
        require(catalogRevision.isNotBlank() && catalogRevision.length <= 128 &&
            catalogRevision.none(Char::isISOControl)) { "Invalid meal intent source" }
        require(options.size in 1..64 && options.map { it.id }.distinct().size == options.size &&
            options.all { validIntentText(it.name, 100, multiline = false) && it.name == it.name.trim() }) {
            "Invalid meal intent source"
        }
        ingredientOptions = Collections.unmodifiableList(options.map { MealIntentIngredientOption(it.id, it.name) })
    }
    override fun toString() = "AccountMealIntentSource(<redacted>)"
}

internal sealed interface AccountMealIntentCapture {
    class Value(val source: AccountMealIntentSource) : AccountMealIntentCapture {
        override fun toString() = "AccountMealIntentCapture.Value(<redacted>)"
    }
    class Denied(val reason: AccountMealIntentFailure) : AccountMealIntentCapture
}

internal enum class AccountMealIntentCurrent {
    CURRENT, UNAUTHENTICATED, SOURCE_CHANGED, NOT_CONFIGURED, UNAVAILABLE,
}

/** Required caller-owned account/catalog authority; no accepting default is installed.
 * Both methods must finish and close their own bounded transaction before returning, including
 * any SQL retry. No Connection or transaction callback crosses this boundary. capture verifies
 * the actual account/device/provider and returns only current authorized options. revalidate
 * must freshly verify those same facts, account identity, exact catalog revision/options and
 * final deadlines for the exact captured object. It must not silently replace changed options.
 * The caller must not invoke the service inside an ambient database transaction. */
internal interface AccountMealIntentSourceAuthority {
    suspend fun capture(subject: VerifiedSupabaseSubject, deviceId: UUID): AccountMealIntentCapture
    suspend fun revalidate(
        subject: VerifiedSupabaseSubject,
        deviceId: UUID,
        source: AccountMealIntentSource,
    ): AccountMealIntentCurrent
}

internal sealed interface AccountMealIntentResult {
    class Value(val proposal: MealIntentProposal) : AccountMealIntentResult {
        override fun toString() = "AccountMealIntentResult.Value(UNCONFIRMED, <redacted>)"
    }
    class Unavailable(val reason: AccountMealIntentFailure) : AccountMealIntentResult {
        override fun toString() = "AccountMealIntentResult.Unavailable(reason=$reason)"
    }
}

/** Orchestration, not an authentication adapter. The service accepts
 * no client catalog, never creates a Plan, and persists/queues nothing. Capture completes before
 * the single model call; eligibility and the original owner/source are checked again afterward.
 * A successful result is still an unconfirmed suggestion, not availability or food-safety proof.
 * An enabled host must supply genuine authority plus bounded concurrency/rate admission
 * compatible with its provider/free-tier budget. This class invents neither a quota nor a paid
 * fallback, and performs no automatic retries. */
internal class AccountMealIntentService(
    private val enabled: Boolean,
    private val audience: AccountMealIntentAudience,
    private val sources: AccountMealIntentSourceAuthority,
    private val interpreter: MealIntentInterpreter,
) {
    suspend fun interpret(subject: VerifiedSupabaseSubject, deviceId: UUID, text: String): AccountMealIntentResult {
        currentCoroutineContext().ensureActive()
        if (!enabled) return denied(AccountMealIntentFailure.NOT_CONFIGURED)
        return try {
            val admission = audience.check(subject, deviceId)
            currentCoroutineContext().ensureActive()
            if (admission != AccountMealIntentAdmission.ALLOWED) return denied(admission.failure())
            if (!validIntentText(text, 1000, multiline = true)) return denied(AccountMealIntentFailure.INVALID_INPUT)
            val captured = sources.capture(subject, deviceId)
            currentCoroutineContext().ensureActive()
            val source = when (captured) {
                is AccountMealIntentCapture.Value -> captured.source
                is AccountMealIntentCapture.Denied -> return denied(captured.reason)
            }
            val result = interpreter.interpret(text, source.ingredientOptions)
            currentCoroutineContext().ensureActive()
            val finalAdmission = audience.check(subject, deviceId)
            currentCoroutineContext().ensureActive()
            if (finalAdmission != AccountMealIntentAdmission.ALLOWED) return denied(finalAdmission.failure())
            val current = sources.revalidate(subject, deviceId, source)
            currentCoroutineContext().ensureActive()
            if (current != AccountMealIntentCurrent.CURRENT) return denied(current.failure())
            when (result) {
                is MealIntentResult.Value -> AccountMealIntentResult.Value(result.proposal)
                is MealIntentResult.Unavailable -> denied(result.reason.failure())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
          catch (_: Exception) { denied(AccountMealIntentFailure.UNAVAILABLE) }
    }

    override fun toString() = "AccountMealIntentService(<redacted>)"
    private fun denied(reason: AccountMealIntentFailure) = AccountMealIntentResult.Unavailable(reason)
}

private fun validIntentText(value: String, maximum: Int, multiline: Boolean): Boolean =
    value.isNotBlank() && value.codePointCount(0, value.length) <= maximum &&
        StandardCharsets.UTF_8.newEncoder().canEncode(value) &&
        value.none { it.isISOControl() && !(multiline && it in "\n\r\t") }

private fun AccountMealIntentAdmission.failure(): AccountMealIntentFailure = when (this) {
    AccountMealIntentAdmission.ALLOWED -> error("Admission is not a failure")
    AccountMealIntentAdmission.FORBIDDEN -> AccountMealIntentFailure.FORBIDDEN
    AccountMealIntentAdmission.UNAUTHENTICATED -> AccountMealIntentFailure.UNAUTHENTICATED
    AccountMealIntentAdmission.NOT_CONFIGURED -> AccountMealIntentFailure.NOT_CONFIGURED
    AccountMealIntentAdmission.RATE_LIMITED -> AccountMealIntentFailure.RATE_LIMITED
    AccountMealIntentAdmission.UNAVAILABLE -> AccountMealIntentFailure.UNAVAILABLE
}

private fun AccountMealIntentCurrent.failure(): AccountMealIntentFailure = when (this) {
    AccountMealIntentCurrent.CURRENT -> error("Current source is not a failure")
    AccountMealIntentCurrent.UNAUTHENTICATED -> AccountMealIntentFailure.UNAUTHENTICATED
    AccountMealIntentCurrent.SOURCE_CHANGED -> AccountMealIntentFailure.SOURCE_CHANGED
    AccountMealIntentCurrent.NOT_CONFIGURED -> AccountMealIntentFailure.NOT_CONFIGURED
    AccountMealIntentCurrent.UNAVAILABLE -> AccountMealIntentFailure.UNAVAILABLE
}

private fun HostedJsonFailure.failure(): AccountMealIntentFailure = when (this) {
    HostedJsonFailure.NOT_CONFIGURED -> AccountMealIntentFailure.NOT_CONFIGURED
    HostedJsonFailure.RATE_LIMITED -> AccountMealIntentFailure.RATE_LIMITED
    HostedJsonFailure.UNAVAILABLE -> AccountMealIntentFailure.UNAVAILABLE
    HostedJsonFailure.INVALID_INPUT -> AccountMealIntentFailure.INVALID_INPUT
    HostedJsonFailure.INVALID_RESPONSE -> AccountMealIntentFailure.INVALID_RESPONSE
}
