package com.feedme.server.auth

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.EventListener

/** Owned production HTTPS engine. Internal client injection exists only for boundary tests;
 * it cannot be supplied to HttpsSupabaseJwksSource.create. No trust-all TLS/HTTP test escape. */
internal class KtorSupabaseJwksExchange internal constructor(private val client: HttpClient) : SupabaseJwksExchange {
    private val closed = AtomicBoolean()
    override suspend fun get(endpoint: URI): PortResult<SupabaseJwksHttpResponse> {
        if (closed.get() || endpoint.scheme != "https" || endpoint.rawUserInfo != null || endpoint.rawQuery != null || endpoint.rawFragment != null) return unavailable()
        return try {
            client.prepareGet(endpoint.toASCIIString()) {
                headers.append("Accept", "application/json, application/jwk-set+json")
                headers.append("Accept-Encoding", "identity")
                headers.append("Cache-Control", "no-cache")
            }.execute { response ->
                // This block streams; never call bodyAsText/ByteArray whole-response buffering.
                check(response.status.value == 200)
                val headers = response.headers.entries().associate { it.key to it.value.toList() }
                check(headers.size <= 64 && headers.entries.sumOf { it.key.length + it.value.sumOf(String::length) } <= 16_384)
                fun header(name: String): String? {
                    val values = response.headers.getAll(name) ?: return null
                    check(values.size == 1)
                    return values.single().also { check(it.length in 1..1024 && it.none(Char::isISOControl)) }
                }
                check(header("Content-Encoding")?.lowercase() in setOf(null, "identity"))
                check(header("Content-Type")?.matches(JSON_MEDIA) == true)
                val declared = header("Content-Length")?.let { value ->
                    check(value.matches(Regex("0|[1-9][0-9]{0,18}")))
                    value.toLong().also { check(it in 1..65_536) }
                }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(65_537)
                var count = 0
                while (true) {
                    val n = channel.readAvailable(buffer, count, buffer.size - count)
                    if (n == -1) { channel.closedCause?.let { throw it }; break }
                    count += n
                    check(count <= 65_536)
                }
                check(count > 0 && (declared == null || declared == count.toLong()))
                PortResult.Value(SupabaseJwksHttpResponse(endpoint, response.status.value, headers, buffer.copyOf(count)))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { unavailable() }
    }
    override fun close() { if (closed.compareAndSet(false, true)) client.close() }
    override fun toString() = "KtorSupabaseJwksExchange(<redacted>)"
    private fun unavailable() = PortResult.Failure(FailureReason.UNAVAILABLE)

    companion object {
        fun create(policy: SupabaseJwksHttpPolicy): KtorSupabaseJwksExchange =
            build(policy, JwksDirectSocketFactory(policy.connectTimeoutMillis.toInt()))

        /** Internal integration seam changes only TCP routing. The public factory never
         * accepts it; TLS, hostname verification and every HTTP boundary stay shared. */
        internal fun forSocketFactory(policy: SupabaseJwksHttpPolicy, sockets: SocketFactory): KtorSupabaseJwksExchange =
            build(policy, sockets)

        private fun build(policy: SupabaseJwksHttpPolicy, sockets: SocketFactory): KtorSupabaseJwksExchange = KtorSupabaseJwksExchange(HttpClient(OkHttp) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = policy.connectTimeoutMillis
                requestTimeoutMillis = policy.totalTimeoutMillis
                socketTimeoutMillis = policy.socketTimeoutMillis
            }
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                    retryOnConnectionFailure(false)
                    fastFallback(false)
                    proxy(Proxy.NO_PROXY)
                    socketFactory(sockets)
                    cookieJar(CookieJar.NO_COOKIES)
                    cache(null)
                    authenticator(Authenticator.NONE)
                    proxyAuthenticator(Authenticator.NONE)
                    eventListener(EventListener.NONE)
                    connectionPool(ConnectionPool())
                    dispatcher(Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
                    // Keep stock trust manager + hostname verification. No caller SSL override.
                    addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().tag(JwksAttempt::class.java, JwksAttempt()).build())
                    }
                    addNetworkInterceptor { chain ->
                        val attempt = chain.request().tag(JwksAttempt::class.java) ?: throw IOException("jwks_attempt_missing")
                        if (!attempt.started.compareAndSet(false, true)) throw IOException("jwks_follow_up_rejected")
                        chain.proceed(chain.request())
                    }
                }
            }
        })
        private val JSON_MEDIA = Regex("application/(?:json|jwk-set\\+json)(?: *; *charset *= *(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
    }
}

private class JwksAttempt { val started = AtomicBoolean() }
/** Avoid ambient SOCKS defaults as well as HTTP proxies, matching existing JVM transport. */
private class JwksDirectSocketFactory(private val timeoutMillis: Int) : SocketFactory() {
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
