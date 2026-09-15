package com.feedme.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.PrivateRecord
import com.feedme.core.ports.PrivateStateStore
import com.feedme.core.ports.RecordKey
import com.feedme.core.ports.SessionControlRecord
import com.feedme.core.ports.StorageScope
import com.feedme.core.ports.StoreMutation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owner-fenced encrypted local storage, not authentication, cloud sync or anti-rollback authority.
 * A platform factory owns the private file, native vault and lifetime exclusive sibling-file lock.
 * All connection/vault access is serialized; no network or suspending callback runs in transactions.
 * Cancellation after a write can hide a committed result: reconcile its CAS state, never blindly
 * create a new command identity. The application session owner alone may activate/resume stores.
 */
class EncryptedStateDatabase private constructor(
    private val connection: SQLiteConnection,
    private val vault: StateVault,
    private val dispatcher: CoroutineDispatcher,
    private val onClosed: () -> Unit,
) {
    private val mutex = Mutex()
    private var closed = false
    private var connectionClosed = false
    private var ownershipReleased = false
    private var usable = true
    private val locallyRetired = mutableSetOf<Pair<String, Long>>()

    /**
     * Read-only preparation for one new private-data incarnation. The serialized identity owner
     * must persist this opaque plan independently before commit and allow only one pending plan
     * for this predecessor. Planning grants neither a session lease nor a private-store handle.
     * It never creates an owner key, changes SQLite state or runs unrelated key garbage collection.
     * The caller must not replace an unresolved plan with another plan for the same predecessor.
     */
    suspend fun planActivation(scope: StorageScope): PortResult<StateActivationPlan> = guarded {
        validateActivationScope(scope)
        val plannedVault = vault as? PlannedStateVault ?: fail(FailureReason.NOT_CONFIGURED)
        val tag = ownerTag(scope)
        transaction(writes = false) {
            val prior = owner(tag)
            if (prior?.active == true) fail(FailureReason.CONFLICT)
            validateActivationPredecessor(tag, prior)
            val generation = prior?.generation ?: 0
            if (generation > Long.MAX_VALUE - 2) fail(FailureReason.STORAGE_FAILURE)
            val key = plannedVault.newOwnerKeyId()
            checkKeyId(key)
            if (key == prior?.keyId) fail(FailureReason.CONFLICT)
            validateActivationReferences(tag, key, selected = false)
            if (vault.hasOwnerKey(key)) fail(FailureReason.CONFLICT)
            val unsigned = StateActivationPlanRecord(tag, generation, prior?.keyId, key, ByteArray(32))
            val mac = activationMac(unsigned)
            try { StateActivationPlanCodec.encode(StateActivationPlanRecord(tag, generation, prior?.keyId, key, mac)) }
            finally { mac.fill(0) }
        }
    }

    /**
     * Read-only observation of this exact native-authenticated plan and scope on the live store.
     * No key creation/deletion, garbage collection, selection or scoped handle is performed.
     * Selected/partial status does not prove key usability, record integrity or durable selection;
     * in particular this observation cannot replace a required write acknowledgement.
     */
    suspend fun inspectPlannedActivation(
        scope: StorageScope,
        plan: StateActivationPlan,
    ): PortResult<StateActivationInspection> = guarded {
        val intended = recoveryPlan(scope, plan)
        transaction(writes = false) { StateActivationInspection(activationRecoveryState(intended)) }
    }

    /**
     * Already-open owner metadata for a wider read-only setup bracket. No GC, file factory,
     * cipher/key-usability probe, record decryption or write is performed. The comparison MAC
     * includes the abort receipt revision even when an acknowledged replay leaves status intact.
     * Record payloads are deliberately outside this metadata observation; inspectPlannedBinding
     * must independently validate the sole exact binding when the selected owner is nonempty.
     */
    suspend fun inspectPlannedActivationState(scope: StorageScope, plan: StateActivationPlan): PortResult<StateActivationPlanObservation> = guarded {
        val inventory = vault as? PlannedStateVault ?: fail(FailureReason.NOT_CONFIGURED)
        val intended = recoveryPlan(scope, plan)
        transaction(writes = false) {
            val status = activationRecoveryState(intended)
            val current = owner(intended.ownerTag)
            val receipt = activationAbortReceipt(intended.ownerTag)
            val present = inventory.containsOwnerKey(intended.keyId)
            if ((status == StateActivationStatus.PREPARED || status == StateActivationStatus.ABORTED) && present ||
                (status == StateActivationStatus.PARTIAL || status == StateActivationStatus.ABORTING) && !present)
                fail(FailureReason.CONFLICT)
            val input = frame("feedme.activation-observation.v1", intended.ownerTag,
                intended.priorGeneration.toString(), intended.priorKeyId ?: "", intended.keyId, status.name,
                current?.generation?.toString() ?: "", current?.active?.toString() ?: "", current?.keyId ?: "",
                receipt?.generation?.toString() ?: "", receipt?.keyId ?: "", receipt?.revision?.toString() ?: "", present.toString())
            val fingerprint = try { vault.index(input) } finally { input.fill(0) }
            try {
                if (fingerprint.size != 32) fail(FailureReason.STORAGE_FAILURE)
                StateActivationPlanObservation(status, PrivateBytes(fingerprint))
            } finally { fingerprint.fill(0) }
        }
    }

    /**
     * Exact selected-plan binding observation, without GC, a scoped handle or write authority.
     * The returned target is for this plan's current incarnation. Only zero rows or one reserved,
     * authenticated non-tombstoned binding is accepted; this is not a durability acknowledgement.
     */
    suspend fun inspectPlannedBinding(
        scope: StorageScope,
        plan: StateActivationPlan,
    ): PortResult<StateRecordInspection> = guarded {
        val intended = recoveryPlan(scope, plan)
        transaction(writes = false) { inspectBinding(selectedBindingOwner(intended), soleRecord = true) }
    }

    /**
     * Purpose-fixed pre-publication binding write. The trusted identity owner must already hold
     * an acknowledged exact composite intent. No lease, ordinary store or completion is granted.
     * Every call changes the reserved record revision and encryption AAD, including exact replay;
     * replay cannot replace a different schema/body or any other row/tombstone. A successful real
     * COMMIT and a separate exact readback are both required before returning the detached receipt.
     */
    suspend fun bindPlannedActivation(
        scope: StorageScope,
        plan: StateActivationPlan,
        schemaVersion: Int,
        payload: PrivateBytes,
    ): PortResult<StateRecordInspection> {
        val plaintext = payload.copyForCodec()
        if (schemaVersion !in 1..2 || plaintext.size !in 1..MAX_BINDING_BYTES) {
            plaintext.fill(0)
            return PortResult.Failure(FailureReason.INVALID_DATA)
        }
        val caller = currentCoroutineContext()
        return try {
            guarded {
                caller.ensureActive()
                val intended = recoveryPlan(scope, plan)
                val revision = transaction(writes = true) {
                    val target = selectedBindingOwner(intended)
                    val prior = inspectBinding(target, soleRecord = true).record
                    if (prior != null && (prior.schemaVersion != schemaVersion || !bindingPayloadEquals(prior, plaintext)))
                        fail(FailureReason.CONFLICT)
                    val nextRevision = next(prior?.revision ?: 0)
                    val tag = recordTag(target.tag, ACTIVATION_BINDING_KEY)
                    val encrypted = vault.seal(target.keyId, plaintext,
                        aad(target.tag, target.generation, tag, nextRevision, schemaVersion))
                    try {
                        if (encrypted.size !in MIN_CIPHERTEXT_BYTES..MAX_BINDING_BYTES + 64)
                            fail(FailureReason.STORAGE_FAILURE)
                        caller.ensureActive()
                        execute("INSERT INTO feedme_records(owner_tag,record_tag,revision,schema_version,payload) VALUES(?,?,?,?,?) " +
                            "ON CONFLICT(owner_tag,record_tag) DO UPDATE SET revision=excluded.revision,schema_version=excluded.schema_version,payload=excluded.payload") {
                            bindText(1, target.tag); bindText(2, tag); bindLong(3, nextRevision)
                            bindLong(4, schemaVersion.toLong()); bindBlob(5, encrypted)
                        }
                        caller.ensureActive()
                    } finally { encrypted.fill(0) }
                    nextRevision
                }
                // A failed/unknown COMMIT exits before readback. Matching bytes cannot give it credit.
                caller.ensureActive()
                val receipt = transaction(writes = false) {
                    val observed = inspectBinding(selectedBindingOwner(intended), soleRecord = true)
                    val record = observed.record ?: fail(FailureReason.OUTCOME_UNKNOWN)
                    if (record.revision > revision) fail(FailureReason.CONFLICT)
                    if (record.revision != revision || record.schemaVersion != schemaVersion ||
                        !bindingPayloadEquals(record, plaintext)) fail(FailureReason.OUTCOME_UNKNOWN)
                    observed
                }
                caller.ensureActive()
                receipt
            }
        } finally { plaintext.fill(0) }
    }

    /**
     * Trusted session-owner publication/restore only, AFTER independent identity verification
     * and acknowledged control completion. This method cannot verify either prerequisite itself.
     * It checks the exact current target and reserved binding receipt without GC or writes, and
     * permits later ordinary domain records. Never expose it as a caller-controlled login API.
     */
    suspend fun resumeBound(
        scope: StorageScope,
        target: StateRetirementTarget,
        expectedBinding: PrivateRecord,
    ): PortResult<PrivateStateStore> {
        val expected = expectedBinding.payload.copyForCodec()
        if (expectedBinding.schemaVersion !in 1..2 || expected.size !in 1..MAX_BINDING_BYTES) {
            expected.fill(0)
            return PortResult.Failure(FailureReason.INVALID_DATA)
        }
        val caller = currentCoroutineContext()
        return try {
            guarded {
                caller.ensureActive()
                validateActivationScope(scope)
                val exact = decodeRetirement(target)
                if (ownerTag(scope) != exact.tag) fail(FailureReason.STALE_SESSION)
                val result = transaction(writes = false) {
                    val current = owner(exact.tag)
                    if (current != Owner(exact.generation, true, exact.keyId) ||
                        (exact.tag to exact.generation) in locallyRetired) fail(FailureReason.STALE_SESSION)
                    validateActivationReferences(exact.tag, exact.keyId, selected = true)
                    requireKey(exact.keyId)
                    val record = inspectBinding(exact, soleRecord = false).record ?: fail(FailureReason.STALE_SESSION)
                    if (record.revision != expectedBinding.revision || record.schemaVersion != expectedBinding.schemaVersion ||
                        !bindingPayloadEquals(record, expected)) fail(FailureReason.STALE_SESSION)
                    ScopedStore(scope, exact.tag, exact.generation, exact.keyId)
                }
                caller.ensureActive()
                result
            }
        } finally { expected.fill(0) }
    }

    private fun selectedBindingOwner(intended: StateActivationPlanRecord): Retirement {
        val state = activationRecoveryState(intended)
        if (state != StateActivationStatus.SELECTED_EMPTY && state != StateActivationStatus.SELECTED_NONEMPTY)
            fail(FailureReason.STALE_SESSION)
        requireKey(intended.keyId)
        return Retirement(intended.ownerTag, intended.selectedGeneration, intended.keyId)
    }

    /** Caller holds this manager mutex and an exact-owner transaction. Never decrypt other rows. */
    private fun inspectBinding(target: Retirement, soleRecord: Boolean): StateRecordInspection {
        val tag = recordTag(target.tag, ACTIVATION_BINDING_KEY)
        if (soleRecord) query("SELECT count(*) FROM feedme_records WHERE owner_tag=? AND record_tag<>?", {
            bindText(1, target.tag); bindText(2, tag)
        }) { if (!step() || getLong(0) != 0L) fail(FailureReason.CONFLICT) }
        val row = record(target.tag, tag)
        if (row != null && (row.payload == null || row.schemaVersion !in 1..2)) fail(FailureReason.CONFLICT)
        if (row?.payload != null && row.payload.size > MAX_BINDING_BYTES + 64) fail(FailureReason.STORAGE_FAILURE)
        val binding = if (row == null) null else {
            readRecord(target.tag, target.generation, target.keyId, ACTIVATION_BINDING_KEY)
                ?: fail(FailureReason.STORAGE_FAILURE)
        }
        if (binding != null) {
            val bytes = binding.payload.copyForCodec()
            try { if (bytes.size !in 1..MAX_BINDING_BYTES) fail(FailureReason.STORAGE_FAILURE) }
            finally { bytes.fill(0) }
        }
        return StateRecordInspection(encodeRetirement(target), binding)
    }

    private fun bindingPayloadEquals(record: PrivateRecord, expected: ByteArray): Boolean {
        val current = record.payload.copyForCodec()
        return try { equalBytes(current, expected) } finally { current.fill(0) }
    }

    /**
     * Commit only an independently persisted, native-authenticated plan from its exact predecessor.
     * This is a selection acknowledgement, not activation/restore authority: no scoped handle or
     * retirement capability is returned. Exact selected replay preserves all records and requires
     * the existing key; a missing selected key is never regenerated. An interrupted key creation
     * remains owned by the original plan and is never automatically deleted on failure/cancellation.
     * Selected replay checks current selection, not the integrity of every stored record or a
     * cross-store setup receipt. Existing-only abort and the complete setup journal are separate.
     */
    suspend fun commitPlannedActivation(scope: StorageScope, plan: StateActivationPlan): PortResult<Unit> = guarded {
        validateActivationScope(scope)
        val plannedVault = vault as? PlannedStateVault ?: fail(FailureReason.NOT_CONFIGURED)
        val intended = authenticateActivationPlan(plan)
        val tag = ownerTag(scope)
        if (intended.ownerTag != tag) fail(FailureReason.INVALID_DATA)
        transaction(writes = true) {
            val current = owner(tag)
            if (current?.active == true && current.generation == intended.selectedGeneration && current.keyId == intended.keyId) {
                if ((tag to current.generation) in locallyRetired) fail(FailureReason.STALE_SESSION)
                validateActivationReferences(tag, intended.keyId, selected = true)
                requireKey(intended.keyId)
            } else {
                val expected = if (intended.priorGeneration == 0L) null
                    else Owner(intended.priorGeneration, false, intended.priorKeyId!!)
                if (current != expected) fail(FailureReason.STALE_SESSION)
                validateActivationPredecessor(tag, current)
                validateActivationReferences(tag, intended.keyId, selected = false)
                // A prior call may have created exactly this key before owner COMMIT failed. It
                // can be reused only while its exact predecessor and zero-reference checks hold.
                if (!vault.hasOwnerKey(intended.keyId)) plannedVault.createOwnerKey(intended.keyId)
                requireKey(intended.keyId)
                if (owner(tag) != expected) fail(FailureReason.STALE_SESSION)
                validateActivationPredecessor(tag, expected)
                validateActivationReferences(tag, intended.keyId, selected = false)
                execute(
                    "INSERT INTO feedme_owners(owner_tag,generation,active,key_id) VALUES(?,?,1,?) " +
                        "ON CONFLICT(owner_tag) DO UPDATE SET generation=excluded.generation,active=1,key_id=excluded.key_id",
                ) { bindText(1, tag); bindLong(2, intended.selectedGeneration); bindText(3, intended.keyId) }
            }
        }
    }

    /** Explicit new login/local identity activation; never invoke from a delayed response callback. */
    suspend fun activate(scope: StorageScope): PortResult<PrivateStateStore> = guarded {
        cleanupKeys()
        val ownerTag = ownerTag(scope)
        var createdKey: String? = null
        try {
            transaction(writes = true) {
                val prior = owner(ownerTag)
                if (prior?.active == true) {
                    requireKey(prior.keyId)
                    fail(FailureReason.CONFLICT)
                }
                val generation = next(prior?.generation ?: 0)
                val key = vault.createOwnerKey().also { createdKey = it }
                checkKeyId(key)
                requireKey(key)
                execute(
                    "INSERT INTO feedme_owners(owner_tag,generation,active,key_id) VALUES(?,?,1,?) " +
                        "ON CONFLICT(owner_tag) DO UPDATE SET generation=excluded.generation,active=1,key_id=excluded.key_id",
                ) { bindText(1, ownerTag); bindLong(2, generation); bindText(3, key) }
                ScopedStore(scope, ownerTag, generation, key)
            }
        } catch (failure: StoreFailure) {
            // Never destroy a key whose owner row may have committed. A pre-commit crash can leave
            // an unused vault key, but no record encrypted with it; audited orphan GC is a later gate.
            if (failure.reason != FailureReason.OUTCOME_UNKNOWN) {
                createdKey?.let { try { vault.deleteOwnerKey(it) } catch (_: Exception) { /* no data under this key */ } }
            }
            throw failure
        }
    }

    /** Existing verified identity only. Missing/retired owners stay missing; keys are never recreated. */
    suspend fun resume(scope: StorageScope): PortResult<PrivateStateStore?> = guarded {
        cleanupKeys()
        val tag = ownerTag(scope)
        transaction(writes = false) {
            val current = owner(tag)
            if (current == null || !current.active || (tag to current.generation) in locallyRetired) null
            else {
                requireKey(current.keyId)
                ScopedStore(scope, tag, current.generation, current.keyId)
            }
        }
    }

    /**
     * Read-only diagnostic of one exact owner and record. A missing/inactive owner says nothing
     * about other owners. The target and decrypted record come from the same read transaction;
     * inspection never returns a handle, activates an owner, runs key GC or changes local fences.
     */
    suspend fun inspectRecord(scope: StorageScope, key: RecordKey): PortResult<StateRecordInspection> {
        if (!validKey(key)) return PortResult.Failure(FailureReason.INVALID_DATA)
        return guarded {
            val tag = ownerTag(scope)
            transaction(writes = false) {
                val current = owner(tag)
                if (current == null || !current.active) StateRecordInspection(null, null)
                else {
                    if ((tag to current.generation) in locallyRetired) fail(FailureReason.STALE_SESSION)
                    requireKey(current.keyId)
                    next(current.generation)
                    val target = encodeRetirement(Retirement(tag, current.generation, current.keyId))
                    StateRecordInspection(target, readRecord(tag, current.generation, current.keyId, key))
                }
            }
        }
    }

    /**
     * Read-only bounded inspection of one exact owner. Every row counts, including tombstones
     * and records with unrecognized schemas. Never enumerates owners or executes pending key GC.
     * Missing/inactive owners with stray rows are corrupt, not proof of empty private state.
     */
    suspend fun inspectOwnerState(scope: StorageScope): PortResult<StateOwnerInspection> = guarded {
        val tag = ownerTag(scope)
        transaction(writes = false) {
            val current = owner(tag)
            val hasRecords = hasOwnerRecords(tag)
            if (current == null || !current.active) {
                if (hasRecords) fail(FailureReason.STORAGE_FAILURE)
                StateOwnerInspection(null, false)
            } else {
                if ((tag to current.generation) in locallyRetired) fail(FailureReason.STALE_SESSION)
                requireKey(current.keyId)
                next(current.generation)
                StateOwnerInspection(encodeRetirement(Retirement(tag, current.generation, current.keyId)), hasRecords)
            }
        }
    }

    /**
     * Read-only capture for an independently persisted logout barrier. Missing/inactive owners
     * remain missing; capturing never activates an owner, repairs a key or performs garbage GC.
     */
    suspend fun captureRetirement(scope: StorageScope): PortResult<StateRetirementTarget?> = guarded {
        val tag = ownerTag(scope)
        transaction(writes = false) {
            val current = owner(tag)
            if (current == null || !current.active) null else {
                if ((tag to current.generation) in locallyRetired) fail(FailureReason.STALE_SESSION)
                requireKey(current.keyId)
                next(current.generation) // Reject an unrepresentable retirement before handing out a target.
                encodeRetirement(Retirement(tag, current.generation, current.keyId))
            }
        }
    }

    /**
     * Explicit cleanup of a captured incarnation, including after its key disappeared before a
     * failed SQLite retirement. This is not identity restore and never creates keys or handles.
     * An authentic old token cannot retire any newer incarnation of the same owner.
     */
    suspend fun recoverRetirement(target: StateRetirementTarget): PortResult<Unit> = guarded {
        retire(decodeRetirement(target))
    }

    /**
     * Read-only preflight before independent credential/work cleanup. Authenticates the captured
     * target and its scope/incarnation without fencing handles, deleting keys or repairing state.
     * A missing original key is allowed: deletion may have preceded a failed retirement COMMIT.
     */
    suspend fun validateRetirement(scope: StorageScope, target: StateRetirementTarget): PortResult<Unit> = guarded {
        val decoded = decodeRetirement(target)
        if (ownerTag(scope) != decoded.tag) fail(FailureReason.STALE_SESSION)
        transaction(writes = false) {
            validateRetirementReferences(decoded, retirementOwner(decoded))
        }
    }

    /**
     * Read-only preflight for an explicitly selected empty setup. Zero means no rows at all,
     * including tombstones, not merely an absent activation binding. A later action must check
     * again; this preflight grants no lease or right to erase newly written private state.
     */
    suspend fun validateEmptyRetirement(scope: StorageScope, target: StateRetirementTarget): PortResult<Unit> = guarded {
        val decoded = decodeRetirement(target)
        if (ownerTag(scope) != decoded.tag) fail(FailureReason.STALE_SESSION)
        transaction(writes = false) { validateEmptyRetirement(decoded, retirementOwner(decoded)) }
    }

    /**
     * Exact-incarnation empty-only retirement. Rechecks every row before fencing/deleting under
     * this manager's mutex; it never upgrades an earlier empty observation into permission to
     * erase data. The original target remains retryable after key deletion or an uncertain COMMIT.
     */
    suspend fun recoverEmptyRetirement(target: StateRetirementTarget): PortResult<Unit> = guarded {
        retire(decodeRetirement(target), emptyOnly = true)
    }

    /**
     * Purpose-fixed internal seam for the independently owned work wrapper. No database/vault or
     * owner capability escapes. The captured scoped handle must belong to this exact manager;
     * re-querying an owner must never silently rebind a stale wrapper to a newer incarnation.
     * A null proof signs only an exact-current predecessor; a non-null proof authenticates it.
     */
    internal suspend fun authenticateWorkOriginPlan(
        store: PrivateStateStore,
        expectedRevision: Long,
        proposal: PrivateBytes,
        proof: PrivateBytes?,
        predecessor: SessionControlRecord?,
    ): PortResult<PrivateBytes> {
        val caller = currentCoroutineContext()
        return guarded {
            caller.ensureActive()
            val proposed = proposal.copyForCodec()
            val supplied = proof?.copyForCodec()
            val previous = predecessor?.payload?.copyForCodec()
            try {
                if (expectedRevision !in 1..Long.MAX_VALUE - 2 || proposed.size !in 1..4096 ||
                    (supplied != null && supplied.size != 64) ||
                    (predecessor != null && (predecessor.revision != expectedRevision || previous!!.size !in 1..32_768)) ||
                    (supplied == null && predecessor == null)) fail(FailureReason.INVALID_DATA)
                val owned = store as? ScopedStore ?: fail(FailureReason.INVALID_DATA)
                if (!owned.belongsTo(this)) fail(FailureReason.INVALID_DATA)
                val result = transaction(writes = false) {
                    owned.workOriginProof(expectedRevision, proposed, supplied, previous)
                }
                caller.ensureActive()
                result
            } finally { proposed.fill(0); supplied?.fill(0); previous?.fill(0) }
        }
    }

    /**
     * Trusted composite owner only, AFTER acknowledgement of the exact independent abort intent.
     * Null preserves empty-only recovery. Non-null permits only the sole reserved schema2 binding
     * with these byte-exact expected contents; the session layer must derive them from the original
     * operation/scope/configuration/credential/work/native-data identities. This primitive neither
     * decodes that session contract nor authenticates confirmation and must not be exposed to UI.
     * Binding validation/removal and the changed consumed-plan receipt share one write transaction.
     * Every retry, even ABORTED, requires a fresh COMMIT before deleting only the exact planned key.
     * No GC, allocation, ordinary scoped handle or deletion of any other row/tombstone is allowed.
     */
    suspend fun abortPlannedActivation(
        scope: StorageScope,
        plan: StateActivationPlan,
        expectedBinding: PrivateBytes? = null,
    ): PortResult<Unit> {
        val expected = expectedBinding?.copyForCodec()
        if (expected != null && expected.size !in 1..MAX_BINDING_BYTES) {
            expected.fill(0)
            return PortResult.Failure(FailureReason.INVALID_DATA)
        }
        val caller = currentCoroutineContext()
        return try {
            guarded {
                caller.ensureActive()
                val intended = recoveryPlan(scope, plan)
                val revision = transaction(writes = true) {
                    val state = activationRecoveryState(intended)
                    if (state == StateActivationStatus.SELECTED_NONEMPTY) {
                        if (expected == null) fail(FailureReason.CONFLICT)
                        val binding = inspectBinding(selectedBindingOwner(intended), soleRecord = true).record
                            ?: fail(FailureReason.CONFLICT)
                        if (binding.schemaVersion != 2 || !bindingPayloadEquals(binding, expected))
                            fail(FailureReason.CONFLICT)
                        caller.ensureActive()
                        val tag = recordTag(intended.ownerTag, ACTIVATION_BINDING_KEY)
                        execute("DELETE FROM feedme_records WHERE owner_tag=? AND record_tag=? AND revision=? AND schema_version=2") {
                            bindText(1, intended.ownerTag); bindText(2, tag); bindLong(3, binding.revision)
                        }
                        if (scalarLong("SELECT changes()") != 1L || hasOwnerRecords(intended.ownerTag))
                            fail(FailureReason.CONFLICT)
                    }
                    caller.ensureActive()
                    val previous = activationAbortReceipt(intended.ownerTag)
                    val changed = if (previous?.generation == intended.consumedGeneration &&
                        previous.keyId == intended.keyId) next(previous.revision) else 1L
                    execute(
                        "INSERT INTO feedme_owners(owner_tag,generation,active,key_id) VALUES(?,?,0,?) " +
                            "ON CONFLICT(owner_tag) DO UPDATE SET generation=excluded.generation,active=0,key_id=excluded.key_id",
                    ) { bindText(1, intended.ownerTag); bindLong(2, intended.consumedGeneration); bindText(3, intended.keyId) }
                    execute(
                        "INSERT INTO feedme_activation_aborts(owner_tag,generation,key_id,revision) VALUES(?,?,?,?) " +
                            "ON CONFLICT(owner_tag) DO UPDATE SET generation=excluded.generation,key_id=excluded.key_id,revision=excluded.revision",
                    ) {
                        bindText(1, intended.ownerTag); bindLong(2, intended.consumedGeneration)
                        bindText(3, intended.keyId); bindLong(4, changed)
                    }
                    caller.ensureActive()
                    changed
                }
                // Failed/unknown COMMIT never reaches readback or key deletion. Matching visible
                // rows cannot promote an uncertain result to an acknowledgement.
                caller.ensureActive()
                transaction(writes = true) {
                    val state = activationRecoveryState(intended)
                    if (state != StateActivationStatus.ABORTING && state != StateActivationStatus.ABORTED)
                        fail(FailureReason.STALE_SESSION)
                    val receipt = activationAbortReceipt(intended.ownerTag)
                    if (receipt != ActivationAbortReceipt(intended.consumedGeneration, intended.keyId, revision))
                        fail(FailureReason.CONFLICT)
                    caller.ensureActive()
                    locallyRetired += intended.ownerTag to intended.selectedGeneration
                    vault.deleteOwnerKey(intended.keyId)
                    if (containsActivationKey(intended.keyId)) fail(FailureReason.STORAGE_FAILURE)
                    caller.ensureActive()
                }
                caller.ensureActive()
            }
        } finally { expected?.fill(0) }
    }

    /** Never release native ownership until SQLite close acknowledges; failed close stays retryable. */
    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        mutex.withLock {
            closed = true
            try {
                if (!connectionClosed) { connection.close(); connectionClosed = true }
                if (!ownershipReleased) { onClosed(); ownershipReleased = true }
                PortResult.Value(Unit)
            } catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
        }
    }

    private inner class ActivationRecovery(
        private val scope: StorageScope,
        private val plan: StateActivationPlan,
    ) : StateActivationRecoveryHandle {
        override suspend fun inspect(): PortResult<StateActivationInspection> = guarded {
            val intended = recoveryPlan(scope, plan)
            transaction(writes = false) { StateActivationInspection(activationRecoveryState(intended)) }
        }

        override suspend fun abort(): PortResult<Unit> = abortPlannedActivation(scope, plan)

        override suspend fun abortBound(expectedBinding: PrivateBytes): PortResult<Unit> =
            abortPlannedActivation(scope, plan, expectedBinding)

        override suspend fun close(): PortResult<Unit> = this@EncryptedStateDatabase.close()
        override fun toString(): String = "StateActivationRecoveryHandle(<redacted>)"
    }

    /** Allocated before any suspended initialization; failed open never drops the close owner. */
    private inner class RetainedActivationRecovery(
        private val scope: StorageScope,
        plan: StateActivationPlan,
    ) : StateActivationRecoveryOwner {
        private val plan = StateActivationPlan(plan.copyForStorage())
        private val ownerMutex = Mutex()
        private var attempted = false
        private var ready = false
        private var closeRequested = false

        override suspend fun open(): PortResult<Unit> {
            var admitted = false
            try {
                val result = withContext(dispatcher) {
                    ownerMutex.withLock {
                        if (attempted) return@withLock PortResult.Failure(FailureReason.CONFLICT)
                        attempted = true
                        admitted = true
                        val result = guarded {
                            val intended = recoveryPlan(scope, plan)
                            initializeExistingRecovery()
                            transaction(writes = false) { activationRecoveryState(intended) }
                            Unit
                        }
                        currentCoroutineContext().ensureActive()
                        result
                    }
                }
                if (!admitted || result is PortResult.Failure) return result
                // Publish only after returning to the caller dispatcher. Other coroutines cannot
                // use READY during a prompt-cancelled initialization handoff. Close wins the gap.
                return ownerMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (closeRequested) PortResult.Failure(FailureReason.STALE_SESSION)
                    else { ready = true; result }
                }
            } catch (cancelled: CancellationException) {
                // Also cover prompt cancellation while handing READY back to the caller.
                withContext(NonCancellable + dispatcher) {
                    ownerMutex.withLock {
                        if (admitted || !attempted) { attempted = true; ready = false; closeRequested = true }
                    }
                }
                throw cancelled
            }
        }

        private suspend fun <T> whenReady(action: suspend () -> PortResult<T>): PortResult<T> =
            withContext(dispatcher) {
                ownerMutex.withLock {
                    if (!ready) PortResult.Failure(FailureReason.STALE_SESSION) else action()
                }
            }

        override suspend fun inspect() = whenReady { inspectPlannedActivationState(scope, plan) }
        override suspend fun binding() = whenReady { inspectPlannedBinding(scope, plan) }
        override suspend fun abort(expectedBinding: PrivateBytes?) = whenReady {
            abortPlannedActivation(scope, plan, expectedBinding)
        }
        override suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
            ownerMutex.withLock {
                attempted = true
                ready = false
                closeRequested = true
                this@EncryptedStateDatabase.close()
            }
        }
        override fun toString() = "StateActivationRecoveryOwner(<redacted>)"
    }

    /**
     * The low-level session codec seam. It never returns a ScopedStore or signs a plan. Its sole
     * row and captured owner are revalidated in the same transaction as every read/proof/write.
     */
    @OptIn(WorkRecoveryCompositionApi::class)
    private inner class RetainedWorkRecovery :
        RetainedFixedLedgerRecovery(WORK_ORIGIN_SCOPE, WORK_ORIGIN_KEY), ExistingSessionWorkRecoveryStore {
        override suspend fun verifyOriginPlan(expectedRevision: Long, proposal: PrivateBytes, proof: PrivateBytes) =
            verify(expectedRevision, proposal, proof, null)
        override suspend fun verifyOriginPredecessor(expected: SessionControlRecord, proposal: PrivateBytes, proof: PrivateBytes) =
            verify(expected.revision, proposal, proof, expected)
        override fun toString() = "ExistingSessionWorkRecoveryStore(<redacted>)"
    }

    @OptIn(SessionControlRecoveryCompositionApi::class)
    private inner class RetainedControlRecovery :
        RetainedFixedLedgerRecovery(CONTROL_RECOVERY_SCOPE, CONTROL_RECOVERY_KEY), ExistingSessionControlRecoveryStore {
        override fun toString() = "ExistingSessionControlRecoveryStore(<redacted>)"
    }

    /** Private implementation accepts only the two fixed companion-owned ledger purposes. */
    private open inner class RetainedFixedLedgerRecovery(
        private val ledgerScope: StorageScope,
        private val ledgerKey: RecordKey,
    ) : com.feedme.core.ports.SessionControlStore {
        private val ownerMutex = Mutex()
        private var attempted = false
        private var ready = false
        private var closeRequested = false
        private var captured: Retirement? = null

        suspend fun open(): PortResult<Unit> {
            var admitted = false
            try {
                val result = withContext(dispatcher) {
                    ownerMutex.withLock {
                        if (attempted) return@withLock PortResult.Failure(FailureReason.CONFLICT)
                        attempted = true
                        admitted = true
                        val result = guarded {
                            initializeExistingRecovery()
                            transaction(writes = false) {
                                val tag = ownerTag(ledgerScope)
                                val current = owner(tag) ?: fail(FailureReason.STORAGE_FAILURE)
                                if (!current.active) fail(FailureReason.STORAGE_FAILURE)
                                val target = Retirement(tag, current.generation, current.keyId)
                                recoveryLedgerRecord(target, ledgerKey)
                                captured = target
                            }
                            Unit
                        }
                        currentCoroutineContext().ensureActive()
                        result
                    }
                }
                if (!admitted || result is PortResult.Failure) return result
                // No authority during the I/O-to-caller return gap; close wins this handoff.
                return ownerMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (closeRequested) PortResult.Failure(FailureReason.STALE_SESSION)
                    else { ready = true; result }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + dispatcher) {
                    ownerMutex.withLock {
                        if (admitted || !attempted) { attempted = true; ready = false; closeRequested = true }
                    }
                }
                throw cancelled
            }
        }

        private suspend fun <T> whenReady(action: suspend () -> PortResult<T>): PortResult<T> =
            withContext(dispatcher) {
                ownerMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!ready) PortResult.Failure(FailureReason.STALE_SESSION) else action()
                }
            }

        private fun target(): Retirement = captured ?: fail(FailureReason.STALE_SESSION)

        override suspend fun read(): PortResult<SessionControlRecord?> {
            val caller = currentCoroutineContext()
            return whenReady {
                guarded {
                    caller.ensureActive()
                    val result = transaction(writes = false) {
                        val record = recoveryLedgerRecord(target(), ledgerKey)
                        SessionControlRecord(record.revision, record.payload)
                    }
                    caller.ensureActive()
                    result
                }
            }
        }

        protected suspend fun verify(revision: Long, proposal: PrivateBytes, proof: PrivateBytes,
            predecessor: SessionControlRecord?): PortResult<Unit> {
            val proposed = proposal.copyForCodec()
            val supplied = proof.copyForCodec()
            val previous = predecessor?.payload?.copyForCodec()
            val caller = currentCoroutineContext()
            return try {
                whenReady {
                    guarded {
                        caller.ensureActive()
                        if (revision !in 1..Long.MAX_VALUE - 2 || proposed.size !in 1..4096 ||
                            supplied.size != 64 || (previous != null && previous.size !in 1..32_768))
                            fail(FailureReason.INVALID_DATA)
                        transaction(writes = false) {
                            val exact = target()
                            recoveryLedgerRecord(exact, ledgerKey)
                            ScopedStore(ledgerScope, exact.tag, exact.generation, exact.keyId)
                                .workOriginProof(revision, proposed, supplied, previous)
                            Unit
                        }
                        caller.ensureActive()
                    }
                }
            } finally { proposed.fill(0); supplied.fill(0); previous?.fill(0) }
        }

        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            val plaintext = payload.copyForCodec()
            val caller = currentCoroutineContext()
            return try {
                whenReady {
                    guarded {
                        caller.ensureActive()
                        if (plaintext.size !in 1..32_768 || (expectedRevision != null && expectedRevision <= 0))
                            fail(FailureReason.INVALID_DATA)
                        val result = transaction(writes = true) {
                            val exact = target()
                            val current = recoveryLedgerRecord(exact, ledgerKey)
                            if (expectedRevision != current.revision) fail(FailureReason.CONFLICT)
                            val revision = next(current.revision)
                            val tag = recordTag(exact.tag, ledgerKey)
                            val encrypted = vault.seal(exact.keyId, plaintext,
                                aad(exact.tag, exact.generation, tag, revision, 1))
                            try {
                                if (encrypted.size !in MIN_CIPHERTEXT_BYTES..32_768 + 64)
                                    fail(FailureReason.STORAGE_FAILURE)
                                caller.ensureActive()
                                execute("UPDATE feedme_records SET revision=?,schema_version=1,payload=? " +
                                    "WHERE owner_tag=? AND record_tag=? AND revision=? AND schema_version=1") {
                                    bindLong(1, revision); bindBlob(2, encrypted); bindText(3, exact.tag)
                                    bindText(4, tag); bindLong(5, current.revision)
                                }
                                if (scalarLong("SELECT changes()") != 1L) fail(FailureReason.CONFLICT)
                                caller.ensureActive()
                                SessionControlRecord(revision, PrivateBytes(plaintext))
                            } finally { encrypted.fill(0) }
                        }
                        // Never substitute readback for a failed/unknown COMMIT. The trusted
                        // session acknowledgement protocol additionally brackets exact raw bytes.
                        caller.ensureActive()
                        result
                    }
                }
            } finally { plaintext.fill(0) }
        }

        suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
            ownerMutex.withLock {
                attempted = true
                ready = false
                closeRequested = true
                this@EncryptedStateDatabase.close()
            }
        }
    }

    /** Caller holds the database mutex and a read/write transaction; includes every tombstone. */
    private fun recoveryLedgerRecord(target: Retirement, ledgerKey: RecordKey): PrivateRecord {
        val current = owner(target.tag)
        if (current != Owner(target.generation, true, target.keyId) ||
            (target.tag to target.generation) in locallyRetired) fail(FailureReason.STALE_SESSION)
        if (scalarLong("SELECT count(*) FROM feedme_owners") != 1L ||
            scalarLong("SELECT count(*) FROM feedme_records") != 1L ||
            scalarLong("SELECT count(*) FROM feedme_key_gc") != 0L ||
            scalarLong("SELECT count(*) FROM feedme_activation_aborts") != 0L)
            fail(FailureReason.STORAGE_FAILURE)
        requireKey(target.keyId)
        val tag = recordTag(target.tag, ledgerKey)
        val row = record(target.tag, tag) ?: fail(FailureReason.STORAGE_FAILURE)
        val ciphertext = row.payload ?: fail(FailureReason.STORAGE_FAILURE)
        if (row.schemaVersion != 1 || ciphertext.size !in MIN_CIPHERTEXT_BYTES..32_768 + 64)
            fail(FailureReason.STORAGE_FAILURE)
        val plaintext = vault.open(target.keyId, ciphertext,
            aad(target.tag, target.generation, tag, row.revision, row.schemaVersion))
        return try {
            if (plaintext.size !in 1..32_768) fail(FailureReason.STORAGE_FAILURE)
            PrivateRecord(row.revision, row.schemaVersion, PrivateBytes(plaintext))
        } finally { plaintext.fill(0); ciphertext.fill(0) }
    }

    private fun recoveryPlan(scope: StorageScope, plan: StateActivationPlan): StateActivationPlanRecord {
        try { validateStateActivationPlan(scope, plan, vault) }
        catch (_: StateActivationPlanFormatException) { fail(FailureReason.INVALID_DATA) }
        return StateActivationPlanCodec.decode(plan)
    }

    private fun containsActivationKey(keyId: String): Boolean =
        (vault as? PlannedStateVault)?.containsOwnerKey(keyId) ?: vault.hasOwnerKey(keyId)

    private fun activationRecoveryState(intended: StateActivationPlanRecord): StateActivationStatus {
        val tag = intended.ownerTag
        val current = owner(tag)
        val receipt = activationAbortReceipt(tag)
        val expected = if (intended.priorGeneration == 0L) null
            else Owner(intended.priorGeneration, false, intended.priorKeyId!!)
        if (current == expected) {
            validateActivationPredecessor(tag, current)
            validateActivationReferences(tag, intended.keyId, selected = false)
            validatePriorAbortReceipt(receipt, intended)
            return if (containsActivationKey(intended.keyId)) StateActivationStatus.PARTIAL else StateActivationStatus.PREPARED
        }
        if (current?.keyId != intended.keyId) fail(FailureReason.STALE_SESSION)
        if (current.active && current.generation == intended.selectedGeneration) {
            if ((tag to current.generation) in locallyRetired) fail(FailureReason.STALE_SESSION)
            validateActivationReferences(tag, intended.keyId, selected = true)
            validatePriorAbortReceipt(receipt, intended)
            return if (hasOwnerRecords(tag)) StateActivationStatus.SELECTED_NONEMPTY else StateActivationStatus.SELECTED_EMPTY
        }
        if (!current.active && current.generation == intended.consumedGeneration) {
            // Ordinary retirement can produce the same owner fence, but not this abort receipt.
            if (receipt == null) fail(FailureReason.STALE_SESSION)
            if (receipt.generation != intended.consumedGeneration || receipt.keyId != intended.keyId)
                fail(FailureReason.STORAGE_FAILURE)
            validateActivationReferences(tag, intended.keyId, selected = true)
            if (hasOwnerRecords(tag)) fail(FailureReason.STORAGE_FAILURE)
            return if (containsActivationKey(intended.keyId)) StateActivationStatus.ABORTING else StateActivationStatus.ABORTED
        }
        fail(FailureReason.STALE_SESSION)
    }

    private fun validatePriorAbortReceipt(receipt: ActivationAbortReceipt?, intended: StateActivationPlanRecord) {
        if (receipt != null && (receipt.generation > intended.priorGeneration ||
                (receipt.generation == intended.priorGeneration && receipt.keyId != intended.priorKeyId)))
            fail(FailureReason.STORAGE_FAILURE)
    }

    private data class ActivationAbortReceipt(val generation: Long, val keyId: String, val revision: Long)

    private fun activationAbortReceipt(tag: String): ActivationAbortReceipt? {
        validateActivationAbortReceipts()
        return query("SELECT generation,key_id,revision FROM feedme_activation_aborts WHERE owner_tag=?",
            { bindText(1, tag) }) {
            if (!step()) null else ActivationAbortReceipt(getLong(0), getText(1), getLong(2))
        }
    }

    private fun validateActivationAbortReceipts() {
        if (scalarLong("SELECT EXISTS(SELECT 1 FROM feedme_activation_aborts WHERE " +
                "typeof(owner_tag)<>'text' OR length(CAST(owner_tag AS BLOB))<>64 OR owner_tag GLOB '*[^0-9a-f]*' OR " +
                "typeof(generation)<>'integer' OR generation<1 OR typeof(revision)<>'integer' OR revision<1 OR " +
                "typeof(key_id)<>'text' OR length(CAST(key_id AS BLOB))<>32 OR key_id GLOB '*[^0-9a-f]*')") != 0L)
            fail(FailureReason.STORAGE_FAILURE)
    }

    private inner class ScopedStore(
        private val scope: StorageScope,
        private val tag: String,
        private val generation: Long,
        private val keyId: String,
    ) : PrivateStateStore {
        fun belongsTo(database: EncryptedStateDatabase): Boolean = this@EncryptedStateDatabase === database

        /** Caller owns this database mutex and one read transaction. Never calls resume/activate/GC. */
        fun workOriginProof(revision: Long, proposal: ByteArray, supplied: ByteArray?, predecessor: ByteArray?): PrivateBytes {
            if (scope != WORK_ORIGIN_SCOPE) fail(FailureReason.INVALID_DATA)
            assertCurrent()
            val current = readRecord(tag, generation, keyId, WORK_ORIGIN_KEY) ?: fail(FailureReason.STORAGE_FAILURE)
            val actual = current.payload.copyForCodec()
            var predecessorMac: ByteArray? = null
            var planMac: ByteArray? = null
            try {
                if (current.schemaVersion != 1 || actual.size !in 1..32_768) fail(FailureReason.STORAGE_FAILURE)
                if (predecessor != null && (revision != current.revision || !equalBytes(predecessor, actual)))
                    fail(FailureReason.CONFLICT)
                predecessorMac = if (supplied == null) {
                    workOriginMac("feedme.work-origin-predecessor.v1", tag, generation, keyId, revision, actual)
                } else supplied.copyOfRange(0, 32)
                planMac = workOriginMac("feedme.work-origin-plan.v1", tag, generation, keyId, revision, proposal, predecessorMac)
                if (supplied != null) {
                    var mismatch = 0
                    for (index in 0 until 32) mismatch = mismatch or (planMac[index].toInt() xor supplied[index + 32].toInt())
                    if (mismatch != 0) fail(FailureReason.INVALID_DATA)
                    if (predecessor != null) {
                        val expectedMac = workOriginMac("feedme.work-origin-predecessor.v1", tag, generation, keyId, revision, actual)
                        try { if (!equalBytes(expectedMac, predecessorMac)) fail(FailureReason.INVALID_DATA) }
                        finally { expectedMac.fill(0) }
                    }
                }
                val result = predecessorMac + planMac
                return try { PrivateBytes(result) } finally { result.fill(0) }
            } finally { actual.fill(0); predecessorMac?.fill(0); planMac?.fill(0) }
        }

        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            if (scope != this.scope) return PortResult.Failure(FailureReason.STALE_SESSION)
            if (!validKey(key)) return PortResult.Failure(FailureReason.INVALID_DATA)
            return guarded {
                transaction(writes = false) {
                    assertCurrent()
                    readRecord(tag, generation, keyId, key)
                }
            }
        }

        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            if (scope != this.scope) return PortResult.Failure(FailureReason.STALE_SESSION)
            // Detach before the first suspension. PrivateBytes itself already owns a detached copy.
            val batch = mutations.take(65)
            if (!validBatch(batch)) return PortResult.Failure(FailureReason.INVALID_DATA)
            return guarded {
                transaction(writes = true) {
                    assertCurrent()
                    val result = linkedMapOf<RecordKey, Long?>()
                    for (mutation in batch) {
                        val recordTag = recordTag(tag, mutation.key)
                        val prior = record(tag, recordTag)
                        val existingRevision = prior?.takeIf { it.payload != null }?.revision
                        if (mutation.expectedRevision != existingRevision) fail(FailureReason.CONFLICT)
                        // Do not silently overwrite a corrupt/authentication-failing prior record.
                        prior?.payload?.let { ciphertext ->
                            val opened = vault.open(keyId, ciphertext, aad(tag, generation, recordTag, prior.revision, prior.schemaVersion))
                            try { if (opened.size > MAX_RECORD_BYTES) fail(FailureReason.STORAGE_FAILURE) }
                            finally { opened.fill(0) }
                        }
                        val revision = next(prior?.revision ?: 0)
                        val schemaVersion: Int
                        val encrypted: ByteArray?
                        when (mutation) {
                            is StoreMutation.Put -> {
                                schemaVersion = mutation.schemaVersion
                                val plaintext = mutation.payload.copyForCodec()
                                encrypted = try {
                                    vault.seal(keyId, plaintext, aad(tag, generation, recordTag, revision, schemaVersion))
                                } finally { plaintext.fill(0) }
                                if (encrypted.size !in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) fail(FailureReason.STORAGE_FAILURE)
                                result[mutation.key] = revision
                            }
                            is StoreMutation.Delete -> {
                                schemaVersion = prior?.schemaVersion ?: fail(FailureReason.CONFLICT)
                                encrypted = null
                                result[mutation.key] = null
                            }
                        }
                        execute(
                            "INSERT INTO feedme_records(owner_tag,record_tag,revision,schema_version,payload) VALUES(?,?,?,?,?) " +
                                "ON CONFLICT(owner_tag,record_tag) DO UPDATE SET revision=excluded.revision,schema_version=excluded.schema_version,payload=excluded.payload",
                        ) {
                            bindText(1, tag); bindText(2, recordTag); bindLong(3, revision); bindLong(4, schemaVersion.toLong())
                            if (encrypted == null) bindNull(5) else bindBlob(5, encrypted)
                        }
                    }
                    result.toMap()
                }
            }
        }

        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> {
            if (scope != this.scope) return PortResult.Failure(FailureReason.STALE_SESSION)
            return guarded {
                val current = owner(tag)
                // Preserve harmless retries through an already-retired local handle after a new
                // activation. Do not inspect/delete even the old key through this stale handle.
                if ((tag to generation) in locallyRetired && current != null &&
                    current.generation > generation && (current.active || current.keyId != keyId)) Unit
                else retire(Retirement(tag, generation, keyId))
            }
        }

        private fun assertCurrent() {
            if ((tag to generation) in locallyRetired) fail(FailureReason.STALE_SESSION)
            val current = owner(tag)
            if (current == null || !current.active || current.generation != generation || current.keyId != keyId) {
                fail(FailureReason.STALE_SESSION)
            }
            requireKey(keyId)
        }

        override fun toString() = "PrivateStateStore(owner=<redacted>, generation=<redacted>)"
    }

    private suspend fun <T> guarded(action: () -> T): PortResult<T> = withContext(dispatcher) {
        mutex.withLock {
            if (closed || !usable) return@withLock PortResult.Failure(FailureReason.STORAGE_FAILURE)
            try { PortResult.Value(action()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: StoreFailure) { PortResult.Failure(failure.reason) }
            catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
        }
    }

    private fun configureConnection(existingOnly: Boolean = false) {
        // Bundled 2.7.1 embeds SQLite 3.50.1 with an unfixed WAL-reset bug. This foundation refuses
        // WAL entirely, uses rollback journals, and is NOT a clearance of the engine's other CVEs.
        val mode = scalarText("PRAGMA journal_mode").lowercase()
        if (mode == "wal" || (existingOnly && mode != "delete")) fail(FailureReason.STORAGE_FAILURE)
        if (!existingOnly && scalarText("PRAGMA journal_mode=DELETE").lowercase() != "delete") fail(FailureReason.STORAGE_FAILURE)
        execute("PRAGMA synchronous=EXTRA")
        execute("PRAGMA fullfsync=ON")
        execute("PRAGMA foreign_keys=ON")
        execute("PRAGMA trusted_schema=OFF")
        execute("PRAGMA secure_delete=ON")
        execute("PRAGMA temp_store=MEMORY")
        execute("PRAGMA busy_timeout=5000")
        if (scalarLong("PRAGMA synchronous") != 3L || scalarLong("PRAGMA foreign_keys") != 1L ||
            scalarLong("PRAGMA trusted_schema") != 0L || scalarLong("PRAGMA secure_delete") != 1L ||
            scalarLong("PRAGMA temp_store") != 2L || scalarLong("PRAGMA busy_timeout") != 5000L) fail(FailureReason.STORAGE_FAILURE)
    }

    private fun initialize() {
        configureConnection()
        transaction(writes = true) {
            val version = scalarLong("PRAGMA user_version")
            val appId = scalarLong("PRAGMA application_id")
            if (version == 0L && appId == 0L) {
                if (scalarLong("SELECT count(*) FROM sqlite_schema") != 0L) fail(FailureReason.STORAGE_FAILURE)
                TABLES.values.forEach { execute(it) }
                execute("PRAGMA application_id=$APPLICATION_ID")
                execute("PRAGMA user_version=2")
            } else if (version == 1L && appId == APPLICATION_ID.toLong()) {
                verifySchema(LEGACY_TABLES)
                execute(ABORT_TABLE)
                execute("PRAGMA user_version=2")
            } else if (version != 2L || appId != APPLICATION_ID.toLong()) fail(FailureReason.STORAGE_FAILURE)
            verifySchema()
        }
        cleanupKeys()
    }

    private fun initializeExistingRecovery() {
        configureConnection(existingOnly = true)
        transaction(writes = false) {
            if (scalarLong("PRAGMA user_version") != 2L || scalarLong("PRAGMA application_id") != APPLICATION_ID.toLong())
                fail(FailureReason.STORAGE_FAILURE)
            verifySchema()
        }
    }

    private fun verifySchema(tables: Map<String, String> = TABLES) {
        val definitions = linkedMapOf<String, String>()
        query("SELECT name,sql,length(CAST(name AS BLOB)),length(CAST(sql AS BLOB)) FROM sqlite_schema WHERE type='table' LIMIT ${tables.size + 1}") {
            while (step()) {
                if (getColumnType(0) != SQLITE_DATA_TEXT || getColumnType(1) != SQLITE_DATA_TEXT ||
                    getLong(2) !in 1L..32L || getLong(3) !in 1L..2048L) fail(FailureReason.STORAGE_FAILURE)
                definitions[getText(0)] = getText(1)
            }
        }
        if (definitions != tables) fail(FailureReason.STORAGE_FAILURE)
        // Only SQLite's automatic primary-key indexes are expected; no injected trigger/view.
        if (scalarLong("SELECT count(*) FROM sqlite_schema WHERE type NOT IN ('table','index')") != 0L ||
            scalarLong("SELECT count(*) FROM sqlite_schema WHERE type='index' AND sql IS NOT NULL") != 0L) {
            fail(FailureReason.STORAGE_FAILURE)
        }
    }

    private fun cleanupKeys() {
        val pending = mutableListOf<String>()
        query("SELECT key_id,length(CAST(key_id AS BLOB)) FROM feedme_key_gc LIMIT 65") {
            while (step()) {
                if (getColumnType(0) != SQLITE_DATA_TEXT || getLong(1) != 32L) fail(FailureReason.STORAGE_FAILURE)
                pending += getText(0)
            }
        }
        if (pending.size > 64) fail(FailureReason.STORAGE_FAILURE)
        if (pending.isNotEmpty()) validateOwnersForKeyDeletion()
        for (key in pending) {
            checkKeyId(key)
            // Never delete a key still referenced by an active owner, even in a malformed database.
            query(
                "SELECT count(*) FROM feedme_owners WHERE key_id=? AND " +
                    "(typeof(generation)<>'integer' OR generation<1 OR typeof(active)<>'integer' OR active<>0 OR typeof(key_id)<>'text')",
                { bindText(1, key) },
            ) {
                if (!step() || getLong(0) != 0L) fail(FailureReason.STORAGE_FAILURE)
            }
            vault.deleteOwnerKey(key)
            transaction(writes = true) { execute("DELETE FROM feedme_key_gc WHERE key_id=?") { bindText(1, key) } }
        }
    }

    private fun validateOwnersForKeyDeletion() {
        if (scalarLong(
                "SELECT EXISTS(SELECT 1 FROM feedme_owners WHERE " +
                    "typeof(owner_tag)<>'text' OR length(CAST(owner_tag AS BLOB))<>64 OR owner_tag GLOB '*[^0-9a-f]*' OR " +
                    "typeof(generation)<>'integer' OR generation<1 OR typeof(active)<>'integer' OR active NOT IN (0,1) OR " +
                    "typeof(key_id)<>'text' OR length(CAST(key_id AS BLOB))<>32 OR key_id GLOB '*[^0-9a-f]*')",
            ) != 0L) fail(FailureReason.STORAGE_FAILURE)
    }

    private data class Retirement(val tag: String, val generation: Long, val keyId: String)

    private fun retirementOwner(target: Retirement): Owner {
        val retiredGeneration = next(target.generation)
        val current = owner(target.tag) ?: fail(FailureReason.STALE_SESSION)
        val isOriginal = current.active && current.generation == target.generation && current.keyId == target.keyId
        val isRetired = !current.active && current.generation == retiredGeneration && current.keyId == target.keyId
        if (!isOriginal && !isRetired) fail(FailureReason.STALE_SESSION)
        return current
    }

    private fun validateRetirementReferences(target: Retirement, current: Owner) {
        validateOwnersForKeyDeletion()
        query("SELECT count(*) FROM feedme_owners WHERE key_id=? AND active=1 AND owner_tag<>?", {
            bindText(1, target.keyId); bindText(2, target.tag)
        }) {
            if (!step() || getLong(0) != 0L) fail(FailureReason.STORAGE_FAILURE)
        }
        if (!current.active) query("SELECT count(*) FROM feedme_records WHERE owner_tag=?", { bindText(1, target.tag) }) {
            if (!step() || getLong(0) != 0L) fail(FailureReason.STORAGE_FAILURE)
        }
    }

    private fun validateEmptyRetirement(target: Retirement, current: Owner) {
        validateRetirementReferences(target, current)
        if (hasOwnerRecords(target.tag)) fail(FailureReason.CONFLICT)
    }

    private fun retire(target: Retirement, emptyOnly: Boolean = false) {
        val current = retirementOwner(target)
        // This must precede the local fence as well as the irreversible key deletion. A failed
        // empty-only request must leave the original nonempty owner's existing handles usable.
        if (emptyOnly) validateEmptyRetirement(target, current)
        // Retire only this validated incarnation in memory even if later deletion checks fail.
        locallyRetired += target.tag to target.generation
        validateRetirementReferences(target, current)
        // Delete before SQLite mutation: if confirmed, a failed COMMIT cannot restore readable data.
        // If Keystore itself fails, still try the independent SQL fence/GC as a durable fallback.
        val keyDeleted = try {
            vault.deleteOwnerKey(target.keyId)
            if (vault.hasOwnerKey(target.keyId)) fail(FailureReason.STORAGE_FAILURE)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        transaction(writes = true) {
            if (owner(target.tag) != current) fail(FailureReason.STALE_SESSION)
            if (current.active) {
                execute("UPDATE feedme_owners SET generation=?,active=0 WHERE owner_tag=?") {
                    bindLong(1, next(target.generation)); bindText(2, target.tag)
                }
                execute("DELETE FROM feedme_records WHERE owner_tag=?") { bindText(1, target.tag) }
            }
            if (keyDeleted) execute("DELETE FROM feedme_key_gc WHERE key_id=?") { bindText(1, target.keyId) }
            else execute("INSERT OR IGNORE INTO feedme_key_gc(key_id) VALUES(?)") { bindText(1, target.keyId) }
        }
        if (!keyDeleted) fail(FailureReason.STORAGE_FAILURE)
    }

    private fun retirementMac(target: Retirement): ByteArray =
        vault.index(frame("feedme.retirement-target.v1", target.tag, target.generation.toString(), target.keyId)).also {
            if (it.size != 32) fail(FailureReason.STORAGE_FAILURE)
        }

    private fun encodeRetirement(target: Retirement): StateRetirementTarget {
        val bytes = ByteArray(StateRetirementTarget.ENCODED_SIZE)
        bytes[0] = 1
        target.tag.encodeToByteArray().copyInto(bytes, 1)
        for (index in 0..7) bytes[65 + index] = (target.generation ushr (56 - index * 8)).toByte()
        target.keyId.encodeToByteArray().copyInto(bytes, 73)
        retirementMac(target).copyInto(bytes, 105)
        return StateRetirementTarget(bytes)
    }

    private fun decodeRetirement(target: StateRetirementTarget): Retirement {
        val bytes = target.copyForStorage()
        if (bytes[0] != 1.toByte() || bytes[65] < 0) fail(FailureReason.INVALID_DATA)
        val tag = bytes.copyOfRange(1, 65).decodeToString()
        val key = bytes.copyOfRange(73, 105).decodeToString()
        if (tag.length != 64 || tag.any { it !in "0123456789abcdef" } ||
            key.length != 32 || key.any { it !in "0123456789abcdef" }) fail(FailureReason.INVALID_DATA)
        var generation = 0L
        for (index in 65..72) generation = (generation shl 8) or (bytes[index].toLong() and 255L)
        if (generation < 1 || generation == Long.MAX_VALUE) fail(FailureReason.INVALID_DATA)
        val value = Retirement(tag, generation, key)
        val mac = retirementMac(value)
        var mismatch = 0
        for (index in mac.indices) mismatch = mismatch or ((mac[index].toInt() xor bytes[105 + index].toInt()) and 255)
        if (mismatch != 0) fail(FailureReason.INVALID_DATA)
        return value
    }

    private data class Owner(val generation: Long, val active: Boolean, val keyId: String)
    private data class Row(val revision: Long, val schemaVersion: Int, val payload: ByteArray?)

    private fun validateActivationScope(scope: StorageScope) {
        if (scope.actorKind == ActorKind.DEMO) fail(FailureReason.INVALID_DATA)
        for (value in listOf(scope.environment, scope.actorId)) {
            val bytes = try { value.encodeToByteArray(throwOnInvalidSequence = true) }
            catch (_: Exception) { fail(FailureReason.INVALID_DATA) }
            bytes.fill(0)
        }
    }

    private fun validateActivationPredecessor(tag: String, prior: Owner?) {
        if (prior?.active == true) fail(FailureReason.CONFLICT)
        // Inactive or absent owners cannot legitimately have rows, including tombstones. Do not
        // overwrite this evidence or turn it into a fresh incarnation by merely changing the key.
        if (hasOwnerRecords(tag)) fail(FailureReason.STORAGE_FAILURE)
        validateOwnersForKeyDeletion()
        validateActivationGc()
        if (prior != null && (hasGarbageKey(prior.keyId) || vault.hasOwnerKey(prior.keyId)))
            fail(FailureReason.CONFLICT)
    }

    private fun validateActivationReferences(tag: String, keyId: String, selected: Boolean) {
        validateOwnersForKeyDeletion()
        validateActivationGc()
        validateActivationAbortReceipts()
        val sql = if (selected) "SELECT count(*) FROM feedme_owners WHERE key_id=? AND owner_tag<>?"
            else "SELECT count(*) FROM feedme_owners WHERE key_id=?"
        query(sql, {
            bindText(1, keyId)
            if (selected) bindText(2, tag)
        }) { if (!step() || getLong(0) != 0L) fail(FailureReason.CONFLICT) }
        val receiptSql = if (selected) "SELECT count(*) FROM feedme_activation_aborts a " +
            "LEFT JOIN feedme_owners o ON o.owner_tag=a.owner_tag WHERE a.key_id=? AND " +
            "(a.owner_tag<>? OR o.owner_tag IS NULL OR o.active<>0 OR o.generation<>a.generation OR o.key_id<>a.key_id)"
            else "SELECT count(*) FROM feedme_activation_aborts WHERE key_id=?"
        query(receiptSql, {
            bindText(1, keyId)
            if (selected) bindText(2, tag)
        }) { if (!step() || getLong(0) != 0L) fail(FailureReason.CONFLICT) }
        if (hasGarbageKey(keyId)) fail(FailureReason.CONFLICT)
    }

    private fun validateActivationGc() {
        if (scalarLong("SELECT EXISTS(SELECT 1 FROM feedme_key_gc WHERE typeof(key_id)<>'text' OR " +
                "length(CAST(key_id AS BLOB))<>32 OR key_id GLOB '*[^0-9a-f]*')") != 0L)
            fail(FailureReason.STORAGE_FAILURE)
    }

    private fun hasGarbageKey(keyId: String): Boolean = query("SELECT EXISTS(SELECT 1 FROM feedme_key_gc WHERE key_id=?)",
        { bindText(1, keyId) }) {
        if (!step()) fail(FailureReason.STORAGE_FAILURE)
        getLong(0) != 0L
    }

    private fun authenticateActivationPlan(plan: StateActivationPlan): StateActivationPlanRecord {
        val record = try { StateActivationPlanCodec.decode(plan) }
        catch (_: StateActivationPlanFormatException) { fail(FailureReason.INVALID_DATA) }
        val expected = activationMac(record)
        try {
            var mismatch = 0
            for (index in expected.indices)
                mismatch = mismatch or (expected[index].toInt() xor record.authenticationMac[index].toInt())
            if (mismatch != 0) fail(FailureReason.INVALID_DATA)
        } finally { expected.fill(0) }
        return record
    }

    private fun activationMac(record: StateActivationPlanRecord): ByteArray {
        val unsigned = StateActivationPlanCodec.encodeUnsigned(record)
        val purpose = "feedme.activation-plan.v1\u0000".encodeToByteArray()
        val input = purpose + unsigned
        return try { vault.index(input).also { if (it.size != 32) fail(FailureReason.STORAGE_FAILURE) } }
        finally { unsigned.fill(0); purpose.fill(0); input.fill(0) }
    }

    /** Only the two literal work-origin domains above use this internal framing operation. */
    private fun workOriginMac(domain: String, tag: String, generation: Long, keyId: String, revision: Long,
        vararg values: ByteArray): ByteArray {
        val prefix = frame(domain, tag, generation.toString(), keyId, recordTag(tag, WORK_ORIGIN_KEY), revision.toString())
        val input = ByteArray(prefix.size + 4 + values.sumOf { 4 + it.size })
        return try {
            prefix.copyInto(input)
            var offset = prefix.size
            fun length(value: Int) { for (shift in 24 downTo 0 step 8) input[offset++] = (value ushr shift).toByte() }
            length(values.size)
            values.forEach { value -> length(value.size); value.copyInto(input, offset); offset += value.size }
            vault.index(input).also { if (it.size != 32) fail(FailureReason.STORAGE_FAILURE) }
        } finally { prefix.fill(0); input.fill(0) }
    }

    private fun hasOwnerRecords(tag: String): Boolean = query(
        "SELECT EXISTS(SELECT 1 FROM feedme_records WHERE owner_tag=?)", { bindText(1, tag) },
    ) {
        if (!step() || getColumnType(0) != SQLITE_DATA_INTEGER || getLong(0) !in 0L..1L) fail(FailureReason.STORAGE_FAILURE)
        getLong(0) == 1L
    }

    /** Shared strict read path; callers establish the exact owner and transaction first. */
    private fun readRecord(tag: String, generation: Long, keyId: String, key: RecordKey): PrivateRecord? {
        val recordTag = recordTag(tag, key)
        val row = record(tag, recordTag)
        if (row == null || row.payload == null) return null
        val plaintext = vault.open(keyId, row.payload, aad(tag, generation, recordTag, row.revision, row.schemaVersion))
        return try {
            if (plaintext.size > MAX_RECORD_BYTES) fail(FailureReason.STORAGE_FAILURE)
            PrivateRecord(row.revision, row.schemaVersion, PrivateBytes(plaintext))
        } finally { plaintext.fill(0) }
    }

    private fun owner(tag: String): Owner? = query(
        "SELECT generation,active,key_id,length(CAST(key_id AS BLOB)) FROM feedme_owners WHERE owner_tag=?", { bindText(1, tag) },
    ) {
        if (!step()) null else {
            if (getColumnType(0) != SQLITE_DATA_INTEGER || getColumnType(1) != SQLITE_DATA_INTEGER ||
                getColumnType(2) != SQLITE_DATA_TEXT || getLong(3) != 32L) fail(FailureReason.STORAGE_FAILURE)
            val generation = getLong(0)
            val active = getLong(1)
            val keyId = getText(2)
            if (generation < 1 || active !in 0L..1L) fail(FailureReason.STORAGE_FAILURE)
            checkKeyId(keyId)
            Owner(generation, active == 1L, keyId)
        }
    }

    private fun record(ownerTag: String, recordTag: String): Row? = query(
        "SELECT revision,schema_version,length(payload),payload FROM feedme_records WHERE owner_tag=? AND record_tag=?",
        { bindText(1, ownerTag); bindText(2, recordTag) },
    ) {
        if (!step()) null else {
            if (getColumnType(0) != SQLITE_DATA_INTEGER || getColumnType(1) != SQLITE_DATA_INTEGER ||
                getColumnType(3) !in setOf(SQLITE_DATA_NULL, SQLITE_DATA_BLOB)) fail(FailureReason.STORAGE_FAILURE)
            val revision = getLong(0)
            val schema = getLong(1)
            if (revision < 1 || schema !in 1..Int.MAX_VALUE.toLong()) fail(FailureReason.STORAGE_FAILURE)
            val payload = if (isNull(2)) null else {
                if (getLong(2) !in MIN_CIPHERTEXT_BYTES.toLong()..MAX_CIPHERTEXT_BYTES.toLong()) fail(FailureReason.STORAGE_FAILURE)
                getBlob(3)
            }
            Row(revision, schema.toInt(), payload)
        }
    }

    private fun ownerTag(scope: StorageScope): String = opaque(frame("feedme.owner-index.v1", scope.environment, scope.actorKind.name, scope.actorId))
    private fun recordTag(ownerTag: String, key: RecordKey): String = opaque(frame("feedme.record-index.v1", ownerTag, key.collection, key.id))
    private fun opaque(value: ByteArray): String {
        val digest = try { vault.index(value) } finally { value.fill(0) }
        if (digest.size != 32) fail(FailureReason.STORAGE_FAILURE)
        return digest.joinToString("") { HEX[(it.toInt() ushr 4) and 15].toString() + HEX[it.toInt() and 15] }
    }

    private fun requireKey(keyId: String) { if (!vault.hasOwnerKey(keyId)) fail(FailureReason.STORAGE_FAILURE) }
    private fun checkKeyId(keyId: String) {
        if (keyId.length != 32 || keyId.any { it !in "0123456789abcdef" }) fail(FailureReason.STORAGE_FAILURE)
    }

    private fun <T> transaction(writes: Boolean, action: () -> T): T {
        var began = false
        var committing = false
        try {
            execute(if (writes) "BEGIN IMMEDIATE" else "BEGIN")
            began = true
            val result = action()
            committing = true
            execute("COMMIT")
            return result
        } catch (failure: Exception) {
            var rolledBack = false
            var active = false
            var statusKnown = false
            try {
                active = connection.inTransaction()
                statusKnown = true
                if (active) {
                    execute("ROLLBACK")
                    rolledBack = !connection.inTransaction()
                }
            } catch (_: Exception) { usable = false }
            if (active && !rolledBack) usable = false
            val unknown = writes && began && ((!statusKnown || (active && !rolledBack)) || (committing && !active))
            if (failure is CancellationException) throw failure
            if (unknown) fail(FailureReason.OUTCOME_UNKNOWN)
            if (failure is StoreFailure) throw failure
            fail(FailureReason.STORAGE_FAILURE)
        }
    }

    private fun execute(sql: String, bind: SQLiteStatement.() -> Unit = {}) {
        query(sql, bind) { while (step()) { /* PRAGMA rows are consumed without logging. */ } }
    }
    private fun scalarLong(sql: String): Long = query(sql) { if (!step()) fail(FailureReason.STORAGE_FAILURE); getLong(0) }
    private fun scalarText(sql: String): String = query(sql) { if (!step()) fail(FailureReason.STORAGE_FAILURE); getText(0) }
    private fun <T> query(sql: String, bind: SQLiteStatement.() -> Unit = {}, action: SQLiteStatement.() -> T): T {
        val statement = connection.prepare(sql)
        try { statement.bind(); return statement.action() } finally { statement.close() }
    }

    companion object {
        private val ACTIVATION_BINDING_KEY = RecordKey("session-activation", "binding-v1")
        private const val MAX_BINDING_BYTES = 4096
        private val WORK_ORIGIN_SCOPE = StorageScope("feedme-session-work-v1", ActorKind.DEMO, "install-work")
        private val WORK_ORIGIN_KEY = RecordKey("session-work", "native-work-ledger")
        private val CONTROL_RECOVERY_SCOPE = StorageScope("feedme-session-control-v1", ActorKind.DEMO, "install-control")
        private val CONTROL_RECOVERY_KEY = RecordKey("session-control", "retirement-ledger")
        private fun equalBytes(left: ByteArray, right: ByteArray): Boolean {
            if (left.size != right.size) return false
            var mismatch = 0
            for (index in left.indices) mismatch = mismatch or (left[index].toInt() xor right[index].toInt())
            return mismatch == 0
        }
        internal const val MAX_RECORD_BYTES = 1_048_576
        private const val MAX_BATCH_BYTES = 4_194_304L
        private const val MIN_CIPHERTEXT_BYTES = 29
        private const val MAX_CIPHERTEXT_BYTES = MAX_RECORD_BYTES + 64
        private const val APPLICATION_ID = 0x464d5331
        private const val HEX = "0123456789abcdef"
        private const val ABORT_TABLE = "CREATE TABLE feedme_activation_aborts(owner_tag TEXT PRIMARY KEY NOT NULL CHECK(length(owner_tag)=64),generation INTEGER NOT NULL CHECK(typeof(generation)='integer' AND generation>0),key_id TEXT NOT NULL CHECK(length(key_id)=32),revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0),FOREIGN KEY(owner_tag) REFERENCES feedme_owners(owner_tag))"
        private val LEGACY_TABLES = linkedMapOf(
            "feedme_owners" to "CREATE TABLE feedme_owners(owner_tag TEXT PRIMARY KEY NOT NULL CHECK(length(owner_tag)=64),generation INTEGER NOT NULL CHECK(typeof(generation)='integer' AND generation>0),active INTEGER NOT NULL CHECK(active IN (0,1)),key_id TEXT NOT NULL CHECK(length(key_id)=32))",
            "feedme_records" to "CREATE TABLE feedme_records(owner_tag TEXT NOT NULL,record_tag TEXT NOT NULL CHECK(length(record_tag)=64),revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0),schema_version INTEGER NOT NULL CHECK(typeof(schema_version)='integer' AND schema_version>0 AND schema_version<=2147483647),payload BLOB CHECK(payload IS NULL OR (typeof(payload)='blob' AND length(payload)>=29 AND length(payload)<=1048640)),PRIMARY KEY(owner_tag,record_tag),FOREIGN KEY(owner_tag) REFERENCES feedme_owners(owner_tag))",
            "feedme_key_gc" to "CREATE TABLE feedme_key_gc(key_id TEXT PRIMARY KEY NOT NULL CHECK(length(key_id)=32))",
        )
        private val TABLES = LinkedHashMap(LEGACY_TABLES).apply { put("feedme_activation_aborts", ABORT_TABLE) }

        /**
         * Synchronous ownership transfer, before any SQL or suspended work. The caller must retain
         * the returned owner and explicitly close it on every outcome, even a failed/cancelled open.
         * Only platform factories/tests may supply this exclusively owned existing connection.
         * Platform factories must guard driver close admission: an internal closed flag/no-op must
         * never turn an earlier failed native close into a successful retry acknowledgement.
         */
        internal fun createActivationRecoveryOwner(
            connection: SQLiteConnection,
            vault: StateVault,
            scope: StorageScope,
            plan: StateActivationPlan,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            onClosed: () -> Unit = {},
        ): StateActivationRecoveryOwner {
            val database = EncryptedStateDatabase(connection, vault, dispatcher, onClosed)
            return database.RetainedActivationRecovery(scope, plan)
        }

        /**
         * Retain before any SQL/suspension. The native factory owns an existing fixed-work file,
         * existing vault and truthful driver-close guard; the caller retains failed-open cleanup.
         * No ordinary initialization/resume or garbage collection is admitted through this seam.
         */
        @WorkRecoveryCompositionApi
        internal fun createWorkRecoveryStore(
            connection: SQLiteConnection,
            vault: StateVault,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            onClosed: () -> Unit = {},
        ): ExistingSessionWorkRecoveryStore {
            val database = EncryptedStateDatabase(connection, vault, dispatcher, onClosed)
            return database.RetainedWorkRecovery()
        }

        /** Retain ownership before any SQL/suspension; no ordinary initialization, resume or GC. */
        @SessionControlRecoveryCompositionApi
        internal fun createControlRecoveryStore(
            connection: SQLiteConnection,
            vault: StateVault,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            onClosed: () -> Unit = {},
        ): ExistingSessionControlRecoveryStore {
            val database = EncryptedStateDatabase(connection, vault, dispatcher, onClosed)
            return database.RetainedControlRecovery()
        }

        /** Existing schema only: no creation, migration, ordinary activation or garbage collection. */
        internal suspend fun openActivationRecovery(
            connection: SQLiteConnection,
            vault: StateVault,
            scope: StorageScope,
            plan: StateActivationPlan,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            onClosed: () -> Unit = {},
        ): PortResult<StateActivationRecoveryHandle> {
            val database = EncryptedStateDatabase(connection, vault, dispatcher, onClosed)
            return try {
                when (val result = database.guarded {
                    val ownedPlan = StateActivationPlan(plan.copyForStorage())
                    val intended = database.recoveryPlan(scope, ownedPlan)
                    database.initializeExistingRecovery()
                    database.transaction(writes = false) { database.activationRecoveryState(intended) }
                    database.ActivationRecovery(scope, ownedPlan) as StateActivationRecoveryHandle
                }) {
                    is PortResult.Value -> result
                    is PortResult.Failure -> { database.close(); result }
                }
            } catch (failure: Throwable) { database.close(); throw failure }
        }

        /** Internal factories transfer ownership even on failure; never pass a shared connection. */
        internal suspend fun open(
            connection: SQLiteConnection,
            vault: StateVault,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            onClosed: () -> Unit = {},
        ): PortResult<EncryptedStateDatabase> {
            val database = EncryptedStateDatabase(connection, vault, dispatcher, onClosed)
            return try {
                when (val result = database.guarded { database.initialize(); database }) {
                    is PortResult.Value -> result
                    is PortResult.Failure -> { database.close(); result }
                }
            } catch (failure: Throwable) { database.close(); throw failure }
        }

        private fun aad(owner: String, generation: Long, record: String, revision: Long, schema: Int): ByteArray =
            frame("feedme.record-aead.v1", owner, generation.toString(), record, revision.toString(), schema.toString())

        /** Unambiguous versioned UTF-8 tuple; strict encoding rejects surrogate-collision inputs. */
        private fun frame(vararg values: String): ByteArray {
            val fields = values.map { it.encodeToByteArray(throwOnInvalidSequence = true) }
            val result = ByteArray(4 + fields.sumOf { 4 + it.size })
            var offset = 0
            fun length(value: Int) {
                for (shift in 24 downTo 0 step 8) result[offset++] = (value ushr shift).toByte()
            }
            length(fields.size)
            fields.forEach { field -> length(field.size); field.copyInto(result, offset); offset += field.size; field.fill(0) }
            return result
        }

        private fun validKey(key: RecordKey): Boolean = validText(key.collection, 128) && validText(key.id, 512)
        private fun validText(value: String, limit: Int): Boolean =
            value.length <= limit && value.isNotBlank() && value.none(Char::isISOControl) &&
                try { value.encodeToByteArray(throwOnInvalidSequence = true); true } catch (_: Exception) { false }

        private fun validBatch(batch: List<StoreMutation>): Boolean {
            if (batch.size !in 1..64 || batch.map { it.key }.toSet().size != batch.size) return false
            var total = 0L
            for (mutation in batch) {
                if (!validKey(mutation.key)) return false
                if (mutation is StoreMutation.Put) {
                    val bytes = mutation.payload.copyForCodec()
                    val length = bytes.size
                    bytes.fill(0)
                    if (length > MAX_RECORD_BYTES) return false
                    total += length
                    if (total > MAX_BATCH_BYTES) return false
                }
            }
            return true
        }

        private fun next(value: Long): Long { if (value == Long.MAX_VALUE) fail(FailureReason.STORAGE_FAILURE); return value + 1 }
        private fun fail(reason: FailureReason): Nothing = throw StoreFailure(reason)
    }

    private class StoreFailure(val reason: FailureReason) : Exception("Private storage operation failed")
}
