package com.feedme.server.media.access

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.db.PgTransactions
import com.feedme.server.db.StoredReply
import com.feedme.server.media.processing.MediaProcessingFailure
import com.feedme.server.media.processing.MediaProcessingFailureCode
import com.feedme.server.media.supabase.SupabaseDerivativeHttp
import com.feedme.server.social.posts.AccountPostReadStore
import com.feedme.server.social.posts.PostReadFailure
import com.feedme.server.social.posts.PostReadFailureCode
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Same-instance ephemeral byte delivery. Every issuance authorizes the actual viewer/post,
 * releases DB locks for the private full-byte read, then reauthorizes the exact observation.
 * No provider key URL escapes. The bearer URL lasts at most 60s, with current post
 * revocation checked again on every delivery admission. Already admitted streams or
 * downloaded copies cannot be recalled. Another instance/restart returns
 * unavailable, never a fallback public object. No network call is inside a DB transaction.
 * The supplied derivative client is owned and closed by this store. */
class AccountMediaAccessStore(val environment: String, private val transactions: PgTransactions,
    private val posts: AccountPostReadStore, private val objects: SupabaseDerivativeHttp,
    val policy: AccountMediaAccessPolicy, private val providerReviewValidUntil: Instant,
    private val clock: Clock = Clock.systemUTC()) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val admissions = Semaphore(policy.maxConcurrentFetches)
    private val random = SecureRandom()
    private val mutex = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val deliveries = mutableSetOf<MediaByteDelivery>()
    private var reservedBytes = 0L
    private var reservations = 0
    private val reaper = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "feedme-media-expiry").apply { isDaemon = true }
    }
    init {
        require(environment == posts.environment)
        reaper.scheduleWithFixedDelay({ synchronized(mutex) { expireLocked() } }, 1, 1, TimeUnit.SECONDS)
    }

    fun getMediaAccess(subject: VerifiedSupabaseSubject, device: UUID, mediaId: UUID,
        postId: UUID, surface: String, variant: String): StoredReply = safe {
        current()
        if (!policy.enabled) fail(MediaAccessFailureCode.NOT_CONFIGURED)
        if (surface !in setOf("today", "plate", "detail") || variant !in setOf("display", "thumbnail"))
            fail(MediaAccessFailureCode.INPUT_INVALID)
        if (!admissions.tryAcquire()) fail(MediaAccessFailureCode.CAPACITY_EXCEEDED)
        var reserved = 0L
        var bytes: ByteArray? = null
        try {
            val first = transactions.run { posts.requireMediaAccess(it, subject, device, postId, mediaId, surface, variant) }
            if (first.output.bucket != objects.bucket || first.output.bytes !in 1..policy.maxObjectBytes.toLong() ||
                first.output.contentType != "image/png") fail(MediaAccessFailureCode.NOT_CONFIGURED)
            synchronized(mutex) {
                current(); expireLocked()
                if (entries.size + reservations >= policy.maxItems ||
                    usedBytes() + first.output.bytes > policy.maxRetainedBytes) fail(MediaAccessFailureCode.CAPACITY_EXCEEDED)
                reserved = first.output.bytes; reservedBytes += reserved; reservations++
            }
            val material = objects.readVerified(first.output) ?: fail(MediaAccessFailureCode.MEDIA_UNAVAILABLE)
            bytes = material
            current()
            // Independently bind the retained buffer; an adapter acknowledgement is not bytes.
            if (material.size.toLong() != first.output.bytes || sha(material) != first.output.sha256)
                fail(MediaAccessFailureCode.MEDIA_UNAVAILABLE)
            val authorizationStarted = System.nanoTime()
            val last = transactions.run { posts.requireMediaAccess(it, subject, device, postId, mediaId, surface, variant) }
            if (!first.sameBinding(last) || last.observedAt < first.observedAt) fail(MediaAccessFailureCode.MEDIA_UNAVAILABLE)
            val expires = minOf(last.validUntil, providerReviewValidUntil,
                last.observedAt.plusSeconds(policy.lifetimeSeconds.toLong()))
            val duration = Duration.between(last.observedAt, expires)
            if (duration.isNegative || duration.isZero) fail(MediaAccessFailureCode.MEDIA_UNAVAILABLE)
            // Beginning the monotonic budget BEFORE final authorization is conservative
            // across transaction waits/commit; wall-clock rollback cannot extend this URL.
            val deadline = authorizationStarted + duration.toNanos()
            synchronized(mutex) {
                current(); expireLocked()
                if (!live(expires, deadline)) fail(MediaAccessFailureCode.MEDIA_UNAVAILABLE)
                val entropy = ByteArray(32).also(random::nextBytes)
                val token = try { Base64.getUrlEncoder().withoutPadding().encodeToString(entropy) }
                    finally { entropy.fill(0) }
                if (entries.containsKey(token)) fail(MediaAccessFailureCode.STORAGE_UNAVAILABLE)
                entries[token] = Entry(material, expires, deadline, last.ownerId, last.postId, last.postVersion)
                reservedBytes -= reserved; reservations--; reserved = 0
                bytes = null // Ownership moves only after both exact DB observations commit.
                StoredReply(200, buildJsonObject {
                    put("url", policy.deliveryOrigin + DELIVERY_PREFIX + token)
                    put("expiresAt", expires.toString()); put("aclVersion", last.aclVersion); put("contentType", "image/png")
                }, null)
            }
        } finally {
            bytes?.fill(0)
            if (reserved != 0L) synchronized(mutex) { reservedBytes -= reserved; reservations-- }
            admissions.release()
        }
    }

    /** No JWT/cookie is accepted or retained. The bearer grants only its original short
     * lease; a fresh database revocation check can narrow it, never extend it. Database
     * work runs outside the cache mutex. Copies share the existing hard byte budget. */
    internal fun openDelivery(capability: String): MediaByteDelivery = safe {
        val entry = synchronized(mutex) {
            current(); expireLocked()
            if (!policy.enabled || !CAPABILITY.matches(capability)) fail(MediaAccessFailureCode.MEDIA_UNAVAILABLE)
            entries[capability] ?: fail(MediaAccessFailureCode.MEDIA_UNAVAILABLE)
        }
        if (!admissions.tryAcquire()) fail(MediaAccessFailureCode.CAPACITY_EXCEEDED)
        try {
            transactions.run { c ->
                PostMediaDeliveryAuthority.requireCurrent(c, environment, entry.ownerId, entry.postId, entry.postVersion)
            }
            synchronized(mutex) {
                current(); expireLocked()
                if (entries[capability] !== entry || !live(entry.expires, entry.deadline)) fail(MediaAccessFailureCode.MEDIA_UNAVAILABLE)
                if (deliveries.size >= policy.maxConcurrentFetches || usedBytes() + entry.bytes.size > policy.maxRetainedBytes)
                    fail(MediaAccessFailureCode.CAPACITY_EXCEEDED)
                MediaByteDelivery(entry.bytes.copyOf(), entry.expires, entry.deadline,
                    current = { expires, deadline -> !closed.get() && live(expires, deadline) },
                    released = { delivery -> synchronized(mutex) { deliveries.remove(delivery) } }).also(deliveries::add)
            }
        } finally { admissions.release() }
    }

    private class Entry(val bytes: ByteArray, val expires: Instant, val deadline: Long,
        val ownerId: UUID, val postId: UUID, val postVersion: Long)
    private fun usedBytes(): Long = reservedBytes + entries.values.sumOf { it.bytes.size.toLong() } +
        deliveries.sumOf { it.byteCount.toLong() }
    private fun live(expires: Instant, deadline: Long): Boolean = clock.instant() < expires && System.nanoTime() - deadline < 0
    private fun expireLocked() {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (closed.get() || !live(entry.expires, entry.deadline)) { entry.bytes.fill(0); iterator.remove() }
        }
        deliveries.toList().filter { !it.isCurrent() }.forEach(MediaByteDelivery::close)
    }
    private fun current() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Media access interrupted")
        if (closed.get()) fail(MediaAccessFailureCode.NOT_CONFIGURED)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            reaper.shutdownNow()
            synchronized(mutex) { expireLocked() }
            objects.close()
        }
    }
    override fun toString() = "AccountMediaAccessStore(<redacted>)"
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (failure: MediaAccessFailure) { throw failure }
        catch (failure: PostReadFailure) { throw MediaAccessFailure(when (failure.code) {
            PostReadFailureCode.UNAUTHENTICATED -> MediaAccessFailureCode.UNAUTHENTICATED
            PostReadFailureCode.INPUT_INVALID -> MediaAccessFailureCode.INPUT_INVALID
            PostReadFailureCode.POST_UNAVAILABLE -> MediaAccessFailureCode.MEDIA_UNAVAILABLE
            PostReadFailureCode.NOT_CONFIGURED -> MediaAccessFailureCode.NOT_CONFIGURED
            else -> MediaAccessFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: MediaProcessingFailure) { throw MediaAccessFailure(when (failure.code) {
            MediaProcessingFailureCode.NOT_CONFIGURED -> MediaAccessFailureCode.NOT_CONFIGURED
            MediaProcessingFailureCode.STORAGE_UNAVAILABLE -> MediaAccessFailureCode.STORAGE_UNAVAILABLE
            else -> MediaAccessFailureCode.MEDIA_UNAVAILABLE
        }) }
        catch (_: Exception) { current(); fail(MediaAccessFailureCode.STORAGE_UNAVAILABLE) }
    companion object {
        const val DELIVERY_PREFIX = "/v1/media-delivery/"
        internal val CAPABILITY = Regex("[A-Za-z0-9_-]{43}")
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun fail(code: MediaAccessFailureCode): Nothing = throw MediaAccessFailure(code)
    }
}

internal class MediaByteDelivery internal constructor(private val bytes: ByteArray, val expiresAt: Instant,
    private val deadline: Long, private val current: (Instant, Long) -> Boolean,
    private val released: (MediaByteDelivery) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    val byteCount: Int = bytes.size
    fun isCurrent(): Boolean = !closed.get() && current(expiresAt, deadline)
    fun remainingMillis(): Long = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
    /** A bounded immutable-in-flight chunk avoids racing expiry/close zeroization with an
     * engine write. The sender zeroes this <=16KiB copy only after write completion. */
    fun copyChunk(offset: Int, count: Int): ByteArray? = synchronized(this) {
        if (!isCurrent()) return@synchronized null
        require(offset >= 0 && count in 1..16384 && offset + count <= byteCount)
        bytes.copyOfRange(offset, offset + count)
    }
    override fun close() {
        val changed = synchronized(this) {
            if (!closed.compareAndSet(false, true)) false else { bytes.fill(0); true }
        }
        if (changed) released(this)
    }
    override fun toString() = "MediaByteDelivery(<redacted>)"
}
