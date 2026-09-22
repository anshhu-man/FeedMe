package com.feedme.server.identity

import com.feedme.server.db.PgTransactions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal enum class AccountProviderErasureAction { DISPATCH, RECONCILE }
internal enum class AccountProviderErasureObservation { PROVIDER_ABSENT, PROVIDER_PRESENT, LEASE_LOST }

/** Committed private work lease, not an app response or permission for an arbitrary target. */
internal class AccountProviderErasureLease internal constructor(
    internal val environment: String,
    internal val jobId: UUID,
    internal val attemptId: UUID,
    internal val accountId: UUID,
    internal val providerIssuer: String,
    internal val providerSubject: UUID,
    internal val token: UUID,
    internal val generation: Long,
    val expiresAt: Instant,
    val action: AccountProviderErasureAction,
) {
    override fun toString() = "AccountProviderErasureLease([redacted])"
}

/** DB-only durable intent, single dispatch boundary and exact-target reconciliation.
 * All methods own their transaction. A lost claim/dispatch commit acknowledgement
 * must propagate without authorizing HTTP. Later claims reconcile an already marked
 * dispatch; they never blindly repeat DELETE. This is not full erasure completion.
 * Deployment explicitly binds this DataSource to the approved issuer; DB names alone
 * cannot attest a Supabase tenant. There is no credential loader or public route.
 */
internal class AccountProviderErasureStore(internal val environment: String,
    internal val transactions: PgTransactions, private val authority: SupabasePostgresAuthority) {
    private val core = AccountErasureCoreStore(environment, transactions)
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(authority.deployment.verification.issuer == SupabaseAuthErasureClient.APPROVED_ISSUER)
    }

    fun claim(leaseSeconds: Int = 60): AccountProviderErasureLease? {
        require(leaseSeconds in 1..300)
        val proposedAttempt = UUID.randomUUID(); val token = UUID.randomUUID()
        return transactions.run { c ->
            checkCompatibility(c)
            val result = c.prepareStatement("SELECT * FROM erasure.claim_account_provider_erasure(?,?,?,?)").use { s ->
                s.setString(1, environment); s.setObject(2, proposedAttempt); s.setObject(3, token); s.setInt(4, leaseSeconds)
                s.executeQuery().use { r ->
                    if (!r.next()) null else AccountProviderErasureLease(environment,
                        r.getObject("job_id", UUID::class.java), r.getObject("attempt_id", UUID::class.java),
                        r.getObject("user_id", UUID::class.java), r.getString("provider_issuer"),
                        r.getObject("provider_subject", UUID::class.java), token, r.getLong("generation"),
                        r.getObject("lease_expires_at", OffsetDateTime::class.java).toInstant(),
                        when (r.getString("action")) {
                            "dispatch" -> AccountProviderErasureAction.DISPATCH
                            "reconcile" -> AccountProviderErasureAction.RECONCILE
                            else -> incompatible()
                        }).also {
                            valid(it)
                            if (r.next()) incompatible()
                        }
                }
            }
            checkProviderProjectors(c); authority.checkCompatibility(c)
            result
        }
    }

    /** Only a known committed true grants the first single HTTP attempt. */
    fun markDispatched(lease: AccountProviderErasureLease): Boolean = mutate(lease,
        "SELECT erasure.dispatch_account_provider_erasure(?,?,?,?,?)")

    /** Transport ACK is saved as evidence only, never inferred provider or whole deletion. */
    fun record(lease: AccountProviderErasureLease, result: SupabaseAuthErasureResult): Boolean =
        mutate(lease, "SELECT erasure.record_account_provider_erasure(?,?,?,?,?,?)") {
            it.setString(6, if (result == SupabaseAuthErasureResult.TRANSPORT_ACKNOWLEDGED) "acknowledged" else "outcome_unknown")
        }

    fun reconcile(lease: AccountProviderErasureLease, retryAfterSeconds: Int = 60): AccountProviderErasureObservation {
        valid(lease); require(retryAfterSeconds in 1..86400)
        return transactions.run { c ->
            checkCompatibility(c)
            // Validate the actual reviewed provider schema/projectors in this transaction.
            // The SQL operation itself observes the retained job's exact provider subject;
            // there is no caller-supplied "absent" boolean or HTTP-status shortcut.
            val result = c.prepareStatement("SELECT erasure.reconcile_account_provider_erasure(?,?,?,?,?,?)").use { s ->
                bind(s, lease); s.setInt(6, retryAfterSeconds)
                s.executeQuery().use { r ->
                    if (!r.next()) incompatible()
                    val value = when (r.getString(1)) {
                        "provider_absent" -> AccountProviderErasureObservation.PROVIDER_ABSENT
                        "provider_present" -> AccountProviderErasureObservation.PROVIDER_PRESENT
                        "lease_lost" -> AccountProviderErasureObservation.LEASE_LOST
                        else -> incompatible()
                    }
                    if (r.next()) incompatible()
                    value
                }
            }
            checkProviderProjectors(c); authority.checkCompatibility(c)
            result
        }
    }

    private fun mutate(lease: AccountProviderErasureLease, sql: String,
        extra: (java.sql.PreparedStatement) -> Unit = {}): Boolean {
        valid(lease)
        return transactions.run { c ->
            checkCompatibility(c)
            val result = c.prepareStatement(sql).use { s ->
                bind(s, lease); extra(s)
                s.executeQuery().use { r ->
                    if (!r.next()) incompatible()
                    val result = r.getBoolean(1)
                    if (r.wasNull() || r.next()) incompatible()
                    result
                }
            }
            checkProviderProjectors(c); authority.checkCompatibility(c)
            result
        }
    }

    private fun bind(s: java.sql.PreparedStatement, lease: AccountProviderErasureLease) {
        s.setString(1, environment); s.setObject(2, lease.jobId); s.setObject(3, lease.attemptId)
        s.setObject(4, lease.token); s.setLong(5, lease.generation)
    }
    private fun valid(lease: AccountProviderErasureLease) {
        require(lease.environment == environment && lease.generation > 0 &&
            lease.providerIssuer == authority.deployment.verification.issuer)
    }

    internal fun checkCompatibility(c: Connection) {
        // Fresh reviewed provider metadata is also a pre-dispatch gate, not something
        // first discovered to be invalid only after an irreversible network request.
        checkProviderProjectors(c); authority.checkCompatibility(c)
        core.checkCompatibility(c)
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=45").use { s ->
            s.executeQuery().use { r -> if (!r.next() || r.getString(1) != resourceHash || r.next()) incompatible() }
        }
        c.createStatement().use { s ->
            s.execute("LOCK TABLE erasure.provider_deletions IN ACCESS SHARE MODE")
            s.executeQuery("""
                SELECT c.relkind='r' AND c.relrowsecurity AND c.relforcerowsecurity
                    AND NOT EXISTS(SELECT 1 FROM pg_policy WHERE polrelid=c.oid)
                    AND NOT EXISTS(SELECT 1 FROM pg_inherits WHERE inhrelid=c.oid OR inhparent=c.oid)
                    AND NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
                        LEFT JOIN pg_roles r ON r.oid=a.grantee WHERE a.grantee<>c.relowner AND
                          (a.grantee=0 OR r.rolname='feedme_api' OR a.privilege_type<>'SELECT' OR a.is_grantable))
                    AND NOT EXISTS(SELECT 1 FROM pg_attribute att CROSS JOIN LATERAL aclexplode(att.attacl) a
                        WHERE att.attrelid=c.oid AND a.grantee<>c.relowner)
                FROM pg_class c WHERE c.oid='erasure.provider_deletions'::regclass
            """.trimIndent()).use { r -> if (!r.next() || !r.getBoolean(1) || r.next()) incompatible() }
        }
        for ((signature, tag, returnType) in functions) c.prepareStatement("""
            SELECT p.prosrc,p.prosecdef,p.proconfig,p.proowner=c.relowner,l.lanname,
                p.prorettype::regtype::text,o.rolsuper OR o.rolbypassrls,
                NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                    LEFT JOIN pg_roles r ON r.oid=a.grantee WHERE a.grantee<>p.proowner AND
                    (a.grantee=0 OR r.rolname='feedme_api' OR a.is_grantable OR p.prorettype='trigger'::regtype))
            FROM pg_proc p JOIN pg_language l ON l.oid=p.prolang JOIN pg_roles o ON o.oid=p.proowner
            JOIN pg_class c ON c.oid='erasure.provider_deletions'::regclass WHERE p.oid=?::regprocedure
        """.trimIndent()).use { s ->
            s.setString(1, signature)
            s.executeQuery().use { r ->
                if (!r.next() || r.getString(1) != body(tag) || !r.getBoolean(2) ||
                    (r.getArray(3)?.array as? Array<*>)?.toSet() != setOf("search_path=pg_catalog, pg_temp", "row_security=off") ||
                    !r.getBoolean(4) || r.getString(5) != "plpgsql" || r.getString(6) != returnType ||
                    !r.getBoolean(7) || !r.getBoolean(8) || r.next()) incompatible()
            }
        }
        // Guard attachments are verified below against the additive source's exact events.
        c.createStatement().use { s -> s.executeQuery("""
            SELECT t.tgtype,t.tgenabled::text,t.tgisinternal,t.tgqual,t.tgattr::text,t.tgnargs,t.tgconstraint,
                p.proowner=c.relowner
            FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_class c ON c.oid=t.tgrelid
            WHERE t.tgrelid='erasure.provider_deletions'::regclass
                AND t.tgfoid='erasure.guard_account_provider_deletion()'::regprocedure ORDER BY t.tgtype
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

    internal fun revalidateProvider(c: Connection) {
        checkProviderProjectors(c); authority.checkCompatibility(c)
    }

    /** A changed user_facts returning no rows cannot be accepted as provider absence.
     * All seven invoked compatibility/authority projections are pinned before execution.
     * Trusted deployments must still coordinate function replacement; table locks cannot
     * freeze CREATE OR REPLACE FUNCTION throughout an operator's concurrent upgrade.
     */
    private fun checkProviderProjectors(c: Connection) {
        for ((signature, name, strict, returnType) in projectors) c.prepareStatement("""
            SELECT p.prosrc,p.prosecdef,p.proconfig,p.proowner=c.relowner,l.lanname,
                p.prorettype::regtype::text,p.provolatile::text,p.proisstrict,p.proretset,
                o.rolsuper OR o.rolbypassrls,
                NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                    WHERE a.grantee=0 OR (a.grantee<>p.proowner AND a.is_grantable))
            FROM pg_proc p JOIN pg_language l ON l.oid=p.prolang JOIN pg_roles o ON o.oid=p.proowner
            JOIN pg_class c ON c.oid='erasure.provider_deletions'::regclass WHERE p.oid=?::regprocedure
        """.trimIndent()).use { s ->
            s.setString(1, signature)
            s.executeQuery().use { r ->
                if (!r.next() || r.getString(1) != projectorBody(name) || !r.getBoolean(2) ||
                    (r.getArray(3)?.array as? Array<*>)?.toSet() != setOf("search_path=pg_catalog, pg_temp", "row_security=off") ||
                    !r.getBoolean(4) || r.getString(5) != "plpgsql" || r.getString(6) != returnType ||
                    r.getString(7) != "v" || r.getBoolean(8) != strict || r.getBoolean(9) != (name != "schema_lock") ||
                    !r.getBoolean(10) || !r.getBoolean(11) || r.next()) incompatible()
            }
        }
    }

    private fun incompatible(): Nothing = throw SQLException("Provider erasure authority differs from reviewed source")
    override fun toString() = "AccountProviderErasureStore([redacted])"
    companion object {
        private val resource = checkNotNull(AccountProviderErasureStore::class.java.getResourceAsStream(
            "/db/migration/V045__account_provider_erasure.sql")).use { it.readBytes() }
        private val resourceText = resource.toString(Charsets.UTF_8)
        private val resourceHash = MessageDigest.getInstance("SHA-256").digest(resource).joinToString("") { "%02x".format(it.toInt() and 255) }
        private val functions = listOf(
            Triple("erasure.guard_account_provider_deletion()", "feedme_provider_guard", "trigger"),
            Triple("erasure.claim_account_provider_erasure(text,uuid,uuid,integer)", "feedme_provider_claim", "record"),
            Triple("erasure.dispatch_account_provider_erasure(text,uuid,uuid,uuid,bigint)", "feedme_provider_dispatch", "boolean"),
            Triple("erasure.record_account_provider_erasure(text,uuid,uuid,uuid,bigint,text)", "feedme_provider_record", "boolean"),
            Triple("erasure.reconcile_account_provider_erasure(text,uuid,uuid,uuid,bigint,integer)", "feedme_provider_reconcile", "text"),
        )
        private data class Projector(val signature: String, val name: String, val strict: Boolean, val returnType: String)
        private val projectors = listOf(
            Projector("feedme_auth_access.schema_lock()", "schema_lock", false, "void"),
            Projector("feedme_auth_access.migration_versions()", "migration_versions", false, "text"),
            Projector("feedme_auth_access.user_facts(uuid)", "user_facts", true, "record"),
            Projector("feedme_auth_access.factor_facts(uuid)", "factor_facts", true, "text"),
            Projector("feedme_auth_access.session_facts(uuid)", "session_facts", true, "record"),
            Projector("feedme_auth_access.amr_facts(uuid)", "amr_facts", true, "record"),
            Projector("feedme_auth_access.password_facts(uuid,uuid)", "password_facts", true, "record"),
        )
        private val projectorResource = checkNotNull(AccountProviderErasureStore::class.java.getResourceAsStream(
            "/db/provider/supabase-authority.sql")).bufferedReader().use { it.readText() }
        private fun projectorBody(name: String): String {
            val marker = "$" + "feedme" + "$"
            return projectorResource.substringAfter("CREATE FUNCTION feedme_auth_access.$name(", "")
                .substringAfter("AS $marker", "").substringBefore("$marker;", "").also { check(it.isNotEmpty()) }
        }
        private fun body(tag: String): String {
            val marker = "$" + tag + "$"
            return resourceText.substringAfter("AS $marker", "").substringBefore("$marker;", "").also { check(it.isNotEmpty()) }
        }
    }
}

internal enum class AccountProviderErasureRun { NOT_CONFIGURED, IDLE, PROVIDER_ABSENT, RECONCILIATION_PENDING, RETRY_EXHAUSTED, LEASE_LOST }

/** Explicit single step only. No launch-on-construction, scheduler, secrets or live activation.
 * Provider absence is a durable exact-identity observation, NOT all-data/full-F49 completion.
 * Cancellation/unknown commit propagate. Never put network I/O inside a retried transaction.
 */
internal class AccountProviderErasureWorker(private val store: AccountProviderErasureStore,
    private val client: SupabaseAuthErasureClient, private val databaseDispatcher: CoroutineDispatcher,
    private val retryStore: AccountProviderRetryStore? = null) {
    init { require(retryStore == null || retryStore.provider === store) }
    suspend fun runOne(): AccountProviderErasureRun {
        currentCoroutineContext().ensureActive()
        if (client.configuredIssuer != SupabaseAuthErasureClient.APPROVED_ISSUER) return AccountProviderErasureRun.NOT_CONFIGURED
        val lease = runInterruptible(databaseDispatcher) { store.claim(60) } ?: return AccountProviderErasureRun.IDLE
        if (lease.action == AccountProviderErasureAction.DISPATCH) {
            if (!runInterruptible(databaseDispatcher) { store.markDispatched(lease) }) return AccountProviderErasureRun.LEASE_LOST
            currentCoroutineContext().ensureActive()
            val result = client.erase(lease.providerSubject.toString())
            if (!runInterruptible(databaseDispatcher) { store.record(lease, result) }) return AccountProviderErasureRun.LEASE_LOST
        } else if (retryStore != null) {
            val prepared = runInterruptible(databaseDispatcher) { retryStore.prepare(lease) }
            when (prepared.outcome) {
                AccountProviderRetryOutcome.PROVIDER_ABSENT -> return AccountProviderErasureRun.PROVIDER_ABSENT
                AccountProviderRetryOutcome.WAITING -> return AccountProviderErasureRun.RECONCILIATION_PENDING
                AccountProviderRetryOutcome.EXHAUSTED -> return AccountProviderErasureRun.RETRY_EXHAUSTED
                AccountProviderRetryOutcome.LEASE_LOST -> return AccountProviderErasureRun.LEASE_LOST
                AccountProviderRetryOutcome.READY -> {
                    val attempt = checkNotNull(prepared.attempt)
                    if (!runInterruptible(databaseDispatcher) { retryStore.markDispatched(lease, attempt) })
                        return AccountProviderErasureRun.LEASE_LOST
                    currentCoroutineContext().ensureActive()
                    val result = client.erase(lease.providerSubject.toString())
                    if (!runInterruptible(databaseDispatcher) { retryStore.record(lease, attempt, result) })
                        return AccountProviderErasureRun.LEASE_LOST
                }
            }
        }
        return when (runInterruptible(databaseDispatcher) { store.reconcile(lease, 60) }) {
            AccountProviderErasureObservation.PROVIDER_ABSENT -> AccountProviderErasureRun.PROVIDER_ABSENT
            AccountProviderErasureObservation.PROVIDER_PRESENT -> AccountProviderErasureRun.RECONCILIATION_PENDING
            AccountProviderErasureObservation.LEASE_LOST -> AccountProviderErasureRun.LEASE_LOST
        }
    }
    override fun toString() = "AccountProviderErasureWorker([redacted])"
}
