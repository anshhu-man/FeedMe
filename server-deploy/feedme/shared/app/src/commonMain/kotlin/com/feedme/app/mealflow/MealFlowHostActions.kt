package com.feedme.app.mealflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** UI attachment admission only. Never grants session authority or owns experience lifetime.
 * All calls are on the UI dispatcher. Retirement is permanent for this composition; a new
 * Activity/composition gets a different gate while the real experience remains retained. */
internal class MealFlowHostActions(private val hostIsCurrent: () -> Boolean) {
    private var attached = true

    fun isCurrent(): Boolean = attached && hostIsCurrent()

    fun retire() { attached = false }

    fun run(action: () -> Unit) {
        if (isCurrent()) action()
    }

    /** Check both the synchronous click and queued coroutine admission. */
    fun launch(scope: CoroutineScope, action: suspend () -> Unit): Job? {
        if (!isCurrent()) return null
        return scope.launch {
            await(action)
        }
    }

    /** A held result cannot authorize this attachment's subsequent local navigation.
     * The operation's own retained-owner semantics still govern any already-started work. */
    suspend fun <T> await(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Meal UI attachment is no longer current")
        val result = action()
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Meal UI attachment is no longer current")
        return result
    }
}
