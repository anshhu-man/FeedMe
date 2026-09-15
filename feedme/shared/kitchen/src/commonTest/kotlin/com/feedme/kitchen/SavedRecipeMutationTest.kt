package com.feedme.kitchen

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Exact CAS fixtures prove repository preparation, not server rights or native durability. */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedRecipeMutationTest {
    @Test fun reservationAndExact201PrepareDetachedAtomicChangesWithoutTransportOrCommit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val admission = value(f.repo.prepareSave(f.lease, COMMAND, plan(), request()))
        assertEquals(2, admission.mutations.size)
        assertNull(admission.savedRecipeId); assertNull(admission.snapshot)
        assertTrue(f.store.records.isEmpty()); assertTrue(f.calls.isEmpty())
        value(f.store.commit(f.scope, admission.mutations))
        val prepared = value(f.repo.prepareSaveReceipt(f.lease, COMMAND, plan(), request(), savedReply()))
        assertEquals(4, prepared.mutations.size)
        assertEquals(SAVED, prepared.savedRecipeId)
        assertNull(prepared.snapshot!!.localRevision)
        assertNull(value(f.repo.read(f.lease, SAVED)))
        val detached = prepared.mutations.toMutableList(); detached.clear()
        assertEquals(4, prepared.mutations.size)
        assertFalse(prepared.toString().contains(SAVED))
        value(f.store.commit(f.scope, prepared.mutations))
        val downloaded = assertNotNull(value(f.repo.read(f.lease, SAVED)))
        assertEquals("2.7500", downloaded.savedRecipe!!.snapshot.ingredients.single().quantity.jsonToken)
        assertNotNull(downloaded.localRevision)
        assertEquals(0, reservationCount(f.store))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun originalPlanRequestAndCommandMustMatchTheDurableReservation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.reserve()
        for ((command, pinned, body) in listOf(
            Triple(COMMAND2, plan(), request()), Triple(COMMAND, plan(version = 2), request()),
            Triple(COMMAND, plan(), request(title = "Changed title")),
        )) failure(FailureReason.CONFLICT, f.repo.prepareSaveReceipt(f.lease, command, pinned, body, savedReply()))
        assertEquals(1, reservationCount(f.store)); assertNull(value(f.repo.read(f.lease, SAVED)))
    }

    @Test fun missingReservationAndReplayAfterConsumptionCannotApplyAnotherSave() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        failure(FailureReason.CONFLICT, f.repo.prepareSaveReceipt(f.lease, COMMAND, plan(), request(), savedReply()))
        f.save()
        failure(FailureReason.CONFLICT, f.repo.prepareSaveReceipt(f.lease, COMMAND, plan(), request(), savedReply()))
        assertEquals(2, f.store.commits.size)
    }

    @Test fun exactEtagStatusContentTypeAndBodyAreRequiredBeforeSavePreparation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.reserve()
        for (reply in listOf(savedReply().copy(status = 200), savedReply().copy(etag = null),
            savedReply().copy(etag = "\"2\""), savedReply().copy(contentType = "text/plain"), savedReply().copy(body = null)))
            failure(FailureReason.INVALID_DATA, f.repo.prepareSaveReceipt(f.lease, COMMAND, plan(), request(), reply))
        assertEquals(1, f.store.commits.size)
    }

    @Test fun changedMaterializedQuantityVersionAndLineageCannotReplaceSelectedPlan() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.reserve()
        for (body in listOf(saved(quantity = "1"), saved(recipeVersion = 2), saved(recipeId = OTHER_RECIPE)))
            failure(FailureReason.CONFLICT, f.repo.prepareSaveReceipt(f.lease, COMMAND, plan(), request(), savedReply(body)))
        assertEquals(1, reservationCount(f.store)); assertNull(value(f.repo.read(f.lease, SAVED)))
    }

    @Test fun explicitTitleConflictAndSocialProvenanceAreNotNewCopyAuthority() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.reserve(request(title = "Exact title"))
        failure(FailureReason.CONFLICT, f.repo.prepareSaveReceipt(f.lease, COMMAND, plan(), request(title = "Exact title"), savedReply()))
        failure(FailureReason.CONFLICT, f.repo.prepareSaveReceipt(f.lease, COMMAND, plan(), request(title = "Exact title"),
            savedReply(saved(title = "Exact title", source = "postGrant"))))
    }

    @Test fun sameSnapshotCatalogDedupUsesReturnedIdentityAndFirstTitleWithoutInventedRename() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.reserve()
        val prepared = value(f.repo.prepareSaveReceipt(f.lease, COMMAND, plan(), request(),
            savedReply(saved(id = SAVED2, title = "First accepted title", source = "catalog"))))
        assertEquals(SAVED2, prepared.savedRecipeId)
        assertEquals("First accepted title", prepared.snapshot!!.savedRecipe!!.title)
    }

    @Test fun wrongSelectorsCollectionsMakeAgainAndNonreadyPlansFailBeforeWrites() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for (body in listOf(SaveRecipeRequest(planId = PlanId(OTHER_PLAN)), SaveRecipeRequest(recipeVersionId = RecipeVersionId(VERSION)),
            SaveRecipeRequest(planId = PlanId(PLAN), collectionId = CollectionId(SAVED)),
            SaveRecipeRequest(planId = PlanId(PLAN), markMakeAgain = true),
            SaveRecipeRequest(planId = PlanId(PLAN), recipeVersionId = RecipeVersionId(OTHER_RECIPE))))
            failure(FailureReason.INVALID_DATA, f.repo.prepareSave(f.lease, COMMAND, plan(), body))
        failure(FailureReason.FORBIDDEN, f.repo.prepareSave(f.lease, COMMAND, plan(status = "recalled"), request()))
        assertTrue(f.store.commits.isEmpty()); assertTrue(f.calls.isEmpty())
    }

    @Test fun cacheCapacityIsReservedBeforeExternalEffectAndCannotBeStolenByDownload() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seedIndex(511)
        f.reserve()
        failure(FailureReason.UNAVAILABLE, f.repo.prepareSave(f.lease, COMMAND2, plan(), request()))
        f.reply = savedReply(saved(id = SAVED2)).copy(status = 200)
        failure(FailureReason.UNAVAILABLE, f.repo.download(f.lease, SAVED2))
        assertNull(f.store.records[bodyKey(SAVED2)])
        val prepared = value(f.repo.prepareSaveReceipt(f.lease, COMMAND, plan(), request(), savedReply()))
        value(f.store.commit(f.scope, prepared.mutations))
        assertEquals(512, indexIds(f.store).size); assertEquals(0, reservationCount(f.store))
    }

    @Test fun reservationAdmissionRacesThroughChangedIndexAndNeverOverbooks() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seedIndex(511)
        val first = value(f.repo.prepareSave(f.lease, COMMAND, plan(), request()))
        val second = value(f.repo.prepareSave(f.lease, COMMAND2, plan(), request()))
        value(f.store.commit(f.scope, first.mutations))
        failure(FailureReason.CONFLICT, f.store.commit(f.scope, second.mutations))
        assertEquals(1, reservationCount(f.store))
    }

    @Test fun unsentDiscardOnlyPreparesExactReservationReleaseAndDoesNotEraseCopies() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.reserve()
        failure(FailureReason.CONFLICT, f.repo.prepareDiscardSave(f.lease, COMMAND2, plan(), request()))
        val discard = value(f.repo.prepareDiscardSave(f.lease, COMMAND, plan(), request()))
        assertEquals(1, reservationCount(f.store))
        value(f.store.commit(f.scope, discard.mutations))
        assertEquals(0, reservationCount(f.store)); assertTrue(indexIds(f.store).isEmpty())
    }

    @Test fun oversizedPlanReplyAndPageFailBoundedlyWithoutPositiveCacheChanges() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        failure(FailureReason.UNAVAILABLE, f.repo.prepareSave(f.lease, COMMAND, plan(instruction = "x".repeat(262_144)), request()))
        f.reserve()
        failure(FailureReason.UNAVAILABLE, f.repo.prepareSaveReceipt(f.lease, COMMAND, plan(), request(),
            savedReply(saved(instruction = "x".repeat(262_144)))))
        f.reply = ApiReply(200, bytes(" ".repeat(262_145)), contentType = "application/json")
        failure(FailureReason.UNAVAILABLE, f.repo.listRemote(f.lease))
        assertEquals(1, f.store.commits.size)
    }

    @Test fun exactDelete204PreparesOnlyOwnedBundleIndexAndIdTombstone() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.save()
        val expected = assertNotNull(value(f.repo.read(f.lease, SAVED))).savedRecipe!!
        val prepared = value(f.repo.prepareDeleteReceipt(f.lease, COMMAND2, expected, "\"1\"", deletedReply()))
        assertEquals(setOf(bodyKey(SAVED), metaKey(SAVED), INDEX, tombstoneKey(SAVED)), prepared.mutations.map { it.key }.toSet())
        assertNotNull(value(f.repo.read(f.lease, SAVED)))
        value(f.store.commit(f.scope, prepared.mutations))
        assertNull(value(f.repo.read(f.lease, SAVED))); assertTrue(value(f.repo.search(f.lease)).isEmpty())
        assertNotNull(f.store.records[tombstoneKey(SAVED)])
        assertEquals(0, f.calls.size)
    }

    @Test fun uncachedRemoteDeletionDoesNotRequireCapacityOrInventBodyDeletes() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seedIndex(512)
        val prepared = value(f.repo.prepareDeleteReceipt(f.lease, COMMAND, wireSaved(), "\"1\"", deletedReply()))
        assertEquals(setOf(INDEX, tombstoneKey(SAVED)), prepared.mutations.map { it.key }.toSet())
        value(f.store.commit(f.scope, prepared.mutations))
        assertEquals(512, indexIds(f.store).size)
    }

    @Test fun deletionRequiresExact204EmptyBodyAndOriginalVersionNotReadAbsence() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.save()
        for (reply in listOf(deletedReply().copy(status = 200), deletedReply().copy(body = bytes("")), deletedReply().copy(etag = "\"2\""),
            problem(status = 404, code = "SAVED_RECIPE_UNAVAILABLE")))
            failure(FailureReason.INVALID_DATA, f.repo.prepareDeleteReceipt(f.lease, COMMAND2, wireSaved(), "\"1\"", reply))
        failure(FailureReason.INVALID_DATA, f.repo.prepareDeleteReceipt(f.lease, COMMAND2, wireSaved(), "\"2\"", deletedReply()))
        assertNotNull(value(f.repo.read(f.lease, SAVED)))
    }

    @Test fun newerDownloadedVersionDoesNotRebaseOriginalDeletionConfirmation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.save()
        f.reply = savedReply(saved(version = 2)).copy(status = 200, etag = "\"2\"")
        value(f.repo.download(f.lease, SAVED))
        failure(FailureReason.CONFLICT, f.repo.prepareDeleteReceipt(f.lease, COMMAND2, wireSaved(), "\"1\"", deletedReply()))
        assertEquals("2", value(f.repo.read(f.lease, SAVED))!!.savedRecipe!!.version.jsonToken)
    }

    @Test fun downloadAlreadyInFlightCannotResurrectAcknowledgedDeletion() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.save()
        val release = CompletableDeferred<Unit>()
        f.exchange = { release.await(); savedReply().copy(status = 200) }
        val download = async { f.repo.download(f.lease, SAVED) }; runCurrent()
        f.delete(); release.complete(Unit)
        failure(FailureReason.CONFLICT, download.await())
        assertNull(value(f.repo.read(f.lease, SAVED)))
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, SAVED))
        assertEquals(1, f.calls.size)
    }

    @Test fun getAndPageAlreadyInFlightCannotExposeDeletedBody() = runTest {
        for (page in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.save()
            val release = CompletableDeferred<Unit>()
            f.exchange = { release.await(); if (page) pageReply() else savedReply().copy(status = 200) }
            val delayed = async { if (page) f.repo.listRemote(f.lease) else f.repo.getRemote(f.lease, SAVED) }
            runCurrent(); f.delete(); release.complete(Unit)
            failure(FailureReason.CONFLICT, delayed.await())
        }
    }

    @Test fun oldSaveReceiptCannotResurrectDeletedIdButNewResaveIdentityCanBeStored() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.save(); f.delete()
        f.reserve(command = COMMAND3)
        failure(FailureReason.CONFLICT, f.repo.prepareSaveReceipt(f.lease, COMMAND3, plan(), request(), savedReply()))
        val fresh = value(f.repo.prepareSaveReceipt(f.lease, COMMAND3, plan(), request(), savedReply(saved(id = SAVED2))))
        value(f.store.commit(f.scope, fresh.mutations))
        assertNull(value(f.repo.read(f.lease, SAVED))); assertNotNull(value(f.repo.read(f.lease, SAVED2)))
    }

    @Test fun deletePreservesOtherCopyAndExactRecipeRecallMarkers() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.save()
        f.reply = savedReply(saved(id = SAVED2)).copy(status = 200); value(f.repo.download(f.lease, SAVED2))
        value(f.repo.observeSaveReply(f.lease, plan(), problem()))
        f.delete()
        assertEquals(SavedRecipeAvailability.RECALLED, value(f.repo.read(f.lease, SAVED2))!!.availability)
        assertTrue(f.store.records.keys.any { it.collection == "feedme.kitchen.recall" })
    }

    @Test fun unknownApplyIsNotReconstructedAsReceiptSuccessFromVisibleSavedBytes() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.reserve()
        val prepared = value(f.repo.prepareSaveReceipt(f.lease, COMMAND, plan(), request(), savedReply()))
        f.store.unknownNext = true
        failure(FailureReason.OUTCOME_UNKNOWN, f.store.commit(f.scope, prepared.mutations))
        assertNotNull(value(f.repo.read(f.lease, SAVED)))
        failure(FailureReason.CONFLICT, f.repo.prepareSaveReceipt(f.lease, COMMAND, plan(), request(), savedReply()))
        failure(FailureReason.CONFLICT, f.store.commit(f.scope, prepared.mutations))
        // Only the controller's retained exact domain AND archive proof can support a fresh ACK.
    }

    @Test fun unknownDeleteApplyRetainsTombstoneAndDoesNotInferOriginalAck() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.save()
        val prepared = value(f.repo.prepareDeleteReceipt(f.lease, COMMAND2, wireSaved(), "\"1\"", deletedReply()))
        f.store.unknownNext = true
        failure(FailureReason.OUTCOME_UNKNOWN, f.store.commit(f.scope, prepared.mutations))
        assertNull(value(f.repo.read(f.lease, SAVED)))
        failure(FailureReason.CONFLICT, f.repo.prepareDeleteReceipt(f.lease, COMMAND2, wireSaved(), "\"1\"", deletedReply()))
    }

    @Test fun remotePagingPreservesAbsentVersusEmptyQueryAndNeverDownloads() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.reply = pageReply(next = "next-token")
        val page = value(f.repo.listRemote(f.lease, limit = 2))
        assertNull(page.query); assertNull(page.cursor); assertEquals("next-token", page.nextCursor)
        assertNull(page.items.single().localRevision); assertNull(page.items.single().etag)
        assertFalse(f.calls.last().queryParameters.containsKey("q"))
        value(f.repo.listRemote(f.lease, q = "", cursor = "original", limit = 2))
        assertEquals(listOf(""), f.calls.last().queryParameters["q"])
        assertEquals(listOf("original"), f.calls.last().queryParameters["cursor"])
        assertTrue(f.store.records.isEmpty()); assertTrue(value(f.repo.search(f.lease)).isEmpty())
    }

    @Test fun remoteGetAndEnumerationRemainAvailableBeyondLocal512EntryBound() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seedIndex(512)
        f.reply = savedReply().copy(status = 200)
        assertNull(value(f.repo.getRemote(f.lease, SAVED)).localRevision)
        f.reply = pageReply(); assertEquals(SAVED, value(f.repo.listRemote(f.lease)).items.single().id)
        assertNull(f.store.records[bodyKey(SAVED)]); assertEquals(512, indexIds(f.store).size)
    }

    @Test fun remotePageRejectsDuplicateIdsRepeatedCursorOversizedCountAndMissingRequiredFields() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for (reply in listOf(pageReply(items = listOf(saved(), saved())), pageReply(next = "same"),
            pageReply(items = listOf(saved(), saved(id = SAVED2))),
            ApiReply(200, bytes("{\"items\":[],\"nextCursor\":null}"), contentType = "application/json"))) {
            f.reply = reply
            failure(FailureReason.INVALID_DATA, f.repo.listRemote(f.lease, cursor = "same", limit = 1))
        }
        assertTrue(f.store.records.isEmpty())
    }

    @Test fun remoteQueryValidationDoesNotSendInvalidUnboundedOrControlParameters() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for (query in listOf("a".repeat(101), "\uD800", "secret\nquery"))
            failure(FailureReason.INVALID_DATA, f.repo.listRemote(f.lease, q = query))
        for (cursor in listOf("", "x".repeat(2049), "secret\ncursor"))
            failure(FailureReason.INVALID_DATA, f.repo.listRemote(f.lease, cursor = cursor))
        failure(FailureReason.INVALID_DATA, f.repo.listRemote(f.lease, limit = 51))
        assertTrue(f.calls.isEmpty()); assertTrue(f.store.records.isEmpty())
    }

    @Test fun cachedVersionAndRedactionRulesAlsoFenceRemoteCardsWithoutAutoDownloading() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.save()
        val before = f.store.snapshot()
        f.reply = pageReply(items = listOf(saved(quantity = "100")))
        failure(FailureReason.CONFLICT, f.repo.listRemote(f.lease))
        f.reply = savedReply(saved(quantity = "100")).copy(status = 200)
        failure(FailureReason.CONFLICT, f.repo.getRemote(f.lease, SAVED))
        assertEquals(before, f.store.snapshot())
    }

    @Test fun boundNegativeReplyInstallsOnlyOriginalRecallAndMalformedProblemCannot() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        failure(FailureReason.INVALID_DATA, f.repo.observeSaveReply(f.lease, plan(), problem().copy(contentType = "text/plain")))
        assertTrue(f.store.records.isEmpty())
        value(f.repo.observeSaveReply(f.lease, plan(), problem()))
        failure(FailureReason.FORBIDDEN, f.repo.prepareSave(f.lease, COMMAND, plan(), request()))
        assertEquals(setOf(RecordKey("feedme.kitchen.recall", "version:$VERSION")), f.store.records.keys)
        value(f.repo.observeDeleteReply(f.lease, wireSaved(), problem()))
        assertTrue(f.store.records.containsKey(RecordKey("feedme.kitchen.recall", "saved:$SAVED")))
        val other = Fixture(StandardTestDispatcher(testScheduler))
        value(other.repo.observeSaveReply(other.lease, plan(), savedReply(saved(recalled = true))))
        failure(FailureReason.FORBIDDEN, other.repo.prepareSave(other.lease, COMMAND, plan(), request()))
        val mismatch = Fixture(StandardTestDispatcher(testScheduler))
        failure(FailureReason.CONFLICT, mismatch.repo.observeSaveReply(mismatch.lease, plan(), savedReply(saved(recalled = true, quantity = "99"))))
        assertTrue(mismatch.store.records.isEmpty())
    }

    @Test fun recalledRemoteBodyRemainsUnavailableAndDoesNotAcquireDownloadIdentity() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.reply = pageReply(items = listOf(saved(recalled = true)))
        val entry = value(f.repo.listRemote(f.lease)).items.single()
        assertEquals(SavedRecipeAvailability.RECALLED, entry.availability)
        assertNull(entry.savedRecipe); assertNull(entry.localRevision)
        assertNull(f.store.records[bodyKey(SAVED)])
    }

    @Test fun leaseInvalidationAndCancellationDropDelayedPageWithoutMutation() = runTest {
        for (cancel in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val release = CompletableDeferred<Unit>()
            f.exchange = { release.await(); pageReply() }
            val read = async { f.repo.listRemote(f.lease) }; runCurrent()
            if (cancel) { read.cancel(); release.complete(Unit); assertFailsWith<CancellationException> { read.await() } }
            else { f.boundary.clear(); release.complete(Unit); failure(FailureReason.STALE_SESSION, read.await()) }
            assertTrue(f.store.records.isEmpty())
        }
    }

    @Test fun corruptedReservationOrTombstoneCannotBeTreatedAsAbsent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.store.seed(RESERVATIONS, "{\"items\":[{\"commandId\":\"invalid\"}]}")
        failure(FailureReason.INVALID_DATA, f.repo.prepareSave(f.lease, COMMAND, plan(), request()))
        f.store.seed(tombstoneKey(SAVED), "{\"unknown\":true}")
        failure(FailureReason.INVALID_DATA, f.repo.read(f.lease, SAVED))
        failure(FailureReason.INVALID_DATA, f.repo.getRemote(f.lease, SAVED))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun remoteRedactionBlocksOlderDownloadedAttributionUntilCompatibleExplicitDownload() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.reply = savedReply(saved(source = "postGrant", provenance = true)).copy(status = 200)
        value(f.repo.download(f.lease, SAVED))
        val oldBytes = f.store.records.getValue(bodyKey(SAVED)).payload.copyForCodec()
        f.reply = savedReply(saved(version = 2, source = "postGrant")).copy(status = 200, etag = "\"2\"")
        val remote = value(f.repo.getRemote(f.lease, SAVED))
        assertEquals(SavedRecipeAvailability.AVAILABLE, remote.availability); assertNull(remote.localRevision)
        assertContentEquals(oldBytes, f.store.records.getValue(bodyKey(SAVED)).payload.copyForCodec())
        assertEquals(SavedRecipeAvailability.UNAVAILABLE, value(f.newRepository().read(f.lease, SAVED))!!.availability)
        assertNull(value(f.repo.search(f.lease)).single().savedRecipe)
        value(f.repo.download(f.lease, SAVED))
        assertEquals(SavedRecipeAvailability.AVAILABLE, value(f.repo.read(f.lease, SAVED))!!.availability)
        f.reply = savedReply(saved(version = 3, source = "postGrant", provenance = true)).copy(status = 200, etag = "\"3\"")
        failure(FailureReason.CONFLICT, f.repo.getRemote(f.lease, SAVED))
    }

    @Test fun failedNegativeRedactionWriteStillBlocksSameLeaseAcrossRepositoryReplacement() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.reply = savedReply(saved(source = "postGrant", provenance = true)).copy(status = 200)
        value(f.repo.download(f.lease, SAVED))
        f.store.denyCollection = "feedme.kitchen.saved.redaction"
        f.reply = pageReply(items = listOf(saved(version = 2, source = "postGrant")))
        failure(FailureReason.OUTCOME_UNKNOWN, f.repo.listRemote(f.lease))
        assertTrue(f.store.records.keys.none { it.collection == "feedme.kitchen.saved.redaction" })
        assertEquals(SavedRecipeAvailability.UNAVAILABLE, value(f.newRepository().read(f.lease, SAVED))!!.availability)
        // Same-process negative knowledge is not a promise of crash-persisted redaction.
        f.boundary.clear()
        failure(FailureReason.STALE_SESSION, f.repo.read(f.lease, SAVED))
    }

    private class Fixture(private val dispatcher: CoroutineDispatcher) {
        val scope = StorageScope("cookbook-mutation", ActorKind.ACCOUNT, "owner-${owners++}")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope)
        val store = CasStore(scope); val calls = mutableListOf<ApiCall>()
        var reply = savedReply().copy(status = 200)
        var exchange: suspend () -> ApiReply = { reply }
        private val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                calls += call; return PortResult.Value(exchange())
            }
        }
        val repo = newRepository()
        fun newRepository() = SavedRecipeRepository(scope, store, boundary, dispatcher, EpochClock { 1_800_000_000_000 }, transport)
        suspend fun reserve(body: SaveRecipeRequest = request(), command: String = COMMAND) {
            value(store.commit(scope, value(repo.prepareSave(lease, command, plan(), body)).mutations))
        }
        suspend fun save() { reserve(); value(store.commit(scope,
            value(repo.prepareSaveReceipt(lease, COMMAND, plan(), request(), savedReply())).mutations)) }
        suspend fun delete() { value(store.commit(scope,
            value(repo.prepareDeleteReceipt(lease, COMMAND2, wireSaved(), "\"1\"", deletedReply())).mutations)) }
        fun seedIndex(count: Int) { store.seed(INDEX, buildJsonObject {
            put("ids", JsonArray((1..count).map { JsonPrimitive(uuid(it)) })) }.toString()) }
    }

    private class CasStore(private val owner: StorageScope) : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>()
        private val revisions = mutableMapOf<RecordKey, Long>()
        val commits = mutableListOf<List<StoreMutation>>()
        var unknownNext = false
        var denyCollection: String? = null
        fun seed(key: RecordKey, text: String) { records[key] = PrivateRecord(1, 1, bytes(text)); revisions[key] = 1 }
        fun snapshot() = records.mapValues { (_, record) -> record.revision to record.payload.copyForCodec().decodeToString() }
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> =
            if (scope != owner) PortResult.Failure(FailureReason.STALE_SESSION) else PortResult.Value(records[key])
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            if (mutations.isEmpty() || mutations.size > 64 || mutations.map { it.key }.distinct().size != mutations.size)
                return PortResult.Failure(FailureReason.INVALID_DATA)
            if (mutations.any { it.expectedRevision != records[it.key]?.revision }) return PortResult.Failure(FailureReason.CONFLICT)
            if (mutations.any { it.key.collection == denyCollection }) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            val result = mutableMapOf<RecordKey, Long?>()
            for (mutation in mutations) {
                val revision = (revisions[mutation.key] ?: 0) + 1; revisions[mutation.key] = revision
                when (mutation) {
                    is StoreMutation.Put -> { records[mutation.key] = PrivateRecord(revision, mutation.schemaVersion, mutation.payload); result[mutation.key] = revision }
                    is StoreMutation.Delete -> { records.remove(mutation.key); result[mutation.key] = null }
                }
            }
            commits += mutations.toList()
            if (unknownNext) { unknownNext = false; return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
            return PortResult.Value(result)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("No repository authority to erase an owner")
    }

    private companion object {
        var owners = 0
        const val PLAN = "123e4567-e89b-12d3-a456-426614174001"
        const val OTHER_PLAN = "123e4567-e89b-12d3-a456-426614174002"
        const val RECIPE = "123e4567-e89b-12d3-a456-426614174003"
        const val OTHER_RECIPE = "123e4567-e89b-12d3-a456-426614174004"
        const val VERSION = "123e4567-e89b-12d3-a456-426614174005"
        const val INGREDIENT = "123e4567-e89b-12d3-a456-426614174006"
        const val SAVED = "123e4567-e89b-12d3-a456-426614174007"
        const val SAVED2 = "123e4567-e89b-12d3-a456-426614174008"
        const val COMMAND = "123e4567-e89b-12d3-a456-426614174009"
        const val COMMAND2 = "123e4567-e89b-12d3-a456-426614174010"
        const val COMMAND3 = "123e4567-e89b-12d3-a456-426614174011"
        val INDEX = RecordKey("feedme.kitchen.saved.index", "v1")
        val RESERVATIONS = RecordKey("feedme.kitchen.saved.reservations", "v1")
        fun bodyKey(id: String) = RecordKey("feedme.kitchen.saved.body", id)
        fun metaKey(id: String) = RecordKey("feedme.kitchen.saved.metadata", id)
        fun tombstoneKey(id: String) = RecordKey("feedme.kitchen.saved.deleted", id)
        fun uuid(n: Int) = "123e4567-e89b-12d3-a456-${n.toString().padStart(12, '0')}"
        fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        fun indexIds(store: CasStore) = Json.parseToJsonElement(store.records.getValue(INDEX).payload.copyForCodec().decodeToString()).jsonObject.getValue("ids").jsonArray
        fun reservationCount(store: CasStore) = Json.parseToJsonElement(store.records.getValue(RESERVATIONS).payload.copyForCodec().decodeToString()).jsonObject.getValue("items").jsonArray.size
        fun request(title: String? = null) = SaveRecipeRequest(planId = PlanId(PLAN), title = title)
        fun savedReply(body: String = saved()) = ApiReply(201, bytes(body), etag = "\"1\"", contentType = "application/json")
        fun deletedReply() = ApiReply(204, null)
        fun problem(status: Int = 410, code: String = "RECIPE_RECALLED") = ApiReply(status, bytes("""{"type":"https://example.test/problem","title":"Unavailable","status":$status,"code":"$code","traceId":"test"}"""), contentType = "application/problem+json")
        fun pageReply(items: List<String> = listOf(saved()), next: String? = null) = ApiReply(200, bytes(buildJsonObject {
            put("items", JsonArray(items.map(Json::parseToJsonElement))); put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull)
            put("serverTime", "2026-09-14T08:00:00Z")
        }.toString()), contentType = "application/json")
        fun wireSaved() = SavedRecipeWire.from(WireDocument.decode(saved().encodeToByteArray()))
        fun plan(version: Int = 1, status: String = "ready", instruction: String = "Exact private instruction"): PlanWire = PlanWire.from(WireDocument.decode(buildJsonObject {
            put("id", PLAN); put("version", version); put("createdAt", "2026-09-14T07:00:00Z"); put("updatedAt", "2026-09-14T07:00:00Z")
            put("status", status); put("mode", "cook"); put("recipeVersionId", VERSION); put("catalogRevision", "catalog-pinned")
            put("missingIngredients", JsonArray(emptyList())); put("changes", JsonArray(emptyList())); put("reasons", JsonArray(emptyList()))
            put("constraints", buildJsonObject {
                put("ingredientIds", JsonArray(listOf(JsonPrimitive(INGREDIENT)))); put("energy", "little"); put("servings", 1)
                put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("hardExcludedIngredientIds", JsonArray(emptyList()))
            })
            put("recipeSnapshot", recipe(instruction = instruction))
        }.toString().encodeToByteArray()))
        fun saved(id: String = SAVED, version: Int = 1, recipeVersion: Int = 1, quantity: String = "2.7500", recipeId: String = RECIPE,
            title: String = "Original title", source: String = "ownPlan", recalled: Boolean = false, provenance: Boolean = false,
            instruction: String = "Exact private instruction") = buildJsonObject {
            put("id", id); put("version", version); put("createdAt", "2026-09-14T07:00:00Z"); put("updatedAt", "2026-09-14T08:00:00Z")
            put("title", title); put("sourceType", source); put("recalled", recalled); put("contentLicense", "privateCopyOnly")
            if (provenance) { put("sourcePostId", OTHER_PLAN); put("grantId", OTHER_RECIPE); put("creatorLabel", "Private creator") }
            put("snapshot", recipe(recipeVersion, quantity, recipeId, instruction))
        }.toString()
        fun recipe(version: Int = 1, quantity: String = "2.7500", id: String = RECIPE, instruction: String = "Exact private instruction") = buildJsonObject {
            put("id", VERSION); put("recipeId", id); put("version", version); put("createdAt", "2026-09-14T07:00:00Z"); put("updatedAt", "2026-09-14T07:00:00Z")
            put("title", "Pinned recipe"); put("reviewStatus", "published"); put("servings", 1); put("activeMinutes", 5); put("totalMinutes", 5); put("utensilCount", 1)
            put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("modes", JsonArray(listOf(JsonPrimitive("cook")))); put("tasteTags", JsonArray(emptyList()))
            put("ingredients", buildJsonArray { add(buildJsonObject { put("ingredientId", INGREDIENT); put("quantity", Json.parseToJsonElement(quantity)); put("unit", "g"); put("optional", false) }) })
            put("steps", buildJsonArray { add(buildJsonObject {
                put("stepId", "first"); put("position", 1); put("instruction", instruction); put("ingredientIds", JsonArray(listOf(JsonPrimitive(INGREDIENT))))
                put("requiredEquipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("mandatorySafetyStep", false)
            }) })
        }
    }
}
