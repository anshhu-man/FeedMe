package com.feedme.mealflow

import com.feedme.core.ports.*
import com.feedme.kitchen.PostDraftCommandHooks
import com.feedme.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Real operation admission and queue over synthetic ports. No coordinator, journal claim,
 * publication consent, domain mutation or provider/native acceptance is supplied. */
@OptIn(ExperimentalCoroutinesApi::class)
class MealKitchenComposerPermitTest {
    @Test fun bothSocialPurposesMintOpaqueStablePerOperationTokensWithoutIo() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            for (feature in SOCIAL) {
                val owner = f.bind(feature)
                f.composition.operate(owner) {
                    val token = f.composition.composerPermit(owner)
                    assertSame(token, f.composition.composerPermit(owner))
                    f.composition.requireComposerPermit(token, owner)
                    assertEquals("ComposerOperationPermit(<redacted>)", token.toString())
                }
            }
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun cookingAndCookbookCannotMintOrValidateComposerPermission() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            for (feature in listOf(MealKitchenFeature.COOKING, MealKitchenFeature.COOKBOOK)) {
                val owner = f.bind(feature)
                f.composition.operate(owner) {
                    failure(FailureReason.NOT_CONFIGURED) { f.composition.composerPermit(owner) }
                    failure(FailureReason.NOT_CONFIGURED) { f.composition.requireComposerPermit(ComposerOperationPermit(), owner) }
                }
            }
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun guestCannotMintWhileExistingGuestCookingAdmissionStillWorks() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler), identity = Identity(ActorKind.GUEST))
        try {
            val draft = f.bind(MealKitchenFeature.POST_DRAFTS)
            val publication = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
            val cooking = f.bind(MealKitchenFeature.COOKING)
            for (owner in listOf(draft, publication)) {
                var entered = false
                failure(FailureReason.UNAUTHENTICATED) {
                    f.composition.operate(owner) { entered = true; f.composition.composerPermit(owner) }
                }
                assertFalse(entered)
            }
            var cooked = false
            f.composition.operate(cooking) { cooked = true }
            assertTrue(cooked); f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun permitCannotCreateAdmissionOutsideOperateOrAfterReturn() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.bind(MealKitchenFeature.POST_DRAFTS)
        try {
            failure(FailureReason.STALE_SESSION) { f.composition.composerPermit(owner) }
            val token = f.composition.operate(owner) { f.composition.composerPermit(owner) }
            failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(token, owner) }
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun forgedTokenIsRejectedBeforeAndAfterTheActualTokenIsRequested() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
        try {
            f.composition.operate(owner) {
                val forged = ComposerOperationPermit()
                failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(forged, owner) }
                val actual = f.composition.composerPermit(owner)
                assertNotSame(forged, actual)
                failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(forged, owner) }
                f.composition.requireComposerPermit(actual, owner)
            }
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun expectedBorrowerMustBeExactAndCannotSelectASiblingPurpose() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val draft = f.bind(MealKitchenFeature.POST_DRAFTS); val publication = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
        try {
            for ((owner, sibling) in listOf(draft to publication, publication to draft)) {
                f.composition.operate(owner) {
                    val token = f.composition.composerPermit(owner)
                    failure(FailureReason.STALE_SESSION) { f.composition.composerPermit(sibling) }
                    failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(token, sibling) }
                    val fabricated = MealKitchenComposition.Borrower(f.composition, owner.feature, owner.hooks)
                    failure(FailureReason.STALE_SESSION) { f.composition.composerPermit(fabricated) }
                    failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(token, fabricated) }
                    f.composition.requireComposerPermit(token, owner)
                }
            }
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun sameBorrowerNextOperationCannotReuseTheOldToken() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
        try {
            val prior = f.composition.operate(owner) { f.composition.composerPermit(owner) }
            f.composition.operate(owner) {
                failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(prior, owner) }
                val fresh = f.composition.composerPermit(owner)
                assertNotSame(prior, fresh)
                failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(prior, owner) }
                f.composition.requireComposerPermit(fresh, owner)
            }
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun foreignCompositionCannotAdoptPermitEvenWithIdenticalActualSessionIdentity() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); val identity = Identity()
        val first = Fixture(dispatcher, identity); val second = Fixture(dispatcher, identity)
        val a = first.bind(MealKitchenFeature.POST_DRAFTS); val b = second.bind(MealKitchenFeature.POST_DRAFTS)
        try {
            val token = first.composition.operate(a) { first.composition.composerPermit(a) }
            second.composition.operate(b) {
                failure(FailureReason.STALE_SESSION) { second.composition.requireComposerPermit(token, b) }
                failure(FailureReason.STALE_SESSION) { second.composition.composerPermit(a) }
                failure(FailureReason.STALE_SESSION) { second.composition.requireComposerPermit(token, a) }
                second.composition.requireComposerPermit(second.composition.composerPermit(b), b)
            }
            first.assertNoIo(); second.assertNoIo()
        } finally { first.close(); second.close() }
    }

    @Test fun foreignStoreOriginAndSameScopeNewLeaseNeverRebindHistoricalToken() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); val shared = Identity()
        val first = Fixture(dispatcher, shared); val a = first.bind(MealKitchenFeature.POST_PUBLICATIONS)
        val alternatives = listOf(
            Fixture(dispatcher, shared, store = Store(shared.scope)),
            Fixture(dispatcher, shared, origin = OTHER_ORIGIN),
            Fixture(dispatcher, Identity()),
        )
        try {
            val token = first.composition.operate(a) { first.composition.composerPermit(a) }
            for (other in alternatives) {
                val b = other.bind(MealKitchenFeature.POST_PUBLICATIONS)
                other.composition.operate(b) {
                    failure(FailureReason.STALE_SESSION) { other.composition.requireComposerPermit(token, b) }
                    other.composition.requireComposerPermit(other.composition.composerPermit(b), b)
                }
                other.assertNoIo()
            }
            first.assertNoIo()
        } finally { first.close(); alternatives.forEach { it.close() } }
    }

    @Test fun unrelatedConcurrentCoroutineCannotBorrowTheCurrentlyActiveToken() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
        val entered = CompletableDeferred<ComposerOperationPermit>(); val release = CompletableDeferred<Unit>()
        val running = async { f.composition.operate(owner) {
            entered.complete(f.composition.composerPermit(owner)); release.await()
        } }
        try {
            val token = entered.await()
            failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(token, owner) }
            failure(FailureReason.STALE_SESSION) { f.composition.composerPermit(owner) }
            release.complete(Unit); running.await(); f.assertNoIo()
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { running.cancelAndJoin() }
            f.close()
        }
    }

    @Test fun sameOperationNestedContextAndActiveInheritedChildRemainAllowed() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.bind(MealKitchenFeature.POST_DRAFTS)
        try {
            f.composition.operate(owner) {
                val token = f.composition.composerPermit(owner)
                withContext(f.dispatcher + CoroutineName("synthetic-nested-hook")) {
                    f.composition.requireComposerPermit(token, owner)
                    assertSame(token, f.composition.composerPermit(owner))
                }
                coroutineScope {
                    async { f.composition.requireComposerPermit(token, owner) }.await()
                }
                f.composition.requireComposerPermit(token, owner)
            }
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun nestedActualQueueGateCanRequirePermitAndReadOriginalWithoutReenteringOperate() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var token: ComposerOperationPermit? = null; var gates = 0
        lateinit var owner: MealKitchenComposition.Borrower
        owner = f.bind(MealKitchenFeature.POST_DRAFTS, draft = object : PostDraftCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision {
                f.composition.requireComposerPermit(checkNotNull(token), owner)
                val read = value(f.composition.kitchen.commands.intent(lease, intent.commandId))!!
                f.composition.requireComposerPermit(checkNotNull(token), owner)
                assertEquals(COMMAND, read.commandId)
                assertContentEquals(intent.call.body!!.copyForCodec(), read.call.body!!.copyForCodec())
                gates++; return ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
            }
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit> =
                error("No transport is authorized by this fixture")
        })
        try {
            withTimeout(5_000) { f.composition.operate(owner) {
                token = f.composition.composerPermit(owner)
                val queue = f.composition.kitchen.commands
                value(queue.enqueue(f.lease, draft()))
                f.composition.requireComposerPermit(checkNotNull(token), owner)
                val pending = value(queue.dispatchConfirmed(f.lease, COMMAND))!!
                assertEquals(CommandIssue.NOT_CONFIGURED, pending.issue); assertEquals(0, pending.attempts)
                f.composition.requireComposerPermit(checkNotNull(token), owner)
                assertNull(value(queue.receipt(f.lease, COMMAND)))
            } }
            assertEquals(1, gates); assertEquals(0, f.calls); assertEquals(0, f.store.erases)
        } finally { f.close() }
    }

    @Test fun expiredCapturedContextCannotAuthorizeEitherOldOrFreshPermit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.bind(MealKitchenFeature.POST_DRAFTS)
        lateinit var old: CoroutineContext
        try {
            val prior = f.composition.operate(owner) {
                old = currentCoroutineContext().minusKey(Job)
                f.composition.composerPermit(owner)
            }
            f.composition.operate(owner) {
                val fresh = f.composition.composerPermit(owner)
                withContext(old) {
                    failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(prior, owner) }
                    failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(fresh, owner) }
                    failure(FailureReason.STALE_SESSION) { f.composition.composerPermit(owner) }
                }
                f.composition.requireComposerPermit(fresh, owner)
            }
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun detachedLateChildCannotCrossIntoSiblingOperationOrReviveItsToken() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val first = f.bind(MealKitchenFeature.POST_DRAFTS); val second = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
        val release = CompletableDeferred<Unit>(); val detached = SupervisorJob()
        lateinit var child: Deferred<Unit>
        try {
            f.composition.operate(first) {
                val token = f.composition.composerPermit(first)
                val inherited = currentCoroutineContext().minusKey(Job) + detached
                child = CoroutineScope(inherited).async {
                    release.await()
                    failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(token, first) }
                    failure(FailureReason.STALE_SESSION) { f.composition.composerPermit(second) }
                }
            }
            withTimeout(5_000) { f.composition.operate(second) {
                val fresh = f.composition.composerPermit(second)
                release.complete(Unit); child.await()
                f.composition.requireComposerPermit(fresh, second)
            } }
            f.assertNoIo()
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { detached.cancelAndJoin() }
            f.close()
        }
    }

    @Test fun childPausedInsideOldOperationCheckCannotMintOrValidateAfterSameBorrowerStartsAgain() = runTest {
        for (mint in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val detached = SupervisorJob(); var admittedAfterWait = false; var checkWaits = 0
            val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS, check = {
                if (currentCoroutineContext()[CoroutineName]?.name == "synthetic-old-permit-check") {
                    checkWaits++; entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            })
            lateinit var child: Deferred<Unit>
            try {
                withTimeout(5_000) { f.composition.operate(owner) {
                    val oldToken = if (mint) null else f.composition.composerPermit(owner)
                    val inherited = currentCoroutineContext().minusKey(Job) + detached +
                        CoroutineName("synthetic-old-permit-check")
                    child = CoroutineScope(inherited).async {
                        failure(FailureReason.STALE_SESSION) {
                            if (mint) f.composition.composerPermit(owner)
                            else f.composition.requireComposerPermit(checkNotNull(oldToken), owner)
                            admittedAfterWait = true
                        }
                    }
                    // The child has passed current()'s first Operation check and is paused
                    // inside domain.checkCurrent(), not merely waiting before calling it.
                    entered.await(); assertEquals(1, checkWaits); assertFalse(child.isCompleted)
                } }
                withTimeout(5_000) { f.composition.operate(owner) {
                    val fresh = f.composition.composerPermit(owner)
                    release.complete(Unit); child.await()
                    assertFalse(admittedAfterWait); assertEquals(1, checkWaits)
                    f.composition.requireComposerPermit(fresh, owner)
                } }
                assertTrue(f.identity.boundary.isCurrent(f.lease)); f.assertNoIo()
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) { detached.cancelAndJoin() }
                f.close()
            }
        }
    }

    @Test fun releasedBorrowerPermitFailsAndSiblingRetainsItsOwnAdmission() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val first = f.bind(MealKitchenFeature.POST_DRAFTS); val second = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
        try {
            failure(FailureReason.STALE_SESSION) { f.composition.operate(first) {
                val token = f.composition.composerPermit(first)
                f.composition.release(first)
                failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(token, first) }
                failure(FailureReason.STALE_SESSION) { f.composition.composerPermit(first) }
            } }
            f.composition.operate(second) {
                f.composition.requireComposerPermit(f.composition.composerPermit(second), second)
            }
            assertTrue(f.identity.boundary.isCurrent(f.lease)); f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun lastBorrowerCloseAndReplacementBindingCannotReviveReturnedPermit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.bind(MealKitchenFeature.POST_DRAFTS)
        try {
            val token = f.composition.operate(owner) { f.composition.composerPermit(owner) }
            f.composition.release(owner)
            val replacement = f.bind(MealKitchenFeature.POST_DRAFTS)
            failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(token, owner) }
            failure(FailureReason.STALE_SESSION) {
                f.composition.operate(replacement) { f.composition.requireComposerPermit(token, replacement) }
            }
            assertTrue(f.identity.boundary.isCurrent(f.lease)); f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun leaseReplacementDuringOperationInvalidatesMintAndRequireWithoutIo() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
        var replacement: SessionLease? = null
        try {
            failure(FailureReason.STALE_SESSION) { f.composition.operate(owner) {
                val token = f.composition.composerPermit(owner)
                replacement = f.identity.boundary.activate(f.scope)
                failure(FailureReason.STALE_SESSION) { f.composition.requireComposerPermit(token, owner) }
                failure(FailureReason.STALE_SESSION) { f.composition.composerPermit(owner) }
            } }
            assertTrue(f.identity.boundary.isCurrent(checkNotNull(replacement))); f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun suspendedDomainCheckRechecksReleaseBeforeReturningMintOrRequirement() = runTest {
        for (mint in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); var suspendCheck = false
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val owner = f.bind(MealKitchenFeature.POST_DRAFTS, check = {
                if (suspendCheck) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            })
            val sibling = f.bind(MealKitchenFeature.COOKING)
            val running = async { assertFailsWith<MealFailure> { f.composition.operate(owner) {
                val token = f.composition.composerPermit(owner)
                suspendCheck = true
                if (mint) f.composition.composerPermit(owner) else f.composition.requireComposerPermit(token, owner)
            } } }
            try {
                entered.await(); f.composition.release(owner); release.complete(Unit)
                assertEquals(FailureReason.STALE_SESSION, running.await().reason)
                f.composition.operate(sibling) {}
                assertTrue(f.identity.boundary.isCurrent(f.lease)); f.assertNoIo()
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) { running.cancelAndJoin() }
                f.close()
            }
        }
    }

    @Test fun cancellationDuringSuspendedCheckCannotDeliverPermitOrSuccessfulRequire() = runTest {
        for (mint in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); var suspendCheck = false; var delivered = false
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val owner = f.bind(MealKitchenFeature.POST_PUBLICATIONS, check = {
                if (suspendCheck) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            })
            val running = async { f.composition.operate(owner) {
                val token = f.composition.composerPermit(owner)
                suspendCheck = true
                if (mint) f.composition.composerPermit(owner) else f.composition.requireComposerPermit(token, owner)
                delivered = true
            } }
            try {
                entered.await(); running.cancel(); release.complete(Unit)
                assertFailsWith<CancellationException> { running.await() }
                assertFalse(delivered); suspendCheck = false
                f.composition.operate(owner) { f.composition.requireComposerPermit(f.composition.composerPermit(owner), owner) }
                assertTrue(f.identity.boundary.isCurrent(f.lease)); f.assertNoIo()
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) { running.cancelAndJoin() }
                f.close()
            }
        }
    }

    @Test fun domainGenerationDenialIsRecheckedOnEveryUseWithoutLosingSibling() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var eligible = true
        val owner = f.bind(MealKitchenFeature.POST_DRAFTS, check = { if (!eligible) mealFail(FailureReason.CONFLICT) })
        val sibling = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
        try {
            failure(FailureReason.CONFLICT) { f.composition.operate(owner) {
                val token = f.composition.composerPermit(owner)
                eligible = false
                failure(FailureReason.CONFLICT) { f.composition.requireComposerPermit(token, owner) }
                failure(FailureReason.CONFLICT) { f.composition.composerPermit(owner) }
            } }
            f.composition.operate(sibling) { f.composition.requireComposerPermit(f.composition.composerPermit(sibling), sibling) }
            f.assertNoIo()
        } finally { f.close() }
    }

    private class Identity(actor: ActorKind = ActorKind.ACCOUNT) {
        val scope = StorageScope("synthetic-composer-permit", actor, "private-owner")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = Store(scope)
    }
    private class Fixture(
        val dispatcher: CoroutineDispatcher,
        val identity: Identity = Identity(),
        val store: Store = identity.store,
        origin: String = ORIGIN,
    ) {
        val scope = identity.scope; val lease = identity.lease; var calls = 0
        val composition = MealKitchenComposition(
            AuthenticatedMealPlanningAccess(lease, origin, store, object : AccountTransport {
                override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                    calls++; return PortResult.Failure(FailureReason.NOT_CONFIGURED)
                }
            }, true), identity.boundary, dispatcher, EpochClock { NOW }, ConnectivityPort { Connectivity.ONLINE })
        private val bindings = mutableListOf<MealKitchenComposition.Borrower>()
        fun bind(feature: MealKitchenFeature, check: suspend () -> Unit = {}, draft: PostDraftCommandHooks? = null) =
            composition.bind(feature, object : MealKitchenHooks {
                override suspend fun checkCurrent() = check()
                override val postDraftCommands = draft
            }).also { bindings += it }
        fun assertNoIo() {
            assertEquals(0, store.reads); assertEquals(0, store.writes); assertEquals(0, store.erases); assertEquals(0, calls)
        }
        fun close() { bindings.forEach { composition.release(it) } }
    }
    private class Store(private val scope: StorageScope) : PrivateStateStore {
        private val records = mutableMapOf<RecordKey, PrivateRecord>()
        private val revisions = mutableMapOf<RecordKey, Long>()
        var reads = 0; var writes = 0; var erases = 0
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            assertEquals(this.scope, scope); reads++; return PortResult.Value(records[key])
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            assertEquals(this.scope, scope)
            if (mutations.any { records[it.key]?.revision != it.expectedRevision }) return PortResult.Failure(FailureReason.CONFLICT)
            val result = mutations.associate { mutation ->
                val revision = (revisions[mutation.key] ?: 0L) + 1; revisions[mutation.key] = revision
                mutation.key to when (mutation) {
                    is StoreMutation.Put -> { records[mutation.key] = PrivateRecord(revision, mutation.schemaVersion, mutation.payload); revision }
                    is StoreMutation.Delete -> { records.remove(mutation.key); null }
                }
            }
            writes++; return PortResult.Value(result)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> {
            erases++; return PortResult.Failure(FailureReason.NOT_CONFIGURED)
        }
    }
    private companion object {
        const val ORIGIN = "00000000-0000-4000-8000-000000000051"
        const val OTHER_ORIGIN = "00000000-0000-4000-8000-000000000052"
        const val COMMAND = "00000000-0000-4000-8000-000000000053"
        const val CLIENT = "00000000-0000-4000-8000-000000000054"
        const val NOW = 1_800_000_000_000L
        val SOCIAL = listOf(MealKitchenFeature.POST_DRAFTS, MealKitchenFeature.POST_PUBLICATIONS)
        fun draft() = CommandIntent(COMMAND, ORIGIN, ApiCall("createPostDraft",
            body = PrivateBytes("""{"clientDraftId":"00000000-0000-4000-8000-000000000054","caption":"Synthetic private text"}""".encodeToByteArray()),
            idempotencyKey = SecretText(COMMAND)))
        suspend fun failure(reason: FailureReason, action: suspend () -> Unit) {
            assertEquals(reason, assertFailsWith<MealFailure> { action() }.reason)
        }
        fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
    }
}
