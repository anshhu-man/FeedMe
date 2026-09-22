package com.feedme.server.social.posts

import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only capability inspection. This never installs serving grants or broadens the
 * general updatePost surface beyond the configured placement-only handler. */
internal object PostPlacementServingCompatibility {
    fun check(c: Connection) {
        try {
            PostReadServingCompatibility.check(c)
            c.createStatement().use { s ->
                s.executeQuery("SELECT * FROM platform.idempotency WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
            }
            rights(c, "social.posts", "UPDATE", "version,content,updated_at")
            rights(c, "platform.idempotency", "INSERT", "principal_scope,operation_id,key,request_hash,state,expires_at")
            rights(c, "platform.idempotency", "UPDATE", "state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at")
            rights(c, "platform.outbox", "INSERT", "event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id")
        } catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { throw PostPlacementFailure(PostPlacementFailureCode.NOT_CONFIGURED) }
    }
    private fun rights(c: Connection, table: String, privilege: String, columns: String) {
        c.prepareStatement("SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) names(column_name)").use {
            it.setString(1, table); it.setString(2, privilege); it.setString(3, columns)
            it.executeQuery().use { rows -> check(rows.next() && rows.getBoolean(1) && !rows.next()) }
        }
    }
}
