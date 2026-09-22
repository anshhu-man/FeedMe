package com.feedme.server.social

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.identity.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class BlockServicePolicy(val maxResponseBytes: Int, val cursorLifetimeSeconds: Int) {
    init { require(maxResponseBytes in 1..262144 && cursorLifetimeSeconds in 1..86400) }
    override fun toString() = "BlockServicePolicy(<redacted>)"
}
enum class BlockFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), TARGET_UNAVAILABLE(404), VERSION_CONFLICT(412),
    CURSOR_EXPIRED(410), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
class BlockFailure(val code: BlockFailureCode) : RuntimeException("Safety operation unavailable: ${code.name}")

/** Actual account-owned blocking, not social readiness. Safety remains available without
 * eligible/ready profile, current Terms or enabled social creation. Every transaction resolves
 * the real provider/account/device and rechecks it AFTER domain and durable receipt work.
 * Blocking writes mutual deny + permanent invitation cutoff + an outbox hint atomically.
 * The event is not evidence that notification/cache consumers have already processed it.
 */
internal class AccountBlockStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val cursors: BlockCursors, val policy: BlockServicePolicy) {
    private val relationships = SocialBlockRelationships()
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && accounts.environment == environment) }

    fun blockUser(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult = safe {
        val input = request(body)
        val target = UUID.fromString(input.getValue("targetUserId").jsonPrimitive.content)
        transactions.run { c ->
            val owner = accounts.lockAccountSafety(c, subject, device)
            if (owner == target) fail(BlockFailureCode.INPUT_INVALID)
            lockTarget(c, target)
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, owner), "blockUser", key, body = input)
            val result = commands.executeInTransaction(c, command,
                { current(it, subject, device, owner) }, {},
                { db, cached ->
                    relationships.lockForChange(db, environment, owner, target)
                    val row = block(db, owner, target) ?: fail(BlockFailureCode.TARGET_UNAVAILABLE)
                    if (!row.active || cached.status != 200 || cached.etag != etag(row.version) || cached.body != json(row))
                        fail(BlockFailureCode.VERSION_CONFLICT)
                    validateReply("blockUser", cached)
                }) { db ->
                relationships.lockForChange(db, environment, owner, target)
                val old = block(db, owner, target)
                if (old?.active == true) return@executeInTransaction reply("blockUser", json(old), old.version)
                if (old == null) exec(db, "INSERT INTO social.blocks(environment,owner_user_id,target_user_id,id,version,active,last_command_key,created_at,updated_at) " +
                    "SELECT ?,?,?,?,1,true,?,t,t FROM (SELECT clock_timestamp() t) clock", {
                    setString(1, environment); setObject(2, owner); setObject(3, target); setObject(4, UUID.randomUUID()); setObject(5, key)
                }) else {
                    nextVersion(old)
                    exec(db, "UPDATE social.blocks SET active=true,version=version+1,last_command_key=?,updated_at=clock_timestamp() " +
                        "WHERE environment=? AND owner_user_id=? AND target_user_id=?", {
                        setObject(1, key); setString(2, environment); setObject(3, owner); setObject(4, target)
                    })
                }
                relationships.advance(db, environment, owner, target)
                val row = block(db, owner, target) ?: fail(BlockFailureCode.STORAGE_UNAVAILABLE)
                event(db, owner, row, key, "blocked")
                reply("blockUser", json(row), row.version)
            }
            current(c, subject, device, owner)
            result
        }
    }

    fun unblockUser(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, targetUserId: UUID,
        ifMatch: String): CommandResult = safe {
        val expected = version(ifMatch)
        transactions.run { c ->
            val owner = accounts.lockAccountSafety(c, subject, device)
            if (owner == targetUserId) fail(BlockFailureCode.INPUT_INVALID)
            lockTarget(c, targetUserId)
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, owner), "unblockUser", key,
                mapOf("userId" to targetUserId.toString()), ifMatch = ifMatch)
            val result = commands.executeInTransaction(c, command, { current(it, subject, device, owner) }, {},
                { db, cached ->
                    relationships.lockForChange(db, environment, owner, targetUserId)
                    val row = block(db, owner, targetUserId) ?: fail(BlockFailureCode.TARGET_UNAVAILABLE)
                    if (row.active || row.commandKey != key || expected == Long.MAX_VALUE || row.version != expected + 1)
                        fail(BlockFailureCode.VERSION_CONFLICT)
                    validateReply("unblockUser", cached)
                    if (cached.etag != null || cached.status != 204 || cached.body != null) fail(BlockFailureCode.STORAGE_UNAVAILABLE)
                }) { db ->
                relationships.lockForChange(db, environment, owner, targetUserId)
                val old = block(db, owner, targetUserId) ?: fail(BlockFailureCode.TARGET_UNAVAILABLE)
                if (!old.active || old.version != expected) fail(BlockFailureCode.VERSION_CONFLICT)
                nextVersion(old)
                exec(db, "UPDATE social.blocks SET active=false,version=version+1,last_command_key=?,updated_at=clock_timestamp() " +
                    "WHERE environment=? AND owner_user_id=? AND target_user_id=?", {
                    setObject(1, key); setString(2, environment); setObject(3, owner); setObject(4, targetUserId)
                })
                relationships.advance(db, environment, owner, targetUserId)
                event(db, owner, block(db, owner, targetUserId) ?: fail(BlockFailureCode.STORAGE_UNAVAILABLE), key, "unblocked")
                StoredReply(204)
            }
            current(c, subject, device, owner)
            result
        }
    }

    fun listBlocks(subject: VerifiedSupabaseSubject, device: UUID, cursor: String? = null, limit: Int = 20): StoredReply = safe {
        if (limit !in 1..50) fail(BlockFailureCode.INPUT_INVALID)
        transactions.run { c ->
            val owner = accounts.lockAccountSafety(c, subject, device)
            val position = cursor?.let { cursors.decode(it, environment, owner, limit, now(c)) }
            val rows = query(c, "SELECT * FROM social.blocks WHERE environment=? AND owner_user_id=? AND active " +
                (if (position == null) "" else "AND id>? ") + "ORDER BY id LIMIT ?", {
                setString(1, environment); setObject(2, owner)
                if (position == null) setInt(3, limit + 1) else { setObject(3, position.afterId); setInt(4, limit + 1) }
            }) { result -> buildList { while (result.next()) add(row(result)) } }
            current(c, subject, device, owner)
            val time = now(c)
            if (position != null && !time.isBefore(position.expiresAt)) fail(BlockFailureCode.CURSOR_EXPIRED)
            val items = rows.take(limit)
            val next = if (rows.size > limit) cursors.encode(environment, owner, limit, items.last().id,
                position?.expiresAt ?: time.plusSeconds(policy.cursorLifetimeSeconds.toLong())) else null
            reply("listBlocks", buildJsonObject {
                put("items", JsonArray(items.map(::json))); put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull)
                put("serverTime", time.toString())
            })
        }
    }

    private fun current(c: Connection, subject: VerifiedSupabaseSubject, device: UUID, owner: UUID) {
        if (accounts.lockAccountSafety(c, subject, device) != owner) fail(BlockFailureCode.UNAUTHENTICATED)
    }
    /** Take the foreign lifecycle fence BEFORE receipt/pair locks, including active-row
     * no-op commands and replay. Retain it through commit so deletion cannot pass a zero
     * reference inventory while this command can still create a receipt, pair or event.
     * Never wait on a foreign actor root: reciprocal block commands already hold their
     * own roots. NOWAIT contention aborts with the existing storage-unavailable result. */
    private fun lockTarget(c: Connection, target: UUID) {
        val available = query(c, "SELECT 1 FROM identity.users WHERE environment=? AND id=? " +
            "AND status IN ('active','suspended') FOR SHARE NOWAIT", {
            setString(1, environment); setObject(2, target)
        }) { it.next() }
        if (!available) fail(BlockFailureCode.TARGET_UNAVAILABLE)
    }
    private fun block(c: Connection, owner: UUID, target: UUID): Row? = query(c,
        "SELECT * FROM social.blocks WHERE environment=? AND owner_user_id=? AND target_user_id=?", {
            setString(1, environment); setObject(2, owner); setObject(3, target)
        }) { if (it.next()) row(it) else null }
    private fun row(r: ResultSet) = Row(r.getObject("id", UUID::class.java), r.getObject("target_user_id", UUID::class.java),
        r.getLong("version"), r.getBoolean("active"), r.getObject("last_command_key", UUID::class.java),
        r.getObject("created_at", OffsetDateTime::class.java).toInstant(), r.getObject("updated_at", OffsetDateTime::class.java).toInstant())
    private class Row(val id: UUID, val target: UUID, val version: Long, val active: Boolean, val commandKey: UUID,
        val createdAt: Instant, val updatedAt: Instant)
    private fun json(row: Row) = buildJsonObject {
        put("id", row.id.toString()); put("version", row.version); put("targetUserId", row.target.toString())
        put("createdAt", row.createdAt.toString()); put("updatedAt", row.updatedAt.toString())
    }
    private fun event(c: Connection, owner: UUID, row: Row, key: UUID, action: String) = outbox.append(c,
        EventDraft(UUID.randomUUID(), "safety.block.changed.v1", 1, "block", row.id, row.version, "safety", key.toString(), key,
            buildJsonObject { put("blockerUserId", owner.toString()); put("blockedUserId", row.target.toString()); put("action", action) }))
    private fun request(body: JsonObject): JsonObject {
        val bytes = body.toString().encodeToByteArray()
        if (bytes.size > 65536 || validator.validateRequest("blockUser", bytes, "application/json") != BodyValidationResult.Valid)
            fail(BlockFailureCode.INPUT_INVALID)
        return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }
    private fun reply(operation: String, body: JsonObject, version: Long? = null): StoredReply =
        StoredReply(200, body, version?.let(::etag)).also { validateReply(operation, it) }
    private fun validateReply(operation: String, reply: StoredReply) {
        val bytes = reply.body?.toString()?.encodeToByteArray()
        if ((bytes?.size ?: 0) > policy.maxResponseBytes ||
            validator.validateResponse(operation, reply.status, bytes, if (bytes == null) null else "application/json") != BodyValidationResult.Valid)
            fail(BlockFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun nextVersion(row: Row) { if (row.version == Long.MAX_VALUE) fail(BlockFailureCode.STORAGE_UNAVAILABLE) }
    private fun etag(version: Long) = "\"$version\""
    private fun version(value: String): Long {
        if (!value.matches(Regex("\"[1-9][0-9]{0,18}\""))) fail(BlockFailureCode.INPUT_INVALID)
        return value.substring(1, value.length - 1).toLongOrNull() ?: fail(BlockFailureCode.INPUT_INVALID)
    }
    private fun now(c: Connection) = query(c, "SELECT clock_timestamp()", {}) {
        check(it.next()); it.getObject(1, OffsetDateTime::class.java).toInstant()
    }
    private fun exec(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) = c.prepareStatement(sql).use {
        it.bind(); check(it.executeUpdate() == 1)
    }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, result: (ResultSet) -> T): T =
        c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(result) }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: BlockFailure) { throw failure }
        catch (failure: BlockCursorFailure) { fail(if (failure.reason == BlockCursorFailureReason.EXPIRED)
            BlockFailureCode.CURSOR_EXPIRED else BlockFailureCode.INPUT_INVALID) }
        catch (failure: AccountFailure) { fail(when (failure.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> BlockFailureCode.UNAUTHENTICATED
            AccountFailureCode.NOT_CONFIGURED -> BlockFailureCode.NOT_CONFIGURED
            else -> BlockFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(BlockFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "AccountBlockStore(<redacted>)"
    companion object {
        private val validator by lazy { ContractBodyValidator.bundled() }
        private fun fail(code: BlockFailureCode): Nothing = throw BlockFailure(code)
    }
}
