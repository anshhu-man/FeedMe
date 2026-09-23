package com.feedme.app.onboarding

/** Reviewed presentation metadata supplied by the configured host, never catalog authority.
 * IDs are exact wire values, not derived from labels. No presets or ingredient effects are implied. */
class OnboardingPreferenceChoice(val id: String, val label: String, val selectable: Boolean) {
    init {
        require(id.isNotEmpty() && id.length <= 512 && wellFormedChoiceText(id))
        require(label.isNotBlank() && label.length <= 512 && wellFormedChoiceText(label))
    }
    override fun toString(): String = "OnboardingPreferenceChoice(redacted)"
}

/** Null configuration means unavailable choices; an explicitly supplied empty list stays empty. */
class OnboardingPreferenceChoices(
    dietaryPatterns: List<OnboardingPreferenceChoice>,
    equipment: List<OnboardingPreferenceChoice>,
) {
    private val dietValues = dietaryPatterns.toList()
    private val equipmentValues = equipment.toList()
    init {
        require(dietValues.size <= 1_000 && equipmentValues.size <= 1_000)
        require(dietValues.map { it.id }.distinct().size == dietValues.size)
        require(equipmentValues.map { it.id }.distinct().size == equipmentValues.size)
    }
    val dietaryPatterns: List<OnboardingPreferenceChoice> get() = dietValues.toList()
    val equipment: List<OnboardingPreferenceChoice> get() = equipmentValues.toList()
    override fun toString(): String = "OnboardingPreferenceChoices(redacted)"
}

internal enum class PreferenceChoiceAvailability { AVAILABLE, RETIRED, UNRESOLVED }
internal class PreferenceChoiceRow(
    val id: String, val label: String, val selected: Boolean,
    val availability: PreferenceChoiceAvailability,
) {
    val canAdd: Boolean get() = availability == PreferenceChoiceAvailability.AVAILABLE
    override fun toString(): String = "PreferenceChoiceRow(redacted)"
}

/** Unknown IDs remain distinct and removable. Neither missing metadata nor retirement clears them. */
internal fun preferencesChoiceRows(
    selected: List<String>?, choices: List<OnboardingPreferenceChoice>?,
): List<PreferenceChoiceRow> {
    val known = choices.orEmpty().associateBy { it.id }
    val selectedIds = selected.orEmpty()
    val retained = selectedIds.map { id ->
        val choice = known[id]
        PreferenceChoiceRow(id, choice?.label ?: "Unlisted choice ($id)", true, when {
            choice == null -> PreferenceChoiceAvailability.UNRESOLVED
            !choice.selectable -> PreferenceChoiceAvailability.RETIRED
            else -> PreferenceChoiceAvailability.AVAILABLE
        })
    }
    return retained + choices.orEmpty().filter { it.id !in selectedIds }.map {
        PreferenceChoiceRow(it.id, it.label, false,
            if (it.selectable) PreferenceChoiceAvailability.AVAILABLE else PreferenceChoiceAvailability.RETIRED)
    }
}

/** Used only on an explicit row click: removing an old ID is allowed, adding needs supplied metadata.
 * The actual saved field is a basis for this explicit edit, never an automatic draft on observation. */
internal fun preferencesChoose(
    draft: List<String>?, saved: List<String>?, choice: OnboardingPreferenceChoice,
): List<String>? {
    val selected = draft ?: saved
    return when {
        choice.id in selected.orEmpty() -> selected.orEmpty().filterNot { it == choice.id }
        choice.selectable -> selected.orEmpty() + choice.id
        else -> selected?.toList()
    }
}

internal fun preferencesRemove(draft: List<String>?, saved: List<String>?, id: String): List<String>? {
    val selected = draft ?: saved
    return if (id in selected.orEmpty()) selected.orEmpty().filterNot { it == id } else selected?.toList()
}

private fun wellFormedChoiceText(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.code < 32 || char.code == 127) return false
        if (char.isHighSurrogate()) {
            if (index == value.length || !value[index++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return true
}
