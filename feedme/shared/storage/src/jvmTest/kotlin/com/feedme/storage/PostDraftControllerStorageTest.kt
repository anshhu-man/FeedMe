package com.feedme.storage

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.*
import com.feedme.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Real EncryptedStateDatabase, actual PrivateSessionRuntime/access and shared controller bundle.
 * RuntimeFixture's credential verifier/JCA vault and this canonical service are TEST fixtures,
 * not production authentication, HTTP authority, native vaults or physical-power-loss evidence.
 * Fault hooks sit before/after actual SQLite COMMIT; no controller, leased store or ACK is mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PostDraftControllerStorageTest {
    @Test fun localDraftUsesRealEncryptedCasAndAcceptsActualRevisionAfterNativeTombstone() = runTest { fixture { f ->
        val first = value(f.access.store.commit(f.access.scope, listOf(StoreMutation.Put(f.key, null, 1, bytes("removed-fixture-row")))))
        val priorRevision = first.getValue(f.key)!!
        value(f.access.store.commit(f.access.scope, listOf(StoreMutation.Delete(f.key, priorRevision))))
        assertNull(value(f.access.store.read(f.access.scope, f.key)))
        val created = value(f.drafts.newLocalDraft(CAPTION, ALT))
        assertEquals(priorRevision + 2, f.record().revision); assertTrue(created.selected!!.localAcknowledged)
        assertEquals(CAPTION, created.selected!!.caption); assertFalse(created.selected!!.serverAssociated)
        val revision = f.record().revision
        value(f.drafts.editCaption(created.selected!!.clientDraftId, "Changed local caption"))
        assertEquals(revision + 1, f.record().revision); assertEquals(ALT, f.drafts.states.value.selected!!.altText)
        assertTrue(f.calls.isEmpty()); assertTrue(f.rt.cancelled.isEmpty())
    } }
    @Test fun encryptedSqlFilesNeverContainDraftTextStableIdentityOrOriginalRequestMarkers() = runTest { fixture { f ->
        val local = value(f.drafts.newLocalDraft(CAPTION, ALT)).selected!!
        f.loseAfter(2); rejected(FailureReason.OUTCOME_UNKNOWN, f.drafts.saveExplicitly())
        val original = command(f.record()); assertEquals("createPostDraft", text(original, "operation"))
        val id = text(original, "id"); val request = value(f.access.store.read(f.access.scope, requestKey(id)))!!
        assertContentEquals(text(original, "body").encodeToByteArray(), request.payload.copyForCodec())
        assertTrue(f.calls.isEmpty()); f.closeControllers(); f.rt.closeRuntimeAndStores()
        f.rt.assertNoPlaintext(listOf(CAPTION, ALT, local.clientDraftId, id, f.access.originBinding,
            "createPostDraft", "mealflow.post-drafts.v1", "access-runtime-secret", "refresh-runtime-secret"))
    } }
    @Test fun beforeAndAfterActualSqlCommitFailuresKeepUnacknowledgedTextAcrossSameLeaseReplacement() = runTest {
        for (after in listOf(false, true)) fixture { f ->
            if (after) f.loseAfter(1) else f.loseBefore(1)
            rejected(if (after) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE, f.drafts.newLocalDraft(CAPTION, ALT))
            val local = f.drafts.states.value.selected!!; assertFalse(local.localAcknowledged)
            assertEquals(after, value(f.access.store.read(f.access.scope, f.key)) != null)
            f.reopenControllers(); value(f.drafts.restoreLocal()); value(f.drafts.openLocal(local.clientDraftId))
            assertEquals(CAPTION, f.drafts.states.value.selected!!.caption); assertFalse(f.drafts.states.value.selected!!.localAcknowledged)
            rejected(FailureReason.CONFLICT, f.drafts.saveExplicitly()); assertTrue(f.calls.isEmpty())
            val retained = value(f.drafts.editCaption(local.clientDraftId, CAPTION))
            assertTrue(retained.selected!!.localAcknowledged); assertEquals(ALT, retained.selected!!.altText)
            assertEquals(local.clientDraftId, text(items(document(f.record()), "locals").single(), "id")); assertEquals(1, f.idCalls)
        }
    }
    @Test fun actualCiphertextCorruptionAfterCommitCannotPassControllerReadbackAcknowledgement() = runTest { fixture { f ->
        val before = f.recordTags()
        f.afterCommit = { count -> if (count == 1) {
            val created = f.recordTags() - before; assertEquals(1, created.size)
            f.rt.dataConnection.prepare("UPDATE feedme_records SET payload=zeroblob(length(payload)) WHERE record_tag=?").use {
                it.bindText(1, created.single()); it.step()
            }
        } }
        rejected(FailureReason.STORAGE_FAILURE, f.drafts.newLocalDraft(CAPTION, ALT))
        assertEquals(CAPTION, f.drafts.states.value.selected!!.caption); assertFalse(f.drafts.states.value.selected!!.localAcknowledged)
        rejected(FailureReason.STORAGE_FAILURE, f.access.store.read(f.access.scope, f.key))
        assertTrue(f.calls.isEmpty()); assertFalse(f.drafts.states.value.serverAcknowledged)
    } }
    @Test fun realConcurrentDomainChangeCannotBeOverwrittenByPreviouslyStagedLocalEdit() = runTest { fixture { f ->
        val local = value(f.drafts.newLocalDraft("Base private text", ALT)).selected!!
        val before = f.record(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.rt.afterControlRead = { f.rt.afterControlRead = {}; entered.complete(Unit); release.await() }
        val edit = async { f.drafts.editCaption(local.clientDraftId, "My unacknowledged edit") }; entered.await()
        val changed = before.payload.copyForCodec().decodeToString().replace("Base private text", "Actual competing edit")
        value(f.access.store.commit(f.access.scope, listOf(StoreMutation.Put(f.key, before.revision, before.schemaVersion, bytes(changed)))))
        release.complete(Unit); rejected(FailureReason.CONFLICT, edit.await())
        assertEquals("Actual competing edit", text(items(document(f.record()), "locals").single(), "caption"))
        assertEquals("My unacknowledged edit", f.drafts.states.value.selected!!.caption)
        assertFalse(f.drafts.states.value.selected!!.localAcknowledged); assertTrue(f.calls.isEmpty())
    } }
    @Test fun unknownEnqueueKeepsAtomicDomainRequestAndMetadataUntilExplicitOriginalRetry() = runTest { fixture { f ->
        value(f.drafts.newLocalDraft(CAPTION, ALT)); f.loseAfter(2)
        rejected(FailureReason.OUTCOME_UNKNOWN, f.drafts.saveExplicitly())
        val original = command(f.record()); val id = text(original, "id")
        val body = value(f.access.store.read(f.access.scope, requestKey(id)))!!.payload.copyForCodec()
        val metadata = value(f.access.store.read(f.access.scope, metadataKey(id)))!!
        assertEquals("AWAITING_CONFIRMATION", text(document(metadata), "phase")); assertTrue(f.calls.isEmpty())
        f.reopenControllers(); val restored = value(f.drafts.restoreLocal())
        assertFalse(restored.serverAcknowledged); assertEquals(id, restored.pending!!.commandId); assertTrue(f.calls.isEmpty())
        val applied = value(f.drafts.retryOriginal()); assertTrue(applied.serverAcknowledged); assertNull(applied.pending)
        assertEquals(id, f.calls.single().idempotencyKey!!.use { it }); assertContentEquals(body, f.calls.single().body!!.copyForCodec())
        assertNull(value(f.access.store.read(f.access.scope, requestKey(id))))
        assertEquals("APPLIED", text(document(value(f.access.store.read(f.access.scope, metadataKey(id)))!!), "phase"))
    } }
    @Test fun lostDispatchResponseReopensActualRuntimeAndRetriesExactOriginalWithOneRemoteEffect() = runTest { fixture { f ->
        value(f.drafts.newLocalDraft(CAPTION, ALT)); var first = true
        f.handler = { call -> val result = f.reply(call); if (first) { first = false; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) } else result }
        val initial = value(f.drafts.saveExplicitly()); assertNotNull(initial.pending); assertFalse(initial.serverAcknowledged)
        val originalCall = f.calls.single(); val original = command(f.record()); val id = text(original, "id")
        rejected(FailureReason.CONFLICT, f.drafts.discardUnsent())
        val priorAccess = f.access; f.reopenRuntime()
        assertNotSame(priorAccess.lease, f.access.lease); assertEquals(priorAccess.originBinding, f.access.originBinding)
        assertIs<PortResult.Failure>(priorAccess.store.read(priorAccess.scope, f.key))
        val restored = value(f.drafts.restoreLocal()); assertEquals(id, restored.pending!!.commandId); assertEquals(1, f.calls.size)
        f.time = maxOf(f.time + 60_000, (restored.earliestRetryAtMillis ?: f.time) + 1)
        val recovered = value(f.drafts.retryOriginal()); assertTrue(recovered.serverAcknowledged)
        assertSameCall(originalCall, f.calls.last()); assertEquals(2, f.calls.size); assertEquals(1, f.remoteEffects)
        assertEquals(id, text(field(document(f.record()), "completion"), "command"))
    } }
    @Test fun unknownApplyUsesSameLeaseActualArchiveProofAndFreshCasWithoutAnotherServerMutation() = runTest { fixture { f ->
        value(f.drafts.newLocalDraft(CAPTION, ALT)); f.loseAfter(5)
        rejected(FailureReason.OUTCOME_UNKNOWN, f.drafts.saveExplicitly())
        val before = f.record(); val completion = field(document(before), "completion"); val id = text(completion, "command")
        assertEquals(WireField.Null, document(before).field("command")); assertFalse(f.drafts.states.value.serverAcknowledged)
        val archive = value(f.access.store.read(f.access.scope, metadataKey(id)))!!
        assertEquals("APPLIED", text(document(archive), "phase")); assertNull(value(f.access.store.read(f.access.scope, requestKey(id))))
        f.reopenControllers(); value(f.drafts.restoreLocal()); val result = value(f.drafts.retryOriginal())
        assertTrue(result.serverAcknowledged); assertNull(result.pending); assertEquals(before.revision + 1, f.record().revision)
        assertRecordEquals(archive, value(f.access.store.read(f.access.scope, metadataKey(id)))!!)
        assertEquals(1, f.calls.size); assertEquals(1, f.remoteEffects)
    } }
    @Test fun actualArchivePayloadRevisionOrAbsenceTamperingCannotBeRepairedByMatchingAppliedDomain() = runTest {
        for (damage in listOf("payload", "revision", "missing")) fixture { f ->
            value(f.drafts.newLocalDraft(CAPTION, ALT)); f.loseAfter(5)
            rejected(FailureReason.OUTCOME_UNKNOWN, f.drafts.saveExplicitly())
            val domain = f.record(); val id = text(field(document(domain), "completion"), "command")
            val key = metadataKey(id); val before = value(f.access.store.read(f.access.scope, key))!!
            val change = when (damage) {
                "missing" -> StoreMutation.Delete(key, before.revision)
                "revision" -> StoreMutation.Put(key, before.revision, before.schemaVersion, before.payload)
                else -> StoreMutation.Put(key, before.revision, before.schemaVersion, bytes(before.payload.copyForCodec().decodeToString() + " "))
            }
            value(f.access.store.commit(f.access.scope, listOf(change))); val writes = f.recordCommits
            f.reopenControllers(); value(f.drafts.restoreLocal())
            assertIs<PortResult.Failure>(f.drafts.retryOriginal()); assertFalse(f.drafts.states.value.serverAcknowledged)
            rejected(FailureReason.CONFLICT, f.drafts.discardUnsent())
            assertEquals(writes, f.recordCommits); assertRecordEquals(domain, f.record()); assertEquals(1, f.calls.size)
        }
    }
    @Test fun repeatedLostFinalizationAcknowledgementRetainsProofForAnotherFreshChangedCas() = runTest { fixture { f ->
        value(f.drafts.newLocalDraft(CAPTION, ALT)); f.loseAfter(5)
        rejected(FailureReason.OUTCOME_UNKNOWN, f.drafts.saveExplicitly())
        val first = f.record().revision; f.loseAfter(1)
        rejected(FailureReason.OUTCOME_UNKNOWN, f.drafts.retryOriginal())
        assertEquals(first + 1, f.record().revision); assertFalse(f.drafts.states.value.serverAcknowledged)
        val result = value(f.drafts.retryOriginal())
        assertTrue(result.serverAcknowledged); assertEquals(first + 2, f.record().revision); assertEquals(1, f.calls.size)
    } }
    @Test fun reconstructedRuntimeTreatsAppliedCompletionAsHistoricalWithoutManufacturingProof() = runTest {
        for (lost in listOf(false, true)) fixture { f ->
            value(f.drafts.newLocalDraft(CAPTION, ALT)); if (lost) f.loseAfter(5)
            if (lost) rejected(FailureReason.OUTCOME_UNKNOWN, f.drafts.saveExplicitly()) else assertTrue(value(f.drafts.saveExplicitly()).serverAcknowledged)
            val before = f.record(); val originalOrigin = f.access.originBinding
            f.reopenRuntime(); assertEquals(originalOrigin, f.access.originBinding)
            val state = value(f.drafts.restoreLocal())
            assertTrue(state.historical); assertFalse(state.serverAcknowledged); assertEquals("HISTORICAL_COMPLETION", state.pending!!.phase)
            assertFalse(state.pending!!.canRetry); val writes = f.recordCommits
            rejected(FailureReason.CONFLICT, f.drafts.retryOriginal())
            assertRecordEquals(before, f.record()); assertEquals(writes, f.recordCommits); assertEquals(1, f.calls.size)
        }
    }
    @Test fun delete204AtomicallyRemovesExactDraftContentAndKeepsTerminalIdentityAndUnrelatedRows() = runTest { fixture { f ->
        val local = value(f.drafts.newLocalDraft(CAPTION, ALT)).selected!!; value(f.drafts.saveExplicitly())
        val unrelatedKey = RecordKey("unrelated-test-domain", "must-remain")
        value(f.access.store.commit(f.access.scope, listOf(StoreMutation.Put(unrelatedKey, null, 4, bytes("unrelated owned data")))))
        val unrelated = value(f.access.store.read(f.access.scope, unrelatedKey))!!
        val prepared = value(f.drafts.prepareServerDiscard()).discardConfirmation!!
        val result = value(f.drafts.confirmServerDiscard(prepared))
        assertTrue(result.serverAcknowledged); assertTrue(result.localDrafts.isEmpty()); assertNull(result.selected)
        val body = document(f.record()); assertTrue(items(body, "locals").isEmpty())
        assertEquals(local.clientDraftId, text(items(body, "tombstones").single(), "client"))
        assertFalse(f.record().payload.copyForCodec().decodeToString().contains(CAPTION))
        val call = f.calls.last(); assertEquals("deletePostDraft", call.operationId); assertEquals("\"1\"", call.ifMatch); assertNull(call.body)
        assertRecordEquals(unrelated, value(f.access.store.read(f.access.scope, unrelatedKey))!!)
        assertTrue(f.rt.cancelled.isEmpty()); assertEquals(1, f.rt.credentials.creates); assertEquals(0, f.rt.credentials.retired.size)
    } }
    @Test fun localTerminalIdentityRemainsInActualEncryptedRecordBeyondSevenDaysAndRejectsReuse() = runTest { fixture { f ->
        val local = value(f.drafts.newLocalDraft(CAPTION)).selected!!
        value(f.drafts.discardLocal(local.clientDraftId, local.localRevision)); val removed = f.record()
        f.time += 30L * 86_400_000; f.idOverride = local.clientDraftId
        rejected(FailureReason.CONFLICT, f.drafts.newLocalDraft("Never recycle this ID"))
        assertRecordEquals(removed, f.record()); assertTrue(items(document(f.record()), "locals").isEmpty())
        assertEquals(local.clientDraftId, text(items(document(f.record()), "tombstones").single(), "client")); assertTrue(f.calls.isEmpty())
    } }
    @Test fun cancellationBeforeStorageAndAfterRealCommitNeverReturnsFalseLocalAcknowledgement() = runTest {
        for (after in listOf(false, true)) fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val suspendRead: suspend () -> Unit = { f.rt.afterControlRead = {}; entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            if (after) f.afterCommit = { count -> if (count == 1) f.rt.afterControlRead = suspendRead }
            else f.rt.afterControlRead = suspendRead
            val task = async { f.drafts.newLocalDraft(CAPTION, ALT) }; entered.await()
            val actual = value(f.rt.data.inspectRecord(f.access.scope, f.key)).record
            assertEquals(after, actual != null); task.cancel(); release.complete(Unit)
            assertFailsWith<CancellationException> { task.await() }
            f.reopenControllers(); val state = value(f.drafts.restoreLocal())
            if (after) { assertEquals(CAPTION, state.localDrafts.single().caption); assertFalse(state.localDrafts.single().localAcknowledged) }
            else assertTrue(state.localDrafts.isEmpty())
            assertFalse(state.serverAcknowledged); assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun cancellationAtActualReadbackReturnRetainsUnacknowledgedLocalDraftAcrossReplacement() = runTest { fixture { f ->
        lateinit var task: Deferred<PortResult<PostDraftState>>
        f.afterCommit = { count -> if (count == 1) f.rt.dataConnection.afterNextReadCommit = { task.cancel() } }
        task = async(start = CoroutineStart.LAZY) { f.drafts.newLocalDraft(CAPTION, ALT) }; task.start()
        assertFailsWith<CancellationException> { task.await() }
        assertNotNull(value(f.access.store.read(f.access.scope, f.key)))
        f.reopenControllers(); val state = value(f.drafts.restoreLocal())
        assertEquals(CAPTION, state.localDrafts.single().caption); assertFalse(state.localDrafts.single().localAcknowledged)
        assertFalse(state.serverAcknowledged); assertTrue(f.calls.isEmpty())
    } }
    @Test fun armedCallerReturnCannotAcknowledgeAppliedSqlAfterBackReplacementOrCallerCancellation() = runTest {
        for (change in listOf("back", "replace", "cancel")) fixture { f ->
            value(f.drafts.newLocalDraft(CAPTION, ALT))
            val scheduled = StandardTestDispatcher(testScheduler); val held = ArrayDeque<Pair<CoroutineContext, Runnable>>(); var hold = false
            val caller = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (hold) held.addLast(context to block) else scheduled.dispatch(context, block)
            } }
            val task = async(caller) { hold = true; f.drafts.saveExplicitly() }
            runCurrent(); assertEquals(1, held.size)
            val first = held.removeFirst(); scheduled.dispatch(first.first, first.second); runCurrent(); assertEquals(1, held.size)
            assertNotNull(field(document(f.record()), "completion")); assertFalse(f.drafts.states.value.serverAcknowledged)
            when (change) { "back" -> value(f.drafts.back()); "replace" -> f.reopenControllers(); else -> task.cancel() }
            hold = false; while (held.isNotEmpty()) { val (context, block) = held.removeFirst(); scheduled.dispatch(context, block) }
            if (change == "cancel") assertFailsWith<CancellationException> { task.await() } else rejected(FailureReason.STALE_SESSION, task.await())
            assertFalse(f.drafts.states.value.serverAcknowledged)
            assertTrue(value(f.drafts.retryOriginal()).serverAcknowledged); assertEquals(1, f.calls.size)
        }
    }
    @Test fun actualAccountScopeAndOriginBindingRejectCrossScopeReadsAndReencryptedForeignPayload() = runTest { fixture { f ->
        value(f.drafts.newLocalDraft(CAPTION, ALT)); val original = f.record()
        val otherScope = f.access.scope.copy(actorId = "another-synthetic-owner")
        rejected(FailureReason.STALE_SESSION, f.access.store.read(otherScope, f.key))
        rejected(FailureReason.STALE_SESSION, f.access.store.commit(otherScope, listOf(StoreMutation.Put(f.key, null, 1, bytes("foreign")))))
        val foreignOrigin = id(9900); val foreignKey = RecordKey(f.key.collection, foreignOrigin)
        value(f.access.store.commit(f.access.scope, listOf(StoreMutation.Put(foreignKey, null, original.schemaVersion, original.payload))))
        f.reopenControllers(); assertEquals(CAPTION, value(f.drafts.restoreLocal()).localDrafts.single().caption)
        val corrupted = original.payload.copyForCodec().decodeToString().replace(f.access.originBinding, foreignOrigin)
        value(f.access.store.commit(f.access.scope, listOf(StoreMutation.Put(f.key, original.revision, original.schemaVersion, bytes(corrupted)))))
        rejected(FailureReason.INVALID_DATA, f.drafts.restoreLocal()); assertTrue(f.calls.isEmpty())
        assertContentEquals(original.payload.copyForCodec(), value(f.access.store.read(f.access.scope, foreignKey))!!.payload.copyForCodec())
    } }
    @Test fun independentActualRuntimeAndStoreCannotBorrowAnotherLeasesUnacknowledgedText() = runTest { fixture { first ->
        first.loseAfter(1); rejected(FailureReason.OUTCOME_UNKNOWN, first.drafts.newLocalDraft(CAPTION, ALT))
        val second = Fixture(StandardTestDispatcher(testScheduler))
        try {
            second.open(); assertNotSame(first.access.lease, second.access.lease); assertNotSame(first.access.store, second.access.store)
            assertEquals(first.access.scope, second.access.scope); assertEquals(first.access.originBinding, second.access.originBinding)
            val state = value(second.drafts.restoreLocal()); assertTrue(state.localDrafts.isEmpty()); assertNull(state.selected)
            assertFalse(state.serverAcknowledged); assertTrue(second.calls.isEmpty())
            assertEquals(CAPTION, first.drafts.states.value.selected!!.caption)
        } finally { second.close() }
    } }
    @Test fun draftBundleUsesActualSessionWithoutCreatingWorkOrChangingCredentialAndWorkLedgers() = runTest { fixture { f ->
        val work = value(f.rt.workControl.read())!!; val controlWrites = f.rt.controlWrites; val workWrites = f.rt.workWrites
        val creates = f.rt.credentials.creates; val keys = f.rt.dataVault.keys.keys.toSet()
        value(f.drafts.restoreLocal()); value(f.drafts.newLocalDraft(CAPTION, ALT)); value(f.drafts.saveExplicitly())
        value(f.drafts.listRemote()); value(f.drafts.back())
        val after = value(f.rt.workControl.read())!!
        assertEquals(work.revision, after.revision); assertContentEquals(work.payload.copyForCodec(), after.payload.copyForCodec())
        assertEquals(controlWrites, f.rt.controlWrites); assertEquals(workWrites, f.rt.workWrites)
        assertEquals(creates, f.rt.credentials.creates); assertEquals(keys, f.rt.dataVault.keys.keys.toSet()); assertTrue(f.rt.cancelled.isEmpty())
        assertSame(f.access, f.rt.runtime.currentAccess())
    } }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(StandardTestDispatcher(testScheduler)); try { f.open(); block(f) } finally { f.close() }
    }
    private class Fixture(private val dispatcher: CoroutineDispatcher) {
        val rt = PrivateSessionRuntimeTest.RuntimeFixture(dispatcher, NativeWorkExecutionPolicy { _, _, _, _ -> PortResult.Value(false) })
        lateinit var access: PrivateSessionAccess
        private lateinit var meals: MealRequestController
        private lateinit var bundle: MealKitchenControllers
        val drafts get() = bundle.postDrafts!!
        val key get() = RecordKey("mealflow.post-drafts.v1", access.originBinding)
        var time = 1_800_000_000_000L; var online = true; var idCalls = 0; var idOverride: String? = null
        var recordCommits = 0; private var failureBefore: Int? = null; private var failureAfter: Int? = null
        var afterCommit: (Int) -> Unit = {}
        val calls = mutableListOf<ApiCall>(); var remoteEffects = 0
        private val remote = linkedMapOf<String, RemoteDraft>(); private val receipts = mutableMapOf<String, Pair<ApiCall, ApiReply>>()
        private var remoteHead = 0L
        var handler: suspend (ApiCall) -> PortResult<ApiReply> = { reply(it) }
        private val ids = MealOperationIds { idCalls++; idOverride ?: id(6000 + idCalls) }
        private val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(access.lease, lease); assertEquals(ActorKind.ACCOUNT, lease.scope.actorKind)
                assertTrue(rt.boundary.isCurrent(lease)); calls += call; return handler(call)
            }
        }
        suspend fun open() { rt.start(); access = rt.create(); compose(); installHooks() }
        private fun compose() {
            val borrowed = AuthenticatedMealPlanningAccess.fromSession(access, transport)
            val clock = EpochClock { time }; val connectivity = ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE }
            meals = MealRequestController(borrowed, rt.boundary, dispatcher, clock, connectivity, ids, MealFlowPolicy(86_400_000, 518_400_000, 20, 128))
            bundle = MealKitchenControllers.createWithDrafts(borrowed, rt.boundary, dispatcher, clock, connectivity, ids, meals, meals.draftReadiness,
                CookingFlowPolicy(60_000, 262_144, 131_072), CookbookPolicy(2, 60_000), PostDraftClientPolicy(8, 1_048_576, 262_144, 128, 64, 2, 100, 60_000))
        }
        private fun installHooks() {
            rt.dataConnection.beforeRecordCommit = { recordCommits++
                if (recordCommits == failureBefore) { failureBefore = null; error("Synthetic failure before actual SQLite COMMIT") } }
            rt.dataConnection.afterRecordCommit = { afterCommit(recordCommits)
                if (recordCommits == failureAfter) { failureAfter = null; error("Synthetic application receipt loss after actual SQLite COMMIT") } }
        }
        fun loseBefore(number: Int) { recordCommits = 0; failureBefore = number }
        fun loseAfter(number: Int) { recordCommits = 0; failureAfter = number }
        suspend fun record() = value(access.store.read(access.scope, key)) ?: error("Expected actual encrypted draft row")
        suspend fun closeControllers() {
            if (::bundle.isInitialized) { bundle.postDrafts!!.close(); bundle.cookbook.close(); bundle.cooking.close(); meals.close() }
        }
        suspend fun reopenControllers() { closeControllers(); compose() }
        suspend fun reopenRuntime() {
            closeControllers(); rt.reopen(); assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, value(rt.runtime.recover()))
            access = value(rt.runtime.restore()); compose(); recordCommits = 0; failureBefore = null; failureAfter = null; afterCommit = {}; installHooks()
        }
        suspend fun close() { closeControllers(); rt.close() }
        fun recordTags(): Set<String> = rt.dataConnection.prepare("SELECT record_tag FROM feedme_records").use { statement ->
            buildSet { while (statement.step()) add(statement.getText(0)) }
        }
        fun reply(call: ApiCall): PortResult<ApiReply> {
            val key = call.idempotencyKey?.use { it }
            if (key != null) receipts[key]?.let { (original, reply) -> assertSameCall(original, call); return PortResult.Value(reply) }
            val reply = when (call.operationId) {
                "createPostDraft" -> {
                    val body = WireDocument.decode(call.body!!.copyForCodec()); val client = text(body, "clientDraftId")
                    assertTrue(remote.values.none { it.client == client })
                    val draft = RemoteDraft(id(7000 + remoteEffects), client, text(body, "caption"), optionalText(body, "altText"), 1)
                    remote[draft.id] = draft; remoteEffects++; remoteHead++; response(draft.body(), 201)
                }
                "updatePostDraft" -> {
                    val id = call.pathParameters.getValue("draftId"); val before = remote.getValue(id)
                    assertEquals("\"${before.version}\"", call.ifMatch)
                    val body = WireDocument.decode(call.body!!.copyForCodec())
                    val after = before.copy(caption = text(body, "caption"), alt = optionalText(body, "altText") ?: before.alt, version = before.version + 1)
                    remote[id] = after; remoteEffects++; remoteHead++; response(after.body())
                }
                "deletePostDraft" -> {
                    val before = remote.getValue(call.pathParameters.getValue("draftId")); assertEquals("\"${before.version}\"", call.ifMatch)
                    remote.remove(before.id); remoteEffects++; remoteHead++; ApiReply(204, null)
                }
                "getPostDraft" -> response(remote.getValue(call.pathParameters.getValue("draftId")).body())
                "listPostDrafts" -> ApiReply(200, bytes("{\"items\":[${remote.values.joinToString(",") { it.body() }}],\"nextCursor\":null,\"serverTime\":\"$TIME\"}"),
                    etag = "\"$remoteHead\"", contentType = "application/json")
                else -> return PortResult.Failure(FailureReason.NOT_CONFIGURED)
            }
            if (key != null) receipts[key] = call to reply
            return PortResult.Value(reply)
        }
    }
    private data class RemoteDraft(val id: String, val client: String, val caption: String, val alt: String?, val version: Long) {
        fun body() = "{\"id\":\"$id\",\"clientDraftId\":\"$client\",\"version\":$version,\"createdAt\":\"$TIME\",\"updatedAt\":\"$TIME\"," +
            "\"status\":\"draft\",\"caption\":${quote(caption)}${alt?.let { ",\"altText\":${quote(it)}" } ?: ""},\"mediaIds\":[]," +
            "\"audience\":{\"kind\":\"self\",\"circleIds\":[]},\"keepOnPlate\":false,\"allowRecipeSaves\":false,\"expiresAt\":\"2030-01-01T00:00:00Z\"}"
    }
    companion object {
        private const val CAPTION = "private-draft-caption-marker-887a11 🌱"
        private const val ALT = "private-draft-alt-text-marker-bb209e"
        private const val TIME = "2026-09-14T00:00:00Z"
        private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
        private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        private fun document(record: PrivateRecord) = WireDocument.decode(record.payload.copyForCodec())
        private fun field(document: WireDocument, name: String) = assertIs<WireField.Value<WireDocument>>(document.field(name)).value
        private fun text(document: WireDocument, name: String) = assertNotNull(field(document, name).stringOrNull())
        private fun optionalText(document: WireDocument, name: String) = (document.field(name) as? WireField.Value)?.value?.stringOrNull()
        private fun items(document: WireDocument, name: String) = assertNotNull(field(document, name).elementsOrNull())
        private fun command(record: PrivateRecord) = field(document(record), "command")
        private fun metadataKey(id: String) = RecordKey("feedme.command.metadata", id)
        private fun requestKey(id: String) = RecordKey("feedme.command.request", id)
        private fun response(body: String, status: Int = 200): ApiReply {
            val version = field(WireDocument.parse(body), "version").numberTokenOrNull()!!
            return ApiReply(status, bytes(body), etag = "\"$version\"", contentType = "application/json")
        }
        private fun quote(value: String) = buildString {
            append('"'); for (character in value) when (character) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (character.code < 32) append("\\u${character.code.toString(16).padStart(4, '0')}") else append(character)
            }; append('"')
        }
        private fun assertSameCall(first: ApiCall, second: ApiCall) {
            assertEquals(first.operationId, second.operationId); assertEquals(first.pathParameters, second.pathParameters)
            assertEquals(first.queryParameters, second.queryParameters); assertEquals(first.ifMatch, second.ifMatch)
            assertEquals(first.idempotencyKey?.use { it }, second.idempotencyKey?.use { it })
            if (first.body == null) assertNull(second.body) else assertContentEquals(first.body!!.copyForCodec(), second.body!!.copyForCodec())
        }
        private fun assertRecordEquals(first: PrivateRecord, second: PrivateRecord) {
            assertEquals(first.revision, second.revision); assertEquals(first.schemaVersion, second.schemaVersion)
            assertContentEquals(first.payload.copyForCodec(), second.payload.copyForCodec())
        }
        private fun <T> value(result: PortResult<T>): T = when (result) { is PortResult.Value -> result.value; is PortResult.Failure -> fail("Expected success, got ${result.reason}") }
        private fun rejected(reason: FailureReason, result: PortResult<*>) { assertEquals(reason, assertIs<PortResult.Failure>(result).reason) }
    }
}
