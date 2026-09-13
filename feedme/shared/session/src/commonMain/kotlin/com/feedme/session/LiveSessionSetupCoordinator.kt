package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.EncryptedStateDatabase
import com.feedme.storage.StateActivationInspection
import com.feedme.storage.StateActivationPlan
import com.feedme.storage.StateActivationStatus
import com.feedme.storage.StateRecordInspection
import com.feedme.storage.StateRetirementTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Internal native-owner seam; protocol fakes are never installed by an application default. */
internal interface LiveSessionSetupResources {
    suspend fun credentialState(): PortResult<CredentialSlotState>
    suspend fun workState(): PortResult<SessionWorkSnapshot>
    suspend fun planCredential(expectedRevision: Long, credentials: StoredCredentials): PortResult<CredentialCreatePlan>
    suspend fun planData(scope: StorageScope): PortResult<StateActivationPlan>
    suspend fun planWork(scope: StorageScope, expectedRevision: Long): PortResult<SessionWorkOriginPlan>
    suspend fun inspectData(scope: StorageScope, plan: StateActivationPlan): PortResult<StateActivationInspection>
    suspend fun inspectWork(plan: SessionWorkOriginPlan): PortResult<SessionWorkOriginPlanStatus>
    suspend fun selectCredential(plan: CredentialCreatePlan, credentials: StoredCredentials): PortResult<CredentialSnapshot>
    suspend fun selectData(scope: StorageScope, plan: StateActivationPlan): PortResult<Unit>
    suspend fun selectWork(plan: SessionWorkOriginPlan): PortResult<Unit>
}

/** Separate seam keeps selection-only callers incapable of completing or publishing setup. */
internal interface LiveSessionSetupCompletionResources : LiveSessionSetupResources {
    suspend fun inspectBinding(scope: StorageScope, plan: StateActivationPlan): PortResult<StateRecordInspection>
    suspend fun bind(scope: StorageScope, plan: StateActivationPlan, payload: PrivateBytes): PortResult<StateRecordInspection>
    suspend fun sealWork(plan: SessionWorkOriginPlan): PortResult<Unit>
}

/** Detached native receipt, not a lease, authentication proof or authority after a restart. */
internal class CompletedSessionSetup(
    val credential: CredentialSnapshot,
    val activation: SessionActivationRecord,
    val binding: PrivateRecord,
    val control: SessionControlRecord,
    val workOriginPlan: SessionWorkOriginPlan,
) {
    override fun toString() = "CompletedSessionSetup(<redacted>)"
}

/** Real stores only: no ordinary activation/resume, key GC, lease or OS installation shortcut. */
internal class NativeLiveSessionSetupResources(
    private val credentials: PlannedCredentialCreateStore,
    private val data: EncryptedStateDatabase,
    private val work: SessionWorkRegistry,
) : LiveSessionSetupCompletionResources {
    override suspend fun credentialState() = credentials.state()
    override suspend fun workState() = work.snapshot()
    override suspend fun planCredential(expectedRevision: Long, credentials: StoredCredentials) =
        this.credentials.planCreate(expectedRevision, credentials)
    override suspend fun planData(scope: StorageScope) = data.planActivation(scope)
    override suspend fun planWork(scope: StorageScope, expectedRevision: Long) = work.planOrigin(scope, expectedRevision)
    override suspend fun inspectData(scope: StorageScope, plan: StateActivationPlan) = data.inspectPlannedActivation(scope, plan)
    override suspend fun inspectWork(plan: SessionWorkOriginPlan) = work.inspectOrigin(plan)
    override suspend fun selectCredential(plan: CredentialCreatePlan, credentials: StoredCredentials) =
        this.credentials.commitPlannedCreate(plan, credentials)
    override suspend fun selectData(scope: StorageScope, plan: StateActivationPlan) = data.commitPlannedActivation(scope, plan)
    override suspend fun selectWork(plan: SessionWorkOriginPlan) = work.selectOrigin(plan)
    override suspend fun inspectBinding(scope: StorageScope, plan: StateActivationPlan) = data.inspectPlannedBinding(scope, plan)
    override suspend fun bind(scope: StorageScope, plan: StateActivationPlan, payload: PrivateBytes) =
        data.bindPlannedActivation(scope, plan, 2, payload)
    override suspend fun sealWork(plan: SessionWorkOriginPlan) = work.sealOrigin(plan)
}

/**
 * Trusted session setup owner after real identity/bootstrap verification. Selection and durable
 * completion are separate calls; neither grants a lease or publishes a secret-bearing view.
 * This is not a provider or a public identity API.
 * The parent must exclusively serialize every mutation of these stores on this dispatcher.
 *
 * One original in-memory verified identity and all three native plans survive non-cancellation
 * failures. The journal is acknowledged BEFORE selection. retry has
 * no arguments: persisted bytes alone cannot reconstruct verified credentials or a live attempt.
 * Cancellation/close drops retained live credentials and fences late completions, without erase.
 *
 * begin/retry return Unit and leave the journal pending. complete requires fresh changed binding
 * and work-seal acknowledgements, followed by changed Complete and exact readback. Readable
 * bytes after a lost acknowledgement never substitute for success. The parent must validate
 * the detached receipt again before granting a late lease and consuming work setup provenance.
 */
internal class LiveSessionSetupCoordinator(
    private val control: SessionControlStore,
    private val resources: LiveSessionSetupResources,
    private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher,
    private val configurationBinding: String,
    private val ids: NativeWorkIdSource,
    private val processRetirementPending: suspend () -> Boolean = { false },
    private val isCurrent: () -> Boolean = { true },
) {
    private val mutex = Mutex()
    private var generation: Any = Any()
    private var closed = false
    private var retained: Attempt? = null

    suspend fun begin(verified: StoredCredentials): PortResult<Unit> = operation { token ->
        if (retained != null) fail(FailureReason.CONFLICT)
        validConfiguration()
        if (verified.scope.actorKind == ActorKind.DEMO) fail(FailureReason.INVALID_DATA)
        if (verified is StoredCredentials.Account && verified.deviceSessionId == null) fail(FailureReason.NOT_CONFIGURED)
        fence(token)
        val original = readControl(token)
        val state = decode(original)
        if (state.blocksAccess()) fail(FailureReason.CONFLICT)
        requireRevisionSpace(original)
        val operationId = try { requireCredentialUuid(ids.nextId()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { fail(FailureReason.INVALID_DATA) }
        check(token)
        if ((state as? RetirementState.Complete)?.operationId == operationId) fail(FailureReason.CONFLICT)
        val slot = observe(token) { resources.credentialState() }
        if (slot.revision !in 1..Long.MAX_VALUE - 2 || slot.owner != null || slot.incarnation != null)
            fail(FailureReason.CONFLICT)
        val work = observe(token) { resources.workState() }
        if (work.revision !in 1..Long.MAX_VALUE - 2 || work.scope != null || work.originBinding != null ||
            work.retiring || work.entries.isNotEmpty()) fail(FailureReason.CONFLICT)
        val credential = observe(token) { resources.planCredential(slot.revision, verified) }
        val details = CredentialCreatePlanCodec.decode(credential.copyForStorage())
        if (details.expectedSlotRevision != slot.revision) fail(FailureReason.CONFLICT)
        // Validate the complete retained credential payload before any journal/native effect.
        CredentialCodec.encodeSnapshot(CredentialSnapshot(details.incarnation, details.snapshotRevision, verified))
        val data = observe(token) { resources.planData(verified.scope) }
        val origin = observe(token) { resources.planWork(verified.scope, work.revision) }
        val originDetails = SessionWorkOriginPlanCodec.decode(origin.copyForStorage())
        if (originDetails.scope != verified.scope || originDetails.expectedRevision != work.revision)
            fail(FailureReason.CONFLICT)
        val plan = SessionSetupPlan.create(SessionSetupPlanRecord(operationId, verified.scope,
            configurationBinding, credential, data, origin))
        val attempt = Attempt(original, plan, verified)
        val currentSlot = observe(token) { resources.credentialState() }
        if (currentSlot.revision != slot.revision || currentSlot.owner != null || currentSlot.incarnation != null)
            fail(FailureReason.CONFLICT)
        inspectData(token, attempt, preparedOnly = true)
        if (observe(token) { resources.inspectWork(origin) } != SessionWorkOriginPlanStatus.PREPARED)
            fail(FailureReason.CONFLICT)
        requireControl(token, original)
        // Set before CAS: even a missing/unknown acknowledgement cannot authorize replanning.
        retained = attempt
        select(token, attempt)
    }

    /** Explicit same-live-attempt retry only. It cannot take a new identity or replacement plan. */
    suspend fun retry(): PortResult<Unit> = operation { token ->
        validConfiguration()
        val attempt = retained ?: fail(FailureReason.NOT_CONFIGURED)
        if (attempt.completionStarted) fail(FailureReason.CONFLICT)
        select(token, attempt)
    }

    /** Same live verified attempt only; never reconstruct this authority from the stored plan. */
    suspend fun complete(): PortResult<CompletedSessionSetup> = operation { token ->
        validConfiguration()
        val attempt = retained ?: fail(FailureReason.NOT_CONFIGURED)
        val completion = resources as? LiveSessionSetupCompletionResources ?: fail(FailureReason.NOT_CONFIGURED)
        if (!attempt.selectionComplete) select(token, attempt)
        attempt.completionStarted = true
        val initial = readControl(token)
        val completePayload = RetirementCodec.encode(RetirementState.Complete(attempt.record.operationId))
        val alreadyComplete = sameBytes(initial.payload, completePayload)
        if (initial.revision <= attempt.original.revision ||
            (!alreadyComplete && !sameBytes(initial.payload, attempt.pending))) fail(FailureReason.CONFLICT)
        requireRevisionSpace(initial)
        inspectCredential(token, attempt, selectedOnly = true)
        val observed = observe(token) { completion.inspectBinding(attempt.record.scope, attempt.record.dataPlan) }
        val target = observed.target ?: fail(FailureReason.STALE_SESSION)
        val origin = SessionWorkOriginPlanCodec.decode(attempt.record.workOriginPlan.copyForStorage()).origin
        val activation = SessionActivationRecord(attempt.record.scope, attempt.credential.incarnation,
            origin, target, configurationBinding, attempt.record.operationId)
        val payload = SessionActivationCodec.encode(activation)
        requireBinding(observed, activation, payload, optional = !alreadyComplete)
        val priorBindingRevision = observed.record?.revision ?: 0
        if (priorBindingRevision == Long.MAX_VALUE) fail(FailureReason.STORAGE_FAILURE)
        val workStatus = observe(token) { resources.inspectWork(attempt.record.workOriginPlan) }
        if (workStatus !in setOf(SessionWorkOriginPlanStatus.SELECTED, SessionWorkOriginPlanStatus.SEALED) ||
            (alreadyComplete && workStatus != SessionWorkOriginPlanStatus.SEALED) ||
            (workStatus == SessionWorkOriginPlanStatus.SEALED && observed.record == null)) fail(FailureReason.CONFLICT)
        requireControl(token, initial)
        // A possibly visible Complete is not publication authority. Revalidate the exact native
        // plan and obtain new data/work/control acknowledgements before returning another receipt.
        val baseline = if (alreadyComplete) initial else
            observe(token) { control.acknowledge(initial, attempt.pending) { check(token) } }
        requireControl(token, baseline)
        val credential = observe(token) { resources.selectCredential(attempt.record.credentialPlan, attempt.credentials) }
        requireSnapshot(attempt, credential)
        requireControl(token, baseline)
        val bound = observe(token) { completion.bind(attempt.record.scope, attempt.record.dataPlan, payload) }
        requireBinding(bound, activation, payload, revision = priorBindingRevision + 1)
        val binding = bound.record ?: fail(FailureReason.STORAGE_FAILURE)
        requireControl(token, baseline)
        inspectCredential(token, attempt, selectedOnly = true)
        val reread = observe(token) { completion.inspectBinding(attempt.record.scope, attempt.record.dataPlan) }
        requireBinding(reread, activation, payload, revision = binding.revision)
        requireControl(token, baseline)
        observe(token) { completion.sealWork(attempt.record.workOriginPlan) }
        validateCompletion(token, attempt, completion, activation, payload, binding.revision, baseline)
        val acknowledged = observe(token) { control.acknowledge(baseline, completePayload) { check(token) } }
        validateCompletion(token, attempt, completion, activation, payload, binding.revision, acknowledged)
        CompletedSessionSetup(credential, activation, binding, acknowledged, attempt.record.workOriginPlan)
    }

    private suspend fun validateCompletion(token: Any, attempt: Attempt, completion: LiveSessionSetupCompletionResources,
        activation: SessionActivationRecord, payload: PrivateBytes, revision: Long, controlRecord: SessionControlRecord) {
        requireControl(token, controlRecord)
        inspectCredential(token, attempt, selectedOnly = true)
        requireBinding(observe(token) { completion.inspectBinding(attempt.record.scope, attempt.record.dataPlan) },
            activation, payload, revision = revision)
        if (observe(token) { resources.inspectWork(attempt.record.workOriginPlan) } != SessionWorkOriginPlanStatus.SEALED)
            fail(FailureReason.STALE_SESSION)
        requireControl(token, controlRecord)
    }

    private fun requireBinding(inspection: StateRecordInspection, activation: SessionActivationRecord,
        payload: PrivateBytes, optional: Boolean = false, revision: Long? = null) {
        val target = inspection.target ?: fail(FailureReason.STALE_SESSION)
        if (!sameTarget(target, activation.dataTarget)) fail(FailureReason.STALE_SESSION)
        val record = inspection.record ?: if (optional) return else fail(FailureReason.STORAGE_FAILURE)
        if (record.revision <= 0 || (revision != null && record.revision != revision) ||
            record.schemaVersion != 2 || !sameBytes(record.payload, payload)) fail(FailureReason.CONFLICT)
    }

    /** Invalidate synchronously on the identity dispatcher, without waiting behind a native call. */
    suspend fun cancel(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        if (closed) PortResult.Failure(FailureReason.STORAGE_FAILURE)
        else {
            generation = Any()
            retained = null
            PortResult.Value(Unit)
        }
    }

    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        generation = Any()
        retained = null
        closed = true
        PortResult.Value(Unit)
    }

    private suspend fun select(token: Any, attempt: Attempt) {
        fence(token)
        if (retained !== attempt || attempt.record.configurationBinding != configurationBinding)
            fail(FailureReason.STALE_SESSION)
        val original = readControl(token)
        val isOriginal = sameRecord(original, attempt.original)
        if (!isOriginal && (original.revision <= attempt.original.revision ||
                !sameBytes(original.payload, attempt.pending))) fail(FailureReason.CONFLICT)
        requireRevisionSpace(original)
        inspectCredential(token, attempt, selectedOnly = false)
        inspectData(token, attempt)
        inspectSelectableWork(token, attempt)
        requireControl(token, original)
        // A readable pending record is not an ack. Every retry obtains a fresh changed CAS.
        val pending = observe(token) { control.acknowledge(original, attempt.pending) { check(token) } }
        requireControl(token, pending)

        val snapshot = observe(token) {
            resources.selectCredential(attempt.record.credentialPlan, attempt.credentials)
        }
        requireSnapshot(attempt, snapshot)
        requireControl(token, pending)
        inspectCredential(token, attempt, selectedOnly = true)
        inspectData(token, attempt)
        inspectSelectableWork(token, attempt)
        requireControl(token, pending)

        observe(token) { resources.selectData(attempt.record.scope, attempt.record.dataPlan) }
        requireControl(token, pending)
        inspectCredential(token, attempt, selectedOnly = true)
        if (inspectData(token, attempt) != StateActivationStatus.SELECTED_EMPTY) fail(FailureReason.STALE_SESSION)
        inspectSelectableWork(token, attempt)
        requireControl(token, pending)

        observe(token) { resources.selectWork(attempt.record.workOriginPlan) }
        requireControl(token, pending)
        inspectCredential(token, attempt, selectedOnly = true)
        if (inspectData(token, attempt) != StateActivationStatus.SELECTED_EMPTY) fail(FailureReason.STALE_SESSION)
        if (observe(token) { resources.inspectWork(attempt.record.workOriginPlan) } != SessionWorkOriginPlanStatus.SELECTED)
            fail(FailureReason.STALE_SESSION)
        requireControl(token, pending)
        attempt.selectionComplete = true
        // Deliberately no Complete, binding, lease, scheduling or secret-bearing return value.
    }

    private suspend fun inspectSelectableWork(token: Any, attempt: Attempt) {
        val status = observe(token) { resources.inspectWork(attempt.record.workOriginPlan) }
        if (status != SessionWorkOriginPlanStatus.PREPARED && status != SessionWorkOriginPlanStatus.SELECTED)
            fail(FailureReason.CONFLICT)
    }

    private fun requireSnapshot(attempt: Attempt, snapshot: CredentialSnapshot) {
        val expected = CredentialSnapshot(attempt.credential.incarnation, attempt.credential.snapshotRevision, attempt.credentials)
        if (!sameBytes(CredentialCodec.encodeSnapshot(snapshot), CredentialCodec.encodeSnapshot(expected)))
            fail(FailureReason.STALE_SESSION)
    }

    private suspend fun inspectCredential(token: Any, attempt: Attempt, selectedOnly: Boolean) {
        val slot = observe(token) { resources.credentialState() }
        val selected = slot.owner == attempt.record.scope && slot.incarnation == attempt.credential.incarnation &&
            slot.revision == attempt.credential.snapshotRevision
        val prepared = slot.owner == null && slot.incarnation == null && slot.revision == attempt.credential.expectedSlotRevision
        if (!selected && (selectedOnly || !prepared)) fail(FailureReason.STALE_SESSION)
    }

    private suspend fun inspectData(token: Any, attempt: Attempt, preparedOnly: Boolean = false): StateActivationStatus {
        val status = observe(token) { resources.inspectData(attempt.record.scope, attempt.record.dataPlan) }.status
        if (preparedOnly && status != StateActivationStatus.PREPARED) fail(FailureReason.CONFLICT)
        if (status !in setOf(StateActivationStatus.PREPARED, StateActivationStatus.PARTIAL, StateActivationStatus.SELECTED_EMPTY))
            fail(FailureReason.CONFLICT)
        return status
    }

    private suspend fun requireControl(token: Any, expected: SessionControlRecord) {
        if (!sameRecord(expected, readControl(token))) fail(FailureReason.CONFLICT)
    }

    private suspend fun readControl(token: Any): SessionControlRecord {
        val record = observe(token) { control.read() } ?: fail(FailureReason.STORAGE_FAILURE)
        if (record.revision <= 0) fail(FailureReason.STORAGE_FAILURE)
        decode(record)
        return record
    }

    private fun decode(record: SessionControlRecord): RetirementState = try { RetirementCodec.decode(record.payload) }
        catch (_: Exception) { fail(FailureReason.INVALID_DATA) }

    private fun requireRevisionSpace(record: SessionControlRecord) {
        if (record.revision > Long.MAX_VALUE - 2) fail(FailureReason.STORAGE_FAILURE)
    }

    private suspend fun <T> observe(token: Any, action: suspend () -> PortResult<T>): T {
        fence(token)
        val result = action()
        fence(token)
        return when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> fail(result.reason)
        }
    }

    private suspend fun fence(token: Any) {
        currentCoroutineContext().ensureActive()
        check(token)
        val pending = processRetirementPending()
        currentCoroutineContext().ensureActive()
        check(token)
        if (pending) fail(FailureReason.CONFLICT)
    }

    private fun check(token: Any) {
        if (closed) fail(FailureReason.STORAGE_FAILURE)
        if (generation !== token || !isCurrent() || boundary.current() != null) fail(FailureReason.STALE_SESSION)
    }

    private fun validConfiguration() {
        if (configurationBinding.length != 64 || configurationBinding.any { it !in "0123456789abcdef" })
            fail(FailureReason.INVALID_DATA)
    }

    private suspend fun <T> operation(action: suspend (Any) -> T): PortResult<T> {
        // The outer catch also owns prompt cancellation at the dispatcher return handoff.
        // A caller still queued behind the mutex has not acquired this attempt's lifetime.
        var ownedToken: Any? = null
        return try {
            withContext(dispatcher) {
                val token = generation
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    check(token)
                    ownedToken = token
                    val result = action(token)
                    currentCoroutineContext().ensureActive()
                    check(token)
                    PortResult.Value(result)
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + dispatcher) {
                if (ownedToken != null && generation === ownedToken) { generation = Any(); retained = null }
            }
            throw cancelled
        } catch (failure: LiveSetupFailure) { PortResult.Failure(failure.reason) }
        catch (_: CredentialFormatException) { PortResult.Failure(FailureReason.INVALID_DATA) }
        catch (_: CredentialCreatePlanFormatException) { PortResult.Failure(FailureReason.INVALID_DATA) }
        catch (_: SessionWorkOriginPlanFormatException) { PortResult.Failure(FailureReason.INVALID_DATA) }
        catch (_: SessionSetupPlanFormatException) { PortResult.Failure(FailureReason.INVALID_DATA) }
        catch (_: SessionActivationFormatException) { PortResult.Failure(FailureReason.INVALID_DATA) }
        catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
    }

    private class Attempt(val original: SessionControlRecord, val plan: SessionSetupPlan, val credentials: StoredCredentials) {
        var selectionComplete = false
        var completionStarted = false
        val record = SessionSetupPlanCodec.decode(plan.copyForStorage())
        val credential = CredentialCreatePlanCodec.decode(record.credentialPlan.copyForStorage())
        val pending = RetirementCodec.encode(RetirementState.PendingSetup(plan, false))
        override fun toString() = "LiveSessionSetupAttempt(<redacted>)"
    }

    override fun toString() = "LiveSessionSetupCoordinator(<redacted>)"

    private fun sameRecord(a: SessionControlRecord, b: SessionControlRecord) = a.revision == b.revision && sameBytes(a.payload, b.payload)
    private fun sameTarget(a: StateRetirementTarget, b: StateRetirementTarget): Boolean {
        val left = a.copyForStorage(); val right = b.copyForStorage()
        return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
    }
    private fun sameBytes(a: PrivateBytes, b: PrivateBytes): Boolean {
        val left = a.copyForCodec(); val right = b.copyForCodec()
        return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
    }
    private fun fail(reason: FailureReason): Nothing = throw LiveSetupFailure(reason)
    private class LiveSetupFailure(val reason: FailureReason) : Exception("Live session setup unavailable")
}
