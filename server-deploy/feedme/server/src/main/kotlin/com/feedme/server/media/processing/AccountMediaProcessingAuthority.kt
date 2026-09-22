package com.feedme.server.media.processing

import com.feedme.server.social.posts.PostPublicationLifecycle
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import com.feedme.server.identity.SupabasePostgresAuthority
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Worker settings are explicit, independent of an uploader's expired JWT/device. A worker
 * may finish an accepted upload after logout, but not after account/draft/policy revocation.
 * This is not a safety assessment, storage capability or deployment authorization. */
class AccountMediaProcessingPolicy(
    val eligibilityPolicyVersion: String,
    val requiredTermsVersion: String,
    val processingRevision: String,
    val safetyRevision: String,
    val enabled: Boolean,
    val lockTimeoutMillis: Int,
    val statementTimeoutMillis: Int,
) {
    init {
        require(listOf(eligibilityPolicyVersion, requiredTermsVersion).all {
            it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)
        })
        require(processingRevision.matches(REVISION) && safetyRevision.matches(REVISION))
        require(lockTimeoutMillis in 1..5_000 && statementTimeoutMillis in lockTimeoutMillis..30_000)
    }
    override fun toString() = "AccountMediaProcessingPolicy(<redacted>)"
}

/** Actual database-only account/draft authority for accepted media jobs. Account locks match
 * identity writers and account erasure. Cleanup-to-processing upgrades can already hold
 * owner roots before provider admission; NOWAIT and bounded lock/statement timeouts refuse
 * contention rather than assuming one global lock order or authorizing past a failed wait.
 * No provider identity, consent, generation or positive content verdict is manufactured.
 * PROCESS enforces current account/Terms/moderation; CLEANUP keeps the same exact ownership
 * locks but remains possible after account/draft revocation. */
class AccountMediaProcessingAuthority(
    private val environment: String,
    private val policy: AccountMediaProcessingPolicy,
    private val provider: SupabasePostgresAuthority,
) : MediaProcessingAuthority {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    override fun lockPrincipal(connection: Connection, owner: MediaProcessingOwner, purpose: MediaWorkerPurpose) {
        transaction(connection, owner)
        connection.prepareStatement("SELECT set_config('lock_timeout',?,true),set_config('statement_timeout',?,true)").use {
            it.setString(1, "${policy.lockTimeoutMillis}ms")
            it.setString(2, "${policy.statementTimeoutMillis}ms")
            it.executeQuery().use { rows -> if (!rows.next() || rows.next()) unavailable() }
        }
        // Resolve without locks, then acquire the actual provider user before account roots.
        // Recheck this mapping under the owner lock; the initial observation is not authority.
        val binding = if (purpose == MediaWorkerPurpose.PROCESS) {
            connection.prepareStatement("SELECT provider_issuer,provider_subject FROM identity.users WHERE environment=? AND id=?").use { statement ->
                statement.setString(1, environment); statement.setObject(2, owner.ownerId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) unavailable()
                    (rows.getString(1) to rows.getObject(2, UUID::class.java)).also { if (rows.next()) unavailable() }
                }
            }.also { (issuer, subject) ->
                try { provider.lockAcceptedWorkAccount(connection, issuer, subject) }
                catch (failure: AccountFailure) {
                    processingFail(if (failure.code == AccountFailureCode.NOT_CONFIGURED)
                        MediaProcessingFailureCode.NOT_CONFIGURED else MediaProcessingFailureCode.CONFLICT)
                }
            }
        } else null
        connection.prepareStatement("SELECT u.status,p.status,p.kind,u.eligibility_state,u.eligibility_policy_version,u.terms_version,u.provider_issuer,u.provider_subject " +
            "FROM identity.users u JOIN identity.principals p ON p.environment=u.environment AND p.user_id=u.id " +
            "WHERE u.environment=? AND u.id=? FOR UPDATE OF u,p NOWAIT").use { statement ->
            statement.setString(1, environment); statement.setObject(2, owner.ownerId)
            statement.executeQuery().use { rows ->
                if (!rows.next() || rows.getString(3) != "user") unavailable()
                if (purpose == MediaWorkerPurpose.PROCESS) {
                    if (!policy.enabled) processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
                    if (binding == null || rows.getString(7) != binding.first || rows.getObject(8, UUID::class.java) != binding.second) unavailable()
                    if (rows.getString(1) != "active" || rows.getString(2) != "active" ||
                        rows.getString(4) != "eligible" || rows.getString(5) != policy.eligibilityPolicyVersion ||
                        rows.getString(6) != policy.requiredTermsVersion) unavailable()
                }
                if (rows.next()) unavailable()
            }
        }
        if (purpose == MediaWorkerPurpose.PROCESS) {
            connection.prepareStatement("SELECT onboarding_step,display_name,normalized_handle FROM profile.profiles " +
                "WHERE environment=? AND user_id=? AND live FOR SHARE NOWAIT").use { statement ->
                statement.setString(1, environment); statement.setObject(2, owner.ownerId)
                statement.executeQuery().use { rows ->
                    if (!rows.next() || rows.getString(1) != "ready" || rows.getString(2).isNullOrBlank() ||
                        rows.getString(3).isNullOrBlank() || rows.next()) unavailable()
                }
            }
            // This same relation fence is used by publication and viewer checks. A report
            // alone is not a ban; only an actual restrictive moderation action is applied.
            connection.createStatement().use { it.execute("LOCK TABLE ONLY safety.moderation_cases IN SHARE MODE NOWAIT") }
            connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM safety.moderation_cases WHERE environment=? " +
                "AND target_type='user' AND target_id=? AND action IN ('hide','remove','suspend'))").use { statement ->
                statement.setString(1, environment); statement.setObject(2, owner.ownerId)
                statement.executeQuery().use { rows ->
                    if (!rows.next() || rows.getBoolean(1) || rows.next()) unavailable()
                }
            }
        }
        processingCurrent()
    }

    override fun lockDraft(connection: Connection, owner: MediaProcessingOwner, draftId: UUID,
        generation: Long, purpose: MediaWorkerPurpose) {
        lockPrincipal(connection, owner, purpose)
        if (generation <= 0) unavailable()
        connection.prepareStatement("SELECT revision FROM platform.post_draft_heads WHERE environment=? " +
            "AND owner_user_id=? FOR UPDATE NOWAIT").use { statement ->
            statement.setString(1, environment); statement.setObject(2, owner.ownerId)
            statement.executeQuery().use { rows -> if (rows.next() && (rows.getLong(1) <= 0 || rows.next())) unavailable() }
        }
        connection.prepareStatement("SELECT generation FROM platform.media_draft_lifecycles WHERE environment=? " +
            "AND owner_user_id=? AND client_draft_id=? FOR UPDATE NOWAIT").use { statement ->
            statement.setString(1, environment); statement.setObject(2, owner.ownerId); statement.setObject(3, draftId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) unavailable()
                val current = rows.getLong(1)
                // Old generation cleanup remains necessary after a draft is cancelled.
                if (current <= 0 || current < generation ||
                    (purpose == MediaWorkerPurpose.PROCESS && current != generation) || rows.next()) unavailable()
            }
        }
        connection.prepareStatement("SELECT draft_generation,status,published_post_id,expires_at>clock_timestamp() " +
            "FROM platform.post_drafts WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR SHARE NOWAIT").use { statement ->
            statement.setString(1, environment); statement.setObject(2, owner.ownerId); statement.setObject(3, draftId)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    if (purpose == MediaWorkerPurpose.PROCESS && (rows.getLong(1) != generation ||
                        rows.getString(2) != "draft" || rows.getObject(3) != null || !rows.getBoolean(4))) unavailable()
                    if (rows.next()) unavailable()
                }
            }
        }
        // Upload may precede server draft creation, but never cross a retained publication.
        if (purpose == MediaWorkerPurpose.PROCESS &&
            PostPublicationLifecycle.isPublished(connection, environment, owner.ownerId, draftId)) unavailable()
        processingCurrent()
    }

    override fun requireProcessing(connection: Connection, source: MediaProcessingSource, policyRevision: String) {
        if (policyRevision != policy.processingRevision) processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
        lockDraft(connection, source.owner, source.draftId, source.draftGeneration, MediaWorkerPurpose.PROCESS)
    }

    override fun requireSafety(connection: Connection, source: MediaProcessingSource, evidence: MediaSafetyEvidence,
        policyRevision: String, now: Instant) {
        requireProcessing(connection, source, policyRevision)
        val current = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT clock_timestamp()").use { rows ->
                if (!rows.next()) unavailable()
                rows.getObject(1, OffsetDateTime::class.java).toInstant().also { if (rows.next()) unavailable() }
            }
        }
        if (current < now || evidence.revision != policy.safetyRevision || evidence.sourceSha256 != source.sha256 ||
            evidence.assessedAt > now || evidence.validUntil <= current)
            processingFail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
        // The store binds both derivative digests and the retained safety receipt. These
        // fields do not replace the actual malware/content assessment supplied to the worker.
        processingCurrent()
    }

    private fun transaction(connection: Connection, owner: MediaProcessingOwner) {
        processingCurrent()
        if (owner.environment != environment || connection.isClosed || connection.autoCommit ||
            connection.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) unavailable()
    }
    private fun unavailable(): Nothing = processingFail(MediaProcessingFailureCode.CONFLICT)
    override fun toString() = "AccountMediaProcessingAuthority(<redacted>)"
}
