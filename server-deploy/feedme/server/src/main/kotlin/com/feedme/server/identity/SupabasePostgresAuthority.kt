package com.feedme.server.identity

import com.feedme.server.auth.SupabaseUserAccessConfiguration
import com.feedme.server.auth.VerifiedSupabaseSubject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit deployment facts, not discovered from JWTs. Auth's service settings/version are
 * not stored authoritatively in auth.sessions. An operator must re-review these facts after
 * ANY provider configuration/deployment change, and before this bounded review expires.
 * The DataSource-to-issuer tenant relationship is trusted deployment configuration; database
 * name/schema checks do not uniquely identify a tenant. This is a compatibility restriction,
 * not a claim of automatic provider-change detection or unique tenant attestation. */
class SupabaseAuthorityDeployment(
    val verification: SupabaseUserAccessConfiguration,
    val databaseName: String,
    val authSourceRevision: String,
    migrationVersions: List<String>,
    val reviewedAt: Instant,
    val validUntil: Instant,
    val timeboxSeconds: Long?,
    val inactivitySeconds: Long?,
    val singleSessionPerUser: Boolean,
    val lowAssuranceTimeoutSeconds: Long?,
) {
    private val versions = migrationVersions.toList()
    internal fun migrations() = versions.toList()
    init {
        require(databaseName.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_-]{0,62}"))) { "Invalid authority deployment" }
        require(authSourceRevision == SUPPORTED_AUTH_REVISION && versions.size in 1..512 &&
            versions == versions.distinct().sorted() && versions.all { it.matches(Regex("[0-9]{1,14}")) }) { "Unsupported authority schema" }
        require(reviewedAt < validUntil && Duration.between(reviewedAt, validUntil) <= Duration.ofHours(24)) { "Invalid authority review window" }
        require(listOf(timeboxSeconds, inactivitySeconds).all { it == null || it in 60..31_536_000 }) { "Invalid authority session policy" }
        // These need additional provider queries/settings and are not silently approximated.
        require(!singleSessionPerUser && lowAssuranceTimeoutSeconds == null) { "Unsupported authority session policy" }
    }
    override fun toString() = "SupabaseAuthorityDeployment(<redacted>)"
    companion object {
        const val SUPPORTED_AUTH_REVISION = "4eee58f296d9698a1c2c0ae14d7a0b379c7622d3"
    }
}

/** Same-PostgreSQL current authority, no network, credentials, mirror or accepting fallback.
 * Order: schema stability locks -> actual user FOR UPDATE -> exact session FOR UPDATE ->
 * all existing AMR rows FOR UPDATE -> FeedMe's existing subject/account/device locks.
 * Validated non-deferrable FKs make new session/AMR inserts take a conflicting parent key
 * lock too. Thus an unseen recovery AMR cannot be inserted behind this transaction.
 * Every invocation rechecks actual rows and DB wall time; nothing is cached as authority.
 * Deadlocks/timeouts are failures handled by the existing bounded DB transaction runner.
 */
class SupabasePostgresAuthority(val deployment: SupabaseAuthorityDeployment) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val passwordEvidenceSeal = Any()

    fun checkCompatibility(connection: Connection) {
        requireTransaction(connection)
        if (closed.get()) unavailable()
        // No DDL or provider writes. Locks live only for the caller-owned transaction.
        connection.createStatement().use {
            it.execute("LOCK TABLE auth.schema_migrations IN SHARE MODE")
            it.execute("LOCK TABLE auth.users, auth.sessions, auth.mfa_amr_claims IN ACCESS SHARE MODE")
        }
        val now = time(connection)
        checkReview(now)
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT current_database(),current_setting('session_replication_role')").use { r ->
                if (!r.next() || r.getString(1) != deployment.databaseName || r.getString(2) != "origin" || r.next()) unavailable()
            }
            statement.executeQuery("SELECT version FROM auth.schema_migrations ORDER BY version LIMIT 513").use { r ->
                val actual = mutableListOf<String>()
                while (r.next()) actual += r.getString(1) ?: unavailable()
                if (actual != deployment.migrations()) unavailable()
            }
        }
        for ((table, columns) in REQUIRED_COLUMNS) {
            connection.prepareStatement("SELECT c.relkind, c.relrowsecurity, c.relforcerowsecurity, " +
                "c.relowner=(SELECT oid FROM pg_roles WHERE rolname=current_user), " +
                "(SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user) " +
                "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='auth' AND c.relname=?").use { s ->
                s.setString(1, table); s.executeQuery().use { r ->
                    if (!r.next() || r.getString(1) != "r" ||
                        (r.getBoolean(2) && !(r.getBoolean(5) || (r.getBoolean(4) && !r.getBoolean(3)))) || r.next()) unavailable()
                }
            }
            connection.prepareStatement("SELECT a.attname,tn.nspname||'.'||t.typname FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid " +
                "JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_type t ON t.oid=a.atttypid JOIN pg_namespace tn ON tn.oid=t.typnamespace " +
                "WHERE n.nspname='auth' AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped").use { s ->
                s.setString(1, table); s.executeQuery().use { r ->
                    val actual = mutableMapOf<String, String>()
                    while (r.next()) actual[r.getString(1)] = r.getString(2)
                    if (columns.any { (name, types) -> actual[name] !in types }) unavailable()
                }
            }
        }
        connection.createStatement().use { s ->
            s.executeQuery("SELECT e.enumlabel FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid JOIN pg_namespace n ON n.oid=t.typnamespace " +
                "WHERE n.nspname='auth' AND t.typname='aal_level' ORDER BY e.enumsortorder").use { r ->
                val levels = buildList { while (r.next()) add(r.getString(1)) }
                if (levels != listOf("aal1", "aal2", "aal3")) unavailable()
            }
        }
        primaryKey(connection, "users", "id")
        primaryKey(connection, "sessions", "id")
        primaryKey(connection, "mfa_amr_claims", "id")
        foreignKey(connection, "sessions", "user_id", "users")
        foreignKey(connection, "mfa_amr_claims", "session_id", "sessions")
        checkReview(time(connection))
        if (closed.get()) unavailable()
    }

    fun lockCurrent(connection: Connection, subject: VerifiedSupabaseSubject) {
        lockCurrentTime(connection, subject)
    }

    /** Locked provider facts remain useful only through their earliest real deadline. This
     * projection is private and cannot serve as an independently constructed authority. */
    private class CurrentProviderTime(val observedAt: Instant, val validUntil: Instant)
    private fun lockCurrentTime(connection: Connection, subject: VerifiedSupabaseSubject): CurrentProviderTime {
        checkCompatibility(connection)
        if (subject.issuer != deployment.verification.issuer) denied()
        val user = connection.prepareStatement("SELECT aud,role,email_confirmed_at,deleted_at,banned_until,is_anonymous " +
            "FROM auth.users WHERE id=? FOR UPDATE").use { s ->
            s.setObject(1, subject.subject); s.executeQuery().use { r ->
                if (!r.next()) denied()
                User(r.getString(1), r.getString(2), instant(r, 3), instant(r, 4), instant(r, 5),
                    r.getObject(6) as? Boolean ?: denied()).also { if (r.next()) unavailable() }
            }
        }
        val session = connection.prepareStatement("SELECT user_id,created_at,not_after,refreshed_at,aal,oauth_client_id " +
            "FROM auth.sessions WHERE id=? FOR UPDATE").use { s ->
            s.setObject(1, subject.providerSessionId); s.executeQuery().use { r ->
                if (!r.next()) denied()
                Session(r.getObject(1, UUID::class.java), instant(r, 2) ?: denied(), instant(r, 3),
                    // Upstream refreshed_at is timestamp WITHOUT timezone and contains UTC.
                    r.getObject(4, java.time.LocalDateTime::class.java)?.toInstant(java.time.ZoneOffset.UTC),
                    r.getString(5), r.getObject(6)).also { if (r.next()) unavailable() }
            }
        }
        if (session.user != subject.subject) denied()
        val methods = connection.prepareStatement("SELECT authentication_method,updated_at FROM auth.mfa_amr_claims " +
            "WHERE session_id=? ORDER BY id LIMIT 33 FOR UPDATE").use { s ->
            s.setObject(1, subject.providerSessionId); s.executeQuery().use { r ->
                buildList { while (r.next()) add((r.getString(1) ?: denied()) to (instant(r, 2) ?: denied())) }
            }
        }
        val now = time(connection)
        checkReview(now)
        if (closed.get() || subject.expiresAtEpochSeconds <= now.epochSecond ||
            subject.issuedAtEpochSeconds > now.epochSecond + deployment.verification.allowedFutureClockSkewSeconds) denied()
        if (user.audience != "authenticated" || user.role != "authenticated" || user.anonymous || user.deleted != null ||
            user.confirmed == null || user.confirmed > now || user.banned?.let { it > now } == true) denied()
        if (session.created > now || session.oauthClient != null || session.aal !in setOf("aal1", "aal2") ||
            session.aal != subject.assuranceLevel || session.notAfter?.let { now >= it } == true ||
            subject.issuedAtEpochSeconds + deployment.verification.allowedFutureClockSkewSeconds < session.created.epochSecond) denied()
        deployment.timeboxSeconds?.let { if (now >= session.created.plusSeconds(it)) denied() }
        deployment.inactivitySeconds?.let {
            // A legacy session with no refreshed_at needs the provider refresh-token fallback;
            // this slice does not read refresh secrets or guess that timestamp.
            val refreshed = session.refreshed ?: denied()
            if (refreshed < session.created || refreshed > now || now >= refreshed.plusSeconds(it)) denied()
        }
        if (methods.size !in 1..32 || methods.map { it.first }.distinct().size != methods.size ||
            methods.any { it.first !in ALLOWED_AMR || it.second > now || it.second < session.created } ||
            methods.none { it.first in PRIMARY_AMR }) denied()
        // Even when a JWT omitted amr, actual DB AMR was checked above; JWT facts never grant.
        if (subject.authenticationMethods?.any { it.method !in ALLOWED_AMR } == true) denied()
        if (closed.get()) unavailable()
        var until = minOf(Instant.ofEpochSecond(subject.expiresAtEpochSeconds), deployment.validUntil)
        session.notAfter?.let { until = minOf(until, it) }
        deployment.timeboxSeconds?.let { until = minOf(until, session.created.plusSeconds(it)) }
        deployment.inactivitySeconds?.let { until = minOf(until, (session.refreshed ?: denied()).plusSeconds(it)) }
        return CurrentProviderTime(now, until)
    }

    /** Fresh password authentication is actual provider evidence, not a newer JWT or refresh.
     * This does not authorize device replacement: exact target, explicit consent, independent
     * policy and atomic evidence persistence remain the account operation's responsibility. */
    fun lockFreshPasswordSession(connection: Connection, subject: VerifiedSupabaseSubject,
        maximumAgeSeconds: Long): SupabasePasswordReauthenticationEvidence = passwordOperation {
        require(maximumAgeSeconds in 1..900) { "Invalid password reauthentication policy" }
        passwordLocal(connection)
        val transaction = passwordTransaction(connection)
        val provider = lockCurrentTime(connection, subject)
        val facts = passwordFacts(connection, subject)
        val until = passwordReauthenticationDeadline(facts.first, facts.second, maximumAgeSeconds,
            Instant.ofEpochSecond(subject.expiresAtEpochSeconds), deployment.validUntil, provider.validUntil)
        // All queryful identity/isolation checks precede the final database clock read.
        if (passwordTransaction(connection) != transaction) unavailable()
        val accepted = time(connection)
        passwordLocal(connection); checkReview(accepted)
        if (accepted < provider.observedAt) denied()
        requirePasswordReauthenticationTime(facts.first, facts.second, accepted, until)
        SupabasePasswordReauthenticationEvidence(this, passwordEvidenceSeal, connection,
            Thread.currentThread(), transaction, subject, facts.first, facts.second, accepted, until)
    }

    internal fun revalidatePassword(connection: Connection, evidence: SupabasePasswordReauthenticationEvidence) = passwordOperation {
        evidence.requireBinding(this, passwordEvidenceSeal, connection)
        passwordLocal(connection)
        if (passwordTransaction(connection) != evidence.transaction) unavailable()
        val provider = lockCurrentTime(connection, evidence.subject)
        val facts = passwordFacts(connection, evidence.subject)
        if (facts.first != evidence.sessionCreatedAt || facts.second != evidence.passwordAuthenticatedAt) denied()
        if (passwordTransaction(connection) != evidence.transaction) unavailable()
        val now = time(connection)
        evidence.requireBinding(this, passwordEvidenceSeal, connection)
        passwordLocal(connection); checkReview(now)
        if (now < evidence.acceptedAt || now < provider.observedAt) denied()
        requirePasswordReauthenticationTime(facts.first, facts.second, now, minOf(evidence.validUntil, provider.validUntil))
    }

    private fun passwordFacts(c: Connection, subject: VerifiedSupabaseSubject): Pair<Instant, Instant> =
        c.prepareStatement("SELECT s.created_at,a.updated_at FROM auth.sessions s JOIN auth.mfa_amr_claims a ON a.session_id=s.id " +
            "WHERE s.id=? AND s.user_id=? AND a.authentication_method='password' FOR UPDATE OF s,a").use { statement ->
            statement.setObject(1, subject.providerSessionId); statement.setObject(2, subject.subject)
            statement.executeQuery().use { rows ->
                if (!rows.next()) denied()
                val created = instant(rows, 1) ?: denied(); val authenticated = instant(rows, 2) ?: denied()
                if (rows.next()) denied()
                created to authenticated
            }
        }
    private fun passwordLocal(c: Connection) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Password reauthentication interrupted")
        if (closed.get() || c.isClosed || c.autoCommit) unavailable()
    }
    private fun <T> passwordOperation(action: () -> T): T = try { action() }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
    private fun passwordTransaction(c: Connection): Long {
        passwordLocal(c); requireTransaction(c)
        return c.createStatement().use { statement -> statement.executeQuery("SELECT txid_current()").use { rows ->
            if (!rows.next()) unavailable()
            rows.getLong(1).also { if (rows.wasNull() || rows.next()) unavailable() }
        } }
    }

    override fun close() { closed.set(true) }
    override fun toString() = "SupabasePostgresAuthority(<redacted>)"
    private fun checkReview(now: Instant) {
        if (now < deployment.reviewedAt || now >= deployment.validUntil) unavailable()
    }
    private fun primaryKey(c: Connection, table: String, column: String) {
        c.prepareStatement("SELECT count(*) FROM pg_constraint k JOIN pg_class t ON t.oid=k.conrelid " +
            "JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_attribute a ON a.attrelid=t.oid AND a.attname=? " +
            "WHERE n.nspname='auth' AND t.relname=? AND k.contype='p' AND k.convalidated AND NOT k.condeferrable AND k.conkey=ARRAY[a.attnum]").use { s ->
            s.setString(1, column); s.setString(2, table); s.executeQuery().use { r -> if (!r.next() || r.getInt(1) != 1) unavailable() }
        }
    }
    private fun foreignKey(c: Connection, table: String, column: String, parent: String) {
        c.prepareStatement("SELECT count(*) FROM pg_constraint k JOIN pg_class t ON t.oid=k.conrelid " +
            "JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_class p ON p.oid=k.confrelid JOIN pg_namespace pn ON pn.oid=p.relnamespace " +
            "JOIN pg_attribute a ON a.attrelid=t.oid AND a.attname=? JOIN pg_attribute pa ON pa.attrelid=p.oid AND pa.attname='id' " +
            "WHERE n.nspname='auth' AND t.relname=? AND pn.nspname='auth' AND p.relname=? AND k.contype='f' " +
            "AND k.convalidated AND NOT k.condeferrable AND k.conkey=ARRAY[a.attnum] AND k.confkey=ARRAY[pa.attnum] " +
            "AND NOT EXISTS(SELECT 1 FROM pg_trigger g WHERE g.tgconstraint=k.oid AND g.tgenabled NOT IN ('O','A'))").use { s ->
            s.setString(1, column); s.setString(2, table); s.setString(3, parent)
            s.executeQuery().use { r -> if (!r.next() || r.getInt(1) != 1) unavailable() }
        }
    }
    private class User(val audience: String?, val role: String?, val confirmed: Instant?, val deleted: Instant?, val banned: Instant?, val anonymous: Boolean)
    private class Session(val user: UUID, val created: Instant, val notAfter: Instant?, val refreshed: Instant?, val aal: String?, val oauthClient: Any?)
    companion object {
        private val PRIMARY_AMR = setOf("password", "oauth", "otp")
        private val ALLOWED_AMR = PRIMARY_AMR + setOf("totp", "mfa/phone", "mfa/webauthn", "mfa/recovery_code")
        private val TEXT = setOf("pg_catalog.varchar", "pg_catalog.text")
        private val REQUIRED_COLUMNS = mapOf(
            "schema_migrations" to mapOf("version" to TEXT),
            "users" to mapOf("id" to setOf("pg_catalog.uuid"), "aud" to TEXT, "role" to TEXT, "email_confirmed_at" to setOf("pg_catalog.timestamptz"),
                "deleted_at" to setOf("pg_catalog.timestamptz"), "banned_until" to setOf("pg_catalog.timestamptz"), "is_anonymous" to setOf("pg_catalog.bool")),
            "sessions" to mapOf("id" to setOf("pg_catalog.uuid"), "user_id" to setOf("pg_catalog.uuid"), "created_at" to setOf("pg_catalog.timestamptz"),
                "not_after" to setOf("pg_catalog.timestamptz"), "refreshed_at" to setOf("pg_catalog.timestamp"), "aal" to setOf("auth.aal_level"), "oauth_client_id" to setOf("pg_catalog.uuid")),
            "mfa_amr_claims" to mapOf("id" to setOf("pg_catalog.uuid"), "session_id" to setOf("pg_catalog.uuid"), "authentication_method" to TEXT, "updated_at" to setOf("pg_catalog.timestamptz")),
        )
        private fun requireTransaction(c: Connection) { if (c.autoCommit || c.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) unavailable() }
        private fun instant(r: ResultSet, column: Int): Instant? = r.getObject(column, OffsetDateTime::class.java)?.toInstant()
        private fun time(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r -> check(r.next()); instant(r, 1)!! } }
        private fun unavailable(): Nothing = throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
        private fun denied(): Nothing = throw AccountFailure(AccountFailureCode.UNAUTHENTICATED)
    }
}
