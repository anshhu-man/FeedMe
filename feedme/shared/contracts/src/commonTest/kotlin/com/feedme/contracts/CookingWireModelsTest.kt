package com.feedme.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Tests the cooking convenience slice and retention, not complete request/response validation. */
class CookingWireModelsTest {
    private val recipeId = "10000000-0000-0000-0000-000000000001"
    private val versionId = "20000000-0000-0000-0000-000000000002"
    private val planId = "30000000-0000-0000-0000-000000000003"
    private val sessionId = "40000000-0000-0000-0000-000000000004"
    private val savedId = "50000000-0000-0000-0000-000000000005"
    private val ingredientId = "60000000-0000-0000-0000-000000000006"
    private val postId = "70000000-0000-0000-0000-000000000007"
    private val grantId = "80000000-0000-0000-0000-000000000008"
    private val timerId = "90000000-0000-0000-0000-000000000009"
    private val timestamp = "2026-09-13T00:00:00Z"

    @Test fun identityRolesAreDistinctAndRedacted() {
        val sameText = recipeId
        val ids = listOf(RecipeId(sameText), RecipeVersionId(sameText), PlanId(sameText),
            CookSessionId(sameText), SavedRecipeId(sameText), CollectionId(sameText),
            SourcePostId(sameText), GrantId(sameText), IngredientId(sameText), TimerId(sameText))
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertFalse(it.toString().contains(sameText)) }
        assertNotEquals<Any>(RecipeId(sameText), RecipeVersionId(sameText))
        assertNotEquals<Any>(PlanId(sameText), CookSessionId(sameText))
        assertEquals("safety:heat-final", StepId("safety:heat-final").value)
        assertFalse(StepId("private-step").toString().contains("private-step"))
        val failure = assertFailsWith<IllegalArgumentException> { PlanId("private-invalid-id") }
        assertFalse(failure.message.orEmpty().contains("private-invalid-id"))
    }

    @Test fun cookStartConstructsPlanIdentityAndPreservesZeroSequence() {
        val request = CookStart(PlanId(planId), 0)
        assertEquals(PlanId(planId), request.planId)
        assertEquals(Json.parseToJsonElement("""{"planId":"$planId","deviceSequence":0}"""), request.document.json())
        assertEquals(setOf("planId"), CookStart(PlanId(planId)).document.json().jsonObject.keys)
        assertEquals(Long.MAX_VALUE.toString(), CookStart(PlanId(planId), Long.MAX_VALUE)
            .document.json().jsonObject.getValue("deviceSequence").jsonPrimitive.content)
        assertFalse(request.toString().contains(planId))
        assertFailsWith<IllegalArgumentException> { CookStart(PlanId(planId), -1) }
    }

    @Test fun saveAnyOfAllowsEachSelectorAndBothWithEveryOptionalField() {
        assertEquals(setOf("planId"), SaveRecipeRequest(planId = PlanId(planId)).document.json().jsonObject.keys)
        assertEquals(setOf("recipeVersionId"), SaveRecipeRequest(recipeVersionId = RecipeVersionId(versionId))
            .document.json().jsonObject.keys)
        val request = SaveRecipeRequest(PlanId(planId), RecipeVersionId(versionId), "private title", CollectionId(savedId), false)
        assertEquals(Json.parseToJsonElement("""{
            "planId":"$planId","recipeVersionId":"$versionId","title":"private title",
            "collectionId":"$savedId","markMakeAgain":false
        }"""), request.document.json())
        assertFalse(request.toString().contains("private title"))
        assertFalse(request.toString().contains(versionId))
        assertFailsWith<IllegalArgumentException> { SaveRecipeRequest(title = "no selector") }
        assertEquals("", SaveRecipeRequest(planId = PlanId(planId), title = "").document.json()
            .jsonObject.getValue("title").jsonPrimitive.content)
    }

    @Test fun saveTitleLimitCountsUnicodeCodePoints() {
        val astral = "\uD83C\uDF72"
        SaveRecipeRequest(planId = PlanId(planId), title = astral.repeat(120))
        assertFailsWith<IllegalArgumentException> {
            SaveRecipeRequest(planId = PlanId(planId), title = astral.repeat(121))
        }
        assertFailsWith<IllegalArgumentException> {
            SaveRecipeRequest(planId = PlanId(planId), title = "x".repeat(121))
        }
    }

    @Test fun recipeVersionKeepsRecipeIdentityStableStepsAndExactAmounts() {
        val document = WireDocument.parse(recipeJson())
        val recipe = RecipeVersionWire.from(document)
        assertEquals(RecipeVersionId(versionId), recipe.id)
        assertEquals(RecipeId(recipeId), recipe.recipeId)
        assertEquals("9007199254740993", recipe.version.jsonToken)
        assertEquals(timestamp, recipe.createdAt)
        assertEquals("0.123456789012345678901234567890", recipe.ingredients.single().quantity.jsonToken)
        assertEquals(IngredientId(ingredientId), recipe.ingredients.single().ingredientId)
        assertEquals("g", recipe.ingredients.single().unit)
        val step = recipe.steps.single()
        assertEquals(StepId("stable-safety-step"), step.stepId)
        assertEquals("2", step.position.jsonToken)
        assertEquals(listOf(IngredientId(ingredientId)), step.ingredientIds)
        assertTrue(step.mandatorySafetyStep)
        assertEquals("6e1", assertIs<WireField.Value<ExactWireNumber>>(step.durationSeconds).value.jsonToken)
        assertEquals("privateCopyOnly", assertIs<WireField.Value<String>>(recipe.contentLicense).value)
        assertEquals("recall-code", assertIs<WireField.Value<String>>(recipe.recallReasonCode).value)
        assertEquals(document.json(), recipe.document.json())
        assertTrue(recipe.document.json().jsonObject.containsKey("futureExtension"))
    }

    @Test fun reviewStatesRemainDistinctIncludingUnknownState() {
        val states = listOf("draft", "inReview", "approved", "published", "recalled", "personal", "retired", "futureState")
        states.forEach { state ->
            assertEquals(state, RecipeVersionWire.from(WireDocument.parse(recipeJson(state))).reviewStatus)
        }
    }

    @Test fun planKeepsSnapshotIdsAndOptionalNullableCursorPresence() {
        val absent = PlanWire.from(WireDocument.parse(planJson()))
        val explicitNull = PlanWire.from(WireDocument.parse(planJson(",\"nextAlternativeCursor\":null")))
        val present = PlanWire.from(WireDocument.parse(planJson(",\"nextAlternativeCursor\":\"private-cursor\"")))
        assertEquals(PlanId(planId), absent.id)
        assertEquals(RecipeVersionId(versionId), assertIs<WireField.Value<RecipeVersionId>>(absent.recipeVersionId).value)
        assertEquals(RecipeId(recipeId), assertIs<WireField.Value<RecipeVersionWire>>(absent.recipeSnapshot).value.recipeId)
        assertEquals("catalog-revision", absent.catalogRevision)
        assertIs<WireField.Missing>(absent.nextAlternativeCursor)
        assertIs<WireField.Null>(explicitNull.nextAlternativeCursor)
        assertEquals("private-cursor", assertIs<WireField.Value<String>>(present.nextAlternativeCursor).value)
        assertFalse(absent.document.json().jsonObject.containsKey("nextAlternativeCursor"))
        assertEquals("null", explicitNull.document.json().jsonObject.getValue("nextAlternativeCursor").toString())
    }

    @Test fun planWithoutOptionalRecipeStillProjects() {
        val original = WireDocument.parse(planJson()).json().jsonObject
        val plan = PlanWire.from(WireDocument.parse(JsonObject(original - setOf("recipeVersionId", "recipeSnapshot")).toString()))
        assertIs<WireField.Missing>(plan.recipeVersionId)
        assertIs<WireField.Missing>(plan.recipeSnapshot)
        assertIs<WireField.Missing>(plan.parentPlanId)
    }

    @Test fun cookSessionKeepsSessionPlanStepTimerSequenceAndServerTimestamps() {
        val session = CookSessionWire.from(WireDocument.parse(sessionJson()))
        assertEquals(CookSessionId(sessionId), session.id)
        assertEquals(PlanId(planId), session.planId)
        assertEquals(StepId("stable-safety-step"), session.currentStepId)
        assertEquals(listOf(StepId("prep-step")), session.completedStepIds)
        assertEquals("922337203685477580812345", session.deviceSequence.jsonToken)
        assertEquals("3", session.version.jsonToken)
        assertEquals(timestamp, session.updatedAt)
        val timer = session.timers.single()
        assertEquals(TimerId(timerId), timer.timerId)
        assertEquals(StepId("stable-safety-step"), timer.stepId)
        assertEquals(timestamp, assertIs<WireField.Value<String>>(timer.endAt).value)
        assertEquals("60", timer.durationSeconds.jsonToken)
        assertIs<WireField.Missing>(timer.pausedRemainingSeconds)
        assertIs<WireField.Missing>(session.completedAt)
        assertEquals("private note", assertIs<WireField.Value<List<WireDocument>>>(session.personalNotes)
            .value.single().json().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test fun savedRecipeKeepsSnapshotRightsSourceGrantAndRecall() {
        val saved = SavedRecipeWire.from(WireDocument.parse(savedJson()))
        assertEquals(SavedRecipeId(savedId), saved.id)
        assertEquals(RecipeVersionId(versionId), saved.snapshot.id)
        assertEquals(RecipeId(recipeId), saved.snapshot.recipeId)
        assertEquals("postGrant", saved.sourceType)
        assertEquals(SourcePostId(postId), assertIs<WireField.Value<SourcePostId>>(saved.sourcePostId).value)
        assertEquals(GrantId(grantId), assertIs<WireField.Value<GrantId>>(saved.grantId).value)
        assertEquals("privateCopyOnly", assertIs<WireField.Value<String>>(saved.contentLicense).value)
        assertTrue(saved.recalled)
        assertEquals("recalled", saved.snapshot.reviewStatus)
        assertEquals(WireDocument.parse(recipeJson("recalled")).json(), saved.snapshot.document.json())
    }

    @Test fun projectionsRejectWrongScalarShapesWithoutLoggingPayload() {
        val document = WireDocument.parse(sessionJson().replace("\"deviceSequence\":922337203685477580812345", "\"deviceSequence\":\"private-number\""))
        val failure = assertFailsWith<IllegalArgumentException> { CookSessionWire.from(document) }
        assertFalse(failure.message.orEmpty().contains("private-number"))
        assertFailsWith<IllegalArgumentException> {
            RecipeVersionWire.from(WireDocument.parse(recipeJson().replace("\"mandatorySafetyStep\":true", "\"mandatorySafetyStep\":\"true\"")))
        }
        assertFailsWith<IllegalArgumentException> {
            CookSessionWire.from(WireDocument.parse(sessionJson().replace("\"currentStepId\":\"stable-safety-step\"", "\"currentStepId\":null")))
        }
    }

    @Test fun projectionsAndNestedDebugStringsRedactPrivateMaterial() {
        val recipe = RecipeVersionWire.from(WireDocument.parse(recipeJson()))
        val plan = PlanWire.from(WireDocument.parse(planJson(",\"nextAlternativeCursor\":\"private-cursor\"")))
        val session = CookSessionWire.from(WireDocument.parse(sessionJson()))
        val saved = SavedRecipeWire.from(WireDocument.parse(savedJson()))
        val debug = listOf(recipe, recipe.ingredients.single(), recipe.steps.single(), recipe.version,
            plan, plan.nextAlternativeCursor, session, session.timers.single(), session.personalNotes,
            saved, saved.snapshot, saved.creatorLabel).joinToString()
        listOf(recipeId, versionId, planId, sessionId, savedId, "private title", "private instruction",
            "private note", "private creator", "private-cursor").forEach { assertFalse(debug.contains(it)) }
    }

    @Test fun returnedListsAndSnapshotBytesCannotMutateTheProjection() {
        val recipe = RecipeVersionWire.from(WireDocument.parse(recipeJson().replace(
            "\"equipmentIds\":[\"pan\"]", "\"equipmentIds\":[\"pan\",\"lid\"]")))
        val detached = recipe.equipmentIds
        (detached as? MutableList<String>)?.let { runCatching { it[0] = "changed" } }
        assertEquals(listOf("pan", "lid"), recipe.equipmentIds)
        val saved = SavedRecipeWire.from(WireDocument.parse(savedJson()))
        val bytes = saved.snapshot.document.encodeUtf8()
        bytes.fill(0)
        assertEquals(RecipeVersionId(versionId), saved.snapshot.id)
        assertEquals(WireDocument.parse(recipeJson("recalled")).json(), saved.snapshot.document.json())
    }

    @Test fun typedSliceMatchesCanonicalPropertiesWithoutChangingTheContract() {
        val catalog = ContractCatalog.bundled()
        assertEquals(188, catalog.schemaNames.size)
        assertEquals(201, catalog.operations.size)
        val start = Json.parseToJsonElement(catalog.schema("CookStart")!!.json).jsonObject
        assertEquals(setOf("planId", "deviceSequence"), start.getValue("properties").jsonObject.keys)
        assertEquals(listOf("planId"), start.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val save = Json.parseToJsonElement(catalog.schema("SaveRecipeRequest")!!.json).jsonObject
        assertEquals(setOf("planId", "recipeVersionId", "title", "collectionId", "markMakeAgain"),
            save.getValue("properties").jsonObject.keys)
        assertFalse(save.containsKey("oneOf"))
        assertEquals(listOf(listOf("planId"), listOf("recipeVersionId")), save.getValue("anyOf").jsonArray.map {
            it.jsonObject.getValue("required").jsonArray.map { name -> name.jsonPrimitive.content }
        })
        val plan = Json.parseToJsonElement(catalog.schema("Plan")!!.json).jsonObject
        assertEquals(listOf("string", "null"), plan.getValue("properties").jsonObject
            .getValue("nextAlternativeCursor").jsonObject.getValue("type").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(plan.getValue("required").jsonArray.any { it.jsonPrimitive.content == "nextAlternativeCursor" })
        val recipe = Json.parseToJsonElement(catalog.schema("RecipeVersion")!!.json).jsonObject
        assertFalse(recipe.getValue("properties").jsonObject.keys.any { it.contains("image", ignoreCase = true) })
    }

    private fun WireDocument.json() = Json.parseToJsonElement(encodeUtf8().decodeToString())

    private fun recipeJson(status: String = "published"): String = """{
        "id":"$versionId","recipeId":"$recipeId","version":9007199254740993,
        "createdAt":"$timestamp","updatedAt":"$timestamp","title":"private title","reviewStatus":"$status",
        "ingredients":[{"ingredientId":"$ingredientId","quantity":0.123456789012345678901234567890,"unit":"g","optional":false}],
        "steps":[{"stepId":"stable-safety-step","position":2,"instruction":"private instruction",
            "ingredientIds":["$ingredientId"],"requiredEquipmentIds":["pan"],"mandatorySafetyStep":true,"durationSeconds":6e1}],
        "servings":1.0,"activeMinutes":2,"totalMinutes":3,"utensilCount":1,"equipmentIds":["pan"],
        "modes":["cook"],"tasteTags":[],"contentLicense":"privateCopyOnly","recallReasonCode":"recall-code",
        "futureExtension":{"retained":true}
    }"""

    private fun planJson(extra: String = ""): String = """{
        "id":"$planId","version":2,"createdAt":"$timestamp","updatedAt":"$timestamp",
        "recipeVersionId":"$versionId","recipeSnapshot":${recipeJson()},"mode":"cook","status":"ready",
        "constraints":{},"missingIngredients":[],"changes":[],"reasons":[],"catalogRevision":"catalog-revision"$extra
    }"""

    private fun sessionJson(): String = """{
        "id":"$sessionId","planId":"$planId","version":3,"createdAt":"$timestamp","updatedAt":"$timestamp",
        "status":"active","currentStepId":"stable-safety-step","completedStepIds":["prep-step"],
        "deviceSequence":922337203685477580812345,
        "timers":[{"timerId":"$timerId","stepId":"stable-safety-step","status":"running","endAt":"$timestamp","durationSeconds":60}],
        "personalNotes":[{"text":"private note","label":"myNote"}]
    }"""

    private fun savedJson(): String = """{
        "id":"$savedId","version":1,"createdAt":"$timestamp","updatedAt":"$timestamp","title":"private title",
        "snapshot":${recipeJson("recalled")},"sourceType":"postGrant","sourcePostId":"$postId","grantId":"$grantId",
        "creatorLabel":"private creator","recalled":true,"contentLicense":"privateCopyOnly"
    }"""
}
