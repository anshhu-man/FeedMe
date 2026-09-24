package com.feedme.server.identity

import com.feedme.server.auth.VerifiedSupabaseSubject
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime

/** Test seam around already-issued, same-transaction provider authority. */
internal interface AccountDeletionSupportOAuthBinding {
    val subject: VerifiedSupabaseSubject
    val oauthAuthenticatedAt: Instant
    val acceptedAt: Instant
    val validUntil: Instant
    fun revalidate(connection: Connection)
}

internal class LockedAccountDeletionSupportOAuthBinding(
    private val evidence: SupabaseOAuthReauthenticationEvidence,
) : AccountDeletionSupportOAuthBinding {
    override val subject get() = evidence.subject
    override val oauthAuthenticatedAt get() = evidence.oauthAuthenticatedAt
    override val acceptedAt get() = evidence.acceptedAt
    override val validUntil get() = evidence.validUntil
    override fun revalidate(connection: Connection) = evidence.revalidate(connection)
}

/**
 * Reads only the reviewed SECURITY DEFINER projection. It cannot inspect email,
 * identity metadata or provider IDs and receives no direct `auth.identities` grant.
 * Missing/duplicate/non-Google facts fail closed without distinguishing the reason.
 */
internal class SupabaseCurrentGoogleIdentitySource {
    fun observe(connection: Connection, evidence: SupabaseOAuthReauthenticationEvidence):
        CurrentSupabaseGoogleIdentityObservation? = observe(connection,
            LockedAccountDeletionSupportOAuthBinding(evidence))

    internal fun observe(connection: Connection, binding: AccountDeletionSupportOAuthBinding):
        CurrentSupabaseGoogleIdentityObservation? = try {
        if (connection.isClosed || connection.autoCommit ||
            connection.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) unavailable()
        binding.revalidate(connection)
        val rows = connection.prepareStatement(SQL).use { statement ->
            statement.setObject(1, binding.subject.subject)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        if (size == 2) { add(Row(null, null, null)); break }
                        add(Row(
                            result.getObject(1, java.util.UUID::class.java),
                            result.getString(2),
                            result.getObject(3, OffsetDateTime::class.java)?.toInstant(),
                        ))
                    }
                }
            }
        }
        binding.revalidate(connection)
        val row = rows.singleOrNull() ?: return null
        if (row.subject != binding.subject.subject || row.provider != "google" ||
            row.observedAt == null || row.observedAt < binding.acceptedAt ||
            row.observedAt >= binding.validUntil) return null
        CurrentSupabaseGoogleIdentityObservation(binding.subject.issuer, binding.subject.subject,
            binding.subject.providerSessionId, row.provider, binding.oauthAuthenticatedAt,
            row.observedAt, binding.validUntil)
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt(); throw failure
    } catch (_: SQLException) { unavailable() }

    override fun toString() = "SupabaseCurrentGoogleIdentitySource(<redacted>)"

    private class Row(val subject: java.util.UUID?, val provider: String?, val observedAt: Instant?)

    companion object {
        internal const val SQL = "SELECT user_id,provider,observed_at FROM feedme_auth_access.google_identity_facts(?)"
        private fun unavailable(): Nothing = throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
    }
}
