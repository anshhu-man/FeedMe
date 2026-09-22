package com.feedme.server.social

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only admission for the incremental safety dependencies, not a role installer,
 * full effective-ACL/trigger attestation or per-user authorization. No domain row is read,
 * no sequence value is allocated, and no grant/schema repair is attempted. Core provider,
 * identity, receipt and outbox compatibility remains the owning runtime's responsibility.
 * The configured safety runtime must not report available with row-filtering RLS or missing
 * privileges. Recheck during health observation because grants may change after startup. */
internal object AccountBlockCompatibility {
    fun check(connection: Connection) {
        try {
            require(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            connection.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=36").use {
                it.executeQuery().use { rows ->
                    required(rows.next() && rows.getString(1) == checksum && !rows.next())
                }
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_catalog.has_schema_privilege(current_user,'social','USAGE')," +
                    "pg_catalog.has_schema_privilege(current_user,'identity','USAGE')").use {
                    required(it.next() && it.getBoolean(1) && it.getBoolean(2) && !it.next())
                }
            }
            table(connection, "social", "blocks", blocks, blocks.keys,
                setOf("version", "active", "last_command_key", "updated_at"), exactColumns = true)
            table(connection, "social", "block_pairs", pairs, pairs.keys,
                setOf("version", "deny_invites_through"), exactColumns = true)
            // Target lifecycle reads take FOR SHARE NOWAIT. PostgreSQL also requires
            // UPDATE on at least one column for a row lock; use the existing core
            // version privilege, never grant a broader identity mutation capability.
            table(connection, "identity", "users", mapOf("environment" to "varchar", "id" to "uuid",
                "status" to "varchar", "version" to "int8"), emptySet(), setOf("version"), exactColumns = false)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT c.relkind,s.seqtypid='pg_catalog.int8'::pg_catalog.regtype," +
                    "s.seqincrement=1,s.seqmin=1,s.seqmax=9223372036854775807,NOT s.seqcycle," +
                    "pg_catalog.has_sequence_privilege(current_user,c.oid,'USAGE') " +
                    "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace " +
                    "JOIN pg_catalog.pg_sequence s ON s.seqrelid=c.oid " +
                    "WHERE n.nspname='social' AND c.relname='relationship_order'").use { rows ->
                    required(rows.next() && rows.getString(1) == "S" && (2..7).all(rows::getBoolean) && !rows.next())
                }
            }
        } catch (failure: BlockFailure) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { unavailable() }
    }

    private fun table(c: Connection, schema: String, name: String, columns: Map<String, String>,
        inserts: Set<String>, updates: Set<String>, exactColumns: Boolean) {
        val oid = c.prepareStatement("SELECT t.oid,t.relkind,t.relrowsecurity,t.relforcerowsecurity," +
            "NOT t.relrowsecurity OR r.rolsuper OR r.rolbypassrls OR (t.relowner=r.oid AND NOT t.relforcerowsecurity)," +
            "EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=t.oid OR i.inhparent=t.oid) " +
            "FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace " +
            "JOIN pg_catalog.pg_roles r ON r.rolname=current_user WHERE n.nspname=? AND t.relname=?").use { statement ->
            statement.setString(1, schema); statement.setString(2, name)
            statement.executeQuery().use { rows ->
                required(rows.next() && rows.getString(2) == "r" && rows.getBoolean(5) && !rows.getBoolean(6))
                if (exactColumns) required(rows.getBoolean(3) && rows.getBoolean(4))
                rows.getLong(1).also { required(!rows.next()) }
            }
        }
        val actual = c.prepareStatement("SELECT a.attname,t.typname,n.nspname," +
            "pg_catalog.has_column_privilege(current_user,a.attrelid,a.attnum,'SELECT')," +
            "pg_catalog.has_column_privilege(current_user,a.attrelid,a.attnum,'INSERT')," +
            "pg_catalog.has_column_privilege(current_user,a.attrelid,a.attnum,'UPDATE') " +
            "FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid " +
            "JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace WHERE a.attrelid=?::oid AND a.attnum>0 AND NOT a.attisdropped").use { statement ->
            statement.setLong(1, oid)
            statement.executeQuery().use { rows -> buildSet {
                while (rows.next()) {
                    val column = rows.getString(1)
                    add(column)
                    if (column in columns) {
                        required(rows.getString(2) == columns.getValue(column) && rows.getString(3) == "pg_catalog" && rows.getBoolean(4))
                        if (column in inserts) required(rows.getBoolean(5))
                        if (column in updates) required(rows.getBoolean(6))
                    }
                }
            } }
        }
        required(if (exactColumns) actual == columns.keys else actual.containsAll(columns.keys))
    }

    private val blocks = mapOf("environment" to "varchar", "owner_user_id" to "uuid", "target_user_id" to "uuid",
        "id" to "uuid", "version" to "int8", "active" to "bool", "last_command_key" to "uuid",
        "created_at" to "timestamptz", "updated_at" to "timestamptz")
    private val pairs = mapOf("environment" to "varchar", "user_low" to "uuid", "user_high" to "uuid",
        "version" to "int8", "deny_invites_through" to "int8")
    private val checksum by lazy {
        AccountBlockCompatibility::class.java.getResourceAsStream("/db/migration/V036__account_block_relationships.sql")?.use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        } ?: unavailable()
    }
    private fun required(value: Boolean) { if (!value) unavailable() }
    private fun unavailable(): Nothing = throw BlockFailure(BlockFailureCode.NOT_CONFIGURED)
}
