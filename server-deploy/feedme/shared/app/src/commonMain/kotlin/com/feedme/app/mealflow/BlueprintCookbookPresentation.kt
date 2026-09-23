package com.feedme.app.mealflow

import com.feedme.app.blueprint.*
import com.feedme.app.release.V1MobileReleaseScope
import com.feedme.kitchen.SavedRecipeAvailability
import com.feedme.mealflow.CookbookPhase
import com.feedme.mealflow.CookbookScreen
import com.feedme.mealflow.CookbookState

/** Projects only the actual owned list. A presentation context is not account authorization;
 * all callbacks additionally fence the original controller/query/host identities. */
internal fun blueprintCookbookState(state: CookbookState, query: String, busy: Boolean,
    context: BlueprintLibraryContext, memoryAvailable: Boolean = false,
    allowedNavigation: Set<BlueprintScreenId>? = null): BlueprintCookbookState? {
    if (state.screen != CookbookScreen.LIST || state.pending != null || state.deleteConfirmation != null ||
        state.failureReason != null || state.phase !in setOf(CookbookPhase.IDLE, CookbookPhase.READY, CookbookPhase.LOADING)) return null
    val blocked = busy || state.busy
    val entries = state.items.map { observed ->
        val material = observed.savedRecipe
        val readable = SavedRecipePresentation(observed)
        val recipe = readable.recipe
        BlueprintSavedEntry(
            reference = BlueprintSavedReference(observed.id, material?.version?.jsonToken ?: observed.etag ?: "unavailable"),
            title = readable.title,
            description = recipe?.let { "${it.totalMinutes.jsonToken} min · ${it.servings.jsonToken} servings" }.orEmpty(),
            marker = when {
                observed.availability == SavedRecipeAvailability.RECALLED || material?.recalled == true -> BlueprintSavedMarker.RECALLED
                !readable.contentVisible -> BlueprintSavedMarker.UNAVAILABLE
                else -> BlueprintSavedMarker.AVAILABLE
            }, badge = if (readable.downloaded) "Downloaded · historical"
                else if (state.historical) "Historical observation" else "Online observation",
        )
    }
    if (entries.map { it.reference.id }.distinct().size != entries.size) return null
    return BlueprintCookbookState(context, query, entries = entries,
        controls = BlueprintLibraryControls(loading = blocked,
            allowedActions = if (blocked) emptySet() else setOf(BlueprintLibraryAction.OPEN_SAVE, BlueprintLibraryAction.LIBRARY_TOOLS) +
                if (memoryAvailable && state.phase in setOf(CookbookPhase.IDLE, CookbookPhase.READY))
                    setOf(BlueprintLibraryAction.MANAGE_MEMORY) else emptySet(),
            allowedNavigation = if (blocked) emptySet() else V1MobileReleaseScope.admitScreens(
                (allowedNavigation ?: setOf(BlueprintScreenId.HOME)).intersect(
                setOf(BlueprintScreenId.HOME, BlueprintScreenId.TODAY, BlueprintScreenId.COOKBOOK,
                    BlueprintScreenId.INBOX, BlueprintScreenId.PROFILE_PLATE))),
            editableFields = if (blocked) emptySet() else setOf("search"),
            message = if (state.hasMore) "More meals are available in More." else null))
}

/** A real descriptor names each destination. Only the historical absent-descriptor
 * HOME affordance means Back; another tab must never silently borrow that behavior. */
internal fun dispatchBlueprintCookbookTab(state: BlueprintCookbookState, destination: BlueprintScreenId,
    current: () -> Boolean, actions: CookbookScreenActions) {
    fun admitted() = V1MobileReleaseScope.allowsScreen(destination) && current() &&
        actions.blueprintIsCurrent() && state.controls.permitsNavigation(destination)
    if (!admitted()) return
    val tabs = actions.tabNavigation
    if (tabs != null) {
        if (destination in tabs.destinations) {
            val guarded = actions.guardedTabNavigate
            if (guarded != null) guarded(destination, ::admitted) else tabs.navigate(destination)
        }
    } else if (destination == BlueprintScreenId.HOME) actions.back()
}

internal fun dispatchBlueprintSaved(state: BlueprintCookbookState, intent: BlueprintLibraryIntent,
    current: Boolean, open: (String) -> Unit, tools: () -> Unit, memory: (() -> Unit)? = null) {
    if (!current || intent.context !== state.context) return
    val exact = state.intent(intent.action, saved = intent.saved) ?: return
    if (exact.saved != intent.saved) return
    when (intent.action) {
        BlueprintLibraryAction.OPEN_SAVE -> intent.saved?.let { open(it.id) }
        BlueprintLibraryAction.LIBRARY_TOOLS -> tools()
        BlueprintLibraryAction.MANAGE_MEMORY -> memory?.invoke()
        else -> Unit
    }
}
