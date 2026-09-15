package com.feedme.mealflow.social

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Read-only presentation lifetime. It is not consent, current principal authority, queue
 * provenance or ACK. All getters are atomics-only and safe from Compose/caller dispatchers.
 * Unbound standalone preparation still pins its actual controller's navigation epoch. */
@OptIn(ExperimentalAtomicApi::class)
internal class PublicationNavigationValidity(private val epoch: AtomicReference<Any>, private val observed: Any,
    private val selection: DraftReviewedSelectionFence? = null) {
    fun isCurrent(): Boolean = epoch.load() === observed && selection?.isCurrentNow() != false
}
