package com.feedme.server.db

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Explicit, transactional platform migrations; never invoked by HTTP startup.
 * Add consecutive, immutable resources for compatible expansion. Backfills and
 * later constraint tightening need separate migrations; application rollback keeps
 * durable data and does not automatically reverse or delete the database schema.
 */
class PlatformMigrations(private val dataSource: DataSource) {
    fun migrate() {
        val migrations = loadMigrations()
        dataSource.connection.use { connection ->
            // Refresh visibility after acquiring the lock even if a pool defaults
            // to repeatable-read isolation and another migrator was ahead of us.
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute("SET LOCAL lock_timeout = '15s'")
                    statement.execute("SET LOCAL statement_timeout = '30s'")
                }
                // A transaction lock also covers first-run schema creation. Concurrent
                // processes wait here and then read the preceding process's commit.
                connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                    statement.setLong(1, MIGRATION_LOCK)
                    statement.execute()
                }
                createHistory(connection)
                val appliedCount = validateHistory(connection, migrations)
                for (migration in migrations.drop(appliedCount)) {
                    connection.createStatement().use { it.execute(migration.sql) }
                    connection.prepareStatement(
                        "INSERT INTO platform.schema_migrations (version, description, checksum) VALUES (?, ?, ?)",
                    ).use { statement ->
                        statement.setInt(1, migration.version)
                        statement.setString(2, migration.description)
                        statement.setString(3, migration.checksum)
                        statement.executeUpdate()
                    }
                }
                connection.commit()
            } catch (failure: Throwable) {
                try {
                    connection.rollback()
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
        }
    }

    private fun createHistory(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA IF NOT EXISTS platform")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS platform.schema_migrations (
                    version integer PRIMARY KEY CHECK (version > 0),
                    description varchar(200) NOT NULL CHECK (btrim(description) <> ''),
                    checksum char(64) NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
                    installed_at timestamptz NOT NULL DEFAULT clock_timestamp()
                )
                """.trimIndent(),
            )
        }
    }

    private fun validateHistory(connection: Connection, migrations: List<Migration>): Int {
        var appliedCount = 0
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT version, checksum FROM platform.schema_migrations ORDER BY version",
            ).use { rows ->
                while (rows.next()) {
                    val version = rows.getInt("version")
                    val expected = migrations.getOrNull(appliedCount)
                    if (expected == null || version !in 1..migrations.size) {
                        throw SQLException("Unknown applied platform migration version: $version")
                    }
                    if (version != expected.version) {
                        throw SQLException("Missing applied platform migration version: ${expected.version}")
                    }
                    if (rows.getString("checksum") != expected.checksum) {
                        throw SQLException("Checksum mismatch for platform migration version: $version")
                    }
                    appliedCount++
                }
            }
        }
        return appliedCount
    }

    private fun loadMigrations(): List<Migration> = RESOURCES.mapIndexed { index, resource ->
        val version = index + 1
        val prefix = "V${version.toString().padStart(3, '0')}__"
        val fileName = resource.substringAfterLast('/')
        if (!fileName.startsWith(prefix) || !fileName.endsWith(".sql")) {
            throw SQLException("Platform migration resources must have contiguous versions starting at 1")
        }
        val bytes = PlatformMigrations::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw SQLException("Missing platform migration resource: $resource")
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        Migration(
            version = version,
            description = fileName.removePrefix(prefix).removeSuffix(".sql"),
            checksum = checksum,
            sql = String(bytes, StandardCharsets.UTF_8),
        )
    }

    private data class Migration(val version: Int, val description: String, val checksum: String, val sql: String)

    private companion object {
        // "FEEDME" plus a dedicated migration-lock suffix; shared by every runner.
        const val MIGRATION_LOCK = 0x464545444D450001L
        val RESOURCES = listOf("/db/migration/V001__durable_platform.sql", "/db/migration/V002__circle_memberships.sql", "/db/migration/V003__private_planning.sql", "/db/migration/V004__private_kitchen.sql", "/db/migration/V005__private_cooking.sql", "/db/migration/V006__private_saved_recipes.sql", "/db/migration/V007__owned_photo_media.sql", "/db/migration/V008__media_processing_jobs.sql", "/db/migration/V009__private_post_drafts.sql", "/db/migration/V010__post_publication.sql")
    }
}
