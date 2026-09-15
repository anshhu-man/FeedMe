package com.feedme.server.db

import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout

class PlatformMigrationsIntegrationTest {
    @Test(timeout = 15_000)
    fun concurrentMigratorsApplyTheFreshDatabaseExactlyOnce() {
        val dataSource = cluster.database()
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val racingDataSource = object : DataSource by dataSource {
            override fun getConnection(): Connection {
                val connection = dataSource.connection
                try {
                    // Exercise the isolation level a pool may supply, as well as first-run DDL.
                    connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                    connection.createStatement().use { it.execute("SET statement_timeout = '5s'") }
                    ready.countDown()
                    check(start.await(3, TimeUnit.SECONDS)) { "Concurrent migration start was not released." }
                    return connection
                } catch (failure: Throwable) {
                    connection.close()
                    throw failure
                }
            }
        }
        val executor = Executors.newFixedThreadPool(4)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val futures = (1..4).map {
            executor.submit<Unit> { PlatformMigrations(racingDataSource).migrate() }
        }
        try {
            assertTrue(ready.await(3, TimeUnit.SECONDS), "All four migrators must connect before starting.")
            start.countDown()
            futures.forEach { future ->
                future.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
            }
        } finally {
            start.countDown()
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "Migration workers must terminate.")
        }

        assertEquals(
            listOf(listOf("1", "durable_platform", migrationChecksum()),
                listOf("2", "circle_memberships", migrationChecksum("V002__circle_memberships.sql")),
                listOf("3", "private_planning", migrationChecksum("V003__private_planning.sql")),
                listOf("4", "private_kitchen", migrationChecksum("V004__private_kitchen.sql")),
                listOf("5", "private_cooking", migrationChecksum("V005__private_cooking.sql")),
                listOf("6", "private_saved_recipes", migrationChecksum("V006__private_saved_recipes.sql")),
                listOf("7", "owned_photo_media", migrationChecksum("V007__owned_photo_media.sql")),
                listOf("8", "media_processing_jobs", migrationChecksum("V008__media_processing_jobs.sql")),
                listOf("9", "private_post_drafts", migrationChecksum("V009__private_post_drafts.sql")),
                listOf("10", "post_publication", migrationChecksum("V010__post_publication.sql"))),
            query(dataSource, "SELECT version, description, checksum FROM platform.schema_migrations ORDER BY version"),
        )
        assertEquals(EXPECTED_TABLES, tableNames(dataSource))
        insertFixtures(dataSource)
        assertTrue(platformRows(dataSource).values.all { it.size == 1 })
    }

    @Test(timeout = 15_000)
    fun repeatedMigrationsPreserveHistoryDataTimestampsAndDatabaseObjects() {
        val dataSource = cluster.database()
        PlatformMigrations(dataSource).migrate()
        insertFixtures(dataSource)
        val before = snapshot(dataSource)

        repeat(3) { PlatformMigrations(dataSource).migrate() }

        assertEquals(before, snapshot(dataSource))
        assertEquals(EXPECTED_TABLES, tableNames(dataSource))
    }

    @Test(timeout = 15_000)
    fun changedAppliedChecksumIsRejectedWithoutChangingTheDatabase() {
        val dataSource = cluster.database()
        PlatformMigrations(dataSource).migrate()
        insertFixtures(dataSource)
        execute(dataSource, "UPDATE platform.schema_migrations SET checksum = repeat('0', 64) WHERE version = 1")
        val before = snapshot(dataSource)

        val failure = assertFailsWith<SQLException> { PlatformMigrations(dataSource).migrate() }

        assertEquals("Checksum mismatch for platform migration version: 1", failure.message)
        assertEquals(before, snapshot(dataSource))
    }

    @Test(timeout = 15_000)
    fun unknownAppliedVersionIsRejectedWithoutChangingTheDatabase() {
        val dataSource = cluster.database()
        PlatformMigrations(dataSource).migrate()
        insertFixtures(dataSource)
        execute(
            dataSource,
            """
            INSERT INTO platform.schema_migrations (version, description, checksum)
            VALUES (999, 'unexpected_future_migration', repeat('0', 64))
            """.trimIndent(),
        )
        val before = snapshot(dataSource)

        val failure = assertFailsWith<SQLException> { PlatformMigrations(dataSource).migrate() }

        assertEquals("Unknown applied platform migration version: 999", failure.message)
        assertEquals(before, snapshot(dataSource))
    }

    @Test(timeout = 15_000)
    fun failedDdlRollsBackHistoryAndEarlierObjectsButPreservesTheConflictingTable() {
        val dataSource = cluster.database()
        execute(
            dataSource,
            """
            CREATE SCHEMA platform;
            CREATE TABLE platform.outbox (marker text PRIMARY KEY);
            INSERT INTO platform.outbox (marker) VALUES ('preexisting synthetic fixture');
            """.trimIndent(),
        )
        val objectsBefore = catalogObjects(dataSource)

        val failure = assertFailsWith<SQLException> { PlatformMigrations(dataSource).migrate() }

        assertEquals("42P07", failure.sqlState, "The existing outbox must cause a duplicate-table DDL failure.")
        assertEquals(setOf("outbox"), tableNames(dataSource))
        assertEquals(
            listOf(listOf(null, null, null, null)),
            query(
                dataSource,
                """
                SELECT to_regclass('platform.schema_migrations')::text,
                       to_regclass('platform.idempotency')::text,
                       to_regclass('platform.idempotency_completed_expiry_idx')::text,
                       to_regclass('platform.consumer_inbox')::text
                """.trimIndent(),
            ),
        )
        assertEquals(objectsBefore, catalogObjects(dataSource))
        assertEquals(listOf(listOf("preexisting synthetic fixture")), query(dataSource, "SELECT marker FROM platform.outbox"))
    }

    private fun insertFixtures(dataSource: DataSource) {
        execute(
            dataSource,
            """
            INSERT INTO platform.idempotency
                (principal_scope, operation_id, key, request_hash, state, response_code, response_json,
                 response_etag, expires_at)
            VALUES ('migration-test', 'create-fixture', '00000000-0000-0000-0000-000000000001',
                    repeat('a', 64), 'completed', 201, '{"fixture":true}', '"fixture-v1"',
                    clock_timestamp() + interval '1 day');
            INSERT INTO platform.outbox
                (event_id, event_type, schema_version, aggregate_type, aggregate_id, aggregate_version,
                 producer, correlation_id, causation_id, payload)
            VALUES ('00000000-0000-0000-0000-000000000002', 'fixture.created', 1, 'fixture',
                    '00000000-0000-0000-0000-000000000003', 1, 'migration-test', 'synthetic-correlation',
                    '00000000-0000-0000-0000-000000000004', '{"fixture":true}');
            INSERT INTO platform.consumer_inbox (consumer_name, event_id)
            VALUES ('migration-test', '00000000-0000-0000-0000-000000000002');
            """.trimIndent(),
        )
    }

    private fun snapshot(dataSource: DataSource) = DatabaseSnapshot(
        history = query(
            dataSource,
            "SELECT version, description, checksum, installed_at::text FROM platform.schema_migrations ORDER BY version",
        ),
        rows = platformRows(dataSource),
        objects = catalogObjects(dataSource),
    )

    private fun platformRows(dataSource: DataSource): Map<String, List<List<String?>>> =
        listOf("idempotency", "outbox", "consumer_inbox").associateWith { table ->
            // JSON includes every column, including all timestamps and nullable state fields.
            query(dataSource, "SELECT to_jsonb(record)::text FROM platform.$table AS record ORDER BY to_jsonb(record)::text")
        }

    private fun tableNames(dataSource: DataSource): Set<String?> = query(
        dataSource,
        "SELECT tablename FROM pg_tables WHERE schemaname = 'platform' ORDER BY tablename",
    ).map { it.single() }.toSet()

    private fun catalogObjects(dataSource: DataSource): List<List<String?>> = query(
        dataSource,
        """
        SELECT 'schema' AS kind, n.oid::text AS identity, n.nspname AS name, '' AS definition
        FROM pg_namespace n WHERE n.nspname = 'platform'
        UNION ALL
        SELECT 'relation', c.oid::text, c.relname,
               c.relkind::text || ':' || c.relfilenode::text || ':' ||
               CASE WHEN c.relkind = 'i' THEN pg_get_indexdef(c.oid) ELSE '' END
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'platform'
        UNION ALL
        SELECT 'column', a.attrelid::text || ':' || a.attnum::text, c.relname || '.' || a.attname,
               format_type(a.atttypid, a.atttypmod) || ':' || a.attnotnull::text || ':' ||
               coalesce(pg_get_expr(d.adbin, d.adrelid), '')
        FROM pg_attribute a
        JOIN pg_class c ON c.oid = a.attrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
        WHERE n.nspname = 'platform' AND a.attnum > 0 AND NOT a.attisdropped
        UNION ALL
        SELECT 'constraint', con.oid::text, c.relname || '.' || con.conname, pg_get_constraintdef(con.oid)
        FROM pg_constraint con
        JOIN pg_class c ON c.oid = con.conrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'platform'
        ORDER BY kind, name, identity
        """.trimIndent(),
    )

    private fun query(dataSource: DataSource, sql: String): List<List<String?>> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = 5
                statement.executeQuery(sql).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add((1..rows.metaData.columnCount).map { rows.getString(it) })
                        }
                    }
                }
            }
        }

    private fun execute(dataSource: DataSource, sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = 5
                statement.execute(sql)
            }
        }
    }

    private fun migrationChecksum(name: String = "V001__durable_platform.sql"): String {
        val resource = PlatformMigrations::class.java.getResourceAsStream("/db/migration/$name")
        val bytes = assertNotNull(resource, "The actual migration resource must be on the test classpath.").use { it.readBytes() }
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class DatabaseSnapshot(
        val history: List<List<String?>>,
        val rows: Map<String, List<List<String?>>>,
        val objects: List<List<String?>>,
    )

    companion object {
        private val EXPECTED_TABLES = setOf("schema_migrations", "idempotency", "outbox", "consumer_inbox",
            "media_draft_lifecycles", "media_assets", "media_cleanup_jobs", "media_processing_jobs", "media_processing_inbox",
            "media_derivative_intents", "media_processing_cleanup", "post_draft_heads", "post_drafts", "post_draft_discard_media")
        private lateinit var cluster: PostgresTestCluster

        @ClassRule
        @JvmField
        val suiteTimeout: Timeout = Timeout(8, TimeUnit.MINUTES)

        @BeforeClass
        @JvmStatic
        fun startPostgres() {
            cluster = PostgresTestCluster.start()
        }

        @AfterClass
        @JvmStatic
        fun stopPostgres() {
            if (::cluster.isInitialized) cluster.close()
        }
    }
}
