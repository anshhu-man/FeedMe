package com.feedme.server.social.reports

import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only admission for the actual complaint store and target authority, not a grant
 * installer, moderation service or full schema attestation. The owning runtime separately
 * requires current migration history and provider authority. Empty compiled reads exercise
 * SELECT plus row-lock permissions, including V038's invoker/deferred checkpoint reads.
 * FORCE RLS must not silently turn missing authority into an empty target/report lookup.
 * This probe never reads a report, captures evidence, allocates an ID, writes or repairs.
 */
internal object ReportServingCompatibility {
    fun check(connection: Connection) {
        try {
            require(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Report compatibility interrupted")
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use {
                    check(it.next() && it.getBoolean(1) && !it.next())
                }
                for ((table, columns, lock) in reads) {
                    // Source-fixed identifiers only. WHERE false discloses no domain rows;
                    // PostgreSQL still checks each referenced column and locking privilege.
                    val locking = if (lock.isEmpty()) "" else " FOR $lock NOWAIT"
                    statement.executeQuery("SELECT $columns FROM $table WHERE false$locking").use { check(!it.next()) }
                }
            }
            rights(connection, "safety.moderation_cases", "INSERT",
                "environment,id,report_id,target_type,target_id,version,priority,status,action,created_at,updated_at")
            rights(connection, "safety.reports", "INSERT",
                "environment,reporter_user_id,id,case_id,command_key,request_sha256,target_type,target_id,reason,description,status,version,creation_event_id,created_at,updated_at")
            rights(connection, "safety.report_evidence", "INSERT",
                "environment,reporter_user_id,report_id,target_type,target_id,target_owner_id,target_version,evidence_text,evidence_sha256,valid_until,captured_at")
            rights(connection, "platform.idempotency", "INSERT", "principal_scope,operation_id,key,request_hash,state,expires_at")
            rights(connection, "platform.idempotency", "UPDATE",
                "state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at")
            rights(connection, "platform.outbox", "INSERT",
                "event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id")
        } catch (failure: ReportFailure) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw ReportFailure(ReportFailureCode.NOT_CONFIGURED) }
    }

    private fun rights(connection: Connection, table: String, privilege: String, columns: String) {
        connection.prepareStatement("SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) " +
            "FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) AS names(column_name)").use { statement ->
            statement.setString(1, table); statement.setString(2, privilege); statement.setString(3, columns)
            statement.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) }
        }
    }

    private val reads = listOf(
        // AccountProfileStore.lockAccountSafety: provider -> account/principal -> device.
        Triple("identity.users", "*", "UPDATE"),
        Triple("identity.principals", "environment,user_id,id,status", "UPDATE"),
        Triple("identity.device_sessions", "*", "UPDATE"),
        // PostgresReportTargets uses current ownership/audience, not social creation,
        // current Terms, completed onboarding, positive moderation or unblocked pairs.
        Triple("profile.profiles", "environment,user_id,live,version,display_name,normalized_handle,bio", "SHARE"),
        Triple("social.circles", "environment,id,status", "SHARE"),
        Triple("social.circle_members", "environment,circle_id,user_id,status,generation", "SHARE"),
        Triple("social.posts", "*", "SHARE"),
        Triple("social.post_audiences", "environment,owner_user_id,post_id,circle_id,author_membership_generation", "SHARE"),
        Triple("social.post_publications", "environment,owner_user_id,post_id,published_at,response_sha256", "SHARE"),
        // Message complaints read existing history only. SHARE requires a narrow column
        // UPDATE privilege for locking, not permission to change message/contact content.
        Triple("social.direct_threads", "environment,id,user_low,user_high,version,last_sequence,last_message_at,created_at,updated_at", "SHARE"),
        Triple("social.thread_messages", "environment,id,thread_id,sender_user_id,client_message_id,sequence,kind,text,created_at,recipe_request_id,recipe_version_id", "SHARE"),
        Triple("social.recipe_requests", "environment,id,thread_id,requester_user_id,author_user_id,version,status,recipe_version_id,created_at,updated_at,expires_at", "SHARE"),
        Triple("social.account_privacy", "environment,user_id,version,allow_recipe_requests", "SHARE"),
        // Typed cards use the same current immutable catalog view as conversation reads.
        Triple("catalog.recipe_heads", "environment,revision,release_id", "SHARE"),
        Triple("catalog.recipe_releases", "environment,release_id,revision,predecessor_revision,request_sha256,content_sha256,taxonomy_revision,taxonomy_sha256,exact_document", "SHARE"),
        Triple("catalog.recipe_release_entries", "environment,release_id,recipe_version_id,entry", "SHARE"),
        Triple("catalog.recipe_release_compositions", "environment,release_id,ingredient_id,component_ids", "SHARE"),
        Triple("catalog.recipe_version_history", "environment,recipe_version_id,revision,release_id", ""),
        // V038 SELECT * INTO %ROWTYPE is invoker code: all columns and locking rights
        // are necessary even where AccountReportStore itself only inserts the row.
        Triple("safety.reports", "*", "SHARE"),
        Triple("safety.report_evidence", "*", "SHARE"),
        Triple("safety.moderation_cases", "*", "SHARE"),
        Triple("platform.idempotency", "*", "UPDATE"),
        Triple("platform.outbox", "*", "SHARE"),
    )
}
