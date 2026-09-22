package com.feedme.server.staff

import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.PgTransactions
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal enum class StaffPublicationFailureCode { NOT_CONFIGURED, AUTHORITY_DENIED, ORIGINAL_MISMATCH, EXPIRED, STORAGE_UNAVAILABLE }
internal class StaffPublicationFailure(val code: StaffPublicationFailureCode) : RuntimeException("Staff publication unavailable: ${code.name}")
internal class StaffApprovalReceipt(val approvalId: UUID, val requestSha256: String, val replayed: Boolean) {
    override fun toString() = "StaffApprovalReceipt(<redacted>)"
}
internal class StaffApprovalRevocationReceipt(val approvalId: UUID, val revocationId: UUID, val replayed: Boolean) {
    override fun toString() = "StaffApprovalRevocationReceipt(<redacted>)"
}

/** Only verified workforce subjects enter; JWT roles, supplied actor IDs and hashes are not
 * authority. Owner-managed enrollment/policy have no accepting defaults. Lock order is policy,
 * actors in UUID order, then approval advisory/row locks; callers take catalog locks afterwards.
 * Approval expiry may outlive the review access token, but changing either actor's enrollment,
 * current policy, reviewer token-valid-after or the append-only revocation can deny publication.
 * No callback performs network I/O or commits. An approval is never a catalog publication.
 */
internal class StaffPublicationRegistry(private val environment: String, private val transactions: PgTransactions) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun approve(subject: VerifiedWorkforceSubject, approvalId: UUID, intent: StaffPublicationIntent,
        policyVersion: String, expiresAt: Instant, reason: String, rightsAttestationReference: String): StaffApprovalReceipt = safe {
        reference(policyVersion, 128); reference(reason, 4000); reference(rightsAttestationReference, 256)
        require(expiresAt.nano % 1000 == 0) // PostgreSQL timestamps retain microseconds, never round an original.
        transactions.run { c ->
            checkCompatibility(c)
            val policy = policy(c)
            val reviewer = actorId(c, subject)
            if (reviewer == intent.publisherId || intent.reviewerId?.let { it != reviewer } == true) denied()
            val actors = actors(c, intent.publisherId, reviewer)
            val publisher = actors.getValue(intent.publisherId); val reviewActor = actors.getValue(reviewer)
            validatePair(policy, publisher, reviewActor, now(c))
            validateSubject(subject, policy, reviewActor, now(c), review = true)
            if (policy.version != policyVersion || intent.publicationPolicyVersion?.let { it != policy.version } == true) denied()
            lock(c, approvalId, exclusive = true)
            val before = approval(c, approvalId)
            if (before != null && !before.sameRequest(intent, reviewer, policyVersion, expiresAt, reason, rightsAttestationReference)) mismatch()
            if (revoked(c, approvalId)) denied()
            if (before == null) {
                val at = now(c)
                validatePair(policy, publisher, reviewActor, at); validateSubject(subject, policy, reviewActor, at, review = true)
                if (expiresAt <= at || expiresAt > policy.until || expiresAt > publisher.until || expiresAt > reviewActor.until) expired()
                c.prepareStatement("INSERT INTO staff.publication_approvals(environment,approval_id,kind,exact_document,request_sha256," +
                    "publisher_id,reviewer_id,policy_version,issuer,client_id,audience,publisher_subject,reviewer_subject," +
                    "reviewer_issued_at,reviewer_authenticated_at,reviewed_at,expires_at,reason,rights_attestation_reference) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
                    s.setString(1, environment); s.setObject(2, approvalId); s.setString(3, intent.kind.wire)
                    s.setString(4, intent.exactDocument); s.setString(5, intent.requestSha256); s.setObject(6, publisher.id)
                    s.setObject(7, reviewer); s.setString(8, policyVersion); s.setString(9, policy.issuer)
                    s.setString(10, policy.client); s.setString(11, policy.audience); s.setString(12, publisher.subject)
                    s.setString(13, reviewActor.subject); s.setObject(14, date(subject.issuedAt)); s.setObject(15, date(subject.authenticatedAt))
                    s.setObject(16, date(at)); s.setObject(17, date(expiresAt)); s.setString(18, reason)
                    s.setString(19, rightsAttestationReference); check(s.executeUpdate() == 1)
                }
            }
            val retained = approval(c, approvalId) ?: unavailable()
            if (!retained.sameRequest(intent, reviewer, policyVersion, expiresAt, reason, rightsAttestationReference)) unavailable()
            val finalPolicy = policy(c); val finalActors = actors(c, publisher.id, reviewer); val at = now(c)
            validateApproval(retained, intent, finalPolicy, finalActors, at)
            validateSubject(subject, finalPolicy, finalActors.getValue(reviewer), at, review = true)
            if (revoked(c, approvalId)) denied()
            StaffApprovalReceipt(approvalId, retained.hash, before != null)
        }
    }

    fun lockApproved(c: Connection, selectedEnvironment: String, subject: VerifiedWorkforceSubject,
        approvalId: UUID, intent: StaffPublicationIntent): Unit = safe {
        if (selectedEnvironment != environment) denied()
        checkCompatibility(c)
        val policy = policy(c)
        if (actorId(c, subject) != intent.publisherId) denied()
        // Immutable lookup supplies only the second lock key, never authority by itself.
        val hint = approval(c, approvalId) ?: denied()
        if (hint.publisher != intent.publisherId || intent.reviewerId?.let { it != hint.reviewer } == true) denied()
        val actors = actors(c, hint.publisher, hint.reviewer)
        lock(c, approvalId, exclusive = false)
        val actual = approval(c, approvalId) ?: denied()
        val at = now(c)
        validateApproval(actual, intent, policy, actors, at)
        validateSubject(subject, policy, actors.getValue(actual.publisher), at, review = false)
        if (revoked(c, approvalId)) denied()
        // This last wall-clock check is after every potentially blocking registry read.
        val finalAt = now(c)
        validateApproval(actual, intent, policy, actors, finalAt)
        validateSubject(subject, policy, actors.getValue(actual.publisher), finalAt, review = false)
    }

    fun revalidateApproved(c: Connection, selectedEnvironment: String, subject: VerifiedWorkforceSubject,
        approvalId: UUID, intent: StaffPublicationIntent) = lockApproved(c, selectedEnvironment, subject, approvalId, intent)

    /** Revokes this review approval only; it does NOT recall a recipe, unpublish content or
     * revoke an already issued Saved-copy grant. Those need their own exact domain commands. */
    fun revoke(subject: VerifiedWorkforceSubject, approvalId: UUID, revocationId: UUID,
        reason: String): StaffApprovalRevocationReceipt = safe {
        reference(reason, 4000)
        transactions.run { c ->
            checkCompatibility(c)
            val policy = policy(c); val caller = actorId(c, subject)
            val original = approval(c, approvalId) ?: denied()
            if (caller != original.publisher && caller != original.reviewer) denied()
            val actors = actors(c, original.publisher, original.reviewer)
            validateSubject(subject, policy, actors.getValue(caller), now(c), review = caller == original.reviewer)
            lock(c, approvalId, exclusive = true)
            val before = revocation(c, approvalId)
            if (before != null && before != Revocation(revocationId, caller, reason)) mismatch()
            if (before == null) c.prepareStatement("INSERT INTO staff.approval_revocations(environment,approval_id,revocation_id,actor_id,reason) VALUES(?,?,?,?,?)").use {
                it.setString(1, environment); it.setObject(2, approvalId); it.setObject(3, revocationId)
                it.setObject(4, caller); it.setString(5, reason); check(it.executeUpdate() == 1)
            }
            if (revocation(c, approvalId) != Revocation(revocationId, caller, reason)) unavailable()
            val finalPolicy = policy(c); val finalActors = actors(c, original.publisher, original.reviewer)
            validateSubject(subject, finalPolicy, finalActors.getValue(caller), now(c), review = caller == original.reviewer)
            StaffApprovalRevocationReceipt(approvalId, revocationId, before != null)
        }
    }

    internal fun checkCompatibility(c: Connection) {
        transaction(c)
        val expected = StaffPublicationRegistry::class.java.getResourceAsStream("/db/migration/V040__staff_publication_approvals.sql")
            ?.use { sha(it.readBytes()) } ?: fail(StaffPublicationFailureCode.NOT_CONFIGURED)
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=40").use {
            it.executeQuery().use { r -> if (!r.next() || r.getString(1) != expected || r.next()) fail(StaffPublicationFailureCode.NOT_CONFIGURED) }
        }
    }

    private fun policy(c: Connection): Policy = c.prepareStatement("SELECT * FROM staff.publication_policies WHERE environment=? FOR SHARE").use {
        it.setString(1, environment); it.executeQuery().use { r ->
            if (!r.next()) fail(StaffPublicationFailureCode.NOT_CONFIGURED)
            Policy(r.getString("version"), r.getString("issuer"), r.getString("client_id"), r.getString("audience"),
                r.getBoolean("enabled"), r.instant("not_before"), r.instant("valid_until")).also { if (r.next()) unavailable() }
        }
    }
    private fun actorId(c: Connection, subject: VerifiedWorkforceSubject): UUID = c.prepareStatement(
        "SELECT actor_id FROM staff.actors WHERE environment=? AND issuer=? AND subject=?").use {
        it.setString(1, environment); it.setString(2, subject.issuer); it.setString(3, subject.subject)
        it.executeQuery().use { r -> if (!r.next()) denied(); r.getObject(1, UUID::class.java).also { if (r.next()) unavailable() } }
    }
    private fun actors(c: Connection, publisher: UUID, reviewer: UUID): Map<UUID, Actor> {
        if (publisher == reviewer) denied()
        return c.prepareStatement("SELECT * FROM staff.actors WHERE environment=? AND actor_id IN (?,?) ORDER BY actor_id FOR SHARE").use {
            it.setString(1, environment); it.setObject(2, publisher); it.setObject(3, reviewer)
            it.executeQuery().use { r -> buildMap {
                while (r.next()) {
                    val id = r.getObject("actor_id", UUID::class.java)
                    put(id, Actor(id, r.getString("issuer"), r.getString("subject"), r.getBoolean("enabled"),
                        r.getBoolean("can_publish"), r.getBoolean("can_review"), r.instant("token_valid_after"),
                        r.instant("not_before"), r.instant("valid_until")))
                }
                if (size != 2) denied()
            } }
        }
    }
    private fun approval(c: Connection, id: UUID): Approval? = c.prepareStatement(
        "SELECT * FROM staff.publication_approvals WHERE environment=? AND approval_id=?").use {
        it.setString(1, environment); it.setObject(2, id); it.executeQuery().use { r -> if (!r.next()) null else {
            Approval(r.getString("kind"), r.getString("exact_document"), r.getString("request_sha256"),
                r.getObject("publisher_id", UUID::class.java), r.getObject("reviewer_id", UUID::class.java),
                r.getString("policy_version"), r.getString("issuer"), r.getString("client_id"), r.getString("audience"),
                r.getString("publisher_subject"), r.getString("reviewer_subject"), r.instant("reviewer_issued_at"),
                r.instant("reviewer_authenticated_at"), r.instant("reviewed_at"), r.instant("expires_at"),
                r.getString("reason"), r.getString("rights_attestation_reference")).also { if (r.next()) unavailable() }
        } }
    }
    private fun validateApproval(a: Approval, intent: StaffPublicationIntent, p: Policy, actors: Map<UUID, Actor>, at: Instant) {
        if (a.kind != intent.kind.wire || a.document != intent.exactDocument || a.hash != intent.requestSha256 ||
            sha(a.document.encodeToByteArray(throwOnInvalidSequence = true)) != a.hash || a.publisher != intent.publisherId ||
            intent.reviewerId?.let { it != a.reviewer } == true) mismatch()
        val publisher = actors[a.publisher] ?: denied(); val reviewer = actors[a.reviewer] ?: denied()
        validatePair(p, publisher, reviewer, at)
        if (a.policy != p.version || intent.publicationPolicyVersion?.let { it != p.version } == true ||
            a.issuer != p.issuer || a.client != p.client || a.audience != p.audience ||
            a.publisherSubject != publisher.subject || a.reviewerSubject != reviewer.subject ||
            a.reviewIssued < reviewer.tokenAfter || a.reviewAuthenticated < reviewer.tokenAfter) denied()
        if (a.reviewAuthenticated > a.reviewIssued || a.reviewIssued > a.reviewed || a.reviewed > at ||
            a.expires <= at || a.expires > p.until || a.expires > publisher.until || a.expires > reviewer.until) expired()
    }
    private fun validatePair(p: Policy, publisher: Actor, reviewer: Actor, at: Instant) {
        validPolicy(p, at)
        if (publisher.id == reviewer.id || publisher.subject == reviewer.subject || publisher.issuer != p.issuer || reviewer.issuer != p.issuer) denied()
        validActor(publisher, at, review = false); validActor(reviewer, at, review = true)
    }
    private fun validateSubject(s: VerifiedWorkforceSubject, p: Policy, actor: Actor, at: Instant, review: Boolean) {
        validPolicy(p, at); validActor(actor, at, review)
        if (s.issuer != p.issuer || s.clientId != p.client || s.audience != p.audience ||
            actor.issuer != s.issuer || actor.subject != s.subject || s.issuedAt < actor.tokenAfter || s.authenticatedAt < actor.tokenAfter) denied()
        if (s.authenticatedAt > s.issuedAt || s.issuedAt > at || s.validUntil > s.expiresAt ||
            s.validUntil <= at || s.expiresAt <= at) expired()
    }
    private fun validPolicy(p: Policy, at: Instant) {
        if (!p.enabled) denied()
        if (at < p.from || at >= p.until) expired()
    }
    private fun validActor(a: Actor, at: Instant, review: Boolean) {
        if (!a.enabled || !(if (review) a.review else a.publish)) denied()
        if (at < a.from || at >= a.until) expired()
    }
    private fun lock(c: Connection, id: UUID, exclusive: Boolean) {
        val key = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest("feedme-staff-approval:$environment:$id".toByteArray())).long
        c.prepareStatement("SELECT pg_advisory_xact_lock${if (exclusive) "" else "_shared"}(?)").use {
            it.setLong(1, key); it.executeQuery().close()
        }
    }
    private fun revoked(c: Connection, id: UUID) = revocation(c, id) != null
    private fun revocation(c: Connection, id: UUID): Revocation? = c.prepareStatement(
        "SELECT revocation_id,actor_id,reason FROM staff.approval_revocations WHERE environment=? AND approval_id=?").use {
        it.setString(1, environment); it.setObject(2, id); it.executeQuery().use { r -> if (!r.next()) null else
            Revocation(r.getObject(1, UUID::class.java), r.getObject(2, UUID::class.java), r.getString(3)).also { if (r.next()) unavailable() } }
    }
    private fun transaction(c: Connection) {
        check(!c.isClosed && !c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        c.createStatement().use { it.executeQuery("SELECT current_setting('session_replication_role')").use { r ->
            if (!r.next() || r.getString(1) != "origin" || r.next()) unavailable()
        } }
    }
    private fun now(c: Connection): Instant = c.createStatement().use { it.executeQuery("SELECT clock_timestamp()").use { r ->
        check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant()
    } }
    private fun reference(value: String, max: Int) { require(value.isNotBlank() && value.length <= max && value.none(Char::isISOControl)); value.encodeToByteArray(throwOnInvalidSequence = true) }
    private fun ResultSet.instant(name: String) = getObject(name, OffsetDateTime::class.java).toInstant()
    private fun date(at: Instant) = OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun denied(): Nothing = fail(StaffPublicationFailureCode.AUTHORITY_DENIED)
    private fun expired(): Nothing = fail(StaffPublicationFailureCode.EXPIRED)
    private fun mismatch(): Nothing = fail(StaffPublicationFailureCode.ORIGINAL_MISMATCH)
    private fun unavailable(): Nothing = fail(StaffPublicationFailureCode.STORAGE_UNAVAILABLE)
    private fun fail(code: StaffPublicationFailureCode): Nothing = throw StaffPublicationFailure(code)
    private inline fun <T> safe(action: () -> T): T = try { action() }
        catch (f: StaffPublicationFailure) { throw f }
        catch (f: CommitOutcomeUnknown) { throw f }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (f: SQLException) { if (f.sqlState in setOf("40001", "40P01")) throw f; unavailable() }
        catch (_: Exception) { unavailable() }
    override fun toString() = "StaffPublicationRegistry(<redacted>)"

    private class Policy(val version: String, val issuer: String, val client: String, val audience: String,
        val enabled: Boolean, val from: Instant, val until: Instant)
    private class Actor(val id: UUID, val issuer: String, val subject: String, val enabled: Boolean,
        val publish: Boolean, val review: Boolean, val tokenAfter: Instant, val from: Instant, val until: Instant)
    private class Approval(val kind: String, val document: String, val hash: String, val publisher: UUID,
        val reviewer: UUID, val policy: String, val issuer: String, val client: String, val audience: String,
        val publisherSubject: String, val reviewerSubject: String, val reviewIssued: Instant,
        val reviewAuthenticated: Instant, val reviewed: Instant, val expires: Instant, val reason: String, val rights: String) {
        fun sameRequest(i: StaffPublicationIntent, r: UUID, p: String, until: Instant, why: String, reference: String) =
            kind == i.kind.wire && document == i.exactDocument && hash == i.requestSha256 && publisher == i.publisherId &&
                reviewer == r && policy == p && expires == until && reason == why && rights == reference
    }
    private data class Revocation(val id: UUID, val actor: UUID, val reason: String) {
        override fun toString() = "StaffApprovalRevocation(<redacted>)"
    }
}
