package com.feedme.server.runtime

import com.feedme.server.auth.HttpsSupabaseJwksSource
import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.config.DependencyHoldConfig
import com.feedme.server.db.PlatformMigrations
import com.feedme.server.db.PlatformMigrationInspectionStatus
import com.feedme.server.identity.SupabasePostgresAuthority
import java.sql.Connection
import java.time.Clock
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/** Coarse operational observation only. No product assembly, provider user rows, account
 * grants or readiness. One nonqueued caller, bounded attempts, no positive health cache. */
internal class DependencyHoldProbe(
    private val metadata: suspend () -> Unit,
    private val keyMaterial: suspend () -> Boolean,
    private val closeResources: () -> Unit = {},
    private val nanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val admission = Semaphore(1)
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private var lastAttempt: Long? = null

    suspend fun available(): Boolean {
        if (closed.get() || !admission.tryAcquire()) return false
        try {
            if (closed.get()) return false
            val now = nanos()
            if (lastAttempt?.let { now - it < 1_000_000_000L } == true) return false
            lastAttempt = now
            return withTimeoutOrNull(PROBE_BUDGET_MILLIS) {
                metadata()
                if (closed.get() || !keyMaterial()) false else {
                    // Recheck current DB review/shape after network I/O, without holding DB locks.
                    metadata(); !closed.get()
                }
            } == true
        } catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { return false }
        finally { admission.release() }
    }

    override fun close() { if (closed.compareAndSet(false, true)) closeResources() }

    companion object {
        // Held operational diagnosis only: measured remote migration + metadata passes
        // take ~17 seconds each before JWKS. Retain both passes, one nonqueued caller,
        // driver/SQL timeouts and full TLS verification. Not a product latency promise
        // or a larger current-account/provider authorization deadline.
        private const val PROBE_BUDGET_MILLIS = 45_000L

        fun open(config: DependencyHoldConfig, database: DataSource, dispatcher: CoroutineDispatcher,
            clock: Clock): DependencyHoldProbe {
            val authority = SupabasePostgresAuthority(config.deployment)
            var source: HttpsSupabaseJwksSource? = null
            try {
                val keys = HttpsSupabaseJwksSource.create(config.deployment.verification, config.keyPolicy, clock)
                source = keys
                val verifier = SupabaseUserAccessVerifier(config.deployment.verification, keys, clock)
                return DependencyHoldProbe(metadata = {
                    runInterruptible(dispatcher) {
                        check(PlatformMigrations(database).inspect().status == PlatformMigrationInspectionStatus.CURRENT)
                        inspectHeldDatabase(database, authority)
                    }
                }, keyMaterial = verifier::keyMaterialAvailable, closeResources = {
                    try { authority.close() } finally { keys.close() }
                })
            } catch (failure: Throwable) {
                val failures = mutableListOf(failure)
                for (close in listOf<() -> Unit>({ authority.close() }, { source?.close() }))
                    try { close() } catch (cleanup: Throwable) { failures += cleanup }
                rethrowAccountCoreFailure(*failures.toTypedArray())
            }
        }
    }
}

/** Dedicated rollback-only diagnostic transaction. No schema_lock(): its ROW EXCLUSIVE
 * locks are incompatible with READ ONLY. This is not the current-user authority path.
 * Failure to roll back/close is failure, never a successful compatibility observation. */
internal fun inspectHeldDatabase(database: DataSource, authority: SupabasePostgresAuthority) {
    val connection = database.connection
    var failure: Throwable? = null
    fun retain(problem: Throwable) {
        if (problem is InterruptedException) Thread.currentThread().interrupt()
        val prior = failure
        if (prior == null) failure = problem else if (prior !== problem) prior.addSuppressed(problem)
    }
    try {
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        connection.autoCommit = false
        connection.createStatement().use { statement ->
            statement.execute("SET TRANSACTION READ ONLY")
            statement.execute("SET LOCAL lock_timeout = '2s'")
            statement.execute("SET LOCAL statement_timeout = '5s'")
            statement.execute("SET LOCAL idle_in_transaction_session_timeout = '10s'")
            statement.executeQuery(HELD_ROLE_SQL).use { rows ->
                check(rows.next() && rows.getBoolean(1) && !rows.next())
            }
        }
        authority.inspectReadOnlyCompatibility(connection)
    } catch (problem: Throwable) { retain(problem) }
    finally {
        try { connection.rollback() } catch (problem: Throwable) { retain(problem) }
        try { connection.close() } catch (problem: Throwable) { retain(problem) }
    }
    failure?.let { rethrowAccountCoreFailure(it, *it.suppressed) }
}

private const val HELD_ROLE_SQL = """
SELECT current_user='feedme_api' AND session_user='feedme_api' AND current_setting('transaction_read_only')='on'
 AND r.rolcanlogin AND r.rolbypassrls AND NOT r.rolsuper AND NOT r.rolcreatedb
 AND NOT r.rolcreaterole AND NOT r.rolreplication AND NOT r.rolinherit AND r.rolconnlimit=12
 AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=r.oid)
 AND NOT EXISTS(SELECT 1 FROM pg_class WHERE relowner=r.oid)
 AND NOT EXISTS(SELECT 1 FROM pg_proc WHERE proowner=r.oid)
 AND NOT EXISTS(SELECT 1 FROM pg_namespace WHERE nspowner=r.oid OR has_schema_privilege(current_user,oid,'CREATE'))
 AND NOT EXISTS(SELECT 1 FROM pg_database WHERE datdba=r.oid)
 AND NOT has_parameter_privilege(current_user,'session_replication_role','SET')
 AND NOT has_schema_privilege(current_user,'auth','USAGE')
 AND NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
     WHERE n.nspname='auth' AND c.relkind IN ('r','p','v','m','f')
     AND (has_table_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
       OR has_any_column_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE')))
FROM pg_roles r WHERE r.rolname=current_user
"""
