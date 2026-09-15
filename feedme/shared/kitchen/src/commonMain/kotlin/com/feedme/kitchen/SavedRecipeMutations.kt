package com.feedme.kitchen

import com.feedme.core.ports.StoreMutation

/**
 * Detached, purpose-fixed repository changes for the existing command queue's atomic batch.
 * Preparation performs no save/delete and is neither rights evidence nor an acknowledgement.
 * The caller must retain the ORIGINAL batch plus actual queue archive evidence across unknown
 * apply acknowledgements. Reading matching records alone never makes an unknown apply successful.
 */
class SavedRecipePreparedMutation internal constructor(
    mutations: List<StoreMutation>,
    val savedRecipeId: String? = null,
    val snapshot: SavedRecipeSnapshot? = null,
) {
    private val changes = mutations.toList()
    val mutations: List<StoreMutation> get() = changes.toList()
    override fun toString() = "SavedRecipePreparedMutation(<redacted>, count=${changes.size})"
}

/** A bounded server observation, not a downloaded bundle or permission to create another copy. */
class SavedRecipePageObservation internal constructor(
    val query: String?,
    val cursor: String?,
    val limit: Int,
    items: List<SavedRecipeSnapshot>,
    val nextCursor: String?,
    val serverTime: String,
) {
    private val entries = items.toList()
    val items: List<SavedRecipeSnapshot> get() = entries.toList()
    override fun toString() = "SavedRecipePageObservation(<redacted>, count=${entries.size})"
}
