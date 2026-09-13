package com.feedme.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class CanonicalSchemaProgramTest {
    private fun accepts(schema: String, input: String, budget: ValidationBudget = ValidationBudget()): Boolean {
        val program = CanonicalSchemaProgram { error("No reference configured") }
        val value = WireDocument.parse(input).detachedJson()
        validateNumberProfile(value, budget)
        return program.compile(Json.parseToJsonElement(schema)).accepts(value, budget)
    }

    @Test fun canonicalCatalogCompilesWithoutDroppingAssertions() { assertNotNull(CanonicalBodyValidator.bundled()) }

    @Test fun integersAreMathematicalNotLexicalAndNeverRounded() {
        for (value in listOf("1", "1.0", "1e400", "9223372036854775808", "-0.0", "100e-2"))
            assertTrue(accepts("{\"type\":\"integer\"}", value), value)
        for (value in listOf("1.00000000000000000001", "1e-400", "true", "\"1\"", "null"))
            assertFalse(accepts("{\"type\":\"integer\"}", value), value)
    }

    @Test fun exactMinimumAndMaximumRetainTinyFractionsAndLargeValues() {
        assertFalse(accepts("{\"minimum\":0.1}", "0.099999999999999999999"))
        assertTrue(accepts("{\"minimum\":0.1}", "0.1"))
        assertFalse(accepts("{\"maximum\":9007199254740992}", "9007199254740993"))
        assertFalse(accepts("{\"minimum\":0}", "-1e-400"))
        assertTrue(accepts("{\"minimum\":0}", "\"-1\"")) // Numeric keyword doesn't constrain strings.
    }

    @Test fun enumAndConstUseStructuralNumericEquality() {
        assertTrue(accepts("{\"enum\":[1]}", "1.00e0"))
        assertFalse(accepts("{\"enum\":[1]}", "true"))
        assertTrue(accepts("{\"const\":{\"a\":1,\"b\":[null,2]}}", "{\"b\":[null,2.0],\"a\":1e0}"))
        assertFalse(accepts("{\"const\":{\"a\":1}}", "{\"a\":1,\"b\":null}"))
        assertTrue(accepts("{\"const\":null}", "null"))
        assertFalse(accepts("{\"const\":null}", "false"))
    }

    @Test fun uniqueItemsUsesNumericObjectAndNestedArrayEquality() {
        val schema = "{\"uniqueItems\":true}"
        for (value in listOf("[1,1.0]", "[-0,0]", "[{\"a\":1,\"b\":2},{\"b\":2.0,\"a\":1e0}]", "[[1,null],[1.00,null]]"))
            assertFalse(accepts(schema, value))
        for (value in listOf("[9007199254740992,9007199254740993]", "[1,true,\"1\",null]", "[{\"a\":null},{}]"))
            assertTrue(accepts(schema, value))
    }

    @Test fun requiredAndNullAreIndependentAndDefaultsNeverFillMissing() {
        val schema = """{"type":"object","properties":{"a":{"type":["string","null"],"default":"x"}},"required":["a"],"additionalProperties":false}"""
        assertFalse(accepts(schema, "{}"))
        assertTrue(accepts(schema, "{\"a\":null}"))
        assertTrue(accepts(schema, "{\"a\":\"\"}"))
        assertFalse(accepts(schema, "{\"a\":1}"))
        assertFalse(accepts(schema, "{\"a\":null,\"extra\":1}"))
    }

    @Test fun additionalPropertiesHasBooleanAndTypedMapSemantics() {
        assertTrue(accepts("{\"additionalProperties\":true}", "{\"any\":[1,null,{}]}"))
        assertFalse(accepts("{\"additionalProperties\":false}", "{\"any\":null}"))
        val typed = "{\"properties\":{\"known\":{\"type\":\"integer\"}},\"additionalProperties\":{\"type\":\"string\"}}"
        assertTrue(accepts(typed, "{\"known\":1,\"other\":\"x\"}"))
        assertFalse(accepts(typed, "{\"other\":1}"))
        assertFalse(accepts(typed, "{\"known\":\"x\"}"))
    }

    @Test fun conditionalBranchesDoNotBecomeExclusiveAndIfMissingPropertyMatches() {
        val schema = """{"allOf":[{"if":{"properties":{"mode":{"const":"improve"}}},"then":{"required":["baseMeal"]},"else":{"required":["recipeId"]}}]}"""
        assertFalse(accepts(schema, "{\"mode\":\"improve\"}"))
        assertTrue(accepts(schema, "{\"mode\":\"improve\",\"baseMeal\":null}"))
        assertFalse(accepts(schema, "{}")) // properties alone doesn't require mode; then still applies.
        assertTrue(accepts(schema, "{\"mode\":\"cook\",\"recipeId\":null}"))
        val either = "{\"anyOf\":[{\"required\":[\"a\"]},{\"required\":[\"b\"]}]}"
        assertTrue(accepts(either, "{\"a\":null,\"b\":null}"))
        assertFalse(accepts(either, "{}"))
        assertTrue(accepts("{\"then\":false,\"else\":false}", "{}")) // Inactive without if.
    }

    @Test fun stringLengthsCountUnicodeScalarsNotCodeUnitsOrGraphemes() {
        val one = "{\"minLength\":1,\"maxLength\":1}"
        assertTrue(accepts(one, "\"😀\""))
        assertFalse(accepts(one, "\"é\""))
        assertTrue(accepts(one, "\"\\u0000\""))
        assertFalse(accepts(one, "\"\""))
    }

    @Test fun arrayBoundsAndItemSchemasApplyToEachElement() {
        val schema = "{\"minItems\":1,\"maxItems\":2,\"items\":{\"type\":\"integer\"}}"
        assertFalse(accepts(schema, "[]"))
        assertTrue(accepts(schema, "[1,2.0]"))
        assertFalse(accepts(schema, "[1,2,3]"))
        assertFalse(accepts(schema, "[1,2.1]"))
    }

    @Test fun pinnedAnchorsNeverAcceptPrefixBeforeFinalLineTerminators() {
        val schema = "{\"pattern\":\"^[a-z0-9_]{3,24}$\"}"
        for (suffix in listOf("\n", "\r", "\u0085", "\u2028", "\u2029")) {
            val json = kotlinx.serialization.json.JsonPrimitive("abc$suffix").toString()
            assertFalse(accepts(schema, json))
        }
        assertTrue(accepts(schema, "\"abc\""))
    }

    @Test fun refSiblingsAreAppliedAndReferencesNeverFetchOrLoop() {
        val program = CanonicalSchemaProgram { reference ->
            assertEquals("#/components/schemas/Number", reference)
            Json.parseToJsonElement("{\"type\":\"number\",\"minimum\":1}").jsonObject
        }
        val rule = program.compile(Json.parseToJsonElement("{\"\$ref\":\"#/components/schemas/Number\",\"maximum\":2}"))
        assertTrue(rule.accepts(Json.parseToJsonElement("1"), ValidationBudget()))
        assertFalse(rule.accepts(Json.parseToJsonElement("3"), ValidationBudget()))
        assertFailsWith<IllegalStateException> { program.compile(Json.parseToJsonElement("{\"\$ref\":\"https://example.com/schema\"}")) }
        val recursive = CanonicalSchemaProgram { Json.parseToJsonElement("{\"\$ref\":\"#/components/schemas/A\"}").jsonObject }
        assertFailsWith<IllegalStateException> { recursive.compile(Json.parseToJsonElement("{\"\$ref\":\"#/components/schemas/A\"}")) }
    }

    @Test fun unsupportedOrMalformedSchemasFailVisiblyInsteadOfPassing() {
        for (schema in listOf("{\"unknown\":true}", "{\"format\":\"email\"}", "{\"type\":\"madeUp\"}",
            "{\"required\":[\"x\",\"x\"]}", "{\"minItems\":-1}", "{\"minimum\":\"1\"}",
            "{\"anyOf\":[]}", "{\"enum\":[1,1.0]}", "{\"pattern\":\".*\"}", "\"true\""))
            assertFailsWith<IllegalStateException> { accepts(schema, "null") }
    }

    @Test fun budgetAndNumericProfileApplyEvenToUnrestrictedContent() {
        assertFailsWith<ContractResourceLimit> { accepts("true", "{\"a\":[1,2]}", ValidationBudget(1)) }
        assertFailsWith<ContractResourceLimit> { accepts("true", "{\"a\":1e10001}") }
        assertTrue(accepts("true", "{\"a\":1.0e10001}"))
    }

    @Test fun bodyBoundaryRejectsSyntaxMissingUnexpectedAndWrongMedia() {
        val validator = CanonicalBodyValidator.bundled()
        fun reason(result: ContractValidationResult) = assertIs<ContractValidationResult.Rejected>(result).reason
        assertEquals(ContractRejectionReason.MISSING_BODY, reason(validator.validateRequest("createCookSession", null, "application/json")))
        assertEquals(ContractRejectionReason.UNEXPECTED_BODY, reason(validator.validateRequest("getServiceHealth", byteArrayOf(), null)))
        assertEquals(ContractRejectionReason.UNKNOWN_SCHEMA, reason(validator.validateSchema("unknown", byteArrayOf())))
        assertEquals(ContractRejectionReason.UNKNOWN_OPERATION, reason(validator.validateResponse("unknown", 200, null, null)))
        assertEquals(ContractRejectionReason.UNKNOWN_STATUS, reason(validator.validateResponse("getServiceHealth", 202, null, null)))
        assertEquals(ContractRejectionReason.INPUT_SYNTAX, reason(validator.validateSchema("Health", "{\"a\":1,\"a\":2}".encodeToByteArray())))
        assertEquals(ContractValidationResult.Valid, validator.validateResponse("deletePost", 204, null, null))
        assertEquals(ContractRejectionReason.UNEXPECTED_BODY, reason(validator.validateResponse("deletePost", 204, "null".encodeToByteArray(), null)))
    }

    @Test fun mediaLexerSupportsQuotedDelimitersAndRejectsMalformedSuffixes() {
        val validator = CanonicalBodyValidator.bundled()
        val body = "{\"planId\":\"123e4567-e89b-12d3-a456-426614174000\"}".encodeToByteArray()
        for (media in listOf("application/json", "APPLICATION/JSON; charset=\"UTF-8\"", "application/json; note=\"x;y\\\"z\""))
            assertEquals(ContractValidationResult.Valid, validator.validateRequest("createCookSession", body, media))
        for (media in listOf("application/json;", "application/json; charset=latin1", "application/json; x=1; X=2", "application/json\r\nx: y", "application/json; x=\"unterminated"))
            assertEquals(ContractRejectionReason.UNSUPPORTED_MEDIA,
                assertIs<ContractValidationResult.Rejected>(validator.validateRequest("createCookSession", body, media)).reason)
    }
}
