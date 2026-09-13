package com.feedme.server.db

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/** Keep the original command key and reconcile; do not invent a replacement command. */
class CommitOutcomeUnknown : RuntimeException("Database commit outcome is unknown; retry with the original command identity")

/** Dedicated connections; callbacks may perform database work only, never external effects or commit themselves. */
class PgTransactions(private val dataSource: DataSource, private val attempts: Int = 3) {
    init { require(attempts in 1..5) }

    fun <T> run(block: (Connection) -> T): T {
        for (attempt in 1..attempts) {
            val connection = dataSource.connection
            var committing = false
            try {
                connection.autoCommit = false
                connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                connection.createStatement().use { statement ->
                    statement.execute("SET LOCAL lock_timeout = '5s'")
                    statement.execute("SET LOCAL statement_timeout = '10s'")
                    statement.execute("SET LOCAL idle_in_transaction_session_timeout = '15s'")
                }
                val result = block(connection)
                check(!connection.autoCommit) { "Transaction callback changed connection ownership" }
                committing = true
                connection.commit()
                return result
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                val retryable = failure is SQLException && failure.sqlState in setOf("40001", "40P01")
                if (!retryable || attempt == attempts) {
                    if (committing && !retryable) throw CommitOutcomeUnknown()
                    throw failure
                }
            } finally {
                // A close failure cannot undo a known successful commit; no credentials/SQL are logged.
                runCatching { connection.close() }
            }
            Thread.sleep(10L * attempt) // Bounded DB-only retry; interruption stops the operation.
        }
        error("Unreachable transaction retry state")
    }
}
