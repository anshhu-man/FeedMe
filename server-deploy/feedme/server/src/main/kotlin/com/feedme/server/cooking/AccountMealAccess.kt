package com.feedme.server.cooking

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import com.feedme.server.identity.AccountProfileStore
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Invocation-local actual account/device admission, not a cached account capability. */
internal class AccountMealAccess(private val environment: String, private val connection: Connection,
    private val accounts: AccountProfileStore, private val subject: VerifiedSupabaseSubject, private val device: UUID) {
    private val thread = Thread.currentThread()
    private val transaction = transactionId()
    private val original = accounts.lockPrivateAccount(connection, subject, device)
    val principalId: UUID get() = original.principalId
    init { if (original.environment != environment) denied() }
    fun current(c: Connection) {
        local(c)
        if (c.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) denied()
        if (transactionId() != transaction) denied()
        val actual = accounts.lockPrivateAccount(c, subject, device)
        if (actual.environment != environment || actual.accountId != original.accountId ||
            actual.principalId != original.principalId || actual.deviceSessionId != device) denied()
        checkAt(c, now(c))
    }
    fun checkAt(c: Connection, at: Instant) {
        local(c)
        if (at.epochSecond >= subject.expiresAtEpochSeconds) denied()
    }
    private fun local(c: Connection) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Account meal operation interrupted")
        // JDBC getTransactionIsolation can execute SQL. Final checkAt must remain local-only
        // after the accepted database timestamp; isolation is checked at bind/current instead.
        if (c !== connection || Thread.currentThread() !== thread || c.isClosed || c.autoCommit) denied()
    }
    private fun transactionId(): Long {
        local(connection)
        if (connection.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) denied()
        return connection.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { r ->
            check(r.next()); r.getLong(1).also { check(!r.next()) }
        } }
    }
    private fun denied(): Nothing = throw AccountFailure(AccountFailureCode.UNAUTHENTICATED)
    override fun toString() = "AccountMealAccess(<redacted>)"
    companion object {
        fun now(c: Connection): Instant = c.createStatement().use { s ->
            s.executeQuery("SELECT clock_timestamp()").use { r -> check(r.next())
                r.getObject(1, OffsetDateTime::class.java).toInstant().also { check(!r.next()) }
            }
        }
    }
}
