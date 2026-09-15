package com.feedme.mealflow.timers

import com.feedme.kitchen.CookingAvailability
import com.feedme.kitchen.CookingStatus
import com.feedme.core.ports.PortResult
import com.feedme.session.NativeWorkTicket

enum class CookingTimerDeliveryPhase { UNAVAILABLE, NOT_INSTALLED, ENQUEUED, SUSPENDED, CHECKING,
    DELIVERED, BLOCKED, CLOCK_UNCERTAIN, OUTCOME_UNKNOWN, CANCELLING }

/** Optional read-only observation of the actual scheduler paired with the facade. This cannot
 * authorize an effect, produce an installation receipt, or accept a replacement scheduler.
 */
fun interface CookingTimerDeliveryObservations {
    fun observeDelivery(ticket: NativeWorkTicket): PortResult<CookingTimerDeliveryPhase>
}

/** Detached observation of one exact local cooking revision, not permission to mutate/deliver. */
class CookingTimerSnapshot internal constructor(
    val sessionId: String,
    val localRevision: Long,
    val planId: String,
    val recipeVersionId: String?,
    val currentStepId: String,
    val availability: CookingAvailability,
    val status: CookingStatus,
    val originMatches: Boolean,
    val pendingCommandCount: Int,
    steps: List<CookingTimerStep>,
    timers: List<CookingTimerView>,
) {
    private val stepValues = steps.toList()
    private val timerValues = timers.toList()
    val steps: List<CookingTimerStep> get() = stepValues.toList()
    val timers: List<CookingTimerView> get() = timerValues.toList()
    override fun toString() = "CookingTimerSnapshot(<redacted>)"
}

/** Labels come only from the pinned recipe. A suggested duration is never a start instruction. */
class CookingTimerStep internal constructor(val stepId: String, val instruction: String,
    val suggestedDurationSeconds: Long?) {
    override fun toString() = "CookingTimerStep(<redacted>)"
}

/** Exact display identity supplied inside the real runtime's gated synchronous local effect.
 * It contains no native ticket and is not itself authority to repeat that effect.
 */
class CookingTimerDue internal constructor(val sessionId: String, val timerId: String, val generation: Long) {
    override fun toString() = "CookingTimerDue(<redacted>)"
}
