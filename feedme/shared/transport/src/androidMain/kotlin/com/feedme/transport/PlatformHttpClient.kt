package com.feedme.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.RequestBody
import okio.BufferedSink

/** Owned engine; callers cannot inject ambient interceptors, credentials, or proxy policy. */
internal actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
            fastFallback(false)
            proxy(Proxy.NO_PROXY)
            socketFactory(DirectSocketFactory())
            cookieJar(CookieJar.NO_COOKIES)
            cache(null)
            authenticator(Authenticator.NONE)
            proxyAuthenticator(Authenticator.NONE)
            eventListener(EventListener.NONE)
            connectionPool(ConnectionPool())
            // Stock platform trust manager and hostname verification remain enabled.
            addInterceptor { chain ->
                val request = chain.request()
                val body = request.body
                val guardedRequest = request.newBuilder()
                    .tag(NetworkAttempt::class.java, NetworkAttempt())
                    .apply {
                        if (body != null) method(request.method, OneShotBody(body))
                    }
                    .build()
                chain.proceed(guardedRequest)
            }
            addNetworkInterceptor { chain ->
                val attempt = chain.request().tag(NetworkAttempt::class.java)
                    ?: throw IOException("transport_attempt_missing")
                if (!attempt.started.compareAndSet(false, true)) {
                    throw IOException("transport_follow_up_rejected")
                }
                chain.proceed(chain.request())
            }
        }
    }
}

private class NetworkAttempt {
    val started = AtomicBoolean(false)
}

/** Also suppresses OkHttp's 503/421 follow-up handling for finite write bodies. */
private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
}

/** A default Java Socket can consult the ambient SOCKS selector despite OkHttp's direct route. */
private class DirectSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)

    override fun createSocket(host: String, port: Int): Socket =
        connected(InetSocketAddress(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        connected(InetSocketAddress(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        connected(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))

    private fun connected(remote: InetSocketAddress, local: InetSocketAddress? = null): Socket {
        val socket = createSocket()
        try {
            if (local != null) socket.bind(local)
            socket.connect(remote, 15_000)
            return socket
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            throw failure
        }
    }
}
