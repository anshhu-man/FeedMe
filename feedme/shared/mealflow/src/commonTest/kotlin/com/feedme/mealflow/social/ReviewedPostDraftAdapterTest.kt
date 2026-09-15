package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.MealFailure
import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic canonical fixtures exercise pure transformation only, not transport, permission,
 * journal migration, review consent, replay admission, queue provenance or acknowledgement. */
class ReviewedPostDraftAdapterTest {
    @Test fun projectionIncludesCompleteReviewedChangesButNoInventedServerTimestamps() {
        val patch = buildJsonObject {
            put("clientDraftId", CLIENT); put("caption", "New caption"); put("altText", "")
            put("mediaIds", strings(MEDIA_B, MEDIA_A)); put("audience", circles(CIRCLE_B, CIRCLE_A))
            put("attachment", catalog(RECIPE)); put("allowRecipeSaves", true); put("keepOnPlate", true)
            put("saveDisclosureVersion", "Explicit synthetic disclosure"); put("sourcePostId", SOURCE)
        }
        val expected = after().merge(patch.without("clientDraftId")).without("updatedAt", "expiresAt")
        assertEquals(expected, json(adapter.expectedFields(call(patch), doc(before()), TAG)))
        assertEquals(number("2"), expected["version"])
        assertEquals(JsonPrimitive(CLIENT), expected["clientDraftId"])
    }

    @Test fun actualReceiptKeepsCompleteRawResponseAndStrongOriginalEtag() {
        val baselineRaw = " \n" + before().toString() + "\n"
        val baseline = WireDocument.parse(baselineRaw)
        val original = call(patch("caption", JsonPrimitive("new")), raw = " { \"caption\" : \"new\" } ")
        val actual = after().with("caption", JsonPrimitive("new"))
        val raw = "\n " + JsonObject(actual.entries.reversed().associate { it.toPair() }) + " \n"
        val result = adapter.receipt(original, baseline, TAG, reply(raw = raw))
        assertContentEquals(raw.encodeToByteArray(), result.document.encodeUtf8())
        assertEquals("\"2\"", result.etag)
        assertContentEquals(baselineRaw.encodeToByteArray(), baseline.encodeUtf8())
        assertContentEquals(" { \"caption\" : \"new\" } ".encodeToByteArray(), original.body!!.copyForCodec())
        assertEquals(TAG, original.ifMatch)
    }

    @Test fun emptyPatchIsARealVersionedUpdateWithoutInventingNewSelections() {
        val result = adapter.receipt(call(), doc(before()), TAG, reply())
        assertEquals(after(), json(result.document))
        assertEquals(empty(), json(WireDocument.decode(call().body!!.copyForCodec())))
    }

    @Test fun removeAttachmentTrueFalseAndAbsentFollowTheActualServiceTransformation() {
        val baseline = before().with("attachment", catalog(RECIPE))
        for (flag in listOf<JsonElement?>(null, JsonPrimitive(false), JsonPrimitive(true))) {
            val original = call(empty().optional("removeAttachment", flag))
            val actual = if (flag == JsonPrimitive(true)) after(baseline).without("attachment") else after(baseline)
            adapter.receipt(original, doc(baseline), TAG, reply(actual))
            adapter.possibleOriginalResult(original, doc(baseline), TAG, doc(actual), "\"2\"")
            assertEquals(actual.without("updatedAt", "expiresAt"), json(adapter.expectedFields(original, doc(baseline), TAG)))
            assertFalse("removeAttachment" in json(adapter.expectedFields(original, doc(baseline), TAG)))
        }
    }

    @Test fun falseOrAbsentRemovalNeverSynthesizesAnAttachmentOrNull() {
        for (patch in listOf(empty(), patch("removeAttachment", JsonPrimitive(false)), patch("removeAttachment", JsonPrimitive(true)))) {
            val result = adapter.receipt(call(patch), doc(before()), TAG, reply())
            assertFalse("attachment" in json(result.document))
            invalid { adapter.receipt(call(patch), doc(before()), TAG, reply(after().with("attachment", JsonNull))) }
        }
    }

    @Test fun catalogPlanAndPersonalReplacementWorkWithFalseOrAbsentRemoval() {
        val baseline = before().with("attachment", catalog(RECIPE))
        for (attachment in listOf(catalog(PLAN, plan = true), personal(recipe()), catalog(SOURCE))) {
            for (flag in listOf<JsonElement?>(null, JsonPrimitive(false))) {
                val patch = patch("attachment", attachment).optional("removeAttachment", flag)
                adapter.receipt(call(patch), doc(baseline), TAG, reply(after(baseline).with("attachment", attachment)))
            }
        }
    }

    @Test fun replacementWithTrueIsRejectedBeforeAnyExpectedResultOrReceipt() {
        val original = call(patch("attachment", catalog(RECIPE)).with("removeAttachment", JsonPrimitive(true)))
        invalid { adapter.expectedFields(original, doc(before()), TAG) }
        invalid { adapter.receipt(original, doc(before()), TAG, reply()) }
        invalid { adapter.possibleOriginalResult(original, doc(before()), TAG, doc(after()), "\"2\"") }
    }

    @Test fun removalPreservesUnrelatedSourceDisclosureAndSaveSelection() {
        val baseline = before().with("attachment", catalog(RECIPE)).with("sourcePostId", JsonPrimitive(SOURCE.uppercase()))
            .with("saveDisclosureVersion", JsonPrimitive("historic disclosure")).with("allowRecipeSaves", JsonPrimitive(true))
        val original = call(patch("removeAttachment", JsonPrimitive(true)))
        val actual = after(baseline).without("attachment")
        adapter.receipt(original, doc(baseline), TAG, reply(actual))
        for (field in listOf("sourcePostId", "saveDisclosureVersion", "allowRecipeSaves"))
            invalid { adapter.receipt(original, doc(baseline), TAG, reply(actual.without(field))) }
        // Correlation does not invent an allow-saves=false PATCH or grant source/save authority.
        assertEquals(JsonPrimitive(true), json(adapter.expectedFields(original, doc(baseline), TAG))["allowRecipeSaves"])
    }

    @Test fun echoedRemovalFlagAndRetainedRemovedAttachmentAreRejected() {
        val baseline = before().with("attachment", catalog(RECIPE))
        val original = call(patch("removeAttachment", JsonPrimitive(true)))
        invalid { adapter.receipt(original, doc(baseline), TAG, reply(after(baseline))) }
        invalid { adapter.receipt(original, doc(baseline), TAG, reply(after(baseline).without("attachment").with("removeAttachment", JsonPrimitive(true)))) }
    }

    @Test fun omittedAltPreservesAbsenceEmptyAndPresentAcrossAllNineResponsePairs() {
        val values = listOf<JsonElement?>(null, JsonPrimitive(""), JsonPrimitive("Existing description"))
        for (beforeAlt in values) for (afterAlt in values) {
            val baseline = before().optional("altText", beforeAlt)
            val actual = after(baseline).optional("altText", afterAlt)
            if (beforeAlt == afterAlt) adapter.receipt(call(), doc(baseline), TAG, reply(actual))
            else invalid { adapter.receipt(call(), doc(baseline), TAG, reply(actual)) }
        }
    }

    @Test fun explicitEmptyAltIsAnUpdateAndNotAbsenceOrTheOldValue() {
        for (old in listOf<JsonElement?>(null, JsonPrimitive(""), JsonPrimitive("Old description"))) {
            val baseline = before().optional("altText", old)
            val original = call(patch("altText", JsonPrimitive("")))
            adapter.receipt(original, doc(baseline), TAG, reply(after(baseline).with("altText", JsonPrimitive(""))))
            invalid { adapter.receipt(original, doc(baseline), TAG, reply(after(baseline).without("altText"))) }
        }
    }

    @Test fun unsupportedClearingVocabularyAndNullAreNeverInventedOrAccepted() {
        val baseline = before().with("sourcePostId", JsonPrimitive(SOURCE)).with("altText", JsonPrimitive("Old"))
        for (patch in listOf(patch("sourcePostId", JsonNull), patch("altText", JsonNull), patch("attachment", JsonNull),
            patch("removeSourcePost", JsonPrimitive(true)), patch("removeAltText", JsonPrimitive(true))))
            invalid { adapter.expectedFields(call(patch), doc(baseline), TAG) }
        val actual = after(baseline)
        for (field in listOf("sourcePostId", "altText"))
            invalid { adapter.receipt(call(), doc(baseline), TAG, reply(actual.without(field))) }
    }

    @Test fun optionalClientAssertionChecksIdentityButIsNotAnEchoedPatchField() {
        val original = call(patch("clientDraftId", JsonPrimitive(CLIENT.uppercase())), pathId = DRAFT.uppercase())
        adapter.receipt(original, doc(before()), TAG, reply())
        assertEquals(JsonPrimitive(CLIENT), json(adapter.expectedFields(original, doc(before()), TAG))["clientDraftId"])
        invalid { adapter.expectedFields(call(patch("clientDraftId", JsonPrimitive(SOURCE))), doc(before()), TAG) }
        invalid { adapter.receipt(original, doc(before()), TAG, reply(after().with("clientDraftId", JsonPrimitive(CLIENT.uppercase())))) }
    }

    @Test fun baselineAndResultRootStatusCreatedAtAndPublicationIdentityAreInvariant() {
        val actual = after()
        for ((field, changed) in listOf("id" to JsonPrimitive(SOURCE), "clientDraftId" to JsonPrimitive(SOURCE),
            "createdAt" to JsonPrimitive("2026-09-13T01:00:00Z"), "status" to JsonPrimitive("published"),
            "publishedPostId" to JsonPrimitive(SOURCE)))
            invalid { adapter.receipt(call(), doc(before()), TAG, reply(actual.with(field, changed))) }
        for (status in listOf("published", "discarded", "expired"))
            invalid { adapter.expectedFields(call(), doc(before().with("status", JsonPrimitive(status))), TAG) }
        invalid { adapter.expectedFields(call(), doc(before().with("publishedPostId", JsonPrimitive(SOURCE))), TAG) }
        invalid { adapter.expectedFields(call(), doc(before().with("id", JsonPrimitive(DRAFT.uppercase()))), TAG) }
    }

    @Test fun sourceAndCatalogOrPlanUuidSpellingIsNotPublicationNormalized() {
        for (attachment in listOf(catalog(RECIPE.uppercase()), catalog(PLAN.uppercase(), plan = true))) {
            val patch = patch("attachment", attachment).with("sourcePostId", JsonPrimitive(SOURCE.uppercase()))
            val original = call(patch)
            val actual = after().merge(patch)
            adapter.receipt(original, doc(before()), TAG, reply(actual))
            invalid { adapter.receipt(original, doc(before()), TAG, reply(actual.with("sourcePostId", JsonPrimitive(SOURCE)))) }
            val field = if ("planId" in attachment) "planId" else "recipeVersionId"
            invalid { adapter.receipt(original, doc(before()), TAG, reply(actual.with("attachment",
                attachment.with(field, JsonPrimitive(attachment.getValue(field).jsonPrimitive.content.lowercase()))))) }
        }
    }

    @Test fun unchangedSourceAndAttachmentSpellingSurvivesAnUnrelatedPatch() {
        val baseline = before().with("sourcePostId", JsonPrimitive(SOURCE.uppercase())).with("attachment", catalog(RECIPE.uppercase()))
        val patch = patch("caption", JsonPrimitive("Only text changed"))
        val actual = after(baseline).merge(patch)
        adapter.receipt(call(patch), doc(baseline), TAG, reply(actual))
        assertEquals(actual.without("updatedAt", "expiresAt"), json(adapter.expectedFields(call(patch), doc(baseline), TAG)))
    }

    @Test fun mediaAndCircleUuidCaseIsNormalizedInReviewedOrderOnly() {
        val patch = patch("mediaIds", strings(MEDIA_B.uppercase(), MEDIA_A.uppercase()))
            .with("audience", circles(CIRCLE_B.uppercase(), CIRCLE_A.uppercase()))
        val actual = after().with("mediaIds", strings(MEDIA_B, MEDIA_A)).with("audience", circles(CIRCLE_B, CIRCLE_A))
        adapter.receipt(call(patch), doc(before()), TAG, reply(actual))
        for (changed in listOf(actual.with("mediaIds", strings(MEDIA_A, MEDIA_B)), actual.with("mediaIds", strings(MEDIA_B)),
            actual.with("audience", circles(CIRCLE_A, CIRCLE_B)), actual.with("mediaIds", strings(MEDIA_B.uppercase(), MEDIA_A))))
            invalid { adapter.receipt(call(patch), doc(before()), TAG, reply(changed)) }
    }

    @Test fun normalizationRejectsDuplicateIdsRatherThanDeduplicatingSelections() {
        for (patch in listOf(patch("mediaIds", strings(MEDIA_A, MEDIA_A.uppercase())),
            patch("audience", circles(CIRCLE_A, CIRCLE_A.uppercase()))))
            invalid { adapter.expectedFields(call(patch), doc(before()), TAG) }
        invalid { adapter.expectedFields(call(), doc(before().with("mediaIds", strings(MEDIA_A, MEDIA_A.uppercase()))), TAG) }
    }

    @Test fun audienceBindingsAreStrippedFromBothReplacedAndUnchangedAudience() {
        val bound = circles(CIRCLE_A.uppercase()).with("bindings", JsonArray(listOf(binding(CIRCLE_B, "9007199254740993"))))
        val actual = after().with("audience", circles(CIRCLE_A))
        adapter.receipt(call(patch("audience", bound)), doc(before()), TAG, reply(actual))
        adapter.receipt(call(), doc(before().with("audience", bound)), TAG, reply(actual))
        invalid { adapter.receipt(call(patch("audience", bound)), doc(before()), TAG, reply(actual.with("audience",
            circles(CIRCLE_A).with("bindings", JsonArray(emptyList()))))) }
    }

    @Test fun selfAndCircleSemanticsAreCheckedWithoutAcceptingMembershipAuthority() {
        for (audience in listOf(circles(), self().with("circleIds", strings(CIRCLE_A))))
            invalid { adapter.expectedFields(call(patch("audience", audience)), doc(before()), TAG) }
        adapter.receipt(call(patch("audience", circles(CIRCLE_A))), doc(before()), TAG, reply(after().with("audience", circles(CIRCLE_A))))
    }

    @Test fun explicitSelectionsMustMatchAndUnownedOptionalsCannotAppearOrDisappear() {
        val patch = patch("keepOnPlate", JsonPrimitive(true)).with("allowRecipeSaves", JsonPrimitive(true))
            .with("attachment", catalog(RECIPE)).with("saveDisclosureVersion", JsonPrimitive("reviewed"))
        val actual = after().merge(patch)
        adapter.receipt(call(patch), doc(before()), TAG, reply(actual))
        for (field in patch.keys) invalid { adapter.receipt(call(patch), doc(before()), TAG, reply(actual.without(field))) }
        invalid { adapter.receipt(call(), doc(before()), TAG, reply(after().with("sourcePostId", JsonPrimitive(SOURCE)))) }
        invalid { adapter.receipt(call(), doc(before()), TAG, reply(after().with("saveDisclosureVersion", JsonPrimitive("invented")))) }
    }

    @Test fun serverManagedTimestampsRemainActualAndAnExpiredOldBaselineIsNotAnAckClock() {
        val baseline = before().with("expiresAt", JsonPrimitive("2026-09-01T00:00:00Z"))
        val actual = after(baseline).with("updatedAt", JsonPrimitive("2026-09-14T14:01:02.123456Z"))
            .with("expiresAt", JsonPrimitive("2026-09-20T14:01:02.123456Z"))
        assertEquals(actual, json(adapter.receipt(call(), doc(baseline), TAG, reply(actual)).document))
        adapter.possibleOriginalResult(call(), doc(baseline), TAG, doc(actual), "\"2\"")
        // Current expiry/eligibility is a later actual owner/authority check, not this pure helper.
        invalid { adapter.receipt(call(), doc(baseline), TAG, reply(actual.with("expiresAt", JsonPrimitive("not-time")))) }
    }

    @Test fun exactSuccessorsCrossDecimalCarriesAndTheSafeDoubleBoundary() {
        for ((beforeVersion, afterVersion) in listOf("9" to "10", "99" to "100", "9007199254740992" to "9007199254740993",
            "9007199254740993" to "9007199254740994", "9223372036854775806" to "9223372036854775807")) {
            val baseline = before().with("version", number(beforeVersion))
            val original = call(tag = "\"$beforeVersion\"")
            val actual = after(baseline).with("version", number(afterVersion))
            val result = adapter.receipt(original, doc(baseline), "\"$beforeVersion\"", reply(actual, tag = "\"$afterVersion\""))
            assertEquals(afterVersion, json(result.document).getValue("version").jsonPrimitive.content)
            invalid { adapter.receipt(original, doc(baseline), "\"$beforeVersion\"", reply(actual.with("version", number(beforeVersion)), tag = "\"$beforeVersion\"")) }
        }
    }

    @Test fun mathematicallyEqualIntegerVersionTokensKeepTheActualLexeme() {
        val baseline = before().with("version", number("1.0e0"))
        val actual = after().with("version", number("2.00"))
        val result = adapter.receipt(call(), doc(baseline), TAG, reply(actual))
        assertEquals("2.00", json(result.document).getValue("version").jsonPrimitive.content)
        assertEquals("2", json(adapter.expectedFields(call(), doc(baseline), TAG)).getValue("version").jsonPrimitive.content)
    }

    @Test fun nonPositiveFractionalOverflowAndUnincrementableVersionsAreRejected() {
        for (version in listOf("0", "-1", "1.1", "9223372036854775807", "9223372036854775808", "1e10000")) {
            val baseline = before().with("version", number(version))
            invalid { adapter.expectedFields(call(tag = "\"$version\""), doc(baseline), "\"$version\"") }
        }
        invalid { adapter.receipt(call(), doc(before()), TAG, reply(after().with("version", number("9223372036854775808")), tag = "\"9223372036854775808\"")) }
    }

    @Test fun baselineIfMatchAndResponseEtagMustBeExactStrongDecimalVersions() {
        for (tag in listOf("1", "W/\"1\"", "\"01\"", "\"1.0\"", "\"2\"", " \"1\""))
            invalid { adapter.expectedFields(call(tag = tag), doc(before()), tag) }
        invalid { adapter.expectedFields(call(tag = "\"2\""), doc(before()), TAG) }
        for (tag in listOf<String?>(null, "2", "W/\"2\"", "\"02\"", "\"2.0\"", "\"3\"", "\"2\" "))
            invalid { adapter.receipt(call(), doc(before()), TAG, reply(tag = tag)) }
    }

    @Test fun onlyExactUpdateEnvelopeIsAcceptedAndUnknownFieldsDoNotBecomePatchAuthority() {
        for (original in listOf(
            ApiCall("createPostDraft", body = PrivateBytes("{}".encodeToByteArray()), idempotencyKey = SecretText(COMMAND), ifMatch = TAG),
            ApiCall("updatePostDraft", pathParameters = mapOf("draftId" to DRAFT), queryParameters = mapOf("extra" to listOf("x")), body = PrivateBytes("{}".encodeToByteArray()), idempotencyKey = SecretText(COMMAND), ifMatch = TAG),
            ApiCall("updatePostDraft", pathParameters = mapOf("draftId" to SOURCE), body = PrivateBytes("{}".encodeToByteArray()), idempotencyKey = SecretText(COMMAND), ifMatch = TAG),
            ApiCall("updatePostDraft", body = PrivateBytes("{}".encodeToByteArray()), idempotencyKey = SecretText(COMMAND), ifMatch = TAG),
            ApiCall("updatePostDraft", pathParameters = mapOf("draftId" to DRAFT, "other" to SOURCE), body = PrivateBytes("{}".encodeToByteArray()), idempotencyKey = SecretText(COMMAND), ifMatch = TAG)))
            invalid { adapter.expectedFields(original, doc(before()), TAG) }
        for (field in listOf("status", "version", "createdAt", "updatedAt", "expiresAt", "publishedPostId", "id"))
            invalid { adapter.expectedFields(call(patch(field, JsonPrimitive("invented"))), doc(before()), TAG) }
    }

    @Test fun malformedOrMissingOriginalBodyKeyAndPreconditionCannotBeCorrelated() {
        for (raw in listOf("", "null", "{\"caption\":\"a\",\"caption\":\"b\"}", "{\"caption\":1}", "{\"removeAttachment\":null}"))
            invalid { adapter.expectedFields(call(raw = raw), doc(before()), TAG) }
        for (original in listOf(
            ApiCall("updatePostDraft", pathParameters = mapOf("draftId" to DRAFT), idempotencyKey = SecretText(COMMAND), ifMatch = TAG),
            ApiCall("updatePostDraft", pathParameters = mapOf("draftId" to DRAFT), body = PrivateBytes("{}".encodeToByteArray()), ifMatch = TAG),
            ApiCall("updatePostDraft", pathParameters = mapOf("draftId" to DRAFT), body = PrivateBytes("{}".encodeToByteArray()), idempotencyKey = SecretText(COMMAND))))
            invalid { adapter.expectedFields(original, doc(before()), TAG) }
    }

    @Test fun wrongSuccessStatusErrorsMissingBytesAndInvalidMediaCannotReturnAReceipt() {
        for (status in listOf(201, 204, 304, 401, 403, 404, 409, 410, 412, 422, 429, 500, 503))
            invalid { adapter.receipt(call(), doc(before()), TAG, reply(status = status)) }
        invalid { adapter.receipt(call(), doc(before()), TAG, ApiReply(200, null, etag = "\"2\"", contentType = "application/json")) }
        for (media in listOf<String?>(null, "text/plain", "application/json; charset=latin1"))
            invalid { adapter.receipt(call(), doc(before()), TAG, reply(media = media)) }
        assertFailsWith<IllegalArgumentException> { reply(media = "application/json\r\nsecret") }
    }

    @Test fun fullCanonicalValidationRejectsUnknownMissingDuplicateAndMalformedResponseFields() {
        for (actual in listOf(after().with("unknown", JsonPrimitive(true)), after().without("caption"), after().with("altText", JsonNull),
            after().with("mediaIds", strings("not-a-uuid")), after().with("audience", self().with("kind", JsonPrimitive("public")))))
            invalid { adapter.receipt(call(), doc(before()), TAG, reply(actual)) }
        for (raw in listOf("", "null", "[]", after().toString().dropLast(1) + ",\"version\":2}", "{bad}"))
            invalid { adapter.receipt(call(), doc(before()), TAG, reply(raw = raw)) }
    }

    @Test fun missingServiceBaselineFieldsAreNotFilledFromDefaults() {
        for (field in listOf("audience", "allowRecipeSaves")) {
            val baseline = before().without(field)
            invalid { adapter.expectedFields(call(), doc(baseline), TAG) }
            invalid { adapter.expectedFields(call(patch(field, before().getValue(field))), doc(baseline), TAG) }
        }
    }

    @Test fun exactNestedNumbersAllowEquivalentLexemesWithoutRoundingLargeValues() {
        val input = personal(recipe("9007199254740993", "0.10000000000000000001"))
        val equivalent = personal(recipe("9007199254740993.000", "1.0000000000000000001e-1"))
        val original = call(patch("attachment", input))
        val actual = after().with("attachment", equivalent)
        val result = adapter.receipt(original, doc(before()), TAG, reply(actual))
        assertEquals(equivalent, json(result.document)["attachment"])
        for (changed in listOf(personal(recipe("9007199254740992", "0.10000000000000000001")),
            personal(recipe("9007199254740993", "0.10000000000000000002"))))
            invalid { adapter.receipt(original, doc(before()), TAG, reply(actual.with("attachment", changed))) }
    }

    @Test fun numericProfileIsAppliedBeforeNonExpandingComparison() {
        val selected = personal(recipe("1e10000"))
        val equivalent = personal(recipe("10e9999"))
        adapter.receipt(call(patch("attachment", selected)), doc(before()), TAG, reply(after().with("attachment", equivalent)))
        for (value in listOf("1e10001", "1e2147483647", "1".repeat(1001)))
            invalid { adapter.expectedFields(call(patch("attachment", personal(recipe(value)))), doc(before()), TAG) }
    }

    @Test fun nestedPersonalUuidTextAndOrderedArraysRemainExact() {
        val selected = personal(recipe(ingredientId = MEDIA_A.uppercase())).with("confirmedChanges", strings("first", "second"))
        val original = call(patch("attachment", selected))
        val actual = after().with("attachment", selected)
        adapter.receipt(original, doc(before()), TAG, reply(actual))
        invalid { adapter.receipt(original, doc(before()), TAG, reply(actual.with("attachment", selected.with("confirmedChanges", strings("second", "first"))))) }
        invalid { adapter.receipt(original, doc(before()), TAG, reply(actual.with("attachment",
            personal(recipe(ingredientId = MEDIA_A)).with("confirmedChanges", strings("first", "second"))))) }
    }

    @Test fun explicitPolicyBoundsApplyToBaselineResponseRequestAndCombinedProjection() {
        for (limit in listOf(0, 262_145)) assertFailsWith<IllegalArgumentException> { ReviewedPostDraftAdapter(limit) }
        unavailable { ReviewedPostDraftAdapter(1).expectedFields(call(), doc(before()), TAG) }
        unavailable { adapter.expectedFields(call(raw = " ".repeat(65_537) + "{}"), doc(before()), TAG) }
        unavailable { adapter.receipt(call(), doc(before()), TAG, reply(raw = " ".repeat(262_145) + after())) }
        val limit = before().toString().encodeToByteArray().size
        unavailable { ReviewedPostDraftAdapter(limit).expectedFields(call(patch("caption", JsonPrimitive("x".repeat(500)))), doc(before()), TAG) }
        unavailable { ReviewedPostDraftAdapter(limit).receipt(call(), doc(before()), TAG, reply(raw = " ".repeat(limit) + after())) }
    }

    @Test fun matchingGetCorrelationIsUnitAndNeverChangesOriginalBaselineKeyOrEtag() {
        val baselineRaw = "\n " + before().with("attachment", catalog(RECIPE)) + "\n"
        val baseline = WireDocument.parse(baselineRaw)
        val bodyRaw = " { \"removeAttachment\" : true, \"caption\" : \"changed\" } "
        val original = call(raw = bodyRaw)
        val actual = after(before().with("attachment", catalog(RECIPE))).without("attachment").with("caption", JsonPrimitive("changed"))
        val actualRaw = "\n" + actual.toString() + "\n"
        val observed = WireDocument.parse(actualRaw)
        repeat(2) {
            assertEquals(Unit, adapter.possibleOriginalResult(original, baseline, TAG, observed, "\"2\""))
            assertContentEquals(bodyRaw.encodeToByteArray(), original.body!!.copyForCodec())
            assertContentEquals(baselineRaw.encodeToByteArray(), baseline.encodeUtf8())
            assertEquals(mapOf("draftId" to DRAFT), original.pathParameters)
            assertEquals(COMMAND, original.idempotencyKey!!.use { it }); assertEquals(TAG, original.ifMatch)
        }
        val receipt = adapter.receipt(original, baseline, TAG, reply(raw = actualRaw))
        assertContentEquals(actualRaw.encodeToByteArray(), receipt.document.encodeUtf8())
    }

    @Test fun getCorrelationRejectsLaterVersionsDifferentMaterialAndInvalidCanonicalObjects() {
        val original = call(patch("removeAttachment", JsonPrimitive(true)))
        val baseline = before().with("attachment", catalog(RECIPE))
        for ((actual, tag) in listOf(after().with("version", number("3")) to "\"3\"", after().with("caption", JsonPrimitive("later text")) to "\"2\"",
            after().with("attachment", catalog(RECIPE)) to "\"2\"", after().with("unknown", JsonPrimitive(true)) to "\"2\"", after() to "W/\"2\""))
            invalid { adapter.possibleOriginalResult(original, doc(baseline), TAG, doc(actual), tag) }
    }

    @Test fun diagnosticsAndDetachedProjectionsDoNotExposeOrMutatePrivateInputs() {
        val original = call(patch("caption", JsonPrimitive("Sensitive caption")))
        val baseline = doc(before())
        val result = adapter.receipt(original, baseline, TAG, reply(after().with("caption", JsonPrimitive("Sensitive caption"))))
        assertEquals("ReviewedPostDraftAdapter(<redacted>)", adapter.toString())
        assertEquals("ReviewedPostDraftReceipt(<redacted>)", result.toString())
        val projection = adapter.expectedFields(original, baseline, TAG)
        val exposed = projection.encodeUtf8(); exposed.fill(0)
        assertEquals(JsonPrimitive("Sensitive caption"), json(adapter.expectedFields(original, baseline, TAG))["caption"])
        assertEquals(JsonPrimitive("Synthetic caption"), json(baseline)["caption"])
        val error = assertFailsWith<MealFailure> { adapter.receipt(original, baseline, TAG, reply()) }
        assertFalse(error.toString().contains("Sensitive caption"))
    }

    private val adapter = ReviewedPostDraftAdapter(262_144)
    private fun call(body: JsonObject = empty(), raw: String = body.toString(), tag: String? = TAG, pathId: String = DRAFT) =
        ApiCall("updatePostDraft", pathParameters = mapOf("draftId" to pathId), body = PrivateBytes(raw.encodeToByteArray()),
            idempotencyKey = SecretText(COMMAND), ifMatch = tag)
    private fun reply(body: JsonObject = after(), status: Int = 200, tag: String? = "\"2\"", raw: String = body.toString(), media: String? = "application/json") =
        ApiReply(status, PrivateBytes(raw.encodeToByteArray()), etag = tag, contentType = media)
    private fun before() = buildJsonObject {
        put("id", DRAFT); put("clientDraftId", CLIENT); put("version", 1); put("status", "draft")
        put("caption", "Synthetic caption"); put("mediaIds", JsonArray(emptyList())); put("audience", self())
        put("keepOnPlate", false); put("allowRecipeSaves", false)
        put("createdAt", "2026-09-14T12:00:00Z"); put("updatedAt", "2026-09-14T12:00:00Z"); put("expiresAt", "2026-09-15T12:00:00Z")
    }
    private fun after(baseline: JsonObject = before()) = baseline.with("version", number("2"))
        .with("updatedAt", JsonPrimitive("2026-09-14T12:01:00Z")).with("expiresAt", JsonPrimitive("2026-09-15T12:01:00Z"))
    private fun self() = buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(emptyList())) }
    private fun circles(vararg ids: String) = buildJsonObject { put("kind", "circles"); put("circleIds", strings(*ids)) }
    private fun binding(id: String, generation: String) = buildJsonObject { put("circleId", id); put("authorMembershipGeneration", number(generation)) }
    private fun catalog(id: String, plan: Boolean = false) = buildJsonObject {
        put(if (plan) "planId" else "recipeVersionId", id); put("confirmedChanges", JsonArray(emptyList()))
        put("reviewStatus", "reviewed"); put("rightsBasis", "catalogRedistributable")
    }
    private fun personal(recipe: JsonObject) = buildJsonObject {
        put("personalRecipe", recipe); put("confirmedChanges", JsonArray(emptyList())); put("reviewStatus", "personal"); put("rightsBasis", "creatorOriginal")
    }
    private fun recipe(quantity: String = "1", servings: String = "1", ingredientId: String = MEDIA_A) = buildJsonObject {
        put("title", "Synthetic recipe")
        put("ingredients", JsonArray(listOf(buildJsonObject { put("ingredientId", ingredientId); put("quantity", number(quantity)); put("unit", "g"); put("optional", false) })))
        put("steps", JsonArray(listOf(buildJsonObject { put("stepId", "mix"); put("position", 1); put("instruction", "Mix")
            put("ingredientIds", strings(ingredientId)); put("requiredEquipmentIds", strings("bowl")); put("mandatorySafetyStep", false) })))
        put("servings", number(servings)); put("activeMinutes", 1); put("totalMinutes", 1); put("utensilCount", 1)
        put("equipmentIds", strings("bowl")); put("modes", strings("assemble"))
    }
    private fun empty() = JsonObject(emptyMap())
    private fun patch(name: String, value: JsonElement) = JsonObject(mapOf(name to value))
    private fun number(value: String) = Json.parseToJsonElement(value)
    private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))
    private fun doc(value: JsonObject) = WireDocument.parse(value.toString())
    private fun json(value: WireDocument) = Json.parseToJsonElement(value.encodeUtf8().decodeToString()).jsonObject
    private fun JsonObject.with(name: String, value: JsonElement) = JsonObject(this + (name to value))
    private fun JsonObject.without(vararg names: String) = JsonObject(filterKeys { it !in names })
    private fun JsonObject.merge(other: JsonObject) = JsonObject(this + other)
    private fun JsonObject.optional(name: String, value: JsonElement?) = if (value == null) without(name) else with(name, value)
    private fun invalid(action: () -> Unit) { assertEquals(FailureReason.INVALID_DATA, assertFailsWith<MealFailure> { action() }.reason) }
    private fun unavailable(action: () -> Unit) { assertEquals(FailureReason.UNAVAILABLE, assertFailsWith<MealFailure> { action() }.reason) }
    private companion object {
        const val TAG = "\"1\""
        const val CLIENT = "bbbbbbbb-2222-4222-8222-222222222222"
        const val COMMAND = "cccccccc-3333-4333-8333-333333333333"
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
