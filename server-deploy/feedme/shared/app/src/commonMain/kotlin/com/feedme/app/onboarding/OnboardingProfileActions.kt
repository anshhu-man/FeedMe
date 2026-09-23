package com.feedme.app.onboarding

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.session.OnboardingProfileController
import com.feedme.session.OnboardingProfileReview
import com.feedme.session.OnboardingProfileState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ProfileActionUi(val busy: Boolean = false, val failure: FailureReason? = null,
    val review: OnboardingProfileReview? = null, val keptAt: OnboardingProfileState? = null) {
    override fun toString() = "ProfileActionUi(busy=$busy, details=<redacted>)"
}

internal class ProfileActionCall(val action: ProfileAction, val expected: OnboardingProfileState,
    val input: ProfileFormInput?, val review: OnboardingProfileReview?, val ticket: ProfileActionGate.Ticket) {
    override fun toString() = "ProfileActionCall(action=$action, details=<redacted>)"
}

/** A borrowed screen adapter, not another runtime, transport or durable command owner.
 * Construct/mount does nothing. Every dispatch uses the real typed controller and exact state.
 * The UI thread calls claim synchronously before launching; retire affects only this screen's
 * tickets. The parent still owns the controller's lifetime (Back explicitly closes that child).
 */
internal class OnboardingProfileActions(private val controller: OnboardingProfileController) {
    private val gate = ProfileActionGate()
    private val mutable = MutableStateFlow(ProfileActionUi())
    val states = mutable.asStateFlow()

    fun claim(action: ProfileAction, rendered: OnboardingProfileState, input: ProfileFormInput? = null): ProfileActionCall? {
        if (!controller.isCurrentState(rendered)) return null
        if (action in setOf(ProfileAction.KEEP, ProfileAction.SAVE, ProfileAction.CONTINUE)) {
            if (input == null || !profileFieldErrors(input, action != ProfileAction.KEEP).valid) return null
        }
        val review = mutable.value.review
        if (action == ProfileAction.CONFIRM_ORIGINAL && (review == null || !controller.isCurrentReview(review))) return null
        if (action == ProfileAction.OPEN_PREFERENCES) return null // Explicit host navigation, never a journal command.
        val ticket = gate.claim(rendered, controller.states.value) ?: return null
        mutable.value = ProfileActionUi(busy = true, review = review, keptAt = mutable.value.keptAt)
        return ProfileActionCall(action, rendered, input, review, ticket)
    }

    suspend fun execute(call: ProfileActionCall): OnboardingProfileState? {
        // Only this invocation may own cleanup; a refused duplicate has no suspension and
        // cannot cancel/finish the first call while its actual controller operation is held.
        if (!gate.begin(call.ticket)) return null
        try {
            current(call, call.expected)
            val state = when (call.action) {
                ProfileAction.KEEP -> value(controller.keep(checkNotNull(call.input).fields(), call.expected))
                ProfileAction.LOAD -> value(controller.refresh(call.expected))
                ProfileAction.RECOVER -> value(controller.recover(call.expected))
                ProfileAction.DISCARD_UNSENT -> value(controller.discardPrepared(call.expected))
                ProfileAction.START_NEW -> value(controller.startNew(call.expected))
                ProfileAction.REVIEW_ORIGINAL -> {
                    val review = value(controller.reviewRetry(call.expected))
                    if (!gate.running(call.ticket) || !controller.isCurrentReview(review)) refused()
                    val selected = controller.states.value
                    current(call, selected)
                    mutable.value = ProfileActionUi(busy = true, review = review)
                    return selected
                }
                ProfileAction.CONFIRM_ORIGINAL -> {
                    val review = checkNotNull(call.review)
                    if (mutable.value.review !== review || !controller.isCurrentReview(review)) refused()
                    value(controller.confirm(review))
                }
                ProfileAction.SAVE, ProfileAction.CONTINUE -> {
                    // The exact visible form is the explicit intent. No automatic refresh or
                    // substituted baseline occurs between that click and the reviewed request.
                    val kept = value(controller.keep(checkNotNull(call.input).fields(), call.expected))
                    current(call, kept)
                    val review = value(if (call.action == ProfileAction.CONTINUE) controller.reviewAdvance(kept)
                        else controller.reviewSave(kept))
                    if (!gate.running(call.ticket) || !controller.isCurrentReview(review)) refused()
                    value(controller.confirm(review))
                }
                ProfileAction.OPEN_PREFERENCES -> refused()
            }
            current(call, state)
            mutable.value = ProfileActionUi(busy = true, keptAt = if (call.action == ProfileAction.KEEP) state else null)
            return state
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failed: ProfileUiFailure) {
            if (gate.running(call.ticket)) mutable.value = ProfileActionUi(busy = true, failure = failed.reason)
            return null
        } catch (_: Exception) {
            if (gate.running(call.ticket)) mutable.value = ProfileActionUi(busy = true, failure = FailureReason.UNAVAILABLE)
            return null
        } finally {
            if (gate.running(call.ticket)) {
                val selected = mutable.value
                mutable.value = ProfileActionUi(false, selected.failure, selected.review, selected.keptAt)
                gate.finish(call.ticket)
            }
        }
    }

    fun canDeliver(call: ProfileActionCall, state: OnboardingProfileState) =
        gate.latest(call.ticket) && controller.isCurrentState(state)

    /** Dismiss this screen's retry ticket only. No save/retry/restore is implied by dismissal.
     * A captured old Confirm callback also fails the adapter's retained-ticket identity check. */
    fun dismissReview() {
        if (mutable.value.busy) return
        mutable.value = ProfileActionUi(keptAt = mutable.value.keptAt)
    }

    fun retire() { gate.retire(); mutable.value = ProfileActionUi() }
    private fun current(call: ProfileActionCall, state: OnboardingProfileState) {
        if (!gate.running(call.ticket) || !controller.isCurrentState(state)) refused()
    }
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> throw ProfileUiFailure(result.reason)
    }
    private fun refused(): Nothing = throw ProfileUiFailure(FailureReason.STALE_SESSION)
    private class ProfileUiFailure(val reason: FailureReason) : Exception("Profile action unavailable")
}
