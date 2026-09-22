package com.feedme.server.staff

import com.feedme.server.identity.SupabasePostgresAuthority
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/** Positive publication uses the actual immutable professional attestation plus
 * current reviewer account/enrollment/qualification. A reviewer need not remain
 * signed in: their real authenticated review is retained by the domain audit.
 * Recall intentionally does not renew positive publication evidence. */
internal class SupabaseStaffRecipePublicationAccess(
    private val environment: String,
    private val provider: SupabasePostgresAuthority,
    private val policy: SupabaseStaffAdmissionPolicy,
) {
    fun lock(c: Connection, actor: SupabaseStaffCatalogActor, reviewerId: UUID,
        reviewedAt: Instant, professionalReview: JsonObject?, recall: Boolean): SupabaseStaffRecipePublicationProof {
        actor.requireConnection(c)
        if (!policy.catalogPublicationEnabled) fail(SupabaseStaffFailureCode.STAFF_NOT_CONFIGURED)
        if (actor.environment != environment || !actor.canPublish) denied()
        if (recall) return SupabaseStaffRecipePublicationProof(actor.validUntil) { actual ->
            actor.requireConnection(actual)
            actor.checkAt(now(actual))
        }.also { it.revalidate(c) }
        if (actor.actorId == reviewerId || professionalReview == null) denied()
        val reviewer = reviewer(c, reviewerId, reviewedAt)
        val qualification = SupabaseStaffRecipeQualificationStore.check(c, environment, reviewerId,
            policy.policyVersion, reviewedAt, professionalReview)
        val until = minOf(actor.validUntil, reviewer.policyUntil, reviewer.actorUntil, qualification.validUntil)
        actor.requireValidUntil(until)
        return SupabaseStaffRecipePublicationProof(until) { actual ->
            actor.requireConnection(actual)
            if (reviewer(actual, reviewerId, reviewedAt) != reviewer) denied()
            qualification.revalidate(actual)
            actor.checkAt(now(actual))
        }.also { it.revalidate(c) }
    }

    private fun reviewer(c: Connection, id: UUID, reviewedAt: Instant): Reviewer {
        // Resolve the real provider identity first, lock it through the existing
        // narrow projection, then lock the exact staff roots with the reviewed helper.
        val initial = enrollment(c, id)
        if (initial.issuer != provider.deployment.verification.issuer) denied()
        val subject = try { UUID.fromString(initial.subject) } catch (_: IllegalArgumentException) { denied() }
        if (subject.toString() != initial.subject) denied()
        provider.lockAcceptedWorkAccount(c, initial.issuer, subject)
        c.prepareStatement("SELECT staff.lock_recipe_draft_actor(?,?,?)").use { s ->
            s.setString(1, environment); s.setString(2, initial.issuer); s.setString(3, initial.subject)
            s.executeQuery().use { r -> if (!r.next() || !r.getBoolean(1) || r.next()) denied() }
        }
        val actual = enrollment(c, id)
        val at = now(c)
        if (actual != initial || actual.policyVersion != policy.policyVersion || actual.clientId != policy.policyClientId ||
            actual.policyIssuer != actual.issuer || actual.audience != "authenticated" || !actual.policyEnabled ||
            !actual.enabled || !actual.canReview || reviewedAt > at ||
            actual.policyFrom > reviewedAt || actual.actorFrom > reviewedAt || actual.tokenValidAfter > reviewedAt ||
            at >= actual.policyUntil || at >= actual.actorUntil) denied()
        return actual
    }

    private fun enrollment(c: Connection, id: UUID): Reviewer = c.prepareStatement(
        "SELECT p.version,p.issuer,p.client_id,p.audience,p.enabled,p.not_before,p.valid_until," +
            "a.issuer,a.subject,a.can_review,a.enabled,a.token_valid_after,a.not_before,a.valid_until " +
            "FROM ONLY staff.publication_policies p JOIN ONLY staff.actors a ON a.environment=p.environment " +
            "WHERE p.environment=? AND a.actor_id=?").use { s ->
        s.setString(1, environment); s.setObject(2, id)
        s.executeQuery().use { r ->
            if (!r.next()) denied()
            Reviewer(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getBoolean(5),
                instant(r.getObject(6, OffsetDateTime::class.java)), instant(r.getObject(7, OffsetDateTime::class.java)),
                r.getString(8), r.getString(9), r.getBoolean(10), r.getBoolean(11),
                instant(r.getObject(12, OffsetDateTime::class.java)), instant(r.getObject(13, OffsetDateTime::class.java)),
                instant(r.getObject(14, OffsetDateTime::class.java))).also { if (r.next()) denied() }
        }
    }

    private data class Reviewer(val policyVersion: String, val policyIssuer: String, val clientId: String,
        val audience: String, val policyEnabled: Boolean, val policyFrom: Instant, val policyUntil: Instant,
        val issuer: String, val subject: String, val canReview: Boolean, val enabled: Boolean,
        val tokenValidAfter: Instant, val actorFrom: Instant, val actorUntil: Instant)
    private fun instant(value: OffsetDateTime?): Instant = value?.toInstant() ?: denied()
    private fun now(c: Connection): Instant = c.createStatement().use { s ->
        s.executeQuery("SELECT clock_timestamp()").use { r ->
            if (!r.next()) denied(); instant(r.getObject(1, OffsetDateTime::class.java)).also { if (r.next()) denied() }
        }
    }
    private fun denied(): Nothing = fail(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
    private fun fail(code: SupabaseStaffFailureCode): Nothing = throw SupabaseStaffFailure(code)
}

internal class SupabaseStaffRecipePublicationProof internal constructor(
    val validUntil: Instant, private val validate: (Connection) -> Unit,
) {
    fun revalidate(c: Connection) = validate(c)
    override fun toString() = "SupabaseStaffRecipePublicationProof(<redacted>)"
}
