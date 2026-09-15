package com.feedme.server.contract

import com.feedme.contracts.CanonicalBodyValidator
import com.feedme.contracts.ContractValidationResult
import com.feedme.contracts.PlanWire
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import kotlinx.serialization.json.*
import kotlin.test.*

class PlanModeParityTest {
    @Test fun serverAndSharedValidatorsAgreeOnConditionalModeAndPreserveUnresolvedResponses() {
        val server = ContractBodyValidator.bundled(ContractCatalog.bundled())
        val shared = CanonicalBodyValidator.bundled()
        for (status in listOf("ready", "recalled", "needsConfirmation", "noMatch", "unknown")) {
            for (mode in listOf(null, JsonNull, JsonPrimitive("auto"), JsonPrimitive("cook"),
                JsonPrimitive("assemble"), JsonPrimitive("improve"), JsonPrimitive(0))) {
                val body = buildJsonObject {
                    put("id", "30000000-0000-4000-8000-000000000003"); put("version", 1)
                    put("createdAt", "2026-09-14T00:00:00Z"); put("updatedAt", "2026-09-14T00:00:00Z")
                    put("status", status); mode?.let { put("mode", it) }
                    put("constraints", buildJsonObject {
                        put("ingredientIds", buildJsonArray {}); put("hardExcludedIngredientIds", buildJsonArray {})
                        put("energy", "little"); put("equipmentIds", buildJsonArray {}); put("servings", 1)
                    })
                    put("missingIngredients", buildJsonArray {}); put("changes", buildJsonArray {})
                    put("reasons", buildJsonArray {}); put("catalogRevision", "synthetic-mode-parity")
                }.toString().encodeToByteArray()
                val validStatus = status != "unknown"
                val validMode = mode is JsonPrimitive && mode.isString && mode.content in setOf("cook", "assemble", "improve")
                val expected = validStatus && (validMode || (mode == null && status in setOf("needsConfirmation", "noMatch")))
                assertEquals(expected, server.validateSchema("Plan", body) == BodyValidationResult.Valid, "$status/$mode")
                assertEquals(expected, shared.validateSchema("Plan", body) == ContractValidationResult.Valid, "$status/$mode")
                if (expected && mode == null) {
                    val projected = PlanWire.from(WireDocument.decode(body))
                    assertEquals(WireField.Missing, projected.mode)
                    assertContentEquals(body, projected.document.encodeUtf8())
                }
            }
        }
    }
}
