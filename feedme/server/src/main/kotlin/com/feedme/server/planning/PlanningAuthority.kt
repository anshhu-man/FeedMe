package com.feedme.server.planning

import com.feedme.server.db.CommandActor
import com.feedme.contracts.WireDocument
import java.sql.Connection
import java.util.UUID

/** Construct only in an adapter that has verified the actual account/device or bounded guest session. */
class VerifiedPlanningPrincipal(val environment: String, val kind: CommandActor, val principalId: UUID,
    val deviceSessionId: UUID?) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(kind == CommandActor.ACCOUNT || kind == CommandActor.GUEST)
        require((kind == CommandActor.ACCOUNT) == (deviceSessionId != null))
    }
    override fun toString() = "VerifiedPlanningPrincipal(<redacted>)"
}

/**
 * Mandatory production integration; there is no permissive default or provider implementation.
 * Calls run on the owning PostgreSQL transaction: database work only, no remote calls/commit.
 * lockPrincipal revalidates and locks eligibility, session binding/revocation, and guest expiry/merge.
 * lockCurrentSnapshot locks authoritative preferences/pantry and current authorized free catalog,
 * ingredient taxonomy, exact immutable recipe bodies and their independent editorial evidence.
 * A direct source UUID is not a grant: authorize it before including its body. Never omit known
 * exclusions, replace unknown pantry status with confirmed, or certify a draft as reviewed.
 * Acquire principal before receipt, lineage, then input/catalog policy locks; lifecycle writers
 * must share those locks. A real adapter and cross-module lock-order review gate HTTP activation.
 */
interface PlanningAuthority {
    fun lockPrincipal(connection: Connection, principal: VerifiedPlanningPrincipal)
    fun requireNewPlanningEnabledAndQuota(connection: Connection, principal: VerifiedPlanningPrincipal)
    fun lockCurrentSnapshot(connection: Connection, principal: VerifiedPlanningPrincipal,
        request: WireDocument): PlanningEvidenceSnapshot
}

enum class PlanningFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), PLAN_UNAVAILABLE(404), PLAN_EXPIRED(410),
    PREFERENCE_CHANGED(409), INPUTS_CHANGED(409), CURSOR_INVALID(409), CURSOR_EXPIRED(410),
    VERSION_CONFLICT(412), MODE_CONFIRMATION_REQUIRED(409), RECIPE_UNAVAILABLE(404),
    RECIPE_RECALLED(409), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
class PlanningServiceFailure(val code: PlanningFailureCode) : RuntimeException("Planning operation unavailable: ${code.name}")

/** Explicit operational policy, not extra API fields or an implicit retention/market decision. */
class PlanningServicePolicy(val rankingVersion: String, val heatEnabled: Boolean, val improveEnabled: Boolean,
    val planRetentionSeconds: Int, val cursorLifetimeSeconds: Int) {
    init {
        require(rankingVersion.isNotBlank() && rankingVersion.length <= 128)
        require(planRetentionSeconds in 60..2_592_000 && cursorLifetimeSeconds in 1..600)
        require(cursorLifetimeSeconds <= planRetentionSeconds)
    }
    override fun toString() = "PlanningServicePolicy(<redacted>)"
}
