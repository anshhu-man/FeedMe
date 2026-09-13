package com.feedme.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.PrivateStateStore
import com.feedme.core.ports.RecordKey
import com.feedme.core.ports.SecretText
import com.feedme.core.ports.SessionBoundary
import com.feedme.core.ports.SessionLease
import com.feedme.core.ports.StorageScope
import com.feedme.core.ports.StoredCredentials
import com.feedme.core.ports.StoreMutation
import com.feedme.storage.AndroidSessionControlStore
import com.feedme.storage.AndroidSessionWorkStore
import com.feedme.storage.AndroidStateDatabase
import com.feedme.storage.EncryptedSessionControlStore
import com.feedme.storage.EncryptedSessionWorkStore
import com.feedme.storage.EncryptedStateDatabase
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real public Keystore/SQLite/file factories, AlarmManager and WorkManager in the test APK only.
 * Run this class in its own instrumentation invocation: the existing cancellation test class
 * independently checks one-time explicit WorkManager initialization. These synthetic credentials
 * are storage fixtures, NOT provider verification, actual sign-in, remote logout or process-kill proof.
 */
@RunWith(AndroidJUnit4::class)
class AndroidLocalRetirementIntegrationTest {
    @Test fun realLogoutErasesExactIdentityAndCancelsOnlyItsNativeTickets() = integration {
        open()
        val session = login("first")
        val timer = install(session.work, NativeWorkKind.TIMER, "timer")
        val worker = install(session.work, NativeWorkKind.WORKER, "worker")
        val siblingTimer = installUnregistered(NativeWorkKind.TIMER)
        val siblingWorker = installUnregistered(NativeWorkKind.WORKER)
        val siblingScope = StorageScope(SCOPE.environment, ActorKind.ACCOUNT, "synthetic-sibling-owner")
        val siblingData = data.activate(siblingScope).integrationValue()
        write(siblingData, siblingScope, "sibling-value")
        val ownerKeys = sandbox.dataOwnerAliases()
        assertEquals(2, ownerKeys.size)
        val credentialKeys = sandbox.credentialAliases()
        assertEquals(1, credentialKeys.size)

        val progress = coordinator.retire(session.retirement, UUID.randomUUID().toString()).integrationValue()
        assertEquals(LocalRetirementPhase.COMPLETE, progress.phase)
        assertTrue(progress.remaining.isEmpty())
        assertNull(boundary.current())
        assertTrue(coordinator.restorationAllowed().integrationValue())
        assertNull(credentials.state().integrationValue().owner)
        assertNull(credentials.read(SCOPE).integrationValue())
        assertTrue(sandbox.credentialAliases().isEmpty())
        assertTrue((credentialKeys intersect sandbox.aliases()).isEmpty())
        assertNull(data.resume(SCOPE).integrationValue())
        assertEquals(1, sandbox.dataOwnerAliases().size)
        assertTrue(ownerKeys.containsAll(sandbox.dataOwnerAliases()))
        assertPayload(siblingData, siblingScope, "sibling-value")
        assertNull(registry.snapshot().integrationValue().scope)
        assertTrue(registry.snapshot().integrationValue().entries.isEmpty())
        assertNull(findTimer(timer))
        assertEquals(WorkInfo.State.CANCELLED, workerState(worker))
        assertNotNull(findTimer(siblingTimer))
        assertEquals(WorkInfo.State.ENQUEUED, workerState(siblingWorker))
        var effects = 0
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), registry.runLocalEffect(timer) {
            effects++; PortResult.Value(Unit)
        })
        assertEquals(0, effects)
    }

    @Test fun lostNativeCancellationAcknowledgementKeepsBarrierAndRecoversAfterAllStoresReopenWithoutLease() = integration {
        var loseAcknowledgementFor: String? = null
        var lost = false
        val attempts = mutableListOf<String>()
        open(NativeWorkCancellationPort { ticket ->
            attempts += ticket.id
            val result = adapter.cancel(ticket)
            if (result is PortResult.Value && ticket.id == loseAcknowledgementFor && !lost) {
                lost = true
                PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            } else result
        })
        val session = login("pending")
        val timer = install(session.work, NativeWorkKind.TIMER, "timer")
        val worker = install(session.work, NativeWorkKind.WORKER, "worker")
        loseAcknowledgementFor = worker.id
        val dataKeys = sandbox.dataOwnerAliases()
        val credentialKeys = sandbox.credentialAliases()
        val operationId = UUID.randomUUID().toString()

        val pending = coordinator.retire(session.retirement, operationId).integrationValue()
        assertTrue(lost)
        assertEquals(LocalRetirementPhase.PENDING, pending.phase)
        assertEquals(setOf(RetirementStep.NATIVE_WORK), pending.remaining)
        assertEquals(mapOf(RetirementStep.NATIVE_WORK to FailureReason.OUTCOME_UNKNOWN), pending.failures)
        assertFalse(coordinator.restorationAllowed().integrationValue())
        assertNull(boundary.current())
        assertNull(credentials.state().integrationValue().owner)
        assertNull(data.resume(SCOPE).integrationValue())
        assertTrue((dataKeys intersect sandbox.aliases()).isEmpty())
        assertTrue((credentialKeys intersect sandbox.aliases()).isEmpty())
        val evidence = registry.snapshot().integrationValue()
        assertTrue(evidence.retiring)
        assertEquals(worker.id, evidence.entries.single().ticket.id)
        // The durable origin-wide retiring bit is the fence; it need not rewrite each task phase.
        assertEquals(NativeWorkPhase.INSTALLED, evidence.entries.single().phase)
        assertNull(findTimer(timer))
        assertEquals(WorkInfo.State.CANCELLED, workerState(worker))
        val survivingAliases = sandbox.aliases()
        closeStores()

        val recoveredAttempts = mutableListOf<String>()
        open(NativeWorkCancellationPort { ticket ->
            recoveredAttempts += ticket.id
            adapter.cancel(ticket)
        }, recoveryOnly = true)
        assertNull(boundary.current())
        assertEquals(survivingAliases, sandbox.aliases())
        assertFalse(coordinator.restorationAllowed().integrationValue())
        val complete = coordinator.recover().integrationValue()
        assertEquals(LocalRetirementPhase.COMPLETE, complete.phase)
        assertEquals(operationId, complete.operationId)
        assertEquals(listOf(timer.id, worker.id), attempts)
        assertEquals(listOf(worker.id), recoveredAttempts)
        assertTrue(coordinator.restorationAllowed().integrationValue())
        assertNull(boundary.current())
        assertNull(registry.snapshot().integrationValue().scope)
        assertNull(credentials.read(SCOPE).integrationValue())
        assertNull(data.resume(SCOPE).integrationValue())
        assertEquals(survivingAliases, sandbox.aliases())
        coordinator.recover().integrationValue()
        assertEquals(listOf(worker.id), recoveredAttempts)
    }

    @Test fun delayedPriorTargetsCannotEraseNewSameOwnerCredentialsDataOrNativeOrigin() = integration {
        open()
        val old = login("old")
        val oldTimer = install(old.work, NativeWorkKind.TIMER, "same-logical-timer")
        val oldWorker = install(old.work, NativeWorkKind.WORKER, "same-logical-worker")
        assertEquals(LocalRetirementPhase.COMPLETE,
            coordinator.retire(old.retirement, UUID.randomUUID().toString()).integrationValue().phase)
        val fresh = login("new")
        val newTimer = install(fresh.work, NativeWorkKind.TIMER, "same-logical-timer")
        val newWorker = install(fresh.work, NativeWorkKind.WORKER, "same-logical-worker")
        assertNotEquals(old.credentials.incarnation, fresh.credentials.incarnation)
        assertNotEquals(old.work.originBinding, fresh.work.originBinding)
        assertNotEquals(oldTimer.id, newTimer.id)
        assertNotEquals(oldWorker.id, newWorker.id)
        val aliases = sandbox.aliases()
        val state = registry.snapshot().integrationValue()

        registry.retire(SCOPE, old.work.originBinding).integrationValue()
        credentials.retire(SCOPE, old.credentials.incarnation).integrationValue()
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), data.recoverRetirement(old.retirement.dataTarget))
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION),
            coordinator.retire(old.retirement, UUID.randomUUID().toString()))
        assertTrue(boundary.isCurrent(fresh.lease))
        assertEquals(aliases, sandbox.aliases())
        val present = checkNotNull(credentials.read(SCOPE).integrationValue())
        assertEquals(fresh.credentials.incarnation, present.incarnation)
        assertEquals(fresh.credentials.revision, present.revision)
        assertEquals("synthetic-access-new", (present.credentials as StoredCredentials.Account).accessToken.use { it })
        assertPayload(checkNotNull(data.resume(SCOPE).integrationValue()), SCOPE, "private-new")
        val unchanged = registry.snapshot().integrationValue()
        assertEquals(state.revision, unchanged.revision)
        assertEquals(fresh.work.originBinding, unchanged.originBinding)
        assertEquals(setOf(newTimer.id, newWorker.id), unchanged.entries.map { it.ticket.id }.toSet())
        assertNotNull(findTimer(newTimer))
        assertEquals(WorkInfo.State.ENQUEUED, workerState(newWorker))
        assertNull(findTimer(oldTimer))
        assertEquals(WorkInfo.State.CANCELLED, workerState(oldWorker))
    }

    @Test fun runtimeCreatesRestoresExactNativeBindingAndRetiresAllRealResources() = integration {
        val verifier = SyntheticNativeSessionVerifier()
        openRuntime(verifier)
        assertEquals(PrivateSessionPhase.STARTUP, runtime.phase())
        assertNull(runtime.currentAccess())
        assertEquals(PrivateSessionPhase.SIGNED_OUT, runtime.recover().integrationValue())
        val original = runtime.create().integrationValue()
        assertEquals(PrivateSessionPhase.ACTIVE, runtime.phase())
        assertEquals(PrivateSessionAccessMode.ONLINE, original.mode)
        assertEquals(1, verifier.acquisitions)
        assertEquals(0, verifier.restorations)
        val credential = checkNotNull(credentials.read(SCOPE).integrationValue())
        verifier.expectedRestore = credential
        write(original.store, SCOPE, "runtime-private-state")
        val timer = installRuntime(original, NativeWorkKind.TIMER, "runtime-timer")
        val worker = installRuntime(original, NativeWorkKind.WORKER, "runtime-worker")
        assertNotNull(findTimer(timer))
        assertEquals(WorkInfo.State.ENQUEUED, workerState(worker))
        val allAliases = sandbox.aliases()
        val dataKeys = sandbox.dataOwnerAliases()
        val credentialKeys = sandbox.credentialAliases()
        assertEquals(1, dataKeys.size)
        assertEquals(1, credentialKeys.size)
        val oldRuntime = runtime
        closeStores()
        assertEquals(PrivateSessionPhase.CLOSED, oldRuntime.phase())
        assertNull(oldRuntime.currentAccess())
        assertNull(boundary.current())
        assertNotNull(findTimer(timer))
        assertEquals(WorkInfo.State.ENQUEUED, workerState(worker))

        // Close/reopen is real native persistence, not a process-kill or provider-authentication test.
        openRuntime(verifier, restoreOnly = true)
        assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, runtime.recover().integrationValue())
        assertNull(boundary.current())
        assertNull(runtime.currentAccess())
        val restored = runtime.restore().integrationValue()
        assertEquals(1, verifier.acquisitions)
        assertEquals(1, verifier.restorations)
        assertEquals(PrivateSessionAccessMode.ONLINE, restored.mode)
        assertEquals(original.originBinding, restored.originBinding)
        assertEquals(original.retirement.credentialIncarnation, restored.retirement.credentialIncarnation)
        assertTrue("Restore must retain the exact data incarnation",
            original.retirement.dataTarget.copyForStorage().contentEquals(restored.retirement.dataTarget.copyForStorage()))
        assertEquals(allAliases, sandbox.aliases())
        assertPayload(restored.store, SCOPE, "runtime-private-state")
        val projected = restored.credentials.read(SCOPE).integrationValue() as StoredCredentials.Account
        assertNull("Transport projection must not expose the native refresh token", projected.refreshToken)
        assertNotNull(findTimer(timer))
        assertEquals(WorkInfo.State.ENQUEUED, workerState(worker))
        var effects = 0
        runtime.runLocalEffect(timer) { effects++; PortResult.Value(Unit) }.integrationValue()
        runtime.runLocalEffect(worker) { effects++; PortResult.Value(Unit) }.integrationValue()
        assertEquals(2, effects)

        val retired = runtime.retire(restored, UUID.randomUUID().toString()).integrationValue()
        assertEquals(LocalRetirementPhase.COMPLETE, retired.phase)
        assertEquals(PrivateSessionPhase.SIGNED_OUT, runtime.phase())
        assertNull(runtime.currentAccess())
        assertNull(boundary.current())
        assertNull(credentials.state().integrationValue().owner)
        assertNull(credentials.read(SCOPE).integrationValue())
        assertNull(data.resume(SCOPE).integrationValue())
        assertTrue((dataKeys intersect sandbox.aliases()).isEmpty())
        assertTrue((credentialKeys intersect sandbox.aliases()).isEmpty())
        assertEquals(SessionWorkState.Idle,
            SessionWorkCodec.decode(checkNotNull(workStore.read().integrationValue()).payload))
        assertNull(findTimer(timer))
        assertEquals(WorkInfo.State.CANCELLED, workerState(worker))
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), restored.store.read(SCOPE, RECORD))
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), restored.credentials.read(SCOPE))
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), runtime.runLocalEffect(timer) {
            effects++; PortResult.Value(Unit)
        })
        assertEquals(2, effects)
        assertEquals(PrivateSessionPhase.SIGNED_OUT, runtime.recover().integrationValue())
    }

    @Test fun recoveryInspectionAfterReopenPreservesNativeEvidenceWithoutVerificationOrActivation() = integration {
        val verifier = SyntheticNativeSessionVerifier()
        openRuntime(verifier)
        runtime.recover().integrationValue()
        val access = runtime.create().integrationValue()
        write(access.store, SCOPE, "private-diagnostic-state")
        val timer = installRuntime(access, NativeWorkKind.TIMER, "runtime-timer")
        val worker = installRuntime(access, NativeWorkKind.WORKER, "runtime-worker")
        closeStores()
        openRuntime(verifier, restoreOnly = true)
        val beforePhase = runtime.phase()
        val beforeFiles = withContext(Dispatchers.IO) { sandbox.fileSnapshot() }
        val beforeAliases = sandbox.aliases()
        val beforeSlot = credentials.state().integrationValue()
        assertEquals(PrivateSessionPhase.STARTUP, beforePhase)
        assertEquals(0, runtimeCredentialReads)

        repeat(3) {
            assertVerificationRequired(runtime.inspectRecovery().integrationValue())
            assertEquals(beforePhase, runtime.phase())
            assertNull(runtime.currentAccess())
            assertNull(boundary.current())
            assertEquals(1, verifier.acquisitions)
            assertEquals(0, verifier.restorations)
            assertEquals("Inspection must never read credential plaintext", 0, runtimeCredentialReads)
            assertEquals(beforeAliases, sandbox.aliases())
            val slot = credentials.state().integrationValue()
            assertEquals(beforeSlot.revision, slot.revision)
            assertEquals(beforeSlot.incarnation, slot.incarnation)
            assertEquals(beforeSlot.owner, slot.owner)
            assertWorkTickets(access.originBinding, setOf(timer.id, worker.id))
            assertNotNull(findTimer(timer))
            assertEquals(WorkInfo.State.ENQUEUED, workerState(worker))
            withContext(Dispatchers.IO) { sandbox.assertFilesEqual(beforeFiles) }
        }
    }

    @Test fun coherentMetadataDoesNotClaimMissingOrDamagedCredentialMaterialIsUsable() {
        for (damage in CredentialDiagnosticDamage.entries) integration {
            val verifier = SyntheticNativeSessionVerifier()
            openRuntime(verifier)
            runtime.recover().integrationValue()
            val access = runtime.create().integrationValue()
            write(access.store, SCOPE, "private-damaged-credential-diagnostic-state")
            val timer = installRuntime(access, NativeWorkKind.TIMER, "runtime-timer")
            val worker = installRuntime(access, NativeWorkKind.WORKER, "runtime-worker")
            val selected = credentials.state().integrationValue()
            closeStores()
            withContext(Dispatchers.IO) { sandbox.damageSelectedCredentials(damage) }
            openRuntime(verifier, restoreOnly = true)
            // Native open authenticates the manifest but deliberately permits exact retirement
            // when its selected key/blob is absent. It does not decrypt the credential snapshot.
            val beforeFiles = withContext(Dispatchers.IO) { sandbox.fileSnapshot() }
            val beforeAliases = sandbox.aliases()
            repeat(2) {
                assertVerificationRequired(runtime.inspectRecovery().integrationValue())
                assertEquals(PrivateSessionPhase.STARTUP, runtime.phase())
                assertNull(runtime.currentAccess())
                assertNull(boundary.current())
                assertEquals(1, verifier.acquisitions)
                assertEquals(0, verifier.restorations)
                assertEquals("Inspection must not probe credential plaintext", 0, runtimeCredentialReads)
                val metadata = credentials.state().integrationValue()
                assertEquals(selected.revision, metadata.revision)
                assertEquals(selected.incarnation, metadata.incarnation)
                assertEquals(selected.owner, metadata.owner)
                // A separate explicit read demonstrates the report is not a usability claim.
                assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), credentials.read(SCOPE))
                assertEquals(beforeAliases, sandbox.aliases())
                assertWorkTickets(access.originBinding, setOf(timer.id, worker.id))
                assertNotNull(findTimer(timer))
                assertEquals(WorkInfo.State.ENQUEUED, workerState(worker))
                withContext(Dispatchers.IO) { sandbox.assertFilesEqual(beforeFiles) }
            }
        }
    }

    @Test fun emptySetupDiscardRequiresExplicitConfirmationAndPreservesUnselectedOwner() {
        val cases = listOf(
            Triple(true, true, true) to null,
            Triple(true, false, false) to null,
            Triple(false, true, true) to null,
            Triple(true, true, true) to CredentialDiagnosticDamage.MISSING_KEY,
            Triple(true, true, true) to CredentialDiagnosticDamage.MISSING_BLOB,
        )
        for ((fragments, damage) in cases) integration {
            val (hasCredentials, hasData, hasWork) = fragments
            seedEmptySetup(hasCredentials, hasData, hasWork)
            // Authenticated metadata still authorizes this exact empty-setup proposal when its
            // selected credential material is gone. No unknown-orphan inventory is bypassed.
            if (damage != null) withContext(Dispatchers.IO) { sandbox.damageSelectedCredentials(damage) }
            val verifier = SyntheticNativeSessionVerifier()
            openRuntime(verifier, restoreOnly = true)
            val foreignScope = SCOPE.copy(actorId = "unselected-native-owner")
            val selectedDataKeys = sandbox.dataOwnerAliases()
            val foreign = data.activate(foreignScope).integrationValue()
            write(foreign, foreignScope, "must-survive-confirmed-empty-discard")
            val foreignKeys = sandbox.dataOwnerAliases() - selectedDataKeys
            val beforeFiles = withContext(Dispatchers.IO) { sandbox.fileSnapshot() }
            val beforeAliases = sandbox.aliases()
            var prepared: PreparedSetupDiscard? = null
            repeat(2) {
                val proposal = runtime.prepareInterruptedSetupDiscard().integrationValue()
                prepared = proposal
                assertEquals(hasCredentials, proposal.hasCredentials)
                assertEquals(hasData, proposal.hasEmptyPrivateStorage)
                assertEquals(hasWork, proposal.hasEmptyWork)
                assertEquals("PreparedSetupDiscard(<redacted>)", proposal.toString())
                assertEquals(PrivateSessionPhase.STARTUP, runtime.phase())
                assertNull(boundary.current())
                assertEquals(0, verifier.acquisitions + verifier.restorations + runtimeCredentialReads + runtimeNativeCancellations)
                assertEquals(beforeAliases, sandbox.aliases())
                withContext(Dispatchers.IO) { sandbox.assertFilesEqual(beforeFiles) }
            }
            val completed = runtime.confirmInterruptedSetupDiscard(checkNotNull(prepared), UUID.randomUUID().toString()).integrationValue()
            assertEquals(LocalRetirementPhase.COMPLETE, completed.phase)
            assertEquals(PrivateSessionPhase.STARTUP, runtime.phase())
            assertNull(boundary.current())
            assertNull(credentials.state().integrationValue().owner)
            assertNull(data.resume(SCOPE).integrationValue())
            assertTrue(sandbox.credentialAliases().isEmpty())
            assertEquals(1, sandbox.dataOwnerAliases().size)
            assertTrue(foreignKeys.containsAll(sandbox.dataOwnerAliases()))
            assertPayload(foreign, foreignScope, "must-survive-confirmed-empty-discard")
            assertEquals(SessionWorkState.Idle, SessionWorkCodec.decode(checkNotNull(workStore.read().integrationValue()).payload))
            assertEquals(0, verifier.acquisitions + verifier.restorations + runtimeCredentialReads + runtimeNativeCancellations)
            assertEquals(PrivateSessionPhase.SIGNED_OUT, runtime.recover().integrationValue())
        }
    }

    @Test fun discardPreparationRejectsPrivateRowsTombstonesBindingAndNativeTickets() {
        for (kind in listOf("row", "tombstone", "binding", "native-ticket")) integration {
            val seeded = seedEmptySetup(withWorkEntry = kind == "native-ticket")
            val verifier = SyntheticNativeSessionVerifier()
            openRuntime(verifier, restoreOnly = true)
            if (kind != "native-ticket") {
                val store = checkNotNull(data.resume(SCOPE).integrationValue())
                val key = if (kind == "binding") RecordKey("session-activation", "binding-v1") else RECORD
                val revision = checkNotNull(store.commit(SCOPE,
                    listOf(StoreMutation.Put(key, null, 1, PrivateBytes("retained-empty-discard-evidence".encodeToByteArray())))).integrationValue()[key])
                if (kind == "tombstone") store.commit(SCOPE, listOf(StoreMutation.Delete(key, revision))).integrationValue()
            }
            val files = withContext(Dispatchers.IO) { sandbox.fileSnapshot() }
            val aliases = sandbox.aliases()
            assertEquals(PortResult.Failure(FailureReason.CONFLICT), runtime.prepareInterruptedSetupDiscard())
            assertEquals(PrivateSessionPhase.STARTUP, runtime.phase())
            assertNull(boundary.current())
            assertEquals(0, verifier.acquisitions + verifier.restorations + runtimeCredentialReads + runtimeNativeCancellations)
            assertEquals(aliases, sandbox.aliases())
            seeded.timer?.let { assertNotNull(findTimer(it)) }
            withContext(Dispatchers.IO) { sandbox.assertFilesEqual(files) }
        }
    }

    @Test fun discardConfirmationRejectsChangedEvidenceAndProposalFromClosedRuntime() {
        for (change in listOf("credential-revision", "tombstone", "reopen")) integration {
            val seeded = seedEmptySetup()
            val verifier = SyntheticNativeSessionVerifier()
            openRuntime(verifier, restoreOnly = true)
            val prepared = runtime.prepareInterruptedSetupDiscard().integrationValue()
            when (change) {
                "credential-revision" -> {
                    val original = checkNotNull(seeded.credentials)
                    credentials.replace(original, (original.credentials as StoredCredentials.Account)
                        .copy(accessToken = SecretText("synthetic-changed-after-prompt"))).integrationValue()
                }
                "tombstone" -> {
                    val store = checkNotNull(data.resume(SCOPE).integrationValue())
                    write(store, SCOPE, "changed-after-prompt")
                    val revision = checkNotNull(store.read(SCOPE, RECORD).integrationValue()).revision
                    store.commit(SCOPE, listOf(StoreMutation.Delete(RECORD, revision))).integrationValue()
                }
                else -> { closeStores(); openRuntime(verifier, restoreOnly = true) }
            }
            val files = withContext(Dispatchers.IO) { sandbox.fileSnapshot() }
            val aliases = sandbox.aliases()
            assertEquals(PortResult.Failure(if (change == "reopen") FailureReason.STALE_SESSION else FailureReason.CONFLICT),
                runtime.confirmInterruptedSetupDiscard(prepared, UUID.randomUUID().toString()))
            assertEquals(PrivateSessionPhase.STARTUP, runtime.phase())
            assertNull(boundary.current())
            assertEquals(0, verifier.acquisitions + verifier.restorations + runtimeCredentialReads + runtimeNativeCancellations)
            assertEquals(aliases, sandbox.aliases())
            withContext(Dispatchers.IO) { sandbox.assertFilesEqual(files) }
        }
    }

    @Test fun legacyPlannedCreatePersistsIndependentControlBeforeNativeInterruptionAndExplicitAbort() = integration {
        var durablePlan: CredentialCreatePlan? = null
        var reachedNativeWrite = false
        openLegacyCreate(credentialFault = { point ->
            if (point == CredentialFileFaultPoint.AFTER_BLOB_TEMP_FORCE) {
                // The real native callback runs on IO. Read the independent encrypted control
                // engine at this exact checkpoint, before allowing any selection manifest write.
                val record = runBlocking { checkNotNull(control.read().integrationValue()) }
                val pending = RetirementCodec.decode(record.payload) as RetirementState.PendingCreate
                assertFalse(pending.abortRequested)
                assertTrue(pending.blocksAccess())
                durablePlan = pending.plan
                reachedNativeWrite = true
                throw IOException("synthetic-native-create-interruption")
            }
        })
        val verified = StoredCredentials.Account(SCOPE, SecretText("synthetic-legacy-access"),
            SecretText("synthetic-legacy-refresh"), Long.MAX_VALUE, SecretText(UUID.randomUUID().toString()))
        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE),
            commitCredentialCreate(control, credentials, credentials.state().integrationValue().revision, verified) {
                check(boundary.current() == null)
            })
        assertTrue(reachedNativeWrite)
        assertNull(boundary.current())
        assertEquals(0, runtimeCredentialReads + runtimeNativeCancellations)
        assertTrue(sandbox.dataOwnerAliases().isEmpty())
        assertEquals(1, sandbox.credentialAliases().size)
        val details = CredentialCreatePlanCodec.decode(checkNotNull(durablePlan).copyForStorage())
        assertTrue(sandbox.credentialAliases().single().endsWith(".credential.${details.target}"))
        closeStores()

        // An ordinary credential factory cannot inventory-adopt this unselected native blob.
        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), AndroidCredentialStore.open(sandbox.context))
        var recoveryOpens = 0
        val recovery = openCreateRecoveryCoordinator(CredentialCreateRecoveryFactory { plan ->
            recoveryOpens++
            AndroidCredentialStore.openCreateRecovery(sandbox.context, plan)
        })
        val files = withContext(Dispatchers.IO) { sandbox.fileSnapshot() }
        val aliases = sandbox.aliases()
        val proposal = checkNotNull(recovery.inspectPending().integrationValue())
        assertFalse(proposal.abortRequested)
        assertEquals(0, recoveryOpens)
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), recovery.recoverAbort())
        assertEquals(0, recoveryOpens)
        assertEquals(aliases, sandbox.aliases())
        withContext(Dispatchers.IO) { sandbox.assertFilesEqual(files) }
        assertNull(boundary.current())

        // This direct invocation is explicit test confirmation, not a shipped UI or provider.
        recovery.requestAbort(proposal).integrationValue()
        assertEquals(1, recoveryOpens)
        assertNull(recovery.inspectPending().integrationValue())
        assertTrue(sandbox.credentialAliases().isEmpty())
        val completed = RetirementCodec.decode(checkNotNull(control.read().integrationValue()).payload) as RetirementState.Complete
        assertEquals(details.incarnation, completed.operationId)
        assertNull(boundary.current())
        closeStores()

        val uncalledVerifier = SyntheticNativeSessionVerifier()
        openRuntime(uncalledVerifier, restoreOnly = true)
        assertEquals(PrivateSessionPhase.SIGNED_OUT, runtime.recover().integrationValue())
        assertNull(runtime.currentAccess()); assertNull(boundary.current())
        assertNull(credentials.state().integrationValue().owner)
        assertEquals(details.abortedRevision, credentials.state().integrationValue().revision)
        assertEquals(0, uncalledVerifier.acquisitions + uncalledVerifier.restorations + runtimeCredentialReads + runtimeNativeCancellations)
        assertTrue(sandbox.dataOwnerAliases().isEmpty())
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), runtime.restore())
    }

    @Test fun runtimeCompositeNativeInterruptionRetainsAllPlansAndRejectsLegacyCredentialOnlyAbort() = integration {
        val verifier = SyntheticNativeSessionVerifier()
        var original: SessionSetupPlan? = null
        openRuntime(verifier, credentialFault = { point ->
            if (point == CredentialFileFaultPoint.AFTER_BLOB_TEMP_FORCE) {
                val entry = runBlocking { checkNotNull(control.read().integrationValue()) }
                val pending = RetirementCodec.decode(entry.payload) as RetirementState.PendingSetup
                assertFalse(pending.abortRequested)
                original = pending.plan
                val plans = SessionSetupPlanCodec.decode(pending.plan.copyForStorage())
                assertEquals(SCOPE, plans.scope)
                assertEquals("4".repeat(64), plans.configurationBinding)
                assertTrue(plans.dataPlan.copyForStorage().isNotEmpty())
                assertTrue(plans.workOriginPlan.copyForStorage().copyForCodec().isNotEmpty())
                assertNull(boundary.current())
                throw IOException("synthetic-composite-native-create-interruption")
            }
        })
        assertEquals(PrivateSessionPhase.SIGNED_OUT, runtime.recover().integrationValue())
        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), runtime.create())
        val plan = checkNotNull(original)
        assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, runtime.phase())
        assertNull(runtime.currentAccess()); assertNull(boundary.current())
        assertEquals(1, verifier.acquisitions); assertEquals(0, verifier.restorations)
        assertTrue(sandbox.dataOwnerAliases().isEmpty())
        assertEquals(SessionWorkState.Idle, SessionWorkCodec.decode(checkNotNull(workStore.read().integrationValue()).payload))
        assertEquals(1, sandbox.credentialAliases().size)
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), runtime.prepareInterruptedSetupDiscard())
        // The poisoned credential manager is not bypassed by a fresh create/provider response.
        assertTrue(runtime.retryCreate() is PortResult.Failure)
        assertEquals(1, verifier.acquisitions)
        assertNull(boundary.current()); assertNull(runtime.currentAccess())
        closeStores()
        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), AndroidCredentialStore.open(sandbox.context))
        var opens = 0
        val legacy = openCreateRecoveryCoordinator(CredentialCreateRecoveryFactory {
            opens++
            throw AssertionError("Legacy recovery cannot extract a composite credential subplan")
        })
        val before = checkNotNull(control.read().integrationValue())
        val files = withContext(Dispatchers.IO) { sandbox.fileSnapshot() }
        val aliases = sandbox.aliases()
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), legacy.inspectPending())
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), legacy.recoverAbort())
        assertEquals(0, opens)
        val after = checkNotNull(control.read().integrationValue())
        assertEquals(before.revision, after.revision)
        assertTrue(before.payload.copyForCodec().contentEquals(after.payload.copyForCodec()))
        val retained = RetirementCodec.decode(after.payload) as RetirementState.PendingSetup
        assertTrue(plan.copyForStorage().copyForCodec().contentEquals(retained.plan.copyForStorage().copyForCodec()))
        assertEquals(aliases, sandbox.aliases())
        withContext(Dispatchers.IO) { sandbox.assertFilesEqual(files) }
        assertNull(boundary.current())
    }

    private fun integration(block: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val sandbox = AndroidRetirementIntegrationSandbox.create(context)
            val fixture = try { Fixture(sandbox) } catch (failure: Throwable) {
                withContext(Dispatchers.IO) { sandbox.removeOwnedFixture() }
                throw failure
            }
            try { fixture.block() }
            finally { withContext(NonCancellable) { fixture.finish() } }
        }
    }

    private class Fixture(val sandbox: AndroidRetirementIntegrationSandbox) {
        val adapter = AndroidNativeWorkCancellation.create(context,
            ComponentName(context, NativeCancellationTestReceiver::class.java), workManager).integrationValue()
        lateinit var boundary: SessionBoundary
        lateinit var data: EncryptedStateDatabase
        lateinit var control: EncryptedSessionControlStore
        lateinit var workStore: EncryptedSessionWorkStore
        lateinit var credentials: AndroidCredentialStore
        lateinit var registry: SessionWorkRegistry
        lateinit var coordinator: LocalRetirementCoordinator
        lateinit var runtime: PrivateSessionRuntime
        private val closers = mutableListOf<suspend () -> PortResult<Unit>>()
        private val tickets = mutableListOf<NativeWorkTicket>()
        private var closeFailed = false
        var runtimeCredentialReads = 0
            private set
        var runtimeNativeCancellations = 0
            private set

        suspend fun open(cancellation: NativeWorkCancellationPort = adapter, recoveryOnly: Boolean = false) {
            openStores()
            registry = SessionWorkRegistry.open(workStore, boundary, Dispatchers.Main.immediate, cancellation,
                NativeWorkIdSource {
                    check(!recoveryOnly) { "Recovery must not allocate an origin or ticket" }
                    UUID.randomUUID().toString()
                },
                NativeWorkAdmissionPolicy { scope ->
                    check(!recoveryOnly) { "Recovery must not admit a session" }
                    durablePolicyAllowed(scope)
                },
                NativeWorkExecutionPolicy { scope, _, _, _ ->
                    check(!recoveryOnly) { "Recovery must not execute private work" }
                    durablePolicyAllowed(scope)
                },
            ).integrationValue().also { value -> closers += { value.close() } }
            coordinator = LocalRetirementCoordinator(control, data, boundary, Dispatchers.Main.immediate, credentials, registry)
        }

        private suspend fun openStores(credentialFault: ((CredentialFileFaultPoint) -> Unit)? = null) {
            check(closers.isEmpty())
            boundary = SessionBoundary()
            data = AndroidStateDatabase.open(sandbox.context).integrationValue().also { value -> closers += { value.close() } }
            control = AndroidSessionControlStore.open(sandbox.context).integrationValue().also { value -> closers += { value.close() } }
            workStore = AndroidSessionWorkStore.open(sandbox.context).integrationValue().also { value -> closers += { value.close() } }
            credentials = (if (credentialFault == null) AndroidCredentialStore.open(sandbox.context)
                else AndroidCredentialStore.openForTests(File(sandbox.context.noBackupFilesDir, "feedme-credentials"),
                    "com.feedme.session.credentials.v1", credentialFault))
                .integrationValue().also { value -> closers += { value.close() } }
        }

        suspend fun openLegacyCreate(credentialFault: (CredentialFileFaultPoint) -> Unit) = openStores(credentialFault)

        suspend fun openRuntime(verifier: NativeSessionVerifier, restoreOnly: Boolean = false,
            credentialFault: ((CredentialFileFaultPoint) -> Unit)? = null) {
            openStores(credentialFault)
            runtimeCredentialReads = 0
            runtimeNativeCancellations = 0
            val nativeCredentials = credentials
            // Transparent test counter only: all state, mutations and retirement still delegate to
            // the real public native store. A diagnostic secret read cannot be silently ignored.
            val countedCredentials = object : PlannedCredentialCreateStore by nativeCredentials {
                override suspend fun read(scope: StorageScope): PortResult<CredentialSnapshot?> {
                    runtimeCredentialReads++
                    return nativeCredentials.read(scope)
                }
                override suspend fun planCreate(expectedSlotRevision: Long, credentials: StoredCredentials) =
                    nativeCredentials.planCreate(expectedSlotRevision, credentials)
                override suspend fun commitPlannedCreate(plan: CredentialCreatePlan, credentials: StoredCredentials) =
                    nativeCredentials.commitPlannedCreate(plan, credentials)
            }
            runtime = PrivateSessionRuntime.open(control, workStore, data, countedCredentials, boundary,
                Dispatchers.Main.immediate, "4".repeat(64), verifier,
                NativeWorkCancellationPort { ticket -> runtimeNativeCancellations++; adapter.cancel(ticket) },
                NativeWorkIdSource {
                    check(!restoreOnly) { "Exact restore must not allocate an origin or ticket" }
                    UUID.randomUUID().toString()
                },
                NativeWorkExecutionPolicy { scope, _, logicalId, ticket ->
                    // Test-only desired-domain state. Runtime owns the durable barrier and lease checks.
                    PortResult.Value(scope == SCOPE && when (ticket.kind) {
                        NativeWorkKind.TIMER -> logicalId == "runtime-timer"
                        NativeWorkKind.WORKER -> logicalId == "runtime-worker"
                    })
                },
            ).integrationValue().also { value -> closers += { value.close() } }
        }

        suspend fun assertWorkTickets(origin: String, ids: Set<String>) {
            val record = checkNotNull(workStore.read().integrationValue())
            val state = SessionWorkCodec.decode(record.payload) as SessionWorkState.Origin
            assertEquals(origin, state.origin)
            assertFalse(state.retiring)
            assertEquals(ids, state.entries.map { it.id }.toSet())
            assertTrue(state.entries.all { it.phase == NativeWorkPhase.INSTALLED })
        }

        private suspend fun durablePolicyAllowed(scope: StorageScope): PortResult<Boolean> {
            val lease = boundary.current() ?: return PortResult.Value(false)
            if (lease.scope != scope) return PortResult.Value(false)
            // Registry policy executes under its mutex. Read the independent durable record, not
            // a coordinator method that would invert coordinator -> registry retirement locking.
            val record = when (val result = control.read()) {
                is PortResult.Failure -> return result
                is PortResult.Value -> result.value ?: return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            }
            val state = RetirementCodec.decode(record.payload)
            return PortResult.Value(boundary.isCurrent(lease) && !state.blocksAccess())
        }

        suspend fun openCreateRecoveryCoordinator(factory: CredentialCreateRecoveryFactory): CredentialCreateCoordinator {
            check(closers.isEmpty())
            boundary = SessionBoundary()
            // Deliberately open only independent control: ordinary credentials are gated by
            // their partial inventory, and no data/work factory is needed to abort this plan.
            control = AndroidSessionControlStore.open(sandbox.context).integrationValue()
                .also { value -> closers += { value.close() } }
            return CredentialCreateCoordinator(control, boundary, Dispatchers.Main.immediate, factory)
        }

        suspend fun seedEmptySetup(withCredentials: Boolean = true, withData: Boolean = true,
            withWork: Boolean = true, withWorkEntry: Boolean = false): EmptySetupSeed {
            open()
            val credential = if (withCredentials) credentials.create(credentials.state().integrationValue().revision,
                StoredCredentials.Account(SCOPE, SecretText("synthetic-incomplete-access"), SecretText("synthetic-incomplete-refresh"),
                    Long.MAX_VALUE, SecretText(UUID.randomUUID().toString()))).integrationValue() else null
            if (withData) data.activate(SCOPE).integrationValue()
            var timer: NativeWorkTicket? = null
            if (withWork) {
                val lease = boundary.activate(SCOPE)
                val binding = registry.createOrigin(lease, registry.snapshot().integrationValue().revision).integrationValue()
                if (withWorkEntry) timer = install(binding, NativeWorkKind.TIMER, "ineligible-existing-timer")
            }
            boundary.clear()
            closeStores()
            return EmptySetupSeed(credential, timer)
        }

        /** Synthetic independently chosen owner fixture; deliberately no provider or transport. */
        suspend fun login(label: String): Login {
            assertTrue(coordinator.restorationAllowed().integrationValue())
            val snapshot = credentials.create(credentials.state().integrationValue().revision,
                StoredCredentials.Account(SCOPE, SecretText("synthetic-access-$label"), SecretText("synthetic-refresh-$label"),
                    Long.MAX_VALUE, SecretText(UUID.randomUUID().toString()))).integrationValue()
            val lease = boundary.activate(SCOPE)
            val store = data.activate(SCOPE).integrationValue()
            write(store, SCOPE, "private-$label")
            val binding = registry.createOrigin(lease, registry.snapshot().integrationValue().revision).integrationValue()
            return Login(lease, snapshot, binding,
                coordinator.capture(lease, binding.originBinding, snapshot.incarnation).integrationValue())
        }

        suspend fun install(binding: SessionWorkBinding, kind: NativeWorkKind, logicalId: String): NativeWorkTicket =
            registry.install(binding, kind, logicalId) { ticket ->
                tickets += ticket
                schedule(ticket)
                PortResult.Value(Unit)
            }.integrationValue()

        suspend fun installRuntime(access: PrivateSessionAccess, kind: NativeWorkKind, logicalId: String): NativeWorkTicket =
            runtime.install(access, kind, logicalId) { ticket ->
                tickets += ticket
                schedule(ticket)
                PortResult.Value(Unit)
            }.integrationValue()

        suspend fun installUnregistered(kind: NativeWorkKind): NativeWorkTicket {
            val ticket = NativeWorkTicket.fromNativeIdentity(UUID.randomUUID().toString(), kind).integrationValue()
            tickets += ticket
            schedule(ticket)
            return ticket
        }

        private suspend fun schedule(ticket: NativeWorkTicket) {
            when (ticket.kind) {
                NativeWorkKind.TIMER -> {
                    val identity = adapter.timerIdentity(ticket).integrationValue()
                    val pending = checkNotNull(PendingIntent.getBroadcast(context, identity.requestCode, identity.intent(), identity.pendingIntentFlags))
                    // Inexact, one day away: no exact-alarm permission or private callback is needed.
                    alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + TimeUnit.DAYS.toMillis(1), pending)
                }
                NativeWorkKind.WORKER -> {
                    val request = OneTimeWorkRequest.Builder(NativeCancellationTestWorker::class.java)
                        .setId(UUID.fromString(ticket.id)).setInitialDelay(1, TimeUnit.DAYS).build()
                    workManager.enqueue(request).await()
                }
            }
        }

        fun findTimer(ticket: NativeWorkTicket): PendingIntent? {
            val identity = adapter.timerIdentity(ticket).integrationValue()
            return PendingIntent.getBroadcast(context, identity.requestCode, identity.intent(),
                identity.pendingIntentFlags or PendingIntent.FLAG_NO_CREATE)
        }

        suspend fun workerState(ticket: NativeWorkTicket): WorkInfo.State? = withContext(Dispatchers.IO) {
            workManager.getWorkInfoById(UUID.fromString(ticket.id)).get(15, TimeUnit.SECONDS)?.state
        }

        suspend fun closeStores() {
            val pending = closers.asReversed().toList()
            closers.clear()
            var failed = false
            for (close in pending) {
                try { if (close() is PortResult.Failure) failed = true }
                catch (_: Exception) { failed = true }
            }
            if (failed) closeFailed = true
            check(!failed) { "Native integration store close was not acknowledged" }
        }

        suspend fun finish() {
            var safeToRemove = true
            if (::boundary.isInitialized) boundary.clear()
            for (ticket in tickets) {
                try { if (adapter.cancel(ticket) is PortResult.Failure) safeToRemove = false }
                catch (_: Exception) { safeToRemove = false }
            }
            try { closeStores() } catch (_: Exception) { safeToRemove = false }
            if (closeFailed) safeToRemove = false
            if (!safeToRemove) {
                sandbox.releasePreservingFixture()
                fail("Unacknowledged exact cleanup; isolated fixture preserved")
            }
            withContext(Dispatchers.IO) { sandbox.removeOwnedFixture() }
            assertEquals("Delayed test workers must never execute private work", 0, NativeCancellationTestWorker.executions.get())
        }
    }

    private class Login(val lease: SessionLease, val credentials: CredentialSnapshot,
        val work: SessionWorkBinding, val retirement: RetirementBinding)

    private class EmptySetupSeed(val credentials: CredentialSnapshot?, val timer: NativeWorkTicket?)

    /** Deliberate test authority; this does not call or verify any actual provider or API. */
    private class SyntheticNativeSessionVerifier : NativeSessionVerifier {
        var acquisitions = 0
        var restorations = 0
        var expectedRestore: CredentialSnapshot? = null

        override suspend fun acquire(): PortResult<StoredCredentials> {
            acquisitions++
            return PortResult.Value(StoredCredentials.Account(SCOPE, SecretText("synthetic-runtime-access"),
                SecretText("synthetic-runtime-refresh"), Long.MAX_VALUE, SecretText(UUID.randomUUID().toString())))
        }

        override suspend fun restore(snapshot: CredentialSnapshot): PortResult<PrivateSessionAccessMode> {
            restorations++
            val expected = checkNotNull(expectedRestore)
            assertEquals(expected.scope, snapshot.scope)
            assertEquals(expected.incarnation, snapshot.incarnation)
            assertEquals(expected.revision, snapshot.revision)
            val actual = snapshot.credentials as StoredCredentials.Account
            val original = expected.credentials as StoredCredentials.Account
            assertTrue(actual.accessToken.use { value -> original.accessToken.use { it == value } })
            assertTrue(checkNotNull(actual.deviceSessionId).use { value -> checkNotNull(original.deviceSessionId).use { it == value } })
            return PortResult.Value(PrivateSessionAccessMode.ONLINE)
        }
    }

    companion object {
        private val SCOPE = StorageScope("native-retirement-test", ActorKind.ACCOUNT, "synthetic-verified-owner")
        private val RECORD = RecordKey("native-integration", "private-state")
        private lateinit var context: Context
        private lateinit var workManager: WorkManager
        private lateinit var alarms: AlarmManager

        @JvmStatic @BeforeClass fun explicitlyInitializeIsolatedNativeServices() {
            context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            assertEquals("Never configure the actual application", "com.feedme.session.test", context.packageName)
            assertEquals("Exercise modern test APK target behavior", 36, context.applicationInfo.targetSdkVersion)
            try {
                WorkManager.getInstance(context)
                fail("Run this integration class separately; WorkManager must not auto-initialize")
            } catch (_: IllegalStateException) { /* Trusted test composition owns initialization. */ }
            WorkManager.initialize(context, Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).build())
            workManager = WorkManager.getInstance(context)
            alarms = checkNotNull(context.getSystemService(AlarmManager::class.java))
        }

        private suspend fun write(store: PrivateStateStore, scope: StorageScope, text: String) {
            store.commit(scope, listOf(StoreMutation.Put(RECORD, null, 1, PrivateBytes(text.encodeToByteArray())))).integrationValue()
        }

        private suspend fun assertPayload(store: PrivateStateStore, scope: StorageScope, text: String) {
            val bytes = checkNotNull(store.read(scope, RECORD).integrationValue()).payload.copyForCodec()
            try { assertTrue("Private record did not retain its exact payload", bytes.contentEquals(text.encodeToByteArray())) }
            finally { bytes.fill(0) }
        }

        private fun assertVerificationRequired(report: SessionRecoveryReport) {
            assertEquals(SessionRecoveryFinding.VERIFICATION_REQUIRED, report.finding)
            assertEquals(SessionRecoveryNextStep.VERIFY_PREVIOUS_IDENTITY, report.nextStep)
            assertNull(report.component)
            assertNull(report.failureReason)
        }
    }
}

private fun <T> PortResult<T>.integrationValue(): T = when (this) {
    is PortResult.Value -> value
    is PortResult.Failure -> throw AssertionError("Expected native integration value, got $reason")
}
