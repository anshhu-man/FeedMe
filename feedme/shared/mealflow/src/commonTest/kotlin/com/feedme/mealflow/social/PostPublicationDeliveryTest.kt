package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Actual purpose-fixed composition admission, explicitly SYNTHETIC trusted mapped owner and
 * pre-change atomic revocation integration. Deny-I/O store/transport. Not a native verifier,
 * queue application, public-controller final ACK, or cross-thread native implementation test. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostPublicationDeliveryTest {
    @Test fun constructionDoesNotResolveCaptureReadOrActivate() = runTest { fixture { f ->
        assertEquals(0, f.source.resolves); assertEquals(0, f.delivery.captures); f.noIo()
    } }

    @Test fun captureRequiresActualPublicationOperationAndPreservesMappedOwnerNotStorageActor() = runTest { fixture { f ->
        val witness = f.capture()
        assertEquals(ACCOUNT, witness.principal.canonicalUserId)
        assertNotEquals(f.lease.scope.actorId, witness.principal.canonicalUserId)
        assertSame(f.lease, witness.binding.lease); assertSame(f.boundary, witness.binding.boundary)
        assertEquals(ORIGIN, witness.binding.origin); assertTrue(witness.isCurrent()); f.noIo()
    } }

    @Test fun publicationAndReviewedSaveCaptureTheirOwnBindingsInTheSameActualOwnerEpoch() = runTest { fixture { f ->
        val publication = f.capture(); val draft = f.capture(draft = true)
        assertNotSame(publication.binding, draft.binding)
        assertTrue(publication.binding.sameRetainedSession(draft.binding))
        assertEquals(1, f.delivery.epochs.distinct().size)
        assertTrue(publication.isCurrent()); assertTrue(draft.isCurrent())
        f.delivery.revokeBeforeMutation()
        assertFalse(publication.isCurrent()); assertFalse(draft.isCurrent()); f.noIo()
    } }

    @Test fun repeatedCaptureDoesNotReplaceOrInvalidateCurrentExactOwnerEpoch() = runTest { fixture { f ->
        val first = f.capture(); val second = f.capture()
        assertNotSame(first, second); assertTrue(first.isCurrent()); assertTrue(second.isCurrent())
        assertSame(f.delivery.epochs[0], f.delivery.epochs[1]); f.noIo()
    } }

    @Test fun aDifferentMappingCannotSilentlyReplaceAnUnrevokedEpoch() = runTest { fixture { f ->
        val original = f.capture(); f.source.generation = Any() // Deliberately broken native discipline.
        failure(FailureReason.CONFLICT) { f.capture() }
        // The API cannot infer native mutations it was not told about; required pre-change revoke
        // is an explicit trusted integration contract, not fabricated automatic discovery.
        assertTrue(original.isCurrent())
        f.delivery.revokeBeforeMutation(); assertFalse(original.isCurrent())
        assertTrue(f.capture().isCurrent()); f.noIo()
    } }

    @Test fun equalValueOwnerOrRefreshTokensDoNotMatchAnExistingEpoch() = runTest { fixture { f ->
        f.source.owner = EqualToken(1); f.source.generation = EqualToken(1)
        f.capture(); f.source.generation = EqualToken(1)
        failure(FailureReason.CONFLICT) { f.capture() }
        f.delivery.revokeBeforeMutation(); f.capture(); f.source.owner = EqualToken(1)
        failure(FailureReason.CONFLICT) { f.capture() }; f.noIo()
    } }

    @Test fun wrongConfiguredPrincipalIntegrationFailsWithoutInvokingDeliveryCapture() = runTest { fixture { f ->
        val foreign = Source(f.access, f.boundary); val delivery = Delivery(foreign)
        val admission = PrincipalDeliveryAdmission(f.publishPrincipal, delivery)
        f.run { permit -> val principal = f.publishPrincipal.resolve(permit)
            failure(FailureReason.NOT_CONFIGURED) { admission.capture(permit, principal) }
        }
        assertEquals(0, delivery.captures); f.noIo()
    } }

    @Test fun anotherCaptureBindingCannotBeEchoedAsTheCurrentPrincipalWitness() = runTest { fixture { f ->
        f.capture(draft = true).also { f.delivery.overrideWitness = it }
        failure(FailureReason.STALE_SESSION) { f.capture() }; f.noIo()
    } }

    @Test fun aWitnessIssuedByAnotherDeliveryIntegrationCannotBeAccepted() = runTest { fixture { f ->
        val foreign = Delivery(f.source)
        val admission = PrincipalDeliveryAdmission(f.publishPrincipal, foreign)
        val witness = f.run { permit -> admission.capture(permit, f.publishPrincipal.resolve(permit)) }
        f.delivery.overrideWitness = witness
        failure(FailureReason.STALE_SESSION) { f.capture() }; f.noIo()
    } }

    @Test fun internalConstructedWitnessCannotGuessThePrivateIssuerSeal() = runTest { fixture { f ->
        val actual = f.capture()
        val forged = PublicationDeliveryWitness(f.delivery, Any(), actual.binding, actual.principal, f.delivery.epochs.single())
        assertFalse(forged.isCurrent()); f.delivery.overrideWitness = forged
        failure(FailureReason.STALE_SESSION) { f.run { permit -> f.publishDelivery.capture(permit, actual.principal) } }
        assertTrue(actual.isCurrent()); f.noIo()
    } }

    @Test fun clonedAccessAndSubstitutedStoreCannotShareTheExactRetainedEpoch() = runTest { fixture { f ->
        val actual = f.capture()
        for (candidate in listOf(access(f.lease, f.store), access(f.lease, Store()))) {
            val binding = PublicationSessionBinding(candidate, f.boundary)
            assertFalse(actual.binding.sameRetainedSession(binding))
            val mapped = f.source.mint(binding)
            failure(FailureReason.CONFLICT) { f.delivery.exposeGeneration(mapped) }
        }
        assertTrue(actual.isCurrent()); f.noIo()
    } }

    @Test fun typedCaptureFailureNeverFabricatesAWitness() = runTest { fixture { f ->
        for (reason in listOf(FailureReason.NOT_CONFIGURED, FailureReason.UNAUTHENTICATED,
            FailureReason.FORBIDDEN, FailureReason.OFFLINE, FailureReason.UNAVAILABLE)) {
            f.delivery.failure = reason; failure(reason) { f.capture() }
        }
        assertTrue(f.delivery.epochs.isEmpty()); f.noIo()
    } }

    @Test fun ordinaryCaptureExceptionDoesNotExposePrivateNativeDetails() = runTest { fixture { f ->
        f.delivery.beforeCapture = { error("private-owner-vault-detail") }
        val failure = assertFailsWith<MealFailure> { f.capture() }
        assertEquals(FailureReason.UNAVAILABLE, failure.reason)
        assertFalse(failure.toString().contains("private-owner-vault-detail")); f.noIo()
    } }

    @Test fun captureCancellationIsRethrownLocallyAndNotConvertedAcrossDispatcherRecovery() = runTest { fixture { f ->
        val cancellation = CancellationException("synthetic capture cancelled")
        f.delivery.beforeCapture = { throw cancellation }
        val outer = assertFailsWith<CancellationException> {
            f.run { permit ->
                val principal = f.publishPrincipal.resolve(permit)
                val inner = assertFailsWith<CancellationException> { f.publishDelivery.capture(permit, principal) }
                assertSame(cancellation, inner); throw inner
            }
        }
        assertEquals(cancellation.message, outer.message); f.noIo()
    } }

    @Test fun revocationDuringCaptureCannotDeliverItsEarlierWitness() = runTest { fixture { f ->
        f.delivery.afterCapture = { f.delivery.revokeBeforeMutation() }
        failure(FailureReason.STALE_SESSION) { f.capture() }
        assertFalse(f.delivery.lastWitness!!.isCurrent()); f.noIo()
    } }

    @Test fun mappingChangeDuringPostCaptureSuspendingCheckRejectsEarlierSuccess() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var arm = false
        f.delivery.afterCapture = { arm = true }
        f.check = { if (arm) { entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        val running = async { assertFailsWith<MealFailure> { f.capture() } }
        try {
            entered.await(); f.delivery.revokeBeforeMutation(); f.source.generation = Any(); release.complete(Unit)
            assertEquals(FailureReason.STALE_SESSION, running.await().reason)
            assertFalse(f.delivery.lastWitness!!.isCurrent()); f.noIo()
        } finally { release.complete(Unit); withContext(NonCancellable) { running.cancelAndJoin() } }
    } }

    @Test fun cancellationDuringPostCaptureNoncooperativeCheckNeverReturnsWitness() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var arm = false; var delivered = false
        f.delivery.afterCapture = { arm = true }
        f.check = { if (arm) { entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        val running = async { f.capture(); delivered = true }
        try {
            entered.await(); running.cancel(); release.complete(Unit); running.join()
            assertTrue(running.isCancelled); assertFalse(delivered); f.noIo()
        } finally { release.complete(Unit); withContext(NonCancellable) { running.cancelAndJoin() } }
    } }

    @Test fun preMutationAtomicRevokeIsVisibleBeforeActualOwnerDataChanges() = runTest { fixture { f ->
        val original = f.capture(); val owner = f.source.owner; val generation = f.source.generation
        f.delivery.revokeBeforeMutation()
        assertSame(owner, f.source.owner); assertSame(generation, f.source.generation)
        assertFalse(original.isCurrent())
        f.source.owner = Any(); f.source.generation = Any()
        assertFalse(original.isCurrent()); assertTrue(f.capture().isCurrent()); f.noIo()
    } }

    @Test fun boundaryInvalidationBackstopRevokesWithoutCreatingOrActivatingASession() = runTest { fixture { f ->
        val witness = f.capture(); f.boundary.clear()
        assertFalse(witness.isCurrent()); assertNull(f.boundary.current())
        assertEquals(1, f.delivery.captures); f.noIo()
    } }

    @Test fun laterOuterCompositionSuspensionLeavesOnlyAnInvalidatedHistoricalWitness() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var helperReturned = false
        f.check = { if (helperReturned) { entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        val running = async { f.run { permit ->
            f.publishDelivery.capture(permit, f.publishPrincipal.resolve(permit)).also { helperReturned = true }
        } }
        try {
            entered.await(); f.delivery.revokeBeforeMutation(); f.source.generation = Any(); release.complete(Unit)
            val historical = running.await(); assertFalse(historical.isCurrent()); f.noIo()
            // The REAL public controller must check this after its final handoff and before ACK;
            // this internal test deliberately does not claim that controller has run.
        } finally { release.complete(Unit); withContext(NonCancellable) { running.cancelAndJoin() } }
    } }

    @Test fun arbitraryCallerCheckNeverInvokesDispatcherConfinedPrincipalCallbacks() = runTest { fixture { f ->
        val witness = f.capture(); val checks = f.source.checks
        f.source.beforeSync = { error("Legacy currentness must not run on caller dispatcher") }
        withContext(Dispatchers.Default) { repeat(128) { assertTrue(witness.isCurrent()) } }
        assertEquals(checks, f.source.checks)
        f.delivery.revokeBeforeMutation()
        withContext(Dispatchers.Default) { repeat(128) { assertFalse(witness.isCurrent()) } }
        assertEquals(checks, f.source.checks); f.noIo()
    } }

    @Test fun revocationSubscriptionsRedactBeforeOwnerMutationAndAreOneShot() = runTest { fixture { f ->
        val witness = f.capture(); val owner = f.source.owner; var first = 0; var second = 0
        witness.observeRevocation { first++; assertFalse(witness.isCurrent()); assertSame(owner, f.source.owner) }
        val cancelled = witness.observeRevocation { second++ }; cancelled.close()
        f.delivery.revokeBeforeMutation(); f.source.owner = Any(); f.delivery.revokeBeforeMutation()
        assertEquals(1, first); assertEquals(0, second)
        failure(FailureReason.STALE_SESSION) { witness.observeRevocation { error("Never registered") } }; f.noIo()
    } }

    @Test fun freshCaptureCannotReviveOldWitnessEvenWhenCanonicalIdentityIsUnchanged() = runTest { fixture { f ->
        val old = f.capture(); f.delivery.revokeBeforeMutation(); f.source.generation = Any()
        val next = f.capture(); assertEquals(old.principal.canonicalUserId, next.principal.canonicalUserId)
        assertFalse(old.isCurrent()); assertTrue(next.isCurrent()); f.noIo()
    } }

    @Test fun forgedExpiredAndForeignPurposePermitsDoNotInvokeCapture() = runTest { fixture { f ->
        lateinit var old: ComposerOperationPermit
        val principal = f.run { old = it; f.publishPrincipal.resolve(it) }
        failure(FailureReason.STALE_SESSION) { f.publishDelivery.capture(old, principal) }
        f.run { failure(FailureReason.STALE_SESSION) { f.publishDelivery.capture(ComposerOperationPermit(), principal) } }
        f.run(draft = true) { failure(FailureReason.STALE_SESSION) { f.publishDelivery.capture(it, principal) } }
        assertEquals(0, f.delivery.captures); f.noIo()
    } }

    @Test fun arbitraryCallerCurrentnessDoesNotTurnExpiredOperationIntoMutationPermission() = runTest { fixture { f ->
        lateinit var expired: ComposerOperationPermit
        val witness = f.run { permit -> expired = permit; f.publishDelivery.capture(permit, f.publishPrincipal.resolve(permit)) }
        assertTrue(witness.isCurrent())
        failure(FailureReason.STALE_SESSION) { f.publishPrincipal.requireCurrent(expired, witness.principal) }
        assertTrue(witness.isCurrent()); f.noIo()
    } }

    @Test fun allEpochWitnessAdmissionAndIntegrationDiagnosticsAreRedacted() = runTest { fixture { f ->
        val witness = f.capture()
        for (value in listOf(f.delivery, f.publishDelivery, witness, f.delivery.epochs.single(), witness.observeRevocation {})) {
            assertTrue(value.toString().contains("redacted")); assertFalse(value.toString().contains(ACCOUNT))
            assertFalse(value.toString().contains(ORIGIN)); assertFalse(value.toString().contains("opaque-owner"))
        }
        f.noIo()
    } }

    @Test fun registrationIsBoundedAndDoesNotByItselfAuthorizeOrDeliver() = runTest { fixture { f ->
        val witness = f.capture(); val first = f.register(witness); val second = f.register(witness)
        assertFalse(first.wasAuthorized()); assertFalse(second.wasAuthorized())
        failure(FailureReason.UNAVAILABLE) { f.register(witness) }
        first.cancel(); val replacement = f.register(witness)
        assertFalse(first.wasAuthorized()); assertFalse(replacement.wasAuthorized()); second.cancel(); replacement.cancel(); f.noIo()
    } }

    @Test fun requiredPendingCapacityCannotBeZeroNegativeOrUnbounded() = runTest { fixture { f ->
        for (capacity in listOf(Int.MIN_VALUE, -1, 0, 257, Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> { Delivery(f.source, capacity) }
        assertEquals(0, f.source.resolves); f.noIo()
    } }

    @Test fun publicationAndReviewedSaveShareOneActualEpochCapacity() = runTest { fixture { f ->
        val publish = f.capture(); val save = f.capture(draft = true)
        val first = f.register(publish); val second = f.register(save, draft = true)
        failure(FailureReason.UNAVAILABLE) { f.register(publish) }
        first.cancel(); assertTrue(f.register(save, draft = true).tryAuthorizeDelivery()); second.cancel(); f.noIo()
    } }

    @Test fun operationCancellationBeforeAuthorizationWinsTheSameEpochGate() = runTest { fixture { f ->
        val gate = f.register(f.capture()); gate.cancel(); gate.cancel()
        assertFalse(gate.tryAuthorizeDelivery()); assertFalse(gate.wasAuthorized()); f.noIo()
    } }

    @Test fun authorizationBeforeLaterOperationCancellationRemainsHistoricalAuthorizationOnly() = runTest { fixture { f ->
        val gate = f.register(f.capture()); assertTrue(gate.tryAuthorizeDelivery()); gate.cancel(); gate.cancel()
        assertTrue(gate.wasAuthorized()); assertFalse(gate.tryAuthorizeDelivery()); f.noIo()
        // This proves no UI emission/receipt/retirement: actual controller recordPublished is separate.
    } }

    @Test fun nativeRevocationBeforeAuthorizationCancelsAllPendingGates() = runTest { fixture { f ->
        val witness = f.capture(); val first = f.register(witness); val second = f.register(witness)
        f.delivery.revokeBeforeMutation()
        assertFalse(first.tryAuthorizeDelivery()); assertFalse(second.tryAuthorizeDelivery())
        assertFalse(first.wasAuthorized()); assertFalse(second.wasAuthorized()); f.noIo()
    } }

    @Test fun authorizationBeforeNativeRevocationIsNotErasedOrMistakenForCurrentness() = runTest { fixture { f ->
        val witness = f.capture(); val winner = f.register(witness); val pending = f.register(witness)
        assertTrue(winner.tryAuthorizeDelivery()); f.delivery.revokeBeforeMutation()
        assertTrue(winner.wasAuthorized()); assertFalse(witness.isCurrent()); assertFalse(pending.wasAuthorized())
        assertFalse(pending.tryAuthorizeDelivery()); assertFalse(winner.tryAuthorizeDelivery()); f.noIo()
    } }

    @Test fun registrationAfterRevocationCannotResurrectAnEpochOrOccupyNextCapacity() = runTest { fixture { f ->
        val old = f.capture(); f.delivery.revokeBeforeMutation()
        failure(FailureReason.STALE_SESSION) { f.register(old) }
        f.source.generation = Any(); val next = f.capture()
        assertTrue(f.register(next).tryAuthorizeDelivery()); assertTrue(f.register(next).tryAuthorizeDelivery())
        failure(FailureReason.STALE_SESSION) { f.register(old) }; f.noIo()
    } }

    @Test fun completedGateHistoryDoesNotConsumePendingRegistryCapacity() = runTest { fixture { f ->
        val witness = f.capture(); val history = mutableListOf<PublicationDeliveryGate>()
        repeat(512) { index ->
            val gate = f.register(witness); history += gate
            if (index % 2 == 0) assertTrue(gate.tryAuthorizeDelivery()) else gate.cancel()
        }
        history.forEachIndexed { index, gate -> assertEquals(index % 2 == 0, gate.wasAuthorized()); assertFalse(gate.tryAuthorizeDelivery()) }
        val first = f.register(witness); val second = f.register(witness)
        failure(FailureReason.UNAVAILABLE) { f.register(witness) }; first.cancel(); second.cancel(); f.noIo()
    } }

    @Test fun forgedGateAndCompletionCannotGuessThePrivateGenerationSeal() = runTest { fixture { f ->
        val witness = f.capture(); val generation = f.delivery.epochs.single(); val seal = Any()
        val fake = PublicationDeliveryGate(generation, f.delivery, seal)
        fake.complete(generation, f.delivery, seal, true)
        assertFalse(fake.tryAuthorizeDelivery()); assertFalse(fake.wasAuthorized()); fake.cancel()
        assertTrue(f.register(witness).tryAuthorizeDelivery()); f.noIo()
    } }

    @Test fun repeatedGateAuthorizationDoesNotCreateMultipleDeliveryRights() = runTest { fixture { f ->
        val gate = f.register(f.capture())
        assertTrue(gate.tryAuthorizeDelivery()); repeat(20) { assertFalse(gate.tryAuthorizeDelivery()) }
        assertTrue(gate.wasAuthorized()); f.noIo()
    } }

    @Test fun cancellingOneGateCannotCancelItsSiblingOrAnotherEpoch() = runTest { fixture { f ->
        val witness = f.capture(); val first = f.register(witness); val second = f.register(witness)
        first.cancel(); assertTrue(second.tryAuthorizeDelivery())
        f.delivery.revokeBeforeMutation(); f.source.generation = Any()
        val next = f.register(f.capture()); first.cancel(); second.cancel(); assertTrue(next.tryAuthorizeDelivery()); f.noIo()
    } }

    @Test fun gateCallerOperationsNeverInvokeLegacyIdentityDispatcherCallbacks() = runTest { fixture { f ->
        val witness = f.capture(); val first = f.register(witness); val second = f.register(witness)
        val checks = f.source.checks; f.source.beforeSync = { error("No legacy callback from gate") }
        withContext(Dispatchers.Default) { assertTrue(first.tryAuthorizeDelivery()); second.cancel(); assertFalse(second.tryAuthorizeDelivery()) }
        assertEquals(checks, f.source.checks); assertTrue(first.wasAuthorized()); assertFalse(second.wasAuthorized()); f.noIo()
    } }

    @Test fun competingCallerCancelAndAuthorizeHaveExactlyOneConservativeOutcome() = runTest { fixture { f ->
        val witness = f.capture()
        repeat(64) {
            val gate = f.register(witness); val start = CompletableDeferred<Unit>()
            val authorize = async(Dispatchers.Default) { start.await(); gate.tryAuthorizeDelivery() }
            val cancel = async(Dispatchers.Default) { start.await(); gate.cancel() }
            start.complete(Unit); val result = authorize.await(); cancel.await()
            assertEquals(result, gate.wasAuthorized()); assertFalse(gate.tryAuthorizeDelivery())
        }
        f.noIo()
    } }

    @Test fun alreadyCancelledOrCompletedCallerImmediatelyCancelsRegistration() = runTest { fixture { f ->
        for (cancelled in listOf(false, true)) {
            val gate = f.register(f.capture()); val caller = Job()
            if (cancelled) caller.cancel() else caller.complete()
            val registration = DeliveryOperationCancellation.register(caller, gate::cancel)
            assertFalse(gate.tryAuthorizeDelivery()); assertFalse(gate.wasAuthorized())
            registration?.dispose(); registration?.dispose()
        }
        f.noIo()
    } }

    @Test fun callerCancellationCallbackBeforeAuthorizationWinsWithoutWaitingForCompletion() = runTest { fixture { f ->
        val gate = f.register(f.capture())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var ordinaryCompletion = false
        val caller = launch {
            val originalJob = currentCoroutineContext()[Job]!!
            val registration = DeliveryOperationCancellation.register(originalJob, gate::cancel)
            originalJob.invokeOnCompletion { ordinaryCompletion = true }
            try { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            finally { registration?.dispose() }
        }
        try {
            entered.await(); caller.cancel()
            assertTrue(caller.isCancelled); assertFalse(caller.isCompleted)
            assertFalse(ordinaryCompletion) // completion-only registration is provably too late
            assertFalse(gate.tryAuthorizeDelivery()); assertFalse(gate.wasAuthorized())
        } finally { release.complete(Unit); caller.join() }
        assertTrue(ordinaryCompletion); f.noIo()
    } }

    @Test fun parentCancellationReachesOriginalChildWhileItsWorkIsNonCooperative() = runTest { fixture { f ->
        val gate = f.register(f.capture()); val parent = Job()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val caller = launch(parent) {
            val registration = DeliveryOperationCancellation.register(currentCoroutineContext()[Job], gate::cancel)
            try { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            finally { registration?.dispose() }
        }
        try {
            entered.await(); parent.cancel()
            assertTrue(caller.isCancelled); assertFalse(caller.isCompleted)
            assertFalse(gate.tryAuthorizeDelivery()); assertFalse(gate.wasAuthorized())
        } finally { release.complete(Unit); caller.join(); parent.cancelAndJoin() }
        f.noIo()
    } }

    @Test fun authorizationBeforeCallerCancellationStaysAuthorizationButFinalCheckStillCancels() = runTest { fixture { f ->
        val gate = f.register(f.capture()); val caller = Job()
        val registration = DeliveryOperationCancellation.register(caller, gate::cancel)
        try {
            assertTrue(gate.tryAuthorizeDelivery()); caller.cancel()
            assertTrue(gate.wasAuthorized())
            assertFailsWith<CancellationException> { caller.ensureActive() }
            // No controller flow was published and no real application can be retired by this
            // historical authorization. The final cooperative check can still abort delivery.
        } finally { registration?.dispose() }
        f.noIo()
    } }

    @Test fun disposingTerminalRegistrationIsIdempotentAndRemovesFutureCancellationNotification() = runTest { fixture { f ->
        val gate = f.register(f.capture()); val caller = Job()
        val registration = DeliveryOperationCancellation.register(caller, gate::cancel)
        registration?.dispose(); registration?.dispose(); caller.cancel()
        assertTrue(gate.tryAuthorizeDelivery())
        // Deliberately helper-only: actual controller may dispose only after terminal cleanup,
        // not use this sequence to authorize an active cancelled operation.
        f.noIo()
    } }

    @Test fun absentCallerJobDoesNotInventASyntheticCancellationSource() = runTest { fixture { f ->
        val gate = f.register(f.capture())
        assertNull(DeliveryOperationCancellation.register(null, gate::cancel))
        assertTrue(gate.tryAuthorizeDelivery()); f.noIo()
    } }

    @Test fun cancellationNotificationFromArbitraryThreadUsesOnlyGateAtomics() = runTest { fixture { f ->
        val gate = f.register(f.capture()); val caller = Job()
        val registration = DeliveryOperationCancellation.register(caller, gate::cancel)
        val checks = f.source.checks; f.source.beforeSync = { error("No identity callback from cancellation thread") }
        try {
            withContext(Dispatchers.Default) { caller.cancel() }
            assertFalse(gate.tryAuthorizeDelivery()); assertFalse(gate.wasAuthorized())
            assertEquals(checks, f.source.checks)
        } finally { registration?.dispose() }
        f.noIo()
    } }

    @Test fun simultaneousRegistrationAndCancellationCannotLoseTheImmediateNotification() = runTest { fixture { f ->
        val witness = f.capture()
        repeat(64) {
            val gate = f.register(witness); val caller = Job(); val start = CompletableDeferred<Unit>()
            val registration = async(Dispatchers.Default) { start.await(); DeliveryOperationCancellation.register(caller, gate::cancel) }
            val cancellation = async(Dispatchers.Default) { start.await(); caller.cancel() }
            start.complete(Unit); val handle = registration.await(); cancellation.await()
            assertFalse(gate.tryAuthorizeDelivery()); assertFalse(gate.wasAuthorized()); handle?.dispose()
        }
        f.noIo()
    } }

    @Test fun expiredCapturedWitnessCannotRegisterWithoutANewActualOperation() = runTest { fixture { f ->
        lateinit var expired: ComposerOperationPermit
        val witness = f.run { permit -> expired = permit; f.publishDelivery.capture(permit, f.publishPrincipal.resolve(permit)) }
        assertTrue(witness.isCurrent())
        failure(FailureReason.STALE_SESSION) { f.publishDelivery.registerDelivery(expired, witness.principal, witness) }
        val first = f.register(witness); val second = f.register(witness)
        failure(FailureReason.UNAVAILABLE) { f.register(witness) }
        first.cancel(); second.cancel(); f.noIo()
    } }

    @Test fun forgedAndForeignPurposePermitsCannotConsumeEpochCapacity() = runTest { fixture { f ->
        val witness = f.capture()
        f.run { failure(FailureReason.STALE_SESSION) {
            f.publishDelivery.registerDelivery(ComposerOperationPermit(), witness.principal, witness)
        } }
        f.run(draft = true) { permit -> failure(FailureReason.STALE_SESSION) {
            f.publishDelivery.registerDelivery(permit, witness.principal, witness)
        } }
        val first = f.register(witness); val second = f.register(witness)
        first.cancel(); second.cancel(); f.noIo()
    } }

    @Test fun constructedRegistrationCapabilityCannotBypassActualPurposeFixedAdmission() = runTest { fixture { f ->
        val witness = f.capture()
        failure(FailureReason.STALE_SESSION) { witness.registerAdmitted(f.publishDelivery, PublicationDeliveryRegistration()) }
        f.run { failure(FailureReason.STALE_SESSION) { witness.registerAdmitted(f.publishDelivery, PublicationDeliveryRegistration()) } }
        assertTrue(f.register(witness).tryAuthorizeDelivery()); f.noIo()
    } }

    @Test fun registrationBindsTheExactCapturedPrincipalNotAnotherCurrentSnapshot() = runTest { fixture { f ->
        val witness = f.capture()
        f.run { permit ->
            val another = f.publishPrincipal.resolve(permit)
            assertNotSame(witness.principal, another)
            failure(FailureReason.STALE_SESSION) { f.publishDelivery.registerDelivery(permit, another, witness) }
        }
        assertTrue(f.register(witness).tryAuthorizeDelivery()); f.noIo()
    } }

    @Test fun anotherDeliverySourceCannotConsumeThisWitnessEvenWithCurrentAdmission() = runTest { fixture { f ->
        val witness = f.capture(); val foreign = Delivery(f.source)
        val admission = PrincipalDeliveryAdmission(f.publishPrincipal, foreign)
        try {
            f.run { permit -> failure(FailureReason.STALE_SESSION) { admission.registerDelivery(permit, witness.principal, witness) } }
            assertTrue(f.register(witness).tryAuthorizeDelivery()); f.noIo()
        } finally { foreign.revokeBeforeMutation() }
    } }

    @Test fun mappingRevocationDuringRegistrationPermitCheckCannotMintALateGate() = runTest { fixture { f ->
        val witness = f.capture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val task = async {
            f.run { permit ->
                f.check = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                failure(FailureReason.STALE_SESSION) { f.publishDelivery.registerDelivery(permit, witness.principal, witness) }
            }
        }
        try {
            entered.await(); f.delivery.revokeBeforeMutation(); f.source.generation = Any(); f.check = {}
            release.complete(Unit); task.await()
            val next = f.capture(); val first = f.register(next); val second = f.register(next)
            first.cancel(); second.cancel(); f.noIo()
        } finally { f.check = {}; release.complete(Unit); task.cancelAndJoin() }
    } }

    @Test fun callerCancellationDuringNoncooperativeRegistrationCheckLeaksNoPendingGate() = runTest { fixture { f ->
        val witness = f.capture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var delivered = false
        val task = async {
            f.run { permit ->
                f.check = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                f.publishDelivery.registerDelivery(permit, witness.principal, witness).also { delivered = true }
            }
        }
        try {
            entered.await(); task.cancel(); f.check = {}; release.complete(Unit); task.join()
            assertTrue(task.isCancelled); assertFalse(delivered)
            val first = f.register(witness); val second = f.register(witness)
            first.cancel(); second.cancel(); f.noIo()
        } finally { f.check = {}; release.complete(Unit); task.cancelAndJoin() }
    } }

    @Test fun emptyBridgeChildAttachesAndEveryTerminalDisposeDetachesWithoutCancellingParent() = runTest {
        val parent = Job(); var notified = false
        assertTrue(parent.children.none())
        val handle = DeliveryOperationCancellation.register(parent) { notified = true }
        assertEquals(1, parent.children.count()); assertTrue(parent.isActive); assertFalse(notified)
        handle?.dispose(); handle?.dispose()
        assertTrue(parent.children.none()); assertTrue(parent.isActive); assertFalse(notified)
        parent.complete(); assertTrue(parent.isCompleted); assertFalse(parent.isCancelled)
    }

    @Test fun normallyCompletingParentWaitsOnlyUntilOwnedEmptyChildIsDisposed() = runTest {
        val parent = Job(); var notified = false
        val handle = DeliveryOperationCancellation.register(parent) { notified = true }
        parent.complete()
        assertTrue(parent.isActive); assertFalse(parent.isCompleted); assertFalse(notified)
        handle?.dispose()
        assertTrue(parent.isCompleted); assertFalse(parent.isCancelled); assertFalse(notified)
        assertTrue(parent.children.none())
    }

    @Test fun normalCoroutineReturnFinallyReleasesItsBridgeChildWithoutHangingOrCancellation() = runTest {
        var notified = false; var childWasAttached = false
        val task = async {
            val original = currentCoroutineContext()[Job]!!
            val handle = DeliveryOperationCancellation.register(original) { notified = true }
            try { childWasAttached = original.children.count() == 1; "actual result" }
            finally { handle?.dispose() }
        }
        assertEquals("actual result", task.await()); assertTrue(childWasAttached)
        assertTrue(task.isCompleted); assertFalse(task.isCancelled); assertFalse(notified)
    }

    private suspend fun TestScope.fixture(action: suspend (Fixture) -> Unit) {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try { action(f) } finally { f.close() }
    }
    private data class EqualToken(val value: Int)
    private class Source(val actual: AuthenticatedMealPlanningAccess, val boundary: SessionBoundary) : PostPublicationPrincipalIntegration() {
        var owner: Any = Any(); var generation: Any = Any(); var account = ACCOUNT
        var resolves = 0; var checks = 0; var beforeSync: () -> Unit = {}
        fun mint(binding: PublicationSessionBinding) = mappedPrincipal(binding, account, owner, generation)
        override suspend fun resolve(binding: PublicationSessionBinding): PortResult<PublicationPrincipalSnapshot> {
            resolves++; return PortResult.Value(mint(binding))
        }
        override suspend fun requireCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot) =
            if (isCurrent(binding, principal)) PortResult.Value(Unit) else PortResult.Failure(FailureReason.STALE_SESSION)
        override fun isCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): Boolean {
            checks++; beforeSync()
            return matchesSession(binding, actual, boundary) && matchesCurrentPrincipal(binding, principal, owner, generation) &&
                principal.canonicalUserId == account
        }
    }
    private class Delivery(source: Source, capacity: Int = 2) : PostPublicationDeliveryIntegration(source, capacity) {
        var captures = 0; val epochs = mutableListOf<PublicationDeliveryGeneration>()
        var beforeCapture: () -> Unit = {}; var afterCapture: () -> Unit = {}
        var failure: FailureReason? = null; var overrideWitness: PublicationDeliveryWitness? = null
        var lastWitness: PublicationDeliveryWitness? = null
        fun revokeBeforeMutation() = revokeCurrent()
        fun exposeGeneration(principal: PublicationPrincipalSnapshot) = generationFor(principal)
        override fun capture(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): PortResult<PublicationDeliveryWitness> {
            captures++; beforeCapture(); failure?.let { return PortResult.Failure(it) }
            val result = overrideWitness ?: generationFor(principal).let { epochs += it; witness(binding, principal, it) }
            lastWitness = result; afterCapture(); return PortResult.Value(result)
        }
    }
    private class Fixture(val dispatcher: CoroutineDispatcher) {
        val boundary = SessionBoundary(); val lease = boundary.activate(StorageScope("test", ActorKind.ACCOUNT, "opaque-owner"))
        val store = Store(); val access = access(lease, store)
        val composition = MealKitchenComposition(access, boundary, dispatcher, EpochClock { 1_800_000_000_000L }, ConnectivityPort { Connectivity.ONLINE })
        var check: suspend () -> Unit = {}
        private val hooks = object : MealKitchenHooks { override suspend fun checkCurrent() { check() } }
        val publication = composition.bind(MealKitchenFeature.POST_PUBLICATIONS, hooks)
        val draft = composition.bind(MealKitchenFeature.POST_DRAFTS, hooks)
        val source = Source(access, boundary); val delivery = Delivery(source)
        val publishPrincipal = PublicationPrincipalAdmission(composition, publication, source)
        val draftPrincipal = ReviewedDraftPrincipalAdmission(composition, draft, source)
        val publishDelivery = PrincipalDeliveryAdmission(publishPrincipal, delivery)
        val draftDelivery = PrincipalDeliveryAdmission(draftPrincipal, delivery)
        suspend fun <T> run(draft: Boolean = false, action: suspend (ComposerOperationPermit) -> T): T {
            val borrower = if (draft) this.draft else publication
            return composition.operate(borrower) { action(composition.composerPermit(borrower)) }
        }
        suspend fun capture(draft: Boolean = false) = run(draft) { permit ->
            if (draft) draftDelivery.capture(permit, draftPrincipal.resolve(permit))
            else publishDelivery.capture(permit, publishPrincipal.resolve(permit))
        }
        suspend fun register(witness: PublicationDeliveryWitness, draft: Boolean = false) = run(draft) { permit ->
            if (draft) draftDelivery.registerDelivery(permit, witness.principal, witness)
            else publishDelivery.registerDelivery(permit, witness.principal, witness)
        }
        fun noIo() { assertEquals(0, store.calls) }
        fun close() { delivery.revokeBeforeMutation(); composition.release(publication); composition.release(draft) }
    }
    private class Store : PrivateStateStore {
        var calls = 0
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> { calls++; error("Unexpected store read") }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> { calls++; error("Unexpected store commit") }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { calls++; error("Unexpected erase") }
    }
    private companion object {
        const val ACCOUNT = "aaaaaaaa-1111-4111-8111-111111111111"
        const val ORIGIN = "cccccccc-3333-4333-8333-333333333333"
        fun access(lease: SessionLease, store: PrivateStateStore) = AuthenticatedMealPlanningAccess(lease, ORIGIN, store,
            object : AccountTransport { override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> = error("Unexpected transport") }, true)
        suspend fun failure(reason: FailureReason, action: suspend () -> Unit) =
            assertEquals(reason, assertFailsWith<MealFailure> { action() }.reason)
    }
}
