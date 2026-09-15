package com.feedme.app.mealflow

import com.feedme.contracts.WireDocument
import kotlinx.serialization.json.*
import kotlin.test.*

/** Display projections only. No controllers, consent, request serialization or new defaults. */
class PostReviewClarityPresentationTest {
    @Test fun onlyActualBooleansBecomePlainYesAndNo() {
        assertEquals("Yes", reviewSettingText(JsonPrimitive(true)))
        assertEquals("No", reviewSettingText(JsonPrimitive(false)))
        for (value in listOf(JsonPrimitive("true"), JsonPrimitive("false"), JsonPrimitive(0), JsonPrimitive(1),
            JsonArray(listOf(JsonPrimitive(false))), JsonObject(mapOf("value" to JsonPrimitive(true))))) {
            val text = reviewSettingText(value)
            assertTrue(text.startsWith("Unrecognized value:"))
            assertTrue(text.contains(value.toString()))
        }
    }

    @Test fun absentNullAndFalseAreDifferentChoicesNotFallbackDefaults() {
        assertEquals("Not included", reviewSettingText(null))
        assertEquals("Explicit null", reviewSettingText(JsonNull))
        assertEquals(3, listOf(reviewSettingText(null), reviewSettingText(JsonNull), reviewSettingText(JsonPrimitive(false))).distinct().size)
    }

    @Test fun flatProposedRecipeSavesAndDisclosureAreReadFromTheirOwnFields() {
        val document = doc("""{"allowRecipeSaves":false,"saveDisclosureVersion":"PROPOSED-v1"}""")
        assertEquals(listOf("Allow recipe saves" to "No"), rows(reviewRecipePolicyRows(document)))
        assertEquals(listOf("Choices · disclosure version" to "PROPOSED-v1"), rows(reviewDisclosureVersionRows(document)))
        assertNull(reviewRecipePolicyNotice(document))
    }

    @Test fun canonicalReturnedPostUsesItsExplicitNestedSavePolicy() {
        val document = doc("""{"id":"POST","savePolicy":{"allowFutureSaves":true,"disclosureVersion":"RETURNED-v2","policyVersion":9007199254740993}}""")
        assertEquals(listOf("Allow future recipe saves · server policy" to "Yes"), rows(reviewRecipePolicyRows(document)))
        assertEquals(listOf("Server policy · disclosure version" to "RETURNED-v2"), rows(reviewDisclosureVersionRows(document)))
        assertFalse(reviewRecipePolicyRows(document).any { it.value == "Not included" })
        assertTrue(technicalReviewRows(document).any { it.value == "Number · 9007199254740993" })
    }

    @Test fun bothRepresentationsAreShownEvenWhenTheyAgree() {
        val document = doc("""{"allowRecipeSaves":false,"saveDisclosureVersion":"v1","savePolicy":{"allowFutureSaves":false,"disclosureVersion":"v1"}}""")
        assertEquals(listOf("No", "No"), reviewRecipePolicyRows(document).map { it.value })
        assertEquals(2, reviewDisclosureVersionRows(document).size)
        assertNull(reviewRecipePolicyNotice(document))
    }

    @Test fun contradictoryProposalAndReturnedPolicyNeverReplaceEachOther() {
        val document = doc("""{"allowRecipeSaves":false,"savePolicy":{"allowFutureSaves":true}}""")
        assertEquals(listOf("No", "Yes"), reviewRecipePolicyRows(document).map { it.value })
        assertTrue(assertNotNull(reviewRecipePolicyNotice(document)).contains("neither replaces the other"))
    }

    @Test fun differentDisclosureVersionsAreBothExactAndExplicitlyFlagged() {
        val document = doc("""{"allowRecipeSaves":false,"saveDisclosureVersion":"earlier\nversion","savePolicy":{"allowFutureSaves":false,"disclosureVersion":"later-version"}}""")
        assertEquals(listOf("earlier\nversion", "later-version"), reviewDisclosureVersionRows(document).map { it.value })
        assertNotNull(reviewRecipePolicyNotice(document))
    }

    @Test fun flatNullDoesNotFallBackToNestedFalse() {
        val document = doc("""{"allowRecipeSaves":null,"savePolicy":{"allowFutureSaves":false}}""")
        assertEquals(listOf("Explicit null", "No"), reviewRecipePolicyRows(document).map { it.value })
        assertNotNull(reviewRecipePolicyNotice(document))
    }

    @Test fun malformedPolicyContainerIsNotInterpretedAsASaveChoice() {
        for (value in listOf("true", "false", "0", "[]", "\"policy\"")) {
            val document = doc("""{"savePolicy":$value}""")
            assertEquals("Unrecognized policy value: $value", reviewRecipePolicyRows(document).single().value)
            assertTrue(reviewDisclosureVersionRows(document).isEmpty())
        }
        assertEquals("Explicit null", reviewRecipePolicyRows(doc("""{"savePolicy":null}""")).single().value)
    }

    @Test fun nestedUnknownChoiceDoesNotBecomeFalseOrAnAbsentFlatField() {
        for (value in listOf("\"false\"", "0", "[]", "{}")) {
            val document = doc("""{"savePolicy":{"allowFutureSaves":$value}}""")
            assertEquals("Unrecognized value: $value", reviewRecipePolicyRows(document).single().value)
        }
        assertEquals("Not included", reviewRecipePolicyRows(doc("""{"savePolicy":{}}""")).single().value)
        assertEquals("Not included", reviewRecipePolicyRows(doc("{}")).single().value)
    }

    @Test fun stringBooleanAndBooleanConflictStayDistinctInPrimaryAndDetailedData() {
        val document = doc("""{"allowRecipeSaves":"false","savePolicy":{"allowFutureSaves":false}}""")
        assertEquals(listOf("Unrecognized value: \"false\"", "No"), reviewRecipePolicyRows(document).map { it.value })
        assertNotNull(reviewRecipePolicyNotice(document))
        val data = technicalReviewRows(document).associate { it.label to it.value }
        assertEquals("Text · \"false\"", data["allowRecipeSaves"])
        assertEquals("Boolean · false", data["savePolicy · allowFutureSaves"])
    }

    @Test fun detailedNullEmptyTextArrayObjectAndAbsentRemainDifferent() {
        val data = technicalReviewRows(doc("""{"null":null,"text":"","array":[],"object":{},"false":false}""")).associate { it.label to it.value }
        assertEquals("Null · null", data["null"])
        assertEquals("Text · \"\"", data["text"])
        assertEquals("Array · []", data["array"])
        assertEquals("Object · {}", data["object"])
        assertEquals("Boolean · false", data["false"])
        assertFalse("absent" in data)
    }

    @Test fun detailedOrderedArraysAndNumericLexemesAreNeverRoundedOrSorted() {
        val document = doc("""{ "mediaIds":["SECOND","FIRST"], "n":9007199254740993.000, "small":1e-9 }""")
        val before = document.encodeUtf8()
        val data = technicalReviewRows(document).map { it.label to it.value }
        assertTrue(data.indexOf("mediaIds · 1" to "Text · \"SECOND\"") < data.indexOf("mediaIds · 2" to "Text · \"FIRST\""))
        assertTrue(data.contains("mediaIds" to "Array · 2 items"))
        assertTrue(data.contains("n" to "Number · 9007199254740993.000"))
        assertTrue(data.contains("small" to "Number · 1e-9"))
        assertContentEquals(before, document.encodeUtf8())
    }

    @Test fun readableAttachmentKeepsAllSafetyTextNumbersAndOptionalPresence() {
        val document = doc("""{"personalRecipe":{"ingredients":[{"quantity":9007199254740993.000,"optional":false}],"steps":[{"instruction":"Do not change\n exact safety text ","mandatorySafetyStep":true}]},"confirmedChanges":["second","first"],"future":null}""")
        val before = document.encodeUtf8()
        val data = readableReviewRows(document).associate { it.label to it.value }
        assertEquals("9007199254740993.000", data["Personal recipe · Ingredients · 1 · Quantity"])
        assertEquals("No", data["Personal recipe · Ingredients · 1 · Optional"])
        assertEquals("Yes", data["Personal recipe · Steps · 1 · Required safety step"])
        assertEquals("Do not change\n exact safety text ", data["Personal recipe · Steps · 1 · Instruction"])
        assertEquals("Explicit null", data["future"])
        assertContentEquals(before, document.encodeUtf8())
    }

    @Test fun patchSummaryOmitsOnlyKnownRootClientIdentifierNotContentOrUnknownFields() {
        val document = doc("""{"clientDraftId":"ROOT","caption":"exact\n text ","altText":"","keepOnPlate":false,"future":{"clientDraftId":"NESTED","flag":true}}""")
        val data = readableReviewRows(document, omitClientId = true).associate { it.label to it.value }
        assertFalse("clientDraftId" in data)
        assertEquals("exact\n text ", data["Caption"])
        assertEquals("Empty text (included)", data["Image description"])
        assertEquals("No", data["Keep on My Plate"])
        assertEquals("NESTED", data["future · clientDraftId"])
        assertEquals("Yes", data["future · flag"])
        assertTrue(technicalReviewRows(document).any { it.label == "clientDraftId" && it.value == "Text · \"ROOT\"" })
    }

    @Test fun malformedClientIdentifierIsNotSilentlyHiddenAsNormalMetadata() {
        val data = readableReviewRows(doc("""{"clientDraftId":{"future":"EXACT"}}"""), omitClientId = true)
        assertEquals(listOf("clientDraftId · future" to "EXACT"), rows(data))
    }

    @Test fun unknownRootAudiencePolicyAndAuthorFieldsRemainAlwaysVisible() {
        val document = doc("""{"caption":"caption","id":"known-id","version":2,"audience":{"kind":"self","circleIds":[],"futureAudience":{"value":false}},"savePolicy":{"allowFutureSaves":false,"disclosureVersion":"v1","futurePolicy":["B","A"]},"author":{"futureAuthor":null},"futureRoot":"exact"}""")
        val data = additionalPostReviewRows(document).associate { it.label to it.value }
        assertEquals("exact", data["futureRoot"])
        assertEquals("No", data["Audience · futureAudience · value"])
        assertEquals("B", data["savePolicy · futurePolicy · 1"])
        assertEquals("A", data["savePolicy · futurePolicy · 2"])
        assertEquals("Explicit null", data["author · futureAuthor"])
        assertFalse("id" in data); assertFalse("version" in data)
    }

    @Test fun everyProjectionLeavesFullSourceBytesAndDisclosureContentUntouched() {
        val source = """{ "caption":"🌱\n exact ", "saveDisclosureVersion":" A-v1 ", "allowRecipeSaves":false, "savePolicy":{"allowFutureSaves":true,"disclosureVersion":" B-v2 "}, "mediaIds":["SECOND","FIRST"], "unknown":{"empty":[],"null":null,"string":"true","number":1e-9} }"""
        val document = doc(source); val before = document.encodeUtf8()
        reviewRecipePolicyRows(document); reviewRecipePolicyNotice(document); reviewDisclosureVersionRows(document)
        readableReviewRows(document); technicalReviewRows(document); additionalPostReviewRows(document)
        assertContentEquals(before, document.encodeUtf8())
        assertEquals(source, document.encodeUtf8().decodeToString())
        assertEquals(listOf(" A-v1 ", " B-v2 "), reviewDisclosureVersionRows(document).map { it.value })
    }

    @Test fun unknownNestedMaterialPreservesSourceOrderAndMalformedMetadataIsVisible() {
        val document = doc("""{"first":"A","audience":{"kind":"self","future":["SECOND","FIRST"]},"middle":"B","savePolicy":{"policyVersion":{"unknown":null}},"version":{"future":false},"last":"C"}""")
        assertEquals(listOf("first", "Audience · future · 1", "Audience · future · 2", "middle",
            "savePolicy · policyVersion · unknown", "version · future", "last"), additionalPostReviewRows(document).map { it.label })
        assertEquals(listOf("A", "SECOND", "FIRST", "B", "Explicit null", "No", "C"), additionalPostReviewRows(document).map { it.value })
    }

    private fun doc(source: String) = WireDocument.parse(source)
    private fun rows(values: List<PostReviewRow>) = values.map { it.label to it.value }
}
