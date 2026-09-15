package com.feedme.development.progress

import com.feedme.contracts.*
import com.feedme.core.ports.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Explicit synthetic CAS fixture. Native encrypted persistence remains a separate host gate. */
class ProgressPostServiceLedgerTest {
    @Test fun freshStartCommitsOneSeparateLedgerAndResumeIsReadOnly() = runTest {
        val f = Fixture(); val cooking = PrivateRecord(7, 1, PrivateBytes("unchanged cooking ledger".encodeToByteArray()))
        f.store.records[ProgressServiceLedger.KEY] = cooking
        f.open(); assertTrue(f.service.available); assertEquals(1, f.store.commits)
        assertSame(cooking, f.store.records[ProgressServiceLedger.KEY])
        f.service.close().value(); f.replace(); f.service.open(false).value()
        assertTrue(f.service.available); assertEquals(1, f.store.commits); assertEquals(0, f.store.erases)
    }

    @Test fun missingResumeNeverInitializesAndCannotLaterOpenAsFresh() = runTest {
        val f = Fixture(); f.service.open(false).value(); assertFalse(f.service.available)
        assertEquals(PortResult.Failure(FailureReason.NOT_CONFIGURED), f.service.execute(read("listPostDrafts")))
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), f.service.open(true)); assertEquals(0, f.store.commits)
    }

    @Test fun unknownFreshInitializationCannotRetryAsNewBeforeOrAfterCommit() = runTest {
        for (after in listOf(false, true)) {
            val f = Fixture(); if (after) f.store.loseAfter = true else f.store.failBefore = true
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.open(true))
            assertEquals(PortResult.Failure(FailureReason.CONFLICT), f.service.open(true))
            assertFalse(f.service.available); assertEquals(1, f.store.commits)
            f.replace(); f.service.open(false).value(); assertEquals(after, f.service.available)
            assertEquals(1, f.store.commits)
        }
    }

    @Test fun actualAllSixOperationsUseCanonicalBodiesAndStrongEtags() = runTest {
        val f = Fixture(); f.open(); val root = uuid()
        val created = f.send(command("createPostDraft", patch(root))); assertEquals(201, created.status); assertEquals("\"1\"", created.etag)
        val id = created.json().postString("id")
        assertEquals(created.json(), f.send(read("getPostDraft", id)).json())
        assertEquals(1, f.send(read("listPostDrafts")).json().getValue("items").jsonArray.size)
        val updated = f.send(command("updatePostDraft", """{"caption":"new","saveDisclosureVersion":"${ProgressPostPreviewContract.disclosureVersion}"}""", id = id, etag = created.etag))
        assertEquals("\"2\"", updated.etag)
        val published = f.send(command("publishPost", write(root, "new", draft = updated.json())))
        assertEquals(201, published.status); assertEquals("\"1\"", published.etag)
        val terminal = f.send(read("getPostDraft", id)); assertEquals("published", terminal.json().postString("status")); assertEquals("\"3\"", terminal.etag)
        assertEquals(0, f.send(read("listPostDrafts")).json().getValue("items").jsonArray.size)
        val second = f.send(command("createPostDraft", patch(uuid()))); val secondId = second.json().postString("id")
        val deleted = f.send(command("deletePostDraft", null, id = secondId, etag = second.etag))
        assertEquals(204, deleted.status); assertNull(deleted.body); assertEquals(404, f.send(read("getPostDraft", secondId)).status)
    }

    @Test fun fullFiveHundredUnicodeScalarsAndAltFitBothReplyAndWholeRecord() = runTest {
        val f = Fixture(); f.open(); val caption = "😀".repeat(500); val alt = "\u0001".repeat(500)
        val root = uuid(); val input = buildJsonObject {
            put("clientDraftId", root); put("caption", caption); put("altText", alt); put("saveDisclosureVersion", ProgressPostPreviewContract.disclosureVersion)
        }.toString()
        val created = f.send(command("createPostDraft", input)); assertEquals(201, created.status)
        assertEquals(caption, created.json().postString("caption")); assertEquals(alt, created.json().postString("altText"))
        val original = command("publishPost", write(root, caption, alt, created.json()))
        val post = f.send(original); assertEquals(201, post.status); assertEquals(alt, post.json().postString("altText"))
        assertTrue(checkNotNull(post.body).copyForCodec().size <= ProgressPostPreviewContract.maxResponseBytes)
        assertTrue(f.record().payload.copyForCodec().size <= ProgressPostPreviewContract.maxRecordBytes)
        assertEquals(post.bodyText(), f.send(original).bodyText())
    }

    @Test fun escapedMaximumInputsAndEightFullDraftsFitPageWithoutTruncation() = runTest {
        val f = Fixture(); f.open(); val caption = "\u0001".repeat(500); val alt = "😀".repeat(500)
        repeat(8) {
            val body = buildJsonObject { put("clientDraftId", uuid()); put("caption", caption); put("altText", alt) }.toString()
            assertEquals(201, f.send(command("createPostDraft", body)).status)
        }
        val page = f.send(read("listPostDrafts")); assertEquals(8, page.json().getValue("items").jsonArray.size)
        assertEquals(JsonNull, page.json()["nextCursor"]); assertTrue(checkNotNull(page.body).copyForCodec().size <= ProgressPostPreviewContract.maxPageBytes)
        assertTrue(checkNotNull(page.body).copyForCodec().size > ProgressPostPreviewContract.maxResponseBytes)
    }

    @Test fun fiveHundredOneScalarsAndOversizedRawOriginalAreRejectedBeforeCommit() = runTest {
        val f = Fixture(); f.open(); val before = f.record(); val count = f.store.commits
        assertEquals(PortResult.Failure(FailureReason.INVALID_DATA), f.service.execute(command("createPostDraft", patch(uuid(), "😀".repeat(501)))))
        val longAlt = buildJsonObject { put("clientDraftId", uuid()); put("altText", "😀".repeat(501)) }.toString()
        assertEquals(PortResult.Failure(FailureReason.INVALID_DATA), f.service.execute(command("createPostDraft", longAlt)))
        val padding = " ".repeat(ProgressPostPreviewContract.maxRequestBytes)
        assertEquals(PortResult.Failure(FailureReason.INVALID_DATA), f.service.execute(command("createPostDraft", padding + patch(uuid()))))
        assertSame(before, f.record()); assertEquals(count, f.store.commits)
    }

    @Test fun absentAltAndExplicitEmptyAreNotConflatedByPatchOrPublication() = runTest {
        val f = Fixture(); f.open(); val root = uuid(); val created = f.send(command("createPostDraft", patch(root)))
        assertFalse("altText" in created.json())
        val updated = f.send(command("updatePostDraft", """{"altText":"","saveDisclosureVersion":"${ProgressPostPreviewContract.disclosureVersion}"}""", id = created.json().postString("id"), etag = created.etag))
        assertEquals("", updated.json().postString("altText"))
        assertEquals(409, f.send(command("publishPost", write(root, draft = updated.json()))).status)
        val published = f.send(command("publishPost", write(root, alt = "", draft = updated.json())))
        assertEquals(201, published.status); assertEquals("", published.json().postString("altText"))
    }

    @Test fun selfOnlyRejectsCirclesMediaAttachmentsSourceAndRecipeSavePermission() = runTest {
        val f = Fixture(); f.open()
        val variants = listOf(
            """"audience":{"kind":"circles","circleIds":["${uuid()}"]}""",
            """"mediaIds":["${uuid()}"]""", """"sourcePostId":"${uuid()}"""", """"allowRecipeSaves":true""",
            """"attachment":{"recipeVersionId":"${uuid()}","confirmedChanges":[],"reviewStatus":"reviewed","rightsBasis":"catalogRedistributable"}""",
            """"saveDisclosureVersion":"foreign""""
        )
        for (extra in variants) {
            val call = command("createPostDraft", """{"clientDraftId":"${uuid()}",$extra}""")
            assertTrue(ProgressPostServiceCodec(ProgressIdentity.scope, f.origin).validateRequest(call), "Variant must reach policy: $extra")
            val result = assertIs<PortResult.Value<ApiReply>>(f.service.execute(call), "Policy result for: $extra").value
            assertEquals(422, result.status, "Policy rejection for: $extra")
        }
        assertEquals(0, f.state().postObject("roots").size)
        val created = f.send(command("createPostDraft", patch(uuid())))
        val removed = f.send(command("updatePostDraft", """{"removeAttachment":true}""", id = created.json().postString("id"), etag = created.etag))
        assertEquals(200, removed.status); assertFalse("attachment" in removed.json())
    }

    @Test fun savedTargetMustBindExactRootVersionAndAllReviewedContent() = runTest {
        val f = Fixture(); f.open(); val root = uuid(); val created = f.send(command("createPostDraft", patch(root, disclosure = true)))
        assertEquals(409, f.send(command("publishPost", write(root, "changed", draft = created.json()))).status)
        assertEquals(409, f.send(command("publishPost", write(uuid(), draft = created.json()))).status)
        val stale = JsonObject(created.json() + ("version" to JsonPrimitive(2)))
        assertEquals(409, f.send(command("publishPost", write(root, draft = stale))).status)
        assertEquals(201, f.send(command("publishPost", write(root, draft = created.json()))).status)
    }

    @Test fun directCannotBypassAnySavedOrDiscardedRootAndPublishedRootsArePermanent() = runTest {
        val f = Fixture(); f.open(); val root = uuid(); val created = f.send(command("createPostDraft", patch(root)))
        assertEquals(409, f.send(command("publishPost", write(root))).status)
        assertEquals(204, f.send(command("deletePostDraft", null, id = created.json().postString("id"), etag = created.etag)).status)
        assertEquals(409, f.send(command("publishPost", write(root))).status)
        assertEquals(409, f.send(command("createPostDraft", patch(root))).status)
        val direct = uuid(); assertEquals(201, f.send(command("publishPost", write(direct))).status)
        assertEquals(409, f.send(command("publishPost", write(direct))).status)
        assertEquals(409, f.send(command("createPostDraft", patch(direct))).status)
    }

    @Test fun expiredDraftReadUpdateAndPublishDenyButExactOwnerDeleteRemainsPossible() = runTest {
        val f = Fixture(); f.open(); val root = uuid(); val created = f.send(command("createPostDraft", patch(root, disclosure = true)))
        f.now += ProgressPostPreviewContract.lifetimeMillis
        val id = created.json().postString("id")
        assertEquals(410, f.send(read("getPostDraft", id)).status)
        assertEquals(410, f.send(command("updatePostDraft", "{}", id = id, etag = created.etag)).status)
        assertEquals(410, f.send(command("publishPost", write(root, draft = created.json()))).status)
        assertEquals(204, f.send(command("deletePostDraft", null, id = id, etag = created.etag)).status)
    }

    @Test fun publishedOriginalReplayAndPublishedDraftReadSurviveLogicalExpiry() = runTest {
        val f = Fixture(); f.open(); val root = uuid(); val created = f.send(command("createPostDraft", patch(root, disclosure = true)))
        val original = command("publishPost", write(root, draft = created.json())); val result = f.send(original)
        f.now += 2 * ProgressPostPreviewContract.lifetimeMillis
        assertEquals(result.bodyText(), f.send(original).bodyText())
        assertEquals("published", f.send(read("getPostDraft", created.json().postString("id"))).json().postString("status"))
        assertEquals(1, f.state().postObject("roots").size)
    }

    @Test fun sameKeyRequiresExactRawBodyNotJustEquivalentParsedJson() = runTest {
        val f = Fixture(); f.open(); val key = "abcdef01-2345-4678-8abc-def012345678"; val body = write(uuid())
        val original = command("publishPost", body, key); val reply = f.send(original); val before = f.record()
        assertEquals(409, f.send(command("publishPost", " $body", key)).status)
        assertSame(before, f.record()); assertEquals(reply.bodyText(), f.send(original).bodyText())
        assertEquals(409, f.send(command("publishPost", body, key.uppercase())).status)
    }

    @Test fun sameKeyChangedOperationPathAndPreconditionNeverSubstituteOriginal() = runTest {
        val f = Fixture(); f.open(); val created = f.send(command("createPostDraft", patch(uuid())))
        val id = created.json().postString("id"); val key = uuid()
        val original = command("updatePostDraft", "{}", key, id, created.etag); assertEquals(200, f.send(original).status)
        for (changed in listOf(command("updatePostDraft", "{}", key, uuid(), created.etag), command("updatePostDraft", "{}", key, id, "\"2\""), command("deletePostDraft", null, key, id, created.etag))) {
            assertEquals(409, f.send(changed).status)
        }
        assertEquals(200, f.send(original).status)
    }

    @Test fun historicalCreateAndPatchCannotReplayOverAChangedCurrentDraft() = runTest {
        val f = Fixture(); f.open(); val original = command("createPostDraft", patch(uuid())); val created = f.send(original)
        val first = command("updatePostDraft", """{"caption":"first"}""", id = created.json().postString("id"), etag = created.etag)
        val updated = f.send(first)
        assertEquals(409, f.send(original).status)
        assertEquals(200, f.send(command("updatePostDraft", """{"caption":"second"}""", id = updated.json().postString("id"), etag = updated.etag)).status)
        assertEquals(409, f.send(first).status)
    }

    @Test fun lostBeforeAndAfterCommitRetryUsesSameOriginalWithoutDuplicatePost() = runTest {
        for (after in listOf(false, true)) {
            val f = Fixture(); f.open(); val original = command("publishPost", write(uuid()))
            if (after) f.store.loseAfter = true else f.store.failBefore = true
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.execute(original))
            val retained = if (after) f.state().postObject("receipts").values.single().jsonObject.postObject("reply") else null
            f.service.close().value(); f.replace(); f.service.open(false).value()
            val result = f.send(original); assertEquals(201, result.status)
            assertEquals(1, f.state().postObject("roots").size); assertEquals(1, f.state().postObject("receipts").size)
            if (retained != null) assertEquals(retained, ProgressPostServiceCodec(ProgressIdentity.scope, f.origin).encodeReply(result))
        }
    }

    @Test fun savedPublicationLostReplyCommitsDraftPostAndReceiptTogether() = runTest {
        val f = Fixture(); f.open(); val root = uuid(); val created = f.send(command("createPostDraft", patch(root, disclosure = true)))
        val original = command("publishPost", write(root, draft = created.json())); f.store.loseAfter = true
        assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.execute(original))
        val entry = f.state().postObject("roots").postObject(root)
        assertEquals("published", entry.postObject("draft").postString("status"))
        assertEquals(entry.postObject("post").postString("id"), entry.postObject("draft").postString("publishedPostId"))
        assertEquals(2, f.state().postObject("receipts").size); assertEquals(201, f.send(original).status)
    }

    @Test fun falseAcknowledgementAndMissingReadbackCannotReportSuccess() = runTest {
        for (badReadback in listOf(false, true)) {
            val f = Fixture(); f.open(); val original = command("publishPost", write(uuid()))
            if (badReadback) f.store.failReadAfterCommit = true else f.store.falseAck = true
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.execute(original))
            assertEquals(1, f.state().postObject("roots").size)
            assertEquals(201, f.send(original).status)
        }
    }

    @Test fun replayPerformsFreshCasAndItsLostAckRemainsUnknown() = runTest {
        val f = Fixture(); f.open(); val original = command("publishPost", write(uuid())); val reply = f.send(original)
        val revision = f.record().revision; f.store.loseAfter = true
        assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.execute(original))
        assertEquals(revision + 1, f.record().revision)
        assertEquals(reply.bodyText(), f.send(original).bodyText())
    }

    @Test fun staleCasPreservesOtherActualWriterAndOriginalRemainsRetryable() = runTest {
        val f = Fixture(); f.open(); val before = f.record(); val original = command("publishPost", write(uuid()))
        f.store.beforeCommit = { f.store.records[ProgressPostServiceLedger.KEY] = PrivateRecord(before.revision + 1, 1, before.payload) }
        assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.execute(original))
        assertEquals(0, f.state().postObject("roots").size); f.store.beforeCommit = null
        assertEquals(201, f.send(original).status)
    }

    @Test fun cancellationAfterActualCommitDoesNotBecomeSuccessOrEraseOriginal() = runTest {
        val f = Fixture(); f.open(); val original = command("publishPost", write(uuid()))
        f.store.afterCommit = { throw CancellationException("synthetic lost caller") }
        assertFailsWith<CancellationException> { f.service.execute(original) }
        assertEquals(1, f.state().postObject("roots").size)
        f.store.afterCommit = null; assertEquals(201, f.send(original).status)
    }

    @Test fun cancellationWhileReadIsSuspendedDoesNoWriteAndReleasesMutex() = runTest {
        val f = Fixture(); f.open(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.store.afterRead = { entered.complete(Unit); release.await() }
        val running = async { f.service.execute(command("publishPost", write(uuid()))) }
        entered.await(); running.cancelAndJoin(); f.store.afterRead = null
        assertEquals(1, f.store.commits); assertEquals(0, f.state().postObject("roots").size)
        assertEquals(201, f.send(command("publishPost", write(uuid()))).status)
    }

    @Test fun exactSessionInvalidationAfterReadAndAfterCommitNeverReturnsAck() = runTest {
        for (after in listOf(false, true)) {
            val f = Fixture(); f.open(); val original = command("publishPost", write(uuid()))
            if (after) f.store.afterCommit = { f.current = false } else f.store.afterRead = { f.current = false }
            assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), f.service.execute(original))
            assertEquals(if (after) 1 else 0, f.state().postObject("roots").size)
        }
    }

    @Test fun disappearanceAndForeignOriginNeverBecomeFreshState() = runTest {
        val f = Fixture(); f.open(); val retained = f.record(); f.store.records.remove(ProgressPostServiceLedger.KEY)
        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), f.service.execute(read("listPostDrafts")))
        assertEquals(1, f.store.commits)
        f.store.records[ProgressPostServiceLedger.KEY] = retained
        val foreign = ProgressPostServiceLedger(f.store, ProgressIdentity.scope, uuid(), EpochClock { f.now }) { }
        assertIs<PortResult.Failure>(foreign.open(false)); assertEquals(1, f.store.commits)
    }

    @Test fun malformedStateAndMissingPublicationReceiptFailClosedWithoutWriting() = runTest {
        val f = Fixture(); f.open(); f.send(command("publishPost", write(uuid())))
        val valid = f.record(); val count = f.store.commits
        val corruptions = listOf(JsonObject(f.state() + ("receipts" to JsonObject(emptyMap()))), JsonObject(f.state() + ("extra" to JsonPrimitive(true))))
        for (state in corruptions) {
            f.store.records[ProgressPostServiceLedger.KEY] = PrivateRecord(valid.revision, 1, PrivateBytes(state.toString().encodeToByteArray()))
            f.replace(); assertIs<PortResult.Failure>(f.service.open(false)); assertEquals(count, f.store.commits)
        }
    }

    @Test fun retainedOriginalContainsExactKeyAndIndexKeyMismatchCannotReopen() = runTest {
        val f = Fixture(); f.open(); val key = "abcdef01-2345-4678-8abc-def012345678"
        val original = command("publishPost", write(uuid()), key); val reply = f.send(original)
        val valid = f.record(); val state = f.state(); val receipts = state.postObject("receipts")
        val entry = receipts.getValue(postHash(postUuid(key))).jsonObject
        assertEquals(key, entry.postObject("original").postString("idempotencyKey"))
        f.replace(); f.service.open(false).value(); assertEquals(reply.bodyText(), f.send(original).bodyText())
        val malformedOriginal = JsonObject(entry.postObject("original") + ("idempotencyKey" to JsonPrimitive(uuid())))
        val malformedEntry = JsonObject(entry + ("original" to malformedOriginal))
        val bad = JsonObject(state + ("receipts" to JsonObject(receipts + (postHash(postUuid(key)) to malformedEntry))))
        f.store.records[ProgressPostServiceLedger.KEY] = PrivateRecord(valid.revision, 1, PrivateBytes(bad.toString().encodeToByteArray()))
        val count = f.store.commits; f.replace(); assertIs<PortResult.Failure>(f.service.open(false)); assertEquals(count, f.store.commits)
    }

    @Test fun wrongStorageScopeAndClosedBorrowerDenyWithoutIo() = runTest {
        val f = Fixture(); val foreign = ProgressPostServiceLedger(f.store, StorageScope("foreign", ActorKind.ACCOUNT, "opaque"), f.origin, EpochClock { f.now }) { }
        assertEquals(PortResult.Failure(FailureReason.UNAUTHENTICATED), foreign.open(true)); assertEquals(0, f.store.reads)
        f.open(); f.service.close().value(); val count = f.store.reads
        assertIs<PortResult.Failure>(f.service.execute(read("listPostDrafts"))); assertEquals(count, f.store.reads); assertEquals(0, f.store.erases)
    }

    @Test fun backwardsClockDeniesAndDoesNotRewriteHistory() = runTest {
        val f = Fixture(); f.open(); val before = f.record(); f.now--
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), f.service.execute(read("listPostDrafts"))); assertSame(before, f.record())
    }

    @Test fun expiryDuringTheFinalAwaitedCredentialFenceCannotCommitAnExpiredSavedTarget() = runTest {
        val f = Fixture(); f.open(); val root = uuid(); val created = f.send(command("createPostDraft", patch(root, disclosure = true)))
        val before = f.record(); val count = f.store.commits
        // Initial admission, actual read fence, then the immediately-precommit fence.
        var checks = 0
        f.onCurrent = { if (++checks == 3) f.now += ProgressPostPreviewContract.lifetimeMillis }
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), f.service.execute(command("publishPost", write(root, draft = created.json()))))
        assertEquals(3, checks); assertEquals(count, f.store.commits); assertSame(before, f.record())
    }

    @Test fun staleStrongVersionAndWeakEtagsDoNotChangeContent() = runTest {
        val f = Fixture(); f.open(); val created = f.send(command("createPostDraft", patch(uuid())))
        val id = created.json().postString("id")
        assertEquals(412, f.send(command("updatePostDraft", "{}", id = id, etag = "\"999999999999999999999999999999\"")).status)
        val weak = f.service.execute(command("updatePostDraft", "{}", id = id, etag = "W/\"1\""))
        assertTrue(weak is PortResult.Failure || (weak as PortResult.Value).value.status == 412)
        assertEquals(created.json(), f.send(read("getPostDraft", id)).json())
    }

    @Test fun cursorIsBoundToOriginHeadLimitAndExpiryWithCompletePages() = runTest {
        val f = Fixture(); f.open(); repeat(3) { f.send(command("createPostDraft", patch(uuid()))) }
        val first = f.send(read("listPostDrafts", query = mapOf("limit" to listOf("2"))))
        assertEquals("\"3\"", first.etag)
        val cursor = first.json().postString("nextCursor")
        val second = f.send(read("listPostDrafts", query = mapOf("limit" to listOf("2"), "cursor" to listOf(cursor))))
        assertEquals(1, second.json().getValue("items").jsonArray.size); assertEquals(JsonNull, second.json()["nextCursor"])
        assertEquals(first.etag, second.etag)
        assertEquals(409, f.send(read("listPostDrafts", query = mapOf("limit" to listOf("1"), "cursor" to listOf(cursor)))).status)
        assertEquals(409, f.send(read("listPostDrafts", query = mapOf("limit" to listOf("2"), "cursor" to listOf(cursor.dropLast(2) + "aa")))).status)
        f.now += ProgressPostPreviewContract.cursorLifetimeMillis
        assertEquals(410, f.send(read("listPostDrafts", query = mapOf("limit" to listOf("2"), "cursor" to listOf(cursor)))).status)
        f.send(command("createPostDraft", patch(uuid())))
        assertEquals(409, f.send(read("listPostDrafts", query = mapOf("limit" to listOf("2"), "cursor" to listOf(cursor)))).status)
    }

    @Test fun permanentRootCapacityRejectsNinthWithoutEviction() = runTest {
        val f = Fixture(); f.open(); val originals = (1..8).map { command("publishPost", write(uuid())) }
        val replies = originals.map { f.send(it).bodyText() }
        assertEquals(422, f.send(command("publishPost", write(uuid()))).status)
        assertEquals(8, f.state().postObject("roots").size)
        assertEquals(replies, originals.map { f.send(it).bodyText() })
    }

    @Test fun commandCapacityRetainsAllOriginalsAndAllowsExactReplayAtLimit() = runTest {
        val f = Fixture(); f.open(); val original = command("createPostDraft", patch(uuid())); val created = f.send(original)
        val id = created.json().postString("id")
        repeat(15) { assertEquals(412, f.send(command("updatePostDraft", "{}", id = id, etag = "\"99\"")).status) }
        val before = f.record()
        assertEquals(429, f.send(command("updatePostDraft", "{}", id = id, etag = created.etag)).status); assertSame(before, f.record())
        assertEquals(16, f.state().postObject("receipts").size); assertEquals(created.bodyText(), f.send(original).bodyText())
    }

    @Test fun allSixteenLargeOriginalsFitDeclaredBudgetWithoutDiscardingBytes() = runTest {
        val f = Fixture(); f.open(); val roots = (1..8).map { uuid() }
        val caption = "\u0001".repeat(500); val alt = "😀".repeat(500)
        val created = roots.map { root ->
            val body = buildJsonObject { put("clientDraftId", root); put("caption", caption); put("altText", alt); put("saveDisclosureVersion", ProgressPostPreviewContract.disclosureVersion) }.toString()
            f.send(command("createPostDraft", " ".repeat(ProgressPostPreviewContract.maxRequestBytes - body.encodeToByteArray().size) + body))
        }
        for ((index, draft) in created.withIndex()) {
            val body = write(roots[index], caption, alt, draft.json())
            assertEquals(201, f.send(command("publishPost", " ".repeat(ProgressPostPreviewContract.maxRequestBytes - body.encodeToByteArray().size) + body)).status)
        }
        assertEquals(16, f.state().postObject("receipts").size); assertEquals(8, f.state().postObject("roots").size)
        assertTrue(f.record().payload.copyForCodec().size < ProgressPostPreviewContract.maxRecordBytes)
        f.replace(); f.service.open(false).value(); assertTrue(f.service.available)
    }

    @Test fun corruptLongStatusesCannotWrapIntoValidCanonicalErrorReceipts() = runTest {
        val f = Fixture(); f.open()
        val original = command("createPostDraft", """{"clientDraftId":"${uuid()}","allowRecipeSaves":true}""")
        assertEquals(422, f.send(original).status)
        val valid = f.record(); val state = f.state(); val receipts = state.postObject("receipts")
        val indexed = receipts.entries.single(); val entry = indexed.value.jsonObject
        val count = f.store.commits
        for (raw in listOf(4_294_967_718L, -4_294_966_874L, 99L, 600L)) {
            val badReply = JsonObject(entry.postObject("reply") + ("status" to JsonPrimitive(raw)))
            val badEntry = JsonObject(entry + ("reply" to badReply))
            val badState = JsonObject(state + ("receipts" to JsonObject(receipts + (indexed.key to badEntry))))
            val corrupt = PrivateRecord(valid.revision, 1, PrivateBytes(badState.toString().encodeToByteArray()))
            f.store.records[ProgressPostServiceLedger.KEY] = corrupt
            f.replace(); assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), f.service.open(false))
            assertSame(corrupt, f.record()); assertEquals(count, f.store.commits)
        }
        f.store.records[ProgressPostServiceLedger.KEY] = valid
        f.replace(); f.service.open(false).value()
        assertEquals(422, f.send(original).status); assertEquals(1, f.state().postObject("receipts").size)
    }

    @Test fun schemaValidCurrentDraftTamperCannotReplaceTheLatestActualReceipt() = runTest {
        val f = Fixture(); f.open(); val root = uuid()
        f.send(command("createPostDraft", patch(root, disclosure = true)))
        val valid = f.record(); val state = f.state(); val roots = state.postObject("roots"); val entry = roots.postObject(root)
        val changed = JsonObject(entry.postObject("draft") + ("caption" to JsonPrimitive("Changed outside actual original")))
        val corruptState = JsonObject(state + ("roots" to JsonObject(roots + (root to JsonObject(entry + ("draft" to changed))))))
        val corrupt = PrivateRecord(valid.revision, 1, PrivateBytes(corruptState.toString().encodeToByteArray()))
        f.store.records[ProgressPostServiceLedger.KEY] = corrupt
        val count = f.store.commits; f.replace()
        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), f.service.open(false))
        assertSame(corrupt, f.record()); assertEquals(count, f.store.commits)
        f.store.records[ProgressPostServiceLedger.KEY] = valid; f.replace(); f.service.open(false).value()
    }

    @Test fun publishedDraftCannotDivergeFromTheSavedOriginalAndActualPost() = runTest {
        val f = Fixture(); f.open(); val root = uuid()
        val created = f.send(command("createPostDraft", patch(root, disclosure = true)))
        val original = command("publishPost", write(root, draft = created.json())); val post = f.send(original)
        val valid = f.record(); val state = f.state(); val roots = state.postObject("roots"); val entry = roots.postObject(root)
        val changed = JsonObject(entry.postObject("draft") + ("caption" to JsonPrimitive("Unsubmitted replacement")))
        val corruptState = JsonObject(state + ("roots" to JsonObject(roots + (root to JsonObject(entry + ("draft" to changed))))))
        val corrupt = PrivateRecord(valid.revision, 1, PrivateBytes(corruptState.toString().encodeToByteArray()))
        f.store.records[ProgressPostServiceLedger.KEY] = corrupt
        val count = f.store.commits; f.replace()
        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), f.service.open(false))
        assertSame(corrupt, f.record()); assertEquals(count, f.store.commits)
        f.store.records[ProgressPostServiceLedger.KEY] = valid; f.replace(); f.service.open(false).value()
        assertEquals(post.bodyText(), f.send(original).bodyText())
    }

    @Test fun getAndPageCannotDeliverExpiredRowsAfterTheFinalAwaitedCredentialFence() = runTest {
        for (operation in listOf("getPostDraft", "listPostDrafts")) {
            val f = Fixture(); f.open(); val created = f.send(command("createPostDraft", patch(uuid())))
            val count = f.store.commits; val before = f.record(); var checks = 0
            f.onCurrent = { if (++checks == 3) f.now += ProgressPostPreviewContract.lifetimeMillis }
            val result = f.send(read(operation, if (operation == "getPostDraft") created.json().postString("id") else null))
            assertEquals(3, checks)
            if (operation == "getPostDraft") assertEquals(410, result.status)
            else {
                assertEquals(200, result.status); assertEquals(0, result.json().getValue("items").jsonArray.size)
                assertEquals(JsonNull, result.json()["nextCursor"]); assertEquals("\"1\"", result.etag)
            }
            assertEquals(count, f.store.commits); assertSame(before, f.record())
        }
    }

    @Test fun newPublicationPrerequisiteIsReadOnlyAndCannotInitializeMissingState() = runTest {
        val f = Fixture(); val input = PrivateBytes(write(uuid()).encodeToByteArray())
        f.service.open(false).value()
        assertEquals(PortResult.Failure(FailureReason.NOT_CONFIGURED), f.service.requireNewPublication(input))
        assertEquals(0, f.store.commits); assertTrue(f.store.records.isEmpty())
        f.replace(); f.open(); val before = f.record(); val count = f.store.commits
        f.service.requireNewPublication(input).value()
        assertSame(before, f.record()); assertEquals(count, f.store.commits)
        assertEquals(0, f.state().postObject("roots").size); assertEquals(0, f.state().postObject("receipts").size)
    }

    @Test fun newDirectPrerequisiteReadsAllPermanentRootsNotOnlyTheVisibleDraftList() = runTest {
        val f = Fixture(); f.open(); val saved = uuid()
        val created = f.send(command("createPostDraft", patch(saved, disclosure = true)))
        val before = f.record(); val count = f.store.commits
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), f.service.requireNewPublication(PrivateBytes(write(saved).encodeToByteArray())))
        f.service.requireNewPublication(PrivateBytes(write(saved, draft = created.json()).encodeToByteArray())).value()
        assertSame(before, f.record()); assertEquals(count, f.store.commits)
        f.send(command("deletePostDraft", null, id = created.json().postString("id"), etag = created.etag))
        val published = uuid(); f.send(command("publishPost", write(published)))
        val expired = uuid(); val oldDraft = f.send(command("createPostDraft", patch(expired, disclosure = true)))
        f.now += ProgressPostPreviewContract.lifetimeMillis
        assertEquals(0, f.send(read("listPostDrafts")).json().getValue("items").jsonArray.size)
        val retained = f.record(); val writes = f.store.commits
        for (root in listOf(saved, published, expired))
            assertEquals(PortResult.Failure(FailureReason.CONFLICT), f.service.requireNewPublication(PrivateBytes(write(root).encodeToByteArray())))
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), f.service.requireNewPublication(PrivateBytes(write(expired, draft = oldDraft.json()).encodeToByteArray())))
        assertSame(retained, f.record()); assertEquals(writes, f.store.commits)
        assertEquals(3, f.state().postObject("roots").size)
    }

    @Test fun newSavedPrerequisiteRequiresExactCurrentVersionAndCompleteSelectedContent() = runTest {
        val f = Fixture(); f.open(); val root = uuid()
        val created = f.send(command("createPostDraft", patch(root, disclosure = true)))
        val updated = f.send(command("updatePostDraft", """{"caption":"new","altText":""}""", id = created.json().postString("id"), etag = created.etag))
        val before = f.record(); val count = f.store.commits
        for (body in listOf(write(root, draft = created.json()), write(root, "new", draft = updated.json()), write(uuid(), "new", "", updated.json())))
            assertEquals(PortResult.Failure(FailureReason.CONFLICT), f.service.requireNewPublication(PrivateBytes(body.encodeToByteArray())))
        f.service.requireNewPublication(PrivateBytes(write(root, "new", "", updated.json()).encodeToByteArray())).value()
        assertSame(before, f.record()); assertEquals(count, f.store.commits)
    }

    @Test fun newPrerequisiteRechecksSessionAndSavedExpiryAfterTheFinalAwaitedFence() = runTest {
        for (invalidate in listOf(false, true)) {
            val f = Fixture(); f.open(); val root = uuid()
            val created = f.send(command("createPostDraft", patch(root, disclosure = true)))
            val before = f.record(); val count = f.store.commits; var checks = 0
            f.onCurrent = { if (++checks == 3) { if (invalidate) f.current = false else f.now += ProgressPostPreviewContract.lifetimeMillis } }
            assertEquals(PortResult.Failure(if (invalidate) FailureReason.STALE_SESSION else FailureReason.CONFLICT),
                f.service.requireNewPublication(PrivateBytes(write(root, draft = created.json()).encodeToByteArray())))
            assertEquals(3, checks); assertSame(before, f.record()); assertEquals(count, f.store.commits)
        }
    }

    @Test fun retainedCreateAndPatchOriginalsMustReconstructTheirExactResponseHistory() = runTest {
        for (operation in listOf("createPostDraft", "updatePostDraft")) {
            val f = Fixture(); f.open(); val root = uuid()
            val create = command("createPostDraft", patch(root, "Original caption", disclosure = true))
            val created = f.send(create)
            // PATCH omits caption: the exact prior caption and explicit empty alt must survive.
            val original = if (operation == "createPostDraft") create else command("updatePostDraft", """{"altText":""}""",
                id = created.json().postString("id"), etag = created.etag)
            val reply = if (operation == "createPostDraft") created else f.send(original)
            f.replace(); f.service.open(false).value()
            assertEquals(reply.bodyText(), f.send(original).bodyText())
            val valid = f.record(); val state = f.state(); val receipts = state.postObject("receipts")
            val key = original.idempotencyKey!!.use { postHash(postUuid(it)) }
            val receipt = receipts.postObject(key); val retained = receipt.postObject("original")
            val changedInput = JsonObject(WireDocument.decode(original.body!!.copyForCodec()).postJson() +
                ("caption" to JsonPrimitive("A different retained request")))
            val changedOriginal = JsonObject(retained + ("body" to JsonPrimitive(postBase64(changedInput.toString().encodeToByteArray()))))
            val changedReceipt = JsonObject(receipt + ("original" to changedOriginal))
            val changedState = JsonObject(state + ("receipts" to JsonObject(receipts + (key to changedReceipt))))
            val corrupt = PrivateRecord(valid.revision, 1, PrivateBytes(changedState.toString().encodeToByteArray()))
            val count = f.store.commits
            f.store.records[ProgressPostServiceLedger.KEY] = corrupt; f.replace()
            assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), f.service.open(false), operation)
            assertSame(corrupt, f.record()); assertEquals(count, f.store.commits)
            f.store.records[ProgressPostServiceLedger.KEY] = valid; f.replace(); f.service.open(false).value()
            assertEquals(reply.bodyText(), f.send(original).bodyText())
            assertEquals("Original caption", reply.json().postString("caption"))
            if (operation == "updatePostDraft") assertEquals("", reply.json().postString("altText"))
        }
    }

    private class Fixture {
        val store = Store(); val origin = uuid(); var current = true; var now = 1_789_344_000_000L
        lateinit var service: ProgressPostServiceLedger; var onCurrent: (() -> Unit)? = null
        init { replace() }
        fun replace() { service = ProgressPostServiceLedger(store, ProgressIdentity.scope, origin, EpochClock { now }) { onCurrent?.invoke(); if (!current) previewFail(FailureReason.STALE_SESSION) } }
        suspend fun open() { service.open(true).value() }
        suspend fun send(call: ApiCall): ApiReply = service.execute(call).value()
        fun record() = store.records.getValue(ProgressPostServiceLedger.KEY)
        fun state() = WireDocument.decode(record().payload.copyForCodec(), WireLimits(maxBytes = ProgressPostPreviewContract.maxRecordBytes)).postJson()
    }
    private class Store : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>(); var reads = 0; var commits = 0; var erases = 0
        var failBefore = false; var loseAfter = false; var falseAck = false; var failReadAfterCommit = false; var readFailure = false
        var beforeCommit: (() -> Unit)? = null; var afterCommit: (() -> Unit)? = null; var afterRead: (suspend () -> Unit)? = null
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            reads++; if (readFailure) { readFailure = false; return PortResult.Failure(FailureReason.STORAGE_FAILURE) }
            val value = records[key]; afterRead?.invoke(); return PortResult.Value(value)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            commits++; assertEquals(ProgressIdentity.scope, scope)
            val put = assertIs<StoreMutation.Put>(mutations.single()); assertEquals(ProgressPostServiceLedger.KEY, put.key)
            beforeCommit?.invoke()
            if (failBefore) { failBefore = false; return PortResult.Failure(FailureReason.STORAGE_FAILURE) }
            if (records[put.key]?.revision != put.expectedRevision) return PortResult.Failure(FailureReason.CONFLICT)
            val revision = (put.expectedRevision ?: 0) + 1
            records[put.key] = PrivateRecord(revision, put.schemaVersion, put.payload); afterCommit?.invoke()
            if (loseAfter) { loseAfter = false; return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
            if (failReadAfterCommit) { failReadAfterCommit = false; readFailure = true }
            if (falseAck) { falseAck = false; return PortResult.Value(mapOf(put.key to revision + 1)) }
            return PortResult.Value(mapOf(put.key to revision))
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { erases++; error("No erase authority") }
    }
    companion object {
        private fun uuid() = UUID.randomUUID().toString()
        private fun command(operation: String, body: String?, key: String = uuid(), id: String? = null, etag: String? = null) =
            ApiCall(operation, pathParameters = id?.let { mapOf("draftId" to it) } ?: emptyMap(), body = body?.let { PrivateBytes(it.encodeToByteArray()) }, idempotencyKey = SecretText(key), ifMatch = etag)
        private fun read(operation: String, id: String? = null, query: Map<String, List<String>> = emptyMap()) =
            ApiCall(operation, pathParameters = id?.let { mapOf("draftId" to it) } ?: emptyMap(), queryParameters = query)
        private fun patch(root: String, caption: String = "preview", disclosure: Boolean = false) = buildJsonObject {
            put("clientDraftId", root); put("caption", caption); if (disclosure) put("saveDisclosureVersion", ProgressPostPreviewContract.disclosureVersion)
        }.toString()
        private fun write(root: String, caption: String = "preview", alt: String? = null, draft: JsonObject? = null) = buildJsonObject {
            put("clientDraftId", root); put("caption", caption); if (alt != null) put("altText", alt)
            put("mediaIds", JsonArray(emptyList())); put("audience", ProgressPostServiceCodec.audience()); put("keepOnPlate", false)
            put("allowRecipeSaves", false); put("saveDisclosureVersion", ProgressPostPreviewContract.disclosureVersion)
            if (draft != null) { put("draftId", draft.getValue("id")); put("draftVersion", draft.getValue("version")) }
        }.toString()
        private fun <T> PortResult<T>.value(): T = assertIs<PortResult.Value<T>>(this).value
        private fun ApiReply.bodyText() = checkNotNull(body).copyForCodec().decodeToString()
        private fun ApiReply.json() = WireDocument.decode(checkNotNull(body).copyForCodec()).postJson()
    }
}
