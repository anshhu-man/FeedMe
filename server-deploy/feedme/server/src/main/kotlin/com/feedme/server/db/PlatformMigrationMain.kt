package com.feedme.server.db

import com.feedme.server.config.PlatformMigrationConfig
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.system.exitProcess

/** Separate opt-in process. Main/HTTP startup never invokes this entry point.
 * Run the packaged class with only --check or --apply; supply secrets through the process environment,
 * never arguments, shell command examples or diagnostic output. No automatic retries. */
fun main(args: Array<String>) {
    var inspection: PlatformMigrationInspection? = null
    val result = runPlatformMigration(args, System::getenv, reportInspection = { inspection = it })
    (if (result.code == 0) System.out else System.err).println(migrationMessage(result, inspection))
    if (result.code != 0) exitProcess(result.code)
}

internal enum class MigrationExit(val code: Int, val message: String) {
    HELP(0, "Use --check to inspect migration history without applying migrations, or --apply to apply them. Each must be the only argument. Select FEEDME_MIGRATION_ENVIRONMENT and explicit FEEDME_MIGRATION_DB_HOST, PORT, NAME, USER, PASSWORD settings. Remote targets also require FEEDME_MIGRATION_DB_SSL_ROOT_CERT. Credentials must not be command arguments. Check exits: 0 current, 3 pending, 4 incompatible; 2 refused, 1 failed, 130 interrupted. History checks do not prove physical schema integrity or product readiness."),
    REFUSED(2, "Migration operation not started: exactly one of --check or --apply and valid, unambiguous migration environment settings are required."),
    COMPLETE(0, "Platform migrations completed. This does not enable product services or establish production readiness."),
    FAILED(1, "Migration failed; its outcome may be unknown. Inspect the selected database before any explicit retry. No automatic retry was attempted."),
    INTERRUPTED(130, "Migration interrupted; its outcome may be unknown. Inspect the selected database before any explicit retry."),
    CHECK_CURRENT(0, "Packaged migration history is current. No migrations were applied. This does not prove physical schema integrity or product readiness."),
    CHECK_PENDING(3, "Packaged migrations are pending. No migrations were applied. This does not prove physical schema integrity or product readiness."),
    CHECK_INCOMPATIBLE(4, "Migration history is incompatible with this package. No migrations were applied. No partial version list is trusted; operator investigation is required."),
    CHECK_FAILED(1, "Migration history check failed; no compatibility result is available. No migrations were applied or automatically retried."),
    CHECK_INTERRUPTED(130, "Migration history check interrupted; no compatibility result is available. No migrations were applied or automatically retried."),
}

internal fun runPlatformMigration(args: Array<String>, environment: () -> Map<String, String>,
    inspect: (PlatformMigrationConfig) -> PlatformMigrationInspection = ::inspectPlatformMigration,
    reportInspection: (PlatformMigrationInspection) -> Unit = {},
    migrate: (PlatformMigrationConfig) -> Unit = ::applyPlatformMigration): MigrationExit {
    if (args.contentEquals(arrayOf("--help"))) return MigrationExit.HELP
    val check = args.contentEquals(arrayOf("--check"))
    if (!check && !args.contentEquals(arrayOf("--apply"))) return MigrationExit.REFUSED
    val interrupted = if (check) MigrationExit.CHECK_INTERRUPTED else MigrationExit.INTERRUPTED
    if (Thread.currentThread().isInterrupted) return interrupted
    val config = try { PlatformMigrationConfig.fromEnvironment(environment()) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); return interrupted }
        catch (_: Exception) { return if (Thread.currentThread().isInterrupted) interrupted else MigrationExit.REFUSED }
    if (Thread.currentThread().isInterrupted) return interrupted
    return try {
        if (check) {
            val inspection = inspect(config)
            if (Thread.currentThread().isInterrupted) return interrupted
            reportInspection(inspection)
            if (Thread.currentThread().isInterrupted) return interrupted
            when (inspection.status) {
                PlatformMigrationInspectionStatus.CURRENT -> MigrationExit.CHECK_CURRENT
                PlatformMigrationInspectionStatus.PENDING -> MigrationExit.CHECK_PENDING
                PlatformMigrationInspectionStatus.INCOMPATIBLE -> MigrationExit.CHECK_INCOMPATIBLE
            }
        } else {
            migrate(config)
            if (Thread.currentThread().isInterrupted) interrupted else MigrationExit.COMPLETE
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt(); interrupted
    } catch (_: Exception) {
        if (Thread.currentThread().isInterrupted) interrupted else if (check) MigrationExit.CHECK_FAILED else MigrationExit.FAILED
    }
}

/** Only successful compatible checks expose packaged version integers, never configuration or SQL. */
internal fun migrationMessage(result: MigrationExit, inspection: PlatformMigrationInspection?): String {
    if (result != MigrationExit.CHECK_CURRENT && result != MigrationExit.CHECK_PENDING) return result.message
    checkNotNull(inspection)
    fun versions(values: List<Int>) = values.joinToString(",").ifEmpty { "none" }
    return "${result.message}\nApplied versions: ${versions(inspection.appliedVersions)}\nPending versions: ${versions(inspection.pendingVersions)}"
}

private fun inspectPlatformMigration(config: PlatformMigrationConfig): PlatformMigrationInspection {
    val source = config.dataSource()
    try { return PlatformMigrations(checkedMigrationDataSource(source, config.database)).inspect() }
    finally { source.setPassword(null) }
}

private fun applyPlatformMigration(config: PlatformMigrationConfig) {
    val source = config.dataSource()
    try { PlatformMigrations(checkedMigrationDataSource(source, config.database)).migrate() }
    finally { source.setPassword(null) }
}

/** Read-only target check precedes every statement issued by the existing migration engine.
 * This verifies the selected database name, not the operator's environment/tenant claims.
 * Credential overrides and datasource fallback connections are deliberately unavailable. */
internal fun checkedMigrationDataSource(source: DataSource, database: String): DataSource = object : DataSource by source {
    override fun getConnection(): Connection {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val connection = source.connection
        try {
            connection.createStatement().use { statement ->
                statement.queryTimeout = 5
                statement.executeQuery("SELECT current_database()").use { rows ->
                    if (!rows.next() || rows.getString(1) != database || rows.next()) throw SQLException("Migration target unavailable")
                }
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            return connection
        } catch (failure: Throwable) {
            try { connection.close() } catch (_: Throwable) { /* Preserve original failure; CLI never prints it. */ }
            throw failure
        }
    }
    override fun getConnection(username: String?, password: String?): Connection = throw SQLException("Migration credential override unavailable")
    override fun toString() = "CheckedMigrationDataSource(<redacted>)"
}
