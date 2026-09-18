package com.feedme.core.ports

/** Platform/provider boundaries. None of these interfaces constitutes server authorization. */
fun interface EpochClock {
    fun nowMillis(): Long
}

enum class ActorKind { ACCOUNT, GUEST, DEMO }

/** Environment and actor kind are part of ownership: guest/demo data must never become account data. */
data class StorageScope(val environment: String, val actorKind: ActorKind, val actorId: String) {
    init {
        require(environment.isNotBlank() && actorId.isNotBlank())
        require(environment.length <= 200 && actorId.length <= 200)
        require(environment.none(Char::isISOControl) && actorId.none(Char::isISOControl))
    }

    // A scope is not a filesystem path. Platform stores must map it to an internal opaque key.
    override fun toString() = "StorageScope(environment=$environment, actorKind=$actorKind, actorId=<redacted>)"
}

/** Accidental debug logging must not print bearer credentials or private serialized records. */
class SecretText(value: String) {
    private val value = value.also { require(it.isNotBlank() && it.none(Char::isISOControl)) }
    fun <T> use(block: (String) -> T): T = block(value)
    override fun toString() = "<redacted>"
}

class PrivateBytes(value: ByteArray) {
    private val value = value.copyOf()
    fun copyForCodec(): ByteArray = value.copyOf()
    override fun toString() = "<private-bytes>"
}

sealed interface StoredCredentials {
    val scope: StorageScope
    val expiresAtMillis: Long

    /** A guest token is not a provider access token and has no registered device-session header. */
    data class Guest(
        override val scope: StorageScope,
        val guestSessionId: SecretText,
        val guestToken: SecretText,
        override val expiresAtMillis: Long,
    ) : StoredCredentials {
        init { require(scope.actorKind == ActorKind.GUEST && expiresAtMillis >= 0) }
    }

    /** Null deviceSessionId is an authenticated account still awaiting account bootstrap. */
    data class Account(
        override val scope: StorageScope,
        val accessToken: SecretText,
        val refreshToken: SecretText?,
        override val expiresAtMillis: Long,
        val deviceSessionId: SecretText? = null,
    ) : StoredCredentials {
        init { require(scope.actorKind == ActorKind.ACCOUNT && expiresAtMillis >= 0) }
    }
}

sealed interface PortResult<out T> {
    data class Value<T>(val value: T) : PortResult<T>
    data class Failure(val reason: FailureReason, val retryAfterSeconds: Long? = null) : PortResult<Nothing> {
        init { require(retryAfterSeconds == null || retryAfterSeconds >= 0) }
    }
}

enum class FailureReason {
    NOT_CONFIGURED, OFFLINE, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT,
    RATE_LIMITED, UNAVAILABLE, INVALID_DATA, STALE_SESSION, STORAGE_FAILURE,
    /** A mutation may have committed: keep the original command key and reconcile that intent. */
    OUTCOME_UNKNOWN,
}

/** Native secure storage only (Keychain/Keystore-backed); never preferences, logs or recipe storage. */
interface SecureCredentialStore {
    suspend fun read(scope: StorageScope): PortResult<StoredCredentials?>
    suspend fun replace(scope: StorageScope, credentials: StoredCredentials): PortResult<Unit>
    suspend fun erase(scope: StorageScope): PortResult<Unit>
}

/** Auth adapter verifies provider results; UI observes identity metadata, never raw credentials. */
interface IdentityPort {
    suspend fun restore(scope: StorageScope): PortResult<IdentitySession?>
    suspend fun refresh(expected: IdentitySession): PortResult<IdentitySession>
    suspend fun revoke(expected: IdentitySession): PortResult<Unit>
}

data class IdentitySession(val scope: StorageScope, val expiresAtMillis: Long) {
    init { require(scope.actorKind != ActorKind.DEMO && expiresAtMillis >= 0) }
}

data class RecordKey(val collection: String, val id: String) {
    init { require(collection.isNotBlank() && id.isNotBlank()) }
    override fun toString() = "RecordKey(collection=$collection, id=<redacted>)"
}

data class PrivateRecord(val revision: Long, val schemaVersion: Int, val payload: PrivateBytes) {
    init { require(revision >= 1 && schemaVersion >= 1) }
}

/** Null expectedRevision means create-if-absent, never unconditional overwrite. */
sealed interface StoreMutation {
    val key: RecordKey
    val expectedRevision: Long?

    data class Put(override val key: RecordKey, override val expectedRevision: Long?, val schemaVersion: Int, val payload: PrivateBytes) : StoreMutation {
        init { require(expectedRevision == null || expectedRevision >= 1); require(schemaVersion >= 1) }
    }
    data class Delete(override val key: RecordKey, override val expectedRevision: Long) : StoreMutation {
        init { require(expectedRevision >= 1) }
    }
}

/**
 * One owner-scoped atomic transaction can persist state and its outbox command together.
 * Any revision conflict rejects the entire batch. Implementations detach buffers and reject
 * duplicate keys. Revisions are store-owned, monotonically increasing, and never caller-set.
 * Credentials are deliberately outside this interface. Durable implementations are not wired yet.
 */
interface PrivateStateStore {
    suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?>
    suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>>
    suspend fun eraseScope(scope: StorageScope): PortResult<Unit>
}

/** Transport objects stay outside the stable cooking models and are decoded by contract adapters. */
class ApiCall(
    val operationId: String,
    pathParameters: Map<String, String> = emptyMap(),
    queryParameters: Map<String, List<String>> = emptyMap(),
    val body: PrivateBytes? = null,
    val idempotencyKey: SecretText? = null,
    val ifMatch: String? = null,
) {
    private val storedPathParameters = pathParameters.toMap()
    private val storedQueryParameters = queryParameters.mapValues { it.value.toList() }

    // Kotlin read-only views can expose mutable implementations through casts. Every projection
    // is detached so callers cannot change an already-created command's stored intent.
    val pathParameters: Map<String, String> get() = storedPathParameters.toMap()
    val queryParameters: Map<String, List<String>> get() = storedQueryParameters.mapValues { it.value.toList() }
    init {
        require(operationId.matches(Regex("[A-Za-z][A-Za-z0-9]*")))
        require(storedPathParameters.all { (key, value) -> key.isNotBlank() && value.isNotBlank() && value.none(Char::isISOControl) })
        require(storedQueryParameters.all { (key, values) -> key.isNotBlank() && values.all { it.none(Char::isISOControl) } })
        require(ifMatch == null || (ifMatch.isNotBlank() && ifMatch.none(Char::isISOControl)))
    }
    override fun toString() = "ApiCall(operationId=$operationId, parameters=<redacted>, body=<redacted>)"
}

data class ApiReply(
    val status: Int,
    val body: PrivateBytes?,
    val etag: String? = null,
    val traceId: String? = null,
    val retryAfterSeconds: Long? = null,
    val contentType: String? = null,
) {
    init {
        require(status in 100..599)
        require(traceId == null || (traceId.isNotBlank() && traceId.length <= 256 && traceId.none(Char::isISOControl)))
        require(etag == null || (etag.length <= 256 && etag.isNotBlank() && etag.none(Char::isISOControl)))
        require(retryAfterSeconds == null || retryAfterSeconds >= 1)
        require(contentType == null || (contentType.isNotBlank() && contentType.length <= 256 && contentType.none(Char::isISOControl)))
    }
    override fun toString() = "ApiReply(status=$status, body=<redacted>, metadata=<redacted>)"
}

/**
 * The generated adapter resolves canonical operations and validated parameters. Implementations
 * must never accept a server URL from a recipe, post, user text or this request body. Credentials
 * are obtained internally from secure storage. A new operation is not permission to invoke it.
 * Preserve HTTP 4xx/5xx as Value(ApiReply) so Problem fields reach the schema adapter. Failure
 * represents a local/transport failure; a possibly accepted write uses OUTCOME_UNKNOWN.
 * Local OFFLINE and UNAUTHENTICATED mean no request was dispatched (HTTP 401 is a Value).
 * Command recovery relies on that distinction to avoid charging an unsent network attempt.
 * Guests never get X-Device-Session; account bootstrap omits it; subsequent user calls require
 * the bootstrapped account deviceSessionId. Reject missing/wrong-kind credentials before send.
 */
interface AccountTransport {
    suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply>
}

/** Explicit anonymous bootstrap/preview/health calls. Never attaches stored account credentials. */
interface PublicTransport {
    suspend fun executePublic(call: ApiCall): PortResult<ApiReply>
}

enum class Connectivity { ONLINE, OFFLINE, UNKNOWN }
fun interface ConnectivityPort { fun current(): Connectivity }

/** Local timer scheduling is best effort and has an explicit permission/unavailable result. */
interface TimerNotificationPort {
    suspend fun schedule(scope: StorageScope, timerId: String, endAtMillis: Long): PortResult<Unit>
    suspend fun cancel(scope: StorageScope, timerId: String): PortResult<Unit>
    suspend fun cancelScope(scope: StorageScope): PortResult<Unit>
}

/** Immutable ticket for a single session incarnation; construction belongs to SessionBoundary. */
class SessionLease internal constructor(val scope: StorageScope, internal val epoch: Long)

/** Dispose on the same serialized application dispatcher as the owning boundary. */
class SessionInvalidationSubscription internal constructor(private val dispose: () -> Unit) {
    fun close() = dispose()
}

/**
 * Client-side stale-result isolation, not authentication or a thread-safe server policy engine.
 * Own it on one serialized UI/application dispatcher. Clear before logout/account switching;
 * a failed remote logout must not reactivate local private UI or pending responses.
 */
class SessionBoundary {
    private var epoch = 0L
    private var active: SessionLease? = null
    private class Invalidation(val lease: SessionLease, var callback: (() -> Unit)?)
    private val invalidations = mutableListOf<Invalidation>()

    fun activate(scope: StorageScope): SessionLease {
        advanceEpoch()
        return SessionLease(scope, epoch).also {
            active = it
            notifyInvalidated()
        }
    }

    fun clear() {
        advanceEpoch()
        active = null
        notifyInvalidated()
    }

    fun current(): SessionLease? = active

    fun isCurrent(lease: SessionLease): Boolean = active === lease && lease.epoch == epoch

    /**
     * One-shot lifecycle signal, never authentication or a replacement for isCurrent checks.
     * Called synchronously AFTER this lease is invalidated by clear/activate, or immediately
     * if it is already stale. Register, dispose and mutate on the same application dispatcher.
     * The callback must only redact in-memory state/invalidate work generations; it must not
     * suspend, do I/O or activate another session. An observer exception cannot undo clearing,
     * prevent other observers from being notified, or leak its private message into logs.
     */
    fun onInvalidated(lease: SessionLease, callback: () -> Unit): SessionInvalidationSubscription {
        val entry = Invalidation(lease, callback)
        if (isCurrent(lease)) invalidations += entry else notify(entry)
        return SessionInvalidationSubscription {
            entry.callback = null
            invalidations.remove(entry)
        }
    }

    private fun notifyInvalidated() {
        val stale = invalidations.filter { !isCurrent(it.lease) }
        invalidations.removeAll(stale.toSet())
        stale.forEach(::notify)
    }

    private fun notify(entry: Invalidation) {
        val callback = entry.callback
        entry.callback = null // Detach captured private state before invoking arbitrary observer code.
        try { callback?.invoke() } catch (_: Exception) { /* No logging and no rollback of invalidation. */ }
    }

    suspend fun <T> execute(lease: SessionLease, operation: suspend () -> PortResult<T>): PortResult<T> {
        if (!isCurrent(lease)) return PortResult.Failure(FailureReason.STALE_SESSION)
        val result = operation()
        return if (isCurrent(lease)) result else PortResult.Failure(FailureReason.STALE_SESSION)
    }

    suspend fun execute(lease: SessionLease, transport: AccountTransport, call: ApiCall): PortResult<ApiReply> =
        execute(lease) { transport.execute(lease, call) }

    private fun advanceEpoch() {
        check(epoch < Long.MAX_VALUE) { "Session epoch exhausted; recreate the application boundary." }
        epoch += 1
    }
}
