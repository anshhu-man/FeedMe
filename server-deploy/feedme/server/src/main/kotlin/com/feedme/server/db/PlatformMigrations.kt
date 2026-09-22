package com.feedme.server.db

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.Collections
import javax.sql.DataSource

enum class PlatformMigrationInspectionStatus { CURRENT, PENDING, INCOMPATIBLE }

/** Packaged migration history only, not schema, data, application or deployment readiness. */
class PlatformMigrationInspection internal constructor(
    val status: PlatformMigrationInspectionStatus,
    appliedVersions: List<Int>,
    pendingVersions: List<Int>,
) {
    val appliedVersions: List<Int> = Collections.unmodifiableList(appliedVersions.toList())
    val pendingVersions: List<Int> = Collections.unmodifiableList(pendingVersions.toList())
    override fun toString() = "PlatformMigrationInspection(<redacted>)"
}

/**
 * Explicit, transactional platform migrations; never invoked by HTTP startup.
 * Add consecutive, immutable resources for compatible expansion. Backfills and
 * later constraint tightening need separate migrations; application rollback keeps
 * durable data and does not automatically reverse or delete the database schema.
 */
class PlatformMigrations(private val dataSource: DataSource) {
    /** Nonmutating inspection under the same transaction lock as migrate. The server-enforced
     * READ ONLY transaction always rolls back, even on a normal result. No history schema is
     * created, no migration is applied, and permission/query/cleanup failures are not a status.
     * READ COMMITTED refreshes the history snapshot after a concurrent migrator releases its
     * lock. Incompatible history deliberately returns no partially trusted version lists.
     */
    fun inspect(): PlatformMigrationInspection {
        val migrations = loadMigrations()
        val connection = try { dataSource.connection }
        catch (failure: Throwable) { preserveInterruption(failure); throw failure }
        var result: PlatformMigrationInspection? = null
        var failure: Throwable? = null
        fun retain(problem: Throwable) {
            preserveInterruption(problem)
            val previous = failure
            if (previous == null) failure = problem
            else if (previous !== problem) previous.addSuppressed(problem)
        }
        try {
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("SET TRANSACTION READ ONLY")
                statement.execute("SET LOCAL lock_timeout = '15s'")
                statement.execute("SET LOCAL statement_timeout = '30s'")
            }
            connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                statement.setLong(1, MIGRATION_LOCK)
                statement.execute()
            }
            val present = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT to_regclass('platform.schema_migrations') IS NOT NULL").use { rows ->
                    if (!rows.next()) throw SQLException("Platform history presence unavailable")
                    rows.getBoolean(1)
                }
            }
            result = try {
                val applied = if (present) validateHistory(connection, migrations) else 0
                PlatformMigrationInspection(
                    if (applied == migrations.size) PlatformMigrationInspectionStatus.CURRENT else PlatformMigrationInspectionStatus.PENDING,
                    migrations.take(applied).map { it.version }, migrations.drop(applied).map { it.version },
                )
            } catch (problem: HistoryIncompatible) {
                // Nested JDBC use blocks may attach a failed row/statement close to the
                // validation exception. A status is valid only after clean SQL cleanup.
                if (problem.suppressed.isNotEmpty()) throw problem
                PlatformMigrationInspection(PlatformMigrationInspectionStatus.INCOMPATIBLE, emptyList(), emptyList())
            }
        } catch (problem: Throwable) { retain(problem) }
        finally {
            // Cleanup failures cannot turn a failed check into a valid CURRENT/PENDING result.
            // Preserve the original failure and attempt both owned cleanup steps exactly once.
            try { connection.rollback() } catch (problem: Throwable) { retain(problem) }
            try { connection.close() } catch (problem: Throwable) { retain(problem) }
        }
        failure?.let { throw it }
        return checkNotNull(result)
    }

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
                        throw HistoryIncompatible("Unknown applied platform migration version: $version")
                    }
                    if (version != expected.version) {
                        throw HistoryIncompatible("Missing applied platform migration version: ${expected.version}")
                    }
                    if (rows.getString("checksum") != expected.checksum) {
                        throw HistoryIncompatible("Checksum mismatch for platform migration version: $version")
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

    private class HistoryIncompatible(message: String) : SQLException(message)

    private fun preserveInterruption(failure: Throwable) {
        if (failure is InterruptedException || failure.suppressed.any { it is InterruptedException }) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        // "FEEDME" plus a dedicated migration-lock suffix; shared by every runner.
        const val MIGRATION_LOCK = 0x464545444D450001L
        val RESOURCES = listOf("/db/migration/V001__durable_platform.sql", "/db/migration/V002__circle_memberships.sql", "/db/migration/V003__private_planning.sql", "/db/migration/V004__private_kitchen.sql", "/db/migration/V005__private_cooking.sql", "/db/migration/V006__private_saved_recipes.sql", "/db/migration/V007__owned_photo_media.sql", "/db/migration/V008__media_processing_jobs.sql", "/db/migration/V009__private_post_drafts.sql", "/db/migration/V010__post_publication.sql", "/db/migration/V011__account_profiles.sql", "/db/migration/V012__device_logout.sql", "/db/migration/V013__ingredient_catalog.sql", "/db/migration/V014__onboarding_decisions.sql", "/db/migration/V015__recipe_catalog.sql", "/db/migration/V016__recipe_catalog_history.sql", "/db/migration/V017__planning_manifests.sql", "/db/migration/V018__guest_sessions.sql", "/db/migration/V019__guest_planning_preparations.sql", "/db/migration/V020__manifest_plan_lineage.sql", "/db/migration/V021__recipe_copy_rights.sql", "/db/migration/V022__private_feedback.sql", "/db/migration/V023__make_again_actions.sql", "/db/migration/V024__preference_memories.sql", "/db/migration/V025__recipe_substitutions.sql", "/db/migration/V026__derived_plan_storage.sql", "/db/migration/V027__supabase_media_sources.sql", "/db/migration/V028__supabase_private_derivatives.sql", "/db/migration/V029__account_planning_windows.sql", "/db/migration/V030__account_device_reconnections.sql")
            .plus("/db/migration/V031__lock_only_key_guards.sql")
            .plus("/db/migration/V032__account_terms_acceptances.sql")
            .plus("/db/migration/V033__substitution_lock_key_guard.sql")
            .plus("/db/migration/V034__root_recipe_plan_storage.sql")
            .plus("/db/migration/V035__root_saved_plan_storage.sql")
            .plus("/db/migration/V036__account_block_relationships.sql")
            .plus("/db/migration/V037__media_safety_records.sql")
            .plus("/db/migration/V038__account_reports.sql")
            .plus("/db/migration/V039__oauth_device_reconnections.sql")
            .plus("/db/migration/V040__staff_publication_approvals.sql")
            .plus("/db/migration/V041__account_deletion_acceptance.sql")
            .plus("/db/migration/V042__outbox_ownership.sql")
            .plus("/db/migration/V043__account_erasure_work_leases.sql")
            .plus("/db/migration/V044__account_core_erasure.sql")
            .plus("/db/migration/V045__account_provider_erasure.sql")
            .plus("/db/migration/V046__account_manifest_erasure.sql")
            .plus("/db/migration/V047__account_provider_erasure_retries.sql")
            .plus("/db/migration/V048__account_draft_erasure.sql")
            .plus("/db/migration/V049__account_media_source_erasure.sql")
            .plus("/db/migration/V050__account_media_source_capture.sql")
            .plus("/db/migration/V051__account_reuse_proposals.sql")
            .plus("/db/migration/V052__account_direct_conversations.sql")
            .plus("/db/migration/V053__account_private_extension_erasure.sql")
            .plus("/db/migration/V054__private_recipe_requests.sql")
            .plus("/db/migration/V055__account_recipe_request_inventory.sql")
            .plus("/db/migration/V056__supabase_digest_readiness.sql")
            .plus("/db/migration/V057__targeted_account_session_revocation.sql")
            .plus("/db/migration/V058__session_and_media_erasure_inventory.sql")
            .plus("/db/migration/V059__account_notification_settings.sql")
            .plus("/db/migration/V060__notification_settings_erasure.sql")
            .plus("/db/migration/V061__root_post_plan_storage.sql")
            .plus("/db/migration/V062__post_recipe_saves.sql")
            .plus("/db/migration/V063__post_recipe_save_erasure_inventory.sql")
            .plus("/db/migration/V064__account_exports.sql")
            .plus("/db/migration/V065__account_export_erasure_inventory.sql")
            .plus("/db/migration/V066__account_make_again_actions.sql")
            .plus("/db/migration/V067__account_make_again_erasure.sql")
            .plus("/db/migration/V068__staff_recipe_drafts.sql")
            .plus("/db/migration/V069__staff_recipe_draft_erasure_inventory.sql")
            .plus("/db/migration/V070__staff_recipe_editorial_review.sql")
            .plus("/db/migration/V071__staff_recipe_review_erasure_inventory.sql")
            .plus("/db/migration/V072__staff_recipe_reviewer_qualifications.sql")
            .plus("/db/migration/V073__staff_recipe_catalog_lifecycle.sql")
            .plus("/db/migration/V074__staff_recipe_publication_erasure_inventory.sql")
            .plus("/db/migration/V075__account_notification_inbox.sql")
            .plus("/db/migration/V076__notification_inbox_erasure_inventory.sql")
            .plus("/db/migration/V077__staff_moderator_enrollments.sql")
            .plus("/db/migration/V078__staff_moderation_workflow.sql")
            .plus("/db/migration/V079__staff_moderation_erasure_inventory.sql")
            .plus("/db/migration/V080__staff_moderation_removal.sql")
            .plus("/db/migration/V081__staff_moderation_removal_erasure_inventory.sql")
            .plus("/db/migration/V082__post_placement_erasure_inventory.sql")
            .plus("/db/migration/V083__post_reactions.sql")
            .plus("/db/migration/V084__post_reaction_erasure_inventory.sql")
            .plus("/db/migration/V085__reaction_notifications.sql")
            .plus("/db/migration/V086__reaction_notification_erasure_inventory.sql")
            .plus("/db/migration/V087__staff_moderation_audit_reader.sql")
            .plus("/db/migration/V088__reaction_actor_erasure.sql")
    }
}
