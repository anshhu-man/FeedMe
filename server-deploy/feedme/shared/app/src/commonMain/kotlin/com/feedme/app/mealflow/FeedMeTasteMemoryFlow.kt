package com.feedme.app.mealflow

import androidx.compose.runtime.*
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.memory.MealMemoryController
import com.feedme.mealflow.memory.MealMemoryPhase
import com.feedme.mealflow.memory.MealMemoryScreen
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SETTINGS.04 is navigation to the existing private MEMORY journey, not a global setting.
 * The caller explicitly opens [MealMemoryController.openHistory] and retains the same owner.
 * Rendering never fetches, submits, retries, or changes personalization settings. */
@Composable
fun FeedMeTasteMemoryFlow(
    controller: MealMemoryController,
    hostIsCurrent: () -> Boolean,
    onClose: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
) {
    val observed by controller.states.collectAsState()
    val state = observed
    val host by rememberUpdatedState(hostIsCurrent)
    val close by rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    var attached by remember(controller) { mutableStateOf(true) }
    var busy by remember(controller) { mutableStateOf(false) }
    var departing by remember(controller) { mutableStateOf(false) }
    DisposableEffect(controller) { onDispose { attached = false } }

    fun current() = attached && host() && controller.isCurrent(state) &&
        state.phase != MealMemoryPhase.UNAVAILABLE
    fun canDepart() = attached && !departing && controller.states.value === state &&
        state.screen != MealMemoryScreen.HIDDEN && state.phase != MealMemoryPhase.WORKING
    fun action(work: suspend () -> Unit) {
        if (!current() || busy || departing) return
        busy = true
        scope.launch { try { if (current()) work() } finally { busy = false } }
    }
    fun leave() {
        if (!canDepart()) return
        departing = true
        scope.launch {
            try {
                withContext(NonCancellable) {
                    val result = controller.leave(state)
                    // leave clears this local observation and fences any old GET. It does
                    // not abandon the encrypted original or cancel a submitted mutation.
                    if (result is PortResult.Value && attached &&
                        controller.states.value.screen == MealMemoryScreen.HIDDEN) close()
                }
            } finally { departing = false }
        }
    }
    fun back() {
        if (!canDepart()) return
        when {
            current() && !busy && state.phase != MealMemoryPhase.LOADING &&
                (state.review != null || state.screen == MealMemoryScreen.MEMORY_DETAIL) ->
                action { controller.back(state) }
            else -> leave()
        }
    }

    BlueprintMealMemoryFlow(controller, state,
        busy = busy || departing || state.phase in setOf(MealMemoryPhase.LOADING, MealMemoryPhase.WORKING),
        isCurrent = ::current, onAction = ::action, onBack = ::back,
        departureAllowed = ::canDepart, platformBackHandler = platformBackHandler)
}
