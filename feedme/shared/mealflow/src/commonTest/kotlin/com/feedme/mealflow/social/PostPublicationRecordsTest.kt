package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.MealFailure
import com.feedme.sync.CommandIntent
import com.feedme.sync.CommandPhase
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure synthetic retained documents only. No trusted mapping, owner, queue, HTTP, SQLite,
 * provider, consent, publication controller or ACK is configured or claimed. */
class PostPublicationRecordsTest {
    @Test fun directOriginalRoundTripsEveryExactNestedUtf8String() {
        val f = Fixture(); val raw = " \n" + write().toString() + "\t"
        val link = f.link(raw = raw)
        val again = f.links.decode(f.links.encode(link))
        assertContentEquals(link.exactUtf8.copyForCodec(), again.exactUtf8.copyForCodec())
        assertEquals(raw, text(again.exactPostWrite))
        assertEquals(raw, text(again.historicalReview.exactPostWrite))
        assertEquals(COMMAND, again.originalIntentForComparison().call.idempotencyKey!!.use { it })
        assertTrue(again.originalIntentForComparison().call.pathParameters.isEmpty())
        assertTrue(again.originalIntentForComparison().call.queryParameters.isEmpty())
        assertTrue(again.originalIntentForComparison().dependencyCommandIds.isEmpty())
        assertNull(again.originalIntentForComparison().call.ifMatch)
    }
    @Test fun savedOriginalPreservesFullBaselineExactEtagAndBigintBodyLexeme() {
        val f = Fixture(); val version = "9007199254740993"
        val baseline = doc(" \n" + draft(version).toString() + " ")
        val body = write().with("draftId", JsonPrimitive(DRAFT.uppercase())).with("draftVersion", n("9.007199254740993e15"))
        val link = f.saved(body, baseline, version)
        val target = link.historicalReview.target as PublicationReviewTargetV1.SavedDraft
        assertEquals(version, target.draftVersion.decimal); assertEquals("\"$version\"", target.reviewedETag)
        assertContentEquals(baseline.encodeUtf8(), target.exactReviewedDraft.encodeUtf8())
        assertTrue(text(link.exactPostWrite).contains("9.007199254740993e15"))
    }
    @Test fun publicationEnvelopeRejectsEveryChangedFixedCommandField() {
        val f = Fixture(); val root = j(f.link().exactUtf8)
        for ((key, value) in listOf("operationId" to JsonPrimitive("updatePostDraft"),
            "pathParameters" to obj("draftId", JsonPrimitive(DRAFT)), "queryParameters" to obj("limit", arr("1")),
            "ifMatch" to JsonPrimitive("\"1\""), "dependencyCommandIds" to arr(COMMAND_2)))
            invalid { f.links.decode(bytes(root.with(key, value))) }
        invalid { f.links.decode(bytes(root.with("unknown", JsonPrimitive(true)))) }
    }
    @Test fun createRejectsWrongKeyOperationPathQueryOrIfMatchBeforeRetainingAnOriginal() {
        val f = Fixture(); val body = bytes(write())
        for (call in listOf(ApiCall("publishPost", body = body, idempotencyKey = SecretText(COMMAND_2)),
            ApiCall("createPostDraft", body = body, idempotencyKey = SecretText(COMMAND)),
            ApiCall("publishPost", mapOf("draftId" to DRAFT), body = body, idempotencyKey = SecretText(COMMAND)),
            ApiCall("publishPost", queryParameters = mapOf("limit" to listOf("1")), body = body, idempotencyKey = SecretText(COMMAND)),
            ApiCall("publishPost", body = body, idempotencyKey = SecretText(COMMAND), ifMatch = "\"1\"")))
            invalid { f.links.create(binding(), COMMAND, 10, call, f.snapshot(), PublicationReviewTargetV1.DirectLocal, disclosure()) }
    }
    @Test fun halfSavedPairsAndTargetSubstitutionAreRejected() {
        val f = Fixture()
        for (body in listOf(write().with("draftId", JsonPrimitive(DRAFT)), write().with("draftVersion", n("1"))))
            invalid { f.link(body) }
        invalid { f.link(write().with("draftId", JsonPrimitive(DRAFT)).with("draftVersion", n("1"))) }
        val baseline = doc(draft()); invalid { f.saved(write(), baseline, "1") }
    }
    @Test fun everyDuplicatedBodyRootRevisionAndDisclosureFieldMustBind() {
        val f = Fixture(); val original = f.link(); val root = j(original.exactUtf8); val review = j(original.historicalReview.exactUtf8)
        for ((key, value) in listOf("clientDraftId" to JsonPrimitive(CLIENT_2), "reviewedLocalRevision" to n("2"),
            "exactPostWriteUtf8" to JsonPrimitive(" " + text(original.exactPostWrite)),
            "displayedDisclosure" to obj("version", JsonPrimitive("other")).with("text", JsonPrimitive("Different display"))))
            invalid { f.links.decode(bytes(root.with("historicalReviewUtf8", JsonPrimitive(review.with(key, value).toString())))) }
    }
    @Test fun canonicalMetadataRejectsUppercaseDuplicateIdentityNegativeFractionAndOverflow() {
        val f = Fixture(); val root = j(f.link().exactUtf8)
        for ((key, value) in listOf("commandId" to JsonPrimitive(COMMAND.uppercase()), "commandId" to JsonPrimitive(CLIENT),
            "clientDraftId" to JsonPrimitive(CLIENT.uppercase()), "originalCanonicalUserId" to JsonPrimitive("opaque-storage-actor"),
            "reviewedLocalRevision" to n("1.0"), "reviewedLocalRevision" to n("0"), "originalCreatedAtMillis" to n("-1"),
            "originalCreatedAtMillis" to n("9223372036854775808"))) invalid { f.links.decode(bytes(root.with(key, value))) }
    }
    @Test fun exactOriginBindingIsPreservedWithoutEquatingItToStorageActor() {
        val f = Fixture(binding = binding(ORIGIN.uppercase())); val link = f.link()
        assertEquals(ORIGIN.uppercase(), link.originBinding); assertEquals(ACCOUNT, link.originalCanonicalUserId)
        assertEquals(ORIGIN, f.journal().binding.canonicalOrigin)
        val row = f.codec.encode(f.journal(PublicationHistoryEntryV1.PendingOriginal(link)))
        assertEquals(ORIGIN.uppercase(), f.codec.decode(1, row).entries.single().original.originBinding)
    }
    @Test fun journalRejectsDifferentEnvironmentAccountOriginAndWrapperVersion() {
        val f = Fixture(); val row = j(f.codec.encode(f.journal(PublicationHistoryEntryV1.PendingOriginal(f.link()))))
        for ((key, value) in listOf("environment" to JsonPrimitive("other"), "originalCanonicalUserId" to JsonPrimitive(CLIENT),
            "origin" to JsonPrimitive(CLIENT), "schema" to n("2"), "schema" to n("1.0")))
            invalid { f.codec.decode(1, bytes(row.with(key, value))) }
        invalid { f.codec.decode(2, bytes(row)) }
        invalid { f.codec.encode(PublicationJournalV1(binding(environment = "other"), 10, emptyList(), emptyList())) }
    }
    @Test fun snapshotAltPresenceAndValueBindAcrossAllNinePairs() {
        val f = Fixture(); val values = listOf<String?>(null, "", "Actual alt")
        for (left in values) for (right in values) {
            val body = write().optional("altText", right?.let(::JsonPrimitive))
            if (left == right) f.link(body, snapshot = f.snapshot(alt = left))
            else invalid { f.link(body, snapshot = f.snapshot(alt = left)) }
        }
    }
    @Test fun textSnapshotMayExplicitlySupplementPhotoAudienceAttachmentAndSourceChoices() {
        val f = Fixture(); val body = write().with("mediaIds", arr(MEDIA)).with("audience", circles(CIRCLE))
            .with("attachment", catalog(RECIPE)).with("sourcePostId", JsonPrimitive(SOURCE)).with("allowRecipeSaves", JsonPrimitive(true))
        val link = f.link(body)
        assertEquals(body, j(link.exactPostWrite)); assertIs<DraftLocalContentV1.TextV1>(link.historicalReview.exactReviewedLocalSnapshot.content)
    }
    @Test fun composerSnapshotRequiresEveryStoredChoiceIncludingUuidSpellingAndAltAbsence() {
        val f = Fixture(); val body = write().with("mediaIds", arr(MEDIA.uppercase()))
        val snapshot = f.composer(body.without("clientDraftId"))
        f.link(body, snapshot = snapshot)
        for (changed in listOf(body.with("mediaIds", arr(MEDIA)), body.with("altText", JsonPrimitive("")),
            body.with("keepOnPlate", JsonPrimitive(true)), body.without("saveDisclosureVersion")))
            invalid { f.link(changed, snapshot = snapshot) }
    }
    @Test fun historicalComposerDisclosureTextMayBeAbsentButNewReviewNeverInventsIt() {
        val f = Fixture(); val content = write().without("clientDraftId")
        val link = f.link(snapshot = f.composer(content, display = null))
        assertEquals(DISPLAY, link.historicalReview.displayedDisclosure.text)
        val root = j(link.exactUtf8); val review = j(link.historicalReview.exactUtf8)
        for (display in listOf(obj("version", JsonPrimitive(DISCLOSURE)), obj("version", JsonPrimitive(DISCLOSURE)).with("text", JsonNull),
            obj("version", JsonPrimitive(DISCLOSURE)).with("text", JsonPrimitive(""))))
            invalid { f.links.decode(bytes(root.with("historicalReviewUtf8", JsonPrimitive(review.with("displayedDisclosure", display).toString())))) }
    }
    @Test fun retainedComposerDisclosureTextCannotChangeWithoutChangingItsPinnedSnapshot() {
        val f = Fixture(); f.link(snapshot = f.composer(write().without("clientDraftId"), DISPLAY))
        invalid { f.link(snapshot = f.composer(write().without("clientDraftId"), "Different retained text")) }
    }
    @Test fun directReviewCannotBypassAnyObservedSavedAssociation() {
        val f = Fixture(); val snapshot = f.snapshot(association = DraftServerAssociationV1.Observed(doc(draft()), "\"1\""))
        invalid { f.link(snapshot = snapshot) }
    }
    @Test fun savedReviewRequiresExactBaselineBytesInsideThePinnedOriginal() {
        val f = Fixture(); val baseline = doc(draft()); val snapshot = f.snapshot(association = DraftServerAssociationV1.Observed(doc(" " + text(baseline)), "\"1\""))
        invalid { f.link(savedBody(), snapshot = snapshot, target = PublicationReviewTargetV1.SavedDraft(DRAFT, ExactPostVersion("1"), "\"1\"", baseline)) }
        // A later JSON-order-only GET is a separate pure observation; this codec never adopts it.
    }
    @Test fun savedBaselineStatusIdentityPublishedFieldAndVersionMustRemainOriginal() {
        val f = Fixture()
        for (baseline in listOf(draft().with("status", JsonPrimitive("published")), draft().with("clientDraftId", JsonPrimitive(CLIENT_2)),
            draft().with("publishedPostId", JsonPrimitive(POST)), draft().with("version", n("2"))))
            invalid { f.saved(savedBody(), doc(baseline), "1") }
        invalid { f.link(savedBody(), target = PublicationReviewTargetV1.SavedDraft(DRAFT, ExactPostVersion("1"), "W/\"1\"", doc(draft()))) }
    }
    @Test fun savedSourceAndAttachmentUuidNormalizationMatchesPublicationNotPatchRules() {
        val f = Fixture(); val baseline = draft().with("attachment", catalog(RECIPE)).with("sourcePostId", JsonPrimitive(SOURCE))
        val body = savedBody().with("attachment", catalog(RECIPE.uppercase())).with("sourcePostId", JsonPrimitive(SOURCE.uppercase()))
        val link = f.saved(body, doc(baseline), "1")
        assertTrue(text(link.exactPostWrite).contains(SOURCE.uppercase()))
        assertTrue(text((link.historicalReview.target as PublicationReviewTargetV1.SavedDraft).exactReviewedDraft).contains(SOURCE))
    }
    @Test fun savedAudienceClientBindingsAreNotComparedAsServerMembershipAuthority() {
        val f = Fixture(); val baseline = draft().with("audience", circles(CIRCLE).with("bindings", JsonArray(listOf(bindingRow(CIRCLE, "2")))))
        val body = savedBody().with("audience", circles(CIRCLE.uppercase()).with("bindings", JsonArray(listOf(bindingRow(CIRCLE, "9007199254740993")))))
        f.saved(body, doc(baseline), "1")
    }
    @Test fun savedSelectionRejectsMissingDisclosureAndOptionalOrOrderedChanges() {
        val f = Fixture()
        for (baseline in listOf(draft().without("saveDisclosureVersion"), draft().with("altText", JsonPrimitive("")),
            draft().with("keepOnPlate", JsonPrimitive(true)))) invalid { f.saved(savedBody(), doc(baseline), "1") }
        val body = savedBody().with("mediaIds", arr(MEDIA, SOURCE))
        invalid { f.saved(body, doc(draft().with("mediaIds", arr(SOURCE, MEDIA))), "1") }
    }
    @Test fun exactNeighboringBigintsAndFractionalSavedVersionsAreNeverRounded() {
        val f = Fixture(); val baseline = doc(draft("9007199254740993"))
        for (token in listOf("9007199254740992", "9007199254740993.1", "0", "-1", "9223372036854775808"))
            invalid { f.saved(savedBody().with("draftVersion", n(token)), baseline, "9007199254740993") }
        f.saved(savedBody().with("draftVersion", n("9223372036854775807")), doc(draft("9223372036854775807")), "9223372036854775807")
    }
    @Test fun personalRecipeNumbersCompareExactlyAndNestedUuidSpellingIsNotNormalized() {
        val f = Fixture(); val bodyAttachment = personal(recipe("9007199254740993", MEDIA.uppercase()))
        val baselineAttachment = personal(recipe("9007199254740993.0", MEDIA.uppercase()))
        f.saved(savedBody().with("attachment", bodyAttachment), doc(draft().with("attachment", baselineAttachment)), "1")
        for (r in listOf(recipe("9007199254740992", MEDIA.uppercase()), recipe("9007199254740993", MEDIA)))
            invalid { f.saved(savedBody().with("attachment", bodyAttachment), doc(draft().with("attachment", personal(r))), "1") }
    }
    @Test fun allSelectionDisclosureAttachmentAndFullRecordBoundsAreEnforced() {
        val f = Fixture(policy(maxMedia = 1, maxCircle = 1, maxChanges = 1))
        unavailable { f.link(write().with("mediaIds", arr(MEDIA, SOURCE))) }
        unavailable { f.link(write().with("audience", circles(CIRCLE, SOURCE))) }
        unavailable { f.link(write().with("attachment", catalog(RECIPE).with("confirmedChanges", arr("a", "b")))) }
        val small = Fixture(policy(maxDisclosure = 12)); unavailable { small.link() }
        val tiny = Fixture(policy(maxRecord = 4096, maxOriginal = 2048, maxResponse = 2048))
        val body = write().with("caption", JsonPrimitive("a".repeat(400)))
        val raw = " ".repeat(1000) + body.toString()
        assertTrue(raw.encodeToByteArray().size < 2048)
        unavailable { tiny.link(body, raw = raw, snapshot = tiny.snapshot(caption = "a".repeat(400))) }
    }
    @Test fun malformedUtf8DuplicateKeysUnknownFieldsAndNullsAreNotDecoded() {
        val f = Fixture(); invalid { f.links.decode(PrivateBytes(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))) }
        val root = j(f.link().exactUtf8)
        invalid { f.links.decode(PrivateBytes(root.toString().replaceFirst("{", "{\"format\":\"publication-original-link-v1\",").encodeToByteArray())) }
        invalid { f.links.decode(bytes(root.with("extra", JsonPrimitive(1)))) }
        invalid { f.links.decode(bytes(root.with("historicalReviewUtf8", JsonNull))) }
    }
    @Test fun pendingJournalPreservesLinkBytesAcrossOuterEncodingAndNoAckFlagsExist() {
        val f = Fixture(); val link = f.link(); val value = f.journal(PublicationHistoryEntryV1.PendingOriginal(link))
        val encoded = f.codec.encode(value); val restored = f.codec.decode(1, encoded)
        assertContentEquals(link.exactUtf8.copyForCodec(), restored.entries.single().original.exactUtf8.copyForCodec())
        assertEquals(setOf("kind", "originalLinkUtf8"), j(encoded).getValue("entries").jsonArray.single().jsonObject.keys)
        for (field in listOf("serverAcknowledged", "delivered", "attempts", "reviewToken"))
            invalid { f.codec.decode(1, bytes(j(encoded).with(field, JsonPrimitive(true)))) }
    }
    @Test fun fullActualPostAndReplyMetadataRemainHistoricalAndByteExact() {
        val f = Fixture(); val raw = " \n" + post().toString() + "\t"
        val reply = HistoricalPublicationReplyV1(doc(raw), "\"1\"", "Application/JSON; charset=UTF-8", "synthetic-trace", 7)
        val row = f.codec.encode(f.journal(PublicationHistoryEntryV1.PublishedHistorical(f.link(), reply)))
        val actual = f.codec.decode(1, row).entries.single() as PublicationHistoryEntryV1.PublishedHistorical
        assertEquals(raw, text(actual.reply.exactPost)); assertEquals(reply.contentType, actual.reply.contentType)
        assertEquals("synthetic-trace", actual.reply.traceId); assertEquals(7L, actual.reply.retryAfterSeconds)
        assertEquals(post(), j(actual.reply.exactPost))
    }
    @Test fun publishedHistoryRejectsWrongStatusEtagVersionAuthorOrExtraPostField() {
        val f = Fixture(); val row = j(f.codec.encode(f.journal(PublicationHistoryEntryV1.PublishedHistorical(f.link(), historical()))))
        val entry = row.getValue("entries").jsonArray.single().jsonObject; val reply = entry.getValue("reply").jsonObject
        for (changed in listOf(reply.with("status", n("200")), reply.with("etag", JsonPrimitive("\"2\"")),
            reply.with("exactPostUtf8", JsonPrimitive(post().with("version", n("2")).toString())),
            reply.with("exactPostUtf8", JsonPrimitive(post().with("author", post().getValue("author").jsonObject.with("userId", JsonPrimitive(CLIENT))).toString())),
            reply.with("exactPostUtf8", JsonPrimitive(post().with("extra", JsonPrimitive(true)).toString()))))
            invalid { f.codec.decode(1, bytes(row.with("entries", JsonArray(listOf(entry.with("reply", changed)))))) }
    }
    @Test fun publishedHistoryRejectsAnySelectionOrSavePolicyMismatch() {
        val f = Fixture()
        for (body in listOf(post().with("caption", JsonPrimitive("changed")), post().with("altText", JsonPrimitive("")),
            post().with("mediaIds", arr(MEDIA)), post().with("savePolicy", obj("allowFutureSaves", JsonPrimitive(true)).with("policyVersion", n("1")).with("disclosureVersion", JsonPrimitive(DISCLOSURE)))))
            invalid { f.codec.encode(f.journal(PublicationHistoryEntryV1.PublishedHistorical(f.link(), historical(body)))) }
    }
    @Test fun cancelledHistoryHasNoPostAndPreservesTheOriginalWithoutClosingTheRoot() {
        val f = Fixture(); val first = f.link(); val next = f.link(command = COMMAND_2)
        val value = f.journal(PublicationHistoryEntryV1.CancelledUnsentHistorical(first), PublicationHistoryEntryV1.PendingOriginal(next))
        assertEquals(2, f.codec.decode(1, f.codec.encode(value)).entries.size)
        val root = j(f.codec.encode(f.journal(PublicationHistoryEntryV1.CancelledUnsentHistorical(first))))
        val e = root.getValue("entries").jsonArray.single().jsonObject
        invalid { f.codec.decode(1, bytes(root.with("entries", JsonArray(listOf(e.with("reply", JsonNull)))))) }
    }
    @Test fun commandLedgerRejectsReuseDuplicateMissingOutOfOrderAndRootIdentityCollision() {
        val f = Fixture(); val a = PublicationHistoryEntryV1.CancelledUnsentHistorical(f.link()); val b = PublicationHistoryEntryV1.PendingOriginal(f.link(command = COMMAND_2))
        for (ids in listOf(emptyList(), listOf(COMMAND, COMMAND), listOf(COMMAND_2, COMMAND), listOf(COMMAND, COMMAND_2, SOURCE)))
            invalid { f.codec.encode(PublicationJournalV1(binding(), 10, ids, listOf(a, b))) }
        invalid { f.codec.encode(f.journal(a, PublicationHistoryEntryV1.PendingOriginal(f.link()))) }
        val cross = f.link(command = CLIENT_2)
        val other = f.link(command = COMMAND_2, client = CLIENT_2)
        invalid { f.codec.encode(f.journal(PublicationHistoryEntryV1.CancelledUnsentHistorical(cross), PublicationHistoryEntryV1.PendingOriginal(other))) }
    }
    @Test fun atMostOneUnresolvedPublicationExistsAcrossDifferentRoots() {
        val f = Fixture()
        invalid { f.codec.encode(f.journal(PublicationHistoryEntryV1.PendingOriginal(f.link()),
            PublicationHistoryEntryV1.PendingOriginal(f.link(command = COMMAND_2, client = CLIENT_2)))) }
    }
    @Test fun anUnresolvedPublicationMustBeTheLastIssuedHistoryAcrossAllRoots() {
        val f = Fixture(); val pending = PublicationHistoryEntryV1.PendingOriginal(f.link())
        for (client in listOf(CLIENT, CLIENT_2)) {
            val later = f.link(command = COMMAND_2, client = client)
            for (entry in listOf(PublicationHistoryEntryV1.CancelledUnsentHistorical(later), PublicationHistoryEntryV1.PublishedHistorical(later, historical())))
                invalid { f.codec.encode(f.journal(pending, entry)) }
        }
    }
    @Test fun publishedRootCannotGetAnotherPendingCancelledOrPublishedCommand() {
        val f = Fixture(); val closed = PublicationHistoryEntryV1.PublishedHistorical(f.link(), historical())
        val next = f.link(command = COMMAND_2)
        for (entry in listOf(PublicationHistoryEntryV1.PendingOriginal(next), PublicationHistoryEntryV1.CancelledUnsentHistorical(next),
            PublicationHistoryEntryV1.PublishedHistorical(next, historical()))) invalid { f.codec.encode(f.journal(closed, entry)) }
    }
    @Test fun earlierCancelledCommandsRemainWhenTheOpenRootLaterPublishes() {
        val f = Fixture(); val value = f.journal(PublicationHistoryEntryV1.CancelledUnsentHistorical(f.link()),
            PublicationHistoryEntryV1.PublishedHistorical(f.link(command = COMMAND_2), historical()))
        assertEquals(2, f.codec.decode(1, f.codec.encode(value)).entries.size)
    }
    @Test fun actualPostIdentityCannotBeReassignedAcrossDifferentOriginalRoots() {
        val f = Fixture(); invalid { f.codec.encode(f.journal(PublicationHistoryEntryV1.PublishedHistorical(f.link(), historical()),
            PublicationHistoryEntryV1.PublishedHistorical(f.link(command = COMMAND_2, client = CLIENT_2), historical()))) }
    }
    @Test fun clientCreationIsPreservedAndCannotExceedTheJournalClock() {
        val f = Fixture(); val link = f.link(created = 9)
        assertEquals(9L, f.codec.decode(1, f.codec.encode(f.journal(PublicationHistoryEntryV1.PendingOriginal(link)))).entries.single().original.originalCreatedAtMillis)
        invalid { f.codec.encode(PublicationJournalV1(binding(), 8, listOf(COMMAND), listOf(PublicationHistoryEntryV1.PendingOriginal(link)))) }
        // No queue creation timestamp exists in this pure domain schema, so no equality is claimed.
    }
    @Test fun retainedRootAndIssuedCommandCapacitiesCountHistoryWithoutEviction() {
        val f = Fixture(policy(maxRoots = 1, maxIssued = 1))
        val first = PublicationHistoryEntryV1.CancelledUnsentHistorical(f.link())
        unavailable { f.codec.encode(f.journal(first, PublicationHistoryEntryV1.PendingOriginal(f.link(command = COMMAND_2)))) }
        val g = Fixture(policy(maxRoots = 1)); unavailable { g.codec.encode(g.journal(
            PublicationHistoryEntryV1.CancelledUnsentHistorical(g.link()), PublicationHistoryEntryV1.PendingOriginal(g.link(command = COMMAND_2, client = CLIENT_2)))) }
    }
    @Test fun responseAndHeaderLimitsRejectOversizedActualMaterialWithoutTruncation() {
        val f = Fixture(policy(maxResponse = 1024)); val raw = post().toString() + " ".repeat(1024)
        unavailable { f.codec.encode(f.journal(PublicationHistoryEntryV1.PublishedHistorical(f.link(), HistoricalPublicationReplyV1(doc(raw), "\"1\"", "application/json", null, null)))) }
        val g = Fixture(); invalid { g.codec.encode(g.journal(PublicationHistoryEntryV1.PublishedHistorical(g.link(),
            HistoricalPublicationReplyV1(doc(post()), "\"1\"", "application/json", "x".repeat(257), null)))) }
    }
    @Test fun publicationReservationBoundsEveryActualAcceptedReplyAndMetadataWithoutFabricatingIt() {
        val f = Fixture(); val link = f.link(); val pending = f.journal(PublicationHistoryEntryV1.PendingOriginal(link))
        val bound = f.codec.reservedPublishedBytes(pending); f.codec.requireReservedPublishedCapacity(pending)
        val raw = post().toString() + "\t".repeat(2000)
        val result = f.journal(PublicationHistoryEntryV1.PublishedHistorical(link,
            HistoricalPublicationReplyV1(doc(raw), "\"1\"", "application/json", "界".repeat(256), Long.MAX_VALUE)))
        assertTrue(f.codec.encode(result).copyForCodec().size <= bound)
        assertEquals(pending.entries.single().original.commandId, link.commandId)
    }
    @Test fun oversizedWorstCaseReservationRefusesNewPublicationAndRetainsPendingData() {
        val f = Fixture(policy(maxResponse = 262_144)); val pending = f.journal(PublicationHistoryEntryV1.PendingOriginal(f.link()))
        val before = f.codec.encode(pending).copyForCodec()
        unavailable { f.codec.requireReservedPublishedCapacity(pending) }
        assertContentEquals(before, f.codec.encode(pending).copyForCodec())
    }
    @Test fun malformedOrAmbiguousHistoryKindsCannotBecomeAReceiptOrCancellation() {
        val f = Fixture(); val root = j(f.codec.encode(f.journal(PublicationHistoryEntryV1.PendingOriginal(f.link()))))
        val e = root.getValue("entries").jsonArray.single().jsonObject
        for (kind in listOf("APPLIED", "receipt-ready", "cancelled", "published-v2"))
            invalid { f.codec.decode(1, bytes(root.with("entries", JsonArray(listOf(e.with("kind", JsonPrimitive(kind))))))) }
    }
    @Test fun defensiveCopiesAndAllPublicDiagnosticsRemainRedacted() {
        val f = Fixture(); val link = f.link(); val entries = mutableListOf<PublicationHistoryEntryV1>(PublicationHistoryEntryV1.PendingOriginal(link))
        val ids = mutableListOf(COMMAND); val journal = PublicationJournalV1(binding(), 10, ids, entries)
        ids.clear(); entries.clear(); assertEquals(1, journal.entries.size); assertEquals(listOf(COMMAND), journal.issuedCommandIds)
        val original = link.exactUtf8.copyForCodec(); link.exactUtf8.copyForCodec().fill(0)
        assertContentEquals(original, f.links.encode(link).copyForCodec())
        for (value in listOf(binding(), link, link.historicalReview, journal, journal.entries.single(), historical(), f.codec, f.links))
            for (secret in listOf(ACCOUNT, COMMAND, CAPTION, DISPLAY)) assertFalse(value.toString().contains(secret))
    }
    @Test fun internallyConstructedLinkWrapperCannotDisagreeWithItsExactRetainedBytes() {
        val f = Fixture(); val actual = f.link()
        val changed = PublicationOriginalLinkV1(actual.exactUtf8, actual.environment, actual.originBinding, actual.originalCanonicalUserId,
            COMMAND_2, actual.clientDraftId, actual.reviewedLocalRevision, actual.originalCreatedAtMillis, actual.exactPostWrite, actual.historicalReview)
        invalid { f.links.encode(changed) }
        val review = actual.historicalReview
        val wrongReview = PublicationReviewMaterialV1(review.exactUtf8, review.clientDraftId, 2, review.exactPostWrite,
            review.exactReviewedLocalSnapshot, review.target, review.displayedDisclosure)
        val forged = PublicationOriginalLinkV1(actual.exactUtf8, actual.environment, actual.originBinding, actual.originalCanonicalUserId,
            actual.commandId, actual.clientDraftId, actual.reviewedLocalRevision, actual.originalCreatedAtMillis, actual.exactPostWrite, wrongReview)
        invalid { f.links.encode(forged) }
    }

    @Test fun exactPendingHoldAndUnrelatedDraftWorkAreRepresentableWithoutGlobalGate() {
        val f = Fixture(); val link = f.link(); val pending = f.journal(PublicationHistoryEntryV1.PendingOriginal(link))
        val other = f.snapshot(CLIENT_2)
        f.cross.requireConsistent(pending, f.projection(link, locals = listOf(link.historicalReview.exactReviewedLocalSnapshot, other),
            ids = listOf(CLIENT, COMMAND, CLIENT_2), otherCommand = CLIENT_2))
        f.cross.requireConsistent(pending, f.projection(link, locals = listOf(link.historicalReview.exactReviewedLocalSnapshot, other),
            ids = listOf(CLIENT, COMMAND, CLIENT_2), otherCompletion = CLIENT_2))
    }
    @Test fun missingExtraOrSubstitutedHoldCannotRepairPendingPublicationHistory() {
        val f = Fixture(); val link = f.link(); val pending = f.journal(PublicationHistoryEntryV1.PendingOriginal(link))
        invalid { f.cross.requireConsistent(pending, f.projection(link, hold = null)) }
        invalid { f.cross.requireConsistent(f.journal(), f.projection(link)) }
        val other = f.link(command = COMMAND_2)
        invalid { f.cross.requireConsistent(pending, f.projection(link, hold = f.hold(other))) }
    }
    @Test fun crossRecordRequiresOriginIssuedIdentitiesAndUniqueEditableRoots() {
        val f = Fixture(); val link = f.link(); val row = f.journal(PublicationHistoryEntryV1.PendingOriginal(link))
        invalid { f.cross.requireConsistent(row, f.projection(link, origin = CLIENT)) }
        invalid { f.cross.requireConsistent(row, f.projection(link, ids = listOf(CLIENT))) }
        invalid { f.cross.requireConsistent(row, f.projection(link, ids = listOf(CLIENT, COMMAND, COMMAND))) }
        invalid { f.cross.requireConsistent(row, f.projection(link, locals = listOf(f.snapshot(), f.snapshot()))) }
        invalid { f.cross.requireConsistent(row, f.projection(link, locals = listOf(f.snapshot(), f.snapshot(COMMAND)))) }
    }
    @Test fun sameRootServerDraftOriginalOrCompletionCannotOverlapAPublicationHold() {
        val f = Fixture(); val link = f.link(); val row = f.journal(PublicationHistoryEntryV1.PendingOriginal(link))
        invalid { f.cross.requireConsistent(row, f.projection(link, otherCommand = CLIENT)) }
        invalid { f.cross.requireConsistent(row, f.projection(link, otherCompletion = CLIENT)) }
    }
    @Test fun currentHeldSnapshotMustBeTheActualProjectedLocalAndNotAnEarlierRevision() {
        val f = Fixture(); val link = f.link(); val row = f.journal(PublicationHistoryEntryV1.PendingOriginal(link))
        val changed = f.snapshots.create(CLIENT, 2, DraftLocalContentV1.TextV1("New text", null), DraftServerAssociationV1.NotObserved)
        invalid { f.cross.requireConsistent(row, f.projection(link, locals = listOf(changed))) }
        f.cross.requireConsistent(row, f.projection(link, locals = listOf(changed), hold = f.hold(link, changed)))
        val sameRevision = f.snapshot(caption = "Changed without revision")
        invalid { f.cross.requireConsistent(row, f.projection(link, locals = listOf(sameRevision), hold = f.hold(link, sameRevision))) }
    }
    @Test fun laterTerminalSavedObservationDoesNotRebaseOrBlockAttemptedOriginalReplay() {
        val f = Fixture(); val link = f.saved(savedBody(), doc(draft()), "1")
        val terminalObserved = doc(draft("2").with("status", JsonPrimitive("published")).with("publishedPostId", JsonPrimitive(POST)))
        val current = f.snapshot(association = DraftServerAssociationV1.Observed(terminalObserved, "\"2\""))
        val row = f.journal(PublicationHistoryEntryV1.PendingOriginal(link))
        f.cross.requireConsistent(row, f.projection(link, locals = listOf(current), hold = f.hold(link, current)))
        f.cross.requirePendingIntent(link, observation(CommandPhase.AWAITING_CONFIRMATION, 1), link.originalIntentForComparison())
        assertEquals("1", (link.historicalReview.target as PublicationReviewTargetV1.SavedDraft).draftVersion.decimal)
        assertEquals(savedBody(), j(link.exactPostWrite))
    }
    @Test fun savedPendingAssociationCannotDisappearChangeDraftIdOrMoveBackward() {
        val f = Fixture(); val link = f.saved(savedBody().with("draftVersion", n("2")), doc(draft("2")), "2")
        val row = f.journal(PublicationHistoryEntryV1.PendingOriginal(link))
        for (association in listOf(DraftServerAssociationV1.NotObserved,
            DraftServerAssociationV1.Observed(doc(draft("2").with("id", JsonPrimitive(SOURCE))), "\"2\""),
            DraftServerAssociationV1.Observed(doc(draft("1")), "\"1\""))) {
            val current = f.snapshot(association = association)
            invalid { f.cross.requireConsistent(row, f.projection(link, locals = listOf(current), hold = f.hold(link, current))) }
        }
    }
    @Test fun sameVersionSavedObservationCannotSubstituteMaterialStatusOrCreatedAt() {
        val f = Fixture(); val link = f.saved(savedBody(), doc(draft()), "1")
        val row = f.journal(PublicationHistoryEntryV1.PendingOriginal(link))
        for (changed in listOf(draft().with("caption", JsonPrimitive("Different server caption")),
            draft().with("status", JsonPrimitive("published")).with("publishedPostId", JsonPrimitive(POST)),
            draft().with("createdAt", JsonPrimitive("2026-09-13T12:00:00Z")), draft().with("altText", JsonPrimitive("")))) {
            val current = f.snapshot(association = DraftServerAssociationV1.Observed(doc(changed), "\"1\""))
            conflict { f.cross.requireConsistent(row, f.projection(link, locals = listOf(current), hold = f.hold(link, current))) }
        }
        val reordered = JsonObject(draft().entries.reversed().associate { it.key to it.value })
        val current = f.snapshot(association = DraftServerAssociationV1.Observed(doc(" " + reordered.toString()), "\"1\""))
        f.cross.requireConsistent(row, f.projection(link, locals = listOf(current), hold = f.hold(link, current)))
        assertContentEquals(doc(draft()).encodeUtf8(), (link.historicalReview.target as PublicationReviewTargetV1.SavedDraft).exactReviewedDraft.encodeUtf8())
    }
    @Test fun publishedHistoryAndDraftTerminalsMustBeAnExactBijection() {
        val f = Fixture(); val link = f.link(); val row = f.journal(PublicationHistoryEntryV1.PublishedHistorical(link, historical()))
        val terminal = terminal(link)
        f.cross.requireConsistent(row, f.projection(link, hold = null, locals = emptyList(), published = listOf(terminal)))
        invalid { f.cross.requireConsistent(row, f.projection(link, hold = null, locals = emptyList())) }
        invalid { f.cross.requireConsistent(f.journal(), f.projection(link, hold = null, locals = emptyList(), published = listOf(terminal))) }
        invalid { f.cross.requireConsistent(row, f.projection(link, hold = null, locals = emptyList(), published = listOf(terminal, terminal))) }
    }
    @Test fun terminalPostCommandReviewedRevisionAndLinkSubstitutionAreRejected() {
        val f = Fixture(); val link = f.link(); val row = f.journal(PublicationHistoryEntryV1.PublishedHistorical(link, historical()))
        for (term in listOf(PublishedRootProjectionV2(CLIENT, COMMAND_2, POST, 1, link), PublishedRootProjectionV2(CLIENT, COMMAND, SOURCE, 1, link),
            PublishedRootProjectionV2(CLIENT, COMMAND, POST, 2, link), terminal(f.link(command = COMMAND_2))))
            invalid { f.cross.requireConsistent(row, f.projection(link, hold = null, locals = emptyList(), published = listOf(term))) }
    }
    @Test fun publishedRootCannotRemainEditableDiscardedOrHaveAnotherDraftCommand() {
        val f = Fixture(); val link = f.link(); val row = f.journal(PublicationHistoryEntryV1.PublishedHistorical(link, historical()))
        invalid { f.cross.requireConsistent(row, f.projection(link, hold = null, published = listOf(terminal(link)))) }
        invalid { f.cross.requireConsistent(row, f.projection(link, hold = null, locals = emptyList(), published = listOf(terminal(link)), discarded = setOf(CLIENT))) }
        invalid { f.cross.requireConsistent(row, f.projection(link, hold = null, locals = emptyList(), published = listOf(terminal(link)), otherCommand = CLIENT)) }
    }
    @Test fun cancelledOriginalAllowsItsOpenRootLaterHoldOrActualLegacyDiscardWithoutPost() {
        val f = Fixture(); val link = f.link(); val row = f.journal(PublicationHistoryEntryV1.CancelledUnsentHistorical(link))
        f.cross.requireConsistent(row, f.projection(link, hold = null))
        f.cross.requireConsistent(row, f.projection(link, hold = null, locals = emptyList(), discarded = setOf(CLIENT)))
        val next = f.link(command = COMMAND_2)
        val both = f.journal(PublicationHistoryEntryV1.CancelledUnsentHistorical(link), PublicationHistoryEntryV1.PendingOriginal(next))
        f.cross.requireConsistent(both, f.projection(next, ids = listOf(CLIENT, COMMAND, COMMAND_2), hold = f.hold(next, journal = both)))
    }
    @Test fun remainderRetainsExactNewerContentWithoutReusingRootOrAddingServerAssociation() {
        val f = Fixture(); val link = f.link(); val row = f.journal(PublicationHistoryEntryV1.PublishedHistorical(link, historical()))
        val content = DraftLocalContentV1.TextV1("New unsent text", "")
        val remainder = PublicationRemainderProjectionV1(CLIENT, COMMAND, 1, 2, content, link)
        f.cross.requireConsistent(row, f.projection(link, hold = null, locals = emptyList(), published = listOf(terminal(link)), remainders = listOf(remainder)))
        assertEquals("", (remainder.content as DraftLocalContentV1.TextV1).altText)
        assertEquals(CLIENT, remainder.closedClientDraftId); assertEquals(2L, remainder.newerLocalRevision)
    }
    @Test fun remainderMustBindExactTerminalLinkAndStrictlyNewerRevision() {
        val f = Fixture(); val link = f.link(); val row = f.journal(PublicationHistoryEntryV1.PublishedHistorical(link, historical()))
        val content = DraftLocalContentV1.TextV1("New", null)
        for (remainder in listOf(PublicationRemainderProjectionV1(CLIENT, COMMAND, 1, 1, content, link),
            PublicationRemainderProjectionV1(CLIENT, COMMAND_2, 1, 2, content, link), PublicationRemainderProjectionV1(CLIENT, COMMAND, 2, 3, content, link),
            PublicationRemainderProjectionV1(CLIENT, COMMAND, 1, 2, content, f.link(command = COMMAND_2))))
            invalid { f.cross.requireConsistent(row, f.projection(link, hold = null, locals = emptyList(), published = listOf(terminal(link)), remainders = listOf(remainder))) }
        val orphan = PublicationRemainderProjectionV1(CLIENT, COMMAND, 1, 2, content, link)
        invalid { f.cross.requireConsistent(f.journal(PublicationHistoryEntryV1.CancelledUnsentHistorical(link)), f.projection(link, hold = null, remainders = listOf(orphan))) }
    }
    @Test fun equalTextAtANewerRevisionStillRemainsAnUnsubmittedRemainder() {
        val f = Fixture(); val link = f.link(); val row = f.journal(PublicationHistoryEntryV1.PublishedHistorical(link, historical()))
        val remainder = PublicationRemainderProjectionV1(CLIENT, COMMAND, 1, 2, DraftLocalContentV1.TextV1(CAPTION, null), link)
        f.cross.requireConsistent(row, f.projection(link, hold = null, locals = emptyList(), published = listOf(terminal(link)), remainders = listOf(remainder)))
        invalid { f.cross.requireConsistent(row, f.projection(link, hold = null, locals = emptyList(), published = listOf(terminal(link)), remainders = listOf(remainder, remainder))) }
    }
    @Test fun reservationMustMatchActualOtherJournalPolicyAndExactRecomputedSize() {
        val f = Fixture(); val link = f.link(); val row = f.journal(PublicationHistoryEntryV1.PendingOriginal(link)); val actual = f.hold(link).reservation
        for (reserve in listOf(PublicationCapacityReservationV1(f.policy.maxResponseBytes - 1, actual.publicationReservedBytes, 1234, 1),
            PublicationCapacityReservationV1(f.policy.maxResponseBytes, actual.publicationReservedBytes - 1, 1234, 1),
            PublicationCapacityReservationV1(f.policy.maxResponseBytes, actual.publicationReservedBytes, 1234, 0),
            PublicationCapacityReservationV1(f.policy.maxResponseBytes, actual.publicationReservedBytes, 0, 1)))
            invalid { f.cross.requireConsistent(row, f.projection(link, hold = PublicationHoldProjectionV1(link, f.snapshot(), reserve))) }
        unavailable { f.cross.requireConsistent(row, f.projection(link, hold = PublicationHoldProjectionV1(link, f.snapshot(),
            PublicationCapacityReservationV1(f.policy.maxResponseBytes, actual.publicationReservedBytes, 1_048_577, 1)))) }
    }
    @Test fun pendingQueueComparisonPreservesActualExactIntentAndDoesNotInferAdmission() {
        val f = Fixture(); val link = f.link(raw = "\n" + write().toString())
        for (phase in CommandPhase.entries.filter { it !in setOf(CommandPhase.APPLIED, CommandPhase.DISCARDED) })
            f.cross.requirePendingIntent(link, observation(phase, if (phase == CommandPhase.AWAITING_CONFIRMATION) 0 else 1), link.originalIntentForComparison())
        val original = link.originalIntentForComparison()
        val wrongBody = CommandIntent(COMMAND, ORIGIN, ApiCall("publishPost", body = bytes(write()), idempotencyKey = SecretText(COMMAND)))
        for (intent in listOf(wrongBody, CommandIntent(COMMAND, SOURCE, original.call), CommandIntent(COMMAND, ORIGIN, original.call, listOf(COMMAND_2))))
            invalid { f.cross.requirePendingIntent(link, observation(CommandPhase.AWAITING_CONFIRMATION, 1), intent) }
        assertEquals("\n" + write().toString(), text(link.exactPostWrite))
    }
    @Test fun pendingAndArchivedQueueIdentityStatusAndRevisionCannotBeSubstituted() {
        val f = Fixture(); val link = f.link(); val intent = link.originalIntentForComparison()
        for (seen in listOf(observation(CommandPhase.APPLIED, 1), observation(CommandPhase.DISCARDED, 0),
            PublicationQueueObservation(COMMAND_2, "publishPost", CommandPhase.AWAITING_CONFIRMATION, 0, 1),
            PublicationQueueObservation(COMMAND, "updatePostDraft", CommandPhase.AWAITING_CONFIRMATION, 0, 1),
            PublicationQueueObservation(COMMAND, "publishPost", CommandPhase.AWAITING_CONFIRMATION, -1, 1),
            PublicationQueueObservation(COMMAND, "publishPost", CommandPhase.AWAITING_CONFIRMATION, 0, 0)))
            invalid { f.cross.requirePendingIntent(link, seen, intent) }
    }
    @Test fun archiveComparisonSeparatesPublishedActualAttemptFromCancelledZeroAttempt() {
        val f = Fixture(); val link = f.link(); val published = PublicationHistoryEntryV1.PublishedHistorical(link, historical())
        val cancelled = PublicationHistoryEntryV1.CancelledUnsentHistorical(link)
        f.cross.requireArchivedHistory(published, observation(CommandPhase.APPLIED, 1))
        f.cross.requireArchivedHistory(cancelled, observation(CommandPhase.DISCARDED, 0))
        for (seen in listOf(observation(CommandPhase.APPLIED, 0), observation(CommandPhase.RECEIPT_READY, 1), observation(CommandPhase.DISCARDED, 0)))
            invalid { f.cross.requireArchivedHistory(published, seen) }
        for (seen in listOf(observation(CommandPhase.DISCARDED, 1), observation(CommandPhase.APPLIED, 0)))
            invalid { f.cross.requireArchivedHistory(cancelled, seen) }
        invalid { f.cross.requireArchivedHistory(PublicationHistoryEntryV1.PendingOriginal(link), observation(CommandPhase.APPLIED, 1)) }
    }
    @Test fun archiveComparisonRevalidatesFullHistoricalReplyRatherThanTrustingItsKind() {
        val f = Fixture(); val link = f.link()
        invalid { f.cross.requireArchivedHistory(PublicationHistoryEntryV1.PublishedHistorical(link,
            historical(post().with("caption", JsonPrimitive("Not original")))), observation(CommandPhase.APPLIED, 1)) }
    }
    @Test fun crossProjectionsAndQueueObservationsRemainDetachedRedactedDataOnly() {
        val f = Fixture(); val link = f.link(); val ids = mutableListOf(CLIENT, COMMAND); val locals = mutableListOf(f.snapshot())
        val projection = f.projection(link, ids = ids, locals = locals); ids.clear(); locals.clear()
        assertEquals(2, projection.issuedIds.size); assertEquals(1, projection.locals.size)
        for (value in listOf(projection, f.hold(link), terminal(link), observation(CommandPhase.APPLIED, 1), f.cross))
            for (secret in listOf(CLIENT, COMMAND, CAPTION)) assertFalse(value.toString().contains(secret))
    }

    private class Fixture(val policy: PostPublicationClientPolicy = policy(), val binding: PublicationJournalBindingData = binding()) {
        val draftPolicy = PostDraftClientPolicy(64, 1_048_576, 262_144, 4096, 4096, 20, 1000, 300_000)
        val snapshots = DraftLocalSnapshotCodecV1(draftPolicy, policy)
        val links = PublicationOriginalLinkCodecV1(policy, snapshots); val codec = PostPublicationJournalCodecV1(binding, policy, links)
        val cross = PublicationCrossRecordValidator(codec, links, snapshots, policy, draftPolicy)
        fun snapshot(client: String = CLIENT, caption: String = CAPTION, alt: String? = null, association: DraftServerAssociationV1 = DraftServerAssociationV1.NotObserved) =
            snapshots.create(client, 1, DraftLocalContentV1.TextV1(caption, alt), association)
        fun composer(choices: JsonObject, display: String? = null) = snapshots.create(CLIENT, 1,
            DraftLocalContentV1.ComposerV2(doc(choices), display), DraftServerAssociationV1.NotObserved)
        fun link(body: JsonObject = write(), raw: String = body.toString(), command: String = COMMAND, client: String = CLIENT,
            created: Long = 10, snapshot: DraftLocalSnapshotV1 = snapshot(client), target: PublicationReviewTargetV1 = PublicationReviewTargetV1.DirectLocal): PublicationOriginalLinkV1 {
            val actualRaw = if (client == CLIENT) raw else body.with("clientDraftId", JsonPrimitive(client)).toString()
            return links.create(binding, command, created, ApiCall("publishPost", body = PrivateBytes(actualRaw.encodeToByteArray()),
                idempotencyKey = SecretText(command)), snapshot, target, disclosure())
        }
        fun saved(body: JsonObject, baseline: WireDocument, version: String) = link(body, snapshot = snapshot(
            association = DraftServerAssociationV1.Observed(baseline, "\"$version\"")),
            target = PublicationReviewTargetV1.SavedDraft(DRAFT, ExactPostVersion(version), "\"$version\"", baseline))
        fun journal(vararg values: PublicationHistoryEntryV1) = PublicationJournalV1(binding, 10, values.map { it.original.commandId }, values.toList())
        fun hold(link: PublicationOriginalLinkV1, current: DraftLocalSnapshotV1 = link.historicalReview.exactReviewedLocalSnapshot,
            journal: PublicationJournalV1 = journal(PublicationHistoryEntryV1.PendingOriginal(link))) = PublicationHoldProjectionV1(link, current,
            PublicationCapacityReservationV1(policy.maxResponseBytes, codec.reservedPublishedBytes(journal), 1234, 1))
        // Synthetic projection data only. Actual whole-draft reserve computation is tested by
        // the complete draft codec, never inferred from this focused cross-comparison fixture.
        fun projection(link: PublicationOriginalLinkV1, origin: String = binding.canonicalOrigin,
            locals: List<DraftLocalSnapshotV1> = listOf(link.historicalReview.exactReviewedLocalSnapshot),
            ids: List<String> = listOf(link.clientDraftId, link.commandId), discarded: Set<String> = emptySet(),
            hold: PublicationHoldProjectionV1? = hold(link), published: List<PublishedRootProjectionV2> = emptyList(),
            remainders: List<PublicationRemainderProjectionV1> = emptyList(), otherCommand: String? = null, otherCompletion: String? = null) =
            DraftPublicationProjectionV2(origin, locals, ids, discarded, hold, published, remainders, otherCommand, otherCompletion)
    }
    private companion object {
        const val ACCOUNT = "aaaaaaaa-1111-4111-8111-111111111111"
        const val CLIENT = "bbbbbbbb-2222-4222-8222-222222222222"
        const val COMMAND = "cccccccc-3333-4333-8333-333333333333"
        const val DRAFT = "dddddddd-4444-4444-8444-444444444444"
        const val POST = "eeeeeeee-5555-4555-8555-555555555555"
        const val SOURCE = "ffffffff-6666-4666-8666-666666666666"
        const val RECIPE = "aaaaaaaa-7777-4777-8777-777777777777"
        const val ORIGIN = "bbbbbbbb-8888-4888-8888-888888888888"
        const val MEDIA = "cccccccc-9999-4999-8999-999999999999"
        const val CLIENT_2 = "dddddddd-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val COMMAND_2 = "eeeeeeee-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val CIRCLE = "ffffffff-cccc-4ccc-8ccc-cccccccccccc"
        const val CAPTION = "Synthetic publication caption"
        const val DISCLOSURE = "synthetic-disclosure-v1"
        const val DISPLAY = "Actual synthetic disclosure shown during this test review."
        fun policy(maxRecord: Int = 1_048_576, maxOriginal: Int = 65_536, maxResponse: Int = 8192,
            maxRoots: Int = 10, maxIssued: Int = 30, maxMedia: Int = 10, maxCircle: Int = 10, maxChanges: Int = 10,
            maxDisclosure: Int = 4096) = PostPublicationClientPolicy(maxRecord, maxOriginal, maxResponse, maxRoots, maxIssued, 10,
            maxMedia, maxCircle, maxChanges, maxOriginal, maxDisclosure, 300_000)
        fun binding(origin: String = ORIGIN, environment: String = "test") = PublicationJournalBindingData(environment, origin.lowercase(), origin, ACCOUNT)
        fun disclosure() = PublicationDisclosure(DISCLOSURE, DISPLAY)
        fun write() = buildJsonObject { put("clientDraftId", CLIENT); put("caption", CAPTION); put("mediaIds", arr()); put("audience", self())
            put("keepOnPlate", false); put("allowRecipeSaves", false); put("saveDisclosureVersion", DISCLOSURE) }
        fun savedBody() = write().with("draftId", JsonPrimitive(DRAFT)).with("draftVersion", n("1"))
        fun draft(version: String = "1") = write().with("id", JsonPrimitive(DRAFT)).with("version", n(version)).with("status", JsonPrimitive("draft"))
            .with("createdAt", JsonPrimitive("2026-09-14T12:00:00Z")).with("updatedAt", JsonPrimitive("2026-09-14T12:00:00Z")).with("expiresAt", JsonPrimitive("2026-09-15T12:00:00Z"))
        fun post() = buildJsonObject { put("id", POST); put("version", 1); put("createdAt", "2026-09-14T12:00:00Z"); put("updatedAt", "2026-09-14T12:00:00Z")
            put("author", buildJsonObject { put("userId", ACCOUNT); put("displayName", "Synthetic cook"); put("handle", "synthetic"); put("avatarMediaId", MEDIA) })
            put("caption", CAPTION); put("mediaIds", arr()); put("audience", self().with("bindings", arr()))
            put("status", "published"); put("publishedAt", "2026-09-14T12:00:00Z"); put("expiresAt", "2026-09-15T12:00:00Z"); put("keepOnPlate", false)
            put("savePolicy", buildJsonObject { put("allowFutureSaves", false); put("policyVersion", 1); put("disclosureVersion", DISCLOSURE) })
            put("aclVersion", 1); put("capabilities", arr("view", "delete")); put("reactionCounts", arr()) }
        fun historical(body: JsonObject = post()) = HistoricalPublicationReplyV1(doc(body), "\"1\"", "application/json", null, null)
        fun terminal(link: PublicationOriginalLinkV1) = PublishedRootProjectionV2(link.clientDraftId, link.commandId, POST, link.reviewedLocalRevision, link)
        fun observation(phase: CommandPhase, attempts: Int) = PublicationQueueObservation(COMMAND, "publishPost", phase, attempts, 1)
        fun self() = buildJsonObject { put("kind", "self"); put("circleIds", arr()) }
        fun circles(vararg values: String) = buildJsonObject { put("kind", "circles"); put("circleIds", arr(*values)) }
        fun bindingRow(id: String, version: String) = obj("circleId", JsonPrimitive(id)).with("authorMembershipGeneration", n(version))
        fun catalog(id: String) = buildJsonObject { put("recipeVersionId", id); put("confirmedChanges", arr()); put("reviewStatus", "reviewed"); put("rightsBasis", "catalogRedistributable") }
        fun personal(recipe: JsonObject) = buildJsonObject { put("personalRecipe", recipe); put("confirmedChanges", arr()); put("reviewStatus", "personal"); put("rightsBasis", "creatorOriginal") }
        fun recipe(quantity: String, id: String) = buildJsonObject { put("title", "Synthetic recipe")
            put("ingredients", JsonArray(listOf(buildJsonObject { put("ingredientId", id); put("quantity", n(quantity)); put("unit", "g"); put("optional", false) })))
            put("steps", JsonArray(listOf(buildJsonObject { put("stepId", "mix"); put("position", 1); put("instruction", "Mix"); put("ingredientIds", arr(id)); put("requiredEquipmentIds", arr("bowl")); put("mandatorySafetyStep", false) })))
            put("servings", 1); put("activeMinutes", 1); put("totalMinutes", 1); put("utensilCount", 1); put("equipmentIds", arr("bowl")); put("modes", arr("assemble")) }
        fun doc(value: JsonObject) = WireDocument.parse(value.toString())
        fun doc(raw: String) = WireDocument.parse(raw)
        fun j(value: WireDocument) = Json.parseToJsonElement(text(value)).jsonObject
        fun j(value: PrivateBytes) = Json.parseToJsonElement(value.copyForCodec().decodeToString()).jsonObject
        fun text(value: WireDocument) = value.encodeUtf8().decodeToString()
        fun bytes(value: JsonObject) = PrivateBytes(value.toString().encodeToByteArray())
        fun n(raw: String) = Json.parseToJsonElement(raw)
        fun arr(vararg values: String) = JsonArray(values.map(::JsonPrimitive))
        fun obj(key: String, value: JsonElement) = JsonObject(mapOf(key to value))
        fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
        fun JsonObject.without(vararg keys: String) = JsonObject(filterKeys { it !in keys })
        fun JsonObject.optional(key: String, value: JsonElement?) = if (value == null) without(key) else with(key, value)
        fun invalid(action: () -> Unit) { assertEquals(FailureReason.INVALID_DATA, assertFailsWith<MealFailure> { action() }.reason) }
        fun unavailable(action: () -> Unit) { assertEquals(FailureReason.UNAVAILABLE, assertFailsWith<MealFailure> { action() }.reason) }
        fun conflict(action: () -> Unit) { assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { action() }.reason) }
    }
}
