package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Actual composition/lease/operation admission over explicitly SYNTHETIC trusted mapping and
 * deny-I/O ports. Not a native verifier, provider, bootstrap, publication or queue-ACK test. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostPublicationPrincipalTest {
    @Test fun constructionDoesNotResolveReadCredentialsOrPerformAnyIo() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            assertEquals(0, f.source.resolves); assertEquals(0, f.source.requirements); assertEquals(0, f.source.syncChecks)
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun opaqueStorageActorNeverBecomesTheCanonicalAccountId() = runTest {
        for (actor in listOf("opaque-storage-owner-not-a-uuid", OTHER_ACCOUNT)) {
            val f = Fixture(StandardTestDispatcher(testScheduler), scope = StorageScope("test", ActorKind.ACCOUNT, actor))
            try {
                val mapped = f.run { f.admission.resolve(it) }
                assertEquals(ACCOUNT, mapped.canonicalUserId); assertNotEquals(actor, mapped.canonicalUserId)
                assertSame(f.lease, f.source.lastBinding!!.lease); assertSame(f.boundary, f.source.lastBinding!!.boundary)
                assertEquals(ORIGIN, f.source.lastBinding!!.origin); assertEquals("test", f.source.lastBinding!!.environment)
                assertEquals(1, f.source.resolves); assertEquals(1, f.source.requirements); assertTrue(f.source.syncChecks >= 1)
                f.assertNoIo()
            } finally { f.close() }
        }
    }

    @Test fun missingRealMappingStaysNotConfiguredAndDoesNotBlockSiblingLocalAdmission() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.source.resolveFailure = FailureReason.NOT_CONFIGURED
        try {
            failure(FailureReason.NOT_CONFIGURED) { f.run { f.admission.resolve(it) } }
            val sibling = f.bind(MealKitchenFeature.POST_DRAFTS)
            var entered = false; f.composition.operate(sibling) { entered = true }
            assertTrue(entered); assertEquals(0, f.source.requirements); f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun guestCannotUseAccountPrincipalAdmissionOrCallTrustedSource() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler), scope = StorageScope("test", ActorKind.GUEST, "guest-owner"))
        try {
            failure(FailureReason.UNAUTHENTICATED) { f.run { f.admission.resolve(it) } }
            assertEquals(0, f.source.resolves); f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun onlyTheActualPublicationBorrowerCanOwnAdmission() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val other = Fixture(StandardTestDispatcher(testScheduler))
        try {
            for (feature in listOf(MealKitchenFeature.POST_DRAFTS, MealKitchenFeature.COOKING, MealKitchenFeature.COOKBOOK)) {
                val sibling = f.bind(feature)
                assertEquals(FailureReason.NOT_CONFIGURED, assertFailsWith<MealFailure> {
                    PublicationPrincipalAdmission(f.composition, sibling, f.source)
                }.reason)
            }
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
                PublicationPrincipalAdmission(f.composition, other.borrower, f.source)
            }.reason)
            assertEquals(0, f.source.resolves); f.assertNoIo(); other.assertNoIo()
        } finally { f.close(); other.close() }
    }

    @Test fun absentForgedExpiredAndSiblingOperationPermitsCannotTriggerResolution() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            failure(FailureReason.STALE_SESSION) { f.admission.resolve(ComposerOperationPermit()) }
            lateinit var old: ComposerOperationPermit
            f.run { old = it; failure(FailureReason.STALE_SESSION) { f.admission.resolve(ComposerOperationPermit()) } }
            failure(FailureReason.STALE_SESSION) { f.admission.resolve(old) }
            f.run { failure(FailureReason.STALE_SESSION) { f.admission.resolve(old) } }
            val sibling = f.bind(MealKitchenFeature.POST_DRAFTS)
            f.composition.operate(sibling) { failure(FailureReason.STALE_SESSION) {
                f.admission.resolve(f.composition.composerPermit(sibling))
            } }
            assertEquals(0, f.source.resolves); f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun snapshotCanBeRecheckedInANewActualOperationButNotWithItsOldPermit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); lateinit var old: ComposerOperationPermit
        try {
            val principal = f.run { old = it; f.admission.resolve(it) }
            f.run { current ->
                failure(FailureReason.STALE_SESSION) { f.admission.requireCurrent(old, principal) }
                f.admission.requireCurrent(current, principal)
            }
            assertEquals(1, f.source.resolves); assertEquals(2, f.source.requirements); f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun wrongIntegrationAndAnotherAdmissionBindingCannotSupplySnapshots() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val other = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val foreign = other.run { other.admission.resolve(it) }
            f.source.overrideResult = foreign
            failure(FailureReason.STALE_SESSION) { f.run { f.admission.resolve(it) } }
            f.source.overrideResult = null
            val principal = f.run { f.admission.resolve(it) }
            val sameAccessDifferentAdmission = PublicationPrincipalAdmission(f.composition, f.borrower, f.source)
            f.run { failure(FailureReason.STALE_SESSION) { sameAccessDifferentAdmission.requireCurrent(it, principal) } }
            val wrongIssuer = SyntheticSource(f.access, f.boundary)
            f.source.overrideResult = wrongIssuer.mint(principal.binding)
            failure(FailureReason.STALE_SESSION) { f.run { f.admission.resolve(it) } }
            f.assertNoIo(); other.assertNoIo()
        } finally { f.close(); other.close() }
    }

    @Test fun exactAccessStoreBoundaryOriginAndEnvironmentCannotBeSubstituted() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val otherBoundary = SessionBoundary()
        try {
            val candidates = listOf(
                access(f.lease, ORIGIN, f.store), // Even equal fields cannot replace actual access identity.
                access(f.lease, ORIGIN, Store()),
                access(f.lease, OTHER_ORIGIN, f.store),
                access(otherBoundary.activate(f.scope), ORIGIN, f.store),
                access(otherBoundary.activate(StorageScope("different-environment", ActorKind.ACCOUNT, "opaque-owner")), ORIGIN, f.store))
            for (candidate in candidates) {
                f.source.expectedAccess = candidate
                failure(FailureReason.STALE_SESSION) { f.run { f.admission.resolve(it) } }
            }
            f.source.expectedAccess = f.access; f.source.expectedBoundary = otherBoundary
            failure(FailureReason.STALE_SESSION) { f.run { f.admission.resolve(it) } }
            f.source.expectedBoundary = f.boundary
            assertEquals(ACCOUNT, f.run { f.admission.resolve(it) }.canonicalUserId)
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun ownerAndRefreshGenerationUseIdentityNotEqualValueTokens() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            f.source.owner = EqualToken(1); f.source.generation = EqualToken(1)
            val principal = f.run { f.admission.resolve(it) }
            f.source.generation = EqualToken(1)
            failure(FailureReason.STALE_SESSION) { f.run { f.admission.requireCurrent(it, principal) } }
            val fresh = f.run { f.admission.resolve(it) }
            f.source.owner = EqualToken(1)
            failure(FailureReason.STALE_SESSION) { f.run { f.admission.requireCurrent(it, fresh) } }
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun canonicalAccountDataCannotBeBlankMalformedUppercaseOrChangedUnderAnOldSnapshot() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            for (id in listOf("", "not-a-uuid", ACCOUNT.uppercase(), " $ACCOUNT", "$ACCOUNT\n")) {
                f.source.account = id
                failure(FailureReason.INVALID_DATA) { f.run { f.admission.resolve(it) } }
            }
            f.source.account = ACCOUNT
            val principal = f.run { f.admission.resolve(it) }
            f.source.account = OTHER_ACCOUNT
            failure(FailureReason.STALE_SESSION) { f.run { f.admission.requireCurrent(it, principal) } }
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun forgedSnapshotOwnerGenerationAndCanonicalDataAreRejectedBeforeCurrentCallback() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val principal = f.run { f.admission.resolve(it) }
            val calls = f.source.requirements
            for (forged in listOf(
                PublicationPrincipalSnapshot(f.source, principal.binding, ACCOUNT, Any(), f.source.generation),
                PublicationPrincipalSnapshot(f.source, principal.binding, ACCOUNT, f.source.owner, Any()),
                PublicationPrincipalSnapshot(f.source, principal.binding, OTHER_ACCOUNT, f.source.owner, f.source.generation)))
                failure(FailureReason.STALE_SESSION) { f.run { f.admission.requireCurrent(it, forged) } }
            val malformed = PublicationPrincipalSnapshot(f.source, principal.binding, "invalid", f.source.owner, f.source.generation)
            failure(FailureReason.INVALID_DATA) { f.run { f.admission.requireCurrent(it, malformed) } }
            assertEquals(calls, f.source.requirements); f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun leaseReplacementDuringSuspendedResolveCannotDeliverOldMapping() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.source.afterResolve = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val running = async { assertFailsWith<MealFailure> { f.run { f.admission.resolve(it) } } }
        try {
            entered.await(); val replacement = f.boundary.activate(f.scope); release.complete(Unit)
            assertEquals(FailureReason.STALE_SESSION, running.await().reason)
            assertTrue(f.boundary.isCurrent(replacement)); assertEquals(0, f.source.requirements); f.assertNoIo()
        } finally { release.complete(Unit); withContext(NonCancellable) { running.cancelAndJoin() }; f.close() }
    }

    @Test fun publicationOwnerReleaseDuringSuspendedResolveKeepsSiblingUsable() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val sibling = f.bind(MealKitchenFeature.POST_DRAFTS)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.source.afterResolve = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val running = async { assertFailsWith<MealFailure> { f.run { f.admission.resolve(it) } } }
        try {
            entered.await(); f.composition.release(f.borrower); release.complete(Unit)
            assertEquals(FailureReason.STALE_SESSION, running.await().reason)
            f.composition.operate(sibling) {}; assertTrue(f.boundary.isCurrent(f.lease)); f.assertNoIo()
        } finally { release.complete(Unit); withContext(NonCancellable) { running.cancelAndJoin() }; f.close() }
    }

    @Test fun generationChangeWhileResolveReturnsAnOldSuccessIsSynchronouslyRejected() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.source.afterResolve = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val running = async { assertFailsWith<MealFailure> { f.run { f.admission.resolve(it) } } }
        try {
            entered.await(); f.source.generation = Any(); release.complete(Unit)
            assertEquals(FailureReason.STALE_SESSION, running.await().reason)
            assertEquals(0, f.source.requirements); f.assertNoIo()
        } finally { release.complete(Unit); withContext(NonCancellable) { running.cancelAndJoin() }; f.close() }
    }

    @Test fun generationChangeWhileRequireCurrentReturnsItsEarlierSuccessIsRejected() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.source.afterRequirement = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val running = async { assertFailsWith<MealFailure> { f.run { f.admission.resolve(it) } } }
        try {
            entered.await(); f.source.generation = Any(); release.complete(Unit)
            assertEquals(FailureReason.STALE_SESSION, running.await().reason)
            assertEquals(1, f.source.requirements); f.assertNoIo()
        } finally { release.complete(Unit); withContext(NonCancellable) { running.cancelAndJoin() }; f.close() }
    }

    @Test fun generationChangeDuringPostCallbackComposerCheckCannotEscapeTheFinalTail() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var arm = false
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.source.afterRequirement = { arm = true }
        f.check = { if (arm) { entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        val running = async { assertFailsWith<MealFailure> { f.run { f.admission.resolve(it) } } }
        try {
            entered.await(); f.source.generation = Any(); release.complete(Unit)
            assertEquals(FailureReason.STALE_SESSION, running.await().reason); f.assertNoIo()
        } finally { release.complete(Unit); withContext(NonCancellable) { running.cancelAndJoin() }; f.close() }
    }

    @Test fun alreadyStalePrincipalCannotEnterTheNextSuspendingDomainCheck() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            f.run { permit ->
                val principal = f.admission.resolve(permit)
                f.source.generation = Any()
                f.check = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                coroutineScope {
                    val checking = async { assertFailsWith<MealFailure> { f.admission.requireCurrent(permit, principal) } }
                    runCurrent(); val incorrectlyEntered = entered.isCompleted; release.complete(Unit)
                    assertEquals(FailureReason.STALE_SESSION, checking.await().reason)
                    assertFalse(incorrectlyEntered)
                }
                f.check = {}
            }
            f.assertNoIo()
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun synchronousCurrentnessGuardChecksDataAfterOperationWithoutRevivingItsPermit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); lateinit var expired: ComposerOperationPermit
        try {
            val principal = f.run { expired = it; f.admission.resolve(it) }
            withContext(f.dispatcher) { f.admission.requireCurrentNow(principal) }
            failure(FailureReason.STALE_SESSION) { f.admission.requireCurrent(expired, principal) }
            assertEquals(1, f.source.resolves); assertEquals(1, f.source.requirements)
            f.source.generation = Any()
            withContext(f.dispatcher) { failure(FailureReason.STALE_SESSION) { f.admission.requireCurrentNow(principal) } }
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun laterOuterCompositionSuspensionRequiresAnotherControllerTailCheck() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var helperReturned = false
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.check = { if (helperReturned) { entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        val running = async { f.run { permit -> f.admission.resolve(permit).also { helperReturned = true } } }
        try {
            entered.await(); f.source.generation = Any(); release.complete(Unit)
            val historicalData = running.await()
            // The helper does not own this later composition/dispatcher handoff. Its returned
            // immutable data is not a controller-delivery proof or an automatically current ID.
            assertEquals(ACCOUNT, historicalData.canonicalUserId)
            withContext(f.dispatcher) { failure(FailureReason.STALE_SESSION) { f.admission.requireCurrentNow(historicalData) } }
            f.assertNoIo()
        } finally { release.complete(Unit); withContext(NonCancellable) { running.cancelAndJoin() }; f.close() }
    }

    @Test fun cancellationDuringNonCooperativeResolveOrCurrentnessNeverDeliversASnapshot() = runTest {
        for (duringResolve in listOf(true, false)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); var delivered = false
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val wait: suspend () -> Unit = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            if (duringResolve) f.source.afterResolve = wait else f.source.afterRequirement = wait
            val running = async { f.run { f.admission.resolve(it) }; delivered = true }
            try {
                entered.await(); running.cancel(); release.complete(Unit); running.join()
                assertTrue(running.isCancelled); assertFalse(delivered); f.assertNoIo()
            } finally { release.complete(Unit); withContext(NonCancellable) { running.cancelAndJoin() }; f.close() }
        }
    }

    @Test fun synchronousFinalCancellationCannotReturnSuccess() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var delivered = false; var ownerJob: Job? = null
        f.source.afterRequirement = { f.source.beforeSync = { ownerJob!!.cancel() } }
        val running = async { ownerJob = currentCoroutineContext()[Job]; f.run { f.admission.resolve(it) }; delivered = true }
        try { running.join(); assertTrue(running.isCancelled); assertFalse(delivered); f.assertNoIo() }
        finally { withContext(NonCancellable) { running.cancelAndJoin() }; f.close() }
    }

    @Test fun cancellationDuringPostCallbackDomainCheckCannotDeliverTheMappedAccount() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var armed = false; var delivered = false
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.source.afterRequirement = { armed = true }
        f.check = { if (armed) { entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        val running = async { f.run { f.admission.resolve(it) }; delivered = true }
        try {
            entered.await(); running.cancel(); release.complete(Unit); running.join()
            assertTrue(running.isCancelled); assertFalse(delivered); f.assertNoIo()
        } finally { release.complete(Unit); withContext(NonCancellable) { running.cancelAndJoin() }; f.close() }
    }

    @Test fun detachedOldChildCannotFinishResolutionInsideALaterSameBorrowerOperation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val detached = SupervisorJob(); lateinit var child: Deferred<Unit>; var delivered = false
        f.source.afterResolve = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        try {
            f.run { old ->
                val inherited = currentCoroutineContext().minusKey(Job) + detached
                child = CoroutineScope(inherited).async { failure(FailureReason.STALE_SESSION) { f.admission.resolve(old); delivered = true } }
                entered.await()
            }
            f.run { fresh -> release.complete(Unit); child.await(); f.composition.requireComposerPermit(fresh, f.borrower) }
            assertFalse(delivered); assertEquals(0, f.source.requirements); f.assertNoIo()
        } finally { release.complete(Unit); withContext(NonCancellable) { detached.cancelAndJoin() }; f.close() }
    }

    @Test fun typedFailuresAndPrivateCallbackExceptionsStayFiniteWithoutInventingMapping() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            for (reason in listOf(FailureReason.NOT_CONFIGURED, FailureReason.OFFLINE, FailureReason.UNAUTHENTICATED,
                FailureReason.FORBIDDEN, FailureReason.UNAVAILABLE, FailureReason.STALE_SESSION)) {
                f.source.resolveFailure = reason
                failure(reason) { f.run { f.admission.resolve(it) } }
            }
            f.source.resolveFailure = null
            f.source.afterResolve = { throw IllegalStateException("PRIVATE_ACCOUNT_SECRET") }
            val thrown = assertFailsWith<MealFailure> { f.run { f.admission.resolve(it) } }
            assertEquals(FailureReason.UNAVAILABLE, thrown.reason); assertFalse(thrown.toString().contains("PRIVATE_ACCOUNT_SECRET"))
            f.source.afterResolve = {}; f.source.currentFailure = FailureReason.FORBIDDEN
            failure(FailureReason.FORBIDDEN) { f.run { f.admission.resolve(it) } }
            f.source.currentFailure = null; f.source.beforeSync = { throw IllegalStateException("PRIVATE_GENERATION") }
            failure(FailureReason.UNAVAILABLE) { f.run { f.admission.resolve(it) } }
            f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun cancellationExceptionsFromTrustedCallbacksAreNeverConvertedToUnavailable() = runTest {
        for (position in listOf("resolve", "current", "sync")) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val cancellation = CancellationException("Synthetic cancellation")
            when (position) {
                "resolve" -> f.source.afterResolve = { throw cancellation }
                "current" -> f.source.afterRequirement = { throw cancellation }
                else -> f.source.beforeSync = { throw cancellation }
            }
            try {
                val delivered = assertFailsWith<CancellationException> {
                    f.run { permit ->
                        val caught = assertFailsWith<CancellationException> { f.admission.resolve(permit) }
                        assertSame(cancellation, caught)
                        throw caught
                    }
                }
                // Coroutine dispatcher recovery may copy the exception after our exact local rethrow.
                assertEquals(cancellation.message, delivered.message)
                f.assertNoIo()
            }
            finally { f.close() }
        }
    }

    @Test fun brokenSynchronousCallbackCannotClearLeaseAndStillDeliverItsTrueValue() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            f.source.afterRequirement = { f.source.forcedCurrent = true; f.source.beforeSync = { f.boundary.clear() } }
            failure(FailureReason.STALE_SESSION) { f.run { f.admission.resolve(it) } }
            assertNull(f.boundary.current()); f.assertNoIo()
        } finally { f.close() }
    }

    @Test fun allBindingSnapshotIntegrationAndAdmissionDiagnosticsAreRedacted() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val principal = f.run { f.admission.resolve(it) }
            for (value in listOf(f.admission, f.source, principal, principal.binding)) {
                assertTrue(value.toString().contains("redacted")); assertFalse(value.toString().contains(ACCOUNT))
                assertFalse(value.toString().contains(ORIGIN)); assertFalse(value.toString().contains("opaque-owner"))
            }
            f.assertNoIo()
        } finally { f.close() }
    }

    private data class EqualToken(val value: Int)
    private class SyntheticSource(var expectedAccess: AuthenticatedMealPlanningAccess, var expectedBoundary: SessionBoundary) : PostPublicationPrincipalIntegration() {
        var owner: Any = Any(); var generation: Any = Any(); var account = ACCOUNT
        var resolves = 0; var requirements = 0; var syncChecks = 0
        var lastBinding: PublicationSessionBinding? = null
        var overrideResult: PublicationPrincipalSnapshot? = null
        var forcedCurrent: Boolean? = null
        var resolveFailure: FailureReason? = null; var currentFailure: FailureReason? = null
        var afterResolve: suspend () -> Unit = {}; var afterRequirement: suspend () -> Unit = {}; var beforeSync: () -> Unit = {}
        fun mint(binding: PublicationSessionBinding) = mappedPrincipal(binding, account, owner, generation)
        override suspend fun resolve(binding: PublicationSessionBinding): PortResult<PublicationPrincipalSnapshot> {
            resolves++; lastBinding = binding
            resolveFailure?.let { return PortResult.Failure(it) }
            val snapshot = overrideResult ?: mint(binding)
            afterResolve() // Deliberately can return an earlier snapshot after a noncooperative wait.
            return PortResult.Value(snapshot)
        }
        override suspend fun requireCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): PortResult<Unit> {
            requirements++
            currentFailure?.let { return PortResult.Failure(it) }
            val earlier = isCurrent(binding, principal)
            afterRequirement() // Deliberately earlier-success semantics to exercise final sync fence.
            return if (earlier) PortResult.Value(Unit) else PortResult.Failure(FailureReason.STALE_SESSION)
        }
        override fun isCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): Boolean {
            syncChecks++; beforeSync()
            return forcedCurrent ?: (matchesSession(binding, expectedAccess, expectedBoundary) &&
                matchesCurrentPrincipal(binding, principal, owner, generation) && principal.canonicalUserId == account)
        }
    }

    private class Fixture(val dispatcher: CoroutineDispatcher,
        val scope: StorageScope = StorageScope("test", ActorKind.ACCOUNT, "opaque-owner")) {
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = Store()
        val access = access(lease, ORIGIN, store)
        val composition = MealKitchenComposition(access, boundary, dispatcher, EpochClock { 1_800_000_000_000L }, ConnectivityPort { Connectivity.ONLINE })
        var check: suspend () -> Unit = {}
        private val borrowers = mutableListOf<MealKitchenComposition.Borrower>()
        val borrower = bind(MealKitchenFeature.POST_PUBLICATIONS)
        val source = SyntheticSource(access, boundary)
        val admission = PublicationPrincipalAdmission(composition, borrower, source)
        fun bind(feature: MealKitchenFeature) = composition.bind(feature, object : MealKitchenHooks {
            override suspend fun checkCurrent() { check() }
        }).also { borrowers += it }
        suspend fun <T> run(action: suspend (ComposerOperationPermit) -> T): T = composition.operate(borrower) {
            action(composition.composerPermit(borrower))
        }
        fun assertNoIo() { assertEquals(0, store.calls) }
        fun close() { borrowers.forEach { composition.release(it) } }
    }
    private class Store : PrivateStateStore {
        var calls = 0
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> { calls++; error("Unexpected synthetic store read") }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> { calls++; error("Unexpected synthetic store commit") }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { calls++; error("Unexpected synthetic erase") }
    }
    private companion object {
        const val ACCOUNT = "aaaaaaaa-1111-4111-8111-111111111111"
        const val OTHER_ACCOUNT = "bbbbbbbb-2222-4222-8222-222222222222"
        const val ORIGIN = "cccccccc-3333-4333-8333-333333333333"
        const val OTHER_ORIGIN = "dddddddd-4444-4444-8444-444444444444"
        fun access(lease: SessionLease, origin: String, store: PrivateStateStore) = AuthenticatedMealPlanningAccess(lease, origin, store,
            object : AccountTransport { override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> = error("Unexpected synthetic transport") }, true)
        suspend fun failure(reason: FailureReason, action: suspend () -> Unit) {
            assertEquals(reason, assertFailsWith<MealFailure> { action() }.reason)
        }
    }
}
