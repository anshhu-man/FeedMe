package com.feedme.app.onboarding

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class OptionalDecisionAction { CHECKPOINT, RECOVER, REVIEW_ORIGINAL, CONFIRM_ORIGINAL }

internal fun decisionIntentMatches(prompt: OnboardingOptionalPrompt, choice: OnboardingDecisionChoice,
    actualPrompt: OnboardingOptionalPrompt?, actualChoice: OnboardingDecisionChoice?) =
    prompt == actualPrompt && choice == actualChoice

internal fun decisionPrimary(status: OnboardingProfileStatus, recovery: Boolean, acknowledged: Boolean,
    originalIntent: OnboardingProfileIntent?, freshDecision: Boolean = false): OptionalDecisionAction? = when {
    status == OnboardingProfileStatus.UNAVAILABLE -> null
    recovery || status == OnboardingProfileStatus.RETAINED_RESPONSE -> OptionalDecisionAction.RECOVER
    status in setOf(OnboardingProfileStatus.PREPARED, OnboardingProfileStatus.UNCERTAIN) -> OptionalDecisionAction.REVIEW_ORIGINAL
    originalIntent == OnboardingProfileIntent.OPTIONAL_DECISION &&
        status in setOf(OnboardingProfileStatus.APPLIED, OnboardingProfileStatus.REJECTED, OnboardingProfileStatus.DISCARDED) ->
        if (freshDecision) OptionalDecisionAction.CHECKPOINT else if (!acknowledged) OptionalDecisionAction.RECOVER else null
    else -> if (freshDecision) OptionalDecisionAction.CHECKPOINT else null
}

/** A route check, never proof of eligibility or a replacement for the runtime's admission. */
internal fun decisionStepMatches(prompt: OnboardingOptionalPrompt, step: String?) = step == when (prompt) {
    OnboardingOptionalPrompt.FOOD_PREFERENCES -> "preferences"
    OnboardingOptionalPrompt.EQUIPMENT -> "equipment"
}

internal fun decisionPurpose(prompt: OnboardingOptionalPrompt, choice: OnboardingDecisionChoice): String = when (prompt) {
    OnboardingOptionalPrompt.FOOD_PREFERENCES -> if (choice == OnboardingDecisionChoice.ANSWERED)
        "Continue using the food choices you just saved." else "Skip food preferences for now. Your saved choices and draft stay unchanged."
    OnboardingOptionalPrompt.EQUIPMENT -> if (choice == OnboardingDecisionChoice.ANSWERED)
        "Finish this step using the kitchen setup you just saved." else "Finish without setting defaults. Your saved choices and draft stay unchanged."
}

internal class OptionalDecisionUi(val busy: Boolean = false, val failure: FailureReason? = null,
    val review: OnboardingProfileReview? = null) {
    override fun toString() = "OptionalDecisionUi(busy=$busy, details=<redacted>)"
}
internal class OptionalDecisionCall(val action: OptionalDecisionAction, val expected: OnboardingProfileState,
    val review: OnboardingProfileReview?, val ticket: ProfileActionGate.Ticket)

/** An explicitly clicked continuation may recover the prior profile result, fetch its current
 * baseline and prepare a separate checkpoint. It never retries an unresolved original implicitly.
 * Display intent is compared to the runtime's opaque review; it supplies no answer reference.
 */
internal class OptionalDecisionActions(private val controller: OnboardingProfileController,
    private val prompt: OnboardingOptionalPrompt, private val choice: OnboardingDecisionChoice,
    private val freshDecision: Boolean = false) {
    private val gate = ProfileActionGate()
    private val mutable = MutableStateFlow(OptionalDecisionUi())
    val states = mutable.asStateFlow()

    fun claim(action: OptionalDecisionAction, expected: OnboardingProfileState): OptionalDecisionCall? {
        if (!controller.isCurrentState(expected)) return null
        val review = mutable.value.review
        if (action == OptionalDecisionAction.CONFIRM_ORIGINAL &&
            (review == null || !controller.isCurrentReview(review) || !matches(review))) return null
        if (action == OptionalDecisionAction.CHECKPOINT && decisionPrimary(expected.status, expected.recoveryRequired,
                expected.completionAcknowledged, expected.originalIntent, freshDecision) != OptionalDecisionAction.CHECKPOINT) return null
        val ticket = gate.claim(expected, controller.states.value) ?: return null
        mutable.value = OptionalDecisionUi(true, review = review)
        return OptionalDecisionCall(action, expected, review, ticket)
    }

    suspend fun execute(call: OptionalDecisionCall): OnboardingProfileState? {
        if (!gate.begin(call.ticket)) return null
        try {
            current(call, call.expected)
            val result = when (call.action) {
                OptionalDecisionAction.RECOVER -> value(controller.recover(call.expected))
                OptionalDecisionAction.REVIEW_ORIGINAL -> {
                    // Never present an old profile text save as an optional setup decision.
                    val decision = call.expected.originalDecision ?: refused(FailureReason.CONFLICT)
                    if (!decisionIntentMatches(prompt, choice, decision.prompt, decision.choice)) refused(FailureReason.CONFLICT)
                    val review = value(controller.reviewRetry(call.expected))
                    if (!matches(review) || !controller.isCurrentReview(review)) refused(FailureReason.CONFLICT)
                    val selected = controller.states.value
                    current(call, selected)
                    mutable.value = OptionalDecisionUi(true, review = review)
                    return selected
                }
                OptionalDecisionAction.CONFIRM_ORIGINAL -> {
                    val review = checkNotNull(call.review)
                    if (mutable.value.review !== review || !matches(review) || !controller.isCurrentReview(review)) refused()
                    value(controller.confirm(review))
                }
                OptionalDecisionAction.CHECKPOINT -> {
                    var selected = call.expected
                    // This is the old profile result, not an optional original to retry.
                    if (selected.status in terminal && !selected.completionAcknowledged) {
                        selected = value(controller.recover(selected)); current(call, selected)
                    }
                    selected = value(controller.refresh(selected)); current(call, selected)
                    if (!selected.baselineFresh || !decisionStepMatches(prompt, selected.onboardingStep)) refused(FailureReason.CONFLICT)
                    if (selected.status in terminal) {
                        selected = value(controller.startNew(selected)); current(call, selected)
                    }
                    val review = value(controller.reviewOptionalDecision(selected))
                    if (!gate.running(call.ticket) || !matches(review) || !controller.isCurrentReview(review)) refused()
                    value(controller.confirm(review))
                }
            }
            current(call, result)
            mutable.value = OptionalDecisionUi(true)
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DecisionUiFailure) {
            if (gate.running(call.ticket)) mutable.value = OptionalDecisionUi(true, failure.reason)
            return null
        } catch (_: Exception) {
            if (gate.running(call.ticket)) mutable.value = OptionalDecisionUi(true, FailureReason.UNAVAILABLE)
            return null
        } finally {
            if (gate.running(call.ticket)) {
                val selected = mutable.value
                mutable.value = OptionalDecisionUi(false, selected.failure, selected.review)
                gate.finish(call.ticket)
            }
        }
    }

    fun canDeliver(call: OptionalDecisionCall, result: OnboardingProfileState) =
        gate.latest(call.ticket) && controller.isCurrentState(result)
    fun dismissReview() { if (!mutable.value.busy) mutable.value = OptionalDecisionUi() }
    fun retire() { gate.retire(); mutable.value = OptionalDecisionUi() }
    private fun matches(review: OnboardingProfileReview) = review.decision?.let {
        decisionIntentMatches(prompt, choice, it.prompt, it.choice)
    } == true
    private fun current(call: OptionalDecisionCall, state: OnboardingProfileState) {
        if (!gate.running(call.ticket) || !controller.isCurrentState(state)) refused()
    }
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> refused(result.reason)
    }
    private fun refused(reason: FailureReason = FailureReason.STALE_SESSION): Nothing = throw DecisionUiFailure(reason)
    private class DecisionUiFailure(val reason: FailureReason) : Exception("Setup continuation unavailable")
    private companion object {
        val terminal = setOf(OnboardingProfileStatus.APPLIED, OnboardingProfileStatus.REJECTED, OnboardingProfileStatus.DISCARDED)
    }
}
