package com.feedme.server.memory

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only activation probe. Does not install schema, grants, RLS policies or a
 * database role. In addition to existing account Save/feedback/planning rights:
 * SELECT on this ledger, INSERT on its listed written fields, UPDATE(parent_key)
 * solely for PostgreSQL FOR SHARE admission, and existing SELECT/locking rights on
 * each retained child/receipt relation below. The immutable trigger rejects actual
 * updates. No DELETE or TRUNCATE right is needed by the serving role. */
internal object AccountMakeAgainServingCompatibility {
    fun check(c: Connection) {
        try {
            require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Account Make Again interrupted")
            for ((version, name) in migrations) c.prepareStatement(
                "SELECT description,checksum FROM platform.schema_migrations WHERE version=?").use { s ->
                s.setInt(1, version); s.executeQuery().use { r ->
                    check(r.next() && r.getString(1) == name && r.getString(2) == checksums.getValue(version) && !r.next())
                }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT rolsuper OR rolbypassrls,current_setting('session_replication_role')='origin' " +
                    "FROM pg_catalog.pg_roles WHERE rolname=current_user").use { r ->
                    check(r.next() && r.getBoolean(1) && r.getBoolean(2) && !r.next())
                }
                s.executeQuery("SELECT relkind,relrowsecurity,relforcerowsecurity," +
                    "EXISTS(SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid=c.oid OR inhparent=c.oid) " +
                    "FROM pg_catalog.pg_class c WHERE c.oid=pg_catalog.to_regclass('memory.account_make_again_actions')").use { r ->
                    check(r.next() && r.getString(1) == "r" && r.getBoolean(2) && r.getBoolean(3) && !r.getBoolean(4) && !r.next())
                }
                // FOR SHARE asks for lock permission even though no rows are selected.
                for (table in readTables) s.executeQuery("SELECT to_jsonb(r)::text FROM ONLY $table r WHERE false FOR SHARE").close()
            }
            c.prepareStatement("SELECT a.attname,n.nspname||'.'||t.typname,a.attnotnull FROM pg_catalog.pg_attribute a " +
                "JOIN pg_catalog.pg_type t ON t.oid=a.atttypid JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace " +
                "WHERE a.attrelid='memory.account_make_again_actions'::regclass AND a.attnum>0 AND NOT a.attisdropped").use { s ->
                s.executeQuery().use { r ->
                    val actual = mutableMapOf<String, String>()
                    while (r.next()) { check(r.getBoolean(3)); actual[r.getString(1)] = r.getString(2) }
                    check(actual == columns)
                }
            }
            for (column in writtenColumns) c.prepareStatement(
                "SELECT has_column_privilege(current_user,'memory.account_make_again_actions',?,'INSERT')").use { s ->
                s.setString(1, column); s.executeQuery().use { r -> check(r.next() && r.getBoolean(1) && !r.next()) }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT tgname,tgenabled::text,tgfoid='memory.guard_account_make_again_action()'::regprocedure " +
                    "FROM pg_catalog.pg_trigger WHERE tgrelid='memory.account_make_again_actions'::regclass AND NOT tgisinternal").use { r ->
                    val observed = mutableSetOf<String>()
                    while (r.next()) { check(r.getString(2) in setOf("O", "A") && r.getBoolean(3)); observed += r.getString(1) }
                    check(observed == setOf("account_make_again_action_guard", "account_make_again_action_retained"))
                }
            }
        } catch (f: CancellationException) { throw f }
          catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
          catch (_: Exception) { throw SavedRecipeFailure(SavedRecipeFailureCode.NOT_CONFIGURED) }
    }

    private val migrations = mapOf(66 to "account_make_again_actions", 67 to "account_make_again_erasure")
    private val checksums by lazy { migrations.mapValues { (version, name) ->
        checkNotNull(javaClass.getResourceAsStream("/db/migration/V${version.toString().padStart(3, '0')}__$name.sql")).use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
        }
    } }
    private val readTables = listOf("memory.account_make_again_actions", "memory.saved_recipes", "memory.save_commands",
        "memory.collections", "memory.collection_items", "memory.feedback", "memory.feedback_commands", "platform.idempotency")
    private val writtenColumns = listOf("environment", "actor_kind", "principal_id", "principal_scope", "parent_operation", "parent_key",
        "parent_request_hash", "saved_recipe_id", "saved_generation", "saved_version", "saved_snapshot_sha256", "created_save",
        "save_key", "save_request_hash", "feedback_id", "feedback_key", "feedback_context_sha256", "feedback_request_hash",
        "feedback_snapshot_sha256", "feedback_provenance_sha256")
    private val columns = buildMap {
        for (column in listOf("environment", "actor_kind", "principal_scope", "parent_operation", "save_operation", "feedback_operation"))
            put(column, "pg_catalog.varchar")
        for (column in listOf("principal_id", "parent_key", "saved_recipe_id", "save_key", "feedback_id", "feedback_key"))
            put(column, "pg_catalog.uuid")
        for (column in listOf("parent_request_hash", "saved_snapshot_sha256", "save_request_hash", "feedback_context_sha256",
            "feedback_request_hash", "feedback_snapshot_sha256", "feedback_provenance_sha256")) put(column, "pg_catalog.bpchar")
        for (column in listOf("saved_generation", "saved_version", "feedback_version")) put(column, "pg_catalog.int8")
        put("created_save", "pg_catalog.bool"); put("created_at", "pg_catalog.timestamptz")
    }
}
