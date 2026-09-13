package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.EncryptedStateDatabase
import com.feedme.storage.StateRetirementTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class PrivateSessionPhase { STARTUP, SIGNED_OUT, RESTORE_REQUIRED, VERIFYING, COMPOSING, ACTIVE, RETIRING, RECOVERY_REQUIRED, CLOSED }
enum class PrivateSessionAccessMode { ONLINE, OFFLINE_PRIVATE }

/**
 * Trusted native integration only: no default implementation and no UI-supplied identity proof.
 * Implementations are bound to the runtime's immutable approved configuration. acquire verifies
 * provider challenges AND explicit FeedMe owner/bootstrap mapping; restore checks that exact
 * prior identity/configuration, returning OFFLINE_PRIVATE only for permitted previously verified
 * local use. Neither HTTP 200, a matching email, nor stored credentials alone prove identity.
 */
interface NativeSessionVerifier {
    suspend fun acquire(): PortResult<StoredCredentials>
    suspend fun restore(snapshot: CredentialSnapshot): PortResult<PrivateSessionAccessMode>
}

/** Native repository composition handle, not a ViewModel state or a transferable auth proof. */
class PrivateSessionAccess internal constructor(
    val lease: SessionLease,
    val originBinding: String,
    val mode: PrivateSessionAccessMode,
    val store: PrivateStateStore,
    val credentials: SecureCredentialStore,
    internal val work: SessionWorkBinding,
    internal val retirement: RetirementBinding,
) {
    val scope: StorageScope get() = lease.scope
    override fun toString() = "PrivateSessionAccess(mode=$mode, identity=<redacted>)"
}

/**
 * Serialized native session composition, not an identity provider, bootstrap HTTP implementation,
 * full UI or guest-merge flow. The application owns the supplied stores/dispatcher/boundary and
 * must not activate that boundary or mutate credentials outside this lifetime owner.
 * Partial initial writes remain explicit repair, never an inferred activation or automatic erase.
 */
class PrivateSessionRuntime private constructor(
    private val control: SessionControlStore,
    private val data: EncryptedStateDatabase,
    private val credentialStore: IncarnationCredentialStore,
    private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher,
    private val configurationBinding: String,
    private val verifier: NativeSessionVerifier,
    private val domainExecution: NativeWorkExecutionPolicy,
) {
    private val mutex = Mutex()
    private lateinit var work: SessionWorkRegistry
    private lateinit var retirement: LocalRetirementCoordinator
    private var phase = PrivateSessionPhase.STARTUP
    private var active: PrivateSessionAccess? = null
    private var composingScope: StorageScope? = null
    private var attempt: Any? = null
    private var wroteInitialState = false
    private var lifecycleGeneration: Any = Any()
    private val setupProposalOwner = Any()

    suspend fun phase(): PrivateSessionPhase = withContext(dispatcher) { phase }
    suspend fun currentAccess(): PrivateSessionAccess? = withContext(dispatcher) {
        active?.takeIf { phase == PrivateSessionPhase.ACTIVE && boundary.isCurrent(it.lease) }
    }

    /**
     * Read-only, UI-safe diagnostics of already-open resources. Never initializes, calls a
     * provider, decrypts credentials, repairs, retires or changes phase/lease. Every subsequent
     * action must use the real lifecycle method and freshly revalidate; this is not authority.
     */
    suspend fun inspectRecovery(): PortResult<SessionRecoveryReport> = owned {
        requireInspectable()
        val generation = lifecycleGeneration
        mutex.withLock {
            val check = {
                notClosed()
                if (lifecycleGeneration !== generation) fail(FailureReason.STALE_SESSION)
                requireInspectable()
            }
            check()
            SessionRecoveryInspector(control, credentialStore, work, data, BINDING_KEY,
                configurationBinding, retirement::hasProcessRetirement, check).inspect()
        }
    }

    /** Read-only proposal for selected, readable, EMPTY incomplete setup; never automatic repair. */
    suspend fun prepareInterruptedSetupDiscard(): PortResult<PreparedSetupDiscard> = owned {
        requireInspectable()
        val generation = lifecycleGeneration
        mutex.withLock {
            checkSetupGeneration(generation)
            val evidence = readSetupDiscardEvidence(generation)
            // Bracket independent resources twice; this is not a cross-store transaction.
            val observed = readSetupDiscardEvidence(generation)
            if (!sameSetupEvidence(evidence, observed)) fail(FailureReason.CONFLICT)
            PreparedSetupDiscard(setupProposalOwner, generation, configurationBinding, evidence)
        }
    }

    /**
     * Call only after explicit UI confirmation of this exact proposal. Persist independently
     * durable optional targets BEFORE cleanup. A failed/uncertain confirmed operation is retried
     * by recover(), never by selecting newer fragments. No verifier, token read or lease grant.
     */
    suspend fun confirmInterruptedSetupDiscard(prepared: PreparedSetupDiscard, operationId: String): PortResult<LocalRetirementProgress> = owned {
        fun check() {
            if (prepared.owner !== setupProposalOwner || prepared.configuration != configurationBinding)
                fail(FailureReason.STALE_SESSION)
            checkSetupGeneration(prepared.generation)
        }
        check()
        try { requireCredentialUuid(operationId) } catch (_: IllegalArgumentException) { fail(FailureReason.INVALID_DATA) }
        mutex.withLock {
            check()
            val current = readSetupDiscardEvidence(prepared.generation)
            if (!sameSetupEvidence(prepared.evidence, current)) fail(FailureReason.CONFLICT)
            val finalEvidence = readSetupDiscardEvidence(prepared.generation)
            if (!sameSetupEvidence(current, finalEvidence)) fail(FailureReason.CONFLICT)
            check()
            val prior = RetirementCodec.decode(current.control.payload) as? RetirementState.Complete
            if (prior?.operationId == operationId) fail(FailureReason.CONFLICT)
            val intent = RetirementState.SetupDiscardPending(operationId, current.scope,
                current.work.originBinding, current.slot.incarnation, current.target, emptySet())
            lifecycleGeneration = Any()
            phase = PrivateSessionPhase.RETIRING
            active = null; composingScope = null; attempt = null
            try {
                val result = value(retirement.discardSetup(intent, current.control))
                notClosed()
                // Completion does not infer that all owners or native orphan artifacts are absent.
                phase = if (result.phase == LocalRetirementPhase.COMPLETE) PrivateSessionPhase.STARTUP else PrivateSessionPhase.RECOVERY_REQUIRED
                result
            } finally {
                withContext(NonCancellable) {
                    if (phase == PrivateSessionPhase.RETIRING) phase = PrivateSessionPhase.RECOVERY_REQUIRED
                }
            }
        }
    }

    private fun checkSetupGeneration(generation: Any) {
        notClosed()
        if (lifecycleGeneration !== generation) fail(FailureReason.STALE_SESSION)
        requireInspectable()
    }

    private suspend fun readSetupDiscardEvidence(generation: Any): SetupDiscardEvidence {
        suspend fun <T> observe(read: suspend () -> PortResult<T>): T {
            val result = read()
            checkSetupGeneration(generation)
            return value(result)
        }
        suspend fun checkProcess() {
            val pending = retirement.hasProcessRetirement()
            checkSetupGeneration(generation)
            if (pending) fail(FailureReason.CONFLICT)
        }
        suspend fun readControl(): SessionControlRecord {
            val record = observe { control.read() } ?: fail(FailureReason.STORAGE_FAILURE)
            if (record.revision <= 0) fail(FailureReason.STORAGE_FAILURE)
            val state = try { RetirementCodec.decode(record.payload) } catch (_: Exception) { fail(FailureReason.STORAGE_FAILURE) }
            if (state.blocksAccess()) fail(FailureReason.CONFLICT)
            return record
        }
        checkSetupGeneration(generation)
        checkProcess()
        val initial = readControl()
        val slot = observe { credentialStore.state() }
        val index = observe { work.snapshot() }
        val scope = slot.owner ?: index.scope ?: fail(FailureReason.NOT_CONFIGURED)
        if (scope.actorKind == ActorKind.DEMO || (slot.owner != null && slot.owner != scope) ||
            (index.scope != null && index.scope != scope)) fail(FailureReason.CONFLICT)
        if (index.retiring || index.entries.isNotEmpty()) fail(FailureReason.CONFLICT)
        // Every record, including activation bindings and tombstones, makes this owner ineligible.
        // Missing binding alone never permits deleting private content.
        val owner = observe { data.inspectOwnerState(scope) }
        if (owner.hasRecords) fail(FailureReason.CONFLICT)
        val final = readControl()
        if (initial.revision != final.revision || !sameBytes(initial.payload, final.payload)) fail(FailureReason.CONFLICT)
        checkProcess()
        return SetupDiscardEvidence(initial, scope, slot, index, owner.target)
    }

    private fun sameSetupEvidence(a: SetupDiscardEvidence, b: SetupDiscardEvidence): Boolean =
        a.control.revision == b.control.revision && sameBytes(a.control.payload, b.control.payload) && a.scope == b.scope &&
            a.slot.revision == b.slot.revision && a.slot.owner == b.slot.owner && a.slot.incarnation == b.slot.incarnation &&
            a.work.revision == b.work.revision && a.work.scope == b.work.scope && a.work.originBinding == b.work.originBinding &&
            a.work.retiring == b.work.retiring && a.work.entries.isEmpty() && b.work.entries.isEmpty() &&
            (if (a.target == null || b.target == null) a.target == null && b.target == null else sameTarget(a.target, b.target))

    /** Startup or explicit pending-retirement retry. Never implicitly logs out an active session. */
    suspend fun recover(): PortResult<PrivateSessionPhase> = owned {
        mutex.withLock {
            notClosed()
            if (phase == PrivateSessionPhase.ACTIVE || phase == PrivateSessionPhase.VERIFYING || phase == PrivateSessionPhase.COMPOSING)
                fail(FailureReason.CONFLICT)
            lifecycleGeneration = Any()
            phase = PrivateSessionPhase.RECOVERY_REQUIRED
            active = null; composingScope = null; attempt = null
            val progress = value(retirement.recover())
            notClosed()
            if (progress.phase == LocalRetirementPhase.PENDING) return@withLock phase
            val slot = value(credentialStore.state())
            notClosed()
            val registry = value(work.snapshot())
            notClosed()
            phase = when {
                slot.owner == null && registry.originBinding == null -> PrivateSessionPhase.SIGNED_OUT
                slot.owner != null && registry.scope == slot.owner && registry.originBinding != null && !registry.retiring ->
                    PrivateSessionPhase.RESTORE_REQUIRED
                else -> PrivateSessionPhase.RECOVERY_REQUIRED
            }
            phase
        }
    }

    /** Calls the configured real verifier; no existing occupied slot or guest is overwritten. */
    suspend fun create(): PortResult<PrivateSessionAccess> = compose(PrivateSessionPhase.SIGNED_OUT) { token ->
        val plannedStore = credentialStore as? PlannedCredentialCreateStore ?: fail(FailureReason.NOT_CONFIGURED)
        val verified = value(verifier.acquire())
        checkAttempt(token)
        validCredentials(verified)
        ensureControl()
        checkAttempt(token)
        val slot = value(credentialStore.state())
        val index = value(work.snapshot())
        checkAttempt(token)
        if (slot.owner != null || index.originBinding != null) fail(FailureReason.CONFLICT)
        if (value(data.resume(verified.scope)) != null) fail(FailureReason.CONFLICT)
        checkAttempt(token)
        // From the first possibly committed write onward, cancellation is a repair state. These
        // stores are not one transaction; do not claim rollback or retry with another identity.
        wroteInitialState = true
        val credential = value(commitCredentialCreate(control, plannedStore, slot.revision, verified) { checkAttempt(token) })
        checkAttempt(token)
        if (credential.scope != verified.scope) fail(FailureReason.STALE_SESSION)
        val store = value(data.activate(credential.scope))
        checkAttempt(token)
        phase = PrivateSessionPhase.COMPOSING
        composingScope = credential.scope
        val lease = boundary.activate(credential.scope)
        val workBinding = value(work.createOrigin(lease, index.revision))
        checkAttempt(token, lease)
        val target = value(data.captureRetirement(credential.scope)) ?: fail(FailureReason.STORAGE_FAILURE)
        checkAttempt(token, lease)
        val record = SessionActivationRecord(credential.scope, credential.incarnation, workBinding.originBinding, target, configurationBinding)
        persistBinding(store, record)
        checkAttempt(token, lease)
        publish(token, credential, lease, store, workBinding, record, PrivateSessionAccessMode.ONLINE)
    }

    /** Restores the exact durable binding, never joins independent stores by scope alone. */
    suspend fun restore(): PortResult<PrivateSessionAccess> = compose(PrivateSessionPhase.RESTORE_REQUIRED) { token ->
        ensureControl()
        checkAttempt(token)
        val slot = value(credentialStore.state())
        val scope = slot.owner ?: fail(FailureReason.STORAGE_FAILURE)
        val store = value(data.resume(scope)) ?: fail(FailureReason.STORAGE_FAILURE)
        val persisted = value(store.read(scope, BINDING_KEY)) ?: fail(FailureReason.STORAGE_FAILURE)
        checkAttempt(token)
        if (persisted.schemaVersion != 1) fail(FailureReason.STORAGE_FAILURE)
        val record = try { SessionActivationCodec.decode(persisted.payload) }
            catch (_: SessionActivationFormatException) { fail(FailureReason.STORAGE_FAILURE) }
        if (record.scope != scope || record.credentialIncarnation != slot.incarnation ||
            record.configurationBinding != configurationBinding) fail(FailureReason.STALE_SESSION)
        assertCurrentTarget(record)
        val index = value(work.snapshot())
        checkAttempt(token)
        if (index.scope != scope || index.originBinding != record.originBinding || index.retiring) fail(FailureReason.STALE_SESSION)
        // Reject a changed issuer/client/API configuration before handing any old secret to a
        // provider adapter, which might otherwise attempt a refresh against the wrong authority.
        val credential = value(credentialStore.read(scope)) ?: fail(FailureReason.STORAGE_FAILURE)
        checkAttempt(token)
        if (credential.scope != scope || credential.incarnation != slot.incarnation) fail(FailureReason.STALE_SESSION)
        validCredentials(credential.credentials)
        val mode = value(verifier.restore(credential))
        checkAttempt(token)
        phase = PrivateSessionPhase.COMPOSING
        composingScope = scope
        val lease = boundary.activate(scope)
        val workBinding = value(work.resume(lease))
        checkAttempt(token, lease)
        if (workBinding.originBinding != record.originBinding) fail(FailureReason.STALE_SESSION)
        // Incomplete scheduling is cleaned, not implicitly re-enqueued. Execution still closed.
        value(work.reconcilePending(workBinding))
        checkAttempt(token, lease)
        publish(token, credential, lease, store, workBinding, record, mode)
    }

    /** Cancel the current provider/restore attempt; never erase a guest or partial setup silently. */
    suspend fun cancelVerification(): PortResult<Unit> = owned {
        if (phase != PrivateSessionPhase.VERIFYING && phase != PrivateSessionPhase.COMPOSING) fail(FailureReason.CONFLICT)
        lifecycleGeneration = Any()
        attempt = null
        boundary.clear()
        composingScope = null
        phase = if (wroteInitialState) PrivateSessionPhase.RECOVERY_REQUIRED else PrivateSessionPhase.STARTUP
    }

    /** Explicit user logout/account switch only; remote revocation is a separate canonical call. */
    suspend fun retire(access: PrivateSessionAccess, operationId: String): PortResult<LocalRetirementProgress> = owned {
        requireActive(access)
        try { requireCredentialUuid(operationId) } catch (_: IllegalArgumentException) { fail(FailureReason.INVALID_DATA) }
        if (operationId == access.retirement.priorCompletedId) fail(FailureReason.CONFLICT)
        lifecycleGeneration = Any()
        phase = PrivateSessionPhase.RETIRING
        active = null
        // Coordinator owns exact lease-clear/latch registration BEFORE its first mutex/I/O wait.
        try {
            val result = value(retirement.retire(access.retirement, operationId))
            notClosed()
            phase = if (result.phase == LocalRetirementPhase.COMPLETE) PrivateSessionPhase.SIGNED_OUT else PrivateSessionPhase.RECOVERY_REQUIRED
            result
        } finally {
            withContext(NonCancellable) {
                if (boundary.isCurrent(access.lease)) boundary.clear()
                if (phase == PrivateSessionPhase.RETIRING) phase = PrivateSessionPhase.RECOVERY_REQUIRED
            }
        }
    }

    suspend fun install(access: PrivateSessionAccess, kind: NativeWorkKind, logicalId: String,
        installer: suspend (NativeWorkTicket) -> PortResult<Unit>): PortResult<NativeWorkTicket> = owned {
        requireActive(access)
        if (access.mode == PrivateSessionAccessMode.OFFLINE_PRIVATE && kind == NativeWorkKind.WORKER) fail(FailureReason.OFFLINE)
        value(work.install(access.work, kind, logicalId, installer))
    }

    suspend fun cancel(access: PrivateSessionAccess, ticket: NativeWorkTicket): PortResult<Unit> = owned {
        requireActive(access)
        value(work.cancel(access.work, ticket))
    }

    suspend fun runLocalEffect(ticket: NativeWorkTicket, effect: () -> PortResult<Unit>): PortResult<Unit> = owned {
        value(work.runLocalEffect(ticket, effect))
    }

    /** No implicit logout; retain durable state for a future explicitly verified restore. */
    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        if (phase == PrivateSessionPhase.CLOSED) return@withContext PortResult.Value(Unit)
        lifecycleGeneration = Any()
        attempt = null; active = null; composingScope = null
        phase = PrivateSessionPhase.CLOSED
        boundary.clear()
        work.close()
    }

    private suspend fun publish(token: Any, credential: CredentialSnapshot, lease: SessionLease,
        store: PrivateStateStore, workBinding: SessionWorkBinding, record: SessionActivationRecord,
        mode: PrivateSessionAccessMode): PrivateSessionAccess {
        ensureControl()
        checkAttempt(token, lease)
        val current = value(credentialStore.read(credential.scope)) ?: fail(FailureReason.STORAGE_FAILURE)
        checkAttempt(token, lease)
        if (current.incarnation != credential.incarnation || current.revision != credential.revision || current.scope != credential.scope)
            fail(FailureReason.STALE_SESSION)
        assertCurrentTarget(record)
        checkAttempt(token, lease)
        val captured = value(retirement.capture(lease, workBinding.originBinding, credential.incarnation))
        checkAttempt(token, lease)
        if (!sameTarget(captured.dataTarget, record.dataTarget)) fail(FailureReason.STALE_SESSION)
        val transport = CredentialTransportView(credentialStore, boundary, lease, credential.incarnation)
        val result = PrivateSessionAccess(lease, workBinding.originBinding, mode,
            LeasedPrivateStore(store, lease), RuntimeCredentials(transport, lease, mode), workBinding, captured)
        active = result
        phase = PrivateSessionPhase.ACTIVE
        composingScope = null
        return result
    }

    private suspend fun assertCurrentTarget(record: SessionActivationRecord) {
        val current = value(data.captureRetirement(record.scope)) ?: fail(FailureReason.STORAGE_FAILURE)
        // validateRetirement intentionally permits retired targets; ACTIVE restore needs equality.
        if (!sameTarget(current, record.dataTarget)) fail(FailureReason.STALE_SESSION)
        value(data.validateRetirement(record.scope, record.dataTarget))
    }

    private suspend fun persistBinding(store: PrivateStateStore, record: SessionActivationRecord) {
        val payload = try { SessionActivationCodec.encode(record) }
            catch (_: SessionActivationFormatException) { fail(FailureReason.INVALID_DATA) }
        val result = store.commit(record.scope, listOf(StoreMutation.Put(BINDING_KEY, null, 1, payload)))
        if (result is PortResult.Failure && result.reason != FailureReason.OUTCOME_UNKNOWN) fail(result.reason)
        val observed = value(store.read(record.scope, BINDING_KEY)) ?: fail(FailureReason.OUTCOME_UNKNOWN)
        if (observed.schemaVersion != 1 || !sameBytes(observed.payload, payload)) fail(FailureReason.OUTCOME_UNKNOWN)
        if (result is PortResult.Value && result.value[BINDING_KEY] != observed.revision) fail(FailureReason.STORAGE_FAILURE)
    }

    private suspend fun ensureControl() {
        val entry = value(control.read()) ?: fail(FailureReason.STORAGE_FAILURE)
        if (entry.revision <= 0) fail(FailureReason.STORAGE_FAILURE)
        val state = try { RetirementCodec.decode(entry.payload) } catch (_: Exception) { fail(FailureReason.STORAGE_FAILURE) }
        if (state.blocksAccess()) fail(FailureReason.CONFLICT)
    }

    private suspend fun workAdmission(scope: StorageScope): PortResult<Boolean> = owned {
        fun eligible() = (phase == PrivateSessionPhase.COMPOSING && composingScope == scope) ||
            (phase == PrivateSessionPhase.ACTIVE && active?.scope == scope && boundary.isCurrent(active!!.lease))
        if (!eligible()) false else { ensureControl(); eligible() }
    }

    private suspend fun workExecution(scope: StorageScope, origin: String, logicalId: String, ticket: NativeWorkTicket): PortResult<Boolean> = owned {
        val selected = active ?: return@owned false
        if (!isActive(selected) || selected.scope != scope || selected.originBinding != origin ||
            (selected.mode == PrivateSessionAccessMode.OFFLINE_PRIVATE && ticket.kind == NativeWorkKind.WORKER)) return@owned false
        ensureControl()
        if (!isActive(selected)) return@owned false
        val allowed = value(domainExecution.allowed(scope, origin, logicalId, ticket))
        allowed && isActive(selected)
    }

    private suspend fun compose(required: PrivateSessionPhase, action: suspend (Any) -> PrivateSessionAccess): PortResult<PrivateSessionAccess> = owned {
        mutex.withLock {
            if (phase != required) fail(FailureReason.CONFLICT)
            lifecycleGeneration = Any()
            val token = Any()
            attempt = token; wroteInitialState = false
            phase = PrivateSessionPhase.VERIFYING
            var succeeded = false
            try { action(token).also { succeeded = true } }
            finally {
                withContext(NonCancellable) {
                    if (!succeeded && attempt === token) {
                        boundary.clear(); active = null; composingScope = null
                        phase = PrivateSessionPhase.RECOVERY_REQUIRED
                    }
                    if (attempt === token) attempt = null
                }
            }
        }
    }

    private fun checkAttempt(token: Any, lease: SessionLease? = null) {
        if (attempt !== token || phase == PrivateSessionPhase.CLOSED || (lease != null && !boundary.isCurrent(lease))) fail(FailureReason.STALE_SESSION)
    }
    private fun notClosed() { if (phase == PrivateSessionPhase.CLOSED) fail(FailureReason.STORAGE_FAILURE) }
    private fun requireInspectable() {
        notClosed()
        if (phase !in setOf(PrivateSessionPhase.STARTUP, PrivateSessionPhase.SIGNED_OUT,
                PrivateSessionPhase.RESTORE_REQUIRED, PrivateSessionPhase.RECOVERY_REQUIRED) || boundary.current() != null)
            fail(FailureReason.CONFLICT)
    }
    private fun isActive(access: PrivateSessionAccess) = phase == PrivateSessionPhase.ACTIVE && active === access && boundary.isCurrent(access.lease)
    private fun requireActive(access: PrivateSessionAccess) { if (!isActive(access)) fail(FailureReason.STALE_SESSION) }

    private inner class LeasedPrivateStore(private val delegate: PrivateStateStore, private val lease: SessionLease) : PrivateStateStore {
        private suspend fun <T> use(action: suspend () -> PortResult<T>): PortResult<T> = owned {
            val selected = active ?: fail(FailureReason.STALE_SESSION)
            if (selected.lease !== lease) fail(FailureReason.STALE_SESSION)
            requireActive(selected)
            ensureControl()
            requireActive(selected)
            val result = action()
            requireActive(selected)
            value(result)
        }
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> = use {
            if (key == BINDING_KEY) PortResult.Failure(FailureReason.FORBIDDEN) else delegate.read(scope, key)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            val captured = mutations.take(65)
            return use {
                if (captured.any { it.key == BINDING_KEY }) PortResult.Failure(FailureReason.FORBIDDEN) else delegate.commit(scope, captured)
            }
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = PortResult.Failure(FailureReason.NOT_CONFIGURED)
    }

    private inner class RuntimeCredentials(private val delegate: SecureCredentialStore, private val lease: SessionLease,
        private val mode: PrivateSessionAccessMode) : SecureCredentialStore {
        override suspend fun read(scope: StorageScope): PortResult<StoredCredentials?> = owned {
            val selected = active ?: fail(FailureReason.STALE_SESSION)
            if (selected.lease !== lease) fail(FailureReason.STALE_SESSION)
            requireActive(selected)
            if (mode == PrivateSessionAccessMode.OFFLINE_PRIVATE) fail(FailureReason.OFFLINE)
            ensureControl()
            requireActive(selected)
            val result = delegate.read(scope)
            requireActive(selected)
            value(result)
        }
        override suspend fun replace(scope: StorageScope, credentials: StoredCredentials): PortResult<Unit> = PortResult.Failure(FailureReason.NOT_CONFIGURED)
        override suspend fun erase(scope: StorageScope): PortResult<Unit> = PortResult.Failure(FailureReason.NOT_CONFIGURED)
    }

    private suspend fun <T> owned(action: suspend () -> T): PortResult<T> = withContext(dispatcher) {
        try {
            if (phase == PrivateSessionPhase.CLOSED) fail(FailureReason.STORAGE_FAILURE)
            PortResult.Value(action())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SessionRuntimeFailure) { PortResult.Failure(failure.reason) }
        catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
    }

    companion object {
        private val BINDING_KEY = RecordKey("session-activation", "binding-v1")

        /** Opening never authenticates. Caller closes all native resources separately. */
        suspend fun open(control: SessionControlStore, workControl: SessionControlStore,
            data: EncryptedStateDatabase, credentials: IncarnationCredentialStore,
            boundary: SessionBoundary, dispatcher: CoroutineDispatcher, configurationBinding: String,
            verifier: NativeSessionVerifier, cancellation: NativeWorkCancellationPort,
            ids: NativeWorkIdSource, domainExecution: NativeWorkExecutionPolicy): PortResult<PrivateSessionRuntime> {
            if (!configurationBinding.matches(Regex("[0-9a-f]{64}")) || control === workControl) return PortResult.Failure(FailureReason.INVALID_DATA)
            var opened: SessionWorkRegistry? = null
            try {
                return withContext(dispatcher) {
                    if (boundary.current() != null) return@withContext PortResult.Failure(FailureReason.CONFLICT)
                    val runtime = PrivateSessionRuntime(control, data, credentials, boundary, dispatcher, configurationBinding, verifier, domainExecution)
                    when (val index = SessionWorkRegistry.open(workControl, boundary, dispatcher, cancellation, ids,
                        NativeWorkAdmissionPolicy(runtime::workAdmission), NativeWorkExecutionPolicy(runtime::workExecution))) {
                        is PortResult.Failure -> index
                        is PortResult.Value -> {
                            opened = index.value
                            if (boundary.current() != null) {
                                index.value.close()
                                return@withContext PortResult.Failure(FailureReason.CONFLICT)
                            }
                            runtime.work = index.value
                            runtime.retirement = LocalRetirementCoordinator(control, data, boundary, dispatcher, credentials, index.value)
                            PortResult.Value(runtime)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + dispatcher) { opened?.close() }
                throw cancelled
            } catch (_: Exception) {
                withContext(NonCancellable + dispatcher) { opened?.close() }
                return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            }
        }

        private fun validCredentials(credentials: StoredCredentials) {
            if (credentials.scope.actorKind == ActorKind.DEMO) fail(FailureReason.INVALID_DATA)
            if (credentials is StoredCredentials.Account && credentials.deviceSessionId == null) fail(FailureReason.NOT_CONFIGURED)
        }
        private fun sameTarget(a: StateRetirementTarget, b: StateRetirementTarget): Boolean {
            val left = a.copyForStorage(); val right = b.copyForStorage()
            return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
        }
        private fun sameBytes(a: PrivateBytes, b: PrivateBytes): Boolean {
            val left = a.copyForCodec(); val right = b.copyForCodec()
            return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
        }
        private fun <T> value(result: PortResult<T>): T = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> fail(result.reason)
        }
        private fun fail(reason: FailureReason): Nothing = throw SessionRuntimeFailure(reason)
    }
}

private class SessionRuntimeFailure(val reason: FailureReason) : Exception("Private session unavailable")
