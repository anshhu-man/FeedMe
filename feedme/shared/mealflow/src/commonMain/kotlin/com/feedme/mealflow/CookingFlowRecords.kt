package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.serialization.json.*

internal enum class CookingStartStage { PREPARED, QUEUED, ATTACHED }
internal class CookingStart(val id: String, val plan: WireDocument, val preferences: WireDocument,
    val sourceRevision: Long, val created: Long, val stage: CookingStartStage,
    val receipt: WireDocument? = null) {
    val planId get() = uuid(PlanWire.from(plan).id.value)
    fun body() = doc(buildJsonObject { put("planId", planId); put("deviceSequence", 0) })
    fun call() = ApiCall("createCookSession", body = PrivateBytes(body().encodeUtf8()), idempotencyKey = SecretText(id))
    fun queued() = CookingStart(id, plan, preferences, sourceRevision, created, CookingStartStage.QUEUED)
    fun attached(receipt: WireDocument) = CookingStart(id, plan, preferences, sourceRevision, created, CookingStartStage.ATTACHED, receipt)
    override fun toString() = "CookingStart(stage=$stage, details=<redacted>)"
}
internal data class CookingFlowRecord(val clock: Long, val screen: CookingFlowScreen = CookingFlowScreen.RECIPE,
    val selected: String? = null, val start: CookingStart? = null, val recalled: List<String> = emptyList()) {
    override fun toString() = "CookingFlowRecord(<redacted>)"
}
internal class CookingFlowEntry(val record: PrivateRecord?, val value: CookingFlowRecord)
internal class CookingStartFinalization(val start: CookingStart, val payload: PrivateBytes,
    val priorDomainRevision: Long, val priorReceiptRevision: Long, val attempts: Int) {
    // The real queue batch supplies this exact archive, never reconstructed from CommandView.
    var archive: StoreMutation.Put? = null
    override fun toString() = "CookingStartFinalization(<redacted>)"
}

/** Exact canonical wire bodies under one independent encrypted owner/origin record. */
internal class CookingFlowCodec(private val origin: String, private val policy: CookingFlowPolicy) {
    private val validator = CanonicalBodyValidator.bundled()
    fun encode(value: CookingFlowRecord): PrivateBytes = PrivateBytes(buildJsonObject {
        put("schema", 1); put("origin", origin); put("clock", value.clock); put("screen", value.screen.name)
        put("selected", value.selected?.let(::JsonPrimitive) ?: JsonNull)
        put("recalled", JsonArray(value.recalled.map(::JsonPrimitive)))
        put("start", value.start?.let { start -> buildJsonObject {
            put("id", start.id); put("plan", text(start.plan)); put("preferences", text(start.preferences))
            put("sourceRevision", start.sourceRevision); put("created", start.created); put("stage", start.stage.name)
            put("receipt", start.receipt?.let(::text) ?: JsonNull)
        } } ?: JsonNull)
    }.toString().encodeToByteArray()).also { decode(it) }
    fun decode(bytes: PrivateBytes): CookingFlowRecord {
        val raw = WireDocument.decode(bytes.copyForCodec(), WireLimits(MAX_BYTES, 24)).json().jsonObject
        keys(raw, "schema", "origin", "clock", "screen", "selected", "recalled", "start")
        if (long(raw.getValue("schema")) != 1L || string(raw.getValue("origin")) != origin) mealFail(FailureReason.INVALID_DATA)
        val screen = CookingFlowScreen.entries.singleOrNull { it.name == string(raw.getValue("screen")) } ?: mealFail(FailureReason.INVALID_DATA)
        val selected = nullableString(raw.getValue("selected"))?.let(::uuid)
        val recalled = raw.getValue("recalled").jsonArray.map { uuid(string(it)) }
        if (recalled.size > 128 || recalled.distinct().size != recalled.size) mealFail(FailureReason.INVALID_DATA)
        val start = raw.getValue("start").takeUnless { it == JsonNull }?.jsonObject?.let {
            keys(it, "id", "plan", "preferences", "sourceRevision", "created", "stage", "receipt")
            val stage = CookingStartStage.entries.singleOrNull { stage -> stage.name == string(it.getValue("stage")) } ?: mealFail(FailureReason.INVALID_DATA)
            val receipt = nullableString(it.getValue("receipt"))?.let { text -> parse(text, "CookSession", policy.maxSessionBytes) }
            if ((stage == CookingStartStage.ATTACHED) != (receipt != null)) mealFail(FailureReason.INVALID_DATA)
            val plan = parse(string(it.getValue("plan")), "Plan", MealFlowCodec.MAX_PLAN_BYTES)
            if (receipt != null && uuid(CookSessionWire.from(receipt).planId.value) != uuid(PlanWire.from(plan).id.value)) mealFail(FailureReason.INVALID_DATA)
            val revision = long(it.getValue("sourceRevision")); if (revision == 0L) mealFail(FailureReason.INVALID_DATA)
            CookingStart(uuid(string(it.getValue("id"))), plan, parse(string(it.getValue("preferences")), "Preference", policy.maxPreferencesBytes),
                revision, long(it.getValue("created")), stage, receipt)
        }
        return CookingFlowRecord(long(raw.getValue("clock")), screen, selected, start, recalled)
    }
    fun schema(name: String, body: WireDocument) {
        if (validator.validateSchema(name, body.encodeUtf8()) != ContractValidationResult.Valid) mealFail(FailureReason.INVALID_DATA)
    }
    fun reserve(value: CookingFlowRecord) {
        val bytes = encode(value).copyForCodec().size
        if (value.start?.stage != CookingStartStage.ATTACHED && bytes.toLong() + 2L * policy.maxSessionBytes + 1024 > MAX_BYTES)
            mealFail(FailureReason.RATE_LIMITED)
    }
    private fun parse(text: String, schemaName: String, limit: Int) = WireDocument.parse(text, WireLimits(limit, 20)).also { schema(schemaName, it) }
    private fun text(body: WireDocument) = JsonPrimitive(body.encodeUtf8().decodeToString())
    companion object { const val MAX_BYTES = 1_048_576 }
}
