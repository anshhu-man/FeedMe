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

        override suspend fun abort(): PortResult<Unit> {
            val callerContext = currentCoroutineContext()
            return guarded {
                callerContext.ensureActive()
                val intended = recoveryPlan(scope, plan)
                // A real changed receipt is required on EVERY attempt, including observed ABORTED.
                // Readback after an uncertain COMMIT cannot substitute for this acknowledgement.
                transaction(writes = true) {
                    if (activationRecoveryState(intended) == StateActivationStatus.SELECTED_NONEMPTY)
                        fail(FailureReason.CONFLICT)
                    val priorReceipt = activationAbortReceipt(intended.ownerTag)
                    val revision = if (priorReceipt?.generation == intended.consumedGeneration &&
                        priorReceipt.keyId == intended.keyId) next(priorReceipt.revision) else 1L
                    execute(
                        "INSERT INTO feedme_owners(owner_tag,generation,active,key_id) VALUES(?,?,0,?) " +
                            "ON CONFLICT(owner_tag) DO UPDATE SET generation=excluded.generation,active=0,key_id=excluded.key_id",
                    ) { bindText(1, intended.ownerTag); bindLong(2, intended.consumedGeneration); bindText(3, intended.keyId) }
                    execute(
                        "INSERT INTO feedme_activation_aborts(owner_tag,generation,key_id,revision) VALUES(?,?,?,?) " +
                            "ON CONFLICT(owner_tag) DO UPDATE SET generation=excluded.generation,key_id=excluded.key_id,revision=excluded.revision",
                    ) {
                        bindText(1, intended.ownerTag); bindLong(2, intended.consumedGeneration)
                        bindText(3, intended.keyId); bindLong(4, revision)
                    }
                }
                callerContext.ensureActive()
                // Any preceding exception/cancellation exits before this irreversible operation.
                // Revalidate inside a fresh write lock in case the preceding COMMIT exposed a race.
                transaction(writes = true) {
                    val current = activationRecoveryState(intended)
                    if (current != StateActivationStatus.ABORTING && current != StateActivationStatus.ABORTED)
                        fail(FailureReason.STALE_SESSION)
                    callerContext.ensureActive()
                    locallyRetired += intended.ownerTag to intended.selectedGeneration
                    vault.deleteOwnerKey(intended.keyId)
                    if (containsActivationKey(intended.keyId)) fail(FailureReason.STORAGE_FAILURE)
                }
            }
        }

        override suspend fun close(): PortResult<Unit> = this@EncryptedStateDatabase.close()
        override fun toString(): String = "StateActivationRecoveryHandle(<redacted>)"
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
