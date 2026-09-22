package com.feedme.server.social.conversations

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Nonmutating admission for this optional serving slice, not a grant installer.
 * The runtime owns full provider/core compatibility. This checks the packaged local
 * V052, its private write fences and actual SQL privileges; it never repairs a role. */
internal object ConversationServingCompatibility {
    fun check(connection: Connection) {
        try {
            require(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Conversation compatibility interrupted")
            connection.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=52").use { s ->
                s.executeQuery().use { check(it.next() && it.getString(1) == checksum && !it.next()) }
            }
            connection.createStatement().use { s ->
                s.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use { check(it.next() && it.getBoolean(1) && !it.next()) }
                for ((table, lock) in reads) s.executeQuery("SELECT * FROM $table WHERE false" + if (lock) " FOR SHARE NOWAIT" else "").use { check(!it.next()) }
                // Current message readers understand the additive V054 typed context.
                // An older schema must fail before binding, not on the first text read.
                s.executeQuery("SELECT kind,recipe_request_id,recipe_version_id FROM social.thread_messages WHERE false").use { check(!it.next()) }
                // Readers and exact original replay must serialize with a staff removal.
                s.execute("LOCK TABLE ONLY safety.moderation_cases IN SHARE MODE NOWAIT")
            }
            for ((table, privilege, columns) in writes) rights(connection, table, privilege, columns)
            for (table in tables) connection.prepareStatement("SELECT c.relkind,c.relrowsecurity,c.relforcerowsecurity," +
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
        } catch (failure: ConversationFailure) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw ConversationFailure(ConversationFailureCode.NOT_CONFIGURED) }
    }
    private fun rights(c: Connection, table: String, privilege: String, columns: String) {
        c.prepareStatement("SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) names(column_name)").use { s ->
            s.setString(1, table); s.setString(2, privilege); s.setString(3, columns); s.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) }
        }
    }
    private val tables = listOf("social.account_privacy", "social.direct_threads", "social.thread_messages", "social.thread_read_watermarks")
    private val reads = listOf("identity.users" to true, "identity.principals" to true, "identity.device_sessions" to true,
        "profile.profiles" to true, "social.circles" to true, "social.circle_members" to false, "social.blocks" to false,
        "social.account_privacy" to true, "social.direct_threads" to true, "social.thread_messages" to false,
        "social.thread_read_watermarks" to false, "platform.idempotency" to true, "safety.moderation_cases" to false)
    private val writes = listOf(
        Triple("social.account_privacy", "UPDATE", "version,default_audience,allow_circle_member_messages,allow_recipe_requests,analytics_consent,allow_coordination_invites,social_discovery_visible,updated_at"),
        Triple("social.direct_threads", "INSERT", "environment,id,user_low,user_high,version"),
        Triple("social.direct_threads", "UPDATE", "version,last_sequence,last_message_at,updated_at"),
        Triple("social.thread_messages", "INSERT", "environment,id,thread_id,sender_user_id,client_message_id,sequence,text,created_at"),
        Triple("social.thread_read_watermarks", "INSERT", "environment,thread_id,user_id"),
        Triple("social.thread_read_watermarks", "UPDATE", "last_sequence,updated_at"),
        Triple("platform.idempotency", "INSERT", "principal_scope,operation_id,key,request_hash,state,expires_at"),
        Triple("platform.idempotency", "UPDATE", "state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at"),
        Triple("platform.outbox", "INSERT", "event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id"),
    )
    private val functions = mapOf("social.initialize_account_privacy" to "feedme_privacy_initial", "social.guard_direct_conversation_write" to "feedme_conversation_write")
    private data class Attachment(val table: String, val name: String, val type: Int, val function: String = "social.guard_direct_conversation_write")
    private val triggers = listOf(
        Attachment("identity.users", "account_privacy_initial", 5, "social.initialize_account_privacy"),
        Attachment("social.account_privacy", "privacy_owner_write", 23),
        Attachment("social.direct_threads", "direct_thread_write", 31), Attachment("social.thread_messages", "thread_message_write", 31),
        Attachment("social.thread_read_watermarks", "thread_watermark_write", 31),
        Attachment("social.direct_threads", "direct_thread_retained", 34), Attachment("social.thread_messages", "thread_message_retained", 34),
        Attachment("social.thread_read_watermarks", "thread_watermark_retained", 34),
    )
    private val migration by lazy { checkNotNull(ConversationServingCompatibility::class.java.getResourceAsStream("/db/migration/V052__account_direct_conversations.sql")).use { it.readBytes().decodeToString() } }
    private val checksum by lazy { MessageDigest.getInstance("SHA-256").digest(migration.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }
}
