package com.feedme.server.identity

import com.feedme.core.ports.SecretText
import com.feedme.server.staff.SupabaseStaffModeratorActor
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * A short-lived delivery owned by one support response. The raw challenge can be
 * transferred exactly once and is never included in diagnostics or persisted.
 */
internal class AccountDeletionSupportChallengeDelivery internal constructor(
    val challenge: AccountDeletionSupportOwnershipChallenge,
    token: SecretText,
) {
    private var pendingToken: SecretText? = token

    @Synchronized fun takeTokenForResponse(): SecretText =
        pendingToken?.also { pendingToken = null } ?: unavailable()

    override fun toString() = "AccountDeletionSupportChallengeDelivery(<redacted>)"

    private companion object {
        fun unavailable(): Nothing = throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
    }
}

/** Test seam for the already locked, current staff-moderator transaction. */
internal interface AccountDeletionSupportChallengeIssuerBinding {
    val environment: String
    val actorId: UUID
    val providerSessionId: UUID
    val authorityRevision: String
    fun revalidate(connection: Connection)
}

private class LockedAccountDeletionSupportChallengeIssuerBinding(
    private val actor: SupabaseStaffModeratorActor,
) : AccountDeletionSupportChallengeIssuerBinding {
    override val environment = actor.environment
    override val actorId = actor.actorId
    override val providerSessionId = actor.providerSessionId
    override val authorityRevision = actor.authorityRevision
    override fun revalidate(connection: Connection) = actor.requireConnection(connection)
}

/**
 * Creates only an unbound one-time ownership challenge. The database function
 * independently rechecks the current moderator, MFA session and policy before
 * inserting the digest; the raw 256-bit secret exists only in the response owner.
 */
internal class AccountDeletionSupportChallengeIssuer(
    private val environment: String,
    private val policy: AccountDeletionSupportOwnershipPolicy,
    private val random: SecureRandom = SecureRandom(),
    private val nextId: () -> UUID = UUID::randomUUID,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(policy.version == POLICY && policy.maximumChallengeLifetimeSeconds == LIFETIME_SECONDS)
    }

    fun issue(connection: Connection, actor: SupabaseStaffModeratorActor):
        AccountDeletionSupportChallengeDelivery =
        issue(connection, LockedAccountDeletionSupportChallengeIssuerBinding(actor))

    internal fun issue(connection: Connection, actor: AccountDeletionSupportChallengeIssuerBinding):
        AccountDeletionSupportChallengeDelivery = try {
        require(actor.environment == environment && actor.actorId != ZERO &&
            actor.providerSessionId != ZERO && actor.actorId != actor.providerSessionId &&
            actor.authorityRevision.matches(Regex("[0-9a-f]{64}")))
        if (connection.isClosed || connection.autoCommit ||
            connection.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) unavailable()
        actor.revalidate(connection)

        val challengeId = nextId().also { require(it != ZERO) }
        val tokenBytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(tokenBytes)
        val encoded = try { Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes) }
            finally { tokenBytes.fill(0) }
        val token = SecretText(encoded)
        val digest = AccountDeletionSupportOwnershipChallenge.digestForPersistence(token)
        val row = try {
            connection.prepareStatement(SQL).use { statement ->
                statement.setString(1, environment)
                statement.setObject(2, challengeId)
                statement.setString(3, digest.joinToString("") { "%02x".format(it.toInt() and 255) })
                statement.setString(4, policy.version)
                statement.setObject(5, actor.actorId)
                statement.setObject(6, actor.providerSessionId)
                statement.setString(7, actor.authorityRevision)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) unavailable()
                    val value = Row(rows.getObject(1, UUID::class.java),
                        rows.getObject(2, OffsetDateTime::class.java)?.toInstant(),
                        rows.getObject(3, OffsetDateTime::class.java)?.toInstant())
                    if (rows.next()) unavailable()
                    value
                }
            }
        } finally { digest.fill(0) }
        actor.revalidate(connection)
        val issuedAt = row.issuedAt ?: unavailable()
        val expiresAt = row.expiresAt ?: unavailable()
        if (row.id != challengeId || issuedAt >= expiresAt ||
            Duration.between(issuedAt, expiresAt) != Duration.ofSeconds(LIFETIME_SECONDS)) unavailable()
        AccountDeletionSupportChallengeDelivery(
            AccountDeletionSupportOwnershipChallenge.issue(challengeId, token, policy.version,
                issuedAt, expiresAt), token)
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt(); throw failure
    } catch (_: SQLException) { unavailable() }

    override fun toString() = "AccountDeletionSupportChallengeIssuer(<redacted>)"

    private class Row(val id: UUID?, val issuedAt: java.time.Instant?, val expiresAt: java.time.Instant?)

    companion object {
        internal const val SQL = "SELECT challenge_id,issued_at,expires_at " +
            "FROM safety.issue_account_deletion_support_challenge(?,?,?,?,?,?,?)"
        private const val POLICY = "feedme-support-ownership-v1"
        private const val LIFETIME_SECONDS = 600L
        private const val TOKEN_BYTES = 32
        private val ZERO = UUID(0, 0)
        private fun unavailable(): Nothing = throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
    }
}
