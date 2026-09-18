package com.feedme.server.db

import java.sql.Connection
import java.sql.SQLException
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import javax.sql.DataSource

/** Keep the original command key and reconcile; do not invent a replacement command. */
class CommitOutcomeUnknown : RuntimeException("Database commit outcome is unknown; retry with the original command identity")

/** Dedicated connections; callbacks may perform database work only, never external effects or
 * commit themselves. Retry only an explicitly aborted serialization/deadlock attempt whose
 * owned cleanup completed. A lost commit acknowledgement always retains the original command
 * identity; cancellation, interruption and fatal failures are never converted into success or
 * a normal database failure. No raw JDBC failure is attached to CommitOutcomeUnknown. */
class PgTransactions(private val dataSource: DataSource, private val attempts: Int = 3) {
    init { require(attempts in 1..5) }

    fun <T> run(block: (Connection) -> T): T {
        for (attempt in 1..attempts) {
            requireUninterrupted()
            val connection = try { dataSource.connection }
            catch (failure: Throwable) { preserveInterruption(failure); throw failure }
            var committing = false
            var committed = false
            var statementCleanupFailed = false
            var failure: Throwable? = null
            val cleanupFailures = mutableListOf<Throwable>()
            var result: T? = null
            fun cleanup(action: () -> Unit) {
                try { action() }
                catch (problem: Throwable) {
                    preserveInterruption(problem)
                    cleanupFailures += problem
                }
            }
            try {
                connection.autoCommit = false
                connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                configureTimeouts(connection) { statementCleanupFailed = true }
                result = block(connection)
                check(!connection.autoCommit) { "Transaction callback changed connection ownership" }
                requireUninterrupted()
                committing = true
                connection.commit()
                committed = true
            } catch (problem: Throwable) {
                preserveInterruption(problem)
                failure = problem
            } finally {
                // Both owned cleanup steps are attempted once even if either throws. Never
                // roll back a known commit or let a failed cleanup start another attempt.
                if (!committed) cleanup { connection.rollback() }
                cleanup { connection.close() }
            }
            val critical = controlFlowFailure(listOfNotNull(failure) + cleanupFailures)
            // Inspect suppressed cleanup too: Kotlin use can retain a cancellation or
            // fatal close failure under an ordinary SQL exception. Throw that exact signal
            // without wrapping it or making a cyclic exception graph by attaching its owner.
            if (critical != null) throw critical
            if (committed) {
                // An ordinary close failure cannot undo a known successful commit. Do not
                // turn it into an ambiguous result that could encourage a duplicate action.
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
            val original = checkNotNull(failure)
            val retryable = original is SQLException && original.sqlState in setOf("40001", "40P01")
            if (committing && !retryable) throw CommitOutcomeUnknown()
            cleanupFailures.forEach { retainSuppressed(original, it) }
            if (!retryable || statementCleanupFailed || cleanupFailures.isNotEmpty() || original.suppressed.isNotEmpty() ||
                attempt == attempts || Thread.currentThread().isInterrupted)
                throw original
            // Bounded DB-only retry. Preserve the interruption flag that sleep clears.
            try { Thread.sleep(10L * attempt) }
            catch (interrupted: InterruptedException) { preserveInterruption(interrupted); throw interrupted }
        }
        error("Unreachable transaction retry state")
    }

    private fun configureTimeouts(connection: Connection, cleanupFailed: () -> Unit) {
        val statement = connection.createStatement()
        var failure: Throwable? = null
        try {
            statement.execute("SET LOCAL lock_timeout = '5s'")
            statement.execute("SET LOCAL statement_timeout = '10s'")
            statement.execute("SET LOCAL idle_in_transaction_session_timeout = '15s'")
        } catch (problem: Throwable) { preserveInterruption(problem); failure = problem }
        finally {
            try { statement.close() }
            catch (problem: Throwable) {
                cleanupFailed()
                preserveInterruption(problem)
                val original = failure
                if (original == null) failure = problem
                else {
                    val critical = controlFlowFailure(listOf(original, problem))
                    if (critical != null) failure = critical
                    else retainSuppressed(original, problem)
                }
            }
        }
        failure?.let { throw it }
    }
}

private fun isControlFlowFailure(failure: Throwable): Boolean =
    failure is Error || failure is CancellationException || failure is InterruptedException

/** Only inspect suppression, not arbitrary driver causes/messages. Bounded, identity-safe
 * traversal handles nested use/cleanup and cyclic test/provider graphs. Any suppression
 * already prevents retry even if the inspection limit is reached. */
private fun controlFlowFailure(failures: List<Throwable>): Throwable? {
    val seen = IdentityHashMap<Throwable, Boolean>()
    var critical: Throwable? = null
    fun inspect(failure: Throwable) {
        preserveInterruption(failure)
        if (critical == null && isControlFlowFailure(failure)) critical = failure
        if (seen.size >= 64 || seen.put(failure, true) != null) return
        failure.suppressed.take(64).forEach(::inspect)
    }
    failures.forEach(::inspect)
    return critical
}

private fun preserveInterruption(failure: Throwable) {
    if (failure is InterruptedException) Thread.currentThread().interrupt()
}

private fun requireUninterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Database transaction interrupted")
}

private fun retainSuppressed(primary: Throwable, secondary: Throwable) {
    if (primary !== secondary && primary.suppressed.none { it === secondary }) primary.addSuppressed(secondary)
}
