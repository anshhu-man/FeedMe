package com.feedme.server.identity

import com.feedme.server.db.PgTransactions
import com.feedme.server.media.supabase.SupabaseStorageErasureTarget
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

internal interface AccountMediaSourceCapturePersistence {
    fun capture(lease: AccountErasureWorkLease, mediaId: UUID): AccountMediaSourceIntent?
}

/** Explicit worker-only accepted-account capture. The exact lease, original v1 source,
 * tombstone, original cleanup rows, worker provenance and V049 intent are one transaction.
 * A lost commit acknowledgement authorizes no provider call: explicit retry returns the
 * retained original. No client command receipt/event or completed erasure is fabricated.
 */
internal class AccountMediaSourceCaptureStore(private val environment: String, private val transactions: PgTransactions) :
    AccountMediaSourceCapturePersistence {
    private val source = AccountMediaSourceErasureStore(environment, transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    override fun capture(lease: AccountErasureWorkLease, mediaId: UUID): AccountMediaSourceIntent? {
        require(lease.environment == environment && lease.generation > 0 &&
            lease.providerIssuer == SupabaseAuthErasureClient.APPROVED_ISSUER)
        val proposed = UUID.randomUUID()
        return transactions.run { c ->
            checkCompatibility(c)
            c.prepareStatement("SELECT * FROM erasure.capture_account_media_source(?,?,?,?,?,?)").use { s ->
                s.setString(1, environment); s.setObject(2, lease.jobId); s.setObject(3, lease.token)
                s.setLong(4, lease.generation); s.setObject(5, mediaId); s.setObject(6, proposed)
                s.executeQuery().use { r ->
                    if (!r.next()) null else {
                        if (r.getObject("capture_id", UUID::class.java) == null || r.getObject("user_id", UUID::class.java) != lease.accountId) incompatible()
                        val dispatched = r.getBoolean("already_dispatched")
                        if (r.wasNull()) incompatible()
                        AccountMediaSourceIntent(lease.jobId, r.getObject("intent_id", UUID::class.java),
                            SupabaseStorageErasureTarget(environment, r.getString("bucket"), lease.accountId, mediaId, r.getString("object_key")),
                            dispatched).also { if (r.next()) incompatible() }
                    }
                }
            }
        }
    }

    internal fun checkCompatibility(c: Connection) {
        source.checkCompatibility(c)
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=50").use { s ->
            s.executeQuery().use { r -> if (!r.next() || r.getString(1) != resourceHash || r.next()) incompatible() }
        }
        c.createStatement().use { it.execute("LOCK TABLE erasure.media_source_captures IN ACCESS SHARE MODE") }
        c.createStatement().use { s -> s.executeQuery("""
            SELECT c.relrowsecurity AND c.relforcerowsecurity AND c.relkind='r'
              AND NOT EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid)
              AND NOT EXISTS(SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid)
              AND NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
                WHERE a.grantee=0 OR a.grantee IN (SELECT oid FROM pg_roles WHERE rolname='feedme_api')
                  OR (a.grantee<>c.relowner AND (a.privilege_type<>'SELECT' OR a.is_grantable)))
              AND NOT EXISTS(SELECT 1 FROM pg_attribute x CROSS JOIN LATERAL aclexplode(x.attacl) a
                WHERE x.attrelid=c.oid AND (a.grantee=0 OR a.grantee<>c.relowner))
            FROM pg_class c WHERE c.oid='erasure.media_source_captures'::regclass
        """.trimIndent()).use { r -> if (!r.next() || !r.getBoolean(1) || r.next()) incompatible() } }
        for ((signature, tag) in functions) c.prepareStatement("""
            SELECT p.prosrc,p.prosecdef,p.proconfig,p.proowner=c.relowner,l.lanname,o.rolsuper OR o.rolbypassrls,
              NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                WHERE a.grantee=0 OR a.grantee IN (SELECT oid FROM pg_roles WHERE rolname='feedme_api')
                  OR (a.grantee<>p.proowner AND (? OR a.privilege_type<>'EXECUTE' OR a.is_grantable))),
              p.prorettype::regtype::text,p.proretset
            FROM pg_proc p JOIN pg_language l ON l.oid=p.prolang JOIN pg_roles o ON o.oid=p.proowner
              JOIN pg_class c ON c.oid='erasure.media_source_captures'::regclass WHERE p.oid=?::regprocedure
        """.trimIndent()).use { s ->
            val guard = signature.contains(".guard_")
            s.setBoolean(1, guard); s.setString(2, signature)
            s.executeQuery().use { r ->
                if (!r.next() || r.getString(1) != body(tag) || !r.getBoolean(2) ||
                    (r.getArray(3)?.array as? Array<*>)?.toSet() != setOf("search_path=pg_catalog, pg_temp", "row_security=off") ||
                    !r.getBoolean(4) || r.getString(5) != "plpgsql" || !r.getBoolean(6) || !r.getBoolean(7) ||
                    r.getString(8) != (if (guard) "trigger" else "record") || r.getBoolean(9) == guard || r.next()) incompatible()
            }
        }
        for ((name, type) in listOf("account_media_capture_guard" to 31, "account_media_capture_retained" to 34)) c.prepareStatement("""
            SELECT t.tgtype=? AND t.tgenabled IN ('O','A') AND NOT t.tgisinternal AND t.tgqual IS NULL
              AND t.tgattr::text='' AND t.tgnargs=0 AND t.tgconstraint=0
              AND t.tgfoid='erasure.guard_account_media_capture()'::regprocedure
            FROM pg_trigger t WHERE t.tgrelid='erasure.media_source_captures'::regclass AND t.tgname=?
        """.trimIndent()).use { s ->
            s.setInt(1, type); s.setString(2, name)
            s.executeQuery().use { r -> if (!r.next() || !r.getBoolean(1) || r.next()) incompatible() }
        }
    }
    override fun toString() = "AccountMediaSourceCaptureStore(<redacted>)"
    private fun incompatible(): Nothing = throw SQLException("Account media source capture authority differs from reviewed source")
    companion object {
        private val resource = checkNotNull(AccountMediaSourceCaptureStore::class.java.getResourceAsStream(
            "/db/migration/V050__account_media_source_capture.sql")).use { it.readBytes() }
        private val resourceText = resource.toString(Charsets.UTF_8)
        private val resourceHash = MessageDigest.getInstance("SHA-256").digest(resource).joinToString("") { "%02x".format(it.toInt() and 255) }
        internal val functions = listOf("erasure.guard_account_media_capture()" to "feedme_media_capture_guard",
            "erasure.capture_account_media_source(text,uuid,uuid,bigint,uuid,uuid)" to "feedme_media_capture")
        private fun body(tag: String): String {
            val marker = "$" + tag + "$"
            return resourceText.substringAfter("AS $marker", "").substringBefore("$marker;", "").also { check(it.isNotEmpty()) }
        }
    }
}
