package com.feedme.server.cooking

import com.feedme.server.db.CommandActor
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.JsonArray

/** Construct only from an actual verifier. Guest session identity is internal, never a request header. */
class VerifiedCookingPrincipal(val environment: String, val kind: CommandActor, val principalId: UUID,
    val deviceSessionId: UUID?, val guestSessionId: UUID? = null) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(kind == CommandActor.ACCOUNT || kind == CommandActor.GUEST)
        require(if (kind == CommandActor.ACCOUNT) deviceSessionId != null && guestSessionId == null
            else deviceSessionId == null && guestSessionId != null)
    }
    internal val sequenceIdentity: UUID get() = checkNotNull(deviceSessionId ?: guestSessionId)
    override fun toString() = "VerifiedCookingPrincipal(<redacted>)"
}

/**
 * Mandatory database-only integration, with NO accepting/default provider implementation.
 * lockPrincipal exclusively locks and revalidates the current account/device or bounded guest
 * session (including revocation/expiry/merge). It must use the SAME principal lock as kitchen,
 * planning and lifecycle writers. No callback may commit, perform network I/O or rewrite input.
 * Lock order: principal, receipt, planning lineage, current input/catalog rights, cooking row,
 * then device cursors/events. PlanningAuthority also verifies current recipe/review/recall rights.
 * requireNewCookingEnabled applies only to a fresh start, never invalidates an owned pin by itself.
 * validatePersonalNotes must authorize any referenced shortcut/community tip, or reject unsupported
 * source integration. Text and private notes never become recipe review or cooking instructions.
 */
interface CookingAuthority {
    fun lockPrincipal(connection: Connection, principal: VerifiedCookingPrincipal)
    fun requireNewCookingEnabled(connection: Connection, principal: VerifiedCookingPrincipal)
    fun validatePersonalNotes(connection: Connection, principal: VerifiedCookingPrincipal, notes: JsonArray)
}

/** Explicit bounded retention and client response budget; enforced before effects commit. */
class CookingServicePolicy(val maxResponseBytes: Int, val sessionRetentionSeconds: Int) {
    init { require(maxResponseBytes in 1..262144); require(sessionRetentionSeconds in 60..2_592_000) }
    override fun toString() = "CookingServicePolicy(<redacted>)"
}

enum class CookingFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), COOK_SESSION_UNAVAILABLE(404), PLAN_UNAVAILABLE(404),
    PLAN_EXPIRED(410), SESSION_EXPIRED(410), PLAN_NOT_READY(409), INPUTS_CHANGED(409),
    RECIPE_UNAVAILABLE(404), RECIPE_RECALLED(409), VERSION_CONFLICT(412), SEQUENCE_CONFLICT(409),
    COMMAND_CONFLICT(409), TERMINAL_CONFLICT(409), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503), RESPONSE_TOO_LARGE(422),
}
class CookingFailure(val code: CookingFailureCode) : RuntimeException("Cooking operation unavailable: ${code.name}")
