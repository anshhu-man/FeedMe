package com.feedme.server.identity

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Optional, read-only admission. Installing the schema and authorizing its narrow runtime
 * privileges are separate operator actions; this check never does either. */
internal object NotificationInboxServingCompatibility {
    fun check(c: Connection) {
        try {
            require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Inbox compatibility interrupted")
            for ((version, source) in sources) c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=?").use { s ->
                s.setInt(1, version); s.executeQuery().use { r -> check(r.next() && r.getString(1) == hash(source) && !r.next()) }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use {
                    check(it.next() && it.getBoolean(1) && !it.next())
                }
                // Real table/column admission without reading any account data or locking a row.
                for (table in tables) s.executeQuery("SELECT * FROM $table WHERE false FOR UPDATE NOWAIT").use { check(!it.next()) }
                for (table in listOf("social.direct_threads", "social.thread_messages", "profile.notification_settings"))
                    s.executeQuery("SELECT * FROM $table WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
            }
            for (table in tables) c.prepareStatement("""SELECT c.relkind='r' AND c.relrowsecurity AND c.relforcerowsecurity
                AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid)
                AND NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(c.relacl,pg_catalog.acldefault('r',c.relowner))) a WHERE a.grantee=0)
                AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_attribute x CROSS JOIN LATERAL pg_catalog.aclexplode(x.attacl) a
                    WHERE x.attrelid=c.oid AND a.grantee=0)
                AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_constraint x WHERE x.conrelid=c.oid AND (NOT x.convalidated OR x.condeferrable))
                FROM pg_catalog.pg_class c WHERE c.oid=?::regclass""").use { s ->
                s.setString(1, table); s.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) }
            }
            function(c, "platform.guard_notification_inbox()", "trigger", sources.getValue(75), "\$feedme_notification_inbox\$")
            // This shared erasure capability is replaced as later reviewed account-
            // deletion slices are added. Pin the current body, not the migration that
            // originally introduced Inbox, or a fully current schema is rejected.
            function(c, "identity.account_erasure_delete_allowed(oid,text,uuid,uuid)", "boolean", sources.getValue(89), "\$feedme_core_allowed\$")
            for ((table, name, type) in triggers) c.prepareStatement("""SELECT tgtype,tgenabled,NOT tgisinternal,
                tgfoid='platform.guard_notification_inbox()'::regprocedure,tgqual IS NULL,tgnargs=0,tgattr::text=''
                FROM pg_catalog.pg_trigger WHERE tgrelid=?::regclass AND tgname=?""").use { s ->
                s.setString(1, table); s.setString(2, name); s.executeQuery().use { r ->
                    check(r.next() && r.getInt(1) == type && r.getString(2) == "O" && (3..7).all(r::getBoolean) && !r.next())
                }
            }
            for ((table, columns) in shapes) c.prepareStatement("""SELECT string_agg(attname,',' ORDER BY attnum)
                FROM pg_catalog.pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped""").use { s ->
                s.setString(1, table); s.executeQuery().use { check(it.next() && it.getString(1) == columns && !it.next()) }
            }
            for ((table, privilege, columns) in writes) c.prepareStatement("""SELECT bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text))
                FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) n(column_name)""").use { s ->
                s.setString(1, table); s.setString(2, privilege); s.setString(3, columns)
                s.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (_: Exception) { throw NotificationInboxFailure(NotificationInboxFailureCode.NOT_CONFIGURED) }
    }

    private fun function(c: Connection, signature: String, returnType: String, source: String, delimiter: String) {
        c.prepareStatement("""SELECT p.prosrc,p.prosecdef,p.proconfig,p.prorettype=?::regtype,
            r.rolsuper OR r.rolbypassrls,
            NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(p.proacl,pg_catalog.acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner),
            p.proowner=(SELECT relowner FROM pg_catalog.pg_class WHERE oid='platform.account_notifications'::regclass),
            p.proowner=(SELECT relowner FROM pg_catalog.pg_class WHERE oid='platform.notification_read_watermarks'::regclass)
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_roles r ON r.oid=p.proowner WHERE p.oid=?::regprocedure""").use { s ->
            s.setString(1, returnType); s.setString(2, signature); s.executeQuery().use { r ->
                check(r.next() && r.getString(1) == source.substringAfter(delimiter).substringBefore(delimiter) && r.getBoolean(2) && (4..8).all(r::getBoolean))
                check((r.getArray(3).array as Array<*>).map { it.toString().replace(" ", "") }.toSet() ==
                    setOf("search_path=pg_catalog,pg_temp", "row_security=off") && !r.next())
            }
        }
    }
    private val tables = listOf("platform.account_notifications", "platform.notification_read_watermarks")
    private val triggers = listOf(
        Triple(tables[0], "account_notification_write", 31), Triple(tables[0], "account_notification_retained", 34),
        Triple(tables[1], "notification_watermark_write", 31), Triple(tables[1], "notification_watermark_retained", 34))
    private val shapes = mapOf(
        tables[0] to "environment,recipient_user_id,id,event_id,thread_id,message_id,sender_user_id,created_at,version,read_at,updated_at",
        tables[1] to "environment,user_id,through_created_at,read_at,version,updated_at")
    private val writes = listOf(
        Triple(tables[0], "INSERT", "environment,recipient_user_id,id,event_id,thread_id,message_id,sender_user_id,created_at,updated_at"),
        Triple(tables[0], "UPDATE", "version,read_at,updated_at"),
        Triple(tables[1], "INSERT", "environment,user_id,updated_at"),
        Triple(tables[1], "UPDATE", "through_created_at,read_at,version,updated_at"),
        Triple("platform.idempotency", "INSERT", "principal_scope,operation_id,key,request_hash,state,expires_at"),
        Triple("platform.idempotency", "UPDATE", "state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at"))
    private val sources by lazy {
        mapOf(
            75 to "/db/migration/V075__account_notification_inbox.sql",
            76 to "/db/migration/V076__notification_inbox_erasure_inventory.sql",
            88 to "/db/migration/V088__reaction_actor_erasure.sql",
            89 to "/db/migration/V089__never_dispatched_export_erasure.sql",
        )
            .mapValues { (_, path) -> checkNotNull(NotificationInboxServingCompatibility::class.java.getResourceAsStream(path)).use { it.readBytes().decodeToString() } }
    }
    private fun hash(source: String) = MessageDigest.getInstance("SHA-256").digest(source.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
