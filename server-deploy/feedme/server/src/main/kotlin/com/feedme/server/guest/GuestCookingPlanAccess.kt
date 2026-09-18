package com.feedme.server.guest

import com.feedme.server.catalog.RecipeCatalogJournal
import com.feedme.server.cooking.CookingFailure
import com.feedme.server.cooking.CookingFailureCode
import com.feedme.server.cooking.CookingPlanReader
import com.feedme.server.cooking.VerifiedCookingPrincipal
import com.feedme.server.db.CommandActor
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import com.feedme.server.planning.CookingPlanSnapshot
import com.feedme.server.planning.CookingPlanUse
import java.sql.Connection
import java.util.UUID

/** Transaction-local Plan bridge created ONLY by GuestPlanningStore.withCooking after its
 * actual opaque-token admission. It is not an identity provider, context issued to clients,
 * alternate runtime, or permission to copy a recipe. All handles remain on the actual
 * caller transaction; retained metadata alone cannot authorize a later operation. */
internal class GuestCookingPlanAccess(
    private val environment: String,
    private val connection: Connection,
    private val actualActor: VerifiedKitchenPrincipal,
    private val catalog: RecipeCatalogJournal,
    private val policy: GuestPlanningPolicy,
) : CookingPlanReader {
    private val thread = Thread.currentThread()
    private val transaction = transactionId()
    val principal: VerifiedCookingPrincipal
    private var binding: BoundPlan? = null

    init {
        current()
        if (actualActor.environment != environment || actualActor.kind != CommandActor.GUEST ||
            actualActor.deviceSessionId != null || catalog.environment != environment) unauthenticated()
        // The actual guest session, not principal/installation/client IDs, is the internal
        // device sequence identity. Both rows are already locked by the real session owner.
        principal = VerifiedCookingPrincipal(environment, CommandActor.GUEST, actualActor.principalId, null, guestId())
        requireBound(connection, principal)
    }

    override fun lock(connection: Connection, actor: VerifiedCookingPrincipal, planId: UUID,
        use: CookingPlanUse, sessionId: UUID?): CookingPlanSnapshot {
        requireBound(connection, actor)
        val previous = binding
        if (previous != null) {
            if (previous.planId != planId || previous.use != use || previous.sessionId != sessionId)
                unavailable()
            return previous.verifier.requireCookingSnapshot(connection, actualActor, previous.verified).also {
                requireSame(it, previous.snapshot); requireBound(connection, actor)
            }
        }
        val verifier = GuestPlanningManifestVerifier(environment, catalog, policy)
        val verified = when (use) {
            CookingPlanUse.NEW_SELECTION -> {
                if (sessionId != null) unavailable()
                verifier.verifyNewCookFirstPlan(connection, actualActor, planId)
            }
            CookingPlanUse.EXISTING_PIN -> verifier.verifyPinnedCookFirstPlan(connection, actualActor, planId,
                sessionId ?: throw CookingFailure(CookingFailureCode.PLAN_UNAVAILABLE))
        }
        val snapshot = verifier.requireCookingSnapshot(connection, actualActor, verified)
        requireBound(connection, actor)
        binding = BoundPlan(planId, use, sessionId, verifier, verified, snapshot)
        return snapshot
    }

    fun requireBound(connection: Connection, actor: VerifiedCookingPrincipal) {
        current()
        if (connection !== this.connection || actor !== principal || Thread.currentThread() !== thread ||
            connection.isClosed || connection.autoCommit || transactionId() != transaction ||
            actor.environment != environment || actor.kind != CommandActor.GUEST || actor.deviceSessionId != null ||
            actor.principalId != actualActor.principalId || actor.guestSessionId != guestId()) unauthenticated()
    }

    fun revalidate() {
        requireBound(connection, principal)
        binding?.let {
            // Snapshot retrieval itself revalidates the exact retained cooking binding.
            requireSame(it.snapshot, it.verifier.requireCookingSnapshot(connection, actualActor, it.verified))
        }
        requireBound(connection, principal)
    }

    private fun guestId(): UUID = connection.prepareStatement("SELECT g.id FROM identity.principals p " +
        "JOIN identity.guest_sessions g ON g.environment=p.environment AND g.id=p.guest_session_id " +
        "WHERE p.environment=? AND p.id=? AND p.kind='guest' AND p.status='active' " +
        "AND g.revoked_at IS NULL AND g.merged_to_user_id IS NULL FOR SHARE OF p,g").use { statement ->
        statement.setString(1, environment); statement.setObject(2, actualActor.principalId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) unauthenticated()
            rows.getObject(1, UUID::class.java).also { if (rows.next()) unavailable() }
        }
    }

    private fun transactionId(): Long {
        current()
        if (connection.isClosed || connection.autoCommit) unauthenticated()
        return connection.createStatement().use { statement -> statement.executeQuery("SELECT txid_current()").use { rows ->
            if (!rows.next()) unavailable()
            rows.getLong(1).also { if (rows.next()) unavailable() }
        } }
    }
    private fun requireSame(a: CookingPlanSnapshot, b: CookingPlanSnapshot) {
        if (a.snapshotText != b.snapshotText || a.snapshotHash != b.snapshotHash || a.proofHash != b.proofHash ||
            a.evidenceHash != b.evidenceHash) unavailable()
    }
    private class BoundPlan(val planId: UUID, val use: CookingPlanUse, val sessionId: UUID?,
        val verifier: GuestPlanningManifestVerifier, val verified: GuestVerifiedManifest, val snapshot: CookingPlanSnapshot)
    private fun current() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest cooking interrupted")
    }
    private fun unauthenticated(): Nothing = throw CookingFailure(CookingFailureCode.UNAUTHENTICATED)
    private fun unavailable(): Nothing = throw CookingFailure(CookingFailureCode.STORAGE_UNAVAILABLE)
    override fun toString() = "GuestCookingPlanAccess(<redacted>)"
}
