package com.feedme.server.identity

import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID

/** Explicit trusted operator configuration, NOT proof of a provider-side condition.
 * Accepted deletion UUIDs must never be reused or restored. The provider has no
 * conditional incarnation/delete primitive; old in-flight requests may still arrive.
 * No default configuration, environment loader or live approval is installed here.
 */
internal class AccountProviderRetryPolicy(
    internal val operationalRuleReference: String,
    internal val baseBackoffSeconds: Int = 60,
) {
    init {
        require(operationalRuleReference.isNotBlank() && operationalRuleReference.length <= 128 &&
            operationalRuleReference.none(Char::isISOControl))
        require(baseBackoffSeconds in 1..3600)
    }
    override fun toString() = "AccountProviderRetryPolicy([redacted])"
    companion object { const val REVISION = "feedme-auth-erasure-no-subject-reuse-v1" }
}

internal enum class AccountProviderRetryOutcome { READY, PROVIDER_ABSENT, WAITING, EXHAUSTED, LEASE_LOST }
internal class AccountProviderRetryAttempt internal constructor(
    internal val jobId: UUID, internal val parentAttemptId: UUID,
    internal val retryId: UUID, internal val ordinal: Int,
) {
    override fun toString() = "AccountProviderRetryAttempt([redacted])"
}
internal class AccountProviderRetryPreparation internal constructor(
    val outcome: AccountProviderRetryOutcome, val attempt: AccountProviderRetryAttempt? = null,
) {
    override fun toString() = "AccountProviderRetryPreparation($outcome)"
}

/** Opt-in bounded at-least-once retry adapter. Only durable accepted targets may be
 * retried, after a current same-database provider observation and persisted backoff.
 * One original + two retry dispatches maximum; prior observations never get rewritten.
 * Unknown commits propagate; the caller cannot infer whether an external call happened.
 */
internal class AccountProviderRetryStore(internal val provider: AccountProviderErasureStore,
    private val policy: AccountProviderRetryPolicy) {
    fun prepare(lease: AccountProviderErasureLease): AccountProviderRetryPreparation {
        valid(lease)
        val proposed = UUID.randomUUID()
        return provider.transactions.run { c ->
            checkCompatibility(c)
            val result = c.prepareStatement("SELECT * FROM erasure.prepare_account_provider_retry(?,?,?,?,?,?,?,?)").use { s ->
                bind(s, lease); s.setObject(6, proposed); s.setString(7, AccountProviderRetryPolicy.REVISION)
                s.setInt(8, policy.baseBackoffSeconds)
                s.executeQuery().use { r ->
                    if (!r.next()) incompatible()
                    val outcome = when (r.getString("outcome")) {
                        "ready" -> AccountProviderRetryOutcome.READY
                        "provider_absent" -> AccountProviderRetryOutcome.PROVIDER_ABSENT
                        "waiting" -> AccountProviderRetryOutcome.WAITING
                        "exhausted" -> AccountProviderRetryOutcome.EXHAUSTED
                        "lease_lost" -> AccountProviderRetryOutcome.LEASE_LOST
                        else -> incompatible()
                    }
                    val id = r.getObject("retry_id", UUID::class.java)
                    val ordinal = r.getInt("retry_ordinal"); val absentOrdinal = r.wasNull()
                    val attempt = if (outcome == AccountProviderRetryOutcome.READY) {
                        if (id == null || absentOrdinal || ordinal !in 2..3) incompatible()
                        AccountProviderRetryAttempt(lease.jobId, lease.attemptId, id, ordinal)
                    } else {
                        if (id != null || !absentOrdinal) incompatible()
                        null
                    }
                    if (r.next()) incompatible()
                    AccountProviderRetryPreparation(outcome, attempt)
                }
            }
            provider.revalidateProvider(c)
            result
        }
    }

    /** Only a known committed true allows one HTTP call for this exact retry intent. */
    fun markDispatched(lease: AccountProviderErasureLease, attempt: AccountProviderRetryAttempt): Boolean =
        mutate(lease, attempt, "SELECT erasure.dispatch_account_provider_retry(?,?,?,?,?,?)")

    fun record(lease: AccountProviderErasureLease, attempt: AccountProviderRetryAttempt,
        result: SupabaseAuthErasureResult): Boolean =
        mutate(lease, attempt, "SELECT erasure.record_account_provider_retry(?,?,?,?,?,?,?)") {
            it.setString(7, if (result == SupabaseAuthErasureResult.TRANSPORT_ACKNOWLEDGED) "acknowledged" else "outcome_unknown")
        }

    private fun mutate(lease: AccountProviderErasureLease, attempt: AccountProviderRetryAttempt,
        sql: String, extra: (PreparedStatement) -> Unit = {}): Boolean {
        valid(lease)
        require(attempt.jobId == lease.jobId && attempt.parentAttemptId == lease.attemptId && attempt.ordinal in 2..3)
        return provider.transactions.run { c ->
            checkCompatibility(c)
            val result = c.prepareStatement(sql).use { s ->
                bind(s, lease); s.setObject(6, attempt.retryId); extra(s)
                s.executeQuery().use { r ->
                    if (!r.next()) incompatible()
                    val value = r.getBoolean(1)
                    if (r.wasNull() || r.next()) incompatible()
                    value
                }
            }
            provider.revalidateProvider(c)
            result
        }
    }

    private fun valid(lease: AccountProviderErasureLease) {
        require(lease.environment == provider.environment && lease.generation > 0 &&
            lease.providerIssuer == SupabaseAuthErasureClient.APPROVED_ISSUER &&
            lease.action == AccountProviderErasureAction.RECONCILE)
    }
    private fun bind(s: PreparedStatement, lease: AccountProviderErasureLease) {
        s.setString(1, lease.environment); s.setObject(2, lease.jobId); s.setObject(3, lease.attemptId)
        s.setObject(4, lease.token); s.setLong(5, lease.generation)
    }

    private fun checkCompatibility(c: Connection) {
        provider.checkCompatibility(c)
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=47").use { s ->
            s.executeQuery().use { r -> if (!r.next() || r.getString(1) != resourceHash || r.next()) incompatible() }
        }
        c.createStatement().use { s ->
            s.execute("LOCK TABLE erasure.provider_deletion_retries IN ACCESS SHARE MODE")
            s.executeQuery("""
                SELECT c.relkind='r' AND c.relrowsecurity AND c.relforcerowsecurity
                    AND c.relowner=p.relowner
                    AND NOT EXISTS(SELECT 1 FROM pg_policy WHERE polrelid=c.oid)
                    AND NOT EXISTS(SELECT 1 FROM pg_inherits WHERE inhrelid=c.oid OR inhparent=c.oid)
                    AND NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
                        LEFT JOIN pg_roles r ON r.oid=a.grantee WHERE a.grantee<>c.relowner AND
                          (a.grantee=0 OR r.rolname='feedme_api' OR a.privilege_type<>'SELECT' OR a.is_grantable))
                    AND NOT EXISTS(SELECT 1 FROM pg_attribute att CROSS JOIN LATERAL aclexplode(att.attacl) a
                        WHERE att.attrelid=c.oid AND a.grantee<>c.relowner)
                FROM pg_class c JOIN pg_class p ON p.oid='erasure.provider_deletions'::regclass
                WHERE c.oid='erasure.provider_deletion_retries'::regclass
            """.trimIndent()).use { r -> if (!r.next() || !r.getBoolean(1) || r.next()) incompatible() }
        }
        for ((signature, tag, returnType) in functions) c.prepareStatement("""
            SELECT p.prosrc,p.prosecdef,p.proconfig,p.proowner=c.relowner,l.lanname,
                p.prorettype::regtype::text,o.rolsuper OR o.rolbypassrls,
                NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                    LEFT JOIN pg_roles r ON r.oid=a.grantee WHERE a.grantee<>p.proowner AND
                    (a.grantee=0 OR r.rolname='feedme_api' OR a.is_grantable OR p.prorettype='trigger'::regtype))
            FROM pg_proc p JOIN pg_language l ON l.oid=p.prolang JOIN pg_roles o ON o.oid=p.proowner
            JOIN pg_class c ON c.oid='erasure.provider_deletion_retries'::regclass WHERE p.oid=?::regprocedure
        """.trimIndent()).use { s ->
            s.setString(1, signature)
            s.executeQuery().use { r ->
                if (!r.next() || r.getString(1) != body(tag) || !r.getBoolean(2) ||
                    (r.getArray(3)?.array as? Array<*>)?.toSet() != setOf("search_path=pg_catalog, pg_temp", "row_security=off") ||
                    !r.getBoolean(4) || r.getString(5) != "plpgsql" || r.getString(6) != returnType ||
                    !r.getBoolean(7) || !r.getBoolean(8) || r.next()) incompatible()
            }
        }
        c.createStatement().use { s -> s.executeQuery("""
            SELECT t.tgtype,t.tgenabled::text,t.tgisinternal,t.tgqual,t.tgattr::text,t.tgnargs,t.tgconstraint,
                p.proowner=c.relowner
            FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_class c ON c.oid=t.tgrelid
            WHERE t.tgrelid='erasure.provider_deletion_retries'::regclass
                AND t.tgfoid='erasure.guard_account_provider_retry()'::regprocedure ORDER BY t.tgtype
        """.trimIndent()).use { r ->
            val types = mutableListOf<Int>()
            while (r.next()) {
                if (r.getString(2) !in setOf("O", "A") || r.getBoolean(3) || r.getObject(4) != null ||
                    r.getString(5) != "" || r.getInt(6) != 0 || r.getLong(7) != 0L || !r.getBoolean(8)) incompatible()
                types += r.getInt(1)
            }
            if (types != listOf(31, 34)) incompatible()
        } }
    }

    private fun incompatible(): Nothing = throw SQLException("Provider retry authority differs from reviewed source")
    override fun toString() = "AccountProviderRetryStore([redacted])"
    companion object {
        private val resource = checkNotNull(AccountProviderRetryStore::class.java.getResourceAsStream(
            "/db/migration/V047__account_provider_erasure_retries.sql")).use { it.readBytes() }
        private val resourceText = resource.toString(Charsets.UTF_8)
        private val resourceHash = MessageDigest.getInstance("SHA-256").digest(resource)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private val functions = listOf(
            Triple("erasure.guard_account_provider_retry()", "feedme_provider_retry_guard", "trigger"),
            Triple("erasure.prepare_account_provider_retry(text,uuid,uuid,uuid,bigint,uuid,text,integer)", "feedme_provider_retry_prepare", "record"),
            Triple("erasure.dispatch_account_provider_retry(text,uuid,uuid,uuid,bigint,uuid)", "feedme_provider_retry_dispatch", "boolean"),
            Triple("erasure.record_account_provider_retry(text,uuid,uuid,uuid,bigint,uuid,text)", "feedme_provider_retry_record", "boolean"),
        )
        private fun body(tag: String): String {
            val marker = "$" + tag + "$"
            return resourceText.substringAfter("AS $marker", "").substringBefore("$marker;", "").also { check(it.isNotEmpty()) }
        }
    }
}
