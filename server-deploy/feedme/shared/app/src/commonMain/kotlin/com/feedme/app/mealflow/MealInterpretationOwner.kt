package com.feedme.app.mealflow

import com.feedme.core.ports.*
import com.feedme.mealflow.MealInterpretationProposal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** RAM-only input and one unconfirmed response. Never a saved draft or a meal command. */
class MealInterpretationState internal constructor(
    val text: String?, val busy: Boolean = false,
    val proposal: MealInterpretationProposal? = null, val failure: FailureReason? = null,
) {
    override fun toString() = "MealInterpretationState(busy=$busy, private=<redacted>)"
}

/** An exact local draft/route observation; the predicate is checked again after suspension. */
internal class MealInterpretationTarget(val form: MealFormState, private val current: () -> Boolean) {
    fun isCurrent() = current()
    override fun toString() = "MealInterpretationTarget(<redacted>)"
}

/** Serialized on the same identity/UI dispatcher as the borrowed session. An edit/dismiss
 * supersedes a pending result without starting another model request or owning that session.
 * One in-flight call stays bounded by the transport; no retry, persistence or plan submission. */
internal class MealInterpretationOwner(
    private val boundary: SessionBoundary,
    private val lease: SessionLease,
    private val dispatcher: CoroutineDispatcher,
    private val interpretText: (suspend (String) -> PortResult<MealInterpretationProposal>)?,
) {
    val enabled: Boolean get() = interpretText != null && current()
    private val mutable = MutableStateFlow(MealInterpretationState(""))
    val states = mutable.asStateFlow()
    private var target: MealInterpretationTarget? = null
    private var inFlight: Any? = null
    private var closed = false
    private val subscription = boundary.onInvalidated(lease) { redact() }

    private fun current() = !closed && boundary.isCurrent(lease)
    fun reviewCurrent(expected: MealInterpretationState): Boolean = current() &&
        mutable.value === expected && (expected.proposal == null || target?.isCurrent() == true)

    fun canConfirm(expected: MealInterpretationState): Boolean = reviewCurrent(expected) && !expected.busy &&
        expected.proposal?.let { proposal -> target?.form?.values?.let { mealInterpretationEdit(it, proposal) } } != null

    fun edit(expected: MealInterpretationState, text: String) {
        if (!current() || mutable.value !== expected || !enabled) return
        // Four UTF-16 units per scalar is an intentionally loose pre-allocation bound;
        // the canonical client performs the exact Unicode/scalar and content checks.
        if (text.length > 4000) {
            target = null
            mutable.value = MealInterpretationState(expected.text, inFlight != null, failure = FailureReason.INVALID_DATA)
            return
        }
        target = null
        mutable.value = MealInterpretationState(text, inFlight != null)
    }

    suspend fun request(expected: MealInterpretationState, captured: MealInterpretationTarget): PortResult<MealInterpretationState> =
        withContext(dispatcher) {
            ensureActive()
            if (!current()) return@withContext failed(FailureReason.STALE_SESSION)
            val sender = interpretText ?: return@withContext failed(FailureReason.NOT_CONFIGURED)
            if (mutable.value !== expected || expected.busy || expected.proposal != null || inFlight != null ||
                !captured.isCurrent()) return@withContext failed(FailureReason.CONFLICT)
            val text = expected.text?.takeIf { it.isNotBlank() } ?: return@withContext failed(FailureReason.INVALID_DATA)
            val token = Any(); inFlight = token; target = null
            val pending = MealInterpretationState(text, busy = true); mutable.value = pending
            try {
                val result = sender(text)
                ensureActive()
                if (!current()) return@withContext failed(FailureReason.STALE_SESSION)
                if (inFlight !== token || mutable.value !== pending || !captured.isCurrent()) {
                    if (mutable.value === pending) mutable.value = MealInterpretationState(text, failure = FailureReason.CONFLICT)
                    return@withContext failed(FailureReason.CONFLICT)
                }
                when (result) {
                    is PortResult.Failure -> {
                        mutable.value = MealInterpretationState(text, failure = result.reason)
                        result
                    }
                    is PortResult.Value -> {
                        // Client decoding binds the exact original text and detached source.
                        if (result.value.originalText != text) {
                            mutable.value = MealInterpretationState(text, failure = FailureReason.INVALID_DATA)
                            failed(FailureReason.INVALID_DATA)
                        } else {
                            target = captured
                            mutable.value = MealInterpretationState(text, proposal = result.value)
                            PortResult.Value(mutable.value)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                ensureActive()
                if (current() && mutable.value === pending)
                    mutable.value = MealInterpretationState(text, failure = FailureReason.UNAVAILABLE)
                failed(if (current()) FailureReason.UNAVAILABLE else FailureReason.STALE_SESSION)
            } finally {
                if (inFlight === token) {
                    inFlight = null
                    if (current() && mutable.value.busy) {
                        val state = mutable.value
                        mutable.value = MealInterpretationState(state.text, proposal = state.proposal, failure = state.failure)
                    }
                }
            }
        }

    /** Consumes this exact review before a synchronous local form edit. No save/submit. */
    fun confirm(expected: MealInterpretationState, apply: (MealFormValues) -> Boolean): PortResult<Unit> {
        if (!current()) return failed(FailureReason.STALE_SESSION)
        if (!canConfirm(expected)) return failed(FailureReason.CONFLICT)
        val next = mealInterpretationEdit(checkNotNull(target?.form?.values), checkNotNull(expected.proposal))
            ?: return failed(FailureReason.INVALID_DATA)
        target = null
        // The interpreted details now belong to the editable manual draft, not a reusable
        // AI command. Empty text makes the next explicit Find use ordinary structured input.
        val consumed = MealInterpretationState(""); mutable.value = consumed
        if (!apply(next)) {
            if (current() && mutable.value === consumed)
                mutable.value = MealInterpretationState(expected.text, failure = FailureReason.CONFLICT)
            return failed(if (current()) FailureReason.CONFLICT else FailureReason.STALE_SESSION)
        }
        return if (current()) PortResult.Value(Unit) else failed(FailureReason.STALE_SESSION)
    }

    fun dismiss(expected: MealInterpretationState) {
        if (!current() || mutable.value !== expected) return
        target = null
        mutable.value = MealInterpretationState(expected.text, inFlight != null)
    }

    private fun redact() { target = null; mutable.value = MealInterpretationState(null, failure = FailureReason.STALE_SESSION) }
    fun close() { closed = true; redact(); subscription.close() }
    override fun toString() = "MealInterpretationOwner(<redacted>)"
    private fun failed(reason: FailureReason) = PortResult.Failure(reason)
}

/** Null means unmentioned, never erase a manually chosen constraint. Unresolved ingredients
 * require a corrected input, not a partial silent acceptance. IDs still are not pantry stock,
 * allergen authority, recipe review or permission to cook. Existing exclusions are untouched. */
internal fun mealInterpretationEdit(before: MealFormValues, proposal: MealInterpretationProposal): MealFormValues? {
    if (!proposal.requiresUserConfirmation || proposal.unresolvedIngredients.isNotEmpty()) return null
    val next = before.copy(ingredientIds = proposal.suggestedIngredientIds,
        energy = proposal.energy ?: before.energy,
        totalMinutes = proposal.maxTotalMinutes?.toString() ?: before.totalMinutes,
        activeMinutes = proposal.maxActiveMinutes?.toString() ?: before.activeMinutes)
    val total = next.totalMinutes.takeIf { it.isNotEmpty() }?.toIntOrNull()
    val active = next.activeMinutes.takeIf { it.isNotEmpty() }?.toIntOrNull()
    if ((next.totalMinutes.isNotEmpty() && (total == null || total <= 0)) ||
        (next.activeMinutes.isNotEmpty() && (active == null || active <= 0)) ||
        (total != null && active != null && active > total)) return null
    return next
}
