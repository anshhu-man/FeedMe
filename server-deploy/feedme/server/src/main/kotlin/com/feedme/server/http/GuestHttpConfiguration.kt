package com.feedme.server.http

import com.feedme.server.guest.GuestIngredientSearchStore
import com.feedme.server.guest.GuestKitchenStore
import com.feedme.server.guest.GuestCookingStore
import com.feedme.server.guest.GuestSavedRecipeStore
import com.feedme.server.guest.GuestFeedbackStore
import com.feedme.server.guest.GuestPlansStore
import com.feedme.server.guest.GuestSessionStore
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit guest bootstrap, kitchen and first-plan composition, not deployment admission.
 * A runtime must supply the real session authority, protected keys, catalog and owned bounded
 * DB dispatcher before constructing this. No accepting policy, fixture, secret lookup or
 * Main activation is supplied here. Account search may coexist via its separate verifier.
 * Only implemented guest capabilities may be advertised by this purpose-fixed assembly.
 */
class GuestHttpConfiguration internal constructor(
    internal val sessions: GuestSessionStore,
    internal val search: GuestIngredientSearchStore,
    internal val databaseDispatcher: CoroutineDispatcher,
    internal val kitchen: GuestKitchenStore? = null,
    internal val plans: GuestPlansStore? = null,
    internal val cooking: GuestCookingStore? = null,
    internal val saved: GuestSavedRecipeStore? = null,
    internal val feedback: GuestFeedbackStore? = null,
) {
    init {
        require(search.isBoundTo(sessions)) { "Guest HTTP session owner mismatch" }
        require(kitchen == null || kitchen.isBoundTo(sessions)) { "Guest kitchen session owner mismatch" }
        require(plans == null || plans.isBoundTo(sessions)) { "Guest planning session owner mismatch" }
        require(cooking == null || cooking.isBoundTo(sessions)) { "Guest cooking session owner mismatch" }
        require(cooking == null || (plans != null && plans.sharesPreparationWith(cooking))) {
            "Guest cooking requires the configured planning owner"
        }
        require(saved == null || saved.isBoundTo(sessions)) { "Guest saved-recipe session owner mismatch" }
        require(saved == null || (plans != null && plans.sharesPreparationWith(saved))) {
            "Guest saved recipes require the configured planning owner"
        }
        require(feedback == null || feedback.isBoundTo(sessions)) { "Guest feedback session owner mismatch" }
        val implemented = setOf("searchIngredients") +
            (if (kitchen == null) emptySet() else guestKitchenHttpOperations) +
            (plans?.implementedOperations ?: emptySet()) +
            (if (cooking == null) emptySet() else guestCookingHttpOperations) +
            (if (saved == null) emptySet() else guestSavedRecipeHttpOperations) +
            (if (feedback == null) emptySet() else guestFeedbackHttpOperations)
        require(sessions.advertisedCapabilities.toSet() == implemented) {
            "Guest HTTP capabilities do not match implemented operations"
        }
    }
    override fun toString() = "GuestHttpConfiguration(<redacted>)"
}
