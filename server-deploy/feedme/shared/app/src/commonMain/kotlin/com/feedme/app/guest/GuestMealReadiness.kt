package com.feedme.app.guest

/** Readiness for the local Find intent only, not ingredient resolution, recipe eligibility,
 * persistence acknowledgement, content review or permission to call a service. Both the
 * visible button and the host must enforce the same explicit-input requirements.
 */
fun guestCanFindMeal(state: GuestKitchenDraftState): Boolean {
    if (state.phase !in setOf(GuestDraftPhase.READY, GuestDraftPhase.SAVING) || state.issue != null) return false
    val draft = state.draft ?: return false
    return draft.ingredientsText.isNotBlank() && draft.hasExplicitEffort
}
