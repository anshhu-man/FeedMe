package com.feedme.server.guest

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandActor
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.OutboxStore
import com.feedme.server.db.PgTransactions
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import com.feedme.server.kitchen.KitchenFailure
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Dedicated guest bootstrap/lifecycle persistence. Never uses an account token, arbitrary
 * principal ID, generic command receipt or installation digest as an authorization fallback.
 * This owns its DB transactions. No HTTP/runtime consumer is enabled by constructing it.
 *
 * Bootstrap locks installation advisory -> command advisory, then for replay existing
 * principal -> guest -> receipt; new issuance locks the daily counter before inserting rows.
 * Normal use: installation advisory -> principal -> guest -> downstream resources.
 * Issuance and idle renewal share the installation lock, so a
 * concurrently renewed session cannot disappear from the active-session allowance count.
 * Cleanup/merge implementations must preserve that order and cannot erase receipt identity
 * to turn an old key into a fresh issuance. No retention/deletion worker is implemented here.
 */
internal class GuestSessionStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val policy: GuestSessionPolicy,
    private val cipher: GuestReplayCipher,
    private val authority: GuestSessionAuthority,
) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) { "Invalid guest environment" } }
    internal val advertisedCapabilities: List<String> get() = policy.capabilities
    internal fun isBoundTo(environment: String, transactions: PgTransactions): Boolean =
        this.environment == environment && this.transactions === transactions
    private val validator by lazy { ContractBodyValidator.bundled() }
    private val outbox = OutboxStore(transactions)

    fun create(commandKey: UUID, body: ByteArray): GuestSessionReply {
        val request = try { GuestBootstrapRequest.parse(environment, commandKey, body) }
            catch (_: IllegalArgumentException) { fail(GuestSessionFailureCode.INPUT_INVALID) }
        return safe {
            transactions.run { c ->
                checkCompatibility(c)
                val guard = Guard(c)
                advisory(c, "installation", request.installationSha256)
                advisory(c, "bootstrap-command", commandKey.toString())
                val original = receipt(c, commandKey, locked = false)
                authority.requireBootstrap(c, request, original != null); guard.check()
                if (original != null) replay(c, guard, request, original)
                else issue(c, guard, request)
            }
        }
    }

    /** The callback is DB-only, on this exact transaction/thread; never commit, roll back,
     * retain the connection, perform external effects, or open another store transaction.
     * Returned principal metadata is not a portable authorization grant. Current root and
     * policy are checked again after the callback before idle time advances and commit.
     */
    fun <T> withCurrent(token: String, operation: String,
        action: (Connection, VerifiedKitchenPrincipal) -> T): T = useCurrent(token, operation, action, complete = { _, _, _ -> })

    /** Additional rejecting domain check AFTER the mandatory final guest authority. This is
     * not an authority provider: every credential/lifecycle/policy check below still runs.
     * The owner invokes it on the same actual actor, thread and transaction before commit,
     * then checks guest expiry again. It may only perform DB checks/throw, never commit,
     * disclose externally or retain the connection. Used for result deadlines and actual
     * domain-state revalidation after final authority callbacks and waits. */
    internal fun <T> withCurrentCompletion(token: String, operation: String,
        action: (Connection, VerifiedKitchenPrincipal) -> T,
        complete: (Connection, VerifiedKitchenPrincipal, T) -> Unit,
        completeAt: (Connection, VerifiedKitchenPrincipal, T, Instant) -> Unit = { _, _, _, _ -> }): T =
        useCurrent(token, operation, action, complete, completeAt)

    /** The two existing explicit Make Again commands require all constituent capabilities.
     * The actual authority checks every operation before work and again before completion;
     * no nested session transaction or caller-provided principal can authorize a child. */
    internal fun <T> withMakeAgainCompletion(token: String, operation: String,
        action: (Connection, VerifiedKitchenPrincipal) -> T,
        complete: (Connection, VerifiedKitchenPrincipal, T) -> Unit,
        completeAt: (Connection, VerifiedKitchenPrincipal, T, Instant) -> Unit): T {
        require(operation in setOf("saveRecipe", "completeCookSession"))
        return useCurrent(token, operation, action, complete, completeAt, setOf("saveRecipe", "createFeedback"))
    }

    private fun <T> useCurrent(token: String, operation: String,
        action: (Connection, VerifiedKitchenPrincipal) -> T,
        complete: (Connection, VerifiedKitchenPrincipal, T) -> Unit,
        completeAt: (Connection, VerifiedKitchenPrincipal, T, Instant) -> Unit = { _, _, _, _ -> },
        requiredOperations: Set<String> = emptySet()): T = safe {
        val operations = (listOf(operation) + requiredOperations).distinct()
        fun checkedCurrent(c: Connection, guest: Guest): Instant {
            val at = current(c, guest, operation)
            if (!guest.capabilities.containsAll(operations)) fail(GuestSessionFailureCode.POLICY_BLOCKED)
            return at
        }
        val material = try { GuestTokenMaterial.parse(token) }
            catch (_: IllegalArgumentException) { fail(GuestSessionFailureCode.UNAUTHENTICATED) }
        val lookup = material.lookupSha256(environment)
        transactions.run { c ->
            checkCompatibility(c)
            val guard = Guard(c)
            val root = query(c, "SELECT id,installation_sha256 FROM identity.guest_sessions WHERE environment=? AND token_sha256=?",
                { setString(1, environment); setString(2, lookup) }) { it.getObject(1, UUID::class.java) to it.getString(2) }
                ?: fail(GuestSessionFailureCode.UNAUTHENTICATED)
            advisory(c, "installation", root.second)
            val guest = lockGuest(c, root.first)
            if (!same(guest.tokenHash, lookup) || !same(guest.installationHash, root.second))
                fail(GuestSessionFailureCode.UNAUTHENTICATED)
            checkedCurrent(c, guest)
            operations.forEach { authority.requireCurrent(c, guest.id, guest.principalId, it); guard.check() }
            val admitted = lockGuest(c, guest.id)
            if (!same(admitted.tokenHash, lookup) || !same(admitted.installationHash, root.second) || admitted.principalId != guest.principalId)
                fail(GuestSessionFailureCode.UNAUTHENTICATED)
            checkedCurrent(c, admitted)
            val actor = VerifiedKitchenPrincipal(environment, CommandActor.GUEST, guest.principalId, null)
            val result = action(c, actor)
            guard.check()
            operations.forEach { authority.requireCurrent(c, guest.id, guest.principalId, it); guard.check() }
            val latest = lockGuest(c, guest.id)
            if (!same(latest.tokenHash, lookup) || !same(latest.installationHash, root.second) || latest.principalId != guest.principalId)
                fail(GuestSessionFailureCode.UNAUTHENTICATED)
            val now = checkedCurrent(c, latest)
            val idle = minOf(now.plusSeconds(policy.idleLifetimeSeconds), latest.absolute)
            execute(c, "UPDATE identity.guest_sessions SET last_seen_at=?,inactivity_expires_at=? WHERE environment=? AND id=?") {
                instant(1, now); instant(2, idle); setString(3, environment); setObject(4, guest.id)
            }
            guard.check()
            checkedCurrent(c, lockGuest(c, guest.id))
            complete(c, actor, result)
            guard.check()
            // A rejecting completion may itself wait; its success never renews or replaces
            // this actual root's eligibility and cannot outlive current guest expiry.
            val acceptedAt = checkedCurrent(c, lockGuest(c, guest.id))
            // Pure rejecting deadline fence, using this ACTUAL final database time.
            // It must not perform SQL/network/storage I/O, refresh proof or issue authority.
            // No database wait follows it before the transaction owner's commit.
            completeAt(c, actor, result, acceptedAt)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest completion interrupted")
            result
        }
    }

    /** Nonrenewing lifecycle read. This intrinsic operation is not a product capability.
     * No callback result is reused as metadata: the response is made from the final locked
     * row after both mandatory authority checks, with the final database time. No token,
     * principal, installation or receipt material leaves this boundary. */
    fun currentSession(token: String): GuestCurrentSessionReply = safe {
        val material = try { GuestTokenMaterial.parse(token) }
            catch (_: IllegalArgumentException) { fail(GuestSessionFailureCode.UNAUTHENTICATED) }
        val lookup = material.lookupSha256(environment)
        transactions.run { c ->
            checkCompatibility(c)
            val guard = Guard(c)
            val root = query(c, "SELECT id,installation_sha256 FROM identity.guest_sessions WHERE environment=? AND token_sha256=?",
                { setString(1, environment); setString(2, lookup) }) { it.getObject(1, UUID::class.java) to it.getString(2) }
                ?: fail(GuestSessionFailureCode.UNAUTHENTICATED)
            advisory(c, "installation", root.second)
            val guest = lockGuest(c, root.first)
            fun exact(value: Guest) {
                if (!same(value.tokenHash, lookup) || !same(value.installationHash, root.second) || value.principalId != guest.principalId)
                    fail(GuestSessionFailureCode.UNAUTHENTICATED)
            }
            exact(guest); current(c, guest, null)
            authority.requireCurrent(c, guest.id, guest.principalId, "getCurrentGuestSession"); guard.check()
            val admitted = lockGuest(c, guest.id)
            exact(admitted); current(c, admitted, null)
            authority.requireCurrent(c, guest.id, guest.principalId, "getCurrentGuestSession"); guard.check()
            val latest = lockGuest(c, guest.id)
            exact(latest)
            val at = current(c, latest, null)
            val text = buildJsonObject {
                put("guestSessionId", latest.id.toString()); put("environment", environment)
                put("expiresAt", latest.absolute.toString()); put("inactivityExpiresAt", latest.idle.toString())
                put("serverTime", at.toString()); put("capabilities", JsonArray(latest.capabilities.map(::JsonPrimitive)))
                put("dailyPlanLimit", latest.dailyPlanLimit)
            }.toString()
            val bytes = text.encodeToByteArray()
            try {
                if (bytes.size > 8192 || validator.validateResponse("getCurrentGuestSession", 200, bytes, "application/json") != BodyValidationResult.Valid)
                    fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
            } finally { bytes.fill(0) }
            guard.check()
            GuestCurrentSessionReply(text)
        }
    }

    private fun issue(c: Connection, guard: Guard, request: GuestBootstrapRequest): GuestSessionReply {
        // New issuance now depends on the preference and event schemas. A historical exact
        // replay does not use these writers and must not become an implicit backfill path.
        checkMigration(c, 1, "/db/migration/V001__durable_platform.sql")
        checkMigration(c, 4, "/db/migration/V004__private_kitchen.sql")
        guard.check()
        val day = now(c).atOffset(ZoneOffset.UTC).toLocalDate()
        execute(c, "INSERT INTO identity.guest_issuance_windows(environment,installation_sha256,window_date,issued_count) VALUES(?,?,?,0) ON CONFLICT DO NOTHING",
            requireOne = false) { setString(1, environment); setString(2, request.installationSha256); setObject(3, day) }
        val issued = query(c, "SELECT issued_count FROM identity.guest_issuance_windows WHERE environment=? AND installation_sha256=? AND window_date=? FOR UPDATE",
            { setString(1, environment); setString(2, request.installationSha256); setObject(3, day) }) { it.getInt(1) }
            ?: fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
        guard.check()
        val at = now(c)
        // A counter-row wait must not stamp a new credential with an old time or charge
        // yesterday's allowance. The caller can retry the same uncommitted command.
        if (at.atOffset(ZoneOffset.UTC).toLocalDate() != day) fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
        if (issued >= policy.maxIssuedPerInstallationPerDay) fail(GuestSessionFailureCode.LIMIT_REACHED)
        val active = query(c, "SELECT count(*) FROM identity.guest_sessions g JOIN identity.principals p ON p.environment=g.environment AND p.guest_session_id=g.id " +
            "WHERE g.environment=? AND g.installation_sha256=? AND p.kind='guest' AND p.status='active' AND g.revoked_at IS NULL AND g.merged_to_user_id IS NULL " +
            "AND g.inactivity_expires_at>? AND g.absolute_expires_at>?",
            { setString(1, environment); setString(2, request.installationSha256); instant(3, at); instant(4, at) }) { it.getLong(1) }
            ?: fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
        if (active >= policy.maxActivePerInstallation) fail(GuestSessionFailureCode.LIMIT_REACHED)
        val id = UUID.randomUUID(); val principal = UUID.randomUUID(); val token = GuestTokenMaterial.issue()
        val absolute = at.plusSeconds(policy.absoluteLifetimeSeconds)
        val idle = minOf(at.plusSeconds(policy.idleLifetimeSeconds), absolute)
        val reply = response(id, token.revealForResponse(), absolute)
        check(validator.validateResponse("createGuestSession", 201, reply.encodeToByteArray(), "application/json") == BodyValidationResult.Valid)
        val binding = binding(request, id)
        val bytes = reply.encodeToByteArray()
        val envelope = try { cipher.seal(binding, bytes) } finally { bytes.fill(0) }
        execute(c, "INSERT INTO identity.guest_sessions(environment,id,installation_sha256,token_sha256,created_at,last_seen_at,inactivity_expires_at,absolute_expires_at,policy_revision,capabilities,daily_plan_limit) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?)") {
            setString(1, environment); setObject(2, id); setString(3, request.installationSha256); setString(4, token.lookupSha256(environment))
            instant(5, at); instant(6, at); instant(7, idle); instant(8, absolute); setString(9, policy.bindingSha256)
            setString(10, JsonArray(policy.capabilities.map(::JsonPrimitive)).toString()); setInt(11, policy.dailyPlanLimit)
        }
        execute(c, "INSERT INTO identity.principals(environment,id,user_id,guest_session_id,kind,status,version,created_at,updated_at) VALUES(?,?,NULL,?,'guest','active',1,?,?)") {
            setString(1, environment); setObject(2, principal); setObject(3, id); instant(4, at); instant(5, at)
        }
        // The foundation is unanswered, not an allergy/equipment/consent declaration. Its
        // row and event share this issuance transaction and the final mandatory policy gate.
        // Exact-original replay never provisions or overwrites an existing preference.
        GuestUnansweredPreferenceProvisioning.provision(c, environment, principal, request.commandKey, outbox)
        execute(c, "INSERT INTO identity.guest_bootstrap_receipts(environment,command_key,installation_sha256,request_sha256,guest_session_id,key_id,nonce,ciphertext,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)") {
            setString(1, environment); setObject(2, request.commandKey); setString(3, request.installationSha256); setString(4, request.requestSha256)
            setObject(5, id); setString(6, envelope.keyId); setBytes(7, envelope.nonce); setBytes(8, envelope.ciphertext)
            instant(9, at); instant(10, minOf(at.plusSeconds(policy.replayLifetimeSeconds), idle))
        }
        execute(c, "UPDATE identity.guest_issuance_windows SET issued_count=issued_count+1 WHERE environment=? AND installation_sha256=? AND window_date=?") {
            setString(1, environment); setString(2, request.installationSha256); setObject(3, day)
        }
        authority.requireBootstrap(c, request, false); guard.check()
        val guest = lockGuest(c, id)
        current(c, guest, null)
        val written = receipt(c, request.commandKey, locked = true) ?: fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
        val checked = readReply(c, request, guest, written)
        if (checked != reply) fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
        guard.check()
        current(c, lockGuest(c, guest.id), null)
        if (now(c) >= written.expires) fail(GuestSessionFailureCode.EXPIRED)
        return GuestSessionReply(checked, false)
    }

    private fun replay(c: Connection, guard: Guard, request: GuestBootstrapRequest, initial: Receipt): GuestSessionReply {
        if (!same(initial.installationHash, request.installationSha256) || !same(initial.requestHash, request.requestSha256))
            fail(GuestSessionFailureCode.ORIGINAL_MISMATCH)
        val guest = lockGuest(c, initial.guestId)
        current(c, guest, null)
        val original = receipt(c, request.commandKey, locked = true) ?: fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
        if (original.guestId != initial.guestId) fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
        authority.requireBootstrap(c, request, true); guard.check()
        val latest = lockGuest(c, guest.id)
        current(c, latest, null)
        val exact = readReply(c, request, latest, original)
        guard.check()
        current(c, lockGuest(c, guest.id), null)
        if (now(c) >= original.expires) fail(GuestSessionFailureCode.EXPIRED)
        // Replay does not advance last_seen, idle/absolute expiry, counters, or issue a new token.
        return GuestSessionReply(exact, true)
    }

    private fun readReply(c: Connection, request: GuestBootstrapRequest, guest: Guest, receipt: Receipt): String {
        if (!same(receipt.installationHash, request.installationSha256) || !same(receipt.requestHash, request.requestSha256) ||
            !same(guest.installationHash, request.installationSha256) || receipt.guestId != guest.id || receipt.created != guest.created ||
            receipt.expires > guest.absolute || receipt.expires > guest.created.plusSeconds(policy.replayLifetimeSeconds))
            fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
        if (now(c) >= receipt.expires) fail(GuestSessionFailureCode.EXPIRED)
        val plaintext = try { cipher.open(binding(request, guest.id), receipt.envelope) }
            catch (_: IllegalArgumentException) { fail(GuestSessionFailureCode.NOT_CONFIGURED) }
        try {
            if (validator.validateResponse("createGuestSession", 201, plaintext, "application/json") != BodyValidationResult.Valid)
                fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
            val exact = plaintext.decodeToString(throwOnInvalidSequence = true)
            val body = Json.parseToJsonElement(exact).jsonObject
            val token = GuestTokenMaterial.parse(body.getValue("guestToken").jsonPrimitive.content)
            if (!same(token.lookupSha256(environment), guest.tokenHash) ||
                exact != response(guest.id, token.revealForResponse(), guest.absolute)) fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
            return exact
        } finally { plaintext.fill(0) }
    }

    private fun lockGuest(c: Connection, id: UUID): Guest {
        val principal = query(c, "SELECT id,kind,status,user_id FROM identity.principals WHERE environment=? AND guest_session_id=? FOR UPDATE",
            { setString(1, environment); setObject(2, id) }) {
            if (it.getString(2) != "guest" || it.getString(3) != "active" || it.getObject(4) != null) fail(GuestSessionFailureCode.UNAUTHENTICATED)
            it.getObject(1, UUID::class.java)
        } ?: fail(GuestSessionFailureCode.UNAUTHENTICATED)
        return query(c, "SELECT * FROM identity.guest_sessions WHERE environment=? AND id=? FOR UPDATE",
            { setString(1, environment); setObject(2, id) }) {
            Guest(id, principal, it.getString("installation_sha256"), it.getString("token_sha256"), time(it, "created_at"),
                time(it, "last_seen_at"), time(it, "inactivity_expires_at"), time(it, "absolute_expires_at"),
                it.getObject("revoked_at") != null || it.getObject("merged_to_user_id") != null, it.getString("policy_revision"),
                Json.parseToJsonElement(it.getString("capabilities")).jsonArray.map { v -> v.jsonPrimitive.content }, it.getInt("daily_plan_limit"))
        } ?: fail(GuestSessionFailureCode.UNAUTHENTICATED)
    }

    private fun current(c: Connection, guest: Guest, operation: String?): Instant {
        val at = now(c)
        if (guest.disabled) fail(GuestSessionFailureCode.UNAUTHENTICATED)
        if (at < guest.created || at < guest.lastSeen) fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
        if (at >= guest.idle || at >= guest.absolute) fail(GuestSessionFailureCode.EXPIRED)
        if (guest.policyHash != policy.bindingSha256 || guest.capabilities != policy.capabilities || guest.dailyPlanLimit != policy.dailyPlanLimit)
            fail(GuestSessionFailureCode.POLICY_BLOCKED)
        if (operation != null && operation !in guest.capabilities) fail(GuestSessionFailureCode.POLICY_BLOCKED)
        return at
    }

    private fun receipt(c: Connection, key: UUID, locked: Boolean): Receipt? =
        query(c, "SELECT * FROM identity.guest_bootstrap_receipts WHERE environment=? AND command_key=?" + if (locked) " FOR UPDATE" else "",
            { setString(1, environment); setObject(2, key) }) {
            Receipt(it.getObject("guest_session_id", UUID::class.java), it.getString("installation_sha256"), it.getString("request_sha256"),
                time(it, "created_at"), time(it, "expires_at"), GuestReplayEnvelope(it.getString("key_id"), it.getBytes("nonce"), it.getBytes("ciphertext")))
        }

    private fun response(id: UUID, token: String, absolute: Instant) = buildJsonObject {
        put("guestSessionId", id.toString()); put("guestToken", token); put("expiresAt", absolute.toString())
        put("capabilities", JsonArray(policy.capabilities.map(::JsonPrimitive))); put("dailyPlanLimit", policy.dailyPlanLimit)
    }.toString()
    private fun binding(request: GuestBootstrapRequest, id: UUID) = GuestReplayBinding(environment, request.commandKey,
        request.installationSha256, request.requestSha256, id)

    private fun checkCompatibility(c: Connection) {
        check(!c.autoCommit)
        checkMigration(c, 18, "/db/migration/V018__guest_sessions.sql")
    }

    private fun checkMigration(c: Connection, version: Int, resource: String) {
        val bytes = GuestSessionStore::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: fail(GuestSessionFailureCode.NOT_CONFIGURED)
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val actual = query(c, "SELECT checksum FROM platform.schema_migrations WHERE version=?", { setInt(1, version) }) { it.getString(1) }
        if (actual != expected) fail(GuestSessionFailureCode.NOT_CONFIGURED)
    }

    private fun advisory(c: Connection, purpose: String, value: String) {
        val input = buildJsonArray { add("feedme.guest-lock.v1"); add(environment); add(purpose); add(value) }.toString().encodeToByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(input)
        c.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { s -> s.setLong(1, ByteBuffer.wrap(hash).long); s.executeQuery().close() }
    }
    private fun now(c: Connection): Instant = query(c, "SELECT date_trunc('milliseconds',clock_timestamp())", {}) {
        it.getObject(1, OffsetDateTime::class.java).toInstant()
    } ?: fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
    private fun time(r: ResultSet, name: String): Instant = r.getObject(name, OffsetDateTime::class.java).toInstant()
    private fun PreparedStatement.instant(index: Int, value: Instant) = setObject(index, value.atOffset(ZoneOffset.UTC))
    private fun execute(c: Connection, sql: String, requireOne: Boolean = true, bind: PreparedStatement.() -> Unit) {
        c.prepareStatement(sql).use { s -> s.bind(); val count = s.executeUpdate(); if (requireOne && count != 1) fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE) }
    }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T? =
        c.prepareStatement(sql).use { s -> s.bind(); s.executeQuery().use { r ->
            if (!r.next()) null else read(r).also { if (r.next()) fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE) }
        } }
    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
    private class Guest(val id: UUID, val principalId: UUID, val installationHash: String, val tokenHash: String,
        val created: Instant, val lastSeen: Instant, val idle: Instant, val absolute: Instant, val disabled: Boolean,
        val policyHash: String, val capabilities: List<String>, val dailyPlanLimit: Int)
    private class Receipt(val guestId: UUID, val installationHash: String, val requestHash: String,
        val created: Instant, val expires: Instant, val envelope: GuestReplayEnvelope)
    private class Guard(private val connection: Connection) {
        private val thread = Thread.currentThread()
        private val transaction = id()
        fun check() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest transaction interrupted")
            if (Thread.currentThread() !== thread || connection.isClosed || connection.autoCommit || id() != transaction)
                throw GuestSessionFailure(GuestSessionFailureCode.STORAGE_UNAVAILABLE)
        }
        private fun id(): Long {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest transaction interrupted")
            return connection.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { r -> check(r.next()); r.getLong(1) } }
        }
    }
    private inline fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: GuestSessionFailure) { throw failure }
        // Canonical kitchen refusals from same-transaction guest callbacks still roll back;
        // retain their declared cursor/input/catalog codes instead of reporting an outage.
        catch (failure: KitchenFailure) { throw failure }
        // Guest planning callbacks own this same transaction; keep typed input/content
        // refusals while still rolling back instead of replacing them with an outage.
        catch (failure: com.feedme.server.planning.PlanningServiceFailure) { throw failure }
        catch (failure: com.feedme.server.cooking.CookingFailure) { throw failure }
        catch (failure: com.feedme.server.memory.SavedRecipeFailure) { throw failure }
        catch (failure: com.feedme.server.memory.FeedbackFailure) { throw failure }
        catch (failure: com.feedme.server.memory.MemoryFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(GuestSessionFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: GuestSessionFailureCode): Nothing = throw GuestSessionFailure(code)
    override fun toString() = "GuestSessionStore(<redacted>)"
}
