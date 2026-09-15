package com.feedme.mealflow.social

import com.feedme.mealflow.*
import com.feedme.core.ports.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Opaque cross-controller selection lifetime, not a principal/review/command permission.
 * Admission also requires the draft controller's exact live registered identity. */
@OptIn(ExperimentalAtomicApi::class)
internal class DraftReviewedSelectionFence internal constructor(private val drafts: PostDraftController,
    private val composition: MealKitchenComposition) {
    private val live = AtomicInt(1)
    fun revoke() { live.store(0) }
    fun isCurrentNow(): Boolean = live.load() == 1
    fun requireCurrentNow() { if (!isCurrentNow()) mealFail(FailureReason.STALE_SESSION) }
    fun requireRegistered(actualComposition: MealKitchenComposition) {
        if (composition !== actualComposition) mealFail(FailureReason.STALE_SESSION)
        drafts.requireReviewedSelection(this, actualComposition); requireCurrentNow()
    }
}
