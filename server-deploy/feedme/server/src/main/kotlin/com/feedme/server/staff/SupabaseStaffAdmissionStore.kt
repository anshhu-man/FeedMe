package com.feedme.server.staff

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.db.PgTransactions
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import com.feedme.server.identity.SupabasePostgresAuthority
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** HOME observations grant no reusable authority. Optional domain access independently
 * rechecks the actual provider/MFA/registry in its owned transaction. Consumer profile,
 * email or Terms state is not staff evidence. The workforce CLI remains unchanged. */
internal class SupabaseStaffAdmissionStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val provider: SupabasePostgresAuthority,
    val policy: SupabaseStaffAdmissionPolicy,
) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    internal fun isBoundTo(env: String, tx: PgTransactions) = environment == env && transactions === tx

    internal fun validateProfessionalRecipeReview(c: Connection, actor: SupabaseStaffCatalogActor,
        reviewedAt: Instant, attestation: JsonObject) {
        actor.requireConnection(c)
        if (!policy.catalogReviewsEnabled || !actor.canReview)
            fail(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
        val proof = SupabaseStaffRecipeQualificationStore.check(c, environment, actor.actorId,
            policy.policyVersion, reviewedAt, attestation)
        proof.revalidate(c)
        actor.requireValidUntil(proof.validUntil)
    }

    internal fun lockRecipePublication(c: Connection, actor: SupabaseStaffCatalogActor,
        reviewerId: UUID, reviewedAt: Instant, professionalReview: JsonObject?, recall: Boolean): SupabaseStaffRecipePublicationProof =
        SupabaseStaffRecipePublicationAccess(environment, provider, policy).lock(c, actor, reviewerId, reviewedAt, professionalReview, recall)

    fun session(subject: VerifiedSupabaseSubject): JsonObject = safe {
        transactions.run { c ->
            val observed = observe(c, subject)
            buildJsonObject {
                put("principalId", observed.actorId.toString())
                put("sessionId", subject.providerSessionId.toString())
                put("revision", observed.revision)
                put("roles", JsonArray(buildList {
                    if (observed.canReview) add(JsonPrimitive("catalogReviewer"))
                    if (observed.canPublish) add(JsonPrimitive("catalogPublisher"))
                    if (observed.canModerate) add(JsonPrimitive("moderator"))
                }))
                put("screens", JsonArray(buildList {
                    add(JsonPrimitive("ADMIN_HOME"))
                    if (policy.catalogDraftsEnabled && (observed.canPublish || observed.canReview)) add(JsonPrimitive("ADMIN_RECIPE"))
                    if (policy.catalogReviewsEnabled && (observed.canPublish || observed.canReview)) {
                        add(JsonPrimitive("ADMIN_REVIEW"))
                        add(JsonPrimitive("ADMIN_SUBSTITUTION"))
                    }
                    if (policy.moderationEnabled && observed.canModerate) {
                        add(JsonPrimitive("ADMIN_REPORTS")); add(JsonPrimitive("ADMIN_CASE")); add(JsonPrimitive("ADMIN_AUDIT"))
                    }
                }))
                put("serverTime", observed.checkedAt.toString())
                put("validUntil", observed.validUntil.toString())
            }.also { check(it.toString().encodeToByteArray().size <= policy.maxResponseBytes) }
        }
    }

    /** Only this actual transaction may consume the actor. No browser HOME revision,
     * role flag or arbitrary staff ID can supply this capability. Registry locking is
     * through a read-only definer helper, never a grant to edit policy/enrollment. */
    internal fun <T> withCatalogAccess(subject: VerifiedSupabaseSubject, write: Boolean, requireReview: Boolean = false,
        action: (Connection, SupabaseStaffCatalogActor) -> T): T {
        if (!policy.catalogDraftsEnabled) fail(SupabaseStaffFailureCode.STAFF_NOT_CONFIGURED)
        if (requireReview && !policy.catalogReviewsEnabled) fail(SupabaseStaffFailureCode.STAFF_NOT_CONFIGURED)
        try {
            return transactions.run { c ->
                SupabaseStaffRecipeServingCompatibility.check(c)
                val before = observe(c, subject, lockCatalog = true)
                if ((!before.canPublish && !before.canReview) || (write && !before.canPublish) || (requireReview && !before.canReview))
                    fail(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
                val actor = SupabaseStaffCatalogActor(environment, before.actorId, before.validUntil, before.checkedAt,
                    before.canPublish, before.canReview, c)
                val result = action(c, actor)
                val after = observe(c, subject, lockCatalog = true)
                if (after.revision != before.revision || after.checkedAt < before.checkedAt ||
                    after.checkedAt >= before.validUntil || after.actorId != before.actorId ||
                    (!after.canPublish && !after.canReview) || (write && !after.canPublish) || (requireReview && !after.canReview))
                    fail(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
                actor.checkAt(after.checkedAt)
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Staff draft interrupted")
                result
            }
        } catch (e: SupabaseStaffRecipeFailure) { throw e }
          catch (e: com.feedme.server.db.CommitOutcomeUnknown) { throw e }
          catch (e: CancellationException) { throw e }
          catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
          // The enclosing draft store owns redaction/classification of domain SQL
          // failures. Do not mislabel a failed revision write as failed staff login.
          catch (e: java.sql.SQLException) { throw e }
          catch (e: SupabaseStaffFailure) { throw e }
          catch (e: AccountFailure) { fail(if (e.code == AccountFailureCode.UNAUTHENTICATED)
              SupabaseStaffFailureCode.STAFF_UNAUTHENTICATED else SupabaseStaffFailureCode.STAFF_NOT_CONFIGURED) }
          catch (_: Exception) { fail(SupabaseStaffFailureCode.STAFF_UNAVAILABLE) }
    }

    /** Separate moderation enrollment, not a recipe role or a browser HOME capability.
     * All domain work and fresh final authorization share this exact transaction.
     * Domain failures are preserved for the moderation store's own redacted mapping. */
    internal fun <T> withModerationAccess(subject: VerifiedSupabaseSubject,
        action: (Connection, SupabaseStaffModeratorActor) -> T): T {
        if (!policy.moderationEnabled) fail(SupabaseStaffFailureCode.STAFF_NOT_CONFIGURED)
        try {
            return transactions.run { c ->
                val before = observe(c, subject, lockModeration = true)
                if (!before.canModerate) fail(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
                val actor = SupabaseStaffModeratorActor(environment, before.actorId, subject.providerSessionId,
                    before.revision, before.checkedAt, before.validUntil, c)
                val result = action(c, actor)
                actor.requireConnection(c)
                val after = observe(c, subject, lockModeration = true)
                if (!after.canModerate || after.actorId != before.actorId || after.revision != before.revision ||
                    after.checkedAt < before.checkedAt || after.checkedAt >= before.validUntil)
                    fail(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
                actor.checkAt(after.checkedAt)
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Staff moderation interrupted")
                result
            }
        } catch (e: AccountFailure) {
            fail(if (e.code == AccountFailureCode.UNAUTHENTICATED) SupabaseStaffFailureCode.STAFF_UNAUTHENTICATED
                else SupabaseStaffFailureCode.STAFF_NOT_CONFIGURED)
        }
    }

    private fun observe(c: Connection, subject: VerifiedSupabaseSubject, lockCatalog: Boolean = false,
        lockModeration: Boolean = false): Observation {
            SupabaseStaffServingCompatibility.check(c)
            check(!lockCatalog || !lockModeration)
            if (policy.moderationEnabled) SupabaseStaffModerationServingCompatibility.check(c)
            else if (lockModeration) fail(SupabaseStaffFailureCode.STAFF_NOT_CONFIGURED)
            if (subject.assuranceLevel != "aal2") fail(SupabaseStaffFailureCode.STAFF_MFA_REQUIRED)
            val signed = subject.authenticationMethods ?: fail(SupabaseStaffFailureCode.STAFF_MFA_REQUIRED)
            if (signed.map { it.method }.distinct().size != signed.size) fail(SupabaseStaffFailureCode.STAFF_MFA_REQUIRED)
            val signedTotp = signed.singleOrNull { it.method == "totp" }
                ?: fail(SupabaseStaffFailureCode.STAFF_MFA_REQUIRED)
            if (signedTotp.atEpochSeconds > subject.issuedAtEpochSeconds) fail(SupabaseStaffFailureCode.STAFF_MFA_REQUIRED)

            // Existing trusted projections lock user -> every factor -> exact session ->
            // AMR. They recheck the reviewed Auth schema, issuer, AAL and session policy.
            val providerUntil = provider.lockCurrentValidUntil(c, subject)
            val factorId = exactCurrentTotp(c, subject)
            val totpAt = c.prepareStatement("SELECT authentication_method,updated_at FROM feedme_auth_access.amr_facts(?) " +
                "WHERE authentication_method='totp'").use { s ->
                s.setObject(1, subject.providerSessionId); s.executeQuery().use { r ->
                    if (!r.next()) fail(SupabaseStaffFailureCode.STAFF_MFA_REQUIRED)
                    instant(r, 2).also { if (r.next()) fail(SupabaseStaffFailureCode.STAFF_MFA_REQUIRED) }
                }
            }
            // Upstream JWT AMR timestamps are the actual database UpdatedAt.Unix().
            // A recently refreshed token, an unrelated factor or a caller MFA flag is
            // not proof. A stale JWT after a newer challenge is refused as well.
            if (totpAt.epochSecond != signedTotp.atEpochSeconds) fail(SupabaseStaffFailureCode.STAFF_MFA_REQUIRED)
            val observedAt = time(c)
            val mfaUntil = Instant.ofEpochSecond(signedTotp.atEpochSeconds).plusSeconds(policy.maximumTotpAgeSeconds)
            if (totpAt > observedAt || observedAt >= mfaUntil) fail(SupabaseStaffFailureCode.STAFF_MFA_REQUIRED)

            // The HOME projection needs SELECT only. Draft access additionally locks
            // these exact roots through the non-mutating helper before observing them;
            // no enrollment/policy UPDATE grant is needed by the serving account.
            if (lockCatalog) {
                // The helper returns only whether exact registry rows were locked. It
                // does not establish their eligibility or replace the checks below.
                c.prepareStatement("SELECT staff.lock_recipe_draft_actor(?,?,?)").use { s ->
                    s.setString(1, environment); s.setString(2, subject.issuer); s.setString(3, subject.subject.toString())
                    s.executeQuery().use { r -> if (!r.next() || !r.getBoolean(1) || r.next())
                        fail(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED) }
                }
            }
            if (lockModeration) c.prepareStatement("SELECT staff.lock_moderation_actor(?,?,?)").use { s ->
                s.setString(1, environment); s.setString(2, subject.issuer); s.setString(3, subject.subject.toString())
                s.executeQuery().use { r -> if (!r.next() || !r.getBoolean(1) || r.next())
                    fail(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED) }
            }
            val enrollment = enrollment(c, subject)
            if (enrollment.version != policy.policyVersion || enrollment.clientId != policy.policyClientId ||
                enrollment.issuer != provider.deployment.verification.issuer || enrollment.issuer != subject.issuer ||
                enrollment.audience != "authenticated" || enrollment.audience != provider.deployment.verification.audience ||
                !enrollment.policyEnabled || !enrollment.actorEnabled ||
                observedAt < enrollment.policyNotBefore || observedAt < enrollment.actorNotBefore ||
                Instant.ofEpochSecond(subject.issuedAtEpochSeconds) < enrollment.tokenValidAfter || totpAt < enrollment.tokenValidAfter) {
                fail(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
            }
            val moderation = if (policy.moderationEnabled) moderationEnrollment(c, enrollment.actorId) else null
            val canModerate = moderation != null && moderation.version > 0 && moderation.enabled &&
                moderation.policyVersion == policy.policyVersion && moderation.notBefore <= observedAt &&
                moderation.notBefore < moderation.validUntil && observedAt < moderation.validUntil
            if (!enrollment.canPublish && !enrollment.canReview && !canModerate)
                fail(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
            var until = minOf(providerUntil, mfaUntil, enrollment.policyUntil, enrollment.actorUntil,
                observedAt.plusSeconds(policy.observationSeconds))
            if (canModerate) until = minOf(until, requireNotNull(moderation).validUntil)
            val revision = revision(subject, factorId, totpAt, enrollment, moderation)
            val serverTime = time(c)
            if (serverTime < observedAt || serverTime >= until) fail(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Staff admission interrupted")
            return Observation(enrollment.actorId, revision, enrollment.canPublish, enrollment.canReview, canModerate, serverTime, until)
    }

    private class Observation(val actorId: UUID, val revision: String, val canPublish: Boolean,
        val canReview: Boolean, val canModerate: Boolean, val checkedAt: Instant, val validUntil: Instant)

    private fun exactCurrentTotp(c: Connection, subject: VerifiedSupabaseSubject): UUID = c.prepareStatement(
        "SELECT s.factor_id,f.user_id,f.status::text,f.factor_type::text FROM ONLY auth.sessions s " +
            "JOIN ONLY auth.mfa_factors f ON f.id=s.factor_id " +
            "WHERE s.id=? AND s.user_id=? LIMIT 2").use { s ->
        s.setObject(1, subject.providerSessionId); s.setObject(2, subject.subject)
        s.executeQuery().use { r ->
            if (!r.next() || r.getObject(2, UUID::class.java) != subject.subject || r.getString(3) != "verified" ||
                r.getString(4) != "totp") fail(SupabaseStaffFailureCode.STAFF_MFA_REQUIRED)
            r.getObject(1, UUID::class.java).also { if (it == null || r.next()) fail(SupabaseStaffFailureCode.STAFF_MFA_REQUIRED) }
        }
    }

    private fun enrollment(c: Connection, subject: VerifiedSupabaseSubject): Enrollment = c.prepareStatement(
        "SELECT p.version,p.issuer,p.client_id,p.audience,p.enabled,p.not_before,p.valid_until," +
            "a.actor_id,a.can_publish,a.can_review,a.enabled,a.token_valid_after,a.not_before,a.valid_until " +
            "FROM ONLY staff.publication_policies p JOIN ONLY staff.actors a ON a.environment=p.environment " +
            "WHERE p.environment=? AND a.issuer=? AND a.subject=? LIMIT 2").use { s ->
        s.setString(1, environment); s.setString(2, subject.issuer); s.setString(3, subject.subject.toString())
        s.executeQuery().use { r ->
            if (!r.next()) fail(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
            Enrollment(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getBoolean(5), instant(r, 6), instant(r, 7),
                r.getObject(8, UUID::class.java), r.getBoolean(9), r.getBoolean(10), r.getBoolean(11), instant(r, 12), instant(r, 13), instant(r, 14))
                .also { if (r.next()) fail(SupabaseStaffFailureCode.STAFF_NOT_CONFIGURED) }
        }
    }

    private fun moderationEnrollment(c: Connection, actorId: UUID): ModerationEnrollment? = c.prepareStatement(
        "SELECT version,policy_version,enabled,not_before,valid_until FROM ONLY staff.moderator_enrollments " +
            "WHERE environment=? AND actor_id=? LIMIT 2").use { s ->
        s.setString(1, environment); s.setObject(2, actorId)
        s.executeQuery().use { r ->
            if (!r.next()) null else ModerationEnrollment(r.getLong(1), r.getString(2), r.getBoolean(3), instant(r, 4), instant(r, 5))
                .also { if (r.next()) fail(SupabaseStaffFailureCode.STAFF_NOT_CONFIGURED) }
        }
    }
    private class ModerationEnrollment(val version: Long, val policyVersion: String, val enabled: Boolean,
        val notBefore: Instant, val validUntil: Instant)

    // V040 has no integer revision. Hash the exact admitted registry/provider evidence,
    // not a fabricated sequence, JWT role grant, token secret or browser-supplied version.
    private fun revision(s: VerifiedSupabaseSubject, factorId: UUID, totpAt: Instant, e: Enrollment,
        moderation: ModerationEnrollment?): String {
        val values = listOf("feedme-supabase-staff-home-v1", environment, policy.policyVersion, policy.policyClientId,
            policy.maximumTotpAgeSeconds.toString(), policy.observationSeconds.toString(), s.issuer, s.subject.toString(),
            s.providerSessionId.toString(), s.issuedAtEpochSeconds.toString(), s.expiresAtEpochSeconds.toString(), factorId.toString(), totpAt.toString(),
            provider.deployment.authSourceRevision, provider.deployment.reviewedAt.toString(), provider.deployment.validUntil.toString(),
            e.version, e.issuer, e.clientId, e.audience, e.policyEnabled.toString(), e.policyNotBefore.toString(), e.policyUntil.toString(),
            e.actorId.toString(), e.canPublish.toString(), e.canReview.toString(), e.actorEnabled.toString(), e.tokenValidAfter.toString(),
            e.actorNotBefore.toString(), e.actorUntil.toString()) + if (policy.moderationEnabled) listOf(
                "feedme-moderation-enrollment-v1", moderation?.version?.toString() ?: "absent",
                moderation?.policyVersion ?: "absent", moderation?.enabled?.toString() ?: "absent",
                moderation?.notBefore?.toString() ?: "absent", moderation?.validUntil?.toString() ?: "absent") else emptyList()
        val evidence = JsonArray(values.map(::JsonPrimitive)).toString().encodeToByteArray()
        return try { MessageDigest.getInstance("SHA-256").digest(evidence).joinToString("") { "%02x".format(it.toInt() and 255) } }
        finally { evidence.fill(0) }
    }

    private class Enrollment(val version: String, val issuer: String, val clientId: String, val audience: String,
        val policyEnabled: Boolean, val policyNotBefore: Instant, val policyUntil: Instant, val actorId: UUID,
        val canPublish: Boolean, val canReview: Boolean, val actorEnabled: Boolean, val tokenValidAfter: Instant,
        val actorNotBefore: Instant, val actorUntil: Instant)

    private fun instant(r: ResultSet, column: Int): Instant = r.getObject(column, OffsetDateTime::class.java)?.toInstant()
        ?: fail(SupabaseStaffFailureCode.STAFF_UNAVAILABLE)
    private fun time(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
        if (!r.next()) fail(SupabaseStaffFailureCode.STAFF_UNAVAILABLE)
        instant(r, 1).also { if (r.next()) fail(SupabaseStaffFailureCode.STAFF_UNAVAILABLE) }
    } }
    private fun fail(code: SupabaseStaffFailureCode): Nothing = throw SupabaseStaffFailure(code)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (e: CancellationException) { throw e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (e: SupabaseStaffFailure) { throw e }
        catch (e: AccountFailure) { fail(if (e.code == AccountFailureCode.UNAUTHENTICATED) SupabaseStaffFailureCode.STAFF_UNAUTHENTICATED else SupabaseStaffFailureCode.STAFF_NOT_CONFIGURED) }
        catch (_: Exception) { fail(SupabaseStaffFailureCode.STAFF_UNAVAILABLE) }

    override fun toString() = "SupabaseStaffAdmissionStore(<redacted>)"
}

/** Created only from the independently checked staff registry inside its owned transaction. */
internal class SupabaseStaffCatalogActor internal constructor(val environment: String, val actorId: UUID,
    val validUntil: Instant, val checkedAt: Instant, val canPublish: Boolean, val canReview: Boolean,
    private val connection: Connection) {
    private val thread = Thread.currentThread()
    private val transactionId = connection.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { r ->
        check(r.next()); r.getLong(1).also { check(!r.next()) }
    } }
    private var requiredUntil = validUntil
    internal fun requireConnection(c: Connection) {
        check(c === connection && Thread.currentThread() === thread && !c.autoCommit &&
            c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        c.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { r ->
            check(r.next() && r.getLong(1) == transactionId && !r.next())
        } }
    }
    /** Domain deadlines may only narrow this transaction's final accepted clock. */
    internal fun requireValidUntil(deadline: Instant) { requiredUntil = minOf(requiredUntil, deadline) }
    internal fun checkAt(at: Instant) {
        if (at < checkedAt || at >= requiredUntil)
            throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.STORAGE_UNAVAILABLE)
    }
    override fun toString() = "SupabaseStaffCatalogActor(<redacted>)"
}

/** Actual moderator for one transaction only. No recipe capability is exposed. */
internal class SupabaseStaffModeratorActor internal constructor(val environment: String, val actorId: UUID,
    val providerSessionId: UUID, val authorityRevision: String, val checkedAt: Instant, val validUntil: Instant,
    private val connection: Connection) {
    private val thread = Thread.currentThread()
    private val transactionId = connection.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { r ->
        check(r.next()); r.getLong(1).also { check(!r.next()) }
    } }
    private var requiredUntil = validUntil
    internal fun requireConnection(c: Connection) {
        check(c === connection && Thread.currentThread() === thread && !c.isClosed && !c.autoCommit &&
            c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        c.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { r ->
            check(r.next() && r.getLong(1) == transactionId && !r.next())
        } }
    }
    internal fun requireValidUntil(deadline: Instant) { requiredUntil = minOf(requiredUntil, deadline) }
    internal fun checkAt(at: Instant) {
        requireConnection(connection)
        if (at < checkedAt || at >= requiredUntil)
            throw SupabaseStaffFailure(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
    }
    override fun toString() = "SupabaseStaffModeratorActor(<redacted>)"
}
