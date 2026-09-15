package com.feedme.contracts

import kotlinx.serialization.json.*
import kotlin.test.*

/** Unresolved UI states are domain responses, not a fabricated preparation mode. */
class PlanModeContractTest {
    @Test fun omittedModeIsAllowedOnlyForConfirmationAndNoMatch() {
        for (status in listOf("needsConfirmation", "noMatch")) {
            val document = plan(status)
            assertEquals(ContractValidationResult.Valid, validator.validateSchema("Plan", document.encodeUtf8()))
            assertEquals(ContractValidationResult.Valid,
                validator.validateResponse("createPlan", 201, document.encodeUtf8(), "application/json"))
        }
        for (status in listOf("ready", "recalled", "unknown")) {
            assertNotEquals(ContractValidationResult.Valid, validator.validateSchema("Plan", plan(status).encodeUtf8()))
        }
    }

    @Test fun everyResolvedCanonicalModeStillValidatesForEveryKnownStatus() {
        for (status in listOf("ready", "recalled", "needsConfirmation", "noMatch")) {
            for (mode in listOf("cook", "assemble", "improve")) {
                assertEquals(ContractValidationResult.Valid,
                    validator.validateSchema("Plan", plan(status, JsonPrimitive(mode)).encodeUtf8()))
            }
        }
    }

    @Test fun optionalDoesNotMeanNullAutoWrongTypeOrUnknownStatus() {
        for (status in listOf("ready", "recalled", "needsConfirmation", "noMatch")) {
            for (mode in listOf(JsonNull, JsonPrimitive("auto"), JsonPrimitive(""), JsonPrimitive(0), JsonPrimitive(false))) {
                assertNotEquals(ContractValidationResult.Valid,
                    validator.validateSchema("Plan", plan(status, mode).encodeUtf8()), "$status/$mode")
            }
        }
        val missingStatus = plan("noMatch").encodeUtf8().decodeToString().let {
            WireDocument.parse(JsonObject(Json.parseToJsonElement(it).jsonObject - "status").toString())
        }
        assertNotEquals(ContractValidationResult.Valid, validator.validateSchema("Plan", missingStatus.encodeUtf8()))
    }

    @Test fun projectionRetainsAbsenceAndExactBytesRatherThanInventingASelection() {
        val unresolved = plan("needsConfirmation")
        val view = PlanWire.from(unresolved)
        assertEquals(WireField.Missing, view.mode)
        assertEquals("needsConfirmation", view.status)
        assertContentEquals(unresolved.encodeUtf8(), view.document.encodeUtf8())
        val resolved = PlanWire.from(plan("ready", JsonPrimitive("assemble")))
        assertEquals("assemble", assertIs<WireField.Value<String>>(resolved.mode).value)
    }

    private fun plan(status: String, mode: JsonElement? = null) = WireDocument.parse(buildJsonObject {
        put("id", "30000000-0000-4000-8000-000000000003"); put("version", 1)
        put("createdAt", "2026-09-14T00:00:00Z"); put("updatedAt", "2026-09-14T00:00:00Z")
        put("status", status); mode?.let { put("mode", it) }
        put("constraints", buildJsonObject {
            put("ingredientIds", buildJsonArray {}); put("hardExcludedIngredientIds", buildJsonArray {})
            put("energy", "little"); put("equipmentIds", buildJsonArray {}); put("servings", 1)
        })
        put("missingIngredients", buildJsonArray {}); put("changes", buildJsonArray {})
        put("reasons", buildJsonArray {}); put("catalogRevision", "synthetic-mode-contract")
    }.toString())

    private val validator = CanonicalBodyValidator.bundled()
}
