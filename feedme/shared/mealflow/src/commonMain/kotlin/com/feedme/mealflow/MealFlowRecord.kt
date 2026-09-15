package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.serialization.json.*

internal class FlowPlan(val body: WireDocument, val etag: String?, val received: Long, val request: WireDocument) {
    fun snapshot(historical: Boolean) = MealPlanSnapshot(PlanWire.from(body), etag, received, historical)
}
internal class FlowCommand(val id: String, val operation: String, val body: WireDocument,
    val manual: WireDocument, val preferences: WireDocument, val parentId: String?,
    val created: Long, val retryAt: Long, val dispatches: Long = 0) {
    fun call() = ApiCall(operation, parentId?.let { mapOf("planId" to it) }.orEmpty(),
        body = PrivateBytes(body.encodeUtf8()), idempotencyKey = SecretText(id))
    fun withRetryAt(time: Long) = FlowCommand(id, operation, body, manual, preferences, parentId, created, time, dispatches)
    fun admitted() = FlowCommand(id, operation, body, manual, preferences, parentId, created, retryAt,
        if (dispatches == Long.MAX_VALUE) mealFail(FailureReason.STORAGE_FAILURE) else dispatches + 1)
}
internal data class FlowIssuedId(val id: String, val created: Long) {
    override fun toString() = "FlowIssuedId(<redacted>)"
}
internal data class FlowRecord(
    val updated: Long, val lastClock: Long, val draft: ManualMealDraft? = null,
    val preferences: WireDocument? = null, val pantry: WireDocument? = null,
    val plans: List<FlowPlan> = emptyList(), val selected: Int = -1,
    val command: FlowCommand? = null, val usedIds: List<FlowIssuedId> = emptyList(),
    val recalledVersions: List<String> = emptyList(),
) {
    override fun toString() = "FlowRecord(<redacted>)"
}
internal class FlowEntry(val record: PrivateRecord?, val data: FlowRecord)

/** One bounded encrypted private-store payload. Wire bodies are retained as exact UTF-8 strings,
 * never serialized through a partial DTO, and revalidated when restored. No tokens or secrets. */
internal class MealFlowCodec(private val builder: MealRequestBuilder, private val origin: String) {
    fun encode(value: FlowRecord): PrivateBytes {
        return PrivateBytes(serialized(value)).also { decode(it) }
    }
    fun fits(value: FlowRecord) = serialized(value).size <= MAX_BYTES
    /** Reserve the full server StoredReply/V003 256KiB Plan bound, worst-case JSON-string
     * escaping, exact request, ETag and metadata BEFORE dispatch. Later draft/context edits
     * cannot consume that reservation while an unknown command remains. */
    fun replyFits(value: FlowRecord, request: WireDocument) =
        serialized(value.copy(command = null, plans = emptyList(), selected = -1, pantry = null)).size.toLong() +
            2L * request.encodeUtf8().size + 2L * MAX_PLAN_BYTES + 16_384 <= MAX_BYTES
    private fun serialized(value: FlowRecord): ByteArray {
        val objectValue = buildJsonObject {
            put("schema", 1); put("origin", origin); put("updated", value.updated); put("lastClock", value.lastClock)
            put("draft", value.draft?.let { text(builder.manual(it)) } ?: JsonNull)
            put("preferencesPendingSync", value.draft?.preferencesPendingSync ?: false)
            put("preferences", value.preferences?.let(::text) ?: JsonNull)
            put("pantry", value.pantry?.let(::text) ?: JsonNull)
            put("selected", value.selected)
            put("plans", JsonArray(value.plans.map { p -> buildJsonObject {
                put("body", text(p.body)); put("etag", p.etag?.let(::JsonPrimitive) ?: JsonNull)
                put("received", p.received); put("request", text(p.request))
            } }))
            put("command", value.command?.let { c -> buildJsonObject {
                put("id", c.id); put("operation", c.operation); put("body", text(c.body)); put("manual", text(c.manual))
                put("preferences", text(c.preferences)); put("parentId", c.parentId?.let(::JsonPrimitive) ?: JsonNull)
                put("created", c.created); put("retryAt", c.retryAt)
                put("dispatches", c.dispatches)
            } } ?: JsonNull)
            put("usedIds", JsonArray(value.usedIds.map { buildJsonObject { put("id", it.id); put("created", it.created) } }))
            put("recalledVersions", JsonArray(value.recalledVersions.map(::JsonPrimitive)))
        }
        return objectValue.toString().encodeToByteArray()
    }
    fun decode(bytes: PrivateBytes): FlowRecord {
        val r = WireDocument.decode(bytes.copyForCodec(), WireLimits(maxBytes = MAX_BYTES, maxDepth = 24)).json().jsonObject
        keys(r, "schema", "origin", "updated", "lastClock", "draft", "preferencesPendingSync", "preferences", "pantry", "selected", "plans", "command", "usedIds", "recalledVersions")
        if (long(r.getValue("schema")) != 1L || string(r.getValue("origin")) != origin) mealFail(FailureReason.INVALID_DATA)
        val pending = boolean(r.getValue("preferencesPendingSync"))
        val draft = wireOrNull(r.getValue("draft"))?.let { builder.decodeManual(it, pending) }
        if (draft == null && pending) mealFail(FailureReason.INVALID_DATA)
        val preferences = wireOrNull(r.getValue("preferences"))?.also { builder.schema("Preference", it) }
        val pantry = wireOrNull(r.getValue("pantry"))?.also { builder.schema("PantryItemPage", it) }
        val plans = r.getValue("plans").jsonArray.map { item -> item.jsonObject.let { p ->
            keys(p, "body", "etag", "received", "request")
            val body = wire(p.getValue("body"), MAX_PLAN_BYTES).also { builder.schema("Plan", it) }
            val request = wire(p.getValue("request")).also { builder.schema("PlanRequest", it) }
            FlowPlan(body, nullableString(p.getValue("etag")), long(p.getValue("received")), request)
        } }
        if (plans.size > 20 || plans.map { PlanWire.from(it.body).id.value.lowercase() }.distinct().size != plans.size) mealFail(FailureReason.INVALID_DATA)
        val selected = r.getValue("selected").jsonPrimitive.let {
            if (it.isString || !it.content.matches(Regex("-1|0|[1-9][0-9]*"))) mealFail(FailureReason.INVALID_DATA)
            it.content.toIntOrNull() ?: mealFail(FailureReason.INVALID_DATA)
        }
        if (selected !in -1 until plans.size || (plans.isNotEmpty() && selected == -1)) mealFail(FailureReason.INVALID_DATA)
        val command = r.getValue("command").takeUnless { it == JsonNull }?.jsonObject?.let { c ->
            keys(c, "id", "operation", "body", "manual", "preferences", "parentId", "created", "retryAt", "dispatches")
            val operation = string(c.getValue("operation")); val parent = nullableString(c.getValue("parentId"))
            if (operation !in setOf("createPlan", "nextPlan") || (operation == "createPlan") != (parent == null)) mealFail(FailureReason.INVALID_DATA)
            parent?.let { PlanId(it) }
            val body = wire(c.getValue("body")).also { builder.schema(if (operation == "createPlan") "PlanRequest" else "AdaptRequest", it) }
            val manual = wire(c.getValue("manual")).also { builder.decodeManual(it, false) }
            val pref = wire(c.getValue("preferences")).also { builder.schema("Preference", it) }
            FlowCommand(uuid(string(c.getValue("id"))), operation, body, manual, pref, parent,
                long(c.getValue("created")), long(c.getValue("retryAt")), long(c.getValue("dispatches")))
        }
        val used = r.getValue("usedIds").jsonArray.map { item -> item.jsonObject.let {
            keys(it, "id", "created"); FlowIssuedId(uuid(string(it.getValue("id"))), long(it.getValue("created")))
        } }
        if (used.size > 4096 || used.map { it.id }.distinct().size != used.size ||
            (command != null && used.none { it.id == command.id && it.created == command.created })) mealFail(FailureReason.INVALID_DATA)
        val recalled = r.getValue("recalledVersions").jsonArray.map { uuid(string(it)) }
        if (recalled.size > 128 || recalled.distinct().size != recalled.size) mealFail(FailureReason.INVALID_DATA)
        return FlowRecord(long(r.getValue("updated")), long(r.getValue("lastClock")), draft, preferences, pantry, plans, selected, command, used, recalled)
    }
    private fun text(value: WireDocument) = JsonPrimitive(value.encodeUtf8().decodeToString())
    private fun wire(value: JsonElement, maxBytes: Int = 131_072) = WireDocument.parse(string(value), WireLimits(maxBytes = maxBytes))
    private fun wireOrNull(value: JsonElement) = if (value == JsonNull) null else wire(value)
    companion object { const val MAX_BYTES = 1_048_576; const val MAX_PLAN_BYTES = 262_144 }
}

internal fun keys(value: JsonObject, vararg names: String) { if (value.keys != names.toSet()) mealFail(FailureReason.INVALID_DATA) }
internal fun string(value: JsonElement): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: mealFail(FailureReason.INVALID_DATA)
internal fun nullableString(value: JsonElement) = if (value == JsonNull) null else string(value)
internal fun boolean(value: JsonElement) = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: mealFail(FailureReason.INVALID_DATA)
internal fun long(value: JsonElement): Long {
    val primitive = value as? JsonPrimitive ?: mealFail(FailureReason.INVALID_DATA)
    if (primitive.isString || !primitive.content.matches(Regex("0|[1-9][0-9]*"))) mealFail(FailureReason.INVALID_DATA)
    return primitive.content.toLongOrNull() ?: mealFail(FailureReason.INVALID_DATA)
}
internal fun uuid(value: String) = value.also { PlanId(it) }.lowercase()
internal fun equal(a: WireDocument, b: WireDocument) = a.encodeUtf8().contentEquals(b.encodeUtf8())
