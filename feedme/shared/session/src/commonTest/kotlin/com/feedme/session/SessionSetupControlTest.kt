package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.StateActivationPlan
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure structural fixtures and recording control: no native authentication or durability claim. */
class SessionSetupControlTest {
    @Test fun pendingSetupRoundTripsEveryOriginalPlanAndOnlyExplicitAbortFlag() {
        val plan = plan()
        for (abort in listOf(false, true)) {
            val encoded = RetirementCodec.encode(RetirementState.PendingSetup(plan, abort))
            assertTrue(encoded.copyForCodec().size <= 32_768)
            val state = assertIs<RetirementState.PendingSetup>(RetirementCodec.decode(encoded))
            assertEquals(abort, state.abortRequested)
            assertContentEquals(plan.copyForStorage().copyForCodec(), state.plan.copyForStorage().copyForCodec())
            assertContentEquals(encoded.copyForCodec(), RetirementCodec.encode(state).copyForCodec())
        }
    }

    @Test fun outerStateRequiresExactFieldsAndActualBooleanWithoutLegacyProgressTargets() {
        val root = root()
        for (key in root.keys) invalid(JsonObject(root - key))
        for (key in listOf("accessToken", "refreshToken", "done", "origin", "dataTarget", "credentialIncarnation"))
            invalid(JsonObject(root + (key to JsonPrimitive("private-canary"))))
        for (value in listOf(JsonNull, JsonPrimitive(0), JsonPrimitive("false"), JsonArray(emptyList()), buildJsonObject {}))
            invalid(JsonObject(root + ("abortRequested" to value)))
        for (version in listOf("0", "2", "1.0", "1e0", "true", "\"1\""))
            invalid(JsonObject(root + ("version" to Json.parseToJsonElement(version))))
    }

    @Test fun malformedOrOversizedOpaqueSetupCannotBeDecodedAsEmptyOrLegacyIntent() {
        for (hex in listOf("", "0", "ff", "AA", "00".repeat(16_001), "private-secret"))
            invalid(JsonObject(root() + ("plan" to JsonPrimitive(hex))))
        for (value in listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(1), buildJsonObject {}))
            invalid(JsonObject(root() + ("plan" to value)))
        invalid(bytes(" ".repeat(32_769) + root()))
        invalid(bytes(root().toString().replace("\"version\":1", "\"version\":1,\"version\":1")))
    }

    @Test fun nestedCanonicalBytesAndScopeAgreementRemainRequiredInsideControl() {
        val plan = plan().copyForStorage().copyForCodec().decodeToString()
        for (bad in listOf(" $plan", plan.replace("session-setup", "credential-create"),
            plan.replace("\"configurationBinding\":\"${"a".repeat(64)}\"", "\"configurationBinding\":\"secret\""))) {
            invalid(JsonObject(root() + ("plan" to JsonPrimitive(hex(bad.encodeToByteArray())))))
        }
    }

    @Test fun setupAlwaysBlocksAccessAndIsNeverALegacyCleanupProgressType() {
        for (abort in listOf(false, true)) {
            val state: RetirementState = RetirementState.PendingSetup(plan(), abort)
            assertTrue(state.blocksAccess())
            assertFalse(state is RetirementState.InFlight)
            assertFalse(state is RetirementState.PendingCreate)
            for (marker in listOf(SCOPE.actorId, ID, "a".repeat(64))) assertFalse(state.toString().contains(marker))
        }
        assertFalse(RetirementState.Idle.blocksAccess())
        assertFalse(RetirementState.Complete(ID).blocksAccess())
    }

    @Test fun legacyCredentialCreateRetainsItsExactWireFormatAndExplicitRecoveryType() {
        for (abort in listOf(false, true)) {
            val legacy = RetirementState.PendingCreate(credential(), abort)
            val expected = """{"version":1,"state":"credential-create-pending","plan":"${hex(legacy.plan.copyForStorage().copyForCodec())}","abortRequested":$abort}"""
            assertEquals(expected, RetirementCodec.encode(legacy).copyForCodec().decodeToString())
            val decoded = assertIs<RetirementState.PendingCreate>(RetirementCodec.decode(bytes(expected)))
            assertEquals(abort, decoded.abortRequested)
            assertContentEquals(legacy.plan.copyForStorage().copyForCodec(), decoded.plan.copyForStorage().copyForCodec())
        }
    }

    @Test fun legacyCredentialCoordinatorNeverExtractsSubplanOrInfersAbortFromComposite() = runTest {
        for (abort in listOf(false, true)) {
            val control = Control(RetirementState.PendingSetup(plan(), abort))
            val before = control.record
            var opens = 0
            val coordinator = CredentialCreateCoordinator(control, SessionBoundary(), StandardTestDispatcher(testScheduler),
                CredentialCreateRecoveryFactory { opens++; error("Composite intent cannot open credential-only recovery") })
            assertEquals(PortResult.Failure(FailureReason.CONFLICT), coordinator.inspectPending())
            assertEquals(PortResult.Failure(FailureReason.CONFLICT), coordinator.recoverAbort())
            assertEquals(0, opens); assertEquals(0, control.writes)
            assertSame(before, control.record)
        }
    }

    @Test fun oldCredentialOnlyProposalCannotOverwriteNewCompositeAtTheSameRevision() = runTest {
        val control = Control(RetirementState.PendingCreate(credential(), false))
        val coordinator = CredentialCreateCoordinator(control, SessionBoundary(), StandardTestDispatcher(testScheduler),
            CredentialCreateRecoveryFactory { error("Stale proposal must not open native recovery") })
        val proposal = assertNotNull(value(coordinator.inspectPending()))
        control.record = SessionControlRecord(control.record.revision, RetirementCodec.encode(RetirementState.PendingSetup(plan(), false)))
        val replacement = control.record
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), coordinator.requestAbort(proposal))
        assertSame(replacement, control.record); assertEquals(0, control.writes)
    }

    @Test fun roundTripCopiesCannotMutatePendingSetupIdentityOrNestedPlanBytes() {
        val original = RetirementState.PendingSetup(plan(), false)
        val saved = RetirementCodec.encode(original)
        val decoded = assertIs<RetirementState.PendingSetup>(RetirementCodec.decode(saved))
        decoded.plan.copyForStorage().copyForCodec().fill(0)
        saved.copyForCodec().fill(0)
        assertContentEquals(original.plan.copyForStorage().copyForCodec(), decoded.plan.copyForStorage().copyForCodec())
        assertFalse(decoded.abortRequested)
    }

    private class Control(state: RetirementState) : SessionControlStore {
        var record = SessionControlRecord(7, RetirementCodec.encode(state))
        var writes = 0
        override suspend fun read(): PortResult<SessionControlRecord?> = PortResult.Value(record)
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            writes++
            return if (expectedRevision != record.revision) PortResult.Failure(FailureReason.CONFLICT)
                else PortResult.Value(SessionControlRecord(record.revision + 1, payload).also { record = it })
        }
    }

    private fun plan(): SessionSetupPlan {
        val data = ByteArray(170).apply {
            this[0] = 1
            for (i in 2..65) this[i] = 'a'.code.toByte()
            for (i in 106..137) this[i] = 'b'.code.toByte()
        }
        val work = SessionWorkOriginPlan.create(SessionWorkOriginPlanRecord(1, SCOPE, ID, PrivateBytes(ByteArray(64))))
        return SessionSetupPlan.create(SessionSetupPlanRecord(ID, SCOPE, "a".repeat(64), credential(),
            value(StateActivationPlan.fromStorage(data)), work))
    }
    private fun credential() = CredentialCreatePlan.create(CredentialCreatePlanRecord(1, ID,
        "a".repeat(64), "b".repeat(64), "c".repeat(64)))
    private fun root() = Json.parseToJsonElement(RetirementCodec.encode(RetirementState.PendingSetup(plan(), false)).copyForCodec().decodeToString()).jsonObject
    private fun invalid(root: JsonObject) = invalid(bytes(root.toString()))
    private fun invalid(bytes: PrivateBytes) { assertFailsWith<RetirementFailure> { RetirementCodec.decode(bytes) } }
    private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    private companion object {
        val SCOPE = StorageScope("setup-control-test", ActorKind.ACCOUNT, "private-composite-owner")
        const val ID = "00000000-0000-4000-8000-000000000391"
    }
}
