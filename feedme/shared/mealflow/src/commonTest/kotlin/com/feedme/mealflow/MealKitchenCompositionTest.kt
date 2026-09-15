package com.feedme.mealflow

import com.feedme.core.ports.*
import com.feedme.kitchen.SavedRecipeCommandHooks
import com.feedme.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual kitchen/journal composition over synthetic CAS ports, not native authentication. */
@OptIn(ExperimentalCoroutinesApi::class)
class MealKitchenCompositionTest {
    @Test fun constructionAndBindingPerformNoIoAndBothFeaturesSeeOneActualJournal() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val cooking = f.bind(MealKitchenFeature.COOKING)
        val cookbook = f.bind(MealKitchenFeature.COOKBOOK)
        assertEquals(0, f.store.reads); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
        val queue = f.composition.kitchen.commands
        f.composition.operate(cooking) { value(queue.enqueue(f.lease, start())) }
        f.composition.operate(cookbook) {
            assertSame(queue, f.composition.kitchen.commands)
            val original = value(queue.intent(f.lease, COMMAND))!!
            assertEquals(ORIGIN, original.originBinding); assertEquals(COMMAND, original.commandId)
            assertContentEquals(start().call.body!!.copyForCodec(), original.call.body!!.copyForCodec())
        }
        assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun actualCookingControllerCanBorrowCompositionWithoutSecondQueueOrIoOnConstruction() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val controller = CookingFlowController(f.composition, MealOperationIds { COMMAND }, CookingFlowPolicy(60_000, 65536, 65536))
        val cookbook = f.bind(MealKitchenFeature.COOKBOOK)
        assertEquals(0, f.store.reads); assertEquals(0, f.store.writes)
        assertEquals(CookingFlowPhase.IDLE, value(controller.restore()).phase)
        controller.close()
        // Detaching cooking does not close the borrowed native owner or the cookbook's journal.
        f.composition.operate(cookbook) { value(f.composition.kitchen.commands.enqueue(f.lease, save())) }
        assertTrue(f.boundary.isCurrent(f.lease)); assertEquals(0, f.store.erases); assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun duplicateFeatureAndForeignBorrowerCannotEnterOrReplaceAnOwner() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val a = f.bind(MealKitchenFeature.COOKING)
        assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { f.bind(MealKitchenFeature.COOKING) }.reason)
        val other = Fixture(StandardTestDispatcher(testScheduler))
        assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> { other.composition.operate(a) { error("not admitted") } }.reason)
        assertEquals(0, other.store.reads); assertEquals(0, other.store.writes)
        f.close(); other.close()
    }

    @Test fun wrapperCannotRunOutsideAnAdmittedCoroutineEvenWhileAnotherFeatureIsActive() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val a = f.bind(MealKitchenFeature.COOKING)
        assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> { f.composition.store.read(f.scope, KEY) }.reason)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val held = async { f.composition.operate(a) { entered.complete(Unit); release.await(); value(f.composition.store.read(f.scope, KEY)) } }
        entered.await()
        assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> { f.composition.store.read(f.scope, KEY) }.reason)
        assertEquals(0, f.store.reads); release.complete(Unit); held.await(); assertEquals(1, f.store.reads); f.close()
    }

    @Test fun expiredOperationContextCannotBorrowLaterFeatureAdmission() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val a = f.bind(MealKitchenFeature.COOKING); val b = f.bind(MealKitchenFeature.COOKBOOK)
        lateinit var old: CoroutineContext
        f.composition.operate(a) { old = currentCoroutineContext().minusKey(Job) }
        f.composition.operate(b) {
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
                withContext(old) { f.composition.store.read(f.scope, KEY) }
            }.reason)
            value(f.composition.store.read(f.scope, KEY))
        }
        assertEquals(1, f.store.reads); f.close()
    }

    @Test fun cancelledQueuedBorrowerDoesNotInvalidateRunningOrNextFeatureOperation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val a = f.bind(MealKitchenFeature.COOKING); val b = f.bind(MealKitchenFeature.COOKBOOK)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var queuedEntered = false
        val running = async { f.composition.operate(a) { entered.complete(Unit); release.await(); value(f.composition.store.read(f.scope, KEY)) } }
        entered.await(); val waiter = async { f.composition.operate(b) { queuedEntered = true } }; runCurrent()
        waiter.cancelAndJoin(); assertFalse(queuedEntered); release.complete(Unit); running.await()
        f.composition.operate(b) { value(f.composition.store.read(f.scope, KEY)) }
        assertEquals(2, f.store.reads); f.close()
    }

    @Test fun queuedStaleBorrowerIsRecheckedAfterOtherFeatureReleasesMutex() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val a = f.bind(MealKitchenFeature.COOKING)
        var current = true; val b = f.bind(MealKitchenFeature.COOKBOOK, check = { if (!current) mealFail(FailureReason.STALE_SESSION) })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val held = async { f.composition.operate(a) { entered.complete(Unit); release.await() } }; entered.await()
        val waiter = async { assertFailsWith<MealFailure> { f.composition.operate(b) { error("stale work ran") } } }
        runCurrent(); current = false; release.complete(Unit); held.await()
        assertEquals(FailureReason.STALE_SESSION, waiter.await().reason); assertEquals(0, f.store.reads); f.close()
    }

    @Test fun releasingSiblingDuringSuspendedReadDoesNotCloseOrInvalidateCurrentBorrower() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val a = f.bind(MealKitchenFeature.COOKING); val b = f.bind(MealKitchenFeature.COOKBOOK)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.store.afterRead = { entered.complete(Unit); release.await() }
        val task = async { f.composition.operate(b) { value(f.composition.store.read(f.scope, KEY)) } }
        entered.await(); f.composition.release(a); release.complete(Unit); task.await()
        assertTrue(f.boundary.isCurrent(f.lease)); assertEquals(0, f.store.erases); f.close()
    }

    @Test fun releasedCurrentBorrowerCannotContinueAfterNonCooperativeRead() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val a = f.bind(MealKitchenFeature.COOKING); f.bind(MealKitchenFeature.COOKBOOK)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.store.afterRead = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val task = async { assertFailsWith<MealFailure> { f.composition.operate(a) {
            value(f.composition.store.read(f.scope, KEY)); f.composition.store.commit(f.scope, listOf(put()))
        } } }
        entered.await(); f.composition.release(a); release.complete(Unit)
        assertEquals(FailureReason.STALE_SESSION, task.await().reason); assertEquals(0, f.store.writes); f.close()
    }

    @Test fun cancelledNonCooperativeReadCannotReachCommitOrInvalidateSibling() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val a = f.bind(MealKitchenFeature.COOKING); val b = f.bind(MealKitchenFeature.COOKBOOK)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.store.afterRead = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val task = async { f.composition.operate(a) { value(f.composition.store.read(f.scope, KEY)); f.composition.store.commit(f.scope, listOf(put())) } }
        entered.await(); task.cancel(); release.complete(Unit); assertFailsWith<CancellationException> { task.await() }
        f.store.afterRead = {}; f.composition.operate(b) { value(f.composition.store.read(f.scope, KEY)) }
        assertEquals(0, f.store.writes); f.close()
    }

    @Test fun currentLeaseChangeFencesEveryBorrowerAndNeverClearsNewerIdentity() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val a = f.bind(MealKitchenFeature.COOKING); val b = f.bind(MealKitchenFeature.COOKBOOK)
        var newer: SessionLease? = null
        f.store.afterRead = { newer = f.boundary.activate(f.scope) }
        assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> { f.composition.operate(a) { f.composition.store.read(f.scope, KEY) } }.reason)
        assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> { f.composition.operate(b) { error("old borrower") } }.reason)
        assertTrue(f.boundary.isCurrent(newer!!)); assertEquals(0, f.store.writes); assertEquals(0, f.store.erases); f.close()
    }

    @Test fun stalePublicCookingConstructionProducesUnavailableStateWithoutIo() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.boundary.clear()
        val controller = CookingFlowController(f.access, f.boundary, f.dispatcher, EpochClock { 1L }, ConnectivityPort { Connectivity.ONLINE },
            MealOperationIds { COMMAND }, CookingFlowPolicy(60_000, 65536, 65536))
        assertEquals(CookingFlowPhase.UNAVAILABLE, controller.states.value.phase)
        assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(controller.restore()).reason)
        assertEquals(0, f.store.reads); assertEquals(0, f.store.writes); controller.close(); f.close()
    }

    @Test fun exactBeforeCommitHookCannotBeBypassedAndUnknownReceiptIsNotPromoted() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var deny = true; var observed: List<StoreMutation>? = null
        val a = f.bind(MealKitchenFeature.COOKING, onCommit = { observed = it; if (deny) mealFail(FailureReason.CONFLICT) })
        assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { f.composition.operate(a) { f.composition.store.commit(f.scope, listOf(put())) } }.reason)
        assertEquals(KEY, observed!!.single().key); assertEquals(0, f.store.writes)
        deny = false; f.store.unknown = true
        f.composition.operate(a) {
            assertEquals(FailureReason.OUTCOME_UNKNOWN, assertIs<PortResult.Failure>(f.composition.kitchen.commands.enqueue(f.lease, start())).reason)
        }
        assertEquals(1, f.store.writes); assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun purposeFixedHooksNeverFallBackAcrossCookingAndCookbook() = runTest {
        for (wrongFeature in MealKitchenFeature.entries) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); var gateCalls = 0
            val a = f.bind(wrongFeature, create = if (wrongFeature == MealKitchenFeature.COOKING) CommandExecutionGate { _, _ -> gateCalls++; ExecutionDecision.Ready } else null,
                saved = if (wrongFeature == MealKitchenFeature.COOKBOOK) object : SavedRecipeCommandHooks {
                    override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision { gateCalls++; return ExecutionDecision.Ready }
                    override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply) = PortResult.Value(Unit)
                } else null)
            f.composition.operate(a) {
                val original = if (wrongFeature == MealKitchenFeature.COOKING) save() else start()
                value(f.composition.kitchen.commands.enqueue(f.lease, original))
                val blocked = value(if (wrongFeature == MealKitchenFeature.COOKING) f.composition.kitchen.commands.dispatchAutomatic(f.lease, COMMAND)
                    else f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND))!!
                assertEquals(CommandIssue.NOT_CONFIGURED, blocked.issue); assertEquals(0, blocked.attempts)
            }
            assertEquals(0, gateCalls); assertTrue(f.calls.isEmpty()); f.close()
        }
    }

    @Test fun wrongScopeForeignLeaseUnsupportedOperationAndOfflineStayOutsideTransport() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val a = f.bind(MealKitchenFeature.COOKBOOK)
        f.composition.operate(a) {
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> { f.composition.store.read(StorageScope("other", ActorKind.ACCOUNT, "owner"), KEY) }.reason)
            val foreign = SessionBoundary().activate(f.scope)
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> { f.composition.transport.execute(foreign, ApiCall("listSavedRecipes")) }.reason)
            assertEquals(FailureReason.NOT_CONFIGURED, assertIs<PortResult.Failure>(f.composition.transport.execute(f.lease, ApiCall("createCookSession"))).reason)
            f.online = false
            assertEquals(FailureReason.OFFLINE, assertIs<PortResult.Failure>(f.composition.transport.execute(f.lease, ApiCall("listSavedRecipes"))).reason)
        }
        assertEquals(0, f.store.reads); assertTrue(f.calls.isEmpty()); assertFalse(f.composition.toString().contains("private-owner")); f.close()
    }

    private class Fixture(val dispatcher: CoroutineDispatcher) {
        val scope = StorageScope("synthetic", ActorKind.ACCOUNT, "private-owner")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = Store()
        var online = true; val calls = mutableListOf<ApiCall>()
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> { calls += call; return PortResult.Failure(FailureReason.NOT_CONFIGURED) }
        }, true)
        val composition = MealKitchenComposition(access, boundary, dispatcher, EpochClock { 1_000L }, ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE })
        private val bindings = mutableListOf<MealKitchenComposition.Borrower>()
        fun bind(feature: MealKitchenFeature, check: suspend () -> Unit = {}, onCommit: (List<StoreMutation>) -> Unit = {},
            create: CommandExecutionGate? = null, saved: SavedRecipeCommandHooks? = null) = composition.bind(feature, object : MealKitchenHooks {
            override suspend fun checkCurrent() = check()
            override fun beforeCommit(mutations: List<StoreMutation>) = onCommit(mutations)
            override val createExecutionGate = create
            override val savedRecipeCommands = saved
        }).also { bindings += it }
        fun close() { bindings.forEach { composition.release(it) }; boundary.clear() }
    }
    private class Store : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>(); var reads = 0; var writes = 0; var erases = 0; var unknown = false
        var afterRead: suspend () -> Unit = {}
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> { reads++; val record = records[key]; afterRead(); return PortResult.Value(record) }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            if (mutations.any { records[it.key]?.revision != it.expectedRevision }) return PortResult.Failure(FailureReason.CONFLICT)
            val result = mutations.associate { m -> m.key to when (m) {
                is StoreMutation.Put -> { val revision = (records[m.key]?.revision ?: 0L) + 1; records[m.key] = PrivateRecord(revision, m.schemaVersion, PrivateBytes(m.payload.copyForCodec())); revision }
                is StoreMutation.Delete -> { records.remove(m.key); null }
            } }; writes++
            return if (unknown) PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else PortResult.Value(result)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { erases++; return PortResult.Failure(FailureReason.NOT_CONFIGURED) }
    }
    private companion object {
        const val ORIGIN = "00000000-0000-4000-8000-000000000001"
        const val COMMAND = "00000000-0000-4000-8000-000000000002"
        const val PLAN = "00000000-0000-4000-8000-000000000003"
        val KEY = RecordKey("test.composition", ORIGIN)
        fun put() = StoreMutation.Put(KEY, null, 1, PrivateBytes("private".encodeToByteArray()))
        fun start() = CommandIntent(COMMAND, ORIGIN, ApiCall("createCookSession", body = PrivateBytes("""{"planId":"$PLAN","deviceSequence":0}""".encodeToByteArray()), idempotencyKey = SecretText(COMMAND)))
        fun save() = CommandIntent(COMMAND, ORIGIN, ApiCall("saveRecipe", body = PrivateBytes("""{"planId":"$PLAN"}""".encodeToByteArray()), idempotencyKey = SecretText(COMMAND)))
        fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
    }
}
