package com.feedme.server.guest

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Non-mutating startup/readiness proof for the optional guest kitchen + first-plan surface.
 * It neither grants privileges nor creates content/sessions. Every migration checksum and
 * actual restricted role capability needed by the composed stores must already exist. Broad
 * destructive rights on guest-owned roots are refused rather than treated as readiness.
 */
internal object GuestServingCompatibility {
    fun check(connection: Connection, planningEnabled: Boolean, kitchenEnabled: Boolean,
        cookingEnabled: Boolean, savedEnabled: Boolean, feedbackEnabled: Boolean) {
        try {
            require(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest compatibility interrupted")
            for ((version, name) in buildList {
                add(1 to "durable_platform"); add(4 to "private_kitchen"); add(18 to "guest_sessions")
                if (planningEnabled) {
                    add(3 to "private_planning"); add(17 to "planning_manifests")
                    add(19 to "guest_planning_preparations"); add(20 to "manifest_plan_lineage")
                }
                if (cookingEnabled) add(5 to "private_cooking")
                if (savedEnabled) add(6 to "private_saved_recipes")
                if (feedbackEnabled) add(22 to "private_feedback")
            }) migration(connection, version, name)

            val readable = mutableListOf(
                "identity.guest_sessions", "identity.guest_bootstrap_receipts",
                "identity.guest_issuance_windows", "identity.principals", "profile.preferences",
                "platform.outbox",
            )
            if (kitchenEnabled || planningEnabled) readable += "pantry.pantry_items"
            if (planningEnabled) readable += listOf(
                "identity.guest_planning_policies", "identity.guest_plan_windows",
                "planning.guest_preparations", "planning.manifest_headers", "planning.manifest_ranks",
                "planning.manifest_seals", "planning.plan_requests", "planning.plans",
                "platform.idempotency",
            )
            if (cookingEnabled) readable += listOf(
                "cooking.cook_sessions", "cooking.device_cursors", "cooking.step_events")
            if (savedEnabled) readable += listOf(
                "memory.saved_recipes", "memory.collections", "memory.collection_items",
                "memory.save_commands", "memory.library_heads")
            if (feedbackEnabled) readable += listOf(
                "cooking.cook_sessions", "cooking.step_events",
                "memory.feedback", "memory.feedback_commands")
            connection.createStatement().use { statement ->
                for (table in readable) statement.executeQuery("SELECT * FROM $table WHERE false").use { check(!it.next()) }
            }

            for ((table, privilege, columns) in sessionWrites +
                (if (kitchenEnabled) kitchenWrites else emptyList()) +
                (if (cookingEnabled) cookingWrites else emptyList()) +
                (if (savedEnabled) savedWrites else emptyList()) +
                (if (feedbackEnabled) feedbackWrites else emptyList()) +
                (if (planningEnabled) planningWrites else emptyList()))
                rights(connection, table, privilege, columns)
            for (table in listOf("identity.guest_sessions", "identity.guest_bootstrap_receipts",
                "identity.guest_issuance_windows") + (if (planningEnabled) listOf(
                    "identity.guest_planning_policies", "identity.guest_plan_windows", "planning.guest_preparations") else emptyList()) +
                    (if (cookingEnabled) listOf("cooking.cook_sessions", "cooking.device_cursors", "cooking.step_events") else emptyList()))
                connection.prepareStatement("SELECT NOT pg_catalog.has_table_privilege(current_user,?::text,'DELETE,TRUNCATE,TRIGGER')").use {
                    it.setString(1, table); it.executeQuery().use { rows -> check(rows.next() && rows.getBoolean(1) && !rows.next()) }
                }
            if (savedEnabled) {
                for (table in listOf("memory.saved_recipes", "memory.collections",
                    "memory.save_commands", "memory.library_heads"))
                    tableRights(connection, table, required = "", refused = "DELETE,TRUNCATE,TRIGGER")
                tableRights(connection, "memory.collection_items", required = "DELETE", refused = "TRUNCATE,TRIGGER")
            }
            if (feedbackEnabled) for (table in listOf("memory.feedback", "memory.feedback_commands"))
                tableRights(connection, table, required = "", refused = "DELETE,TRUNCATE,TRIGGER")
        } catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw GuestSessionFailure(GuestSessionFailureCode.NOT_CONFIGURED) }
    }

    private fun migration(connection: Connection, version: Int, name: String) {
        val resource = "/db/migration/V${version.toString().padStart(3, '0')}__$name.sql"
        val bytes = GuestServingCompatibility::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw IllegalStateException("Guest migration unavailable")
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        connection.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=?").use {
            it.setInt(1, version); it.executeQuery().use { rows ->
                check(rows.next() && rows.getString(1) == expected && !rows.next())
            }
        }
    }

    private fun rights(connection: Connection, table: String, privilege: String, columns: String) {
        connection.prepareStatement("SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) " +
            "FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) names(column_name)").use {
            it.setString(1, table); it.setString(2, privilege); it.setString(3, columns)
            it.executeQuery().use { rows -> check(rows.next() && rows.getBoolean(1) && !rows.next()) }
        }
    }

    private fun tableRights(connection: Connection, table: String, required: String, refused: String) {
        val sql = buildString {
            append("SELECT ")
            if (required.isNotEmpty()) append("pg_catalog.has_table_privilege(current_user,?::text,?::text) AND ")
            append("NOT pg_catalog.has_table_privilege(current_user,?::text,?::text)")
        }
        connection.prepareStatement(sql).use {
            var index = 1
            if (required.isNotEmpty()) { it.setString(index++, table); it.setString(index++, required) }
            it.setString(index++, table); it.setString(index, refused)
            it.executeQuery().use { rows -> check(rows.next() && rows.getBoolean(1) && !rows.next()) }
        }
    }

    private val sessionWrites = listOf(
        Triple("identity.guest_sessions", "INSERT", "environment,id,installation_sha256,token_sha256,created_at,last_seen_at,inactivity_expires_at,absolute_expires_at,policy_revision,capabilities,daily_plan_limit"),
        Triple("identity.guest_sessions", "UPDATE", "last_seen_at,inactivity_expires_at"),
        Triple("identity.principals", "INSERT", "environment,id,user_id,guest_session_id,kind,status,version,created_at,updated_at"),
        Triple("identity.guest_bootstrap_receipts", "INSERT", "environment,command_key,installation_sha256,request_sha256,guest_session_id,key_id,nonce,ciphertext,created_at,expires_at"),
        Triple("identity.guest_issuance_windows", "INSERT", "environment,installation_sha256,window_date,issued_count"),
        Triple("identity.guest_issuance_windows", "UPDATE", "issued_count"),
        Triple("profile.preferences", "INSERT", "environment,actor_kind,principal_id,id,version,created_at,updated_at,fields"),
        Triple("platform.outbox", "INSERT", "event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id"),
    )
    private val planningWrites = listOf(
        Triple("identity.guest_planning_policies", "INSERT", "environment,guest_session_id,policy_sha256"),
        Triple("identity.guest_plan_windows", "INSERT", "environment,guest_session_id,window_date,policy_sha256,used_count"),
        Triple("identity.guest_plan_windows", "UPDATE", "used_count"),
        Triple("planning.guest_preparations", "INSERT", "environment,actor_kind,principal_id,command_key,request_sha256,guest_session_id,policy_sha256,manifest_id,window_date"),
        Triple("planning.guest_preparations", "UPDATE", "command_key"),
        Triple("planning.manifest_headers", "INSERT", "environment,actor_kind,principal_id,manifest_id,header_text,header_sha256,catalog_release_id,catalog_revision,catalog_request_sha256,taxonomy_revision,taxonomy_sha256,version_count,comparator"),
        Triple("planning.manifest_ranks", "INSERT", "environment,actor_kind,principal_id,manifest_id,recipe_version_id,recipe_id,source_release_id,source_revision,source_request_sha256,recipe_sha256,review_sha256,taste_matches,disliked_ingredients,confirmed_ingredients,active_minutes,cleanup_minutes"),
        Triple("planning.manifest_seals", "INSERT", "environment,actor_kind,principal_id,manifest_id,header_sha256,traversed_count,eligible_count,rows_sha256,first_decision_text,first_decision_sha256"),
        Triple("planning.plan_requests", "INSERT", "environment,actor_kind,principal_id,id,request_text,request_hash,evidence_text,evidence_hash,ordered_ids,policy_text,current_plan_id,version,created_at,expires_at,cursor_expires_at,storage_format,manifest_id,create_command_key,command_request_sha256"),
        Triple("planning.plan_requests", "UPDATE", "current_plan_id,version"),
        Triple("planning.plans", "INSERT", "environment,actor_kind,principal_id,id,request_id,parent_plan_id,version,position,recipe_version_id,status,snapshot_text,snapshot_hash,proof_text,proof_hash,next_cursor_hash,created_at,storage_format"),
        Triple("platform.idempotency", "INSERT", "principal_scope,operation_id,key,request_hash,state,expires_at"),
        Triple("platform.idempotency", "UPDATE", "state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at"),
    )
    private val kitchenWrites = listOf(
        Triple("profile.preferences", "UPDATE", "fields,version,updated_at"),
        Triple("pantry.pantry_items", "INSERT", "environment,actor_kind,principal_id,ingredient_id,id,version,created_at,updated_at,fields"),
        Triple("pantry.pantry_items", "UPDATE", "id,version,created_at,updated_at,fields,deleted,deletion_key"),
    )
    private val cookingWrites = listOf(
        Triple("cooking.cook_sessions", "INSERT", "environment,actor_kind,principal_id,id,plan_id,version,status,device_sequence,snapshot,plan_snapshot_text,plan_snapshot_hash,plan_proof_hash,plan_evidence_hash,created_at,updated_at,expires_at"),
        Triple("cooking.cook_sessions", "UPDATE", "version,status,device_sequence,snapshot,updated_at"),
        Triple("cooking.device_cursors", "INSERT", "environment,actor_kind,principal_id,session_id,device_identity,device_sequence"),
        Triple("cooking.device_cursors", "UPDATE", "device_sequence"),
        Triple("cooking.step_events", "INSERT", "environment,actor_kind,principal_id,session_id,command_id,device_identity,device_sequence,session_version,kind,request_hash,payload,accepted_at"),
        Triple("cooking.step_events", "UPDATE", "command_id"),
    )
    private val savedWrites = listOf(
        Triple("memory.saved_recipes", "INSERT", "environment,actor_kind,principal_id,id,generation,version,recipe_version_id,recipe_hash,source_type,source_id,origin_plan_id,content_license,snapshot,copy_evidence,created_at,updated_at"),
        Triple("memory.saved_recipes", "UPDATE", "deleted,snapshot,copy_evidence,deletion_key,version,updated_at"),
        Triple("memory.collections", "INSERT", "environment,actor_kind,principal_id,id,version,is_default,name,created_at,updated_at"),
        Triple("memory.collections", "UPDATE", "version,updated_at"),
        Triple("memory.collection_items", "INSERT", "environment,actor_kind,principal_id,collection_id,saved_recipe_id,position"),
        Triple("memory.collection_items", "UPDATE", "saved_recipe_id"),
        Triple("memory.save_commands", "INSERT", "environment,actor_kind,principal_id,command_id,saved_recipe_id,collection_id,generation"),
        Triple("memory.save_commands", "UPDATE", "command_id"),
        Triple("memory.library_heads", "INSERT", "environment,actor_kind,principal_id,revision"),
        Triple("memory.library_heads", "UPDATE", "revision"),
    )
    private val feedbackWrites = listOf(
        Triple("memory.feedback", "INSERT", "environment,actor_kind,principal_id,id,version,cook_session_id,context_text,context_sha256,provenance_text,provenance_sha256,snapshot,created_at,updated_at"),
        Triple("memory.feedback", "UPDATE", "version,snapshot,updated_at,deleted,deletion_key,cook_session_id,context_text,context_sha256,provenance_text,provenance_sha256"),
        Triple("memory.feedback_commands", "INSERT", "environment,actor_kind,principal_id,feedback_id,feedback_version,principal_scope,operation_id,command_key,request_hash,response_sha256"),
    )
}
