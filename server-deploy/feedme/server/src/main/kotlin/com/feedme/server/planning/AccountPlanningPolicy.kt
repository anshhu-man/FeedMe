package com.feedme.server.planning

import com.feedme.server.db.CommandActor
import java.security.MessageDigest
import java.sql.Connection
import java.time.LocalDate

/** Required operational choices, not subscription eligibility or a rollout default. Every
 * committed fresh create/next result consumes one unit on its database UTC admission day.
 * Receipts/reads do not. The real account principal lock serializes devices and absent rows;
 * reservation, Plan, outbox and command receipt commit or roll back together. */
class AccountPlanningPolicy(val revision: String, val newPlanningEnabled: Boolean, val maxPlansPerUtcDay: Int) {
    init {
        require(revision.isNotBlank() && revision.length <= 128 && revision.none(Char::isISOControl))
        revision.encodeToByteArray(throwOnInvalidSequence = true)
        require(maxPlansPerUtcDay in 1..10000)
    }

    internal fun checkCompatibility(c: Connection) {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        val expected = AccountPlanningPolicy::class.java.getResourceAsStream(RESOURCE)?.use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        } ?: fail(PlanningFailureCode.NOT_CONFIGURED)
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=29").use {
            it.executeQuery().use { rows ->
                if (!rows.next() || rows.getString(1) != expected || rows.next()) fail(PlanningFailureCode.NOT_CONFIGURED)
            }
        }
        c.createStatement().use { it.executeQuery("SELECT environment,actor_kind,principal_id,window_date,policy_revision,max_plans,used_count FROM planning.account_plan_windows WHERE false").close() }
    }

    internal fun requireNew(c: Connection, principal: VerifiedPlanningPrincipal) {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        if (principal.kind != CommandActor.ACCOUNT || principal.deviceSessionId == null) fail(PlanningFailureCode.UNAUTHENTICATED)
        if (!newPlanningEnabled) fail(PlanningFailureCode.POLICY_BLOCKED)
        val day = c.createStatement().use { it.executeQuery("SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date").use { rows ->
            if (!rows.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            rows.getObject(1, LocalDate::class.java)
        } }
        val used = c.prepareStatement("SELECT policy_revision,max_plans,used_count FROM planning.account_plan_windows WHERE environment=? AND principal_id=? AND window_date=? FOR UPDATE").use {
            it.setString(1, principal.environment); it.setObject(2, principal.principalId); it.setObject(3, day)
            it.executeQuery().use { rows ->
                if (!rows.next()) null else {
                    if (rows.getString(1) != revision || rows.getInt(2) != maxPlansPerUtcDay) fail(PlanningFailureCode.NOT_CONFIGURED)
                    rows.getInt(3)
                }
            }
        }
        if (used != null && used >= maxPlansPerUtcDay) fail(PlanningFailureCode.RATE_LIMITED)
        if (used == null) c.prepareStatement("INSERT INTO planning.account_plan_windows(environment,actor_kind,principal_id,window_date,policy_revision,max_plans,used_count) VALUES(?,'account',?,?,?,?,1)").use {
            it.setString(1, principal.environment); it.setObject(2, principal.principalId); it.setObject(3, day)
            it.setString(4, revision); it.setInt(5, maxPlansPerUtcDay); check(it.executeUpdate() == 1)
        } else c.prepareStatement("UPDATE planning.account_plan_windows SET used_count=used_count+1 WHERE environment=? AND principal_id=? AND window_date=? AND used_count=?").use {
            it.setString(1, principal.environment); it.setObject(2, principal.principalId); it.setObject(3, day); it.setInt(4, used)
            check(it.executeUpdate() == 1)
        }
    }

    override fun toString() = "AccountPlanningPolicy(<redacted>)"
    private fun fail(code: PlanningFailureCode): Nothing = throw PlanningServiceFailure(code)
    private companion object { const val RESOURCE = "/db/migration/V029__account_planning_windows.sql" }
}
