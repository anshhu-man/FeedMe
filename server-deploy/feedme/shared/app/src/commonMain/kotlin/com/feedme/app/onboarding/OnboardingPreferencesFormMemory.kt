package com.feedme.app.onboarding

import com.feedme.session.OnboardingOptionalPrompt
import com.feedme.session.OnboardingPreferencesController
import com.feedme.session.OnboardingPreferencesFields
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable RAM-only editing snapshot. List getters detach from caller-owned mutable lists. */
class PreferencesFormBuffer(input: OnboardingPreferencesFields, base: OnboardingPreferencesFields = input) {
    private val inputValue = input.detachedPreferences()
    private val baseValue = base.detachedPreferences()
    val input: OnboardingPreferencesFields get() = inputValue.detachedPreferences()
    val base: OnboardingPreferencesFields get() = baseValue.detachedPreferences()
    val dirty: Boolean get() = inputValue != baseValue
    fun edit(next: OnboardingPreferencesFields): PreferencesFormBuffer = PreferencesFormBuffer(next, baseValue)

    /** Update only untouched fields; even an explicit [] or omitted field can be a newer edit. */
    fun observe(next: OnboardingPreferencesFields): PreferencesFormBuffer {
        fun <T> keep(current: T, previous: T, observed: T): T = if (current == previous) observed else current
        return PreferencesFormBuffer(OnboardingPreferencesFields(
            keep(inputValue.hardExcludedIngredientIds, baseValue.hardExcludedIngredientIds, next.hardExcludedIngredientIds),
            keep(inputValue.dietaryPatterns, baseValue.dietaryPatterns, next.dietaryPatterns),
            keep(inputValue.dislikedIngredientIds, baseValue.dislikedIngredientIds, next.dislikedIngredientIds),
            keep(inputValue.equipmentIds, baseValue.equipmentIds, next.equipmentIds),
            keep(inputValue.preferredTasteTags, baseValue.preferredTasteTags, next.preferredTasteTags),
            keep(inputValue.defaultEnergy, baseValue.defaultEnergy, next.defaultEnergy),
            keep(inputValue.consentVersion, baseValue.consentVersion, next.consentVersion),
            keep(inputValue.defaultServings, baseValue.defaultServings, next.defaultServings),
        ), next)
    }

    /** A late Keep acknowledges its captured input, not later typing. */
    fun kept(captured: OnboardingPreferencesFields): PreferencesFormBuffer = PreferencesFormBuffer(inputValue, captured)
    override fun toString(): String = "PreferencesFormBuffer(redacted)"
}

class PreferencesFormSnapshot internal constructor(
    val buffer: PreferencesFormBuffer,
    val query: String,
    val retired: Boolean,
) {
    override fun toString(): String = "PreferencesFormSnapshot(redacted)"
}

/** Caller-owned RAM, retained per exact controller/prompt across Activity recreation.
 * No SavedState, local persistence, automatic Keep, network or runtime permission. The parent must
 * fence the actual child and clear/drop this holder on retirement, NOT ordinary composition disposal.
 * Internal identity constructor permits pure lifetime tests without manufacturing a controller. */
class OnboardingPreferencesFormMemory internal constructor(
    private val owner: Any,
    private val prompt: OnboardingOptionalPrompt,
) {
    constructor(controller: OnboardingPreferencesController, prompt: OnboardingOptionalPrompt) : this(controller as Any, prompt)
    private val mutable = MutableStateFlow(PreferencesFormSnapshot(PreferencesFormBuffer(OnboardingPreferencesFields()), "", false))
    val states: StateFlow<PreferencesFormSnapshot> = mutable.asStateFlow()
    val hasUnkeptChanges: Boolean get() = !mutable.value.retired && mutable.value.buffer.dirty
    fun isBoundTo(controller: OnboardingPreferencesController, prompt: OnboardingOptionalPrompt): Boolean = bound(controller, prompt)
    internal fun bound(identity: Any, prompt: OnboardingOptionalPrompt): Boolean = !mutable.value.retired && owner === identity && this.prompt == prompt
    fun edit(fields: OnboardingPreferencesFields) = update { PreferencesFormSnapshot(it.buffer.edit(fields), it.query, false) }
    fun observe(fields: OnboardingPreferencesFields) = update { PreferencesFormSnapshot(it.buffer.observe(fields), it.query, false) }
    fun kept(captured: OnboardingPreferencesFields) = update { PreferencesFormSnapshot(it.buffer.kept(captured), it.query, false) }
    fun setQuery(query: String) = update { PreferencesFormSnapshot(it.buffer, query, false) }
    fun clear() {
        mutable.value = PreferencesFormSnapshot(PreferencesFormBuffer(OnboardingPreferencesFields()), "", true)
    }
    private fun update(transform: (PreferencesFormSnapshot) -> PreferencesFormSnapshot) {
        while (true) {
            val previous = mutable.value
            if (previous.retired) return
            val next = transform(previous)
            if (previous.buffer.input == next.buffer.input && previous.buffer.base == next.buffer.base &&
                previous.query == next.query && previous.retired == next.retired) return
            if (mutable.compareAndSet(previous, next)) return
        }
    }
    override fun toString(): String = "OnboardingPreferencesFormMemory(redacted)"
}

/** Omitted draft fields do not acquire GET values just because a form is displayed. */
internal fun preferencesFormInput(draft: OnboardingPreferencesFields?): OnboardingPreferencesFields =
    (draft ?: OnboardingPreferencesFields()).detachedPreferences()

internal fun OnboardingPreferencesFields.detachedPreferences(): OnboardingPreferencesFields = copy(
    hardExcludedIngredientIds = hardExcludedIngredientIds?.toList(), dietaryPatterns = dietaryPatterns?.toList(),
    dislikedIngredientIds = dislikedIngredientIds?.toList(), equipmentIds = equipmentIds?.toList(),
    preferredTasteTags = preferredTasteTags?.toList(),
)
