package com.feedme.server.social.reciperequests

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only admission for the optional V054 slice. Core, catalog, post-reader and
 * conversation probes remain required by the composition; this is not a grant installer. */
internal object RecipeRequestServingCompatibility {
    fun check(connection: Connection) {
        try {
            require(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Recipe request compatibility interrupted")
            connection.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=54").use { s ->
                s.executeQuery().use { check(it.next() && it.getString(1) == checksum && !it.next()) }
            }
            connection.createStatement().use { s ->
                s.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use { check(it.next() && it.getBoolean(1) && !it.next()) }
                s.executeQuery("SELECT * FROM social.recipe_requests WHERE false FOR UPDATE NOWAIT").use { check(!it.next()) }
                s.executeQuery("SELECT kind,recipe_request_id,recipe_version_id FROM social.thread_messages WHERE false").use { check(!it.next()) }
            }
            for ((table, privilege, columns) in writes) connection.prepareStatement("SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) " +
                "FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) names(column_name)").use { s ->
                s.setString(1, table); s.setString(2, privilege); s.setString(3, columns)
                s.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) }
            }
            for (table in listOf("social.recipe_requests", "social.thread_messages")) connection.prepareStatement("SELECT c.relkind,c.relrowsecurity,c.relforcerowsecurity," +
                "NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid)," +
                "NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(c.relacl,pg_catalog.acldefault('r',c.relowner))) a WHERE a.grantee=0) " +
                "FROM pg_catalog.pg_class c WHERE c.oid=pg_catalog.to_regclass(?)").use { s ->
                s.setString(1, table); s.executeQuery().use { r -> check(r.next() && r.getString(1) == "r" && (2..5).all(r::getBoolean) && !r.next()) }
            }
            for ((name, tag) in functions) connection.prepareStatement("SELECT p.prosrc,p.prosecdef,p.prokind,p.prorettype='pg_catalog.trigger'::pg_catalog.regtype," +
                "p.proconfig,NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(p.proacl,pg_catalog.acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner) " +
                "FROM pg_catalog.pg_proc p WHERE p.oid=pg_catalog.to_regprocedure(?)").use { s ->
                s.setString(1, "$name()"); s.executeQuery().use { r ->
                    check(r.next() && r.getString(1) == migration.substringAfter("\$$tag\$").substringBefore("\$$tag\$") && r.getBoolean(2) && r.getString(3) == "f" && r.getBoolean(4))
                    val config = (r.getArray(5).array as Array<*>).map { it.toString().replace(" ", "") }.toSet()
                    check(config == setOf("search_path=pg_catalog,pg_temp", "row_security=off") && r.getBoolean(6) && !r.next())
                }
            }
            for ((table, name, type, function) in triggers) connection.prepareStatement("SELECT tgtype,tgenabled,NOT tgisinternal,tgfoid=pg_catalog.to_regprocedure(?),tgqual IS NULL,tgnargs=0 " +
                "FROM pg_catalog.pg_trigger WHERE tgrelid=pg_catalog.to_regclass(?) AND tgname=?").use { s ->
                s.setString(1, "$function()"); s.setString(2, table); s.setString(3, name)
                s.executeQuery().use { r -> check(r.next() && r.getInt(1) == type && r.getString(2) == "O" && (3..6).all(r::getBoolean) && !r.next()) }
            }
        } catch (failure: RecipeRequestFailure) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw RecipeRequestFailure(RecipeRequestFailureCode.NOT_CONFIGURED) }
    }
    private val writes = listOf(
        Triple("social.recipe_requests", "INSERT", "environment,id,requester_user_id,author_user_id,post_id,thread_id,version,status,expires_at,created_at,updated_at"),
        Triple("social.recipe_requests", "UPDATE", "status,close_reason,recipe_version_id,version,updated_at"),
        Triple("social.thread_messages", "INSERT", "environment,id,thread_id,sender_user_id,client_message_id,sequence,text,created_at,kind,recipe_request_id,recipe_version_id"),
    )
    private val functions = mapOf("social.guard_recipe_request" to "feedme_recipe_request_guard", "social.guard_recipe_request_message" to "feedme_recipe_message_guard")
    private data class Attachment(val table: String, val name: String, val type: Int, val function: String)
    private val triggers = listOf(
        Attachment("social.recipe_requests", "recipe_request_write", 31, "social.guard_recipe_request"),
        Attachment("social.recipe_requests", "recipe_request_retained", 34, "social.guard_recipe_request"),
        Attachment("social.thread_messages", "recipe_request_message", 7, "social.guard_recipe_request_message"),
    )
    private val migration by lazy { checkNotNull(RecipeRequestServingCompatibility::class.java.getResourceAsStream("/db/migration/V054__private_recipe_requests.sql")).use { it.readBytes().decodeToString() } }
    private val checksum by lazy { MessageDigest.getInstance("SHA-256").digest(migration.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }
}
