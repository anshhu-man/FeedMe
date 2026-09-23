package com.feedme.app.mealflow

import com.feedme.kitchen.SavedRecipeSnapshot

internal enum class CookbookViewSort { AS_RECEIVED, TITLE_ASCENDING }

/**
 * Presentation of this loaded view only, never a globally sorted paginated cookbook.
 * Returns the exact supplied snapshot objects without modifying their order in [items].
 */
internal fun sortedCookbookView(
    items: List<SavedRecipeSnapshot>,
    sort: CookbookViewSort,
): List<SavedRecipeSnapshot> {
    val received = items.toList()
    if (sort == CookbookViewSort.AS_RECEIVED) return received
    val order = cookbookViewOrder(received.map(::SavedRecipePresentation), sort)
    return order.map { received[it] }
}

/**
 * Only already-displayable titles influence the permutation. Opaque rows keep their exact
 * original slots, including their relative order. Invariant lowercase comparison is
 * deterministic and locale-independent; equal folded titles keep their received order.
 */
internal fun cookbookViewOrder(
    items: List<SavedRecipePresentation>,
    sort: CookbookViewSort,
): List<Int> {
    if (sort == CookbookViewSort.AS_RECEIVED) return items.indices.toList()
    val readable = items.mapIndexedNotNull { index, view ->
        if (view.contentVisible) index to view.title.lowercase() else null
    }.sortedWith(compareBy<Pair<Int, String>> { it.second }.thenBy { it.first })
    val order = items.indices.toMutableList()
    val slots = readable.map { it.first }.sorted()
    slots.forEachIndexed { position, slot -> order[slot] = readable[position].first }
    return order
}
