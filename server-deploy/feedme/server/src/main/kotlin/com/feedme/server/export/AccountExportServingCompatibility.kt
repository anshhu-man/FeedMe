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
            val resource="/db/migration/V064__account_exports.sql"
            val hash=checkNotNull(javaClass.getResourceAsStream(resource)).use{MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString(""){b->"%02x".format(b.toInt() and 255)}}
            c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=64").use{s->s.executeQuery().use{check(it.next()&&it.getString(1)==hash&&!it.next())}}
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
        }catch(e:CancellationException){throw e}catch(e:InterruptedException){Thread.currentThread().interrupt();throw e}
        catch(_:Exception){throw ExportFailure(ExportFailureCode.NOT_CONFIGURED)}
    }
}
