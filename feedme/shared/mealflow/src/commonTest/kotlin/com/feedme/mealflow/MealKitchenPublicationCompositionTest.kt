package com.feedme.mealflow

import com.feedme.contracts.ContractCatalog
import com.feedme.contracts.ContractSurface
import com.feedme.core.ports.*
import com.feedme.kitchen.PostDraftCommandHooks
import com.feedme.kitchen.PostPublicationCommandHooks
import com.feedme.kitchen.SavedRecipeCommandHooks
import com.feedme.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual shared composition/journal over synthetic CAS and transport ports.
 * No publication controller, review authority, upload/native work or server proof is supplied. */
@OptIn(ExperimentalCoroutinesApi::class)
class MealKitchenPublicationCompositionTest {
    @Test fun allFourBorrowersShareOneJournalAndConstructionHasNoIo() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val owners = MealKitchenFeature.entries.map { f.bind(it) }
        assertEquals(4, owners.size); assertEquals(0, f.store.reads); assertEquals(0, f.store.writes)
        val queue = f.composition.kitchen.commands
        f.composition.operate(owners.last()) { value(queue.enqueue(f.lease, publish())) }
        for (owner in owners) f.composition.operate(owner) {
            assertSame(queue, f.composition.kitchen.commands)
            assertOriginal(publish(), value(queue.intent(f.lease, COMMAND))!!)
        }
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.erases); f.close()
    }

    @Test fun publicationTransportAdmitsOnlyPublishAcrossTheEntireCanonicalMobileCatalog() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var before = 0; var after = 0
        val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS, before = { before++ }, after = { _, _ -> after++ })
        f.composition.operate(owner) {
            for (operation in ContractCatalog.bundled().operationsFor(ContractSurface.MOBILE)) {
                val count = f.calls.size
                val result = f.composition.transport.execute(f.lease, ApiCall(operation.id))
                if (operation.id == "publishPost") {
                    assertIs<PortResult.Value<ApiReply>>(result); assertEquals(count + 1, f.calls.size)
                } else {
                    assertEquals(FailureReason.NOT_CONFIGURED, assertIs<PortResult.Failure>(result).reason)
                    assertEquals(count, f.calls.size)
                }
            }
            assertEquals(FailureReason.NOT_CONFIGURED, assertIs<PortResult.Failure>(
                f.composition.transport.execute(f.lease, ApiCall("unconfiguredFutureOperation"))).reason)
        }
        assertEquals(listOf("publishPost"), f.calls.map { it.operationId })
        assertEquals(1, before); assertEquals(1, after); f.close()
    }

    @Test fun everyCrossFeatureHookCombinationIsRejectedWithoutReplacingOwners() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for (feature in MealKitchenFeature.entries) for (provided in MealKitchenFeature.entries.filterNot { it == feature }) {
            assertFailsWith<IllegalArgumentException> {
                f.bind(feature, create = if (provided == MealKitchenFeature.COOKING) createHooks() else null,
                    saved = if (provided == MealKitchenFeature.COOKBOOK) savedHooks() else null,
                    draft = if (provided == MealKitchenFeature.POST_DRAFTS) draftHooks() else null,
                    publication = if (provided == MealKitchenFeature.POST_PUBLICATIONS) publicationHooks() else null)
            }
        }
        for (feature in MealKitchenFeature.entries) f.bind(feature)
        assertEquals(FailureReason.CONFLICT,
            assertFailsWith<MealFailure> { f.bind(MealKitchenFeature.POST_PUBLICATIONS, publication = publicationHooks()) }.reason)
        assertEquals(0, f.store.reads); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun unconfiguredPublicationBorrowerDeniesExplicitAndAutomaticDispatch() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
        f.composition.operate(owner) {
            val queue = f.composition.kitchen.commands
            value(queue.enqueue(f.lease, publish()))
            assertNull(value(queue.dispatchNext(f.lease))); assertNull(value(queue.dispatchAutomatic(f.lease, COMMAND)))
            val blocked = value(queue.dispatchConfirmed(f.lease, COMMAND))!!
            assertEquals(CommandIssue.NOT_CONFIGURED, blocked.issue); assertEquals(0, blocked.attempts)
            assertNull(value(queue.receipt(f.lease, COMMAND)))
        }
        assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun siblingsCannotUsePublicationTransportOrAnAcceptingPublicationQueueHook() = runTest {
        for (feature in MealKitchenFeature.entries.filterNot { it == MealKitchenFeature.POST_PUBLICATIONS }) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); var gates = 0
            val publication = f.bind(MealKitchenFeature.POST_PUBLICATIONS,
                publication = publicationHooks(gate = { _, _ -> gates++; ExecutionDecision.Ready }))
            val sibling = f.bind(feature)
            f.composition.operate(publication) { value(f.composition.kitchen.commands.enqueue(f.lease, publish())) }
            f.composition.operate(sibling) {
                assertEquals(FailureReason.NOT_CONFIGURED,
                    assertIs<PortResult.Failure>(f.composition.transport.execute(f.lease, publish().call)).reason)
                val blocked = value(f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND))!!
                assertEquals(CommandIssue.NOT_CONFIGURED, blocked.issue); assertEquals(0, blocked.attempts)
            }
            assertEquals(0, gates); assertTrue(f.calls.isEmpty()); f.close()
        }
    }

    @Test fun publicationCannotBorrowConfiguredCookingCookbookOrDraftCommandHooks() = runTest {
        for (feature in MealKitchenFeature.entries.filterNot { it == MealKitchenFeature.POST_PUBLICATIONS }) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); var gates = 0
            f.bind(feature, create = if (feature == MealKitchenFeature.COOKING) createHooks { gates++ } else null,
                saved = if (feature == MealKitchenFeature.COOKBOOK) savedHooks { gates++ } else null,
                draft = if (feature == MealKitchenFeature.POST_DRAFTS) draftHooks { gates++ } else null)
            val publication = f.bind(MealKitchenFeature.POST_PUBLICATIONS, publication = publicationHooks())
            f.composition.operate(publication) {
                val queue = f.composition.kitchen.commands; value(queue.enqueue(f.lease, siblingIntent(feature)))
                val blocked = value(queue.dispatchConfirmed(f.lease, COMMAND))!!
                assertEquals(CommandIssue.NOT_CONFIGURED, blocked.issue); assertEquals(0, blocked.attempts)
            }
            assertEquals(0, gates); assertTrue(f.calls.isEmpty()); f.close()
        }
    }

    @Test fun configuredFourFeatureHooksCoexistWithoutCrossDelegationOnTheSameJournal() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val gates = mutableListOf<String>(); val observations = mutableListOf<String>()
        val cooking = f.bind(MealKitchenFeature.COOKING, create = createHooks { gates += "createCookSession" })
        val cookbook = f.bind(MealKitchenFeature.COOKBOOK, saved = object : SavedRecipeCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision {
                assertSame(f.lease, lease); assertEquals("deleteSavedRecipe", intent.call.operationId)
                gates += intent.call.operationId; return ExecutionDecision.Ready
            }
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit> {
                assertSame(f.lease, lease); assertEquals("deleteSavedRecipe", intent.call.operationId)
                observations += intent.call.operationId; return PortResult.Value(Unit)
            }
        })
        val draft = f.bind(MealKitchenFeature.POST_DRAFTS, draft = object : PostDraftCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision {
                assertSame(f.lease, lease); assertEquals("deletePostDraft", intent.call.operationId)
                gates += intent.call.operationId; return ExecutionDecision.Ready
            }
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit> {
                assertSame(f.lease, lease); assertEquals("deletePostDraft", intent.call.operationId)
                observations += intent.call.operationId; return PortResult.Value(Unit)
            }
        })
        val publication = f.bind(MealKitchenFeature.POST_PUBLICATIONS, publication = publicationHooks(
            gate = { lease, original ->
                assertSame(f.lease, lease); assertEquals("publishPost", original.call.operationId)
                gates += original.call.operationId; ExecutionDecision.Ready
            }, observer = { lease, original, _ ->
                assertSame(f.lease, lease); assertEquals("publishPost", original.call.operationId)
                observations += original.call.operationId; PortResult.Value(Unit)
            }))
        val owners = listOf(cooking, cookbook, draft, publication)
        val originals = listOf(siblingIntent(MealKitchenFeature.COOKING), siblingIntent(MealKitchenFeature.COOKBOOK, SECOND),
            siblingIntent(MealKitchenFeature.POST_DRAFTS, THIRD), publish(FOURTH))
        val queue = f.composition.kitchen.commands
        for ((owner, original) in owners.zip(originals)) f.composition.operate(owner) {
            assertSame(queue, f.composition.kitchen.commands)
            value(queue.enqueue(f.lease, original))
            assertEquals(CommandPhase.RECEIPT_READY, value(queue.dispatchConfirmed(f.lease, original.commandId))!!.phase)
            assertOriginal(original, value(queue.intent(f.lease, original.commandId))!!)
            val receipt = value(queue.receipt(f.lease, original.commandId))!!
            // Test-only journal archive, not controller/domain publication acknowledgement.
            value(queue.applyReceipt(f.lease, original.commandId, receipt.command.localRevision, emptyList()))
        }
        assertEquals(originals.map { it.call.operationId }, gates)
        assertEquals(listOf("deleteSavedRecipe", "deletePostDraft", "publishPost"), observations)
        assertEquals(gates, f.calls.map { it.operationId }); assertEquals(0, f.store.erases); f.close()
    }

    @Test fun publicationKeepsTheEarlierDraftSocialLaneHeadAndNeverDrainsASibling() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var gates = 0
        val draft = f.bind(MealKitchenFeature.POST_DRAFTS)
        val publication = f.bind(MealKitchenFeature.POST_PUBLICATIONS,
            publication = publicationHooks(gate = { _, _ -> gates++; ExecutionDecision.Ready }))
        val queue = f.composition.kitchen.commands
        val head = f.composition.operate(draft) { value(queue.enqueue(f.lease, siblingIntent(MealKitchenFeature.POST_DRAFTS))) }
        f.composition.operate(publication) {
            value(queue.enqueue(f.lease, publish(SECOND)))
            assertNull(value(queue.dispatchNext(f.lease)))
            assertNull(value(queue.dispatchAutomatic(f.lease, SECOND)))
            assertNull(value(queue.dispatchConfirmed(f.lease, SECOND)))
            assertEquals(0, value(queue.command(f.lease, SECOND))!!.attempts)
        }
        assertEquals(0, gates); assertTrue(f.calls.isEmpty())
        f.composition.operate(draft) { value(queue.discardUnsent(f.lease, COMMAND, head.localRevision)) }
        f.composition.operate(publication) {
            assertEquals(CommandPhase.RECEIPT_READY, value(queue.dispatchConfirmed(f.lease, SECOND))!!.phase)
        }
        assertEquals(1, gates); assertEquals(listOf("publishPost"), f.calls.map { it.operationId }); f.close()
    }

    @Test fun guestCannotReadOrWritePublicationStateAndGuestCookingRemainsAvailable() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler), actor = ActorKind.GUEST); var checked = 0
        val publication = f.bind(MealKitchenFeature.POST_PUBLICATIONS, check = { checked++ }, publication = publicationHooks())
        assertEquals(FailureReason.UNAUTHENTICATED, assertFailsWith<MealFailure> { f.composition.operate(publication) {
            f.composition.store.read(f.scope, KEY)
        } }.reason)
        assertEquals(FailureReason.UNAUTHENTICATED, assertFailsWith<MealFailure> { f.composition.operate(publication) {
            f.composition.store.commit(f.scope, listOf(put()))
        } }.reason)
        assertEquals(FailureReason.UNAUTHENTICATED, assertFailsWith<MealFailure> { f.composition.operate(publication) {
            f.composition.transport.execute(f.lease, publish().call)
        } }.reason)
        val cooking = f.bind(MealKitchenFeature.COOKING)
        f.composition.operate(cooking) { value(f.composition.store.read(f.scope, KEY)) }
        assertEquals(0, checked); assertEquals(1, f.store.reads); assertEquals(0, f.store.writes)
        assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun exactLeaseScopeAndOriginAreRequiredBeforePublicationDomainGate() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var gates = 0
        val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS,
            publication = publicationHooks(gate = { _, _ -> gates++; ExecutionDecision.Ready }))
        f.composition.operate(owner) {
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
                f.composition.store.read(StorageScope("foreign", ActorKind.ACCOUNT, "other"), KEY)
            }.reason)
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
                f.composition.transport.execute(SessionBoundary().activate(f.scope), publish().call)
            }.reason)
            value(f.composition.kitchen.commands.enqueue(f.lease, CommandIntent(COMMAND, CLIENT, publish().call)))
            val blocked = value(f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND))!!
            assertEquals(CommandIssue.AUTH_REQUIRED, blocked.issue); assertEquals(0, blocked.attempts)
        }
        assertEquals(0, gates); assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun offlinePrivateAccessAndConnectivityLossDoNotReachPublicationTransport() = runTest {
        for (onlineAllowed in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler), onlineAllowed = onlineAllowed); f.online = !onlineAllowed
            var before = 0; val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS, before = { before++ })
            f.composition.operate(owner) {
                assertEquals(FailureReason.OFFLINE,
                    assertIs<PortResult.Failure>(f.composition.transport.execute(f.lease, publish().call)).reason)
            }
            assertEquals(0, before); assertTrue(f.calls.isEmpty()); f.close()
        }
    }

    @Test fun leakedWrapperCannotBorrowAnActivePublicationOperationFromAnotherCoroutine() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val running = async { f.composition.operate(owner) { entered.complete(Unit); release.await() } }
        try {
            entered.await()
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
                f.composition.transport.execute(f.lease, publish().call)
            }.reason)
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
                f.composition.store.read(f.scope, KEY)
            }.reason)
            release.complete(Unit); running.await()
            assertEquals(0, f.store.reads); assertTrue(f.calls.isEmpty())
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { running.cancelAndJoin() }
            f.close()
        }
    }

    @Test fun latePublicationChildCannotUseTheNextBorrowersAdmission() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val publication = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
        val cooking = f.bind(MealKitchenFeature.COOKING)
        val release = CompletableDeferred<Unit>(); val detached = SupervisorJob(); lateinit var late: Deferred<FailureReason>
        try {
            f.composition.operate(publication) {
                val captured = currentCoroutineContext().minusKey(Job) + detached
                late = CoroutineScope(captured).async {
                    release.await()
                    assertFailsWith<MealFailure> { f.composition.transport.execute(f.lease, publish().call) }.reason
                }
            }
            withTimeout(5_000) { f.composition.operate(cooking) {
                release.complete(Unit); assertEquals(FailureReason.STALE_SESSION, late.await())
                value(f.composition.store.read(f.scope, KEY))
            } }
            assertEquals(1, f.store.reads); assertTrue(f.calls.isEmpty())
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { detached.cancelAndJoin() }
            f.close()
        }
    }

    @Test fun oldSiblingContextCannotUseCurrentPublicationAdmission() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val sibling = f.bind(MealKitchenFeature.POST_DRAFTS)
        val publication = f.bind(MealKitchenFeature.POST_PUBLICATIONS); lateinit var old: CoroutineContext
        f.composition.operate(sibling) { old = currentCoroutineContext().minusKey(Job) }
        f.composition.operate(publication) {
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
                withContext(old) { f.composition.transport.execute(f.lease, publish().call) }
            }.reason)
        }
        assertTrue(f.calls.isEmpty()); f.close()
    }

    @Test fun releasedPublicationCannotDeliverNonCooperativeTransportReplyOrInvalidateSibling() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var after = 0
        val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS, after = { _, _ -> after++ })
        val sibling = f.bind(MealKitchenFeature.COOKBOOK)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.exchange = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; PortResult.Value(reply("publishPost")) }
        val running = async { assertFailsWith<MealFailure> {
            f.composition.operate(owner) { f.composition.transport.execute(f.lease, publish().call) }
        } }
        try {
            entered.await(); f.composition.release(owner); release.complete(Unit)
            assertEquals(FailureReason.STALE_SESSION, running.await().reason); assertEquals(0, after)
            f.composition.operate(sibling) { value(f.composition.store.read(f.scope, KEY)) }
            assertTrue(f.boundary.isCurrent(f.lease)); assertEquals(0, f.store.erases)
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { running.cancelAndJoin() }
            f.close()
        }
    }

    @Test fun cancelledPublicationCannotDeliverLateReplyOrInvalidateSibling() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var after = 0
        val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS, after = { _, _ -> after++ })
        val sibling = f.bind(MealKitchenFeature.COOKING)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.exchange = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; PortResult.Value(reply("publishPost")) }
        val running = async { f.composition.operate(owner) { f.composition.transport.execute(f.lease, publish().call) } }
        try {
            entered.await(); running.cancel(); release.complete(Unit); assertFailsWith<CancellationException> { running.await() }
            f.composition.operate(sibling) { value(f.composition.store.read(f.scope, KEY)) }
            assertEquals(0, after); assertTrue(f.boundary.isCurrent(f.lease))
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { running.cancelAndJoin() }
            f.close()
        }
    }

    @Test fun queuedPublicationIsRecheckedAfterAnotherOwnerReleasesTheMutex() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val sibling = f.bind(MealKitchenFeature.COOKING)
        var eligible = true
        val publication = f.bind(MealKitchenFeature.POST_PUBLICATIONS,
            check = { if (!eligible) mealFail(FailureReason.CONFLICT) }, publication = publicationHooks())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var admitted = false
        val held = async { f.composition.operate(sibling) { entered.complete(Unit); release.await() } }
        var queued: Deferred<MealFailure>? = null
        try {
            entered.await()
            val waiting = async { assertFailsWith<MealFailure> {
                f.composition.operate(publication) { admitted = true; f.composition.transport.execute(f.lease, publish().call) }
            } }.also { queued = it }
            runCurrent(); eligible = false; release.complete(Unit); held.await()
            assertEquals(FailureReason.CONFLICT, waiting.await().reason); assertFalse(admitted); assertTrue(f.calls.isEmpty())
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { queued?.cancelAndJoin(); held.cancelAndJoin() }
            f.close()
        }
    }

    @Test fun publicationGateLeaseChangePreventsClaimAndSend() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var newer: SessionLease? = null
        val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS,
            publication = publicationHooks(gate = { _, _ -> newer = f.boundary.activate(f.scope); ExecutionDecision.Ready }))
        f.composition.operate(owner) { value(f.composition.kitchen.commands.enqueue(f.lease, publish())) }
        val writes = f.store.writes
        assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
            f.composition.operate(owner) { f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND) }
        }.reason)
        assertEquals(writes, f.store.writes); assertTrue(f.calls.isEmpty()); assertTrue(f.boundary.isCurrent(newer!!)); f.close()
    }

    @Test fun publicationReplyObserverLeaseChangePreventsOutcomePersistence() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var observed = 0; var writesAtObservation = 0
        val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS, publication = publicationHooks(observer = { _, _, _ ->
            observed++; writesAtObservation = f.store.writes; f.boundary.activate(f.scope); PortResult.Value(Unit)
        }))
        f.composition.operate(owner) { value(f.composition.kitchen.commands.enqueue(f.lease, publish())) }
        assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
            f.composition.operate(owner) { f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND) }
        }.reason)
        assertEquals(1, observed); assertEquals(1, f.calls.size); assertEquals(writesAtObservation, f.store.writes); f.close()
    }

    @Test fun publicationHooksCanReadOriginalJournalWithoutReenteringCompositionOrQueueLocks() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var reads = 0
        val original = publish(saved = true)
        val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS, publication = publicationHooks(gate = { lease, seen ->
            assertSame(f.lease, lease); assertOriginal(original, seen)
            assertOriginal(original, value(f.composition.kitchen.commands.intent(lease, seen.commandId))!!)
            value(f.composition.store.read(f.scope, KEY)); reads++; ExecutionDecision.Ready
        }, observer = { lease, seen, reply ->
            assertSame(f.lease, lease); assertOriginal(original, seen); assertEquals(201, reply.status)
            assertEquals(CommandPhase.IN_FLIGHT, value(f.composition.kitchen.commands.command(lease, seen.commandId))!!.phase)
            reads++; PortResult.Value(Unit)
        }))
        withTimeout(5_000) { f.composition.operate(owner) {
            value(f.composition.kitchen.commands.enqueue(f.lease, original))
            assertEquals(CommandPhase.RECEIPT_READY,
                value(f.composition.kitchen.commands.dispatchConfirmed(f.lease, COMMAND))!!.phase)
        } }
        assertEquals(2, reads); assertEquals(1, f.calls.size); f.close()
    }

    @Test fun publicationBeforeTransportAndBeforeCommitFencesRemainEffective() = runTest {
        for (blockTransport in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); var deny = false; var after = 0
            val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS,
                onCommit = { if (deny && !blockTransport) mealFail(FailureReason.CONFLICT) },
                before = { if (deny && blockTransport) mealFail(FailureReason.CONFLICT) },
                after = { _, _ -> after++ }, publication = publicationHooks())
            f.composition.operate(owner) { value(f.composition.kitchen.commands.enqueue(f.lease, publish())) }
            deny = true
            f.composition.operate(owner) {
                val queue = f.composition.kitchen.commands
                val result = queue.dispatchConfirmed(f.lease, COMMAND)
                if (blockTransport) {
                    // The queue has claimed the attempt before this domain transport fence.
                    // Its conservative unknown outcome is not an actual network send or ACK.
                    val pending = value(result)!!
                    assertEquals(CommandIssue.OUTCOME_UNKNOWN, pending.issue); assertEquals(1, pending.attempts)
                    assertEquals(CommandPhase.AWAITING_CONFIRMATION, pending.phase)
                } else assertIs<PortResult.Failure>(result)
                assertNull(value(queue.receipt(f.lease, COMMAND)))
            }
            assertEquals(0, after); assertTrue(f.calls.isEmpty()); f.close()
        }
    }

    private class Fixture(val dispatcher: CoroutineDispatcher, actor: ActorKind = ActorKind.ACCOUNT, onlineAllowed: Boolean = true) {
        val scope = StorageScope("synthetic-publication-composition", actor, "private-owner")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = Store(scope)
        var online = true; val calls = mutableListOf<ApiCall>()
        var exchange: suspend (ApiCall) -> PortResult<ApiReply> = { PortResult.Value(reply(it.operationId)) }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@Fixture.lease, lease); calls += call; return exchange(call)
            }
        }, onlineAllowed)
        val composition = MealKitchenComposition(access, boundary, dispatcher, EpochClock { NOW },
            ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE })
        private val bindings = mutableListOf<MealKitchenComposition.Borrower>()
        fun bind(feature: MealKitchenFeature, check: suspend () -> Unit = {}, onCommit: (List<StoreMutation>) -> Unit = {},
            before: suspend (ApiCall) -> Unit = {}, after: suspend (ApiCall, ApiReply) -> Unit = { _, _ -> },
            create: CommandExecutionGate? = null, saved: SavedRecipeCommandHooks? = null,
            draft: PostDraftCommandHooks? = null, publication: PostPublicationCommandHooks? = null) =
            composition.bind(feature, object : MealKitchenHooks {
                override suspend fun checkCurrent() = check()
                override fun beforeCommit(mutations: List<StoreMutation>) = onCommit(mutations)
                override suspend fun beforeTransport(call: ApiCall) = before(call)
                override suspend fun afterTransport(call: ApiCall, reply: ApiReply) = after(call, reply)
                override val createExecutionGate = create
                override val savedRecipeCommands = saved
                override val postDraftCommands = draft
                override val postPublicationCommands = publication
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
        const val ORIGIN = "00000000-0000-4000-8000-000000000031"
        const val COMMAND = "00000000-0000-4000-8000-000000000032"
        const val CLIENT = "00000000-0000-4000-8000-000000000033"
        const val SERVER = "00000000-0000-4000-8000-000000000034"
        const val SECOND = "00000000-0000-4000-8000-000000000035"
        const val THIRD = "00000000-0000-4000-8000-000000000036"
        const val FOURTH = "00000000-0000-4000-8000-000000000037"
        const val NOW = 1_800_000_000_000L
        val KEY = RecordKey("test.publication.composition", ORIGIN)
        fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        fun put() = StoreMutation.Put(KEY, null, 1, bytes("synthetic"))
        fun publish(id: String = COMMAND, saved: Boolean = false): CommandIntent {
            val pair = if (saved) """, "draftId":"$SERVER", "draftVersion":9007199254740993""" else ""
            return CommandIntent(id, ORIGIN, ApiCall("publishPost", body = bytes("""{ "caption":"Synthetic reviewed moment", "mediaIds":[], "audience":{"kind":"self","circleIds":[]}, "keepOnPlate":false, "allowRecipeSaves":false, "saveDisclosureVersion":"synthetic-disclosure-v1", "clientDraftId":"$CLIENT"$pair }"""),
                idempotencyKey = SecretText(id)))
        }
        fun siblingIntent(feature: MealKitchenFeature, id: String = COMMAND) = CommandIntent(id, ORIGIN, when (feature) {
            MealKitchenFeature.COOKING -> ApiCall("createCookSession", body = bytes("""{"planId":"$CLIENT","deviceSequence":0}"""), idempotencyKey = SecretText(id))
            MealKitchenFeature.COOKBOOK -> ApiCall("deleteSavedRecipe", pathParameters = mapOf("savedRecipeId" to SERVER), ifMatch = "\"1\"", idempotencyKey = SecretText(id))
            MealKitchenFeature.POST_DRAFTS -> ApiCall("deletePostDraft", pathParameters = mapOf("draftId" to SERVER), ifMatch = "\"1\"", idempotencyKey = SecretText(id))
            MealKitchenFeature.POST_PUBLICATIONS -> error("Use exact publication helper")
        })
        fun reply(operation: String): ApiReply = when (operation) {
            "deleteSavedRecipe", "deletePostDraft" -> ApiReply(204, null)
            "createCookSession" -> ApiReply(201, bytes("""{"id":"$SERVER","version":1,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","planId":"$CLIENT","status":"active","currentStepId":"step-one","completedStepIds":[],"deviceSequence":0,"timers":[]}"""),
                etag = "\"1\"", contentType = "application/json")
            else -> ApiReply(201, bytes("""{"id":"$SERVER","version":1,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","author":{"userId":"$CLIENT","displayName":"Synthetic author","handle":"synthetic-author"},"caption":"Synthetic reviewed moment","mediaIds":[],"audience":{"kind":"self","circleIds":[]},"status":"published","publishedAt":"2026-09-14T00:00:00Z","expiresAt":"2026-09-15T00:00:00Z","keepOnPlate":false,"savePolicy":{"allowFutureSaves":false,"policyVersion":1,"disclosureVersion":"synthetic-disclosure-v1"},"aclVersion":1,"capabilities":[],"reactionCounts":[]}"""),
                etag = "\"1\"", contentType = "application/json")
        }
        fun createHooks(called: () -> Unit = {}) = CommandExecutionGate { _, _ -> called(); ExecutionDecision.Ready }
        fun savedHooks(called: () -> Unit = {}) = object : SavedRecipeCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision { called(); return ExecutionDecision.Ready }
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply) = PortResult.Value(Unit)
        }
        fun draftHooks(called: () -> Unit = {}) = object : PostDraftCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision { called(); return ExecutionDecision.Ready }
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply) = PortResult.Value(Unit)
        }
        fun publicationHooks(gate: suspend (SessionLease, CommandIntent) -> ExecutionDecision = { _, _ -> ExecutionDecision.Ready },
            observer: suspend (SessionLease, CommandIntent, ApiReply) -> PortResult<Unit> = { _, _, _ -> PortResult.Value(Unit) }) = object : PostPublicationCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent) = gate(lease, intent)
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply) = observer(lease, intent, reply)
        }
        fun assertOriginal(expected: CommandIntent, actual: CommandIntent) {
            assertEquals(expected.commandId, actual.commandId); assertEquals(expected.originBinding, actual.originBinding)
            assertEquals(expected.dependencyCommandIds, actual.dependencyCommandIds); assertEquals(expected.call.operationId, actual.call.operationId)
            assertEquals(expected.call.pathParameters, actual.call.pathParameters); assertEquals(expected.call.queryParameters, actual.call.queryParameters)
            assertEquals(expected.call.ifMatch, actual.call.ifMatch)
            assertEquals(expected.call.idempotencyKey!!.use { it }, actual.call.idempotencyKey!!.use { it })
            assertContentEquals(expected.call.body?.copyForCodec(), actual.call.body?.copyForCodec())
        }
        fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
    }
}
