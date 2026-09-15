@file:OptIn(com.feedme.storage.SessionControlRecoveryCompositionApi::class)

package com.feedme.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import com.feedme.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real native owners. Injected wrappers model app acknowledgement loss, not physical I/O faults. */
@RunWith(AndroidJUnit4::class)
class AndroidSessionSetupRecoveryOwnerTest {
    @Test fun constructingAndInspectingRetainedPublicOwnerNeverMutatesNativeMetadataOrCreatesAuthority() = integration {
        prepare(Stage.SEALED); closeOrdinary(); val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
        val owner = publicOwner(); sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
        rejected(FailureReason.CONFLICT, token.release()); ok(owner.open()); ok(owner.inspect()); ok(owner.prepareAbort())
        sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases()); assertNull(boundary.current())
        rejected(FailureReason.CONFLICT, owner.retryAbort()); ok(owner.close()); recovery = null
    }

    @Test fun allReachableOriginalSetupStagesUseRealExistingOwnersAndCloseBeforeComplete() {
        for (stage in Stage.entries) integration {
            prepare(stage); closeOrdinary(); val owner = countedOwner(); ok(owner.open())
            ok(owner.confirmAbort(ok(owner.prepareAbort())))
            assertEquals(listOf("work", "data", "credential"), effects)
            assertEquals(listOf("work", "data", "credential"), closed)
            assertEquals(SessionSetupRecoveryPhase.COMPLETE, owner.phase()); assertTrue(completedAfterCloses)
            rejected(FailureReason.CONFLICT, token.release()); ok(owner.close()); recovery = null
            readCompleted(); assertNull(boundary.current())
        }
    }

    @Test fun oneOrTwoClosedNativeOwnersCanRestartOriginalConfirmedPlanWithoutReopeningClosedHandles() {
        for (part in listOf("data", "credential")) integration {
            prepare(Stage.SEALED); closeOrdinary(); failClose = part
            val first = countedOwner(); ok(first.open())
            rejected(FailureReason.STORAGE_FAILURE, first.confirmAbort(ok(first.prepareAbort())))
            assertEquals(if (part == "data") 1 else 2, closed.size)
            failClose = null; ok(first.close()); recovery = null
            token = ok(root.reserve()); val second = publicOwner(); ok(second.open()); ok(second.inspect())
            rejected(FailureReason.CONFLICT, second.prepareAbort()); ok(second.retryAbort())
            ok(second.close()); recovery = null; readCompleted()
        }
    }

    @Test fun eachNativeCloseFailureRetainsExactOwnershipAndCheckpointWithoutFurtherObservations() {
        for (part in listOf("work", "data", "credential")) integration {
            prepare(Stage.SEALED); closeOrdinary(); failClose = part
            val owner = countedOwner(); ok(owner.open())
            rejected(FailureReason.STORAGE_FAILURE, owner.confirmAbort(ok(owner.prepareAbort())))
            val before = observations; val oldEffects = effects.toList()
            rejected(FailureReason.CONFLICT, root.close()); rejected(FailureReason.CONFLICT, token.release())
            failClose = null; ok(owner.retryAbort())
            assertEquals(before, observations); assertEquals(oldEffects, effects); assertTrue(completedAfterCloses)
            ok(owner.close()); recovery = null; readCompleted()
        }
    }

    @Test fun lostFinalCompleteReceiptNeedsSameOwnedCheckpointAndFreshControlAcknowledgement() = integration {
        prepare(Stage.SEALED); closeOrdinary(); completeLosses = 2
        val owner = countedOwner(); ok(owner.open())
        rejected(FailureReason.OUTCOME_UNKNOWN, owner.confirmAbort(ok(owner.prepareAbort())))
        val before = lastCompleteRevision; val count = observations; val oldEffects = effects.toList()
        rejected(FailureReason.OUTCOME_UNKNOWN, owner.retryAbort()); assertEquals(before + 1, lastCompleteRevision)
        ok(owner.retryAbort()); assertEquals(before + 2, lastCompleteRevision)
        assertEquals(count, observations); assertEquals(oldEffects, effects)
        ok(owner.close()); recovery = null; readCompleted()
    }

    @Test fun freshOwnerNeverTreatsPersistedCompleteAsUserConfirmationOrRecoveryCapability() = integration {
        prepare(Stage.SEALED); closeOrdinary(); completeLosses = 1
        val first = countedOwner(); ok(first.open())
        rejected(FailureReason.OUTCOME_UNKNOWN, first.confirmAbort(ok(first.prepareAbort())))
        ok(first.close()); recovery = null; token = ok(root.reserve())
        val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases(); val second = publicOwner()
        rejected(FailureReason.CONFLICT, second.open()); assertTrue(second.retryAbort() is PortResult.Failure)
        sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases()); ok(second.close()); recovery = null
    }

    @Test fun authoritativeSelectedCredentialMetadataSupportsExactMissingKeyOrBlobCleanup() {
        for (damage in listOf("key", "blob")) integration {
            prepare(Stage.SEALED); closeOrdinary()
            sandbox.damageSelectedCredentials(if (damage == "key") CredentialDiagnosticDamage.MISSING_KEY else CredentialDiagnosticDamage.MISSING_BLOB)
            val owner = publicOwner(); ok(owner.open()); ok(owner.inspect()); ok(owner.confirmAbort(ok(owner.prepareAbort())))
            ok(owner.close()); recovery = null; readCompleted(); assertTrue(sandbox.credentialAliases().none { it.contains(".credential.") })
        }
    }

    @Test fun failedDataAcquisitionRetainsPriorNativeOwnersAndNeverInitializesMissingDatabase() = integration {
        prepare(Stage.SEALED); closeOrdinary()
        val database = File(sandbox.context.noBackupFilesDir, "feedme-state/state.sqlite")
        assertTrue(database.isFile); assertTrue(database.delete()) // Exact fixture-owned corruption only.
        val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases(); val owner = countedOwner()
        rejected(FailureReason.STORAGE_FAILURE, owner.open()); assertEquals(SessionSetupRecoveryPhase.CLOSE_ONLY, owner.phase())
        assertTrue(effects.isEmpty()); assertFalse(database.exists()); sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
        rejected(FailureReason.CONFLICT, token.release()); ok(owner.close()); recovery = null
        assertEquals(setOf("control", "work", "data", "credential"), closed.toSet())
    }

    @Test fun realProcessRetirementLatchBlocksBeforeAnyNativeControlFactoryOrOpen() = integration {
        val runtime = ok(runtimeOpen(reserved = true, verifiedFixture = true))
        try {
            ok(runtime.recover()); val access = ok(runtime.create())
            // Real captured retirement authority installs the process fence before its first
            // durable write. Only that write's failure is injected; no fake pending provider.
            val unavailableControl = object : SessionControlStore {
                override suspend fun read() = control!!.read()
                override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> =
                    PortResult.Failure(FailureReason.STORAGE_FAILURE)
            }
            val coordinator = LocalRetirementCoordinator(unavailableControl, data!!, boundary, Dispatchers.Main.immediate,
                credentials!!, SessionWorkRetirementPort { _, _ -> forbidden() })
            rejected(FailureReason.STORAGE_FAILURE, coordinator.retire(access.retirement, OPERATION))
            assertTrue(processRetirementPending(boundary)); assertNull(boundary.current())
        } finally { ok(runtime.close()) }
        closeOrdinary(); val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
        val owner = countedOwner(); rejected(FailureReason.CONFLICT, owner.open())
        assertEquals(0, controlFactories); assertTrue(effects.isEmpty()); sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
        ok(owner.close()); recovery = null
    }

    @Test fun applicationInvalidationFencesReadyOwnerWhileExactCloseAndRootReplacementRemainSafe() = integration {
        prepare(Stage.SEALED); closeOrdinary(); val owner = publicOwner(); ok(owner.open())
        val proposal = ok(owner.prepareAbort()); val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
        ok(root.invalidate()); rejected(FailureReason.STALE_SESSION, owner.confirmAbort(proposal))
        rejected(FailureReason.CONFLICT, SessionApplicationComposition.create(boundary, Dispatchers.Main.immediate, OTHER_CONFIG))
        sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases()); ok(owner.close()); recovery = null
        val old = token; rejected(FailureReason.STALE_SESSION, root.reserve()); ok(root.close())
        root = ok(SessionApplicationComposition.create(boundary, Dispatchers.Main.immediate, OTHER_CONFIG))
        token = ok(root.reserve()); rejected(FailureReason.STALE_SESSION, SessionCompositions.claim(old, Any()))
    }

    @Test fun reservedRuntimeActuallyRegistersAndCannotReleaseBorrowedNativeResourcesEarly() = integration {
        rejected(FailureReason.CONFLICT, runtimeOpen(reserved = false))
        val runtime = ok(runtimeOpen(reserved = true, verifiedFixture = true))
        try {
            rejected(FailureReason.CONFLICT, token.release()); rejected(FailureReason.CONFLICT, root.reserve())
            rejected(FailureReason.CONFLICT, runtimeOpen(reserved = true))
            ok(runtime.recover()); val access = ok(runtime.create())
            var redacted = false
            boundary.onInvalidated(access.lease) { redacted = true }
            ok(root.invalidate())
            assertTrue(redacted); assertNull(boundary.current()); assertNull(runtime.currentAccess())
        } finally { ok(runtime.close()) }
        // Native resources are still borrowed/open: runtime.close must not free reservation.
        rejected(FailureReason.STALE_SESSION, root.reserve()); ok(control!!.read()); closeOrdinary()
    }

    @Test fun legacyRuntimeBlocksARealApplicationRootAndFailedReservedOpenDoesNotLeakRegistration() = integration {
        prepare(Stage.PREPARED); ok(token.release()); ok(root.close())
        val legacy = ok(runtimeOpen(reserved = false))
        rejected(FailureReason.CONFLICT, SessionApplicationComposition.create(boundary, Dispatchers.Main.immediate, CONFIG))
        ok(legacy.close()); root = ok(SessionApplicationComposition.create(boundary, Dispatchers.Main.immediate, CONFIG)); token = ok(root.reserve())
        val invalidWork = object : SessionControlStore {
            override suspend fun read(): PortResult<SessionControlRecord?> = PortResult.Failure(FailureReason.STORAGE_FAILURE)
            override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> = forbidden()
        }
        rejected(FailureReason.STORAGE_FAILURE, runtimeOpen(reserved = true, workStore = invalidWork))
        // Failed opening released logical registration but still leaves caller-owned stores reserved.
        val retry = ok(runtimeOpen(reserved = true)); ok(retry.close()); closeOrdinary()
    }

    private fun integration(block: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val sandbox = AndroidRetirementIntegrationSandbox.create(InstrumentationRegistry.getInstrumentation().targetContext)
            val fixture = Fixture(sandbox)
            try { fixture.openOrdinary(); fixture.block() } finally { withContext(NonCancellable) { fixture.finish() } }
        }
    }
    private enum class Stage { PREPARED, CREDENTIAL, DATA, WORK, BOUND, SEALED }
    private class Fixture(val sandbox: AndroidRetirementIntegrationSandbox) {
        val boundary = SessionBoundary()
        var root = ok(SessionApplicationComposition.create(boundary, Dispatchers.Main.immediate, CONFIG))
        var token = ok(root.reserve()) // Before ANY production factory or storage opening.
        var data: EncryptedStateDatabase? = null; var control: EncryptedSessionControlStore? = null
        var work: EncryptedSessionWorkStore? = null; var credentials: AndroidCredentialStore? = null
        lateinit var details: SessionSetupPlanRecord
        var recovery: SessionSetupRecoveryOwner? = null
        var failClose: String? = null; var completeLosses = 0; var lastCompleteRevision = 0L
        var observations = 0; var controlFactories = 0; var completedAfterCloses = false
        val effects = mutableListOf<String>(); val closed = mutableListOf<String>()
        private var closeFailed = false

        suspend fun openOrdinary() {
            data = ok(AndroidStateDatabase.open(sandbox.context)); control = ok(AndroidSessionControlStore.open(sandbox.context))
            work = ok(AndroidSessionWorkStore.open(sandbox.context)); credentials = ok(AndroidCredentialStore.open(sandbox.context))
        }
        suspend fun prepare(stage: Stage) {
            val identity = StoredCredentials.Account(SCOPE, SecretText("synthetic-access"), SecretText("synthetic-refresh"), Long.MAX_VALUE, SecretText(DEVICE))
            val credentialPlan = ok(credentials!!.planCreate(ok(credentials!!.state()).revision, identity))
            val dataPlan = ok(data!!.planActivation(SCOPE))
            val planner = ok(SessionWorkRegistry.open(work!!, boundary, Dispatchers.Main.immediate,
                NativeWorkCancellationPort { forbidden() }, NativeWorkIdSource { ORIGIN },
                NativeWorkAdmissionPolicy { forbidden() }, NativeWorkExecutionPolicy { _, _, _, _ -> forbidden() }))
            try {
                val workPlan = ok(planner.planOrigin(SCOPE, ok(work!!.read())!!.revision))
                details = SessionSetupPlanRecord(OPERATION, SCOPE, CONFIG, credentialPlan, dataPlan, workPlan)
                ok(control!!.compareAndSet(ok(control!!.read())!!.revision, RetirementCodec.encode(RetirementState.PendingSetup(SessionSetupPlan.create(details), false))))
                if (stage >= Stage.CREDENTIAL) ok(credentials!!.commitPlannedCreate(credentialPlan, identity))
                if (stage >= Stage.DATA) ok(data!!.commitPlannedActivation(SCOPE, dataPlan))
                if (stage >= Stage.WORK) ok(planner.selectOrigin(workPlan))
                if (stage >= Stage.BOUND) {
                    val target = ok(data!!.inspectPlannedBinding(SCOPE, dataPlan)).target!!
                    val incarnation = CredentialCreatePlanCodec.decode(credentialPlan.copyForStorage()).incarnation
                    val payload = SessionActivationCodec.encode(SessionActivationRecord(SCOPE, incarnation, ORIGIN, target, CONFIG, OPERATION))
                    ok(data!!.bindPlannedActivation(SCOPE, dataPlan, 2, payload))
                }
                if (stage >= Stage.SEALED) ok(planner.sealOrigin(workPlan))
            } finally { ok(planner.close()) }
        }
        suspend fun closeOrdinary() {
            credentials?.let { ok(it.close()); credentials = null }; work?.let { ok(it.close()); work = null }
            data?.let { ok(it.close()); data = null }; control?.let { ok(it.close()); control = null }
        }
        fun publicOwner() = AndroidSessionSetupRecovery.createOwner(sandbox.context, token).also { recovery = it }
        fun countedOwner(): SessionSetupRecoveryOwner = RetainedSessionSetupRecoveryOwner(token, object : SessionSetupRecoveryFactories {
            override fun control(): ExistingSessionControlRecoveryStore {
                controlFactories++; val delegate = AndroidSessionControlStore.createRecoveryStore(sandbox.context)
                return object : ExistingSessionControlRecoveryStore by delegate {
                    override suspend fun close() = closePart("control") { delegate.close() }
                    override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
                        val complete = RetirementCodec.decode(payload) is RetirementState.Complete
                        if (complete) { assertEquals(listOf("work", "data", "credential"), closed); completedAfterCloses = true }
                        val result = delegate.compareAndSet(expectedRevision, payload)
                        if (complete && result is PortResult.Value) {
                            lastCompleteRevision = result.value.revision
                            if (completeLosses > 0) { completeLosses--; return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
                        }
                        return result
                    }
                }
            }
            override fun credentials(scope: StorageScope, plan: CredentialCreatePlan): CredentialCreateRecoveryOwner {
                val delegate = AndroidCredentialStore.createRecoveryOwner(sandbox.context, scope, plan)
                return object : CredentialCreateRecoveryOwner by delegate {
                    override suspend fun inspect(): PortResult<CredentialCreatePlanObservation> { observations++; return delegate.inspect() }
                    override suspend fun abort(): PortResult<Unit> { effects += "credential"; return delegate.abort() }
                    override suspend fun close() = closePart("credential") { delegate.close() }
                }
            }
            override fun data(scope: StorageScope, plan: StateActivationPlan): StateActivationRecoveryOwner {
                val delegate = AndroidStateDatabase.createActivationRecoveryOwner(sandbox.context, scope, plan)
                return object : StateActivationRecoveryOwner by delegate {
                    override suspend fun inspect(): PortResult<StateActivationPlanObservation> { observations++; return delegate.inspect() }
                    override suspend fun binding(): PortResult<StateRecordInspection> { observations++; return delegate.binding() }
                    override suspend fun abort(expectedBinding: PrivateBytes?): PortResult<Unit> { effects += "data"; return delegate.abort(expectedBinding) }
                    override suspend fun close() = closePart("data") { delegate.close() }
                }
            }
            override fun work(scope: StorageScope, plan: SessionWorkOriginPlan): SessionWorkOriginRecoveryOwner {
                val delegate = AndroidSessionWorkRecovery.createOwner(sandbox.context, scope, plan, boundary, Dispatchers.Main.immediate)
                return object : SessionWorkOriginRecoveryOwner by delegate {
                    override suspend fun inspect(): PortResult<SessionWorkOriginObservation> { observations++; return delegate.inspect() }
                    override suspend fun abort(): PortResult<Unit> { effects += "work"; return delegate.abort() }
                    override suspend fun close() = closePart("work") { delegate.close() }
                }
            }
        }).also { recovery = it }
        private suspend fun closePart(part: String, action: suspend () -> PortResult<Unit>): PortResult<Unit> {
            if (failClose == part) return PortResult.Failure(FailureReason.STORAGE_FAILURE) // Known pre-call failure only.
            val result = action(); if (result is PortResult.Value) closed += part; return result
        }
        suspend fun runtimeOpen(reserved: Boolean, workStore: SessionControlStore = work!!,
            verifiedFixture: Boolean = false): PortResult<PrivateSessionRuntime> {
            var nextId = 990
            val verifier = object : NativeSessionVerifier {
                // Explicit synthetic test verifier, never a provider authentication claim.
                override suspend fun acquire(): PortResult<StoredCredentials> = if (verifiedFixture) PortResult.Value(
                    StoredCredentials.Account(SCOPE, SecretText("synthetic-access"), SecretText("synthetic-refresh"), Long.MAX_VALUE, SecretText(DEVICE))) else forbidden()
                override suspend fun restore(snapshot: CredentialSnapshot): PortResult<PrivateSessionAccessMode> = forbidden()
            }
            return if (reserved) PrivateSessionRuntime.openReserved(token, control!!, workStore, data!!, credentials!!,
                verifier, NativeWorkCancellationPort { forbidden() }, NativeWorkIdSource {
                    if (verifiedFixture) "00000000-0000-4000-8000-000000000${nextId++}" else forbidden()
                }, NativeWorkExecutionPolicy { _, _, _, _ -> forbidden() })
            else PrivateSessionRuntime.open(control!!, workStore, data!!, credentials!!, boundary, Dispatchers.Main.immediate, CONFIG,
                verifier, NativeWorkCancellationPort { forbidden() }, NativeWorkIdSource { forbidden() }, NativeWorkExecutionPolicy { _, _, _, _ -> forbidden() })
        }
        suspend fun readCompleted() {
            // Reservation released only after the entire recovery owner acknowledged close.
            token = ok(root.reserve()); val store = AndroidSessionControlStore.createRecoveryStore(sandbox.context)
            try { ok(store.open()); assertEquals(OPERATION, (RetirementCodec.decode(ok(store.read())!!.payload) as RetirementState.Complete).operationId) }
            finally { ok(store.close()) }
        }
        suspend fun finish() {
            try {
                recovery?.let { ok(it.close()); recovery = null }; closeOrdinary(); ok(token.release()); ok(root.close())
            } catch (failure: Throwable) { closeFailed = true; throw failure }
            finally { if (closeFailed) sandbox.releasePreservingFixture() else sandbox.removeOwnedFixture() }
        }
        fun forbidden(): Nothing = throw AssertionError("Owned startup attempted identity, provider, secret or native-work activity")
    }
    companion object {
        private val SCOPE = StorageScope("native-owned-startup", ActorKind.ACCOUNT, "private-owner")
        private val CONFIG = "a3".repeat(32); private val OTHER_CONFIG = "b4".repeat(32)
        private const val OPERATION = "00000000-0000-4000-8000-000000000981"
        private const val ORIGIN = "00000000-0000-4000-8000-000000000982"
        private const val DEVICE = "00000000-0000-4000-8000-000000000983"
        private fun <T> ok(result: PortResult<T>): T = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> throw AssertionError("Owned native recovery failed: ${result.reason}")
        }
        private fun rejected(reason: FailureReason, result: PortResult<*>) {
            assertTrue(result is PortResult.Failure); assertEquals(reason, (result as PortResult.Failure).reason)
        }
    }
}
