package com.feedme.server.social.conversations

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.RecipeCatalogJournal
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.identity.*
import com.feedme.server.social.*
import com.feedme.server.social.posts.PostgresPostReadContentAuthority
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class AccountConversationPolicy(val maxResponseBytes: Int, val cursorLifetimeSeconds: Int, val sendsEnabled: Boolean,
    val maxThreadsPerAccount: Int, val maxMessagesPer24Hours: Int) {
    init { require(maxResponseBytes in 1024..262144 && cursorLifetimeSeconds in 1..86400 && maxThreadsPerAccount in 1..1000 && maxMessagesPer24Hours in 1..10000) }
    override fun toString() = "AccountConversationPolicy(<redacted>)"
}
enum class ConversationFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), THREAD_UNAVAILABLE(404), CONTACT_UNAVAILABLE(403),
    VERSION_CONFLICT(412), MESSAGE_CONFLICT(409), CURSOR_EXPIRED(410), RATE_LIMITED(429), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
class ConversationFailure(val code: ConversationFailureCode) : RuntimeException("Conversation unavailable: ${code.name}")

/** Direct text plus separately authorized recipe-request transitions. Current root/profile,
 * shared-circle, privacy and reciprocal-block facts
 * are locked in the same transaction as the exact durable command and content-free event.
 * Reads do not require a remaining shared circle; new sends do. No push delivery is implied. */
internal class AccountConversationStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val identities: AccountSocialIdentityPolicy,
    private val cursors: ConversationCursors, val policy: AccountConversationPolicy,
    private val recipeMessageCatalog: RecipeCatalogJournal? = null,
    private val notificationInbox: AccountNotificationInboxStore? = null) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment == accounts.environment && environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    /** Same-transaction capability projection only; creation/send independently recheck it.
     * Missing storage or authority is never projected as a successful empty capability. */
    fun canStartDirect(connection: Connection, actor: VerifiedSocialAccount, recipient: UUID): Boolean {
        identities.lockPrincipal(connection, actor)
        if (!policy.sendsEnabled || actor.accountId == recipient) return false
        return try { requireContact(connection, actor.accountId, recipient); true }
        catch (failure: ConversationFailure) {
            if (failure.code == ConversationFailureCode.CONTACT_UNAVAILABLE) false else throw failure
        } catch (failure: SocialFailure) {
            if (failure.code == SocialFailureCode.CIRCLE_UNAVAILABLE) false else throw failure
        }
    }

    /** Request consent is distinct from unsolicited text-message contact. The original
     * requester has asked for a response; only the author's request inbox opt-in applies. */
    internal fun requireRecipeParticipants(c: Connection, actor: VerifiedSocialAccount, requester: UUID, author: UUID, delivery: Boolean) {
        identities.lockPrincipal(c, actor)
        if (actor.accountId !in setOf(requester, author)) fail(ConversationFailureCode.THREAD_UNAVAILABLE)
        identities.readProfile(c, environment, requester); identities.readProfile(c, environment, author)
        requireContact(c, requester, author, recipeRequest = true, requireSending = delivery)
    }
    internal fun resolveRecipeThread(c: Connection, actor: VerifiedSocialAccount, author: UUID): UUID {
        if (actor.accountId == author) fail(ConversationFailureCode.INPUT_INVALID)
        // Same current roots and quota as direct creation, with request-specific consent.
        if (!query(c, "SELECT 1 FROM identity.users WHERE environment=? AND id=? AND status='active' FOR UPDATE NOWAIT", {
            setString(1, environment); setObject(2, author)
        }) { it.next() }) fail(ConversationFailureCode.CONTACT_UNAVAILABLE)
        requireRecipeParticipants(c, actor, actor.accountId, author, delivery = true)
        val pair = sortedPair(actor.accountId, author)
        val existing = query(c, "SELECT id FROM social.direct_threads WHERE environment=? AND user_low=? AND user_high=? FOR UPDATE NOWAIT", {
            setString(1, environment); setObject(2, pair.first); setObject(3, pair.second)
        }) { if (it.next()) it.getObject(1, UUID::class.java) else null }
        if (existing != null) return existing
        for (participant in listOf(pair.first, pair.second)) {
            val count = query(c, "SELECT count(*) FROM social.direct_threads WHERE environment=? AND (user_low=? OR user_high=?)", {
                setString(1, environment); setObject(2, participant); setObject(3, participant)
            }) { it.next(); it.getLong(1) }
            if (count >= policy.maxThreadsPerAccount) fail(ConversationFailureCode.RATE_LIMITED)
        }
        val id = UUID.randomUUID()
        update(c, "INSERT INTO social.direct_threads(environment,id,user_low,user_high,version) VALUES(?,?,?,?,1)", {
            setString(1, environment); setObject(2, id); setObject(3, pair.first); setObject(4, pair.second)
        })
        for (participant in listOf(pair.first, pair.second)) update(c, "INSERT INTO social.thread_read_watermarks(environment,thread_id,user_id) VALUES(?,?,?)", {
            setString(1, environment); setObject(2, id); setObject(3, participant)
        })
        return id
    }
    /** The surrounding request command owns idempotency. This appends exactly one typed
     * message per retained request transition; it neither opens a nested command nor sends
     * a provider push. UUIDs are actual server-created message identities, not client proof. */
    internal fun appendRecipeMessage(c: Connection, actor: VerifiedSocialAccount, threadId: UUID, requestId: UUID,
        kind: String, text: String, recipeVersion: UUID?, key: UUID) {
        if (kind !in setOf("recipeRequest", "recipeCard", "system") || text.length > 2000 || text.any { it.isISOControl() && it !in "\n\r\t" }) fail(ConversationFailureCode.INPUT_INVALID)
        if (!policy.sendsEnabled) fail(ConversationFailureCode.NOT_CONFIGURED)
        identities.lockPrincipal(c, actor)
        if (!recipeRequestVisible(c, requestId)) fail(ConversationFailureCode.THREAD_UNAVAILABLE)
        val row = thread(c, actor.accountId, threadId, true)
        val sent = query(c, "SELECT count(*) FROM social.thread_messages WHERE environment=? AND sender_user_id=? AND created_at>=clock_timestamp()-interval '24 hours'", {
            setString(1, environment); setObject(2, actor.accountId)
        }) { it.next(); it.getLong(1) }
        if (sent >= policy.maxMessagesPer24Hours) fail(ConversationFailureCode.RATE_LIMITED)
        if (row.version == Long.MAX_VALUE || row.sequence == Long.MAX_VALUE) fail(ConversationFailureCode.STORAGE_UNAVAILABLE)
        val at = now(c); val id = UUID.randomUUID()
        update(c, "UPDATE social.direct_threads SET version=version+1,last_sequence=last_sequence+1,last_message_at=?,updated_at=? WHERE environment=? AND id=?", {
            instant(1, at); instant(2, at); setString(3, environment); setObject(4, threadId)
        })
        update(c, "INSERT INTO social.thread_messages(environment,id,thread_id,sender_user_id,client_message_id,sequence,text,created_at,kind,recipe_request_id,recipe_version_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)", {
            setString(1, environment); setObject(2, id); setObject(3, threadId); setObject(4, actor.accountId); setObject(5, UUID.randomUUID())
            setLong(6, row.sequence + 1); setString(7, text); instant(8, at); setString(9, kind); setObject(10, requestId); setObject(11, recipeVersion)
        })
        val eventId = UUID.randomUUID()
        outbox.append(c, EventDraft(eventId, "conversations.message.created.v1", 1, "message", id, 1, "conversations", key.toString(), key,
            buildJsonObject { put("threadId", threadId.toString()); put("messageId", id.toString()); put("senderUserId", actor.accountId.toString()) }, EventOwner.account(environment, actor.accountId)))
        notificationInbox?.appendMessage(c, actor, threadId, id, eventId)
    }

    fun getPrivacySettings(subject: VerifiedSupabaseSubject, device: UUID): StoredReply = safe {
        transactions.run { c ->
            val owner = accounts.lockAccountSafety(c, subject, device)
            val row = privacy(c, owner)
            safetyCurrent(c, subject, device, owner)
            reply("getPrivacySettings", privacyJson(row), row.version)
        }
    }

    fun updatePrivacySettings(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, ifMatch: String, body: JsonObject): CommandResult = safe {
        val input = request("updatePrivacySettings", body); val expected = version(ifMatch)
        transactions.run { c ->
            val owner = accounts.lockAccountSafety(c, subject, device)
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, owner), "updatePrivacySettings", key, body = input, ifMatch = ifMatch)
            val result = commands.executeInTransaction(c, command, { safetyCurrent(it, subject, device, owner) }, {},
                { _, cached -> validateReply("updatePrivacySettings", cached) }) { db ->
                val old = privacy(db, owner)
                if (old.version != expected || expected == Long.MAX_VALUE) fail(ConversationFailureCode.VERSION_CONFLICT)
                val audience = (input["defaultAudience"] as? JsonObject)?.also { validateAudience(db, owner, it) } ?: old.audience
                fun flag(name: String, previous: Boolean) = input[name]?.jsonPrimitive?.boolean ?: previous
                update(db, "UPDATE social.account_privacy SET version=version+1,default_audience=?::jsonb,allow_circle_member_messages=?,allow_recipe_requests=?,analytics_consent=?,allow_coordination_invites=?,social_discovery_visible=?,updated_at=clock_timestamp() WHERE environment=? AND user_id=?", {
                    setString(1, audience.toString()); setBoolean(2, flag("allowCircleMemberMessages", old.messages)); setBoolean(3, flag("allowRecipeRequests", old.requests))
                    setBoolean(4, flag("analyticsConsent", old.analytics)); setBoolean(5, flag("allowCoordinationInvites", old.invites)); setBoolean(6, flag("socialDiscoveryVisible", old.discovery))
                    setString(7, environment); setObject(8, owner)
                })
                val saved = privacy(db, owner); reply("updatePrivacySettings", privacyJson(saved), saved.version)
            }
            safetyCurrent(c, subject, device, owner); result
        }
    }

    fun createThread(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult = safe {
        val input = request("createThread", body)
        if (input["kind"]?.jsonPrimitive?.content != "direct" || input.keys.any { it !in setOf("kind", "recipientUserId") }) fail(ConversationFailureCode.NOT_CONFIGURED)
        val recipient = UUID.fromString(input.getValue("recipientUserId").jsonPrimitive.content)
        transactions.run { c ->
            val actor = identities.resolvePrincipal(c, subject, device)
            val owner = actor.accountId
            // Both account roots serialize the actual persisted per-account thread
            // quota. Never wait while holding our own root against a reciprocal actor.
            if (owner == recipient) fail(ConversationFailureCode.INPUT_INVALID)
            if (!query(c, "SELECT 1 FROM identity.users WHERE environment=? AND id=? AND status='active' FOR UPDATE NOWAIT", {
                setString(1, environment); setObject(2, recipient)
            }) { it.next() }) fail(ConversationFailureCode.CONTACT_UNAVAILABLE)
            requireContact(c, owner, recipient)
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, owner), "createThread", key, body = input)
            val result = commands.executeInTransaction(c, command, { identities.lockPrincipal(it, actor) }, {},
                { db, cached ->
                    val cachedId = cached.body?.jsonObject?.get("id")?.jsonPrimitive?.content?.let(UUID::fromString) ?: fail(ConversationFailureCode.STORAGE_UNAVAILABLE)
                    if (peer(readThread(db, owner, cachedId), owner) != recipient) fail(ConversationFailureCode.STORAGE_UNAVAILABLE)
                    validateReply("createThread", cached)
                }) { db ->
                val pair = sortedPair(owner, recipient); val id = UUID.randomUUID()
                val exists = query(db, "SELECT 1 FROM social.direct_threads WHERE environment=? AND user_low=? AND user_high=?", {
                    setString(1, environment); setObject(2, pair.first); setObject(3, pair.second)
                }) { it.next() }
                if (!exists) for (participant in listOf(owner, recipient)) {
                    val count = query(db, "SELECT count(*) FROM social.direct_threads WHERE environment=? AND (user_low=? OR user_high=?)", {
                        setString(1, environment); setObject(2, participant); setObject(3, participant)
                    }) { it.next(); it.getLong(1) }
                    if (count >= policy.maxThreadsPerAccount) fail(ConversationFailureCode.RATE_LIMITED)
                }
                db.prepareStatement("INSERT INTO social.direct_threads(environment,id,user_low,user_high,version) VALUES(?,?,?,?,1) ON CONFLICT(environment,user_low,user_high) DO NOTHING").use {
                    it.setString(1, environment); it.setObject(2, id); it.setObject(3, pair.first); it.setObject(4, pair.second); it.executeUpdate()
                }
                val row = query(db, "SELECT * FROM social.direct_threads WHERE environment=? AND user_low=? AND user_high=? FOR UPDATE NOWAIT", {
                    setString(1, environment); setObject(2, pair.first); setObject(3, pair.second)
                }) { if (it.next()) threadRow(it) else fail(ConversationFailureCode.STORAGE_UNAVAILABLE) }
                for (participant in listOf(pair.first, pair.second)) db.prepareStatement("INSERT INTO social.thread_read_watermarks(environment,thread_id,user_id) VALUES(?,?,?) ON CONFLICT DO NOTHING").use {
                    it.setString(1, environment); it.setObject(2, row.id); it.setObject(3, participant); it.executeUpdate()
                }
                reply("createThread", threadJson(db, owner, row), row.version, 201)
            }
            identities.lockPrincipal(c, actor); result
        }
    }

    fun getThread(subject: VerifiedSupabaseSubject, device: UUID, threadId: UUID): StoredReply = read(subject, device) { c, owner ->
        val row = readThread(c, owner, threadId); reply("getThread", threadJson(c, owner, row), row.version)
    }

    fun listThreads(subject: VerifiedSupabaseSubject, device: UUID, cursor: String? = null, limit: Int = 20): StoredReply = read(subject, device) { c, owner ->
        requireLimit(limit)
        val position = cursor?.let { cursors.decode(it, environment, owner, "threads", limit, now(c)) }
        // Bound both work and returned rows. Inaccessible peers are omitted, never replaced
        // by invented participants. The cursor advances over examined actual thread rows.
        val candidates = query(c, "SELECT * FROM social.direct_threads WHERE environment=? AND (user_low=? OR user_high=?) " +
            (if (position == null) "" else "AND id>? ") + "ORDER BY id LIMIT ?", {
            setString(1, environment); setObject(2, owner); setObject(3, owner)
            if (position == null) setInt(4, limit + 1) else { setObject(4, UUID.fromString(position.after)); setInt(5, limit + 1) }
        }) { r -> buildList { while (r.next()) add(threadRow(r)) } }
        val considered = candidates.take(limit)
        val rows = considered.mapNotNull { row ->
            if (!visiblePeer(c, owner, peer(row, owner))) null else threadJson(c, owner, row)
        }
        page("listThreads", c, owner, "threads", limit, rows, if (candidates.size > limit) considered.last().id.toString() else null, position)
    }

    fun listMessages(subject: VerifiedSupabaseSubject, device: UUID, threadId: UUID, cursor: String? = null, limit: Int = 20): StoredReply = read(subject, device) { c, owner ->
        requireLimit(limit); val thread = readThread(c, owner, threadId)
        val route = "messages:$threadId"
        val position = cursor?.let { cursors.decode(it, environment, owner, route, limit, now(c)) }
        val profiles = listOf(thread.low, thread.high).associateWith { identities.readProfile(c, environment, it) }
        val rows = query(c, "SELECT * FROM social.thread_messages WHERE environment=? AND thread_id=? AND sequence>? ORDER BY sequence LIMIT ?", {
            setString(1, environment); setObject(2, threadId); setLong(3, position?.after?.toLong() ?: 0); setInt(4, limit + 1)
        }) { r -> buildList { while (r.next()) add(messageRow(r)) } }
        val selected = rows.take(limit)
        page("listMessages", c, owner, route, limit, selected.map { renderMessage(c, owner, it, profiles.getValue(it.sender)) },
            if (rows.size > limit) selected.last().sequence.toString() else null, position)
    }

    fun sendMessage(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, threadId: UUID, body: JsonObject): CommandResult = safe {
        val input = request("sendMessage", body)
        if (input["kind"]?.jsonPrimitive?.content != "text" || input.keys.any { it !in setOf("kind", "text", "clientMessageId") }) fail(ConversationFailureCode.NOT_CONFIGURED)
        val text = input.getValue("text").jsonPrimitive.content
        if (text.isBlank() || text.any { it.isISOControl() && it !in "\n\t\r" }) fail(ConversationFailureCode.INPUT_INVALID)
        val clientId = UUID.fromString(input.getValue("clientMessageId").jsonPrimitive.content)
        transactions.run { c ->
            val actor = identities.resolvePrincipal(c, subject, device); val owner = actor.accountId
            val initial = thread(c, owner, threadId)
            requireContact(c, owner, peer(initial, owner))
            val locked = thread(c, owner, threadId, lock = true)
            val sender = identities.readProfile(c, environment, owner)
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, owner), "sendMessage", key, mapOf("threadId" to threadId.toString()), body = input)
            val result = commands.executeInTransaction(c, command, { identities.lockPrincipal(it, actor) }, {},
                { db, cached ->
                    val persisted = clientMessage(db, owner, clientId) ?: fail(ConversationFailureCode.STORAGE_UNAVAILABLE)
                    if (persisted.kind != "text" || persisted.thread != threadId || persisted.text != text || cached.body?.jsonObject?.get("id")?.jsonPrimitive?.content != persisted.id.toString()) fail(ConversationFailureCode.MESSAGE_CONFLICT)
                    if (!messageVisible(db, persisted.id)) fail(ConversationFailureCode.THREAD_UNAVAILABLE)
                    validateReply("sendMessage", cached)
                }) { db ->
                val existing = clientMessage(db, owner, clientId)
                if (existing != null) {
                    if (existing.kind != "text" || existing.thread != threadId || existing.text != text) fail(ConversationFailureCode.MESSAGE_CONFLICT)
                    if (!messageVisible(db, existing.id)) fail(ConversationFailureCode.THREAD_UNAVAILABLE)
                    return@executeInTransaction reply("sendMessage", messageJson(existing, sender), 1)
                }
                val sent = query(db, "SELECT count(*) FROM social.thread_messages WHERE environment=? AND sender_user_id=? AND created_at>=clock_timestamp()-interval '24 hours'", {
                    setString(1, environment); setObject(2, owner)
                }) { it.next(); it.getLong(1) }
                if (sent >= policy.maxMessagesPer24Hours) fail(ConversationFailureCode.RATE_LIMITED)
                if (locked.version == Long.MAX_VALUE || locked.sequence == Long.MAX_VALUE) fail(ConversationFailureCode.STORAGE_UNAVAILABLE)
                val at = now(db); val id = UUID.randomUUID()
                update(db, "UPDATE social.direct_threads SET version=version+1,last_sequence=last_sequence+1,last_message_at=?,updated_at=? WHERE environment=? AND id=?", {
                    instant(1, at); instant(2, at); setString(3, environment); setObject(4, threadId)
                })
                update(db, "INSERT INTO social.thread_messages(environment,id,thread_id,sender_user_id,client_message_id,sequence,text,created_at) VALUES(?,?,?,?,?,?,?,?)", {
                    setString(1, environment); setObject(2, id); setObject(3, threadId); setObject(4, owner); setObject(5, clientId); setLong(6, locked.sequence + 1); setString(7, text); instant(8, at)
                })
                val eventId = UUID.randomUUID()
                outbox.append(db, EventDraft(eventId, "conversations.message.created.v1", 1, "message", id, 1, "conversations", key.toString(), key,
                    buildJsonObject { put("threadId", threadId.toString()); put("messageId", id.toString()); put("senderUserId", owner.toString()) }, EventOwner.account(environment, owner)))
                notificationInbox?.appendMessage(db, actor, threadId, id, eventId)
                reply("sendMessage", messageJson(clientMessage(db, owner, clientId) ?: fail(ConversationFailureCode.STORAGE_UNAVAILABLE), sender), 1)
            }
            identities.lockPrincipal(c, actor); result
        }
    }

    fun markThreadRead(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, threadId: UUID, body: JsonObject): CommandResult = safe {
        val input = request("markThreadRead", body); val last = UUID.fromString(input.getValue("lastMessageId").jsonPrimitive.content)
        transactions.run { c ->
            val actor = identities.resolvePrincipal(c, subject, device); val owner = actor.accountId
            readThread(c, owner, threadId); val locked = thread(c, owner, threadId, true)
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, owner), "markThreadRead", key, mapOf("threadId" to threadId.toString()), body = input)
            val result = commands.executeInTransaction(c, command, { identities.lockPrincipal(it, actor) }, {},
                { _, cached -> validateReply("markThreadRead", cached) }) { db ->
                val sequence = query(db, "SELECT sequence FROM social.thread_messages WHERE environment=? AND thread_id=? AND id=?", {
                    setString(1, environment); setObject(2, threadId); setObject(3, last)
                }) { if (it.next()) it.getLong(1) else fail(ConversationFailureCode.INPUT_INVALID) }
                val previous = watermark(db, owner, threadId)
                if (sequence > previous) {
                    if (locked.version == Long.MAX_VALUE) fail(ConversationFailureCode.STORAGE_UNAVAILABLE)
                    update(db, "UPDATE social.thread_read_watermarks SET last_sequence=?,updated_at=clock_timestamp() WHERE environment=? AND thread_id=? AND user_id=?", {
                        setLong(1, sequence); setString(2, environment); setObject(3, threadId); setObject(4, owner)
                    })
                    update(db, "UPDATE social.direct_threads SET version=version+1,updated_at=clock_timestamp() WHERE environment=? AND id=?", { setString(1, environment); setObject(2, threadId) })
                }
                val row = thread(db, owner, threadId); reply("markThreadRead", threadJson(db, owner, row), row.version)
            }
            identities.lockPrincipal(c, actor); result
        }
    }

    private fun requireContact(c: Connection, owner: UUID, recipient: UUID, recipeRequest: Boolean = false, requireSending: Boolean = true) {
        if (requireSending && !policy.sendsEnabled) fail(ConversationFailureCode.NOT_CONFIGURED)
        if (owner == recipient) fail(ConversationFailureCode.INPUT_INVALID)
        identities.readProfile(c, environment, recipient)
        val circles = query(c, "SELECT a.circle_id FROM social.circle_members a JOIN social.circle_members b ON b.environment=a.environment AND b.circle_id=a.circle_id WHERE a.environment=? AND a.user_id=? AND b.user_id=? AND a.status='active' AND b.status='active' ORDER BY a.circle_id LIMIT 100", {
            setString(1, environment); setObject(2, owner); setObject(3, recipient)
        }) { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java)) } }
        val shared = circles.any { circle ->
            val active = query(c, "SELECT 1 FROM social.circles WHERE environment=? AND id=? AND status='active' FOR SHARE NOWAIT", { setString(1, environment); setObject(2, circle) }) { it.next() }
            active && query(c, "SELECT count(*) FROM social.circle_members WHERE environment=? AND circle_id=? AND user_id IN (?,?) AND status='active'", {
                setString(1, environment); setObject(2, circle); setObject(3, owner); setObject(4, recipient)
            }) { it.next(); it.getInt(1) == 2 }
        }
        if (!shared) fail(ConversationFailureCode.CONTACT_UNAVAILABLE)
        val consent = privacy(c, recipient)
        if (!(if (recipeRequest) consent.requests else consent.messages)) fail(ConversationFailureCode.CONTACT_UNAVAILABLE)
        identities.lockUnblockedPair(c, environment, owner, recipient)
    }
    private fun visiblePeer(c: Connection, owner: UUID, recipient: UUID): Boolean = try {
        identities.readProfile(c, environment, recipient); identities.lockUnblockedPair(c, environment, owner, recipient); true
    } catch (failure: SocialFailure) {
        if (failure.code == SocialFailureCode.CIRCLE_UNAVAILABLE) false else throw failure
    }
    private fun readThread(c: Connection, owner: UUID, id: UUID): ThreadRow = thread(c, owner, id).also {
        if (!visiblePeer(c, owner, peer(it, owner))) fail(ConversationFailureCode.THREAD_UNAVAILABLE)
    }
    private fun peer(row: ThreadRow, owner: UUID) = if (row.low == owner) row.high else row.low
    private fun sortedPair(a: UUID, b: UUID) = if (a.toString() < b.toString()) a to b else b to a
    private fun thread(c: Connection, owner: UUID, id: UUID, lock: Boolean = false): ThreadRow = query(c,
        "SELECT * FROM social.direct_threads WHERE environment=? AND id=? AND (user_low=? OR user_high=?)" + if (lock) " FOR UPDATE NOWAIT" else "", {
            setString(1, environment); setObject(2, id); setObject(3, owner); setObject(4, owner)
        }) { if (it.next()) threadRow(it) else fail(ConversationFailureCode.THREAD_UNAVAILABLE) }
    private class ThreadRow(val id: UUID, val low: UUID, val high: UUID, val version: Long, val sequence: Long, val last: Instant?, val created: Instant, val updated: Instant)
    private fun threadRow(r: ResultSet) = ThreadRow(r.uuid("id"), r.uuid("user_low"), r.uuid("user_high"), r.getLong("version"), r.getLong("last_sequence"), r.getObject("last_message_at", OffsetDateTime::class.java)?.toInstant(), r.time("created_at"), r.time("updated_at"))
    private fun watermark(c: Connection, owner: UUID, id: UUID): Long = query(c, "SELECT last_sequence FROM social.thread_read_watermarks WHERE environment=? AND thread_id=? AND user_id=?", {
        setString(1, environment); setObject(2, id); setObject(3, owner)
    }) { if (it.next()) it.getLong(1) else fail(ConversationFailureCode.STORAGE_UNAVAILABLE) }
    private fun threadJson(c: Connection, owner: UUID, row: ThreadRow): JsonObject {
        val seen = watermark(c, owner, row.id)
        val unread = query(c, "SELECT count(*) FROM social.thread_messages WHERE environment=? AND thread_id=? AND sender_user_id<>? AND sequence>?", {
            setString(1, environment); setObject(2, row.id); setObject(3, owner); setLong(4, seen)
        }) { it.next(); it.getLong(1) }
        return buildJsonObject {
            put("id", row.id.toString()); put("version", row.version); put("kind", "direct"); put("participantIds", JsonArray(listOf(JsonPrimitive(row.low.toString()), JsonPrimitive(row.high.toString()))))
            put("createdAt", row.created.toString()); put("updatedAt", row.updated.toString()); row.last?.let { put("lastMessageAt", it.toString()) }; put("unreadCount", unread)
        }
    }
    private class MessageRow(val id: UUID, val thread: UUID, val sender: UUID, val client: UUID, val sequence: Long, val text: String, val created: Instant,
        val kind: String, val requestId: UUID?, val recipeVersion: UUID?)
    private fun messageRow(r: ResultSet) = MessageRow(r.uuid("id"), r.uuid("thread_id"), r.uuid("sender_user_id"), r.uuid("client_message_id"), r.getLong("sequence"), r.getString("text"), r.time("created_at"),
        r.getString("kind"), r.getObject("recipe_request_id", UUID::class.java), r.getObject("recipe_version_id", UUID::class.java))
    private fun clientMessage(c: Connection, sender: UUID, client: UUID): MessageRow? = query(c, "SELECT * FROM social.thread_messages WHERE environment=? AND sender_user_id=? AND client_message_id=?", {
        setString(1, environment); setObject(2, sender); setObject(3, client)
    }) { if (it.next()) messageRow(it) else null }
    private fun messageJson(row: MessageRow, sender: SocialProfileSummary, available: Boolean = true) = buildJsonObject {
        put("id", row.id.toString()); put("version", 1); put("createdAt", row.created.toString()); put("updatedAt", row.created.toString())
        put("threadId", row.thread.toString()); put("sender", sender.json()); put("kind", row.kind); put("text", if (available) row.text else "")
        put("clientMessageId", row.client.toString()); put("availability", if (available) "available" else "contextUnavailable")
        row.requestId?.let { put("recipeRequestId", it.toString()) }
        if (available) row.recipeVersion?.let { put("recipeVersionId", it.toString()) }
    }
    private fun renderMessage(c: Connection, owner: UUID, row: MessageRow, sender: SocialProfileSummary): JsonObject {
        // Moderation changes visibility, not the immutable message or its original receipt.
        // Keep its real position/identity so paging never silently skips an unavailable item.
        if (!messageVisible(c, row.id)) return messageJson(row, sender, available = false)
        if (row.kind == "text") return messageJson(row, sender)
        if (!recipeRequestVisible(c, row.requestId ?: fail(ConversationFailureCode.STORAGE_UNAVAILABLE)))
            return messageJson(row, sender, available = false)
        val request = query(c, "SELECT requester_user_id,author_user_id,status,expires_at,recipe_version_id FROM social.recipe_requests WHERE environment=? AND id=? AND thread_id=?", {
            setString(1, environment); setObject(2, row.requestId); setObject(3, row.thread)
        }) { r ->
            if (!r.next()) fail(ConversationFailureCode.STORAGE_UNAVAILABLE)
            val requester = r.uuid("requester_user_id"); val author = r.uuid("author_user_id")
            if (owner !in setOf(requester, author)) fail(ConversationFailureCode.THREAD_UNAVAILABLE)
            if ((row.kind == "recipeRequest" && row.sender != requester) ||
                (row.kind in setOf("recipeCard", "system") && row.sender != author) ||
                (row.kind == "recipeCard" && (r.getString("status") != "fulfilled" || row.recipeVersion != r.getObject("recipe_version_id", UUID::class.java))) ||
                (row.kind == "system" && r.getString("status") != "declined")) fail(ConversationFailureCode.STORAGE_UNAVAILABLE)
            Triple(requester, author, r.getString("status") != "unavailable" && (r.getString("status") != "pending" || now(c) < r.time("expires_at")))
        }
        var available = request.third
        if (available) try { requireContact(c, request.first, request.second, recipeRequest = true, requireSending = false) }
        catch (failure: ConversationFailure) { if (failure.code == ConversationFailureCode.CONTACT_UNAVAILABLE) available = false else throw failure }
        catch (failure: SocialFailure) { if (failure.code == SocialFailureCode.CIRCLE_UNAVAILABLE) available = false else throw failure }
        if (available && row.recipeVersion != null) {
            val catalog = recipeMessageCatalog ?: fail(ConversationFailureCode.NOT_CONFIGURED)
            val view = catalog.openView(c); val actual = view.lookupCurrent(row.recipeVersion)?.entry
            available = actual != null && actual.recipe["reviewStatus"] == JsonPrimitive("published") && actual.recipe["contentLicense"] == JsonPrimitive("catalogRedistributable") &&
                actual.review["freeCatalogEligible"] == JsonPrimitive(true) && actual.recall == null && actual.rightsReference.isNotBlank()
            view.checkCurrent()
        }
        return messageJson(row, sender, available)
    }

    private fun messageVisible(c: Connection, id: UUID): Boolean {
        PostgresPostReadContentAuthority.lockModeration(c)
        return query(c, "SELECT NOT EXISTS(SELECT 1 FROM safety.moderation_cases WHERE environment=? " +
            "AND target_type='message' AND target_id=? AND action IN ('hide','remove'))", {
            setString(1, environment); setObject(2, id)
        }) { r -> if (!r.next()) fail(ConversationFailureCode.STORAGE_UNAVAILABLE)
            r.getBoolean(1).also { if (r.next()) fail(ConversationFailureCode.STORAGE_UNAVAILABLE) } }
    }

    /** The real request ties its source Post and typed messages together. A removed
     * card/note cannot be reopened through getRecipeRequest or a historical receipt.
     * This is only a negative visibility fence; participant/catalog authority is separate. */
    internal fun recipeRequestVisible(c: Connection, id: UUID): Boolean {
        PostgresPostReadContentAuthority.lockModeration(c)
        return query(c, "SELECT NOT EXISTS(SELECT 1 FROM safety.moderation_cases mc " +
            "WHERE mc.environment=? AND mc.action IN ('hide','remove') AND " +
            "((mc.target_type='post' AND EXISTS(SELECT 1 FROM social.recipe_requests r " +
            "WHERE r.environment=mc.environment AND r.id=? AND r.post_id=mc.target_id)) " +
            "OR (mc.target_type='message' AND EXISTS(SELECT 1 FROM social.thread_messages m " +
            "WHERE m.environment=mc.environment AND m.recipe_request_id=? AND m.id=mc.target_id))))", {
            setString(1, environment); setObject(2, id); setObject(3, id)
        }) { r -> if (!r.next()) fail(ConversationFailureCode.STORAGE_UNAVAILABLE)
            r.getBoolean(1).also { if (r.next()) fail(ConversationFailureCode.STORAGE_UNAVAILABLE) } }
    }
    private class PrivacyRow(val id: UUID, val version: Long, val audience: JsonObject, val messages: Boolean, val requests: Boolean, val analytics: Boolean, val invites: Boolean, val discovery: Boolean, val created: Instant, val updated: Instant)
    private fun privacy(c: Connection, owner: UUID): PrivacyRow = query(c, "SELECT * FROM social.account_privacy WHERE environment=? AND user_id=? FOR SHARE NOWAIT", {
        setString(1, environment); setObject(2, owner)
    }) { r -> if (!r.next()) fail(ConversationFailureCode.NOT_CONFIGURED)
        PrivacyRow(r.uuid("id"), r.getLong("version"), Json.parseToJsonElement(r.getString("default_audience")).jsonObject, r.getBoolean("allow_circle_member_messages"), r.getBoolean("allow_recipe_requests"), r.getBoolean("analytics_consent"), r.getBoolean("allow_coordination_invites"), r.getBoolean("social_discovery_visible"), r.time("created_at"), r.time("updated_at")) }
    private fun privacyJson(row: PrivacyRow) = buildJsonObject {
        put("id", row.id.toString()); put("version", row.version); put("createdAt", row.created.toString()); put("updatedAt", row.updated.toString()); put("defaultAudience", row.audience)
        put("allowCircleMemberMessages", row.messages); put("allowRecipeRequests", row.requests); put("analyticsConsent", row.analytics); put("publicDiscoveryEnabled", false)
        put("allowCoordinationInvites", row.invites); put("socialDiscoveryVisible", row.discovery)
    }
    private fun validateAudience(c: Connection, owner: UUID, audience: JsonObject) {
        if ("bindings" in audience) fail(ConversationFailureCode.INPUT_INVALID)
        val ids = audience.getValue("circleIds").jsonArray.map { UUID.fromString(it.jsonPrimitive.content) }
        if (ids.size > 50 || ids.size != ids.distinct().size || (audience.getValue("kind").jsonPrimitive.content == "self") != ids.isEmpty()) fail(ConversationFailureCode.INPUT_INVALID)
        for (id in ids.sortedBy(UUID::toString)) {
            val active = query(c, "SELECT 1 FROM social.circles WHERE environment=? AND id=? AND status='active' FOR SHARE NOWAIT", { setString(1, environment); setObject(2, id) }) { it.next() }
            val member = query(c, "SELECT 1 FROM social.circle_members WHERE environment=? AND circle_id=? AND user_id=? AND status='active'", { setString(1, environment); setObject(2, id); setObject(3, owner) }) { it.next() }
            if (!active || !member) fail(ConversationFailureCode.CONTACT_UNAVAILABLE)
        }
    }
    private fun page(operation: String, c: Connection, owner: UUID, route: String, limit: Int, items: List<JsonObject>, next: String?, position: ConversationCursors.Position?): StoredReply {
        val at = now(c)
        if (position != null && !at.isBefore(position.expires)) fail(ConversationFailureCode.CURSOR_EXPIRED)
        val cursor = next?.let { cursors.encode(environment, owner, route, limit, it, position?.expires ?: at.plusSeconds(policy.cursorLifetimeSeconds.toLong())) }
        return reply(operation, buildJsonObject { put("items", JsonArray(items)); put("nextCursor", cursor?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", at.toString()) })
    }
    private fun <T> read(subject: VerifiedSupabaseSubject, device: UUID, action: (Connection, UUID) -> T): T = safe { transactions.run { c ->
        val actor = identities.resolvePrincipal(c, subject, device); val result = action(c, actor.accountId); identities.lockPrincipal(c, actor); result
    } }
    private fun safetyCurrent(c: Connection, subject: VerifiedSupabaseSubject, device: UUID, owner: UUID) { if (accounts.lockAccountSafety(c, subject, device) != owner) fail(ConversationFailureCode.UNAUTHENTICATED) }
    private fun request(operation: String, body: JsonObject): JsonObject {
        val bytes = body.toString().encodeToByteArray()
        if (bytes.size > 16384 || validator.validateRequest(operation, bytes, "application/json") != BodyValidationResult.Valid) fail(ConversationFailureCode.INPUT_INVALID)
        return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }
    private fun reply(operation: String, body: JsonObject, version: Long? = null, status: Int = 200) = StoredReply(status, body, version?.let { "\"$it\"" }).also { validateReply(operation, it) }
    private fun validateReply(operation: String, reply: StoredReply) {
        val bytes = reply.body?.toString()?.encodeToByteArray()
        if ((bytes?.size ?: 0) > policy.maxResponseBytes || validator.validateResponse(operation, reply.status, bytes, "application/json") != BodyValidationResult.Valid) fail(ConversationFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun version(value: String): Long = value.takeIf { it.matches(Regex("\"[1-9][0-9]{0,18}\"")) }?.drop(1)?.dropLast(1)?.toLongOrNull() ?: fail(ConversationFailureCode.INPUT_INVALID)
    private fun requireLimit(value: Int) { if (value !in 1..50) fail(ConversationFailureCode.INPUT_INVALID) }
    private fun ResultSet.uuid(name: String) = getObject(name, UUID::class.java)
    private fun ResultSet.time(name: String) = getObject(name, OffsetDateTime::class.java).toInstant()
    private fun PreparedStatement.instant(index: Int, value: Instant) = setObject(index, value.atOffset(java.time.ZoneOffset.UTC))
    private fun now(c: Connection) = query(c, "SELECT clock_timestamp()", {}) { it.next(); it.getObject(1, OffsetDateTime::class.java).toInstant() }
    private fun update(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) = c.prepareStatement(sql).use { it.bind(); check(it.executeUpdate() == 1) }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, result: (ResultSet) -> T): T = c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(result) }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: ConversationFailure) { throw failure }
        catch (failure: SocialFailure) { fail(when (failure.code) {
            SocialFailureCode.UNAUTHENTICATED -> ConversationFailureCode.UNAUTHENTICATED
            SocialFailureCode.NOT_CONFIGURED -> ConversationFailureCode.NOT_CONFIGURED
            SocialFailureCode.CIRCLE_UNAVAILABLE -> ConversationFailureCode.THREAD_UNAVAILABLE
            else -> ConversationFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: AccountFailure) { fail(when (failure.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> ConversationFailureCode.UNAUTHENTICATED
            AccountFailureCode.NOT_CONFIGURED -> ConversationFailureCode.NOT_CONFIGURED
            else -> ConversationFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(ConversationFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "AccountConversationStore(<redacted>)"
    companion object {
        private val validator by lazy { ContractBodyValidator.bundled() }
        private fun fail(code: ConversationFailureCode): Nothing = throw ConversationFailure(code)
    }
}
