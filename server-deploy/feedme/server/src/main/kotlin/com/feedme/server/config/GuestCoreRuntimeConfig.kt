package com.feedme.server.config

import com.feedme.planning.PlanningPolicy
import com.feedme.planning.PlanningScanBudget
import com.feedme.server.guest.GuestPlanCharge
import com.feedme.server.guest.GuestPlanWindow
import com.feedme.server.guest.GuestPlanningPolicy
import com.feedme.server.guest.GuestReplayCipher
import com.feedme.server.guest.GuestSessionPolicy
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Optional guest serving policy for the configured account-core listener. Absence keeps every
 * guest route uncomposed. Presence still supplies no catalog content, migration, deployment or
 * launch claim: the runtime must construct the exact DB-backed stores and pass compatibility.
 * Replay encryption has a separate explicit key ring; no account/database/provider secret is
 * reused. Only complete, already implemented operation groups can be advertised here.
 */
internal class GuestCoreRuntimeConfig private constructor(
    val sessions: GuestSessionPolicy,
    val newSessionsEnabled: Boolean,
    val bootstrapReplayEnabled: Boolean,
    val maxSearchResponseBytes: Int,
    val kitchenEnabled: Boolean,
    val cookingEnabled: Boolean,
    val savedEnabled: Boolean,
    val feedbackEnabled: Boolean,
    val planning: GuestPlanningPolicy?,
    private val replayKeys: GuestReplayKeyRing,
) {
    fun replayCipher(): GuestReplayCipher = replayKeys.open()
    override fun toString() = "GuestCoreRuntimeConfig(<redacted>)"

    companion object {
        const val REPLAY_KEYS_ENV = "FEEDME_GUEST_REPLAY_KEYS"
        val ENVIRONMENT_KEYS: Set<String> = setOf(REPLAY_KEYS_ENV)

        fun from(policyElement: JsonElement?, environment: Map<String, String>): GuestCoreRuntimeConfig? {
            val rawKeys = environment[REPLAY_KEYS_ENV]
            if (policyElement == null || policyElement == JsonNull) {
                require(rawKeys == null) { "Guest replay keys require a guest policy" }
                return null
            }
            val policy = policyElement.jsonObject
            exact(policy, "revision", "newSessionsEnabled", "bootstrapReplayEnabled",
                "idleLifetimeSeconds", "absoluteLifetimeSeconds", "replayLifetimeSeconds",
                "dailyPlanLimit", "maxActivePerInstallation", "maxIssuedPerInstallationPerDay",
                "capabilities", "maxSearchResponseBytes", "planning")
            val planning = policy.getValue("planning").takeUnless { it == JsonNull }?.jsonObject?.let(::planning)
            val capabilities = strings(policy, "capabilities", 32, 128).also {
                require(it.distinct().size == it.size)
            }.toSet()
            val kitchen = setOf("getPreferences", "updatePreferences", "listPantry",
                "upsertPantryItem", "removePantryItem")
            val kitchenEnabled = capabilities.any { it in kitchen }
            require(!kitchenEnabled || capabilities.containsAll(kitchen)) {
                "Guest kitchen capabilities must be enabled as one complete group"
            }
            val cooking = setOf("createCookSession", "getCookSession", "updateCookSession", "completeCookSession")
            val cookingEnabled = capabilities.any { it in cooking }
            require(!cookingEnabled || capabilities.containsAll(cooking)) {
                "Guest cooking capabilities must be enabled as one complete group"
            }
            require(!cookingEnabled || planning != null) { "Guest cooking requires guest planning" }
            val saved = setOf("saveRecipe", "getSavedRecipe", "listSavedRecipes",
                "deleteSavedRecipe", "listCollections", "getCollection")
            val savedEnabled = capabilities.any { it in saved }
            require(!savedEnabled || capabilities.containsAll(saved)) {
                "Guest saved-recipe capabilities must be enabled as one complete group"
            }
            require(!savedEnabled || planning != null) { "Guest saved recipes require guest planning" }
            val feedback = setOf("createFeedback", "updateFeedback", "deleteFeedback")
            val feedbackEnabled = capabilities.any { it in feedback }
            require(!feedbackEnabled || capabilities.containsAll(feedback)) {
                "Guest feedback capabilities must be enabled as one complete group"
            }
            require(!feedbackEnabled || planning != null) { "Guest feedback requires guest planning" }
            val implemented = setOf("searchIngredients") +
                (if (kitchenEnabled) kitchen else emptySet()) +
                (if (cookingEnabled) cooking else emptySet()) +
                (if (savedEnabled) saved else emptySet()) +
                (if (feedbackEnabled) feedback else emptySet()) +
                (if (planning == null) emptySet() else setOf("createPlan", "getPlan", "getPlanExplanation"))
            require(capabilities == implemented) { "Guest capabilities differ from the configured stores" }
            val sessions = GuestSessionPolicy(text(policy, "revision", 256),
                number(policy, "idleLifetimeSeconds", 1L..604800L),
                number(policy, "absoluteLifetimeSeconds", 1L..2592000L),
                number(policy, "replayLifetimeSeconds", 1L..604800L),
                integer(policy, "dailyPlanLimit", 1..10000),
                integer(policy, "maxActivePerInstallation", 1..16),
                integer(policy, "maxIssuedPerInstallationPerDay", 1..10000), capabilities)
            return GuestCoreRuntimeConfig(sessions, boolean(policy, "newSessionsEnabled"),
                boolean(policy, "bootstrapReplayEnabled"),
                integer(policy, "maxSearchResponseBytes", 1..262144), kitchenEnabled, cookingEnabled,
                savedEnabled, feedbackEnabled, planning,
                GuestReplayKeyRing.parse(requireNotNull(rawKeys) { "Guest replay keys are required" }))
        }

        private fun planning(value: JsonObject): GuestPlanningPolicy {
            exact(value, "revision", "rankingVersion", "heatEnabled", "improveEnabled",
                "relatedTasteExplicitlyRequested", "retentionSeconds", "cursorLifetimeSeconds",
                "maxCandidates", "maxPages", "pageSize", "window", "charge")
            val window = GuestPlanWindow.valueOf(text(value, "window", 64))
            val charge = GuestPlanCharge.valueOf(text(value, "charge", 64))
            return GuestPlanningPolicy(text(value, "revision", 128),
                PlanningPolicy(text(value, "rankingVersion", 128), boolean(value, "heatEnabled"),
                    boolean(value, "improveEnabled"), boolean(value, "relatedTasteExplicitlyRequested")),
                integer(value, "retentionSeconds", 60..2_592_000),
                integer(value, "cursorLifetimeSeconds", 1..600),
                PlanningScanBudget(number(value, "maxCandidates", 1L..1_000_000L),
                    number(value, "maxPages", 1L..100_000L)),
                integer(value, "pageSize", 1..32), window, charge)
        }

        private fun exact(value: JsonObject, vararg fields: String) = require(value.keys == fields.toSet())
        private fun text(value: JsonObject, field: String, max: Int): String =
            value.getValue(field).jsonPrimitive.let {
                require(it.isString && it.content.length in 1..max && !it.content.isBlank() &&
                    it.content.none(Char::isISOControl)); it.content
            }
        private fun number(value: JsonObject, field: String, range: LongRange): Long =
            value.getValue(field).jsonPrimitive.let {
                require(!it.isString && it.content.matches(Regex("0|[1-9][0-9]{0,18}")))
                it.content.toLong().also { parsed -> require(parsed in range) }
            }
        private fun integer(value: JsonObject, field: String, range: IntRange): Int =
            number(value, field, range.first.toLong()..range.last.toLong()).toInt()
        private fun boolean(value: JsonObject, field: String): Boolean =
            value.getValue(field).jsonPrimitive.let { require(!it.isString); it.boolean }
        private fun strings(value: JsonObject, field: String, max: Int, length: Int): List<String> =
            value.getValue(field).jsonArray.also { require(it.size in 1..max) }.map {
                it.jsonPrimitive.let { item -> require(item.isString && item.content.length in 1..length &&
                    !item.content.isBlank() && item.content.none(Char::isISOControl)); item.content }
            }
    }
}

/** Parsed once from the dedicated bounded environment document. It owns only detached copies;
 * each assembly receives a further detached cipher and closes it with the listener lifetime.
 */
private class GuestReplayKeyRing private constructor(
    private val currentKeyId: String,
    keys: Map<String, ByteArray>,
) {
    private val values = keys.mapValues { it.value.copyOf() }
    private var consumed = false
    @Synchronized fun open(): GuestReplayCipher {
        require(!consumed) { "Guest replay keys already consumed" }
        consumed = true
        return try { GuestReplayCipher(currentKeyId, values) }
        finally { values.values.forEach { it.fill(0) } }
    }

    companion object {
        fun parse(raw: String): GuestReplayKeyRing {
            require(raw.length in 1..8192)
            // Reuse the canonical strict document reader so duplicate fields, invalid UTF-8
            // representations and over-complex key documents cannot be normalized into use.
            val root = AccountCoreRuntimeConfig.document(raw, 8192)
            require(root.keys == setOf("currentKeyId", "keys"))
            val current = root.getValue("currentKeyId").jsonPrimitive.let {
                require(it.isString && it.content.matches(Regex("[a-z0-9_-]{1,32}"))); it.content
            }
            val encoded = root.getValue("keys").jsonObject
            require(encoded.size in 1..8 && current in encoded &&
                encoded.keys.all { it.matches(Regex("[a-z0-9_-]{1,32}")) })
            val decoded = linkedMapOf<String, ByteArray>()
            try {
                for ((id, value) in encoded) {
                    val text = value.jsonPrimitive.let { require(it.isString); it.content }
                    require(text.matches(Regex("[A-Za-z0-9_-]{43}")))
                    val bytes = Base64.getUrlDecoder().decode(text)
                    require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == text)
                    decoded[id] = bytes
                }
                return GuestReplayKeyRing(current, decoded)
            } finally { decoded.values.forEach { it.fill(0) } }
        }
    }
}
