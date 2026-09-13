package com.feedme.kitchen

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PrivateBytes
import kotlinx.serialization.json.*
import kotlin.test.*

class KitchenValidationTest {
    @Test fun integralJsonNumbersNormalizeExactlyWithoutFloatingPointRounding() {
        val cases = mapOf("0" to "0", "-0" to "0", "0.000e99999999999999999" to "0",
            "1" to "1", "1.0" to "1", "1e0" to "1", "1E+2" to "100", "12.00e1" to "120",
            "12000e-3" to "12", "0.001e3" to "1", "9007199254740993" to "9007199254740993",
            "9223372036854775808" to "9223372036854775808")
        cases.forEach { (token, expected) -> assertEquals(expected, integerValue(token), token) }
    }

    @Test fun integerNormalizationRejectsMalformedFractionalAndNegativeValues() {
        for (token in listOf("", " 1", "1 ", "+1", "01", "00", "1.", ".1", "1e", "1e+", "NaN", "Infinity",
            "true", "null", "\"1\"", "-1", "-1.0", "-0.01e2", "0.1", "1e-1", "12.01", "1001e-3",
            "1e999999999999999999", "1e-999999999999999999")) invalid { integerValue(token) }
    }

    @Test fun integerMetadataHasExplicit4096DigitLimit() {
        val largest = "9".repeat(4096)
        assertEquals(largest, integerValue(largest))
        assertEquals("1" + "0".repeat(4095), integerValue("1e4095"))
        assertEquals(4096, integerValue("9.9e4095").length)
        invalid { integerValue("9".repeat(4097)) }
        invalid { integerValue("1e4096") }
        invalid { integerValue("1e-4096") }
    }

    @Test fun normalizedVersionComparisonIsExactAboveLongAndDoubleRanges() {
        for ((left, right) in listOf("0" to "1", "9" to "10", "999" to "1000",
            "9007199254740992" to "9007199254740993", "9223372036854775807" to "9223372036854775808",
            "9".repeat(4095) to "1" + "0".repeat(4095))) {
            assertTrue(compareVersions(left, right) < 0)
            assertTrue(compareVersions(right, left) > 0)
            assertEquals(0, compareVersions(left, left))
        }
        assertEquals(0, compareVersions(integerValue("10e1"), integerValue("100.00")))
    }

    @Test fun documentVersionReadsOnlyRootVersionAndKeepsExactWire() {
        val bytes = " { \"snapshot\": { \"version\": 999 }, \"version\": 1.2e1 } \n".encodeToByteArray()
        val document = WireDocument.decode(bytes)
        assertEquals("12", documentVersion(document))
        assertEquals("12", documentVersion(wire("{\"version\":12,\"snapshot\":{\"version\":1}}")))
        assertContentEquals(bytes, document.encodeUtf8())
        assertFails { documentVersion(wire("{\"snapshot\":{\"version\":12}}")) }
    }

    @Test fun documentVersionRejectsZeroStringsNonintegralAndNegativeValues() {
        for (token in listOf("0", "-0", "0e99", "-1", "0.5", "1.01", "\"1\"", "true", "null"))
            invalid { documentVersion(wire("{\"version\":$token}")) }
    }

    @Test fun etagChecksExactNumericRootVersionButPreservesOriginalQuotedHeader() {
        val document = wire("{\"version\":1.2e1,\"snapshot\":{\"version\":99}}")
        assertEquals("\"12\"", checkedEtag(document, "\"12\""))
        assertEquals("\"00012\"", checkedEtag(document, "\"00012\""))
        assertEquals("\"${"0".repeat(252)}12\"", checkedEtag(document, "\"${"0".repeat(252)}12\""))
        invalid { checkedEtag(document, "\"99\"") }
        invalid { checkedEtag(document, "\"13\"") }
    }

    @Test fun missingEtagPermitsReadOnlyWithoutInventingVersionHeader() {
        assertNull(checkedEtag(wire("{\"version\":7}"), null))
        assertNull(checkedEtag(wire("{\"version\":1e3000}"), null))
        assertNull(checkedEtag(wire("{\"nested\":{\"version\":7}}"), null))
    }

    @Test fun invalidEtagSyntaxLengthAndZeroAreRejected() {
        for (etag in listOf("", "7", "'7'", "*", "W/\"7\"", "\"7\", \"8\"", " \"7\"", "\"7\" ",
            "\"7.0\"", "\"7e0\"", "\"+7\"", "\"-7\"", "\"0\"", "\"\"", "\"7\"\n",
            "\"${"0".repeat(254)}7\"")) invalid { checkedEtag(wire("{\"version\":7}"), etag) }
        invalid { checkedEtag(wire("{\"version\":0}"), "\"0\"") }
    }

    @Test fun recipeMaskExcludesOnlyLifecycleReviewFieldsWithoutDroppingStoredWire() {
        val base = recipe()
        val changed = JsonObject(base + mapOf("version" to JsonPrimitive(9), "updatedAt" to JsonPrimitive("2026-09-13T12:00:00Z"),
            "reviewStatus" to JsonPrimitive("recalled"), "recallReasonCode" to JsonPrimitive("safety-check"),
            "reviewedAt" to JsonPrimitive("2026-09-13T11:00:00Z"), "reviewerLabel" to JsonPrimitive("Reviewer B")))
        val original = " \n${changed}\n ".encodeToByteArray()
        val full = WireDocument.decode(original)
        assertEquals(immutableRecipe(wire(base.toString())), immutableRecipe(full))
        assertContentEquals(original, full.encodeUtf8())
        assertEquals("recalled", full.json().jsonObject.getValue("reviewStatus").jsonPrimitive.content)
        assertEquals("safety-check", full.json().jsonObject.getValue("recallReasonCode").jsonPrimitive.content)
        assertEquals(9, full.json().jsonObject.getValue("version").jsonPrimitive.int)
    }

    @Test fun recipeMaskRetainsActualContentRightsAndPreparationEvidence() {
        val base = recipe()
        val immutable = immutableRecipe(wire(base.toString()))
        val changes = mapOf<String, JsonElement>(
            "id" to JsonPrimitive(OTHER_ID), "recipeId" to JsonPrimitive(ID),
            "createdAt" to JsonPrimitive("2026-09-12T00:00:00Z"), "title" to JsonPrimitive("Changed title"),
            "summary" to JsonPrimitive("Changed method"), "contentLicense" to JsonPrimitive("privateCopyOnly"),
            "ingredients" to JsonArray(listOf(JsonObject(mapOf("quantity" to JsonPrimitive(4))))),
            "steps" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive("Different instructions"))))),
            "servings" to JsonPrimitive(8), "equipmentIds" to JsonArray(listOf(JsonPrimitive("oven"))),
            "estimateBasis" to JsonPrimitive("creatorReported"), "estimateNote" to JsonPrimitive("Unverified estimate"),
            "waitingMinutes" to JsonPrimitive(25), "preparationTags" to JsonArray(listOf(JsonPrimitive("batchPrep"))),
        )
        changes.forEach { (key, replacement) ->
            assertNotEquals(immutable, immutableRecipe(wire(JsonObject(base + (key to replacement)).toString())), key)
        }
        assertEquals(base.getValue("contentLicense"), immutable.getValue("contentLicense"))
    }

    @Test fun strictMetadataRequiresExactRootKeysAndRejectsDuplicateMembers() {
        val keys = setOf("version", "id")
        val valid = PrivateJson.decode(bytes("{\"version\":1,\"id\":\"$ID\"}"), keys)
        assertEquals(keys, valid.keys)
        for (text in listOf("{}", "[]", "null", "true", "{\"version\":1}",
            "{\"version\":1,\"id\":\"$ID\",\"extra\":false}",
            "{\"version\":1,\"version\":2,\"id\":\"$ID\"}",
            "{\"version\":1,\"\\u0076ersion\":2,\"id\":\"$ID\"}",
            "{\"version\":1,\"id\":\"$ID\",}")) invalid { PrivateJson.decode(bytes(text), keys) }
    }

    @Test fun strictMetadataRejectsMalformedUtf8LoneSurrogatesAndBounds() {
        invalid { PrivateJson.decode(PrivateBytes(byteArrayOf(0x7b, 0x22, 0x80.toByte(), 0x22, 0x3a, 0x31, 0x7d)), setOf("x")) }
        invalid { PrivateJson.decode(bytes("{\"x\":\"\\uD800\"}"), setOf("x")) }
        invalid { PrivateJson.decode(bytes("{\"x\":\"${"a".repeat(65_536)}\"}"), setOf("x")) }
        invalid { PrivateJson.decode(bytes("{\"x\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}"), setOf("x")) }
        assertFails { PrivateJson.encode(JsonObject(mapOf("x" to JsonPrimitive("\uD800")))) }
    }

    @Test fun metadataEncodingRoundTripsWithoutAliasingCallerBytes() {
        val root = buildJsonObject { put("name", "private value"); put("sequence", 2); put("optional", JsonNull) }
        val encoded = PrivateJson.encode(root)
        val copy = encoded.copyForCodec()
        copy.fill(0)
        assertEquals(root, PrivateJson.decode(encoded, root.keys))
        assertEquals("private value", PrivateJson.string(PrivateJson.decode(encoded, root.keys).getValue("name")))
    }

    @Test fun localMetadataIntegerParserIsStrictAndDoesNotAcceptWireExponentAliases() {
        assertEquals(0L, PrivateJson.long(JsonPrimitive(0)))
        assertEquals(Long.MAX_VALUE, PrivateJson.long(JsonPrimitive(Long.MAX_VALUE)))
        for (text in listOf("\"1\"", "1.0", "1e0", "-0", "-1", "9223372036854775808", "true", "null", "[]", "{}"))
            invalid { PrivateJson.long(Json.parseToJsonElement(text)) }
    }

    @Test fun metadataStringsBooleansArraysAndNullableFieldsRejectWrongTypes() {
        assertEquals("", PrivateJson.string(JsonPrimitive("")))
        assertEquals("value", PrivateJson.nullableString(JsonPrimitive("value")))
        assertNull(PrivateJson.nullableString(JsonNull))
        assertTrue(PrivateJson.boolean(JsonPrimitive(true)))
        assertFalse(PrivateJson.boolean(JsonPrimitive(false)))
        assertEquals(listOf("a", "b"), PrivateJson.strings(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))))
        for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()))) invalid { PrivateJson.string(value) }
        for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive("true"), JsonObject(emptyMap()))) invalid { PrivateJson.boolean(value) }
        invalid { PrivateJson.nullableString(JsonPrimitive(1)) }
        invalid { PrivateJson.strings(JsonPrimitive("a")) }
        invalid { PrivateJson.strings(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(1)))) }
    }

    @Test fun localMetadataHashesAndIdentifiersRequireCanonicalLowercaseSpelling() {
        assertEquals("ab".repeat(32), PrivateJson.hash(JsonPrimitive("ab".repeat(32))))
        assertEquals(ID, PrivateJson.uuid(JsonPrimitive(ID)))
        for (hash in listOf("ab".repeat(31), "ab".repeat(33), "AB".repeat(32), "g".repeat(64), " " + "a".repeat(64)))
            invalid { PrivateJson.hash(JsonPrimitive(hash)) }
        for (id in listOf(ID.uppercase(), "../$ID", "$ID/extra", "not-an-id", ID.replace("-", "")))
            invalid { PrivateJson.uuid(JsonPrimitive(id)) }
    }

    @Test fun wireLongAcceptsExactNonnegativeIntegralRepresentationsThroughLongMaximum() {
        for ((token, expected) in mapOf("0" to 0L, "-0" to 0L, "0.0e100" to 0L, "1.0" to 1L,
            "12e2" to 1200L, "120e-1" to 12L, "0.001e3" to 1L, "9007199254740993" to 9007199254740993L,
            "9223372036854775807" to Long.MAX_VALUE, "9.223372036854775807e18" to Long.MAX_VALUE)) {
            val document = wire("{\"sequence\":$token}")
            val original = document.encodeUtf8()
            assertEquals(expected, wireLong(document, "sequence"), token)
            assertContentEquals(original, document.encodeUtf8())
        }
    }

    @Test fun wireLongRejectsOverflowFractionsStringsAndBelowMinimum() {
        for (token in listOf("9223372036854775808", "1e19", "1e100", "0.1", "1.1", "1e-1", "1001e-3",
            "-1", "\"1\"", "true", "null")) invalid { wireLong(wire("{\"sequence\":$token}"), "sequence") }
        invalid { wireLong(wire("{\"sequence\":0}"), "sequence", 1) }
        invalid { wireLong(wire("{\"sequence\":9}"), "sequence", 10) }
        assertEquals(10L, wireLong(wire("{\"sequence\":1e1}"), "sequence", 10))
    }

    @Test fun wireLongReadsRequestedRootFieldNotNestedLookalike() {
        assertEquals(3L, wireLong(wire("{\"sequence\":3,\"nested\":{\"sequence\":999}}"), "sequence"))
        assertFails { wireLong(wire("{\"nested\":{\"sequence\":3}}"), "sequence") }
    }

    companion object {
        private const val ID = "123e4567-e89b-12d3-a456-426614174001"
        private const val OTHER_ID = "123e4567-e89b-12d3-a456-426614174002"
        private fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        private fun wire(text: String) = WireDocument.decode(text.encodeToByteArray())
        private fun invalid(action: () -> Unit) {
            assertEquals(FailureReason.INVALID_DATA, assertFailsWith<KitchenFailure> { action() }.reason)
        }
        private fun recipe() = buildJsonObject {
            put("id", ID); put("version", 1); put("recipeId", OTHER_ID)
            put("createdAt", "2026-09-13T00:00:00Z"); put("updatedAt", "2026-09-13T01:00:00Z")
            put("title", "Easy rice"); put("summary", "Simple cooking"); put("reviewStatus", "published")
            put("reviewedAt", "2026-09-13T00:30:00Z"); put("reviewerLabel", "Reviewer A")
            put("contentLicense", "catalogRedistributable"); put("servings", 2)
            put("ingredients", JsonArray(emptyList())); put("steps", JsonArray(emptyList()))
            put("equipmentIds", JsonArray(listOf(JsonPrimitive("pan"))))
            put("estimateBasis", "reviewerEstimate"); put("estimateNote", "Preparation included")
            put("waitingMinutes", 5); put("preparationTags", JsonArray(listOf(JsonPrimitive("onePan"))))
        }
    }
}
