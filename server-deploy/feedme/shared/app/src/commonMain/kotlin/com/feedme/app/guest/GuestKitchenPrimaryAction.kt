package com.feedme.app.guest

/** Local input guidance only. These actions do not acknowledge a save, resolve ingredients,
 * select a recipe or grant permission to contact a service. */
internal enum class GuestKitchenPrimaryAction(val label: String) {
    ADD_INGREDIENTS("Add ingredients"),
    CHOOSE_PREPARATION("Choose preparation"),
    CHOOSE_CLEANUP("Choose cleanup"),
    CHOOSE_SERVINGS("Choose servings"),
    FIND_MEAL("Find me a meal"),
    UNAVAILABLE("Find me a meal"),
}

/** Follow the visible form order, without translating retained legacy choices into inputs. */
internal fun guestPrimaryAction(state: GuestKitchenDraftState): GuestKitchenPrimaryAction {
    if (state.phase !in setOf(GuestDraftPhase.READY, GuestDraftPhase.SAVING) || state.issue != null)
        return GuestKitchenPrimaryAction.UNAVAILABLE
    val draft = state.draft ?: return GuestKitchenPrimaryAction.UNAVAILABLE
    return when {
        draft.ingredientsText.isBlank() -> GuestKitchenPrimaryAction.ADD_INGREDIENTS
        draft.preparation == null -> GuestKitchenPrimaryAction.CHOOSE_PREPARATION
        draft.cleanupLimit == null -> GuestKitchenPrimaryAction.CHOOSE_CLEANUP
        draft.servings == null -> GuestKitchenPrimaryAction.CHOOSE_SERVINGS
        guestCanFindMeal(state) -> GuestKitchenPrimaryAction.FIND_MEAL
        else -> GuestKitchenPrimaryAction.UNAVAILABLE
    }
}
