package com.feedme.mealflow.timers

import android.os.Handler
import android.os.Looper
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SessionBoundary
import com.feedme.kitchen.PrivateKitchenSession
import com.feedme.session.AndroidNativeWorkCancellation
import com.feedme.session.NativeWorkCancellationPort
import com.feedme.session.NativeWorkKind
import com.feedme.session.NativeWorkTicket
import com.feedme.session.PrivateSessionAccess
import com.feedme.session.PrivateSessionRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Foreground-only, borrowed native integration. Construct on Main before opening the runtime and
 * supply THIS exact object as its cancellation port. [bind] creates the sole matched timer facade
 * using that actual runtime/current access. No public callback-gate replacement or accepting
 * default exists. The application owns native stores, runtime, policy, lifecycle and retirement.
 *
 * No notification, AlarmManager scheduling, receiver or WorkManager initialization is installed.
 * The required real cancellation delegate removes exact existing alarm/notification identities
 * (and workers); this adapter first fences and removes its own Handler callback. Neither enqueue
 * nor cancellation proves deadline precision, background delivery or callback quiescence.
 */
class AndroidForegroundCookingTimerAdapter private constructor(
    private val cancellation: AndroidNativeWorkCancellation,
    val clock: AndroidProcessCookingTimerClock,
) : CookingTimerScheduler, NativeWorkCancellationPort, CookingTimerDeliveryObservations {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var owner: SessionCookingTimers? = null
    private var effect: ((NativeWorkTicket) -> PortResult<Unit>)? = null
    private var attempted = false
    private var closed = false
    private var foregroundAttachment: AndroidTimerForegroundAttachment? = null
    private val dispatch = AndroidForegroundTimerDispatch(Handler(Looper.getMainLooper()), scope, clock,
        { ticket, local -> owner?.runLocalEffect(ticket, local) ?: PortResult.Failure(FailureReason.NOT_CONFIGURED) },
        { ticket -> effect?.invoke(ticket) ?: PortResult.Failure(FailureReason.NOT_CONFIGURED) })

    /**
     * Creates, rather than accepts, the matched facade. Main.immediate must also own the supplied
     * runtime, boundary and kitchen. onDue is a short synchronous foreground UI effect only:
     * no HTTP, suspension, recursive runtime call, step advancement or automatic completion.
     * A Value acknowledges facade binding, not a timer install or a delivered alert.
     */
    suspend fun bind(runtime: PrivateSessionRuntime, access: PrivateSessionAccess,
        kitchen: PrivateKitchenSession, boundary: SessionBoundary, policy: CookingTimerExecutionPolicy,
        onDue: (NativeWorkTicket) -> PortResult<Unit>): PortResult<SessionCookingTimers> {
        var created: SessionCookingTimers? = null
        var admitted = false
        try {
            val result = withContext(Dispatchers.Main.immediate) {
                currentCoroutineContext().ensureActive()
                if (closed) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
                if (attempted) return@withContext PortResult.Failure(FailureReason.CONFLICT)
                if (!runtime.usesCancellationPort(this@AndroidForegroundCookingTimerAdapter) || runtime.currentAccess() !== access)
                    return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
                attempted = true; admitted = true
                when (val bound = SessionCookingTimers.create(runtime, access, kitchen, boundary,
                    Dispatchers.Main.immediate, clock, this@AndroidForegroundCookingTimerAdapter, policy)) {
                    is PortResult.Failure -> { close(); bound }
                    is PortResult.Value -> {
                        created = bound.value
                        if (closed || !bound.value.matchesIntegration(clock, this@AndroidForegroundCookingTimerAdapter)) {
                            bound.value.close(); PortResult.Failure(FailureReason.STALE_SESSION)
                        } else {
                            owner = bound.value; effect = onDue
                            PortResult.Value(bound.value)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            return result
        } catch (cancelled: CancellationException) {
            // The adapter was retained before binding. A lost binding reply never frees a policy
            // for another facade or leaves a deliverable orphan. Caller still owns the runtime.
            if (admitted) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                created?.close(); close()
            }
            throw cancelled
        }
    }

    /** UI-facing composition: resolves display identity only inside the actual runtime-gated
     * synchronous due effect. The callback receives no ticket or private mapping/proof bytes.
     */
    suspend fun bindForUi(runtime: PrivateSessionRuntime, access: PrivateSessionAccess,
        kitchen: PrivateKitchenSession, boundary: SessionBoundary, policy: CookingTimerExecutionPolicy,
        onDue: (CookingTimerDue) -> PortResult<Unit>): PortResult<SessionCookingTimers> =
        bind(runtime, access, kitchen, boundary, policy) { ticket ->
            val identity = owner?.dueIdentity(ticket)
            if (identity == null) PortResult.Failure(FailureReason.STALE_SESSION) else onDue(identity)
        }

    /** An exact retained Activity attachment. Replacing it fences the old foreground before
     * publication. A stale Activity's stop/destroy cannot alter the new host's callbacks.
     */
    fun attachForeground(): PortResult<AndroidTimerForegroundAttachment> {
        requireMain()
        if (closed || owner == null) return PortResult.Failure(if (closed) FailureReason.STALE_SESSION else FailureReason.NOT_CONFIGURED)
        val fenced = dispatch.setForeground(false)
        if (fenced is PortResult.Failure) return fenced
        return PortResult.Value(AndroidTimerForegroundAttachment().also { foregroundAttachment = it })
    }

    fun setForeground(attachment: AndroidTimerForegroundAttachment, foreground: Boolean): PortResult<Unit> {
        requireMain()
        if (foregroundAttachment !== attachment) return PortResult.Failure(FailureReason.STALE_SESSION)
        return setForeground(foreground)
    }

    fun detachForeground(attachment: AndroidTimerForegroundAttachment): PortResult<Unit> {
        requireMain()
        if (foregroundAttachment !== attachment) return PortResult.Failure(FailureReason.STALE_SESSION)
        val result = setForeground(false)
        if (result is PortResult.Value) foregroundAttachment = null
        return result
    }

    /** Trusted Activity/foreground-owner lifecycle, called synchronously on Main. Default is off.
     * Re-entering foreground can resume only callbacks retained in this exact live owner; it does
     * not reload persisted mappings or manufacture a lease after process death.
     */
    fun setForeground(foreground: Boolean): PortResult<Unit> {
        requireMain()
        if (closed) return PortResult.Failure(FailureReason.STALE_SESSION)
        if (owner == null) return PortResult.Failure(FailureReason.NOT_CONFIGURED)
        return dispatch.setForeground(foreground)
    }

    override suspend fun schedule(ticket: NativeWorkTicket, deadlineMillis: Long): PortResult<Unit> =
        withContext(Dispatchers.Main.immediate) {
            currentCoroutineContext().ensureActive()
            when {
                closed -> PortResult.Failure(FailureReason.STALE_SESSION)
                owner == null -> PortResult.Failure(FailureReason.NOT_CONFIGURED)
                else -> dispatch.enqueue(ticket, deadlineMillis)
            }
        }

    /** Cleanup remains available after close. Failed/cancelled downstream acknowledgements retain
     * the exact CANCELLING entry; retry calls the real delegate again, even if local work is absent.
     */
    override suspend fun cancel(ticket: NativeWorkTicket): PortResult<Unit> = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        if (ticket.kind == NativeWorkKind.TIMER) dispatch.fence(ticket)
        try {
            val result = cancellation.cancel(ticket)
            currentCoroutineContext().ensureActive()
            if (result is PortResult.Value && ticket.kind == NativeWorkKind.TIMER) dispatch.acknowledgeCancellation(ticket)
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PortResult.Failure(FailureReason.UNAVAILABLE) }
    }

    /** Main-thread diagnostic only, not effect or session authority. */
    fun inspect(ticket: NativeWorkTicket): PortResult<AndroidForegroundTimerObservation> {
        requireMain(); return dispatch.observe(ticket)
    }

    override fun observeDelivery(ticket: NativeWorkTicket): PortResult<CookingTimerDeliveryPhase> {
        requireMain()
        return when (val observed = dispatch.observe(ticket)) {
            is PortResult.Value -> PortResult.Value(CookingTimerDeliveryPhase.valueOf(observed.value.phase.name))
            is PortResult.Failure -> if (observed.reason == FailureReason.NOT_FOUND)
                PortResult.Value(CookingTimerDeliveryPhase.NOT_INSTALLED) else observed
        }
    }

    /** Local callback fence only. Does not retire a runtime, close its stores or acknowledge native
     * cancellation. Keep this same adapter available until the actual runtime cleanup completes.
     */
    fun close() {
        requireMain()
        if (closed) return
        closed = true; foregroundAttachment = null; dispatch.close(); owner?.close(); owner = null; effect = null; scope.cancel()
    }

    override fun toString() = "AndroidForegroundCookingTimerAdapter(<redacted>)"
    private fun requireMain() { check(Looper.myLooper() === Looper.getMainLooper()) { "Foreground timer requires main owner" } }

    companion object {
        /** No context/resource initialization, receiver default, native scheduling or permission. */
        fun create(cancellation: AndroidNativeWorkCancellation, clock: AndroidProcessCookingTimerClock): PortResult<AndroidForegroundCookingTimerAdapter> =
            if (Looper.myLooper() !== Looper.getMainLooper()) PortResult.Failure(FailureReason.NOT_CONFIGURED)
            else PortResult.Value(AndroidForegroundCookingTimerAdapter(cancellation, clock))
    }
}

/** Identity token only. Application retains it with the exact foreground Activity attachment. */
class AndroidTimerForegroundAttachment internal constructor() {
    override fun toString() = "AndroidTimerForegroundAttachment(<redacted>)"
}
