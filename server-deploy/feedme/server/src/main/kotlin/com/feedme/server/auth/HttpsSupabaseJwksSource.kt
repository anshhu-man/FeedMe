package com.feedme.server.auth

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Explicit per-source resource/cache policy. No unbounded caller queue or stale-on-error mode. */
class SupabaseJwksHttpPolicy(
    val connectTimeoutMillis: Long,
    val socketTimeoutMillis: Long,
    val totalTimeoutMillis: Long,
    val cacheSeconds: Long,
    val minimumFetchIntervalMillis: Long,
    val maximumAdmittedCalls: Int,
) {
    init {
        require(connectTimeoutMillis in 100..10_000 && socketTimeoutMillis in 100..15_000 &&
            totalTimeoutMillis in maxOf(connectTimeoutMillis, socketTimeoutMillis)..30_000 &&
            cacheSeconds in 1..600 && minimumFetchIntervalMillis in 100..30_000 && maximumAdmittedCalls in 1..32) {
            "Invalid key source resource policy"
        }
    }
    override fun toString() = "SupabaseJwksHttpPolicy(<redacted>)"
}

/** One configured endpoint, one owned engine, one bounded cache entry. No token/subject is
 * accepted by this type. It is NOT installed by Main and establishes no account authorization.
 * A cache hit preserves original fetchedAt and bytes; failure never serves stale material.
 */
class HttpsSupabaseJwksSource private constructor(
    private val configuration: SupabaseUserAccessConfiguration,
    private val policy: SupabaseJwksHttpPolicy,
    private val clock: Clock,
    private val monotonicNanos: () -> Long,
    private val exchange: SupabaseJwksExchange,
) : SupabaseJwksSource, AutoCloseable {
    private val mutex = Mutex()
    private val admitted = Semaphore(policy.maximumAdmittedCalls)
    private val closed = AtomicBoolean()
    private val generation = AtomicLong()
    private var cached: Cached? = null
    private var lastWall: Instant? = null
    private var lastNanos: Long? = null
    private var lastAttemptNanos: Long? = null
    init { require(policy.cacheSeconds <= configuration.maximumJwksAgeSeconds) { "Key cache exceeds verification policy" } }

    override suspend fun load(exactEndpoint: URI): PortResult<SupabaseJwksSnapshot> {
        if (closed.get() || exactEndpoint.toASCIIString() != configuration.jwksEndpoint.toASCIIString()) return unavailable()
        if (!admitted.tryAcquire()) return unavailable()
        return try {
            withTimeoutOrNull(policy.totalTimeoutMillis) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (closed.get()) return@withLock unavailable()
                    val ticket = generation.get()
                    val start = observeTime()
                    cached?.takeIf { it.generation == ticket && current(it, start) }?.let {
                        return@withLock deliver(it.snapshot, ticket)
                    }
                    cached = null
                    val previous = lastAttemptNanos
                    if (previous != null && elapsedMillis(previous, start.nanos) < policy.minimumFetchIntervalMillis) return@withLock unavailable()
                    lastAttemptNanos = start.nanos
                    val response = when (val result = exchange.get(configuration.jwksEndpoint)) {
                        is PortResult.Value -> result.value
                        is PortResult.Failure -> return@withLock unavailable()
                    }
                    currentCoroutineContext().ensureActive()
                    val end = observeTime()
                    if (closed.get() || generation.get() != ticket) return@withLock unavailable()
                    val material = validate(response, start, end)
                    val snapshot = SupabaseJwksSnapshot(configuration.jwksEndpoint, material.fetchedAt, material.bytes)
                    if (material.cacheMillis > 0) cached = Cached(snapshot, ticket, end.nanos, material.cacheMillis)
                    deliver(snapshot, ticket)
                }
            } ?: unavailable()
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { unavailable() }
        finally { admitted.release() }
    }

    /** Explicit operational rotation purge; late in-flight reads cannot repopulate the cache.
     * Does not bypass minimum fetch interval or claim the provider's edge cache was purged. */
    fun invalidate() { generation.incrementAndGet() }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            generation.incrementAndGet()
            exchange.close()
        }
    }
    override fun toString() = "HttpsSupabaseJwksSource(<redacted>)"

    private suspend fun deliver(snapshot: SupabaseJwksSnapshot, ticket: Long): PortResult<SupabaseJwksSnapshot> {
        currentCoroutineContext().ensureActive()
        return if (!closed.get() && generation.get() == ticket) PortResult.Value(snapshot) else unavailable()
    }
    private class Time(val wall: Instant, val nanos: Long)
    private class Cached(val snapshot: SupabaseJwksSnapshot, val generation: Long, val startedNanos: Long, val lifetimeMillis: Long)
    private class Material(val bytes: ByteArray, val fetchedAt: Instant, val cacheMillis: Long)
    private fun observeTime(): Time {
        val wall = clock.instant(); val nanos = monotonicNanos()
        check(lastWall?.let { wall >= it } != false && lastNanos?.let { nanos - it >= 0 } != false)
        lastWall = wall; lastNanos = nanos
        return Time(wall, nanos)
    }
    private fun elapsedMillis(start: Long, end: Long): Long = (end - start).also { check(it >= 0) } / 1_000_000
    private fun current(value: Cached, time: Time): Boolean =
        elapsedMillis(value.startedNanos, time.nanos) < value.lifetimeMillis &&
            time.wall >= value.snapshot.fetchedAt && time.wall < value.snapshot.fetchedAt.plusSeconds(policy.cacheSeconds) &&
            time.wall < value.snapshot.fetchedAt.plusSeconds(configuration.maximumJwksAgeSeconds)

    private fun validate(response: SupabaseJwksHttpResponse, start: Time, end: Time): Material {
        check(response.endpoint.toASCIIString() == configuration.jwksEndpoint.toASCIIString() && response.status == 200)
        val headers = response.headers
        check(headers.size <= 64 && headers.entries.sumOf { it.key.length + it.value.sumOf(String::length) } <= 16_384)
        check(headers.keys.map { it.lowercase() }.distinct().size == headers.size)
        fun header(name: String): String? {
            val values = headers.entries.singleOrNull { it.key.equals(name, true) }?.value ?: return null
            check(values.size == 1)
            return values.single().also { check(it.length in 1..1024 && it.none(Char::isISOControl)) }
        }
        check(header("Content-Encoding")?.lowercase() in setOf(null, "identity"))
        check(header("Content-Type")?.matches(JSON_MEDIA) == true)
        val length = header("Content-Length")?.let { number(it).also { value -> check(value in 1..65_536) } }
        val bytes = response.bytes()
        check(bytes.size in 1..65_536 && (length == null || length == bytes.size.toLong()))
        val body = WireDocument.decode(bytes, WireLimits(65_536, 16, 20))
        val fields = Json.parseToJsonElement(body.encodeUtf8().decodeToString()) as? JsonObject ?: error("Invalid keys")
        val keys = fields["keys"] as? JsonArray ?: error("Invalid keys")
        check(fields.keys == setOf("keys") && keys.size in 1..8)
        // No key filtering, normalization, or algorithm promotion. The verifier checks each key.
        val age = header("Age")?.let(::number) ?: 0L
        check(age <= configuration.maximumJwksAgeSeconds)
        val date = header("Date")?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
        val delay = Duration.between(start.wall, end.wall)
        check(!delay.isNegative && delay.toMillis() <= policy.totalTimeoutMillis)
        val observedOrigin = minOf(end.wall.minusSeconds(age), date ?: end.wall)
        val fetchedAt = minOf(start.wall, observedOrigin.minus(delay))
        val currentAge = Duration.between(fetchedAt, end.wall).toMillis()
        check(currentAge >= 0 && currentAge < configuration.maximumJwksAgeSeconds * 1000)
        var ttl = policy.cacheSeconds
        var cacheable = true
        header("Cache-Control")?.let { text ->
            val directives = text.split(',').map { it.trim().lowercase() }
            check(directives.none(String::isEmpty))
            check(directives.map { it.substringBefore('=') }.distinct().size == directives.size)
            for (directive in directives) when (directive.substringBefore('=')) {
                "no-store", "no-cache" -> { check(!directive.contains('=')); cacheable = false }
                "max-age" -> ttl = minOf(ttl, number(directive.substringAfter('=', "").removeSurrounding("\"")))
                // Shared-cache directives never extend this private cache's explicit TTL.
                else -> Unit
            }
        }
        val remaining = if (cacheable) maxOf(0, ttl * 1000 - currentAge) else 0
        return Material(bytes, fetchedAt, remaining)
    }
    private fun number(value: String): Long { check(value.matches(Regex("0|[1-9][0-9]{0,18}"))); return value.toLong() }
    private fun unavailable() = PortResult.Failure(FailureReason.UNAVAILABLE)

    companion object {
        /** Caller owns this source and must close it. No ambient HttpClient can be injected here. */
        fun create(configuration: SupabaseUserAccessConfiguration, policy: SupabaseJwksHttpPolicy, clock: Clock): HttpsSupabaseJwksSource {
            require(policy.cacheSeconds <= configuration.maximumJwksAgeSeconds) { "Key cache exceeds verification policy" }
            return HttpsSupabaseJwksSource(configuration, policy, clock, System::nanoTime, KtorSupabaseJwksExchange.create(policy))
        }
        internal fun forExchange(configuration: SupabaseUserAccessConfiguration, policy: SupabaseJwksHttpPolicy,
            clock: Clock, monotonicNanos: () -> Long, exchange: SupabaseJwksExchange) =
            HttpsSupabaseJwksSource(configuration, policy, clock, monotonicNanos, exchange)
        private val JSON_MEDIA = Regex("application/(?:json|jwk-set\\+json)(?: *; *charset *= *(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
    }
}

internal interface SupabaseJwksExchange : AutoCloseable {
    suspend fun get(endpoint: URI): PortResult<SupabaseJwksHttpResponse>
}
internal class SupabaseJwksHttpResponse(val endpoint: URI, val status: Int, headers: Map<String, List<String>>, bytes: ByteArray) {
    private val retainedHeaders = headers.mapValues { (_, values) -> values.toList() }.toMap()
    val headers get() = retainedHeaders.mapValues { (_, values) -> values.toList() }.toMap()
    private val retained = bytes.copyOf()
    fun bytes() = retained.copyOf()
    override fun toString() = "SupabaseJwksHttpResponse(<redacted>)"
}
