package com.feedme.server.identity

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.social.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class AccountNotificationInboxPolicy(val maxResponseBytes: Int, val cursorLifetimeSeconds: Int) {
    init { require(maxResponseBytes in 4096..262144 && cursorLifetimeSeconds in 1..86400) }
    override fun toString() = "AccountNotificationInboxPolicy(<redacted>)"
}
enum class NotificationInboxFailureCode(val status: Int) {
    INPUT_INVALID(422), NOTIFICATION_UNAVAILABLE(404), CURSOR_EXPIRED(410), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503)
}
class NotificationInboxFailure(val code: NotificationInboxFailureCode) : RuntimeException("Notification inbox unavailable: ${code.name}")

/** Recipient-owned in-app receipts only. No push, history backfill, implicit read on open,
 * peer read receipt or side effect on the source thread's message watermark. */
internal class AccountNotificationInboxStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val identities: AccountSocialIdentityPolicy,
    private val cursors: NotificationInboxCursors, val policy: AccountNotificationInboxPolicy,
    private val reactions: (() -> AccountReactionNotificationStore?)? = null) {
    private val commands = DurableCommands(transactions)
    private val validator = ContractBodyValidator.bundled()
    init { require(environment == accounts.environment && environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    /** Only the actual message producer calls this after its existing outbox append, within
     * that SAME transaction. SQL verifies both originals have this transaction's xmin. */
    internal fun appendMessage(c: Connection, actor: VerifiedSocialAccount, threadId: UUID, messageId: UUID, eventId: UUID) = safe {
        NotificationInboxServingCompatibility.check(c)
        identities.lockPrincipal(c, actor)
        if (actor.environment != environment) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
        val recipient = query(c, """SELECT t.user_low,t.user_high,m.sender_user_id FROM social.thread_messages m
            JOIN social.direct_threads t ON t.environment=m.environment AND t.id=m.thread_id
            WHERE m.environment=? AND m.id=? AND m.thread_id=? FOR SHARE OF m,t NOWAIT""", {
            setString(1, environment); setObject(2, messageId); setObject(3, threadId)
        }) { r ->
            if (!r.next() || r.uuid(3) != actor.accountId) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
            val low = r.uuid(1); val high = r.uuid(2)
            if (actor.accountId !in setOf(low, high) || r.next()) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
            if (actor.accountId == low) high else low
        }
        identities.readProfile(c, environment, recipient)
        identities.lockUnblockedPair(c, environment, actor.accountId, recipient)
        val optedIn = query(c, "SELECT replies FROM profile.notification_settings WHERE environment=? AND user_id=? FOR SHARE NOWAIT", {
            setString(1, environment); setObject(2, recipient)
        }) { r -> if (!r.next()) false else r.getBoolean(1).also { if (r.next()) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE) } }
        if (!optedIn) return@safe
        initialize(c, recipient)
        val watermark = watermark(c, recipient, true)
        val at = now(c)
        if (watermark.updated > at || watermark.through?.let { at <= it } == true) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
        val id = UUID.randomUUID()
        execute(c, """INSERT INTO platform.account_notifications(environment,recipient_user_id,id,event_id,thread_id,message_id,
            sender_user_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)""", {
            setString(1, environment); setObject(2, recipient); setObject(3, id); setObject(4, eventId)
            setObject(5, threadId); setObject(6, messageId); setObject(7, actor.accountId); instant(8, at); instant(9, at)
        })
        val recorded = row(c, recipient, id)
        if (recorded.event != eventId || recorded.thread != threadId || recorded.message != messageId ||
            recorded.sender != actor.accountId || recorded.created != at || recorded.version != 1L || recorded.read != null)
            fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
        identities.readProfile(c, environment, recipient)
        identities.lockUnblockedPair(c, environment, actor.accountId, recipient)
        identities.lockPrincipal(c, actor)
    }

    fun listNotifications(subject: VerifiedSupabaseSubject, device: UUID, limit: Int, cursor: String?): StoredReply = safe {
        if (limit !in 1..50) fail(NotificationInboxFailureCode.INPUT_INVALID)
        transactions.run { c ->
            NotificationInboxServingCompatibility.check(c)
            val actor = actor(c, subject, device); val owner = actor.accountId
            val at = now(c)
            val position = cursor?.let { cursors.decode(it, environment, owner, limit, at) }
            val through = position?.through ?: at
            val expires = position?.expires ?: at.plusSeconds(policy.cursorLifetimeSeconds.toLong())
            val mark = watermark(c, owner)
            fun sourceRows(reaction: Boolean): List<Row> = query(c, """SELECT * FROM ${table(reaction)} WHERE environment=? AND recipient_user_id=?
                AND created_at<=? ${if (position != null) "AND (created_at,id)<(?,?)" else ""}
                ORDER BY created_at DESC,id DESC LIMIT ? FOR SHARE NOWAIT""", {
                setString(1, environment); setObject(2, owner); instant(3, through)
                var index = 4
                if (position != null) { instant(index++, position.after); setObject(index++, position.id) }
                setInt(index, limit * 4 + 1)
            }) { r -> buildList { while (r.next()) add(decode(r, reaction)) } }
            val rows = (sourceRows(false) + if (reactions != null) sourceRows(true) else emptyList())
                .sortedWith(compareByDescending<Row> { it.created }.thenByDescending { it.id.toString() })
                .take(limit * 4 + 1)
            if (rows.map { it.id }.distinct().size != rows.size) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
            val items = mutableListOf<JsonObject>(); val admitted = mutableListOf<Row>()
            var scanned: Row? = null; var consumed = 0
            for (row in rows.take(limit * 4)) {
                if (visible(c, owner, row)) {
                    val item = json(row, mark, at)
                    if (page(items + item, "x".repeat(512), through).toString().encodeToByteArray().size > policy.maxResponseBytes) {
                        if (items.isEmpty()) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
                        break
                    }
                    items += item; admitted += row
                }
                scanned = row; consumed++
                if (items.size == limit) break
            }
            val next = if (consumed < rows.size) scanned?.let {
                cursors.encode(environment, owner, limit, NotificationInboxCursors.Position(through, it.created, it.id, expires))
            } ?: fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE) else null
            admitted.forEach { if (!visible(c, owner, it)) fail(NotificationInboxFailureCode.NOTIFICATION_UNAVAILABLE) }
            current(c, subject, device, actor)
            val finalAt = now(c)
            if (finalAt < at || finalAt >= expires) fail(NotificationInboxFailureCode.CURSOR_EXPIRED)
            // Cursor membership remains anchored at its original cutoff; response metadata
            // is observed now and can include a read watermark advanced between pages.
            reply("listNotifications", page(items, next, finalAt))
        }
    }

    fun markNotificationRead(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, notificationId: UUID,
        body: JsonObject): CommandResult = safe {
        validateInput("markNotificationRead", body)
        transactions.run { c ->
            NotificationInboxServingCompatibility.check(c)
            val actor = actor(c, subject, device); val owner = actor.accountId
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, owner), "markNotificationRead", key,
                mapOf("notificationId" to notificationId.toString()), body = body)
            val result = commands.executeInTransaction(c, command, { current(it, subject, device, actor) }, {}, { db, cached ->
                val original = row(db, owner, notificationId)
                if (!visible(db, owner, original)) fail(NotificationInboxFailureCode.NOTIFICATION_UNAVAILABLE)
                validateReply("markNotificationRead", cached)
                val response = cached.body?.jsonObject ?: fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
                if (response["id"] != JsonPrimitive(notificationId.toString()) || response["objectId"] != JsonPrimitive(original.objectId.toString()) ||
                    response["kind"] != JsonPrimitive(original.kind) || response["objectType"] != JsonPrimitive(original.objectType) || response["readAt"] == null)
                    fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
            }) { db ->
                val mark = watermark(db, owner, true)
                val before = row(db, owner, notificationId, true)
                if (!visible(db, owner, before)) fail(NotificationInboxFailureCode.NOTIFICATION_UNAVAILABLE)
                val at = now(db)
                if (before.read == null && mark.through?.let { before.created <= it } != true) {
                    if (at < before.updated || mark.updated > at) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
                    execute(db, """UPDATE ${table(before.post != null)} SET read_at=?,updated_at=?,version=version+1
                        WHERE environment=? AND recipient_user_id=? AND id=? AND read_at IS NULL""", {
                        instant(1, at); instant(2, at); setString(3, environment); setObject(4, owner); setObject(5, notificationId)
                    })
                }
                val saved = row(db, owner, notificationId)
                if (!visible(db, owner, saved)) fail(NotificationInboxFailureCode.NOTIFICATION_UNAVAILABLE)
                reply("markNotificationRead", json(saved, mark, at), true)
            }
            if (!visible(c, owner, row(c, owner, notificationId))) fail(NotificationInboxFailureCode.NOTIFICATION_UNAVAILABLE)
            current(c, subject, device, actor); result
        }
    }

    fun markNotificationsRead(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult = safe {
        validateInput("markNotificationsRead", body)
        val through = try { Instant.parse(body.getValue("throughCreatedAt").jsonPrimitive.content) }
            catch (_: Exception) { fail(NotificationInboxFailureCode.INPUT_INVALID) }
        if (through < Instant.EPOCH || through.nano % 1000 != 0) fail(NotificationInboxFailureCode.INPUT_INVALID)
        transactions.run { c ->
            NotificationInboxServingCompatibility.check(c)
            val actor = actor(c, subject, device); val owner = actor.accountId
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, owner), "markNotificationsRead", key, body = body)
            val result = commands.executeInTransaction(c, command, { current(it, subject, device, actor) }, {}, { _, cached ->
                validateReply("markNotificationsRead", cached)
            }) { db ->
                initialize(db, owner)
                val previous = watermark(db, owner, true)
                val at = now(db)
                if (through > at) fail(NotificationInboxFailureCode.INPUT_INVALID)
                if (previous.updated > at) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
                if (previous.through == null || through > previous.through) {
                    if (previous.version >= Long.MAX_VALUE - 2) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
                    execute(db, """UPDATE platform.notification_read_watermarks SET through_created_at=?,read_at=?,version=version+1,updated_at=?
                        WHERE environment=? AND user_id=?""", {
                        instant(1, through); instant(2, at); instant(3, at); setString(4, environment); setObject(5, owner)
                    })
                }
                reply("markNotificationsRead", buildJsonObject {})
            }
            current(c, subject, device, actor); result
        }
    }

    private class Row(val id: UUID, val event: UUID, val thread: UUID?, val message: UUID?, val sender: UUID,
        val created: Instant, val updated: Instant, val version: Long, val read: Instant?, val post: UUID? = null) {
        val kind get() = if (post == null) "reply" else "reaction"
        val objectType get() = if (post == null) "thread" else "post"
        val objectId get() = post ?: checkNotNull(thread)
    }
    private class Watermark(val through: Instant?, val read: Instant?, val version: Long, val updated: Instant)
    private fun table(reaction: Boolean) = if (reaction) "platform.account_reaction_notifications" else "platform.account_notifications"
    private fun decode(r: ResultSet, reaction: Boolean = false) = Row(r.uuid("id"), r.uuid("event_id"),
        if (reaction) null else r.uuid("thread_id"), if (reaction) null else r.uuid("message_id"),
        r.uuid(if (reaction) "actor_user_id" else "sender_user_id"), r.time("created_at"), r.time("updated_at"),
        r.getLong("version"), r.optionalTime("read_at"), if (reaction) r.uuid("post_id") else null)
    private fun row(c: Connection, owner: UUID, id: UUID, lock: Boolean = false): Row {
        fun from(reaction: Boolean): Row? = query(c,
            "SELECT * FROM ${table(reaction)} WHERE environment=? AND recipient_user_id=? AND id=? FOR ${if (lock) "UPDATE" else "SHARE"} NOWAIT", {
                setString(1, environment); setObject(2, owner); setObject(3, id)
            }) { r -> if (!r.next()) null else decode(r, reaction).also { if (r.next()) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE) } }
        val message = from(false)
        val reaction = if (reactions != null) from(true) else null
        if (message != null && reaction != null) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
        return message ?: reaction ?: fail(NotificationInboxFailureCode.NOTIFICATION_UNAVAILABLE)
    }
    private fun initialize(c: Connection, owner: UUID) = execute(c,
        "INSERT INTO platform.notification_read_watermarks(environment,user_id,updated_at) VALUES(?,?,clock_timestamp()) ON CONFLICT DO NOTHING", {
            setString(1, environment); setObject(2, owner)
        }, exact = false)
    private fun watermark(c: Connection, owner: UUID, lock: Boolean = false): Watermark = query(c,
        "SELECT * FROM platform.notification_read_watermarks WHERE environment=? AND user_id=? FOR ${if (lock) "UPDATE" else "SHARE"} NOWAIT", {
            setString(1, environment); setObject(2, owner)
        }) { r -> if (!r.next()) Watermark(null, null, 0, Instant.EPOCH) else
            Watermark(r.optionalTime("through_created_at"), r.optionalTime("read_at"), r.getLong("version"), r.time("updated_at")) }
    private fun visible(c: Connection, owner: UUID, row: Row): Boolean {
        if (row.post != null) return (reactions?.invoke()
            ?: fail(NotificationInboxFailureCode.NOT_CONFIGURED)).visible(c, owner, row.id)
        val bound = query(c, """SELECT 1 FROM social.direct_threads t JOIN social.thread_messages m ON m.environment=t.environment AND m.thread_id=t.id
            WHERE t.environment=? AND t.id=? AND m.id=? AND m.sender_user_id=? AND ? IN(t.user_low,t.user_high)
            AND ? IN(t.user_low,t.user_high) FOR SHARE OF t,m NOWAIT""", {
            setString(1, environment); setObject(2, row.thread); setObject(3, row.message); setObject(4, row.sender)
            setObject(5, owner); setObject(6, row.sender)
        }) { it.next() }
        if (!bound || owner == row.sender) return false
        return try {
            identities.readProfile(c, environment, row.sender)
            identities.lockUnblockedPair(c, environment, owner, row.sender)
            true
        } catch (failure: SocialFailure) {
            if (failure.code == SocialFailureCode.CIRCLE_UNAVAILABLE && failure.suppressed.isEmpty()) false else throw failure
        }
    }
    private fun actor(c: Connection, subject: VerifiedSupabaseSubject, device: UUID): VerifiedSocialAccount =
        identities.resolvePrincipal(c, subject, device).also {
            if (accounts.lockAccountSafety(c, subject, device) != it.accountId) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
        }
    private fun current(c: Connection, subject: VerifiedSupabaseSubject, device: UUID, actor: VerifiedSocialAccount) {
        identities.lockPrincipal(c, actor)
        if (accounts.lockAccountSafety(c, subject, device) != actor.accountId) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun json(row: Row, mark: Watermark, at: Instant): JsonObject {
        val version = Math.addExact(row.version, mark.version)
        val updated = maxOf(row.updated, mark.updated)
        val read = row.read ?: mark.read?.takeIf { mark.through?.let { row.created <= it } == true }
        if (version <= 0 || row.created < Instant.EPOCH || updated > at || read?.let { it < row.created || it > updated } == true)
            fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
        return buildJsonObject {
            put("id", row.id.toString()); put("version", version); put("createdAt", row.created.toString()); put("updatedAt", updated.toString())
            put("kind", row.kind); put("objectType", row.objectType); put("objectId", row.objectId.toString())
            read?.let { put("readAt", it.toString()) }
            put("preview", if (row.post == null) "You have a new message." else "You have a new reaction.")
        }
    }
    private fun page(items: List<JsonObject>, next: String?, through: Instant) = buildJsonObject {
        put("items", JsonArray(items)); put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", through.toString())
    }
    private fun validateInput(operation: String, body: JsonObject) {
        val bytes = body.toString().encodeToByteArray()
        if (bytes.size > 4096 || validator.validateRequest(operation, bytes, "application/json") != BodyValidationResult.Valid)
            fail(NotificationInboxFailureCode.INPUT_INVALID)
    }
    private fun reply(operation: String, body: JsonObject, etag: Boolean = false) =
        StoredReply(200, body, if (etag) "\"${body.getValue("version").jsonPrimitive.content}\"" else null).also { validateReply(operation, it) }
    private fun validateReply(operation: String, reply: StoredReply) {
        val body = reply.body?.jsonObject ?: fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
        val expected = if (operation == "markNotificationRead") "\"${body.getValue("version").jsonPrimitive.content}\"" else null
        val bytes = body.toString().encodeToByteArray()
        if (reply.status != 200 || reply.etag != expected || bytes.size > policy.maxResponseBytes ||
            validator.validateResponse(operation, 200, bytes, "application/json") != BodyValidationResult.Valid)
            fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun now(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
        check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant()
    } }
    private fun PreparedStatement.instant(index: Int, value: Instant) = setObject(index, value.atOffset(ZoneOffset.UTC))
    private fun ResultSet.uuid(column: String) = getObject(column, UUID::class.java)
    private fun ResultSet.uuid(column: Int) = getObject(column, UUID::class.java)
    private fun ResultSet.time(column: String) = getObject(column, OffsetDateTime::class.java).toInstant()
    private fun ResultSet.optionalTime(column: String) = getObject(column, OffsetDateTime::class.java)?.toInstant()
    private fun execute(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, exact: Boolean = true) =
        c.prepareStatement(sql).use { s -> s.bind(); val count = s.executeUpdate(); if (exact && count != 1) fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE); Unit }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T =
        c.prepareStatement(sql).use { s -> s.bind(); s.executeQuery().use(read) }
    private fun fail(code: NotificationInboxFailureCode): Nothing = throw NotificationInboxFailure(code)
    private inline fun <T> safe(block: () -> T): T = try { block() }
        catch (e: NotificationInboxFailure) { throw e } catch (e: SocialFailure) { throw e } catch (e: AccountFailure) { throw e }
        catch (e: CommitOutcomeUnknown) { throw e } catch (e: CancellationException) { throw e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (_: Exception) { fail(NotificationInboxFailureCode.STORAGE_UNAVAILABLE) }
}
