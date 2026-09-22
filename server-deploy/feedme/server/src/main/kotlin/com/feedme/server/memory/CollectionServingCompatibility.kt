package com.feedme.server.memory

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only admission, not an installer. Existing account/Saved/catalog/outbox guards are
 * independently checked by the account assembly. Optional mutations need additional name,
 * description and collection DELETE rights; this probe never grants them. */
internal object CollectionServingCompatibility {
    fun check(c:Connection) {
        try {
            check(!c.autoCommit && c.transactionIsolation==Connection.TRANSACTION_READ_COMMITTED)
            if(Thread.currentThread().isInterrupted) throw InterruptedException("Collection admission interrupted")
            c.createStatement().use { s ->
                s.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use {
                    check(it.next() && it.getBoolean(1) && !it.next()) }
                for(table in tables) s.executeQuery("SELECT * FROM $table WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
            }
            for((version,name) in listOf(6 to "private_saved_recipes",42 to "outbox_ownership")) {
                val resource="/db/migration/V${version.toString().padStart(3,'0')}__$name.sql"
                val hash=javaClass.getResourceAsStream(resource)?.use { stream ->
                    MessageDigest.getInstance("SHA-256").digest(stream.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                } ?: error("Missing collection schema")
                c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=?").use { s ->
                    s.setInt(1,version);s.executeQuery().use { check(it.next() && it.getString(1)==hash && !it.next()) } }
            }
            for((table,privilege,columns) in writes) c.prepareStatement(
                "SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,col,?::text)) FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) t(col)").use { s ->
                s.setString(1,table);s.setString(2,privilege);s.setString(3,columns)
                s.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) } }
            for(table in listOf("memory.collections","memory.collection_items")) c.prepareStatement(
                "SELECT pg_catalog.has_table_privilege(current_user,?::text,'DELETE')").use { s ->
                s.setString(1,table);s.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) } }
        } catch(f:SavedRecipeFailure) { throw f }
        catch(f:CancellationException) { throw f }
        catch(f:InterruptedException) { Thread.currentThread().interrupt();throw f }
        catch(_:Exception) { throw SavedRecipeFailure(SavedRecipeFailureCode.NOT_CONFIGURED) }
    }
    private val tables=listOf("memory.library_heads","memory.collections","memory.collection_items","memory.saved_recipes",
        "memory.save_commands","platform.idempotency","platform.outbox")
    private val writes=listOf(
        Triple("memory.collections","INSERT","environment,actor_kind,principal_id,id,version,is_default,name,description,created_at,updated_at"),
        Triple("memory.collections","UPDATE","name,description,version,updated_at"),
        Triple("memory.collection_items","INSERT","environment,actor_kind,principal_id,collection_id,saved_recipe_id,position"),
        Triple("memory.library_heads","INSERT","environment,actor_kind,principal_id,revision"),
        Triple("memory.library_heads","UPDATE","revision"),
        Triple("platform.idempotency","INSERT","principal_scope,operation_id,key,request_hash,state,expires_at"),
        Triple("platform.idempotency","UPDATE","state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at"),
        Triple("platform.outbox","INSERT","event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id"))
}
