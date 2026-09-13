package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException

/** Native secure-store snapshot, not identity proof. Never expose to UI, telemetry or recipe state. */
class CredentialSnapshot internal constructor(
    val incarnation: String,
    val revision: Long,
    val credentials: StoredCredentials,
) {
    val scope: StorageScope get() = credentials.scope
    init { requireCredentialUuid(incarnation); require(revision > 0) }
    override fun toString() = "CredentialSnapshot(revision=$revision, identity=<redacted>, credentials=<redacted>)"
}

/** Non-secret slot metadata; revision persists even when its credential key has been retired. */
class CredentialSlotState(val revision: Long, val owner: StorageScope?, val incarnation: String?) {
    init {
        require(revision > 0 && (owner == null) == (incarnation == null))
        require(owner?.actorKind != ActorKind.DEMO)
        incarnation?.let(::requireCredentialUuid)
    }
    override fun toString() = "CredentialSlotState(revision=$revision, occupied=${owner != null}, identity=<redacted>)"
}

/**
 * One active native credential slot, outside ordinary private/recipe storage. Implementations must
 * preserve a durable empty-slot revision and generate a new unguessable incarnation for create.
 * The serialized identity owner must verify provider/configuration/owner and recovery gates before
 * calling these methods. They do not verify a token, authorize sign-in or stage guest-merge proof.
 */
interface IncarnationCredentialStore : CredentialRetirementPort {
    suspend fun state(): PortResult<CredentialSlotState>
    suspend fun read(scope: StorageScope): PortResult<CredentialSnapshot?>
    suspend fun create(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialSnapshot>
    /** Exact-incarnation/revision refresh. Preserve scope, credential kind and device-session ID. */
    suspend fun replace(expected: CredentialSnapshot, credentials: StoredCredentials): PortResult<CredentialSnapshot>
    /** Verified bootstrap result only; account with no prior device session, exact snapshot CAS. */
    suspend fun attachDeviceSession(expected: CredentialSnapshot, deviceSessionId: SecretText): PortResult<CredentialSnapshot>
}

/**
 * Transport-only view. Bound to one actual lease/incarnation; never follows a same-account login.
 * The composition root must pair it with its immutable trusted endpoint/provider configuration.
 * Expiry is retained for transport/provider policy, not converted into automatic data erasure.
 */
class CredentialTransportView(
    private val store: IncarnationCredentialStore,
    private val boundary: SessionBoundary,
    private val lease: SessionLease,
    private val incarnation: String,
) : SecureCredentialStore {
    init { requireCredentialUuid(incarnation); require(lease.scope.actorKind != ActorKind.DEMO) }

    override suspend fun read(scope: StorageScope): PortResult<StoredCredentials?> {
        if (scope != lease.scope || !boundary.isCurrent(lease)) return PortResult.Failure(FailureReason.STALE_SESSION)
        val result = try { store.read(scope) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            return PortResult.Failure(if (boundary.isCurrent(lease)) FailureReason.STORAGE_FAILURE else FailureReason.STALE_SESSION)
        }
        if (!boundary.isCurrent(lease)) return PortResult.Failure(FailureReason.STALE_SESSION)
        return when (result) {
            is PortResult.Failure -> result
            is PortResult.Value -> {
                val value = result.value ?: return PortResult.Value(null)
                if (value.scope != scope || value.incarnation != incarnation) return PortResult.Failure(FailureReason.STALE_SESSION)
                val projected = when (val credentials = value.credentials) {
                    is StoredCredentials.Account -> StoredCredentials.Account(scope, credentials.accessToken, null,
                        credentials.expiresAtMillis, credentials.deviceSessionId)
                    is StoredCredentials.Guest -> credentials
                }
                PortResult.Value(projected)
            }
        }
    }

    override suspend fun replace(scope: StorageScope, credentials: StoredCredentials): PortResult<Unit> =
        PortResult.Failure(FailureReason.NOT_CONFIGURED)
    override suspend fun erase(scope: StorageScope): PortResult<Unit> = PortResult.Failure(FailureReason.NOT_CONFIGURED)
    override fun toString() = "CredentialTransportView(<redacted>)"
}

internal fun requireCredentialUuid(value: String): String = value.also {
    require(it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
}

internal class CredentialManifest(val revision: Long, val scope: StorageScope?, val incarnation: String?) {
    init { CredentialSlotState(revision, scope, incarnation) }
    override fun toString() = "CredentialManifest(revision=$revision, identity=<redacted>)"
}

internal class CredentialFormatException : Exception("Credential data unavailable")
