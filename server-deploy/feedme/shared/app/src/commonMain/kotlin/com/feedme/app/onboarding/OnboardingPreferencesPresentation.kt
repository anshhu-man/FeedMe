package com.feedme.app.onboarding

import com.feedme.contracts.CanonicalBodyValidator
import com.feedme.contracts.ContractValidationResult
import com.feedme.core.ports.FailureReason
import com.feedme.session.*
import kotlinx.serialization.json.*

internal fun preferencesScreenId(prompt: OnboardingOptionalPrompt): String = when (prompt) {
    OnboardingOptionalPrompt.FOOD_PREFERENCES -> "FOOD_PREFS"
    OnboardingOptionalPrompt.EQUIPMENT -> "EQUIPMENT"
}
internal fun preferencesSaveId(prompt: OnboardingOptionalPrompt): String = "${preferencesScreenId(prompt)}.01"
internal fun preferencesSkipId(prompt: OnboardingOptionalPrompt): String = "${preferencesScreenId(prompt)}.02"
internal fun preferencesBackId(prompt: OnboardingOptionalPrompt): String = "${preferencesScreenId(prompt)}.back"
internal fun preferencesSaveLabel(prompt: OnboardingOptionalPrompt): String = when (prompt) {
    OnboardingOptionalPrompt.FOOD_PREFERENCES -> "Save preferences"
    OnboardingOptionalPrompt.EQUIPMENT -> "Save kitchen setup"
}
internal fun preferencesSkipLabel(prompt: OnboardingOptionalPrompt): String = when (prompt) {
    OnboardingOptionalPrompt.FOOD_PREFERENCES -> "Skip for now"
    OnboardingOptionalPrompt.EQUIPMENT -> "Finish without defaults"
}
internal fun preferencesTitle(prompt: OnboardingOptionalPrompt): String = when (prompt) {
    OnboardingOptionalPrompt.FOOD_PREFERENCES -> "Food preferences"
    OnboardingOptionalPrompt.EQUIPMENT -> "Your kitchen setup"
}
internal fun preferencesIntro(prompt: OnboardingOptionalPrompt): String = when (prompt) {
    OnboardingOptionalPrompt.FOOD_PREFERENCES -> "Choose what to leave out and what you enjoy. These guide suggestions, not a guarantee of preparation safety."
    OnboardingOptionalPrompt.EQUIPMENT -> "Choose the equipment you use. Servings are optional; nothing is selected for you."
}
internal const val PREFERENCES_CHOICES_UNAVAILABLE = "More choices are not available right now. Your saved selections are kept."
internal const val PREFERENCES_SKIP_COPY = "Skip this step without changing your saved choices or submitting this draft."

internal class PreferencesValidation(
    val canKeep: Boolean, val canSave: Boolean, val servingsError: String?, val formError: String?,
)

/** Uses the actual bundled canonical rules, including exact decimal range/precision, not Double.
 * No catalog or consent authority is inferred. Hidden fields are kept whole and never projected into
 * a prompt Save; an invalid full RAM draft cannot be Kept before that Save either. */
internal fun preferencesValidation(prompt: OnboardingOptionalPrompt, fields: OnboardingPreferencesFields): PreferencesValidation {
    val full = preferencesPatchValid(fields)
    val projected = preferencesPromptFields(prompt, fields)
    val explicit = projected != OnboardingPreferencesFields()
    val servings = fields.defaultServings
    val invalidServings = servings != null && !preferencesPatchValid(OnboardingPreferencesFields(defaultServings = servings))
    return PreferencesValidation(full, full && explicit && preferencesPatchValid(projected),
        if (invalidServings) "Enter a valid number of servings, or leave this choice unrecorded." else null,
        when {
            !full -> "Some choices need attention before they can be kept or saved."
            !explicit -> "Choose something or explicitly leave a section empty to save. You can also skip this step."
            else -> null
        })
}

internal fun preferencesPromptFields(prompt: OnboardingOptionalPrompt, fields: OnboardingPreferencesFields): OnboardingPreferencesFields = when (prompt) {
    OnboardingOptionalPrompt.FOOD_PREFERENCES -> OnboardingPreferencesFields(
        hardExcludedIngredientIds = fields.hardExcludedIngredientIds?.toList(),
        dietaryPatterns = fields.dietaryPatterns?.toList(), dislikedIngredientIds = fields.dislikedIngredientIds?.toList())
    OnboardingOptionalPrompt.EQUIPMENT -> OnboardingPreferencesFields(
        equipmentIds = fields.equipmentIds?.toList(), defaultServings = fields.defaultServings)
}

internal fun preferencesPatchValid(fields: OnboardingPreferencesFields): Boolean = try {
    val value = buildJsonObject {
        fun list(name: String, value: List<String>?) { value?.let { put(name, JsonArray(it.map(::JsonPrimitive))) } }
        list("hardExcludedIngredientIds", fields.hardExcludedIngredientIds)
        list("dietaryPatterns", fields.dietaryPatterns); list("dislikedIngredientIds", fields.dislikedIngredientIds)
        list("equipmentIds", fields.equipmentIds); list("preferredTasteTags", fields.preferredTasteTags)
        fields.defaultEnergy?.let { put("defaultEnergy", it) }; fields.consentVersion?.let { put("consentVersion", it) }
        fields.defaultServings?.let {
            // Parsing is not numeric conversion. Require a sole exact JSON-number token: no whitespace,
            // strings, booleans, null, exponent rewriting or a second injected field.
            require(it.length in 1..1_000 && it.first() in "-0123456789" && it.none(Char::isWhitespace))
            val number = Json.parseToJsonElement(it)
            require(number is JsonPrimitive && !number.isString && number.content == it)
            put("defaultServings", number)
        }
    }
    val bytes = value.toString().encodeToByteArray(throwOnInvalidSequence = true)
    bytes.size <= 65_536 && CanonicalBodyValidator.bundled().validateRequest("updatePreferences", bytes, "application/json") == ContractValidationResult.Valid
} catch (_: Exception) { false }

internal fun preferencesStatusTitle(status: OnboardingPreferencesStatus, recoveryRequired: Boolean, acknowledged: Boolean): String = when {
    recoveryRequired -> "Restore your saved progress"
    status in setOf(OnboardingPreferencesStatus.APPLIED, OnboardingPreferencesStatus.REJECTED, OnboardingPreferencesStatus.DISCARDED) && !acknowledged -> "Restore your saved progress"
    else -> when (status) {
        OnboardingPreferencesStatus.LOCAL -> "Your choices"
        OnboardingPreferencesStatus.PREPARED -> "A save is ready"
        OnboardingPreferencesStatus.UNCERTAIN -> "Check your last save"
        OnboardingPreferencesStatus.RETAINED_RESPONSE -> "Finish restoring your save"
        OnboardingPreferencesStatus.APPLIED -> "Choices saved"
        OnboardingPreferencesStatus.DISCARDED -> "Unsent save set aside"
        OnboardingPreferencesStatus.REJECTED -> "Your preferences changed elsewhere"
        OnboardingPreferencesStatus.UNAVAILABLE -> "Preferences unavailable"
    }
}
internal fun preferencesStatusBody(status: OnboardingPreferencesStatus): String = when (status) {
    OnboardingPreferencesStatus.LOCAL -> "Keep on device saves a draft here. Save sends only the choices for this step."
    OnboardingPreferencesStatus.PREPARED -> "Review the original choices before sending, or set aside this unsent save. Newer edits stay separate."
    OnboardingPreferencesStatus.UNCERTAIN -> "The last save may have arrived. Review its original choices before trying that same save again."
    OnboardingPreferencesStatus.RETAINED_RESPONSE -> "A reply was kept on this device. Restore it before continuing."
    OnboardingPreferencesStatus.APPLIED -> "The saved result is shown separately. Any newer draft still needs its own save."
    OnboardingPreferencesStatus.DISCARDED -> "The unsent save was set aside. Your editable draft is still here."
    OnboardingPreferencesStatus.REJECTED -> "Load the latest saved choices before starting a new save. Your draft is kept."
    OnboardingPreferencesStatus.UNAVAILABLE -> "Private choices are hidden. Return to account setup to restore access."
}
internal fun preferencesFailureText(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OFFLINE -> "You're offline. Your online changes are not confirmed."
    FailureReason.INVALID_DATA -> "Check your choices and try again."
    FailureReason.CONFLICT -> "This view changed. Use the current view to continue."
    FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED, FailureReason.FORBIDDEN -> "Account access changed. Private choices are hidden."
    FailureReason.OUTCOME_UNKNOWN -> "The save may have arrived. Restore or review the original before retrying."
    else -> "This step could not finish. Your last confirmed progress has not been replaced."
}
