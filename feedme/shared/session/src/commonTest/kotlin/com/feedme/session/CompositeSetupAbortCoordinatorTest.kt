package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Explicit protocol fakes only. No key, file, native MAC, provider or physical durability claim. */
class CompositeSetupAbortCoordinatorTest {
    @Test fun prepareIsReadOnlyRedactedAndDoesNotInferConfirmationFromCoherentNativeMetadata() = runTest {
        val f = Fixture().apply { bound() }
        val before = f.record!!
        val prepared = ok(f.coordinator().prepare())
        assertEquals(0, f.writes); assertTrue(f.effects.isEmpty()); same(before.payload, f.record!!.payload)
        assertEquals(before.revision, f.record!!.revision)
        for (privateValue in listOf(SCOPE.environment, SCOPE.actorId, CONFIGURATION, OPERATION, INCARNATION, ORIGIN,
            PLAN.copyForStorage().copyForCodec().decodeToString(), "private-token")) assertFalse(prepared.toString().contains(privateValue))
        rejected(FailureReason.CONFLICT, f.coordinator().retry())
        assertFalse(f.pending().abortRequested); assertTrue(f.effects.isEmpty()); assertEquals(0, f.writes)
    }

    @Test fun everyReachableUnconfirmedStageCanBePreparedWithoutAllocatingOrSelectingAnything() = runTest {
        for ((credential, data, work) in LIVE_STAGES) {
            val f = Fixture().apply { stage(credential, data, work) }
            ok(f.coordinator().prepare())
            assertTrue(f.effects.isEmpty()); assertEquals(0, f.writes)
            assertEquals(credential, f.credential.status); assertEquals(data, f.data.status); assertEquals(work, f.work.status)
        }
    }

    @Test fun noPendingMissingMalformedAndLegacyControlNeverBecomeAbortProposals() = runTest {
        for (state in listOf(RetirementState.Idle, RetirementState.Complete(OPERATION),
            RetirementState.PendingCreate(credentialPlan(), false),
            RetirementState.Pending(OPERATION, SCOPE, ORIGIN, INCARNATION, target(), emptySet()))) {
            val f = Fixture().apply { set(state) }
            assertIs<PortResult.Failure>(f.coordinator().prepare())
            assertEquals(0, f.count("credential")); assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
        }
        for (payload in listOf<PrivateBytes?>(null, bytes("private-malformed-control"))) {
            val f = Fixture().apply { record = payload?.let { SessionControlRecord(11, it) } }
            assertIs<PortResult.Failure>(f.coordinator().prepare())
            assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
        }
    }

    @Test fun mismatchedConfigurationAndUnavailableNativeAbortCapabilityNeverWriteConfirmation() = runTest {
        for (configurationMismatch in listOf(false, true)) {
            val f = Fixture().apply { if (!configurationMismatch) available = false }
            assertIs<PortResult.Failure>(f.coordinator(configuration = if (configurationMismatch) "e".repeat(64) else CONFIGURATION).prepare())
            assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
        }
        val f = Fixture(); val c = f.coordinator(); val proposal = ok(c.prepare()); f.available = false
        rejected(FailureReason.NOT_CONFIGURED, c.confirm(proposal)); assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
    }

    @Test fun proposalOwnerGenerationAndConfigurationAreExactAndNotTransferable() = runTest {
        for (changed in listOf("owner", "generation", "configuration")) {
            val f = Fixture(); val proposal = ok(f.coordinator().prepare()); val before = f.events.size
            val other = f.coordinator(owner = if (changed == "owner") Any() else f.owner,
                generation = if (changed == "generation") Any() else f.generation,
                configuration = if (changed == "configuration") "e".repeat(64) else CONFIGURATION)
            assertIs<PortResult.Failure>(other.confirm(proposal))
            assertEquals(before, f.events.size); assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
        }
    }

    @Test fun aFreshCoordinatorForTheSameRuntimeOwnerAndGenerationCanValidateTheExactProposal() = runTest {
        val f = Fixture().apply { bound() }
        val proposal = ok(f.coordinator().prepare())
        ok(f.coordinator().confirm(proposal)); f.completed()
    }

    @Test fun newerControlRevisionWithIdenticalPayloadInvalidatesTheOldProposal() = runTest {
        val f = Fixture(); val c = f.coordinator(); val prepared = ok(c.prepare())
        f.record = SessionControlRecord(f.record!!.revision + 1, f.record!!.payload)
        rejected(FailureReason.CONFLICT, c.confirm(prepared))
        assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
    }

    @Test fun changedFingerprintsWorkRevisionOrBindingCannotBeHiddenBehindSamePublicStages() = runTest {
        for (changed in listOf("credential", "data", "work", "binding-revision", "binding-payload", "binding-target")) {
            val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
            when (changed) {
                "credential" -> f.credential = CredentialCreatePlanObservation(f.credential.status, fingerprint(31))
                "data" -> f.data = StateActivationPlanObservation(f.data.status, fingerprint(32))
                "work" -> f.work = SessionWorkOriginObservation(f.work.status, f.work.revision + 1)
                "binding-revision" -> f.binding = StateRecordInspection(target(), boundRecord().copy(revision = 2))
                "binding-payload" -> f.binding = StateRecordInspection(target(), boundRecord().copy(payload = bytes("wrong-body")))
                else -> f.binding = StateRecordInspection(target(19), boundRecord(target(19)))
            }
            assertIs<PortResult.Failure>(c.confirm(prepared), changed)
            assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
        }
    }

    @Test fun evidenceChangedWithinPreparationNeverEscapesAsAProposal() = runTest {
        for (changed in listOf("credential", "data", "work", "binding", "control")) {
            val f = Fixture().apply { bound() }
            f.after = { event -> if (event == "work1") when (changed) {
                "credential" -> f.credential = CredentialCreatePlanObservation(f.credential.status, fingerprint(21))
                "data" -> f.data = StateActivationPlanObservation(f.data.status, fingerprint(22))
                "work" -> f.work = SessionWorkOriginObservation(f.work.status, f.work.revision + 1)
                "binding" -> f.binding = StateRecordInspection(target(), boundRecord().copy(revision = 2))
                else -> f.record = SessionControlRecord(f.record!!.revision + 1, f.record!!.payload)
            } }
            assertIs<PortResult.Failure>(f.coordinator().prepare(), changed)
            assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
        }
    }

    @Test fun extraDomainRowsTombstonesAndUnavailablePrivateKeysHaveNoEmptyOnlyFallback() = runTest {
        for (reason in listOf(FailureReason.CONFLICT, FailureReason.STORAGE_FAILURE)) {
            val f = Fixture().apply { bound(); failures["binding1"] = reason }
            assertIs<PortResult.Failure>(f.coordinator().prepare())
            assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
        }
    }

    @Test fun soleBindingMustMatchEveryCanonicalOriginalSetupDimensionAndSchemaTwo() = runTest {
        for (changed in listOf("scope", "incarnation", "origin", "target", "configuration", "operation", "schema", "whitespace", "missing")) {
            val f = Fixture().apply { bound() }
            val payload = when (changed) {
                "scope" -> SessionActivationCodec.encode(SessionActivationRecord(SCOPE.copy(actorId = "other"), INCARNATION, ORIGIN, target(), CONFIGURATION, OPERATION))
                "incarnation" -> SessionActivationCodec.encode(SessionActivationRecord(SCOPE, OTHER_ID, ORIGIN, target(), CONFIGURATION, OPERATION))
                "origin" -> SessionActivationCodec.encode(SessionActivationRecord(SCOPE, INCARNATION, OTHER_ID, target(), CONFIGURATION, OPERATION))
                "target" -> boundPayload(target(19))
                "configuration" -> SessionActivationCodec.encode(SessionActivationRecord(SCOPE, INCARNATION, ORIGIN, target(), "e".repeat(64), OPERATION))
                "operation" -> SessionActivationCodec.encode(SessionActivationRecord(SCOPE, INCARNATION, ORIGIN, target(), CONFIGURATION, OTHER_ID))
                "whitespace" -> bytes(" " + boundPayload().copyForCodec().decodeToString())
                else -> boundPayload()
            }
            f.binding = StateRecordInspection(target(), if (changed == "missing") null else PrivateRecord(1, if (changed == "schema") 1 else 2, payload))
            assertIs<PortResult.Failure>(f.coordinator().prepare(), changed)
            assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
        }
    }

    @Test fun contradictoryUnconfirmedResourceStagesCannotAuthorizeConfirmation() = runTest {
        for ((credential, data, work) in listOf(
            Triple(CredentialCreateRecoveryStatus.PREPARED, StateActivationStatus.SELECTED_EMPTY, SessionWorkOriginPlanStatus.PREPARED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.PREPARED, SessionWorkOriginPlanStatus.SEALED),
            Triple(CredentialCreateRecoveryStatus.ABORTED, StateActivationStatus.PREPARED, SessionWorkOriginPlanStatus.PREPARED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_NONEMPTY, SessionWorkOriginPlanStatus.ABORTED))) {
            val f = Fixture().apply { stage(credential, data, work) }
            assertIs<PortResult.Failure>(f.coordinator().prepare())
            assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
        }
    }

    @Test fun confirmationAcknowledgesExactIntentThenAbortsWorkDataCredentialsAndCompletesOriginalOperation() = runTest {
        val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
        ok(c.confirm(prepared))
        assertEquals(listOf("work", "data", "credential"), f.effects)
        assertTrue(f.writtenPayloads.first().let { (RetirementCodec.decode(it) as RetirementState.PendingSetup).abortRequested })
        same(PLAN.copyForStorage(), (RetirementCodec.decode(f.writtenPayloads.first()) as RetirementState.PendingSetup).plan.copyForStorage())
        same(boundPayload(), f.dataBodies.single()!!); f.completed()
    }

    @Test fun unboundSetupPassesNullExpectedBindingRatherThanInventingAPrivateRecord() = runTest {
        for ((credential, data, work) in LIVE_STAGES.filter { it.second != StateActivationStatus.SELECTED_NONEMPTY }) {
            val f = Fixture().apply { stage(credential, data, work) }; val c = f.coordinator()
            ok(c.confirm(ok(c.prepare()))); assertEquals(listOf<PrivateBytes?>(null), f.dataBodies); f.completed()
        }
    }

    @Test fun failureBeforeOrAfterInitialConfirmationCasNeverStartsAnyNativeAbort() = runTest {
        for (after in listOf(false, true)) for (reason in listOf(FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN)) {
            val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
            if (after) f.afterCasFailures[1] = reason else f.beforeCasFailures[1] = reason
            rejected(reason, c.confirm(prepared)); assertTrue(f.effects.isEmpty())
            assertEquals(after, f.pending().abortRequested)
            if (!after) { rejected(FailureReason.CONFLICT, f.coordinator().retry()); assertEquals(1, f.writes) }
        }
    }

    @Test fun visibleUnknownConfirmationCanBeRetriedOnlyWithAFreshAcknowledgement() = runTest {
        val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
        f.afterCasFailures[1] = FailureReason.OUTCOME_UNKNOWN
        rejected(FailureReason.OUTCOME_UNKNOWN, c.confirm(prepared)); val old = f.record!!
        f.beforeEffect = { assertTrue(f.record!!.revision > old.revision); assertEquals(f.record!!.revision, f.acknowledged) }
        ok(f.coordinator().retry()); f.completed(); assertEquals(listOf("work", "data", "credential"), f.effects)
    }

    @Test fun malformedSuccessfulConfirmationReceiptNeverAuthorizesAnAbort() = runTest {
        for (kind in listOf("revision", "payload")) {
            val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
            f.receiptTransform = { record -> if (kind == "revision") SessionControlRecord(record.revision - 1, record.payload)
                else SessionControlRecord(record.revision, RetirementCodec.encode(RetirementState.Idle)) }
            rejected(FailureReason.STORAGE_FAILURE, c.confirm(prepared)); assertTrue(f.effects.isEmpty()); assertTrue(f.pending().abortRequested)
        }
    }

    @Test fun successfulCasNeedsExactReadbackBeforeConfirmationCanAuthorizeEffects() = runTest {
        for (kind in listOf("missing", "older", "newer", "wrong-body", "unavailable")) {
            val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare()); val original = f.record!!
            f.afterCas = { index, _ -> if (index == 1) when (kind) {
                "missing" -> f.record = null
                "older" -> f.record = original
                "newer" -> f.record = SessionControlRecord(f.record!!.revision + 1, f.record!!.payload)
                "wrong-body" -> f.record = SessionControlRecord(f.record!!.revision, RetirementCodec.encode(RetirementState.Idle))
                else -> f.readFailure = FailureReason.STORAGE_FAILURE
            } }
            assertIs<PortResult.Failure>(c.confirm(prepared)); assertTrue(f.effects.isEmpty())
        }
    }

    @Test fun eachFailedNativeAbortStopsLaterEffectsAndRetainsExactConfirmedPlan() = runTest {
        for ((index, part) in listOf("work", "data", "credential").withIndex()) {
            val f = Fixture().apply { bound(); effectFailures[part] = FailureReason.UNAVAILABLE }; val c = f.coordinator()
            rejected(FailureReason.UNAVAILABLE, c.confirm(ok(c.prepare())))
            assertEquals(listOf("work", "data", "credential").take(index + 1), f.effects)
            assertTrue(f.pending().abortRequested); same(PLAN.copyForStorage(), f.pending().plan.copyForStorage())
        }
    }

    @Test fun failedAbortAcknowledgementIsNotPromotedEvenWhenAbortedMetadataAlreadyBecameVisible() = runTest {
        for (part in listOf("work", "data", "credential")) {
            val f = Fixture().apply { bound(); afterEffectFailures[part] = FailureReason.OUTCOME_UNKNOWN }; val c = f.coordinator()
            rejected(FailureReason.OUTCOME_UNKNOWN, c.confirm(ok(c.prepare())))
            assertTrue(f.pending().abortRequested); val before = f.record!!.revision
            f.afterEffectFailures.clear(); f.effects.clear()
            f.beforeEffect = { assertTrue(f.record!!.revision > before) }
            ok(f.coordinator().retry()); assertEquals(listOf("work", "data", "credential"), f.effects); f.completed()
        }
    }

    @Test fun alreadyAbortedResourcesAreAllReacknowledgedInsteadOfSkippedOnConfirmedRetry() = runTest {
        val f = Fixture(confirmed = true).apply {
            stage(CredentialCreateRecoveryStatus.ABORTED, StateActivationStatus.ABORTED, SessionWorkOriginPlanStatus.ABORTED)
        }
        ok(f.coordinator().retry()); assertEquals(listOf("work", "data", "credential"), f.effects)
        assertEquals(listOf<PrivateBytes?>(null), f.dataBodies); f.completed()
    }

    @Test fun successfulPortResultWithoutAbortedMetadataCannotAdvanceToAnotherResource() = runTest {
        for ((index, part) in listOf("work", "data", "credential").withIndex()) {
            val f = Fixture().apply { bound(); noTransition += part }; val c = f.coordinator()
            assertIs<PortResult.Failure>(c.confirm(ok(c.prepare())))
            assertEquals(listOf("work", "data", "credential").take(index + 1), f.effects)
            assertTrue(f.pending().abortRequested)
        }
    }

    @Test fun changedControlAfterAnEffectCannotAuthorizeTheNextResourceOrCompleteAnotherOperation() = runTest {
        for ((index, part) in listOf("work", "data", "credential").withIndex()) {
            val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
            f.afterEffect = { if (it == part) f.record = SessionControlRecord(f.record!!.revision + 1,
                RetirementCodec.encode(RetirementState.Complete(OTHER_ID))) }
            assertIs<PortResult.Failure>(c.confirm(prepared))
            assertEquals(listOf("work", "data", "credential").take(index + 1), f.effects)
            assertEquals(OTHER_ID, (RetirementCodec.decode(f.record!!.payload) as RetirementState.Complete).operationId)
        }
    }

    @Test fun failedFinalCompleteWriteRetainsConfirmedIntentAndRetryReacknowledgesAllThreeResources() = runTest {
        val f = Fixture().apply { bound(); failCompletionBefore = FailureReason.STORAGE_FAILURE }; val c = f.coordinator()
        rejected(FailureReason.STORAGE_FAILURE, c.confirm(ok(c.prepare())))
        assertTrue(f.pending().abortRequested)
        assertEquals(CredentialCreateRecoveryStatus.ABORTED, f.credential.status)
        assertEquals(StateActivationStatus.ABORTED, f.data.status); assertEquals(SessionWorkOriginPlanStatus.ABORTED, f.work.status)
        f.failCompletionBefore = null; f.effects.clear(); ok(f.coordinator().retry())
        assertEquals(listOf("work", "data", "credential"), f.effects); f.completed()
    }

    @Test fun confirmedIntentCannotBecomeAnotherProposalAndRetryPreservesItsExactRawBytes() = runTest {
        val f = Fixture(confirmed = true).apply { bound() }
        val raw = bytes(" \n" + f.record!!.payload.copyForCodec().decodeToString() + "\n ")
        f.record = SessionControlRecord(f.record!!.revision, raw)
        rejected(FailureReason.CONFLICT, f.coordinator().prepare()); assertEquals(0, f.writes)
        ok(f.coordinator().retry())
        same(raw, f.writtenPayloads.first()); f.completed()
    }

    @Test fun confirmedPartialCleanupMustRespectWorkThenDataThenCredentialOrder() = runTest {
        for ((credential, data, work) in listOf(
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.ABORTING, SessionWorkOriginPlanStatus.SEALED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.ABORTED, SessionWorkOriginPlanStatus.SELECTED),
            Triple(CredentialCreateRecoveryStatus.ABORTING, StateActivationStatus.SELECTED_EMPTY, SessionWorkOriginPlanStatus.ABORTED),
            Triple(CredentialCreateRecoveryStatus.ABORTED, StateActivationStatus.ABORTING, SessionWorkOriginPlanStatus.ABORTED))) {
            val f = Fixture(confirmed = true).apply { stage(credential, data, work) }
            rejected(FailureReason.CONFLICT, f.coordinator().retry())
            assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
        }
        for ((credential, data, work) in listOf(
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_NONEMPTY, SessionWorkOriginPlanStatus.ABORTED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.ABORTING, SessionWorkOriginPlanStatus.ABORTED),
            Triple(CredentialCreateRecoveryStatus.ABORTING, StateActivationStatus.ABORTED, SessionWorkOriginPlanStatus.ABORTED))) {
            val f = Fixture(confirmed = true).apply { stage(credential, data, work) }
            ok(f.coordinator().retry()); assertEquals(listOf("work", "data", "credential"), f.effects); f.completed()
        }
    }

    @Test fun workAbortRequiresExactlyOneChangedRevisionAndDataAbortRequiresAChangedFingerprint() = runTest {
        for (corruption in listOf("work-same", "work-jump", "data-same")) {
            val f = Fixture().apply { bound() }; val workBefore = f.work; val dataBefore = f.data
            f.afterEffect = { part -> when {
                corruption == "work-same" && part == "work" -> f.work = SessionWorkOriginObservation(SessionWorkOriginPlanStatus.ABORTED, workBefore.revision)
                corruption == "work-jump" && part == "work" -> f.work = SessionWorkOriginObservation(SessionWorkOriginPlanStatus.ABORTED, workBefore.revision + 2)
                corruption == "data-same" && part == "data" -> f.data = StateActivationPlanObservation(StateActivationStatus.ABORTED, dataBefore.fingerprint)
            } }
            val c = f.coordinator(); rejected(FailureReason.CONFLICT, c.confirm(ok(c.prepare())))
            assertEquals(if (corruption == "data-same") listOf("work", "data") else listOf("work"), f.effects)
            assertTrue(f.pending().abortRequested)
        }
    }

    @Test fun untouchedComponentsMustStayExactAfterEachAcknowledgedNativeEffect() = runTest {
        for ((part, changed) in listOf("work" to "data", "work" to "credential", "work" to "binding",
            "data" to "work", "data" to "credential", "credential" to "work", "credential" to "data")) {
            val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
            f.afterEffect = { effect -> if (effect == part) when (changed) {
                "data" -> f.data = StateActivationPlanObservation(f.data.status, fingerprint(77))
                "credential" -> f.credential = CredentialCreatePlanObservation(f.credential.status, fingerprint(78))
                "binding" -> f.binding = StateRecordInspection(target(), boundRecord().copy(revision = 3))
                else -> f.work = SessionWorkOriginObservation(f.work.status, f.work.revision + 1)
            } }
            rejected(FailureReason.CONFLICT, c.confirm(prepared))
            assertEquals(listOf("work", "data", "credential").take(listOf("work", "data", "credential").indexOf(part) + 1), f.effects)
            assertTrue(f.pending().abortRequested)
        }
    }

    @Test fun evidenceChangedDuringConfirmationCasCannotUseTheNewConsentToDeleteChangedResources() = runTest {
        for (part in listOf("credential", "data", "work", "binding")) {
            val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
            f.afterCas = { index, _ -> if (index == 1) when (part) {
                "credential" -> f.credential = CredentialCreatePlanObservation(f.credential.status, fingerprint(81))
                "data" -> f.data = StateActivationPlanObservation(f.data.status, fingerprint(82))
                "work" -> f.work = SessionWorkOriginObservation(f.work.status, f.work.revision + 1)
                else -> f.binding = StateRecordInspection(target(), boundRecord().copy(revision = 2))
            } }
            rejected(FailureReason.CONFLICT, c.confirm(prepared))
            assertTrue(f.pending().abortRequested); assertTrue(f.effects.isEmpty())
        }
    }

    @Test fun lostFinalReceiptRequiresRetainedExactPlanAndFreshCompleteAcknowledgementOnly() = runTest {
        val f = Fixture().apply { bound(); afterCasFailures[2] = FailureReason.OUTCOME_UNKNOWN }; val c = f.coordinator()
        rejected(FailureReason.OUTCOME_UNKNOWN, c.confirm(ok(c.prepare()))); f.completed()
        val unknown = f.record!!; val effects = f.effects.toList(); val writes = f.writes
        rejected(FailureReason.CONFLICT, f.coordinator().retry()); assertEquals(writes, f.writes)
        f.afterCasFailures[3] = FailureReason.OUTCOME_UNKNOWN
        rejected(FailureReason.OUTCOME_UNKNOWN, c.retry()); assertEquals(unknown.revision + 1, f.record!!.revision)
        same(unknown.payload, f.record!!.payload); assertEquals(effects, f.effects)
        ok(c.retry()); assertEquals(unknown.revision + 2, f.record!!.revision)
        same(unknown.payload, f.record!!.payload); assertEquals(effects, f.effects); f.completed()
    }

    @Test fun retainedCompletionCannotFollowChangedComponentEvidenceOrAnotherControlRevision() = runTest {
        for (changed in listOf("credential", "data", "work", "control-revision", "control-operation")) {
            val f = Fixture().apply { bound(); afterCasFailures[2] = FailureReason.OUTCOME_UNKNOWN }; val c = f.coordinator()
            rejected(FailureReason.OUTCOME_UNKNOWN, c.confirm(ok(c.prepare())))
            when (changed) {
                "credential" -> f.credential = CredentialCreatePlanObservation(f.credential.status, fingerprint(71))
                "data" -> f.data = StateActivationPlanObservation(f.data.status, fingerprint(72))
                "work" -> f.work = SessionWorkOriginObservation(f.work.status, f.work.revision + 1)
                "control-revision" -> f.record = SessionControlRecord(f.record!!.revision + 1, f.record!!.payload)
                else -> f.record = SessionControlRecord(f.record!!.revision, RetirementCodec.encode(RetirementState.Complete(OTHER_ID)))
            }
            val writes = f.writes; val effects = f.effects.toList()
            rejected(FailureReason.CONFLICT, c.retry()); assertEquals(writes, f.writes); assertEquals(effects, f.effects)
        }
    }

    @Test fun postCompleteReadbackOrEvidenceFailureCannotReturnSuccessfulCompletion() = runTest {
        for (kind in listOf("readback-unavailable", "readback-old", "changed-evidence")) {
            val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
            f.afterCas = { index, _ -> if (index == 2) when (kind) {
                "readback-unavailable" -> f.readFailure = FailureReason.STORAGE_FAILURE
                "readback-old" -> f.record = SessionControlRecord(f.record!!.revision - 1, f.record!!.payload)
                else -> f.credential = CredentialCreatePlanObservation(CredentialCreateRecoveryStatus.ABORTED, fingerprint(69))
            } }
            assertIs<PortResult.Failure>(c.confirm(prepared))
            assertEquals(listOf("work", "data", "credential"), f.effects); assertEquals(2, f.writes)
        }
    }

    @Test fun processRetirementBeforeOrDuringConfirmationStopsSubsequentCleanup() = runTest {
        val initiallyPending = Fixture().apply { pendingRetirement = true }
        rejected(FailureReason.CONFLICT, initiallyPending.coordinator().prepare())
        assertEquals(0, initiallyPending.count("control")); assertTrue(initiallyPending.effects.isEmpty())
        for (event in listOf("credential3", "cas1", "abort-work", "abort-data", "abort-credential")) {
            val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
            f.after = { if (it == event) f.pendingRetirement = true }
            rejected(FailureReason.CONFLICT, c.confirm(prepared))
            val expected = when (event) { "abort-work" -> 1; "abort-data" -> 2; "abort-credential" -> 3; else -> 0 }
            assertEquals(expected, f.effects.size, event)
            assertFalse(RetirementCodec.decode(f.record!!.payload) is RetirementState.Complete)
        }
    }

    @Test fun unavailableMetadataAfterAnEffectCannotAuthorizeTheNextEffectOrCompletion() = runTest {
        for (part in listOf("work", "data", "credential")) {
            val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
            f.afterEffect = { if (it == part) f.failures["credential${f.count("credential") + 1}"] = FailureReason.STORAGE_FAILURE }
            rejected(FailureReason.STORAGE_FAILURE, c.confirm(prepared))
            assertEquals(listOf("work", "data", "credential").take(listOf("work", "data", "credential").indexOf(part) + 1), f.effects)
            assertTrue(f.pending().abortRequested)
        }
    }

    @Test fun staleOwnerAtEveryPreparationAndConfirmationAwaitStopsWithoutAnotherPortCall() = runTest {
        val baseline = Fixture().apply { bound() }; val bc = baseline.coordinator(); val bp = ok(bc.prepare())
        val preparationCount = baseline.events.size; ok(bc.confirm(bp)); val all = baseline.events.toList()
        for ((index, event) in all.withIndex()) {
            val f = Fixture().apply { bound() }; val c = f.coordinator()
            if (index < preparationCount) {
                f.after = { if (it == event) f.current = false }
                assertFailsWith<OwnerChanged>(event) { c.prepare() }
            } else {
                val proposal = ok(c.prepare()); f.after = { if (it == event) f.current = false }
                assertFailsWith<OwnerChanged>(event) { c.confirm(proposal) }
            }
            assertEquals(all.take(index + 1), f.events, event)
        }
        val f = Fixture().apply { current = false }
        assertFailsWith<OwnerChanged> { f.coordinator().prepare() }; assertTrue(f.events.isEmpty())
    }

    @Test fun callerCancellationAtEveryAwaitSurvivesNoncooperativePortReturnsWithoutMoreCalls() = runTest {
        val baseline = Fixture().apply { bound() }; val bc = baseline.coordinator(); val bp = ok(bc.prepare())
        val preparationCount = baseline.events.size; ok(bc.confirm(bp)); val all = baseline.events.toList()
        for ((index, event) in all.withIndex()) {
            val f = Fixture().apply { bound() }; val c = f.coordinator()
            val proposal = if (index >= preparationCount) ok(c.prepare()) else null
            f.after = { if (it == event) currentCoroutineContext().cancel() }
            val job = async { if (proposal == null) c.prepare() else c.confirm(proposal) }
            assertFailsWith<CancellationException>(event) { job.await() }
            assertEquals(all.take(index + 1), f.events, event)
        }
        val f = Fixture(); val job = async { currentCoroutineContext().cancel(); f.coordinator().prepare() }
        assertFailsWith<CancellationException> { job.await() }; assertTrue(f.events.isEmpty())
    }

    @Test fun nativeAndControlExceptionsAreSanitizedButCancellationStillPropagates() = runTest {
        for (event in listOf("credential3", "data3", "binding3", "work3", "cas1", "abort-work", "abort-data", "abort-credential", "cas2")) {
            val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
            f.before = { if (it == event) throw IllegalStateException("private-token native filename owner") }
            val result = c.confirm(prepared)
            rejected(FailureReason.STORAGE_FAILURE, result); assertFalse(result.toString().contains("private-token"))
            assertEquals(event, f.events.last())
        }
        for (event in listOf("credential3", "cas1", "abort-work", "abort-data", "abort-credential", "cas2")) {
            val f = Fixture().apply { bound() }; val c = f.coordinator(); val prepared = ok(c.prepare())
            f.before = { if (it == event) throw CancellationException("cancelled") }
            assertFailsWith<CancellationException> { c.confirm(prepared) }; assertEquals(event, f.events.last())
        }
    }

    @Test fun staleOrCancelledConfirmedAndCompleteOnlyRetriesCannotContinueToAnotherEffect() = runTest {
        for (complete in listOf(false, true)) for (cancel in listOf(false, true)) {
            val baseline = Fixture().apply { bound(); afterCasFailures[if (complete) 2 else 1] = FailureReason.OUTCOME_UNKNOWN }
            val bc = baseline.coordinator(); rejected(FailureReason.OUTCOME_UNKNOWN, bc.confirm(ok(bc.prepare())))
            val before = baseline.events.size; ok(bc.retry()); val retryEvents = baseline.events.drop(before)
            for (event in retryEvents) {
                val f = Fixture().apply { bound(); afterCasFailures[if (complete) 2 else 1] = FailureReason.OUTCOME_UNKNOWN }
                val c = f.coordinator(); rejected(FailureReason.OUTCOME_UNKNOWN, c.confirm(ok(c.prepare())))
                val oldEvents = f.events.toList()
                f.after = { if (it == event) { if (cancel) currentCoroutineContext().cancel() else f.current = false } }
                if (cancel) { val job = async { c.retry() }; assertFailsWith<CancellationException> { job.await() } }
                else assertFailsWith<OwnerChanged> { c.retry() }
                assertEquals(oldEvents + retryEvents.take(retryEvents.indexOf(event) + 1), f.events, event)
            }
        }
    }

    @Test fun successfulCompletionConsumesRetainedRetryProofAndCannotWriteCompleteAgain() = runTest {
        for (lostReceipt in listOf(false, true)) {
            val f = Fixture().apply { bound(); if (lostReceipt) afterCasFailures[2] = FailureReason.OUTCOME_UNKNOWN }
            val c = f.coordinator(); val proposal = ok(c.prepare())
            if (lostReceipt) { rejected(FailureReason.OUTCOME_UNKNOWN, c.confirm(proposal)); ok(c.retry()) }
            else ok(c.confirm(proposal))
            val writes = f.writes; val effects = f.effects.toList(); val record = f.record!!
            rejected(FailureReason.CONFLICT, c.retry())
            assertEquals(writes, f.writes); assertEquals(effects, f.effects)
            assertEquals(record.revision, f.record!!.revision); same(record.payload, f.record!!.payload)
        }
    }

    @Test fun processLatchExceptionsAreSanitizedWhileOwnerAndCancellationFencesStillWin() = runTest {
        for (duringConfirmation in listOf(false, true)) for (cancel in listOf(false, true)) {
            val f = Fixture().apply { bound() }; val c = f.coordinator()
            val proposal = if (duringConfirmation) ok(c.prepare()) else null
            val event = "latch${f.count("latch") + 1}"
            f.before = { if (it == event) {
                if (cancel) throw CancellationException("cancelled")
                else throw IllegalStateException("private-token private-owner-path")
            } }
            if (cancel) assertFailsWith<CancellationException> { if (proposal == null) c.prepare() else c.confirm(proposal) }
            else {
                val result = if (proposal == null) c.prepare() else c.confirm(proposal)
                rejected(FailureReason.STORAGE_FAILURE, result); assertFalse(result.toString().contains("private-token"))
            }
            assertEquals(event, f.events.last()); assertEquals(0, f.writes); assertTrue(f.effects.isEmpty())
        }
    }

    private class Fixture(confirmed: Boolean = false) : CompositeSetupAbortResources, SessionControlStore {
        val owner = Any(); val generation = Any()
        var record: SessionControlRecord? = SessionControlRecord(11, RetirementCodec.encode(RetirementState.PendingSetup(PLAN, confirmed)))
        var credential = CredentialCreatePlanObservation(CredentialCreateRecoveryStatus.PREPARED, fingerprint(1))
        var data = StateActivationPlanObservation(StateActivationStatus.PREPARED, fingerprint(2))
        var work = SessionWorkOriginObservation(SessionWorkOriginPlanStatus.PREPARED, 7)
        var binding = StateRecordInspection(target(), boundRecord())
        var available = true; override val credentialAbortAvailable get() = available
        var current = true; var pendingRetirement = false
        var before: suspend (String) -> Unit = {}; var after: suspend (String) -> Unit = {}
        var beforeEffect: suspend (String) -> Unit = {}; var afterEffect: suspend (String) -> Unit = {}
        var afterCas: suspend (Int, PrivateBytes) -> Unit = { _, _ -> }
        var receiptTransform: ((SessionControlRecord) -> SessionControlRecord)? = null
        var readFailure: FailureReason? = null; var failCompletionBefore: FailureReason? = null
        val beforeCasFailures = mutableMapOf<Int, FailureReason>(); val afterCasFailures = mutableMapOf<Int, FailureReason>()
        val failures = mutableMapOf<String, FailureReason>(); val effectFailures = mutableMapOf<String, FailureReason>()
        val afterEffectFailures = mutableMapOf<String, FailureReason>(); val noTransition = mutableSetOf<String>()
        val effects = mutableListOf<String>(); val events = mutableListOf<String>(); val writtenPayloads = mutableListOf<PrivateBytes>()
        val dataBodies = mutableListOf<PrivateBytes?>(); private val calls = mutableMapOf<String, Int>()
        var writes = 0; var acknowledged = 0L; private var awaiting: SessionControlRecord? = null
        private var dataReceipt = 41
        fun coordinator(owner: Any = this.owner, generation: Any = this.generation, configuration: String = CONFIGURATION) =
            CompositeSetupAbortCoordinator(this, this, configuration, owner, generation,
                processPending = { call("latch") { pendingRetirement }.let { ok(it) } },
                checkCurrent = { if (!current) throw OwnerChanged() })
        fun count(name: String) = calls[name] ?: 0
        fun pending() = RetirementCodec.decode(record!!.payload) as RetirementState.PendingSetup
        fun set(state: RetirementState) { record = SessionControlRecord(record!!.revision + 1, RetirementCodec.encode(state)) }
        fun bound() = stage(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_NONEMPTY, SessionWorkOriginPlanStatus.SEALED)
        fun stage(c: CredentialCreateRecoveryStatus, d: StateActivationStatus, w: SessionWorkOriginPlanStatus) {
            credential = CredentialCreatePlanObservation(c, fingerprint(1)); data = StateActivationPlanObservation(d, fingerprint(2))
            work = SessionWorkOriginObservation(w, if (w == SessionWorkOriginPlanStatus.PREPARED) 7 else 9)
        }
        fun completed() {
            assertEquals(OPERATION, (RetirementCodec.decode(record!!.payload) as RetirementState.Complete).operationId)
            assertEquals(CredentialCreateRecoveryStatus.ABORTED, credential.status); assertEquals(StateActivationStatus.ABORTED, data.status)
            assertEquals(SessionWorkOriginPlanStatus.ABORTED, work.status)
        }
        override suspend fun read(): PortResult<SessionControlRecord?> = call("control") {
            record?.let { current ->
                awaiting?.let { expected -> if (current.revision == expected.revision && equal(current.payload, expected.payload)) acknowledged = current.revision }
                awaiting = null; SessionControlRecord(current.revision, PrivateBytes(current.payload.copyForCodec()))
            }
        }.let { result -> readFailure?.let { PortResult.Failure(it) } ?: result }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            val index = ++writes; val event = "cas$index"; events += event; before(event); awaiting = null
            writtenPayloads += PrivateBytes(payload.copyForCodec())
            val state = RetirementCodec.decode(payload)
            if (state is RetirementState.Complete) {
                assertEquals(OPERATION, state.operationId)
                assertEquals(CredentialCreateRecoveryStatus.ABORTED, credential.status); assertEquals(StateActivationStatus.ABORTED, data.status)
                assertEquals(SessionWorkOriginPlanStatus.ABORTED, work.status)
                failCompletionBefore?.let { return PortResult.Failure(it) }
            }
            beforeCasFailures[index]?.let { return PortResult.Failure(it) }
            val previous = record ?: return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            if (previous.revision != expectedRevision) return PortResult.Failure(FailureReason.CONFLICT)
            val committed = SessionControlRecord(previous.revision + 1, PrivateBytes(payload.copyForCodec())); record = committed
            afterCas(index, payload); after(event)
            afterCasFailures[index]?.let { return PortResult.Failure(it) }
            return PortResult.Value((receiptTransform?.invoke(committed) ?: committed).also { awaiting = it })
        }
        override suspend fun credentials(scope: StorageScope, plan: CredentialCreatePlan) = call("credential") {
            assertEquals(SCOPE, scope); same(credentialPlan().copyForStorage(), plan.copyForStorage()); credential
        }
        override suspend fun data(scope: StorageScope, plan: StateActivationPlan) = call("data") {
            assertEquals(SCOPE, scope); assertContentEquals(dataPlan().copyForStorage(), plan.copyForStorage()); data
        }
        override suspend fun binding(scope: StorageScope, plan: StateActivationPlan) = call("binding") {
            assertEquals(SCOPE, scope); assertContentEquals(dataPlan().copyForStorage(), plan.copyForStorage()); binding
        }
        override suspend fun work(plan: SessionWorkOriginPlan) = call("work") { same(workPlan().copyForStorage(), plan.copyForStorage()); work }
        override suspend fun abortWork(plan: SessionWorkOriginPlan) = effect("work") {
            same(workPlan().copyForStorage(), plan.copyForStorage()); work = SessionWorkOriginObservation(SessionWorkOriginPlanStatus.ABORTED, work.revision + 1)
        }
        override suspend fun abortData(scope: StorageScope, plan: StateActivationPlan, expectedBinding: PrivateBytes?): PortResult<Unit> {
            dataBodies += expectedBinding?.let { PrivateBytes(it.copyForCodec()) }
            assertEquals(SCOPE, scope); assertContentEquals(dataPlan().copyForStorage(), plan.copyForStorage())
            if (data.status == StateActivationStatus.SELECTED_NONEMPTY) same(boundPayload(), assertNotNull(expectedBinding))
            else assertNull(expectedBinding)
            return effect("data") { data = StateActivationPlanObservation(StateActivationStatus.ABORTED, fingerprint(++dataReceipt)); binding = StateRecordInspection(null, null) }
        }
        override suspend fun abortCredentials(scope: StorageScope, plan: CredentialCreatePlan) = effect("credential") {
            assertEquals(SCOPE, scope); same(credentialPlan().copyForStorage(), plan.copyForStorage())
            credential = CredentialCreatePlanObservation(CredentialCreateRecoveryStatus.ABORTED, fingerprint(41))
        }
        private suspend fun effect(part: String, transition: () -> Unit): PortResult<Unit> {
            effects += part; val event = "abort-$part"; events += event; before(event)
            assertTrue(pending().abortRequested); same(PLAN.copyForStorage(), pending().plan.copyForStorage())
            assertEquals(record!!.revision, acknowledged); beforeEffect(part)
            effectFailures[part]?.let { return PortResult.Failure(it) }
            if (part !in noTransition) transition()
            afterEffect(part); after(event)
            return afterEffectFailures[part]?.let { PortResult.Failure(it) } ?: PortResult.Value(Unit)
        }
        private suspend fun <T> call(name: String, action: () -> T): PortResult<T> {
            val event = "$name${count(name) + 1}"; calls[name] = count(name) + 1; events += event; before(event)
            val result = failures[event]?.let { PortResult.Failure(it) } ?: PortResult.Value(action())
            after(event); return result
        }
    }
    private class OwnerChanged : Exception("Owner changed")
    companion object {
        private val SCOPE = StorageScope("composite-abort-protocol", ActorKind.ACCOUNT, "private-abort-owner")
        private val CONFIGURATION = "d".repeat(64)
        private const val OPERATION = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        private const val INCARNATION = "11111111-2222-4333-8444-555555555555"
        private const val ORIGIN = "22222222-3333-4444-8555-666666666666"
        private const val OTHER_ID = "33333333-4444-4555-8666-777777777777"
        private val LIVE_STAGES = listOf(
            Triple(CredentialCreateRecoveryStatus.PREPARED, StateActivationStatus.PREPARED, SessionWorkOriginPlanStatus.PREPARED),
            Triple(CredentialCreateRecoveryStatus.PARTIAL, StateActivationStatus.PREPARED, SessionWorkOriginPlanStatus.PREPARED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.PREPARED, SessionWorkOriginPlanStatus.PREPARED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.PARTIAL, SessionWorkOriginPlanStatus.PREPARED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_EMPTY, SessionWorkOriginPlanStatus.PREPARED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_EMPTY, SessionWorkOriginPlanStatus.SELECTED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_NONEMPTY, SessionWorkOriginPlanStatus.SELECTED),
            Triple(CredentialCreateRecoveryStatus.SELECTED, StateActivationStatus.SELECTED_NONEMPTY, SessionWorkOriginPlanStatus.SEALED))
        private fun credentialPlan() = CredentialCreatePlan.create(CredentialCreatePlanRecord(3, INCARNATION, "a".repeat(64), "b".repeat(64), "c".repeat(64)))
        private fun dataPlan() = StateActivationPlan(ByteArray(170).apply {
            this[0] = 1; for (i in 2..65) this[i] = 'a'.code.toByte(); for (i in 106..137) this[i] = 'b'.code.toByte()
        })
        private fun workPlan() = SessionWorkOriginPlan.create(SessionWorkOriginPlanRecord(7, SCOPE, ORIGIN, PrivateBytes(ByteArray(64))))
        private val PLAN = SessionSetupPlan.create(SessionSetupPlanRecord(OPERATION, SCOPE, CONFIGURATION, credentialPlan(), dataPlan(), workPlan()))
        private fun target(byte: Int = 17) = StateRetirementTarget(ByteArray(StateRetirementTarget.ENCODED_SIZE) { byte.toByte() })
        private fun boundPayload(target: StateRetirementTarget = target()) = SessionActivationCodec.encode(
            SessionActivationRecord(SCOPE, INCARNATION, ORIGIN, target, CONFIGURATION, OPERATION))
        private fun boundRecord(target: StateRetirementTarget = target()) = PrivateRecord(1, 2, boundPayload(target))
        private fun fingerprint(byte: Int) = PrivateBytes(ByteArray(32) { byte.toByte() })
        private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
        private fun same(a: PrivateBytes, b: PrivateBytes) = assertContentEquals(a.copyForCodec(), b.copyForCodec())
        private fun equal(a: PrivateBytes, b: PrivateBytes) = a.copyForCodec().contentEquals(b.copyForCodec())
        private fun <T> ok(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun rejected(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
