package com.feedme.app.mealflow

import com.feedme.core.ports.*
import com.feedme.mealflow.MealInterpretationProposal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** RAM-only words and one unconfirmed AI proposal for the exact Make Mine form. */
class AdaptationInterpretationState internal constructor(
    val text: String?, val busy: Boolean = false,
    val proposal: MealInterpretationProposal? = null, val failure: FailureReason? = null,
) {
    override fun toString() = "AdaptationInterpretationState(busy=$busy, private=<redacted>)"
}

/** Exact form/route observation. A response cannot move to a newer Make Mine visit. */
internal class AdaptationInterpretationTarget(
    val form: AdaptationFormState,
    private val current: () -> Boolean,
) {
    fun isCurrent() = current()
    override fun toString() = "AdaptationInterpretationTarget(<redacted>)"
}

/** Separate from the ordinary request interpreter: confirmation edits only the proposed
 * adaptation form. It never edits the main draft, sends AdaptRequest, saves, or retries. */
internal class AdaptationInterpretationOwner(
    private val boundary: SessionBoundary,
    private val lease: SessionLease,
    private val dispatcher: CoroutineDispatcher,
    private val interpretText: (suspend (String) -> PortResult<MealInterpretationProposal>)?,
) {
    val enabled: Boolean get() = interpretText != null && current()
    private val mutable = MutableStateFlow(AdaptationInterpretationState(""))
    val states = mutable.asStateFlow()
    private var target: AdaptationInterpretationTarget? = null
    private var inFlight: Any? = null
    private var closed = false
    private val subscription = boundary.onInvalidated(lease) { redact() }

    private fun current() = !closed && boundary.isCurrent(lease)
    fun reviewCurrent(expected: AdaptationInterpretationState): Boolean = current() &&
        mutable.value === expected && (expected.proposal == null || target?.isCurrent() == true)

    fun canConfirm(expected: AdaptationInterpretationState): Boolean = reviewCurrent(expected) && !expected.busy &&
        expected.proposal?.let { proposal -> target?.form?.values?.let { adaptationInterpretationEdit(it, proposal) } } != null

    fun edit(expected: AdaptationInterpretationState, text: String) {
        if (!current() || mutable.value !== expected || !enabled) return
        if (text.length > 4000) {
            target = null
            mutable.value = AdaptationInterpretationState(expected.text, inFlight != null,
                failure = FailureReason.INVALID_DATA)
            return
        }
        target = null
        mutable.value = AdaptationInterpretationState(text, inFlight != null)
    }

    suspend fun request(expected: AdaptationInterpretationState,
        captured: AdaptationInterpretationTarget): PortResult<AdaptationInterpretationState> = withContext(dispatcher) {
        ensureActive()
        if (!current()) return@withContext failed(FailureReason.STALE_SESSION)
        val sender = interpretText ?: return@withContext failed(FailureReason.NOT_CONFIGURED)
        if (mutable.value !== expected || expected.busy || expected.proposal != null || inFlight != null ||
            !captured.isCurrent()) return@withContext failed(FailureReason.CONFLICT)
        val text = expected.text?.takeIf { it.isNotBlank() } ?: return@withContext failed(FailureReason.INVALID_DATA)
        val token = Any(); inFlight = token; target = null
        val pending = AdaptationInterpretationState(text, busy = true); mutable.value = pending
        try {
            val result = sender(text)
            ensureActive()
            if (!current()) return@withContext failed(FailureReason.STALE_SESSION)
            if (inFlight !== token || mutable.value !== pending || !captured.isCurrent()) {
                if (mutable.value === pending)
                    mutable.value = AdaptationInterpretationState(text, failure = FailureReason.CONFLICT)
                return@withContext failed(FailureReason.CONFLICT)
            }
            when (result) {
                is PortResult.Failure -> {
                    mutable.value = AdaptationInterpretationState(text, failure = result.reason)
                    result
                }
                is PortResult.Value -> if (result.value.originalText != text) {
                    mutable.value = AdaptationInterpretationState(text, failure = FailureReason.INVALID_DATA)
                    failed(FailureReason.INVALID_DATA)
                } else {
                    target = captured
                    mutable.value = AdaptationInterpretationState(text, proposal = result.value)
                    PortResult.Value(mutable.value)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            ensureActive()
            if (current() && mutable.value === pending)
                mutable.value = AdaptationInterpretationState(text, failure = FailureReason.UNAVAILABLE)
            failed(if (current()) FailureReason.UNAVAILABLE else FailureReason.STALE_SESSION)
        } finally {
            if (inFlight === token) {
                inFlight = null
                if (current() && mutable.value.busy) {
                    val state = mutable.value
                    mutable.value = AdaptationInterpretationState(state.text, proposal = state.proposal,
                        failure = state.failure)
                }
            }
        }
    }

    /** Consumes the exact review before the synchronous local adaptation-form edit. */
    fun confirm(expected: AdaptationInterpretationState,
        apply: (AdaptationFormValues) -> Boolean): PortResult<Unit> {
        if (!current()) return failed(FailureReason.STALE_SESSION)
        if (!canConfirm(expected)) return failed(FailureReason.CONFLICT)
        val next = adaptationInterpretationEdit(checkNotNull(target?.form?.values),
            checkNotNull(expected.proposal)) ?: return failed(FailureReason.INVALID_DATA)
        target = null
        val consumed = AdaptationInterpretationState(""); mutable.value = consumed
        if (!apply(next)) {
            if (current() && mutable.value === consumed)
                mutable.value = AdaptationInterpretationState(expected.text, failure = FailureReason.CONFLICT)
            return failed(if (current()) FailureReason.CONFLICT else FailureReason.STALE_SESSION)
        }
        return if (current()) PortResult.Value(Unit) else failed(FailureReason.STALE_SESSION)
    }

    fun dismiss(expected: AdaptationInterpretationState) {
        if (!current() || mutable.value !== expected) return
        target = null
        mutable.value = AdaptationInterpretationState(expected.text, inFlight != null)
    }

    fun clear() {
        if (!current()) { redact(); return }
        target = null
        mutable.value = AdaptationInterpretationState("")
    }

    private fun redact() {
        target = null
        mutable.value = AdaptationInterpretationState(null, failure = FailureReason.STALE_SESSION)
    }
    fun close() { closed = true; redact(); subscription.close() }
    private fun failed(reason: FailureReason) = PortResult.Failure(reason)
    override fun toString() = "AdaptationInterpretationOwner(<redacted>)"
}

/** Null model fields preserve the explicit proposed values. A nonempty ingredient suggestion
 * replaces this version's availability only after review; an empty suggestion means no
 * ingredient change. All source, exclusion, equipment, serving and swap fields stay fixed. */
internal fun adaptationInterpretationEdit(before: AdaptationFormValues,
    proposal: MealInterpretationProposal): AdaptationFormValues? {
    if (!proposal.requiresUserConfirmation || proposal.unresolvedIngredients.isNotEmpty()) return null
    val meal = before.meal
    val selected = proposal.suggestedIngredientIds
    val nextMeal = meal.copy(
        ingredientIds = if (selected.isEmpty()) meal.ingredientIds else selected,
        energy = proposal.energy ?: meal.energy,
        totalMinutes = proposal.maxTotalMinutes?.toString() ?: meal.totalMinutes,
        activeMinutes = proposal.maxActiveMinutes?.toString() ?: meal.activeMinutes,
    )
    if (!adaptationRequiredTotalMinutesValid(nextMeal.totalMinutes) ||
        !blueprintEffortTimesValid(nextMeal.totalMinutes, nextMeal.activeMinutes)) return null
    return before.copy(meal = nextMeal)
}
