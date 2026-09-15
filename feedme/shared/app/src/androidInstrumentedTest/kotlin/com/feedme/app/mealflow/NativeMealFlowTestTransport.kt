package com.feedme.app.mealflow

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*

/** Explicit SYNTHETIC transport, not provider or HTTP proof. Actual controller validates every
 * canonical response and commits it into the real encrypted native store. Main dispatcher only. */
internal class NativeMealFlowTestTransport(private val session: NativeMealFlowTestSession) : AccountTransport {
    val calls = mutableListOf<ApiCall>()
    // Test-only completion witness: exact call whose createPlan result is already constructed.
    // Recorded immediately before returning, never while the plan gate remains suspended.
    val completedPlanCalls = mutableListOf<ApiCall>()
    var unknownNextPlan = false
    var planGate: CompletableDeferred<Unit>? = null
    private var preference = Json.parseToJsonElement("""{"id":"$OTHER","version":1,"createdAt":"$TIME","updatedAt":"$TIME",
        "hardExcludedIngredientIds":[],"dietaryPatterns":[],"dislikedIngredientIds":[],"equipmentIds":["bowl"]}""").jsonObject
    private val pantry = mutableMapOf<String, JsonObject>()
    override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
        check(session.boundary.isCurrent(lease) && lease.scope == session.scope)
        calls += call
        return when (call.operationId) {
            "getPreferences" -> reply(preference.toString(), etag = "\"${preference.getValue("version")}\"")
            "listPantry" -> reply("""{"items":${JsonArray(pantry.values.toList())},"nextCursor":null,"serverTime":"$TIME"}""")
            "searchIngredients" -> reply("""{"items":[{"id":"$INGREDIENT","version":1,"createdAt":"$TIME","updatedAt":"$TIME",
                "name":"Synthetic cucumber","aliases":["Synthetic fixture"],"category":"fixture only","supportedUnits":["g"]}],
                "nextCursor":null,"serverTime":"$TIME"}""")
            "updatePreferences" -> {
                check(call.ifMatch == "\"${preference.getValue("version")}\"" && call.idempotencyKey != null)
                val patch = Json.parseToJsonElement(checkNotNull(call.body).copyForCodec().decodeToString()).jsonObject
                preference = JsonObject(preference + patch + ("version" to JsonPrimitive(preference.getValue("version").jsonPrimitive.int + 1)))
                reply(preference.toString(), etag = "\"${preference.getValue("version")}\"")
            }
            "upsertPantryItem" -> {
                check(call.ifMatch == null && call.idempotencyKey != null)
                val write = Json.parseToJsonElement(checkNotNull(call.body).copyForCodec().decodeToString()).jsonObject
                val id = write.getValue("ingredientId").jsonPrimitive.content
                check(write["expectedVersion"] == pantry[id]?.get("version"))
                val item = JsonObject((write - "expectedVersion") + mapOf("id" to JsonPrimitive(OTHER),
                    "staple" to (write["staple"] ?: pantry[id]?.get("staple") ?: JsonPrimitive(false)),
                    "version" to JsonPrimitive((pantry[id]?.get("version")?.jsonPrimitive?.int ?: 0) + 1),
                    "createdAt" to JsonPrimitive(TIME), "updatedAt" to JsonPrimitive(TIME)))
                pantry[id] = item
                reply(item.toString(), etag = "\"${item.getValue("version")}\"")
            }
            "removePantryItem" -> {
                val id = call.pathParameters.getValue("ingredientId"); val item = checkNotNull(pantry[id])
                check(call.ifMatch == "\"${item.getValue("version")}\"" && call.body == null && call.idempotencyKey != null)
                pantry.remove(id); PortResult.Value(ApiReply(204, null))
            }
            "createPlan" -> {
                planGate?.await()
                val result = if (unknownNextPlan) { unknownNextPlan = false; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
                else {
                    val request = Json.parseToJsonElement(checkNotNull(call.body).copyForCodec().decodeToString()).jsonObject
                    val constraints = request.getValue("constraints")
                    val servings = constraints.jsonObject.getValue("servings")
                    reply("""{"id":"$PLAN","version":1,"createdAt":"$TIME","updatedAt":"$TIME","status":"ready","mode":"assemble",
                        "constraints":$constraints,"missingIngredients":[],"changes":[],"reasons":[],"catalogRevision":"synthetic-host-only",
                        "nextAlternativeCursor":null,"recipeVersionId":"$VERSION","recipeSnapshot":{
                        "id":"$VERSION","recipeId":"$OTHER","version":1,"createdAt":"$TIME","updatedAt":"$TIME",
                        "title":"Synthetic retained crunch bowl","reviewStatus":"published","reviewedAt":"$TIME","servings":$servings,
                        "activeMinutes":1,"totalMinutes":1,"utensilCount":1,"equipmentIds":["bowl"],"modes":["assemble"],"tasteTags":[],
                        "ingredients":[{"ingredientId":"$INGREDIENT","quantity":1.230000,"unit":"g","optional":false,"preparation":"fixture only"}],
                        "steps":[{"stepId":"first","position":1,"instruction":"Synthetic required instruction — not cooking guidance.",
                        "ingredientIds":["$INGREDIENT"],"requiredEquipmentIds":["bowl"],"mandatorySafetyStep":true,"durationSeconds":60}]}}""", 201, "\"1\"")
                }
                completedPlanCalls += call
                result
            }
            else -> error("Unexpected synthetic transport operation ${call.operationId}")
        }
    }
    private fun reply(json: String, status: Int = 200, etag: String? = null): PortResult<ApiReply> =
        PortResult.Value(ApiReply(status, PrivateBytes(WireDocument.parse(json).encodeUtf8()), etag, contentType = "application/json"))
    companion object {
        const val INGREDIENT = "00000000-0000-4000-8000-000000000011"
        const val OTHER = "00000000-0000-4000-8000-000000000012"
        const val PLAN = "00000000-0000-4000-8000-000000000201"
        const val VERSION = "00000000-0000-4000-8000-000000000301"
        const val TIME = "2026-09-14T00:00:00Z"
    }
}
