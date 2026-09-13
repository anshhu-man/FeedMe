package com.feedme.session

import android.content.Context
import android.os.Build
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.SecretText
import com.feedme.core.ports.StorageScope
import com.feedme.core.ports.StoredCredentials
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Independent AndroidKeyStore credential slot. Opening never authenticates or activates identity.
 * The trusted identity owner must serialize provider verification and retirement/recovery gates.
 *
 * A manifest selects one immutable revision-qualified encrypted snapshot. Incomplete operations
 * preserve unknown files/keys and fail closed on reopen; they require explicit repair, not login.
 * State is authenticated slot metadata, not proof that its credential key or snapshot is usable.
 * Hardware backing, power-loss behavior and native revocation require device verification.
 */
class AndroidCredentialStore private constructor(
    private val files: AndroidCredentialFiles,
    private val vault: AndroidCredentialVault,
    private val keyPrefix: String,
) : PlannedCredentialCreateStore, CredentialCreatePlanInspection, CredentialCreatePlanAbort {
    private val mutex = Mutex()
    private var closed = false
    private var closing = false
    private var poisoned = false

    override suspend fun state(): PortResult<CredentialSlotState> = guarded {
        val manifest = loadManifest()
        CredentialSlotState(manifest.revision, manifest.scope, manifest.incarnation)
    }

    override suspend fun read(scope: StorageScope): PortResult<CredentialSnapshot?> = guarded {
        validScope(scope)
        val manifest = loadManifest()
        if (manifest.scope != scope) null else loadSnapshot(manifest)
    }

    override suspend fun create(
        expectedSlotRevision: Long,
        credentials: StoredCredentials,
    ): PortResult<CredentialSnapshot> = guarded { mutation ->
        if (expectedSlotRevision <= 0) reject(FailureReason.INVALID_DATA)
        val previous = loadManifest()
        if (previous.scope != null || previous.revision != expectedSlotRevision) reject(FailureReason.CONFLICT)
        val snapshot = CredentialSnapshot(UUID.randomUUID().toString(), nextRevision(previous.revision), credentials)
        val plaintext = validatedSnapshot(snapshot)
        try {
            val target = target(snapshot.scope, snapshot.incarnation)
            mutation.started = true
            vault.createIncarnationKey(target)
            saveSnapshot(target, snapshot, plaintext)
            saveManifest(CredentialManifest(snapshot.revision, snapshot.scope, snapshot.incarnation))
            mutation.committed = true
            snapshot
        } finally { plaintext.fill(0) }
    }

    override suspend fun planCreate(
        expectedSlotRevision: Long,
        credentials: StoredCredentials,
    ): PortResult<CredentialCreatePlan> = guarded {
        if (expectedSlotRevision !in 1..Long.MAX_VALUE - 2) reject(FailureReason.INVALID_DATA)
        val manifest = loadManifest()
        if (manifest.scope != null || manifest.revision != expectedSlotRevision) reject(FailureReason.CONFLICT)
        validateInventory(manifest)
        // Planning uses only existing install keys. No incarnation key, file or slot is created.
        val snapshot = CredentialSnapshot(UUID.randomUUID().toString(), expectedSlotRevision + 1, credentials)
        val plaintext = validatedSnapshot(snapshot)
        try {
            val unsigned = CredentialCreatePlanRecord(expectedSlotRevision, snapshot.incarnation,
                target(snapshot.scope, snapshot.incarnation), payloadMac(plaintext), "0".repeat(64))
            CredentialCreatePlan.create(unsigned.copy(authenticationMac = planMac(unsigned)))
        } finally { plaintext.fill(0) }
    }

    override suspend fun commitPlannedCreate(
        plan: CredentialCreatePlan,
        credentials: StoredCredentials,
    ): PortResult<CredentialSnapshot> = guarded { mutation ->
        val record = authenticatePlan(plan)
        val snapshot = CredentialSnapshot(record.incarnation, record.snapshotRevision, credentials)
        val plaintext = validatedSnapshot(snapshot)
        try {
            if (target(snapshot.scope, snapshot.incarnation) != record.target ||
                !sameMac(payloadMac(plaintext), record.payloadMac)) reject(FailureReason.INVALID_DATA)
            val state = createState(record)
            if (state.status == CredentialCreateRecoveryStatus.ABORTING ||
                state.status == CredentialCreateRecoveryStatus.ABORTED ||
                files.exists(AndroidCredentialFiles.abortManifestPending(record.incarnation))) {
                reject(FailureReason.CONFLICT)
            }
            if (state.status == CredentialCreateRecoveryStatus.SELECTED) {
                // Selection consumes CREATE. Replay is strictly read-only: absent or damaged
                // selected material must never lead to generating a replacement incarnation key.
                val selected = loadSnapshot(state.manifest)
                requireSnapshotPayload(record, selected)
                // An earlier rename can be visible without an acknowledged directory fsync.
                // Re-acknowledge it before the caller clears its independent durable plan.
                files.synchronize()
                return@guarded selected
            }
            val blob = AndroidCredentialFiles.blobName(record.target, record.snapshotRevision)
            val pendingBlob = "$blob.pending"
            val pendingManifest = AndroidCredentialFiles.createManifestPending(record.incarnation)
            // A partial temp may not contain complete authenticated ciphertext. Only explicit
            // recovery abort can discard it; this writer does not guess or overwrite it.
            if (files.exists(pendingBlob) || files.exists(pendingManifest)) reject(FailureReason.STORAGE_FAILURE)
            val hasBlob = files.exists(blob)
            if (!state.hasKey && hasBlob) reject(FailureReason.STORAGE_FAILURE)
            mutation.started = true
            if (!state.hasKey) {
                vault.createIncarnationKey(record.target)
                files.checkpoint(CredentialFileFaultPoint.AFTER_KEY_CREATE)
            } else if (!vault.hasIncarnationKey(record.target)) reject(FailureReason.STORAGE_FAILURE)
            if (hasBlob) {
                requireSnapshotPayload(record,
                    loadSnapshot(CredentialManifest(record.snapshotRevision, snapshot.scope, record.incarnation)))
            } else saveSnapshot(record.target, snapshot, plaintext)
            saveManifest(CredentialManifest(record.snapshotRevision, snapshot.scope, record.incarnation), pendingManifest)
            mutation.committed = true
            snapshot
        } finally { plaintext.fill(0) }
    }

    private suspend fun inspectPlannedCreate(plan: CredentialCreatePlan): PortResult<CredentialCreateRecoveryStatus> = guarded {
        createState(authenticatePlan(plan)).status
    }

    override suspend fun inspectPlannedCreate(
        scope: StorageScope,
        plan: CredentialCreatePlan,
    ): PortResult<CredentialCreatePlanObservation> = guarded {
        val record = authenticateScopePlan(scope, plan)
        val state = createState(record)
        CredentialCreatePlanObservation(state.status, observationFingerprint(record, state))
    }

    private suspend fun abortPlannedCreate(plan: CredentialCreatePlan): PortResult<Unit> = guarded { mutation ->
        abortCreate(authenticatePlan(plan), mutation)
    }

    override suspend fun abortPlannedCreate(
        scope: StorageScope,
        plan: CredentialCreatePlan,
    ): PortResult<Unit> = guarded { mutation ->
        // Both native authentication and scope binding belong to this same mutex acquisition.
        // No temporary recovery handle may close or bypass the already-open parent owner.
        abortCreate(authenticateScopePlan(scope, plan), mutation)
    }

    private fun abortCreate(record: CredentialCreatePlanRecord, mutation: Mutation) {
        val state = createState(record)
        val selected = state.manifest.scope != null
        val abortPending = AndroidCredentialFiles.abortManifestPending(record.incarnation)
        mutation.started = true
        if (!selected) {
            if (state.manifest.revision == record.expectedSlotRevision) {
                // A prior partial abort temp is owned by this authenticated plan, never by
                // another CREATE or refresh. Consume the empty slot before destroying its key.
                files.deleteCreateArtifact(abortPending)
                saveManifest(CredentialManifest(record.abortedRevision, null, null), abortPending)
            } else {
                // Rename may have succeeded while the prior directory-fsync acknowledgement
                // was lost. Re-establish the consumption barrier before any exact key deletion.
                files.synchronize()
            }
            mutation.committed = true
            files.checkpoint(CredentialFileFaultPoint.AFTER_ABORT_CONSUMED)
        } else {
            // Visible selection after an interrupted rename is not yet a durable consumption
            // fence. Establish it before deleting the key, so rollback cannot expose EMPTY R.
            files.synchronize()
        }
        // A selected CREATE is already consumed. Its exact R+1 selection was validated above;
        // a later refresh shares this key and is rejected before reaching this deletion.
        vault.deleteIncarnationKey(record.target)
        files.checkpoint(CredentialFileFaultPoint.AFTER_KEY_DELETE)
        val blob = AndroidCredentialFiles.blobName(record.target, record.snapshotRevision)
        files.deleteCreateArtifact(blob)
        files.deleteCreateArtifact("$blob.pending")
        files.deleteCreateArtifact(AndroidCredentialFiles.createManifestPending(record.incarnation))
        files.deleteCreateArtifact(abortPending)
        if (selected) {
            // Retain the authenticated selected metadata until exact key and file erasure are
            // acknowledged. Missing selected material remains replayable without secret reads.
            saveManifest(CredentialManifest(record.abortedRevision, null, null), abortPending)
            mutation.committed = true
            files.checkpoint(CredentialFileFaultPoint.AFTER_ABORT_CONSUMED)
        }
    }

    override suspend fun replace(
        expected: CredentialSnapshot,
        credentials: StoredCredentials,
    ): PortResult<CredentialSnapshot> = guarded { mutation ->
        val previous = exactSnapshot(expected)
        if (previous.scope != credentials.scope) reject(FailureReason.INVALID_DATA)
        when (val current = previous.credentials) {
            is StoredCredentials.Account -> {
                val replacement = credentials as? StoredCredentials.Account ?: reject(FailureReason.INVALID_DATA)
                if (!sameSecret(current.deviceSessionId, replacement.deviceSessionId)) reject(FailureReason.INVALID_DATA)
            }
            is StoredCredentials.Guest -> {
                val replacement = credentials as? StoredCredentials.Guest ?: reject(FailureReason.INVALID_DATA)
                if (!sameSecret(current.guestSessionId, replacement.guestSessionId)) reject(FailureReason.INVALID_DATA)
            }
        }
        replaceSnapshot(mutation, previous, credentials)
    }

    override suspend fun attachDeviceSession(
        expected: CredentialSnapshot,
        deviceSessionId: SecretText,
    ): PortResult<CredentialSnapshot> = guarded { mutation ->
        val previous = exactSnapshot(expected)
        val account = previous.credentials as? StoredCredentials.Account ?: reject(FailureReason.INVALID_DATA)
        if (account.deviceSessionId != null) reject(FailureReason.CONFLICT)
        // The shared codec validates the external API UUID and preserves its spelling/case.
        replaceSnapshot(mutation, previous, account.copy(deviceSessionId = deviceSessionId))
    }

    override suspend fun retire(scope: StorageScope, credentialIncarnation: String): PortResult<Unit> = guarded { mutation ->
        validScope(scope)
        try { requireCredentialUuid(credentialIncarnation) } catch (_: IllegalArgumentException) {
            reject(FailureReason.INVALID_DATA)
        }
        val manifest = loadManifest()
        val selected = manifest.scope == scope && manifest.incarnation == credentialIncarnation
        // Validate revision overflow before the first destructive action.
        val emptyRevision = if (selected) nextRevision(manifest.revision) else null
        val target = target(scope, credentialIncarnation)
        mutation.started = true
        // This exact derivation never decrypts or follows a newer incarnation's snapshot.
        // Invalidated keys are deleted without first trying to use them; absence is confirmed.
        vault.deleteIncarnationKey(target)
        if (selected) {
            files.deleteBlob(AndroidCredentialFiles.blobName(target, manifest.revision))
            // Leave the old authenticated selection until key + exact blob deletion are durable.
            // A crash before this commit can replay using metadata even when the key is absent.
            saveManifest(CredentialManifest(checkNotNull(emptyRevision), null, null))
            mutation.committed = true
        }
        Unit
    }

    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock PortResult.Value(Unit)
            closing = true
            try {
                files.close()
                closed = true
                PortResult.Value(Unit)
            } catch (_: Exception) {
                PortResult.Failure(FailureReason.STORAGE_FAILURE)
            } catch (_: LinkageError) {
                PortResult.Failure(FailureReason.STORAGE_FAILURE)
            }
        }
    }

    override fun toString() = "AndroidCredentialStore(<redacted>)"

    private fun exactSnapshot(expected: CredentialSnapshot): CredentialSnapshot {
        val manifest = loadManifest()
        if (manifest.scope != expected.scope || manifest.incarnation != expected.incarnation ||
            manifest.revision != expected.revision) reject(FailureReason.CONFLICT)
        // Caller-supplied snapshot payload is not authoritative for replacement invariants.
        return loadSnapshot(manifest)
    }

    private fun replaceSnapshot(
        mutation: Mutation,
        previous: CredentialSnapshot,
        credentials: StoredCredentials,
    ): CredentialSnapshot {
        val snapshot = CredentialSnapshot(previous.incarnation, nextRevision(previous.revision), credentials)
        val plaintext = validatedSnapshot(snapshot)
        try {
            val target = target(snapshot.scope, snapshot.incarnation)
            mutation.started = true
            saveSnapshot(target, snapshot, plaintext)
            saveManifest(CredentialManifest(snapshot.revision, snapshot.scope, snapshot.incarnation))
            mutation.committed = true
            // No enumeration or broad wipe. An unsuccessful cleanup leaves an explicit repair gate.
            files.deleteBlob(AndroidCredentialFiles.blobName(target, previous.revision))
            return snapshot
        } finally { plaintext.fill(0) }
    }

    private fun loadManifest(): CredentialManifest {
        val ciphertext = files.read(AndroidCredentialFiles.MANIFEST)
        val aad = manifestAad()
        try {
            val plaintext = vault.openManifest(ciphertext, aad)
            try { return CredentialCodec.decodeManifest(PrivateBytes(plaintext)) } finally { plaintext.fill(0) }
        } finally { ciphertext.fill(0); aad.fill(0) }
    }

    private fun loadSnapshot(manifest: CredentialManifest): CredentialSnapshot {
        val scope = checkNotNull(manifest.scope)
        val incarnation = checkNotNull(manifest.incarnation)
        val target = target(scope, incarnation)
        val ciphertext = files.read(AndroidCredentialFiles.blobName(target, manifest.revision))
        val aad = snapshotAad(target, manifest.revision)
        try {
            val plaintext = vault.openCredentials(target, ciphertext, aad)
            try {
                val snapshot = CredentialCodec.decodeSnapshot(PrivateBytes(plaintext))
                require(snapshot.revision == manifest.revision && snapshot.scope == scope && snapshot.incarnation == incarnation)
                return snapshot
            } finally { plaintext.fill(0) }
        } finally { ciphertext.fill(0); aad.fill(0) }
    }

    private fun saveSnapshot(target: String, snapshot: CredentialSnapshot, plaintext: ByteArray) {
        val aad = snapshotAad(target, snapshot.revision)
        try {
            val ciphertext = vault.sealCredentials(target, plaintext, aad)
            try { files.write(AndroidCredentialFiles.blobName(target, snapshot.revision), ciphertext, replace = false) }
            finally { ciphertext.fill(0) }
        } finally { aad.fill(0) }
    }

    private fun saveManifest(manifest: CredentialManifest, pendingName: String = "${AndroidCredentialFiles.MANIFEST}.pending") {
        val plaintext = CredentialCodec.encodeManifest(manifest).copyForCodec()
        val aad = manifestAad()
        try {
            val ciphertext = vault.sealManifest(plaintext, aad)
            try { files.write(AndroidCredentialFiles.MANIFEST, ciphertext, replace = true, pendingName = pendingName) }
            finally { ciphertext.fill(0) }
        } finally { plaintext.fill(0); aad.fill(0) }
    }

    private fun target(scope: StorageScope, incarnation: String): String {
        val input = frame("feedme.credentials.target.v1", scope.environment, scope.actorKind.name, scope.actorId, incarnation)
        try {
            val digest = vault.index(input)
            try { return digest.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') } }
            finally { digest.fill(0) }
        } finally { input.fill(0) }
    }

    private fun manifestAad() = frame("feedme.credentials.manifest.v1", keyPrefix)
    private fun snapshotAad(target: String, revision: Long) =
        frame("feedme.credentials.snapshot.v1", keyPrefix, target, revision.toString())

    private fun authenticatePlan(plan: CredentialCreatePlan): CredentialCreatePlanRecord {
        val record = try { CredentialCreatePlanCodec.decode(plan.copyForStorage()) }
        catch (_: CredentialCreatePlanFormatException) { reject(FailureReason.INVALID_DATA) }
        if (!sameMac(record.authenticationMac, planMac(record))) reject(FailureReason.INVALID_DATA)
        return record
    }

    private fun authenticateScopePlan(scope: StorageScope, plan: CredentialCreatePlan): CredentialCreatePlanRecord {
        val record = authenticatePlan(plan)
        validScope(scope)
        // StorageScope bounds characters but intentionally is not a Unicode codec. Reject
        // malformed UTF-16 before deriving a target, rather than folding it into replacement text.
        for (field in listOf(scope.environment, scope.actorId)) {
            val encoded = try { field.encodeToByteArray(throwOnInvalidSequence = true) }
            catch (_: CharacterCodingException) { reject(FailureReason.INVALID_DATA) }
            encoded.fill(0)
        }
        if (!sameMac(target(scope, record.incarnation), record.target)) reject(FailureReason.INVALID_DATA)
        return record
    }

    private fun planMac(record: CredentialCreatePlanRecord): String {
        val unsigned = CredentialCreatePlanCodec.encodeUnsigned(record).copyForCodec()
        try { return indexedHex("feedme.credentials.create-plan-auth.v1", unsigned) }
        finally { unsigned.fill(0) }
    }

    private fun payloadMac(plaintext: ByteArray): String = indexedHex("feedme.credentials.create-payload.v1", plaintext)

    private fun indexedHex(domain: String, payload: ByteArray): String {
        val prefix = frame(domain, keyPrefix)
        val input = ByteArray(prefix.size + Int.SIZE_BYTES + payload.size)
        try {
            ByteBuffer.wrap(input).put(prefix).putInt(payload.size).put(payload)
            val digest = vault.index(input)
            try { return digest.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') } }
            finally { digest.fill(0) }
        } finally { prefix.fill(0); input.fill(0) }
    }

    private fun requireSnapshotPayload(record: CredentialCreatePlanRecord, snapshot: CredentialSnapshot) {
        val plaintext = validatedSnapshot(snapshot)
        try {
            require(snapshot.incarnation == record.incarnation && snapshot.revision == record.snapshotRevision)
            require(target(snapshot.scope, snapshot.incarnation) == record.target)
            require(sameMac(payloadMac(plaintext), record.payloadMac))
        } finally { plaintext.fill(0) }
    }

    private data class CreateState(
        val manifest: CredentialManifest,
        val status: CredentialCreateRecoveryStatus,
        val hasKey: Boolean,
        val names: Set<String>,
        val keyTargets: Set<String>,
    )

    /** Exact prior, exact selected, or exact consumed slot only; never a general orphan permit. */
    private fun createState(record: CredentialCreatePlanRecord): CreateState {
        val manifest = loadManifest()
        val selected = manifest.scope != null
        if (selected) {
            if (manifest.revision != record.snapshotRevision || manifest.incarnation != record.incarnation ||
                target(checkNotNull(manifest.scope), checkNotNull(manifest.incarnation)) != record.target) {
                reject(FailureReason.CONFLICT)
            }
        } else if (manifest.revision != record.expectedSlotRevision && manifest.revision != record.abortedRevision) {
            reject(FailureReason.CONFLICT)
        }
        val blob = AndroidCredentialFiles.blobName(record.target, record.snapshotRevision)
        val artifacts = setOf(blob, "$blob.pending", AndroidCredentialFiles.createManifestPending(record.incarnation),
            AndroidCredentialFiles.abortManifestPending(record.incarnation))
        val names = files.names()
        require(names.all { it == AndroidCredentialFiles.LOCK || it == AndroidCredentialFiles.MANIFEST || it in artifacts })
        val targets = vault.credentialTargets()
        require(targets.all { it == record.target })
        val hasKey = record.target in targets
        val hasArtifacts = hasKey || names.any { it in artifacts }
        val status = when {
            selected -> CredentialCreateRecoveryStatus.SELECTED
            manifest.revision == record.abortedRevision -> if (hasArtifacts) CredentialCreateRecoveryStatus.ABORTING
                else CredentialCreateRecoveryStatus.ABORTED
            AndroidCredentialFiles.abortManifestPending(record.incarnation) in names -> CredentialCreateRecoveryStatus.ABORTING
            hasArtifacts -> CredentialCreateRecoveryStatus.PARTIAL
            else -> CredentialCreateRecoveryStatus.PREPARED
        }
        return CreateState(manifest, status, hasKey, names, targets)
    }

    private fun observationFingerprint(record: CredentialCreatePlanRecord, state: CreateState): PrivateBytes {
        val manifest = CredentialCodec.encodeManifest(state.manifest).copyForCodec()
        // Bind the exact authenticated plan as well as the captured inventory. No second
        // inventory scan, credential blob read or key-usability probe enters this observation.
        val metadata = frame("feedme.credentials.create-observation.v1", keyPrefix, record.authenticationMac,
            state.names.size.toString(), *state.names.sorted().toTypedArray(),
            state.keyTargets.size.toString(), *state.keyTargets.sorted().toTypedArray())
        val input = ByteArray(metadata.size + Int.SIZE_BYTES + manifest.size)
        try {
            ByteBuffer.wrap(input).put(metadata).putInt(manifest.size).put(manifest)
            val digest = vault.index(input)
            try { return PrivateBytes(digest) } finally { digest.fill(0) }
        } finally { manifest.fill(0); metadata.fill(0); input.fill(0) }
    }

    private class CreateRecoveryHandle(
        private val store: AndroidCredentialStore,
        private val plan: CredentialCreatePlan,
    ) : CredentialCreateRecoveryHandle {
        override suspend fun inspect() = store.inspectPlannedCreate(plan)
        override suspend fun abort() = store.abortPlannedCreate(plan)
        override suspend fun close() = store.close()
        override fun toString() = "CredentialCreateRecoveryHandle(<redacted>)"
    }

    /** The caller owns this object before opening starts, including cancelled dispatch back. */
    private class RetainedCreateRecoveryOwner(
        private val scope: StorageScope,
        private val plan: CredentialCreatePlan,
        private val location: () -> Pair<File, String>,
        private val faultInjector: (CredentialFileFaultPoint) -> Unit,
    ) : CredentialCreateRecoveryOwner {
        private enum class Phase { NEW, OPENING, READY, CLOSE_ONLY, CLOSED }
        private val mutex = Mutex()
        private var phase = Phase.NEW
        private var opening: AndroidCredentialFiles.Opening? = null
        private var files: AndroidCredentialFiles? = null
        private var store: AndroidCredentialStore? = null

        override suspend fun open(): PortResult<Unit> {
            var admitted = false
            return try {
                val initialized = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        if (phase != Phase.NEW) return@withLock PortResult.Failure(FailureReason.CONFLICT)
                        admitted = true
                        phase = Phase.OPENING
                        try {
                            // Even Context directory access is deferred until this supported-API gate.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) reject(FailureReason.NOT_CONFIGURED)
                            val (directory, prefix) = location()
                            val retained = AndroidCredentialFiles.prepareOpen(directory, faultInjector, existingOnly = true)
                            opening = retained
                            val ownedFiles = retained.open()
                            files = ownedFiles
                            faultInjector(CredentialFileFaultPoint.AFTER_RECOVERY_FILES_OPEN)
                            require(AndroidCredentialFiles.MANIFEST in ownedFiles.names())
                            val vault = AndroidCredentialVault.open(prefix, initialize = false)
                            val manager = AndroidCredentialStore(ownedFiles, vault, prefix)
                            store = manager
                            manager.createState(manager.authenticateScopePlan(scope, plan))
                            faultInjector(CredentialFileFaultPoint.AFTER_RECOVERY_AUTHENTICATED)
                            currentCoroutineContext().ensureActive()
                            PortResult.Value(Unit)
                        } catch (cancelled: CancellationException) {
                            phase = Phase.CLOSE_ONLY
                            throw cancelled
                        } catch (rejected: Rejected) {
                            phase = Phase.CLOSE_ONLY
                            PortResult.Failure(rejected.reason)
                        } catch (_: Exception) {
                            phase = Phase.CLOSE_ONLY
                            PortResult.Failure(FailureReason.STORAGE_FAILURE)
                        } catch (_: LinkageError) {
                            phase = Phase.CLOSE_ONLY
                            PortResult.Failure(FailureReason.STORAGE_FAILURE)
                        }
                    }
                }
                if (initialized is PortResult.Failure) return initialized
                // Publish only after the IO result reaches the caller dispatcher. There is no
                // ready capability during a cancellable return handoff or after close began.
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (phase != Phase.OPENING) return@withLock PortResult.Failure(FailureReason.STALE_SESSION)
                    phase = Phase.READY
                    PortResult.Value(Unit)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    mutex.withLock {
                        // A cancelled queued/rejected second open cannot revoke the first opener.
                        if (phase != Phase.CLOSED && (admitted || phase == Phase.NEW)) phase = Phase.CLOSE_ONLY
                    }
                }
                throw cancelled
            }
        }

        override suspend fun inspect(): PortResult<CredentialCreatePlanObservation> = operation {
            it.inspectPlannedCreate(scope, plan)
        }

        override suspend fun abort(): PortResult<Unit> = operation { it.abortPlannedCreate(scope, plan) }

        private suspend fun <T> operation(action: suspend (AndroidCredentialStore) -> PortResult<T>): PortResult<T> =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    if (phase != Phase.READY) return@withLock PortResult.Failure(FailureReason.STALE_SESSION)
                    // Only failed opening or close makes this owner close-only. Mutation
                    // poisoning belongs to the store; a lost successful reply may be retried.
                    val result = action(checkNotNull(store))
                    currentCoroutineContext().ensureActive()
                    result
                }
            }

        override suspend fun close(): PortResult<Unit> = withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                if (phase == Phase.CLOSED) return@withLock PortResult.Value(Unit)
                phase = Phase.CLOSE_ONLY
                val result = try {
                    store?.close() ?: run { opening?.close(); PortResult.Value(Unit) }
                } catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
                catch (_: LinkageError) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
                if (result is PortResult.Value) {
                    phase = Phase.CLOSED
                    store = null; files = null; opening = null
                }
                result
            }
        }

        override fun toString() = "CredentialCreateRecoveryOwner(<redacted>)"
    }

    /** Missing selected material is recoverable retirement, not authorization to restore a token. */
    private fun validateInventory(manifest: CredentialManifest) {
        val target = manifest.scope?.let { target(it, checkNotNull(manifest.incarnation)) }
        val allowed = buildSet {
            add(AndroidCredentialFiles.LOCK)
            add(AndroidCredentialFiles.MANIFEST)
            if (target != null) add(AndroidCredentialFiles.blobName(target, manifest.revision))
        }
        require(files.names().all { it in allowed })
        require(vault.credentialTargets().all { it == target })
    }

    private class Mutation(var started: Boolean = false, var committed: Boolean = false)
    private class Rejected(val reason: FailureReason) : Exception("Credential operation unavailable")

    private suspend fun <T> guarded(operation: (Mutation) -> T): PortResult<T> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || closing || poisoned) return@withLock PortResult.Failure(FailureReason.STORAGE_FAILURE)
            val mutation = Mutation()
            try {
                PortResult.Value(operation(mutation))
            } catch (cancelled: CancellationException) {
                if (mutation.started) poisoned = true
                throw cancelled
            } catch (rejected: Rejected) {
                PortResult.Failure(rejected.reason)
            } catch (error: Exception) {
                if (mutation.started) poisoned = true
                PortResult.Failure(if (mutation.committed || (error is CredentialFileException && error.outcomeUnknown))
                    FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE)
            } catch (_: LinkageError) {
                if (mutation.started) poisoned = true
                PortResult.Failure(if (mutation.committed) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE)
            }
        }
    }

    companion object {
        private const val DIRECTORY_NAME = "feedme-credentials"
        private const val KEY_PREFIX = "com.feedme.session.credentials.v1"

        /** This native adapter requires API 27+; API 26 returns NOT_CONFIGURED without I/O. */
        suspend fun open(context: Context): PortResult<AndroidCredentialStore> = openOwned({
            val base = context.applicationContext.noBackupFilesDir
            require(!Files.isSymbolicLink(base.toPath()))
            File(base.canonicalFile, DIRECTORY_NAME) to KEY_PREFIX
        }, {})

        /** Instrumentation must use an exact dedicated directory and independently owned prefix. */
        internal suspend fun openForTests(
            directory: File,
            keyPrefix: String,
            faultInjector: (CredentialFileFaultPoint) -> Unit = {},
        ): PortResult<AndroidCredentialStore> = openOwned({ directory to keyPrefix }, faultInjector)

        /**
         * No I/O during construction. Retain this exact owner before calling open, and through
         * every open/close failure or cancellation. It never exposes the underlying store.
         */
        fun createRecoveryOwner(context: Context, scope: StorageScope,
            plan: CredentialCreatePlan): CredentialCreateRecoveryOwner = RetainedCreateRecoveryOwner(
                scope, detachedPlan(plan), {
                    val base = context.applicationContext.noBackupFilesDir
                    require(!Files.isSymbolicLink(base.toPath()))
                    File(base.canonicalFile, DIRECTORY_NAME) to KEY_PREFIX
                }, {},
            )

        internal fun createRecoveryOwnerForTests(directory: File, keyPrefix: String, scope: StorageScope,
            plan: CredentialCreatePlan, faultInjector: (CredentialFileFaultPoint) -> Unit = {}): CredentialCreateRecoveryOwner =
            RetainedCreateRecoveryOwner(scope, detachedPlan(plan), { directory to keyPrefix }, faultInjector)

        private fun detachedPlan(plan: CredentialCreatePlan): CredentialCreatePlan =
            when (val copied = CredentialCreatePlan.fromStorage(plan.copyForStorage())) {
                is PortResult.Value -> copied.value
                is PortResult.Failure -> throw IllegalArgumentException("Credential create plan unavailable")
            }

        /** Existing-only, one authenticated plan, no credential reads or activation surface. */
        suspend fun openCreateRecovery(
            context: Context,
            plan: CredentialCreatePlan,
        ): PortResult<CredentialCreateRecoveryHandle> = openRecoveryOwned({
            val base = context.applicationContext.noBackupFilesDir
            require(!Files.isSymbolicLink(base.toPath()))
            File(base.canonicalFile, DIRECTORY_NAME) to KEY_PREFIX
        }, plan, {})

        internal suspend fun openCreateRecoveryForTests(
            directory: File,
            keyPrefix: String,
            plan: CredentialCreatePlan,
            faultInjector: (CredentialFileFaultPoint) -> Unit = {},
        ): PortResult<CredentialCreateRecoveryHandle> = openRecoveryOwned({ directory to keyPrefix }, plan, faultInjector)

        private suspend fun openRecoveryOwned(
            location: () -> Pair<File, String>,
            plan: CredentialCreatePlan,
            faultInjector: (CredentialFileFaultPoint) -> Unit,
        ): PortResult<CredentialCreateRecoveryHandle> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return PortResult.Failure(FailureReason.NOT_CONFIGURED)
            var files: AndroidCredentialFiles? = null
            var manager: AndroidCredentialStore? = null
            try {
                return withContext(Dispatchers.IO) {
                    val (directory, prefix) = location()
                    val ownedFiles = AndroidCredentialFiles.open(directory, faultInjector, existingOnly = true)
                    files = ownedFiles
                    require(AndroidCredentialFiles.MANIFEST in ownedFiles.names())
                    val vault = AndroidCredentialVault.open(prefix, initialize = false)
                    val store = AndroidCredentialStore(ownedFiles, vault, prefix)
                    manager = store
                    store.createState(store.authenticatePlan(plan))
                    PortResult.Value(CreateRecoveryHandle(store, plan))
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { manager?.close() ?: runCatching { files?.close() } }
                throw cancelled
            } catch (rejected: Rejected) {
                withContext(NonCancellable + Dispatchers.IO) { manager?.close() ?: runCatching { files?.close() } }
                return PortResult.Failure(rejected.reason)
            } catch (_: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { manager?.close() ?: runCatching { files?.close() } }
                return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            } catch (_: LinkageError) {
                withContext(NonCancellable + Dispatchers.IO) { manager?.close() ?: runCatching { files?.close() } }
                return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            }
        }

        private suspend fun openOwned(
            location: () -> Pair<File, String>,
            faultInjector: (CredentialFileFaultPoint) -> Unit,
        ): PortResult<AndroidCredentialStore> {
            // Keep the app's minSdk 26, but do not weaken atomic close-on-exec on that release.
            // Check before invoking location (including Context directory access) or native I/O.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                return PortResult.Failure(FailureReason.NOT_CONFIGURED)
            }
            var files: AndroidCredentialFiles? = null
            var manager: AndroidCredentialStore? = null
            try {
                return withContext(Dispatchers.IO) {
                    val (directory, prefix) = location()
                    val ownedFiles = AndroidCredentialFiles.open(directory, faultInjector)
                    files = ownedFiles
                    val names = ownedFiles.names()
                    val hasManifest = AndroidCredentialFiles.MANIFEST in names
                    if (!hasManifest) {
                        // An existing empty/lock-only directory may be damaged prior state.
                        // Missing files and missing aliases together are not first-install proof.
                        require(ownedFiles.wasCreatedByThisOpen)
                        require(names == setOf(AndroidCredentialFiles.LOCK))
                    }
                    // A lost manifest with surviving aliases must never become a fresh empty slot.
                    val vault = AndroidCredentialVault.open(prefix, initialize = !hasManifest)
                    val store = AndroidCredentialStore(ownedFiles, vault, prefix)
                    manager = store
                    if (!hasManifest) store.saveManifest(CredentialManifest(1, null, null))
                    store.validateInventory(store.loadManifest())
                    PortResult.Value(store)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { manager?.close() ?: runCatching { files?.close() } }
                throw cancelled
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { manager?.close() ?: runCatching { files?.close() } }
                return PortResult.Failure(if (error is CredentialFileException && error.outcomeUnknown)
                    FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE)
            } catch (_: LinkageError) {
                withContext(NonCancellable + Dispatchers.IO) { manager?.close() ?: runCatching { files?.close() } }
                return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            }
        }

        private fun validScope(scope: StorageScope) {
            if (scope.actorKind == ActorKind.DEMO) reject(FailureReason.INVALID_DATA)
        }

        private fun nextRevision(revision: Long): Long {
            if (revision == Long.MAX_VALUE) reject(FailureReason.CONFLICT)
            return revision + 1
        }

        private fun validatedSnapshot(snapshot: CredentialSnapshot): ByteArray = try {
            CredentialCodec.encodeSnapshot(snapshot).copyForCodec()
        } catch (_: CredentialFormatException) { reject(FailureReason.INVALID_DATA) }

        private fun sameSecret(first: SecretText?, second: SecretText?): Boolean = when {
            first == null -> second == null
            second == null -> false
            else -> first.use { left -> second.use { right -> left == right } }
        }

        private fun sameMac(first: String, second: String): Boolean =
            MessageDigest.isEqual(first.encodeToByteArray(), second.encodeToByteArray())

        private fun reject(reason: FailureReason): Nothing = throw Rejected(reason)

        /** Length-delimited domain-separated input, not a cryptographic primitive. */
        private fun frame(vararg fields: String): ByteArray = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                fields.forEach { field ->
                    // Reject unpaired UTF-16 surrogates; replacement encoding would collapse
                    // distinct caller scopes onto the same destructive HMAC target.
                    val bytes = field.encodeToByteArray(throwOnInvalidSequence = true)
                    try { output.writeInt(bytes.size); output.write(bytes) } finally { bytes.fill(0) }
                }
                output.flush()
                buffer.toByteArray()
            }
        }
    }
}
