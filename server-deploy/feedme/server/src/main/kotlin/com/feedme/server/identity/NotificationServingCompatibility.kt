package com.feedme.server.identity

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only optional capability check. No installation or privilege escalation. */
internal object NotificationServingCompatibility {
    fun check(c:Connection) {
        try {
            require(!c.autoCommit&&c.transactionIsolation==Connection.TRANSACTION_READ_COMMITTED)
            if(Thread.currentThread().isInterrupted)throw InterruptedException("Notification compatibility interrupted")
            for((v,source)in sources)c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=?").use{s->s.setInt(1,v);s.executeQuery().use{check(it.next()&&it.getString(1)==hash(source)&&!it.next())}}
            c.createStatement().use{s->
                s.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use{check(it.next()&&it.getBoolean(1)&&!it.next())}
                s.executeQuery("SELECT * FROM profile.notification_settings WHERE false FOR UPDATE NOWAIT").use{check(!it.next())}
                s.executeQuery("SELECT c.relkind='r' AND c.relrowsecurity AND c.relforcerowsecurity AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) AND NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(c.relacl,pg_catalog.acldefault('r',c.relowner))) a WHERE a.grantee=0) FROM pg_catalog.pg_class c WHERE c.oid='profile.notification_settings'::regclass").use{check(it.next()&&it.getBoolean(1)&&!it.next())}
                s.executeQuery("SELECT p.prosrc,p.prosecdef,p.proconfig,p.proowner=c.relowner,p.prorettype='trigger'::regtype,NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(p.proacl,pg_catalog.acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner) FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_class c ON c.oid='profile.notification_settings'::regclass WHERE p.oid='profile.guard_notification_settings()'::regprocedure").use{r->
                    check(r.next()&&r.getString(1)==sources.getValue(60).substringAfter("\$feedme_notification_settings\$").substringBefore("\$feedme_notification_settings\$")&&r.getBoolean(2)&&(4..6).all(r::getBoolean))
                    check((r.getArray(3).array as Array<*>).map{it.toString().replace(" ","")}.toSet()==setOf("search_path=pg_catalog,pg_temp","row_security=off")&&!r.next())
                }
            }
            for((name,type)in listOf("notification_settings_write" to 31,"notification_settings_retained" to 34))c.prepareStatement("SELECT tgtype,tgenabled,NOT tgisinternal,tgfoid='profile.guard_notification_settings()'::regprocedure,tgqual IS NULL,tgnargs=0 FROM pg_catalog.pg_trigger WHERE tgrelid='profile.notification_settings'::regclass AND tgname=?").use{s->s.setString(1,name);s.executeQuery().use{r->check(r.next()&&r.getInt(1)==type&&r.getString(2)=="O"&&(3..6).all(r::getBoolean)&&!r.next())}}
            for((table,privilege,columns)in writes)c.prepareStatement("SELECT bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) n(column_name)").use{s->s.setString(1,table);s.setString(2,privilege);s.setString(3,columns);s.executeQuery().use{check(it.next()&&it.getBoolean(1)&&!it.next())}}
        }catch(e:CancellationException){throw e}catch(e:InterruptedException){Thread.currentThread().interrupt();throw e}catch(_:Exception){throw NotificationFailure(NotificationFailureCode.NOT_CONFIGURED)}
    }
    private val writes=listOf(
        Triple("profile.notification_settings","INSERT","environment,user_id,id"),
        Triple("profile.notification_settings","UPDATE","replies,reactions,invitations,remixes,cooking_reminders,quiet_start_local,quiet_end_local,time_zone,version,updated_at"),
        Triple("platform.idempotency","INSERT","principal_scope,operation_id,key,request_hash,state,expires_at"),
        Triple("platform.idempotency","UPDATE","state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at"))
    private val sources by lazy { mapOf(59 to "/db/migration/V059__account_notification_settings.sql",60 to "/db/migration/V060__notification_settings_erasure.sql").mapValues{(_,path)->checkNotNull(NotificationServingCompatibility::class.java.getResourceAsStream(path)).use{it.readBytes().decodeToString()}} }
    private fun hash(source:String)=MessageDigest.getInstance("SHA-256").digest(source.encodeToByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
}
