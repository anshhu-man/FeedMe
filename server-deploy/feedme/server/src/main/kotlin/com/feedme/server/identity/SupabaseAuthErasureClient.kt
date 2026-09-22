package com.feedme.server.identity

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.http.readBoundedHttpBody
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.EventListener

/** Server-worker injection only; no credential discovery, environment reads or client assets.
 * Legacy service-role JWT syntax/role/ref checks reject obvious wrong credentials; they do NOT
 * validate a signature, expiry, privilege or ownership. Supabase authenticates the real request. */
internal class SupabaseAuthErasureConfiguration(
    internal val issuer: String,
    internal val serviceRoleKey: String,
) {
    internal fun valid(): Boolean = try {
        require(issuer == SupabaseAuthErasureClient.APPROVED_ISSUER)
        require(serviceRoleKey.length in 1..8_192)
        require(serviceRoleKey.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")))
        val bytes = Base64.getUrlDecoder().decode(serviceRoleKey.split('.')[1])
        try {
            val payload = Json.parseToJsonElement(WireDocument.decode(bytes, WireLimits(8_192, 8))
                .encodeUtf8().decodeToString()) as JsonObject
            require(payload["role"] == JsonPrimitive("service_role"))
            require(payload["ref"] == JsonPrimitive(SupabaseAuthErasureClient.PROJECT_REF))
        } finally { bytes.fill(0) }
        true
    } catch (_: Exception) { false }
    override fun toString() = "SupabaseAuthErasureConfiguration(<redacted>)"
}

/** ACK is only the pinned provider HTTP acknowledgement, not an overall erasure receipt,
 * ownership proof, Supabase Storage purge, backup removal or permission to advance another job. */
internal enum class SupabaseAuthErasureResult {
    NOT_CONFIGURED, INVALID_TARGET, TRANSPORT_ACKNOWLEDGED, OUTCOME_UNKNOWN,
}

/** One explicit hard-delete attempt for a future durable worker, after its own accepted job,
 * same-subject authority, consent, ordered stages and retained attempt have been established.
 * No routes, worker admission, retry, persistence, receipt creation or account lookup live here.
 *
 * Pinned Supabase Auth v2.197.0 internal/api/admin.go: DELETE /admin/users/{id} with
 * should_soft_delete:false destroys the Auth user in a transaction, then returns 200 JSON {}.
 * A lost response, 404, provider error or malformed success is UNKNOWN, never inferred complete.
 * The caller must durably reconcile uncertainty; repeating this method is a new explicit attempt.
 * No redirects, cookies, logging, proxy, fallback, credential refresh or automatic retry.
 */
internal class SupabaseAuthErasureClient private constructor(
    private val configuration: SupabaseAuthErasureConfiguration?,
    private val client: HttpClient?,
    private val timeoutMillis: Long,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    /** Configuration availability only, not proof that Supabase accepts the credential. */
    internal val configuredIssuer: String? get() = if (closed.get()) null else configuration?.issuer

    suspend fun erase(providerUserId: String): SupabaseAuthErasureResult {
        currentCoroutineContext().ensureActive()
        val config = configuration ?: return SupabaseAuthErasureResult.NOT_CONFIGURED
        val http = client ?: return SupabaseAuthErasureResult.NOT_CONFIGURED
        if (closed.get()) return SupabaseAuthErasureResult.NOT_CONFIGURED
        if (!canonicalUuid(providerUserId)) return SupabaseAuthErasureResult.INVALID_TARGET
        val bytes = "{\"should_soft_delete\":false}".encodeToByteArray()
        return try {
            withTimeoutOrNull(timeoutMillis) {
                http.prepareRequest("${config.issuer}/admin/users/$providerUserId") {
                    method = HttpMethod.Delete
                    headers.append("Authorization", "Bearer ${config.serviceRoleKey}")
                    headers.append("apikey", config.serviceRoleKey)
                    headers.append("Accept", "application/json")
                    headers.append("Accept-Encoding", "identity")
                    headers.append("Cache-Control", "no-store")
                    contentType(ContentType.Application.Json)
                    setBody(bytes)
                }.execute { response ->
                    if (response.status.value != 200) return@execute SupabaseAuthErasureResult.OUTCOME_UNKNOWN
                    val entries = response.headers.entries()
                    validResponse(entries.size <= 64 && entries.sumOf { it.key.length + it.value.sumOf(String::length) } <= 16_384)
                    fun header(name: String): String? {
                        val values = response.headers.getAll(name) ?: return null
                        validResponse(values.size == 1)
                        return values.single().also { validResponse(it.length in 1..1_024 && it.none(Char::isISOControl)) }
                    }
                    validResponse(header("Content-Type")?.matches(JSON_MEDIA) == true)
                    validResponse(header("Content-Encoding")?.lowercase() in setOf(null, "identity"))
                    val length = header("Content-Length")?.let {
                        validResponse(it.matches(Regex("0|[1-9][0-9]{0,18}")))
                        it.toLongOrNull().also { count -> validResponse(count != null && count in 2..MAX_RESPONSE_BYTES.toLong()) }
                    }
                    val body = readBoundedHttpBody(response.bodyAsChannel(), MAX_RESPONSE_BYTES, length,
                        { throw InvalidAuthErasureResponse() }, timeoutMillis + 1_000)
                    try {
                        val json = Json.parseToJsonElement(WireDocument.decode(body, WireLimits(MAX_RESPONSE_BYTES, 4))
                            .encodeUtf8().decodeToString())
                        validResponse(json is JsonObject && json.isEmpty())
                        currentCoroutineContext().ensureActive()
                        if (closed.get()) SupabaseAuthErasureResult.OUTCOME_UNKNOWN
                        else SupabaseAuthErasureResult.TRANSPORT_ACKNOWLEDGED
                    } finally { body.fill(0) }
                }
            } ?: SupabaseAuthErasureResult.OUTCOME_UNKNOWN
        } catch (_: CancellationException) {
              // A cancelled caller must remain cancelled. The HTTP client can also
              // cancel its own request during close while this caller remains active;
              // that is an uncertain provider outcome, not a successful deletion.
              currentCoroutineContext().ensureActive()
              SupabaseAuthErasureResult.OUTCOME_UNKNOWN
          }
          catch (_: Exception) {
              currentCoroutineContext().ensureActive()
              SupabaseAuthErasureResult.OUTCOME_UNKNOWN
          } finally { bytes.fill(0) }
    }

    override fun close() { if (closed.compareAndSet(false, true)) client?.close() }
    override fun toString() = "SupabaseAuthErasureClient(<redacted>)"

    companion object {
        internal const val PROJECT_REF = "bljskfhazmnzmhkbkcev"
        internal const val APPROVED_ISSUER = "https://bljskfhazmnzmhkbkcev.supabase.co/auth/v1"
        internal const val MAX_RESPONSE_BYTES = 16_384
        internal const val TIMEOUT_MILLIS = 10_000L
        private val JSON_MEDIA = Regex("application/json(?: *; *charset *= *(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)

        /** Default disabled. Invalid configuration constructs no engine. Stock TLS verification. */
        fun create(configuration: SupabaseAuthErasureConfiguration? = null): SupabaseAuthErasureClient {
            val config = configuration?.takeIf { it.valid() } ?: return SupabaseAuthErasureClient(null, null, TIMEOUT_MILLIS)
            return SupabaseAuthErasureClient(config, HttpClient(OkHttp) {
                followRedirects = false
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 3_000
                    requestTimeoutMillis = TIMEOUT_MILLIS
                    socketTimeoutMillis = TIMEOUT_MILLIS
                }
                engine { config {
                    followRedirects(false)
                    followSslRedirects(false)
                    retryOnConnectionFailure(false)
                    fastFallback(false)
                    proxy(Proxy.NO_PROXY)
                    socketFactory(AuthErasureDirectSocketFactory(3_000))
                    cookieJar(CookieJar.NO_COOKIES)
                    cache(null)
                    authenticator(Authenticator.NONE)
                    proxyAuthenticator(Authenticator.NONE)
                    eventListener(EventListener.NONE)
                    connectionPool(ConnectionPool())
                    dispatcher(Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
                    addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                        .tag(AuthErasureAttempt::class.java, AuthErasureAttempt()).build()) }
                    // Also stop OkHttp's implicit 408/503/421 follow-up requests.
                    addNetworkInterceptor { chain ->
                        val attempt = chain.request().tag(AuthErasureAttempt::class.java)
                            ?: throw IOException("auth_erasure_attempt_missing")
                        if (!attempt.started.compareAndSet(false, true)) throw IOException("auth_erasure_follow_up_rejected")
                        chain.proceed(chain.request())
                    }
                } }
            }, TIMEOUT_MILLIS)
        }

        /** Synthetic engine seam only, never runtime-selectable. Caller owns the engine. */
        internal fun forTests(configuration: SupabaseAuthErasureConfiguration?, engine: HttpClientEngine,
            timeoutMillis: Long = TIMEOUT_MILLIS): SupabaseAuthErasureClient {
            require(timeoutMillis in 1..TIMEOUT_MILLIS) { "Invalid test timeout" }
            val config = configuration?.takeIf { it.valid() } ?: return SupabaseAuthErasureClient(null, null, timeoutMillis)
            return SupabaseAuthErasureClient(config, HttpClient(engine) {
                followRedirects = false; expectSuccess = false
            }, timeoutMillis)
        }

        private fun canonicalUuid(value: String): Boolean = try {
            value.length == 36 && UUID.fromString(value).toString() == value
        } catch (_: IllegalArgumentException) { false }
        private fun validResponse(condition: Boolean) { if (!condition) throw InvalidAuthErasureResponse() }
    }
}

private class InvalidAuthErasureResponse : IllegalArgumentException("Invalid Auth erasure response")
private class AuthErasureAttempt { val started = AtomicBoolean() }
private class AuthErasureDirectSocketFactory(private val timeoutMillis: Int) : SocketFactory() {
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
