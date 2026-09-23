package com.feedme.android

import android.app.Application
import android.os.Looper
import com.feedme.app.guest.GuestDraftPhase
import com.feedme.app.guest.GuestKitchenDraft
import com.feedme.app.guest.GuestKitchenDraftController
import com.feedme.app.guest.GuestKitchenDraftStore
import com.feedme.app.guest.GuestKitchenTab
import com.feedme.app.guest.guestCanFindMeal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Separate process-owned local draft. No account entry, network, credentials or merge. */
@OptIn(FlowPreview::class)
internal class GuestEntry(
    store: GuestKitchenDraftStore,
    private val scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val assertMainThread: () -> Unit,
) {
    constructor(application: Application) : this(androidGuestDraftStore(application),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), Dispatchers.IO,
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
        if (!attached(token)) return
        controller.update(draft)
        mutableMatchingUnavailable.value = false
    }

    fun flush(token: Any) {
        mainThread()
        if (attached(token)) scope.launch { controller.save() }
    }

    fun retry(token: Any) {
        mainThread()
        if (attached(token) && states.value.phase == GuestDraftPhase.BLOCKED) scope.launch { controller.load() }
    }

    fun select(token: Any, tab: GuestKitchenTab) {
        mainThread()
        if (attached(token)) mutableTab.value = tab
    }

    fun findMeal(token: Any) {
        mainThread()
        if (!attached(token) || !guestCanFindMeal(states.value)) return
        // No reviewed local catalog or live guest matching exists yet. Never substitute fixtures.
        mutableMatchingUnavailable.value = true
        flush(token)
    }

    private fun mainThread() = assertMainThread()
}
