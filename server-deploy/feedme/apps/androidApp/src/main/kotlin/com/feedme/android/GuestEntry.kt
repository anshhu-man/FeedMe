package com.feedme.android

import android.app.Application
import android.os.Looper
import com.feedme.app.guest.GuestDraftPhase
import com.feedme.app.guest.GuestKitchenDraft
import com.feedme.app.guest.GuestKitchenDraftController
import com.feedme.app.guest.GuestKitchenDraftStore
import com.feedme.app.guest.GuestKitchenTab
import com.feedme.app.guest.guestCanFindMeal
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal enum class GuestMealPhase {
    UNCONFIGURED, NEW, OPENING, BINDING_RETRY_REQUIRED, READY, MATCHING,
    VISIBLE, FAILED, CLOSING, CLOSE_FAILED, CLOSED,
}

internal class GuestMealState(
    val phase: GuestMealPhase,
    val route: GuestMealRoute? = null,
    val failure: FailureReason? = null,
) {
    override fun toString() = "GuestMealState(phase=$phase, failure=$failure, private=<redacted>)"
}

/** Process-owned guest draft and optional exact guest meal session. Account entry remains
 * separate; guest inputs are never merged into an account or uploaded before explicit Find. */
@OptIn(FlowPreview::class)
internal class GuestEntry(
    store: GuestKitchenDraftStore,
    private val scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val mealBackend: GuestMealBackend? = null,
    private val assertMainThread: () -> Unit,
) {
    constructor(application: Application) : this(androidGuestDraftStore(application),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), Dispatchers.IO,
        AndroidGuestMealBackend.create(application, loadFeedMeConfiguration(application)),
        { check(Looper.myLooper() == Looper.getMainLooper()) })

    private val controller = GuestKitchenDraftController(store, dispatcher)
    private val attachments = EntryAttachmentGate()
    private var started = false
    val states = controller.states
    val attachmentStates = attachments.states
    private val mutableTab = MutableStateFlow(GuestKitchenTab.COOK)
    val tabs = mutableTab.asStateFlow()
    private val mutableMatchingUnavailable = MutableStateFlow(false)
    val matchingUnavailable = mutableMatchingUnavailable.asStateFlow()
    private val mutableMeal = MutableStateFlow(GuestMealState(
        if (mealBackend == null) GuestMealPhase.UNCONFIGURED else GuestMealPhase.NEW))
    val mealStates = mutableMeal.asStateFlow()
    private var mealOperation: Any? = null
    private var mealJob: Job? = null

    fun attach(): Any {
        mainThread()
        val token = attachments.attach()
        if (!started) {
            started = true
            scope.launch {
                states.map { state -> state.draft.takeIf { state.phase == GuestDraftPhase.READY && state.dirty } }
                    .distinctUntilChanged().debounce(300).collect { pending ->
                        // collect (not collectLatest): new typing must never cancel a dispatched write.
                        if (pending != null && states.value.draft == pending &&
                            states.value.phase == GuestDraftPhase.READY && states.value.dirty) controller.save()
                    }
            }
            scope.launch { controller.load() }
            if (mealBackend != null) openMealSession(retry = false)
        }
        return token
    }

    fun attached(token: Any): Boolean = attachments.owns(token)

    fun detach(token: Any) {
        mainThread()
        attachments.detach(token)
        // Keep the process owner and any pending write. Activity recreation is not a reset.
    }

    fun update(token: Any, draft: GuestKitchenDraft) {
        mainThread()
        if (!attached(token) || mutableMeal.value.phase in setOf(GuestMealPhase.MATCHING,
                GuestMealPhase.CLOSING, GuestMealPhase.CLOSE_FAILED, GuestMealPhase.CLOSED)) return
        controller.update(draft)
        mutableMatchingUnavailable.value = false
        val meal = mutableMeal.value
        if (meal.phase == GuestMealPhase.READY && meal.failure != null)
            mutableMeal.value = GuestMealState(GuestMealPhase.READY, meal.route)
    }

    fun flush(token: Any) {
        mainThread()
        if (attached(token)) scope.launch { controller.save() }
    }

    fun retry(token: Any) {
        mainThread()
        if (attached(token) && states.value.phase == GuestDraftPhase.BLOCKED) scope.launch { controller.load() }
        if (attached(token) && mutableMeal.value.phase == GuestMealPhase.BINDING_RETRY_REQUIRED)
            openMealSession(retry = true)
    }

    fun select(token: Any, tab: GuestKitchenTab) {
        mainThread()
        if (attached(token)) mutableTab.value = tab
    }

    fun findMeal(token: Any) {
        mainThread()
        if (!attached(token) || !guestCanFindMeal(states.value)) return
        val selected = mealBackend
        val meal = mutableMeal.value
        val route = meal.route
        val draft = states.value.draft
        if (selected == null || route == null || draft == null || meal.phase != GuestMealPhase.READY ||
            mealOperation != null || !selected.current(route)) {
            mutableMatchingUnavailable.value = true
            flush(token)
            return
        }
        val ticket = Any(); mealOperation = ticket
        mutableMatchingUnavailable.value = false
        mutableMeal.value = GuestMealState(GuestMealPhase.MATCHING, route)
        flush(token)
        val job = scope.launch {
            try {
                val result = selected.find(route, draft)
                if (mealOperation !== ticket || mutableMeal.value.route !== route) return@launch
                when (result) {
                    is PortResult.Value -> mutableMeal.value = GuestMealState(GuestMealPhase.VISIBLE, route)
                    is PortResult.Failure -> {
                        mutableMatchingUnavailable.value = true
                        mutableMeal.value = GuestMealState(GuestMealPhase.READY, route, result.reason)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (mealOperation === ticket && mutableMeal.value.route === route) {
                    mutableMatchingUnavailable.value = true
                    mutableMeal.value = GuestMealState(GuestMealPhase.READY, route, FailureReason.UNAVAILABLE)
                }
            } finally {
                if (mealOperation === ticket) mealOperation = null
                if (mealJob === currentCoroutineContext()[Job]) mealJob = null
            }
        }
        mealJob = job
    }

    fun mealRouteCurrent(token: Any, expected: GuestMealState): Boolean {
        mainThread()
        val route = expected.route ?: return false
        return attached(token) && mutableMeal.value === expected && expected.phase == GuestMealPhase.VISIBLE &&
            mealBackend?.current(route) == true
    }

    fun hideMeal(token: Any, expected: GuestMealState) {
        mainThread()
        if (!mealRouteCurrent(token, expected)) return
        mutableMeal.value = GuestMealState(GuestMealPhase.READY, expected.route)
    }

    /** Account composition cannot borrow the guest reservation. Retire and acknowledge the
     * exact guest owner first; only then may the Activity open the separate account entry. */
    fun switchToAccount(token: Any, ready: () -> Unit) {
        mainThread()
        if (!attached(token)) return
        val selected = mealBackend
        if (selected == null || mutableMeal.value.phase in setOf(GuestMealPhase.UNCONFIGURED, GuestMealPhase.CLOSED)) {
            ready(); return
        }
        if (mutableMeal.value.phase == GuestMealPhase.CLOSING ||
            mealOperation != null && mealJob == null) return
        val route = mutableMeal.value.route
        val pending = mealJob
        pending?.cancel()
        val ticket = Any(); mealOperation = ticket
        mutableMeal.value = GuestMealState(GuestMealPhase.CLOSING, route)
        val job = scope.launch {
            try {
                pending?.join()
                val draft = states.value
                if (draft.dirty && !controller.save()) {
                    mutableMeal.value = GuestMealState(GuestMealPhase.CLOSE_FAILED, route,
                        FailureReason.STORAGE_FAILURE)
                    return@launch
                }
                when (val result = selected.close(route)) {
                    is PortResult.Value -> {
                        mutableMeal.value = GuestMealState(GuestMealPhase.CLOSED)
                        if (attached(token)) ready()
                    }
                    is PortResult.Failure -> mutableMeal.value =
                        GuestMealState(GuestMealPhase.CLOSE_FAILED, route, result.reason)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableMeal.value = GuestMealState(GuestMealPhase.CLOSE_FAILED, route,
                    FailureReason.STORAGE_FAILURE)
            } finally {
                if (mealOperation === ticket) mealOperation = null
                if (mealJob === currentCoroutineContext()[Job]) mealJob = null
            }
        }
        mealJob = job
    }

    internal fun closedForReplacement(): Boolean {
        mainThread()
        return mutableMeal.value.phase == GuestMealPhase.CLOSED && mealOperation == null &&
            attachments.states.value == null && mealJob?.isActive != true
    }

    internal fun disposeClosed() {
        mainThread()
        check(closedForReplacement())
        controller.close()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun openMealSession(retry: Boolean) {
        val selected = mealBackend ?: return
        if (mealOperation != null) return
        val before = mutableMeal.value
        if ((!retry && before.phase != GuestMealPhase.NEW) ||
            (retry && before.phase != GuestMealPhase.BINDING_RETRY_REQUIRED)) return
        val ticket = Any(); mealOperation = ticket
        mutableMeal.value = GuestMealState(GuestMealPhase.OPENING)
        val job = scope.launch {
            try {
                val result = if (retry) selected.retryOpen() else selected.open()
                if (mealOperation !== ticket) return@launch
                when (result) {
                    is PortResult.Value -> mutableMeal.value = GuestMealState(GuestMealPhase.READY, result.value)
                    is PortResult.Failure -> mutableMeal.value = GuestMealState(
                        if (selected.bindingRetryAvailable()) GuestMealPhase.BINDING_RETRY_REQUIRED else GuestMealPhase.FAILED,
                        failure = result.reason)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (mealOperation === ticket)
                    mutableMeal.value = GuestMealState(GuestMealPhase.FAILED, failure = FailureReason.UNAVAILABLE)
            } finally {
                if (mealOperation === ticket) mealOperation = null
                if (mealJob === currentCoroutineContext()[Job]) mealJob = null
            }
        }
        mealJob = job
    }

    private fun mainThread() = assertMainThread()
}
