@file:OptIn(com.feedme.storage.SessionControlRecoveryCompositionApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Protocol fakes only: ownership and acknowledgement ordering, not native durability. */
class SessionSetupRecoveryOwnerTest {
    @Test fun constructionClaimsReservationBeforeAnyFactoryOrResourceIo() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner()
        assertTrue(f.events.isEmpty()); assertEquals(SessionSetupRecoveryPhase.NEW, owner.phase())
        rejected(FailureReason.CONFLICT, f.reservation.release()); rejected(FailureReason.CONFLICT, f.root.reserve())
        ok(owner.close()); assertTrue(f.events.isEmpty()); ok(f.root.close())
    }

    @Test fun openingReadsOriginalPendingAndRetainsAllOwnersBeforeOpeningSubordinates() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
        assertEquals(listOf("control", "credential", "data", "work"), f.created)
        assertTrue(f.events.indexOf("create-work") < f.events.indexOf("open-credential"))
        assertEquals(SessionSetupRecoveryPhase.READY, owner.phase()); assertEquals(0, f.writes)
        assertTrue(f.effects.isEmpty()); assertNull(f.boundary.current()); f.finish(owner)
    }

    @Test fun everyFailedAcquisitionRetainsExactPartialOwnersUntilClose() = runTest {
        for (part in PARTS) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.failures["open-$part"] = FailureReason.STORAGE_FAILURE
            val owner = f.owner(); rejected(FailureReason.STORAGE_FAILURE, owner.open())
            assertEquals(SessionSetupRecoveryPhase.CLOSE_ONLY, owner.phase()); assertIs<PortResult.Failure>(owner.inspect())
            rejected(FailureReason.CONFLICT, f.reservation.release()); assertTrue(f.effects.isEmpty())
            f.finish(owner); assertEquals(f.created.toSet(), f.closed.toSet())
        }
    }

    @Test fun missingIdleCompleteMalformedOrDifferentConfigurationNeverOpensSubordinates() = runTest {
        for (kind in listOf("missing", "idle", "complete", "malformed", "configuration")) {
            val f = Fixture(StandardTestDispatcher(testScheduler), configuration = if (kind == "configuration") "e".repeat(64) else CONFIG)
            when (kind) {
                "missing" -> f.record = null
                "idle" -> f.record = record(RetirementState.Idle)
                "complete" -> f.record = record(RetirementState.Complete(OPERATION))
                "malformed" -> f.record = SessionControlRecord(11, PrivateBytes(byteArrayOf(0)))
            }
            val owner = f.owner(); assertIs<PortResult.Failure>(owner.open(), kind)
            assertEquals(listOf("control"), f.created); assertEquals(0, f.writes); f.finish(owner)
        }
    }

    @Test fun rejectedRepeatedOpenDoesNotQuarantineReadyOwnership() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
        rejected(FailureReason.CONFLICT, owner.open()); assertEquals(SessionSetupRecoveryPhase.READY, owner.phase())
        ok(owner.inspect()); ok(owner.prepareAbort()); f.finish(owner)
    }

    @Test fun duplicateRecoveryCannotReleaseOrOperateOriginalOwnersReservation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val first = f.owner(); val second = f.owner()
        rejected(FailureReason.CONFLICT, second.open()); ok(second.close()); assertTrue(f.events.isEmpty())
        rejected(FailureReason.CONFLICT, f.reservation.release()); ok(first.open()); f.finish(first)
    }

    @Test fun inspectionAndPreparationAreStableReadOnlyAndRedacted() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open()); val before = f.record!!
        ok(owner.inspect()); val prepared = ok(owner.prepareAbort()); ok(owner.inspect())
        assertEquals(before.revision, f.record!!.revision); same(before.payload, f.record!!.payload)
        assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
        for (private in listOf(SCOPE.actorId, CONFIG, OPERATION, ORIGIN)) {
            assertFalse(prepared.toString().contains(private)); assertFalse(owner.toString().contains(private))
        }
        rejected(FailureReason.CONFLICT, owner.retryAbort()); f.finish(owner)
    }

    @Test fun changedControlOrComponentEvidenceRejectsConfirmationWithoutEffects() = runTest {
        for (part in listOf("control", "credential", "data", "work")) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
            val proposal = ok(owner.prepareAbort())
            when (part) {
                "control" -> f.record = SessionControlRecord(f.record!!.revision + 1, f.record!!.payload)
                "credential" -> f.credential = CredentialCreatePlanObservation(f.credential.status, fingerprint(91))
                "data" -> f.data = StateActivationPlanObservation(f.data.status, fingerprint(92))
                else -> f.work = SessionWorkOriginObservation(f.work.status, f.work.revision + 1)
            }
            assertIs<PortResult.Failure>(owner.confirmAbort(proposal)); assertTrue(f.effects.isEmpty()); assertEquals(0, f.writes)
            f.finish(owner)
        }
    }

    @Test fun confirmationOrdersExactAbortThenAllSubordinateCloseBeforeComplete() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
        ok(owner.confirmAbort(ok(owner.prepareAbort())))
        assertEquals(listOf("work", "data", "credential"), f.effects)
        assertEquals(listOf("work", "data", "credential"), f.closed)
        val complete = f.events.indexOf("complete"); assertTrue(f.events.indexOf("close-credential") < complete)
        assertEquals(OPERATION, (RetirementCodec.decode(f.record!!.payload) as RetirementState.Complete).operationId)
        assertEquals(SessionSetupRecoveryPhase.COMPLETE, owner.phase())
        rejected(FailureReason.CONFLICT, f.root.close()); rejected(FailureReason.CONFLICT, f.reservation.release())
        f.finish(owner); assertEquals("control", f.closed.last())
    }

    @Test fun failedInitialConfirmationNeverStartsAbortEvenWhenWriteBecameVisible() = runTest {
        for (after in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
            f.failCas = 1; f.afterCas = after
            rejected(FailureReason.OUTCOME_UNKNOWN, owner.confirmAbort(ok(owner.prepareAbort())))
            assertTrue(f.effects.isEmpty()); assertTrue(f.closed.isEmpty())
            assertEquals(after, (RetirementCodec.decode(f.record!!.payload) as RetirementState.PendingSetup).abortRequested)
            f.finish(owner)
        }
    }

    @Test fun abortFailuresStopLaterEffectsAndRetryReacknowledgesBeforeClosing() = runTest {
        for ((index, part) in listOf("work", "data", "credential").withIndex()) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
            f.failures["abort-$part"] = FailureReason.UNAVAILABLE
            rejected(FailureReason.UNAVAILABLE, owner.confirmAbort(ok(owner.prepareAbort())))
            assertEquals(listOf("work", "data", "credential").take(index + 1), f.effects); assertTrue(f.closed.isEmpty())
            val before = f.record!!.revision; f.failures.clear(); f.effects.clear(); ok(owner.retryAbort())
            assertTrue(f.record!!.revision > before); assertEquals(listOf("work", "data", "credential"), f.effects); f.finish(owner)
        }
    }

    @Test fun failedCloseRetainsCheckpointAndNeverReinspectsAlreadyClosedHandles() = runTest {
        for (part in listOf("work", "data", "credential")) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
            f.failures["close-$part"] = FailureReason.STORAGE_FAILURE
            rejected(FailureReason.STORAGE_FAILURE, owner.confirmAbort(ok(owner.prepareAbort())))
            assertEquals(SessionSetupRecoveryPhase.CLOSING, owner.phase()); assertIs<RetirementState.PendingSetup>(RetirementCodec.decode(f.record!!.payload))
            val observations = f.observations; val effects = f.effects.toList(); val closed = f.closed.toList()
            f.failures.clear(); ok(owner.retryAbort())
            assertEquals(observations, f.observations); assertEquals(effects, f.effects)
            for (done in closed) assertEquals(1, f.events.count { it == "close-$done" })
            f.finish(owner)
        }
    }

    @Test fun finalCasFailureBeforeCommitRetriesOnlyFreshCompleteAfterAcknowledgedCloses() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
        f.failCas = 2; rejected(FailureReason.OUTCOME_UNKNOWN, owner.confirmAbort(ok(owner.prepareAbort())))
        val before = f.record!!; val observations = f.observations; val effects = f.effects.toList()
        ok(owner.retryAbort()); assertEquals(before.revision + 1, f.record!!.revision)
        assertEquals(observations, f.observations); assertEquals(effects, f.effects); f.finish(owner)
    }

    @Test fun visibleUnknownCompleteNeedsRetainedCheckpointAndFreshChangedCasEveryRetry() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
        f.failCas = 2; f.afterCas = true
        rejected(FailureReason.OUTCOME_UNKNOWN, owner.confirmAbort(ok(owner.prepareAbort())))
        val before = f.record!!; val effects = f.effects.toList(); val observations = f.observations
        f.failCas = 3; rejected(FailureReason.OUTCOME_UNKNOWN, owner.retryAbort())
        assertEquals(before.revision + 1, f.record!!.revision)
        ok(owner.retryAbort()); assertEquals(before.revision + 2, f.record!!.revision)
        assertEquals(effects, f.effects); assertEquals(observations, f.observations); f.finish(owner)
    }

    @Test fun competingControlAfterAllClosesNeverReceivesComplete() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
        f.failCas = 2; rejected(FailureReason.OUTCOME_UNKNOWN, owner.confirmAbort(ok(owner.prepareAbort())))
        f.record = SessionControlRecord(f.record!!.revision + 1, f.record!!.payload); val before = f.writes
        rejected(FailureReason.CONFLICT, owner.retryAbort()); assertEquals(before, f.writes); f.finish(owner)
    }

    @Test fun abandoningAfterOneOrTwoClosesLeavesOriginalConfirmedPlanForNewOwner() = runTest {
        for (part in listOf("data", "credential")) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val first = f.owner(); ok(first.open())
            f.failures["close-$part"] = FailureReason.STORAGE_FAILURE
            rejected(FailureReason.STORAGE_FAILURE, first.confirmAbort(ok(first.prepareAbort())))
            assertEquals(if (part == "data") 1 else 2, f.closed.size)
            f.failures.clear(); ok(first.close()); assertIs<RetirementState.PendingSetup>(RetirementCodec.decode(f.record!!.payload))
            f.newReservation(); val second = f.owner(); ok(second.open()); ok(second.inspect())
            rejected(FailureReason.CONFLICT, second.prepareAbort()); ok(second.retryAbort()); f.finish(second)
        }
    }

    @Test fun freshOwnerCannotInferConfirmationFromPersistedComplete() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val first = f.owner(); ok(first.open())
        f.failCas = 2; f.afterCas = true; rejected(FailureReason.OUTCOME_UNKNOWN, first.confirmAbort(ok(first.prepareAbort())))
        ok(first.close()); f.newReservation(); val second = f.owner()
        rejected(FailureReason.CONFLICT, second.open()); assertIs<PortResult.Failure>(second.retryAbort()); f.finish(second)
    }

    @Test fun explicitCloseNeverCompletesAndRetainsFailedSubordinateBeforeControl() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
        f.failures["close-data"] = FailureReason.STORAGE_FAILURE
        rejected(FailureReason.STORAGE_FAILURE, owner.close()); assertFalse("control" in f.closed)
        rejected(FailureReason.CONFLICT, f.root.close()); rejected(FailureReason.CONFLICT, f.reservation.release())
        assertEquals(0, f.writes); f.failures.clear(); f.finish(owner)
    }

    @Test fun failedControlCloseRetainsReservationAndDoesNotRepeatAcknowledgedSubordinateCloses() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
        f.failures["close-control"] = FailureReason.STORAGE_FAILURE
        rejected(FailureReason.STORAGE_FAILURE, owner.close()); val events = f.events.toList()
        rejected(FailureReason.CONFLICT, f.reservation.release()); f.failures.clear(); f.finish(owner)
        for (part in listOf("work", "data", "credential")) assertEquals(1, f.events.count { it == "close-$part" })
        assertEquals(2, f.events.count { it == "close-control" }); assertTrue(events.isNotEmpty())
    }

    @Test fun invalidatedCompositionFencesReadyAndClosingButStillPermitsExactCleanup() = runTest {
        for (closing in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
            if (closing) { f.failures["close-data"] = FailureReason.STORAGE_FAILURE
                rejected(FailureReason.STORAGE_FAILURE, owner.confirmAbort(ok(owner.prepareAbort()))) }
            ok(f.root.invalidate()); val writes = f.writes
            assertIs<PortResult.Failure>(owner.inspect()); assertIs<PortResult.Failure>(owner.retryAbort())
            assertEquals(writes, f.writes); f.failures.clear(); f.finish(owner)
        }
    }

    @Test fun cancellationDuringAcquisitionIsCloseOnlyAndRetainsAcquiredOwner() = runTest {
        for (part in PARTS) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner()
            f.hook = { if (it == "open-$part") currentCoroutineContext().cancel() }
            val job = async { owner.open() }; assertFailsWith<CancellationException> { job.await() }
            assertEquals(SessionSetupRecoveryPhase.CLOSE_ONLY, owner.phase()); f.hook = {}; f.finish(owner)
        }
    }

    @Test fun cancellationAfterAcknowledgedCloseRetainsThatCloseAndDoesNotInspectItAgain() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open()); val proposal = ok(owner.prepareAbort())
        val caller = Job(currentCoroutineContext()[Job]); f.hook = { if (it == "closed-work") caller.cancel() }
        val job = async(caller) { owner.confirmAbort(proposal) }; assertFailsWith<CancellationException> { job.await() }
        assertEquals(listOf("work"), f.closed); val observations = f.observations; f.hook = {}
        ok(owner.retryAbort()); assertEquals(observations, f.observations)
        assertEquals(1, f.events.count { it == "close-work" }); f.finish(owner)
    }

    @Test fun thrownNativeCloseFailureIsSanitizedRetainedAndNeverCompletes() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); ok(owner.open())
        f.hook = { if (it == "close-data") error("private-token private-path") }
        val result = owner.confirmAbort(ok(owner.prepareAbort())); rejected(FailureReason.STORAGE_FAILURE, result)
        assertFalse(result.toString().contains("private-token")); assertIs<RetirementState.PendingSetup>(RetirementCodec.decode(f.record!!.payload))
        f.hook = {}; ok(owner.retryAbort()); f.finish(owner)
    }

    @Test fun synchronousFactoryExceptionRetainsEveryPreviouslyReturnedOwnerAndNeverInitializesAnother() = runTest {
        for (part in PARTS) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.throwFactory = part
            val owner = f.owner(); val result = owner.open(); rejected(FailureReason.STORAGE_FAILURE, result)
            assertFalse(result.toString().contains("private-path")); assertEquals(SessionSetupRecoveryPhase.CLOSE_ONLY, owner.phase())
            assertTrue(f.effects.isEmpty()); assertEquals(0, f.writes); f.finish(owner)
            assertEquals(f.created.toSet(), f.closed.toSet())
        }
    }

    @Test fun callerReturnGapNeverPublishesReadyAndCancellationCloseOrRootInvalidationWins() = runTest {
        for (action in listOf("cancel", "close", "invalidate")) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner(); val caller = HandoffDispatcher()
            val opening = async(caller) { owner.open() }; caller.runNext(); runCurrent()
            assertTrue(caller.pending.isNotEmpty()); assertEquals(SessionSetupRecoveryPhase.OPENING, owner.phase())
            rejected(FailureReason.STALE_SESSION, owner.inspect()); rejected(FailureReason.STALE_SESSION, owner.prepareAbort())
            when (action) { "cancel" -> opening.cancel(); "close" -> ok(owner.close()); else -> ok(f.root.invalidate()) }
            repeat(4) { caller.runAll(); runCurrent() }
            if (action == "cancel") assertFailsWith<CancellationException> { opening.await() }
            else rejected(FailureReason.STALE_SESSION, opening.await())
            assertNotEquals(SessionSetupRecoveryPhase.READY, owner.phase()); assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
            f.finish(owner)
        }
    }

    @Test fun cancelledQueuedSecondOpenerCannotQuarantineFirstAdmittedOwner() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val owner = f.owner()
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        f.hook = { if (it == "open-control") { entered.complete(Unit); released.await() } }
        val first = async { owner.open() }; entered.await()
        val second = async { owner.open() }; runCurrent(); second.cancel(); runCurrent()
        released.complete(Unit); ok(first.await()); assertFailsWith<CancellationException> { second.await() }
        ok(owner.inspect()); assertEquals(1, f.events.count { it == "open-control" }); f.finish(owner)
    }

    private class Fixture(val dispatcher: CoroutineDispatcher, configuration: String = CONFIG) {
        val boundary = SessionBoundary()
        val root = ok(SessionApplicationComposition.create(boundary, dispatcher, configuration))
        var reservation = ok(root.reserve())
        var record: SessionControlRecord? = record(RetirementState.PendingSetup(PLAN, false))
        var credential = CredentialCreatePlanObservation(CredentialCreateRecoveryStatus.PREPARED, fingerprint(1))
        var data = StateActivationPlanObservation(StateActivationStatus.PREPARED, fingerprint(2))
        var work = SessionWorkOriginObservation(SessionWorkOriginPlanStatus.PREPARED, 7)
        val created = mutableListOf<String>(); val closed = mutableListOf<String>(); val events = mutableListOf<String>()
        val effects = mutableListOf<String>(); val failures = mutableMapOf<String, FailureReason>()
        var writes = 0; var observations = 0; var failCas = -1; var afterCas = false
        var hook: suspend (String) -> Unit = {}; var throwFactory: String? = null; private var receipt = 10
        fun newReservation() { reservation = ok(root.reserve()); created.clear(); closed.clear() }
        fun owner(): SessionSetupRecoveryOwner = RetainedSessionSetupRecoveryOwner(reservation, object : SessionSetupRecoveryFactories {
            override fun control(): ExistingSessionControlRecoveryStore {
                made("control"); val part = Part("control")
                return object : ExistingSessionControlRecoveryStore {
                    override suspend fun open() = part.open()
                    override suspend fun close() = part.close()
                    override suspend fun read(): PortResult<SessionControlRecord?> { part.alive(); event("read-control"); return PortResult.Value(record) }
                    override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
                        part.alive(); writes++
                        val complete = RetirementCodec.decode(payload) is RetirementState.Complete
                        if (complete) { assertTrue(closed.containsAll(listOf("work", "data", "credential"))); event("complete") }
                        else event("confirm")
                        if (failCas == writes && !afterCas) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
                        if (record?.revision != expectedRevision) return PortResult.Failure(FailureReason.CONFLICT)
                        val next = SessionControlRecord(expectedRevision!! + 1, payload); record = next
                        return if (failCas == writes) PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else PortResult.Value(next)
                    }
                }
            }
            override fun credentials(scope: StorageScope, plan: CredentialCreatePlan): CredentialCreateRecoveryOwner {
                assertEquals(SCOPE, scope); same(CREDENTIAL.copyForStorage(), plan.copyForStorage()); made("credential"); val part = Part("credential")
                return object : CredentialCreateRecoveryOwner {
                    override suspend fun open() = part.open()
                    override suspend fun close() = part.close()
                    override suspend fun inspect(): PortResult<CredentialCreatePlanObservation> { part.observe(); return PortResult.Value(credential) }
                    override suspend fun abort() = part.abort { credential = CredentialCreatePlanObservation(CredentialCreateRecoveryStatus.ABORTED, fingerprint(3)) }
                }
            }
            override fun data(scope: StorageScope, plan: StateActivationPlan): StateActivationRecoveryOwner {
                assertEquals(SCOPE, scope); assertContentEquals(DATA.copyForStorage(), plan.copyForStorage()); made("data"); val part = Part("data")
                return object : StateActivationRecoveryOwner {
                    override suspend fun open() = part.open()
                    override suspend fun close() = part.close()
                    override suspend fun inspect(): PortResult<StateActivationPlanObservation> { part.observe(); return PortResult.Value(data) }
                    override suspend fun binding(): PortResult<StateRecordInspection> { part.observe(); return PortResult.Value(StateRecordInspection(null, null)) }
                    override suspend fun abort(expectedBinding: PrivateBytes?): PortResult<Unit> { assertNull(expectedBinding)
                        return part.abort { data = StateActivationPlanObservation(StateActivationStatus.ABORTED, fingerprint(++receipt)) } }
                }
            }
            override fun work(scope: StorageScope, plan: SessionWorkOriginPlan): SessionWorkOriginRecoveryOwner {
                assertEquals(SCOPE, scope); same(WORK.copyForStorage(), plan.copyForStorage()); made("work"); val part = Part("work")
                return object : SessionWorkOriginRecoveryOwner {
                    override suspend fun open() = part.open()
                    override suspend fun close() = part.close()
                    override suspend fun inspect(): PortResult<SessionWorkOriginObservation> { part.observe(); return PortResult.Value(work) }
                    override suspend fun abort() = part.abort { work = SessionWorkOriginObservation(SessionWorkOriginPlanStatus.ABORTED, work.revision + 1) }
                }
            }
        })
        private fun made(name: String) {
            events += "create-$name"
            if (throwFactory == name) error("private-path factory failure")
            created += name
        }
        private suspend fun event(name: String) { events += name; hook(name) }
        private inner class Part(val name: String) {
            var isClosed = false
            fun alive() { assertFalse(isClosed, "A closed handle was used: $name") }
            suspend fun open(): PortResult<Unit> { alive(); event("open-$name"); return result("open-$name") }
            suspend fun observe() { alive(); observations++; event("inspect-$name") }
            suspend fun abort(change: () -> Unit): PortResult<Unit> {
                alive(); effects += name; event("abort-$name")
                assertTrue((RetirementCodec.decode(record!!.payload) as RetirementState.PendingSetup).abortRequested)
                val result = result("abort-$name"); if (result is PortResult.Value) change(); return result
            }
            suspend fun close(): PortResult<Unit> {
                if (isClosed) return PortResult.Value(Unit)
                event("close-$name"); val result = result("close-$name")
                if (result is PortResult.Value) { isClosed = true; closed += name; event("closed-$name") }
                return result
            }
            fun result(name: String): PortResult<Unit> = failures[name]?.let { PortResult.Failure(it) } ?: PortResult.Value(Unit)
        }
        suspend fun finish(owner: SessionSetupRecoveryOwner) { ok(owner.close()); ok(root.close()) }
    }
    private class HandoffDispatcher : CoroutineDispatcher() {
        val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun runNext() = pending.removeFirst().run()
        fun runAll() { while (pending.isNotEmpty()) runNext() }
    }
    companion object {
        private val PARTS = listOf("control", "credential", "data", "work")
        private val SCOPE = StorageScope("owned-startup-test", ActorKind.ACCOUNT, "private-owner")
        private val CONFIG = "d".repeat(64)
        private const val OPERATION = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        private const val INCARNATION = "11111111-2222-4333-8444-555555555555"
        private const val ORIGIN = "22222222-3333-4444-8555-666666666666"
        private val CREDENTIAL = CredentialCreatePlan.create(CredentialCreatePlanRecord(3, INCARNATION, "a".repeat(64), "b".repeat(64), "c".repeat(64)))
        private val DATA = StateActivationPlan(ByteArray(170).apply {
            this[0] = 1; for (i in 2..65) this[i] = 'a'.code.toByte(); for (i in 106..137) this[i] = 'b'.code.toByte()
        })
        private val WORK = SessionWorkOriginPlan.create(SessionWorkOriginPlanRecord(7, SCOPE, ORIGIN, PrivateBytes(ByteArray(64))))
        private val PLAN = SessionSetupPlan.create(SessionSetupPlanRecord(OPERATION, SCOPE, CONFIG, CREDENTIAL, DATA, WORK))
        private fun record(state: RetirementState) = SessionControlRecord(11, RetirementCodec.encode(state))
        private fun fingerprint(value: Int) = PrivateBytes(ByteArray(32) { value.toByte() })
        private fun <T> ok(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun rejected(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private fun same(a: PrivateBytes, b: PrivateBytes) = assertContentEquals(a.copyForCodec(), b.copyForCodec())
    }
}
