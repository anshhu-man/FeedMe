package com.feedme.server.guest

import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Runtime-selected guest admission. Configuration is only the outer kill switch; existing
 * sessions/replays must also resolve to one current DB root bound to the exact policy. The
 * session store independently performs token, expiry, replay, issuance and rate-limit checks.
 * These callbacks add no writes and never turn an installation digest into identity.
 */
internal class ConfiguredGuestSessionAuthority(
    private val environment: String,
    private val policy: GuestSessionPolicy,
    private val newSessionsEnabled: Boolean,
    private val bootstrapReplayEnabled: Boolean,
) : GuestSessionAuthority {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    override fun requireBootstrap(connection: Connection, request: GuestBootstrapRequest, replay: Boolean) = checked {
        requireTransaction(connection)
        if (request.environment != environment || (replay && !bootstrapReplayEnabled) || (!replay && !newSessionsEnabled))
            fail(GuestSessionFailureCode.POLICY_BLOCKED)
        val existing = one(connection,
            "SELECT 1 FROM identity.guest_bootstrap_receipts b " +
                "JOIN identity.guest_sessions g ON g.environment=b.environment AND g.id=b.guest_session_id " +
                "JOIN identity.principals p ON p.environment=g.environment AND p.guest_session_id=g.id " +
                "AND p.kind='guest' AND p.status='active' " +
                "WHERE b.environment=? AND b.command_key=? AND b.installation_sha256=? AND b.request_sha256=? " +
                "AND b.expires_at>clock_timestamp() AND g.revoked_at IS NULL AND g.disabled=false " +
                "AND g.merged_to_user_id IS NULL AND g.inactivity_expires_at>clock_timestamp() " +
                "AND g.absolute_expires_at>clock_timestamp() AND g.policy_revision=? " +
                "AND g.capabilities=?::jsonb AND g.daily_plan_limit=?",
            { setString(1, environment); setObject(2, request.commandKey); setString(3, request.installationSha256)
                setString(4, request.requestSha256); setString(5, policy.bindingSha256)
                setString(6, capabilities()); setInt(7, policy.dailyPlanLimit) }) != null
        // The first pre-issue check has no receipt. A replay must already have one; the
        // post-issue check must resolve the newly committed-in-transaction root exactly.
        if (replay && !existing) fail(GuestSessionFailureCode.POLICY_BLOCKED)
    }

    override fun requireCurrent(connection: Connection, guestSessionId: UUID, principalId: UUID,
        operation: String) = checked {
        requireTransaction(connection)
        if (operation != "getCurrentGuestSession" && operation !in policy.capabilities)
            fail(GuestSessionFailureCode.POLICY_BLOCKED)
        if (one(connection,
            "SELECT 1 FROM identity.guest_sessions g JOIN identity.principals p " +
                "ON p.environment=g.environment AND p.guest_session_id=g.id " +
                "WHERE g.environment=? AND g.id=? AND p.id=? AND p.kind='guest' AND p.status='active' " +
                "AND g.revoked_at IS NULL AND g.disabled=false AND g.merged_to_user_id IS NULL " +
                "AND g.inactivity_expires_at>clock_timestamp() AND g.absolute_expires_at>clock_timestamp() " +
                "AND g.policy_revision=? AND g.capabilities=?::jsonb AND g.daily_plan_limit=?",
            { setString(1, environment); setObject(2, guestSessionId); setObject(3, principalId)
                setString(4, policy.bindingSha256); setString(5, capabilities()); setInt(6, policy.dailyPlanLimit) }) == null)
            fail(GuestSessionFailureCode.POLICY_BLOCKED)
    }

    private fun capabilities(): String = JsonArray(policy.capabilities.map(::JsonPrimitive)).toString()
    private fun requireTransaction(connection: Connection) {
        if (connection.isClosed || connection.autoCommit) fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun one(connection: Connection, sql: String, bind: PreparedStatement.() -> Unit): Int? =
        connection.prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use { rows ->
            if (!rows.next()) null else rows.getInt(1).also {
                if (rows.next()) fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
            }
        } }
    private inline fun <T> checked(action: () -> T): T = try { action() }
        catch (failure: GuestSessionFailure) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: GuestSessionFailureCode): Nothing = throw GuestSessionFailure(code)
    override fun toString() = "ConfiguredGuestSessionAuthority(<redacted>)"
}
