package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Recording protocol fakes only: fingerprints/plans below do not claim native authentication. */
class InterruptedSetupInspectorTest {
    @Test fun boundEvidenceIsReadForwardAndBackwardInsideControlAndLatchBrackets() = runTest {
        val f = Fixture().apply { bound() }
        val report = f.inspect()
        assertEquals(InterruptedSetupFinding.UNCONFIRMED_SETUP, report.finding)
        assertEquals(listOf("latch1", "control1", "credential1", "data1", "binding1", "work1",
            "work2", "data2", "binding2", "credential2", "latch2", "control2", "latch3"), f.events)
        assertEquals(CredentialCreateRecoveryStatus.SELECTED, report.credentialStage)
        assertEquals(InterruptedSetupDataStage.BOUND, report.dataStage)
        assertEquals(SessionWorkOriginPlanStatus.SEALED, report.workStage)
        f.unchanged()
    }

    @Test fun preparedInspectionNeverReadsBindingOrCreatesAnyResource() = runTest {
        val f = Fixture(); val report = f.inspect()
        assertEquals(InterruptedSetupFinding.UNCONFIRMED_SETUP, report.finding)
        assertEquals(InterruptedSetupDataStage.PREPARED, report.dataStage)
        assertEquals(0, f.count("binding")); assertEquals(2, f.count("credential"))
        assertEquals(2, f.count("data")); assertEquals(2, f.count("work")); f.unchanged()
    }

    @Test fun everyReachableUnconfirmedStageReportsOnlyCoherentEnums() = runTest {
        val stages = listOf(
            Triple(CredentialCreateRecoveryStatus.PREPARED, StateActivationStatus.PREPARED, SessionWorkOriginPlanStatus.PREPARED),
            Triple(CredentialCreateRecoveryStatus.PARTIAL, StateActivationStatus.PREPARED, SessionWorkOriginPlanStatus.PREPARED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.PREPARED, SessionWorkOriginPlanStatus.PREPARED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.PARTIAL, SessionWorkOriginPlanStatus.PREPARED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_EMPTY, SessionWorkOriginPlanStatus.PREPARED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_EMPTY, SessionWorkOriginPlanStatus.SELECTED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_NONEMPTY, SessionWorkOriginPlanStatus.SELECTED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_NONEMPTY, SessionWorkOriginPlanStatus.SEALED),
        )
        for ((credential, data, work) in stages) {
            val f = Fixture().apply { stage(credential, data, work) }
            val report = f.inspect()
            assertEquals(InterruptedSetupFinding.UNCONFIRMED_SETUP, report.finding)
            assertEquals(credential, report.credentialStage); assertEquals(work, report.workStage)
            assertEquals(dataStage(data), report.dataStage); f.unchanged()
        }
    }

    @Test fun everyImpossibleLiveSelectionOrderFailsWithoutPublishingStages() = runTest {
        for (credential in CredentialCreateRecoveryStatus.entries) for (data in StateActivationStatus.entries)
            for (work in SessionWorkOriginPlanStatus.entries) {
                val permitted = when (credential) {
                    CredentialCreateRecoveryStatus.PREPARED, CredentialCreateRecoveryStatus.PARTIAL ->
                        data == StateActivationStatus.PREPARED && work == SessionWorkOriginPlanStatus.PREPARED
                    CredentialCreateRecoveryStatus.SELECTED -> when (data) {
                        StateActivationStatus.PREPARED, StateActivationStatus.PARTIAL -> work == SessionWorkOriginPlanStatus.PREPARED
                        StateActivationStatus.SELECTED_EMPTY -> work in setOf(SessionWorkOriginPlanStatus.PREPARED, SessionWorkOriginPlanStatus.SELECTED)
                        StateActivationStatus.SELECTED_NONEMPTY -> work in setOf(SessionWorkOriginPlanStatus.SELECTED, SessionWorkOriginPlanStatus.SEALED)
                        else -> false
                    }
                    else -> false
                }
                if (permitted) continue
                val f = Fixture().apply { stage(credential, data, work) }
                val report = f.inspect()
                assertEquals(InterruptedSetupFinding.RESOURCE_MISMATCH, report.finding, "$credential/$data/$work")
                assertEquals(FailureReason.CONFLICT, report.failureReason); noStages(report); f.unchanged()
            }
    }

    @Test fun confirmedFlagReportsAuthenticatedCleanupProgressWithoutInferringActions() = runTest {
        for (credential in CredentialCreateRecoveryStatus.entries) for (data in StateActivationStatus.entries)
            for (work in SessionWorkOriginPlanStatus.entries) {
            val f = Fixture(abort = true).apply { stage(credential, data, work) }
            val report = f.inspect()
            assertEquals(InterruptedSetupFinding.ABORT_REQUESTED, report.finding)
            assertEquals(credential, report.credentialStage); assertEquals(dataStage(data), report.dataStage)
            assertEquals(work, report.workStage)
            assertEquals(SessionRecoveryNextStep.PRESERVE_FOR_REPAIR, report.nextStep); f.unchanged()
        }
    }

    @Test fun unconfirmedAbortedWorkIsAMismatchWhileConfirmedObservationGrantsNoCleanup() = runTest {
        for (confirmed in listOf(false, true)) {
            val f = Fixture(confirmed).apply {
                stage(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_NONEMPTY, SessionWorkOriginPlanStatus.ABORTED)
            }
            val report = f.inspect()
            if (confirmed) {
                assertEquals(InterruptedSetupFinding.ABORT_REQUESTED, report.finding)
                assertEquals(SessionWorkOriginPlanStatus.ABORTED, report.workStage)
                assertEquals(SessionRecoveryNextStep.PRESERVE_FOR_REPAIR, report.nextStep)
            } else mismatch(report, InterruptedSetupComponent.WORK_METADATA)
            f.unchanged()
        }
    }

    @Test fun repeatedInspectionsNeverConfirmAbortAcknowledgeControlOrMintAuthority() = runTest {
        for (abort in listOf(false, true)) {
            val f = Fixture(abort).apply { bound() }
            repeat(3) {
                assertEquals(if (abort) InterruptedSetupFinding.ABORT_REQUESTED else InterruptedSetupFinding.UNCONFIRMED_SETUP,
                    f.inspect().finding)
            }
            assertEquals(6, f.count("binding")); f.unchanged()
        }
    }

    @Test fun idleAndCompleteAreNoneWithoutLowerResourceInspection() = runTest {
        for (state in listOf(RetirementState.Idle, RetirementState.Complete(OPERATION), RetirementState.Complete(OTHER_ID))) {
            val f = Fixture().apply { setControl(state) }
            val report = f.inspect()
            assertEquals(InterruptedSetupFinding.NONE, report.finding)
            assertEquals(SessionRecoveryNextStep.RECHECK_STARTUP, report.nextStep)
            noStages(report); f.noLowerEffects(); f.unchanged()
        }
    }

    @Test fun legacyCredentialPendingNeverExtractsOrObservesCompositeResources() = runTest {
        for (abort in listOf(false, true)) {
            val f = Fixture().apply { setControl(RetirementState.PendingCreate(credentialPlan(), abort)) }
            val report = f.inspect()
            assertEquals(InterruptedSetupFinding.OTHER_CONTROL_PENDING, report.finding)
            noStages(report); f.noLowerEffects(); f.unchanged()
        }
    }

    @Test fun ordinaryRetirementAndEmptyDiscardWinBeforeAnyNativeSetupRead() = runTest {
        val states = listOf(
            RetirementState.Pending(OPERATION, SCOPE, ORIGIN, CREDENTIAL, target(), emptySet()),
            RetirementState.SetupDiscardPending(OPERATION, SCOPE, null, CREDENTIAL, null, emptySet()),
        )
        for (state in states) {
            val f = Fixture().apply { setControl(state) }
            val report = f.inspect()
            assertEquals(InterruptedSetupFinding.RETIREMENT_PENDING, report.finding)
            assertEquals(SessionRecoveryNextStep.RETRY_EXISTING_RETIREMENT, report.nextStep)
            noStages(report); f.noLowerEffects(); f.unchanged()
        }
    }

    @Test fun configurationDisagreementIsDetectedBeforeAnyLowerNativeRead() = runTest {
        val f = Fixture()
        val report = f.inspect(configuration = "e".repeat(64))
        assertEquals(InterruptedSetupFinding.CONFIGURATION_CHANGED, report.finding)
        noStages(report); f.noLowerEffects(); f.unchanged()
    }

    @Test fun missingMalformedAndUnreadableControlNeverBecomeNoneOrInitializeState() = runTest {
        for (raw in listOf<String?>(null, "private-control-corruption", "{}", "{\"version\":1,\"version\":1,\"state\":\"idle\"}", " ".repeat(32_769))) {
            val f = Fixture().apply { control = raw?.let { SessionControlRecord(11, bytes(it)) } }
            val report = f.inspect()
            assertEquals(InterruptedSetupFinding.EVIDENCE_UNAVAILABLE, report.finding)
            assertEquals(InterruptedSetupComponent.CONTROL, report.component)
            assertEquals(if (raw == null) FailureReason.STORAGE_FAILURE else FailureReason.INVALID_DATA, report.failureReason)
            noStages(report); f.noLowerEffects(); assertEquals(0, f.writes)
        }
        val f = Fixture().apply { failures["control1"] = FailureReason.UNAVAILABLE }
        assertEquals(FailureReason.UNAVAILABLE, f.inspect().failureReason); f.noLowerEffects(); f.unchanged()
    }

    @Test fun missingNativeCapabilityAndStorageFailuresStayTypedAndHaveNoFallback() = runTest {
        for (part in listOf("credential", "data", "binding", "work"))
            for (reason in listOf(FailureReason.NOT_CONFIGURED, FailureReason.STORAGE_FAILURE, FailureReason.UNAVAILABLE)) {
                val f = Fixture().apply { bound(); failures["${part}1"] = reason }
                val report = f.inspect()
                assertEquals(InterruptedSetupFinding.EVIDENCE_UNAVAILABLE, report.finding)
                assertEquals(component(part), report.component); assertEquals(reason, report.failureReason)
                noStages(report); f.unchanged()
            }
    }

    @Test fun malformedForeignAndStaleNativeProofFailuresAreResourceMismatches() = runTest {
        for (part in listOf("credential", "data", "binding", "work"))
            for (reason in listOf(FailureReason.INVALID_DATA, FailureReason.CONFLICT, FailureReason.STALE_SESSION)) {
                val f = Fixture().apply { bound(); failures["${part}1"] = reason }
                val report = f.inspect()
                assertEquals(InterruptedSetupFinding.RESOURCE_MISMATCH, report.finding)
                assertEquals(component(part), report.component); assertEquals(reason, report.failureReason)
                noStages(report); f.unchanged()
            }
    }

    @Test fun nativeExceptionsCannotEscapeIntoDiagnosticTextOrBecomeSuccess() = runTest {
        for (part in listOf("control", "credential", "data", "binding", "work")) {
            val f = Fixture().apply { bound(); throwsAt = "${part}1" }
            val report = f.inspect()
            assertEquals(InterruptedSetupFinding.EVIDENCE_UNAVAILABLE, report.finding)
            assertEquals(component(part), report.component); assertEquals(FailureReason.STORAGE_FAILURE, report.failureReason)
            assertFalse(report.toString().contains("private-exception-secret")); noStages(report); f.unchanged()
        }
    }

    @Test fun sameCredentialStatusWithChangedFingerprintIsChangedEvidence() = runTest {
        val f = Fixture().apply { before = { if (it == "credential2") credential = CredentialCreatePlanObservation(credential.status, fingerprint(9)) } }
        changed(f.inspect(), InterruptedSetupComponent.CREDENTIAL_METADATA); f.unchanged()
    }

    @Test fun sameDataStatusWithChangedFingerprintIsChangedEvidence() = runTest {
        val f = Fixture().apply { before = { if (it == "data2") data = StateActivationPlanObservation(data.status, fingerprint(9)) } }
        changed(f.inspect(), InterruptedSetupComponent.PRIVATE_DATA); f.unchanged()
    }

    @Test fun sameWorkStatusWithChangedRevisionIsChangedEvidence() = runTest {
        val f = Fixture().apply { before = { if (it == "work2") work = SessionWorkOriginObservation(work.status, work.revision + 1) } }
        changed(f.inspect(), InterruptedSetupComponent.WORK_METADATA); f.unchanged()
    }

    @Test fun changedResourceStatusCannotBeHiddenBehindUnchangedFingerprintOrRevision() = runTest {
        for (part in listOf("credential", "data", "work")) {
            val f = Fixture().apply { before = { if (it == "${part}2") when (part) {
                "credential" -> credential = CredentialCreatePlanObservation(CredentialCreateRecoveryStatus.PARTIAL, credential.fingerprint)
                "data" -> data = StateActivationPlanObservation(StateActivationStatus.PARTIAL, data.fingerprint)
                else -> work = SessionWorkOriginObservation(SessionWorkOriginPlanStatus.SELECTED, work.revision)
            } } }
            changed(f.inspect(), component(part)); f.unchanged()
        }
    }

    @Test fun byteIdenticalBindingAtChangedRevisionInvalidatesTheWholeObservation() = runTest {
        val f = Fixture().apply {
            bound()
            before = { if (it == "binding2") binding = StateRecordInspection(binding.target,
                binding.record!!.let { record -> PrivateRecord(record.revision + 1, record.schemaVersion, record.payload) }) }
        }
        changed(f.inspect(), InterruptedSetupComponent.PRIVATE_BINDING); f.unchanged()
    }

    @Test fun nonemptyDataRequiresPresentTargetAndReservedSchemaTwoBinding() = runTest {
        val bad = listOf(StateRecordInspection(null, boundRecord()), StateRecordInspection(target(), null),
            StateRecordInspection(target(), PrivateRecord(1, 1, boundPayload())),
            StateRecordInspection(target(), PrivateRecord(1, 3, boundPayload())))
        for (inspection in bad) {
            val f = Fixture().apply { bound(); binding = inspection }
            mismatch(f.inspect(), InterruptedSetupComponent.PRIVATE_BINDING); f.unchanged()
        }
    }

    @Test fun fullCanonicalBindingFieldsMustMatchTheOriginalCompositeIntent() = runTest {
        val originals = Json.parseToJsonElement(boundPayload().copyForCodec().decodeToString()).jsonObject
        val changes = mapOf(
            "scope" to buildJsonObject { put("environment", SCOPE.environment); put("actorKind", "GUEST"); put("actorId", SCOPE.actorId) },
            "credentialIncarnation" to JsonPrimitive(OTHER_ID), "originBinding" to JsonPrimitive(OTHER_ID),
            "configurationBinding" to JsonPrimitive("e".repeat(64)), "setupOperationId" to JsonPrimitive(OTHER_ID),
            "dataTarget" to JsonPrimitive("17".repeat(StateRetirementTarget.ENCODED_SIZE)),
        )
        for ((key, altered) in changes) {
            val f = Fixture().apply { bound(); binding = StateRecordInspection(target(),
                PrivateRecord(1, 2, bytes(JsonObject(originals + (key to altered)).toString()))) }
            mismatch(f.inspect(), InterruptedSetupComponent.PRIVATE_BINDING); f.unchanged()
        }
    }

    @Test fun semanticallyEquivalentOrLegacyBindingJsonIsNotCanonicalSetupEvidence() = runTest {
        val raw = boundPayload().copyForCodec().decodeToString()
        val parsed = Json.parseToJsonElement(raw).jsonObject
        val variants = listOf(" $raw", JsonObject(parsed.entries.reversed().associate { it.toPair() }).toString(),
            raw.replace("\"version\":2", "\"version\":2,\"version\":2"),
            raw.replace("\"version\":2", "\"version\":2.0"),
            SessionActivationCodec.encode(SessionActivationRecord(SCOPE, CREDENTIAL, ORIGIN, target(), CONFIGURATION)).copyForCodec().decodeToString())
        for (payload in variants) {
            val f = Fixture().apply { bound(); binding = StateRecordInspection(target(), PrivateRecord(1, 2, bytes(payload))) }
            mismatch(f.inspect(), InterruptedSetupComponent.PRIVATE_BINDING); f.unchanged()
        }
    }

    @Test fun changedBindingPayloadOnReverseReadNeverLeaksPreviouslyGoodStages() = runTest {
        val f = Fixture().apply { bound(); before = { if (it == "binding2")
            binding = StateRecordInspection(target(), PrivateRecord(1, 2, bytes("private-replacement"))) } }
        mismatch(f.inspect(), InterruptedSetupComponent.PRIVATE_BINDING); f.unchanged()
    }

    @Test fun changedBindingTargetWithInternallyMatchingPayloadIsStillChangedEvidence() = runTest {
        val f = Fixture().apply { bound(); before = { if (it == "binding2") {
            val replacement = target(23)
            binding = StateRecordInspection(replacement, boundRecord(replacement))
        } } }
        changed(f.inspect(), InterruptedSetupComponent.PRIVATE_BINDING); f.unchanged()
    }

    @Test fun changedControlRevisionOrPayloadWinsOverSuccessAndNativeFailure() = runTest {
        for (nativeFailure in listOf(false, true)) for (revisionOnly in listOf(false, true)) {
            val f = Fixture().apply {
                if (nativeFailure) failures["credential1"] = FailureReason.UNAVAILABLE
                before = { if (it == "control2") control = if (revisionOnly)
                    SessionControlRecord(control!!.revision + 1, control!!.payload)
                    else SessionControlRecord(control!!.revision, RetirementCodec.encode(RetirementState.PendingSetup(PLAN, true))) }
            }
            val report = f.inspect()
            assertEquals(InterruptedSetupFinding.EVIDENCE_CHANGED, report.finding)
            assertEquals(InterruptedSetupComponent.CONTROL, report.component); noStages(report); assertEquals(0, f.writes)
        }
    }

    @Test fun durableRetirementReplacementWinsOverLowerReadFailureAndPriorSetup() = runTest {
        val f = Fixture().apply {
            failures["credential1"] = FailureReason.UNAVAILABLE
            before = { if (it == "control2") setControl(RetirementState.Pending(OPERATION, SCOPE, ORIGIN, CREDENTIAL, target(), emptySet())) }
        }
        val report = f.inspect()
        assertEquals(InterruptedSetupFinding.RETIREMENT_PENDING, report.finding); noStages(report); assertEquals(0, f.writes)
    }

    @Test fun processRetirementLatchWinsAtEveryOuterBracketIncludingUnavailableControl() = runTest {
        for (latch in 1..3) for (unavailable in listOf(false, true)) {
            val f = Fixture().apply {
                if (unavailable) failures["control1"] = FailureReason.UNAVAILABLE
                before = { if (it == "latch$latch") pending = true }
            }
            val report = f.inspect()
            assertEquals(InterruptedSetupFinding.RETIREMENT_PENDING, report.finding)
            noStages(report); if (latch == 1) assertEquals(0, f.count("control")); f.unchanged()
        }
    }

    @Test fun ownerAlreadyStaleFailsBeforeAnyControlOrMetadataCall() = runTest {
        val f = Fixture().apply { current = false }
        assertFailsWith<OwnerChanged> { f.inspect() }
        assertTrue(f.events.isEmpty()); f.unchanged()
    }

    @Test fun lateOwnerChangeAtEveryAwaitEscapesRatherThanReturningOldDiagnostics() = runTest {
        for (point in BOUND_READS) {
            val f = Fixture().apply { bound(); after = { if (it == point) current = false } }
            assertFailsWith<OwnerChanged> { f.inspect() }
            assertEquals(point, f.events.last()); assertEquals(0, f.writes)
        }
    }

    @Test fun noncooperativeCancellationAtEveryAwaitStopsFurtherInspection() = runTest {
        for (point in BOUND_READS) {
            val f = Fixture().apply { bound(); after = { if (it == point) currentCoroutineContext().cancel() } }
            val inspection = async { f.inspect() }
            assertFailsWith<CancellationException> { inspection.await() }
            assertEquals(point, f.events.last()); f.unchanged()
        }
    }

    @Test fun cancellationBeforeEntryOrThrownByNativePortIsNeverFlattenedToEvidenceFailure() = runTest {
        val preCancelled = Fixture()
        val cancelled = async { currentCoroutineContext().cancel(); preCancelled.inspect() }
        assertFailsWith<CancellationException> { cancelled.await() }
        assertTrue(preCancelled.events.isEmpty()); preCancelled.unchanged()
        for (part in listOf("control", "credential", "data", "binding", "work")) {
            val f = Fixture().apply {
                bound()
                this.before = { event -> if (event == "${part}1") throw CancellationException("cancelled native read") }
            }
            assertFailsWith<CancellationException> { f.inspect() }; f.unchanged()
        }
    }

    @Test fun reportsAndObservationsExposeOnlySafeStagesReasonsAndAdvisoryNextSteps() = runTest {
        val f = Fixture().apply { bound() }; val report = f.inspect()
        assertEquals(SessionRecoveryNextStep.PRESERVE_FOR_REPAIR, report.nextStep)
        for (value in listOf(report, f.inspector(), f.credential, f.data, f.work, f.binding))
            for (marker in listOf(SCOPE.actorId, SCOPE.environment, OPERATION, CREDENTIAL, ORIGIN, CONFIGURATION,
                "private-exception-secret", PLAN.copyForStorage().copyForCodec().decodeToString()))
                assertFalse(value.toString().contains(marker))
        for (finding in listOf(InterruptedSetupFinding.EVIDENCE_CHANGED, InterruptedSetupFinding.EVIDENCE_UNAVAILABLE)) {
            val advisory = InterruptedSetupReport(finding)
            assertEquals(SessionRecoveryNextStep.RECHECK_EVIDENCE, advisory.nextStep); noStages(advisory)
        }
        f.unchanged()
    }

    private class Fixture(abort: Boolean = false) : InterruptedSetupResources, SessionControlStore {
        var control: SessionControlRecord? = SessionControlRecord(11, RetirementCodec.encode(RetirementState.PendingSetup(PLAN, abort)))
        private var baseline = control!!
        var credential = CredentialCreatePlanObservation(CredentialCreateRecoveryStatus.PREPARED, fingerprint(1))
        var data = StateActivationPlanObservation(StateActivationStatus.PREPARED, fingerprint(2))
        var work = SessionWorkOriginObservation(SessionWorkOriginPlanStatus.PREPARED, 7)
        var binding = StateRecordInspection(target(), boundRecord())
        var current = true; var pending = false; var writes = 0
        var before: suspend (String) -> Unit = {}; var after: suspend (String) -> Unit = {}
        var throwsAt: String? = null
        val failures = mutableMapOf<String, FailureReason>()
        val events = mutableListOf<String>(); private val calls = mutableMapOf<String, Int>()
        fun count(name: String) = calls[name] ?: 0
        fun inspector(configuration: String = CONFIGURATION) = InterruptedSetupInspector(this, this, configuration,
            processPending = { call("latch") { pending }.let { assertIs<PortResult.Value<Boolean>>(it).value } },
            checkCurrent = { if (!current) throw OwnerChanged() })
        suspend fun inspect(configuration: String = CONFIGURATION) = inspector(configuration).inspect()
        fun bound() = stage(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_NONEMPTY, SessionWorkOriginPlanStatus.SEALED)
        fun stage(credentialStatus: CredentialCreateRecoveryStatus, dataStatus: StateActivationStatus, workStatus: SessionWorkOriginPlanStatus) {
            credential = CredentialCreatePlanObservation(credentialStatus, fingerprint(1))
            data = StateActivationPlanObservation(dataStatus, fingerprint(2))
            work = SessionWorkOriginObservation(workStatus, if (workStatus == SessionWorkOriginPlanStatus.PREPARED) 7 else 9)
        }
        fun setControl(state: RetirementState) { control = SessionControlRecord(11, RetirementCodec.encode(state)); baseline = control!! }
        fun unchanged() { assertEquals(0, writes); assertEquals(baseline.revision, control!!.revision); assertBytes(baseline.payload, control!!.payload) }
        fun noLowerEffects() { for (name in listOf("credential", "data", "binding", "work")) assertEquals(0, count(name)) }
        override suspend fun read() = call("control") { control?.let { SessionControlRecord(it.revision, PrivateBytes(it.payload.copyForCodec())) } }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            writes++; return PortResult.Failure(FailureReason.FORBIDDEN)
        }
        override suspend fun credentials(scope: StorageScope, plan: CredentialCreatePlan) = call("credential") {
            assertEquals(SCOPE, scope); assertBytes(credentialPlan().copyForStorage(), plan.copyForStorage()); credential
        }
        override suspend fun data(scope: StorageScope, plan: StateActivationPlan) = call("data") {
            assertEquals(SCOPE, scope); assertContentEquals(dataPlan().copyForStorage(), plan.copyForStorage()); data
        }
        override suspend fun binding(scope: StorageScope, plan: StateActivationPlan) = call("binding") {
            assertEquals(SCOPE, scope); assertContentEquals(dataPlan().copyForStorage(), plan.copyForStorage()); binding
        }
        override suspend fun work(plan: SessionWorkOriginPlan) = call("work") {
            assertBytes(workPlan().copyForStorage(), plan.copyForStorage()); work
        }
        private suspend fun <T> call(name: String, read: () -> T): PortResult<T> {
            val occurrence = count(name) + 1; calls[name] = occurrence
            val event = "$name$occurrence"; events += event; before(event)
            if (event == throwsAt) error("private-exception-secret")
            val result = failures[event]?.let { PortResult.Failure(it) } ?: PortResult.Value(read())
            after(event); return result
        }
    }

    private class OwnerChanged : Exception("Owner changed")
    companion object {
        private val SCOPE = StorageScope("interrupted-inspector-test", ActorKind.ACCOUNT, "private-inspector-owner")
        private val CONFIGURATION = "d".repeat(64)
        private const val OPERATION = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        private const val CREDENTIAL = "11111111-2222-4333-8444-555555555555"
        private const val ORIGIN = "22222222-3333-4444-8555-666666666666"
        private const val OTHER_ID = "33333333-4444-4555-8666-777777777777"
        private val BOUND_READS = listOf("latch1", "control1", "credential1", "data1", "binding1", "work1",
            "work2", "data2", "binding2", "credential2", "latch2", "control2", "latch3")
        private fun credentialPlan() = CredentialCreatePlan.create(CredentialCreatePlanRecord(3, CREDENTIAL,
            "a".repeat(64), "b".repeat(64), "c".repeat(64)))
        private fun workPlan() = SessionWorkOriginPlan.create(SessionWorkOriginPlanRecord(7, SCOPE, ORIGIN, PrivateBytes(ByteArray(64))))
        private fun dataPlan() = StateActivationPlan(ByteArray(170).apply {
            this[0] = 1
            for (index in 2..65) this[index] = 'a'.code.toByte()
            for (index in 106..137) this[index] = 'b'.code.toByte()
        })
        private val PLAN = SessionSetupPlan.create(SessionSetupPlanRecord(OPERATION, SCOPE, CONFIGURATION, credentialPlan(), dataPlan(), workPlan()))
        private fun target(byte: Int = 17) = StateRetirementTarget(ByteArray(StateRetirementTarget.ENCODED_SIZE) { byte.toByte() })
        private fun boundPayload(target: StateRetirementTarget = target()) = SessionActivationCodec.encode(
            SessionActivationRecord(SCOPE, CREDENTIAL, ORIGIN, target, CONFIGURATION, OPERATION))
        private fun boundRecord(target: StateRetirementTarget = target()) = PrivateRecord(1, 2, boundPayload(target))
        private fun fingerprint(byte: Int) = PrivateBytes(ByteArray(32) { byte.toByte() })
        private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
        private fun assertBytes(a: PrivateBytes, b: PrivateBytes) = assertContentEquals(a.copyForCodec(), b.copyForCodec())
        private fun component(name: String) = when (name) {
            "control" -> InterruptedSetupComponent.CONTROL
            "credential" -> InterruptedSetupComponent.CREDENTIAL_METADATA
            "data" -> InterruptedSetupComponent.PRIVATE_DATA
            "binding" -> InterruptedSetupComponent.PRIVATE_BINDING
            else -> InterruptedSetupComponent.WORK_METADATA
        }
        private fun dataStage(status: StateActivationStatus) = when (status) {
            StateActivationStatus.PREPARED -> InterruptedSetupDataStage.PREPARED
            StateActivationStatus.PARTIAL -> InterruptedSetupDataStage.PARTIAL
            StateActivationStatus.SELECTED_EMPTY -> InterruptedSetupDataStage.SELECTED_EMPTY
            StateActivationStatus.SELECTED_NONEMPTY -> InterruptedSetupDataStage.BOUND
            StateActivationStatus.ABORTING -> InterruptedSetupDataStage.ABORTING
            StateActivationStatus.ABORTED -> InterruptedSetupDataStage.ABORTED
        }
        private fun noStages(report: InterruptedSetupReport) { assertNull(report.credentialStage); assertNull(report.dataStage); assertNull(report.workStage) }
        private fun mismatch(report: InterruptedSetupReport, part: InterruptedSetupComponent) {
            assertEquals(InterruptedSetupFinding.RESOURCE_MISMATCH, report.finding); assertEquals(part, report.component)
            assertEquals(FailureReason.CONFLICT, report.failureReason); noStages(report)
        }
        private fun changed(report: InterruptedSetupReport, part: InterruptedSetupComponent) {
            assertEquals(InterruptedSetupFinding.EVIDENCE_CHANGED, report.finding); assertEquals(part, report.component)
            assertEquals(FailureReason.CONFLICT, report.failureReason); noStages(report)
        }
    }
}
