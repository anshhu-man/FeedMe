package com.feedme.server.social.posts

import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Non-mutating proof of the exact restricted serving rights used by this optional lane.
 * The deletion cleanup trigger executes its fixed owner-owned insertion; serving receives
 * no cleanup-job mutation or physical-erasure permission. */
internal object PostReactionServingCompatibility {
    fun check(c: Connection) {
        try {
            PostReadServingCompatibility.check(c)
            c.createStatement().use { s ->
                for (table in listOf("platform.idempotency", "social.post_reactions"))
                    s.executeQuery("SELECT * FROM $table WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
                s.executeQuery("SELECT count(*)=1 FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_proc p ON p.oid=t.tgfoid " +
                    "WHERE t.tgrelid='social.posts'::regclass AND t.tgname='deleted_post_reaction_cleanup' AND t.tgenabled='O' " +
                    "AND NOT t.tgisinternal AND p.oid='social.queue_deleted_post_reactions()'::regprocedure AND p.prosecdef").use {
                    check(it.next() && it.getBoolean(1) && !it.next())
                }
            }
            rights(c, "social.post_reactions", "INSERT", "environment,actor_user_id,post_owner_user_id,post_id,id,version,active,kind,last_command_key,last_operation_id,created_at,updated_at")
            rights(c, "social.post_reactions", "UPDATE", "active,kind,version,last_command_key,last_operation_id,updated_at")
            rights(c, "platform.idempotency", "INSERT", "principal_scope,operation_id,key,request_hash,state,expires_at")
            rights(c, "platform.idempotency", "UPDATE", "state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at")
            rights(c, "platform.outbox", "INSERT", "event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id")
        } catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { throw PostReactionFailure(PostReactionFailureCode.NOT_CONFIGURED) }
    }
    private fun rights(c: Connection, table: String, privilege: String, columns: String) {
        c.prepareStatement("SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) names(column_name)").use {
            it.setString(1, table); it.setString(2, privilege); it.setString(3, columns)
            it.executeQuery().use { rows -> check(rows.next() && rows.getBoolean(1) && !rows.next()) }
        }
    }
}
