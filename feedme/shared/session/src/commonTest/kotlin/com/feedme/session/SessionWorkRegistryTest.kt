package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Deterministic authority/uncertainty tests, not proof of native OS scheduling or cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionWorkRegistryTest {
    @Test fun emptyDiscardRemovesOnlyExactOriginWithoutNativeEffectsAndRetriesIdempotently() = runTest {
        withFixture { f ->
            val binding = f.bind()
            f.boundary.clear()
            value(f.registry.retireEmpty(ACCOUNT, binding.originBinding))
            assertNull(value(f.registry.snapshot()).originBinding)
            val attempts = f.control.attempts
            value(f.registry.retireEmpty(ACCOUNT, binding.originBinding))
            assertEquals(attempts, f.control.attempts)
            assertTrue(f.cancelled.isEmpty())
            assertEquals(0, f.executionCalls)
        }
    }

    @Test fun emptyDiscardRejectsEveryNativeEntryPhaseWithoutMutationOrCancellation() = runTest {
        for (phase in NativeWorkPhase.entries) withFixture { f ->
            f.control.replaceState(SessionWorkState.Origin(ACCOUNT, uuid(100), false, listOf(entry(10, phase))))
            val before = f.control.record!!.payload.copyForCodec()
            failure(FailureReason.CONFLICT, f.registry.retireEmpty(ACCOUNT, uuid(100)))
            assertContentEquals(before, f.control.record!!.payload.copyForCodec())
            assertEquals(0, f.control.attempts)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun emptyDiscardNeverTouchesDifferentOwnerOrNewerOrigin() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val ticket = f.install(binding)
            val before = f.control.record!!.payload.copyForCodec()
            val attempts = f.control.attempts
            value(f.registry.retireEmpty(ACCOUNT, uuid(900)))
            value(f.registry.retireEmpty(ACCOUNT.copy(actorKind = ActorKind.GUEST), binding.originBinding))
            assertContentEquals(before, f.control.record!!.payload.copyForCodec())
            assertEquals(attempts, f.control.attempts)
            value(f.registry.runLocalEffect(ticket) { PortResult.Value(Unit) })
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun emptyDiscardCasFailurePreservesOriginAndRetriesWithoutNativeCancellation() = runTest {
        withFixture { f ->
            val binding = f.bind()
            f.control.beforeFailures[f.control.attempts + 1] = FailureReason.STORAGE_FAILURE
            failure(FailureReason.STORAGE_FAILURE, f.registry.retireEmpty(ACCOUNT, binding.originBinding))
            assertEquals(binding.originBinding, value(f.registry.snapshot()).originBinding)
            failure(FailureReason.STALE_SESSION, f.registry.resume(f.lease))
            value(f.registry.retireEmpty(ACCOUNT, binding.originBinding))
            assertNull(value(f.registry.snapshot()).originBinding)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun emptyDiscardReconcilesExactAmbiguousCommitAndPreservesConcurrentNewEntry() = runTest {
        withFixture { f ->
            val binding = f.bind()
            f.control.afterFailures[f.control.attempts + 1] = FailureReason.OUTCOME_UNKNOWN
            value(f.registry.retireEmpty(ACCOUNT, binding.originBinding))
            assertNull(value(f.registry.snapshot()).originBinding)
            assertTrue(f.cancelled.isEmpty())
        }
        withFixture { f ->
            val binding = f.bind()
            f.control.beforeCas = { _, _ ->
                f.control.beforeCas = { _, _ -> }
                f.control.replaceState(SessionWorkState.Origin(ACCOUNT, binding.originBinding, false, listOf(entry(10, NativeWorkPhase.INSTALLED))))
            }
            failure(FailureReason.CONFLICT, f.registry.retireEmpty(ACCOUNT, binding.originBinding))
            failure(FailureReason.CONFLICT, f.registry.retireEmpty(ACCOUNT, binding.originBinding))
            assertEquals(uuid(10), value(f.registry.snapshot()).entries.single().ticket.id)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun emptyDiscardRejectsMalformedIdentityBeforeReadingOrWriting() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val attempts = f.control.attempts
            failure(FailureReason.INVALID_DATA, f.registry.retireEmpty(ACCOUNT, "../not-an-origin"))
            assertEquals(binding.originBinding, value(f.registry.snapshot()).originBinding)
            assertEquals(attempts, f.control.attempts)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun nativeIdentityFactoryValidatesAndRedactsButNeverGrantsCallbackAuthority() = runTest {
        withFixture { f ->
            val id = "abcdef01-2345-4678-89ab-cdef01234567"
            for (kind in NativeWorkKind.entries) {
                val ticket = value(NativeWorkTicket.fromNativeIdentity(id, kind))
                assertEquals(id, ticket.id)
                assertEquals(kind, ticket.kind)
                assertFalse(ticket.toString().contains(id))
                failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(ticket) { fail("Unregistered native identity became authority") })
            }
            for (malformed in listOf("", id.uppercase(), "$id ", "$id\n", "../$id", "private-token", "x".repeat(201))) {
                val result = NativeWorkTicket.fromNativeIdentity(malformed, NativeWorkKind.TIMER)
                failure(FailureReason.INVALID_DATA, result)
                if (malformed.isNotEmpty()) assertFalse(result.toString().contains(malformed))
            }
            assertEquals(0, f.executionCalls)
            assertEquals(0, f.control.attempts)
        }
    }

    @Test fun openIsReadOnlySingletonAndCloseReleasesOnlyManagerOwnership() = runTest {
        withFixture { f ->
            assertEquals(0, f.control.attempts)
            assertNull(value(f.registry.snapshot()).originBinding)
            failure(FailureReason.CONFLICT, f.openResult())
            assertEquals(0, f.control.attempts)
            value(f.registry.close())
            failure(FailureReason.STORAGE_FAILURE, f.registry.snapshot())
            failure(FailureReason.STORAGE_FAILURE, f.registry.createOrigin(f.lease, 1))
            f.reopen()
            assertEquals(1, value(f.registry.snapshot()).revision)
            assertEquals(0, f.control.attempts)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun createsFreshOriginWithExactCasAndOnlyExplicitVerifiedLeaseCanResume() = runTest {
        withFixture { f ->
            failure(FailureReason.NOT_CONFIGURED, f.registry.resume(f.lease))
            failure(FailureReason.CONFLICT, f.registry.createOrigin(f.lease, 2))
            failure(FailureReason.INVALID_DATA, f.registry.createOrigin(f.lease, 0))
            val binding = f.bind()
            assertEquals(uuid(1), binding.originBinding)
            val snapshot = value(f.registry.snapshot())
            assertEquals(2, snapshot.revision)
            assertEquals(ACCOUNT, snapshot.scope)
            assertFalse(snapshot.retiring)
            assertEquals(binding.originBinding, value(f.registry.resume(f.lease)).originBinding)
            failure(FailureReason.CONFLICT, f.registry.createOrigin(f.lease, snapshot.revision))
            assertEquals(1, f.control.attempts)
            assertEquals(listOf(ACCOUNT, ACCOUNT, ACCOUNT, ACCOUNT, ACCOUNT), f.admitted.takeLast(5))
        }
    }

    @Test fun staleLeaseAndDifferentGuestAccountOrEnvironmentCannotTakeExistingOrigin() = runTest {
        withFixture { f ->
            val binding = f.bind()
            for (scope in listOf(ACCOUNT.copy(actorKind = ActorKind.GUEST), ACCOUNT.copy(environment = "another-environment"), ACCOUNT.copy(actorId = "another-owner"))) {
                val replacement = f.boundary.activate(scope)
                failure(FailureReason.STALE_SESSION, f.registry.resume(replacement))
                failure(FailureReason.STALE_SESSION, f.registry.install(binding, NativeWorkKind.TIMER, "old-timer") { fail("Stale installer called") })
            }
            failure(FailureReason.STALE_SESSION, f.registry.createOrigin(f.lease, 2))
            assertEquals(1, f.control.attempts)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun admissionDenialFailureAndInvalidScopeNeverCreateOrInstallNativeWork() = runTest {
        withFixture { f ->
            f.admit = { PortResult.Value(false) }
            failure(FailureReason.STALE_SESSION, f.registry.createOrigin(f.lease, 1))
            f.admit = { PortResult.Failure(FailureReason.UNAVAILABLE) }
            failure(FailureReason.UNAVAILABLE, f.registry.createOrigin(f.lease, 1))
            assertEquals(0, f.control.attempts)
            f.admit = { PortResult.Value(true) }
            val binding = f.bind()
            f.admit = { throw IllegalStateException("private-policy-detail") }
            failure(FailureReason.STORAGE_FAILURE, f.registry.install(binding, NativeWorkKind.TIMER, "timer") { fail("Denied installer called") })
            val demo = f.boundary.activate(ACCOUNT.copy(actorKind = ActorKind.DEMO))
            failure(FailureReason.INVALID_DATA, f.registry.createOrigin(demo, 2))
            assertEquals(1, f.control.attempts)
        }
    }

    @Test fun originCasFailureDoesNotInventOriginAndCommittedUnknownReadsBackExactIntent() = runTest {
        withFixture { f ->
            f.control.beforeFailures[1] = FailureReason.STORAGE_FAILURE
            failure(FailureReason.STORAGE_FAILURE, f.registry.createOrigin(f.lease, 1))
            assertSame(SessionWorkState.Idle, f.control.state())
            f.control.afterFailures[2] = FailureReason.OUTCOME_UNKNOWN
            val binding = value(f.registry.createOrigin(f.lease, 1))
            assertEquals(uuid(2), binding.originBinding)
            assertEquals(binding.originBinding, value(f.registry.snapshot()).originBinding)
            assertEquals(2, f.control.attempts)
            assertEquals(2, value(f.registry.snapshot()).revision)
        }
    }

    @Test fun durableReservationPrecedesInstallerAndInstalledCallbackUsesExactPolicyContext() = runTest {
        withFixture { f ->
            val binding = f.bind()
            var installed: NativeWorkTicket? = null
            val ticket = value(f.registry.install(binding, NativeWorkKind.TIMER, "cook-step-timer") { candidate ->
                val state = assertIs<SessionWorkState.Origin>(f.control.state())
                assertEquals(binding.originBinding, state.origin)
                assertEquals(NativeWorkPhase.RESERVED, state.entries.single().phase)
                assertEquals(candidate.id, state.entries.single().id)
                assertEquals(3, f.control.record!!.revision)
                installed = candidate
                PortResult.Value(Unit)
            })
            assertEquals(installed!!.id, ticket.id)
            assertEquals(NativeWorkPhase.INSTALLED, value(f.registry.snapshot()).entries.single().phase)
            var effects = 0
            f.execute = { scope, origin, logical, actual ->
                assertEquals(ACCOUNT, scope)
                assertEquals(binding.originBinding, origin)
                assertEquals("cook-step-timer", logical)
                assertEquals(ticket.id, actual.id)
                assertEquals(NativeWorkKind.TIMER, actual.kind)
                PortResult.Value(true)
            }
            value(f.registry.runLocalEffect(ticket) { effects++; PortResult.Value(Unit) })
            assertEquals(1, effects)
            assertTrue(f.cancelled.isEmpty())
            assertEquals(3, f.control.attempts)
        }
    }

    @Test fun cancellationDuringInstallerPreservesReservedTicketAndQueuedCallbackCannotRun() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val entered = CompletableDeferred<NativeWorkTicket>()
            val release = CompletableDeferred<Unit>()
            val install = async {
                f.registry.install(binding, NativeWorkKind.TIMER, "timer") { ticket ->
                    entered.complete(ticket)
                    release.await()
                    throw CancellationException("fixture cancellation")
                }
            }
            val ticket = entered.await()
            var effects = 0
            val callback = async { f.registry.runLocalEffect(ticket) { effects++; PortResult.Value(Unit) } }
            runCurrent()
            assertFalse(callback.isCompleted)
            assertEquals(NativeWorkPhase.RESERVED, assertIs<SessionWorkState.Origin>(f.control.state()).entries.single().phase)
            release.complete(Unit)
            assertFailsWith<CancellationException> { install.await() }
            failure(FailureReason.STALE_SESSION, callback.await())
            assertEquals(0, effects)
            assertTrue(f.cancelled.isEmpty())
            f.reopen()
            val resumed = value(f.registry.resume(f.lease))
            value(f.registry.reconcilePending(resumed))
            assertEquals(listOf(ticket.id), f.cancelled.map { it.id })
            assertTrue(value(f.registry.snapshot()).entries.isEmpty())
        }
    }

    @Test fun retirementFencesBlockedInstallerBeforeMutexAndCompensatesItsExactTicket() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val entered = CompletableDeferred<NativeWorkTicket>()
            val release = CompletableDeferred<Unit>()
            val install = async {
                f.registry.install(binding, NativeWorkKind.WORKER, "sync-worker") { ticket ->
                    entered.complete(ticket); release.await(); PortResult.Value(Unit)
                }
            }
            val ticket = entered.await()
            val retirement = async { f.registry.retire(ACCOUNT, binding.originBinding) }
            runCurrent()
            assertFalse(retirement.isCompleted)
            release.complete(Unit)
            failure(FailureReason.STALE_SESSION, install.await())
            value(retirement.await())
            assertEquals(listOf(ticket.id), f.cancelled.map { it.id })
            assertEquals(listOf(NativeWorkKind.WORKER), f.cancelled.map { it.kind })
            assertNull(value(f.registry.snapshot()).originBinding)
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(ticket) { fail("Retired effect called") })
        }
    }

    @Test fun cancellationFailureDuringStaleInstallStillAllowsRetirementToRetryOriginalIdentity() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val entered = CompletableDeferred<NativeWorkTicket>()
            val release = CompletableDeferred<Unit>()
            var cleanupCalls = 0
            f.cancel = { if (++cleanupCalls == 1) PortResult.Failure(FailureReason.UNAVAILABLE) else PortResult.Value(Unit) }
            val install = async { f.registry.install(binding, NativeWorkKind.TIMER, "timer") { ticket ->
                entered.complete(ticket); release.await(); PortResult.Value(Unit)
            } }
            val ticket = entered.await()
            val retirement = async { f.registry.retire(ACCOUNT, binding.originBinding) }
            runCurrent()
            release.complete(Unit)
            failure(FailureReason.UNAVAILABLE, install.await())
            value(retirement.await())
            assertEquals(listOf(ticket.id, ticket.id), f.cancelled.map { it.id })
            assertNull(value(f.registry.snapshot()).originBinding)
        }
    }

    @Test fun leaseChangesDuringInstallCannotAdmitTheScheduledWork() = runTest {
        withFixture { f ->
            val binding = f.bind()
            var ticket: NativeWorkTicket? = null
            failure(FailureReason.STALE_SESSION, f.registry.install(binding, NativeWorkKind.TIMER, "timer") {
                ticket = it
                f.boundary.activate(ACCOUNT.copy(actorId = "new-owner"))
                PortResult.Value(Unit)
            })
            assertEquals(listOf(ticket!!.id), f.cancelled.map { it.id })
            assertTrue(value(f.registry.snapshot()).entries.isEmpty())
        }
    }

    @Test fun postInstallAdmissionFailureCompensatesWithoutErasingTheOrigin() = runTest {
        withFixture { f ->
            val binding = f.bind()
            var ticket: NativeWorkTicket? = null
            failure(FailureReason.FORBIDDEN, f.registry.install(binding, NativeWorkKind.TIMER, "timer") {
                ticket = it
                f.admit = { PortResult.Failure(FailureReason.FORBIDDEN) }
                PortResult.Value(Unit)
            })
            assertEquals(listOf(ticket!!.id), f.cancelled.map { it.id })
            assertEquals(binding.originBinding, value(f.registry.snapshot()).originBinding)
            assertTrue(value(f.registry.snapshot()).entries.isEmpty())
        }
    }

    @Test fun installerTypedFailureOrExceptionCancelsExactReservationAndNeverInstallsIt() = runTest {
        for (throws in listOf(false, true)) withFixture { f ->
            val binding = f.bind()
            var ticket: NativeWorkTicket? = null
            val outcome = f.registry.install(binding, NativeWorkKind.TIMER, "timer") {
                ticket = it
                if (throws) throw IllegalStateException("private-native-detail")
                PortResult.Failure(FailureReason.UNAVAILABLE)
            }
            failure(if (throws) FailureReason.STORAGE_FAILURE else FailureReason.UNAVAILABLE, outcome)
            assertEquals(listOf(ticket!!.id), f.cancelled.map { it.id })
            assertTrue(value(f.registry.snapshot()).entries.isEmpty())
        }
    }

    @Test fun exactCancelThenReplacementGetsNewGenerationAndOldTicketCannotAffectIt() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val old = f.install(binding)
            failure(FailureReason.CONFLICT, f.registry.install(binding, NativeWorkKind.TIMER, "timer") { fail("Duplicate logical task installed") })
            val worker = f.install(binding, NativeWorkKind.WORKER, "timer")
            value(f.registry.cancel(binding, old))
            val fresh = f.install(binding)
            assertNotEquals(old.id, fresh.id)
            value(f.registry.cancel(binding, old))
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(old) { fail("Old generation ran") })
            value(f.registry.runLocalEffect(fresh) { PortResult.Value(Unit) })
            value(f.registry.runLocalEffect(worker) { PortResult.Value(Unit) })
            assertEquals(listOf(old.id), f.cancelled.map { it.id })
            assertEquals(setOf(fresh.id, worker.id), value(f.registry.snapshot()).entries.map { it.ticket.id }.toSet())
        }
    }

    @Test fun wrongKindCancellationCannotDisableOrCancelTheLegitimateTicket() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val ticket = f.install(binding)
            failure(FailureReason.INVALID_DATA, f.registry.cancel(binding, NativeWorkTicket(ticket.id, NativeWorkKind.WORKER)))
            assertTrue(f.cancelled.isEmpty())
            value(f.registry.runLocalEffect(ticket) { PortResult.Value(Unit) })
            assertEquals(NativeWorkPhase.INSTALLED, value(f.registry.snapshot()).entries.single().phase)
        }
    }

    @Test fun failedNativeCancellationPersistsCancellingAndReconcileUsesSameIdentityAfterReopen() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val ticket = f.install(binding)
            f.cancel = { throw IllegalStateException("private-cancellation-detail") }
            failure(FailureReason.STORAGE_FAILURE, f.registry.cancel(binding, ticket))
            assertEquals(NativeWorkPhase.CANCELLING, value(f.registry.snapshot()).entries.single().phase)
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(ticket) { fail("Cancelling effect ran") })
            f.reopen()
            f.cancel = { PortResult.Value(Unit) }
            value(f.registry.reconcilePending(value(f.registry.resume(f.lease))))
            assertEquals(listOf(ticket.id, ticket.id), f.cancelled.map { it.id })
            assertTrue(value(f.registry.snapshot()).entries.isEmpty())
        }
    }

    @Test fun cancellationCheckpointFailureRetainsFenceAndRetriesExactNativeIdentity() = runTest {
        for (checkpoint in listOf("barrier", "removal")) withFixture { f ->
            val binding = f.bind()
            val ticket = f.install(binding)
            f.control.beforeFailures[f.control.attempts + if (checkpoint == "barrier") 1 else 2] = FailureReason.STORAGE_FAILURE
            failure(FailureReason.STORAGE_FAILURE, f.registry.cancel(binding, ticket))
            assertEquals(listOf(ticket.id), f.cancelled.map { it.id })
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(ticket) { fail("Unconfirmed cancellation admitted callback") })
            assertEquals(ticket.id, value(f.registry.snapshot()).entries.single().ticket.id)
            value(f.registry.cancel(binding, ticket))
            assertEquals(listOf(ticket.id, ticket.id), f.cancelled.map { it.id })
            assertTrue(value(f.registry.snapshot()).entries.isEmpty())
        }
    }

    @Test fun reconcileCancelsOnlyUncertainEntriesContinuesFailuresAndPreservesInstalledTask() = runTest {
        withFixture { f ->
            val entries = listOf(entry(10, NativeWorkPhase.RESERVED), entry(11, NativeWorkPhase.CANCELLING), entry(12, NativeWorkPhase.INSTALLED))
            f.control.replaceState(SessionWorkState.Origin(ACCOUNT, uuid(100), false, entries))
            val binding = value(f.registry.resume(f.lease))
            f.cancel = { if (it.id == uuid(10)) PortResult.Failure(FailureReason.UNAVAILABLE) else PortResult.Value(Unit) }
            failure(FailureReason.UNAVAILABLE, f.registry.reconcilePending(binding))
            assertEquals(listOf(uuid(10), uuid(11)), f.cancelled.map { it.id })
            assertEquals(setOf(uuid(10), uuid(12)), value(f.registry.snapshot()).entries.map { it.ticket.id }.toSet())
            f.cancel = { PortResult.Value(Unit) }
            value(f.registry.reconcilePending(binding))
            assertEquals(listOf(uuid(10), uuid(11), uuid(10)), f.cancelled.map { it.id })
            assertEquals(uuid(12), value(f.registry.snapshot()).entries.single().ticket.id)
        }
    }

    @Test fun reservedCallbacksAndDeniedFailedThrowingExecutionPoliciesNeverExecuteEffects() = runTest {
        withFixture { f ->
            val entries = listOf(entry(10, NativeWorkPhase.RESERVED), entry(11, NativeWorkPhase.INSTALLED))
            f.control.replaceState(SessionWorkState.Origin(ACCOUNT, uuid(100), false, entries))
            var effects = 0
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(entries[0].ticket()) { effects++; PortResult.Value(Unit) })
            assertEquals(0, f.executionCalls)
            f.execute = { _, _, _, _ -> PortResult.Value(false) }
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(entries[1].ticket()) { effects++; PortResult.Value(Unit) })
            f.execute = { _, _, _, _ -> PortResult.Failure(FailureReason.UNAVAILABLE) }
            failure(FailureReason.UNAVAILABLE, f.registry.runLocalEffect(entries[1].ticket()) { effects++; PortResult.Value(Unit) })
            f.execute = { _, _, _, _ -> throw IllegalStateException("private-eligibility-detail") }
            failure(FailureReason.STORAGE_FAILURE, f.registry.runLocalEffect(entries[1].ticket()) { effects++; PortResult.Value(Unit) })
            assertEquals(0, effects)
            assertEquals(0, f.control.attempts)
        }
    }

    @Test fun retirementDuringDelayedExecutionPolicyFencesBeforeSynchronousLocalEffect() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val ticket = f.install(binding)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.execute = { _, _, _, _ -> entered.complete(Unit); release.await(); PortResult.Value(true) }
            var effects = 0
            val callback = async { f.registry.runLocalEffect(ticket) { effects++; PortResult.Value(Unit) } }
            entered.await()
            val retirement = async { f.registry.retire(ACCOUNT, binding.originBinding) }
            runCurrent()
            assertFalse(retirement.isCompleted)
            release.complete(Unit)
            failure(FailureReason.STALE_SESSION, callback.await())
            value(retirement.await())
            assertEquals(0, effects)
            assertEquals(listOf(ticket.id), f.cancelled.map { it.id })
        }
    }

    @Test fun exactCancelDuringDelayedExecutionPolicyPreventsEffectWithoutRetiringOtherTasks() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val ticket = f.install(binding)
            val other = f.install(binding, logicalId = "other-timer")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.execute = { _, _, _, _ -> entered.complete(Unit); release.await(); PortResult.Value(true) }
            val callback = async { f.registry.runLocalEffect(ticket) { fail("Cancelled effect ran") } }
            entered.await()
            val cancel = async { f.registry.cancel(binding, ticket) }
            runCurrent()
            release.complete(Unit)
            failure(FailureReason.STALE_SESSION, callback.await())
            value(cancel.await())
            f.execute = { _, _, _, _ -> PortResult.Value(true) }
            value(f.registry.runLocalEffect(other) { PortResult.Value(Unit) })
            assertEquals(listOf(ticket.id), f.cancelled.map { it.id })
        }
    }

    @Test fun retireContinuesIndependentCancellationFailuresAndRetriesOnlyRemainingExactWork() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val one = f.install(binding)
            val two = f.install(binding, NativeWorkKind.WORKER, "worker")
            f.cancel = { if (it.id == one.id) PortResult.Failure(FailureReason.UNAVAILABLE) else PortResult.Value(Unit) }
            failure(FailureReason.UNAVAILABLE, f.registry.retire(ACCOUNT, binding.originBinding))
            assertEquals(listOf(one.id, two.id), f.cancelled.map { it.id })
            val pending = value(f.registry.snapshot())
            assertTrue(pending.retiring)
            assertEquals(one.id, pending.entries.single().ticket.id)
            failure(FailureReason.STALE_SESSION, f.registry.resume(f.lease))
            f.boundary.clear()
            f.reopen()
            f.admit = { fail("Retirement must not require current account admission") }
            f.execute = { _, _, _, _ -> fail("Retirement must not run callback eligibility") }
            f.cancel = { PortResult.Value(Unit) }
            value(f.registry.retire(ACCOUNT, binding.originBinding))
            assertEquals(listOf(one.id, two.id, one.id), f.cancelled.map { it.id })
            assertNull(value(f.registry.snapshot()).scope)
        }
    }

    @Test fun retirementFailureAtFirstBarrierFencesCallbacksAndRetryDoesNotNeedPrivateStores() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val ticket = f.install(binding)
            f.control.beforeFailures[f.control.attempts + 1] = FailureReason.STORAGE_FAILURE
            failure(FailureReason.STORAGE_FAILURE, f.registry.retire(ACCOUNT, binding.originBinding))
            assertTrue(f.cancelled.isEmpty())
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(ticket) { fail("Failed retirement barrier reopened callback admission") })
            f.boundary.clear()
            value(f.registry.retire(ACCOUNT, binding.originBinding))
            assertEquals(listOf(ticket.id), f.cancelled.map { it.id })
            assertNull(value(f.registry.snapshot()).originBinding)
        }
    }

    @Test fun oldRetirementAndOldBindingCannotTouchNewLoginEvenWithSameOwnerAndLogicalTimer() = runTest {
        withFixture { f ->
            val old = f.bind()
            val oldTicket = f.install(old)
            value(f.registry.retire(ACCOUNT, old.originBinding))
            val freshLease = f.boundary.activate(ACCOUNT)
            val fresh = value(f.registry.createOrigin(freshLease, value(f.registry.snapshot()).revision))
            val ticket = f.install(fresh)
            assertNotEquals(old.originBinding, fresh.originBinding)
            value(f.registry.retire(ACCOUNT, old.originBinding))
            value(f.registry.retire(ACCOUNT.copy(actorKind = ActorKind.GUEST), fresh.originBinding))
            failure(FailureReason.STALE_SESSION, f.registry.cancel(old, ticket))
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(oldTicket) { fail("Old login callback ran") })
            value(f.registry.runLocalEffect(ticket) { PortResult.Value(Unit) })
            assertEquals(listOf(oldTicket.id), f.cancelled.map { it.id })
            assertEquals(fresh.originBinding, value(f.registry.snapshot()).originBinding)
        }
    }

    @Test fun finalInstallCheckpointFailureCleansExactIdentityAndNeverReplaysInstaller() = runTest {
        withFixture { f ->
            val binding = f.bind()
            f.control.beforeFailures[f.control.attempts + 2] = FailureReason.STORAGE_FAILURE
            var installed: NativeWorkTicket? = null
            failure(FailureReason.STORAGE_FAILURE, f.registry.install(binding, NativeWorkKind.TIMER, "timer") { installed = it; PortResult.Value(Unit) })
            val ticket = installed!!
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(ticket) { fail("Unacknowledged install ran") })
            f.reopen()
            value(f.registry.reconcilePending(value(f.registry.resume(f.lease))))
            assertEquals(listOf(ticket.id), f.cancelled.map { it.id })
            assertTrue(value(f.registry.snapshot()).entries.isEmpty())
        }
    }

    @Test fun unknownCommittedReservationAndInstalledCheckpointsReconcileWithoutDuplicateInstaller() = runTest {
        for (phase in listOf("reservation", "installed")) withFixture { f ->
            val binding = f.bind()
            f.control.afterFailures[f.control.attempts + if (phase == "reservation") 1 else 2] = FailureReason.OUTCOME_UNKNOWN
            var installs = 0
            val ticket = value(f.registry.install(binding, NativeWorkKind.TIMER, "timer") { installs++; PortResult.Value(Unit) })
            assertEquals(1, installs)
            assertEquals(ticket.id, value(f.registry.snapshot()).entries.single().ticket.id)
            assertEquals(NativeWorkPhase.INSTALLED, value(f.registry.snapshot()).entries.single().phase)
            assertEquals(3, f.control.attempts)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun unknownUncommittedReservationOrNonmatchingReadbackCannotCallInstaller() = runTest {
        for (replacement in listOf(false, true)) withFixture { f ->
            val binding = f.bind()
            val attempt = f.control.attempts + 1
            f.control.beforeFailures[attempt] = FailureReason.OUTCOME_UNKNOWN
            if (replacement) f.control.beforeCas = { _, _ ->
                f.control.beforeCas = { _, _ -> }
                f.control.replaceState(SessionWorkState.Origin(ACCOUNT, binding.originBinding, false, listOf(entry(80, NativeWorkPhase.CANCELLING))))
            }
            val result = f.registry.install(binding, NativeWorkKind.TIMER, "timer") { fail("Unknown unmatched reservation called native installer") }
            assertIs<PortResult.Failure>(result)
            assertTrue(f.cancelled.isEmpty())
            assertEquals(attempt, f.control.attempts)
            assertTrue(value(f.registry.snapshot()).entries.none { it.phase == NativeWorkPhase.INSTALLED })
        }
    }

    @Test fun malformedReservationReceiptIsNotAuthorityEvenIfUnderlyingWriteCommitted() = runTest {
        for (bad in listOf("revision", "payload")) withFixture { f ->
            val binding = f.bind()
            f.control.receipts[f.control.attempts + 1] = { actual ->
                if (bad == "revision") SessionControlRecord(1, actual.payload)
                else SessionControlRecord(actual.revision, SessionWorkCodec.encode(SessionWorkState.Idle))
            }
            failure(FailureReason.STORAGE_FAILURE, f.registry.install(binding, NativeWorkKind.TIMER, "timer") { fail("Malformed receipt admitted installer") })
            assertEquals(NativeWorkPhase.RESERVED, value(f.registry.snapshot()).entries.single().phase)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun malformedFinalReceiptCompensatesExactNativeWorkAndNeverAdmitsItsCallback() = runTest {
        for (bad in listOf("revision", "payload")) for (cleanupFails in listOf(false, true)) withFixture { f ->
            val binding = f.bind()
            f.control.receipts[f.control.attempts + 2] = { actual ->
                if (bad == "revision") SessionControlRecord(1, actual.payload)
                else SessionControlRecord(actual.revision, SessionWorkCodec.encode(SessionWorkState.Idle))
            }
            if (cleanupFails) f.cancel = { PortResult.Failure(FailureReason.UNAVAILABLE) }
            var installed: NativeWorkTicket? = null
            failure(if (cleanupFails) FailureReason.UNAVAILABLE else FailureReason.STORAGE_FAILURE,
                f.registry.install(binding, NativeWorkKind.TIMER, "timer") { installed = it; PortResult.Value(Unit) })
            val ticket = installed!!
            assertEquals(listOf(ticket.id), f.cancelled.map { it.id })
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(ticket) { fail("Malformed final receipt admitted effect") })
            f.reopen()
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(ticket) { fail("Unconfirmed native work became eligible after reopen") })
            if (cleanupFails) {
                assertEquals(NativeWorkPhase.CANCELLING, value(f.registry.snapshot()).entries.single().phase)
                f.cancel = { PortResult.Value(Unit) }
                value(f.registry.reconcilePending(value(f.registry.resume(f.lease))))
                assertEquals(listOf(ticket.id, ticket.id), f.cancelled.map { it.id })
            }
            assertTrue(value(f.registry.snapshot()).entries.isEmpty())
        }
    }

    @Test fun coroutineCancellationAfterFinalCommitStillCleansExactNativeWorkBeforePropagating() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val finalAttempt = f.control.attempts + 2
            f.control.afterCas = { attempt -> if (attempt == finalAttempt) throw CancellationException("lost final handoff") }
            var installed: NativeWorkTicket? = null
            assertFailsWith<CancellationException> {
                f.registry.install(binding, NativeWorkKind.TIMER, "timer") { installed = it; PortResult.Value(Unit) }
            }
            val ticket = installed!!
            assertEquals(listOf(ticket.id), f.cancelled.map { it.id })
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(ticket) { fail("Cancelled result admitted callback") })
            f.reopen()
            assertTrue(value(f.registry.snapshot()).entries.isEmpty())
        }
    }

    @Test fun callerCancellationDuringMalformedFinalReceiptCompensationCannotAbandonNativeCleanup() = runTest {
        withFixture { f ->
            val binding = f.bind()
            f.control.receipts[f.control.attempts + 2] = { actual -> SessionControlRecord(1, actual.payload) }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var installed: NativeWorkTicket? = null
            val install = async {
                f.registry.install(binding, NativeWorkKind.TIMER, "timer") {
                    installed = it
                    f.control.afterRead = { entered.complete(Unit); release.await() }
                    PortResult.Value(Unit)
                }
            }
            entered.await()
            install.cancel()
            runCurrent()
            assertFalse(install.isCompleted, "Compensation remains owned despite caller cancellation")
            release.complete(Unit)
            assertFailsWith<CancellationException> { install.await() }
            f.control.afterRead = { }
            val ticket = installed!!
            assertEquals(listOf(ticket.id), f.cancelled.map { it.id })
            failure(FailureReason.STALE_SESSION, f.registry.runLocalEffect(ticket) { fail("Interrupted compensation admitted effect") })
            f.reopen()
            assertTrue(value(f.registry.snapshot()).entries.isEmpty())
        }
    }

    @Test fun awaitedReadsCannotReturnStaleBindingsOrStartEffectsAfterSameOrDifferentAccountReplacement() = runTest {
        for (action in listOf("create", "resume", "install", "cancel", "callback")) {
            for (replacement in listOf(ACCOUNT, ACCOUNT.copy(actorId = "replacement-owner"))) withFixture { f ->
                val binding = if (action == "create") null else f.bind()
                val ticket = if (action in setOf("cancel", "callback")) f.install(binding!!) else null
                val writesBefore = f.control.attempts
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.control.afterRead = { entered.complete(Unit); release.await() }
                val operation = async {
                    when (action) {
                        "create" -> f.registry.createOrigin(f.lease, 1)
                        "resume" -> f.registry.resume(f.lease)
                        "install" -> f.registry.install(binding!!, NativeWorkKind.TIMER, "new-timer") { fail("Stale read admitted installer") }
                        "cancel" -> f.registry.cancel(binding!!, ticket!!)
                        else -> f.registry.runLocalEffect(ticket!!) { fail("Stale read admitted callback") }
                    }
                }
                entered.await()
                f.boundary.activate(replacement)
                release.complete(Unit)
                failure(FailureReason.STALE_SESSION, operation.await())
                f.control.afterRead = { }
                assertEquals(writesBefore, f.control.attempts)
                assertEquals(0, f.executionCalls)
                assertTrue(f.cancelled.isEmpty())
            }
        }
    }

    @Test fun delayedExecutionPolicyCannotTransferCapturedAuthorityToAnotherLease() = runTest {
        for (replacement in listOf<StorageScope?>(null, ACCOUNT, ACCOUNT.copy(actorId = "different-owner"))) withFixture { f ->
            val binding = f.bind()
            val ticket = f.install(binding)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.execute = { _, _, _, _ -> entered.complete(Unit); release.await(); PortResult.Value(true) }
            val callback = async { f.registry.runLocalEffect(ticket) { fail("Delayed old-lease policy admitted effect") } }
            entered.await()
            if (replacement == null) f.boundary.clear() else f.boundary.activate(replacement)
            release.complete(Unit)
            failure(FailureReason.STALE_SESSION, callback.await())
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun delayedAdmissionCannotTransferCreateResumeOrInstallToNewSameAccountLease() = runTest {
        for (action in listOf("create", "resume", "install")) withFixture { f ->
            val binding = if (action == "create") null else f.bind()
            val writesBefore = f.control.attempts
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.admit = { entered.complete(Unit); release.await(); PortResult.Value(true) }
            val operation = async {
                when (action) {
                    "create" -> f.registry.createOrigin(f.lease, 1)
                    "resume" -> f.registry.resume(f.lease)
                    else -> f.registry.install(binding!!, NativeWorkKind.TIMER, "timer") { fail("Stale admission installed native work") }
                }
            }
            entered.await()
            f.boundary.activate(ACCOUNT)
            release.complete(Unit)
            failure(FailureReason.STALE_SESSION, operation.await())
            assertEquals(writesBefore, f.control.attempts)
        }
    }

    @Test fun invalidLogicalIdsUuidSourceAndCapacityFailBeforeNativeInstaller() = runTest {
        withFixture { f ->
            val binding = f.bind()
            for (logical in listOf("", " ", "secret\nstep", "x".repeat(201), "😀".repeat(51))) {
                failure(FailureReason.INVALID_DATA, f.registry.install(binding, NativeWorkKind.TIMER, logical) { fail("Invalid logical id installed") })
            }
            f.nextId = { "not-a-uuid" }
            failure(FailureReason.INVALID_DATA, f.registry.install(binding, NativeWorkKind.TIMER, "valid") { fail("Invalid native id installed") })
            f.control.replaceState(SessionWorkState.Origin(ACCOUNT, binding.originBinding, false, (100..163).map { entry(it, NativeWorkPhase.INSTALLED) }))
            failure(FailureReason.CONFLICT, f.registry.install(binding, NativeWorkKind.TIMER, "capacity") { fail("Capacity limit bypassed") })
            assertEquals(1, f.control.attempts)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun missingCorruptAndNewerDurableStateFailClosedWithoutInitializationOrNativeCleanup() = runTest {
        for (raw in listOf<String?>(null, "{}", "{\"version\":2,\"state\":\"idle\"}", "{\"version\":1,\"state\":\"idle\",\"state\":\"active\"}")) {
            withFixture { f ->
                value(f.registry.close())
                f.control.record = raw?.let { SessionControlRecord(2, PrivateBytes(it.encodeToByteArray())) }
                failure(FailureReason.STORAGE_FAILURE, f.openResult())
                assertEquals(0, f.control.attempts)
                assertTrue(f.cancelled.isEmpty())
                f.control.record = SessionControlRecord(3, SessionWorkCodec.encode(SessionWorkState.Idle))
                f.reopen()
                assertEquals(3, value(f.registry.snapshot()).revision)
            }
        }
    }

    @Test fun loadedCorruptionAndReadFailureNeverPermitCallbacksOrRepairWrites() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val ticket = f.install(binding)
            val before = f.control.attempts
            f.control.readFailure = FailureReason.UNAVAILABLE
            failure(FailureReason.UNAVAILABLE, f.registry.runLocalEffect(ticket) { fail("Unreadable registry admitted callback") })
            f.control.readFailure = null
            f.control.record = SessionControlRecord(20, PrivateBytes("{\"version\":1,\"state\":\"unknown\"}".encodeToByteArray()))
            failure(FailureReason.STORAGE_FAILURE, f.registry.runLocalEffect(ticket) { fail("Corrupt registry admitted callback") })
            failure(FailureReason.STORAGE_FAILURE, f.registry.cancel(binding, ticket))
            failure(FailureReason.STORAGE_FAILURE, f.registry.retire(ACCOUNT, binding.originBinding))
            assertEquals(before, f.control.attempts)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun effectFailureRemainsLocalAndPrivateDiagnosticViewsDoNotExposeIdentity() = runTest {
        withFixture { f ->
            val binding = f.bind()
            val ticket = f.install(binding, logicalId = "private-cooking-timer")
            failure(FailureReason.UNAVAILABLE, f.registry.runLocalEffect(ticket) { PortResult.Failure(FailureReason.UNAVAILABLE) })
            val failedEffect = f.registry.runLocalEffect(ticket) { throw IllegalStateException("private-cooking-timer") }
            failure(FailureReason.STORAGE_FAILURE, failedEffect)
            val snapshot = value(f.registry.snapshot())
            for (view in listOf(binding, ticket, snapshot, snapshot.entries.single(), failedEffect)) {
                val text = view.toString()
                for (secret in listOf(ACCOUNT.actorId, binding.originBinding, ticket.id, "private-cooking-timer")) assertFalse(text.contains(secret))
            }
            assertEquals(NativeWorkPhase.INSTALLED, snapshot.entries.single().phase)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try { f.reopen(); block(f) } finally { if (f.hasRegistry) value(f.registry.close()) }
    }

    private class Fixture(private val dispatcher: CoroutineDispatcher) {
        val control = FakeWorkControl()
        val boundary = SessionBoundary()
        val lease = boundary.activate(ACCOUNT)
        lateinit var registry: SessionWorkRegistry
        var hasRegistry = false
        val cancelled = mutableListOf<NativeWorkTicket>()
        val admitted = mutableListOf<StorageScope>()
        var executionCalls = 0
        var next = 0
        var nextId: () -> String = { uuid(++next) }
        var cancel: suspend (NativeWorkTicket) -> PortResult<Unit> = { PortResult.Value(Unit) }
        var admit: suspend (StorageScope) -> PortResult<Boolean> = { PortResult.Value(true) }
        var execute: suspend (StorageScope, String, String, NativeWorkTicket) -> PortResult<Boolean> = { _, _, _, _ -> PortResult.Value(true) }

        suspend fun openResult() = SessionWorkRegistry.open(control, boundary, dispatcher,
            NativeWorkCancellationPort { cancelled += it; cancel(it) }, NativeWorkIdSource { nextId() },
            NativeWorkAdmissionPolicy { admitted += it; admit(it) },
            NativeWorkExecutionPolicy { scope, origin, logical, ticket -> executionCalls++; execute(scope, origin, logical, ticket) })
        suspend fun reopen() {
            if (hasRegistry) value(registry.close())
            registry = value(openResult())
            hasRegistry = true
        }
        suspend fun bind() = value(registry.createOrigin(lease, value(registry.snapshot()).revision))
        suspend fun install(binding: SessionWorkBinding, kind: NativeWorkKind = NativeWorkKind.TIMER, logicalId: String = "timer") =
            value(registry.install(binding, kind, logicalId) { PortResult.Value(Unit) })
    }

    /** Detached opaque record, strict CAS, and separately controlled before/after-commit failures. */
    private class FakeWorkControl : SessionControlStore {
        var record: SessionControlRecord? = SessionControlRecord(1, SessionWorkCodec.encode(SessionWorkState.Idle))
        var attempts = 0
        var readFailure: FailureReason? = null
        val beforeFailures = mutableMapOf<Int, FailureReason>()
        val afterFailures = mutableMapOf<Int, FailureReason>()
        val receipts = mutableMapOf<Int, (SessionControlRecord) -> SessionControlRecord>()
        var beforeCas: suspend (Long?, PrivateBytes) -> Unit = { _, _ -> }
        var afterCas: suspend (Int) -> Unit = { }
        var afterRead: suspend () -> Unit = { }

        override suspend fun read(): PortResult<SessionControlRecord?> {
            val result = readFailure?.let { PortResult.Failure(it) } ?: PortResult.Value(record?.detached())
            afterRead()
            return result
        }

        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            val attempt = ++attempts
            val bytes = payload.copyForCodec()
            if (bytes.size !in 1..32_768 || (expectedRevision != null && expectedRevision <= 0)) {
                bytes.fill(0)
                return PortResult.Failure(FailureReason.INVALID_DATA)
            }
            val detached = PrivateBytes(bytes)
            bytes.fill(0)
            beforeCas(expectedRevision, detached)
            beforeFailures.remove(attempt)?.let { return PortResult.Failure(it) }
            val current = record ?: return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            if (current.revision != expectedRevision) return PortResult.Failure(FailureReason.CONFLICT)
            if (current.revision == Long.MAX_VALUE) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            val committed = SessionControlRecord(current.revision + 1, detached)
            record = committed.detached()
            afterCas(attempt)
            afterFailures.remove(attempt)?.let { return PortResult.Failure(it) }
            return PortResult.Value(receipts.remove(attempt)?.invoke(committed.detached()) ?: committed.detached())
        }

        fun state(): SessionWorkState = SessionWorkCodec.decode(checkNotNull(record).payload)
        fun replaceState(state: SessionWorkState) { record = SessionControlRecord((record?.revision ?: 0) + 1, SessionWorkCodec.encode(state)) }
        private fun SessionControlRecord.detached() = SessionControlRecord(revision, PrivateBytes(payload.copyForCodec()))
    }

    companion object {
        private val ACCOUNT = StorageScope("native-work-fixture", ActorKind.ACCOUNT, "private-work-owner")
        private fun uuid(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
        private fun entry(number: Int, phase: NativeWorkPhase) = SessionWorkEntry(uuid(number), NativeWorkKind.TIMER, "logical-$number", phase)
        private fun <T> value(result: PortResult<T>): T = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> fail("Expected work result, got ${result.reason}")
        }
        private fun failure(reason: FailureReason, result: PortResult<*>) { assertEquals(reason, assertIs<PortResult.Failure>(result).reason) }
    }
}
