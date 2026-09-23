package com.feedme.app.onboarding

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PreferencesAction {
    KEEP, LOAD, SEARCH, MORE, SAVE, RECOVER, REVIEW_ORIGINAL, CONFIRM_ORIGINAL, DISCARD_UNSENT, START_NEW,
}

internal class PreferencesActionUi(val busy: Boolean = false, val failure: FailureReason? = null,
    val review: OnboardingPreferencesReview? = null, val keptAt: OnboardingPreferencesState? = null) {
    override fun toString() = "PreferencesActionUi(busy=$busy, details=<redacted>)"
}

internal class PreferencesActionCall(val action: PreferencesAction, val expected: OnboardingPreferencesState,
    val fields: OnboardingPreferencesFields?, val query: String?, val review: OnboardingPreferencesReview?,
    val ticket: ProfileActionGate.Ticket) {
    override fun toString() = "PreferencesActionCall(action=$action, details=<redacted>)"
}

/** Borrowed presentation adapter only. Constructing it performs no work. The native parent
 * still owns the real controller, journals and navigation. Synchronous claim fences duplicate
 * taps before coroutine launch; every await is followed by exact-controller/state admission.
 * Skip is a separate host handoff, never a preference patch or an inferred empty answer.
 */
internal class OnboardingPreferencesActions(
    private val controller: OnboardingPreferencesController,
    private val prompt: OnboardingOptionalPrompt,
) {
    private val gate = ProfileActionGate()
    private val mutable = MutableStateFlow(PreferencesActionUi())
    val states = mutable.asStateFlow()

    fun claim(action: PreferencesAction, rendered: OnboardingPreferencesState,
        fields: OnboardingPreferencesFields? = null, query: String? = null): PreferencesActionCall? {
        if (!controller.isCurrentState(rendered)) return null
        val captured = fields?.detachedPreferences()
        if (action in setOf(PreferencesAction.KEEP, PreferencesAction.SAVE)) {
            val validation = captured?.let { preferencesValidation(prompt, it) } ?: return null
            if (action == PreferencesAction.KEEP && !validation.canKeep || action == PreferencesAction.SAVE && !validation.canSave) return null
        }
        if (action == PreferencesAction.SEARCH && query == null) return null
        val review = mutable.value.review
        if (action == PreferencesAction.CONFIRM_ORIGINAL && (review == null || !controller.isCurrentReview(review))) return null
        val ticket = gate.claim(rendered, controller.states.value) ?: return null
        mutable.value = PreferencesActionUi(true, review = review, keptAt = mutable.value.keptAt)
        return PreferencesActionCall(action, rendered, captured, query, review, ticket)
    }

    suspend fun execute(call: PreferencesActionCall): OnboardingPreferencesState? {
        if (!gate.begin(call.ticket)) return null
        try {
            current(call, call.expected)
            val result = when (call.action) {
                PreferencesAction.KEEP -> value(controller.keep(checkNotNull(call.fields), call.expected))
                PreferencesAction.LOAD -> value(controller.refresh(call.expected))
                PreferencesAction.SEARCH -> value(controller.searchIngredients(checkNotNull(call.query), call.expected))
                PreferencesAction.MORE -> value(controller.nextIngredientPage(call.expected))
                PreferencesAction.RECOVER -> value(controller.recover(call.expected))
                PreferencesAction.DISCARD_UNSENT -> value(controller.discardPrepared(call.expected))
                PreferencesAction.START_NEW -> value(controller.startNew(call.expected))
                PreferencesAction.REVIEW_ORIGINAL -> {
                    val reviewed = value(controller.reviewRetry(call.expected))
                    if (!gate.running(call.ticket) || !controller.isCurrentReview(reviewed)) refused()
                    val selected = controller.states.value
                    current(call, selected)
                    mutable.value = PreferencesActionUi(true, review = reviewed)
                    return selected
                }
                PreferencesAction.CONFIRM_ORIGINAL -> {
                    val reviewed = checkNotNull(call.review)
                    if (mutable.value.review !== reviewed || !controller.isCurrentReview(reviewed)) refused()
                    value(controller.confirm(reviewed))
                }
                PreferencesAction.SAVE -> {
                    // The full exact kept draft is retained. Only the fixed displayed prompt
                    // is reviewed/sent; no hidden default or automatic GET is inserted here.
                    val kept = value(controller.keep(checkNotNull(call.fields), call.expected))
                    current(call, kept)
                    val reviewed = value(controller.reviewSaveForPrompt(prompt, kept))
                    if (!gate.running(call.ticket) || !controller.isCurrentReview(reviewed)) refused()
                    value(controller.confirm(reviewed))
                }
            }
            current(call, result)
            mutable.value = PreferencesActionUi(true, keptAt = if (call.action == PreferencesAction.KEEP) result else null)
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PreferencesUiFailure) {
            if (gate.running(call.ticket)) mutable.value = PreferencesActionUi(true, failure.reason)
            return null
        } catch (_: Exception) {
            if (gate.running(call.ticket)) mutable.value = PreferencesActionUi(true, FailureReason.UNAVAILABLE)
            return null
        } finally {
            if (gate.running(call.ticket)) {
                val selected = mutable.value
                mutable.value = PreferencesActionUi(false, selected.failure, selected.review, selected.keptAt)
                gate.finish(call.ticket)
            }
        }
    }

    fun canDeliver(call: PreferencesActionCall, result: OnboardingPreferencesState) =
        gate.latest(call.ticket) && controller.isCurrentState(result)

    fun dismissReview() {
        if (!mutable.value.busy) mutable.value = PreferencesActionUi(keptAt = mutable.value.keptAt)
    }
    fun retire() { gate.retire(); mutable.value = PreferencesActionUi() }
    private fun current(call: PreferencesActionCall, state: OnboardingPreferencesState) {
        if (!gate.running(call.ticket) || !controller.isCurrentState(state)) refused()
    }
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> throw PreferencesUiFailure(result.reason)
    }
    private fun refused(): Nothing = throw PreferencesUiFailure(FailureReason.STALE_SESSION)
    private class PreferencesUiFailure(val reason: FailureReason) : Exception("Preferences action unavailable")
}
