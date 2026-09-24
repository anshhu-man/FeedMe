package com.feedme.server.identity

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Historical one-time ownership claim; not a deletion request or completion receipt. */
internal class AccountDeletionSupportClaim internal constructor(
    val id: UUID,
    val challengeId: UUID,
    val issuer: String,
    val subject: UUID,
    val claimedAt: Instant,
) {
    override fun toString() = "AccountDeletionSupportClaim(<redacted>)"
}

/**
 * Calls only the atomic SECURITY DEFINER claim primitive. The primitive independently
 * locks the challenge, compares its digest, rechecks current OAuth + Google identity,
 * inserts the immutable claim and consumes the challenge in one transaction.
 */
internal class AccountDeletionSupportClaimStore(private val environment: String) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun claim(connection: Connection, challenge: AccountDeletionSupportOwnershipChallenge,
        ownership: AccountDeletionSupportOwnershipEvidence, oauth: SupabaseOAuthReauthenticationEvidence,
        claimId: UUID): AccountDeletionSupportClaim = claim(connection, challenge, ownership,
            LockedAccountDeletionSupportOAuthBinding(oauth), claimId)

    internal fun claim(connection: Connection, challenge: AccountDeletionSupportOwnershipChallenge,
        ownership: AccountDeletionSupportOwnershipEvidence, oauth: AccountDeletionSupportOAuthBinding,
        claimId: UUID): AccountDeletionSupportClaim = try {
        require(claimId != ZERO && ownership.challengeId == challenge.id &&
            ownership.policyVersion == challenge.policyVersion &&
            ownership.issuer == oauth.subject.issuer && ownership.subject == oauth.subject.subject &&
            ownership.providerSessionId == oauth.subject.providerSessionId &&
            ownership.verifiedAt >= oauth.acceptedAt && ownership.validUntil <= oauth.validUntil &&
            ownership.verifiedAt < ownership.validUntil)
        if (connection.isClosed || connection.autoCommit ||
            connection.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) unavailable()
        oauth.revalidate(connection)
        val digest = challenge.digestForPersistence()
        val row = try {
            connection.prepareStatement(SQL).use { statement ->
                statement.setString(1, environment)
                statement.setObject(2, challenge.id)
                statement.setObject(3, claimId)
                statement.setString(4, digest.joinToString("") { "%02x".format(it.toInt() and 255) })
                statement.setString(5, challenge.policyVersion)
                statement.setString(6, ownership.issuer)
                statement.setObject(7, ownership.subject)
                statement.setObject(8, ownership.providerSessionId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) unavailable()
                    val value = Row(rows.getObject(1, UUID::class.java),
                        rows.getObject(2, UUID::class.java), rows.getString(3),
                        rows.getObject(4, UUID::class.java),
                        rows.getObject(5, OffsetDateTime::class.java)?.toInstant())
                    if (rows.next()) unavailable()
                    value
                }
            }
        } finally { digest.fill(0) }
        oauth.revalidate(connection)
        if (row.id != claimId || row.challenge != challenge.id || row.issuer != ownership.issuer ||
            row.subject != ownership.subject || row.claimedAt == null ||
            row.claimedAt < ownership.verifiedAt || row.claimedAt >= ownership.validUntil) unavailable()
        AccountDeletionSupportClaim(row.id, row.challenge, row.issuer, row.subject, row.claimedAt)
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt(); throw failure
    } catch (_: SQLException) { unavailable() }

    override fun toString() = "AccountDeletionSupportClaimStore(<redacted>)"

    private class Row(val id: UUID?, val challenge: UUID?, val issuer: String?,
        val subject: UUID?, val claimedAt: Instant?)

    companion object {
        internal const val SQL = "SELECT claim_id,challenge_id,provider_issuer,provider_subject,claimed_at " +
            "FROM safety.claim_account_deletion_support_ownership(?,?,?,?,?,?,?,?)"
        private val ZERO = UUID(0, 0)
        private fun unavailable(): Nothing = throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
    }
}
