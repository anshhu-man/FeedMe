package com.feedme.server.reuse

import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only admission for the optional proposal service. Never installs grants, seeds
 * reviewed relationships or treats FORCE-RLS-hidden history as an empty result. Base
 * account, ingredient, recipe and planning compatibility is checked by the assembly. */
internal object ReuseServingCompatibility {
    fun check(c: Connection) {
        try {
            check(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Reuse compatibility interrupted")
            val sql = javaClass.getResourceAsStream("/db/migration/V051__account_reuse_proposals.sql")
                ?.use { it.readBytes().decodeToString() } ?: error("Missing reuse schema")
            val extension = javaClass.getResourceAsStream("/db/migration/V053__account_private_extension_erasure.sql")
                ?.use { it.readBytes().decodeToString() } ?: error("Missing reuse owner/erasure schema")
            c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=51").use { s ->
                s.executeQuery().use { check(it.next() && it.getString(1) == reuseSha(sql) && !it.next()) }
            }
            c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=53").use { s ->
                s.executeQuery().use { check(it.next() && it.getString(1) == reuseSha(extension) && !it.next()) }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use {
                    check(it.next() && it.getBoolean(1) && !it.next())
                }
                for (table in tables) s.executeQuery("SELECT * FROM $table WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
                for (table in listOf("planning.plans","platform.idempotency"))
                    s.executeQuery("SELECT * FROM $table WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
            }
            for (table in tables) c.prepareStatement("""
                SELECT r.relkind='r' AND r.relrowsecurity AND r.relforcerowsecurity,
                  NOT EXISTS(SELECT 1 FROM pg_catalog.pg_policy p WHERE p.polrelid=r.oid),
                  NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhparent=r.oid OR i.inhrelid=r.oid),
                  NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(COALESCE(r.relacl,pg_catalog.acldefault('r',r.relowner))) a WHERE a.grantee=0),
                  NOT EXISTS(SELECT 1 FROM pg_catalog.pg_attribute at, LATERAL pg_catalog.aclexplode(at.attacl) a WHERE at.attrelid=r.oid AND a.grantee=0)
                FROM pg_catalog.pg_class r WHERE r.oid=?::regclass
            """.trimIndent()).use { s ->
                s.setString(1,table); s.executeQuery().use { r -> check(r.next() && (1..5).all(r::getBoolean) && !r.next()) }
            }
            for ((name,tag,definer) in functions) c.prepareStatement("""
                SELECT p.prosrc,p.prosecdef,p.proconfig,p.prorettype='pg_catalog.trigger'::regtype,
                  p.pronargs=0 AND NOT p.proretset,p.proowner=r.relowner,
                  NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(COALESCE(p.proacl,pg_catalog.acldefault('f',p.proowner))) a WHERE a.grantee=0),
                  NOT pg_catalog.has_function_privilege(current_user,p.oid,'EXECUTE'),o.rolsuper OR o.rolbypassrls
                FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_class r ON r.oid='planning.reuse_requests'::regclass
                JOIN pg_catalog.pg_roles o ON o.oid=p.proowner WHERE p.oid=?::regprocedure
            """.trimIndent()).use { s ->
                s.setString(1,"$name()"); s.executeQuery().use { r ->
                    check(r.next()); val configs = (r.getArray(3)?.array as? Array<*>)?.map { it.toString().replace(" ","") }?.toSet()
                    val expected = setOf("search_path=pg_catalog,pg_temp") + if (definer) setOf("row_security=off") else emptySet()
                    val source = if (tag == "feedme_reuse_immutable") sql else extension
                    check(r.getString(1) == body(source,tag) && r.getBoolean(2) == definer && configs == expected &&
                        (4..8).all(r::getBoolean) && (!definer || r.getBoolean(9)) && !r.next())
                }
            }
            val expectedTriggers = tables.associateWith { table ->
                val base = if (table == "planning.reuse_windows") setOf(
                    Trigger("reuse_window_transition",27,"planning.guard_reuse_window()"),
                    Trigger("reuse_window_no_truncate",34,"planning.reject_reuse_evidence_mutation()"))
                else setOf(Trigger("reuse_evidence_immutable",27,if (table.startsWith("planning."))
                    "planning.guard_reuse_account_erasure()" else "planning.reject_reuse_evidence_mutation()"),
                    Trigger("reuse_evidence_no_truncate",34,"planning.reject_reuse_evidence_mutation()"))
                base + if (table.startsWith("planning.")) setOf(Trigger("reuse_owner_insert",7,"planning.guard_reuse_owner_insert()")) else emptySet()
            }
            for ((table,expected) in expectedTriggers) c.prepareStatement("""
                SELECT t.tgname,t.tgtype,t.tgfoid::regprocedure::text,t.tgenabled,t.tgqual IS NULL,
                  t.tgnargs=0 AND t.tgconstraint=0 AND NOT t.tgdeferrable AND NOT t.tginitdeferred
                FROM pg_catalog.pg_trigger t WHERE t.tgrelid=?::regclass AND NOT t.tgisinternal ORDER BY t.tgname
            """.trimIndent()).use { s ->
                s.setString(1,table); s.executeQuery().use { r ->
                    val actual = mutableSetOf<Trigger>(); while (r.next()) {
                        check(r.getString(4) == "O" && r.getBoolean(5) && r.getBoolean(6))
                        check(actual.add(Trigger(r.getString(1),r.getInt(2),r.getString(3))))
                    }; check(actual == expected)
                }
            }
            for ((table,privilege,columns) in writes) c.prepareStatement(
                "SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) names(column_name)").use { s ->
                s.setString(1,table);s.setString(2,privilege);s.setString(3,columns)
                s.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) }
            }
            // LOCK TABLE SHARE is a deliberate coarse publication fence. PostgreSQL
            // requires table UPDATE permission; immutable UPDATE guards above must remain
            // exact. This check grants nothing and does not permit relationship insertion.
            for (table in tables.take(2)) c.prepareStatement("SELECT pg_catalog.has_table_privilege(current_user,?::text,'UPDATE')").use { s ->
                s.setString(1,table);s.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) }
            }
            // A serving account must never acquire editorial publication/revocation
            // INSERT rights through a table, column or inherited grant.
            for (table in tables.take(2)) c.prepareStatement("""
                SELECT NOT pg_catalog.has_any_column_privilege(current_user,?::text,'INSERT'),
                  NOT pg_catalog.has_table_privilege(current_user,?::text,'DELETE'),
                  NOT pg_catalog.has_table_privilege(current_user,?::text,'TRUNCATE')
            """.trimIndent()).use { s ->
                (1..3).forEach { s.setString(it,table) }
                s.executeQuery().use { check(it.next() && (1..3).all(it::getBoolean) && !it.next()) }
            }
        } catch (f: ReuseFailure) { throw f }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (_: Exception) { reuseFail(ReuseFailureCode.NOT_CONFIGURED) }
    }
    private fun body(sql: String,tag: String): String {
        val delimiter="\$$tag\$"; val parts=sql.split(delimiter); check(parts.size==3);return parts[1]
    }
    private data class Trigger(val name:String,val type:Int,val function:String)
    private val tables=listOf("catalog.reuse_relationships","catalog.reuse_revocations","planning.reuse_requests","planning.reuse_pages","planning.reuse_windows")
    private val functions=listOf(Triple("planning.reject_reuse_evidence_mutation","feedme_reuse_immutable",false),
        Triple("planning.guard_reuse_window","feedme_reuse_window",true),Triple("planning.guard_reuse_owner_insert","feedme_reuse_owner",true),
        Triple("planning.guard_reuse_account_erasure","feedme_reuse_erasure",true))
    private val writes=listOf(
        Triple("planning.reuse_requests","INSERT","environment,actor_kind,principal_id,id,request_text,request_sha256,evidence_text,evidence_sha256,created_at,expires_at"),
        Triple("planning.reuse_pages","INSERT","environment,actor_kind,principal_id,command_key,principal_scope,operation_id,request_sha256,proposal_id,page_offset,page_limit,response_text,response_sha256"),
        Triple("planning.reuse_windows","INSERT","environment,actor_kind,principal_id,window_date,policy_sha256,maximum,used"),
        Triple("planning.reuse_windows","UPDATE","used"),
        Triple("platform.idempotency","INSERT","principal_scope,operation_id,key,request_hash,state,expires_at"),
        Triple("platform.idempotency","UPDATE","state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at"))
}
