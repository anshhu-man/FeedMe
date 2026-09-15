package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.MealFailure
import com.feedme.mealflow.json
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.CLIENT
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.document
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.draft
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure complete-format and actual-codec cross-record tests. No store, migration writer,
 * queue receipt provenance or live consent is supplied by these historical fixture values. */
class PostDraftV2CodecTest {
    @Test fun completeEnvelopeRequiresVersionEnvironmentOriginAndEveryExactField() {
        val f = Fixture(); val root = json(f.codec.encode(f.record()))
        for (changed in listOf(root - "publicationHold", root - "remainders", root + ("proof" to JsonPrimitive(true)),
            root + ("schema" to JsonPrimitive(1)), root + ("environment" to JsonPrimitive("other")),
            root + ("origin" to JsonPrimitive(CLIENT)))) invalid { f.codec.decode(2, bytes(changed)) }
        invalid { f.codec.decode(1, bytes(root)) }
        assertEquals(3L, f.round(f.record()).locals.single().localRevision)
    }

    @Test fun legacyCodecAndVersionRemainUnchangedAndNeverReadCompleteVersionTwo() {
        val f = Fixture(); val old = PostRecord(10, listOf(PostLocal(CLIENT, 3, "Caption", null)), listOf(CLIENT))
        val codec = PostDraftCodec(ORIGIN.lowercase(), f.draftPolicy); val raw = codec.encode(old)
        assertContentEquals(raw.copyForCodec(), codec.encode(codec.decode(raw)).copyForCodec())
        invalid { f.codec.decode(1, raw) }
        assertFails { codec.decode(f.codec.encode(f.record())) }
        assertEquals(JsonPrimitive(1), json(raw)["schema"])
    }

    @Test fun everyLegacyTextOriginalRetainsExactRawBodyBaselineEtagCreatedAndDerivedPath() {
        val f = Fixture()
        for (operation in listOf("createPostDraft", "updatePostDraft", "deletePostDraft")) {
            val original = f.legacyOriginal(operation)
            val command = when (operation) {
                "createPostDraft" -> PostDraftCommandV2.TextCreate(original)
                "updatePostDraft" -> PostDraftCommandV2.TextPatch(original)
                else -> PostDraftCommandV2.Discard(original)
            }
            val result = f.round(f.record(command = command)).command!!
            assertTrue(postSameCall(original.call(), result.historicalCall()))
            assertEquals(original.created, result.created); assertEquals(original.localRevision, result.localRevision)
            original.body?.let { assertContentEquals(it.encodeUtf8(), result.legacyOriginal!!.body!!.encodeUtf8()) }
            original.baseline?.let { assertContentEquals(it.encodeUtf8(), result.legacyOriginal!!.baseline!!.encodeUtf8()) }
        }
    }

    @Test fun legacyTextAllowlistCannotBeWidenedByChangingOnlyTheDiscriminator() {
        val f = Fixture(); val original = f.legacyOriginal("updatePostDraft")
        val full = PostOriginal(original.id, original.operation, original.clientId, original.localRevision,
            document(mapOf("caption" to JsonPrimitive("Caption"), "keepOnPlate" to JsonPrimitive(true))),
            original.baseline, original.etag, original.created)
        invalid { f.codec.encode(f.record(command = PostDraftCommandV2.TextPatch(full))) }
        invalid { f.codec.encode(f.record(command = PostDraftCommandV2.TextCreate(original))) }
        val root = json(f.codec.encode(f.record(command = PostDraftCommandV2.TextPatch(original))))
        val c = root.getValue("command").jsonObject + ("kind" to JsonPrimitive("reviewed-patch-v2"))
        invalid { f.codec.decode(2, bytes(root + ("command" to JsonObject(c)))) }
    }

    @Test fun validLegacyCommandAndNewerLocalPendingRemainRepresentableWithoutRewritingOriginal() {
        val f = Fixture(); val original = f.legacyOriginal("updatePostDraft")
        val newer = f.snapshots.withText(f.snapshot(), "Newer text", "", 4)
        val input = f.record(locals = listOf(newer), command = PostDraftCommandV2.TextPatch(original), pending = PostLocalPending(CLIENT, 4))
        val result = f.round(input)
        assertEquals("Newer text", result.locals.single().content.caption)
        assertEquals(PostLocalPending(CLIENT, 4), result.localPending)
        assertTrue(postSameCall(original.call(), result.command!!.historicalCall()))
        assertEquals(3L, result.command!!.localRevision)
    }

    @Test fun legacyCompletionAndDiscardTerminalKeepOriginalMeaningWithoutPublishedFabrication() {
        val f = Fixture()
        for (operation in listOf("createPostDraft", "updatePostDraft", "deletePostDraft")) for (unsent in listOf(false, true)) {
            val old = PostCompletion(COMMAND, operation, CLIENT, unsent)
            assertEquals(old, assertIs<PostDraftCompletionV2.Legacy>(f.round(f.record(completion = PostDraftCompletionV2.Legacy(old))).completion).exactLegacy)
        }
        val old = PostTerminal(CLIENT, DRAFT, COMMAND)
        val input = f.record(locals = emptyList(), terminals = listOf(PostDraftTerminalV2.LegacyDiscard(old)),
            completion = PostDraftCompletionV2.Legacy(PostCompletion(COMMAND, "deletePostDraft", CLIENT, false)))
        assertEquals(old, assertIs<PostDraftTerminalV2.LegacyDiscard>(f.round(input).terminals.single()).exactLegacy)
        invalid { f.codec.encode(input.copy(locals = listOf(f.snapshot()))) }
    }

    @Test fun legacyHistoricalLargeVersionAndBodyWhitespaceRemainLossless() {
        val f = Fixture(); val version = "9007199254740993123456789"
        val baseline = rawDoc(" \n${f.baseline(version).encodeUtf8().decodeToString()}\n")
        val original = PostOriginal(COMMAND, "updatePostDraft", CLIENT, 3, rawDoc(" { \"caption\" : \"Caption\" } \n"), baseline, "\"$version\"", 7)
        val local = f.snapshots.fromLegacy(PostLocal(CLIENT, 3, "Caption", null, baseline, original.etag))
        val round = f.round(f.record(locals = listOf(local), command = PostDraftCommandV2.TextPatch(original)))
        assertTrue(postSameCall(original.call(), round.command!!.historicalCall()))
        assertContentEquals(local.exactUtf8.copyForCodec(), round.locals.single().exactUtf8.copyForCodec())
    }

    @Test fun reviewedOriginalRetainsUppercasePathEmptyQueryAndEveryExactHistoricalByte() {
        val f = Fixture(); val c = f.reviewed()
        val result = assertIs<PostDraftCommandV2.ReviewedPatch>(f.round(f.record(locals = listOf(c.historicalReview.exactReviewedLocalSnapshot), command = c)).command)
        assertTrue(postSameCall(c.historicalCall(), result.historicalCall()))
        assertEquals(mapOf("draftId" to DRAFT.uppercase()), result.original.path)
        assertTrue(result.historicalCall().queryParameters.isEmpty())
        assertContentEquals(c.original.body.copyForCodec(), result.original.body.copyForCodec())
        assertContentEquals(c.original.baseline.encodeUtf8(), result.original.baseline.encodeUtf8())
        assertContentEquals(c.historicalReview.exactUtf8.copyForCodec(), result.historicalReview.exactUtf8.copyForCodec())
        assertContentEquals(c.expectedFields.encodeUtf8(), result.expectedFields.encodeUtf8())
    }

    @Test fun reviewedOriginalPathBaselineKeyVersionAndBodySubstitutionsAreRejected() {
        val f = Fixture(); val c = f.reviewed(); val root = json(f.codec.encode(f.record(locals = listOf(c.historicalReview.exactReviewedLocalSnapshot), command = c)))
        val command = root.getValue("command").jsonObject; val o = command.getValue("original").jsonObject
        for (changed in listOf(o + ("path" to buildJsonObject { put("draftId", number(99)) }),
            o + ("query" to buildJsonObject { put("limit", "1") }), o + ("operation" to JsonPrimitive("publishPost")),
            o + ("etag" to JsonPrimitive("\"2\"")), o + ("body" to JsonPrimitive("{\"caption\":\"Changed\"}"))))
            invalid { f.codec.decode(2, bytes(root + ("command" to JsonObject(command + ("original" to JsonObject(changed)))))) }
        val map = mutableMapOf("draftId" to DRAFT)
        val exact = ReviewedDraftOriginalFieldsV2(COMMAND, CLIENT, 3, map, c.original.body, c.original.baseline, c.original.etag, 7)
        map["draftId"] = number(99)
        assertEquals(DRAFT, exact.path.getValue("draftId"))
        assertFails { ReviewedDraftOriginalFieldsV2(COMMAND, CLIENT, 3, map + ("other" to "x"), c.original.body, c.original.baseline, c.original.etag, 7) }
    }

    @Test fun reviewedExpectedProjectionIsNotACanonicalDraftAndCannotBeChangedOrTurnedIntoAck() {
        val f = Fixture(); val c = f.reviewed(); val fields = c.expectedFields.json().jsonObject
        assertFalse("updatedAt" in fields); assertFalse("expiresAt" in fields)
        val wrong = PostDraftCommandV2.ReviewedPatch(c.original, document(fields + ("caption" to JsonPrimitive("Wrong"))), c.historicalReview)
        invalid { f.codec.encode(f.record(locals = listOf(c.historicalReview.exactReviewedLocalSnapshot), command = wrong)) }
        val root = json(f.codec.encode(f.record(locals = listOf(c.historicalReview.exactReviewedLocalSnapshot), command = c)))
        invalid { f.codec.decode(2, bytes(root + ("acknowledged" to JsonPrimitive(true)))) }
    }

    @Test fun removeAttachmentIsRequestOnlyAndFalseOrOmittedPreserveWhileTrueRemoves() {
        val f = Fixture(); val attachment = buildJsonObject {
            put("recipeVersionId", number(80)); put("confirmedChanges", JsonArray(emptyList()))
            put("reviewStatus", "reviewed"); put("rightsBasis", "catalogRedistributable")
        }
        val baseline = document(f.baseline().json().jsonObject + ("attachment" to attachment))
        for (flag in listOf<Boolean?>(null, false, true)) {
            val patch = buildJsonObject { put("caption", "Caption"); flag?.let { put("removeAttachment", it) } }
            val c = f.reviewed(patch, baseline, display = null)
            assertEquals(flag != true, "attachment" in c.expectedFields.json().jsonObject)
            assertFalse("removeAttachment" in c.expectedFields.json().jsonObject)
            val round = assertIs<PostDraftCommandV2.ReviewedPatch>(f.round(f.record(locals = listOf(c.historicalReview.exactReviewedLocalSnapshot), command = c)).command)
            assertContentEquals(c.original.body.copyForCodec(), round.original.body.copyForCodec())
        }
        invalid { f.reviewed(buildJsonObject { put("removeAttachment", true); put("attachment", attachment) }, baseline) }
    }

    @Test fun reviewedSnapshotMustPinActualBaselineAndExactResultantTextPresence() {
        val f = Fixture(); val c = f.reviewed(); val s = c.historicalReview.exactReviewedLocalSnapshot
        invalid { f.codec.createReviewedCommand(c.original, f.snapshot(), f.disclosure) }
        invalid { f.codec.createReviewedCommand(c.original, f.snapshots.create(CLIENT, 3, DraftLocalContentV1.TextV1("Caption", ""), s.serverAssociation), f.disclosure) }
        invalid { f.codec.createReviewedCommand(c.original, f.snapshots.create(CLIENT, 4, s.content, s.serverAssociation), f.disclosure) }
        val reordered = rawDoc(" ${c.original.baseline.encodeUtf8().decodeToString()} ")
        invalid { f.codec.createReviewedCommand(c.original, f.snapshots.create(CLIENT, 3, s.content, DraftServerAssociationV1.Observed(reordered, "\"1\"")), f.disclosure) }
    }

    @Test fun composerReviewedPatchBindsAllStoredChoicesAndKeepsServiceNormalizationSeparate() {
        val f = Fixture(); val choices = f.write().json().jsonObject - "clientDraftId" + mapOf("mediaIds" to JsonArray(listOf(JsonPrimitive(MEDIA.uppercase()))))
        val baseline = f.baseline(); val snapshot = f.snapshots.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(document(choices), f.disclosure.text),
            DraftServerAssociationV1.Observed(baseline, "\"1\""))
        val original = f.reviewedFields(document(choices), baseline)
        val c = f.codec.createReviewedCommand(original, snapshot, f.disclosure)
        assertEquals(JsonArray(listOf(JsonPrimitive(MEDIA))), c.expectedFields.json().jsonObject["mediaIds"])
        assertEquals(JsonArray(listOf(JsonPrimitive(MEDIA.uppercase()))), snapshot.content.let { it as DraftLocalContentV1.ComposerV2 }.exactChoices.json().jsonObject["mediaIds"])
        val different = f.snapshots.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(document(choices + ("keepOnPlate" to JsonPrimitive(true))), f.disclosure.text), snapshot.serverAssociation)
        invalid { f.codec.createReviewedCommand(original, different, f.disclosure) }
    }

    @Test fun changedDisclosureRequiresActuallySuppliedMatchingVersionAndTextNotHistoricalAbsence() {
        val f = Fixture()
        invalid { f.reviewed(display = null) }
        invalid { f.reviewed(display = PublicationDisclosure("wrong", "Actual wrong version")) }
        val textOnly = f.reviewed(buildJsonObject { put("caption", "Caption") }, display = null)
        assertNull(textOnly.historicalReview.displayedDisclosure)
        assertFalse("saveDisclosureVersion" in textOnly.expectedFields.json().jsonObject)
        assertNull(assertIs<PostDraftCommandV2.ReviewedPatch>(f.round(f.record(locals = listOf(textOnly.historicalReview.exactReviewedLocalSnapshot), command = textOnly)).command).historicalReview.displayedDisclosure)
    }

    @Test fun reviewedAppliedCompletionRetainsExactOriginalActualResultAndEtagButNoQueueProof() {
        val f = Fixture(); val c = f.reviewed(); val actual = f.reviewedResult(c)
        val observed = f.snapshots.withServerAssociation(c.historicalReview.exactReviewedLocalSnapshot, DraftServerAssociationV1.Observed(actual, "\"2\""))
        val completion = PostDraftCompletionV2.ReviewedApplied(c, actual, "\"2\"")
        val parsed = assertIs<PostDraftCompletionV2.ReviewedApplied>(f.round(f.record(locals = listOf(observed), completion = completion)).completion)
        assertContentEquals(actual.encodeUtf8(), parsed.actualDraft.encodeUtf8()); assertEquals("\"2\"", parsed.etag)
        assertTrue(postSameCall(c.historicalCall(), parsed.original.historicalCall()))
        assertFalse(parsed.unsent)
        val root = json(f.codec.encode(f.record(locals = listOf(observed), completion = completion)))
        assertFalse("receiptRevision" in root.getValue("completion").jsonObject)
    }

    @Test fun reviewedCompletionRejectsLaterMatchingVersionWrongEtagProjectionAndMissingObservation() {
        val f = Fixture(); val c = f.reviewed(); val actual = f.reviewedResult(c)
        val local = f.snapshots.withServerAssociation(c.historicalReview.exactReviewedLocalSnapshot, DraftServerAssociationV1.Observed(actual, "\"2\""))
        for ((body, etag) in listOf(actual to "\"3\"", document(actual.json().jsonObject + ("version" to JsonPrimitive(3))) to "\"3\"", c.expectedFields to "\"2\""))
            invalid { f.codec.encode(f.record(locals = listOf(local), completion = PostDraftCompletionV2.ReviewedApplied(c, body, etag))) }
        assertFails { f.codec.encode(f.record(locals = listOf(c.historicalReview.exactReviewedLocalSnapshot), completion = PostDraftCompletionV2.ReviewedApplied(c, actual, "\"2\""))) }
    }

    @Test fun reviewedUnsentCompletionRetainsOriginalAndNeverFabricatesCanonicalResponse() {
        val f = Fixture(); val c = f.reviewed()
        val input = f.record(locals = listOf(c.historicalReview.exactReviewedLocalSnapshot), completion = PostDraftCompletionV2.ReviewedUnsent(c))
        val parsed = assertIs<PostDraftCompletionV2.ReviewedUnsent>(f.round(input).completion)
        assertTrue(parsed.unsent); assertTrue(postSameCall(c.historicalCall(), parsed.original.historicalCall()))
        assertFalse("actualDraftUtf8" in json(f.codec.encode(input)).getValue("completion").jsonObject)
        invalid { f.codec.encode(input.copy(command = c)) }
    }

    @Test fun reviewedOriginalAllowsNewerEditsButRejectsSameRevisionContentOrAssociationRebinding() {
        val f = Fixture(); val c = f.reviewed(); val prior = c.historicalReview.exactReviewedLocalSnapshot
        val newer = f.snapshots.withText(prior, "New local edit", "", 4)
        assertTrue(postSameCall(c.historicalCall(), f.round(f.record(locals = listOf(newer), command = c, pending = PostLocalPending(CLIENT, 4))).command!!.historicalCall()))
        invalid { f.codec.encode(f.record(locals = listOf(f.snapshots.create(CLIENT, 3, newer.content, prior.serverAssociation)), command = c)) }
        invalid { f.codec.encode(f.record(locals = listOf(f.snapshots.create(CLIENT, 3, prior.content, DraftServerAssociationV1.NotObserved)), command = c)) }
    }

    @Test fun newReviewedRawSizeIsRefusedBeforeInvalidUtf8DecodingOrJsonExpansion() {
        val f = Fixture(); val baseline = f.baseline()
        val huge = PrivateBytes(ByteArray(f.publicationPolicy.maxOriginalBytes + 1) { 0xff.toByte() })
        val original = ReviewedDraftOriginalFieldsV2(COMMAND, CLIENT, 3, mapOf("draftId" to DRAFT), huge, baseline, "\"1\"", 7)
        unavailable { f.codec.createReviewedCommand(original, f.snapshot(), f.disclosure) }
    }

    @Test fun actualJointHoldReservesBothRowsAndExactLinkWithNoSyntheticDraftCapacity() {
        val f = Fixture(); val link = f.link(); val publication = f.journal(PublicationHistoryEntryV1.PendingOriginal(link))
        val value = f.held(link, publication)
        f.cross.requireConsistent(publication, f.codec.publicationProjection(value))
        assertEquals(f.publications.reservedPublishedBytes(publication), value.publicationHold!!.reservation.publicationReservedBytes)
        assertTrue(value.publicationHold!!.reservation.draftFinalizationReservedBytes > 0)
        assertContentEquals(link.exactUtf8.copyForCodec(), f.round(value).publicationHold!!.link.exactUtf8.copyForCodec())
    }

    @Test fun actualDraftProjectionRejectsTamperedReservationBeforeCrossValidation() {
        val f = Fixture(); val value = f.held(); val hold = value.publicationHold!!; val r = hold.reservation
        for (reserve in listOf(PublicationCapacityReservationV1(r.maxResponseBytes, r.publicationReservedBytes, 1234, 1),
            PublicationCapacityReservationV1(r.maxResponseBytes, r.publicationReservedBytes, r.draftFinalizationReservedBytes + 1, 1),
            PublicationCapacityReservationV1(r.maxResponseBytes, r.publicationReservedBytes, r.draftFinalizationReservedBytes, 0)))
            invalid { f.codec.publicationProjection(value.copy(publicationHold = PostDraftPublicationHoldV2(hold.link, reserve))) }
    }

    @Test fun everyLaterEditRecomputesReserveAndOldReservedValueCannotSilentlyRemainValid() {
        val f = Fixture(); val before = f.held(); val old = before.publicationHold!!.reservation
        val newer = f.snapshots.withText(before.locals.single(), "A much longer new unsubmitted caption", "Accessibility description", 4)
        val edited = before.copy(locals = listOf(newer), localPending = PostLocalPending(CLIENT, 4))
        invalid { f.codec.encode(edited) }
        val reserved = f.codec.withPublicationReservation(edited, old.publicationReservedBytes)
        assertTrue(reserved.publicationHold!!.reservation.draftFinalizationReservedBytes > old.draftFinalizationReservedBytes)
        f.cross.requireConsistent(f.journal(PublicationHistoryEntryV1.PendingOriginal(before.publicationHold!!.link)), f.codec.publicationProjection(reserved))
    }

    @Test fun finalizationCapacityCoversCompleteActualTerminalAndFullNewerRemainderAtMaxClock() {
        val f = Fixture(); val held = f.held(); val link = held.publicationHold!!.link
        val newer = f.snapshots.withText(held.locals.single(), "New unsent caption", "", 4)
        val reserved = f.codec.withPublicationReservation(held.copy(locals = listOf(newer)), held.publicationHold!!.reservation.publicationReservedBytes)
        val remainder = PostDraftRemainderV2(link, f.snapshots.create(CLIENT, 4, newer.content, DraftServerAssociationV1.NotObserved))
        val actual = reserved.copy(clock = Long.MAX_VALUE, locals = emptyList(), publicationHold = null,
            terminals = listOf(PostDraftTerminalV2.Published(link, POST)), remainders = listOf(remainder))
        assertTrue(f.codec.encode(actual).copyForCodec().size.toLong() <= reserved.publicationHold!!.reservation.draftFinalizationReservedBytes)
        f.cross.requireConsistent(f.journal(PublicationHistoryEntryV1.PublishedHistorical(link, f.reply())), f.codec.publicationProjection(actual))
    }

    @Test fun finalizationSlotMustBeReservedBeforeHoldEvenWhenNoNewerEditExists() {
        val f = Fixture(remainderLimit = 1); val prior = f.link(client = number(90), command = number(91))
        val terminal = PostDraftTerminalV2.Published(prior, number(92))
        val remainder = PostDraftRemainderV2(prior, f.snapshots.create(prior.clientDraftId, 4, DraftLocalContentV1.TextV1("Old unsent", null), DraftServerAssociationV1.NotObserved))
        val base = f.held().copy(issued = listOf(CLIENT, COMMAND, prior.clientDraftId, prior.commandId), terminals = listOf(terminal), remainders = listOf(remainder))
        unavailable { f.codec.withPublicationReservation(base, base.publicationHold!!.reservation.publicationReservedBytes) }
        assertEquals(3L, base.locals.single().localRevision)
    }

    @Test fun actualRecordByteLimitRefusesLargeLaterContentWithoutDroppingAnyRemainderOrIssuedId() {
        val f = Fixture(recordBytes = 65_536); val held = f.held()
        val oldBytes = f.codec.encode(held).copyForCodec()
        val locals = (100..115).map { id -> f.snapshots.create(number(id), 1,
            DraftLocalContentV1.ComposerV2(document(f.write().json().jsonObject - "clientDraftId"), "d".repeat(4000)), DraftServerAssociationV1.NotObserved) }
        val tooLarge = held.copy(locals = held.locals + locals, issued = held.issued + locals.map { it.clientDraftId })
        unavailable { f.codec.withPublicationReservation(tooLarge, held.publicationHold!!.reservation.publicationReservedBytes) }
        assertContentEquals(oldBytes, f.codec.encode(held).copyForCodec()); assertEquals(listOf(CLIENT, COMMAND), held.issued)
    }

    @Test fun sameRootDraftCommandAndCompletionAreExcludedButOtherRootTextAndPendingRemainAllowed() {
        val f = Fixture(); val held = f.held(); val old = f.legacyOriginal("createPostDraft")
        invalid { f.codec.withPublicationReservation(held.copy(command = PostDraftCommandV2.TextCreate(old)), held.publicationHold!!.reservation.publicationReservedBytes) }
        invalid { f.codec.withPublicationReservation(held.copy(completion = PostDraftCompletionV2.Legacy(PostCompletion(COMMAND, "createPostDraft", CLIENT, false))), held.publicationHold!!.reservation.publicationReservedBytes) }
        val otherRoot = number(70); val otherCommand = number(71); val otherLocal = f.snapshot(client = otherRoot)
        val otherOriginal = PostOriginal(otherCommand, "createPostDraft", otherRoot, 3, document(f.write(otherRoot).json().jsonObject - "saveDisclosureVersion"), null, null, 7)
        val allowed = f.codec.withPublicationReservation(held.copy(locals = held.locals + otherLocal, issued = held.issued + listOf(otherRoot, otherCommand),
            command = PostDraftCommandV2.TextCreate(otherOriginal), localPending = PostLocalPending(otherRoot, 3)), held.publicationHold!!.reservation.publicationReservedBytes)
        f.cross.requireConsistent(f.journal(PublicationHistoryEntryV1.PendingOriginal(held.publicationHold!!.link)), f.codec.publicationProjection(allowed))
        assertEquals(otherRoot, allowed.command!!.clientId)
    }

    @Test fun savedOriginalPermitsLaterTerminalObservationWithoutChangingPinnedBaselineOrBranch() {
        val f = Fixture(); val link = f.link(saved = true); val held = f.held(link)
        val terminal = document(f.baseline("2", withDisclosure = true).json().jsonObject + mapOf("status" to JsonPrimitive("published"), "publishedPostId" to JsonPrimitive(POST)))
        val observed = f.snapshots.withServerAssociation(held.locals.single(), DraftServerAssociationV1.Observed(terminal, "\"2\""))
        val current = f.codec.withPublicationReservation(held.copy(locals = listOf(observed)), held.publicationHold!!.reservation.publicationReservedBytes)
        f.cross.requireConsistent(f.journal(PublicationHistoryEntryV1.PendingOriginal(link)), f.codec.publicationProjection(current))
        val target = assertIs<PublicationReviewTargetV1.SavedDraft>(current.publicationHold!!.link.historicalReview.target)
        assertEquals("1", target.draftVersion.decimal); assertEquals(3L, observed.localRevision)
        assertContentEquals(link.exactUtf8.copyForCodec(), current.publicationHold!!.link.exactUtf8.copyForCodec())
    }

    @Test fun holdSameRevisionContentAssociationDemotionAndForeignBindingAreRejected() {
        val f = Fixture(); val link = f.link(saved = true); val held = f.held(link); val reserve = held.publicationHold!!.reservation.publicationReservedBytes
        invalid { f.codec.withPublicationReservation(held.copy(locals = listOf(f.snapshot())), reserve) }
        val original = held.locals.single()
        invalid { f.codec.withPublicationReservation(held.copy(locals = listOf(f.snapshots.create(CLIENT, 3, DraftLocalContentV1.TextV1("Changed", null), original.serverAssociation))), reserve) }
        val other = Fixture(environment = "different").link()
        invalid { f.codec.withPublicationReservation(f.record().copy(publicationHold = PostDraftPublicationHoldV2(other, held.publicationHold!!.reservation)), reserve) }
        val alias = Fixture(originBinding = ORIGIN.lowercase()).link()
        invalid { f.codec.withPublicationReservation(f.record().copy(publicationHold = PostDraftPublicationHoldV2(alias, held.publicationHold!!.reservation)), reserve) }
    }

    @Test fun completeComposerPinSurvivesSameRevisionIndependentPublishedGetWithoutRebasingChoices() {
        val f = Fixture(); val choices = document(f.write().json().jsonObject - "clientDraftId")
        val baseline = f.baseline(withDisclosure = true)
        val reviewed = f.snapshots.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(choices, f.disclosure.text),
            DraftServerAssociationV1.Observed(baseline, "\"1\""))
        val original = document(f.write().json().jsonObject + mapOf("draftId" to JsonPrimitive(DRAFT), "draftVersion" to JsonPrimitive(1)))
        val link = f.links.create(f.binding, COMMAND, 7, ApiCall("publishPost", body = PrivateBytes(original.encodeUtf8()), idempotencyKey = SecretText(COMMAND)),
            reviewed, PublicationReviewTargetV1.SavedDraft(DRAFT, ExactPostVersion("1"), "\"1\"", baseline), f.disclosure)
        val hold = f.held(link)
        val observed = document(f.baseline("2", withDisclosure = true).json().jsonObject +
            mapOf("status" to JsonPrimitive("published"), "publishedPostId" to JsonPrimitive(POST)))
        val current = f.snapshots.withServerAssociation(reviewed, DraftServerAssociationV1.Observed(observed, "\"2\""))
        val next = f.codec.withPublicationReservation(hold.copy(locals = listOf(current)), hold.publicationHold!!.reservation.publicationReservedBytes)
        f.cross.requireConsistent(f.journal(PublicationHistoryEntryV1.PendingOriginal(link)), f.codec.publicationProjection(next))
        assertContentEquals(choices.encodeUtf8(), assertIs<DraftLocalContentV1.ComposerV2>(next.locals.single().content).exactChoices.encodeUtf8())
        assertContentEquals(reviewed.exactUtf8.copyForCodec(), next.publicationHold!!.link.historicalReview.exactReviewedLocalSnapshot.exactUtf8.copyForCodec())
        assertTrue(postSameCall(link.originalIntentForComparison().call, next.publicationHold!!.link.originalIntentForComparison().call))
        assertEquals(3L, current.localRevision)
    }

    @Test fun unrelatedDraftHistoryChangesRecomputeTheWholeReserveAndPreserveOriginalReplayBytes() {
        val f = Fixture(); val held = f.held(); val link = held.publicationHold!!.link
        val otherRoot = number(70); val otherCommand = number(71)
        val input = held.copy(issued = held.issued + listOf(otherRoot, otherCommand),
            terminals = listOf(PostDraftTerminalV2.LegacyDiscard(PostTerminal(otherRoot, number(72), otherCommand))),
            completion = PostDraftCompletionV2.Legacy(PostCompletion(otherCommand, "deletePostDraft", otherRoot, false)))
        invalid { f.codec.encode(input) }
        val next = f.codec.withPublicationReservation(input, held.publicationHold!!.reservation.publicationReservedBytes)
        assertTrue(next.publicationHold!!.reservation.draftFinalizationReservedBytes > held.publicationHold!!.reservation.draftFinalizationReservedBytes)
        f.cross.requireConsistent(f.journal(PublicationHistoryEntryV1.PendingOriginal(link)), f.codec.publicationProjection(next))
        assertTrue(postSameCall(link.originalIntentForComparison().call, f.round(next).publicationHold!!.link.originalIntentForComparison().call))
        assertEquals(otherRoot, next.completion!!.clientId)
    }

    @Test fun publicationResponseReserveDenialLeavesPendingOriginalAndBothExactRowsRecoverable() {
        val f = Fixture(); val held = f.held(); val link = held.publicationHold!!.link
        val row = f.journal(PublicationHistoryEntryV1.PendingOriginal(link))
        val oldDraft = f.codec.encode(held); val oldPublication = f.publications.encode(row)
        unavailable { f.codec.withPublicationReservation(held, f.publicationPolicy.maxRecordBytes.toLong() + 1) }
        assertContentEquals(oldDraft.copyForCodec(), f.codec.encode(held).copyForCodec())
        assertContentEquals(oldPublication.copyForCodec(), f.publications.encode(row).copyForCodec())
        f.cross.requireConsistent(row, f.codec.publicationProjection(held))
        assertTrue(postSameCall(link.originalIntentForComparison().call, f.round(held).publicationHold!!.link.originalIntentForComparison().call))
    }

    @Test fun publishedRootsAreClosedAndFullRemaindersRequireExactTerminalAndNewerRevision() {
        val f = Fixture(); val link = f.link(); val terminal = PostDraftTerminalV2.Published(link, POST)
        val newer = f.snapshots.create(CLIENT, 4, DraftLocalContentV1.TextV1("Still unsent", ""), DraftServerAssociationV1.NotObserved)
        val value = f.record(locals = emptyList(), terminals = listOf(terminal)).copy(remainders = listOf(PostDraftRemainderV2(link, newer)))
        assertEquals("Still unsent", f.round(value).remainders.single().content.caption)
        assertEquals("", f.round(value).remainders.single().content.altText)
        invalid { f.codec.encode(value.copy(locals = listOf(f.snapshot()))) }
        invalid { f.codec.encode(value.copy(terminals = emptyList())) }
        invalid { f.codec.encode(value.copy(remainders = listOf(PostDraftRemainderV2(link, f.snapshot())))) }
        invalid { f.codec.encode(value.copy(remainders = listOf(PostDraftRemainderV2(link,
            f.snapshots.create(CLIENT, 4, newer.content, DraftServerAssociationV1.Observed(f.baseline(), "\"1\"")))))) }
        val different = f.link(command = number(88))
        invalid { f.codec.encode(value.copy(issued = value.issued + different.commandId, remainders = listOf(PostDraftRemainderV2(different, newer)))) }
    }

    @Test fun duplicateRootsPostIdsRemaindersAndPublicationCommandRootCollisionsAreRejected() {
        val f = Fixture(); val a = f.link(); val b = f.link(client = number(90), command = number(91))
        val row = f.record(locals = emptyList(), terminals = listOf(PostDraftTerminalV2.Published(a, POST), PostDraftTerminalV2.Published(b, POST)))
            .copy(issued = listOf(CLIENT, COMMAND, b.clientDraftId, b.commandId))
        invalid { f.codec.encode(row) }
        invalid { f.codec.encode(f.record(locals = listOf(f.snapshot(), f.snapshot()))) }
        invalid { f.codec.encode(f.record().copy(issued = listOf(CLIENT, COMMAND, COMMAND))) }
        val collision = f.held().copy(locals = listOf(f.snapshot(), f.snapshot(client = COMMAND)))
        invalid { f.codec.withPublicationReservation(collision, collision.publicationHold!!.reservation.publicationReservedBytes) }
    }

    @Test fun jointCancelledUnsentKeepsOpenRootAndCanUseOnlyAnotherRetainedCommand() {
        val f = Fixture(); val previous = f.link(); val cancelled = f.journal(PublicationHistoryEntryV1.CancelledUnsentHistorical(previous))
        f.cross.requireConsistent(cancelled, f.codec.publicationProjection(f.record()))
        val next = f.link(command = number(88)); val journal = f.journal(PublicationHistoryEntryV1.CancelledUnsentHistorical(previous), PublicationHistoryEntryV1.PendingOriginal(next))
        val nextHold = f.held(next, journal).copy(issued = listOf(CLIENT, COMMAND, next.commandId))
        val reserved = f.codec.withPublicationReservation(nextHold, f.publications.reservedPublishedBytes(journal))
        f.cross.requireConsistent(journal, f.codec.publicationProjection(reserved))
        assertEquals(CLIENT, reserved.locals.single().clientDraftId)
        assertNotEquals(previous.commandId, next.commandId)
    }

    @Test fun jointValidationRejectsMissingOtherJournalTerminalAndChangedPublicationReserve() {
        val f = Fixture(); val held = f.held(); val link = held.publicationHold!!.link
        invalid { f.cross.requireConsistent(f.journal(), f.codec.publicationProjection(held)) }
        val terminal = f.record(locals = emptyList(), terminals = listOf(PostDraftTerminalV2.Published(link, POST)))
        invalid { f.cross.requireConsistent(f.journal(), f.codec.publicationProjection(terminal)) }
        val badNumber = f.codec.withPublicationReservation(held, held.publicationHold!!.reservation.publicationReservedBytes + 1)
        invalid { f.cross.requireConsistent(f.journal(PublicationHistoryEntryV1.PendingOriginal(link)), f.codec.publicationProjection(badNumber)) }
    }

    @Test fun immutableCurrentAndLegacyEntryAggregatesDoNotExposeRetainedListAliases() {
        val f = Fixture(); val source = mutableListOf(f.snapshot(), f.snapshot(client = number(90)))
        val ids = mutableListOf(CLIENT, number(90)); val record = PostDraftV2Record(10, source, ids)
        source.clear(); ids.clear()
        (record.locals as MutableList).clear(); (record.issued as MutableList).clear()
        assertEquals(2, record.locals.size); assertEquals(2, record.issued.size)
        val locals = mutableListOf(PostLocal(CLIENT, 3, "Caption", null), PostLocal(number(90), 1, "Second", ""))
        val oldIds = mutableListOf(CLIENT, number(90)); val old = PostDraftJournalEntry.Legacy(PostEntry(null, PostRecord(10, locals, oldIds)))
        locals.clear(); oldIds.clear(); (old.entry.value.locals as MutableList).clear(); (old.entry.value.issued as MutableList).clear()
        assertEquals(2, old.entry.value.locals.size); assertEquals(2, old.entry.value.issued.size)
        assertFalse(record.toString().contains("Caption")); assertFalse(old.toString().contains("Caption"))
    }

    private class Fixture(recordBytes: Int = 1_048_576, remainderLimit: Int = 8, val environment: String = "test", val originBinding: String = ORIGIN) {
        val draftPolicy = PostDraftClientPolicy(64, recordBytes, 262_144, 4096, 4096, 20, 1000, 300_000)
        val publicationPolicy = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 64, 256, remainderLimit, 32, 32, 32, 65_536, 8192, 300_000)
        val snapshots = DraftLocalSnapshotCodecV1(draftPolicy, publicationPolicy)
        val links = PublicationOriginalLinkCodecV1(publicationPolicy, snapshots)
        val codec = PostDraftV2Codec(environment, originBinding, draftPolicy, publicationPolicy, snapshots, links)
        val binding = PublicationJournalBindingData(environment, originBinding.lowercase(), originBinding, ACCOUNT)
        val publications = PostPublicationJournalCodecV1(binding, publicationPolicy, links)
        val cross = PublicationCrossRecordValidator(publications, links, snapshots, publicationPolicy, draftPolicy)
        val disclosure = PublicationDisclosure("test-disclosure-v1", "Actual disclosure displayed during this synthetic review")
        fun snapshot(client: String = CLIENT) = snapshots.create(client, 3, DraftLocalContentV1.TextV1("Caption", null), DraftServerAssociationV1.NotObserved)
        fun record(locals: List<DraftLocalSnapshotV1> = listOf(snapshot()), command: PostDraftCommandV2? = null,
            completion: PostDraftCompletionV2? = null, pending: PostLocalPending? = null, terminals: List<PostDraftTerminalV2> = emptyList()) =
            PostDraftV2Record(10, locals, listOf(CLIENT, COMMAND), command, completion, pending, terminals)
        fun round(record: PostDraftV2Record) = codec.decode(2, codec.encode(record))
        fun write(client: String = CLIENT) = document(mapOf("clientDraftId" to JsonPrimitive(client), "caption" to JsonPrimitive("Caption"),
            "mediaIds" to JsonArray(emptyList()), "audience" to postSelfAudience(), "keepOnPlate" to JsonPrimitive(false),
            "allowRecipeSaves" to JsonPrimitive(false), "saveDisclosureVersion" to JsonPrimitive(disclosure.version)))
        fun baseline(version: String = "1", withDisclosure: Boolean = false): WireDocument = document(draft(CLIENT, "Caption", version).json().jsonObject +
            mapOf("id" to JsonPrimitive(DRAFT)) + if (withDisclosure) mapOf("saveDisclosureVersion" to JsonPrimitive(disclosure.version)) else emptyMap())
        fun legacyOriginal(operation: String): PostOriginal {
            val baseline = if (operation == "createPostDraft") null else rawDoc(" \n${baseline().encodeUtf8().decodeToString()}\n")
            val body = when (operation) {
                "createPostDraft" -> document(write().json().jsonObject - "saveDisclosureVersion")
                "updatePostDraft" -> rawDoc(" { \"caption\" : \"Caption\" }\n")
                else -> null
            }
            return PostOriginal(COMMAND, operation, CLIENT, 3, body, baseline, baseline?.let { "\"1\"" }, 7)
        }
        fun reviewedFields(patch: WireDocument, baseline: WireDocument) = ReviewedDraftOriginalFieldsV2(COMMAND, CLIENT, 3,
            mapOf("draftId" to DRAFT.uppercase()), PrivateBytes(" \n${patch.encodeUtf8().decodeToString()}\n".encodeToByteArray()), baseline, "\"1\"", 7)
        fun reviewed(patch: JsonObject = buildJsonObject { put("caption", "Caption"); put("saveDisclosureVersion", disclosure.version) },
            baseline: WireDocument = baseline(), display: PublicationDisclosure? = disclosure): PostDraftCommandV2.ReviewedPatch {
            val snapshot = snapshots.create(CLIENT, 3, DraftLocalContentV1.TextV1("Caption", null), DraftServerAssociationV1.Observed(baseline, "\"1\""))
            return codec.createReviewedCommand(reviewedFields(document(patch), baseline), snapshot, display)
        }
        fun reviewedResult(c: PostDraftCommandV2.ReviewedPatch) = document(c.expectedFields.json().jsonObject + mapOf(
            "updatedAt" to JsonPrimitive("2026-09-14T12:00:00Z"), "expiresAt" to JsonPrimitive("2026-09-15T12:00:00Z")))
        fun link(client: String = CLIENT, command: String = COMMAND, saved: Boolean = false): PublicationOriginalLinkV1 {
            val baseline = baseline(withDisclosure = true)
            val snapshot = if (saved) snapshots.create(client, 3, DraftLocalContentV1.TextV1("Caption", null), DraftServerAssociationV1.Observed(baseline, "\"1\"")) else snapshot(client)
            val body = if (saved) document(write(client).json().jsonObject + mapOf("draftId" to JsonPrimitive(DRAFT), "draftVersion" to JsonPrimitive(1))) else write(client)
            val target = if (saved) PublicationReviewTargetV1.SavedDraft(DRAFT, ExactPostVersion("1"), "\"1\"", baseline) else PublicationReviewTargetV1.DirectLocal
            return links.create(binding, command, 7, ApiCall("publishPost", body = PrivateBytes(body.encodeUtf8()), idempotencyKey = SecretText(command)), snapshot, target, disclosure)
        }
        fun journal(vararg entries: PublicationHistoryEntryV1) = PublicationJournalV1(binding, 10, entries.map { it.original.commandId }, entries.toList())
        fun held(link: PublicationOriginalLinkV1 = link(), journal: PublicationJournalV1 = journal(PublicationHistoryEntryV1.PendingOriginal(link))): PostDraftV2Record {
            val row = PostDraftV2Record(10, listOf(link.historicalReview.exactReviewedLocalSnapshot), listOf(link.clientDraftId, link.commandId),
                publicationHold = PostDraftPublicationHoldV2(link, PublicationCapacityReservationV1(publicationPolicy.maxResponseBytes, 1, 0, 1)))
            return codec.withPublicationReservation(row, publications.reservedPublishedBytes(journal))
        }
        fun reply(): HistoricalPublicationReplyV1 = HistoricalPublicationReplyV1(document(buildJsonObject {
            put("id", POST); put("version", 1); put("createdAt", "2026-09-14T12:00:00Z"); put("updatedAt", "2026-09-14T12:00:00Z")
            put("author", buildJsonObject { put("userId", ACCOUNT); put("displayName", "Synthetic cook"); put("handle", "synthetic"); put("avatarMediaId", MEDIA) })
            put("caption", "Caption"); put("mediaIds", JsonArray(emptyList())); put("audience", JsonObject(postSelfAudience() + ("bindings" to JsonArray(emptyList()))))
            put("status", "published"); put("publishedAt", "2026-09-14T12:00:00Z"); put("expiresAt", "2026-09-15T12:00:00Z"); put("keepOnPlate", false)
            put("savePolicy", buildJsonObject { put("allowFutureSaves", false); put("policyVersion", 1); put("disclosureVersion", disclosure.version) })
            put("aclVersion", 1); put("capabilities", JsonArray(listOf(JsonPrimitive("view"), JsonPrimitive("delete")))); put("reactionCounts", JsonArray(emptyList()))
        }), "\"1\"", "application/json", null, null)
    }
    private companion object {
        const val ORIGIN = "ABCDEFAB-1111-4111-8111-111111111111"
        const val DRAFT = "abcdefab-2222-4222-8222-222222222222"
        const val COMMAND = "abcdefab-3333-4333-8333-333333333333"
        const val POST = "abcdefab-4444-4444-8444-444444444444"
        const val ACCOUNT = "abcdefab-5555-4555-8555-555555555555"
        const val MEDIA = "abcdefab-6666-4666-8666-666666666666"
        fun rawDoc(value: String) = WireDocument.parse(value)
        fun json(value: PrivateBytes) = Json.parseToJsonElement(value.copyForCodec().decodeToString()).jsonObject
        fun bytes(value: Map<String, JsonElement>) = PrivateBytes(JsonObject(value).toString().encodeToByteArray())
        fun invalid(block: () -> Unit) { assertEquals(FailureReason.INVALID_DATA, assertFailsWith<MealFailure>(block = block).reason) }
        fun unavailable(block: () -> Unit) { assertEquals(FailureReason.UNAVAILABLE, assertFailsWith<MealFailure>(block = block).reason) }
    }
}
