package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure new PATCH encoding only; never a retry encoder, actual review or store/queue proof. */
class ReviewedDraftSaveEncoderTest {
    @Test fun unchangedAndExplicitFalseEmptyValuesStayDistinctWithoutInventedFields() {
        val empty = encode(patch(alt = PatchValue.Set(""), remove = PatchValue.Set(false), keep = PatchValue.Set(false)))
        assertEquals(setOf("altText", "removeAttachment", "keepOnPlate"), empty.keys)
        assertEquals("", empty.getValue("altText").jsonPrimitive.content)
        assertEquals(false, empty.getValue("removeAttachment").jsonPrimitive.boolean)
        assertEquals(false, empty.getValue("keepOnPlate").jsonPrimitive.boolean)
        val caption = encode(patch(caption = PatchValue.Set("Caption only")))
        assertEquals(setOf("caption"), caption.keys)
    }
    @Test fun orderedMediaCirclesAndRawUuidSpellingArePreservedInNewRequest() {
        val body = encode(patch(media = PatchValue.Set(listOf(ID2, ID1)), audience = PatchValue.Set(PublicationAudience.Circles(listOf(ID2, ID1))),
            source = PatchValue.Set(ID1)))
        assertEquals(listOf(ID2, ID1), body.getValue("mediaIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(ID2, ID1), body.getValue("audience").jsonObject.getValue("circleIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(ID1, body.getValue("sourcePostId").jsonPrimitive.content)
    }
    @Test fun actualDisclosureTextStaysOutOfWireWhileItsExactVersionIsIncluded() {
        val body = encode(patch(disclosure = PatchValue.Set(PublicationDisclosure("supplied-version", "Private exact supplied text"))))
        assertEquals(setOf("saveDisclosureVersion"), body.keys); assertEquals("supplied-version", body.getValue("saveDisclosureVersion").jsonPrimitive.content)
        assertFalse(body.toString().contains("Private exact supplied text"))
    }
    @Test fun removeAttachmentTrueIsAnExplicitPatchInstructionNotResponseMaterial() {
        val body = encode(patch(remove = PatchValue.Set(true)))
        assertEquals(setOf("removeAttachment"), body.keys); assertTrue(body.getValue("removeAttachment").jsonPrimitive.boolean)
        assertFalse("attachment" in body)
    }
    @Test fun mediaAndDisclosureResourceLimitsRefuseWithoutTruncatingSelection() {
        val limited = ReviewedDraftSaveEncoder(policy(media = 1, disclosure = 32))
        assertEquals(FailureReason.UNAVAILABLE, assertFailsWith<MealFailure> {
            limited.encode(patch(media = PatchValue.Set(listOf(ID2, ID1))))
        }.reason)
        assertEquals(FailureReason.UNAVAILABLE, assertFailsWith<MealFailure> {
            limited.encode(patch(disclosure = PatchValue.Set(PublicationDisclosure("version", "Large disclosed text ".repeat(10)))))
        }.reason)
    }
    @Test fun attachmentSourceAndOrderedChangesStayExactWithoutServerNormalization() {
        val attachment = PublicationAttachment(PublicationAttachmentSource.RecipeVersion(ID1), listOf("Second", "First"),
            AttachmentReviewStatus.REVIEWED, AttachmentRightsBasis.CATALOG_REDISTRIBUTABLE)
        val body = encode(patch(attachment = PatchValue.Set(attachment))).getValue("attachment").jsonObject
        assertEquals(ID1, body.getValue("recipeVersionId").jsonPrimitive.content)
        assertEquals(listOf("Second", "First"), body.getValue("confirmedChanges").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("reviewed", body.getValue("reviewStatus").jsonPrimitive.content)
        assertEquals("catalogRedistributable", body.getValue("rightsBasis").jsonPrimitive.content)
    }
    @Test fun completePersonalRecipeKeepsNestedExactNumbersAndOptionalMaterial() {
        val recipe = WireDocument.parse(buildJsonObject {
            put("title", "Explicit complete personal recipe")
            put("ingredients", JsonArray(listOf(buildJsonObject { put("ingredientId", ID1)
                put("quantity", Json.parseToJsonElement("9007199254740993.000")); put("unit", "g"); put("optional", false) })))
            put("steps", JsonArray(listOf(buildJsonObject { put("stepId", "mix"); put("position", 1); put("instruction", "Mix")
                put("ingredientIds", JsonArray(listOf(JsonPrimitive(ID1)))); put("requiredEquipmentIds", JsonArray(listOf(JsonPrimitive("bowl"))))
                put("mandatorySafetyStep", false) })))
            put("servings", 1); put("activeMinutes", 1); put("totalMinutes", 1); put("utensilCount", 1)
            put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("modes", JsonArray(listOf(JsonPrimitive("assemble"))))
        }.toString())
        val attachment = PublicationAttachment(PublicationAttachmentSource.Personal(recipe), emptyList(),
            AttachmentReviewStatus.PERSONAL, AttachmentRightsBasis.CREATOR_ORIGINAL)
        val actual = encode(patch(attachment = PatchValue.Set(attachment))).getValue("attachment").jsonObject.getValue("personalRecipe")
        assertEquals(recipe.json(), actual)
        assertEquals("9007199254740993.000", actual.jsonObject.getValue("ingredients").jsonArray.single().jsonObject.getValue("quantity").jsonPrimitive.content)
        assertEquals(ID1, actual.jsonObject.getValue("ingredients").jsonArray.single().jsonObject.getValue("ingredientId").jsonPrimitive.content)
    }
    private fun encode(patch: ReviewedDraftPatch) = ReviewedDraftSaveEncoder(policy()).encode(patch).json().jsonObject
    private fun patch(caption: PatchValue<String> = PatchValue.Unchanged, alt: PatchValue<String> = PatchValue.Unchanged,
        media: PatchValue<List<String>> = PatchValue.Unchanged, attachment: PatchValue<PublicationAttachment> = PatchValue.Unchanged,
        remove: PatchValue<Boolean> = PatchValue.Unchanged, audience: PatchValue<PublicationAudience> = PatchValue.Unchanged,
        keep: PatchValue<Boolean> = PatchValue.Unchanged, disclosure: PatchValue<PublicationDisclosure> = PatchValue.Unchanged,
        source: PatchValue<String> = PatchValue.Unchanged) = ReviewedDraftPatch(PatchValue.Unchanged, caption, alt, media,
        attachment, remove, audience, keep, PatchValue.Unchanged, disclosure, source)
    private fun policy(media: Int = 32, disclosure: Int = 8192) = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 64, 64, 8,
        media, 32, 32, 65_536, disclosure, 60_000)
    private companion object {
        const val ID1 = "ABCDEFAB-1111-4111-8111-111111111111"
        const val ID2 = "ABCDEFAB-2222-4222-8222-222222222222"
    }
}
