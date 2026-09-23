package com.feedme.app.release

import com.feedme.app.blueprint.BlueprintAction
import com.feedme.app.blueprint.BlueprintRegistry
import com.feedme.app.blueprint.BlueprintScreenId

/**
 * Runtime projection of the approved V1 partition for production mobile routing.
 *
 * The generated registry retains all 98 prototype screens. This policy does not delete or
 * disable their debug/reference presentations; it prevents production route descriptors and
 * action dispatchers from admitting later-only destinations.
 */
internal object V1MobileReleaseScope {
    val deferredFeatureIds: Set<String> = setOf(
        "F32", "F33", "F34", "F35", "F36", "F37", "F38", "F44", "F45", "F46",
    )

    val deferredOnlyScreens: Set<BlueprintScreenId> by lazy {
        val included = BlueprintRegistry.features.filterNot { it.id in deferredFeatureIds }
            .flatMapTo(linkedSetOf()) { it.screenIds }
        BlueprintRegistry.features.filter { it.id in deferredFeatureIds }
            .flatMapTo(linkedSetOf()) { it.screenIds }
            .filterTo(linkedSetOf()) { it !in included }
    }

    val deferredOnlyOperations: Set<String> by lazy {
        val included = BlueprintRegistry.features.filterNot { it.id in deferredFeatureIds }
            .flatMapTo(linkedSetOf()) { it.operationIds }
        BlueprintRegistry.features.filter { it.id in deferredFeatureIds }
            .flatMapTo(linkedSetOf()) { it.operationIds }
            .filterTo(linkedSetOf()) { it !in included }
    }

    fun allowsScreen(screen: BlueprintScreenId): Boolean = screen !in deferredOnlyScreens

    fun admitScreens(screens: Set<BlueprintScreenId>): Set<BlueprintScreenId> =
        screens.filterTo(linkedSetOf(), ::allowsScreen)

    /**
     * Classifies only an action that is unambiguously later-only. A multi-branch picker stays
     * available when at least one branch is included; its production owner must still admit the
     * exact selected branch. Unknown action IDs fail closed.
     */
    fun allowsAction(actionId: String): Boolean {
        val action = BlueprintRegistry.action(actionId) ?: return false
        val source = runCatching { BlueprintScreenId.valueOf(action.id.substringBefore('.')) }.getOrNull()
            ?: return false
        return source !in deferredOnlyScreens &&
            action.target !in deferredOnlyScreens &&
            action.operationId !in deferredOnlyOperations &&
            !action.allBranchesDeferred()
    }

    private fun BlueprintAction.allBranchesDeferred(): Boolean =
        branches.isNotEmpty() && branches.all { it.target in deferredOnlyScreens }
}
