package com.feedme.server.identity

import java.sql.Connection
import java.sql.SQLException

internal data class AccountDeletionSupportRedaction(
    val claims: Int,
    val challenges: Int,
)

/**
 * Bounded maintenance adapter for the future erasure worker. It can only invoke
 * the reviewed redaction primitive; it has no direct claim/challenge table access.
 */
internal class AccountDeletionSupportRetentionStore(
    private val environment: String,
    private val batchLimit: Int,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(batchLimit in 1..1000)
    }

    fun redactDue(connection: Connection): AccountDeletionSupportRedaction = try {
        if (connection.isClosed || connection.autoCommit || connection.isReadOnly ||
            connection.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) unavailable()
        connection.prepareStatement(SQL).use { statement ->
            statement.setString(1, environment)
            statement.setInt(2, batchLimit)
            statement.executeQuery().use { rows ->
                if (!rows.next()) unavailable()
                val claims = rows.getLong(1)
                val claimsNull = rows.wasNull()
                val challenges = rows.getLong(2)
                val challengesNull = rows.wasNull()
                if (claimsNull || challengesNull || rows.next() || claims !in 0..batchLimit.toLong() ||
                    challenges !in 0..batchLimit.toLong()) unavailable()
                AccountDeletionSupportRedaction(claims.toInt(), challenges.toInt())
            }
        }
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt(); throw failure
    } catch (_: SQLException) { unavailable() }

    override fun toString() = "AccountDeletionSupportRetentionStore(<redacted>)"

    companion object {
        internal const val SQL = "SELECT redacted_claims,redacted_challenges " +
            "FROM safety.redact_expired_account_deletion_support_ownership(?,?)"
        private fun unavailable(): Nothing = throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
    }
}
