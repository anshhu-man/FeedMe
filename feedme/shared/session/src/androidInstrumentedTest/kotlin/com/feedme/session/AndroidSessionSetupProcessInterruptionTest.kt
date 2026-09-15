@file:OptIn(com.feedme.storage.SessionControlRecoveryCompositionApi::class)

package com.feedme.session

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import com.feedme.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seven interrupt/recover pairs, each selected in its own instrumentation process. Interrupt
 * methods deliberately NEVER pass: the host witnesses checkpoint.json, verifies this test APK's
 * PID, force-stops it, proves PID disappearance, and starts the matching recovery method. A
 * normal test-suite invocation is invalid. Acknowledged-boundary process death is not power loss,
 * an injected SQLite I/O error, a hot-journal recovery test, or provider authentication.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSessionSetupProcessInterruptionTest {
    @Test fun interruptDuringExistingOpen() = interrupt("opening")
    @Test fun recoverAfterInterruptedExistingOpen() = recover("opening")
    @Test fun interruptReadyWithoutConfirmation() = interrupt("ready")
    @Test fun recoverUnconfirmedReadyWithoutInferringConsent() = recover("ready")
    @Test fun interruptAfterWorkAbort() = interrupt("work-aborted")
    @Test fun recoverAfterAcknowledgedWorkAbort() = recover("work-aborted")
    @Test fun interruptAfterDataAbort() = interrupt("data-aborted")
    @Test fun recoverAfterAcknowledgedDataAbort() = recover("data-aborted")
    @Test fun interruptAfterWorkClose() = interrupt("work-closed")
    @Test fun recoverAfterAcknowledgedWorkClose() = recover("work-closed")
    @Test fun interruptAfterDataClose() = interrupt("data-closed")
    @Test fun recoverAfterAcknowledgedDataClose() = recover("data-closed")
    @Test fun interruptAfterCompleteBeforeControlClose() = interrupt("complete")
    @Test fun rejectCompleteAsFreshAbortAuthority() = recover("complete")

    private fun interrupt(scenario: String): Unit = runBlocking {
        withContext<Unit>(Dispatchers.Main.immediate) {
            // Fixture files are test orchestration only. Reserve before the first native factory.
            val fixture = Fixture(AndroidSessionSetupProcessSandbox.create(scenario))
            retained = fixture // Retain even if the host fails to kill us before the timeout.
            fixture.seedSealedSetup()
            val owner = if (scenario == "ready" || scenario == "complete") fixture.publicOwner()
                else fixture.gatedOwner()
            ok(owner.open())
            if (scenario == "ready") {
                assertEquals(SessionSetupRecoveryPhase.READY, owner.phase())
                fixture.assertReport(ok(owner.inspect()))
                fixture.hold("unconfirmed", 0)
            }
            ok(owner.confirmAbort(ok(owner.prepareAbort())))
            assertEquals("Only the final scenario can reach acknowledged Complete", "complete", scenario)
            assertEquals(SessionSetupRecoveryPhase.COMPLETE, owner.phase())
            assertNull(fixture.boundary.current())
            fixture.hold("complete", 3)
            // No finally/close: the experiment specifically requires process death with ownership
            // still retained. Timeouts fail and preserve the exact fixture, never synthesize a pass.
        }
    }

    private fun recover(scenario: String) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val fixture = Fixture(AndroidSessionSetupProcessSandbox.reopen(scenario))
            retained = fixture
            var completed = false
            try {
                fixture.recoverOriginalIntent()
                fixture.closeAll()
                fixture.sandbox.removeAfterSuccessfulRecovery()
                completed = true
                InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
                    putString("startup_recovery_scenario", scenario)
                    putString("startup_recovery_previous_pid", fixture.sandbox.previousPid.toString())
                    putString("startup_recovery_pid", Process.myPid().toString())
                    putString("startup_recovery_control", "complete")
                    putString("startup_recovery_closed", "3")
                    putString("startup_recovery_cleanup", "exact-owned-fixture-removed")
                })
            } finally {
                if (completed) retained = null
                else withContext(NonCancellable) {
                    // Truthful close only. Failed assertions never authorize test cleanup.
                    fixture.closeAll()
                }
            }
        }
    }

    private class Fixture(val sandbox: AndroidSessionSetupProcessSandbox) {
        val boundary = SessionBoundary()
        private var root = ok(SessionApplicationComposition.create(boundary, Dispatchers.Main.immediate, CONFIG))
        private var reservation = ok(root.reserve())
        private var owner: SessionSetupRecoveryOwner? = null
        private var ordinaryData: EncryptedStateDatabase? = null
        private var ordinaryControl: EncryptedSessionControlStore? = null
        private var ordinaryWork: EncryptedSessionWorkStore? = null
        private var ordinaryCredentials: AndroidCredentialStore? = null
        private var nativeControl: ExistingSessionControlRecoveryStore? = null
        private var nativeCredentials: CredentialCreateRecoveryOwner? = null
        private var nativeData: StateActivationRecoveryOwner? = null
        private var nativeWork: SessionWorkOriginRecoveryOwner? = null
        private var controlProbe: ExistingSessionControlRecoveryStore? = null
        private var originalPlan: PrivateBytes? = null
        private val aborted = mutableListOf<String>()
        private val closed = mutableListOf<String>()

        suspend fun seedSealedSetup() {
            ordinaryData = ok(AndroidStateDatabase.open(sandbox.context))
            ordinaryControl = ok(AndroidSessionControlStore.open(sandbox.context))
            ordinaryWork = ok(AndroidSessionWorkStore.open(sandbox.context))
            ordinaryCredentials = ok(AndroidCredentialStore.open(sandbox.context))
            val identity = StoredCredentials.Account(SCOPE, SecretText("synthetic-process-access"),
                SecretText("synthetic-process-refresh"), Long.MAX_VALUE, SecretText(DEVICE))
            val credentialPlan = ok(ordinaryCredentials!!.planCreate(ok(ordinaryCredentials!!.state()).revision, identity))
            val dataPlan = ok(ordinaryData!!.planActivation(SCOPE))
            val planner = ok(SessionWorkRegistry.open(ordinaryWork!!, boundary, Dispatchers.Main.immediate,
                NativeWorkCancellationPort { forbidden() }, NativeWorkIdSource { ORIGIN },
                NativeWorkAdmissionPolicy { forbidden() }, NativeWorkExecutionPolicy { _, _, _, _ -> forbidden() }))
            try {
                val workPlan = ok(planner.planOrigin(SCOPE, ok(ordinaryWork!!.read())!!.revision))
                val plan = SessionSetupPlan.create(SessionSetupPlanRecord(sandbox.runId, SCOPE, CONFIG,
                    credentialPlan, dataPlan, workPlan))
                originalPlan = plan.copyForStorage()
                ok(ordinaryControl!!.compareAndSet(ok(ordinaryControl!!.read())!!.revision,
                    RetirementCodec.encode(RetirementState.PendingSetup(plan, false))))
                ok(ordinaryCredentials!!.commitPlannedCreate(credentialPlan, identity))
                ok(ordinaryData!!.commitPlannedActivation(SCOPE, dataPlan))
                ok(planner.selectOrigin(workPlan))
                val target = ok(ordinaryData!!.inspectPlannedBinding(SCOPE, dataPlan)).target!!
                val incarnation = CredentialCreatePlanCodec.decode(credentialPlan.copyForStorage()).incarnation
                val binding = SessionActivationCodec.encode(SessionActivationRecord(SCOPE, incarnation, ORIGIN,
                    target, CONFIG, sandbox.runId))
                ok(ordinaryData!!.bindPlannedActivation(SCOPE, dataPlan, 2, binding))
                ok(planner.sealOrigin(workPlan))
                assertNull(boundary.current())
            } finally { ok(planner.close()) }
            closeOrdinary()
        }

        fun publicOwner(): SessionSetupRecoveryOwner =
            AndroidSessionSetupRecovery.createOwner(sandbox.context, reservation).also { owner = it }

        /** Only pauses real native calls; no fabricated plans, observations, close or abort result. */
        fun gatedOwner(): SessionSetupRecoveryOwner = RetainedSessionSetupRecoveryOwner(reservation,
            object : SessionSetupRecoveryFactories {
                override fun control(): ExistingSessionControlRecoveryStore =
                    AndroidSessionControlStore.createRecoveryStore(sandbox.context).also { nativeControl = it }

                override fun credentials(scope: StorageScope, plan: CredentialCreatePlan): CredentialCreateRecoveryOwner {
                    val native = AndroidCredentialStore.createRecoveryOwner(sandbox.context, scope, plan)
                    nativeCredentials = native
                    return object : CredentialCreateRecoveryOwner by native {
                        override suspend fun abort(): PortResult<Unit> {
                            val result = native.abort()
                            if (result is PortResult.Value) aborted += "credential"
                            return result
                        }
                        override suspend fun close(): PortResult<Unit> {
                            if (sandbox.scenario == "data-closed") beforeCloseGate(2)
                            val result = native.close()
                            if (result is PortResult.Value) closed += "credential"
                            return result
                        }
                    }
                }

                override fun data(scope: StorageScope, plan: StateActivationPlan): StateActivationRecoveryOwner {
                    val native = AndroidStateDatabase.createActivationRecoveryOwner(sandbox.context, scope, plan)
                    nativeData = native
                    return object : StateActivationRecoveryOwner by native {
                        override suspend fun abort(expectedBinding: PrivateBytes?): PortResult<Unit> {
                            assertNotNull("Real schema2 binding must be supplied before its exact abort", expectedBinding)
                            val result = native.abort(expectedBinding)
                            if (result is PortResult.Value) {
                                aborted += "data"
                                if (sandbox.scenario == "data-aborted") {
                                    assertEquals(listOf("work", "data"), aborted)
                                    assertNativePending(true)
                                    assertEquals(StateActivationStatus.ABORTED, ok(native.inspect()).status)
                                    assertEquals(SessionWorkOriginPlanStatus.ABORTED, ok(nativeWork!!.inspect()).status)
                                    assertEquals(CredentialCreateRecoveryStatus.SELECTED, ok(nativeCredentials!!.inspect()).status)
                                    hold("requested", 0)
                                }
                            }
                            return result
                        }
                        override suspend fun close(): PortResult<Unit> {
                            if (sandbox.scenario == "work-closed") beforeCloseGate(1)
                            val result = native.close()
                            if (result is PortResult.Value) closed += "data"
                            return result
                        }
                    }
                }

                override fun work(scope: StorageScope, plan: SessionWorkOriginPlan): SessionWorkOriginRecoveryOwner {
                    val native = AndroidSessionWorkRecovery.createOwner(sandbox.context, scope, plan,
                        boundary, Dispatchers.Main.immediate)
                    nativeWork = native
                    return object : SessionWorkOriginRecoveryOwner by native {
                        override suspend fun open(): PortResult<Unit> {
                            if (sandbox.scenario == "opening") {
                                assertNativePending(false)
                                assertEquals(CredentialCreateRecoveryStatus.SELECTED, ok(nativeCredentials!!.inspect()).status)
                                assertEquals(StateActivationStatus.SELECTED_NONEMPTY, ok(nativeData!!.inspect()).status)
                                assertNotNull(ok(nativeData!!.binding()).record)
                                assertTrue(aborted.isEmpty()); assertTrue(closed.isEmpty())
                                hold("unconfirmed", 0)
                            }
                            return native.open()
                        }
                        override suspend fun abort(): PortResult<Unit> {
                            val result = native.abort()
                            if (result is PortResult.Value) {
                                aborted += "work"
                                if (sandbox.scenario == "work-aborted") {
                                    assertEquals(listOf("work"), aborted)
                                    assertNativePending(true)
                                    assertEquals(SessionWorkOriginPlanStatus.ABORTED, ok(native.inspect()).status)
                                    assertEquals(StateActivationStatus.SELECTED_NONEMPTY, ok(nativeData!!.inspect()).status)
                                    assertEquals(CredentialCreateRecoveryStatus.SELECTED, ok(nativeCredentials!!.inspect()).status)
                                    hold("requested", 0)
                                }
                            }
                            return result
                        }
                        override suspend fun close(): PortResult<Unit> {
                            val result = native.close()
                            if (result is PortResult.Value) closed += "work"
                            return result
                        }
                    }
                }
            }).also { owner = it }

        private suspend fun beforeCloseGate(count: Int) {
            assertEquals(listOf("work", "data", "credential"), aborted)
            assertEquals(listOf("work", "data").take(count), closed)
            assertNativePending(true)
            // Do not inspect a closed subordinate. The owner already captured all-ABORTED and
            // acknowledged exactly this prefix of closes before invoking the next real close.
            hold("requested", count)
        }

        private suspend fun assertNativePending(requested: Boolean) {
            val record = ok(nativeControl!!.read())!!
            val pending = RetirementCodec.decode(record.payload) as RetirementState.PendingSetup
            assertEquals(requested, pending.abortRequested)
            assertBytes(originalPlan!!, pending.plan.copyForStorage())
            assertEquals(sandbox.runId, SessionSetupPlanCodec.decode(pending.plan.copyForStorage()).operationId)
            assertNull(boundary.current())
        }

        suspend fun hold(control: String, closedCount: Int): Nothing {
            assertNull(boundary.current())
            rejected(FailureReason.CONFLICT, reservation.release())
            sandbox.writeCheckpoint(control, closedCount)
            try {
                withTimeout(AndroidSessionSetupProcessSandbox.MAX_HOLD_MILLIS) { awaitCancellation() }
            } catch (_: TimeoutCancellationException) {
                throw AssertionError("Host did not interrupt the witnessed test process; fixture preserved")
            }
        }

        suspend fun recoverOriginalIntent() {
            assertNotEquals(sandbox.previousPid, Process.myPid())
            val initial = readControl()
            val state = RetirementCodec.decode(initial.payload)
            if (sandbox.scenario == "complete") {
                assertEquals(sandbox.runId, (state as RetirementState.Complete).operationId)
            } else {
                val pending = state as RetirementState.PendingSetup
                assertEquals(AndroidSessionSetupProcessSandbox.expectedControl(sandbox.scenario) == "requested", pending.abortRequested)
                originalPlan = pending.plan.copyForStorage()
                val plan = SessionSetupPlanCodec.decode(originalPlan!!)
                assertEquals(sandbox.runId, plan.operationId); assertEquals(CONFIG, plan.configurationBinding)
                assertEquals(SCOPE, plan.scope)
                assertEquals(ORIGIN, SessionWorkOriginPlanCodec.decode(plan.workOriginPlan.copyForStorage()).origin)
            }

            // Wrong trusted configuration never becomes cleanup authority, even after a real kill.
            val files = sandbox.snapshot(); val aliases = sandbox.aliases()
            replaceRoot(OTHER_CONFIG)
            val wrong = publicOwner()
            rejected(FailureReason.CONFLICT, wrong.open())
            assertEquals(SessionSetupRecoveryPhase.CLOSE_ONLY, wrong.phase())
            assertTrue(wrong.retryAbort() is PortResult.Failure)
            sandbox.assertUnchanged(files, aliases)
            closeOwner(); replaceRoot(CONFIG)
            assertSameRecord(initial, readControl())

            if (sandbox.scenario == "complete") {
                val fresh = publicOwner()
                rejected(FailureReason.CONFLICT, fresh.open())
                assertTrue(fresh.prepareAbort() is PortResult.Failure)
                assertTrue(fresh.retryAbort() is PortResult.Failure)
                sandbox.assertUnchanged(files, aliases)
                closeOwner(); reservation = ok(root.reserve())
                assertSameRecord(initial, readControl())
                assertNull(boundary.current())
                return
            }

            // Actual lifecycle invalidation makes the root close-only; inactive-boundary ABA does
            // not recreate the captured proposal. No callback, secret read or provider is involved.
            var current = publicOwner(); ok(current.open()); assertReport(ok(current.inspect()))
            val unconfirmed = !(state as RetirementState.PendingSetup).abortRequested
            val prepared = if (unconfirmed) ok(current.prepareAbort()) else null
            val lifecycleFiles = sandbox.snapshot(); val lifecycleAliases = sandbox.aliases()
            ok(root.invalidate())
            if (prepared != null) rejected(FailureReason.STALE_SESSION, current.confirmAbort(prepared))
            else rejected(FailureReason.STALE_SESSION, current.retryAbort())
            assertNull(boundary.current()); sandbox.assertUnchanged(lifecycleFiles, lifecycleAliases)
            val old = reservation
            closeOwner(); rejected(FailureReason.STALE_SESSION, root.reserve())
            replaceRoot(CONFIG)
            val staleOwner = AndroidSessionSetupRecovery.createOwner(sandbox.context, old)
            rejected(FailureReason.STALE_SESSION, staleOwner.open()); ok(staleOwner.close())
            assertSameRecord(initial, readControl())

            current = publicOwner(); ok(current.open()); assertReport(ok(current.inspect()))
            sandbox.assertUnchanged(lifecycleFiles, lifecycleAliases)
            if (unconfirmed) {
                rejected(FailureReason.CONFLICT, current.retryAbort())
                sandbox.assertUnchanged(lifecycleFiles, lifecycleAliases)
                // Only this fresh explicit confirmation requests erasure; inspect/prepare did not.
                val proposal = ok(current.prepareAbort())
                sandbox.assertUnchanged(lifecycleFiles, lifecycleAliases)
                ok(current.confirmAbort(proposal))
            } else {
                rejected(FailureReason.CONFLICT, current.prepareAbort())
                sandbox.assertUnchanged(lifecycleFiles, lifecycleAliases)
                // Replays only the exact original durable abortRequested, not a new operation ID.
                ok(current.retryAbort())
            }
            assertEquals(SessionSetupRecoveryPhase.COMPLETE, current.phase())
            assertNull(boundary.current())
            closeOwner(); reservation = ok(root.reserve())
            val completed = readControl()
            assertTrue(completed.revision > initial.revision)
            assertEquals(sandbox.runId, (RetirementCodec.decode(completed.payload) as RetirementState.Complete).operationId)
            assertNoTargetKeys()
        }

        fun assertReport(report: InterruptedSetupReport) {
            val requested = AndroidSessionSetupProcessSandbox.expectedControl(sandbox.scenario) == "requested"
            assertEquals(if (requested) InterruptedSetupFinding.ABORT_REQUESTED else InterruptedSetupFinding.UNCONFIRMED_SETUP, report.finding)
            val allAborted = sandbox.scenario in setOf("work-closed", "data-closed")
            assertEquals(if (allAborted) CredentialCreateRecoveryStatus.ABORTED else CredentialCreateRecoveryStatus.SELECTED,
                report.credentialStage)
            assertEquals(if (allAborted || sandbox.scenario == "data-aborted") InterruptedSetupDataStage.ABORTED
                else InterruptedSetupDataStage.BOUND, report.dataStage)
            assertEquals(if (requested) SessionWorkOriginPlanStatus.ABORTED else SessionWorkOriginPlanStatus.SEALED,
                report.workStage)
            assertNull(report.failureReason)
            assertNull(boundary.current())
        }

        private fun assertNoTargetKeys() {
            assertTrue(sandbox.aliases().none { it.startsWith("com.feedme.storage.private.v1.owner.") })
            assertTrue(sandbox.aliases().none { it.startsWith("com.feedme.session.credentials.v1.credential.") })
        }

        private suspend fun readControl(): SessionControlRecord {
            val control = AndroidSessionControlStore.createRecoveryStore(sandbox.context)
            controlProbe = control // Retain before open, including any real close failure.
            try { ok(control.open()); return ok(control.read())!! }
            finally { ok(control.close()); controlProbe = null }
        }

        private suspend fun closeOwner() { owner?.let { ok(it.close()); owner = null } }
        private fun replaceRoot(configuration: String) {
            check(owner == null)
            ok(reservation.release()); ok(root.close())
            root = ok(SessionApplicationComposition.create(boundary, Dispatchers.Main.immediate, configuration))
            reservation = ok(root.reserve())
        }
        private suspend fun closeOrdinary() {
            ordinaryCredentials?.let { ok(it.close()); ordinaryCredentials = null }
            ordinaryWork?.let { ok(it.close()); ordinaryWork = null }
            ordinaryData?.let { ok(it.close()); ordinaryData = null }
            ordinaryControl?.let { ok(it.close()); ordinaryControl = null }
        }
        suspend fun closeAll() {
            closeOwner(); closeOrdinary()
            controlProbe?.let { ok(it.close()); controlProbe = null }
            ok(reservation.release()); ok(root.close())
        }
        private fun forbidden(): Nothing = throw AssertionError("Startup fixture attempted provider, lease or native-work effect")
    }

    companion object {
        private var retained: Fixture? = null
        private val SCOPE = StorageScope("native-process-startup", ActorKind.ACCOUNT, "synthetic-owner")
        private val CONFIG = "d7".repeat(32)
        private val OTHER_CONFIG = "e8".repeat(32)
        private const val ORIGIN = "00000000-0000-4000-8000-000000001782"
        private const val DEVICE = "00000000-0000-4000-8000-000000001783"
        private fun <T> ok(result: PortResult<T>): T = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> throw AssertionError("Process-separated startup failed: ${result.reason}")
        }
        private fun rejected(reason: FailureReason, result: PortResult<*>) {
            assertTrue(result is PortResult.Failure)
            assertEquals(reason, (result as PortResult.Failure).reason)
        }
        private fun assertBytes(expected: PrivateBytes, actual: PrivateBytes) {
            val a = expected.copyForCodec(); val b = actual.copyForCodec()
            try { assertArrayEquals(a, b) } finally { a.fill(0); b.fill(0) }
        }
        private fun assertSameRecord(expected: SessionControlRecord, actual: SessionControlRecord) {
            assertEquals(expected.revision, actual.revision); assertBytes(expected.payload, actual.payload)
        }
    }
}
