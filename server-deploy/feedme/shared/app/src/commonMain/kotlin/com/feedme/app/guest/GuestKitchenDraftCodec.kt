package com.feedme.app.guest

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireKind
import com.feedme.contracts.WireLimits
import kotlinx.serialization.json.*

/** Bounded local format only, unrelated to server requests, principal IDs or recipe evidence.
 * v1 loads without inferring explicit effort or rewriting storage. Only an actual save emits
 * v2; missing explicit fields remain unconfirmed, distinct from an explicit unlimited choice. */
object GuestKitchenDraftCodec {
    const val MAX_ENCODED_BYTES = 8192
    private val fields = setOf("version", "ingredientsText", "minutes", "energy", "cleanup")
    private val explicitFields = setOf("preparation", "cleanupLimit", "servings")

    fun encode(draft: GuestKitchenDraft): ByteArray = buildJsonObject {
        put("version", 2)
        put("ingredientsText", draft.ingredientsText)
        put("minutes", draft.minutes)
        put("energy", draft.energy.name)
        put("cleanup", draft.cleanup.name)
        draft.preparation?.let { put("preparation", it.name) }
        draft.cleanupLimit?.let { put("cleanupLimit", it.name) }
        draft.servings?.let { put("servings", it) }
    }.toString().encodeToByteArray().also { require(it.size <= MAX_ENCODED_BYTES) { "Guest draft is invalid" } }

    fun decode(bytes: ByteArray): GuestDraftLoad = try {
        require(bytes.size <= MAX_ENCODED_BYTES)
        val original = bytes.decodeToString(throwOnInvalidSequence = true)
        val wire = WireDocument.parse(original, WireLimits(MAX_ENCODED_BYTES, 4, 16))
        require(wire.kind == WireKind.OBJECT)
        val root = Json.parseToJsonElement(original) as JsonObject
        val version = root["version"] as? JsonPrimitive
        require(version != null && !version.isString && version.content.matches(Regex("[1-9][0-9]*")))
        val number = version.content.toLongOrNull() ?: error("Invalid version")
        if (number > 2) GuestDraftLoad.FutureVersion else {
            require(number == 1L && root.keys == fields || number == 2L &&
                root.keys.containsAll(fields) && root.keys.all { it in fields || it in explicitFields })
            fun text(name: String): String = (root[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("Invalid field")
            fun integer(name: String): Int {
                val value = root[name] as? JsonPrimitive
                require(value != null && !value.isString && value.content.matches(Regex("[1-9][0-9]*")))
                return value.content.toInt()
            }
            GuestDraftLoad.Loaded(GuestKitchenDraft(text("ingredientsText"), integer("minutes"),
                GuestEnergy.valueOf(text("energy")), GuestCleanup.valueOf(text("cleanup")),
                preparation = if (root.containsKey("preparation")) GuestPreparation.valueOf(text("preparation")) else null,
                cleanupLimit = if (root.containsKey("cleanupLimit")) GuestCleanupLimit.valueOf(text("cleanupLimit")) else null,
                servings = if (root.containsKey("servings")) integer("servings") else null))
        }
    } catch (_: Exception) { GuestDraftLoad.Corrupt }
}
