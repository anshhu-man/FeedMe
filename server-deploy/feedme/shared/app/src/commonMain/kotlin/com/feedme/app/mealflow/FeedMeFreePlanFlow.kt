package com.feedme.app.mealflow

import androidx.compose.runtime.Composable
import com.feedme.app.blueprint.BlueprintCommerceEvent
import com.feedme.app.blueprint.BlueprintCommercePage
import com.feedme.app.blueprint.BlueprintCommercePhase
import com.feedme.app.blueprint.BlueprintCommercePolicyFact
import com.feedme.app.blueprint.BlueprintCommerceScreen
import com.feedme.app.blueprint.BlueprintCommerceState

/**
 * V1's truthful free-release owner for the retained MANAGE_PLAN presentation.
 *
 * This owner deliberately has no store offer, transaction, paid entitlement,
 * purchase SDK or server command. It explains that core cooking is free and
 * admits only Back. Later paid-commerce owners must replace this state rather
 * than treating it as an entitlement or a successful purchase observation.
 */
fun freeReleasePlanState(enabled: Boolean): BlueprintCommerceState = BlueprintCommerceState(
    page = BlueprintCommercePage.MANAGE_PLAN,
    phase = BlueprintCommercePhase.UNAVAILABLE,
    enabledActionIds = if (enabled) setOf("MANAGE_PLAN.back") else emptySet(),
    policies = setOf(
        BlueprintCommercePolicyFact.CORE_COOKING_FREE,
        BlueprintCommercePolicyFact.RETAINED_SAVED_ACCESS,
    ),
    statusMessage = "FeedMe's core cooking experience is free. No purchase offer is available in this release.",
)

@Composable
fun FeedMeFreePlanFlow(
    enabled: Boolean,
    onBack: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
) {
    val state = freeReleasePlanState(enabled)
    platformBackHandler(enabled, onBack)
    BlueprintCommerceScreen(state, onEvent = { event ->
        if (enabled && event is BlueprintCommerceEvent.Action && event.expected === state &&
            event.actionId == "MANAGE_PLAN.back") onBack()
    })
}
