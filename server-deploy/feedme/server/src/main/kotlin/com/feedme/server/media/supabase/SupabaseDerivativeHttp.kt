package com.feedme.server.media.supabase

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.media.SUPABASE_MEDIA_PROTOCOL
import com.feedme.server.media.processing.MediaDerivativeIntent
import com.feedme.server.media.processing.MediaProcessingFailure
import com.feedme.server.media.processing.MediaProcessingFailureCode
import com.feedme.server.media.processing.SupabaseDerivativeObjects
import com.feedme.server.media.processing.SupabaseDerivativeReceipt
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Explicit private-object I/O only. No environment lookup, public URL, object-version fiction,
 * READY/safety/delivery grant, deletion, or late-write settlement promise.
 *
 * The worker/store must persist a single create-attempt marker BEFORE calling create. A write
 * acknowledgement is not content evidence: only a separate complete inspect can produce a
 * digest-bound receipt. Even a canonical absence observation cannot prove a prior write will
 * never arrive, and must not authorize a second POST after a possible first attempt.
 */
class SupabaseDerivativeHttp private constructor(
    private val configuration: SupabaseStorageHttpConfiguration,
    private val clock: Clock,
    private val wireOrigin: String,
    private val client: OkHttpClient,
) : SupabaseDerivativeObjects, AutoCloseable {
    override val bucket: String get() = configuration.bucket
    private val closed = AtomicBoolean()
    private val admission = Semaphore(4)

    override fun create(intent: MediaDerivativeIntent, bytes: ByteArray): Unit = guarded {
        validate(intent)
        if (bytes.size.toLong() != intent.bytes) fail(MediaProcessingFailureCode.OBJECT_MISMATCH)
        val attempted = intent.writeAttemptedAt ?: fail(MediaProcessingFailureCode.NOT_CONFIGURED)
        if (intent.acknowledgedAt != null) fail(MediaProcessingFailureCode.NOT_CONFIGURED)
        val started = clock.instant()
        // PostgreSQL supplies the marker; the worker clock may lag within explicit policy.
        // This is a structural bound, not proof of the store's actual durable claim. Skew
        // never extends either the marker's or the local dispatch's acceptance deadline.
        if (attempted > started.plusSeconds(configuration.clockSkewSeconds) ||
            attempted >= intent.acceptanceDeadline || started >= intent.acceptanceDeadline)
            fail(MediaProcessingFailureCode.OBJECT_UNAVAILABLE)
        val owned = bytes.copyOf()
        try {
            verifyMaterial(intent, owned)
            current()
            val beforeDispatch = clock.instant()
            if (beforeDispatch < started || beforeDispatch >= intent.acceptanceDeadline) fail(MediaProcessingFailureCode.OBJECT_UNAVAILABLE)
            val request = authenticated("/object/$bucket/${intent.objectKey}")
                .header("Accept", "application/json").header("x-upsert", "false")
                .post(owned.toRequestBody("image/png".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                headers(response)
                if (response.code != 200 || !jsonMedia(single(response, "Content-Type"))) unavailable()
                val acknowledgement = body(response, MAX_JSON_BYTES)
                try {
                    val value = json(acknowledgement)
                    demand(value.keys == setOf("Key") || value.keys == setOf("Id", "Key"))
                    demand(string(value, "Key") == "$bucket/${intent.objectKey}")
                    if ("Id" in value) demand(string(value, "Id").let { it.length in 1..128 && it.none(Char::isISOControl) })
                    // Id is provider metadata, NOT an immutable objectVersionId or receipt.
                    current()
                } finally { acknowledgement.fill(0) }
            }
        } finally { owned.fill(0) }
    }

    override fun inspect(intent: MediaDerivativeIntent): SupabaseDerivativeReceipt? {
        val bytes = readVerified(intent) ?: return null
        return try { SupabaseDerivativeReceipt(bucket, intent.objectKey, intent.sha256, intent.bytes, intent.contentType) }
        finally { bytes.fill(0) }
    }

    /** Complete bounded private bytes. This is NOT viewer authority or a delivery grant.
     * The caller owns/zeroes the returned buffer and must authorize after the external read. */
    internal fun readVerified(intent: MediaDerivativeIntent): ByteArray? {
        var retained: ByteArray? = null
        try { return guarded {
            validate(intent)
            // Historical recovery may inspect after the write-admission deadline.
            val request = authenticated("/object/authenticated/$bucket/${intent.objectKey}")
                .header("Accept", "image/png").get().build()
            client.newCall(request).execute().use { response ->
                headers(response)
                if (response.code != 200) {
                    if (canonicalMissing(response)) { current(); return@use null }
                    unavailable()
                }
                if (single(response, "Content-Type") != "image/png" || single(response, "Content-Range") != null)
                    fail(MediaProcessingFailureCode.OBJECT_MISMATCH)
                val declared = length(response)
                if (declared != null && declared != intent.bytes) fail(MediaProcessingFailureCode.OBJECT_MISMATCH)
                val bytes = body(response, intent.bytes.toInt(), MediaProcessingFailureCode.OBJECT_MISMATCH)
                try {
                    verifyMaterial(intent, bytes)
                    current()
                    bytes.also { retained = it }
                } catch (failure: Throwable) { bytes.fill(0); throw failure }
            }
        } } catch (failure: Throwable) { retained?.fill(0); throw failure }
    }

    private fun validate(intent: MediaDerivativeIntent) {
        val source = intent.source
        if (source.storageProtocol != SUPABASE_MEDIA_PROTOCOL || source.bucket != bucket ||
            intent.storageProtocol != SUPABASE_MEDIA_PROTOCOL || intent.bucket != bucket ||
            source.owner.environment != configuration.environment || source.objectVersionId != null || intent.objectVersionId != null)
            fail(MediaProcessingFailureCode.NOT_CONFIGURED)
        if (intent.bytes !in 1..configuration.maxObjectBytes) fail(MediaProcessingFailureCode.LIMIT_EXCEEDED)
        if (intent.objectKey != "derivatives/${configuration.environment}/${source.mediaId}/${intent.id}" ||
            intent.contentType != "image/png" || !HASH.matches(intent.sha256) ||
            intent.width !in 1..65_535 || intent.height !in 1..65_535)
            fail(MediaProcessingFailureCode.OBJECT_MISMATCH)
    }

    /** Narrow normalized-PNG header binding only, not another decoder or a safety verdict.
     * The persisted codec intent owns full structural/raster validation. We independently bind
     * the actual complete bytes and IHDR dimensions to that intent without raster allocation. */
    private fun verifyMaterial(intent: MediaDerivativeIntent, bytes: ByteArray) {
        if (bytes.size.toLong() != intent.bytes || digest(bytes) != intent.sha256 || bytes.size < 33 ||
            !PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } || uint32(bytes, 8) != 13L ||
            bytes[12] != 73.toByte() || bytes[13] != 72.toByte() || bytes[14] != 68.toByte() || bytes[15] != 82.toByte() ||
            uint32(bytes, 16) != intent.width.toLong() || uint32(bytes, 20) != intent.height.toLong() ||
            bytes[24] != 8.toByte() || bytes[25].toInt() !in setOf(2, 6) ||
            bytes[26] != 0.toByte() || bytes[27] != 0.toByte() || bytes[28] != 0.toByte() ||
            CRC32().apply { update(bytes, 12, 17) }.value != uint32(bytes, 29))
            fail(MediaProcessingFailureCode.OBJECT_MISMATCH)
    }

    private fun authenticated(path: String) = Request.Builder().url(wireOrigin + "/storage/v1" + path)
        .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache, no-store").apply {
            configuration.apiKey.use { header("apikey", it) }
            configuration.bearer?.use { header("Authorization", "Bearer $it") }
        }

    /** HTTP404 alone (or a missing bucket/access denial) is never object absence. */
    private fun canonicalMissing(response: Response): Boolean {
        if (response.code !in setOf(400, 404) || !jsonMedia(single(response, "Content-Type"))) return false
        val bytes = body(response, MAX_JSON_BYTES)
        try {
            val value = json(bytes)
            demand(value.keys == setOf("statusCode", "code", "error", "message"))
            demand(string(value, "error").length <= 256 && string(value, "message").length <= 4096)
            return string(value, "statusCode") == "404" && string(value, "code") == "NoSuchKey" &&
                string(value, "error") == "NoSuchKey" && string(value, "message") == "Object not found"
        } finally { bytes.fill(0) }
    }

    private fun headers(response: Response) {
        demand(response.headers.size <= 64 && response.headers.sumOf { it.first.length + it.second.length } <= 16_384)
        demand(single(response, "Content-Encoding")?.lowercase() in setOf(null, "identity"))
        current()
    }
    private fun single(response: Response, name: String): String? {
        val values = response.headers.values(name)
        demand(values.size <= 1)
        return values.singleOrNull()?.also { demand(it.length in 1..1024 && it.none(Char::isISOControl)) }
    }
    private fun length(response: Response) = single(response, "Content-Length")?.let {
        demand(LENGTH.matches(it)); it.toLong()
    }
    private fun body(response: Response, maximum: Int,
        mismatch: MediaProcessingFailureCode = MediaProcessingFailureCode.OBJECT_UNAVAILABLE): ByteArray {
        val declared = length(response)
        if (declared != null && declared !in 1..maximum.toLong()) fail(mismatch)
        val buffer = ByteArray(maximum + 1)
        try {
            var count = 0
            response.body.byteStream().use { input ->
                while (true) {
                    current()
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    demand(read > 0)
                    count += read
                    if (count > maximum) fail(mismatch)
                }
            }
            if (count == 0 || (declared != null && declared != count.toLong())) fail(mismatch)
            return buffer.copyOf(count)
        } finally { buffer.fill(0) }
    }

    private fun <T> guarded(action: () -> T): T {
        current()
        if (!admission.tryAcquire()) fail(MediaProcessingFailureCode.LIMIT_EXCEEDED)
        try {
            val started = System.nanoTime()
            val result = action()
            current()
            demand(System.nanoTime() - started <= TimeUnit.MILLISECONDS.toNanos(configuration.callTimeoutMillis))
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
          catch (failure: MediaProcessingFailure) { throw failure }
          catch (_: Exception) { current(); unavailable() }
        finally { admission.release() }
    }
    private fun current() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Supabase derivative interrupted")
        if (closed.get()) fail(MediaProcessingFailureCode.STORAGE_UNAVAILABLE)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    override fun toString() = "SupabaseDerivativeHttp(<redacted>)"

    companion object {
        fun create(configuration: SupabaseStorageHttpConfiguration, clock: Clock): SupabaseDerivativeHttp =
            SupabaseDerivativeHttp(configuration, clock, configuration.projectOrigin, ownedClient(configuration))
        /** Synthetic HTTP fixture only. The production factory has no TLS/origin override. */
        internal fun loopback(configuration: SupabaseStorageHttpConfiguration, clock: Clock, port: Int): SupabaseDerivativeHttp {
            require(port in 1..65535) { "Invalid synthetic derivative port" }
            return SupabaseDerivativeHttp(configuration, clock, "http://127.0.0.1:$port", ownedClient(configuration))
        }
        private fun ownedClient(configuration: SupabaseStorageHttpConfiguration) = OkHttpClient.Builder()
            .connectTimeout(configuration.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(configuration.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(configuration.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(configuration.callTimeoutMillis, TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).fastFallback(false)
            .proxy(Proxy.NO_PROXY).socketFactory(DerivativeDirectSocketFactory(configuration.connectTimeoutMillis.toInt()))
            .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .eventListener(EventListener.NONE).connectionPool(ConnectionPool())
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().tag(DerivativeAttempt::class.java, DerivativeAttempt()).build()) }
            .addNetworkInterceptor { chain ->
                val attempt = chain.request().tag(DerivativeAttempt::class.java) ?: throw IOException("Derivative attempt unavailable")
                if (!attempt.started.compareAndSet(false, true)) throw IOException("Derivative follow-up refused")
                chain.proceed(chain.request())
            }.build()
        private const val MAX_JSON_BYTES = 65_536
        private val HASH = Regex("[0-9a-f]{64}")
        private val LENGTH = Regex("0|[1-9][0-9]{0,18}")
        private val JSON_MEDIA = Regex("application/json(?: *; *charset *= *(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
        private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private fun jsonMedia(value: String?) = value != null && JSON_MEDIA.matches(value)
        private fun json(bytes: ByteArray): JsonObject = Json.parseToJsonElement(WireDocument.decode(bytes,
            WireLimits(MAX_JSON_BYTES, 8, 20)).encodeUtf8().decodeToString()) as? JsonObject ?: error("Invalid derivative response")
        private fun string(value: JsonObject, key: String) =
            (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid derivative response")
        private fun uint32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, index ->
            (value shl 8) or (bytes[offset + index].toLong() and 255)
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun demand(value: Boolean) { if (!value) unavailable() }
        private fun unavailable(): Nothing = fail(MediaProcessingFailureCode.OBJECT_UNAVAILABLE)
        private fun fail(code: MediaProcessingFailureCode): Nothing = throw MediaProcessingFailure(code)
    }
}

private class DerivativeAttempt { val started = AtomicBoolean() }
private class DerivativeDirectSocketFactory(private val timeoutMillis: Int) : SocketFactory() {
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
