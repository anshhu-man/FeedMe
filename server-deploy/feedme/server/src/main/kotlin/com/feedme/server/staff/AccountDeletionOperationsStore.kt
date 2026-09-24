package com.feedme.server.staff

import com.feedme.server.identity.AccountDeletionOperationsEvidence
import com.feedme.server.identity.AccountDeletionOperationsProjection
import com.feedme.server.identity.AccountDeletionOperationsState
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** One atomic, audited observation of an opaque account-deletion receipt.
 *
 * This adapter is deliberately not runtime-composed until V092 and its exact
 * EXECUTE-only grant are registered and accepted. It reads no ledger directly:
 * the database function validates current moderator/MFA authority, appends the
 * immutable access audit and only then returns already-redacted stage evidence.
 */
internal class AccountDeletionOperationsStore(
    private val environment: String,
    private val providerRequired: Boolean,
    private val nextAuditId: () -> UUID = UUID::randomUUID,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) {
            "Invalid deletion operations environment"
        }
    }

    fun observe(connection: Connection, actor: SupabaseStaffModeratorActor,
        receipt: UUID, traceId: UUID): AccountDeletionOperationsState {
        require(actor.environment == environment) { "Deletion operations authority mismatch" }
        actor.requireConnection(connection)
        val evidence = connection.prepareStatement(OBSERVE).use { statement ->
            statement.setString(1, environment)
            statement.setObject(2, receipt)
            statement.setObject(3, nextAuditId())
            statement.setObject(4, actor.actorId)
            statement.setObject(5, actor.providerSessionId)
            statement.setString(6, actor.authorityRevision)
            statement.setObject(7, traceId)
            statement.executeQuery().use(::single)
        }
        actor.requireConnection(connection)
        return AccountDeletionOperationsProjection.project(evidence)
    }

    private fun single(rows: ResultSet): AccountDeletionOperationsEvidence {
        check(rows.next()) { "Deletion operations observation unavailable" }
        val accepted = rows.getString(1)
        val work = rows.getString(2)
        val reason = rows.getString(3)
        val provider = rows.getString(4)
        val durable = rows.getBoolean(5)
        check(!rows.wasNull() && !rows.next()) { "Deletion operations observation unavailable" }
        return AccountDeletionOperationsEvidence(
            acceptedStage = accepted,
            workStage = work,
            lastReason = reason,
            providerRequired = providerRequired,
            providerStage = provider,
            durableCompletion = durable,
        )
    }

    override fun toString() = "AccountDeletionOperationsStore(<redacted>)"

    private companion object {
        const val OBSERVE =
            "SELECT accepted_stage,work_stage,last_reason,provider_stage,durable_completion " +
                "FROM safety.observe_account_deletion_receipt(?,?,?,?,?,?,?)"
    }
}
