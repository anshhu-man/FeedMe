package com.feedme.app.mealflow

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.social.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ReviewedPostPresentationTest {
    @Test fun revokedDetachedPublicationReviewCannotBecomeVisibleOnSameRootRemount() {
        // A new composable may observe matching screen/root/revision again. The actual retained
        // view's navigation epoch must still be current for review, retry and unsent cancellation.
        assertTrue(publicationReviewIsStale(true, false, true))
        assertTrue(publicationReviewIsStale(true, true, false))
        assertTrue(publicationReviewIsStale(true, false, false))
        assertFalse(publicationReviewIsStale(true, true, true))
        for (navigation in listOf(false, true)) for (selection in listOf(false, true))
            assertFalse(publicationReviewIsStale(false, navigation, selection))
    }

    @Test fun lateRecoveryRouteAndSaveFailureRequireTheExactNavigationTicket() {
        val ticket = Any()
        val pin = ReviewedDraftViewPin(PostDraftScreen.EDITOR, "root", 7)
        assertTrue(reviewedEntryDeliveryCurrent(ticket, ticket, pin, PostDraftScreen.EDITOR, "root", 7, true))
        // Back/new inspection supersedes even an otherwise identical draft view.
        assertFalse(reviewedEntryDeliveryCurrent(ticket, Any(), pin, PostDraftScreen.EDITOR, "root", 7, true))
        val equalButNotIdentical = listOf("navigation")
        val anotherEqual = listOf("navigation")
        assertEquals(equalButNotIdentical, anotherEqual)
        assertFalse(reviewedEntryDeliveryCurrent(equalButNotIdentical, anotherEqual, pin, PostDraftScreen.EDITOR, "root", 7, true))
    }

    @Test fun detachedChoicesAndPublicationTargetAreHiddenAfterRootRevisionOrScreenChange() {
        val pin = ReviewedDraftViewPin(PostDraftScreen.EDITOR, "root", 9007199254740993L)
        assertTrue(pin.matches(PostDraftScreen.EDITOR, "root", 9007199254740993L, true))
        assertFalse(pin.matches(PostDraftScreen.EDITOR, "other-root", 9007199254740993L, true))
        assertFalse(pin.matches(PostDraftScreen.EDITOR, "root", 9007199254740994L, true))
        assertFalse(pin.matches(PostDraftScreen.LOCAL_LIST, "root", 9007199254740993L, true))
        assertFalse(pin.matches(PostDraftScreen.EDITOR, "root", 9007199254740993L, false))
        assertFalse(pin.matches(PostDraftScreen.EDITOR, null, null, true))
    }

    @Test fun retainedRecoveryFromListRequiresExactNullSelectionAndNoIdentityDisclosure() {
        val pin = ReviewedDraftViewPin(PostDraftScreen.LOCAL_LIST, null, null)
        assertTrue(pin.matches(PostDraftScreen.LOCAL_LIST, null, null, true))
        assertFalse(pin.matches(PostDraftScreen.LOCAL_LIST, "new-root", 1, true))
        assertFalse(ReviewedDraftViewPin(PostDraftScreen.EDITOR, "PRIVATE_MARKER", 1).toString().contains("PRIVATE_MARKER"))
    }

    @Test fun fullComposerSaveNeverFallsThroughToTextOnlySave() {
        assertEquals(DraftSaveUiRoute.REVIEWED_SAVE, draftSaveUiRoute(true, true))
        assertEquals(DraftSaveUiRoute.REVIEW_NOT_CONNECTED, draftSaveUiRoute(true, false))
        for (configured in listOf(false, true)) assertEquals(DraftSaveUiRoute.LEGACY_SAVE, draftSaveUiRoute(false, configured))
    }

    @Test fun includedEmptyNullAndAbsentAreDifferentDisplayValues() {
        val values = listOf(exactReviewText(null), exactReviewText(JsonNull), exactReviewText(JsonPrimitive("")),
            exactReviewText(JsonArray(emptyList())), exactReviewText(JsonObject(emptyMap())))
        assertEquals(5, values.distinct().size)
        assertEquals("false", exactReviewText(JsonPrimitive(false)))
        assertEquals("0", exactReviewText(JsonPrimitive(0)))
    }

    @Test fun nestedNumbersAndOrderedValuesRemainExactAndDoNotChangeOriginalBytes() {
        val source = """{ "caption":"🌱\n exact ", "mediaIds":["SECOND","FIRST"], "recipe":{"quantity":9007199254740993.000,"ratio":1e-9},"allowRecipeSaves":false }"""
        val document = WireDocument.parse(source)
        val before = document.encodeUtf8()
        assertEquals(listOf(
            "caption" to "🌱\n exact ", "mediaIds · 1" to "SECOND", "mediaIds · 2" to "FIRST",
            "recipe · quantity" to "9007199254740993.000", "recipe · ratio" to "1e-9", "allowRecipeSaves" to "false"),
            exactReviewRows(document).map { it.label to it.value })
        assertContentEquals(before, document.encodeUtf8())
    }

    @Test fun unknownFieldsRemainVisibleInsteadOfBeingSilentlyDropped() {
        val rows = exactReviewRows(WireDocument.parse("""{"retainedUnexpected":{"deep":[false,""]}}"""))
        assertEquals(listOf("retainedUnexpected · deep · 1", "retainedUnexpected · deep · 2"), rows.map { it.label })
        assertEquals(listOf("false", "Empty text (included)"), rows.map { it.value })
    }

    @Test fun absentOrUnknownAudienceNeverDefaultsToOnlyYou() {
        assertEquals("Only you", reviewAudienceLabel(WireDocument.parse("""{"audience":{"kind":"self","circleIds":[]}}""")))
        assertEquals("Selected circles", reviewAudienceLabel(WireDocument.parse("""{"audience":{"kind":"circles","circleIds":["a"]}}""")))
        for (body in listOf("{}", """{"audience":{"kind":"unknown"}}""", "[]"))
            assertTrue(reviewAudienceLabel(WireDocument.parse(body)).contains("unavailable"))
    }

    @Test fun exactSavedPairAndPartialPairCannotBePresentedAsDirectLocal() {
        assertTrue(publicationBranchLabel(WireDocument.parse("{}")).startsWith("Direct local"))
        assertEquals("Exact saved-draft original", publicationBranchLabel(WireDocument.parse("""{"draftId":"id","draftVersion":9007199254740993}""")))
        for (body in listOf("""{"draftId":"id"}""", """{"draftVersion":1}"""))
            assertTrue(publicationBranchLabel(WireDocument.parse(body)).contains("not a direct"))
    }

    @Test fun onlyExactZeroAttemptSupportedQueueStatesOfferUnsentReview() {
        for (phase in listOf("READY", "AWAITING_CONFIRMATION", "RETRY_WAIT", "NEEDS_RESOLUTION")) {
            assertTrue(publicationUnsentReviewAvailable(phase, 0))
            for (attempt in listOf(null, -1, 1, 2)) assertFalse(publicationUnsentReviewAvailable(phase, attempt))
        }
        for (phase in listOf(null, "IN_FLIGHT", "RECEIPT_READY", "APPLIED", "DISCARDED", "UNOBSERVED", "PRIVATE_UNKNOWN"))
            assertFalse(publicationUnsentReviewAvailable(phase, 0))
    }

    @Test fun retainedResultsNeverClaimAcknowledgementWithoutActualDeliveredFlag() {
        assertTrue(publicationOutcomeText(PostComposerPhase.PUBLISHED, false).contains("has not confirmed"))
        assertTrue(publicationOutcomeText(PostComposerPhase.CANCELLED_UNSENT, false).contains("has not confirmed"))
        assertTrue(publicationOutcomeText(PostComposerPhase.HISTORY, true).contains("not a new"))
        assertTrue(publicationOutcomeText(PostComposerPhase.PUBLISHED, true).contains("confirmed on this device"))
        assertTrue(publicationOutcomeText(PostComposerPhase.CANCELLED_UNSENT, true).contains("local draft was retained"))
    }

    @Test fun fullChoiceSaveCopyNeverUsesCaptionEqualityAsCompleteSaveProof() {
        val text = reviewedDraftAcknowledgementText(true, false, false)
        assertTrue(text.contains("caption text alone")); assertTrue(text.contains("audience")); assertTrue(text.contains("attachment"))
        assertTrue(reviewedDraftAcknowledgementText(true, true, false).contains("Newer local choices"))
        assertTrue(reviewedDraftAcknowledgementText(true, true, false).contains("never confirms publication"))
    }

    @Test fun uncertainLocalAckAndFinalizationOverrideSaveProjection() {
        assertTrue(reviewedDraftAcknowledgementText(false, true, false).contains("not confirmed by storage"))
        assertTrue(reviewedDraftAcknowledgementText(false, true, true).contains("needs local confirmation"))
        assertTrue(reviewedDraftAcknowledgementText(true, false, true).contains("not an acknowledgement"))
    }

    @Test fun everyFailureHasBoundedNonAcceptingCopyAndNullIsQuiet() {
        assertNull(publicationFailureText(null))
        FailureReason.entries.forEach {
            val message = assertNotNull(publicationFailureText(it))
            assertTrue(message.length in 1..300)
            assertFalse(message.contains("PRIVATE_MARKER"))
        }
        assertTrue(publicationFailureText(FailureReason.NOT_CONFIGURED)!!.contains("not connected"))
        assertTrue(publicationFailureText(FailureReason.OUTCOME_UNKNOWN)!!.contains("do not start a replacement"))
        assertTrue(publicationFailureText(FailureReason.INVALID_DATA)!!.contains("could not be verified"))
    }

    @Test fun currentLocalSnapshotDisplayIsSeparateAndPreservesExactChoicesBytes() {
        val choices = """{ "caption":"NEWER", "mediaIds":[], "audience":{"kind":"self","circleIds":[]}, "keepOnPlate":false, "allowRecipeSaves":false }"""
        val snapshot = WireDocument.parse(buildJsonObject {
            put("content", buildJsonObject { put("kind", "composer-v2"); put("exactChoicesUtf8", choices) })
        }.toString())
        val extracted = assertNotNull(publicationLocalContent(snapshot))
        assertEquals(choices, extracted.encodeUtf8().decodeToString())
        val original = WireDocument.parse("""{"caption":"ORIGINAL"}""")
        assertEquals("ORIGINAL", exactReviewRows(original).single().value)
        assertEquals("NEWER", exactReviewRows(extracted).first().value)
    }

    @Test fun sharedEntryFailuresDoNotMislabelPrivateSaveAsPublication() {
        assertNull(reviewedEntryFailureText(null))
        FailureReason.entries.forEach {
            val text = assertNotNull(reviewedEntryFailureText(it))
            assertTrue(text.length in 1..300)
            assertFalse(text.contains("published")); assertFalse(text.contains("publication"))
        }
        assertTrue(reviewedEntryFailureText(FailureReason.OUTCOME_UNKNOWN)!!.contains("missing row as rollback"))
    }

    @Test fun unknownLocalSnapshotNeverPretendsToHaveCurrentChoices() {
        for (source in listOf("{}", """{"content":{"kind":"unrecognized"}}""", "[]"))
            assertNull(publicationLocalContent(WireDocument.parse(source)))
        val text = publicationLocalContent(WireDocument.parse("""{"content":{"kind":"text-v1","caption":"text","altText":""}}"""))
        assertNotNull(text)
        assertTrue(exactReviewRows(text).any { it.label == "altText" && it.value == "Empty text (included)" })
    }

    @Test fun textOnlyAbsenceDoesNotInventAudienceOrAttachmentOrSource() {
        val retained = retainedPostSelections(null)
        assertNull(retained.audience); assertTrue(retained.mediaIds.isEmpty())
        assertSame(OptionalValue.Absent, retained.attachment); assertSame(OptionalValue.Absent, retained.source)
    }

    @Test fun typedRetainedMaterialPreservesUppercaseIdsOrderAndConfirmedChanges() {
        val source = material("""{"recipeVersionId":"$RECIPE","confirmedChanges":["second","first"],"reviewStatus":"reviewed","rightsBasis":"catalogRedistributable"}""")
        val before = source.encodeUtf8()
        val value = retainedPostSelections(source)
        assertEquals(listOf(MEDIA_B, MEDIA_A), value.mediaIds)
        assertEquals(listOf(CIRCLE_B, CIRCLE_A), assertIs<PublicationAudience.Circles>(value.audience).orderedCircleIds)
        assertEquals(SOURCE, assertIs<OptionalValue.Present<String>>(value.source).value)
        val attachment = assertIs<OptionalValue.Present<PublicationAttachment>>(value.attachment).value
        assertEquals(RECIPE, assertIs<PublicationAttachmentSource.RecipeVersion>(attachment.source).recipeVersionId)
        assertEquals(listOf("second", "first"), attachment.confirmedChanges)
        assertContentEquals(before, source.encodeUtf8())
    }

    @Test fun retainedPlanSourceIsNotSwitchedToRecipeVersion() {
        val value = retainedPostSelections(material("""{"planId":"$RECIPE","confirmedChanges":[],"reviewStatus":"reviewed","rightsBasis":"catalogRedistributable"}"""))
        val attachment = assertIs<OptionalValue.Present<PublicationAttachment>>(value.attachment).value
        assertEquals(RECIPE, assertIs<PublicationAttachmentSource.Plan>(attachment.source).planId)
    }

    @Test fun fullPersonalRecipeNestedNumbersAndSafetyMaterialRemainPresent() {
        val recipe = """{"title":"Synthetic personal","ingredients":[{"ingredientId":"$MEDIA_A","quantity":9007199254740993.000,"unit":"g","optional":false}],"steps":[{"stepId":"mix","position":1,"instruction":"Exact safety instruction","ingredientIds":["$MEDIA_A"],"requiredEquipmentIds":["bowl"],"mandatorySafetyStep":true}],"servings":1,"activeMinutes":1,"totalMinutes":1,"utensilCount":1,"equipmentIds":["bowl"],"modes":["assemble"]}"""
        val material = material("""{"personalRecipe":$recipe,"confirmedChanges":["exact"],"reviewStatus":"personal","rightsBasis":"creatorOriginal"}""")
        val attachment = assertIs<OptionalValue.Present<PublicationAttachment>>(retainedPostSelections(material).attachment).value
        val personal = assertIs<PublicationAttachmentSource.Personal>(attachment.source).recipeDraft
        val fields = exactReviewRows(personal).associate { it.label to it.value }
        assertEquals("9007199254740993.000", fields["ingredients · 1 · quantity"])
        assertEquals("Exact safety instruction", fields["steps · 1 · instruction"])
        assertEquals("true", fields["steps · 1 · mandatorySafetyStep"])
    }

    @Test fun unsupportedRetainedMaterialIsRefusedNotDropped() {
        for (source in listOf("""{"mediaIds":null}""", """{"mediaIds":[]}""".dropLast(1) +
            ""","attachment":{"recipeVersionId":"$RECIPE","planId":"$RECIPE","confirmedChanges":[],"reviewStatus":"reviewed","rightsBasis":"catalogRedistributable"}}""")) {
            assertFails { retainedPostSelections(WireDocument.parse(source)) }
        }
    }

    @Test fun retainedAggregateGetterDoesNotLeakItsMutableMediaList() {
        val value = retainedPostSelections(material())
        val exposed = value.mediaIds
        if (exposed is MutableList<*>) exposed.clear()
        assertEquals(listOf(MEDIA_B, MEDIA_A), value.mediaIds)
    }

    @Test fun displayAndRetainedMaterialDiagnosticsStayRedacted() {
        assertFalse(PostReviewRow("PRIVATE_MARKER", "PRIVATE_MARKER").toString().contains("PRIVATE_MARKER"))
        val retained = retainedPostSelections(material())
        assertTrue(retained.toString().contains("redacted"))
        assertFalse(retained.toString().contains(MEDIA_A))
    }

    private fun material(attachment: String? = null): WireDocument = WireDocument.parse(buildJsonObject {
        put("mediaIds", JsonArray(listOf(MEDIA_B, MEDIA_A).map(::JsonPrimitive)))
        put("audience", buildJsonObject { put("kind", "circles"); put("circleIds", JsonArray(listOf(CIRCLE_B, CIRCLE_A).map(::JsonPrimitive))) })
        put("sourcePostId", SOURCE)
        attachment?.let { put("attachment", Json.parseToJsonElement(it)) }
    }.toString())

    private companion object {
        const val MEDIA_A = "CCCCCCCC-9999-4999-8999-999999999999"
        const val MEDIA_B = "DDDDDDDD-AAAA-4AAA-8AAA-AAAAAAAAAAAA"
        const val CIRCLE_A = "EEEEEEEE-BBBB-4BBB-8BBB-BBBBBBBBBBBB"
        const val CIRCLE_B = "FFFFFFFF-CCCC-4CCC-8CCC-CCCCCCCCCCCC"
        const val RECIPE = "AAAAAAAA-7777-4777-8777-777777777777"
        const val SOURCE = "FFFFFFFF-6666-4666-8666-666666666666"
    }
}
