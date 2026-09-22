package com.feedme.server.ai

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.http.readBoundedHttpBody
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.EventListener

/** A transport result is untrusted model output, not a schema, food-safety or eligibility approval. */
internal interface HostedJsonModel {
    suspend fun complete(systemInstructions: String, input: JsonObject, schema: JsonObject): HostedJsonResult
}

internal sealed interface HostedJsonResult {
    data class Value(val value: JsonObject) : HostedJsonResult {
        override fun toString() = "HostedJsonResult.Value(<redacted>)"
    }
    data class Unavailable(val reason: HostedJsonFailure) : HostedJsonResult
}

internal enum class HostedJsonFailure { NOT_CONFIGURED, RATE_LIMITED, UNAVAILABLE, INVALID_RESPONSE, INVALID_INPUT }

internal data class CloudflareJsonPolicy(
    val maxRequestBytes: Int = 32_768,
    val maxResponseBytes: Int = 65_536,
    val maxOutputTokens: Int = 2_048,
    val timeoutMillis: Long = 10_000,
) {
    init {
        require(maxRequestBytes in 1_024..65_536 && maxResponseBytes in 1_024..262_144 &&
            maxOutputTokens in 1..4_096 && timeoutMillis in 1..30_000) { "Unsupported hosted JSON resource policy" }
    }
}

/** Backend-injected only. No environment lookup, client asset or credential discovery. */
internal class CloudflareJsonConfiguration(
    internal val accountId: String,
    internal val apiToken: String,
    val policy: CloudflareJsonPolicy = CloudflareJsonPolicy(),
) {
    internal fun valid() = accountId.matches(Regex("[a-fA-F0-9]{32}")) &&
        apiToken.length in 1..4_096 && apiToken.matches(Regex("[A-Za-z0-9._~+/=-]+"))
    override fun toString() = "CloudflareJsonConfiguration(<redacted>)"
}

/**
 * Single-attempt Workers AI transport. JSON mode follows Cloudflare's documented
 * response_format {type:json_schema,json_schema:<schema>}; callers still validate every field.
 * No redirects, retry, logging, paid fallback, schema fetching or prompt/response persistence.
 * References: developers.cloudflare.com/workers-ai/{configuration/open-ai-compatibility,features/json-mode}/
 * Qwen's model page lists response_format, but the generic JSON-mode list omits it: live
 * model/endpoint compatibility requires the explicit live check. This transport grants no
 * audience permission; the configured account service separately requires persisted adult consent.
 */
internal class CloudflareJsonModel private constructor(
    private val configuration: CloudflareJsonConfiguration?,
    private val client: HttpClient?,
) : HostedJsonModel, AutoCloseable {
    private val closed = AtomicBoolean()

    override suspend fun complete(systemInstructions: String, input: JsonObject, schema: JsonObject): HostedJsonResult {
        currentCoroutineContext().ensureActive()
        val config = configuration ?: return unavailable(HostedJsonFailure.NOT_CONFIGURED)
        val http = client ?: return unavailable(HostedJsonFailure.NOT_CONFIGURED)
        if (closed.get()) return unavailable(HostedJsonFailure.NOT_CONFIGURED)
        val bytes = try { request(systemInstructions, input, schema, config.policy) }
            catch (_: Exception) { return unavailable(HostedJsonFailure.INVALID_INPUT) }
        return try {
            withTimeoutOrNull(config.policy.timeoutMillis) {
                http.preparePost("https://api.cloudflare.com/client/v4/accounts/${config.accountId}/ai/v1/chat/completions") {
                    headers.append("Authorization", "Bearer ${config.apiToken}")
                    headers.append("Accept", "application/json")
                    headers.append("Accept-Encoding", "identity")
                    headers.append("Cache-Control", "no-store")
                    contentType(ContentType.Application.Json)
                    setBody(bytes)
                }.execute { response ->
                    when (response.status.value) {
                        429 -> unavailable(HostedJsonFailure.RATE_LIMITED)
                        401, 403 -> unavailable(HostedJsonFailure.NOT_CONFIGURED)
                        200 -> {
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
                                it.toLongOrNull().also { n -> validResponse(n != null && n in 1..config.policy.maxResponseBytes.toLong()) }
                            }
                            val body = readBoundedHttpBody(response.bodyAsChannel(), config.policy.maxResponseBytes,
                                length, { throw InvalidModelResponse() }, config.policy.timeoutMillis + 1_000)
                            try { HostedJsonResult.Value(parseResponse(body, config.policy.maxResponseBytes)) }
                            finally { body.fill(0) }
                        }
                        else -> unavailable(HostedJsonFailure.UNAVAILABLE)
                    }
                }
            } ?: unavailable(HostedJsonFailure.UNAVAILABLE)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: InvalidModelResponse) { unavailable(HostedJsonFailure.INVALID_RESPONSE) }
          catch (_: Exception) {
              currentCoroutineContext().ensureActive()
              unavailable(HostedJsonFailure.UNAVAILABLE)
          } finally { bytes.fill(0) }
    }

    override fun close() { if (closed.compareAndSet(false, true)) client?.close() }
    override fun toString() = "CloudflareJsonModel(<redacted>)"

    companion object {
        internal const val MODEL = "@cf/qwen/qwen3-30b-a3b-fp8"
        private val JSON_MEDIA = Regex("application/json(?: *; *charset *= *(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)

        /** Default is disabled, and invalid configuration constructs no engine. */
        fun create(configuration: CloudflareJsonConfiguration? = null): CloudflareJsonModel {
            val config = configuration?.takeIf { it.valid() } ?: return CloudflareJsonModel(null, null)
            return CloudflareJsonModel(config, HttpClient(OkHttp) {
                followRedirects = false
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = minOf(3_000, config.policy.timeoutMillis)
                    requestTimeoutMillis = config.policy.timeoutMillis
                    socketTimeoutMillis = config.policy.timeoutMillis
                }
                engine { config {
                    followRedirects(false)
                    followSslRedirects(false)
                    retryOnConnectionFailure(false)
                    fastFallback(false)
                    proxy(Proxy.NO_PROXY)
                    socketFactory(ModelDirectSocketFactory(minOf(3_000, config.policy.timeoutMillis).toInt()))
                    cookieJar(CookieJar.NO_COOKIES)
                    cache(null)
                    authenticator(Authenticator.NONE)
                    proxyAuthenticator(Authenticator.NONE)
                    eventListener(EventListener.NONE)
                    connectionPool(ConnectionPool())
                    dispatcher(Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
                    // Stock TLS/hostname verification. Also reject implicit 408/503/421 follow-ups.
                    addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                        .tag(ModelAttempt::class.java, ModelAttempt()).build()) }
                    addNetworkInterceptor { chain ->
                        val attempt = chain.request().tag(ModelAttempt::class.java) ?: throw IOException("model_attempt_missing")
                        if (!attempt.started.compareAndSet(false, true)) throw IOException("model_follow_up_rejected")
                        chain.proceed(chain.request())
                    }
                } }
            })
        }

        /** Internal MockEngine seam, never selected by runtime configuration. The caller owns engine. */
        internal fun forTests(configuration: CloudflareJsonConfiguration?, engine: HttpClientEngine): CloudflareJsonModel {
            val config = configuration?.takeIf { it.valid() } ?: return CloudflareJsonModel(null, null)
            return CloudflareJsonModel(config, HttpClient(engine) { followRedirects = false; expectSuccess = false })
        }

        private fun request(system: String, input: JsonObject, schema: JsonObject, policy: CloudflareJsonPolicy): ByteArray {
            require(system.isNotBlank() && system.length <= policy.maxRequestBytes)
            // Bound traversal before serializing caller-owned trees (including cyclic backing maps).
            boundedTree(input, policy.maxRequestBytes)
            boundedTree(schema, policy.maxRequestBytes)
            require((schema["type"] as? JsonPrimitive)?.let { it.isString && it.content == "object" } == true)
            val inputText = WireDocument.parse(input.toString(), WireLimits(policy.maxRequestBytes, 24)).encodeUtf8().decodeToString()
            val schemaCopy = Json.parseToJsonElement(WireDocument.parse(schema.toString(), WireLimits(policy.maxRequestBytes, 24)).encodeUtf8().decodeToString())
            val document = buildJsonObject {
                put("model", MODEL)
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", system) })
                    add(buildJsonObject { put("role", "user"); put("content", inputText) })
                })
                put("response_format", buildJsonObject { put("type", "json_schema"); put("json_schema", schemaCopy) })
                put("stream", false)
                put("max_tokens", policy.maxOutputTokens)
                put("temperature", 0)
            }
            return WireDocument.parse(document.toString(), WireLimits(policy.maxRequestBytes, 32)).encodeUtf8()
        }

        private fun boundedTree(value: JsonElement, maximum: Int) {
            var budget = maximum
            fun visit(node: JsonElement, depth: Int) {
                require(depth <= 24 && --budget >= 0)
                when (node) {
                    is JsonObject -> node.forEach { (key, child) -> budget -= key.length; require(budget >= 0); visit(child, depth + 1) }
                    is JsonArray -> node.forEach { visit(it, depth + 1) }
                    is JsonPrimitive -> { budget -= node.content.length; require(budget >= 0) }
                }
            }
            visit(value, 0)
        }

        private fun parseResponse(bytes: ByteArray, limit: Int): JsonObject = try {
            fun json(raw: ByteArray): JsonElement = Json.parseToJsonElement(WireDocument.decode(raw, WireLimits(limit, 32)).encodeUtf8().decodeToString())
            val envelope = json(bytes) as? JsonObject ?: throw InvalidModelResponse()
            validResponse(envelope["error"] in listOf(null, JsonNull) && envelope["errors"] in listOf(null, JsonNull))
            val choices = envelope["choices"] as? JsonArray ?: throw InvalidModelResponse()
            validResponse(choices.size == 1)
            val choice = choices.single() as? JsonObject ?: throw InvalidModelResponse()
            validResponse(choice["index"] == JsonPrimitive(0) && choice["finish_reason"] == JsonPrimitive("stop"))
            val message = choice["message"] as? JsonObject ?: throw InvalidModelResponse()
            validResponse(message["role"] == JsonPrimitive("assistant"))
            for (name in listOf("refusal", "tool_calls", "function_call")) validResponse(message[name] in listOf(null, JsonNull))
            val content = (message["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw InvalidModelResponse()
            json(content.encodeToByteArray(throwOnInvalidSequence = true)) as? JsonObject ?: throw InvalidModelResponse()
        } catch (_: Exception) { throw InvalidModelResponse() }

        private fun validResponse(condition: Boolean) { if (!condition) throw InvalidModelResponse() }
        private fun unavailable(reason: HostedJsonFailure) = HostedJsonResult.Unavailable(reason)
    }
}

private class InvalidModelResponse : IllegalArgumentException("Invalid hosted JSON response")
private class ModelAttempt { val started = AtomicBoolean() }
private class ModelDirectSocketFactory(private val timeoutMillis: Int) : SocketFactory() {
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
