package com.feedme.server.identity

import com.feedme.core.ports.SecretText
import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

/** Explicit reviewed notice/policy values. No enabling default or approval inference. */
internal class AccountDeletionRules(val revision: String, val confirmationVersion: String,
    val maximumAuthenticationAgeSeconds: Long) {
    init {
        require(deletionText(revision, 128) && deletionText(confirmationVersion, 256) &&
            maximumAuthenticationAgeSeconds in 1..900) { "Invalid deletion policy" }
    }
    override fun toString() = "AccountDeletionRules(<redacted>)"
}

/** Request memory only. Never enqueue/persist/log the raw proof or optional private reason.
 * The canonical fingerprint binds every submitted field without storing the raw body. */
internal class AccountDeletionRequest private constructor(private val body: JsonObject) {
    val confirmationVersion: String get() = body.getValue("acknowledgedVersion").jsonPrimitive.content
    internal val proofToken: SecretText get() = SecretText(body.getValue("reauthenticationProof").jsonPrimitive.content)
    internal val proofSha256: String get() = deletionProofSha256(body.getValue("reauthenticationProof").jsonPrimitive.content)
    internal fun command(environment: String, account: UUID, key: UUID) = CommandIdentity(
        PrincipalScope(environment, CommandActor.ACCOUNT, account), OPERATION, key, body = body)
    override fun toString() = "AccountDeletionRequest(<redacted>)"
    companion object {
        fun parse(body: JsonObject): AccountDeletionRequest {
            val bytes = try { body.toString().encodeToByteArray(throwOnInvalidSequence = true) }
                catch (_: Exception) { throw AccountFailure(AccountFailureCode.INPUT_INVALID) }
            try {
                if (bytes.size > 16_384 || deletionValidator.validateRequest(OPERATION, bytes, "application/json") != BodyValidationResult.Valid)
                    throw AccountFailure(AccountFailureCode.INPUT_INVALID)
                val copy = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                if (!deletionText(copy.getValue("acknowledgedVersion").jsonPrimitive.content, 256) ||
                    copy.getValue("reauthenticationProof").jsonPrimitive.content.length !in 1..16_384)
                    throw AccountFailure(AccountFailureCode.INPUT_INVALID)
                return AccountDeletionRequest(copy)
            } finally { bytes.fill(0) }
        }
    }
}

/** Two database phases around verification that may load public keys over the network.
 * Exact committed replay is resolved first, so an expired/retired original proof need not
 * be reverified. New acceptance reacquires all authority; this is not a reusable preflight
 * grant. Unknown commits/cancellation are never automatically retried by this service. */
internal class AccountDeletionService(private val store: AccountDeletionStore,
    private val proofVerifier: AccountDeletionProofVerifier, private val databaseDispatcher: CoroutineDispatcher) {
    suspend fun request(original: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult {
        currentCoroutineContext().ensureActive()
        val request = AccountDeletionRequest.parse(body)
        runInterruptible(databaseDispatcher) { store.replayOrNull(original, device, key, request) }?.let { return it }
        val proof = proofVerifier.verify(original, request.proofToken)
        currentCoroutineContext().ensureActive()
        return runInterruptible(databaseDispatcher) { store.accept(original, device, key, request, proof) }
    }
    override fun toString() = "AccountDeletionService(<redacted>)"
}

/** Atomic acceptance only, not erasure. The default runtime has no grants for this SQL
 * capability; the optional serving assembly checks separately reviewed permissions before
 * registering its route. Live activation still requires the complete worker/receipt path.
 * The SQL function is
 * a constrained trusted-server mutation, never a substitute for signed current authority.
 * Locks: provider -> subject -> account/principal -> devices -> command/job -> profile.
 */
internal class AccountDeletionStore(private val environment: String, private val transactions: PgTransactions,
    private val authority: SupabasePostgresAuthority, private val rules: AccountDeletionRules) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun replayOrNull(original: VerifiedSupabaseSubject, device: UUID, key: UUID,
        request: AccountDeletionRequest): CommandResult? = safe {
        transactions.run { c ->
            checkCompatibility(c); authority.lockCurrent(c, original)
            val existing = retained(c, original, device, key, request)
            if (existing == null) {
                val owner = owner(c, original)
                val requested = requestedDevice(devices(c, owner), original, device)
                requireNew(owner, requested)
                if (request.confirmationVersion != rules.confirmationVersion) fail(AccountFailureCode.POLICY_BLOCKED)
            }
            authority.lockCurrent(c, original)
            existing
        }
    }

    fun accept(original: VerifiedSupabaseSubject, device: UUID, key: UUID, request: AccountDeletionRequest,
        proof: VerifiedAccountDeletionProof): CommandResult = safe {
        if (proof.original !== original || proof.tokenSha256 != request.proofSha256) fail(AccountFailureCode.UNAUTHENTICATED)
        transactions.run { c ->
            checkCompatibility(c)
            authority.lockCurrent(c, original)
            // A committed acceptance may have appeared after service preflight. Recover
            // it before requiring a still-fresh proof or roots/devices that core cleanup
            // intentionally removes. This is an original acknowledgement, not job status.
            retained(c, original, device, key, request)?.let {
                authority.lockCurrent(c, original)
                return@run it
            }
            val evidence = authority.lockAccountDeletionOAuth(c, proof, rules.maximumAuthenticationAgeSeconds)
            val owner = owner(c, original)
            val devices = devices(c, owner)
            val requested = requestedDevice(devices, original, device)
            val command = request.command(environment, owner.account, key)
            requireNew(owner, requested)
            if (request.confirmationVersion != rules.confirmationVersion) fail(AccountFailureCode.POLICY_BLOCKED)
            val result = commands.executeInTransaction(c, command,
                { authority.lockCurrent(it, original) },
                { requireNew(owner, requested) },
                // A receipt without its atomic retained job is inconsistent, not authority.
                { _, _ -> fail(AccountFailureCode.STORAGE_UNAVAILABLE) }) { db ->
                val jobId = UUID.randomUUID()
                db.prepareStatement("SELECT identity.accept_account_deletion(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
                    s.setString(1, environment); s.setObject(2, owner.account); s.setObject(3, owner.principal)
                    s.setString(4, original.issuer); s.setObject(5, original.subject); s.setObject(6, device)
                    s.setObject(7, original.providerSessionId); s.setObject(8, key); s.setString(9, command.requestHash)
                    s.setObject(10, jobId); s.setString(11, request.confirmationVersion); s.setString(12, rules.revision)
                    s.setObject(13, proof.fresh.providerSessionId); s.setLong(14, rules.maximumAuthenticationAgeSeconds)
                    s.setObject(15, evidence.sessionCreatedAt.atOffset(ZoneOffset.UTC))
                    s.setObject(16, evidence.oauthAuthenticatedAt.atOffset(ZoneOffset.UTC))
                    s.setObject(17, evidence.acceptedAt.atOffset(ZoneOffset.UTC)); s.setObject(18, evidence.validUntil.atOffset(ZoneOffset.UTC))
                    s.executeQuery().use { rows ->
                        if (!rows.next() || rows.getObject(1, UUID::class.java) != jobId || rows.next()) fail(AccountFailureCode.STORAGE_UNAVAILABLE)
                    }
                }
                outbox.append(db, EventDraft(UUID.randomUUID(), "identity.account.deletion_requested.v1", 1,
                    "account", owner.account, Math.addExact(owner.version, 1), "identity", key.toString(), key,
                    buildJsonObject { put("userId", owner.account.toString()); put("deletionJobId", jobId.toString()) },
                    owner = EventOwner.account(environment, owner.account)))
                acknowledgement(jobId)
            }
            // Final receipt write/waits are included. Expiry rolls back job, fences, device
            // revocation, event and receipt together; caller never gets partial acceptance.
            evidence.revalidate(c)
            result
        }
    }

    private class Owner(val account: UUID, val principal: UUID, val status: String, val principalStatus: String, val version: Long)
    private class Device(val id: UUID, val provider: UUID, val version: Long, val revokedAt: Instant?)

    private fun owner(c: Connection, subject: VerifiedSupabaseSubject): Owner {
        val material = buildJsonArray { add(environment); add(subject.issuer); add(subject.subject.toString()) }.toString()
        val lock = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(material.encodeToByteArray())).long
        c.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { it.setLong(1, lock); it.execute() }
        return c.prepareStatement("SELECT u.id,p.id,u.status,p.status,u.version FROM identity.users u " +
            "JOIN identity.principals p ON p.environment=u.environment AND p.user_id=u.id " +
            "WHERE u.environment=? AND u.provider_issuer=? AND u.provider_subject=? FOR UPDATE OF u,p").use { s ->
            s.setString(1, environment); s.setString(2, subject.issuer); s.setObject(3, subject.subject)
            s.executeQuery().use { r ->
                if (!r.next()) fail(AccountFailureCode.UNAUTHENTICATED)
                Owner(r.getObject(1, UUID::class.java), r.getObject(2, UUID::class.java), r.getString(3), r.getString(4), r.getLong(5))
                    .also { if (r.next()) fail(AccountFailureCode.STORAGE_UNAVAILABLE) }
            }
        }
    }

    private fun devices(c: Connection, owner: Owner): List<Device> = c.prepareStatement(
        "SELECT id,provider_session_id,version,revoked_at FROM identity.device_sessions WHERE environment=? AND user_id=? ORDER BY id FOR UPDATE").use { s ->
        s.setString(1, environment); s.setObject(2, owner.account)
        s.executeQuery().use { r -> buildList {
            while (r.next()) add(Device(r.getObject(1, UUID::class.java), r.getObject(2, UUID::class.java), r.getLong(3),
                r.getObject(4, OffsetDateTime::class.java)?.toInstant()))
        } }
    }

    private fun requestedDevice(devices: List<Device>, subject: VerifiedSupabaseSubject, id: UUID): Device =
        devices.singleOrNull { it.id == id && it.provider == subject.providerSessionId && it.id != subject.providerSessionId }
            ?: fail(AccountFailureCode.UNAUTHENTICATED)

    private fun requireNew(owner: Owner, device: Device) {
        if (owner.status !in setOf("active", "suspended") || owner.principalStatus !in setOf("active", "suspended") || device.revokedAt != null)
            fail(AccountFailureCode.UNAUTHENTICATED)
    }

    /** Minimal original acceptance, independent of short-lived HTTP receipts AND erased
     * identity/device roots. The immutable accepted job/lineage is the receipt authority;
     * current provider authorization is still required before and after this lookup. Plain
     * SELECT is intentional: these originals cannot change and survive V044, so taking job
     * row locks here would add needless lock inversion against the purge worker. No work
     * ledger/status, cleanup progress, new proof, current policy or bootstrap is consulted.
     * This grants no other action on a revoked device and is not public status polling. */
    private fun retained(c: Connection, subject: VerifiedSupabaseSubject, device: UUID, key: UUID,
        request: AccountDeletionRequest): CommandResult? =
        c.prepareStatement("SELECT j.*,d.provider_session_id AS retired_provider,d.prior_version,d.revoked_at AS retired_at " +
            "FROM identity.account_deletion_jobs j LEFT JOIN identity.account_deletion_devices d ON d.environment=j.environment " +
            "AND d.job_id=j.id AND d.device_session_id=j.device_session_id " +
            "WHERE j.environment=? AND j.provider_issuer=? AND j.provider_subject=?").use { s ->
            s.setString(1, environment); s.setString(2, subject.issuer); s.setObject(3, subject.subject)
            s.executeQuery().use { r ->
                if (!r.next()) return@use null
                if (r.getString("provider_issuer") != subject.issuer ||
                    r.getObject("provider_subject", UUID::class.java) != subject.subject ||
                    r.getObject("device_session_id", UUID::class.java) != device || device == subject.providerSessionId ||
                    r.getObject("provider_session_id", UUID::class.java) != subject.providerSessionId ||
                    r.getObject("retired_provider", UUID::class.java) != subject.providerSessionId) fail(AccountFailureCode.UNAUTHENTICATED)
                val account = r.getObject("user_id", UUID::class.java)
                val retired = r.getObject("retired_at", OffsetDateTime::class.java)?.toInstant()
                if (account == r.getObject("principal_id", UUID::class.java) ||
                    r.getLong("prior_version") !in 1 until Long.MAX_VALUE || retired == null ||
                    retired != r.getObject("accepted_at", OffsetDateTime::class.java)?.toInstant() ||
                    r.getString("stage") != "pending") fail(AccountFailureCode.STORAGE_UNAVAILABLE)
                val command = request.command(environment, account, key)
                if (r.getObject("command_key", UUID::class.java) != key) fail(AccountFailureCode.POLICY_BLOCKED)
                val matches = r.getString("request_sha256") == command.requestHash
                if (matches && r.getString("acknowledged_version") != request.confirmationVersion) fail(AccountFailureCode.STORAGE_UNAVAILABLE)
                val job = r.getObject("id", UUID::class.java)
                if (r.next()) fail(AccountFailureCode.STORAGE_UNAVAILABLE)
                if (matches) CommandResult.Replayed(acknowledgement(job)) else CommandResult.Mismatch
            }
        }

    private fun acknowledgement(job: UUID): StoredReply {
        val body = buildJsonObject { put("jobId", job.toString()); put("status", "pending") }
        if (deletionValidator.validateResponse(OPERATION, 202, body.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid)
            fail(AccountFailureCode.STORAGE_UNAVAILABLE)
        return StoredReply(202, body)
    }

    internal fun checkCompatibility(c: Connection) {
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=41").use { s -> s.executeQuery().use { r ->
            if (!r.next() || r.getString(1) != migrationSha256 || r.next()) fail(AccountFailureCode.NOT_CONFIGURED)
        } }
        // Preserve the checked table/trigger attachments for this transaction. These are
        // read locks only; the ordinary runtime is not granted mutation of original jobs.
        c.createStatement().use { it.execute("LOCK TABLE ONLY identity.account_deletion_jobs, ONLY identity.account_deletion_devices," +
            "ONLY identity.users, ONLY identity.principals, ONLY identity.device_sessions, ONLY profile.profiles IN ACCESS SHARE MODE") }
        for (name in listOf("account_deletion_jobs", "account_deletion_devices")) {
            c.prepareStatement("SELECT c.relkind,c.relrowsecurity,c.relforcerowsecurity," +
                "EXISTS(SELECT 1 FROM pg_inherits WHERE inhrelid=c.oid OR inhparent=c.oid)," +
                "EXISTS(SELECT 1 FROM pg_policy WHERE polrelid=c.oid)," +
                "EXISTS(SELECT 1 FROM aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE a.grantee=0) " +
                "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='identity' AND c.relname=?").use { s ->
                s.setString(1, name); s.executeQuery().use { r ->
                    if (!r.next() || r.getString(1) != "r" || !r.getBoolean(2) || !r.getBoolean(3) ||
                        r.getBoolean(4) || r.getBoolean(5) || r.getBoolean(6) || r.next()) fail(AccountFailureCode.NOT_CONFIGURED)
                }
            }
        }
        for (guard in guards) checkGuard(c, guard)
        c.prepareStatement("SELECT p.prosrc,p.prosecdef,p.proconfig,l.lanname," +
            "EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0),p.pronargs,p.prorettype::regtype::text " +
            "FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang " +
            "WHERE n.nspname='identity' AND p.proname='accept_account_deletion'").use { s -> s.executeQuery().use { r ->
            if (!r.next() || r.getString(1) != acceptanceSource || !r.getBoolean(2) ||
                (r.getArray(3)?.array as? Array<*>)?.toList() != listOf("search_path=pg_catalog, pg_temp", "row_security=off") ||
                r.getString(4) != "plpgsql" || r.getBoolean(5) || r.getInt(6) != 18 || r.getString(7) != "uuid" || r.next()) fail(AccountFailureCode.NOT_CONFIGURED)
        } }
    }

    private fun checkGuard(c: Connection, guard: Guard) {
        c.prepareStatement("SELECT t.tgtype,t.tgenabled,t.tgisinternal,t.tgqual,t.tgattr::text,t.tgnargs,t.tgconstraint," +
            "p.prosrc,p.prosecdef,p.proconfig,p.pronargs,p.prorettype::regtype::text,p.proowner=c.relowner,l.lanname," +
            "EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner) " +
            "FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace " +
            "JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_namespace pn ON pn.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang " +
            "WHERE n.nspname=? AND c.relname=? AND t.tgname=? AND pn.nspname='identity' AND p.proname=?").use { s ->
            s.setString(1, guard.schema); s.setString(2, guard.table); s.setString(3, guard.trigger); s.setString(4, guard.function)
            s.executeQuery().use { r ->
                val settings = if (guard.definer) listOf("search_path=pg_catalog, pg_temp", "row_security=off")
                    else listOf("search_path=pg_catalog, pg_temp")
                if (!r.next() || r.getInt(1) != guard.type || r.getString(2) !in setOf("O", "A") || r.getBoolean(3) ||
                    r.getObject(4) != null || r.getString(5) != "" || r.getInt(6) != 0 || r.getLong(7) != 0L ||
                    r.getString(8) != body(guard.delimiter) || r.getBoolean(9) != guard.definer ||
                    (r.getArray(10)?.array as? Array<*>)?.toList() != settings || r.getInt(11) != 0 ||
                    r.getString(12) != "trigger" || !r.getBoolean(13) || r.getString(14) != "plpgsql" || r.getBoolean(15) || r.next())
                    fail(AccountFailureCode.NOT_CONFIGURED)
            }
        }
    }

    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: AccountFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: SQLException) { fail(AccountFailureCode.STORAGE_UNAVAILABLE) }
        catch (_: Exception) { fail(AccountFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: AccountFailureCode): Nothing = throw AccountFailure(code)
    override fun toString() = "AccountDeletionStore(<redacted>)"
    private companion object {
        class Guard(val schema: String, val table: String, val trigger: String, val function: String,
            val delimiter: String, val type: Int, val definer: Boolean)
        val guards = listOf(
            Guard("identity", "account_deletion_jobs", "account_deletion_job_immutable", "keep_account_deletion_acceptance_immutable", "feedme_immutable", 27, false),
            Guard("identity", "account_deletion_jobs", "account_deletion_job_retained", "keep_account_deletion_acceptance_immutable", "feedme_immutable", 34, false),
            Guard("identity", "account_deletion_devices", "account_deletion_device_immutable", "keep_account_deletion_acceptance_immutable", "feedme_immutable", 27, false),
            Guard("identity", "account_deletion_devices", "account_deletion_device_retained", "keep_account_deletion_acceptance_immutable", "feedme_immutable", 34, false),
            Guard("identity", "users", "account_deletion_root_fence", "guard_deleting_account_root", "feedme_root_guard", 23, true),
            Guard("identity", "principals", "account_deletion_principal_fence", "guard_deleting_principal", "feedme_principal_guard", 23, true),
            Guard("identity", "device_sessions", "account_deletion_device_fence", "guard_deleting_device", "feedme_device_guard", 23, true),
            Guard("profile", "profiles", "account_deletion_profile_fence", "guard_deleting_profile", "feedme_profile_guard", 23, true),
        )
        val migration: ByteArray by lazy { checkNotNull(AccountDeletionStore::class.java.getResourceAsStream(
            "/db/migration/V041__account_deletion_acceptance.sql")).use { it.readBytes() } }
        val migrationSha256: String by lazy { MessageDigest.getInstance("SHA-256").digest(migration).joinToString("") { "%02x".format(it.toInt() and 255) } }
        fun body(delimiter: String): String {
            val parts = migration.decodeToString().split("\$$delimiter\$")
            check(parts.size == 3)
            return parts[1]
        }
        val acceptanceSource: String by lazy { body("feedme_accept") }
    }
}

private const val OPERATION = "requestAccountDeletion"
private val deletionValidator by lazy { ContractBodyValidator.bundled() }
private fun deletionText(value: String, maximum: Int) = value.length in 1..maximum &&
    !value.isBlank() && value.none(Char::isISOControl)
