package com.feedme.server.memory

import com.feedme.server.db.CommandActor
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/** Identity metadata constructed by a real transaction owner, never an authorization grant. */
internal class VerifiedMemoryPrincipal(val environment: String, val kind: CommandActor,
    val principalId: UUID, val deviceSessionId: UUID?, val guestSessionId: UUID? = null) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(kind in setOf(CommandActor.ACCOUNT, CommandActor.GUEST))
        require(if (kind == CommandActor.ACCOUNT) deviceSessionId != null && guestSessionId == null
            else deviceSessionId == null && guestSessionId != null)
    }
    override fun toString() = "VerifiedMemoryPrincipal(<redacted>)"
}

/** Actual immutable source/context proof. The implementation binds connection, principal,
 * original context, thread and transaction; neither returned JSON nor this interface grants
 * recipe/cooking/copy rights. Existing private opinions do not require fresh content rights. */
internal interface MemoryContextEvidence {
    val context: JsonObject
    fun revalidate(connection: Connection, actor: VerifiedMemoryPrincipal)
}

internal interface MemoryAuthority {
    fun lockPrincipal(connection: Connection, actor: VerifiedMemoryPrincipal)
    fun authorizeContext(connection: Connection, actor: VerifiedMemoryPrincipal, context: JsonObject): MemoryContextEvidence
    fun resolveFeedbackContext(connection: Connection, actor: VerifiedMemoryPrincipal, feedbackRow: JsonObject): MemoryContextEvidence
}

/** Explicit deployment budgets, not silent truncation or accepting authorization defaults. */
internal class MemoryServicePolicy(val maxResponseBytes: Int, val maxProjectionFeedback: Int,
    val cursorLifetimeSeconds: Long, val maxSourcesPerMemory: Int) {
    init {
        require(maxResponseBytes in 1..262_144)
        require(maxProjectionFeedback in 1..1_000)
        require(cursorLifetimeSeconds in 1..86_400)
        require(maxSourcesPerMemory in 1..4_096)
    }
    override fun toString() = "MemoryServicePolicy(<redacted>)"
}

/** Internal bounded reconciliation progress; not additional canonical response fields. */
internal class MemoryProjectionProgress(val processed: Int, val pending: Boolean,
    val sourceRevision: Long, val projectedRevision: Long) {
    init { require(processed >= 0 && projectedRevision >= 0 && sourceRevision >= projectedRevision) }
    override fun toString() = "MemoryProjectionProgress(<redacted>)"
}

internal enum class MemoryFailureCode { INPUT_INVALID, UNAUTHENTICATED, FORBIDDEN, MEMORY_UNAVAILABLE,
    CONTEXT_UNAVAILABLE, SOURCE_UNAVAILABLE, VERSION_CONFLICT, PROJECTION_PENDING, CURSOR_INVALID, CURSOR_EXPIRED, NOT_CONFIGURED,
    STORAGE_UNAVAILABLE, RESPONSE_TOO_LARGE }
internal class MemoryFailure(val code: MemoryFailureCode) : RuntimeException("Memory unavailable: ${code.name}")
