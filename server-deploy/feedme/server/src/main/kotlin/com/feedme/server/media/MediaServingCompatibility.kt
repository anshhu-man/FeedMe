package com.feedme.server.media

import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only privilege admission for the actual four-operation MediaStore and account
 * authority. This is not a grant installer, provider health/safety assertion or full schema
 * attestation. The runtime separately validates migration/provider authority. Empty compiled
 * reads check all referenced columns and row-lock privileges without reading private rows.
 * All four routes include exact draft-media tombstone/cleanup capture; this grants no worker,
 * publication, derivative, remote erasure or direct DELETE capability. */
internal object MediaServingCompatibility {
    fun check(connection: Connection) {
        try {
            require(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Media compatibility interrupted")
            connection.createStatement().use { statement ->
                // Required for the current canonical serving role and FORCE-RLS tables:
                // policy-filtered absence must never be mistaken for unattached/no-quota use.
                statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use {
                    check(it.next() && it.getBoolean(1) && !it.next())
                }
                for ((table, columns, lock) in reads) {
                    statement.executeQuery("SELECT $columns FROM $table WHERE false FOR $lock NOWAIT").use { check(!it.next()) }
                }
            }
            for ((table, privilege, columns) in writes) rights(connection, table, privilege, columns)
        } catch (failure: MediaFailure) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw MediaFailure(MediaFailureCode.NOT_CONFIGURED) }
    }

    private fun rights(connection: Connection, table: String, privilege: String, columns: String) {
        connection.prepareStatement("SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) " +
            "FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) AS names(column_name)").use { statement ->
            statement.setString(1, table); statement.setString(2, privilege); statement.setString(3, columns)
            statement.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) }
        }
    }

    private val reads = listOf(
        Triple("identity.users", "*", "UPDATE"),
        Triple("identity.principals", "environment,user_id,id,status", "UPDATE"),
        Triple("identity.device_sessions", "*", "UPDATE"),
        Triple("profile.profiles", "*", "UPDATE"),
        Triple("platform.post_draft_heads", "environment,owner_user_id,revision", "UPDATE"),
        Triple("platform.media_draft_lifecycles", "environment,owner_user_id,client_draft_id,generation,created_at", "UPDATE"),
        Triple("platform.post_drafts", "environment,owner_user_id,client_draft_id,draft_generation,status,published_post_id,expires_at", "SHARE"),
        Triple("social.post_publications", "environment,owner_user_id,client_draft_id", "SHARE"),
        Triple("social.post_media", "environment,owner_user_id,media_id", "SHARE"),
        // Invoker V027 mode/issuance guards SELECT the exact %ROWTYPE too.
        Triple("platform.media_assets", "*", "UPDATE"),
        Triple("platform.media_upload_issuances", "*", "UPDATE"),
        Triple("platform.media_cleanup_jobs", "*", "UPDATE"),
        Triple("platform.media_processing_jobs", "id,environment,owner_user_id,media_id,state,lease_token,lease_generation", "UPDATE"),
        Triple("platform.media_derivative_intents", "id,job_id,variant,object_key,acceptance_deadline", "UPDATE"),
        Triple("platform.media_processing_cleanup", "environment,owner_user_id,media_id,object_key,derivative_intent_id,not_before,storage_protocol,storage_bucket", "UPDATE"),
        Triple("platform.idempotency", "*", "UPDATE"),
        Triple("platform.outbox", "*", "SHARE"),
    )
    private val writes = listOf(
        Triple("platform.media_draft_lifecycles", "INSERT", "environment,owner_user_id,client_draft_id,generation,created_at"),
        Triple("platform.media_assets", "INSERT", "environment,owner_user_id,id,client_draft_id,draft_generation,kind,state,version,quarantine_key,expected_sha256,expected_bytes,content_type,reservation_expires_at,created_at,updated_at,storage_protocol,storage_bucket"),
        Triple("platform.media_assets", "UPDATE", "state,version,quarantine_version_id,updated_at,completion_key,completion_version,completion_accepted_at,deletion_key,cleanup_manifest_hash,derivative_set,rejection_code"),
        Triple("platform.media_upload_issuances", "INSERT", "environment,owner_user_id,media_id,requested_expires_at,created_at"),
        Triple("platform.media_upload_issuances", "UPDATE", "acknowledged_expires_at"),
        Triple("platform.media_cleanup_jobs", "INSERT", "environment,owner_user_id,media_id,media_version,quarantine_key,known_version_id,derivative_set,manifest_hash,final_sweep_after,available_at,storage_protocol,storage_bucket"),
        Triple("platform.media_processing_jobs", "UPDATE", "state,terminal_token,terminal_generation,terminal_media_version,terminal_at,lease_token,lease_expires_at"),
        Triple("platform.media_derivative_intents", "UPDATE", "cleanup_required"),
        Triple("platform.media_processing_cleanup", "INSERT", "id,environment,owner_user_id,media_id,object_key,derivative_intent_id,not_before,available_at,state,storage_protocol,storage_bucket"),
        Triple("platform.idempotency", "INSERT", "principal_scope,operation_id,key,request_hash,state,expires_at"),
        Triple("platform.idempotency", "UPDATE", "state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at"),
        Triple("platform.outbox", "INSERT", "event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id"),
    )
}
