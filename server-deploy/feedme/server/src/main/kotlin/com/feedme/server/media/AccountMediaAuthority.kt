package com.feedme.server.media

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import com.feedme.server.identity.AccountProfileStore
import com.feedme.server.social.posts.PostPublicationLifecycle
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Explicit private-upload admission, not image safety, publication or storage readiness.
 * The byte budget counts ALL retained reservations, including tombstones: deleting a row's
 * public status is not evidence that remote source/derivative bytes have been erased. */
class AccountMediaAdmissionPolicy(val eligibilityPolicyVersion: String, val requiredTermsVersion: String,
    val uploadsEnabled: Boolean, val maximumReservationsPer24Hours: Int, val maximumOwnedSourceBytes: Long) {
    init {
        require(listOf(eligibilityPolicyVersion, requiredTermsVersion).all {
            it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)
        })
        require(maximumReservationsPer24Hours in 1..10_000)
        require(maximumOwnedSourceBytes in 1..1_000_000_000_000L)
    }
    override fun toString() = "AccountMediaAdmissionPolicy(<redacted>)"
}

/** Provider -> subject -> account/private principal -> device, shared with account deletion,
 * logout and social writers. AccountProfileStore owns those real locks; no request UUID is
 * an authority. The exclusive account lock also serializes new reservations and their quotas.
 * All callbacks retain the caller's transaction, and perform no network/commit/hidden signup.
 * Account status is the current moderation restriction; reports/case workflow are NOT bans.
 * This adapter never claims that uploaded content is safe or grants a circle/public read. */
class AccountMediaAuthority(private val environment: String, private val accounts: AccountProfileStore,
    private val policy: AccountMediaAdmissionPolicy, private val maxSourceBytes: Long) : MediaAuthority {
    init {
        require(environment == accounts.environment)
        require(maxSourceBytes in 1..10_000_000 && maxSourceBytes <= policy.maximumOwnedSourceBytes)
    }

    fun resolvePrincipal(connection: Connection, subject: VerifiedSupabaseSubject,
        deviceSessionId: UUID): VerifiedMediaAccount = accountFailure {
        transaction(connection)
        VerifiedMediaAccount.resolve(connection, accounts, subject, deviceSessionId).also { lockPrincipal(connection, it) }
    }

    override fun lockPrincipal(connection: Connection, actor: VerifiedMediaAccount) = accountFailure {
        transaction(connection)
        val subject = actor.providerSubject ?: fail(MediaFailureCode.UNAUTHENTICATED)
        if (actor.environment != environment || accounts.lockAccountSafety(connection, subject, actor.deviceSessionId) != actor.accountId)
            fail(MediaFailureCode.UNAUTHENTICATED)
        Unit
    }

    override fun requireUploadEnabled(connection: Connection, actor: VerifiedMediaAccount, newReservation: Boolean) = accountFailure {
        lockPrincipal(connection, actor)
        if (!policy.uploadsEnabled) fail(MediaFailureCode.NOT_CONFIGURED)
        val subject = actor.providerSubject ?: fail(MediaFailureCode.UNAUTHENTICATED)
        if (accounts.lockSocialEligibility(connection, subject, actor.deviceSessionId) != actor.accountId)
            fail(MediaFailureCode.UNAUTHENTICATED)
        connection.prepareStatement("SELECT eligibility_state,eligibility_policy_version,terms_version FROM identity.users " +
            "WHERE environment=? AND id=? FOR SHARE NOWAIT").use { statement ->
            statement.setString(1, environment); statement.setObject(2, actor.accountId)
            statement.executeQuery().use { rows ->
                if (!rows.next() || rows.getString(1) != "eligible" || rows.getString(2) != policy.eligibilityPolicyVersion ||
                    rows.getString(3) != policy.requiredTermsVersion) fail(MediaFailureCode.FORBIDDEN)
                if (rows.next()) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
            }
        }
        if (newReservation) {
            // No separate counter is consumed: the successful reservation INSERT in this
            // same transaction is the accounting fact. Original-key replay does not charge.
            // Reserve maximum request headroom because this interface intentionally does
            // not accept client byte counts. This is conservative, never an overspend race.
            connection.prepareStatement("SELECT count(*) FILTER(WHERE created_at>clock_timestamp()-interval '24 hours')," +
                "coalesce(sum(expected_bytes),0) FROM platform.media_assets WHERE environment=? AND owner_user_id=?").use { statement ->
                statement.setString(1, environment); statement.setObject(2, actor.accountId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
                    val count = rows.getLong(1); val bytes = rows.getBigDecimal(2) ?: fail(MediaFailureCode.STORAGE_UNAVAILABLE)
                    if (count < 0 || bytes < BigDecimal.ZERO) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
                    if (count >= policy.maximumReservationsPer24Hours ||
                        bytes > BigDecimal.valueOf(policy.maximumOwnedSourceBytes - maxSourceBytes)) fail(MediaFailureCode.FORBIDDEN)
                }
            }
        }
        lockPrincipal(connection, actor)
    }

    override fun lockDraftLifecycle(connection: Connection, actor: VerifiedMediaAccount,
        clientDraftId: UUID, forUpload: Boolean): Long = accountFailure {
        lockPrincipal(connection, actor)
        // Match the owner-head -> client-root -> draft/publication lock order. Absence is
        // fenced by the exclusive real account lock, not an invented generation or a GUC.
        connection.prepareStatement("SELECT revision FROM platform.post_draft_heads WHERE environment=? AND owner_user_id=? FOR UPDATE NOWAIT").use {
            it.setString(1, environment); it.setObject(2, actor.accountId)
            it.executeQuery().use { rows -> if (rows.next() && rows.getLong(1) <= 0) fail(MediaFailureCode.STORAGE_UNAVAILABLE) }
        }
        if (forUpload) {
            requireUploadEnabled(connection, actor, false)
            connection.prepareStatement("INSERT INTO platform.media_draft_lifecycles(environment,owner_user_id,client_draft_id,generation,created_at) " +
                "VALUES(?,?,?,1,clock_timestamp()) ON CONFLICT(environment,owner_user_id,client_draft_id) DO NOTHING").use {
                it.setString(1, environment); it.setObject(2, actor.accountId); it.setObject(3, clientDraftId); it.executeUpdate()
            }
        }
        val generation = connection.prepareStatement("SELECT generation FROM platform.media_draft_lifecycles " +
            "WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR UPDATE NOWAIT").use {
            it.setString(1, environment); it.setObject(2, actor.accountId); it.setObject(3, clientDraftId)
            it.executeQuery().use { rows ->
                if (!rows.next()) fail(MediaFailureCode.DRAFT_UNAVAILABLE)
                rows.getLong(1).also { value -> if (value <= 0 || rows.next()) fail(MediaFailureCode.STORAGE_UNAVAILABLE) }
            }
        }
        connection.prepareStatement("SELECT draft_generation,status,published_post_id,expires_at>clock_timestamp() FROM platform.post_drafts " +
            "WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR SHARE NOWAIT").use {
            it.setString(1, environment); it.setObject(2, actor.accountId); it.setObject(3, clientDraftId)
            it.executeQuery().use { rows ->
                if (rows.next()) {
                    if (rows.getLong(1) != generation) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
                    if (forUpload && (rows.getString(2) != "draft" || rows.getObject(3) != null || !rows.getBoolean(4)))
                        fail(MediaFailureCode.DRAFT_UNAVAILABLE)
                    if (rows.next()) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
                }
            }
        }
        if (forUpload && PostPublicationLifecycle.isPublished(connection, environment, actor.accountId, clientDraftId))
            fail(MediaFailureCode.DRAFT_UNAVAILABLE)
        lockPrincipal(connection, actor)
        generation
    }

    override fun requireUnattached(connection: Connection, actor: VerifiedMediaAccount, mediaId: UUID) = accountFailure {
        lockPrincipal(connection, actor)
        // Includes historical published/hidden/deleted posts: publication lineage is immutable.
        if (PostPublicationLifecycle.isAttached(connection, environment, actor.accountId, mediaId)) fail(MediaFailureCode.MEDIA_ATTACHED)
        // A profile avatar is also an attachment, not a source-photo cleanup candidate.
        // The current profile writer only accepts owned media; no arbitrary UUID/prefix scan.
        connection.prepareStatement("SELECT avatar_media_id FROM profile.profiles WHERE environment=? AND user_id=? FOR SHARE NOWAIT").use {
            it.setString(1, environment); it.setObject(2, actor.accountId)
            it.executeQuery().use { rows -> if (rows.next() && rows.getObject(1, UUID::class.java) == mediaId) fail(MediaFailureCode.MEDIA_ATTACHED) }
        }
        lockPrincipal(connection, actor)
    }

    private fun transaction(connection: Connection) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Media account operation interrupted")
        require(!connection.isClosed && !connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
    }
    private fun <T> accountFailure(action: () -> T): T = try { action() }
        catch (failure: AccountFailure) {
            throw MediaFailure(when (failure.code) {
                AccountFailureCode.UNAUTHENTICATED -> MediaFailureCode.UNAUTHENTICATED
                AccountFailureCode.NOT_CONFIGURED -> MediaFailureCode.NOT_CONFIGURED
                AccountFailureCode.STORAGE_UNAVAILABLE -> MediaFailureCode.STORAGE_UNAVAILABLE
                else -> MediaFailureCode.FORBIDDEN
            }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
        } catch (failure: SQLException) {
            if (failure.sqlState != "55P03") throw failure
            throw SQLException("Media lifecycle is contended", "40001").also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
        }
    private fun fail(code: MediaFailureCode): Nothing = throw MediaFailure(code)
    override fun toString() = "AccountMediaAuthority(<redacted>)"
}
