package com.feedme.kitchen

import com.feedme.contracts.WireField
import com.feedme.core.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SavedRecipeRepositoryTest {
    @Test fun exactCanonicalBodyAndReadOnlyMetadataSurviveRepositoryRecreation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val raw = " \r\n" + saved(quantity = "1.2500") + "\t\n"
        f.respond(raw, "\"0001\"")
        val downloaded = value(f.repo.download(f.lease, ID.uppercase()))
        assertEquals(SavedRecipeAvailability.AVAILABLE, downloaded.availability)
        assertEquals(ID, downloaded.id)
        assertEquals("\"0001\"", downloaded.etag)
        assertEquals(START, downloaded.lastCheckedMillis)
        assertContentEquals(raw.encodeToByteArray(), f.store.records.getValue(bodyKey(ID)).payload.copyForCodec())
        val reopened = assertNotNull(value(f.newRepository().read(f.lease, ID)))
        assertContentEquals(raw.encodeToByteArray(), assertNotNull(reopened.savedRecipe).document.encodeUtf8())
        assertEquals(downloaded.localRevision, reopened.localRevision)
        assertEquals("1.2500", reopened.savedRecipe!!.snapshot.ingredients.single().quantity.jsonToken)
        assertEquals(listOf("getSavedRecipe"), f.calls.map { it.operationId })
        assertEquals(mapOf("savedRecipeId" to ID), f.calls.single().pathParameters)
        assertFalse(downloaded.toString().contains("Private lunch"))
        assertFalse(downloaded.toString().contains(ID))
    }

    @Test fun bodyMetadataAndIndexAreOneAtomicCasBatch() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        val batch = f.store.commits.single { it.any { change -> change.key == bodyKey(ID) } }
        assertEquals(setOf(bodyKey(ID), metadataKey(ID), INDEX), batch.map { it.key }.toSet())
        assertTrue(batch.all { it.expectedRevision == null })
        f.respond(saved(version = "2"), "\"2\"")
        val before = f.store.detached()
        f.store.failWhen = { it.any { mutation -> mutation.key == bodyKey(ID) } }
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
        assertEquals(before, f.store.detached())
        assertEquals("1", assertNotNull(value(f.repo.read(f.lease, ID))).savedRecipe!!.version.jsonToken)
    }

    @Test fun failedFirstDownloadDoesNotCreateAnOrphanBodyOrIndexEntry() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.store.failWhen = { it.any { mutation -> mutation.key == bodyKey(ID) } }
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
        assertNull(f.store.records[bodyKey(ID)])
        assertNull(f.store.records[metadataKey(ID)])
        assertNull(f.store.records[INDEX])
        assertNull(value(f.repo.read(f.lease, ID)))
    }

    @Test fun delayedFreshReadCannotOverwriteAConcurrentHydration() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        var ordinal = 0
        f.exchange = {
            if (++ordinal == 1) { release.await(); reply(saved(), "\"1\"") }
            else reply(saved(version = "2", reviewStatus = "retired", recipeVersion = "2"), "\"2\"")
        }
        val delayed = async { f.repo.download(f.lease, ID) }
        runCurrent()
        value(f.newRepository().download(f.lease, ID))
        release.complete(Unit)
        failure(FailureReason.CONFLICT, delayed.await())
        assertEquals("2", assertNotNull(value(f.repo.read(f.lease, ID))).savedRecipe!!.version.jsonToken)
    }

    @Test fun ownerRetirementDuringTransportAwaitDropsTheResultWithoutCaching() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.exchange = { f.boundary.clear(); reply(saved()) }
        failure(FailureReason.STALE_SESSION, f.repo.download(f.lease, ID))
        assertNull(f.store.records[bodyKey(ID)])
        assertNull(f.store.records[INDEX])
    }

    @Test fun sameOwnerNewLeaseEnvironmentAndGuestNeverReuseTheOldLease() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val replacement = f.boundary.activate(f.scope)
        failure(FailureReason.STALE_SESSION, f.repo.download(f.lease, ID))
        value(f.repo.download(replacement, ID))
        val other = f.boundary.activate(StorageScope("other-environment", ActorKind.ACCOUNT, f.scope.actorId))
        failure(FailureReason.STALE_SESSION, f.repo.read(other, ID))
        val guest = f.boundary.activate(StorageScope(f.scope.environment, ActorKind.GUEST, f.scope.actorId))
        failure(FailureReason.STALE_SESSION, f.repo.search(guest))
        assertEquals(1, f.calls.size)
    }

    @Test fun retirementDuringStorageAwaitDoesNotExposeThePrivateDocument() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.store.afterRead = { if (it == bodyKey(ID)) f.boundary.clear() }
        failure(FailureReason.STALE_SESSION, f.repo.read(f.lease, ID))
    }

    @Test fun personalAndRetiredCopiesAreReadableWithoutPaidOrCurrentGrantChecks() = runTest {
        for (status in listOf("personal", "retired")) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            f.respond(saved(reviewStatus = status, sourceType = "postGrant", provenance = true))
            val snapshot = value(f.repo.download(f.lease, ID))
            assertEquals(SavedRecipeAvailability.AVAILABLE, snapshot.availability)
            assertEquals(status, snapshot.savedRecipe!!.snapshot.reviewStatus)
            assertEquals("postGrant", snapshot.savedRecipe!!.sourceType)
            f.exchange = { error("An existing private copy must not recheck a post, grant or purchase") }
            assertNotNull(value(f.newRepository().read(f.lease, ID))!!.savedRecipe)
            assertEquals(1, value(f.repo.search(f.lease, "lunch")).size)
            assertEquals(listOf("getSavedRecipe"), f.calls.map { it.operationId })
        }
    }

    @Test fun rawBodyWhitespaceCorruptionIsExplicitEvenForANonmatchingSearchQuery() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.store.corrupt(bodyKey(ID), f.store.text(bodyKey(ID)) + " ")
        val read = assertNotNull(value(f.repo.read(f.lease, ID)))
        assertEquals(SavedRecipeAvailability.INTEGRITY_FAILURE, read.availability)
        assertNull(read.savedRecipe)
        assertEquals(SavedRecipeAvailability.INTEGRITY_FAILURE,
            value(f.repo.search(f.lease, "not present")).single().availability)
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, ID))
        assertEquals(1, f.calls.size)
    }

    @Test fun missingOrMalformedIndexedMetadataRemainsAnExplicitEntry() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.store.corrupt(metadataKey(ID), "{\"unexpected\":\"private marker\"}")
        assertEquals(SavedRecipeAvailability.INTEGRITY_FAILURE, value(f.repo.search(f.lease)).single().availability)
        f.store.records.remove(metadataKey(ID))
        assertEquals(SavedRecipeAvailability.INTEGRITY_FAILURE, assertNotNull(value(f.repo.read(f.lease, ID))).availability)
        assertEquals(1, value(f.repo.search(f.lease, "unknown title")).size)
    }

    @Test fun learnedRecallRemainsStickyAcrossLaterSuccessAndRepositoryRecreation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.respond(saved(version = "2", recalled = true), "\"2\"")
        assertEquals(SavedRecipeAvailability.RECALLED, value(f.repo.download(f.lease, ID)).availability)
        f.respond(saved(version = "3"), "\"3\"")
        assertEquals(SavedRecipeAvailability.RECALLED, value(f.repo.download(f.lease, ID)).availability)
        val read = assertNotNull(value(f.newRepository().read(f.lease, ID)))
        assertEquals(SavedRecipeAvailability.RECALLED, read.availability)
        assertNull(read.savedRecipe)
    }

    @Test fun recallWithChangedImmutableContentFailsButStillInvalidatesTheOldCopy() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        val original = f.store.text(bodyKey(ID))
        f.respond(saved(version = "2", recalled = true, quantity = "999"), "\"2\"")
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
        assertEquals(original, f.store.text(bodyKey(ID)))
        assertEquals(SavedRecipeAvailability.RECALLED, assertNotNull(value(f.newRepository().read(f.lease, ID))).availability)
    }

    @Test fun recallMarkersSurviveAFailedBodyMetadataIndexCommit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.respond(saved(version = "2", recalled = true), "\"2\"")
        f.store.failWhen = { it.any { mutation -> mutation.key == bodyKey(ID) } }
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
        assertEquals(SavedRecipeAvailability.RECALLED, assertNotNull(value(f.newRepository().read(f.lease, ID))).availability)
        assertEquals("1", Json.parseToJsonElement(f.store.text(bodyKey(ID))).jsonObject.getValue("version").jsonPrimitive.content)
    }

    @Test fun versionRecallInvalidatesAnotherSavedCopyOfThatExactVersion() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.respond(saved(id = ID2))
        value(f.repo.download(f.lease, ID2))
        f.respond(saved(version = "2", reviewStatus = "recalled", recipeVersion = "2"), "\"2\"")
        value(f.repo.download(f.lease, ID))
        assertEquals(SavedRecipeAvailability.RECALLED, assertNotNull(value(f.repo.read(f.lease, ID2))).availability)
    }

    @Test fun failedDurableRecallMarkersStillFailClosedInTheCurrentProcess() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.respond(saved(version = "2", recalled = true), "\"2\"")
        f.store.failWhen = { it.any { mutation -> mutation.key.collection == "feedme.kitchen.recall" } }
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
        val read = assertNotNull(value(f.newRepository().read(f.lease, ID)))
        assertEquals(SavedRecipeAvailability.RECALLED, read.availability)
        assertNull(read.savedRecipe)
        // This proves the same-process fence only, not crash recovery after a failed durable write.
        assertTrue(f.store.records.keys.none { it.collection == "feedme.kitchen.recall" })
    }

    @Test fun wrongSavedIdentityCannotTombstoneTheRequestedCopy() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.respond(saved(id = ID2, version = "2", recalled = true), "\"2\"")
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, ID))
        assertEquals(SavedRecipeAvailability.AVAILABLE, assertNotNull(value(f.repo.read(f.lease, ID))).availability)
    }

    @Test fun ownedMaterializedVariantIsNeverReplacedWithBaseCatalogRecipe() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.respond(saved(sourceType = "ownPlan", quantity = "2.7500"))
        value(f.repo.download(f.lease, ID))
        f.respond(saved(version = "2", sourceType = "ownPlan", quantity = "1"), "\"2\"")
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
        assertEquals("2.7500", assertNotNull(value(f.repo.read(f.lease, ID))).savedRecipe!!.snapshot.ingredients.single().quantity.jsonToken)
        assertTrue(f.calls.all { it.operationId == "getSavedRecipe" })
    }

    @Test fun unchangedImmutableContentPermitsRetirementAndSourceRedactionButNeverIdentityResurrection() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.respond(saved(sourceType = "postGrant", provenance = true))
        value(f.repo.download(f.lease, ID))
        f.respond(saved(version = "2", sourceType = "postGrant", reviewStatus = "retired", recipeVersion = "2"), "\"2\"")
        val redacted = value(f.repo.download(f.lease, ID))
        assertEquals(WireField.Missing, redacted.savedRecipe!!.creatorLabel)
        f.respond(saved(version = "3", sourceType = "postGrant", reviewStatus = "retired", recipeVersion = "3", provenance = true), "\"3\"")
        failure(FailureReason.CONFLICT, f.newRepository().download(f.lease, ID))
        f.respond(saved(sourceType = "postGrant", provenance = true))
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
        assertEquals(WireField.Missing, assertNotNull(value(f.repo.read(f.lease, ID))).savedRecipe!!.sourcePostId)
    }

    @Test fun sameVersionContentChangeAndLowerVersionAreConflicts() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.respond(saved(version = "2"), "\"2\"")
        value(f.repo.download(f.lease, ID))
        f.respond(saved(version = "2", recalled = false, reviewStatus = "retired"), "\"2\"")
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
        f.respond(saved())
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
        assertEquals("2", assertNotNull(value(f.repo.read(f.lease, ID))).savedRecipe!!.version.jsonToken)
    }

    @Test fun nestedLifecycleCannotRegressOrChangeAtTheSameVersionDespiteNewerSaveVersion() = runTest {
        for (initialStatus in listOf("personal", "retired")) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            f.respond(saved(recipeVersion = "2", reviewStatus = initialStatus))
            value(f.repo.download(f.lease, ID))
            val original = f.store.text(bodyKey(ID))
            f.respond(saved(version = "2", recipeVersion = "1", reviewStatus = "published"), "\"2\"")
            failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
            assertEquals(original, f.store.text(bodyKey(ID)))
            f.respond(saved(version = "2", recipeVersion = "2", reviewStatus = "published"), "\"2\"")
            failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
            assertEquals(original, f.store.text(bodyKey(ID)))
            f.respond(saved(version = "2", recipeVersion = "3", reviewStatus = "published"), "\"2\"")
            val accepted = value(f.repo.download(f.lease, ID))
            assertEquals(SavedRecipeAvailability.AVAILABLE, accepted.availability)
            assertEquals("2", accepted.savedRecipe!!.version.jsonToken)
            assertEquals("3", accepted.savedRecipe!!.snapshot.version.jsonToken)
            assertEquals("published", accepted.savedRecipe!!.snapshot.reviewStatus)
            assertEquals("1.2500", accepted.savedRecipe!!.snapshot.ingredients.single().quantity.jsonToken)
        }
    }

    @Test fun recalledNestedVersionRegressionStillFencesTheEstablishedSavedCopy() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.respond(saved(recipeVersion = "2", reviewStatus = "retired"))
        value(f.repo.download(f.lease, ID))
        val original = f.store.text(bodyKey(ID))
        f.respond(saved(version = "2", recipeVersion = "1", reviewStatus = "recalled"), "\"2\"")
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
        assertEquals(original, f.store.text(bodyKey(ID)))
        val fenced = assertNotNull(value(f.newRepository().read(f.lease, ID)))
        assertEquals(SavedRecipeAvailability.RECALLED, fenced.availability)
        assertNull(fenced.savedRecipe)
    }

    @Test fun noEtagIsReadableButAMismatchedEtagCannotBeSynthesizedOrRounded() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.respond(saved(), null)
        assertNull(value(f.repo.download(f.lease, ID)).etag)
        f.respond(saved(version = "9007199254740993"), "\"9007199254740992\"")
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, ID))
        assertNull(assertNotNull(value(f.repo.read(f.lease, ID))).etag)
        f.respond(saved(version = "1e3"), "\"001000\"")
        assertEquals("\"001000\"", value(f.repo.download(f.lease, ID)).etag)
    }

    @Test fun validRecallWithMismatchedEtagStillBlocksAnExistingCopy() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.respond(saved(version = "2", recalled = true), "\"3\"")
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, ID))
        assertEquals(SavedRecipeAvailability.RECALLED, assertNotNull(value(f.repo.read(f.lease, ID))).availability)
    }

    @Test fun boundRecallProblemInvalidatesTheOwnedCopyAndItsPinnedVersion() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.respond(saved(id = ID2))
        value(f.repo.download(f.lease, ID2))
        f.exchange = { problem("RECIPE_RECALLED") }
        failure(FailureReason.NOT_FOUND, f.repo.download(f.lease, ID))
        assertEquals(SavedRecipeAvailability.RECALLED, assertNotNull(value(f.newRepository().read(f.lease, ID))).availability)
        assertEquals(SavedRecipeAvailability.RECALLED, assertNotNull(value(f.repo.read(f.lease, ID2))).availability)
    }

    @Test fun recallProblemForAnUnknownCopyNeverInventsARecipeVersionIdentity() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.exchange = { problem("RECIPE_RECALLED") }
        failure(FailureReason.NOT_FOUND, f.repo.download(f.lease, ID))
        assertEquals(SavedRecipeAvailability.RECALLED, assertNotNull(value(f.repo.read(f.lease, ID))).availability)
        assertNull(f.store.records[bodyKey(ID)])
        assertNull(f.store.records[INDEX])
        assertTrue(f.store.records.keys.none { it.collection == "feedme.kitchen.recall" && it.id.startsWith("version:") })
    }

    @Test fun unboundMalformedAndOtherProblemsCannotInstallRecallEvidence() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.exchange = { problem("SOURCE_UNAVAILABLE") }
        failure(FailureReason.NOT_FOUND, f.repo.download(f.lease, ID))
        f.exchange = { PortResult.Value(ApiReply(410, bytes("""{"code":"RECIPE_RECALLED"}"""), contentType = "application/problem+json")) }
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, ID))
        f.exchange = { problem("RECIPE_RECALLED", bodyStatus = 409) }
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, ID))
        assertEquals(SavedRecipeAvailability.AVAILABLE, assertNotNull(value(f.repo.read(f.lease, ID))).availability)
        assertTrue(f.store.records.keys.none { it.collection == "feedme.kitchen.recall" })
    }

    @Test fun recallProblemRetainsItsDomainReasonAndMemoryFenceWhenPersistenceFails() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.exchange = { problem("RECIPE_RECALLED") }
        f.store.failWhen = { it.any { mutation -> mutation.key.collection == "feedme.kitchen.recall" } }
        failure(FailureReason.NOT_FOUND, f.repo.download(f.lease, ID))
        assertEquals(SavedRecipeAvailability.RECALLED, assertNotNull(value(f.newRepository().read(f.lease, ID))).availability)
        assertTrue(f.store.records.keys.none { it.collection == "feedme.kitchen.recall" })
    }

    @Test fun unknownIdsAreNullAndInvalidIdsFailBeforeAnyStoreOrNetworkAccess() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        assertNull(value(f.repo.read(f.lease, ID)))
        val reads = f.store.readCount
        for (id in listOf("", "../private", "$ID?token=secret", " $ID")) {
            failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, id))
            failure(FailureReason.INVALID_DATA, f.repo.read(f.lease, id))
        }
        assertEquals(reads, f.store.readCount)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun schemaRejectsSourceMediaCommentsAndWrongResponseIdentity() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for (field in listOf("sourceMediaId", "comments", "sourcePhoto")) {
            val body = Json.parseToJsonElement(saved()).jsonObject
            f.respond(JsonObject(body + (field to JsonPrimitive("must never be cached"))).toString())
            failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, ID))
            assertNull(f.store.records[bodyKey(ID)])
        }
        f.respond(saved(id = ID2))
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, ID))
    }

    @Test fun localSearchUsesOnlyTitlesIsUnicodeBoundedAndNeverUsesTransport() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, ID))
        f.respond(saved(id = ID2, title = "Other dinner"))
        value(f.repo.download(f.lease, ID2))
        f.exchange = { error("Local searches must not leave the device") }
        assertEquals(listOf(ID), value(f.repo.search(f.lease, " LUNCH ")).map { it.id })
        assertTrue(value(f.repo.search(f.lease, "private instruction marker")).isEmpty())
        assertEquals(1, value(f.repo.search(f.lease, limit = 1)).size)
        assertTrue(value(f.repo.search(f.lease, "😀".repeat(100))).isEmpty())
        for (query in listOf("😀".repeat(101), "\uD800", "\uDC00", "secret\nquery"))
            failure(FailureReason.INVALID_DATA, f.repo.search(f.lease, query))
        for (limit in listOf(0, -1, 513)) failure(FailureReason.INVALID_DATA, f.repo.search(f.lease, limit = limit))
        assertEquals(2, f.calls.size)
    }

    @Test fun indexBoundAndStrictIndexMetadataPreventUnboundedEnumeration() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val ids = (1..512).map(::uuid)
        f.store.seed(INDEX, buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) }.toString())
        assertEquals(512, value(f.repo.search(f.lease, limit = 512)).size)
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, ID))
        assertNull(f.store.records[bodyKey(ID)])
        f.store.corrupt(INDEX, buildJsonObject { put("ids", JsonArray((ids + ids[0]).map(::JsonPrimitive))) }.toString())
        failure(FailureReason.INVALID_DATA, f.repo.search(f.lease))
    }

    private class Fixture(private val dispatcher: CoroutineDispatcher) {
        val scope = StorageScope("saved-test", ActorKind.ACCOUNT, "private-owner-${nextOwner++}")
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        val store = SavedCasStore(scope)
        val calls = mutableListOf<ApiCall>()
        var now = START
        var exchange: suspend () -> PortResult<ApiReply> = { reply(saved()) }
        private val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                calls += call
                return exchange()
            }
        }
        val repo = newRepository()
        fun newRepository() = SavedRecipeRepository(scope, store, boundary, dispatcher, EpochClock { now }, transport)
        fun respond(body: String, etag: String? = "\"1\"") { exchange = { reply(body, etag) } }
    }

    /** Detached, all-or-nothing CAS fake; recreation tests exercise repository state, not SQL. */
    private class SavedCasStore(private val owner: StorageScope) : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>()
        private val revisions = mutableMapOf<RecordKey, Long>()
        val commits = mutableListOf<List<StoreMutation>>()
        var failWhen: (List<StoreMutation>) -> Boolean = { false }
        var afterRead: suspend (RecordKey) -> Unit = {}
        var readCount = 0
        fun text(key: RecordKey) = records.getValue(key).payload.copyForCodec().decodeToString()
        fun detached() = records.mapValues { (_, record) -> record.revision to record.payload.copyForCodec().decodeToString() }
        fun corrupt(key: RecordKey, body: String) { records[key] = records.getValue(key).copy(payload = bytes(body)) }
        fun seed(key: RecordKey, body: String) { records[key] = PrivateRecord(1, 1, bytes(body)); revisions[key] = 1 }
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            readCount++
            val result = records[key]?.let { it.copy(payload = PrivateBytes(it.payload.copyForCodec())) }
            afterRead(key)
            return PortResult.Value(result)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            if (mutations.isEmpty() || mutations.size > 64 || mutations.map { it.key }.distinct().size != mutations.size)
                return PortResult.Failure(FailureReason.INVALID_DATA)
            if (failWhen(mutations) || mutations.any { it.expectedRevision != records[it.key]?.revision })
                return PortResult.Failure(FailureReason.CONFLICT)
            val result = mutableMapOf<RecordKey, Long?>()
            for (mutation in mutations) {
                val revision = (revisions[mutation.key] ?: 0) + 1
                revisions[mutation.key] = revision
                when (mutation) {
                    is StoreMutation.Put -> {
                        records[mutation.key] = PrivateRecord(revision, mutation.schemaVersion, PrivateBytes(mutation.payload.copyForCodec()))
                        result[mutation.key] = revision
                    }
                    is StoreMutation.Delete -> { records.remove(mutation.key); result[mutation.key] = null }
                }
            }
            commits += mutations.toList()
            return PortResult.Value(result)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("Repository cannot activate or erase identities")
    }

    companion object {
        private var nextOwner = 1
        private const val ID = "123e4567-e89b-12d3-a456-426614174abc"
        private const val ID2 = "123e4567-e89b-12d3-a456-426614174abd"
        private const val VERSION = "123e4567-e89b-12d3-a456-426614174080"
        private const val RECIPE = "123e4567-e89b-12d3-a456-426614174081"
        private const val INGREDIENT = "123e4567-e89b-12d3-a456-426614174082"
        private const val SOURCE = "123e4567-e89b-12d3-a456-426614174083"
        private const val GRANT = "123e4567-e89b-12d3-a456-426614174084"
        private const val START = 1_800_000_000_000L
        private val INDEX = RecordKey("feedme.kitchen.saved.index", "v1")
        private fun metadataKey(id: String) = RecordKey("feedme.kitchen.saved.metadata", id)
        private fun bodyKey(id: String) = RecordKey("feedme.kitchen.saved.body", id)
        private fun uuid(value: Int) = "123e4567-e89b-12d3-a456-${value.toString().padStart(12, '0')}"
        private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
        private fun reply(raw: String, etag: String? = "\"1\""): PortResult<ApiReply> =
            PortResult.Value(ApiReply(200, bytes(raw), etag = etag, contentType = "application/json"))
        private fun problem(code: String, bodyStatus: Int = 410): PortResult<ApiReply> = PortResult.Value(ApiReply(410,
            bytes("""{"type":"https://example.test/problems/content","title":"Content unavailable","status":$bodyStatus,"code":"$code","traceId":"test-trace"}"""),
            contentType = "application/problem+json"))
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private fun saved(id: String = ID, version: String = "1", recipeVersion: String = "1", reviewStatus: String = "published",
            quantity: String = "1.2500", title: String = "Private lunch", sourceType: String = "catalog", provenance: Boolean = false,
            recalled: Boolean = false): String = buildJsonObject {
            put("id", id); put("version", Json.parseToJsonElement(version)); put("title", title)
            put("createdAt", "2026-09-13T07:00:00Z"); put("updatedAt", "2026-09-13T08:00:00Z")
            put("sourceType", sourceType); put("recalled", recalled); put("contentLicense", "privateCopyOnly")
            if (provenance) { put("sourcePostId", SOURCE); put("grantId", GRANT); put("creatorLabel", "Private creator") }
            put("snapshot", buildJsonObject {
                put("id", VERSION); put("recipeId", RECIPE); put("version", Json.parseToJsonElement(recipeVersion))
                put("createdAt", "2026-09-13T07:00:00Z"); put("updatedAt", "2026-09-13T08:00:00Z")
                put("title", "Pinned recipe"); put("reviewStatus", reviewStatus); put("servings", 1)
                put("activeMinutes", 5); put("totalMinutes", 5); put("utensilCount", 1)
                put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl"))))
                put("modes", JsonArray(listOf(JsonPrimitive("cook")))); put("tasteTags", JsonArray(emptyList()))
                put("ingredients", buildJsonArray { add(buildJsonObject {
                    put("ingredientId", INGREDIENT); put("quantity", Json.parseToJsonElement(quantity)); put("unit", "g"); put("optional", false)
                }) })
                put("steps", buildJsonArray { add(buildJsonObject {
                    put("stepId", "step-one"); put("position", 1); put("instruction", "private instruction marker")
                    put("ingredientIds", JsonArray(listOf(JsonPrimitive(INGREDIENT))))
                    put("requiredEquipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("mandatorySafetyStep", false)
                }) })
            })
        }.toString()
    }
}
