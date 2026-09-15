package com.feedme.kitchen

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Adversarial exact-CAS port tests, not native durability or current server authorization. */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedRecipeDeletionTargetTest {
    @Test fun intactDownloadedTargetIsReadOnlyDetachedAndDoesNotDiscloseRecipeOrOwner() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download(etag = "\"0001\"")
        val before = f.records(); val commits = f.store.commits.size
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID.uppercase()))
        assertEquals(ID, target.id); assertEquals("\"0001\"", target.etag)
        value(f.repo.validateLocalDeletion(f.lease, target))
        val raw = target.evidence.encode().copyForCodec()
        val decoded = value(SavedRecipeDeletionEvidence.decode(PrivateBytes(raw)))
        assertContentEquals(raw, decoded.encode().copyForCodec())
        raw.fill(0); assertEquals(ID, decoded.id)
        for (secret in listOf("Private saved title", "Private instruction", "Private creator", INGREDIENT, "actor-secret"))
            assertFalse(decoded.encode().copyForCodec().decodeToString().contains(secret))
        assertEquals("SavedRecipeDeletionTarget(<redacted>)", target.toString())
        assertEquals("SavedRecipeDeletionEvidence(<redacted>)", decoded.toString())
        assertEquals(before, f.records()); assertEquals(commits, f.store.commits.size); assertTrue(f.calls.isEmpty())
    }

    @Test fun learnedRecallHidesBodyButExactLocalRemovalNeedsNoContentGet() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        value(f.repo.observeDeleteReply(f.lease, savedWire(), problem()))
        val hidden = value(f.repo.read(f.lease, ID))!!
        assertEquals(SavedRecipeAvailability.RECALLED, hidden.availability); assertNull(hidden.savedRecipe)
        val recallRecords = f.records().filterKeys { it.collection == "feedme.kitchen.recall" }
        assertTrue(recallRecords.isNotEmpty())
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID))
        value(f.repo.validateLocalDeletion(f.lease, target))
        val changes = value(f.repo.prepareDeleteReceipt(f.lease, COMMAND, target.evidence, deleted()))
        value(f.store.commit(f.scope, changes.mutations))
        assertNull(value(f.repo.read(f.lease, ID)))
        assertEquals(recallRecords, f.records().filterKeys { it.collection == "feedme.kitchen.recall" })
        assertTrue(f.calls.isEmpty())
    }

    @Test fun attributionRedactionDoesNotExposeOldBodyOrPreventExactOriginalEtagRemoval() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download(saved(provenance = true))
        f.reply = reply(saved(version = "2", provenance = false), "\"2\"")
        value(f.repo.getRemote(f.lease, ID)); f.calls.clear()
        val hidden = value(f.repo.read(f.lease, ID))!!
        assertEquals(SavedRecipeAvailability.UNAVAILABLE, hidden.availability); assertNull(hidden.savedRecipe)
        val before = f.records(); val target = value(f.repo.prepareLocalDeletion(f.lease, ID))
        assertEquals("\"1\"", target.etag)
        val changes = value(f.repo.prepareDeleteReceipt(f.lease, COMMAND, target.evidence, deleted()))
        assertFalse(changes.toString().contains("Private creator"))
        assertEquals(before, f.records()); assertTrue(f.calls.isEmpty())
    }

    @Test fun liveTargetCannotMoveToAnotherRepositoryButStoredEvidenceCanBeRevalidated() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID)); val replacement = f.repository()
        failure(FailureReason.STALE_SESSION, replacement.validateLocalDeletion(f.lease, target))
        val decoded = value(SavedRecipeDeletionEvidence.decode(target.evidence.encode()))
        value(replacement.validateLocalDeletion(f.lease, decoded))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun foreignStoreCannotUseLiveTargetEvenWithIdenticalScopeAndRecordBytes() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID))
        val other = KitchenReliabilityStore(f.scope)
        f.store.records.forEach { (key, record) -> other.seed(key, record.revision, record.schemaVersion, record.payload) }
        val repository = SavedRecipeRepository(f.scope, other, f.boundary, f.dispatcher, f.clock, f.transport)
        failure(FailureReason.STALE_SESSION, repository.validateLocalDeletion(f.lease, target))
        assertTrue(other.commits.isEmpty()); assertTrue(f.calls.isEmpty())
    }

    @Test fun foreignScopeEvidenceFailsEvenWhenAllLocalRecordsAreCopiedExactly() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val evidence = value(f.repo.prepareLocalDeletion(f.lease, ID)).evidence
        for (scope in listOf(StorageScope("foreign", ActorKind.ACCOUNT, "actor-secret"),
            StorageScope(f.scope.environment, ActorKind.ACCOUNT, "foreign"),
            StorageScope(f.scope.environment, ActorKind.GUEST, f.scope.actorId))) {
            val other = Fixture(f.dispatcher, scope)
            f.store.records.forEach { (key, record) -> other.store.seed(key, record.revision, record.schemaVersion, record.payload) }
            failure(FailureReason.STALE_SESSION, other.repo.validateLocalDeletion(other.lease, evidence))
            assertTrue(other.calls.isEmpty()); assertTrue(other.store.commits.isEmpty())
        }
    }

    @Test fun leaseReplacementNeverRevivesOriginalLiveTargetOrOldLease() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID)); val next = f.boundary.activate(f.scope)
        failure(FailureReason.STALE_SESSION, f.repo.validateLocalDeletion(f.lease, target.evidence))
        failure(FailureReason.STALE_SESSION, f.repo.validateLocalDeletion(next, target))
        // Read-only revalidation under a new exact owner is possible; it supplies NO new consent.
        value(f.repo.validateLocalDeletion(next, target.evidence))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun absentLocalBodyNeverFallsBackToGetOrAcceptsDisplayedIdAsTarget() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        failure(FailureReason.NOT_FOUND, f.repo.prepareLocalDeletion(f.lease, ID))
        failure(FailureReason.INVALID_DATA, f.repo.prepareLocalDeletion(f.lease, "not-an-id"))
        assertTrue(f.calls.isEmpty()); assertTrue(f.store.commits.isEmpty())
    }

    @Test fun missingEtagAndDamagedBodyDigestCannotMintTarget() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val records = f.store.records.toMap()
        for ((key, transform) in listOf<Pair<RecordKey, (JsonObject) -> JsonObject>>(
            metaKey(ID) to { JsonObject(it + ("etag" to JsonNull)) },
            metaKey(ID) to { JsonObject(it + ("digest" to JsonPrimitive("0".repeat(64)))) },
            bodyKey(ID) to { JsonObject(it + ("title" to JsonPrimitive("Replaced private title"))) },
        )) {
            f.store.records.clear(); f.store.records.putAll(records)
            f.rewrite(key, transform = transform)
            failure(FailureReason.INVALID_DATA, f.repo.prepareLocalDeletion(f.lease, ID))
        }
        assertTrue(f.calls.isEmpty())
    }

    @Test fun missingRecordsWrongSchemasAndInconsistentIndexCannotMintTarget() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val original = f.store.records.toMap()
        for (key in listOf(bodyKey(ID), metaKey(ID), INDEX)) {
            f.store.records.clear(); f.store.records.putAll(original); f.store.records.remove(key)
            failure(FailureReason.INVALID_DATA, f.repo.prepareLocalDeletion(f.lease, ID))
            f.store.records.clear(); f.store.records.putAll(original)
            val record = f.store.records.getValue(key)
            f.store.records[key] = PrivateRecord(record.revision, 2, record.payload)
            failure(FailureReason.INVALID_DATA, f.repo.prepareLocalDeletion(f.lease, ID))
        }
        assertTrue(f.calls.isEmpty())
    }

    @Test fun decoderRejectsExtraMissingDuplicateNoncanonicalAndUnboundedFields() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val raw = value(f.repo.prepareLocalDeletion(f.lease, ID)).evidence.encode().copyForCodec().decodeToString()
        val document = Json.parseToJsonElement(raw).jsonObject
        val invalid = listOf("", " "+raw, raw+"\n", raw.replaceFirst("{", "{\"schemaVersion\":1,"),
            JsonObject(document - "bodyDigest").toString(), JsonObject(document + ("title" to JsonPrimitive("Private"))).toString(),
            JsonObject(document + ("schemaVersion" to JsonPrimitive(2))).toString(),
            JsonObject(document + ("bodyRevision" to JsonPrimitive("1"))).toString(),
            JsonObject(document + ("bodyRevision" to JsonPrimitive(0))).toString(),
            JsonObject(document + ("bodyRevision" to Json.parseToJsonElement("9223372036854775808"))).toString(),
            JsonObject(document + ("etag" to JsonPrimitive("W/\"1\""))).toString(),
            JsonObject(document + ("etag" to JsonPrimitive("\"2\""))).toString(),
            JsonObject(document + ("id" to JsonPrimitive(ID.uppercase()))).toString(),
            "x".repeat(SavedRecipeDeletionEvidence.MAX_BYTES + 1))
        for (text in invalid) failure(FailureReason.INVALID_DATA, SavedRecipeDeletionEvidence.decode(bytes(text)))
        failure(FailureReason.INVALID_DATA, SavedRecipeDeletionEvidence.decode(PrivateBytes(byteArrayOf(0xc3.toByte(), 0x28))))
    }

    @Test fun longRevisionsAndOriginalNumericEtagSpellingAreNotRounded() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download(saved(version = "9007199254740993"), "\"09007199254740993\"")
        for (key in listOf(bodyKey(ID), metaKey(ID), INDEX)) {
            val record = f.store.records.getValue(key)
            f.store.seed(key, 9007199254740993L, record.schemaVersion, record.payload)
        }
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID))
        assertEquals("\"09007199254740993\"", target.etag)
        val evidence = value(SavedRecipeDeletionEvidence.decode(target.evidence.encode()))
        value(f.repo.validateLocalDeletion(f.lease, evidence))
        val changes = value(f.repo.prepareDeleteReceipt(f.lease, COMMAND, evidence, deleted()))
        assertEquals(3, changes.mutations.count { it.expectedRevision == 9007199254740993L })
        assertEquals("9007199254740993", Json.parseToJsonElement(changes.mutations.filterIsInstance<StoreMutation.Put>()
            .single { it.key == tombstoneKey(ID) }.payload.copyForCodec().decodeToString()).jsonObject.getValue("version").jsonPrimitive.content)
    }

    @Test fun structurallyValidChangedEvidenceCannotSelectOtherRecordsOrPreconditions() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val original = value(f.repo.prepareLocalDeletion(f.lease, ID)).evidence
        for ((field, value) in listOf("bodyDigest" to JsonPrimitive("0".repeat(64)), "metadataDigest" to JsonPrimitive("1".repeat(64)),
            "indexDigest" to JsonPrimitive("2".repeat(64)), "bodyRevision" to JsonPrimitive(2),
            "metadataRevision" to JsonPrimitive(2), "indexRevision" to JsonPrimitive(2), "recipeVersionId" to JsonPrimitive(OTHER))) {
            val altered = altered(original, field, value)
            failure(FailureReason.CONFLICT, f.repo.validateLocalDeletion(f.lease, altered))
        }
        failure(FailureReason.STALE_SESSION, f.repo.validateLocalDeletion(f.lease, altered(original, "scopeDigest", JsonPrimitive("0".repeat(64)))))
        failure(FailureReason.NOT_FOUND, f.repo.validateLocalDeletion(f.lease, altered(original, "id", JsonPrimitive(OTHER))))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun samePayloadAtNewRevisionInvalidatesOriginalTargetForEachExactRecord() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID)); val original = f.store.records.toMap()
        for (key in listOf(bodyKey(ID), metaKey(ID), INDEX)) {
            f.store.records.clear(); f.store.records.putAll(original)
            val record = f.store.records.getValue(key)
            f.store.records[key] = PrivateRecord(record.revision + 1, record.schemaVersion, record.payload)
            failure(FailureReason.CONFLICT, f.repo.validateLocalDeletion(f.lease, target))
            failure(FailureReason.CONFLICT, f.repo.prepareDeleteReceipt(f.lease, COMMAND, target.evidence, deleted()))
        }
    }

    @Test fun newerDownloadDoesNotSilentlyRebaseOldConsentOrEvidence() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID))
        f.download(saved(version = "2"), "\"2\"")
        failure(FailureReason.CONFLICT, f.repo.validateLocalDeletion(f.lease, target))
        failure(FailureReason.CONFLICT, f.repo.prepareDeleteReceipt(f.lease, COMMAND, target.evidence, deleted()))
        assertEquals("\"1\"", target.etag)
        assertEquals("\"2\"", value(f.repo.prepareLocalDeletion(f.lease, ID)).etag)
    }

    @Test fun unrelatedIndexChangeRequiresFreshTargetWithoutDroppingSiblingMembership() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val old = value(f.repo.prepareLocalDeletion(f.lease, ID)); f.download(saved(id = OTHER), id = OTHER)
        failure(FailureReason.CONFLICT, f.repo.validateLocalDeletion(f.lease, old))
        val current = value(f.repo.prepareLocalDeletion(f.lease, ID))
        value(f.store.commit(f.scope, value(f.repo.prepareDeleteReceipt(f.lease, COMMAND, current.evidence, deleted())).mutations))
        assertNotNull(value(f.repo.read(f.lease, OTHER)))
    }

    @Test fun suspendedTargetReadRejectsChangedAtomicBundleRatherThanMintingTornEvidence() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var once = true
        f.store.afterRead = { if (once && f.store.reads.last() == bodyKey(ID)) { once = false; entered.complete(Unit); release.await() } }
        val pending = async { f.repo.prepareLocalDeletion(f.lease, ID) }; runCurrent(); entered.await()
        f.download(saved(version = "2"), "\"2\""); release.complete(Unit)
        failure(FailureReason.CONFLICT, pending.await()); assertTrue(f.calls.isEmpty())
    }

    @Test fun exactRecordRechecksDetectMutationEvenWhenIndexAndRevisionAreUnchanged() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID)); var once = true
        f.store.afterRead = { if (once && f.store.reads.last() == metaKey(ID)) {
            once = false; f.rewrite(metaKey(ID)) { JsonObject(it + ("lastCheckedMillis" to JsonPrimitive(1_800_000_000_001L))) }
        } }
        failure(FailureReason.CONFLICT, f.repo.validateLocalDeletion(f.lease, target))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun cancellationDuringPrivateReadProducesNoTargetOrMutation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val before = f.records(); val commits = f.store.commits.size; val entered = CompletableDeferred<Unit>()
        f.store.afterRead = { entered.complete(Unit); awaitCancellation() }
        val pending = async { f.repo.prepareLocalDeletion(f.lease, ID) }; runCurrent(); entered.await(); pending.cancelAndJoin()
        assertTrue(pending.isCancelled); assertEquals(before, f.records()); assertEquals(commits, f.store.commits.size)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun invalidationDuringPrivateReadNeverReturnsTargetOrReceiptPreparation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val before = f.records(); f.store.afterRead = { f.boundary.clear() }
        failure(FailureReason.STALE_SESSION, f.repo.prepareLocalDeletion(f.lease, ID))
        assertEquals(before, f.records()); assertTrue(f.calls.isEmpty())
    }

    @Test fun exact204PreparationIsDetachedFixedAndLeavesAllRecordsUntilActualQueueCommit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID)); val before = f.records()
        val result = value(f.repo.prepareDeleteReceipt(f.lease, COMMAND, target.evidence, deleted()))
        assertEquals(ID, result.savedRecipeId); assertNull(result.snapshot)
        assertEquals(setOf(bodyKey(ID), metaKey(ID), INDEX, tombstoneKey(ID)), result.mutations.map { it.key }.toSet())
        result.mutations.toMutableList().clear(); assertEquals(4, result.mutations.size)
        assertEquals(before, f.records()); assertTrue(f.calls.isEmpty())
        value(f.store.commit(f.scope, result.mutations)); assertNull(value(f.repo.read(f.lease, ID)))
    }

    @Test fun non204MalformedReceiptsAndInvalidCommandNeverPrepareDeletion() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val evidence = value(f.repo.prepareLocalDeletion(f.lease, ID)).evidence; val before = f.records()
        for (response in listOf(ApiReply(200, null), deleted().copy(body = bytes("{}")), deleted().copy(etag = "\"1\""),
            problem(401, "UNAUTHENTICATED"), problem(403, "FORBIDDEN"), problem(404, "NOT_FOUND"),
            problem(409), problem(412, "PRECONDITION_FAILED")))
            failure(FailureReason.INVALID_DATA, f.repo.prepareDeleteReceipt(f.lease, COMMAND, evidence, response))
        failure(FailureReason.INVALID_DATA, f.repo.prepareDeleteReceipt(f.lease, "bad-command", evidence, deleted()))
        assertEquals(before, f.records()); assertTrue(f.calls.isEmpty())
    }

    @Test fun negativeObserverBindsReplyAndLocalEvidenceWithoutPromotingPositiveAck() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID)); val before = f.records()
        value(f.repo.observeDeleteReply(f.lease, target.evidence, deleted())); assertEquals(before, f.records())
        failure(FailureReason.INVALID_DATA, f.repo.observeDeleteReply(f.lease, target.evidence, problem().copy(contentType = "text/plain")))
        assertEquals(before, f.records())
        value(f.repo.observeDeleteReply(f.lease, target.evidence, problem()))
        assertEquals(SavedRecipeAvailability.RECALLED, value(f.repo.read(f.lease, ID))!!.availability)
        value(f.repo.validateLocalDeletion(f.lease, target))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun repeatedPreparationIsNotAckAndCommittedUnknownApplyCannotBeReconstructed() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID)); val before = f.records()
        val first = value(f.repo.prepareDeleteReceipt(f.lease, COMMAND, target.evidence, deleted()))
        val again = value(f.repo.prepareDeleteReceipt(f.lease, COMMAND, target.evidence, deleted()))
        assertEquals(first.mutations.map { it.key to it.expectedRevision }, again.mutations.map { it.key to it.expectedRevision })
        assertEquals(before, f.records())
        f.store.receipt = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        failure(FailureReason.OUTCOME_UNKNOWN, f.store.commit(f.scope, first.mutations))
        assertNull(value(f.repo.read(f.lease, ID)))
        failure(FailureReason.CONFLICT, f.repo.prepareLocalDeletion(f.lease, ID))
        failure(FailureReason.CONFLICT, f.repo.validateLocalDeletion(f.lease, target.evidence))
        failure(FailureReason.CONFLICT, f.repo.prepareDeleteReceipt(f.lease, COMMAND, target.evidence, deleted()))
        failure(FailureReason.CONFLICT, f.repository().prepareDeleteReceipt(f.lease, COMMAND, target.evidence, deleted()))
    }

    @Test fun removalPreservesOtherCopyCookingRecordsAndRecipeRecallMarkers() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.download(); f.download(saved(id = OTHER), id = OTHER)
        val cooking = RecordKey("feedme.kitchen.cooking.body", COOKING)
        f.store.seed(cooking, payload = bytes("independently owned cooking pin"))
        value(f.repo.observeDeleteReply(f.lease, savedWire(), problem()))
        val kept = f.records().filterKeys { it !in setOf(bodyKey(ID), metaKey(ID), INDEX) }
        val target = value(f.repo.prepareLocalDeletion(f.lease, ID))
        value(f.store.commit(f.scope, value(f.repo.prepareDeleteReceipt(f.lease, COMMAND, target.evidence, deleted())).mutations))
        assertEquals(kept, f.records().filterKeys { it in kept.keys })
        val sibling = value(f.repo.read(f.lease, OTHER))!!
        assertEquals(SavedRecipeAvailability.RECALLED, sibling.availability); assertNull(sibling.savedRecipe)
        assertNotNull(value(f.repo.prepareLocalDeletion(f.lease, OTHER)))
    }

    @Test fun staleGetPageAndDownloadCannotResurrectDeletedIdentityAndNewIdCanBeSaved() = runTest {
        for (operation in listOf("get", "page", "download")) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.download()
            val target = value(f.repo.prepareLocalDeletion(f.lease, ID)); val release = CompletableDeferred<Unit>()
            f.exchange = { release.await(); if (operation == "page") page() else reply(saved()) }
            val pending = async { when (operation) {
                "get" -> f.repo.getRemote(f.lease, ID)
                "page" -> f.repo.listRemote(f.lease)
                else -> f.repo.download(f.lease, ID)
            } }; runCurrent()
            value(f.store.commit(f.scope, value(f.repo.prepareDeleteReceipt(f.lease, COMMAND, target.evidence, deleted())).mutations))
            release.complete(Unit); failure(FailureReason.CONFLICT, pending.await())
            assertNull(value(f.repo.read(f.lease, ID)))
            f.exchange = { f.reply }
            // A later authorized Save receipt uses a new saved identity; neither tombstone nor
            // an old GET is permission to reuse the deleted ID. This is queue-batch preparation.
            val plan = plan(); val request = SaveRecipeRequest(planId = PlanId(plan.id.value))
            value(f.store.commit(f.scope, value(f.repo.prepareSave(f.lease, COOKING, plan, request)).mutations))
            value(f.store.commit(f.scope, value(f.repo.prepareSaveReceipt(f.lease, COOKING, plan, request,
                reply(saved(id = OTHER)).copy(status = 201))).mutations))
            assertNotNull(value(f.repo.read(f.lease, OTHER)))
            failure(FailureReason.CONFLICT, f.repo.download(f.lease, ID))
        }
    }

    private class Fixture(val dispatcher: CoroutineDispatcher,
        val scope: StorageScope = StorageScope("local-deletion", ActorKind.ACCOUNT, "actor-secret")) {
        val boundary = SessionBoundary(); val lease = boundary.activate(scope)
        val store = KitchenReliabilityStore(scope); val calls = mutableListOf<ApiCall>()
        val clock = EpochClock { 1_800_000_000_000L }
        var reply = reply(saved()); var exchange: suspend () -> ApiReply = { reply }
        val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                calls += call; return PortResult.Value(exchange())
            }
        }
        val repo = repository()
        fun repository() = SavedRecipeRepository(scope, store, boundary, dispatcher, clock, transport)
        suspend fun download(body: String = saved(), etag: String = "\"1\"", id: String = ID) {
            reply = reply(body, etag); value(repo.download(lease, id)); calls.clear()
        }
        fun records() = store.records.mapValues { (_, r) -> Triple(r.revision, r.schemaVersion, r.payload.copyForCodec().decodeToString()) }
        fun rewrite(key: RecordKey, transform: (JsonObject) -> JsonObject) {
            val record = store.records.getValue(key)
            val document = Json.parseToJsonElement(record.payload.copyForCodec().decodeToString()).jsonObject
            store.records[key] = PrivateRecord(record.revision, record.schemaVersion, bytes(transform(document).toString()))
        }
    }

    private companion object {
        const val ID = "123e4567-e89b-12d3-a456-426614174001"
        const val OTHER = "123e4567-e89b-12d3-a456-426614174002"
        const val VERSION = "123e4567-e89b-12d3-a456-426614174003"
        const val RECIPE = "123e4567-e89b-12d3-a456-426614174004"
        const val INGREDIENT = "123e4567-e89b-12d3-a456-426614174005"
        const val COMMAND = "123e4567-e89b-12d3-a456-426614174006"
        const val COOKING = "123e4567-e89b-12d3-a456-426614174007"
        val INDEX = RecordKey("feedme.kitchen.saved.index", "v1")
        fun bodyKey(id: String) = RecordKey("feedme.kitchen.saved.body", id)
        fun metaKey(id: String) = RecordKey("feedme.kitchen.saved.metadata", id)
        fun tombstoneKey(id: String) = RecordKey("feedme.kitchen.saved.deleted", id)
        fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        fun deleted() = ApiReply(204, null)
        fun reply(body: String, etag: String = "\"1\"") = ApiReply(200, bytes(body), etag = etag, contentType = "application/json")
        fun savedWire() = SavedRecipeWire.from(WireDocument.decode(saved().encodeToByteArray()))
        fun plan() = PlanWire.from(WireDocument.decode(buildJsonObject {
            put("id", COOKING); put("version", 1); put("createdAt", "2026-09-14T07:00:00Z"); put("updatedAt", "2026-09-14T07:00:00Z")
            put("status", "ready"); put("mode", "cook"); put("recipeVersionId", VERSION); put("catalogRevision", "pinned")
            put("missingIngredients", JsonArray(emptyList())); put("changes", JsonArray(emptyList())); put("reasons", JsonArray(emptyList()))
            put("constraints", buildJsonObject {
                put("ingredientIds", JsonArray(listOf(JsonPrimitive(INGREDIENT)))); put("energy", "little"); put("servings", 1)
                put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("hardExcludedIngredientIds", JsonArray(emptyList()))
            })
            put("recipeSnapshot", Json.parseToJsonElement(saved()).jsonObject.getValue("snapshot"))
        }.toString().encodeToByteArray()))
        fun altered(evidence: SavedRecipeDeletionEvidence, field: String, item: JsonElement): SavedRecipeDeletionEvidence {
            val doc = Json.parseToJsonElement(evidence.encode().copyForCodec().decodeToString()).jsonObject
            return value(SavedRecipeDeletionEvidence.decode(bytes(JsonObject(doc + (field to item)).toString())))
        }
        fun problem(status: Int = 409, code: String = "RECIPE_RECALLED") = ApiReply(status,
            bytes("""{"type":"https://example.test/problem","title":"Unavailable","status":$status,"code":"$code","traceId":"test"}"""),
            contentType = "application/problem+json")
        fun page() = ApiReply(200, bytes(buildJsonObject {
            put("items", JsonArray(listOf(Json.parseToJsonElement(saved())))); put("nextCursor", JsonNull)
            put("serverTime", "2026-09-14T08:00:00Z")
        }.toString()), contentType = "application/json")
        fun saved(id: String = ID, version: String = "1", provenance: Boolean = false) = buildJsonObject {
            put("id", id); put("version", Json.parseToJsonElement(version)); put("createdAt", "2026-09-14T07:00:00Z")
            put("updatedAt", "2026-09-14T08:00:00Z"); put("title", "Private saved title"); put("sourceType", "ownPlan")
            put("recalled", false); put("contentLicense", "privateCopyOnly")
            if (provenance) { put("creatorLabel", "Private creator"); put("sourcePostId", OTHER); put("grantId", RECIPE) }
            put("snapshot", buildJsonObject {
                put("id", VERSION); put("recipeId", RECIPE); put("version", 1)
                put("createdAt", "2026-09-14T07:00:00Z"); put("updatedAt", "2026-09-14T07:00:00Z")
                put("title", "Private recipe title"); put("reviewStatus", "published"); put("servings", 1)
                put("activeMinutes", 5); put("totalMinutes", 5); put("utensilCount", 1)
                put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("modes", JsonArray(listOf(JsonPrimitive("cook"))))
                put("tasteTags", JsonArray(emptyList()))
                put("ingredients", buildJsonArray { add(buildJsonObject {
                    put("ingredientId", INGREDIENT); put("quantity", Json.parseToJsonElement("2.7500")); put("unit", "g"); put("optional", false)
                }) })
                put("steps", buildJsonArray { add(buildJsonObject {
                    put("stepId", "first"); put("position", 1); put("instruction", "Private instruction")
                    put("ingredientIds", JsonArray(listOf(JsonPrimitive(INGREDIENT)))); put("requiredEquipmentIds", JsonArray(listOf(JsonPrimitive("bowl"))))
                    put("mandatorySafetyStep", false)
                }) })
            })
        }.toString()
    }
}
