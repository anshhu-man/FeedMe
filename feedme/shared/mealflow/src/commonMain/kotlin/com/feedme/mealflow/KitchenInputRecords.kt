package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.serialization.json.*

internal data class KitchenInputRecord(val clock: Long, val preferences: WireDocument? = null,
    val preferenceEtag: String? = null, val preferenceDraft: WireDocument? = null,
    val pantry: List<WireDocument> = emptyList(), val pantryDrafts: List<WireDocument> = emptyList(),
    val commands: List<KitchenInputCommand> = emptyList()) {
    fun pendingPreferences() = preferenceDraft != null || commands.any { it.operation == "updatePreferences" }
    override fun toString() = "KitchenInputRecord(<redacted>)"
}
internal class KitchenInputCommand(val id: String, val operation: String, val target: String?,
    val body: WireDocument?, val etag: String?, val base: WireDocument?) {
    fun call() = ApiCall(operation, target?.takeIf { operation == "removePantryItem" }?.let { mapOf("ingredientId" to it) }.orEmpty(),
        body = body?.let { PrivateBytes(it.encodeUtf8()) }, idempotencyKey = SecretText(id), ifMatch = etag)
    override fun toString() = "KitchenInputCommand(operation=$operation, details=<redacted>)"
}
internal class KitchenInputEntry(val record: PrivateRecord?, val value: KitchenInputRecord)
internal class KitchenInputFinalization(val command: KitchenInputCommand, val payload: PrivateBytes,
    val priorDomainRevision: Long, val priorReceiptRevision: Long, val attempts: Int) {
    // Captured from the queue's actual atomic archive batch, not reconstructed journal JSON.
    var archive: StoreMutation.Put? = null
    override fun toString() = "KitchenInputFinalization(<redacted>)"
}

internal class KitchenInputCodec(private val origin: String) {
    private val validator = CanonicalBodyValidator.bundled()
    fun encode(value: KitchenInputRecord): PrivateBytes = PrivateBytes(buildJsonObject {
        put("schema", 1); put("origin", origin); put("clock", value.clock)
        put("preferences", text(value.preferences)); put("preferenceEtag", value.preferenceEtag?.let(::JsonPrimitive) ?: JsonNull)
        put("preferenceDraft", text(value.preferenceDraft))
        put("pantry", JsonArray(value.pantry.map(::text))); put("pantryDrafts", JsonArray(value.pantryDrafts.map(::text)))
        put("commands", JsonArray(value.commands.map { command -> buildJsonObject {
            put("id", command.id); put("operation", command.operation); put("target", command.target?.let(::JsonPrimitive) ?: JsonNull)
            put("body", text(command.body)); put("etag", command.etag?.let(::JsonPrimitive) ?: JsonNull); put("base", text(command.base))
        } }))
    }.toString().encodeToByteArray()).also { decode(it) }

    fun decode(bytes: PrivateBytes): KitchenInputRecord {
        val root = WireDocument.decode(bytes.copyForCodec(), WireLimits(1_048_576, 24)).json().jsonObject
        keys(root, "schema", "origin", "clock", "preferences", "preferenceEtag", "preferenceDraft", "pantry", "pantryDrafts", "commands")
        if (long(root.getValue("schema")) != 1L || string(root.getValue("origin")) != origin) mealFail(FailureReason.INVALID_DATA)
        val pref = document(root.getValue("preferences"), "Preference")
        val tag = nullableString(root.getValue("preferenceEtag")); if (tag != null) kiEtag(pref ?: mealFail(FailureReason.INVALID_DATA), tag)
        val draft = document(root.getValue("preferenceDraft"), "PreferencePatch")?.also(::preferencePatch)
        val pantry = root.getValue("pantry").jsonArray.map { document(it, "PantryItem") ?: mealFail(FailureReason.INVALID_DATA) }
        val drafts = root.getValue("pantryDrafts").jsonArray.map { document(it, "PantryWrite")?.also(::pantryDraft) ?: mealFail(FailureReason.INVALID_DATA) }
        if (pantry.size > 512 || drafts.size > 128 || pantry.map(::kiIngredientId).distinct().size != pantry.size ||
            drafts.map(::kiIngredientId).distinct().size != drafts.size) mealFail(FailureReason.INVALID_DATA)
        val commands = root.getValue("commands").jsonArray.map { node -> node.jsonObject.let { raw ->
            keys(raw, "id", "operation", "target", "body", "etag", "base")
            val id = uuid(string(raw.getValue("id"))); val operation = string(raw.getValue("operation"))
            val target = nullableString(raw.getValue("target"))?.let(::uuid); val etag = nullableString(raw.getValue("etag"))
            val body = when (operation) {
                "updatePreferences" -> document(raw.getValue("body"), "PreferencePatch")?.also(::preferencePatch) ?: mealFail(FailureReason.INVALID_DATA)
                "upsertPantryItem" -> document(raw.getValue("body"), "PantryWrite") ?: mealFail(FailureReason.INVALID_DATA)
                "removePantryItem" -> { if (raw.getValue("body") != JsonNull) mealFail(FailureReason.INVALID_DATA); null }
                else -> mealFail(FailureReason.INVALID_DATA)
            }
            val base = document(raw.getValue("base"), if (operation == "updatePreferences") "Preference" else "PantryItem")
            when (operation) {
                "updatePreferences" -> {
                    if (target != null || base == null || etag == null) mealFail(FailureReason.INVALID_DATA)
                    kiEtag(base, etag)
                }
                "upsertPantryItem" -> {
                    if (target == null || target != kiIngredientId(body!!) || etag != null || body.json().jsonObject.keys.any { it in setOf("quantity", "unit") }) mealFail(FailureReason.INVALID_DATA)
                    val expected = body.json().jsonObject["expectedVersion"]
                    if ((base == null) != (expected == null) || (base != null && (kiIngredientId(base) != target ||
                                kiNumber(expected!!) != pickerVersion(base)))) mealFail(FailureReason.INVALID_DATA)
                }
                else -> {
                    if (target == null || base == null || kiIngredientId(base) != target || etag == null) mealFail(FailureReason.INVALID_DATA)
                    kiEtag(base, etag)
                }
            }
            KitchenInputCommand(id, operation, target, body, etag, base)
        } }
        if (commands.size > 128 || commands.map { it.id }.distinct().size != commands.size ||
            commands.map { if (it.operation == "updatePreferences") "preferences" else it.target!! }.distinct().size != commands.size)
            mealFail(FailureReason.INVALID_DATA)
        return KitchenInputRecord(long(root.getValue("clock")), pref, tag, draft, pantry, drafts, commands)
    }
    fun schema(name: String, document: WireDocument) {
        if (document.encodeUtf8().size > 262_144 || validator.validateSchema(name, document.encodeUtf8()) != ContractValidationResult.Valid)
            mealFail(FailureReason.INVALID_DATA)
        if (name == "Preference") selectionBounds(document.json().jsonObject)
    }
    fun preferencePatch(document: WireDocument) {
        schema("PreferencePatch", document)
        val objectValue = document.json().jsonObject
        // Consent provenance has its own explicit workflow; never issue it from this editor.
        if (objectValue.isEmpty() || "consentVersion" in objectValue) mealFail(FailureReason.INVALID_DATA)
        selectionBounds(objectValue)
    }
    fun pantryDraft(document: WireDocument) {
        schema("PantryWrite", document)
        if (document.json().jsonObject.keys.any { it in setOf("expectedVersion", "quantity", "unit") }) mealFail(FailureReason.INVALID_DATA)
    }
    private fun selectionBounds(value: JsonObject) {
        for ((key, child) in value) if (child is JsonArray) {
            if (child.size > 128) mealFail(FailureReason.INVALID_DATA)
            val strings = child.map { string(it).let { value -> if (key.endsWith("IngredientIds")) uuid(value) else value } }
            if (strings.distinct().size != strings.size || strings.any { it.length > 200 || it.any(Char::isISOControl) }) mealFail(FailureReason.INVALID_DATA)
        }
    }
    private fun document(value: JsonElement, schemaName: String): WireDocument? = if (value == JsonNull) null else
        WireDocument.parse(string(value), WireLimits(262_144, 16)).also { schema(schemaName, it) }
    private fun text(document: WireDocument?): JsonElement = document?.let { JsonPrimitive(it.encodeUtf8().decodeToString()) } ?: JsonNull
    private fun nullableString(value: JsonElement) = if (value == JsonNull) null else string(value)
}

internal fun kiIngredientId(document: WireDocument) = uuid(document.json().jsonObject.getValue("ingredientId").jsonPrimitive.content)
internal fun kiId(document: WireDocument) = uuid(document.json().jsonObject.getValue("id").jsonPrimitive.content)
internal fun kiEtag(document: WireDocument, tag: String) {
    if (tag.length !in 3..4098 || tag.first() != '"' || tag.last() != '"') mealFail(FailureReason.INVALID_DATA)
    val digits = tag.substring(1, tag.lastIndex)
    if (digits.any { it !in '0'..'9' } || digits.trimStart('0') != pickerVersion(document)) mealFail(FailureReason.INVALID_DATA)
}
/** Exact decimal normalization for canonical numeric comparisons, never Double rounding. */
internal fun kiNumber(value: JsonElement): String {
    val primitive = value.jsonPrimitive
    if (primitive.isString || primitive.content.length > 1000) mealFail(FailureReason.INVALID_DATA)
    val parts = primitive.content.lowercase().split('e'); val mantissa = parts[0]
    var digits = mantissa.replace(".", "").trimStart('0')
    var exponent = (parts.getOrNull(1)?.toIntOrNull() ?: if (parts.size == 1) 0 else mealFail(FailureReason.INVALID_DATA)) - mantissa.substringAfter('.', "").length
    if (exponent !in -1000..1000 || mantissa.startsWith('-')) mealFail(FailureReason.INVALID_DATA)
    if (digits.isEmpty()) return "0"
    while (digits.endsWith('0')) { digits = digits.dropLast(1); exponent++ }
    return if (exponent >= 0) digits + "0".repeat(exponent) else "$digits@$exponent"
}

/** Process-only fence complements the durable draft/journal record after failed writes. It has
 * no authority to discard pending data. The actual session's invalidation releases old memory. */
internal object KitchenInputPreferenceGuard {
    private class Fence(val lease: SessionLease, val origin: String) {
        var subscription: SessionInvalidationSubscription? = null
        var finalization: KitchenInputFinalization? = null
        var unacknowledgedDraft: WireDocument? = null
    }
    private val fences = mutableListOf<Fence>()
    fun block(lease: SessionLease, origin: String, boundary: SessionBoundary) {
        if (!blocked(lease, origin)) {
            val fence = Fence(lease, origin); fences += fence
            // This subscription belongs to the fence, not an editor which may close first.
            fence.subscription = boundary.onInvalidated(lease) { release(lease, origin) }
        }
    }
    fun blocked(lease: SessionLease, origin: String) = fences.any { it.lease === lease && it.origin == origin }
    fun finalization(lease: SessionLease, origin: String) = fences.singleOrNull { it.lease === lease && it.origin == origin }?.finalization
    fun draft(lease: SessionLease, origin: String) = fences.singleOrNull { it.lease === lease && it.origin == origin }?.unacknowledgedDraft
    fun retainDraft(lease: SessionLease, origin: String, boundary: SessionBoundary, draft: WireDocument) {
        block(lease, origin, boundary)
        fences.singleOrNull { it.lease === lease && it.origin == origin }?.unacknowledgedDraft = draft
    }
    fun clearDraft(lease: SessionLease, origin: String) {
        fences.singleOrNull { it.lease === lease && it.origin == origin }?.unacknowledgedDraft = null
    }
    fun retain(lease: SessionLease, origin: String, boundary: SessionBoundary, proof: KitchenInputFinalization) {
        block(lease, origin, boundary)
        fences.singleOrNull { it.lease === lease && it.origin == origin }?.finalization = proof
    }
    fun clearFinalization(lease: SessionLease, origin: String) {
        fences.singleOrNull { it.lease === lease && it.origin == origin }?.finalization = null
    }
    fun release(lease: SessionLease, origin: String) {
        val removed = fences.filter { it.lease === lease && it.origin == origin }
        fences.removeAll(removed.toSet()); removed.forEach { it.subscription?.close() }
    }
    fun key(origin: String) = RecordKey("mealflow.kitchen-inputs.v1", origin)
    suspend fun pending(access: AuthenticatedMealPlanningAccess): Boolean {
        if (blocked(access.lease, access.origin)) return true
        val stored = mealValue(access.store.read(access.lease.scope, key(access.origin)))
        if (blocked(access.lease, access.origin)) return true
        if (stored == null) return false
        if (stored.schemaVersion != 1) mealFail(FailureReason.INVALID_DATA)
        return KitchenInputCodec(access.origin).decode(stored.payload).pendingPreferences()
    }
}
