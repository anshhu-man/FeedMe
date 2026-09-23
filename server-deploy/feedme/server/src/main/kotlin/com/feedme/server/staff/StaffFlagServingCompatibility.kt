package com.feedme.server.staff

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Exact read-only compatibility probe for V091 and its narrow serving grants.
 * It repairs nothing and creates no flag or authority. */
internal object StaffFlagServingCompatibility {
    fun check(c: Connection) {
        try {
            require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Feature-flag compatibility interrupted")
            c.prepareStatement("SELECT description,checksum FROM platform.schema_migrations WHERE version=91").use { s ->
                s.executeQuery().use { r -> check(r.next() && r.getString(1) == "restrictive_staff_feature_flags" &&
                    r.getString(2) == hash(source) && !r.next()) }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT NOT rolsuper AND rolbypassrls AND current_setting('session_replication_role')='origin' " +
                    "FROM pg_catalog.pg_roles WHERE rolname=current_user").use { r ->
                    check(r.next() && r.getBoolean(1) && !r.next())
                }
                for (table in listOf("platform.feature_flags", "platform.feature_flag_actions")) {
                    c.prepareStatement("SELECT relkind='r' AND relrowsecurity AND relforcerowsecurity " +
                        "AND relowner<>(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user) " +
                        "AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid=f.oid OR inhparent=f.oid) " +
                        "FROM pg_catalog.pg_class f WHERE oid=to_regclass(?)").use { p ->
                        p.setString(1, table); p.executeQuery().use { r -> check(r.next() && r.getBoolean(1) && !r.next()) }
                    }
                    s.executeQuery("SELECT * FROM $table WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
                }
            }
            rights(c, "platform.feature_flags", "UPDATE", "enabled,rollout_percent,revision,last_action_id,updated_at")
            rights(c, "platform.feature_flag_actions", "INSERT", "environment,id,flag_key,flag_revision,actor_id," +
                "provider_session_id,authority_revision,operation_id,command_key,request_sha256,request_text,if_match," +
                "reason,response_text,response_sha256,event_id,trace_id,created_at")
            rights(c, "platform.feature_flag_actions", "UPDATE", "id")
            c.prepareStatement("SELECT NOT has_table_privilege(current_user,?,'INSERT,DELETE,TRUNCATE,TRIGGER," +
                "SELECT WITH GRANT OPTION,UPDATE WITH GRANT OPTION')").use { s ->
                s.setString(1, "platform.feature_flags"); s.executeQuery().use { r -> check(r.next() && r.getBoolean(1) && !r.next()) }
            }
            c.prepareStatement("SELECT NOT has_table_privilege(current_user,?,'UPDATE,DELETE,TRUNCATE,TRIGGER," +
                "INSERT WITH GRANT OPTION,SELECT WITH GRANT OPTION')").use { s ->
                s.setString(1, "platform.feature_flag_actions"); s.executeQuery().use { r -> check(r.next() && r.getBoolean(1) && !r.next()) }
            }
            for ((table, expected) in triggers) c.prepareStatement("SELECT tgname,tgenabled::text,tgfoid::regprocedure::text," +
                "tgdeferrable,tginitdeferred FROM pg_catalog.pg_trigger WHERE tgrelid=to_regclass(?) " +
                "AND NOT tgisinternal AND tgname=ANY(?::text[])").use { s ->
                s.setString(1, table); s.setArray(2, c.createArrayOf("text", expected.keys.toTypedArray()))
                s.executeQuery().use { r ->
                    val found = mutableSetOf<String>()
                    while (r.next()) {
                        val name = r.getString(1); val function = expected[name] ?: error("Unexpected feature-flag guard")
                        val deferred = name.endsWith("checkpoint")
                        check(found.add(name) && r.getString(2) in setOf("O", "A") &&
                            r.getString(3) == "platform.$function()" && r.getBoolean(4) == deferred && r.getBoolean(5) == deferred)
                    }
                    check(found == expected.keys)
                }
            }
        } catch (e: StaffModerationFailure) { throw e }
          catch (e: CancellationException) { throw e }
          catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
          catch (_: Exception) { throw StaffModerationFailure(StaffModerationFailureCode.NOT_CONFIGURED) }
    }

    private fun rights(c: Connection, table: String, privilege: String, columns: String) {
        c.prepareStatement("SELECT bool_and(has_column_privilege(current_user,?::text,column_name,?::text)) " +
            "FROM unnest(string_to_array(?::text,',')) AS names(column_name)").use { s ->
            s.setString(1, table); s.setString(2, privilege); s.setString(3, columns)
            s.executeQuery().use { r -> check(r.next() && r.getBoolean(1) && !r.next()) }
        }
    }

    private val triggers = mapOf(
        "platform.feature_flags" to mapOf("feature_flag_guard" to "guard_feature_flag",
            "feature_flag_no_truncate" to "guard_feature_flag",
            "feature_flag_transition_checkpoint" to "require_feature_flag_action"),
        "platform.feature_flag_actions" to mapOf("feature_flag_action_immutable" to "protect_feature_flag_action",
            "feature_flag_action_no_truncate" to "protect_feature_flag_action",
            "feature_flag_action_checkpoint" to "require_feature_flag_action"),
    )
    private val source by lazy { checkNotNull(javaClass.getResourceAsStream(
        "/db/migration/V091__restrictive_staff_feature_flags.sql")).use { it.readBytes().decodeToString() } }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
