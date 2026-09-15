package com.feedme.app.mealflow

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** SYNTHETIC canonical service only. Actual host/session/controller/queue/encryption are real. */
internal class NativeCookingFlowTestTransport(private val owner: NativeMealFlowTestSession) : AccountTransport {
    private val meals = NativeMealFlowTestTransport(owner)
    val calls = mutableListOf<ApiCall>()
    private val plans = linkedMapOf<String, JsonObject>()
    private val sessions = linkedMapOf<String, JsonObject>()
    private val receipts = linkedMapOf<String, JsonObject>()
    var unknownCreate = false
    var failDownload = false
    var unavailablePlan = false
    var recalledPlan: String? = null
    var downloadGate: CompletableDeferred<Unit>? = null
    var createGate: CompletableDeferred<Unit>? = null
    override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
        check(owner.boundary.isCurrent(lease) && lease.scope == owner.scope)
        calls += call
        return when (call.operationId) {
            "createPlan" -> {
                val response = meals.execute(lease, call)
                if (response !is PortResult.Value) response else {
                    val root = json(checkNotNull(response.value.body)); val n = plans.size + 1
                    val id = id(200 + n); val version = id(300 + n)
                    val recipe = root.getValue("recipeSnapshot").jsonObject
                    val last = recipe.getValue("steps").jsonArray.single().jsonObject
                    val steps = JsonArray(listOf(last, JsonObject(last + mapOf("stepId" to JsonPrimitive("second"),
                        "position" to JsonPrimitive(2), "instruction" to JsonPrimitive("Synthetic second instruction — no readiness claim."),
                        "mandatorySafetyStep" to JsonPrimitive(false)))))
                    val exact = JsonObject(root + mapOf("id" to JsonPrimitive(id), "recipeVersionId" to JsonPrimitive(version),
                        "recipeSnapshot" to JsonObject(recipe + mapOf("id" to JsonPrimitive(version),
                            "title" to JsonPrimitive("Synthetic cooking bowl ${if (n == 1) "A" else "B"}"), "steps" to steps))))
                    plans[id] = exact; reply(exact, 201)
                }
            }
            "getPlan" -> {
                val id = call.pathParameters.getValue("planId")
                when {
                    unavailablePlan -> PortResult.Failure(FailureReason.NOT_CONFIGURED)
                    recalledPlan == id -> PortResult.Value(ApiReply(410, PrivateBytes("""{"type":"https://feedme.invalid/problems/recipe-recalled",
                        "title":"Synthetic recall","status":410,"code":"RECIPE_RECALLED","traceId":"synthetic-cooking-recall","detail":"Synthetic exact pinned recall"}""".encodeToByteArray()),
                        contentType = "application/problem+json"))
                    else -> reply(checkNotNull(plans[id]))
                }
            }
            "createCookSession" -> {
                check(call.ifMatch == null); val key = checkNotNull(call.idempotencyKey).use { it }
                val body = json(checkNotNull(call.body)); check(body.keys == setOf("planId", "deviceSequence")); check(body["deviceSequence"] == JsonPrimitive(0))
                val plan = body.getValue("planId").jsonPrimitive.content; check(plans.containsKey(plan))
                val result = receipts.getOrPut(key) {
                    val id = id(500 + sessions.size + 1)
                    Json.parseToJsonElement("""{"id":"$id","version":1,"createdAt":"$TIME","updatedAt":"$TIME","planId":"$plan",
                        "status":"active","currentStepId":"first","completedStepIds":[],"timers":[],"deviceSequence":0}""").jsonObject.also { sessions[id] = it }
                }
                createGate?.let { withContext(NonCancellable) { it.await() } }
                if (unknownCreate) { unknownCreate = false; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) } else reply(result, 201)
            }
            "getCookSession" -> {
                downloadGate?.let { withContext(NonCancellable) { it.await() } }
                if (failDownload) PortResult.Failure(FailureReason.OFFLINE)
                else reply(checkNotNull(sessions[call.pathParameters.getValue("sessionId")]))
            }
            "updateCookSession", "completeCookSession" -> {
                val key = checkNotNull(call.idempotencyKey).use { it }; val id = call.pathParameters.getValue("sessionId")
                val old = checkNotNull(sessions[id]); val body = json(checkNotNull(call.body))
                if (call.operationId == "updateCookSession") check(call.ifMatch == "\"${old.getValue("version")}\"")
                else { check(call.ifMatch == null); check(body["makeAgain"] == JsonPrimitive(false)) }
                val result = receipts.getOrPut(key) {
                    JsonObject(old + (body - setOf("makeAgain", "finishedAtClient")) + mapOf(
                        "version" to JsonPrimitive(old.getValue("version").jsonPrimitive.int + 1),
                        "status" to if (call.operationId == "completeCookSession") JsonPrimitive("completed") else (body["status"] ?: old.getValue("status"))))
                        .also { sessions[id] = it }
                }
                reply(result)
            }
            else -> meals.execute(lease, call)
        }
    }
    fun mutations() = calls.filter { it.operationId in setOf("createCookSession", "updateCookSession", "completeCookSession") }
    private fun reply(json: JsonObject, status: Int = 200): PortResult<ApiReply> = PortResult.Value(ApiReply(status,
        PrivateBytes(WireDocument.parse(json.toString()).encodeUtf8()), etag = "\"${json.getValue("version")}\"", contentType = "application/json"))
    companion object {
        const val TIME = NativeMealFlowTestTransport.TIME
        fun id(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
        fun json(bytes: PrivateBytes) = Json.parseToJsonElement(bytes.copyForCodec().decodeToString()).jsonObject
    }
}
