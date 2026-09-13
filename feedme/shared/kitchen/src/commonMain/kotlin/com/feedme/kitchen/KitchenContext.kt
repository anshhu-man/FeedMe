package com.feedme.kitchen

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
    suspend fun read(lease: SessionLease, key: RecordKey): PrivateRecord? {
        current(lease)
        val result = store.read(scope, key)
        current(lease)
        return kitchenValue(result)
    }
    suspend fun commit(lease: SessionLease, changes: List<StoreMutation>): Map<RecordKey, Long?> {
        current(lease)
        val result = store.commit(scope, changes)
        current(lease)
        return kitchenValue(result)
    }

    /** Only explicit read operations owned by these repositories, not arbitrary URLs/mutations. */
    suspend fun fetch(lease: SessionLease, operation: String, path: Map<String, String>): ApiReply {
        if (operation !in setOf("getCookSession", "getPlan", "getSavedRecipe")) kitchenFail(FailureReason.INVALID_DATA)
        current(lease)
        val result = transport.execute(lease, ApiCall(operation, path))
        current(lease)
        val reply = kitchenValue(result)
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
    suspend fun <T> guarded(lease: SessionLease, action: suspend () -> T): PortResult<T> = withContext(dispatcher) {
        try { current(lease); PortResult.Value(action()).also { current(lease) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: KitchenFailure) { PortResult.Failure(failure.reason, failure.retryAfterSeconds) }
        catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
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
