package com.feedme.server.staff

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only opt-in serving probe. No grants or repairs. Empty reads exercise the
 * invoker/deferred checkpoints as well as the store, without disclosing a case. */
internal object StaffModerationServingCompatibility {
    fun check(c: Connection) {
        try {
            require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Moderation compatibility interrupted")
            c.prepareStatement("SELECT description,checksum FROM platform.schema_migrations WHERE version=78").use { s ->
                s.executeQuery().use { r -> check(r.next() && r.getString(1) == "staff_moderation_workflow" && r.getString(2) == hash(source) && !r.next()) }
            }
            c.prepareStatement("SELECT description,checksum FROM platform.schema_migrations WHERE version=80").use { s ->
                s.executeQuery().use { r -> check(r.next() && r.getString(1) == "staff_moderation_removal" && r.getString(2) == hash(removalSource) && !r.next()) }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT NOT rolsuper AND rolbypassrls AND current_setting('session_replication_role')='origin' FROM pg_catalog.pg_roles WHERE rolname=current_user").use {
                    check(it.next() && it.getBoolean(1) && !it.next())
                }
                for (table in listOf("safety.moderation_cases", "safety.reports", "safety.report_evidence", "safety.moderation_actions", "safety.moderation_access_audit", "safety.moderation_removals")) {
                    c.prepareStatement("SELECT relkind='r' AND relrowsecurity AND relforcerowsecurity AND relowner<>(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user) " +
                        "AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid=m.oid OR inhparent=m.oid) FROM pg_catalog.pg_class m WHERE oid=to_regclass(?)").use { p ->
                        p.setString(1, table); p.executeQuery().use { r -> check(r.next() && r.getBoolean(1) && !r.next()) }
                    }
                    s.executeQuery("SELECT * FROM $table WHERE false" + if (table.endsWith("access_audit")) "" else " FOR SHARE NOWAIT").use { check(!it.next()) }
                }
                s.executeQuery("SELECT * FROM platform.idempotency WHERE false FOR UPDATE NOWAIT").use { check(!it.next()) }
                s.executeQuery("SELECT * FROM platform.outbox WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
                s.executeQuery("SELECT * FROM social.posts WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
                s.executeQuery("SELECT * FROM social.thread_messages WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
            }
            rights(c, "safety.moderation_cases", "UPDATE", "version,status,assignee_staff_id,reason_code,updated_at,action")
            rights(c, "safety.reports", "UPDATE", "version,status,updated_at")
            rights(c, "safety.moderation_actions", "INSERT", "environment,id,case_id,report_id,case_version,report_version,actor_id,provider_session_id,authority_revision,action,reason_code,notes,operation_id,command_key,request_sha256,request_text,if_match,response_text,response_sha256,event_id,trace_id,created_at,target_version")
            rights(c, "safety.moderation_removals", "INSERT", "environment,id,case_id,report_id,action_id,target_type,target_id,target_owner_id,target_version,target_sha256,state,created_at")
            rights(c, "safety.moderation_access_audit", "INSERT", "environment,id,actor_id,provider_session_id,authority_revision,purpose,observed_cases,trace_id,created_at")
            rights(c, "platform.idempotency", "INSERT", "principal_scope,operation_id,key,request_hash,state,expires_at")
            rights(c, "platform.idempotency", "UPDATE", "state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at")
            rights(c, "platform.outbox", "INSERT", "event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id")
            for (table in listOf("safety.moderation_actions", "safety.moderation_access_audit", "safety.moderation_removals")) {
                c.prepareStatement("SELECT NOT has_table_privilege(current_user,?,'DELETE,TRUNCATE,TRIGGER,INSERT WITH GRANT OPTION,SELECT WITH GRANT OPTION,UPDATE WITH GRANT OPTION')").use { s ->
                    s.setString(1, table); s.executeQuery().use { r -> check(r.next() && r.getBoolean(1) && !r.next()) }
                }
            }
            val bodies = listOf("protect_moderation_audit", "require_moderation_action", "require_moderation_transition", "require_moderation_removal")
            for (name in bodies) c.prepareStatement("SELECT p.prosrc,p.prosecdef,p.prorettype='trigger'::regtype,l.lanname " +
                "FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang WHERE p.oid=to_regprocedure(?)").use { s ->
                s.setString(1, "safety.$name()"); s.executeQuery().use { r ->
                    val selectedSource = if (name == "protect_moderation_audit") source else removalSource
                    val body = selectedSource.substringAfter("FUNCTION safety.$name() RETURNS trigger LANGUAGE plpgsql AS \$\$").substringBefore("\$\$;")
                    check(r.next() && r.getString(1) == body && !r.getBoolean(2) && r.getBoolean(3) && r.getString(4) == "plpgsql" && !r.next())
                }
            }
            for ((table, expected) in triggers) c.prepareStatement("SELECT tgname,tgenabled::text,tgfoid::regprocedure::text,tgdeferrable,tginitdeferred " +
                "FROM pg_catalog.pg_trigger WHERE tgrelid=to_regclass(?) AND NOT tgisinternal AND tgname=ANY(?::text[])").use { s ->
                s.setString(1, table); s.setArray(2, c.createArrayOf("text", expected.keys.toTypedArray()))
                s.executeQuery().use { r ->
                    val found = mutableSetOf<String>()
                    while (r.next()) {
                        val name = r.getString(1); val function = expected[name] ?: error("Unexpected moderation guard")
                        val deferred = name.endsWith("checkpoint")
                        check(found.add(name) && r.getString(2) in setOf("O", "A") && r.getString(3) == "safety.$function()" &&
                            r.getBoolean(4) == deferred && r.getBoolean(5) == deferred)
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
        c.prepareStatement("SELECT bool_and(has_column_privilege(current_user,?::text,column_name,?::text)) FROM unnest(string_to_array(?::text,',')) AS names(column_name)").use { s ->
            s.setString(1, table); s.setString(2, privilege); s.setString(3, columns)
            s.executeQuery().use { r -> check(r.next() && r.getBoolean(1) && !r.next()) }
        }
    }
    private val triggers = mapOf(
        "safety.moderation_access_audit" to mapOf("moderation_access_immutable" to "protect_moderation_audit", "moderation_access_no_truncate" to "protect_moderation_audit"),
        "safety.moderation_actions" to mapOf("moderation_action_immutable" to "protect_moderation_audit", "moderation_action_no_truncate" to "protect_moderation_audit", "moderation_action_checkpoint" to "require_moderation_action"),
        "safety.reports" to mapOf("report_moderation_checkpoint" to "require_moderation_transition"),
        "safety.moderation_cases" to mapOf("case_moderation_checkpoint" to "require_moderation_transition"),
        "safety.moderation_removals" to mapOf("moderation_removal_immutable" to "protect_moderation_audit", "moderation_removal_no_truncate" to "protect_moderation_audit", "moderation_removal_checkpoint" to "require_moderation_removal"),
    )
    private val source by lazy { checkNotNull(javaClass.getResourceAsStream("/db/migration/V078__staff_moderation_workflow.sql")).use { it.readBytes().decodeToString() } }
    private val removalSource by lazy { checkNotNull(javaClass.getResourceAsStream("/db/migration/V080__staff_moderation_removal.sql")).use { it.readBytes().decodeToString() } }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
