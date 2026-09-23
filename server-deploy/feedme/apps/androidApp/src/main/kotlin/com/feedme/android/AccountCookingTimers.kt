package com.feedme.android

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.feedme.app.mealflow.MealFlowExperience
import com.feedme.core.ports.*
import com.feedme.kitchen.PrivateKitchenSession
import com.feedme.mealflow.MealOperationIds
import com.feedme.mealflow.timers.*
import com.feedme.session.*
import kotlinx.coroutines.Dispatchers

/** One account-owner lifetime. No timers, HTTP, notifications or workers start on construction.
 * The native owner invokes open only after acquiring its actual reservation/stores. Persisted
 * timers on a new process remain observation/cleanup-only; nothing automatically reinstalls. */
internal class AccountCookingTimers(private val context: Context, private val clock: EpochClock,
    private val current: (EmailAccountPrivateConnection, MealFlowExperience) -> Boolean) {
    private val policy = CookingTimerExecutionPolicy()
    private var adapter: AndroidForegroundCookingTimerAdapter? = null
    private var connection: EmailAccountPrivateConnection? = null
    private var facade: SessionCookingTimers? = null
    private var experience: MealFlowExperience? = null
    private var nativeAttachment: AndroidTimerForegroundAttachment? = null
    private var hostAttachment: Any? = null
    private var opened = false
    private var closed = false
    private var published = false
    private var activationFailure: FailureReason? = null
    val foreground = EntryTimerForegroundGate(::foregroundChanged)

    fun open(): PortResult<AndroidEmailAccountWorkIntegration> {
        if (opened || closed) return PortResult.Failure(FailureReason.CONFLICT)
        opened = true
        val cancellation = when (val result = AndroidNativeWorkCancellation.createTimerOnly(context,
            ComponentName(context, AccountTimerCancellationReceiver::class.java))) {
            is PortResult.Failure -> return result
            is PortResult.Value -> result.value
        }
        val selected = when (val result = AndroidForegroundCookingTimerAdapter.create(cancellation, AndroidProcessCookingTimerClock())) {
            is PortResult.Failure -> return result
            is PortResult.Value -> result.value
        }
        adapter = selected
        return PortResult.Value(AndroidEmailAccountWorkIntegration(selected, policy, ::bind) {
            close(); PortResult.Value(Unit)
        })
    }

    private suspend fun bind(runtime: PrivateSessionRuntime, selected: EmailAccountPrivateConnection): PortResult<Unit> {
        if (closed || connection != null) return PortResult.Failure(FailureReason.CONFLICT)
        connection = selected // Retain before a suspended bind or a lost caller reply.
        val access = selected.access
        // Same native store/origin and durable cooking journal, with no optional command grants.
        val kitchen = PrivateKitchenSession(access.scope, access.store, selected.boundary,
            Dispatchers.Main.immediate, clock, selected.transport, access.originBinding)
        return when (val result = checkNotNull(adapter).bindForUi(runtime, access, kitchen,
            selected.boundary, policy) { due ->
                val ui = experience
                if (closed || ui == null || !current(selected, ui)) PortResult.Failure(FailureReason.STALE_SESSION)
                else ui.onTimerDue(due)
            }) {
            is PortResult.Failure -> result
            is PortResult.Value -> { facade = result.value; PortResult.Value(Unit) }
        }
    }

    suspend fun attach(selected: EmailAccountPrivateConnection, ui: MealFlowExperience,
        ids: MealOperationIds): PortResult<Unit> {
        if (closed || connection !== selected || experience != null) return PortResult.Failure(FailureReason.STALE_SESSION)
        val retained = facade ?: return PortResult.Failure(FailureReason.NOT_CONFIGURED)
        experience = ui
        return ui.attachTimers(selected.access, retained, ids)
    }

    /** Separate from product publication: the caller first rechecks the exact delivered route.
     * Native lifecycle callbacks stay disabled until this explicit post-delivery activation. */
    fun activateForeground(selected: EmailAccountPrivateConnection, ui: MealFlowExperience): PortResult<Unit> {
        if (closed || connection !== selected || experience !== ui || !selected.boundary.isCurrent(selected.access.lease))
            return PortResult.Failure(FailureReason.STALE_SESSION)
        published = true
        foreground.reapply()
        return if (closed) PortResult.Failure(activationFailure ?: FailureReason.STALE_SESSION) else PortResult.Value(Unit)
    }

    /** Confirmation is reversible. Keep due work retained, not delivered to a hidden meal
     * route or permanently closed before native sign-out has actually been admitted. */
    fun pauseForAccountReview() {
        foreground.pauseDelivery()
    }

    fun resumeAfterAccountReview(): PortResult<Unit> {
        if (closed) return PortResult.Failure(activationFailure ?: FailureReason.STALE_SESSION)
        foreground.resumeDelivery()
        return if (closed) PortResult.Failure(activationFailure ?: FailureReason.STALE_SESSION) else PortResult.Value(Unit)
    }

    private fun foregroundChanged(token: Any?, resumed: Boolean) {
        val selected = adapter ?: return
        if (token !== hostAttachment) {
            nativeAttachment?.let { selected.detachForeground(it) }
            nativeAttachment = null; hostAttachment = token
        }
        if (closed || !published || token == null || facade == null || experience == null) return
        if (nativeAttachment == null) when (val attached = selected.attachForeground()) {
            is PortResult.Value -> nativeAttachment = attached.value
            is PortResult.Failure -> { activationFailure = attached.reason; close(); return }
        }
        val result = selected.setForeground(checkNotNull(nativeAttachment), resumed)
        if (result is PortResult.Failure) {
            activationFailure = result.reason; close() // Never leave a failed lifecycle fence deliverable.
        }
    }

    /** Fence callbacks synchronously before product/connection/native teardown. The same adapter
     * remains the runtime's exact cancellation delegate until its work ledger has closed. */
    fun close() {
        if (closed) return
        closed = true; foreground.close(); adapter?.close(); facade?.close()
        experience = null; nativeAttachment = null; hostAttachment = null
    }
}

/** Disabled, private identity for exact cleanup only. No incoming Intent can restore or deliver
 * a timer. This foreground-only build never schedules an alarm to this receiver. */
class AccountTimerCancellationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) = Unit
}
