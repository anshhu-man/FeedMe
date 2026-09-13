package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Protocol-only recording ports; fabricated proofs are never native authenticity evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionSetupCompletionTest {
    @Test fun selectionAloneNeverBindsSealsCompletesOrPublishesALease() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
        assertTrue(f.state() is RetirementState.PendingSetup)
        assertNull(f.binding); assertEquals(0, f.count("bind")); assertEquals(0, f.count("sealWork"))
        assertNull(f.boundary.current())
    }

    @Test fun completionOrdersFreshBindingThenSealThenCompleteAndReturnsOnlyExactDetachedReceipt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin(); f.events.clear()
        val receipt = value(f.coordinator.complete())
        val binding = f.events.indexOf("bind"); val seal = f.events.indexOf("sealWork"); val complete = f.events.indexOf("control.complete")
        assertTrue(binding >= 0 && binding < seal && seal < complete)
        assertEquals(OPERATION, assertIs<RetirementState.Complete>(f.state()).operationId)
        assertEquals(2, receipt.activation.schemaVersion); assertEquals(OPERATION, receipt.activation.setupOperationId)
        assertEquals(ACCOUNT, receipt.activation.scope); assertEquals(CREDENTIAL, receipt.activation.credentialIncarnation)
        assertEquals(ORIGIN, receipt.activation.originBinding); assertEquals(CONFIGURATION, receipt.activation.configurationBinding)
        assertContentEquals(f.target!!.copyForStorage(), receipt.activation.dataTarget.copyForStorage())
        assertRecord(f.binding!!, receipt.binding); assertControl(f.control.record, receipt.control)
        assertEquals(SessionWorkOriginPlanStatus.SEALED, f.workStatus)
        assertEquals(1, f.binding!!.revision); assertNull(f.boundary.current())
        receipt.binding.payload.copyForCodec().fill(0)
        assertBytes(f.binding!!.payload, SessionActivationCodec.encode(receipt.activation))
    }

    @Test fun guestCompletionKeepsGuestIdentityAndAllOriginalPlans() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin(guest())
        val plan = assertIs<RetirementState.PendingSetup>(f.state()).plan.copyForStorage()
        val receipt = value(f.coordinator.complete())
        assertEquals(GUEST, receipt.activation.scope); assertEquals(GUEST, receipt.credential.scope)
        assertEquals(1, f.count("planCredential")); assertEquals(1, f.count("planData")); assertEquals(1, f.count("planWork"))
        assertBytes(plan, f.originalPlan!!)
        assertNull(f.boundary.current())
    }

    @Test fun repeatedCompletionRequiresNewBindingSealAndControlAcknowledgements() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
        val first = value(f.coordinator.complete()); val seals = f.count("sealWork")
        val second = value(f.coordinator.complete())
        assertEquals(first.binding.revision + 1, second.binding.revision)
        assertEquals(first.control.revision + 1, second.control.revision)
        assertBytes(first.binding.payload, second.binding.payload)
        assertEquals(seals + 1, f.count("sealWork"))
        assertEquals(1, f.count("planCredential")); assertNull(f.boundary.current())
    }

    @Test fun selectionOnlyResourcesHaveNoImplicitCompletionFallback() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val onlySelection = object : LiveSessionSetupResources by f {}
        val coordinator = f.newCoordinator(onlySelection)
        value(coordinator.begin(account()))
        val before = f.control.record; val calls = f.events.toList()
        failure(coordinator.complete())
        assertControl(before, f.control.record); assertEquals(calls, f.events)
        assertNull(f.binding)
    }

    @Test fun persistedPendingOrCompleteNeverReconstructsLiveCompletionAuthority() = runTest {
        for (complete in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            if (complete) value(f.coordinator.complete())
            val before = f.control.record; val calls = f.events.toList()
            failure(f.newCoordinator().complete())
            assertControl(before, f.control.record); assertEquals(calls, f.events)
        }
    }

    @Test fun completeCanResumeFailedSelectionOnlyUsingTheOriginalPreparedAttempt() = runTest {
        for (stage in listOf("selectCredential", "selectData", "selectWork")) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.afterFailures[stage] = FailureReason.OUTCOME_UNKNOWN
            failure(f.coordinator.begin(account()))
            val before = f.control.record
            f.afterFailures.clear()
            val receipt = value(f.coordinator.complete())
            assertEquals(OPERATION, receipt.activation.setupOperationId)
            assertEquals(1, f.count("planCredential")); assertEquals(1, f.ids)
            assertEquals(if (stage == "selectCredential") 3 else 2, f.count(stage),
                "Failed selection must retry, and completion separately re-acknowledges credentials")
            assertTrue(receipt.control.revision > before.revision)
            assertNull(f.boundary.current())
        }
    }

    @Test fun failedCompletionCannotBeReenteredThroughSelectionRetryOrNewBegin() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
        f.failures["bind"] = FailureReason.STORAGE_FAILURE
        failure(f.coordinator.complete())
        val calls = f.events.toList(); val before = f.control.record
        failure(f.coordinator.retry()); failure(f.coordinator.begin(guest()))
        assertEquals(calls, f.events); assertControl(before, f.control.record)
        f.failures.clear(); value(f.coordinator.complete())
    }

    @Test fun bindingFailureBeforeCommitStopsSealAndRetryBindsTheSamePayload() = runTest {
        for (reason in listOf(FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin(); f.failures["bind"] = reason
            failure(f.coordinator.complete())
            assertNull(f.binding); assertEquals(0, f.count("sealWork")); assertTrue(f.state() is RetirementState.PendingSetup)
            f.failures.clear(); value(f.coordinator.complete())
            assertEquals(1, f.binding!!.revision); assertEquals(2, f.count("bind")); assertEquals(1, f.ids)
        }
    }

    @Test fun visibleBindingAfterUnknownWriteRequiresNewRevisionBeforeSealing() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin(); f.afterFailures["bind"] = FailureReason.OUTCOME_UNKNOWN
        failure(f.coordinator.complete())
        val visible = f.binding!!
        assertEquals(0, f.count("sealWork")); assertTrue(f.state() is RetirementState.PendingSetup)
        f.afterFailures.clear()
        f.before = { if (it == "sealWork") assertEquals(visible.revision + 1, f.binding!!.revision) }
        value(f.coordinator.complete())
        assertBytes(visible.payload, f.binding!!.payload)
    }

    @Test fun failedSealBeforeOrAfterSelectionRequiresFreshBindingAndSealOnRetry() = runTest {
        for (after in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            if (after) f.afterFailures["sealWork"] = FailureReason.OUTCOME_UNKNOWN else f.failures["sealWork"] = FailureReason.STORAGE_FAILURE
            failure(f.coordinator.complete())
            val first = f.binding!!
            assertTrue(f.state() is RetirementState.PendingSetup)
            assertEquals(if (after) SessionWorkOriginPlanStatus.SEALED else SessionWorkOriginPlanStatus.SELECTED, f.workStatus)
            f.failures.clear(); f.afterFailures.clear()
            value(f.coordinator.complete())
            assertEquals(first.revision + 1, f.binding!!.revision); assertEquals(2, f.count("sealWork"))
        }
    }

    @Test fun failedPendingReackBeforeOrAfterCommitStopsCredentialReackBindingAndSeal() = runTest {
        for (after in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            val calls = f.count("selectCredential")
            if (after) f.control.afterFailures[2] = FailureReason.OUTCOME_UNKNOWN else f.control.beforeFailures[2] = FailureReason.STORAGE_FAILURE
            failure(f.coordinator.complete())
            assertEquals(calls, f.count("selectCredential")); assertNull(f.binding); assertEquals(0, f.count("sealWork"))
            assertTrue(f.state() is RetirementState.PendingSetup)
            value(f.coordinator.complete())
            assertNotNull(f.binding)
        }
    }

    @Test fun failedCompleteBeforeOrAfterCommitNeverReturnsReceiptAndRetryReacksEveryComponent() = runTest {
        for (after in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            if (after) f.control.afterFailures[3] = FailureReason.OUTCOME_UNKNOWN else f.control.beforeFailures[3] = FailureReason.STORAGE_FAILURE
            failure(f.coordinator.complete())
            val first = f.binding!!; val revision = f.control.record.revision; val seals = f.count("sealWork")
            assertEquals(after, f.state() is RetirementState.Complete)
            val receipt = value(f.coordinator.complete())
            assertEquals(first.revision + 1, receipt.binding.revision)
            assertEquals(seals + 1, f.count("sealWork")); assertTrue(receipt.control.revision > revision)
        }
    }

    @Test fun alreadyCompleteWithMissingBindingCannotCreateAReplacementBinding() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin(); value(f.coordinator.complete())
        f.binding = null
        val binds = f.count("bind"); val writes = f.control.writes
        failure(f.coordinator.complete())
        assertEquals(binds, f.count("bind")); assertEquals(writes, f.control.writes)
    }

    @Test fun alreadyCompleteRequiresExactStillUnusedSealedWork() = runTest {
        for (status in listOf(SessionWorkOriginPlanStatus.PREPARED, SessionWorkOriginPlanStatus.SELECTED)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin(); value(f.coordinator.complete())
            f.workStatus = status
            val binds = f.count("bind"); val writes = f.control.writes
            failure(f.coordinator.complete())
            assertEquals(binds, f.count("bind")); assertEquals(writes, f.control.writes)
        }
    }

    @Test fun staleOrForeignCompleteRecordNeverCompletesThisLiveAttempt() = runTest {
        for (sameOperation in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            f.control.record = SessionControlRecord(if (sameOperation) 11 else 20,
                RetirementCodec.encode(RetirementState.Complete(if (sameOperation) OPERATION else ORIGIN)))
            val before = f.control.record
            failure(f.coordinator.complete())
            assertControl(before, f.control.record); assertNull(f.binding)
        }
    }

    @Test fun noOpOrJumpedBindingReceiptCannotSubstituteForOneChangedRevision() = runTest {
        for (jump in listOf(0L, 2L)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            f.binding = PrivateRecord(9, 2, f.expectedPayload())
            f.bindRevision = { it + jump }
            failure(f.coordinator.complete())
            assertEquals(0, f.count("sealWork")); assertTrue(f.state() is RetirementState.PendingSetup)
        }
    }

    @Test fun missingWrongSchemaPayloadOrTargetInBindingReceiptStopsBeforeSeal() = runTest {
        for (variant in 0..4) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            f.bindReceipt = { inspection -> when (variant) {
                0 -> StateRecordInspection(inspection.target, null)
                1 -> StateRecordInspection(null, inspection.record)
                2 -> StateRecordInspection(otherTarget(), inspection.record)
                3 -> StateRecordInspection(inspection.target, PrivateRecord(inspection.record!!.revision, 1, inspection.record!!.payload))
                else -> StateRecordInspection(inspection.target, PrivateRecord(inspection.record!!.revision, 2, bytes("private-wrong-payload")))
            } }
            failure(f.coordinator.complete())
            assertEquals(0, f.count("sealWork")); assertTrue(f.state() is RetirementState.PendingSetup)
        }
    }

    @Test fun changedBindingReadbackCannotAuthorizeSealOrComplete() = runTest {
        for (afterSeal in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            f.after = { if (it == (if (afterSeal) "sealWork" else "bind")) {
                val current = f.binding!!
                f.binding = PrivateRecord(current.revision + 1, 2, current.payload)
            } }
            failure(f.coordinator.complete())
            assertEquals(if (afterSeal) 1 else 0, f.count("sealWork"))
            assertTrue(f.state() is RetirementState.PendingSetup)
        }
    }

    @Test fun nonemptyTombstonedMissingOrForeignDataFailuresNeverFallBackToGeneralActivation() = runTest {
        for (reason in listOf(FailureReason.CONFLICT, FailureReason.STALE_SESSION, FailureReason.STORAGE_FAILURE, FailureReason.INVALID_DATA)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin(); f.failures["inspectBinding"] = reason
            val writes = f.control.writes
            failure(f.coordinator.complete())
            assertEquals(writes, f.control.writes); assertNull(f.binding); assertEquals(0, f.count("sealWork"))
        }
    }

    @Test fun existingDifferentConfigurationOperationOrVersionedBindingIsNeverOverwritten() = runTest {
        for (variant in 0..2) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            val activation = f.activation(configuration = if (variant == 0) "e".repeat(64) else CONFIGURATION,
                operation = if (variant == 1) ORIGIN else if (variant == 2) null else OPERATION)
            f.binding = PrivateRecord(3, activation.schemaVersion, SessionActivationCodec.encode(activation))
            val before = f.binding!!
            failure(f.coordinator.complete())
            assertRecord(before, f.binding!!); assertEquals(0, f.count("bind"))
        }
    }

    @Test fun credentialMetadataChangesBeforeOrDuringBindingFenceCompletion() = runTest {
        for (during in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            val change = { f.slot = CredentialSlotState(5, ACCOUNT, CREDENTIAL) }
            if (during) f.after = { if (it == "bind") change() } else change()
            failure(f.coordinator.complete())
            assertEquals(0, f.count("sealWork")); assertTrue(f.state() is RetirementState.PendingSetup)
        }
    }

    @Test fun exactCredentialReackMustMatchFullPayloadNotOnlyIncarnationMetadata() = runTest {
        for (variant in 0..3) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            f.credentialReceipt = { snapshot -> CredentialSnapshot(snapshot.incarnation, snapshot.revision,
                when (variant) {
                    0 -> account(access = "different-access")
                    1 -> account(refresh = "different-refresh")
                    2 -> account(device = ORIGIN)
                    else -> account(scope = ACCOUNT.copy(actorId = "different-owner"))
                }) }
            failure(f.coordinator.complete())
            assertNull(f.binding); assertEquals(0, f.count("sealWork"))
        }
    }

    @Test fun replacementAbortJournalAfterBindingStopsSealAndPreservesReplacement() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
        val pending = assertIs<RetirementState.PendingSetup>(f.state())
        f.after = { if (it == "bind") f.control.record = SessionControlRecord(f.control.record.revision + 1,
            RetirementCodec.encode(RetirementState.PendingSetup(pending.plan, true))) }
        failure(f.coordinator.complete())
        assertTrue(assertIs<RetirementState.PendingSetup>(f.state()).abortRequested)
        assertEquals(0, f.count("sealWork"))
    }

    @Test fun successfulSealReceiptRequiresExactSealedStatusBeforeControlCompletion() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
        f.after = { if (it == "sealWork") f.workStatus = SessionWorkOriginPlanStatus.SELECTED }
        failure(f.coordinator.complete())
        assertTrue(f.state() is RetirementState.PendingSetup)
        assertEquals(0, f.count("control.complete"))
    }

    @Test fun falseCompleteReceiptAndChangedReadbackNeverReturnACompletionReceipt() = runTest {
        for (variant in 0..2) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            when (variant) {
                0 -> f.control.receipt = { if (f.state() is RetirementState.Complete) SessionControlRecord(it.revision - 1, it.payload) else it }
                1 -> f.control.receipt = { if (f.state() is RetirementState.Complete) SessionControlRecord(it.revision, RetirementCodec.encode(RetirementState.Idle)) else it }
                else -> f.after = { if (it == "control.complete") f.control.readFailure = FailureReason.STORAGE_FAILURE }
            }
            failure(f.coordinator.complete())
            assertTrue(f.state() is RetirementState.Complete)
            f.control.receipt = { it }; f.after = { }; f.control.readFailure = null
            value(f.coordinator.complete())
            assertEquals(2, f.count("bind")); assertEquals(2, f.count("sealWork"))
        }
    }

    @Test fun newlyRetiringStaleOrActiveOwnerAfterAwaitStopsTheNextCompletionEffect() = runTest {
        for (stage in listOf("bind", "sealWork", "control.complete")) for (kind in 0..2) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            f.after = { if (it == stage) when (kind) { 0 -> f.retiring = true; 1 -> f.current = false; else -> f.boundary.activate(ACCOUNT) } }
            failure(f.coordinator.complete())
            if (kind == 2) assertNotNull(f.boundary.current())
            if (stage == "bind") assertEquals(0, f.count("sealWork"))
            if (stage != "control.complete") assertTrue(f.state() is RetirementState.PendingSetup)
        }
    }

    @Test fun explicitCancelDuringSuspendedBindingDropsLiveAuthorityWithoutCleanup() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.after = { if (it == "bind") { entered.complete(Unit); release.await() } }
        val completing = async { f.coordinator.complete() }; entered.await()
        val before = f.control.record; val bound = f.binding!!
        value(f.coordinator.cancel()); release.complete(Unit)
        failure(completing.await()); failure(f.coordinator.complete())
        assertRecord(bound, f.binding!!); assertControl(before, f.control.record); assertEquals(0, f.count("sealWork"))
    }

    @Test fun closeDuringSuspendedSealFencesFinalCompleteAndIsPermanent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.after = { if (it == "sealWork") { entered.complete(Unit); release.await() } }
        val completing = async { f.coordinator.complete() }; entered.await()
        value(f.coordinator.close()); value(f.coordinator.close()); release.complete(Unit)
        failure(completing.await()); failure(f.coordinator.complete())
        assertTrue(f.state() is RetirementState.PendingSetup); assertEquals(SessionWorkOriginPlanStatus.SEALED, f.workStatus)
    }

    @Test fun coroutineCancellationAtCompletionEffectsDropsRetryAuthorityButPreservesWrittenState() = runTest {
        for (stage in listOf("bind", "sealWork", "control.complete")) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
            f.after = { if (it == stage) throw CancellationException("private-cancel") }
            assertFailsWith<CancellationException> { f.coordinator.complete() }
            f.after = { }; val calls = f.events.toList(); val before = f.control.record
            failure(f.coordinator.complete()); failure(f.coordinator.retry())
            assertEquals(calls, f.events); assertControl(before, f.control.record); assertNull(f.boundary.current())
        }
    }

    @Test fun completionReturnHandoffCancellationDropsLiveContextEvenAfterVisibleComplete() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
        val scheduled = StandardTestDispatcher(testScheduler); var armed = false
        val caller = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (armed) context[Job]?.cancel()
                scheduled.dispatch(context, block)
            }
        }
        f.after = { if (it == "control.complete") armed = true }
        val completing = async(caller) { f.coordinator.complete() }
        assertFailsWith<CancellationException> { completing.await() }
        assertTrue(f.state() is RetirementState.Complete)
        val calls = f.events.toList(); failure(f.coordinator.complete())
        assertEquals(calls, f.events); assertNull(f.boundary.current())
    }

    @Test fun cancellationOfQueuedCompletionDoesNotInvalidateTheRunningCompletion() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.after = { if (it == "bind") { entered.complete(Unit); release.await() } }
        val first = async { f.coordinator.complete() }; entered.await()
        val queued = async { f.coordinator.complete() }; runCurrent(); queued.cancel(); runCurrent()
        assertFailsWith<CancellationException> { queued.await() }; release.complete(Unit)
        value(first.await()); assertTrue(f.state() is RetirementState.Complete)
    }

    @Test fun schemaTwoBindsOperationAndLegacyOneNeverInfersItOrAllowsExtraFields() {
        val legacy = activation(operation = null); val modern = activation()
        assertNull(SessionActivationCodec.decode(SessionActivationCodec.encode(legacy)).setupOperationId)
        assertEquals(OPERATION, SessionActivationCodec.decode(SessionActivationCodec.encode(modern)).setupOperationId)
        val one = Json.parseToJsonElement(SessionActivationCodec.encode(legacy).copyForCodec().decodeToString()).jsonObject
        val two = Json.parseToJsonElement(SessionActivationCodec.encode(modern).copyForCodec().decodeToString()).jsonObject
        assertEquals(one.keys + "setupOperationId", two.keys)
        for (bad in listOf(JsonObject(two - "setupOperationId"), JsonObject(one + ("setupOperationId" to JsonPrimitive(OPERATION))),
            JsonObject(two + ("setupOperationId" to JsonNull)), JsonObject(two + ("version" to JsonPrimitive("2"))),
            JsonObject(two + ("setupOperationId" to JsonPrimitive("private-operation")))))
            assertFailsWith<SessionActivationFormatException> { SessionActivationCodec.decode(bytes(bad.toString())) }
    }

    @Test fun completedReceiptAndBindingDiagnosticsNeverPrintIdentifiersOrCredentials() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.begin()
        val receipt = value(f.coordinator.complete())
        for (view in listOf(receipt, receipt.activation, receipt.binding.payload, receipt.control.payload, receipt.credential))
            for (secret in listOf(OPERATION, ORIGIN, CREDENTIAL, ACCOUNT.actorId, "private-access", "private-refresh", CONFIGURATION))
                assertFalse(view.toString().contains(secret))
        assertNull(f.boundary.current())
    }

    private class Fixture(private val dispatcher: CoroutineDispatcher) : LiveSessionSetupCompletionResources {
        val events = mutableListOf<String>()
        val boundary = SessionBoundary()
        val control = Control(this)
        val coordinator = newCoordinator()
        var before: suspend (String) -> Unit = { }
        var after: suspend (String) -> Unit = { }
        val failures = mutableMapOf<String, FailureReason>()
        val afterFailures = mutableMapOf<String, FailureReason>()
        var current = true; var retiring = false; var ids = 0
        var verified: StoredCredentials = account()
        var slot = CredentialSlotState(3, null, null)
        var dataStatus = StateActivationStatus.PREPARED
        var workStatus = SessionWorkOriginPlanStatus.PREPARED
        var target: StateRetirementTarget? = target()
        var binding: PrivateRecord? = null
        var originalPlan: PrivateBytes? = null
        var bindRevision: (Long) -> Long = { it + 1 }
        var bindReceipt: (StateRecordInspection) -> StateRecordInspection = { it }
        var credentialReceipt: (CredentialSnapshot) -> CredentialSnapshot = { it }
        fun newCoordinator(resources: LiveSessionSetupResources = this) = LiveSessionSetupCoordinator(control, resources, boundary,
            dispatcher, CONFIGURATION, NativeWorkIdSource { ids++; OPERATION }, { retiring }, { current })
        suspend fun begin(credentials: StoredCredentials = account()) { verified = credentials; value(coordinator.begin(credentials)); originalPlan = assertIs<RetirementState.PendingSetup>(state()).plan.copyForStorage() }
        fun state() = RetirementCodec.decode(control.record.payload)
        fun count(name: String) = events.count { it == name }
        fun activation(configuration: String = CONFIGURATION, operation: String? = OPERATION) =
            SessionActivationRecord(verified.scope, CREDENTIAL, ORIGIN, target!!, configuration, operation)
        fun expectedPayload() = SessionActivationCodec.encode(activation())
        suspend fun <T> call(name: String, action: () -> T): PortResult<T> {
            events += name; before(name)
            failures[name]?.let { return PortResult.Failure(it) }
            val result = action(); after(name)
            afterFailures[name]?.let { return PortResult.Failure(it) }
            return PortResult.Value(result)
        }
        override suspend fun credentialState() = call("credentialState") { slot }
        override suspend fun workState() = call("workState") { SessionWorkSnapshot(7, null, null, false, emptyList()) }
        override suspend fun planCredential(expectedRevision: Long, credentials: StoredCredentials) = call("planCredential") {
            verified = credentials; credentialPlan(expectedRevision)
        }
        override suspend fun planData(scope: StorageScope) = call("planData") { assertEquals(verified.scope, scope); dataPlan() }
        override suspend fun planWork(scope: StorageScope, expectedRevision: Long) = call("planWork") { workPlan(scope, expectedRevision) }
        override suspend fun inspectData(scope: StorageScope, plan: StateActivationPlan) = call("inspectData") { StateActivationInspection(dataStatus) }
        override suspend fun inspectWork(plan: SessionWorkOriginPlan) = call("inspectWork") { workStatus }
        override suspend fun selectCredential(plan: CredentialCreatePlan, credentials: StoredCredentials) = call("selectCredential") {
            assertBytes(credentialPlan().copyForStorage(), plan.copyForStorage()); assertEquals(verified.scope, credentials.scope)
            slot = CredentialSlotState(4, credentials.scope, CREDENTIAL)
            credentialReceipt(CredentialSnapshot(CREDENTIAL, 4, credentials))
        }
        override suspend fun selectData(scope: StorageScope, plan: StateActivationPlan) = call("selectData") {
            assertEquals(verified.scope, scope); assertContentEquals(dataPlan().copyForStorage(), plan.copyForStorage())
            dataStatus = StateActivationStatus.SELECTED_EMPTY
        }
        override suspend fun selectWork(plan: SessionWorkOriginPlan) = call("selectWork") {
            assertBytes(workPlan(verified.scope).copyForStorage(), plan.copyForStorage()); workStatus = SessionWorkOriginPlanStatus.SELECTED
        }
        override suspend fun inspectBinding(scope: StorageScope, plan: StateActivationPlan) = call("inspectBinding") {
            assertEquals(verified.scope, scope); assertContentEquals(dataPlan().copyForStorage(), plan.copyForStorage())
            StateRecordInspection(target, binding)
        }
        override suspend fun bind(scope: StorageScope, plan: StateActivationPlan, payload: PrivateBytes) = call("bind") {
            assertEquals(verified.scope, scope); assertContentEquals(dataPlan().copyForStorage(), plan.copyForStorage())
            assertBytes(expectedPayload(), payload); assertNull(boundary.current())
            binding = PrivateRecord(bindRevision(binding?.revision ?: 0), 2, PrivateBytes(payload.copyForCodec()))
            dataStatus = StateActivationStatus.SELECTED_NONEMPTY
            bindReceipt(StateRecordInspection(target, binding))
        }
        override suspend fun sealWork(plan: SessionWorkOriginPlan) = call("sealWork") {
            assertBytes(workPlan(verified.scope).copyForStorage(), plan.copyForStorage())
            assertNotNull(binding); assertNull(boundary.current())
            workStatus = SessionWorkOriginPlanStatus.SEALED
        }
    }

    private class Control(private val f: Fixture) : SessionControlStore {
        var record = SessionControlRecord(11, RetirementCodec.encode(RetirementState.Idle))
        var writes = 0
        val beforeFailures = mutableMapOf<Int, FailureReason>()
        val afterFailures = mutableMapOf<Int, FailureReason>()
        var readFailure: FailureReason? = null
        var receipt: (SessionControlRecord) -> SessionControlRecord = { it }
        override suspend fun read(): PortResult<SessionControlRecord?> {
            f.events += "control.read"; f.before("control.read")
            readFailure?.let { return PortResult.Failure(it) }
            val saved = record; f.after("control.read"); return PortResult.Value(saved)
        }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            writes++
            val name = if (RetirementCodec.decode(payload) is RetirementState.Complete) "control.complete" else "control.pending"
            f.events += name; f.before(name)
            beforeFailures[writes]?.let { return PortResult.Failure(it) }
            if (record.revision != expectedRevision) return PortResult.Failure(FailureReason.CONFLICT)
            record = SessionControlRecord(record.revision + 1, PrivateBytes(payload.copyForCodec()))
            val result = record; f.after(name)
            afterFailures[writes]?.let { return PortResult.Failure(it) }
            return PortResult.Value(receipt(result))
        }
    }

    companion object {
        private val ACCOUNT = StorageScope("completion-test", ActorKind.ACCOUNT, "private-completion-owner")
        private val GUEST = ACCOUNT.copy(actorKind = ActorKind.GUEST, actorId = "private-guest-owner")
        private val CONFIGURATION = "d".repeat(64)
        private const val OPERATION = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        private const val CREDENTIAL = "11111111-2222-4333-8444-555555555555"
        private const val ORIGIN = "22222222-3333-4444-8555-666666666666"
        private const val DEVICE = "33333333-4444-4555-8666-777777777777"
        private fun account(scope: StorageScope = ACCOUNT, access: String = "private-access", refresh: String = "private-refresh", device: String = DEVICE) =
            StoredCredentials.Account(scope, SecretText(access), SecretText(refresh), 500_000, SecretText(device))
        private fun guest() = StoredCredentials.Guest(GUEST, SecretText(CREDENTIAL), SecretText("private-guest-token"), 500_000)
        private fun credentialPlan(expected: Long = 3) = CredentialCreatePlan.create(CredentialCreatePlanRecord(expected, CREDENTIAL,
            "a".repeat(64), "b".repeat(64), "c".repeat(64)))
        private fun workPlan(scope: StorageScope = ACCOUNT, expected: Long = 7) = SessionWorkOriginPlan.create(
            SessionWorkOriginPlanRecord(expected, scope, ORIGIN, PrivateBytes(ByteArray(64))))
        private fun dataPlan() = StateActivationPlan(ByteArray(170).apply { this[0] = 1
            for (index in 2..65) this[index] = 'a'.code.toByte()
            for (index in 106..137) this[index] = 'b'.code.toByte()
        })
        private fun target() = StateRetirementTarget(ByteArray(StateRetirementTarget.ENCODED_SIZE) { 17 })
        private fun otherTarget() = StateRetirementTarget(ByteArray(StateRetirementTarget.ENCODED_SIZE) { 23 })
        private fun activation(operation: String? = OPERATION) = SessionActivationRecord(ACCOUNT, CREDENTIAL, ORIGIN, target(), CONFIGURATION, operation)
        private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
        private fun assertBytes(a: PrivateBytes, b: PrivateBytes) = assertContentEquals(a.copyForCodec(), b.copyForCodec())
        private fun assertRecord(a: PrivateRecord, b: PrivateRecord) { assertEquals(a.revision, b.revision); assertEquals(a.schemaVersion, b.schemaVersion); assertBytes(a.payload, b.payload) }
        private fun assertControl(a: SessionControlRecord, b: SessionControlRecord) { assertEquals(a.revision, b.revision); assertBytes(a.payload, b.payload) }
        private fun failure(result: PortResult<*>) = assertIs<PortResult.Failure>(result)
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    }
}
