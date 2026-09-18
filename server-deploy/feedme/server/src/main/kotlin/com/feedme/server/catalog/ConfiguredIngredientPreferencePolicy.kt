package com.feedme.server.catalog

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandActor
import com.feedme.server.kitchen.KitchenFailure
import com.feedme.server.kitchen.KitchenFailureCode
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import java.math.BigDecimal
import java.nio.charset.CharacterCodingException
import java.sql.Connection
import kotlinx.serialization.json.*

/** Explicit permission to store a changed preference consent-version field, not legal consent,
 * personalization permission, eligibility, or a privacy-purpose grant. No launch value/default.
 * The caller supplies actual locked previous fields and the exact merged proposed resource:
 * a changed field therefore came from the submitted PATCH. An unchanged field proves no new
 * action, even if its value happens to equal currentVersion. It remains historical data only. */
class PreferenceConsentPolicy(val currentVersion: String, val acceptNewConsent: Boolean) {
    init { preferenceConfiguration(preferenceText(currentVersion, 256)) }

    internal fun validate(previous: String?, proposed: String?) {
        if (previous != null && !preferenceText(previous, 256)) preferenceFailure(KitchenFailureCode.STORAGE_UNAVAILABLE)
        if (proposed != null && !preferenceText(proposed, 256)) preferenceFailure(KitchenFailureCode.INPUT_INVALID)
        if (previous == proposed) return
        if (proposed == null || proposed != currentVersion) preferenceFailure(KitchenFailureCode.INPUT_INVALID)
        if (!acceptNewConsent) preferenceFailure(KitchenFailureCode.POLICY_BLOCKED)
    }

    override fun toString() = "PreferenceConsentPolicy(<redacted>)"
}

/** Immutable operator-governed choices for one explicitly configured account runtime. There
 * are no supplied dietary presets, equipment labels, serving defaults or consent versions.
 * revision identifies this configuration; the wire request has no policy revision, so this
 * class does NOT claim stale-client revision detection or a live database policy registry.
 * Replace/drain the owning runtime to change its configuration; do not mutate it in place.
 *
 * Applies to fresh preference writes only, not owned GET or exact receipt replay. Well-formed
 * retired choices already stored may remain or be removed, but cannot be freshly selected.
 * Neither retained values nor an empty list grant consent/readiness, imply equipment ownership,
 * declare absence of allergies, expand an exclusion, or authorize planning. In particular this
 * does not add dietary support to the account planner. Actual ingredient publication/reference
 * checks stay in PostgresPendingPreferencesCatalog; current account/device/terms and the caller's
 * transaction remain mandatory. No SQL, network, commit, principal lookup or writes occur here. */
class ConfiguredIngredientPreferencePolicy(
    val environment: String,
    val revision: String,
    dietaryPatterns: Set<String>,
    equipmentIds: Set<String>,
    preferredTasteTags: Set<String>,
    defaultEnergies: Set<String>,
    val minimumDefaultServings: BigDecimal,
    val maximumDefaultServings: BigDecimal,
    val consent: PreferenceConsentPolicy,
) : IngredientPreferencePolicy {
    private val diets = choices(dietaryPatterns)
    private val equipment = choices(equipmentIds)
    private val tastes = choices(preferredTasteTags)
    private val energies = choices(defaultEnergies)
    val dietaryPatterns: Set<String> get() = diets.toSet()
    val equipmentIds: Set<String> get() = equipment.toSet()
    val preferredTasteTags: Set<String> get() = tastes.toSet()
    val defaultEnergies: Set<String> get() = energies.toSet()

    init {
        preferenceConfiguration(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && preferenceText(revision, 128))
        preferenceConfiguration(TASTES.containsAll(tastes) && ENERGIES.containsAll(energies))
        preferenceConfiguration(boundedNumber(minimumDefaultServings) && boundedNumber(maximumDefaultServings) &&
            minimumDefaultServings >= BigDecimal("0.1") && maximumDefaultServings >= minimumDefaultServings)
    }

    override fun validate(connection: Connection, principal: VerifiedKitchenPrincipal,
        previousFields: JsonObject, proposed: JsonObject) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Preference validation interrupted")
        if (principal.environment != environment || principal.kind != CommandActor.ACCOUNT || principal.deviceSessionId == null)
            preferenceFailure(KitchenFailureCode.UNAUTHENTICATED)
        if (connection.isClosed || connection.autoCommit) preferenceFailure(KitchenFailureCode.NOT_CONFIGURED)
        document(previousFields, "PreferencePatch", KitchenFailureCode.STORAGE_UNAVAILABLE)
        if (!previousFields.keys.containsAll(REQUIRED)) preferenceFailure(KitchenFailureCode.STORAGE_UNAVAILABLE)
        // Diagnose retained corruption before inspecting the merged resource, which may
        // contain the same corrupt fields. It is not a fresh user input rejection.
        val before = fields(previousFields, KitchenFailureCode.STORAGE_UNAVAILABLE)
        document(proposed, "Preference", KitchenFailureCode.INPUT_INVALID)
        // Actual PATCH merge cannot drop a field. Array removals remain explicit empty/subset arrays.
        if (!proposed.keys.containsAll(previousFields.keys)) preferenceFailure(KitchenFailureCode.INPUT_INVALID)
        val after = fields(proposed, KitchenFailureCode.INPUT_INVALID)
        selections(before.diets, after.diets, diets)
        selections(before.equipment, after.equipment, equipment)
        selections(before.tastes, after.tastes, tastes)
        if (after.energy != null && after.energy != before.energy && after.energy !in energies)
            preferenceFailure(KitchenFailureCode.INPUT_INVALID)
        if (after.servings != null && (before.servings == null || after.servings.compareTo(before.servings) != 0) &&
            (after.servings < minimumDefaultServings || after.servings > maximumDefaultServings))
            preferenceFailure(KitchenFailureCode.INPUT_INVALID)
        consent.validate(before.consent, after.consent)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Preference validation interrupted")
    }

    private class Fields(val diets: List<String>, val equipment: List<String>, val tastes: List<String>,
        val energy: String?, val servings: BigDecimal?, val consent: String?)

    private fun fields(value: JsonObject, failure: KitchenFailureCode): Fields {
        fun text(name: String, maximum: Int): String? = value[name]?.let {
            val item = it as? JsonPrimitive ?: preferenceFailure(failure)
            if (!item.isString || !preferenceText(item.content, maximum)) preferenceFailure(failure)
            item.content
        }
        fun list(name: String): List<String> {
            val array = value[name] as? JsonArray ?: if (!value.containsKey(name)) return emptyList() else preferenceFailure(failure)
            if (array.size > 256) preferenceFailure(failure)
            return array.map {
                val item = it as? JsonPrimitive ?: preferenceFailure(failure)
                if (!item.isString || !preferenceText(item.content, 128)) preferenceFailure(failure)
                item.content
            }
        }
        val servings = value["defaultServings"]?.let {
            val item = it as? JsonPrimitive ?: preferenceFailure(failure)
            if (item.isString || item.content.length > 128) preferenceFailure(failure)
            val number = item.content.toBigDecimalOrNull() ?: preferenceFailure(failure)
            if (!boundedNumber(number) || number < BigDecimal("0.1")) preferenceFailure(failure)
            number
        }
        return Fields(list("dietaryPatterns"), list("equipmentIds"), list("preferredTasteTags"),
            text("defaultEnergy", 128), servings, text("consentVersion", 256))
    }

    private fun selections(previous: List<String>, proposed: List<String>, supported: Set<String>) {
        val retained = previous.toSet()
        if (proposed.any { it !in retained && it !in supported }) preferenceFailure(KitchenFailureCode.INPUT_INVALID)
    }

    private fun document(value: JsonObject, schema: String, failure: KitchenFailureCode) {
        val bytes = try { value.toString().encodeToByteArray(throwOnInvalidSequence = true) }
            catch (_: IllegalArgumentException) { preferenceFailure(failure) }
            catch (_: CharacterCodingException) { preferenceFailure(failure) }
        if (bytes.size > 262144 || validator.validateSchema(schema, bytes) != BodyValidationResult.Valid) preferenceFailure(failure)
    }

    override fun toString() = "ConfiguredIngredientPreferencePolicy(<redacted>)"

    private companion object {
        val REQUIRED = setOf("hardExcludedIngredientIds", "dietaryPatterns", "dislikedIngredientIds", "equipmentIds")
        val TASTES = setOf("crunch", "fresh", "creamy", "heat")
        val ENERGIES = setOf("assemble", "little", "happy")
        val validator by lazy { ContractBodyValidator.bundled() }
        fun choices(values: Set<String>): Set<String> {
            preferenceConfiguration(values.size <= 256 && values.all { preferenceText(it, 128) })
            return values.toSet()
        }
        fun boundedNumber(value: BigDecimal) = value.precision() <= 32 && value.scale() in -32..32
    }
}

private fun preferenceText(value: String, maximum: Int): Boolean {
    if (value.length !in 1..maximum || value.isBlank() || value.any(Char::isISOControl)) return false
    return try { value.encodeToByteArray(throwOnInvalidSequence = true); true }
        catch (_: IllegalArgumentException) { false }
        catch (_: CharacterCodingException) { false }
}
private fun preferenceConfiguration(valid: Boolean) {
    require(valid) { "Invalid governed preference configuration" }
}
private fun preferenceFailure(code: KitchenFailureCode): Nothing = throw KitchenFailure(code)
