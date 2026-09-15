package com.feedme.server.media.processing

import com.feedme.server.db.*
import com.feedme.server.media.*
import com.feedme.server.media.processing.codec.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.sql.DataSource
import kotlinx.serialization.json.*

/** TEST ONLY: actual PostgreSQL and optional real child JPEG/PNG codec. Identity, private object
 * storage and malware/moderation adapters are explicitly synthetic, never production defaults. */
class MediaProcessingTestFixture(val database: DataSource, val policy: MediaProcessingPolicy = policy(),
    val media: MediaTestFixture = MediaTestFixture(database)) {
    val authority = TestAuthority()
    val objects = TestObjects()
    val codec = TestCodec()
    val safety = TestSafety()
    val store = MediaProcessingStore("test", PgTransactions(media.faults.wrap(database)), authority, policy)
    val processor = MediaProcessor(store, codec, objects, safety)
    val cleanup = MediaObjectCleanup("test", PgTransactions(media.faults.wrap(database)), authority, objects, policy)
    lateinit var prepared: JsonObject
    lateinit var completionBody: JsonObject
    lateinit var completion: StoredReply
    lateinit var completionKey: UUID
    lateinit var input: ByteArray
    val mediaId get() = UUID.fromString(prepared.getValue("id").jsonPrimitive.content)
    fun seed(bytes: ByteArray = ByteArray(128) { it.toByte() }, contentType: String = "image/png", ttl: Int = 3600,
        clientDraftId: UUID = media.draft()): CommittedEvent {
        input = bytes.copyOf()
        val body = JsonObject(media.prepareBody(clientDraftId=clientDraftId) + mapOf("bytes" to JsonPrimitive(bytes.size), "sha256" to JsonPrimitive(hash(bytes)), "contentType" to JsonPrimitive(contentType)))
        val selected = media.newStore(media.policy(reservationLifetimeSeconds = ttl, capabilityLifetimeSeconds = minOf(ttl, 60)))
        prepared = media.prepare(body = body, selected = selected)
        val upload = media.upload(prepared)
        completionBody = JsonObject(upload + ("sha256" to JsonPrimitive(hash(bytes))))
        completionKey = UUID.randomUUID()
        completion = MediaTestFixture.reply(selected.completeMediaUpload(media.account, completionKey, mediaId, completionBody))
        val key = media.value("SELECT quarantine_key FROM platform.media_assets WHERE id='$mediaId'")
        objects.data[key to "object-version-1"] = bytes.copyOf()
        return event()
    }
    fun event(): CommittedEvent = database.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery("SELECT * FROM platform.outbox WHERE aggregate_id='$mediaId' AND event_type='platform.media.upload_completed.v1'").use { r ->
            check(r.next()); CommittedEvent(EventDraft(r.getObject("event_id", UUID::class.java), r.getString("event_type"), r.getInt("schema_version"),
                r.getString("aggregate_type"), r.getObject("aggregate_id", UUID::class.java), r.getLong("aggregate_version"), r.getString("producer"),
                r.getString("correlation_id"), r.getObject("causation_id", UUID::class.java), Json.parseToJsonElement(r.getString("payload")).jsonObject),
                r.getObject("occurred_at", OffsetDateTime::class.java).toInstant())
        }
    } }
    fun claimed(): MediaProcessingLease { store.ingest(seed()); return checkNotNull(store.claim()) }
    fun delete(): CommandResult {
        val version = media.value("SELECT version FROM platform.media_assets WHERE id='$mediaId'")
        return media.store.deleteDraftMedia(media.account, UUID.randomUUID(), mediaId, "\"$version\"")
    }
    fun sql(sql: String) = media.sql(sql)
    fun value(sql: String) = media.value(sql)
    fun count(table: String) = media.count(table)
    fun expireLease(id: UUID) = sql("UPDATE platform.media_processing_jobs SET lease_expires_at=clock_timestamp()-interval '1 second',available_at=clock_timestamp() WHERE id='$id'")
    fun eligible() = sql("UPDATE platform.media_processing_jobs SET available_at=clock_timestamp() WHERE state='retry'")
    fun realCodec() = PhotoDecodeProcess(Path.of(System.getProperty("java.home"), "bin", "java"),
        listOf(PhotoDecodeProcess::class.java, PhotoCodec::class.java, kotlin.Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator),
        PhotoProcessingPolicy("codec-test-1", 10000, 100, 100, 10000, 16_000_000, 100, 5000, 64, 8, 32, 10000, 20000, "image/png"),
        PhotoProcessPolicy(64, 1, 10_000, 1000))
    inner class TestAuthority : MediaProcessingAuthority {
        var enabled = true; var approved = true
        var beforeProcessing: ((Connection) -> Unit)? = null
        var beforeSafety: ((Connection) -> Unit)? = null
        override fun lockPrincipal(connection: Connection, owner: MediaProcessingOwner, purpose: MediaWorkerPurpose) {
            check(owner.environment == "test")
            connection.prepareStatement("SELECT active,expires_at>clock_timestamp() FROM cooking_test.principals WHERE kind='account' AND id=? FOR UPDATE").use {
                it.setObject(1, owner.ownerId); it.executeQuery().use { r ->
                    if (!r.next() || (purpose == MediaWorkerPurpose.PROCESS && (!r.getBoolean(1) || !r.getBoolean(2)))) throw MediaProcessingFailure(MediaProcessingFailureCode.CONFLICT)
                }
            }
        }
        override fun lockDraft(connection: Connection, owner: MediaProcessingOwner, draftId: UUID, generation: Long, purpose: MediaWorkerPurpose) {
            connection.prepareStatement("SELECT generation,eligible FROM media_test.drafts WHERE owner_id=? AND id=? FOR UPDATE").use {
                it.setObject(1, owner.ownerId); it.setObject(2, draftId); it.executeQuery().use { r ->
                    if (!r.next() || (purpose == MediaWorkerPurpose.PROCESS && (r.getLong(1) != generation || !r.getBoolean(2)))) throw MediaProcessingFailure(MediaProcessingFailureCode.CONFLICT)
                }
            }
        }
        override fun requireProcessing(connection: Connection, source: MediaProcessingSource, policyRevision: String) {
            lockPrincipal(connection, source.owner, MediaWorkerPurpose.PROCESS)
            lockDraft(connection, source.owner, source.draftId, source.draftGeneration, MediaWorkerPurpose.PROCESS)
            if (!enabled || policyRevision != policy.revision) throw MediaProcessingFailure(MediaProcessingFailureCode.NOT_CONFIGURED)
            beforeProcessing?.invoke(connection)
        }
        override fun requireSafety(connection: Connection, source: MediaProcessingSource, evidence: MediaSafetyEvidence, policyRevision: String, now: Instant) {
            beforeSafety?.invoke(connection)
            if (!approved || evidence.receiptId != "synthetic-safety-receipt" || evidence.revision != "synthetic-both-scans-v1" || policyRevision != policy.revision)
                throw MediaProcessingFailure(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
        }
    }
    class TestCodec : PhotoCodec {
        override val revision = "codec-test-1"
        var result: PhotoDecodeResult = PhotoDecodeResult.Decoded(listOf(
            EncodedPhotoVariant(PhotoVariant.THUMBNAIL, "image/png", 1, 1, byteArrayOf(1, 2)),
            EncodedPhotoVariant(PhotoVariant.DISPLAY, "image/png", 2, 2, byteArrayOf(3, 4))))
        var calls = 0
        override fun decode(source: ByteArray, contentType: String): PhotoDecodeResult { calls++; return result }
    }
    class TestSafety : MediaSafetyAssessment {
        var result: MediaSafetyResult? = null
        var before: (() -> Unit)? = null
        var calls = 0
        override fun assess(source: MediaProcessingSource, derivatives: List<EncodedPhotoVariant>): MediaSafetyResult {
            calls++; before?.invoke(); return result ?: MediaSafetyResult.Approved(proof(source, derivatives))
        }
        fun proof(source: MediaProcessingSource, variants: List<EncodedPhotoVariant>) = MediaSafetyEvidence("synthetic-safety-receipt", "synthetic-both-scans-v1",
            source.sha256, variants.associate { it.variant to hash(it.copyBytes()) }, Instant.now().minusSeconds(1), Instant.now().plusSeconds(300))
    }
    class TestObjects : MediaProcessingObjects {
        val data = ConcurrentHashMap<Pair<String, String>, ByteArray>()
        var reads = 0; var writes = 0; var inspections = 0; var deletes = 0; var settlements = 0
        var beforeRead: (() -> Unit)? = null; var afterWrite: (() -> Unit)? = null; var beforeDelete: (() -> Unit)? = null
        var loseWrite = false; var unavailableSettle = false
        override fun read(source: MediaProcessingSource): ByteArrayInputStream {
            reads++; beforeRead?.invoke()
            return ByteArrayInputStream(checkNotNull(data[source.objectKey to source.objectVersionId]).copyOf())
        }
        override fun inspect(intent: MediaDerivativeIntent): MediaDerivativeReceipt? {
            inspections++; val matches = data.entries.filter { it.key.first == intent.objectKey }
            if (matches.isEmpty()) return null
            check(matches.size == 1)
            val (key, bytes) = matches.single()
            return MediaDerivativeReceipt(key.first, key.second, hash(bytes), bytes.size.toLong(), intent.contentType)
        }
        override fun create(intent: MediaDerivativeIntent, bytes: ByteArray): MediaDerivativeReceipt {
            check(Instant.now() < intent.acceptanceDeadline); check(hash(bytes) == intent.sha256)
            writes++; val key = intent.objectKey to "version-${intent.id}"
            val previous = data.putIfAbsent(key, bytes.copyOf()); check(previous == null || previous.contentEquals(bytes))
            afterWrite?.invoke()
            if (loseWrite) { loseWrite = false; error("Synthetic lost immutable create reply") }
            return checkNotNull(inspect(intent))
        }
        override fun settle(cleanup: MediaCleanupLease): SettledMediaVersions {
            settlements++; if (unavailableSettle) error("Synthetic provider cannot prove final inventory")
            check(Instant.now() >= cleanup.notBefore)
            return SettledMediaVersions(cleanup.objectKey, data.keys.filter { it.first == cleanup.objectKey }.map { it.second })
        }
        override fun deleteVersion(cleanup: MediaCleanupLease, versionId: String) {
            beforeDelete?.invoke(); deletes++; data.remove(cleanup.objectKey to versionId)
        }
    }
    companion object {
        fun policy(lease: Int = 30, attempts: Int = 5, acceptance: Int = 10, manifest: Int = 65536) =
            MediaProcessingPolicy("processing-test-1", "codec-test-1", lease, attempts, 1, acceptance, 10000, 10000, 20000, 100, 512, 20, manifest)
        fun hash(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun image(format: String): ByteArray = ByteArrayOutputStream().also { output ->
            val image = BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 6) for (x in 0 until 8) image.setRGB(x, y, (x * 30 shl 16) or (y * 40 shl 8))
            check(ImageIO.write(image, format, output))
        }.toByteArray()
    }
}
