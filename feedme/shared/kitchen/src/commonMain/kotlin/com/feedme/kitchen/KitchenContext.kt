package com.feedme.kitchen

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Shared owner/operation fencing, not server authentication. No identity activation or retry. */
internal class KitchenContext(
    val scope: StorageScope,
    private val store: PrivateStateStore,
    private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher,
    private val clock: EpochClock,
    private val transport: AccountTransport,
) {
    val ownerBoundary: SessionBoundary get() = boundary
    val validator = CanonicalBodyValidator.bundled()
    private val binder = CanonicalResponseBinder(validator)

    fun current(lease: SessionLease) {
        if (scope != lease.scope || !boundary.isCurrent(lease)) kitchenFail(FailureReason.STALE_SESSION)
        if (scope.actorKind == ActorKind.DEMO) kitchenFail(FailureReason.UNAUTHENTICATED)
    }
    fun now(): Long = clock.nowMillis().also { if (it < 0) kitchenFail(FailureReason.INVALID_DATA) }
    private suspend fun active(lease: SessionLease) {
        currentCoroutineContext().ensureActive()
        current(lease)
    }
    suspend fun read(lease: SessionLease, key: RecordKey): PrivateRecord? {
        active(lease)
        val result = store.read(scope, key)
        active(lease)
        return kitchenValue(result)
    }
    suspend fun commit(lease: SessionLease, changes: List<StoreMutation>): Map<RecordKey, Long?> {
        active(lease)
        // Snapshot the exact request before suspension. A null predecessor is create-if-absent,
        // not proof of revision zero: native tombstones preserve a prior generation's counter.
        val batch = changes.toList()
        if (batch.isEmpty() || batch.map { it.key }.toSet().size != batch.size ||
            batch.any { it.expectedRevision == Long.MAX_VALUE }) kitchenFail(FailureReason.INVALID_DATA)
        val result = store.commit(scope, batch)
        active(lease)
        // Never promote visible bytes after Failure/OUTCOME_UNKNOWN into an acknowledgement.
        val revisions = kitchenValue(result).toMap()
        if (revisions.keys != batch.map { it.key }.toSet()) kitchenFail(FailureReason.STORAGE_FAILURE)
        batch.forEach { mutation ->
            val revision = revisions[mutation.key]
            when (mutation) {
                is StoreMutation.Put -> {
                    val previous = mutation.expectedRevision
                    if (revision == null || revision <= 0 || (previous != null && revision != previous + 1))
                        kitchenFail(FailureReason.STORAGE_FAILURE)
                }
                is StoreMutation.Delete -> if (revision != null) kitchenFail(FailureReason.STORAGE_FAILURE)
            }
        }
        batch.forEach { mutation ->
            val stored = read(lease, mutation.key)
            when (mutation) {
                is StoreMutation.Put -> if (stored == null || stored.revision != revisions[mutation.key] ||
                    stored.schemaVersion != mutation.schemaVersion || !samePayload(stored.payload, mutation.payload))
                    kitchenFail(FailureReason.STORAGE_FAILURE)
                is StoreMutation.Delete -> if (stored != null) kitchenFail(FailureReason.STORAGE_FAILURE)
            }
        }
        active(lease)
        return revisions
    }

    /** Only explicit read operations owned by these repositories, not arbitrary URLs/mutations. */
    suspend fun fetch(lease: SessionLease, operation: String, path: Map<String, String>,
        query: Map<String, List<String>> = emptyMap()): ApiReply {
        if (operation !in setOf("getCookSession", "getPlan", "getSavedRecipe", "listSavedRecipes")) kitchenFail(FailureReason.INVALID_DATA)
        if (operation == "listSavedRecipes") {
            if (path.isNotEmpty() || query.keys.any { it !in setOf("q", "cursor", "limit") } ||
                query.values.any { it.size != 1 }) kitchenFail(FailureReason.INVALID_DATA)
        } else if (query.isNotEmpty()) kitchenFail(FailureReason.INVALID_DATA)
        active(lease)
        val result = transport.execute(lease, ApiCall(operation, path, query))
        active(lease)
        val reply = kitchenValue(result)
        if (operation in setOf("getSavedRecipe", "listSavedRecipes") &&
            reply.body?.copyForCodec()?.size?.let { it > SavedRecipeRepository.MAX_RESPONSE_BYTES } == true)
            kitchenFail(FailureReason.UNAVAILABLE)
        if (binder.bind(operation, reply.status, reply.body?.copyForCodec(), reply.contentType, reply.traceId) !is ResponseBindingResult.Accepted)
            kitchenFail(FailureReason.INVALID_DATA)
        if (reply.status != 200) {
            // Domain evidence is taken only from the operation/status/content/schema-bound Problem,
            // never from an unvalidated error body or exception message.
            val problem = WireDocument.decode((reply.body ?: kitchenFail(FailureReason.INVALID_DATA)).copyForCodec())
            val problemCode = (problem.field("code") as? WireField.Value)?.value?.stringOrNull()
            kitchenFail(when (reply.status) {
                401 -> FailureReason.UNAUTHENTICATED
                403 -> FailureReason.FORBIDDEN
                404, 410 -> FailureReason.NOT_FOUND
                409, 412 -> FailureReason.CONFLICT
                429 -> FailureReason.RATE_LIMITED
                500, 503 -> FailureReason.UNAVAILABLE
                else -> FailureReason.INVALID_DATA
            }, reply.retryAfterSeconds, problemCode)
        }
        return reply
    }

    fun document(schema: String, bytes: PrivateBytes): WireDocument {
        val copy = bytes.copyForCodec()
        if (validator.validateSchema(schema, copy) != ContractValidationResult.Valid) kitchenFail(FailureReason.INVALID_DATA)
        return WireDocument.decode(copy)
    }
    suspend fun <T> guarded(lease: SessionLease, action: suspend () -> T): PortResult<T> {
        val result = withContext(dispatcher) {
            try { active(lease); PortResult.Value(action()).also { active(lease) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: KitchenFailure) { PortResult.Failure(failure.reason, failure.retryAfterSeconds) }
            catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
        }
        // Recheck after returning from the repository dispatcher as well. The boundary and its
        // callers still belong to one serialized application owner, not an arbitrary thread pool.
        currentCoroutineContext().ensureActive()
        return if (scope != lease.scope || !boundary.isCurrent(lease)) PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    private fun samePayload(left: PrivateBytes, right: PrivateBytes): Boolean {
        val a = left.copyForCodec(); val b = right.copyForCodec()
        return try { a.contentEquals(b) } finally { a.fill(0); b.fill(0) }
    }

    /** Queue batches must obey the same acknowledgement contract as direct domain writes. */
    fun commandStore(): PrivateStateStore = object : PrivateStateStore {
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            val lease = boundary.current() ?: return PortResult.Failure(FailureReason.STALE_SESSION)
            return guarded(lease) {
                if (scope != this@KitchenContext.scope) kitchenFail(FailureReason.STALE_SESSION)
                this@KitchenContext.read(lease, key)
            }
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            val lease = boundary.current() ?: return PortResult.Failure(FailureReason.STALE_SESSION)
            return guarded(lease) {
                if (scope != this@KitchenContext.scope) kitchenFail(FailureReason.STALE_SESSION)
                this@KitchenContext.commit(lease, mutations)
            }
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = PortResult.Failure(FailureReason.NOT_CONFIGURED)
    }
}

internal class KitchenFailure(val reason: FailureReason, val retryAfterSeconds: Long?, val problemCode: String? = null) : Exception("Kitchen operation failed")
internal fun kitchenFail(reason: FailureReason, retryAfterSeconds: Long? = null, problemCode: String? = null): Nothing =
    throw KitchenFailure(reason, retryAfterSeconds, problemCode)
internal fun <T> kitchenValue(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> kitchenFail(result.reason, result.retryAfterSeconds)
}

internal fun privateDigest(bytes: PrivateBytes): String {
    val result = contentSha256(bytes.copyForCodec())
    check(result.size == 32) { "Content digest unavailable" }
    val alphabet = "0123456789abcdef"
    return buildString(64) { result.forEach { byte -> val value = byte.toInt() and 255; append(alphabet[value ushr 4]); append(alphabet[value and 15]) } }
}

internal fun readVerified(record: PrivateRecord?, digest: String): PrivateBytes {
    if (record == null || record.schemaVersion != 1 || privateDigest(record.payload) != digest) kitchenFail(FailureReason.INVALID_DATA)
    return record.payload
}

internal val kitchenUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
internal fun normalizedId(value: String): String = value.also { if (!kitchenUuid.matches(it)) kitchenFail(FailureReason.INVALID_DATA) }.lowercase()
internal fun kitchenPut(key: RecordKey, revision: Long?, bytes: PrivateBytes) = StoreMutation.Put(key, revision, 1, bytes)
