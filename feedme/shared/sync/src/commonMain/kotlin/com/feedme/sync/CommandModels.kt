package com.feedme.sync

import com.feedme.core.ports.ApiCall
import com.feedme.core.ports.ApiReply
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SessionLease

/** Durable state is distinct from remote success and from applying a receipt to domain state. */
enum class CommandPhase { READY, IN_FLIGHT, RETRY_WAIT, AWAITING_CONFIRMATION, NEEDS_RESOLUTION, RECEIPT_READY, APPLIED, DISCARDED }

/** Safe local categories only. Never persist arbitrary server exception/problem text in this field. */
enum class CommandIssue {
    NONE, OUTCOME_UNKNOWN, OFFLINE, TEMPORARILY_UNAVAILABLE, AUTH_REQUIRED, PERMISSION_CHANGED,
    NOT_FOUND, CONFLICT, GONE, VERSION_CONFLICT, INVALID_REQUEST, REMOTE_REJECTED,
    RETRY_EXHAUSTED, RECONCILIATION_REQUIRED, CLOCK_CHANGED, DEPENDENCY_FAILED,
    NOT_CONFIGURED, DOMAIN_RECHECK_REQUIRED, RECIPE_RECALLED,
}

enum class CommandResumption { AUTOMATIC_WITH_PREFLIGHT, FRESH_CONFIRMATION }

/**
 * The binding is a non-secret random reference maintained by the identity coordinator. It is NOT
 * an access/refresh/guest token or the HTTP device-session header. Rotate on native session/device
 * re-bootstrap, retain across ordinary token refresh, and verify it in every execution gate.
 */
class CommandIntent(
    val commandId: String,
    val originBinding: String,
    val call: ApiCall,
    dependencyCommandIds: List<String> = emptyList(),
) {
    private val dependencies = dependencyCommandIds.toList()
    val dependencyCommandIds: List<String> get() = dependencies.toList()
    override fun toString() = "CommandIntent(operationId=${call.operationId}, identity=<redacted>, request=<redacted>)"
}

class CommandView internal constructor(
    val commandId: String,
    val operationId: String,
    val phase: CommandPhase,
    val attempts: Int,
    val issue: CommandIssue,
    val retryAtMillis: Long,
    /** CAS identity for acknowledging this exact receipt/state, not the remote ETag. */
    val localRevision: Long,
) {
    override fun toString() = "CommandView(operationId=$operationId, phase=$phase, attempts=$attempts, identity=<redacted>)"
}

class CommandReceipt internal constructor(val command: CommandView, val reply: ApiReply) {
    override fun toString() = "CommandReceipt(operationId=${command.operationId}, status=${reply.status}, body=<redacted>)"
}

sealed interface ExecutionDecision {
    /** Client preflight only; server still authenticates/authorizes and applies domain constraints. */
    data object Ready : ExecutionDecision
    data class Wait(val issue: CommandIssue = CommandIssue.DOMAIN_RECHECK_REQUIRED) : ExecutionDecision
}

/**
 * Required real composition dependency, with no permissive default. Recheck identity/origin binding,
 * current ownership, permissions/recalls and operation-specific prerequisites. A preflight grant is
 * not durable authority; execute immediately with the same lease and original immutable intent.
 */
fun interface CommandExecutionGate {
    suspend fun evaluate(lease: SessionLease, intent: CommandIntent): ExecutionDecision
}

/**
 * Domain handoff for a fully operation/status/content/schema-bound reply, before journal outcome
 * persistence and outside its mutex. Cooking composition MUST supply this alongside its required
 * execution gate to install learned recall fences. Generic journals without domain side effects
 * may retain the queue's no-op default. Implementations must be idempotent, owner-fenced and safe
 * to repeat after interrupted recovery; this callback does not authorize changing the request.
 * A failure leaves the already-sent attempt IN_FLIGHT instead of claiming success or retry.
 */
fun interface CommandReplyObserver {
    suspend fun observe(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit>
}

internal data class QueueIndex(val ids: List<String>, val lastObservedMillis: Long)
internal data class RequestMetadata(
    val operationId: String,
    val path: Map<String, String>,
    val query: Map<String, List<String>>,
    val ifMatch: String?,
    val hasBody: Boolean,
)
internal data class ReplyMetadata(
    val status: Int, val etag: String?, val traceId: String?, val retryAfterSeconds: Long?,
    val contentType: String?, val hasBody: Boolean,
)
internal data class JournalCommand(
    val id: String,
    val originBinding: String,
    val scopeEnvironment: String,
    val scopeActorKind: String,
    val scopeActorId: String,
    val request: RequestMetadata,
    val dependencies: List<String>,
    val createdAt: Long,
    val firstAttemptAt: Long?,
    val retryAt: Long,
    val phase: CommandPhase,
    val attempts: Int,
    val issue: CommandIssue,
    val reply: ReplyMetadata? = null,
)

internal val commandUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
internal const val MAX_PENDING = 128
internal const val MAX_DEPENDENCIES = 64
internal const val JOURNAL_SCHEMA = 1
