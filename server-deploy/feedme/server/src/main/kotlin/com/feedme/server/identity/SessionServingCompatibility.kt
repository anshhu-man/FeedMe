package com.feedme.server.identity

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Optional capability admission, never installs roles/grants/provider objects. The
 * existing SupabasePostgresAuthority still checks the reviewed provider deployment
 * in every account authorization; this adds the exact targeted writer and cascade. */
internal object SessionServingCompatibility {
    fun check(c:Connection) {
        try {
            require(!c.autoCommit && c.transactionIsolation==Connection.TRANSACTION_READ_COMMITTED)
            if(Thread.currentThread().isInterrupted)throw InterruptedException("Session compatibility interrupted")
            c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=57").use{s->s.executeQuery().use{check(it.next()&&it.getString(1)==checksum&&!it.next())}}
            c.prepareStatement("""SELECT p.prosrc,p.prosecdef,p.prokind,p.prorettype='pg_catalog.int8'::regtype,p.proconfig,
                p.proowner=t.relowner,r.rolsuper OR r.rolbypassrls,
                NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(p.proacl,pg_catalog.acldefault('f',p.proowner))) a WHERE a.grantee=0),
                pg_catalog.has_function_privilege(current_user,p.oid,'EXECUTE'),
                pg_catalog.has_table_privilege(p.proowner,'auth.sessions','DELETE'),
                pg_catalog.has_column_privilege(p.proowner,'auth.sessions','id','SELECT') AND pg_catalog.has_column_privilege(p.proowner,'auth.sessions','user_id','SELECT'),
                pg_catalog.has_column_privilege(p.proowner,'auth.refresh_tokens','session_id','SELECT'),
                pg_catalog.has_schema_privilege(current_user,'identity','USAGE')
                FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_roles r ON r.oid=p.proowner
                JOIN pg_catalog.pg_class t ON t.oid='identity.users'::regclass
                WHERE p.oid=pg_catalog.to_regprocedure(?)""").use{s->s.setString(1,signature);s.executeQuery().use{r->
                check(r.next()&&r.getString(1)==body&&r.getBoolean(2)&&r.getString(3)=="f"&&r.getBoolean(4)&&(6..13).all(r::getBoolean))
                check((r.getArray(5).array as Array<*>).map{it.toString().replace(" ","")}.toSet()==setOf("search_path=pg_catalog,pg_temp","row_security=off")&&!r.next())
            }}
            c.createStatement().use{s->
                s.executeQuery("SELECT id,version,created_at,updated_at,platform,device_label,last_seen_at,revoked_at,provider_session_id,user_id,environment FROM identity.device_sessions WHERE false FOR UPDATE NOWAIT").use{check(!it.next())}
                s.executeQuery("""SELECT count(*) FROM pg_catalog.pg_constraint k
                    WHERE k.conrelid=pg_catalog.to_regclass('auth.refresh_tokens') AND k.confrelid=pg_catalog.to_regclass('auth.sessions')
                    AND k.contype='f' AND k.confdeltype='c' AND k.convalidated AND NOT k.condeferrable
                    AND k.conkey=ARRAY[(SELECT attnum FROM pg_catalog.pg_attribute WHERE attrelid=k.conrelid AND attname='session_id' AND NOT attisdropped)]::smallint[]
                    AND k.confkey=ARRAY[(SELECT attnum FROM pg_catalog.pg_attribute WHERE attrelid=k.confrelid AND attname='id' AND NOT attisdropped)]::smallint[]
                    AND (SELECT count(*) FROM pg_catalog.pg_trigger WHERE tgconstraint=k.oid AND tgisinternal AND tgenabled IN('O','A'))=4""").use{check(it.next()&&it.getInt(1)==1&&!it.next())}
            }
            for((table,privilege,columns)in writes)c.prepareStatement("SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) n(column_name)").use{s->
                s.setString(1,table);s.setString(2,privilege);s.setString(3,columns);s.executeQuery().use{check(it.next()&&it.getBoolean(1)&&!it.next())}
            }
        }catch(e:CancellationException){throw e}catch(e:InterruptedException){Thread.currentThread().interrupt();throw e}
        catch(_:Exception){throw SessionFailure(SessionFailureCode.NOT_CONFIGURED)}
    }
    private val writes=listOf(
        Triple("platform.idempotency","INSERT","principal_scope,operation_id,key,request_hash,state,expires_at"),
        Triple("platform.idempotency","UPDATE","state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at"),
        Triple("platform.outbox","INSERT","event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id"),
    )
    private const val signature="identity.revoke_account_device_session(text,text,uuid,uuid,uuid,bigint)"
    private val migration by lazy { checkNotNull(SessionServingCompatibility::class.java.getResourceAsStream("/db/migration/V057__targeted_account_session_revocation.sql")).use{it.readBytes().decodeToString()} }
    private val body by lazy { migration.substringAfter("\$feedme_session_revoke\$").substringBefore("\$feedme_session_revoke\$") }
    private val checksum by lazy { MessageDigest.getInstance("SHA-256").digest(migration.encodeToByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)} }
}
