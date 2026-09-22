package com.feedme.server.social.posts

import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only check for optional owner removal, not a grant installer. Media serving
 * compatibility is checked by the composing runtime; this adds no new publish rights. */
internal object PostDeletionServingCompatibility {
    fun check(c: Connection) {
        try {
            require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            c.createStatement().use { s ->
                s.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use { check(it.next() && it.getBoolean(1) && !it.next()) }
                for (table in listOf("identity.users", "identity.principals", "identity.device_sessions", "profile.profiles", "platform.idempotency",
                    "platform.post_draft_heads", "platform.media_draft_lifecycles", "platform.post_drafts", "social.posts", "social.post_publications", "social.post_media", "social.recipe_save_policies"))
                    s.executeQuery("SELECT * FROM $table WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
            }
            rights(c, "social.posts", "UPDATE", "version,status,content,updated_at")
            rights(c, "social.recipe_save_policies", "UPDATE", "version,allow_future_saves")
            rights(c, "platform.idempotency", "INSERT", "principal_scope,operation_id,key,request_hash,state,expires_at")
            rights(c, "platform.idempotency", "UPDATE", "state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at")
            rights(c, "platform.outbox", "INSERT", "event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id")
        } catch (failure: PostDeletionFailure) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw PostDeletionFailure(PostDeletionFailureCode.NOT_CONFIGURED) }
    }
    private fun rights(c: Connection, table: String, privilege: String, columns: String) {
        c.prepareStatement("SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) names(column_name)").use {
            it.setString(1, table); it.setString(2, privilege); it.setString(3, columns); it.executeQuery().use { rows -> check(rows.next() && rows.getBoolean(1) && !rows.next()) }
        }
    }
}
