package com.feedme.transport

import com.feedme.contracts.PrincipalClass
import com.feedme.contracts.CanonicalBodyValidator
import com.feedme.contracts.CanonicalResponseBinder
import com.feedme.contracts.ResponseBindingResult
import com.feedme.core.ports.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel

internal expect fun platformHttpClient(): HttpClient

/**
 * Explicit-lifetime transport. Own boundary activation/clear and this adapter on the SAME serial
 * dispatcher (normally UI Main). A dispatcher with parallel execution is not a session boundary.
 * Secure storage is supplied by the platform; this adapter never refreshes or persists credentials.
 * Cancellation propagates: it cannot undo a dispatched mutation. Reconcile using its original key.
 */
class FeedMeTransport internal constructor(
    private val endpoint: ApiEndpoint,
    private val credentials: SecureCredentialStore,
    private val boundary: SessionBoundary,
    private val ownerDispatcher: CoroutineDispatcher,
    private val clock: EpochClock,
    private val connectivity: ConnectivityPort,
    private val client: HttpClient,
) : AccountTransport, PublicTransport {
    private val preparation = RequestPreparation()
    private val exchange = HttpExchange(endpoint, client)
    private val validator = CanonicalBodyValidator.bundled()
    private val requestValidator = MobileRequestValidator(preparation, validator)
    private val binder = CanonicalResponseBinder(validator)
    private var closed = false

    override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> = withContext(ownerDispatcher) {
        if (closed) return@withContext failure(FailureReason.NOT_CONFIGURED)
        if (!boundary.isCurrent(lease)) return@withContext failure(FailureReason.STALE_SESSION)
        if (lease.scope.environment != endpoint.environment) return@withContext failure(FailureReason.INVALID_DATA)
        if (connectivity.current() == Connectivity.OFFLINE) return@withContext failure(FailureReason.OFFLINE)
        val principal = when (lease.scope.actorKind) {
            ActorKind.ACCOUNT -> PrincipalClass.ACCOUNT
            ActorKind.GUEST -> PrincipalClass.GUEST
            ActorKind.DEMO -> return@withContext failure(FailureReason.UNAUTHENTICATED)
        }
        // Detach collections before the secure-store suspension; no caller mutation can alter intent.
        val snapshot = ApiCall(call.operationId, call.pathParameters.toMap(),
            call.queryParameters.mapValues { it.value.toList() }, call.body, call.idempotencyKey, call.ifMatch)
        if (!requestValidator.accepts(snapshot, principal)) return@withContext failure(FailureReason.INVALID_DATA)
        val stored = try {
            when (val result = credentials.read(lease.scope)) {
                is PortResult.Value -> result.value
                is PortResult.Failure -> return@withContext if (boundary.isCurrent(lease))
                    failure(FailureReason.STORAGE_FAILURE) else failure(FailureReason.STALE_SESSION)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            return@withContext if (boundary.isCurrent(lease)) failure(FailureReason.STORAGE_FAILURE)
            else failure(FailureReason.STALE_SESSION)
        }
        if (!boundary.isCurrent(lease)) return@withContext failure(FailureReason.STALE_SESSION)
        if (closed) return@withContext failure(FailureReason.NOT_CONFIGURED)
        if (stored == null || stored.scope != lease.scope || stored.expiresAtMillis <= clock.nowMillis())
            return@withContext failure(FailureReason.UNAUTHENTICATED)
        val token: SecretText
        val device: String?
        when (stored) {
            is StoredCredentials.Account -> {
                if (principal != PrincipalClass.ACCOUNT) return@withContext failure(FailureReason.UNAUTHENTICATED)
                token = stored.accessToken
                device = stored.deviceSessionId?.use { it }
            }
            is StoredCredentials.Guest -> {
                if (principal != PrincipalClass.GUEST) return@withContext failure(FailureReason.UNAUTHENTICATED)
                token = stored.guestToken
                device = null
            }
        }
        val prepared = preparation.prepare(snapshot, principal, device)
            ?: return@withContext failure(FailureReason.INVALID_DATA)
        val bearer = token.use { it }
        if (bearer.length > 8192 || !bearer.matches(Regex("[A-Za-z0-9._~+/-]+=*")))
            return@withContext failure(FailureReason.UNAUTHENTICATED)
        if (!boundary.isCurrent(lease)) return@withContext failure(FailureReason.STALE_SESSION)
        if (connectivity.current() == Connectivity.OFFLINE) return@withContext failure(FailureReason.OFFLINE)
        val result = exchange.execute(prepared, bearer)
        // A stale result must not reach new-owner UI/storage, even if its remote write committed.
        if (!boundary.isCurrent(lease)) failure(FailureReason.STALE_SESSION)
        else if (closed) failure(FailureReason.NOT_CONFIGURED) else bindReply(snapshot.operationId, prepared.operation.method, result)
    }

    override suspend fun executePublic(call: ApiCall): PortResult<ApiReply> = withContext(ownerDispatcher) {
        if (closed) return@withContext failure(FailureReason.NOT_CONFIGURED)
        if (connectivity.current() == Connectivity.OFFLINE) return@withContext failure(FailureReason.OFFLINE)
        if (!requestValidator.accepts(call, PrincipalClass.PUBLIC)) return@withContext failure(FailureReason.INVALID_DATA)
        val prepared = preparation.prepare(call, PrincipalClass.PUBLIC)
            ?: return@withContext failure(FailureReason.INVALID_DATA)
        val result = exchange.execute(prepared, null)
        if (closed) failure(FailureReason.NOT_CONFIGURED) else bindReply(call.operationId, prepared.operation.method, result)
    }

    /** Call on ownerDispatcher; cancels this owned client's work. Does not revoke a remote session. */
    fun close() { closed = true; client.coroutineContext.cancel(); client.close() }

    private fun bindReply(operationId: String, method: String, result: PortResult<ApiReply>): PortResult<ApiReply> {
        if (result !is PortResult.Value) return result
        val reply = result.value
        val binding = try {
            binder.bind(operationId, reply.status, reply.body?.copyForCodec(), reply.contentType, reply.traceId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // A decoder defect after dispatch is not proof of rollback either. No raw error escapes.
            return failure(if (method in setOf("GET", "HEAD", "OPTIONS")) FailureReason.INVALID_DATA else FailureReason.OUTCOME_UNKNOWN)
        }
        return if (binding is ResponseBindingResult.Accepted) result else
            failure(if (method in setOf("GET", "HEAD", "OPTIONS")) FailureReason.INVALID_DATA else FailureReason.OUTCOME_UNKNOWN)
    }

    companion object {
        fun create(endpoint: ApiEndpoint, credentials: SecureCredentialStore, boundary: SessionBoundary,
            ownerDispatcher: CoroutineDispatcher, clock: EpochClock, connectivity: ConnectivityPort): FeedMeTransport =
            FeedMeTransport(endpoint, credentials, boundary, ownerDispatcher, clock, connectivity, platformHttpClient())
    }
}

private fun failure(reason: FailureReason) = PortResult.Failure(reason)
