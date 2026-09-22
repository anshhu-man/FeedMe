package com.feedme.server.identity

import com.feedme.server.db.PgTransactions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

internal enum class AccountErasureHoldReason(val storageValue: String) {
    RETRY("retry"), UNATTRIBUTED_EVENTS("unattributed_events"), UNKNOWN_RECEIPTS("unknown_receipts"),
    RELATED_DATA("related_data"), IDENTITY_CONFLICT("identity_conflict");
}

/** Committed inventory-work claim, not permission to erase or to call a provider.
 * Token/generation are private fencing data. Never return this object to an app client. */
internal class AccountErasureWorkLease internal constructor(
    internal val environment: String,
    internal val jobId: UUID,
    internal val accountId: UUID,
    internal val principalId: UUID,
    internal val providerIssuer: String,
    internal val providerSubject: UUID,
    internal val token: UUID,
    internal val generation: Long,
    val expiresAt: Instant,
    val previousHold: AccountErasureHoldReason?,
) {
    override fun toString() = "AccountErasureWorkLease([redacted])"
}

/** Durable scheduling only. No route, default worker, provider call, destructive stage or
 * completion transition is installed by this store. The separate V044 core purge independently
 * lock/check the immutable job, lease generation and full inventory in its own transaction.
 * A lost claim-commit acknowledgement yields no usable lease: let it expire, never start an
 * external effect or invent a successor on that exception. PgTransactions owns all commits.
 */
internal class AccountErasureWorkStore(internal val environment: String, internal val transactions: PgTransactions) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun claim(leaseSeconds: Int = 30): AccountErasureWorkLease? {
        require(leaseSeconds in 1..300)
        val token = UUID.randomUUID()
        return transactions.run { c ->
            checkCompatibility(c)
            c.prepareStatement("SELECT * FROM identity.claim_account_erasure_work(?,?,?)").use { s ->
                s.setString(1, environment); s.setObject(2, token); s.setInt(3, leaseSeconds)
                s.executeQuery().use { r ->
                    if (!r.next()) null else AccountErasureWorkLease(environment,
                        r.getObject("job_id", UUID::class.java), r.getObject("user_id", UUID::class.java),
                        r.getObject("principal_id", UUID::class.java), r.getString("provider_issuer"),
                        r.getObject("provider_subject", UUID::class.java), token, r.getLong("generation"),
                        r.getObject("lease_expires_at", OffsetDateTime::class.java).toInstant(),
                        r.getString("last_reason").let { value ->
                            if (value == "none") null else AccountErasureHoldReason.entries.singleOrNull { it.storageValue == value }
                                ?: throw SQLException("Unknown account erasure work reason")
                        }).also { if (r.next()) throw SQLException("Unexpected account erasure lease count") }
                }
            }
        }
    }

    /** Fixed reason codes only; optional user-entered deletion reasons/tokens never enter this ledger. */
    fun defer(lease: AccountErasureWorkLease, reason: AccountErasureHoldReason, retryAfterSeconds: Int): Boolean {
        require(lease.environment == environment && retryAfterSeconds in 1..86400)
        return transactions.run { c ->
            checkCompatibility(c)
            c.prepareStatement("SELECT identity.defer_account_erasure_work(?,?,?,?,?,?)").use { s ->
                s.setString(1, environment); s.setObject(2, lease.jobId); s.setObject(3, lease.token)
                s.setLong(4, lease.generation); s.setString(5, reason.storageValue); s.setInt(6, retryAfterSeconds)
                s.executeQuery().use { r -> check(r.next()); r.getBoolean(1) }
            }
        }
    }

    internal fun checkCompatibility(c: Connection) {
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=43").use { s ->
            s.executeQuery().use { r ->
                if (!r.next() || r.getString(1) != resourceHash || r.next()) incompatible()
            }
        }
        // Preserve attachments against ordinary concurrent ALTER/DROP. Trusted deploys must
        // still coordinate CREATE OR REPLACE FUNCTION; table locks cannot prevent that.
        c.createStatement().use { it.execute("LOCK TABLE identity.account_erasure_work,identity.account_deletion_jobs IN ACCESS SHARE MODE") }
        c.createStatement().use { s -> s.executeQuery("""
            SELECT c.relrowsecurity AND c.relforcerowsecurity AND c.relkind='r'
                AND NOT EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid)
                AND NOT EXISTS(SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid)
                AND NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a WHERE a.grantee=0)
            FROM pg_class c WHERE c.oid IN ('identity.account_erasure_work'::regclass,'identity.account_deletion_jobs'::regclass)
        """.trimIndent()).use { r ->
            var checked = 0
            while (r.next()) { if (!r.getBoolean(1)) incompatible(); checked++ }
            if (checked != 2) incompatible()
        } }
        // A retained accepted job is the sole source of the work binding. Neither
        // the worker's read-lock privilege nor a disabled job guard may rewrite it.
        for ((name, type) in listOf("account_deletion_job_immutable" to 27, "account_deletion_job_retained" to 34))
            c.prepareStatement("""
                SELECT t.tgtype=?,t.tgenabled IN ('O','A') AND NOT t.tgisinternal AND t.tgqual IS NULL
                    AND t.tgattr::text='' AND t.tgnargs=0 AND t.tgconstraint=0,
                    p.prosrc,p.prosecdef,p.proconfig,p.proowner=c.relowner,
                    NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner)
                FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_class c ON c.oid=t.tgrelid
                WHERE t.tgrelid='identity.account_deletion_jobs'::regclass AND t.tgname=?
                    AND t.tgfoid='identity.keep_account_deletion_acceptance_immutable()'::regprocedure
            """.trimIndent()).use { s ->
                s.setInt(1, type); s.setString(2, name)
                s.executeQuery().use { r ->
                    if (!r.next() || !r.getBoolean(1) || !r.getBoolean(2) ||
                        r.getString(3).filterNot(Char::isWhitespace) != acceptanceGuard.filterNot(Char::isWhitespace) ||
                        r.getBoolean(4) || (r.getArray(5)?.array as? Array<*>)?.toSet() != setOf("search_path=pg_catalog, pg_temp") ||
                        !r.getBoolean(6) || !r.getBoolean(7)) incompatible()
                }
            }
        for ((signature, tag) in functions) c.prepareStatement("""
            SELECT p.prosrc,p.prosecdef,p.proconfig,p.proowner=c.relowner,l.lanname,
                (o.rolsuper OR o.rolbypassrls),
                NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0)
            FROM pg_proc p JOIN pg_language l ON l.oid=p.prolang JOIN pg_roles o ON o.oid=p.proowner
            JOIN pg_class c ON c.oid='identity.account_erasure_work'::regclass WHERE p.oid=?::regprocedure
        """.trimIndent()).use { s ->
            s.setString(1, signature)
            s.executeQuery().use { r ->
                if (!r.next() || r.getString(1) != functionBody(tag) || !r.getBoolean(2) ||
                    (r.getArray(3)?.array as? Array<*>)?.toSet() != setOf("search_path=pg_catalog, pg_temp", "row_security=off") ||
                    !r.getBoolean(4) || r.getString(5) != "plpgsql" || !r.getBoolean(6) || !r.getBoolean(7)) incompatible()
            }
        }
        c.createStatement().use { s -> s.executeQuery("""
            SELECT t.tgtype=19 AND t.tgenabled IN ('O','A') AND NOT t.tgisinternal AND t.tgqual IS NULL
                AND t.tgattr::text='' AND t.tgnargs=0 AND t.tgconstraint=0,
                p.prosrc,p.prosecdef,p.proconfig,p.proowner=c.relowner,
                NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner)
            FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_class c ON c.oid=t.tgrelid
            WHERE t.tgrelid='identity.account_erasure_work'::regclass AND t.tgname='account_erasure_work_binding'
                AND t.tgfoid='identity.keep_account_erasure_work_binding()'::regprocedure
        """.trimIndent()).use { r ->
            if (!r.next() || !r.getBoolean(1) || r.getString(2) != functionBody("feedme_work_binding") || r.getBoolean(3) ||
                (r.getArray(4)?.array as? Array<*>)?.toSet() != setOf("search_path=pg_catalog, pg_temp") ||
                !r.getBoolean(5) || !r.getBoolean(6)) incompatible()
        } }
    }

    private fun incompatible(): Nothing = throw SQLException("Account erasure work authority differs from reviewed source")

    companion object {
        private const val acceptanceGuard = "BEGIN RAISE EXCEPTION 'Account deletion acceptance is immutable' USING ERRCODE='23514'; END;"
        private val resource = checkNotNull(AccountErasureWorkStore::class.java.getResourceAsStream(
            "/db/migration/V043__account_erasure_work_leases.sql")).use { it.readBytes() }
        private val resourceText = resource.toString(Charsets.UTF_8)
        private val resourceHash = MessageDigest.getInstance("SHA-256").digest(resource).joinToString("") { "%02x".format(it.toInt() and 255) }
        private val functions = listOf(
            "identity.claim_account_erasure_work(text,uuid,integer)" to "feedme_work_claim",
            "identity.defer_account_erasure_work(text,uuid,uuid,bigint,text,integer)" to "feedme_work_defer")
        private fun functionBody(tag: String): String {
            val marker = "$" + tag + "$"
            return resourceText.substringAfter("AS $marker", "").substringBefore("$marker;", "")
                .also { check(it.isNotEmpty()) }
        }
    }
}
