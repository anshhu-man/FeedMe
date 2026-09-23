package com.feedme.app.mealflow

import com.feedme.app.blueprint.BlueprintEffortChoice
import com.feedme.app.blueprint.BlueprintEffortState
import com.feedme.app.blueprint.BlueprintCleanupChoice
import com.feedme.app.blueprint.BlueprintTasteChoice
import com.feedme.app.blueprint.BlueprintTasteState
import com.feedme.mealflow.MealEnergy

/** Rejection-only render fence. The host must also check its exact dialog/visit and session
 * before dispatch and inside the existing synchronous form edit. This grants no authority. */
internal fun blueprintRefinementIsCurrent(expected: MealFormState, current: MealFormState,
    hostCurrent: Boolean): Boolean = hostCurrent && expected === current && !expected.busy && expected.values != null

/** Original EFFORT fields over the real unsent meal form. No preference write or read on mount.
 * Vessel chips map only to exact reviewed preparation tags and remain independent of an
 * existing numeric cleanup-minute limit. */
internal fun blueprintEffortPresentation(form: MealFormState, enabled: Boolean = false,
    equipmentAvailable: Boolean = false): BlueprintEffortState? {
    val values = form.values ?: return null
    return blueprintEffortPresentation(values, enabled && !form.busy, equipmentAvailable)
}

/** Pure values presentation for an already-owned adaptation or meal form. The caller's
 * actual owner supplies availability; this does not manufacture a form or edit authority. */
internal fun blueprintEffortPresentation(values: MealFormValues, enabled: Boolean = false,
    equipmentAvailable: Boolean = false): BlueprintEffortState {
    val active = enabled
    val cleanup = when (values.requiredPreparationTags) {
        emptyList<String>() -> BlueprintCleanupChoice.ANY
        listOf("oneBowl") -> BlueprintCleanupChoice.ONE_BOWL
        listOf("onePan") -> BlueprintCleanupChoice.ONE_PAN
        else -> null
    }
    return BlueprintEffortState(
        effort = when (values.energy) {
            MealEnergy.ASSEMBLE -> BlueprintEffortChoice.ASSEMBLE_ONLY
            MealEnergy.LITTLE -> BlueprintEffortChoice.LITTLE_COOKING
            MealEnergy.HAPPY -> BlueprintEffortChoice.HAPPY_TO_COOK
        },
        minutes = values.totalMinutes,
        activeMinutes = values.activeMinutes,
        cleanup = cleanup,
        enabled = active,
        allowApply = active && cleanup != null && blueprintEffortTimesValid(values.totalMinutes, values.activeMinutes),
        status = if (cleanup == null)
            "Your current preparation style is kept. Choose Any, One bowl or One pan to replace it."
        else if (equipmentAvailable)
            "Change equipment opens your saved kitchen preferences. Your effort choices stay here until Apply; this meal’s explicit equipment choices are unchanged."
        else if (values.cleanupMinutes.isNotEmpty())
            "Your existing cleanup-minute limit is kept alongside this preparation style."
        else "Choose a reviewed preparation style; equipment and other meal choices stay unchanged.",
        allowedCleanupChoices = BlueprintCleanupChoice.entries.toSet(), allowEquipment = active && equipmentAvailable,
        allowedNavigation = emptySet(), allowMore = false,
    )
}

/** Apply only these four exact fields. The original screen's required time input supports
 * plain positive Int values; unsupported numeric representations remain visible and unchanged,
 * rather than being rounded, trimmed or normalised. The actual controller still validates. */
internal fun blueprintEffortEdit(before: MealFormValues, input: BlueprintEffortState): MealFormValues? {
    if (input.cleanup !in BlueprintCleanupChoice.entries ||
        !blueprintEffortTimesValid(input.minutes, input.activeMinutes)) return null
    val energy = when (input.effort ?: return null) {
        BlueprintEffortChoice.ASSEMBLE_ONLY -> MealEnergy.ASSEMBLE
        BlueprintEffortChoice.LITTLE_COOKING -> MealEnergy.LITTLE
        BlueprintEffortChoice.HAPPY_TO_COOK -> MealEnergy.HAPPY
    }
    val preparationTags = when (input.cleanup) {
        BlueprintCleanupChoice.ANY -> emptyList()
        BlueprintCleanupChoice.ONE_BOWL -> listOf("oneBowl")
        BlueprintCleanupChoice.ONE_PAN -> listOf("onePan")
        null -> return null
    }
    return before.copy(energy = energy, totalMinutes = input.minutes, activeMinutes = input.activeMinutes,
        requiredPreparationTags = preparationTags)
}

internal fun blueprintEffortTimesValid(totalText: String, activeText: String): Boolean {
    fun exactPositive(text: String): Int? = text.toIntOrNull()?.takeIf { it > 0 && it.toString() == text }
    val total = exactPositive(totalText) ?: return false
    if (activeText.isEmpty()) return true
    return exactPositive(activeText)?.let { it <= total } == true
}

private fun BlueprintTasteChoice.canonicalTag(): String? = when (this) {
    BlueprintTasteChoice.ANY -> null
    BlueprintTasteChoice.CRUNCH -> "crunch"
    BlueprintTasteChoice.FRESH -> "fresh"
    BlueprintTasteChoice.CREAMY -> "creamy"
    BlueprintTasteChoice.HEAT -> "heat"
}

/** Match configured canonical identifiers, never display labels or case-folded guesses.
 * Any is an explicit local removal of this meal's optional taste tags, not a provider fact. */
internal fun blueprintAvailableTastes(choices: MealInputChoices): Set<BlueprintTasteChoice> =
    BlueprintTasteChoice.entries.filterTo(linkedSetOf()) { choice ->
        choice == BlueprintTasteChoice.ANY || choices.tastes.any { it.id == choice.canonicalTag() }
    }

internal fun blueprintTastePresentation(form: MealFormState, choices: MealInputChoices,
    enabled: Boolean = false): BlueprintTasteState? {
    val values = form.values ?: return null
    return blueprintTastePresentation(values, choices, enabled && !form.busy)
}

/** The actual form owner supplies availability; projecting adaptation values does not
 * create another owner or permission to change its ingredient/replacement constraints. */
internal fun blueprintTastePresentation(values: MealFormValues, choices: MealInputChoices,
    enabled: Boolean = false): BlueprintTasteState {
    val available = blueprintAvailableTastes(choices)
    val selected = if (values.tasteTags.isEmpty()) BlueprintTasteChoice.ANY
        else if (values.tasteTags.size == 1) available.singleOrNull { it.canonicalTag() == values.tasteTags.single() }
        else null
    val active = enabled
    return BlueprintTasteState(taste = selected, enabled = active, allowApply = active && selected != null,
        status = if (selected == null)
            "Your current taste choices are kept. Choose a supported single taste to replace them, or Any to clear them."
        else null, allowedTastes = available, allowedNavigation = emptySet(), allowMore = false)
}

/** Explicit TASTE.01 only. TASTE.02 (“Anything works today”) is a navigation action in the
 * canonical registry and must NOT call this helper to silently clear an existing choice. */
internal fun blueprintTasteEdit(before: MealFormValues, choice: BlueprintTasteChoice,
    choices: MealInputChoices): MealFormValues? {
    if (choice !in blueprintAvailableTastes(choices)) return null
    return before.copy(tasteTags = listOfNotNull(choice.canonicalTag()))
}

/** An optional exact equipment picker adapter, not a cleanup-chip mapping or saved preference
 * mutation. Only explicitly selected configured IDs are accepted; labels cannot supply IDs. */
internal fun blueprintEquipmentEdit(before: MealFormValues, selectedIds: List<String>,
    choices: MealInputChoices): MealFormValues? {
    if (selectedIds.distinct().size != selectedIds.size ||
        selectedIds.any { id -> choices.equipment.none { it.id == id } }) return null
    return before.copy(equipmentIds = selectedIds.toList())
}
