package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.MealFailure
import com.feedme.mealflow.json
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.CLIENT
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.SERVER
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.document
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.draft
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.policy
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure historical data validation, never journal ownership, a live review or publication. */
class DraftLocalSnapshotTest {
    @Test fun everyLegacyAltPresenceRetainsTextWithoutInventedComposerDefaults() {
        for (alt in listOf(null, "", "Description")) {
            val local = PostLocal(CLIENT, 3, "Caption", alt)
            val parsed = codec.decode(codec.encode(codec.fromLegacy(local)))
            val content = assertIs<DraftLocalContentV1.TextV1>(parsed.content)
            assertEquals("Caption", content.caption); assertEquals(alt, content.altText)
            assertSame(DraftServerAssociationV1.NotObserved, parsed.serverAssociation)
            val fields = json(parsed).getValue("content").jsonObject
            assertEquals(alt != null, "altText" in fields)
            assertFalse("audience" in fields); assertFalse("mediaIds" in fields); assertFalse("saveDisclosureVersion" in fields)
        }
    }

    @Test fun legacyServerAssociationPreservesCompleteRawCanonicalDraftAndExactLargeEtag() {
        val baseline = WireDocument.parse(" \n${draft(CLIENT, version = "9007199254740993123456789").encodeUtf8().decodeToString()}\n")
        val tag = "\"9007199254740993123456789\""
        val snapshot = codec.fromLegacy(PostLocal(CLIENT, 3, "Caption", null, baseline, tag))
        val association = assertIs<DraftServerAssociationV1.Observed>(codec.decode(snapshot.exactUtf8).serverAssociation)
        assertContentEquals(baseline.encodeUtf8(), association.exactPostDraft.encodeUtf8())
        assertEquals(tag, association.etag)
        assertEquals("draft", postString(association.exactPostDraft, "status"))
    }

    @Test fun observedExpiredDiscardedAndPublishedAreHistoryNotMadeEditableOrDirect() {
        for (status in listOf("expired", "discarded", "published")) {
            val baseline = document(draft(CLIENT).json().jsonObject + mapOf("status" to JsonPrimitive(status)) +
                if (status == "published") mapOf("publishedPostId" to JsonPrimitive(number(80))) else emptyMap())
            val snapshot = codec.fromLegacy(PostLocal(CLIENT, 3, "Caption", null, baseline, "\"1\""))
            val observed = assertIs<DraftServerAssociationV1.Observed>(snapshot.serverAssociation)
            assertEquals(status, postString(observed.exactPostDraft, "status"))
        }
    }

    @Test fun exactSnapshotBytesSurviveObjectOrderWhitespaceAndChoiceSpelling() {
        val snapshot = codec.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(choices(), "Actual historical text"), DraftServerAssociationV1.NotObserved)
        val reordered = JsonObject(json(snapshot).entries.reversed().associate { it.toPair() })
        val raw = PrivateBytes(" \n$reordered\n".encodeToByteArray())
        val result = codec.decode(raw)
        assertContentEquals(raw.copyForCodec(), codec.encode(result).copyForCodec())
        assertContentEquals(choices().encodeUtf8(), assertIs<DraftLocalContentV1.ComposerV2>(result.content).exactChoices.encodeUtf8())
    }

    @Test fun composerRetainsEveryActuallyStoredChoiceAndHistoricalDisclosureTextSeparately() {
        val input = choices()
        val snapshot = codec.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(input, "Historical disclosure"), DraftServerAssociationV1.NotObserved)
        val parsed = assertIs<DraftLocalContentV1.ComposerV2>(codec.decode(snapshot.exactUtf8).content)
        assertContentEquals(input.encodeUtf8(), parsed.exactChoices.encodeUtf8())
        assertEquals("Historical disclosure", parsed.historicalDisclosureText)
        assertEquals("Caption", parsed.caption); assertEquals("", parsed.altText)
        assertEquals(SOURCE.uppercase(), postString(parsed.exactChoices, "sourcePostId"))
        assertEquals(JsonArray(listOf(JsonPrimitive(number(41)), JsonPrimitive(number(40)))), parsed.exactChoices.json().jsonObject["mediaIds"])
    }

    @Test fun historicalMissingDisclosureMaterialIsNotFilledFromDefaults() {
        for (version in listOf<String?>(null, "actual-version")) {
            val input = document(choices().json().jsonObject - "saveDisclosureVersion" +
                (version?.let { mapOf("saveDisclosureVersion" to JsonPrimitive(it)) } ?: emptyMap()))
            val snapshot = codec.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(input, null), DraftServerAssociationV1.NotObserved)
            val parsed = assertIs<DraftLocalContentV1.ComposerV2>(snapshot.content)
            assertNull(parsed.historicalDisclosureText)
            assertEquals(version != null, "saveDisclosureVersion" in parsed.exactChoices.json().jsonObject)
        }
        assertFails { codec.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(
            document(choices().json().jsonObject - "saveDisclosureVersion"), "invented text without version"), DraftServerAssociationV1.NotObserved) }
    }

    @Test fun historicalAllowSavesWithoutAttachmentIsRetainedButCannotBecomeLiveChoicesAutomatically() {
        val input = document(choices().json().jsonObject - "attachment")
        val snapshot = codec.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(input, null), DraftServerAssociationV1.NotObserved)
        assertEquals(JsonPrimitive(true), assertIs<DraftLocalContentV1.ComposerV2>(snapshot.content).exactChoices.json().jsonObject["allowRecipeSaves"])
        assertFalse("attachment" in snapshot.content.exactChoices.json().jsonObject)
        // This type deliberately is not ReviewedPostChoices and provides no live review token.
    }

    @Test fun textEditPreservesAllUnownedComposerChoicesAndFullServerAssociation() {
        val baseline = draft(CLIENT)
        val snapshot = codec.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(choices(), "Historical disclosure"),
            DraftServerAssociationV1.Observed(baseline, "\"1\""))
        val edited = codec.withText(snapshot, "New caption", "New description", 4)
        val before = assertIs<DraftLocalContentV1.ComposerV2>(snapshot.content)
        val after = assertIs<DraftLocalContentV1.ComposerV2>(edited.content)
        assertEquals(before.exactChoices.json().jsonObject.filterKeys { it !in setOf("caption", "altText") },
            after.exactChoices.json().jsonObject.filterKeys { it !in setOf("caption", "altText") })
        assertEquals("New caption", after.caption); assertEquals("New description", after.altText)
        assertEquals(before.historicalDisclosureText, after.historicalDisclosureText)
        assertEquals(4L, edited.localRevision); assertEquals(3L, snapshot.localRevision)
        assertContentEquals(baseline.encodeUtf8(), assertIs<DraftServerAssociationV1.Observed>(edited.serverAssociation).exactPostDraft.encodeUtf8())
    }

    @Test fun textRevisionMustBeExactSuccessorAndInvalidInputLeavesOriginalSnapshotUnchanged() {
        val snapshot = codec.fromLegacy(PostLocal(CLIENT, 3, "Caption", "")); val raw = snapshot.exactUtf8.copyForCodec()
        for (revision in listOf(2L, 3L, 5L)) assertFails { codec.withText(snapshot, "Next", "", revision) }
        assertFails { codec.withText(snapshot, "x".repeat(501), "", 4) }
        assertFails { codec.withText(snapshot, "Next", "y".repeat(501), 4) }
        assertFails { codec.withText(codec.fromLegacy(PostLocal(CLIENT, Long.MAX_VALUE, "Caption", null)), "Next", null, Long.MAX_VALUE) }
        assertContentEquals(raw, snapshot.exactUtf8.copyForCodec())
    }

    @Test fun serverAssociationUpdateKeepsLogicalTextRevisionAndNeverGuessesAClientBinding() {
        val original = codec.fromLegacy(PostLocal(CLIENT, 3, "Caption", null))
        val changed = codec.withServerAssociation(original, DraftServerAssociationV1.Observed(draft(CLIENT), "\"1\""))
        assertEquals(original.localRevision, changed.localRevision); assertEquals(original.content.caption, changed.content.caption)
        assertSame(DraftServerAssociationV1.NotObserved, original.serverAssociation)
        assertFails { codec.withServerAssociation(original, DraftServerAssociationV1.Observed(draft(number(90)), "\"1\"")) }
        assertFails { codec.withServerAssociation(original, DraftServerAssociationV1.Observed(draft(CLIENT), "\"2\"")) }
        assertFails { codec.fromLegacy(PostLocal(CLIENT, 3, "Caption", null, draft(CLIENT), null)) }
    }

    @Test fun knownAssociationCannotDisappearRebindOrRegressButLaterTerminalObservationKeepsOriginalPin() {
        val baseline = draft(CLIENT)
        val before = codec.fromLegacy(PostLocal(CLIENT, 3, "Caption", null, baseline, "\"1\""))
        assertFails { codec.withServerAssociation(before, DraftServerAssociationV1.NotObserved) }
        val different = document(baseline.json().jsonObject + ("id" to JsonPrimitive(number(90))))
        assertFails { codec.withServerAssociation(before, DraftServerAssociationV1.Observed(different, "\"1\"")) }
        assertFails { codec.withServerAssociation(before, DraftServerAssociationV1.Observed(draft(CLIENT, "Changed same version"), "\"1\"")) }
        val published = document(draft(CLIENT, version = "2").json().jsonObject + mapOf("status" to JsonPrimitive("published"),
            "publishedPostId" to JsonPrimitive(number(91))))
        val observed = codec.withServerAssociation(before, DraftServerAssociationV1.Observed(published, "\"2\""))
        assertEquals(before.localRevision, observed.localRevision)
        assertEquals(before.content.caption, observed.content.caption)
        assertContentEquals(baseline.encodeUtf8(), assertIs<DraftServerAssociationV1.Observed>(before.serverAssociation).exactPostDraft.encodeUtf8())
        assertContentEquals(published.encodeUtf8(), assertIs<DraftServerAssociationV1.Observed>(observed.serverAssociation).exactPostDraft.encodeUtf8())
        assertFails { codec.withServerAssociation(observed, DraftServerAssociationV1.Observed(baseline, "\"1\"")) }
    }

    @Test fun inventedKindsUnknownFieldsExplicitNullAndAssociationHalfPairsAreRejected() {
        val snapshot = codec.fromLegacy(PostLocal(CLIENT, 3, "Caption", null)); val root = json(snapshot)
        for (changed in listOf(root + ("format" to JsonPrimitive("draft-local-snapshot-v2")), root + ("live" to JsonPrimitive(true)),
            root + ("localRevision" to JsonPrimitive(0)))) assertFails { codec.decode(bytes(changed)) }
        val content = root.getValue("content").jsonObject
        for (changed in listOf(content + ("kind" to JsonPrimitive("reviewed")), content + ("altText" to JsonNull),
            content + ("disclosure" to JsonPrimitive("default")))) assertFails { codec.decode(bytes(root + ("content" to JsonObject(changed)))) }
        for (changed in listOf(buildJsonObject { put("kind", "observed"); put("etag", "\"1\"") },
            buildJsonObject { put("kind", "not-observed"); put("etag", "\"1\"") },
            buildJsonObject { put("kind", "observed"); put("exactPostDraftUtf8", JsonNull); put("etag", "\"1\"") }))
            assertFails { codec.decode(bytes(root + ("serverAssociation" to changed))) }
    }

    @Test fun composerRejectsRequestOnlyControlsMissingChoicesAndClientAudienceBindings() {
        val base = choices().json().jsonObject
        val invalid = listOf(base + ("clientDraftId" to JsonPrimitive(CLIENT)), base + ("removeAttachment" to JsonPrimitive(false)),
            base - "caption", base - "mediaIds", base - "audience", base - "keepOnPlate", base - "allowRecipeSaves",
            base + ("altText" to JsonNull), base + ("audience" to JsonObject(base.getValue("audience").jsonObject +
                ("bindings" to JsonArray(emptyList())))))
        for (item in invalid) assertFails { codec.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(document(item), null), DraftServerAssociationV1.NotObserved) }
    }

    @Test fun orderedCaseAliasesArePreservedButNormalizedDuplicatesAndWrongAudiencePairReject() {
        val unique = document(choices().json().jsonObject + ("mediaIds" to JsonArray(listOf(JsonPrimitive(SOURCE.uppercase()), JsonPrimitive(number(40))))))
        val snapshot = codec.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(unique, null), DraftServerAssociationV1.NotObserved)
        assertContentEquals(unique.encodeUtf8(), assertIs<DraftLocalContentV1.ComposerV2>(snapshot.content).exactChoices.encodeUtf8())
        val duplicate = document(unique.json().jsonObject + ("mediaIds" to JsonArray(listOf(JsonPrimitive(SOURCE), JsonPrimitive(SOURCE.uppercase())))))
        assertFails { codec.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(duplicate, null), DraftServerAssociationV1.NotObserved) }
        for (audience in listOf(buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(listOf(JsonPrimitive(number(42))))) },
            buildJsonObject { put("kind", "circles"); put("circleIds", JsonArray(emptyList())) }))
            assertFails { codec.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(document(choices().json().jsonObject +
                ("audience" to audience)), null), DraftServerAssociationV1.NotObserved) }
    }

    @Test fun aggregateBoundsRejectBeforeEscapingAndNeverTruncateRetainedMaterial() {
        val limited = DraftLocalSnapshotCodecV1(policy(), publicationPolicy(record = 4096, original = 2048, response = 2048, disclosure = 64, media = 1))
        assertEquals(FailureReason.UNAVAILABLE, assertFailsWith<MealFailure> {
            limited.create(CLIENT, 3, DraftLocalContentV1.TextV1("\uD800".repeat(4097), null), DraftServerAssociationV1.NotObserved)
        }.reason)
        assertEquals(FailureReason.UNAVAILABLE, assertFailsWith<MealFailure> {
            limited.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(choices(), "x".repeat(65)), DraftServerAssociationV1.NotObserved)
        }.reason)
        assertEquals(FailureReason.UNAVAILABLE, assertFailsWith<MealFailure> {
            limited.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(choices(), null), DraftServerAssociationV1.NotObserved)
        }.reason)
        assertFails { codec.decode(PrivateBytes(byteArrayOf(0xc3.toByte(), 0x28))) }
    }

    @Test fun composerTextReplacementPreflightsRejectedPasteBeforeAnyJsonReconstruction() {
        val limited = DraftLocalSnapshotCodecV1(policy(), publicationPolicy(record = 4096, original = 2048, response = 2048))
        val snapshot = limited.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(choices(), null), DraftServerAssociationV1.NotObserved)
        val original = snapshot.exactUtf8.copyForCodec()
        val rejected = "\uD800".repeat(4097)
        assertEquals(FailureReason.UNAVAILABLE, assertFailsWith<MealFailure> { limited.withText(snapshot, rejected, "", 4) }.reason)
        assertEquals(FailureReason.UNAVAILABLE, assertFailsWith<MealFailure> { limited.withText(snapshot, "Next", rejected, 4) }.reason)
        assertContentEquals(original, snapshot.exactUtf8.copyForCodec())
    }

    @Test fun fabricatedWrapperCannotRelabelValidatedRawSnapshotMetadata() {
        val snapshot = codec.fromLegacy(PostLocal(CLIENT, 3, "Caption", null))
        for (fake in listOf(DraftLocalSnapshotV1(snapshot.exactUtf8, number(90), 3, snapshot.content, snapshot.serverAssociation),
            DraftLocalSnapshotV1(snapshot.exactUtf8, CLIENT, 4, snapshot.content, snapshot.serverAssociation),
            DraftLocalSnapshotV1(snapshot.exactUtf8, CLIENT, 3, DraftLocalContentV1.TextV1("Other", null), snapshot.serverAssociation),
            DraftLocalSnapshotV1(snapshot.exactUtf8, CLIENT, 3, snapshot.content, DraftServerAssociationV1.Observed(draft(CLIENT), "\"1\""))))
            assertFails { codec.encode(fake) }
    }

    @Test fun encodedBytesAndDiagnosticStringsDoNotLeakMutablePrivateState() {
        val snapshot = codec.fromLegacy(PostLocal(CLIENT, 3, "Private caption", "Private description", draft(CLIENT), "\"1\""))
        val copy = codec.encode(snapshot).copyForCodec(); copy.fill(32)
        assertEquals("Private caption", codec.decode(codec.encode(snapshot)).content.caption)
        for (value in listOf(snapshot.toString(), snapshot.content.toString(), snapshot.serverAssociation.toString(), codec.toString())) {
            assertFalse(CLIENT in value); assertFalse(SERVER in value); assertFalse("Private caption" in value)
        }
    }

    private val codec = DraftLocalSnapshotCodecV1(policy(), publicationPolicy())
    private fun choices() = WireDocument.parse(" \n" + buildJsonObject {
        put("caption", "Caption"); put("altText", "")
        put("mediaIds", JsonArray(listOf(JsonPrimitive(number(41)), JsonPrimitive(number(40)))))
        put("audience", buildJsonObject { put("kind", "circles"); put("circleIds", JsonArray(listOf(JsonPrimitive(number(43)), JsonPrimitive(number(42))))) })
        put("keepOnPlate", true); put("allowRecipeSaves", true); put("saveDisclosureVersion", "actual-version")
        put("sourcePostId", SOURCE.uppercase()); put("attachment", buildJsonObject {
            put("recipeVersionId", SOURCE.uppercase()); put("reviewStatus", "reviewed"); put("rightsBasis", "catalogRedistributable")
            put("confirmedChanges", JsonArray(listOf(JsonPrimitive("Kept actual source")))) })
    }.toString() + "\n")
    private fun json(value: DraftLocalSnapshotV1) = WireDocument.decode(value.exactUtf8.copyForCodec()).json().jsonObject
    private fun bytes(value: Map<String, JsonElement>) = PrivateBytes(JsonObject(value).toString().encodeToByteArray())
    private fun publicationPolicy(record: Int = 1_048_576, original: Int = 65_536, response: Int = 262_144,
        disclosure: Int = 4096, media: Int = 64) = PostPublicationClientPolicy(record, original, response,
        64, 128, 64, media, 64, 64, original, disclosure, 60_000)
    private companion object { const val SOURCE = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee" }
}
