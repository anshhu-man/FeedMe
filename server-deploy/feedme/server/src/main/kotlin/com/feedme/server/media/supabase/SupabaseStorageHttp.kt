package com.feedme.server.media.supabase

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.SecretText
import com.feedme.server.media.MediaFailure
import com.feedme.server.media.MediaFailureCode
import com.feedme.server.media.SupabaseMediaStorage
import com.feedme.server.media.SupabaseObjectRequest
import com.feedme.server.media.SupabaseUploadCapability
import com.feedme.server.media.SupabaseUploadRequest
import com.feedme.server.media.VerifiedSupabaseObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Explicit provider credentials only, never a FeedMe account bearer. A new-format API key is
 * not automatically relabelled as a Bearer JWT. No environment/configuration is read here. */
class SupabaseStorageHttpConfiguration(
    val environment: String,
    val projectOrigin: String,
    val bucket: String,
    internal val apiKey: SecretText,
    internal val bearer: SecretText?,
    val connectTimeoutMillis: Long,
    val readTimeoutMillis: Long,
    val callTimeoutMillis: Long,
    val maxObjectBytes: Long,
    val maxCapabilityLifetimeSeconds: Long,
    val clockSkewSeconds: Long,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) &&
            bucket.matches(Regex("[a-z0-9][a-z0-9_-]{0,62}")) && validOrigin(projectOrigin) &&
            connectTimeoutMillis in 100..10_000 && readTimeoutMillis in 100..30_000 &&
            callTimeoutMillis in maxOf(connectTimeoutMillis, readTimeoutMillis)..60_000 &&
            maxObjectBytes in 1..10_000_000 && maxCapabilityLifetimeSeconds in 1..86400 &&
            clockSkewSeconds in 0..120 && credential(apiKey) && (bearer == null || credential(bearer))) {
            "Invalid Supabase storage configuration"
        }
    }
    override fun toString() = "SupabaseStorageHttpConfiguration(<redacted>)"

    private companion object {
        fun credential(value: SecretText) = value.use { it.length in 1..16_384 && it.all { c -> c.code in 33..126 } }
        fun validOrigin(value: String): Boolean = try {
            val uri = URI(value)
            value.length in 1..512 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.host == uri.host.lowercase() && uri.rawUserInfo == null && uri.rawQuery == null &&
                uri.rawFragment == null && uri.rawPath.isEmpty() && uri.port in setOf(-1, 443) &&
                uri.toASCIIString() == value && !value.contains('%') && !value.contains('\\')
        } catch (_: Exception) { false }
    }
}

/** Bounded source-upload adapter only. It neither authorizes an account nor implements image
 * safety, derivatives, publication, deletion or a late-write settlement guarantee.
 *
 * Mint is remote and MUST be called outside database transactions after durable issuance intent.
 * A failure may follow a successful provider mint: it never proves no capability was issued.
 * JWT decoding checks the authenticated response representation, NOT the provider signature.
 * read() finishes bounded byte/hash verification before handing any bytes to the processor.
 */
class SupabaseStorageHttp private constructor(
    private val configuration: SupabaseStorageHttpConfiguration,
    private val clock: Clock,
    private val wireOrigin: String,
    private val client: OkHttpClient,
) : SupabaseMediaStorage, AutoCloseable {
    override val bucket: String get() = configuration.bucket
    private val closed = AtomicBoolean()
    // No waiting queue or unbounded simultaneous full-photo buffers.
    private val admission = Semaphore(4)

    override fun mint(request: SupabaseUploadRequest): SupabaseUploadCapability = guarded(MediaFailureCode.CAPABILITY_UNAVAILABLE) {
        validate(request.environment, request.objectKey, request.expectedBytes, request.contentType, request.sha256)
        val started = clock.instant()
        demand(request.expiresNoLaterThan > started)
        val path = "/object/upload/sign/$bucket/${request.objectKey}"
        val call = authenticated(path).header("Accept", "application/json").header("x-upsert", "false")
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        client.newCall(call).execute().use { response ->
            checkHeaders(response)
            if (response.code != 200) errorResponse(response, MediaFailureCode.CAPABILITY_UNAVAILABLE)
            demand(jsonMedia(single(response, "Content-Type")))
            val bytes = boundedBody(response, MAX_JSON_BYTES)
            try {
                val body = json(bytes)
                demand(body.keys == setOf("url") || body.keys == setOf("url", "token"))
                val relative = string(body, "url")
                demand(relative.length <= MAX_JSON_BYTES && relative.startsWith("$path?token="))
                val token = relative.removePrefix("$path?token=")
                demand(token.length in 1..MAX_TOKEN_BYTES && TOKEN.matches(token))
                if ("token" in body) demand(string(body, "token") == token)
                val expiry = tokenExpiry(token, "$bucket/${request.objectKey}", started, request.expiresNoLaterThan)
                current()
                SupabaseUploadCapability(configuration.projectOrigin + "/storage/v1" + relative, expiry)
            } finally { bytes.fill(0) }
        }
    }

    override fun verify(request: SupabaseObjectRequest): VerifiedSupabaseObject = guarded(MediaFailureCode.OBJECT_VERIFICATION_UNAVAILABLE) {
        val bytes = download(request)
        try {
            current()
            VerifiedSupabaseObject(request.objectKey, bytes.size.toLong(), request.contentType, request.sha256)
        } finally { bytes.fill(0) }
    }

    override fun read(request: SupabaseObjectRequest): InputStream = guarded(MediaFailureCode.OBJECT_VERIFICATION_UNAVAILABLE) {
        val bytes = download(request)
        try { current(); VerifiedMemoryInput(bytes) }
        catch (failure: Throwable) { bytes.fill(0); throw failure }
    }

    private fun download(request: SupabaseObjectRequest): ByteArray {
        validate(request.environment, request.objectKey, request.expectedBytes, request.contentType, request.sha256)
        val call = authenticated("/object/authenticated/$bucket/${request.objectKey}")
            .header("Accept", request.contentType).get().build()
        return client.newCall(call).execute().use { response ->
            checkHeaders(response)
            if (response.code != 200) errorResponse(response, MediaFailureCode.OBJECT_VERIFICATION_UNAVAILABLE)
            if (single(response, "Content-Type") != request.contentType || single(response, "Content-Range") != null)
                fail(MediaFailureCode.OBJECT_MISMATCH)
            val declared = length(response)
            if (declared != null && declared != request.expectedBytes) fail(MediaFailureCode.OBJECT_MISMATCH)
            val bytes = boundedBody(response, request.expectedBytes.toInt())
            try {
                if (bytes.size.toLong() != request.expectedBytes || digest(bytes) != request.sha256)
                    fail(MediaFailureCode.OBJECT_MISMATCH)
                current()
                bytes
            } catch (failure: Throwable) { bytes.fill(0); throw failure }
        }
    }

    private fun authenticated(path: String): Request.Builder = Request.Builder()
        .url(wireOrigin + "/storage/v1" + path)
        .header("Accept-Encoding", "identity")
        .header("Cache-Control", "no-cache, no-store")
        .apply {
            configuration.apiKey.use { header("apikey", it) }
            configuration.bearer?.use { header("Authorization", "Bearer $it") }
        }

    private fun validate(environment: String, key: String, bytes: Long, type: String, hash: String) {
        if (environment != configuration.environment || bytes !in 1..configuration.maxObjectBytes ||
            type !in setOf("image/jpeg", "image/png") || !HASH.matches(hash) || key.length !in 1..200 ||
            key.split('/').any { !PATH_SEGMENT.matches(it) || it == "." || it == ".." })
            fail(MediaFailureCode.INPUT_INVALID)
    }

    private fun tokenExpiry(token: String, path: String, started: Instant, noLaterThan: Instant): Instant {
        val parts = token.split('.')
        val headerBytes = unbase64(parts[0])
        val payloadBytes = unbase64(parts[1])
        val signatureBytes = unbase64(parts[2])
        try {
            val header = json(headerBytes)
            demand(header.keys.all { it in setOf("alg", "typ", "kid") })
            demand(string(header, "alg") in ALGORITHMS)
            if ("typ" in header) demand(string(header, "typ") == "JWT")
            if ("kid" in header) demand(string(header, "kid").let { it.length in 1..256 && it.none(Char::isISOControl) })
            val payload = json(payloadBytes)
            demand(payload.keys.all { it in setOf("url", "upsert", "owner", "scope", "iat", "exp") })
            demand(string(payload, "url") == path)
            val upsert = payload["upsert"] as? JsonPrimitive
            demand(upsert != null && !upsert.isString && upsert.booleanOrNull == false)
            if ("scope" in payload) demand(string(payload, "scope") == "upload")
            // Legacy provider upload tokens omit scope; false upsert and exact path remain mandatory.
            if ("owner" in payload && payload["owner"] !== JsonNull)
                demand(string(payload, "owner").let { it.length in 1..256 && it.none(Char::isISOControl) })
            val issued = instant(payload, "iat")
            val expiry = instant(payload, "exp")
            val finished = clock.instant()
            demand(finished >= started && issued <= finished.plusSeconds(configuration.clockSkewSeconds) &&
                issued >= started.minusSeconds(configuration.clockSkewSeconds) && expiry > finished &&
                expiry > issued && expiry <= issued.plusSeconds(configuration.maxCapabilityLifetimeSeconds) &&
                expiry <= noLaterThan)
            return expiry
        } finally { headerBytes.fill(0); payloadBytes.fill(0); signatureBytes.fill(0) }
    }

    private fun instant(body: JsonObject, name: String): Instant {
        val value = body[name] as? JsonPrimitive
        demand(value != null && !value.isString && POSITIVE_INTEGER.matches(value.content))
        return Instant.ofEpochSecond(checkNotNull(value).content.toLong())
    }

    private fun unbase64(text: String): ByteArray {
        demand(text.length in 1..MAX_TOKEN_BYTES)
        val bytes = Base64.getUrlDecoder().decode(text)
        demand(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == text)
        return bytes
    }

    private fun checkHeaders(response: Response) {
        demand(response.headers.size <= 64 && response.headers.sumOf { it.first.length + it.second.length } <= 16_384)
        demand(single(response, "Content-Encoding")?.lowercase() in setOf(null, "identity"))
        current()
    }

    private fun single(response: Response, name: String): String? {
        val values = response.headers.values(name)
        demand(values.size <= 1)
        return values.singleOrNull()?.also { demand(it.length in 1..1024 && it.none(Char::isISOControl)) }
    }

    private fun length(response: Response): Long? = single(response, "Content-Length")?.let {
        demand(LENGTH.matches(it)); it.toLong()
    }

    private fun boundedBody(response: Response, maximum: Int): ByteArray {
        val declared = length(response)
        demand(declared == null || declared in 1..maximum.toLong())
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
                    demand(count <= maximum)
                }
            }
            demand(count > 0 && (declared == null || declared == count.toLong()))
            return buffer.copyOf(count)
        } finally { buffer.fill(0) }
    }

    /** Known REST errors can carry HTTP 400 while statusCode describes 404/409. Arbitrary
     * status codes, HTML, access denial and a missing bucket are never object absence. */
    private fun errorResponse(response: Response, fallback: MediaFailureCode): Nothing {
        if (!jsonMedia(single(response, "Content-Type"))) fail(fallback)
        val bytes = boundedBody(response, MAX_JSON_BYTES)
        try {
            val body = json(bytes)
            demand(body.keys == setOf("statusCode", "code", "error", "message"))
            val code = string(body, "code")
            val status = string(body, "statusCode")
            demand(string(body, "error").length <= 256 && string(body, "message").length <= 4096)
            if (code == "NoSuchKey" && status == "404" && response.code in setOf(400, 404))
                fail(MediaFailureCode.MEDIA_UNAVAILABLE)
            if (code in setOf("KeyAlreadyExists", "ResourceAlreadyExists") && status == "409" && response.code in setOf(400, 409))
                fail(MediaFailureCode.MEDIA_CONFLICT)
            fail(fallback)
        } finally { bytes.fill(0) }
    }

    private fun <T> guarded(fallback: MediaFailureCode, action: () -> T): T {
        current()
        if (!admission.tryAcquire()) fail(fallback)
        try {
            val started = System.nanoTime()
            val value = action()
            try {
                current()
                demand(System.nanoTime() - started <= TimeUnit.MILLISECONDS.toNanos(configuration.callTimeoutMillis))
                return value
            } catch (failure: Throwable) {
                // read() may already own verified private bytes when close/deadline wins this hop.
                if (value is VerifiedMemoryInput) value.close()
                throw failure
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
          catch (failure: MediaFailure) { throw failure }
          catch (_: Exception) {
              current()
              fail(fallback)
          }
        finally { admission.release() }
    }

    private fun current() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Supabase storage interrupted")
        if (closed.get()) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
    override fun toString() = "SupabaseStorageHttp(<redacted>)"

    companion object {
        fun create(configuration: SupabaseStorageHttpConfiguration, clock: Clock): SupabaseStorageHttp =
            SupabaseStorageHttp(configuration, clock, configuration.projectOrigin, ownedClient(configuration))

        /** Internal synthetic HTTP fixture only; production factory has no HTTP/TLS escape. */
        internal fun loopback(configuration: SupabaseStorageHttpConfiguration, clock: Clock, port: Int): SupabaseStorageHttp {
            require(port in 1..65535) { "Invalid synthetic storage port" }
            return SupabaseStorageHttp(configuration, clock, "http://127.0.0.1:$port", ownedClient(configuration))
        }

        private fun ownedClient(configuration: SupabaseStorageHttpConfiguration): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(configuration.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(configuration.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(configuration.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(configuration.callTimeoutMillis, TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).fastFallback(false)
            .proxy(Proxy.NO_PROXY).socketFactory(StorageDirectSocketFactory(configuration.connectTimeoutMillis.toInt()))
            .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .eventListener(EventListener.NONE).connectionPool(ConnectionPool())
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().tag(StorageAttempt::class.java, StorageAttempt()).build()) }
            .addNetworkInterceptor { chain ->
                val attempt = chain.request().tag(StorageAttempt::class.java) ?: throw IOException("Storage attempt unavailable")
                if (!attempt.started.compareAndSet(false, true)) throw IOException("Storage follow-up refused")
                chain.proceed(chain.request())
            }.build()

        private const val MAX_JSON_BYTES = 65_536
        private const val MAX_TOKEN_BYTES = 8192
        private val HASH = Regex("[0-9a-f]{64}")
        private val PATH_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val TOKEN = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        private val POSITIVE_INTEGER = Regex("[1-9][0-9]{0,11}")
        private val LENGTH = Regex("0|[1-9][0-9]{0,18}")
        private val JSON_MEDIA = Regex("application/json(?: *; *charset *= *(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
        private val ALGORITHMS = setOf("HS256", "HS384", "HS512", "RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "EdDSA")
        private fun jsonMedia(value: String?) = value != null && JSON_MEDIA.matches(value)
        private fun json(bytes: ByteArray): JsonObject {
            val document = WireDocument.decode(bytes, WireLimits(MAX_JSON_BYTES, 8, 20))
            return Json.parseToJsonElement(document.encodeUtf8().decodeToString()) as? JsonObject ?: error("Invalid storage response")
        }
        private fun string(body: JsonObject, name: String): String =
            (body[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid storage response")
        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun demand(value: Boolean) { check(value) { "Invalid storage response" } }
        private fun fail(code: MediaFailureCode): Nothing = throw MediaFailure(code)
    }
}

private class StorageAttempt { val started = AtomicBoolean() }
private class VerifiedMemoryInput(private val content: ByteArray) : InputStream() {
    private val delegate = ByteArrayInputStream(content)
    private var closed = false
    override fun read(): Int = if (closed) -1 else delegate.read()
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = if (closed) -1 else delegate.read(bytes, offset, length)
    override fun available(): Int = if (closed) 0 else delegate.available()
    override fun close() { if (!closed) { closed = true; content.fill(0); delegate.close() } }
    override fun toString() = "VerifiedSupabaseInput(<redacted>)"
}

/** Avoid ambient SOCKS as well as HTTP proxy configuration. Stock TLS verification is unchanged. */
private class StorageDirectSocketFactory(private val timeoutMillis: Int) : SocketFactory() {
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
