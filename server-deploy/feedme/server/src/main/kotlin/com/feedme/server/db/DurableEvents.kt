package com.feedme.server.db

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlinx.serialization.json.*

/** Internal storage attribution, not an authentication credential or a list of every data subject.
 * Account IDs and private principal IDs are deliberately different namespaces. Legacy events
 * without attribution remain unknown; neither payload UUIDs nor aggregate IDs may fill the gap.
 */
enum class EventOwnerKind(val storageValue: String) {
    ACCOUNT("account"), PRIVATE_PRINCIPAL("private_principal"), GUEST_PRINCIPAL("guest_principal");

    companion object {
        internal fun parse(value: String) = entries.singleOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("Unknown event owner kind")
    }
}

data class EventOwner(val environment: String, val kind: EventOwnerKind, val id: UUID) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }
    override fun toString() = "EventOwner([redacted])"

    companion object {
        fun account(environment: String, id: UUID) = EventOwner(environment, EventOwnerKind.ACCOUNT, id)
        fun principal(environment: String, kind: CommandActor, id: UUID) = EventOwner(environment, when (kind) {
            CommandActor.ACCOUNT -> EventOwnerKind.PRIVATE_PRINCIPAL
            CommandActor.GUEST -> EventOwnerKind.GUEST_PRINCIPAL
            CommandActor.STAFF -> throw IllegalArgumentException("A staff actor is not a private cooking principal")
        }, id)
    }
}

/** Module owners validate the type-specific data and exclude private content before appending. */
class EventDraft(
    val eventId: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val producer: String,
    val correlationId: String,
    val causationId: UUID,
    data: JsonObject,
    val owner: EventOwner? = null,
) {
    val data: JsonObject = Json.parseToJsonElement(data.toString()).jsonObject
    init {
        require(eventType.length <= 120 && eventType.matches(Regex("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+\\.v[1-9][0-9]*")))
        require(schemaVersion > 0 && aggregateVersion > 0)
        require(aggregateType.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,79}")))
        require(producer.matches(Regex("[a-z][a-z0-9-]{0,79}")))
        require(correlationId.isNotBlank() && correlationId.length <= 128 && correlationId.none { it.code < 32 || it.code == 127 })
        require(data.toString().toByteArray(Charsets.UTF_8).size <= 65536)
    }
    override fun toString() = "EventDraft(type=$eventType, schemaVersion=$schemaVersion, data=[redacted])"

    /** Enforced on new writes, not historical reads. Unknown event families remain explicitly
     * unattributed until their producer has an independently reviewed ownership contract. */
    internal fun requireAppendOwnership() {
        val field = when (eventType) {
            "social.post_draft.changed.v1" -> {
                require(owner?.kind == EventOwnerKind.ACCOUNT) { "Draft event ownership is required" }
                val claimedEnvironment = data["environment"] as? JsonPrimitive
                require(claimedEnvironment?.isString == true && claimedEnvironment.content == owner!!.environment) {
                    "Draft event environment and owner differ"
                }
                "ownerId"
            }
            in ACCOUNT_EVENTS -> {
                require(owner?.kind == EventOwnerKind.ACCOUNT) { "Account event ownership is required" }
                "userId"
            }
            in PRINCIPAL_EVENTS -> {
                require(owner?.kind in setOf(EventOwnerKind.PRIVATE_PRINCIPAL, EventOwnerKind.GUEST_PRINCIPAL)) {
                    "Private event ownership is required"
                }
                "principalId"
            }
            else -> return
        }
        val claimedId = data[field] as? JsonPrimitive
        require(claimedId?.isString == true && claimedId.content == owner!!.id.toString()) {
            "Event owner and domain fact differ"
        }
    }

    companion object {
        private val ACCOUNT_EVENTS = setOf("identity.account.bootstrapped.v1", "identity.session.revoked.v1",
            "profile.profile.changed.v1", "identity.account.deletion_requested.v1")
        private val PRINCIPAL_EVENTS = setOf("profile.preferences.changed.v1", "pantry.item.changed.v1",
            "planning.plan.created.v1", "cooking.session.started.v1", "cooking.session.progressed.v1",
            "cooking.session.completed.v1", "memory.recipe.saved.v1", "memory.recipe.deleted.v1",
            "memory.collection.changed.v1", "memory.feedback.changed.v1", "memory.preference.changed.v1")
    }
}

class CommittedEvent(val draft: EventDraft, val occurredAt: Instant) {
    fun envelope(): JsonObject = buildJsonObject {
        put("eventId", draft.eventId.toString()); put("eventType", draft.eventType)
        put("schemaVersion", draft.schemaVersion); put("aggregateType", draft.aggregateType)
        put("aggregateId", draft.aggregateId.toString()); put("aggregateVersion", draft.aggregateVersion)
        put("occurredAt", occurredAt.toString()); put("producer", draft.producer)
        put("correlationId", draft.correlationId); put("causationId", draft.causationId.toString())
        put("data", draft.data)
    }
    override fun toString() = "CommittedEvent(type=${draft.eventType}, data=[redacted])"
}

class OutboxLease(val event: CommittedEvent, val token: UUID, val attempt: Int) {
    override fun toString() = "OutboxLease(type=${event.draft.eventType}, token=[redacted], attempt=$attempt)"
}

enum class DeliveryFailure { DELIVERY_FAILED, UNSUPPORTED_EVENT, PERMANENT_FAILURE }

/** Durable leasing and fencing. A successful publisher call followed by a crash can be delivered again. */
class OutboxStore(private val transactions: PgTransactions) {
    fun append(connection: Connection, event: EventDraft) {
        require(!connection.autoCommit) { "Append an event inside the domain transaction" }
        event.requireAppendOwnership()
        connection.prepareStatement("""
            INSERT INTO platform.outbox(event_id,event_type,schema_version,aggregate_type,aggregate_id,
                aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id)
            VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)
        """.trimIndent()).use { statement ->
            statement.setObject(1, event.eventId); statement.setString(2, event.eventType)
            statement.setInt(3, event.schemaVersion); statement.setString(4, event.aggregateType)
            statement.setObject(5, event.aggregateId); statement.setLong(6, event.aggregateVersion)
            statement.setString(7, event.producer); statement.setString(8, event.correlationId)
            statement.setObject(9, event.causationId); statement.setString(10, event.data.toString())
            statement.setString(11, event.owner?.environment); statement.setString(12, event.owner?.kind?.storageValue)
            statement.setObject(13, event.owner?.id)
            statement.executeUpdate()
        }
    }

    fun claim(batchSize: Int = 20, leaseSeconds: Int = 30): List<OutboxLease> {
        require(batchSize in 1..100 && leaseSeconds in 1..300)
        return transactions.run { connection ->
            // Five crashed deliveries must also stop retrying; never steal an unexpired live lease.
            connection.createStatement().use { it.executeUpdate("""
                WITH exhausted AS (
                    SELECT event_id FROM platform.outbox
                    WHERE published_at IS NULL AND quarantined_at IS NULL AND attempts>=5
                        AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp())
                    ORDER BY available_at,occurred_at,event_id LIMIT 100 FOR UPDATE SKIP LOCKED
                )
                UPDATE platform.outbox o SET quarantined_at=clock_timestamp(),last_failure_code='ATTEMPTS_EXHAUSTED',
                    lease_token=NULL,lease_expires_at=NULL
                FROM exhausted e WHERE o.event_id=e.event_id
            """.trimIndent()) }
            val token = UUID.randomUUID()
            connection.prepareStatement("""
                WITH candidates AS (
                    SELECT event_id FROM platform.outbox
                    WHERE published_at IS NULL AND quarantined_at IS NULL AND attempts<5
                        AND available_at<=clock_timestamp()
                        AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp())
                    ORDER BY available_at,occurred_at,event_id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE platform.outbox o SET lease_token=?, lease_expires_at=clock_timestamp()+(? * interval '1 second'),
                    attempts=o.attempts+1
                FROM candidates c WHERE o.event_id=c.event_id RETURNING o.*
            """.trimIndent()).use { statement ->
                statement.setInt(1, batchSize); statement.setObject(2, token); statement.setInt(3, leaseSeconds)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) {
                        try {
                            add(OutboxLease(readEvent(rows), token, rows.getInt("attempts")))
                        } catch (_: IllegalArgumentException) {
                            // Imported/older-writer poison must not roll back every healthy claim forever.
                            connection.prepareStatement("""
                                UPDATE platform.outbox SET quarantined_at=clock_timestamp(),last_failure_code='INVALID_ENVELOPE',
                                    lease_token=NULL,lease_expires_at=NULL WHERE event_id=? AND lease_token=?
                            """.trimIndent()).use { quarantine ->
                                quarantine.setObject(1,rows.getObject("event_id",UUID::class.java));quarantine.setObject(2,token)
                                quarantine.executeUpdate()
                            }
                        }
                    }
                } }
            }
        }
    }

    fun acknowledge(lease: OutboxLease): Boolean = transactions.run { connection ->
        connection.prepareStatement("""
            UPDATE platform.outbox SET published_at=clock_timestamp(),lease_token=NULL,lease_expires_at=NULL,last_failure_code=NULL
            WHERE event_id=? AND lease_token=? AND lease_expires_at>clock_timestamp()
                AND published_at IS NULL AND quarantined_at IS NULL
        """.trimIndent()).use { statement ->
            statement.setObject(1, lease.event.draft.eventId); statement.setObject(2, lease.token)
            statement.executeUpdate() == 1
        }
    }

    fun fail(lease: OutboxLease, reason: DeliveryFailure): Boolean {
        val permanent = reason != DeliveryFailure.DELIVERY_FAILED || lease.attempt >= 5
        val delay = minOf(300, 1 shl minOf(lease.attempt, 8)) + ThreadLocalRandom.current().nextInt(0, 4)
        return transactions.run { connection ->
            connection.prepareStatement("""
                UPDATE platform.outbox SET quarantined_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                    last_failure_code=?,available_at=clock_timestamp()+(? * interval '1 second'),
                    lease_token=NULL,lease_expires_at=NULL
                WHERE event_id=? AND lease_token=? AND lease_expires_at>clock_timestamp()
                    AND published_at IS NULL AND quarantined_at IS NULL
            """.trimIndent()).use { statement ->
                statement.setBoolean(1, permanent); statement.setString(2, reason.name); statement.setInt(3, delay)
                statement.setObject(4, lease.event.draft.eventId); statement.setObject(5, lease.token)
                statement.executeUpdate() == 1
            }
        }
    }

    private fun readEvent(row: ResultSet) = CommittedEvent(EventDraft(
        row.getObject("event_id", UUID::class.java), row.getString("event_type"), row.getInt("schema_version"),
        row.getString("aggregate_type"), row.getObject("aggregate_id", UUID::class.java), row.getLong("aggregate_version"),
        row.getString("producer"), row.getString("correlation_id"), row.getObject("causation_id", UUID::class.java),
        Json.parseToJsonElement(row.getString("payload")).jsonObject,
        owner = readOwner(row),
    ), row.getObject("occurred_at", OffsetDateTime::class.java).toInstant())

    private fun readOwner(row: ResultSet): EventOwner? {
        val environment = row.getString("owner_environment")
        val kind = row.getString("owner_kind")
        val id = row.getObject("owner_id", UUID::class.java)
        if (environment == null && kind == null && id == null) return null
        require(environment != null && kind != null && id != null) { "Incomplete event ownership" }
        return EventOwner(environment, EventOwnerKind.parse(kind), id)
    }
}

/**
 * No queue/provider is configured. Claim close to each send, not a batch that can expire while waiting.
 * Publisher adapters must bound their HTTP timeout below the lease and never run arbitrary long jobs here.
 * A timeout/expiry during a send can still duplicate delivery; consumer dedupe remains mandatory.
 */
class OutboxRelay(private val store: OutboxStore, private val supported: Set<Pair<String, Int>>) {
    fun deliverBatch(publish: (CommittedEvent) -> Unit): Int {
        var acknowledged = 0
        for (index in 1..20) {
            val lease = store.claim(batchSize=1).singleOrNull() ?: break
            if ((lease.event.draft.eventType to lease.event.draft.schemaVersion) !in supported) {
                store.fail(lease, DeliveryFailure.UNSUPPORTED_EVENT)
                continue
            }
            try {
                publish(lease.event)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted // Lease expires naturally; acceptance may be unknown.
            } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                store.fail(lease, DeliveryFailure.DELIVERY_FAILED)
                continue
            }
            if (store.acknowledge(lease)) acknowledged++
        }
        return acknowledged
    }
}

/** Inbox marker and DB-only consumer effect share one commit; external delivery needs its own intent ledger. */
class ConsumerInbox(private val transactions: PgTransactions) {
    fun consume(consumer: String, event: CommittedEvent, apply: (Connection, CommittedEvent) -> Unit): Boolean {
        require(consumer.matches(Regex("[a-z][a-z0-9_.-]{0,99}")))
        return transactions.run { connection ->
            val first = connection.prepareStatement("""
                INSERT INTO platform.consumer_inbox(consumer_name,event_id) VALUES (?,?) ON CONFLICT DO NOTHING
            """.trimIndent()).use { statement ->
                statement.setString(1, consumer); statement.setObject(2, event.draft.eventId)
                statement.executeUpdate() == 1
            }
            if (first) apply(connection, event)
            first
        }
    }
}
