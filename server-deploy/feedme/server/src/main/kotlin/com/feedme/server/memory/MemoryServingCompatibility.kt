package com.feedme.server.memory

import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only admission for optional account feedback/memory serving. No grants, row
 * writes or projection are performed. Catalog/account/planning compatibility remains
 * separately required by the actual assembly, and no public Reuse API is implied. */
internal object MemoryServingCompatibility {
    fun check(connection: Connection) {
        try {
            require(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Memory compatibility interrupted")
            connection.createStatement().use { s ->
                // The current serving contract uses FORCE RLS and an already reviewed
                // BYPASSRLS role; policy-filtered absence is not an empty owner history.
                s.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use {
                    check(it.next() && it.getBoolean(1) && !it.next())
                }
                for (table in reads) s.executeQuery("SELECT * FROM $table WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
                s.executeQuery("SELECT memory.valid_memory_context('{}'::jsonb)").use { check(it.next() && it.getBoolean(1) && !it.next()) }
            }
            for ((version, name) in listOf(1 to "durable_platform", 22 to "private_feedback", 24 to "preference_memories", 42 to "outbox_ownership")) {
                val expected = MemoryServingCompatibility::class.java.getResourceAsStream("/db/migration/V${version.toString().padStart(3, '0')}__$name.sql")
                    ?.use { feedbackSha(it.readBytes().decodeToString()) } ?: error("Missing memory schema")
                connection.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=?").use { s ->
                    s.setInt(1, version); s.executeQuery().use { check(it.next() && it.getString(1) == expected && !it.next()) }
                }
            }
            for ((table, privilege, columns) in writes) connection.prepareStatement(
                "SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) names(column_name)").use { s ->
                s.setString(1, table); s.setString(2, privilege); s.setString(3, columns)
                s.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) }
            }
            for (table in listOf("memory.memory_sources", "memory.memory_dirty_groups")) connection.prepareStatement(
                "SELECT pg_catalog.has_table_privilege(current_user,?::text,'DELETE')").use { s ->
                s.setString(1, table); s.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) }
            }
        } catch (f: MemoryFailure) { throw f }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (_: Exception) { throw MemoryFailure(MemoryFailureCode.NOT_CONFIGURED) }
    }
    private val reads = listOf("identity.users", "identity.principals", "identity.device_sessions", "profile.profiles",
        "planning.plans", "planning.plan_requests", "cooking.cook_sessions", "cooking.step_events", "memory.feedback",
        "memory.feedback_commands", "memory.memory_heads", "memory.memory_feedback_state", "memory.memory_dirty_groups",
        "memory.memories", "memory.memory_sources", "memory.memory_suppressions", "memory.memory_commands",
        "memory.memory_projection_events", "platform.idempotency", "platform.outbox")
    private val writes = listOf(
        Triple("memory.feedback", "INSERT", "environment,actor_kind,principal_id,id,version,cook_session_id,context_text,context_sha256,provenance_text,provenance_sha256,snapshot,created_at,updated_at"),
        Triple("memory.feedback", "UPDATE", "version,snapshot,updated_at,deleted,deletion_key,cook_session_id,context_text,context_sha256,provenance_text,provenance_sha256"),
        Triple("memory.feedback_commands", "INSERT", "environment,actor_kind,principal_id,feedback_id,feedback_version,principal_scope,operation_id,command_key,request_hash,response_sha256"),
        Triple("memory.memory_heads", "INSERT", "environment,actor_kind,principal_id,source_revision,projected_revision,memory_revision"),
        Triple("memory.memory_heads", "UPDATE", "source_revision,projected_revision,memory_revision"),
        Triple("memory.memory_feedback_state", "INSERT", "environment,actor_kind,principal_id,feedback_id,feedback_version,dirty,taste_epoch,effort_epoch,make_again_epoch"),
        Triple("memory.memory_feedback_state", "UPDATE", "feedback_version,dirty,taste_epoch,effort_epoch,make_again_epoch"),
        Triple("memory.memory_dirty_groups", "INSERT", "environment,actor_kind,principal_id,kind,semantic_key"),
        Triple("memory.memories", "INSERT", "environment,actor_kind,principal_id,id,generation,version,kind,semantic_key,original_context,snapshot,created_at,updated_at"),
        Triple("memory.memories", "UPDATE", "version,snapshot,user_override,updated_at,deleted,deletion_key"),
        Triple("memory.memory_sources", "INSERT", "environment,actor_kind,principal_id,semantic_key,feedback_id,signal_key,signal_epoch,fingerprint,kind,value,context,source_version,source_sha256"),
        Triple("memory.memory_suppressions", "INSERT", "environment,actor_kind,principal_id,semantic_key,feedback_id,signal_key,signal_epoch,fingerprint,created_at"),
        Triple("memory.memory_commands", "INSERT", "environment,actor_kind,principal_id,memory_id,memory_version,principal_scope,operation_id,command_key,request_hash,response_sha256"),
        Triple("memory.memory_projection_events", "INSERT", "environment,actor_kind,principal_id,event_id,feedback_id,source_version,rule_version,processed_at"),
        Triple("platform.idempotency", "INSERT", "principal_scope,operation_id,key,request_hash,state,expires_at"),
        Triple("platform.idempotency", "UPDATE", "state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at"),
        Triple("platform.outbox", "INSERT", "event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id"),
    )
}
