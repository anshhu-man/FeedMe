package com.feedme.server.social.posts

import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Non-mutating actual-role admission, not a grant installer or schema migration. Missing
 * authority refuses optional startup; never substitute an owner connection or an empty feed.
 * Runtime separately checks account, planning/catalog and shared media serving dependencies. */
internal object PostAuthoringServingCompatibility {
    fun check(connection: Connection) {
        try {
            require(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use {
                    check(it.next() && it.getBoolean(1) && !it.next())
                }
                for ((table, locked) in reads) statement.executeQuery("SELECT * FROM $table WHERE false" +
                    if (locked) " FOR SHARE NOWAIT" else "").use { check(!it.next()) }
            }
            PostgresPostReadContentAuthority.lockModeration(connection)
            com.feedme.server.media.processing.SupabaseMediaReadiness.checkCompatibility(connection)
            rights(connection, "platform.post_draft_heads", "INSERT", "environment,owner_user_id,revision")
            rights(connection, "platform.post_draft_heads", "UPDATE", "revision")
            rights(connection, "platform.media_draft_lifecycles", "INSERT", "environment,owner_user_id,client_draft_id,generation,created_at")
            rights(connection, "platform.post_drafts", "INSERT", "environment,owner_user_id,id,client_draft_id,draft_generation,version,status,content,created_at,updated_at,expires_at")
            rights(connection, "platform.post_drafts", "UPDATE", "version,status,content,updated_at,expires_at,published_post_id,deletion_key")
            rights(connection, "platform.post_draft_discard_media", "INSERT", "environment,owner_user_id,draft_id,media_id,media_version,cleanup_evidence")
            rights(connection, "social.posts", "INSERT", "environment,owner_user_id,id,version,status,content,published_at,expires_at,updated_at")
            rights(connection, "social.post_publications", "INSERT", "environment,owner_user_id,client_draft_id,draft_generation,post_id,command_key,request_sha256,response_sha256,recipe_sha256,draft_id,reviewed_draft_version,published_at")
            rights(connection, "social.post_media", "INSERT", "environment,owner_user_id,post_id,media_id,position,media_version,derivative_set")
            rights(connection, "social.post_audiences", "INSERT", "environment,owner_user_id,post_id,circle_id,author_membership_generation")
            rights(connection, "social.post_attachments", "INSERT", "environment,owner_user_id,post_id,attachment,recipe_snapshot,recipe_sha256,accepted_at")
            rights(connection, "social.recipe_save_policies", "INSERT", "environment,owner_user_id,post_id,version,allow_future_saves,recipe_sha256,disclosure_version,accepted_at")
            rights(connection, "social.post_publication_discard_media", "INSERT", "environment,owner_user_id,post_id,media_id,media_version,cleanup_evidence")
            rights(connection, "platform.idempotency", "INSERT", "principal_scope,operation_id,key,request_hash,state,expires_at")
            rights(connection, "platform.idempotency", "UPDATE", "state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at")
            rights(connection, "platform.outbox", "INSERT", "event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id")
        } catch (failure: PostPublicationFailure) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw PostPublicationFailure(PostPublicationFailureCode.NOT_CONFIGURED) }
    }
    private fun rights(c: Connection, table: String, privilege: String, columns: String) {
        c.prepareStatement("SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) " +
            "FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) AS names(column_name)").use {
            it.setString(1, table); it.setString(2, privilege); it.setString(3, columns)
            it.executeQuery().use { rows -> check(rows.next() && rows.getBoolean(1) && !rows.next()) }
        }
    }
    private val reads = listOf(
        "identity.users" to true, "identity.principals" to true, "profile.profiles" to true,
        "social.circles" to true, "social.circle_members" to true, "social.blocks" to false, "social.block_pairs" to false,
        "platform.idempotency" to true, "platform.post_draft_heads" to true, "platform.media_draft_lifecycles" to true,
        "platform.post_drafts" to true, "platform.post_draft_discard_media" to false,
        "social.posts" to true, "social.post_publications" to true, "social.post_media" to true,
        "social.post_audiences" to true, "social.post_attachments" to true, "social.recipe_save_policies" to true,
        "social.post_publication_discard_media" to false, "safety.moderation_cases" to false,
        "planning.plans" to true, "planning.plan_requests" to true,
        "catalog.recipe_heads" to true, "catalog.recipe_version_history" to false,
        "catalog.recipe_releases" to false, "catalog.recipe_release_entries" to false, "catalog.recipe_release_compositions" to false,
        "platform.media_assets" to true, "platform.media_processing_jobs" to true,
        "platform.media_derivative_intents" to true, "platform.media_safety_records" to true, "platform.media_safety_revocations" to false,
        "platform.media_private_materializations" to false, "platform.media_digest_readiness" to true,
    )
}
