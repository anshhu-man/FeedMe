package com.feedme.development.progress

import com.feedme.contracts.*
import com.feedme.core.ports.*
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/** Synthetic own-Plan copies only. No copy-rights, moderation, manifest or production cursor
 * authority is claimed. The enclosing scoped ledger atomically stores effects AND exact replies. */
internal object ProgressCookbookLedger {
    private const val MAX_IDENTITIES = 128 // Retained preview ledger capacity, never a product quota.
    // Canonical fixture value for synthetic content; the visible preview disclaimer is not
    // encoded as an invented enum member. This provides no live content-license authority.
    private const val LICENSE = "privateCopyOnly"
    private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val HASH_PATTERN = Regex("[0-9a-f]{64}")

    fun apply(state: JsonObject, call: ApiCall, now: String): Pair<JsonObject, ApiReply> {
        val saves = state.getValue("savedRecipes").jsonObject
        val latest = state.getValue("savedLatest").jsonObject
        fun owned(id: String): JsonObject = saves[id]?.takeUnless { it == JsonNull }?.jsonObject ?: previewFail(FailureReason.NOT_FOUND)
        return when (call.operationId) {
            "saveRecipe" -> {
                val request = Json.parseToJsonElement(checkNotNull(call.body).copyForCodec().decodeToString()).jsonObject
                if (request["markMakeAgain"] == JsonPrimitive(true) || request.keys.any { it !in setOf("planId", "title", "markMakeAgain") })
                    previewFail(FailureReason.NOT_CONFIGURED)
                val planId = request["planId"]?.jsonPrimitive?.content ?: previewFail(FailureReason.NOT_CONFIGURED)
                val plan = state.getValue("plans").jsonObject[planId]?.jsonObject ?: previewFail(FailureReason.NOT_FOUND)
                if (plan["status"] != JsonPrimitive("ready")) previewFail(FailureReason.CONFLICT)
                val snapshot = plan.getValue("recipeSnapshot").jsonObject
                if (snapshot["id"] != JsonPrimitive(ProgressCatalog.recipeVersionId)) previewFail(FailureReason.NOT_CONFIGURED)
                requireAvailable(state, snapshot)
                val fingerprint = digest(snapshot)
                val previousId = latest[fingerprint]?.jsonPrimitive?.content
                val previous = previousId?.let { saves[it]?.takeUnless { item -> item == JsonNull }?.jsonObject }
                if (previous != null) {
                    if (request["title"] != null && request["title"] != previous["title"]) previewFail(FailureReason.CONFLICT)
                    state to reply(previous, 201, "\"1\"")
                } else {
                    if (saves.size >= MAX_IDENTITIES) previewFail(FailureReason.RATE_LIMITED)
                    val id = UUID.randomUUID().toString()
                    val saved = buildJsonObject {
                        put("id", id); put("version", 1); put("createdAt", now); put("updatedAt", now)
                        put("title", request["title"] ?: snapshot.getValue("title")); put("snapshot", snapshot)
                        put("sourceType", "ownPlan"); put("recalled", false); put("contentLicense", LICENSE)
                    }
                    if (saved.toString().encodeToByteArray().size + 4096 > ProgressCatalog.maxResponseBytes) previewFail(FailureReason.RATE_LIMITED)
                    JsonObject(state + mapOf("savedRecipes" to JsonObject(saves + (id to saved)),
                        "savedLatest" to JsonObject(latest + (fingerprint to JsonPrimitive(id))))) to reply(saved, 201, "\"1\"")
                }
            }
            "getSavedRecipe" -> {
                val saved = owned(call.pathParameters.getValue("savedRecipeId"))
                requireAvailable(state, saved.getValue("snapshot").jsonObject)
                state to reply(saved, 200, "\"1\"")
            }
            "deleteSavedRecipe" -> {
                val id = call.pathParameters.getValue("savedRecipeId"); owned(id)
                if (call.ifMatch != "\"1\"") throw PreviewVersionConflict()
                // Keep the exact identity tombstone and latest-lineage marker. A later re-save
                // gets another ID and invalidates old save/delete replay witnesses.
                JsonObject(state + ("savedRecipes" to JsonObject(saves + (id to JsonNull)))) to ApiReply(204, null)
            }
            "listSavedRecipes" -> state to page(state, call, now)
            else -> previewFail(FailureReason.NOT_CONFIGURED)
        }
    }

    fun witness(state: JsonObject, id: String): JsonObject = buildJsonObject {
        put("item", state.getValue("savedRecipes").jsonObject[id] ?: JsonNull)
        put("lineage", JsonObject(state.getValue("savedLatest").jsonObject.filterValues { it.jsonPrimitive.content == id }))
    }

    fun isWithdrawn(state: JsonObject, snapshot: JsonObject): Boolean =
        snapshot.getValue("id").jsonPrimitive.content in state.getValue("withdrawnRecipeVersions").jsonObject

    fun requireAvailable(state: JsonObject, snapshot: JsonObject) {
        if (isWithdrawn(state, snapshot)) throw PreviewRecipeRecalled()
    }

    fun validate(state: JsonObject, validator: CanonicalBodyValidator) {
        val saves = state.getValue("savedRecipes").jsonObject; val latest = state.getValue("savedLatest").jsonObject
        if (saves.size > MAX_IDENTITIES || latest.size > MAX_IDENTITIES) previewFail(FailureReason.STORAGE_FAILURE)
        for ((id, item) in saves) {
            if (!UUID_PATTERN.matches(id)) previewFail(FailureReason.STORAGE_FAILURE)
            if (item == JsonNull) continue
            val body = item.jsonObject
            if (body["id"] != JsonPrimitive(id) || body["version"] != JsonPrimitive(1) || body["sourceType"] != JsonPrimitive("ownPlan") ||
                body["contentLicense"] != JsonPrimitive(LICENSE) || body["recalled"] != JsonPrimitive(false) ||
                validator.validateSchema("SavedRecipe", body.toString().encodeToByteArray()) != ContractValidationResult.Valid ||
                latest[digest(body.getValue("snapshot"))] != JsonPrimitive(id)) previewFail(FailureReason.STORAGE_FAILURE)
        }
        for ((fingerprint, item) in latest) {
            val id = item.jsonPrimitive.content
            if (!HASH_PATTERN.matches(fingerprint) || !UUID_PATTERN.matches(id) || id !in saves ||
                (saves[id] != JsonNull && digest(saves.getValue(id).jsonObject.getValue("snapshot")) != fingerprint)) previewFail(FailureReason.STORAGE_FAILURE)
        }
    }

    private fun page(state: JsonObject, call: ApiCall, now: String): ApiReply {
        val q = call.queryParameters["q"]?.single()
        val limit = call.queryParameters["limit"]?.single()?.toInt() ?: 20
        val saves = state.getValue("savedRecipes").jsonObject
        // The canonical page cannot represent content-free owned removal summaries. Never leak
        // recalled titles/instructions or return a filtered partial page as a complete result.
        saves.values.filter { it != JsonNull }.forEach { requireAvailable(state, it.jsonObject.getValue("snapshot").jsonObject) }
        val view = digest(JsonObject(saves.toSortedMap()))
        val scope = buildJsonObject { put("origin", state.getValue("origin")); put("q", q?.let(::JsonPrimitive) ?: JsonNull)
            put("limit", limit); put("view", view) }
        val items = saves.filterValues { it != JsonNull && (q == null || q.lowercase() in it.jsonObject.getValue("title").jsonPrimitive.content.lowercase()) }.toSortedMap()
        fun cursor(id: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(JsonObject(scope + ("after" to JsonPrimitive(id))).toString().encodeToByteArray())
        val after = call.queryParameters["cursor"]?.single()?.let { token ->
            val parsed = try { WireDocument.decode(Base64.getUrlDecoder().decode(token), WireLimits(maxBytes = 1536, maxDepth = 4)).let {
                Json.parseToJsonElement(it.encodeUtf8().decodeToString()).jsonObject } }
            catch (_: Exception) { previewFail(FailureReason.INVALID_DATA) }
            if (parsed.keys != scope.keys + "after" || JsonObject(parsed - "after") != scope) previewFail(FailureReason.CONFLICT)
            val id = parsed["after"]?.jsonPrimitive?.content ?: previewFail(FailureReason.INVALID_DATA)
            if (id !in items || cursor(id) != token) previewFail(FailureReason.INVALID_DATA)
            id
        }
        val remaining = items.filterKeys { after == null || it > after }.entries.toList()
        val page = mutableListOf<JsonElement>(); var next: String? = null
        fun envelope() = buildJsonObject { put("items", JsonArray(page)); put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", now) }
        for ((index, entry) in remaining.take(limit).withIndex()) {
            val priorNext = next
            page += entry.value; next = if (index + 1 < remaining.size) cursor(entry.key) else null
            if (envelope().toString().encodeToByteArray().size > ProgressCatalog.maxResponseBytes) {
                page.removeAt(page.lastIndex); next = priorNext
                if (page.isEmpty()) previewFail(FailureReason.RATE_LIMITED)
                break
            }
        }
        return reply(envelope())
    }
    // These preview tokens bind the exact owned snapshot/filter; they are not a replacement for
    // the production server's signed/expiring cursors. They grant no identity or copy authority.
    private fun digest(value: JsonElement) = MessageDigest.getInstance("SHA-256").digest(value.toString().encodeToByteArray()).joinToString("") { "%02x".format(it) }
    private fun reply(body: JsonObject, status: Int = 200, etag: String? = null) = ApiReply(status,
        PrivateBytes(WireDocument.parse(body.toString()).encodeUtf8()), etag, contentType = "application/json")
}
