package com.feedme.server.identity

import com.feedme.server.db.*
import com.feedme.server.social.*
import com.feedme.server.social.posts.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.*

class AccountReactionNotificationPolicy(val enabled: Boolean, val coalesceSeconds: Int, val maxEventAgeSeconds: Int) {
    init { require(coalesceSeconds in 1..300 && maxEventAgeSeconds in (coalesceSeconds + 1)..3600) }
    override fun toString() = "AccountReactionNotificationPolicy(<redacted>)"
}

/** A dedicated, bounded local consumer, not the global outbox publisher. Only the latest
 * still-active reaction after its quiet window can produce generic in-app activity.
 * No push, historical body, session impersonation, ranking input or external effect. */
internal class AccountReactionNotificationStore(private val environment: String, private val transactions: PgTransactions,
    private val identities: AccountSocialIdentityPolicy, private val posts: AccountPostReadStore,
    val policy: AccountReactionNotificationPolicy, private val workerAccountAuthority: (Connection, UUID) -> Unit) {
    private val consumer = "feedme.reaction-inbox.v1.$environment"
    private val inbox = ConsumerInbox(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && posts.environment == environment) }

    fun consumeBatch(limit: Int = 20): Int {
        require(limit in 1..20)
        if (!policy.enabled) return 0
        val events = transactions.run { c ->
            AccountReactionNotificationCompatibility.check(c)
            query(c, """SELECT e.* FROM platform.outbox e WHERE e.owner_environment=? AND e.schema_version=1
                AND e.event_type IN('social.reaction.changed.v1','social.reaction.removed.v1')
                AND e.occurred_at<=clock_timestamp()-(? * interval '1 second')
                AND NOT EXISTS(SELECT 1 FROM platform.consumer_inbox i WHERE i.consumer_name=? AND i.event_id=e.event_id)
                ORDER BY e.occurred_at,e.event_id LIMIT ?""", {
                setString(1, environment); setInt(2, policy.coalesceSeconds); setString(3, consumer); setInt(4, limit)
            }) { r -> buildList { while (r.next()) add(event(r)) } }
        }
        var consumed = 0
        for (candidate in events) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Reaction consumer interrupted")
            if (inbox.consume(consumer, candidate) { c, original -> consume(c, original) }) consumed++
        }
        return consumed
    }

    /** Recipient-authenticated Inbox calls this in its own transaction, before and after
     * response work. It does not turn notification creation into a content grant. */
    fun visible(c: Connection, recipient: UUID, notificationId: UUID): Boolean {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        if (!policy.enabled) return false
        AccountReactionNotificationCompatibility.check(c)
        val source = query(c, """SELECT actor_user_id,post_id,reaction_id,reaction_version,event_id
            FROM platform.account_reaction_notifications WHERE environment=? AND recipient_user_id=? AND id=? FOR SHARE NOWAIT""", {
            setString(1, environment); setObject(2, recipient); setObject(3, notificationId)
        }) { r -> if (!r.next()) null else Source(recipient, r.uuid(1), r.uuid(2), r.uuid(3), r.getLong(4), r.uuid(5))
            .also { if (r.next()) unavailable() } } ?: return false
        val actual = readEvent(c, source.event)
        if (actual.draft.aggregateId != source.reaction || actual.draft.aggregateVersion != source.version ||
            actual.draft.data != payload(source, actual.draft.data["kind"]?.jsonPrimitive?.content ?: unavailable(), true)) unavailable()
        return authorized(c, source, actual) != null
    }

    private fun consume(c: Connection, candidate: CommittedEvent) {
        val actual = readEvent(c, candidate.draft.eventId)
        if (actual.draft != candidate.draft && !sameEvent(actual, candidate)) unavailable()
        val at = now(c)
        if (actual.occurredAt > at.minusSeconds(policy.coalesceSeconds.toLong())) unavailable()
        if (actual.occurredAt < at.minusSeconds(policy.maxEventAgeSeconds.toLong())) return
        val data = actual.draft.data
        val source = Source(uuid(data, "postOwnerUserId"), uuid(data, "actorUserId"), uuid(data, "postId"),
            actual.draft.aggregateId, actual.draft.aggregateVersion, actual.draft.eventId)
        if (actual.draft.eventType == "social.reaction.removed.v1" || source.actor == source.recipient) return
        val deadline = authorized(c, source, actual) ?: return
        execute(c, "INSERT INTO platform.notification_read_watermarks(environment,user_id,updated_at) VALUES(?,?,clock_timestamp()) ON CONFLICT DO NOTHING", {
            setString(1, environment); setObject(2, source.recipient)
        })
        val through = query(c, "SELECT through_created_at,updated_at FROM platform.notification_read_watermarks WHERE environment=? AND user_id=? FOR UPDATE NOWAIT", {
            setString(1, environment); setObject(2, source.recipient)
        }) { r -> if (!r.next()) unavailable() else (r.getObject(1, OffsetDateTime::class.java)?.toInstant() to r.time(2))
            .also { if (r.next()) unavailable() } }
        // Delivery-placement time follows the locked recipient watermark, never event time.
        val delivered = now(c)
        if (delivered < through.second || through.first?.let { delivered <= it } == true || !delivered.isBefore(deadline)) unavailable()
        finalAccounts(c, source)
        execute(c, """INSERT INTO platform.account_reaction_notifications(environment,recipient_user_id,id,event_id,actor_user_id,
            post_id,reaction_id,reaction_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)""", {
            setString(1, environment); setObject(2, source.recipient); setObject(3, UUID.randomUUID()); setObject(4, source.event)
            setObject(5, source.actor); setObject(6, source.post); setObject(7, source.reaction); setLong(8, source.version)
            instant(9, delivered); instant(10, delivered)
        })
        // Any late loss rolls back BOTH the marker and new activity; it is not a successful
        // suppression after a positive write. A later bounded attempt may suppress it.
        finalAccounts(c, source)
        if (!now(c).isBefore(deadline)) unavailable()
    }

    private class Source(val recipient: UUID, val actor: UUID, val post: UUID, val reaction: UUID, val version: Long, val event: UUID)
    private fun authorized(c: Connection, source: Source, event: CommittedEvent): Instant? = try {
        if (source.recipient == source.actor || event.draft.eventType != "social.reaction.changed.v1") null else {
            finalAccounts(c, source)
            if (!optedIn(c, source.recipient)) null else {
                val deadline = posts.reactionNotificationDeadline(c, source.recipient, source.actor, source.post)
                if (deadline == null || !currentReaction(c, source, event)) null else {
                    finalAccounts(c, source)
                    deadline.takeIf { now(c).isBefore(it) }
                }
            }
        }
    } catch (failure: AccountFailure) {
        if (failure.code == AccountFailureCode.UNAUTHENTICATED && failure.suppressed.isEmpty()) null else throw failure
    } catch (failure: SocialFailure) {
        if (failure.code == SocialFailureCode.CIRCLE_UNAVAILABLE && failure.suppressed.isEmpty()) null else throw failure
    } catch (failure: PostReadFailure) {
        if (failure.code == PostReadFailureCode.POST_UNAVAILABLE && failure.suppressed.isEmpty()) null else throw failure
    }

    private fun finalAccounts(c: Connection, source: Source) {
        for (id in listOf(source.actor, source.recipient).sortedBy(UUID::toString)) {
            workerAccountAuthority(c, id)
            if (identities.readProfile(c, environment, id).userId != id) unavailable()
        }
        identities.lockUnblockedPair(c, environment, source.actor, source.recipient)
    }
    private fun optedIn(c: Connection, recipient: UUID): Boolean = query(c,
        "SELECT reactions FROM profile.notification_settings WHERE environment=? AND user_id=? FOR SHARE NOWAIT", {
            setString(1, environment); setObject(2, recipient)
        }) { r -> if (!r.next()) false else r.getBoolean(1).also { if (r.next()) unavailable() } }

    private fun currentReaction(c: Connection, source: Source, event: CommittedEvent): Boolean = query(c,
        "SELECT * FROM social.post_reactions WHERE environment=? AND id=? FOR SHARE NOWAIT", {
            setString(1, environment); setObject(2, source.reaction)
        }) { r ->
            if (!r.next()) false else {
                if (r.uuid("actor_user_id") != source.actor || r.uuid("post_owner_user_id") != source.recipient ||
                    r.uuid("post_id") != source.post || r.getLong("version") < source.version) unavailable()
                val current = r.getBoolean("active") && r.getLong("version") == source.version
                if (current && (r.getString("last_operation_id") != "setReaction" || r.uuid("last_command_key") != event.draft.causationId ||
                        r.getString("kind") != event.draft.data.getValue("kind").jsonPrimitive.content || r.time("updated_at") > event.occurredAt)) unavailable()
                if (r.next()) unavailable()
                current
            }
        }
    private fun readEvent(c: Connection, id: UUID): CommittedEvent = query(c,
        "SELECT * FROM platform.outbox WHERE event_id=? FOR SHARE NOWAIT", { setObject(1, id) }) {
            if (!it.next()) unavailable() else event(it).also { _ -> if (it.next()) unavailable() }
        }
    private fun event(r: ResultSet): CommittedEvent {
        val data = Json.parseToJsonElement(r.getString("payload")).jsonObject
        val active = r.getString("event_type") == "social.reaction.changed.v1"
        val source = Source(uuid(data, "postOwnerUserId"), uuid(data, "actorUserId"), uuid(data, "postId"),
            r.uuid("aggregate_id"), r.getLong("aggregate_version"), r.uuid("event_id"))
        val kind = data["kind"]?.jsonPrimitive?.content ?: unavailable()
        if (r.getString("event_type") !in setOf("social.reaction.changed.v1", "social.reaction.removed.v1") ||
            r.getInt("schema_version") != 1 || r.getString("producer") != "social" || r.getString("aggregate_type") != "reaction" ||
            r.getString("owner_environment") != environment || r.getString("owner_kind") != "account" || r.uuid("owner_id") != source.actor ||
            r.getString("correlation_id") != r.uuid("causation_id").toString() || source.version <= 0 ||
            kind !in setOf("heart", "looksDoable", "makingThis", "yum") || data != payload(source, kind, active)) unavailable()
        return CommittedEvent(EventDraft(source.event, r.getString("event_type"), 1, "reaction", source.reaction, source.version,
            "social", r.getString("correlation_id"), r.uuid("causation_id"), data, EventOwner.account(environment, source.actor)), r.time("occurred_at"))
    }
    private fun payload(source: Source, kind: String, active: Boolean) = buildJsonObject {
        put("reactionId", source.reaction.toString()); put("postId", source.post.toString()); put("postOwnerUserId", source.recipient.toString())
        put("actorUserId", source.actor.toString()); put("kind", kind); put("active", active)
    }
    private fun sameEvent(a: CommittedEvent, b: CommittedEvent) = a.occurredAt == b.occurredAt &&
        a.draft.eventId == b.draft.eventId && a.draft.aggregateId == b.draft.aggregateId && a.draft.aggregateVersion == b.draft.aggregateVersion &&
        a.draft.eventType == b.draft.eventType && a.draft.causationId == b.draft.causationId && a.draft.data == b.draft.data && a.draft.owner == b.draft.owner
    private fun uuid(body: JsonObject, name: String) = UUID.fromString(body.getValue(name).jsonPrimitive.content)
    private fun ResultSet.uuid(name: String) = getObject(name, UUID::class.java)
    private fun ResultSet.uuid(index: Int) = getObject(index, UUID::class.java)
    private fun ResultSet.time(name: String) = getObject(name, OffsetDateTime::class.java).toInstant()
    private fun ResultSet.time(index: Int) = getObject(index, OffsetDateTime::class.java).toInstant()
    private fun PreparedStatement.instant(index: Int, at: Instant) = setObject(index, OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
    private fun now(c: Connection) = query(c, "SELECT clock_timestamp()", {}) { check(it.next()); it.time(1) }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T = retryLocks {
        c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(read) }
    }
    private fun execute(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) = retryLocks {
        c.prepareStatement(sql).use { it.bind(); it.executeUpdate() }
    }
    private fun <T> retryLocks(action: () -> T): T = try { action() } catch (failure: SQLException) {
        if (failure.sqlState == "55P03") throw SQLException("Reaction notification dependency contended", "40001").also {
            failure.suppressed.forEach(it::addSuppressed)
        }
        throw failure
    }
    private fun unavailable(): Nothing = throw NotificationInboxFailure(NotificationInboxFailureCode.STORAGE_UNAVAILABLE)
    override fun toString() = "AccountReactionNotificationStore(<redacted>)"
}
