package com.feedme.app.mealflow

import com.feedme.app.blueprint.BlueprintScreenId
import com.feedme.app.release.V1MobileReleaseScope

/** Explicit owner-admitted tab destinations, not authentication or network authority.
 * The callback must retain its exact meal/form/route admission across any suspension. */
class MealTabNavigation(destinations: Set<BlueprintScreenId>, val navigate: (BlueprintScreenId) -> Unit) {
    private val admitted = V1MobileReleaseScope.admitScreens(destinations)
    val destinations: Set<BlueprintScreenId> get() = admitted.toSet()
}

private val mealTabs = setOf(BlueprintScreenId.TODAY, BlueprintScreenId.HOME,
    BlueprintScreenId.COOKBOOK, BlueprintScreenId.INBOX, BlueprintScreenId.PROFILE_PLATE)

/** A supplied empty set closes every tab. An absent seam preserves only the existing
 * Saved callback; it does not invent access to any newly connected destination. */
internal fun blueprintMealTabDestinations(form: MealFormState, actions: MealScreenActions,
    available: Boolean = true): Set<BlueprintScreenId> {
    if (!available || form.busy) return emptySet()
    return actions.tabNavigation?.destinations?.intersect(mealTabs)?.let(V1MobileReleaseScope::admitScreens)
        ?: if (actions.cookbook != null) setOf(BlueprintScreenId.COOKBOOK) else emptySet()
}

internal fun dispatchBlueprintMealTab(destination: BlueprintScreenId, admitted: Set<BlueprintScreenId>,
    actions: MealScreenActions) {
    if (!V1MobileReleaseScope.allowsScreen(destination) || !actions.blueprintCurrent() ||
        destination !in admitted || destination !in mealTabs) return
    val tabs = actions.tabNavigation
    if (tabs != null) {
        if (destination in tabs.destinations) tabs.navigate(destination)
    } else if (destination == BlueprintScreenId.COOKBOOK) actions.cookbook?.invoke()
}
