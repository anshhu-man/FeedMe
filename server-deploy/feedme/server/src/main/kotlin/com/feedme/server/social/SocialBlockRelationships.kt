package com.feedme.server.social

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

/** Database-only relationship fence shared by circles, post/media readers and safety writers.
 * Even an absent pair has a shared/exclusive transaction advisory lock. No negative lookup
 * or old cursor is an authorization grant. Acquire after the acting principal and any circle
 * parents. A writer never takes a circle/invitation or foreign account lock afterward.
 * Target account/profile readers must use non-waiting foreign locks to avoid actor cycles.
 */
class SocialBlockRelationships {
    fun lockUnblockedPair(connection: Connection, environment: String, first: UUID, second: UUID) {
        if (first == second) return
        lock(connection, environment, first, second, exclusive = false)
        if (blocked(connection, environment, first, second)) unavailable()
    }

    fun requireInvitationPair(connection: Connection, environment: String, first: UUID, second: UUID,
        issuedOrder: Long) {
        require(issuedOrder > 0)
        if (first == second) return
        lockUnblockedPair(connection, environment, first, second)
        val (low, high) = pair(first, second)
        connection.prepareStatement("SELECT deny_invites_through FROM social.block_pairs WHERE environment=? AND user_low=? AND user_high=?").use {
            it.setString(1, environment); it.setObject(2, low); it.setObject(3, high)
            it.executeQuery().use { rows -> if (rows.next() && issuedOrder <= rows.getLong(1)) unavailable() }
        }
    }

    internal fun lockForChange(connection: Connection, environment: String, first: UUID, second: UUID) =
        lock(connection, environment, first, second, exclusive = true)

    /** Called under the SAME exclusive pair fence on both block and unblock. Allocation
     * order linearizes concurrent invitation creation; it does not pretend to be commit time.
     * Unblocking keeps history and never restores an invitation from the blocked interval. */
    internal fun advance(connection: Connection, environment: String, first: UUID, second: UUID) {
        lockForChange(connection, environment, first, second)
        val (low, high) = pair(first, second)
        connection.prepareStatement("INSERT INTO social.block_pairs(environment,user_low,user_high,version,deny_invites_through) " +
            "VALUES(?,?,?,1,nextval('social.relationship_order')) ON CONFLICT(environment,user_low,user_high) DO UPDATE " +
            "SET version=block_pairs.version+1,deny_invites_through=EXCLUDED.deny_invites_through").use {
            it.setString(1, environment); it.setObject(2, low); it.setObject(3, high); check(it.executeUpdate() == 1)
        }
    }

    private fun blocked(connection: Connection, environment: String, first: UUID, second: UUID): Boolean =
        connection.prepareStatement("SELECT 1 FROM social.blocks WHERE environment=? AND active AND " +
            "((owner_user_id=? AND target_user_id=?) OR (owner_user_id=? AND target_user_id=?)) LIMIT 1").use {
            it.setString(1, environment); it.setObject(2, first); it.setObject(3, second)
            it.setObject(4, second); it.setObject(5, first); it.executeQuery().use { rows -> rows.next() }
        }

    private fun lock(connection: Connection, environment: String, first: UUID, second: UUID, exclusive: Boolean) {
        require(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && first != second)
        val (low, high) = pair(first, second)
        val material = buildJsonArray { add("feedme.social.block-pair.v1"); add(environment); add(low.toString()); add(high.toString()) }
        val key = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(material.toString().encodeToByteArray())).long
        val function = if (exclusive) "pg_advisory_xact_lock" else "pg_advisory_xact_lock_shared"
        connection.prepareStatement("SELECT $function(?)").use { it.setLong(1, key); it.execute() }
    }

    private fun pair(first: UUID, second: UUID) =
        if (first.toString() < second.toString()) first to second else second to first
    private fun unavailable(): Nothing = throw SocialFailure(SocialFailureCode.CIRCLE_UNAVAILABLE)
    override fun toString() = "SocialBlockRelationships(<redacted>)"
}
