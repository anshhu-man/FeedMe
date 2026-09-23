package com.feedme.app.mealflow

import com.feedme.core.ports.SessionBoundary
import com.feedme.core.ports.SessionLease
import com.feedme.mealflow.CookingFlowState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CookingConfirmationKind { START, ABANDON, COMPLETE }

/** Ephemeral UI consent only. The controller still authenticates all state and operations. */
class CookingConfirmation internal constructor(val kind: CookingConfirmationKind,
    internal val planId: String, internal val sessionId: String?, internal val sequence: String?,
    internal val formTicket: Long, internal val cookingState: CookingFlowState? = null) {
    override fun toString() = "CookingConfirmation(kind=$kind, private=<redacted>)"
}

/** Navigation only; never a second cooking progress, completion or persisted route authority. */
class CookingNavigation internal constructor(val visible: Boolean, val confirmation: CookingConfirmation?) {
    override fun toString() = "CookingNavigation(visible=$visible, private=<redacted>)"
}

internal class CookingUiOwner(private val boundary: SessionBoundary, private val lease: SessionLease) {
    private val mutable = MutableStateFlow(CookingNavigation(false, null))
    val states = mutable.asStateFlow()
    private var epoch = 0L
    private var disposed = false
    private val subscription = boundary.onInvalidated(lease) { clear() }
    fun current() = !disposed && boundary.isCurrent(lease)
    fun ticket() = epoch
    fun matches(ticket: Long) = current() && ticket == epoch
    fun show(): CookingNavigation? {
        if (!current()) return null
        val shown = CookingNavigation(true, mutable.value.confirmation)
        mutable.value = shown
        return shown
    }
    fun present(intent: CookingConfirmation) {
        if (current()) { epoch++; mutable.value = CookingNavigation(true, intent) }
    }
    fun owns(intent: CookingConfirmation) = current() && mutable.value.confirmation === intent
    fun dismiss() { epoch++; mutable.value = CookingNavigation(current() && mutable.value.visible, null) }
    fun leave() { epoch++; mutable.value = CookingNavigation(false, null) }
    private fun clear() { epoch++; mutable.value = CookingNavigation(false, null) }
    fun close() { clear(); disposed = true; subscription.close() }
}
