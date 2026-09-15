package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.kitchen.SavedRecipeDeletionEvidence
import kotlinx.serialization.json.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class CookbookCommand(val id: String, val operation: String, val body: WireDocument?,
    val plan: WireDocument?, val expected: WireDocument?, val etag: String?, val created: Long,
    val localDeletion: SavedRecipeDeletionEvidence? = null) {
    fun savedRecipeId(): String = localDeletion?.id ?: uuid(SavedRecipeWire.from(expected!!).id.value)
    fun call() = ApiCall(operation, pathParameters = if (operation == "deleteSavedRecipe")
        mapOf("savedRecipeId" to savedRecipeId()) else emptyMap(),
        body = body?.let { PrivateBytes(it.encodeUtf8()) }, idempotencyKey = SecretText(id), ifMatch = etag)
    fun request() = SaveRecipeRequest(planId = PlanId(PlanWire.from(plan!!).id.value),
        title = (body!!.field("title") as? WireField.Value)?.value?.stringOrNull())
    override fun toString() = "CookbookCommand(operation=$operation, details=<redacted>)"
}
internal data class CookbookRecord(val clock: Long, val command: CookbookCommand? = null,
    val resultId: String? = null, val completedCommand: String? = null) {
    override fun toString() = "CookbookRecord(<redacted>)"
}
internal class CookbookEntry(val record: PrivateRecord?, val value: CookbookRecord)
internal class CookbookFinalization(val command: CookbookCommand, val mutations: List<StoreMutation>,
    val receiptRevision: Long, val discarded: Boolean = false) {
    var archive: StoreMutation.Put? = null
    var delivery: CookbookDelivery? = null
    override fun toString() = "CookbookFinalization(<redacted>)"
}
/** One exact operation's final no-suspension delivery linearization. Navigation revokes pending
 * tickets synchronously. A delivered ticket cannot authorize another proof or publication. */
@OptIn(ExperimentalAtomicApi::class)
internal class CookbookDelivery(val lease: SessionLease, val operation: Any) {
    private sealed interface State
    private data object Pending : State
    private class Armed(val proof: CookbookFinalization?, val publication: Any) : State
    private class Delivered(val proof: CookbookFinalization?) : State
    private data object Revoked : State
    private val state = AtomicReference<State>(Pending)
    fun arm(proof: CookbookFinalization?, publication: Any): Boolean = state.compareAndSet(Pending, Armed(proof, publication))
    fun deliver(proof: CookbookFinalization?, publication: Any): Boolean {
        val expected = state.load() as? Armed ?: return false
        return expected.proof === proof && expected.publication === publication && state.compareAndSet(expected, Delivered(proof))
    }
    fun revoke() { while (true) {
        val old = state.load(); if (old is Delivered || old === Revoked || state.compareAndSet(old, Revoked)) return
    } }
    fun delivered(proof: CookbookFinalization) = (state.load() as? Delivered)?.proof === proof
    override fun toString() = "CookbookDelivery(<redacted>)"
}
@OptIn(ExperimentalAtomicApi::class)
internal object CookbookProofs {
    private class Held(val access: AuthenticatedMealPlanningAccess, val boundary: SessionBoundary, value: CookbookFinalization) {
        val proof = AtomicReference<CookbookFinalization?>(value)
        var subscription: SessionInvalidationSubscription? = null
        fun matches(other: AuthenticatedMealPlanningAccess, otherBoundary: SessionBoundary) =
            access.lease === other.lease && access.store === other.store && access.origin == other.origin && boundary === otherBoundary
    }
    private val held = AtomicReference<List<Held>>(emptyList())
    fun get(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary) = held.load().singleOrNull { it.matches(access, boundary) }?.proof?.load()
        ?.takeUnless { it.delivery?.delivered(it) == true }
    fun retain(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, proof: CookbookFinalization) {
        if (!boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
        while (true) {
            val old = held.load()
            old.singleOrNull { it.matches(access, boundary) }?.let { it.proof.store(proof); return }
            val item = Held(access, boundary, proof)
            if (!held.compareAndSet(old, old + item)) continue
            val subscription = boundary.onInvalidated(access.lease) { clear(access, boundary) }
            if (boundary.isCurrent(access.lease) && item in held.load()) item.subscription = subscription
            else { clear(access, boundary); subscription.close() }
            return
        }
    }
    /** Exact atomic housekeeping only. No callback/dispatcher mutations in the caller tail.
     * The now-empty holder remains bounded to this lease until its one-shot invalidation. */
    fun consume(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, proof: CookbookFinalization) {
        held.load().singleOrNull { it.matches(access, boundary) }?.proof?.compareAndSet(proof, null)
    }
    fun clear(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary) {
        while (true) {
            val old = held.load(); val removed = old.filter { it.matches(access, boundary) }
            if (removed.isEmpty()) return
            if (held.compareAndSet(old, old - removed.toSet())) { removed.forEach { it.subscription?.close() }; return }
        }
    }
}
internal class CookbookCodec(private val origin: String) {
    private val validator = CanonicalBodyValidator.bundled()
    // Old readable commands keep the exact schema-1 shape; only redacted local intent uses v2.
    fun schema(value: CookbookRecord): Int = if (value.command?.localDeletion != null) 2 else 1
    fun encode(value: CookbookRecord) = PrivateBytes(buildJsonObject {
        put("schema", schema(value)); put("origin", origin); put("clock", value.clock)
        put("result", value.resultId?.let(::JsonPrimitive) ?: JsonNull)
        put("completed", value.completedCommand?.let(::JsonPrimitive) ?: JsonNull)
        put("command", value.command?.let { c -> buildJsonObject {
            put("id", c.id); put("operation", c.operation); put("created", c.created)
            put("body", c.body?.let(::text) ?: JsonNull); put("plan", c.plan?.let(::text) ?: JsonNull)
            put("expected", c.expected?.let(::text) ?: JsonNull); put("etag", c.etag?.let(::JsonPrimitive) ?: JsonNull)
            c.localDeletion?.let { put("localDeletion", it.encode().copyForCodec().decodeToString(throwOnInvalidSequence = true)) }
        } } ?: JsonNull)
    }.toString().encodeToByteArray()).also { decode(it) }
    fun decode(bytes: PrivateBytes): CookbookRecord {
        val raw = WireDocument.decode(bytes.copyForCodec(), WireLimits(1_048_576, 24)).json().jsonObject
        keys(raw, "schema", "origin", "clock", "result", "completed", "command")
        val storedSchema = long(raw.getValue("schema"))
        if (storedSchema !in 1L..2L || string(raw.getValue("origin")) != origin) mealFail(FailureReason.INVALID_DATA)
        val command = raw.getValue("command").takeUnless { it == JsonNull }?.jsonObject?.let { c ->
            if (storedSchema == 1L) keys(c, "id", "operation", "created", "body", "plan", "expected", "etag")
            else keys(c, "id", "operation", "created", "body", "plan", "expected", "etag", "localDeletion")
            val operation = string(c.getValue("operation"))
            if (operation !in setOf("saveRecipe", "deleteSavedRecipe")) mealFail(FailureReason.INVALID_DATA)
            val body = parse(c.getValue("body"), "SaveRecipeRequest", 4096)
            val plan = parse(c.getValue("plan"), "Plan", 262_144)
            val expected = parse(c.getValue("expected"), "SavedRecipe", 262_144)
            val etag = nullableString(c.getValue("etag"))
            val localDeletion = if (storedSchema == 2L) mealValue(SavedRecipeDeletionEvidence.decode(
                PrivateBytes(string(c.getValue("localDeletion")).encodeToByteArray()))) else null
            if (operation == "saveRecipe") {
                if (body == null || plan == null || expected != null || etag != null || localDeletion != null) mealFail(FailureReason.INVALID_DATA)
                val allowed = setOf("planId", "title")
                if (body.json().jsonObject.keys.any { it !in allowed }) mealFail(FailureReason.INVALID_DATA)
            } else if (body != null || plan != null || etag == null || (expected == null) == (localDeletion == null) ||
                (localDeletion != null && (localDeletion.etag != etag || uuid(localDeletion.id) != localDeletion.id))) mealFail(FailureReason.INVALID_DATA)
            CookbookCommand(uuid(string(c.getValue("id"))), operation, body, plan, expected, etag, long(c.getValue("created")), localDeletion).also {
                if (operation == "saveRecipe" && !equal(it.request().document, body!!)) mealFail(FailureReason.INVALID_DATA)
            }
        }
        return CookbookRecord(long(raw.getValue("clock")), command,
            nullableString(raw.getValue("result"))?.let(::uuid), nullableString(raw.getValue("completed"))?.let(::uuid)).also {
            if (schema(it).toLong() != storedSchema) mealFail(FailureReason.INVALID_DATA)
        }
    }
    private fun parse(value: JsonElement, schema: String, limit: Int): WireDocument? = nullableString(value)?.let {
        WireDocument.parse(it, WireLimits(limit, 20)).also { body ->
            if (validator.validateSchema(schema, body.encodeUtf8()) != ContractValidationResult.Valid) mealFail(FailureReason.INVALID_DATA)
        }
    }
    private fun text(body: WireDocument) = JsonPrimitive(body.encodeUtf8().decodeToString())
}
