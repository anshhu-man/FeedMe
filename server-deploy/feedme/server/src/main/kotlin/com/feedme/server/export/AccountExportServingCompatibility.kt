package com.feedme.server.export

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only feature admission, not a grant installer. Current role must see its actual
 * forced-RLS inventory; an empty RLS projection cannot masquerade as an empty export. */
internal object AccountExportServingCompatibility {
    fun check(c:Connection,worker:Boolean=false) {
        try {
            require(!c.autoCommit && c.transactionIsolation==Connection.TRANSACTION_READ_COMMITTED)
            for ((version, source) in sources) c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=?").use { s ->
                s.setInt(1, version); s.executeQuery().use { check(it.next() && it.getString(1) == hash(source) && !it.next()) }
            }
            if(worker) {
                // A complete export must include actual owned Inbox read metadata. Do not
                // turn a missing/filtered new table into an apparently empty section.
                val inboxResource="/db/migration/V075__account_notification_inbox.sql"
                val inboxHash=checkNotNull(javaClass.getResourceAsStream(inboxResource)).use {
                    MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString(""){b->"%02x".format(b.toInt() and 255)}
                }
                c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=75").use { s ->
                    s.executeQuery().use { check(it.next()&&it.getString(1)==inboxHash&&!it.next()) }
                }
            }
            c.createStatement().use{s->
                s.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use{check(it.next()&&it.getBoolean(1)&&!it.next())}
                for(table in listOf("platform.account_export_jobs","platform.account_export_artifacts")) {
                    s.executeQuery("SELECT * FROM $table WHERE false FOR SHARE NOWAIT").close()
                    val writes=if(table.endsWith("jobs"))if(worker)listOf("UPDATE")else listOf("INSERT","UPDATE") else if(worker)listOf("INSERT","UPDATE")else emptyList()
                    for(right in writes)s.executeQuery("SELECT has_table_privilege(current_user,'$table','$right')").use{check(it.next()&&it.getBoolean(1))}
                }
                s.executeQuery("SELECT environment,user_id FROM identity.account_deletion_jobs WHERE false").close()
                if(worker) {
                    s.executeQuery("SELECT environment,recipient_user_id,id,version,created_at,updated_at,read_at FROM platform.account_notifications WHERE false FOR SHARE NOWAIT").close()
                    s.executeQuery("SELECT environment,user_id,through_created_at,read_at,version,updated_at FROM platform.notification_read_watermarks WHERE false FOR SHARE NOWAIT").close()
                }
            }
            function(c, "platform.guard_account_export_jobs()", "platform.account_export_jobs", "\$feedme_export_jobs\$")
            function(c, "platform.guard_account_export_artifacts()", "platform.account_export_artifacts", "\$feedme_export_artifacts\$")
            for ((table, name, type, signature) in triggers) c.prepareStatement("""SELECT tgtype,tgenabled,NOT tgisinternal,
                tgfoid=?::regprocedure,tgqual IS NULL,tgnargs=0,tgattr::text='',tgconstraint=0
                FROM pg_catalog.pg_trigger WHERE tgrelid=?::regclass AND tgname=?""").use { s ->
                s.setString(1, signature); s.setString(2, table); s.setString(3, name)
                s.executeQuery().use { r -> check(r.next() && r.getInt(1) == type && r.getString(2) == "O" && (3..8).all(r::getBoolean) && !r.next()) }
            }
        }catch(e:CancellationException){throw e}catch(e:InterruptedException){Thread.currentThread().interrupt();throw e}
        catch(_:Exception){throw ExportFailure(ExportFailureCode.NOT_CONFIGURED)}
    }

    private fun function(c:Connection,signature:String,table:String,delimiter:String) {
        c.prepareStatement("""SELECT p.prosrc,NOT p.prosecdef,p.proconfig,p.prorettype='trigger'::regtype,
            p.proowner=t.relowner,l.lanname='plpgsql',
            NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(p.proacl,pg_catalog.acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner)
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang
            JOIN pg_catalog.pg_class t ON t.oid=?::regclass WHERE p.oid=?::regprocedure""").use { s ->
            s.setString(1,table);s.setString(2,signature);s.executeQuery().use { r ->
                val source=sources.getValue(89).substringAfter(delimiter).substringBefore(delimiter)
                check(r.next()&&r.getString(1)==source&&r.getBoolean(2)&&(4..7).all(r::getBoolean)&&!r.next())
                check((r.getArray(3).array as Array<*>).map { it.toString().replace(" ","") }.toSet()==setOf("search_path=pg_catalog,pg_temp"))
            }
        }
    }

    private val triggers=listOf(
        listOf("platform.account_export_jobs","account_export_jobs_guard","31","platform.guard_account_export_jobs()"),
        listOf("platform.account_export_jobs","account_export_jobs_truncate","34","platform.guard_account_export_jobs()"),
        listOf("platform.account_export_artifacts","account_export_artifacts_guard","31","platform.guard_account_export_artifacts()"),
        listOf("platform.account_export_artifacts","account_export_artifacts_truncate","34","platform.guard_account_export_artifacts()"),
    ).map { (table,name,type,signature)->ExportTrigger(table,name,type.toInt(),signature) }
    private data class ExportTrigger(val table:String,val name:String,val type:Int,val signature:String)
    private val sources by lazy { mapOf(
        64 to "/db/migration/V064__account_exports.sql",
        89 to "/db/migration/V089__never_dispatched_export_erasure.sql",
    ).mapValues { (_,path)->checkNotNull(AccountExportServingCompatibility::class.java.getResourceAsStream(path)).use { it.readBytes().decodeToString() } } }
    private fun hash(source:String)=MessageDigest.getInstance("SHA-256").digest(source.encodeToByteArray()).joinToString(""){b->"%02x".format(b.toInt() and 255)}
}
