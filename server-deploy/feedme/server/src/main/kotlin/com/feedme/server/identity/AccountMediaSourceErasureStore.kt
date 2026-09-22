package com.feedme.server.identity

import com.feedme.server.db.PgTransactions
import com.feedme.server.media.supabase.SupabaseStorageEraseResult
import com.feedme.server.media.supabase.SupabaseStorageErasureTarget
import com.feedme.server.media.supabase.SupabaseStoragePresence
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID

/** Private committed original. A presence or DELETE result is never upload settlement. */
internal class AccountMediaSourceIntent internal constructor(
    internal val jobId: UUID, internal val intentId: UUID, internal val target: SupabaseStorageErasureTarget,
    val alreadyDispatched: Boolean,
) { override fun toString() = "AccountMediaSourceIntent(<redacted>)" }

/** Narrow DB-only seam. Each successful result includes a known commit acknowledgement;
 * implementations must not run network I/O or retry an ambiguous commit. */
internal interface AccountMediaSourceErasurePersistence {
    fun prepare(lease: AccountErasureWorkLease, mediaId: UUID): AccountMediaSourceIntent?
    fun markDispatched(lease: AccountErasureWorkLease, original: AccountMediaSourceIntent): Boolean
    fun recordErase(lease: AccountErasureWorkLease, original: AccountMediaSourceIntent, result: SupabaseStorageEraseResult): Boolean
    fun prepareInspection(lease: AccountErasureWorkLease, original: AccountMediaSourceIntent, observationId: UUID): Boolean
    fun recordInspection(lease: AccountErasureWorkLease, original: AccountMediaSourceIntent, observationId: UUID, result: SupabaseStoragePresence): Boolean
}

/** Worker-only V049 adapter. Caller explicitly supplies an inventory lease for an accepted
 * deleting account and an exact media ID; SQL derives every provider target from retained
 * immutable media rows. No object lists, bucket sweeps, route, default worker or completion.
 * Deployment must separately attest this DataSource and provider to the approved project.
 */
internal class AccountMediaSourceErasureStore(private val environment: String, private val transactions: PgTransactions) :
    AccountMediaSourceErasurePersistence {
    private val work = AccountErasureWorkStore(environment, transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    override fun prepare(lease: AccountErasureWorkLease, mediaId: UUID): AccountMediaSourceIntent? {
        valid(lease)
        val proposed = UUID.randomUUID()
        return transactions.run { c ->
            checkCompatibility(c)
            c.prepareStatement("SELECT * FROM erasure.prepare_account_media_source(?,?,?,?,?,?)").use { s ->
                bind(s, lease, mediaId, proposed)
                s.executeQuery().use { r ->
                    if (!r.next()) null else {
                        val account = r.getObject("user_id", UUID::class.java)
                        if (account != lease.accountId) incompatible()
                        AccountMediaSourceIntent(lease.jobId, r.getObject("intent_id", UUID::class.java),
                            SupabaseStorageErasureTarget(environment, r.getString("bucket"), account, mediaId, r.getString("object_key")),
                            r.getBoolean("already_dispatched")).also { if (r.next()) incompatible() }
                    }
                }
            }
        }
    }

    override fun markDispatched(lease: AccountErasureWorkLease, original: AccountMediaSourceIntent) =
        mutate(lease, original, "SELECT erasure.dispatch_account_media_source(?,?,?,?,?,?)")

    override fun recordErase(lease: AccountErasureWorkLease, original: AccountMediaSourceIntent, result: SupabaseStorageEraseResult) =
        mutate(lease, original, "SELECT erasure.record_account_media_source(?,?,?,?,?,?,?)") { it.setString(7, result.name.lowercase()) }

    override fun prepareInspection(lease: AccountErasureWorkLease, original: AccountMediaSourceIntent, observationId: UUID) =
        mutate(lease, original, "SELECT erasure.prepare_account_media_observation(?,?,?,?,?,?,?)") { it.setObject(7, observationId) }

    override fun recordInspection(lease: AccountErasureWorkLease, original: AccountMediaSourceIntent, observationId: UUID, result: SupabaseStoragePresence) =
        mutate(lease, original, "SELECT erasure.record_account_media_observation(?,?,?,?,?,?,?,?)") {
            it.setObject(7, observationId); it.setString(8, result.name.lowercase())
        }

    private fun mutate(lease: AccountErasureWorkLease, original: AccountMediaSourceIntent, sql: String,
        extra: (PreparedStatement) -> Unit = {}): Boolean {
        valid(lease)
        require(original.jobId == lease.jobId && original.target.environment == environment && original.target.ownerId == lease.accountId)
        return transactions.run { c ->
            checkCompatibility(c)
            // Re-read through the exact-authority definer, not a count-only or FORCE
            // RLS-hidden direct SELECT. A forged target never authorizes provider I/O.
            c.prepareStatement("SELECT * FROM erasure.prepare_account_media_source(?,?,?,?,?,?)").use { s ->
                bind(s, lease, original.target.mediaId, original.intentId)
                s.executeQuery().use { r ->
                    if (!r.next()) return@run false
                    if (r.getObject("intent_id", UUID::class.java) != original.intentId ||
                        r.getObject("user_id", UUID::class.java) != original.target.ownerId ||
                        r.getString("bucket") != original.target.bucket || r.getString("object_key") != original.target.objectKey || r.next()) incompatible()
                }
            }
            c.prepareStatement(sql).use { s ->
                bind(s, lease, original.target.mediaId, original.intentId); extra(s)
                s.executeQuery().use { r ->
                    if (!r.next()) incompatible()
                    val result = r.getBoolean(1)
                    if (r.wasNull() || r.next()) incompatible()
                    result
                }
            }
        }
    }

    private fun valid(lease: AccountErasureWorkLease) {
        require(lease.environment == environment && lease.generation > 0 &&
            lease.providerIssuer == SupabaseAuthErasureClient.APPROVED_ISSUER)
    }
    private fun bind(s: PreparedStatement, lease: AccountErasureWorkLease, media: UUID, intent: UUID) {
        s.setString(1, environment); s.setObject(2, lease.jobId); s.setObject(3, lease.token)
        s.setLong(4, lease.generation); s.setObject(5, media); s.setObject(6, intent)
    }

    internal fun checkCompatibility(c: Connection) {
        work.checkCompatibility(c)
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=49").use { s ->
            s.executeQuery().use { r -> if (!r.next() || r.getString(1) != resourceHash || r.next()) incompatible() }
        }
        c.createStatement().use { it.execute("LOCK TABLE erasure.media_source_deletions,erasure.media_source_observations IN ACCESS SHARE MODE") }
        for (table in tables) c.prepareStatement("""
            SELECT c.relrowsecurity AND c.relforcerowsecurity AND c.relkind='r'
              AND NOT EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid)
              AND NOT EXISTS(SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid)
              AND NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
                WHERE a.grantee=0 OR a.grantee IN (SELECT oid FROM pg_roles WHERE rolname='feedme_api')
                  OR (a.grantee<>c.relowner AND (a.privilege_type<>'SELECT' OR a.is_grantable)))
              AND NOT EXISTS(SELECT 1 FROM pg_attribute x CROSS JOIN LATERAL aclexplode(x.attacl) a
                WHERE x.attrelid=c.oid AND (a.grantee=0 OR a.grantee<>c.relowner))
            FROM pg_class c WHERE c.oid=?::regclass
        """.trimIndent()).use { s ->
            s.setString(1, table); s.executeQuery().use { r -> if (!r.next() || !r.getBoolean(1) || r.next()) incompatible() }
        }
        for ((signature, tag) in functions) c.prepareStatement("""
            SELECT p.prosrc,p.prosecdef,p.proconfig,p.proowner=c.relowner,l.lanname,o.rolsuper OR o.rolbypassrls,
              NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                WHERE a.grantee=0 OR a.grantee IN (SELECT oid FROM pg_roles WHERE rolname='feedme_api')
                  OR (a.grantee<>p.proowner AND (? OR a.privilege_type<>'EXECUTE' OR a.is_grantable))),
              p.prorettype::regtype::text,p.proretset
            FROM pg_proc p JOIN pg_language l ON l.oid=p.prolang JOIN pg_roles o ON o.oid=p.proowner
              JOIN pg_class c ON c.oid='erasure.media_source_deletions'::regclass WHERE p.oid=?::regprocedure
        """.trimIndent()).use { s ->
            val private = signature.contains(".guard_") || signature.contains(".lock_")
            s.setBoolean(1, private); s.setString(2, signature)
            s.executeQuery().use { r ->
                val prepare = signature.startsWith("erasure.prepare_account_media_source(")
                val expectedType = when { signature.contains(".guard_") -> "trigger"; prepare -> "record"; else -> "boolean" }
                if (!r.next() || r.getString(1) != body(tag) || !r.getBoolean(2) ||
                    (r.getArray(3)?.array as? Array<*>)?.toSet() != setOf("search_path=pg_catalog, pg_temp", "row_security=off") ||
                    !r.getBoolean(4) || r.getString(5) != "plpgsql" || !r.getBoolean(6) || !r.getBoolean(7) ||
                    r.getString(8) != expectedType || r.getBoolean(9) != prepare || r.next()) incompatible()
            }
        }
        for ((table, prefix, function) in listOf(
            Triple(tables[0], "account_media_source", "erasure.guard_account_media_source()"),
            Triple(tables[1], "account_media_observation", "erasure.guard_account_media_observation()"))) {
            for ((suffix, type) in listOf("guard" to 31, "retained" to 34)) c.prepareStatement("""
                SELECT t.tgtype=? AND t.tgenabled IN ('O','A') AND NOT t.tgisinternal AND t.tgqual IS NULL
                  AND t.tgattr::text='' AND t.tgnargs=0 AND t.tgconstraint=0 AND t.tgfoid=?::regprocedure
                FROM pg_trigger t WHERE t.tgrelid=?::regclass AND t.tgname=?
            """.trimIndent()).use { s ->
                s.setInt(1, type); s.setString(2, function); s.setString(3, table); s.setString(4, "${prefix}_$suffix")
                s.executeQuery().use { r -> if (!r.next() || !r.getBoolean(1) || r.next()) incompatible() }
            }
        }
    }
    override fun toString() = "AccountMediaSourceErasureStore(<redacted>)"
    private fun incompatible(): Nothing = throw SQLException("Account media source erasure authority differs from reviewed source")

    companion object {
        private val resource = checkNotNull(AccountMediaSourceErasureStore::class.java.getResourceAsStream(
            "/db/migration/V049__account_media_source_erasure.sql")).use { it.readBytes() }
        private val resourceText = resource.toString(Charsets.UTF_8)
        private val resourceHash = MessageDigest.getInstance("SHA-256").digest(resource).joinToString("") { "%02x".format(it.toInt() and 255) }
        private val tables = listOf("erasure.media_source_deletions", "erasure.media_source_observations")
        internal val functions = listOf(
            "erasure.lock_account_media_source(text,uuid,uuid,bigint,uuid)" to "feedme_media_source_lock",
            "erasure.guard_account_media_source()" to "feedme_media_source_guard",
            "erasure.guard_account_media_observation()" to "feedme_media_observation_guard",
            "erasure.prepare_account_media_source(text,uuid,uuid,bigint,uuid,uuid)" to "feedme_media_source_prepare",
            "erasure.dispatch_account_media_source(text,uuid,uuid,bigint,uuid,uuid)" to "feedme_media_source_dispatch",
            "erasure.record_account_media_source(text,uuid,uuid,bigint,uuid,uuid,text)" to "feedme_media_source_record",
            "erasure.prepare_account_media_observation(text,uuid,uuid,bigint,uuid,uuid,uuid)" to "feedme_media_observation_prepare",
            "erasure.record_account_media_observation(text,uuid,uuid,bigint,uuid,uuid,uuid,text)" to "feedme_media_observation_record")
        private fun body(tag: String): String {
            val marker = "$" + tag + "$"
            return resourceText.substringAfter("AS $marker", "").substringBefore("$marker;", "").also { check(it.isNotEmpty()) }
        }
    }
}
