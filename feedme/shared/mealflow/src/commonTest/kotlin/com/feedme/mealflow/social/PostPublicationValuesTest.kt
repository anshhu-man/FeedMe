package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.MealFailure
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure synthetic value/encoding tests. No principal/provider, live review, queue, store,
 * HTTP, native session or acknowledgement is configured or implied. Defaults below are FIXTURES. */
class PostPublicationValuesTest {
    @Test fun directEncodingContainsExactlyRequiredFieldsWithoutInventedPublicationMetadata() {
        val body = json(encoder().encode(direct(), choices()))
        assertEquals(setOf("caption", "mediaIds", "audience", "keepOnPlate", "allowRecipeSaves", "saveDisclosureVersion", "clientDraftId"), body.keys)
        assertEquals(JsonPrimitive(CLIENT), body["clientDraftId"])
        assertEquals(JsonPrimitive("Synthetic caption"), body["caption"])
        assertEquals(JsonArray(emptyList()), body["mediaIds"])
        assertEquals(buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(emptyList())) }, body["audience"])
        assertEquals(ContractValidationResult.Valid, validator.validateRequest("publishPost", body.toString().encodeToByteArray(), "application/json"))
    }

    @Test fun savedTargetEncodesItsIndivisibleExactPairWithoutIfMatchOrLocalRevision() {
        val version = "9007199254740993"
        val target = saved(version)
        val body = json(encoder().encode(target, choices()))
        assertEquals(JsonPrimitive(DRAFT), body["draftId"])
        assertFalse(body.getValue("draftVersion").jsonPrimitive.isString)
        assertEquals(version, body.getValue("draftVersion").jsonPrimitive.content)
        assertEquals("\"$version\"", target.etag)
        for (key in listOf("etag", "ifMatch", "localRevision", "accepted", "idempotencyKey", "reviewedAt", "disclosureText")) assertFalse(key in body)
    }

    @Test fun exactVersionRejectsNoncanonicalZeroNegativeFractionExponentAndOverflow() {
        for (value in listOf("", "0", "-1", "+1", "01", "1.0", "1e0", " 1", "1\n", "9223372036854775808", "1".repeat(1000)))
            bad { ExactPostVersion(value) }
        for (value in listOf("1", "9007199254740993", "9223372036854775807")) {
            val version = ExactPostVersion(value)
            assertEquals(value, version.decimal); assertEquals(version, ExactPostVersion(value)); assertEquals(version.hashCode(), ExactPostVersion(value).hashCode())
        }
    }

    @Test fun savedTargetRequiresMatchingStrongEtagAndPositiveLogicalRevision() {
        for (etag in listOf("1", "W/\"1\"", "\"01\"", "\"2\"", "\"1.0\"", "\"1\"\n"))
            bad { PublicationTarget.SavedDraft(CLIENT, 1, DRAFT, ExactPostVersion("1"), etag) }
        for (revision in listOf(0L, -1L, Long.MIN_VALUE)) {
            bad { PublicationTarget.DirectLocal(CLIENT, revision) }
            bad { PublicationTarget.SavedDraft(CLIENT, revision, DRAFT, ExactPostVersion("1"), "\"1\"") }
        }
        assertEquals(Long.MAX_VALUE, PublicationTarget.DirectLocal(CLIENT, Long.MAX_VALUE).localRevision)
    }

    @Test fun everyUuidPositionValidatesSyntaxWithoutSilentlyChangingSpelling() {
        val spelling = CLIENT.uppercase()
        assertEquals(spelling, PublicationTarget.DirectLocal(spelling, 1).clientDraftId)
        val cases: List<(String) -> Any> = listOf({ PublicationTarget.DirectLocal(it, 1) },
            { PublicationTarget.SavedDraft(CLIENT, 1, it, ExactPostVersion("1"), "\"1\"") },
            { PublicationAudience.Circles(listOf(it)) }, { PublicationAttachmentSource.RecipeVersion(it) },
            { PublicationAttachmentSource.Plan(it) }, { choices(media = listOf(it)) }, { choices(source = OptionalValue.Present(it)) })
        for (create in cases) for (value in listOf("", "not-an-id", "$CLIENT\n", " $CLIENT", CLIENT.drop(1))) bad { create(value) }
    }

    @Test fun originalUuidSpellingAndSelectionOrderRemainDistinctFromServerNormalization() {
        val target = PublicationTarget.SavedDraft(CLIENT.uppercase(), 1, DRAFT.uppercase(), ExactPostVersion("17"), "\"17\"")
        val selected = choices(media = listOf(MEDIA_B.uppercase(), MEDIA_A.uppercase()),
            audience = PublicationAudience.Circles(listOf(CIRCLE_B.uppercase(), CIRCLE_A.uppercase())),
            attachment = OptionalValue.Present(catalog(RECIPE.uppercase())), source = OptionalValue.Present(SOURCE.uppercase()))
        val body = json(encoder().encode(target, selected))
        assertEquals(strings(listOf(MEDIA_B.uppercase(), MEDIA_A.uppercase())), body["mediaIds"])
        assertEquals(strings(listOf(CIRCLE_B.uppercase(), CIRCLE_A.uppercase())), body.getValue("audience").jsonObject["circleIds"])
        assertEquals(JsonPrimitive(SOURCE.uppercase()), body["sourcePostId"])
        assertEquals(JsonPrimitive(RECIPE.uppercase()), body.getValue("attachment").jsonObject["recipeVersionId"])
        assertEquals(JsonPrimitive(CLIENT.uppercase()), body["clientDraftId"])
        assertEquals(JsonPrimitive(DRAFT.uppercase()), body["draftId"])
    }

    @Test fun altAbsenceEmptyAndPresentProduceThreeDistinctWireBodies() {
        val absent = json(encoder().encode(direct(), choices()))
        val empty = json(encoder().encode(direct(), choices(alt = OptionalValue.Present(""))))
        val present = json(encoder().encode(direct(), choices(alt = OptionalValue.Present("Meaningful description"))))
        assertFalse("altText" in absent); assertEquals(JsonPrimitive(""), empty["altText"])
        assertEquals(JsonPrimitive("Meaningful description"), present["altText"])
        assertTrue(listOf(absent, empty, present).distinct().size == 3)
    }

    @Test fun captionAndAltCountUnicodeScalarsNotUtf16Units() {
        val text = "🌱".repeat(500)
        val body = json(encoder().encode(direct(), choices(caption = text, alt = OptionalValue.Present(text))))
        assertEquals(text, body.getValue("caption").jsonPrimitive.content)
        assertEquals(text, body.getValue("altText").jsonPrimitive.content)
        bad { choices(caption = "🌱".repeat(501)) }
        bad { choices(alt = OptionalValue.Present("🌱".repeat(501))) }
        bad { choices(caption = "x".repeat(501)) }
    }

    @Test fun invalidUnicodeIsRejectedWithoutReplacementOrPrivateDiagnostics() {
        for (text in listOf("\uD800", "\uDC00", "a\uD800b", "a\uDC00b")) {
            bad { choices(caption = text) }; bad { choices(alt = OptionalValue.Present(text)) }
            bad { PublicationDisclosure("v", text) }; bad { PublicationDisclosure(text, "Actual synthetic policy text") }
            bad { catalog(changes = listOf(text)) }
        }
    }

    @Test fun disclosureRequiresActualNonblankTextAndVersionButAddsOnlyVersionToWire() {
        for (empty in listOf("", " ", "\n")) {
            bad { PublicationDisclosure(empty, "Synthetic disclosure") }; bad { PublicationDisclosure("v", empty) }
        }
        bad { PublicationDisclosure("v\n1", "Synthetic disclosure") }
        val actual = PublicationDisclosure("version-α", "Keep this exact disclosure.\nIt is test data, not authority.")
        val body = encoder().encode(direct(), choices(disclosure = actual)).encodeUtf8().decodeToString()
        assertTrue(body.contains("version-α")); assertFalse(body.contains("Keep this exact disclosure"))
        assertEquals("Keep this exact disclosure.\nIt is test data, not authority.", actual.text)
    }

    @Test fun selfAndCircleChoicesHaveNoClientBindingAuthorityField() {
        val selected = choices(audience = PublicationAudience.Circles(listOf(CIRCLE_A)))
        val audience = json(encoder().encode(direct(), selected)).getValue("audience").jsonObject
        assertEquals(setOf("kind", "circleIds"), audience.keys)
        assertEquals(JsonPrimitive("circles"), audience["kind"])
        bad { PublicationAudience.Circles(emptyList()) }
    }

    @Test fun normalizedDuplicateMediaOrCirclesAreRejectedNotDeduplicated() {
        for (ids in listOf(listOf(MEDIA_A, MEDIA_A), listOf(MEDIA_A, MEDIA_A.uppercase()))) bad { choices(media = ids) }
        for (ids in listOf(listOf(CIRCLE_A, CIRCLE_A), listOf(CIRCLE_A, CIRCLE_A.uppercase()))) bad { PublicationAudience.Circles(ids) }
    }

    @Test fun independentKeepOnPlateAndSavePermissionValuesAreNeverDefaulted() {
        for (keep in listOf(false, true)) for (allow in listOf(false, true)) {
            val body = json(encoder().encode(direct(), choices(keep = keep, allow = allow, attachment = OptionalValue.Present(catalog()))))
            assertEquals(JsonPrimitive(keep), body["keepOnPlate"]); assertEquals(JsonPrimitive(allow), body["allowRecipeSaves"])
        }
        bad { choices(allow = true) }
    }

    @Test fun exactlyOneAttachmentSourceVariantIsEncodedWithAllOrderedChanges() {
        val sources = listOf(PublicationAttachmentSource.RecipeVersion(RECIPE), PublicationAttachmentSource.Plan(PLAN),
            PublicationAttachmentSource.Personal(WireDocument.parse(recipe().toString())))
        for (source in sources) {
            val personal = source is PublicationAttachmentSource.Personal
            val attachment = PublicationAttachment(source, listOf("second", "first", "second"),
                if (personal) AttachmentReviewStatus.PERSONAL else AttachmentReviewStatus.REVIEWED,
                if (personal) AttachmentRightsBasis.CREATOR_ORIGINAL else AttachmentRightsBasis.CATALOG_REDISTRIBUTABLE)
            val body = json(encoder().encode(direct(), choices(attachment = OptionalValue.Present(attachment)))).getValue("attachment").jsonObject
            assertEquals(1, listOf("recipeVersionId", "planId", "personalRecipe").count { it in body })
            assertEquals(strings(listOf("second", "first", "second")), body["confirmedChanges"])
            assertEquals(JsonPrimitive(if (personal) "personal" else "reviewed"), body["reviewStatus"])
            assertEquals(JsonPrimitive(if (personal) "creatorOriginal" else "catalogRedistributable"), body["rightsBasis"])
        }
    }

    @Test fun descriptorContradictionsAreInvalidValuesNotAuthorityGrants() {
        for (source in listOf(PublicationAttachmentSource.RecipeVersion(RECIPE), PublicationAttachmentSource.Plan(PLAN))) {
            bad { PublicationAttachment(source, emptyList(), AttachmentReviewStatus.PERSONAL, AttachmentRightsBasis.CATALOG_REDISTRIBUTABLE) }
            bad { PublicationAttachment(source, emptyList(), AttachmentReviewStatus.REVIEWED, AttachmentRightsBasis.CREATOR_ORIGINAL) }
        }
        val personal = PublicationAttachmentSource.Personal(WireDocument.parse(recipe().toString()))
        bad { PublicationAttachment(personal, emptyList(), AttachmentReviewStatus.REVIEWED, AttachmentRightsBasis.CREATOR_ORIGINAL) }
        bad { PublicationAttachment(personal, emptyList(), AttachmentReviewStatus.PERSONAL, AttachmentRightsBasis.CATALOG_REDISTRIBUTABLE) }
    }

    @Test fun fullPersonalRecipeAndExactNumericTokensSurviveNewBodyEncoding() {
        val personal = recipe().with("summary", JsonPrimitive("Complete synthetic recipe"))
            .with("servings", number("0.10000000000000000001")).with("activeMinutes", number("9007199254740993"))
            .with("scalingMin", number("0.1")).with("scalingMax", number("9e1"))
            .with("waitingMinutes", number("3")).with("cleanupMinutes", number("4"))
            .with("tasteTags", strings(listOf("savory", "bright"))).with("preparationTags", strings(listOf("oneBowl")))
            .with("estimateBasis", JsonPrimitive("creatorReported")).with("estimateNote", JsonPrimitive("Synthetic estimate"))
        val source = PublicationAttachmentSource.Personal(WireDocument.parse(" \n" + personal.toString()))
        val attachment = PublicationAttachment(source, emptyList(), AttachmentReviewStatus.PERSONAL, AttachmentRightsBasis.CREATOR_ORIGINAL)
        val encoded = json(encoder().encode(direct(), choices(attachment = OptionalValue.Present(attachment)))).getValue("attachment").jsonObject.getValue("personalRecipe")
        assertEquals(personal, encoded)
        assertEquals("9007199254740993", encoded.jsonObject.getValue("activeMinutes").jsonPrimitive.content)
        assertEquals("0.10000000000000000001", encoded.jsonObject.getValue("servings").jsonPrimitive.content)
        assertEquals("9e1", encoded.jsonObject.getValue("scalingMax").jsonPrimitive.content)
    }

    @Test fun personalRecipeDoesNotDropUnknownFieldsGuessMissingFieldsOrNormalizeNestedIds() {
        for (body in listOf(recipe().without("ingredients"), recipe().with("unowned", JsonPrimitive("field")),
            recipe().with("servings", JsonPrimitive("1")), recipe().with("title", JsonNull)))
            bad { PublicationAttachmentSource.Personal(WireDocument.parse(body.toString())) }
        val body = recipe(ingredient = MEDIA_A.uppercase())
        val source = PublicationAttachmentSource.Personal(WireDocument.parse(body.toString()))
        val value = PublicationAttachment(source, emptyList(), AttachmentReviewStatus.PERSONAL, AttachmentRightsBasis.CREATOR_ORIGINAL)
        val actual = json(encoder().encode(direct(), choices(attachment = OptionalValue.Present(value)))).getValue("attachment").jsonObject.getValue("personalRecipe")
        assertEquals(body, actual)
    }

    @Test fun absentAttachmentAndSourceAreOmittedRatherThanEncodedNull() {
        val absent = json(encoder().encode(direct(), choices()))
        assertFalse("attachment" in absent); assertFalse("sourcePostId" in absent)
        val actual = json(encoder().encode(direct(), choices(attachment = OptionalValue.Present(catalog()), source = OptionalValue.Present(SOURCE))))
        assertEquals(JsonPrimitive(SOURCE), actual["sourcePostId"]); assertTrue(actual["attachment"] is JsonObject)
    }

    @Test fun choicesAudienceAndAttachmentDefensivelySnapshotInputAndExposedLists() {
        val media = mutableListOf(MEDIA_A, MEDIA_B); val circles = mutableListOf(CIRCLE_A, CIRCLE_B); val changes = mutableListOf("one", "two")
        val audience = PublicationAudience.Circles(circles); val attachment = catalog(changes = changes)
        val selected = choices(media = media, audience = audience, attachment = OptionalValue.Present(attachment))
        val original = encoder().encode(direct(), selected).encodeUtf8()
        media.reverse(); circles.reverse(); changes.reverse()
        mutateCopy(selected.orderedMediaIds); mutateCopy(audience.orderedCircleIds); mutateCopy(attachment.confirmedChanges)
        assertContentEquals(original, encoder().encode(direct(), selected).encodeUtf8())
        assertEquals(listOf(MEDIA_A, MEDIA_B), selected.orderedMediaIds)
        assertEquals(listOf(CIRCLE_A, CIRCLE_B), audience.orderedCircleIds)
        assertEquals(listOf("one", "two"), attachment.confirmedChanges)
    }

    @Test fun personalWireDocumentAndEncoderOutputExposeNoMutableBackingBytes() {
        val raw = recipe().toString().encodeToByteArray(); val document = WireDocument.decode(raw)
        val source = PublicationAttachmentSource.Personal(document); raw.fill(0); source.recipeDraft.encodeUtf8().fill(0)
        val selected = choices(attachment = OptionalValue.Present(PublicationAttachment(source, emptyList(), AttachmentReviewStatus.PERSONAL, AttachmentRightsBasis.CREATOR_ORIGINAL)))
        val result = encoder().encode(direct(), selected); val before = result.encodeUtf8(); result.encodeUtf8().fill(0)
        assertContentEquals(before, result.encodeUtf8()); assertEquals(recipe(), json(source.recipeDraft))
    }

    @Test fun repeatedEncodingIsDeterministicAndDoesNotConstructCommandOrReviewFields() {
        val target = direct(); val selected = choices(alt = OptionalValue.Present(""), source = OptionalValue.Present(SOURCE))
        val first = encoder().encode(target, selected)
        repeat(3) { assertContentEquals(first.encodeUtf8(), encoder().encode(target, selected).encodeUtf8()) }
        assertEquals(CLIENT, target.clientDraftId); assertEquals(7L, target.localRevision)
        assertTrue(json(first).keys.none { it in setOf("commandId", "idempotencyKey", "reviewToken", "lease", "owner", "consent") })
    }

    @Test fun patchUnchangedFalseEmptyAndRemovalRemainDistinctDataValues() {
        val unchanged = patch(); val explicit = patch(caption = PatchValue.Set(""), alt = PatchValue.Set(""), media = PatchValue.Set(emptyList()),
            remove = PatchValue.Set(false), keep = PatchValue.Set(false), allow = PatchValue.Set(false))
        assertSame(PatchValue.Unchanged, unchanged.caption); assertSame(PatchValue.Unchanged, unchanged.removeAttachment)
        assertEquals("", (explicit.caption as PatchValue.Set).value); assertEquals("", (explicit.altText as PatchValue.Set).value)
        assertEquals(emptyList(), (explicit.orderedMediaIds as PatchValue.Set).value)
        assertFalse((explicit.removeAttachment as PatchValue.Set).value)
        assertFalse((explicit.keepOnPlate as PatchValue.Set).value); assertFalse((explicit.allowRecipeSaves as PatchValue.Set).value)
        assertTrue((patch(remove = PatchValue.Set(true)).removeAttachment as PatchValue.Set).value)
    }

    @Test fun patchMediaListIsDetachedOnEntryAndEveryExposure() {
        val ids = mutableListOf(MEDIA_A, MEDIA_B); val change = patch(media = PatchValue.Set(ids))
        ids.reverse(); mutateCopy((change.orderedMediaIds as PatchValue.Set).value)
        assertEquals(listOf(MEDIA_A, MEDIA_B), (change.orderedMediaIds as PatchValue.Set).value)
        assertNotSame(change.orderedMediaIds, change.orderedMediaIds)
    }

    @Test fun patchRejectsConflictingAttachmentRemovalButDoesNotInventBaseline() {
        bad { patch(attachment = PatchValue.Set(catalog()), remove = PatchValue.Set(true)) }
        assertTrue(patch(attachment = PatchValue.Set(catalog()), remove = PatchValue.Set(false)).attachment is PatchValue.Set)
        assertTrue(patch(allow = PatchValue.Set(true)).allowRecipeSaves is PatchValue.Set)
        bad { patch(caption = PatchValue.Set("x".repeat(501))) }; bad { patch(alt = PatchValue.Set("\uD800")) }
        bad { patch(media = PatchValue.Set(listOf(MEDIA_A, MEDIA_A.uppercase()))) }
    }

    @Test fun exactOriginalByteBoundaryRefusesRatherThanTruncates() {
        val selected = choices(caption = "\"\n".repeat(100))
        val size = encoder().encode(direct(), selected).encodeUtf8().size
        assertEquals(size, encoder(policy(original = size)).encode(direct(), selected).encodeUtf8().size)
        unavailable { encoder(policy(original = size - 1)).encode(direct(), selected) }
    }

    @Test fun exactAttachmentByteBoundaryCountsEscapesAndCompleteSourceFields() {
        val value = catalog(changes = listOf("\"\n☕"))
        val selected = choices(attachment = OptionalValue.Present(value))
        val attachment = json(encoder().encode(direct(), selected)).getValue("attachment").toString().encodeToByteArray().size
        encoder(policy(attachment = attachment)).encode(direct(), selected)
        unavailable { encoder(policy(attachment = attachment - 1)).encode(direct(), selected) }
    }

    @Test fun exactDisclosureByteBoundaryCountsBothDisplayStringsAndJsonEscapes() {
        val value = PublicationDisclosure("v-α", "Actual \"synthetic\"\n🌱 text")
        val size = buildJsonObject { put("version", value.version); put("text", value.text) }.toString().encodeToByteArray().size
        encoder(policy(disclosure = size)).encode(direct(), choices(disclosure = value))
        unavailable { encoder(policy(disclosure = size - 1)).encode(direct(), choices(disclosure = value)) }
    }

    @Test fun configuredMediaCircleAndConfirmedChangeCountsAreRequiredAndEnforced() {
        unavailable { encoder(policy(media = 1)).encode(direct(), choices(media = listOf(MEDIA_A, MEDIA_B))) }
        unavailable { encoder(policy(circles = 1)).encode(direct(), choices(audience = PublicationAudience.Circles(listOf(CIRCLE_A, CIRCLE_B)))) }
        unavailable { encoder(policy(changes = 1)).encode(direct(), choices(attachment = OptionalValue.Present(catalog(changes = listOf("a", "b"))))) }
        encoder(policy(media = 2, circles = 2, changes = 2)).encode(direct(), choices(media = listOf(MEDIA_A, MEDIA_B),
            audience = PublicationAudience.Circles(listOf(CIRCLE_A, CIRCLE_B)), attachment = OptionalValue.Present(catalog(changes = listOf("a", "b")))))
    }

    @Test fun largeRepeatedStringsAreRefusedBeforeUnboundedJsonConstruction() {
        val large = "x".repeat(65_536)
        unavailable { encoder().encode(direct(), choices(attachment = OptionalValue.Present(catalog(changes = List(16) { large })))) }
        bad { catalog(changes = List(17) { large }) }
        unavailable { encoder(policy(disclosure = 100)).encode(direct(), choices(disclosure = PublicationDisclosure("v", large))) }
    }

    @Test fun policyBoundsAreExplicitPositiveAndConsistentWithoutProductionDefaults() {
        for (invalid in listOf(0, -1, 65_537)) bad { policy(original = invalid) }
        for (invalid in listOf(0, -1, 262_145)) bad { policy(response = invalid) }
        for (invalid in listOf(0, -1, 4097)) {
            bad { policy(media = invalid) }; bad { policy(circles = invalid) }; bad { policy(changes = invalid) }
            bad { policy(roots = invalid) }; bad { policy(ids = invalid) }; bad { policy(remainders = invalid) }
        }
        for (invalid in listOf(0L, -1L, 300_001L)) bad { policy(lifetime = invalid) }
        bad { policy(record = 0) }; bad { policy(record = 1_048_577) }
        bad { policy(record = 100, original = 200, response = 100, attachment = 10, disclosure = 10) }
        bad { policy(record = 100, original = 100, response = 200, attachment = 10, disclosure = 10) }
        bad { policy(attachment = 65_537) }; bad { policy(disclosure = 1_048_577) }
        bad { policy(attachment = 0) }; bad { policy(disclosure = 0) }
    }

    @Test fun allContentBearingValueAndEncoderDiagnosticsAreRedacted() {
        val objects: List<Any> = listOf(OptionalValue.Present("PRIVATE_MARKER"), PatchValue.Set("PRIVATE_MARKER"), ExactPostVersion("9007199254740993"),
            PublicationAudience.Circles(listOf(CIRCLE_A)), PublicationAttachmentSource.RecipeVersion(RECIPE), PublicationAttachmentSource.Plan(PLAN),
            PublicationAttachmentSource.Personal(WireDocument.parse(recipe().toString())), catalog(changes = listOf("PRIVATE_MARKER")),
            PublicationDisclosure("PRIVATE_MARKER", "PRIVATE_MARKER"), choices(caption = "PRIVATE_MARKER"), direct(), saved("1"), patch(caption = PatchValue.Set("PRIVATE_MARKER")), policy(), encoder())
        for (value in objects) {
            assertFalse(value.toString().contains("PRIVATE_MARKER")); assertFalse(value.toString().contains(CLIENT))
            assertFalse(value.toString().contains(RECIPE)); assertFalse(value.toString().contains("9007199254740993"))
            assertTrue(value.toString().contains("redacted"))
        }
    }

    private fun choices(caption: String = "Synthetic caption", alt: OptionalValue<String> = OptionalValue.Absent,
        media: List<String> = emptyList(), audience: PublicationAudience = PublicationAudience.OnlyYou, keep: Boolean = false,
        attachment: OptionalValue<PublicationAttachment> = OptionalValue.Absent, allow: Boolean = false,
        disclosure: PublicationDisclosure = PublicationDisclosure("test-disclosure", "Synthetic required disclosure"), source: OptionalValue<String> = OptionalValue.Absent) =
        ReviewedPostChoices(caption, alt, media, audience, keep, attachment, allow, disclosure, source)
    private fun direct() = PublicationTarget.DirectLocal(CLIENT, 7)
    private fun saved(version: String) = PublicationTarget.SavedDraft(CLIENT, 7, DRAFT, ExactPostVersion(version), "\"$version\"")
    private fun catalog(id: String = RECIPE, changes: List<String> = emptyList()) = PublicationAttachment(
        PublicationAttachmentSource.RecipeVersion(id), changes, AttachmentReviewStatus.REVIEWED, AttachmentRightsBasis.CATALOG_REDISTRIBUTABLE)
    private fun policy(record: Int = 1_048_576, original: Int = 65_536, response: Int = 262_144, roots: Int = 64, ids: Int = 64,
        remainders: Int = 64, media: Int = 20, circles: Int = 20, changes: Int = 20, attachment: Int = minOf(original, 65_536),
        disclosure: Int = 65_536, lifetime: Long = 60_000) =
        PostPublicationClientPolicy(record, original, response, roots, ids, remainders, media, circles, changes, attachment, disclosure, lifetime)
    private fun encoder(policy: PostPublicationClientPolicy = policy()) = PostPublicationEncoder(policy)
    private fun patch(caption: PatchValue<String> = PatchValue.Unchanged, alt: PatchValue<String> = PatchValue.Unchanged,
        media: PatchValue<List<String>> = PatchValue.Unchanged, attachment: PatchValue<PublicationAttachment> = PatchValue.Unchanged,
        remove: PatchValue<Boolean> = PatchValue.Unchanged, keep: PatchValue<Boolean> = PatchValue.Unchanged, allow: PatchValue<Boolean> = PatchValue.Unchanged) =
        ReviewedDraftPatch(PatchValue.Unchanged, caption, alt, media, attachment, remove, PatchValue.Unchanged, keep, allow, PatchValue.Unchanged, PatchValue.Unchanged)
    private fun recipe(ingredient: String = MEDIA_A) = buildJsonObject {
        put("title", "Synthetic full recipe")
        put("ingredients", JsonArray(listOf(buildJsonObject { put("ingredientId", ingredient); put("quantity", number("9007199254740993.000")); put("unit", "g"); put("optional", false) })))
        put("steps", JsonArray(listOf(buildJsonObject { put("stepId", "mix"); put("position", 1); put("instruction", "Mix")
            put("ingredientIds", strings(listOf(ingredient))); put("requiredEquipmentIds", strings(listOf("bowl"))); put("mandatorySafetyStep", false) })))
        put("servings", 1); put("activeMinutes", 1); put("totalMinutes", 1); put("utensilCount", 1)
        put("equipmentIds", strings(listOf("bowl"))); put("modes", strings(listOf("assemble")))
    }
    private fun mutateCopy(values: List<String>) { if (values is MutableList<*>) { try { values.clear() } catch (_: UnsupportedOperationException) { } } }
    private fun json(document: WireDocument) = Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    private fun JsonObject.with(field: String, value: JsonElement) = JsonObject(this + (field to value))
    private fun JsonObject.without(field: String) = JsonObject(filterKeys { it != field })
    private fun number(value: String) = Json.parseToJsonElement(value)
    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun bad(action: () -> Any?) {
        val failure = assertFailsWith<IllegalArgumentException> { action() }
        assertEquals("Invalid publication value", failure.message)
    }
    private fun unavailable(action: () -> Any?) = assertEquals(FailureReason.UNAVAILABLE, assertFailsWith<MealFailure> { action() }.reason)
    private val validator = CanonicalBodyValidator.bundled()
    private companion object {
        const val CLIENT = "bbbbbbbb-2222-4222-8222-222222222222"
        const val DRAFT = "dddddddd-4444-4444-8444-444444444444"
        const val SOURCE = "ffffffff-6666-4666-8666-666666666666"
        const val RECIPE = "aaaaaaaa-7777-4777-8777-777777777777"
        const val PLAN = "bbbbbbbb-8888-4888-8888-888888888888"
        const val MEDIA_A = "cccccccc-9999-4999-8999-999999999999"
        const val MEDIA_B = "dddddddd-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val CIRCLE_A = "eeeeeeee-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val CIRCLE_B = "ffffffff-cccc-4ccc-8ccc-cccccccccccc"
    }
}
