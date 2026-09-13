package com.feedme.server.contract

import com.feedme.contracts.CookSessionWire
import com.feedme.contracts.CookStart
import com.feedme.contracts.PlanId
import com.feedme.contracts.PlanWire
import com.feedme.contracts.RecipeVersionId
import com.feedme.contracts.SaveRecipeRequest
import com.feedme.contracts.SavedRecipeWire
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** Data-boundary integration only: synthetic fixtures, no accounts, repository writes or live cook. */
class WireContractIntegrationTest {
    @Test fun everyCanonicalValidationFixtureSurvivesSharedWireRoundTripWithoutCoercion() {
        val cases = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/schema-validator-cases.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        var count = 0
        for (entry in cases) {
            val case = entry.jsonObject
            val name = case.getValue("ref").jsonPrimitive.content.substringAfterLast('/')
            if (name.startsWith("__Spike")) continue
            val input = (case["inputJson"]?.jsonPrimitive?.content ?: case.getValue("input").toString()).encodeToByteArray()
            val roundTrip = WireDocument.decode(input).encodeUtf8()
            assertContentEquals(input, roundTrip)
            assertEquals(validator.validateSchema(name, input), validator.validateSchema(name, roundTrip))
            count++
        }
        assertEquals(149, count)
    }

    @Test fun everyDocumentedRequestExamplePassesItsActualOperationSchema() {
        var count = 0
        for (operation in catalog.operations) {
            val op = catalog.document.getValue("paths").jsonObject.getValue(operation.path).jsonObject
                .getValue(operation.method.lowercase()).jsonObject
            val example = op["requestBody"]?.jsonObject?.get("content")?.jsonObject
                ?.get("application/json")?.jsonObject?.get("example") ?: continue
            assertEquals(BodyValidationResult.Valid,
                validator.validateRequest(operation.id, example.toString().encodeToByteArray(), "application/json"), operation.id)
            count++
        }
        assertEquals(9, count)
    }

    @Test fun typedCommandsUsePlanIdentityAndKeepNonexclusiveSaveSelectors() {
        val planId = PlanId(PLAN)
        val start = CookStart(planId, Long.MAX_VALUE)
        assertEquals(BodyValidationResult.Valid,
            validator.validateRequest("createCookSession", start.document.encodeUtf8(), "application/json"))
        for (save in listOf(SaveRecipeRequest(planId = planId), SaveRecipeRequest(recipeVersionId = RecipeVersionId(VERSION)),
            SaveRecipeRequest(planId, RecipeVersionId(VERSION), title = "🍋".repeat(120), markMakeAgain = false))) {
            assertEquals(BodyValidationResult.Valid,
                validator.validateRequest("saveRecipe", save.document.encodeUtf8(), "application/json"))
        }
        // A private generic save is not a post-grant transaction, even if a post ID is known.
        assertEquals(BodyValidationResult.Rejected(BodyRejectionReason.SCHEMA_VIOLATION),
            validator.validateRequest("saveRecipe", """{"sourcePostId":"$POST"}""".encodeToByteArray(), "application/json"))
    }

    @Test fun recipePlanCookAndPrivateSnapshotKeepTheirDistinctProductionIdentities() {
        val recipe = buildJsonObject {
            put("id", VERSION); put("recipeId", RECIPE); metadata()
            put("title", "Synthetic mapping fixture"); put("reviewStatus", "published")
            put("ingredients", buildJsonArray { })
            put("steps", buildJsonArray { add(buildJsonObject {
                put("stepId", "stable-step"); put("position", 1); put("instruction", "Synthetic instruction")
                put("ingredientIds", buildJsonArray { }); put("requiredEquipmentIds", buildJsonArray { })
                put("mandatorySafetyStep", true)
            }) })
            put("servings", 1); put("activeMinutes", 1); put("totalMinutes", 1); put("utensilCount", 0)
            put("equipmentIds", buildJsonArray { }); put("modes", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("cook")) })
            put("tasteTags", buildJsonArray { })
        }
        val planJson = buildJsonObject {
            put("id", PLAN); metadata(); put("recipeVersionId", VERSION); put("mode", "cook"); put("status", "ready")
            put("constraints", buildJsonObject {
                put("ingredientIds", buildJsonArray { }); put("hardExcludedIngredientIds", buildJsonArray { })
                put("energy", "little"); put("equipmentIds", buildJsonArray { }); put("servings", 1)
            })
            put("recipeSnapshot", recipe); put("missingIngredients", buildJsonArray { }); put("changes", buildJsonArray { })
            put("reasons", buildJsonArray { }); put("catalogRevision", "synthetic-revision"); put("nextAlternativeCursor", JsonNull)
        }
        val planDocument = wire(planJson)
        assertEquals(BodyValidationResult.Valid, validator.validateSchema("Plan", planDocument.encodeUtf8()))
        val plan = PlanWire.from(planDocument)
        val snapshot = assertIs<WireField.Value<*>>(plan.recipeSnapshot).value as com.feedme.contracts.RecipeVersionWire
        assertEquals(RECIPE, snapshot.recipeId.value)
        assertEquals(VERSION, snapshot.id.value)
        assertNotEquals(snapshot.recipeId.value, snapshot.id.value)
        assertEquals(WireField.Null, plan.nextAlternativeCursor)
        assertEquals(PLAN, CookStart(plan.id).planId.value)

        val sessionDocument = wire(buildJsonObject {
            put("id", SESSION); metadata(); put("planId", PLAN); put("status", "active")
            put("currentStepId", "stable-step"); put("completedStepIds", buildJsonArray { }); put("deviceSequence", 2)
            put("timers", buildJsonArray { })
        })
        assertEquals(BodyValidationResult.Valid, validator.validateSchema("CookSession", sessionDocument.encodeUtf8()))
        val session = CookSessionWire.from(sessionDocument)
        assertEquals(plan.id, session.planId)
        assertEquals(snapshot.steps.single().stepId, session.currentStepId)
        assertEquals("2", session.deviceSequence.jsonToken)
        val save = SaveRecipeRequest(planId = session.planId, markMakeAgain = true)
        assertEquals(BodyValidationResult.Valid, validator.validateRequest("saveRecipe", save.document.encodeUtf8(), "application/json"))

        val savedDocument = wire(buildJsonObject {
            put("id", SAVED); metadata(); put("title", "Private snapshot"); put("snapshot", recipe)
            put("sourceType", "postGrant"); put("sourcePostId", POST); put("grantId", GRANT); put("recalled", true)
        })
        assertEquals(BodyValidationResult.Valid, validator.validateSchema("SavedRecipe", savedDocument.encodeUtf8()))
        val saved = SavedRecipeWire.from(savedDocument)
        assertEquals(VERSION, saved.snapshot.id.value)
        assertEquals(RECIPE, saved.snapshot.recipeId.value)
        assertEquals(POST, (saved.sourcePostId as WireField.Value).value.value)
        assertEquals(GRANT, (saved.grantId as WireField.Value).value.value)
        assertEquals(true, saved.recalled) // Schema validity does not authorize cooking recalled content.
        assertContentEquals(savedDocument.encodeUtf8(), saved.document.encodeUtf8())
    }

    private fun wire(value: JsonObject) = WireDocument.parse(value.toString())
    private fun kotlinx.serialization.json.JsonObjectBuilder.metadata() {
        put("version", 1); put("createdAt", "2026-09-13T00:00:00Z"); put("updatedAt", "2026-09-13T00:00:00Z")
    }

    companion object {
        private val catalog by lazy { ContractCatalog.bundled() }
        private val validator by lazy { ContractBodyValidator.bundled(catalog) }
        private const val RECIPE = "11111111-1111-4111-8111-111111111111"
        private const val VERSION = "22222222-2222-4222-8222-222222222222"
        private const val PLAN = "33333333-3333-4333-8333-333333333333"
        private const val SESSION = "44444444-4444-4444-8444-444444444444"
        private const val SAVED = "55555555-5555-4555-8555-555555555555"
        private const val POST = "66666666-6666-4666-8666-666666666666"
        private const val GRANT = "77777777-7777-4777-8777-777777777777"
    }
}
