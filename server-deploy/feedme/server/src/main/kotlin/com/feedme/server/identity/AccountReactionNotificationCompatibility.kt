package com.feedme.server.identity

import com.feedme.server.social.posts.PostReadServingCompatibility
import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only admission of the dedicated reaction consumer. It never grants privileges,
 * changes the global relay, or weakens the original message-notification guard. */
internal object AccountReactionNotificationCompatibility {
    fun check(c: Connection) {
        try {
            require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Reaction compatibility interrupted")
            PostReadServingCompatibility.check(c)
            c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=85").use { s ->
                s.executeQuery().use { r -> check(r.next() && r.getString(1) == hash(source) && !r.next()) }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use {
                    check(it.next() && it.getBoolean(1) && !it.next())
                }
                for (table in listOf("platform.account_reaction_notifications", "platform.notification_read_watermarks"))
                    s.executeQuery("SELECT * FROM $table WHERE false FOR UPDATE NOWAIT").use { check(!it.next()) }
                s.executeQuery("SELECT * FROM platform.consumer_inbox WHERE false").use { check(!it.next()) }
                for (table in listOf("platform.outbox", "social.post_reactions", "profile.notification_settings"))
                    s.executeQuery("SELECT * FROM $table WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
                s.executeQuery("""SELECT c.relkind='r' AND c.relrowsecurity AND c.relforcerowsecurity
                    AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid)
                    AND NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(c.relacl,pg_catalog.acldefault('r',c.relowner))) a WHERE a.grantee=0)
                    AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_attribute x CROSS JOIN LATERAL pg_catalog.aclexplode(x.attacl) a WHERE x.attrelid=c.oid AND a.grantee=0)
                    AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_constraint x WHERE x.conrelid=c.oid AND (NOT x.convalidated OR x.condeferrable))
                    FROM pg_catalog.pg_class c WHERE c.oid='platform.account_reaction_notifications'::regclass""").use {
                    check(it.next() && it.getBoolean(1) && !it.next())
                }
                s.executeQuery("""SELECT string_agg(attname,',' ORDER BY attnum) FROM pg_catalog.pg_attribute
                    WHERE attrelid='platform.account_reaction_notifications'::regclass AND attnum>0 AND NOT attisdropped""").use {
                    check(it.next() && it.getString(1) == "environment,recipient_user_id,id,event_id,actor_user_id,post_id,reaction_id,reaction_version,created_at,version,read_at,updated_at" && !it.next())
                }
                s.executeQuery("""SELECT p.prosrc,p.prosecdef,p.proconfig,p.prorettype='trigger'::regtype,
                    r.rolsuper OR r.rolbypassrls,
                    NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(p.proacl,pg_catalog.acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner),
                    p.proowner=(SELECT relowner FROM pg_catalog.pg_class WHERE oid='platform.account_reaction_notifications'::regclass)
                    FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_roles r ON r.oid=p.proowner
                    WHERE p.oid='platform.guard_reaction_notification()'::regprocedure""").use { r ->
                    check(r.next() && r.getString(1) == source.substringAfter(delimiter).substringBefore(delimiter) && r.getBoolean(2) && (4..7).all(r::getBoolean))
                    check((r.getArray(3).array as Array<*>).map { it.toString().replace(" ", "") }.toSet() ==
                        setOf("search_path=pg_catalog,pg_temp", "row_security=off") && !r.next())
                }
            }
            for ((name, type) in mapOf("account_reaction_notification_write" to 31, "account_reaction_notification_retained" to 34))
                c.prepareStatement("""SELECT tgtype,tgenabled,NOT tgisinternal,tgfoid='platform.guard_reaction_notification()'::regprocedure,
                    tgqual IS NULL,tgnargs=0,tgattr::text='' FROM pg_catalog.pg_trigger
                    WHERE tgrelid='platform.account_reaction_notifications'::regclass AND tgname=?""").use { s ->
                    s.setString(1, name); s.executeQuery().use { r ->
                        check(r.next() && r.getInt(1) == type && r.getString(2) == "O" && (3..7).all(r::getBoolean) && !r.next())
                    }
                }
            for ((table, privilege, columns) in writes) c.prepareStatement("""SELECT bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text))
                FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) n(column_name)""").use { s ->
                s.setString(1, table); s.setString(2, privilege); s.setString(3, columns)
                s.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { throw NotificationInboxFailure(NotificationInboxFailureCode.NOT_CONFIGURED) }
    }
    private val writes = listOf(
        Triple("platform.account_reaction_notifications", "INSERT", "environment,recipient_user_id,id,event_id,actor_user_id,post_id,reaction_id,reaction_version,created_at,updated_at"),
        Triple("platform.account_reaction_notifications", "UPDATE", "version,read_at,updated_at"),
        Triple("platform.notification_read_watermarks", "INSERT", "environment,user_id,updated_at"),
        Triple("platform.consumer_inbox", "INSERT", "consumer_name,event_id"))
    private const val delimiter = "\$feedme_reaction_notification\$"
    private val source by lazy { checkNotNull(AccountReactionNotificationCompatibility::class.java.getResourceAsStream(
        "/db/migration/V085__reaction_notifications.sql")).use { it.readBytes().decodeToString() } }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
