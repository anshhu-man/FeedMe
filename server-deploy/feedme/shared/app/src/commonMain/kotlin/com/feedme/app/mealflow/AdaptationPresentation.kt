package com.feedme.app.mealflow

import com.feedme.contracts.*
import com.feedme.mealflow.*

/** Display-only text; id retains the exact spelling in the corresponding snapshot. */
class AdaptationDisplayRow internal constructor(val id: String, val title: String,
    val before: String, val after: String) {
    val accessibilityText get() = "$title. Original: $before. Proposed: $after."
    override fun toString() = "AdaptationDisplayRow(<redacted>)"
}

/** A projection of the retained offer, not acceptance, editorial review or fresh authority.
 * Literal quantities, instructions and estimates are never transformed into a new recipe. */
class AdaptationPresentation internal constructor(
    val original: MealPlanPresentation,
    val proposed: MealPlanPresentation,
    reason: MealAdaptationReason,
    comparison: AdaptationComparison?,
    choices: MealInputChoices,
) {
    private val equipment = choices.equipment.associate { it.id to it.label }
    val title = when (reason) {
        MealAdaptationReason.MAKE_MINE -> "Review your version"
        MealAdaptationReason.MISSING_INGREDIENT -> "Review the replacement"
    }
    val originalNotice = "Original meal unchanged: ${original.title}. Choose a version explicitly to replace it."
    /** Informational only. Actual actions must revalidate the exact controller-issued offer. */
    val canCompare = comparison != null && original.recipeVisible && proposed.recipeVisible
    val statusMessage = when (proposed.plan.status) {
        "ready" -> if (canCompare) "Compare the proposed version. It has not been selected."
            else "No verified comparison is available. Your original meal has not been replaced."
        "needsConfirmation" -> "The response needs confirmation. No replacement has been selected."
        "noMatch" -> "No supported version was returned. Your original meal remains selected."
        "recalled" -> "The proposed version is unavailable. Your original meal has not been replaced."
        else -> "No verified proposal is available. Your original meal has not been replaced."
    }
    val serverChangesLabel = "Server-reported changes"
    val comparisonNotice = "These are recorded snapshot differences, not a new review or permission to cook or save."
    private val effort = if (!canCompare) emptyList() else comparison!!.let {
        listOf(
            simplificationEffortLine("Servings", it.before.servings, it.after.servings, ""),
            simplificationEffortLine("Hands-on", it.before.activeMinutes, it.after.activeMinutes),
            simplificationEffortLine("Total", it.before.totalMinutes, it.after.totalMinutes),
            simplificationEffortLine("Cleanup", it.before.cleanupMinutes, it.after.cleanupMinutes),
            simplificationEffortLine("Utensils", it.before.utensilCount, it.after.utensilCount, ""),
            "Selected energy: ${simplificationEnergyLabel(it.before.energy)} → ${simplificationEnergyLabel(it.after.energy)}",
            "Mode: ${simplificationModeLabel(it.before.mode)} → ${simplificationModeLabel(it.after.mode)}",
        )
    }
    private val ingredients = if (!canCompare) emptyList() else comparison!!.ingredientChanges.map { change ->
        val id = change.before?.ingredientId?.value ?: change.after!!.ingredientId.value
        val name = original.ingredientName(id) ?: proposed.ingredientName(id) ?: "Ingredient label unavailable ($id)"
        val kind = when { change.before == null -> "Added ingredient"; change.after == null -> "Removed ingredient"; else -> "Changed ingredient" }
        AdaptationDisplayRow(id, "$kind: $name", change.before?.let(original::ingredientLine) ?: "Not in original",
            change.after?.let(proposed::ingredientLine) ?: "Not in proposed version")
    }
    private val steps = if (!canCompare) emptyList() else comparison!!.stepChanges.map { change ->
        val kind = when { change.before == null -> "Added step"; change.after == null -> "Removed step"; else -> "Changed step" }
        AdaptationDisplayRow(change.stepId, "$kind: ${change.stepId}",
            change.before?.let { stepText(it, original) } ?: "Not in original",
            change.after?.let { stepText(it, proposed) } ?: "Not in proposed version")
    }
    private val equipmentChanges = if (!canCompare) emptyList() else comparison!!.let {
        buildList {
            if (it.addedEquipmentIds.isNotEmpty()) add("Added equipment: ${it.addedEquipmentIds.joinToString(transform = ::equipmentName)}")
            if (it.removedEquipmentIds.isNotEmpty()) add("Removed equipment: ${it.removedEquipmentIds.joinToString(transform = ::equipmentName)}")
            if (isEmpty()) add("No equipment changes recorded.")
        }
    }
    private val missing = if (proposed.recipeVisible || proposed.plan.status in setOf("needsConfirmation", "noMatch"))
        proposed.plan.missingIngredients.map(proposed::ingredientLine) else emptyList()
    val missingIngredientStatus = when {
        missing.isNotEmpty() -> "Missing ingredients reported by the server. This is not a fresh availability check."
        !proposed.recipeVisible -> "No replacement recipe is available to check for missing ingredients."
        else -> "No missing ingredients reported. This is not a fresh availability check."
    }
    private val recordedReasons = proposed.reasons.toList()
    private val recordedChanges = proposed.changes.toList()
    val effortLines get() = effort.toList()
    val ingredientRows get() = ingredients.toList()
    val stepRows get() = steps.toList()
    val equipmentLines get() = equipmentChanges.toList()
    val missingIngredientLines get() = missing.toList()
    val reasons get() = recordedReasons.toList()
    val serverChanges get() = recordedChanges.toList()

    private fun equipmentName(id: String) = equipment[id] ?: "Equipment label unavailable ($id)"
    private fun stepText(step: RecipeStepWire, plan: MealPlanPresentation): String = buildString {
        append("Step ${step.position.jsonToken}: ${step.instruction}")
        append("\nIngredients: ")
        append(step.ingredientIds.joinToString { plan.ingredientName(it.value) ?: "Ingredient label unavailable (${it.value})" }.ifEmpty { "None listed" })
        append("\nEquipment: ")
        append(step.requiredEquipmentIds.joinToString(transform = ::equipmentName).ifEmpty { "None listed" })
        append("\nDuration: ")
        append(step.durationSeconds.valueOrNull()?.jsonToken?.let { "$it s" } ?: "not recorded")
        append("\nMandatory safety step: ")
        append(if (step.mandatorySafetyStep) "yes" else "no")
    }
    override fun toString() = "AdaptationPresentation(<redacted>)"

    companion object {
        fun from(proposal: MealAdaptationProposal, labels: Map<String, String>, choices: MealInputChoices) =
            AdaptationPresentation(MealPlanPresentation(proposal.parent.plan, proposal.parent.historical, labels),
                MealPlanPresentation(proposal.child.plan, proposal.child.historical, labels), proposal.reason,
                proposal.comparison, choices)
    }
}
