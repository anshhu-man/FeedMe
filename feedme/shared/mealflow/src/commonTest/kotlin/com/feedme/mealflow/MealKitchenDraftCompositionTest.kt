package com.feedme.mealflow

import com.feedme.contracts.ContractCatalog
import com.feedme.contracts.ContractSurface
import com.feedme.core.ports.*
import com.feedme.kitchen.PostDraftCommandHooks
import com.feedme.kitchen.SavedRecipeCommandHooks
import com.feedme.mealflow.social.PostDraftClientPolicy
import com.feedme.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual shared kitchen/journal and fencing over synthetic CAS/transport ports. No provider grant. */
@OptIn(ExperimentalCoroutinesApi::class)
class MealKitchenDraftCompositionTest {
    @Test fun allThreeBorrowersShareOneJournalWithoutConstructionIo() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val owners = listOf(MealKitchenFeature.COOKING, MealKitchenFeature.COOKBOOK, MealKitchenFeature.POST_DRAFTS).map { f.bind(it) }
        assertEquals(0, f.store.reads); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
        val queue = f.composition.kitchen.commands
        f.composition.operate(owners.last()) { value(queue.enqueue(f.lease, intent("createPostDraft"))) }
        for (owner in owners) f.composition.operate(owner) {
            assertSame(queue, f.composition.kitchen.commands)
            val original = value(queue.intent(f.lease, COMMAND))!!
            assertEquals(COMMAND, original.commandId); assertEquals(ORIGIN, original.originBinding)
            assertContentEquals(intent("createPostDraft").call.body!!.copyForCodec(), original.call.body!!.copyForCodec())
        }
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.erases); f.close()
    }

    @Test fun draftTransportAdmitsExactlyFiveCanonicalMobileOperations() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var before = 0; var after = 0
        f.exchange = { PortResult.Value(ApiReply(204, null)) }
        val owner = f.bind(MealKitchenFeature.POST_DRAFTS, before = { before++ }, after = { _, _ -> after++ })
        f.composition.operate(owner) {
            for (operation in ContractCatalog.bundled().operationsFor(ContractSurface.MOBILE)) {
                val count = f.calls.size
                val result = f.composition.transport.execute(f.lease, ApiCall(operation.id))
                if (operation.id in DRAFT_OPERATIONS) {
                    assertIs<PortResult.Value<ApiReply>>(result); assertEquals(count + 1, f.calls.size)
                } else {
                    assertEquals(FailureReason.NOT_CONFIGURED, assertIs<PortResult.Failure>(result).reason)
                    assertEquals(count, f.calls.size)
                }
            }
            assertEquals(FailureReason.NOT_CONFIGURED,
                assertIs<PortResult.Failure>(f.composition.transport.execute(f.lease, ApiCall("unconfiguredFutureOperation"))).reason)
        }
        assertEquals(DRAFT_OPERATIONS, f.calls.map { it.operationId }.toSet())
        assertEquals(5, before); assertEquals(5, after); f.close()
    }

    @Test fun cookingAndCookbookCannotUseAnyDraftTransportOperation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for (feature in listOf(MealKitchenFeature.COOKING, MealKitchenFeature.COOKBOOK)) {
            val owner = f.bind(feature)
            f.composition.operate(owner) { for (operation in DRAFT_OPERATIONS)
                assertEquals(FailureReason.NOT_CONFIGURED,
                    assertIs<PortResult.Failure>(f.composition.transport.execute(f.lease, ApiCall(operation))).reason)
            }
        }
        assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun purposeFixedBindingRejectsEveryCrossFeatureHook() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val create = CommandExecutionGate { _, _ -> ExecutionDecision.Ready }
        val saved = object : SavedRecipeCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent) = ExecutionDecision.Ready
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply) = PortResult.Value(Unit)
        }
        assertFailsWith<IllegalArgumentException> { f.bind(MealKitchenFeature.COOKING, draft = hooks()) }
        assertFailsWith<IllegalArgumentException> { f.bind(MealKitchenFeature.COOKBOOK, draft = hooks()) }
        assertFailsWith<IllegalArgumentException> { f.bind(MealKitchenFeature.POST_DRAFTS, create = create) }
        assertFailsWith<IllegalArgumentException> { f.bind(MealKitchenFeature.POST_DRAFTS, saved = saved) }
        assertFailsWith<IllegalArgumentException> { f.bind(MealKitchenFeature.COOKING, saved = saved) }
        assertFailsWith<IllegalArgumentException> { f.bind(MealKitchenFeature.COOKBOOK, create = create) }
        f.bind(MealKitchenFeature.POST_DRAFTS, draft = hooks())
        assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { f.bind(MealKitchenFeature.POST_DRAFTS) }.reason)
        assertEquals(0, f.store.reads); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun unconfiguredDraftBorrowerDeniesAllThreeConfirmedMutations() = runTest {
        for (operation in MUTATIONS) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.bind(MealKitchenFeature.POST_DRAFTS)
            f.composition.operate(owner) {
                value(f.composition.kitchen.commands.enqueue(f.lease, intent(operation)))
                val result = value(f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND))!!
                assertEquals(CommandIssue.NOT_CONFIGURED, result.issue); assertEquals(0, result.attempts)
            }
            assertTrue(f.calls.isEmpty()); f.close()
        }
    }

    @Test fun siblingsCannotDispatchDraftCommandEvenWithAnAcceptingDraftOwner() = runTest {
        for (feature in listOf(MealKitchenFeature.COOKING, MealKitchenFeature.COOKBOOK)) for (operation in MUTATIONS) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); var hookCalls = 0
            val draft = f.bind(MealKitchenFeature.POST_DRAFTS, draft = hooks(gate = { _, _ -> hookCalls++; ExecutionDecision.Ready }))
            val sibling = f.bind(feature)
            f.composition.operate(draft) { value(f.composition.kitchen.commands.enqueue(f.lease, intent(operation))) }
            f.composition.operate(sibling) {
                val result = value(f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND))!!
                assertEquals(CommandIssue.NOT_CONFIGURED, result.issue); assertEquals(0, result.attempts)
            }
            assertEquals(0, hookCalls); assertTrue(f.calls.isEmpty()); f.close()
        }
    }

    @Test fun draftCannotDispatchCookingOrCookbookCommand() = runTest {
        for (call in listOf(
            ApiCall("createCookSession", body = bytes("""{"planId":"$CLIENT","deviceSequence":0}"""), idempotencyKey = SecretText(COMMAND)),
            ApiCall("saveRecipe", body = bytes("""{"planId":"$CLIENT"}"""), idempotencyKey = SecretText(COMMAND)),
            ApiCall("deleteSavedRecipe", pathParameters = mapOf("savedRecipeId" to SERVER), ifMatch = "\"1\"", idempotencyKey = SecretText(COMMAND)),
        )) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); var hookCalls = 0
            val owner = f.bind(MealKitchenFeature.POST_DRAFTS, draft = hooks(gate = { _, _ -> hookCalls++; ExecutionDecision.Ready }))
            f.composition.operate(owner) {
                value(f.composition.kitchen.commands.enqueue(f.lease, CommandIntent(COMMAND, ORIGIN, call)))
                val result = value(f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND))!!
                assertEquals(CommandIssue.NOT_CONFIGURED, result.issue); assertEquals(0, result.attempts)
            }
            assertEquals(0, hookCalls); assertTrue(f.calls.isEmpty()); f.close()
        }
    }

    @Test fun exactDraftHooksReceiveOriginalIntentOnlyAfterExplicitConfirmation() = runTest {
        for (operation in MUTATIONS) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val seen = mutableListOf<CommandIntent>()
            val owner = f.bind(MealKitchenFeature.POST_DRAFTS, draft = hooks(gate = { lease, original ->
                assertSame(f.lease, lease); seen += original; ExecutionDecision.Ready
            }, observer = { lease, original, observed ->
                assertSame(f.lease, lease); assertEquals(reply(operation).status, observed.status); seen += original; PortResult.Value(Unit)
            }))
            val original = intent(operation)
            f.composition.operate(owner) {
                value(f.composition.kitchen.commands.enqueue(f.lease, original))
                assertNull(value(f.composition.kitchen.commands.dispatchNext(f.lease)))
                assertNull(value(f.composition.kitchen.commands.dispatchAutomatic(f.lease, COMMAND)))
                assertTrue(f.calls.isEmpty()); assertTrue(seen.isEmpty())
                assertEquals(CommandPhase.RECEIPT_READY, value(f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND))!!.phase)
            }
            assertEquals(2, seen.size); assertEquals(1, f.calls.size)
            for (actual in seen) {
                assertEquals(original.commandId, actual.commandId); assertEquals(original.originBinding, actual.originBinding)
                assertEquals(original.call.operationId, actual.call.operationId); assertEquals(original.call.pathParameters, actual.call.pathParameters)
                assertEquals(original.call.ifMatch, actual.call.ifMatch); assertEquals(COMMAND, actual.call.idempotencyKey!!.use { it })
                assertContentEquals(original.call.body?.copyForCodec(), actual.call.body?.copyForCodec())
            }
            f.close()
        }
    }

    @Test fun readsAndConnectivityRecoveryDoNotSubmitPendingDraft() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var gates = 0
        val owner = f.bind(MealKitchenFeature.POST_DRAFTS, draft = hooks(gate = { _, _ -> gates++; ExecutionDecision.Ready }))
        f.composition.operate(owner) {
            value(f.composition.kitchen.commands.enqueue(f.lease, intent("createPostDraft")))
            f.online = false; assertFalse(f.composition.online()); f.online = true
            for (operation in listOf("listPostDrafts", "getPostDraft")) f.composition.transport.execute(f.lease, ApiCall(operation))
            assertNull(value(f.composition.kitchen.commands.dispatchNext(f.lease)))
            assertNull(value(f.composition.kitchen.commands.dispatchAutomatic(f.lease, COMMAND)))
            assertEquals(0, value(f.composition.kitchen.commands.command(f.lease, COMMAND))!!.attempts)
        }
        assertEquals(0, gates); assertEquals(listOf("listPostDrafts", "getPostDraft"), f.calls.map { it.operationId }); f.close()
    }

    @Test fun confirmedDraftRetryCannotBypassEarlierSocialLaneIntent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var gates = 0
        val owner = f.bind(MealKitchenFeature.POST_DRAFTS, draft = hooks(gate = { _, _ -> gates++; ExecutionDecision.Ready }))
        f.composition.operate(owner) {
            val queue = f.composition.kitchen.commands
            val first = value(queue.enqueue(f.lease, intent("createPostDraft")))
            value(queue.enqueue(f.lease, intent("deletePostDraft", SECOND)))
            assertNull(value(queue.dispatchConfirmed(f.lease, SECOND))); assertEquals(0, gates); assertTrue(f.calls.isEmpty())
            value(queue.discardUnsent(f.lease, COMMAND, first.localRevision))
            assertEquals(CommandPhase.RECEIPT_READY, value(queue.dispatchConfirmed(f.lease, SECOND))!!.phase)
        }
        assertEquals(1, gates); assertEquals(listOf("deletePostDraft"), f.calls.map { it.operationId }); f.close()
    }

    @Test fun guestCannotEnterDraftLocalStoreOrTransportEvenWithAcceptingHooks() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler), actor = ActorKind.GUEST); var checked = 0
        val owner = f.bind(MealKitchenFeature.POST_DRAFTS, check = { checked++ }, draft = hooks())
        assertEquals(FailureReason.UNAUTHENTICATED, assertFailsWith<MealFailure> { f.composition.operate(owner) {
            f.composition.store.read(f.scope, KEY)
        } }.reason)
        assertEquals(FailureReason.UNAUTHENTICATED, assertFailsWith<MealFailure> { f.composition.operate(owner) {
            f.composition.transport.execute(f.lease, ApiCall("listPostDrafts"))
        } }.reason)
        val cooking = f.bind(MealKitchenFeature.COOKING)
        f.composition.operate(cooking) { value(f.composition.store.read(f.scope, KEY)) }
        assertEquals(0, checked); assertEquals(1, f.store.reads); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun draftRequiresExactLeaseScopeAndOriginBeforeDomainGate() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var gates = 0
        val owner = f.bind(MealKitchenFeature.POST_DRAFTS, draft = hooks(gate = { _, _ -> gates++; ExecutionDecision.Ready }))
        f.composition.operate(owner) {
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
                f.composition.store.read(StorageScope("foreign", ActorKind.ACCOUNT, "owner"), KEY)
            }.reason)
            val foreign = SessionBoundary().activate(f.scope)
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
                f.composition.transport.execute(foreign, ApiCall("listPostDrafts"))
            }.reason)
            val original = intent("createPostDraft")
            value(f.composition.kitchen.commands.enqueue(f.lease, CommandIntent(COMMAND, CLIENT, original.call)))
            val blocked = value(f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND))!!
            assertEquals(CommandIssue.AUTH_REQUIRED, blocked.issue); assertEquals(0, blocked.attempts)
        }
        assertEquals(0, gates); assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun offlinePrivateAccessAndLostConnectivityCannotReachDraftTransport() = runTest {
        for (onlineAllowed in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler), onlineAllowed = onlineAllowed)
            f.online = !onlineAllowed
            var before = 0; val owner = f.bind(MealKitchenFeature.POST_DRAFTS, before = { before++ })
            f.composition.operate(owner) { for (operation in DRAFT_OPERATIONS)
                assertEquals(FailureReason.OFFLINE, assertIs<PortResult.Failure>(f.composition.transport.execute(f.lease, ApiCall(operation))).reason)
            }
            assertEquals(0, before); assertTrue(f.calls.isEmpty()); f.close()
        }
    }

    @Test fun releasedDraftCannotPublishNonCooperativeTransportReturnToCallerOrObserver() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var observed = 0
        val owner = f.bind(MealKitchenFeature.POST_DRAFTS, after = { _, _ -> observed++ }); val sibling = f.bind(MealKitchenFeature.COOKBOOK)
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        f.exchange = { entered.complete(Unit); withContext(NonCancellable) { released.await() }; PortResult.Value(ApiReply(204, null)) }
        val task = async { assertFailsWith<MealFailure> { f.composition.operate(owner) { f.composition.transport.execute(f.lease, ApiCall("listPostDrafts")) } } }
        entered.await(); f.composition.release(owner); released.complete(Unit)
        assertEquals(FailureReason.STALE_SESSION, task.await().reason); assertEquals(0, observed)
        f.composition.operate(sibling) { value(f.composition.store.read(f.scope, KEY)) }
        assertTrue(f.boundary.isCurrent(f.lease)); assertEquals(0, f.store.erases); f.close()
    }

    @Test fun cancelledDraftTransportCannotReturnPrivateValueOrInvalidateSibling() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var observed = 0
        val owner = f.bind(MealKitchenFeature.POST_DRAFTS, after = { _, _ -> observed++ }); val sibling = f.bind(MealKitchenFeature.COOKING)
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        f.exchange = { entered.complete(Unit); withContext(NonCancellable) { released.await() }; PortResult.Value(ApiReply(204, null)) }
        val task = async { f.composition.operate(owner) { f.composition.transport.execute(f.lease, ApiCall("getPostDraft")) } }
        entered.await(); task.cancel(); released.complete(Unit); assertFailsWith<CancellationException> { task.await() }
        assertEquals(0, observed); f.composition.operate(sibling) { value(f.composition.store.read(f.scope, KEY)) }; f.close()
    }

    @Test fun leaseInvalidationInsideDraftGatePreventsClaimAndNetworkSend() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var newer: SessionLease? = null
        val owner = f.bind(MealKitchenFeature.POST_DRAFTS, draft = hooks(gate = { _, _ -> newer = f.boundary.activate(f.scope); ExecutionDecision.Ready }))
        f.composition.operate(owner) { value(f.composition.kitchen.commands.enqueue(f.lease, intent("createPostDraft"))) }
        val writes = f.store.writes
        assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
            f.composition.operate(owner) { f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND) }
        }.reason)
        assertEquals(writes, f.store.writes); assertTrue(f.calls.isEmpty()); assertTrue(f.boundary.isCurrent(newer!!)); f.close()
    }

    @Test fun leaseInvalidationInsideDraftReplyObserverCannotAcknowledgeSentCommand() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var observed = 0; var writesAtObservation = 0
        val owner = f.bind(MealKitchenFeature.POST_DRAFTS, draft = hooks(observer = { _, _, _ ->
            observed++; writesAtObservation = f.store.writes; f.boundary.activate(f.scope); PortResult.Value(Unit)
        }))
        f.composition.operate(owner) { value(f.composition.kitchen.commands.enqueue(f.lease, intent("deletePostDraft"))) }
        assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
            f.composition.operate(owner) { f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND) }
        }.reason)
        assertEquals(1, observed); assertEquals(1, f.calls.size); assertEquals(writesAtObservation, f.store.writes); f.close()
    }

    @Test fun draftHooksCanReadExactJournalInsideOperationWithoutReentrantCompositionLock() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var reads = 0
        val owner = f.bind(MealKitchenFeature.POST_DRAFTS, draft = hooks(gate = { lease, original ->
            assertEquals(original.commandId, value(f.composition.kitchen.commands.intent(lease, original.commandId))!!.commandId)
            value(f.composition.store.read(f.scope, KEY)); reads++; ExecutionDecision.Ready
        }, observer = { lease, original, _ ->
            assertEquals(CommandPhase.IN_FLIGHT, value(f.composition.kitchen.commands.command(lease, original.commandId))!!.phase)
            reads++; PortResult.Value(Unit)
        }))
        withTimeout(5_000) { f.composition.operate(owner) {
            value(f.composition.kitchen.commands.enqueue(f.lease, intent("deletePostDraft")))
            assertEquals(CommandPhase.RECEIPT_READY, value(f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND))!!.phase)
        } }
        assertEquals(2, reads); assertEquals(1, f.calls.size); f.close()
    }

    @Test fun expiredSiblingCoroutineCannotBorrowCurrentDraftAdmission() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val sibling = f.bind(MealKitchenFeature.COOKBOOK)
        val draft = f.bind(MealKitchenFeature.POST_DRAFTS); lateinit var old: CoroutineContext
        f.composition.operate(sibling) { old = currentCoroutineContext().minusKey(Job) }
        f.composition.operate(draft) {
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
                withContext(old) { f.composition.transport.execute(f.lease, ApiCall("listPostDrafts")) }
            }.reason)
        }
        assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun draftBeforeTransportFenceCannotBeBypassed() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var after = 0
        val draft = f.bind(MealKitchenFeature.POST_DRAFTS, before = { mealFail(FailureReason.CONFLICT) }, after = { _, _ -> after++ })
        assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
            f.composition.operate(draft) { f.composition.transport.execute(f.lease, ApiCall("listPostDrafts")) }
        }.reason)
        assertEquals(0, after); assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun oldBundleLeavesDraftsUnconfiguredAndOptInRequiresExplicitPolicyWithoutIo() = runTest {
        for (enabled in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); var ids = 0
            val source = MealOperationIds { ids++; COMMAND }; val clock = EpochClock { NOW }; val connectivity = ConnectivityPort { Connectivity.ONLINE }
            val meals = MealRequestController(f.access, f.boundary, f.dispatcher, clock, connectivity, source, MealFlowPolicy(60_000, 60_000, 2, 20))
            val bundle = if (enabled) MealKitchenControllers.createWithDrafts(f.access, f.boundary, f.dispatcher, clock, connectivity,
                source, meals, meals.draftReadiness, COOK_POLICY, BOOK_POLICY, DRAFT_POLICY)
            else MealKitchenControllers.create(f.access, f.boundary, f.dispatcher, clock, connectivity,
                source, meals, meals.draftReadiness, COOK_POLICY, BOOK_POLICY)
            if (enabled) assertNotNull(bundle.postDrafts) else assertNull(bundle.postDrafts)
            assertEquals(0, ids); assertEquals(0, f.store.reads); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
            bundle.postDrafts?.close(); bundle.cookbook.close(); bundle.cooking.close(); meals.close()
            assertTrue(f.boundary.isCurrent(f.lease)); assertEquals(0, f.store.erases); f.close()
        }
    }

    @Test fun draftBundleCannotUseReadinessFromOtherMealControllerOrAccess() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val clock = EpochClock { NOW }; val connectivity = ConnectivityPort { Connectivity.ONLINE }
        val ids = MealOperationIds { COMMAND }
        fun meals(access: AuthenticatedMealPlanningAccess) = MealRequestController(access, f.boundary, f.dispatcher, clock, connectivity,
            ids, MealFlowPolicy(60_000, 60_000, 2, 20))
        val original = meals(f.access); val other = meals(f.access)
        assertFailsWith<IllegalArgumentException> { MealKitchenControllers.createWithDrafts(f.access, f.boundary, f.dispatcher, clock, connectivity,
            ids, original, other.draftReadiness, COOK_POLICY, BOOK_POLICY, DRAFT_POLICY) }
        val foreign = AuthenticatedMealPlanningAccess(f.lease, CLIENT, f.store, f.access.transport, true)
        assertFailsWith<IllegalArgumentException> { MealKitchenControllers.createWithDrafts(foreign, f.boundary, f.dispatcher, clock, connectivity,
            ids, original, original.draftReadiness, COOK_POLICY, BOOK_POLICY, DRAFT_POLICY) }
        assertEquals(0, f.store.reads); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
        other.close(); original.close(); f.close()
    }

    private class Fixture(val dispatcher: CoroutineDispatcher, actor: ActorKind = ActorKind.ACCOUNT, onlineAllowed: Boolean = true) {
        val scope = StorageScope("synthetic-draft-composition", actor, "private-owner")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = Store(scope)
        var online = true; val calls = mutableListOf<ApiCall>()
        var exchange: suspend (ApiCall) -> PortResult<ApiReply> = { PortResult.Value(reply(it.operationId)) }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@Fixture.lease, lease); calls += call; return exchange(call)
            }
        }, onlineAllowed)
        val composition = MealKitchenComposition(access, boundary, dispatcher, EpochClock { NOW }, ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE })
        private val bindings = mutableListOf<MealKitchenComposition.Borrower>()
        fun bind(feature: MealKitchenFeature, check: suspend () -> Unit = {}, before: suspend (ApiCall) -> Unit = {},
            after: suspend (ApiCall, ApiReply) -> Unit = { _, _ -> }, create: CommandExecutionGate? = null,
            saved: SavedRecipeCommandHooks? = null, draft: PostDraftCommandHooks? = null) = composition.bind(feature, object : MealKitchenHooks {
            override suspend fun checkCurrent() = check()
            override suspend fun beforeTransport(call: ApiCall) = before(call)
            override suspend fun afterTransport(call: ApiCall, reply: ApiReply) = after(call, reply)
            override val createExecutionGate = create
            override val savedRecipeCommands = saved
            override val postDraftCommands = draft
        }).also { bindings += it }
        fun close() { bindings.forEach { composition.release(it) }; boundary.clear() }
    }
    private class Store(private val owner: StorageScope) : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>(); private val counters = mutableMapOf<RecordKey, Long>()
        var reads = 0; var writes = 0; var erases = 0
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            assertEquals(owner, scope); reads++; return PortResult.Value(records[key])
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            assertEquals(owner, scope)
            if (mutations.any { records[it.key]?.revision != it.expectedRevision }) return PortResult.Failure(FailureReason.CONFLICT)
            val result = mutations.associate { mutation ->
                val revision = (counters[mutation.key] ?: 0L) + 1; counters[mutation.key] = revision
                mutation.key to when (mutation) {
                    is StoreMutation.Put -> { records[mutation.key] = PrivateRecord(revision, mutation.schemaVersion, mutation.payload); revision }
                    is StoreMutation.Delete -> { records.remove(mutation.key); null }
                }
            }; writes++; return PortResult.Value(result)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { erases++; return PortResult.Failure(FailureReason.NOT_CONFIGURED) }
    }
    private companion object {
        const val ORIGIN = "00000000-0000-4000-8000-000000000011"
        const val COMMAND = "00000000-0000-4000-8000-000000000012"
        const val CLIENT = "00000000-0000-4000-8000-000000000013"
        const val SERVER = "00000000-0000-4000-8000-000000000014"
        const val SECOND = "00000000-0000-4000-8000-000000000015"
        const val NOW = 1_800_000_000_000L
        val KEY = RecordKey("test.draft.composition", ORIGIN)
        val MUTATIONS = setOf("createPostDraft", "updatePostDraft", "deletePostDraft")
        val DRAFT_OPERATIONS = MUTATIONS + setOf("getPostDraft", "listPostDrafts")
        val COOK_POLICY = CookingFlowPolicy(60_000, 65_536, 65_536)
        val BOOK_POLICY = CookbookPolicy(20, 60_000)
        val DRAFT_POLICY = PostDraftClientPolicy(8, 262_144, 65_536, 64, 64, 20, 200, 60_000)
        fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        fun intent(operation: String, id: String = COMMAND) = CommandIntent(id, ORIGIN, when (operation) {
            "createPostDraft" -> ApiCall(operation, body = bytes("""{ "clientDraftId":"$CLIENT", "caption":"Original caption" }"""), idempotencyKey = SecretText(id))
            "updatePostDraft" -> ApiCall(operation, pathParameters = mapOf("draftId" to SERVER), body = bytes("""{ "caption":"Original caption" }"""),
                ifMatch = "\"9007199254740993\"", idempotencyKey = SecretText(id))
            else -> ApiCall(operation, pathParameters = mapOf("draftId" to SERVER), ifMatch = "\"9007199254740993\"", idempotencyKey = SecretText(id))
        })
        fun reply(operation: String) = if (operation == "deletePostDraft") ApiReply(204, null) else ApiReply(if (operation == "createPostDraft") 201 else 200,
            bytes("""{"id":"$SERVER","version":9007199254740994,"clientDraftId":"$CLIENT","status":"draft","caption":"Original caption","mediaIds":[],"keepOnPlate":false,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","expiresAt":"2026-10-14T00:00:00Z"}"""),
            etag = "\"9007199254740994\"", contentType = "application/json")
        fun hooks(gate: suspend (SessionLease, CommandIntent) -> ExecutionDecision = { _, _ -> ExecutionDecision.Ready },
            observer: suspend (SessionLease, CommandIntent, ApiReply) -> PortResult<Unit> = { _, _, _ -> PortResult.Value(Unit) }) = object : PostDraftCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent) = gate(lease, intent)
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply) = observer(lease, intent, reply)
        }
        fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
    }
}
