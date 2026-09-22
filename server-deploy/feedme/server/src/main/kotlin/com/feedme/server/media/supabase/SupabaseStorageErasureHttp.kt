package com.feedme.server.media.supabase

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Worker-only exact target, NOT authorization. The caller must first commit a durable intent
 * bound to the accepted account and the actual media row. The random path component is not
 * an owner id; only the DB can establish ownership. No prefix/bucket/derivative sweep exists. */
internal class SupabaseStorageErasureTarget(val environment: String, val bucket: String,
    val ownerId: UUID, val mediaId: UUID, val objectKey: String) {
    init {
        val parts = objectKey.split('/')
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) &&
            bucket.matches(Regex("[a-z0-9][a-z0-9_-]{0,62}")) && parts.size == 4 &&
            parts[0] == "quarantine" && parts[1] == environment && canonicalStorageUuid(parts[2]) &&
            parts[3] == mediaId.toString()) { "Invalid exact storage target" }
    }
    override fun toString() = "SupabaseStorageErasureTarget(<redacted>)"
}

/** A provider response is not late-write settlement, all-version erasure, backup removal or
 * account deletion completion. Missing metadata does not establish physical absence. */
internal enum class SupabaseStorageEraseResult {
    NOT_CONFIGURED, INVALID_TARGET, ENTRY_ACKNOWLEDGED, OUTCOME_UNKNOWN,
}
internal enum class SupabaseStoragePresence {
    NOT_CONFIGURED, INVALID_TARGET, PRESENT, NOT_FOUND_REPORTED, OUTCOME_UNKNOWN,
}

/** Explicit worker injection only; no environment reader or serving-route activation.
 * Each method is ONE HTTP attempt, with no redirects, retries, refresh, cookies or proxy.
 * Current upstream REST contract: DELETE /object/{bucket}/{key} deletes one exact object;
 * GET /object/info/authenticated/{bucket}/{key} observes database metadata, not stored bytes.
 * The managed deployment contract still needs acceptance before enabling this adapter.
 */
internal class SupabaseStorageErasureHttp private constructor(
    private val configuration: SupabaseStorageHttpConfiguration?, private val clock: Clock,
    private val wireOrigin: String?, private val client: OkHttpClient?,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val admission = Semaphore(1)

    /** Read-only configuration admission, not DB ownership or a provider-health claim.
     * A concurrent close after a committed dispatch still requires reconciliation. */
    val configured: Boolean get() = configuration != null && !closed.get()
    internal val configuredProjectOrigin: String? get() = configuration?.projectOrigin?.takeIf { configured }
    fun acceptsTarget(target: SupabaseStorageErasureTarget): Boolean =
        configuration?.let { configured && matches(target, it) } == true

    fun eraseExact(target: SupabaseStorageErasureTarget): SupabaseStorageEraseResult {
        currentThread()
        val config = configuration ?: return SupabaseStorageEraseResult.NOT_CONFIGURED
        if (closed.get()) return SupabaseStorageEraseResult.NOT_CONFIGURED
        if (!matches(target, config)) return SupabaseStorageEraseResult.INVALID_TARGET
        return guarded(SupabaseStorageEraseResult.OUTCOME_UNKNOWN) {
            val request = authenticated("/object/${config.bucket}/${target.objectKey}").delete().build()
            checkNotNull(client).newCall(request).execute().use { response ->
                val payload = responseJson(response) as? JsonObject ?: invalid()
                demand(response.code == 200 && payload.keys == setOf("message") &&
                    text(payload, "message") == "Successfully deleted")
                // Acknowledges this invocation only. An already admitted upload can recreate the key.
                SupabaseStorageEraseResult.ENTRY_ACKNOWLEDGED
            }
        }
    }

    fun inspectExact(target: SupabaseStorageErasureTarget): SupabaseStoragePresence {
        currentThread()
        val config = configuration ?: return SupabaseStoragePresence.NOT_CONFIGURED
        if (closed.get()) return SupabaseStoragePresence.NOT_CONFIGURED
        if (!matches(target, config)) return SupabaseStoragePresence.INVALID_TARGET
        return guarded(SupabaseStoragePresence.OUTCOME_UNKNOWN) {
            val request = authenticated("/object/info/authenticated/${config.bucket}/${target.objectKey}").get().build()
            checkNotNull(client).newCall(request).execute().use { response ->
                val payload = responseJson(response) as? JsonObject ?: invalid()
                if (response.code == 200) {
                    demand(payload.keys.all { it in INFO_FIELDS } && text(payload, "name") == target.objectKey &&
                        text(payload, "bucket_id") == config.bucket && canonicalStorageUuid(text(payload, "id")))
                    val version = text(payload, "version")
                    demand(version.length in 1..256 && version.isNotBlank() && version.none(Char::isISOControl))
                    // Version/id are representation checks only; not a complete immutable inventory.
                    SupabaseStoragePresence.PRESENT
                } else {
                    demand(response.code in setOf(400, 404) && payload.keys == ERROR_FIELDS &&
                        text(payload, "statusCode") == "404" && text(payload, "code") == "NoSuchKey" &&
                        text(payload, "error").length <= 256 && text(payload, "message").length <= 4096)
                    SupabaseStoragePresence.NOT_FOUND_REPORTED
                }
            }
        }
    }

    private fun authenticated(path: String) = Request.Builder()
        .url(checkNotNull(wireOrigin) + "/storage/v1" + path)
        .header("Accept", "application/json").header("Accept-Encoding", "identity")
        .header("Cache-Control", "no-cache, no-store")
        .apply {
            checkNotNull(configuration).apiKey.use { header("apikey", it) }
            configuration.bearer?.use { header("Authorization", "Bearer $it") }
        }

    private fun responseJson(response: Response): JsonElement {
        current()
        demand(response.headers.size <= 64 && response.headers.sumOf { it.first.length + it.second.length } <= 16_384)
        fun header(name: String): String? {
            val values = response.headers.values(name)
            demand(values.size <= 1)
            return values.singleOrNull()?.also { demand(it.length in 1..1024 && it.none(Char::isISOControl)) }
        }
        demand(header("Content-Type")?.matches(JSON_MEDIA) == true &&
            header("Content-Encoding")?.lowercase() in setOf(null, "identity") && header("Content-Range") == null)
        val length = header("Content-Length")?.let {
            demand(it.matches(Regex("0|[1-9][0-9]{0,18}")))
            it.toLong().also { size -> demand(size in 2..MAX_RESPONSE_BYTES.toLong()) }
        }
        val buffer = ByteArray(MAX_RESPONSE_BYTES + 1)
        try {
            var count = 0
            response.body.byteStream().use { input ->
                while (true) {
                    current()
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    demand(read > 0); count += read; demand(count <= MAX_RESPONSE_BYTES)
                }
            }
            demand(count >= 2 && (length == null || length == count.toLong()))
            val bytes = buffer.copyOf(count)
            return try {
                val normalized = WireDocument.decode(bytes, WireLimits(MAX_RESPONSE_BYTES, 8, 20)).encodeUtf8()
                try { Json.parseToJsonElement(normalized.decodeToString(throwOnInvalidSequence = true)) }
                finally { normalized.fill(0) }
            } finally { bytes.fill(0) }
        } finally { buffer.fill(0) }
    }

    private fun <T> guarded(unknown: T, action: () -> T): T {
        if (!admission.tryAcquire()) return unknown
        return try {
            current()
            val started = System.nanoTime(); val at = clock.instant()
            val value = action()
            current()
            demand(clock.instant() >= at && System.nanoTime() - started <=
                TimeUnit.MILLISECONDS.toNanos(checkNotNull(configuration).callTimeoutMillis))
            value
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
          catch (_: Exception) { currentThread(); unknown }
        finally { admission.release() }
    }
    private fun current() { currentThread(); if (closed.get()) invalid() }
    private fun currentThread() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Storage erasure interrupted")
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            client?.dispatcher?.cancelAll(); client?.connectionPool?.evictAll(); client?.dispatcher?.executorService?.shutdown()
        }
    }
    override fun toString() = "SupabaseStorageErasureHttp(<redacted>)"

    companion object {
        internal const val MAX_RESPONSE_BYTES = 65_536
        fun create(configuration: SupabaseStorageHttpConfiguration? = null, clock: Clock = Clock.systemUTC()) =
            SupabaseStorageErasureHttp(configuration, clock, configuration?.projectOrigin, configuration?.let(::ownedClient))

        /** Internal synthetic endpoint only; production always uses the validated HTTPS origin. */
        internal fun loopback(configuration: SupabaseStorageHttpConfiguration, clock: Clock, port: Int): SupabaseStorageErasureHttp {
            require(port in 1..65535)
            return SupabaseStorageErasureHttp(configuration, clock, "http://127.0.0.1:$port", ownedClient(configuration))
        }
        private fun ownedClient(configuration: SupabaseStorageHttpConfiguration) = OkHttpClient.Builder()
            .connectTimeout(configuration.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(configuration.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(configuration.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(configuration.callTimeoutMillis, TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).fastFallback(false)
            .proxy(Proxy.NO_PROXY).socketFactory(ErasureDirectSocketFactory(configuration.connectTimeoutMillis.toInt()))
            .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .eventListener(EventListener.NONE).connectionPool(ConnectionPool())
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().tag(ErasureAttempt::class.java, ErasureAttempt()).build()) }
            .addNetworkInterceptor { chain ->
                val attempt = chain.request().tag(ErasureAttempt::class.java) ?: throw IOException("Storage erasure attempt unavailable")
                if (!attempt.started.compareAndSet(false, true)) throw IOException("Storage erasure follow-up refused")
                chain.proceed(chain.request())
            }.build()
        private fun matches(target: SupabaseStorageErasureTarget, config: SupabaseStorageHttpConfiguration) =
            target.environment == config.environment && target.bucket == config.bucket
        private val JSON_MEDIA = Regex("application/json(?: *; *charset *= *(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
        private val INFO_FIELDS = setOf("id", "name", "version", "bucket_id", "size", "content_type", "cache_control", "etag",
            "metadata", "last_modified", "created_at", "archived_at", "is_delete_marker", "is_versioned")
        private val ERROR_FIELDS = setOf("statusCode", "code", "error", "message")
        private fun text(value: JsonObject, name: String): String =
            (value[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
        private fun demand(value: Boolean) { if (!value) invalid() }
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid storage erasure response")
    }
}

private fun canonicalStorageUuid(value: String): Boolean = try { value.length == 36 && UUID.fromString(value).toString() == value }
    catch (_: IllegalArgumentException) { false }
private class ErasureAttempt { val started = AtomicBoolean() }
private class ErasureDirectSocketFactory(private val timeoutMillis: Int) : SocketFactory() {
    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)
    override fun createSocket(host: String, port: Int): Socket = connected(InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = connected(InetSocketAddress(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        connected(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))
    private fun connected(remote: InetSocketAddress, local: InetSocketAddress? = null): Socket {
        val socket = createSocket()
        try { if (local != null) socket.bind(local); socket.connect(remote, timeoutMillis); return socket }
        catch (failure: Throwable) { socket.close(); throw failure }
    }
}
