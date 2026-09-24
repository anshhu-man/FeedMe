package com.feedme.server.media.processing

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
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.EventListener

/** One bounded content decision for an already decoded private display derivative.
 * It is not malware evidence, a publication grant or whole-photo safety proof. */
internal fun interface PhotoContentModeration {
    fun assess(display: EncodedPhotoVariant): PhotoContentModerationResult
}

internal enum class PhotoContentCategory {
    CHILD_SAFETY, ADULT_NUDITY, SEXUAL_CONTENT, VIOLENCE, WEAPON, DANGEROUS_ACT,
    SELF_HARM, HATE_SYMBOL, DRUGS, PERSONAL_DATA, NON_FOOD, OTHER_UNSAFE,
}

internal enum class PhotoModerationFailure { NOT_CONFIGURED, RATE_LIMITED, UNAVAILABLE, INVALID_RESPONSE, INVALID_INPUT }

internal sealed interface PhotoContentModerationResult {
    class Approved(val revision: String, val assessedAt: Instant, val observationSha256: String) :
        PhotoContentModerationResult {
        override fun toString() = "PhotoContentModerationResult.Approved(<redacted>)"
    }
    data class Rejected(val category: PhotoContentCategory) : PhotoContentModerationResult
    data class Unavailable(val reason: PhotoModerationFailure) : PhotoContentModerationResult
}

internal data class CloudflarePhotoModerationPolicy(
    val revision: String = "cloudflare-moondream-photo-moderation-v1",
    // Base64 plus fixed JSON must remain inside WireDocument's hard 4 MiB ceiling.
    val maxImageBytes: Int = 2_900_000,
    val maxRequestBytes: Int = 4_194_304,
    val maxResponseBytes: Int = 65_536,
    val maxOutputTokens: Int = 12,
    val timeoutMillis: Long = 10_000,
) {
    init {
        require(revision.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")))
        require(maxImageBytes in 1..2_900_000 && maxRequestBytes in 4_096..4_194_304 &&
            maxRequestBytes > maxImageBytes * 4L / 3L && maxResponseBytes in 1_024..262_144 &&
            maxOutputTokens in 1..32 && timeoutMillis in 1..30_000)
    }
}

/** Backend-only Workers AI configuration. No environment lookup or client asset. */
internal class CloudflarePhotoModerationConfiguration(
    internal val accountId: String,
    internal val apiToken: String,
    internal val billingMode: String,
    internal val freeTierAccountVerified: Boolean,
    val policy: CloudflarePhotoModerationPolicy = CloudflarePhotoModerationPolicy(),
) {
    internal fun valid() = accountId.matches(Regex("[a-fA-F0-9]{32}")) &&
        apiToken.length in 1..4_096 && apiToken.matches(Regex("[A-Za-z0-9._~+/=-]+")) &&
        billingMode == "free-only" && freeTierAccountVerified
    override fun toString() = "CloudflarePhotoModerationConfiguration(<redacted>)"
}

/**
 * Single-attempt, fail-closed Workers AI vision adapter. The model must return one exact
 * allow/reject token; prose, uncertainty, malformed output, quota exhaustion and outages never
 * become approval. No redirects, retries, cookies, logging, paid fallback or response persistence.
 * This supplies content moderation only. A separate concrete malware/CDR decision remains
 * mandatory before it can be composed as [MediaSafetyAssessment].
 */
internal class CloudflarePhotoModeration private constructor(
    private val configuration: CloudflarePhotoModerationConfiguration?,
    private val client: HttpClient?,
    private val clock: Clock,
) : PhotoContentModeration, AutoCloseable {
    private val closed = AtomicBoolean()

    override fun assess(display: EncodedPhotoVariant): PhotoContentModerationResult {
        val config = configuration ?: return unavailable(PhotoModerationFailure.NOT_CONFIGURED)
        val http = client ?: return unavailable(PhotoModerationFailure.NOT_CONFIGURED)
        if (closed.get()) return unavailable(PhotoModerationFailure.NOT_CONFIGURED)
        if (display.variant != PhotoVariant.DISPLAY || display.contentType != "image/png" ||
            display.width !in 1..2_048 || display.height !in 1..2_048 ||
            display.width.toLong() * display.height > 4_194_304 ||
            display.byteCount !in 1..config.policy.maxImageBytes) return unavailable(PhotoModerationFailure.INVALID_INPUT)

        val image = display.copyBytes()
        val request = try { request(image, config.policy) }
            catch (_: Exception) {
                image.fill(0)
                return unavailable(PhotoModerationFailure.INVALID_INPUT)
            }
        val result = try {
            runBlocking {
                withTimeoutOrNull(config.policy.timeoutMillis) {
                    http.preparePost("https://api.cloudflare.com/client/v4/accounts/${config.accountId}/ai/run/$MODEL") {
                        headers.append("Authorization", "Bearer ${config.apiToken}")
                        headers.append("Accept", "application/json")
                        headers.append("Accept-Encoding", "identity")
                        headers.append("Cache-Control", "no-store")
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }.execute { response ->
                        when (response.status.value) {
                            429 -> unavailable(PhotoModerationFailure.RATE_LIMITED)
                            401, 403 -> unavailable(PhotoModerationFailure.NOT_CONFIGURED)
                            200 -> {
                                val entries = response.headers.entries()
                                valid(entries.size <= 64 && entries.sumOf { it.key.length + it.value.sumOf(String::length) } <= 16_384)
                                fun header(name: String): String? {
                                    val values = response.headers.getAll(name) ?: return null
                                    valid(values.size == 1)
                                    return values.single().also { valid(it.length in 1..1_024 && it.none(Char::isISOControl)) }
                                }
                                valid(header("Content-Type")?.matches(JSON_MEDIA) == true)
                                valid(header("Content-Encoding")?.lowercase() in setOf(null, "identity"))
                                val length = header("Content-Length")?.let {
                                    valid(it.matches(Regex("0|[1-9][0-9]{0,18}")))
                                    it.toLongOrNull().also { n -> valid(n != null && n in 1..config.policy.maxResponseBytes.toLong()) }
                                }
                                val body = readBoundedHttpBody(response.bodyAsChannel(), config.policy.maxResponseBytes,
                                    length, { throw InvalidPhotoModerationResponse() }, config.policy.timeoutMillis + 1_000)
                                try { parse(body, display, config.policy.revision, clock.instant()) }
                                finally { body.fill(0) }
                            }
                            else -> unavailable(PhotoModerationFailure.UNAVAILABLE)
                        }
                    }
                } ?: unavailable(PhotoModerationFailure.UNAVAILABLE)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: InvalidPhotoModerationResponse) { unavailable(PhotoModerationFailure.INVALID_RESPONSE) }
          catch (_: Exception) { unavailable(PhotoModerationFailure.UNAVAILABLE) }
        finally {
            image.fill(0)
            request.fill(0)
        }
        return result
    }

    override fun close() { if (closed.compareAndSet(false, true)) client?.close() }
    override fun toString() = "CloudflarePhotoModeration(<redacted>)"

    companion object {
        internal const val MODEL = "@cf/moondream/moondream3.1-9B-A2B"
        private val JSON_MEDIA = Regex("application/json(?: *; *charset *= *(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
        private const val QUESTION = "Classify this private FeedMe social photo. Return exactly one token: allow_food, reject_child_safety, reject_adult_nudity, reject_sexual_content, reject_violence, reject_weapon, reject_dangerous_act, reject_self_harm, reject_hate_symbol, reject_drugs, reject_personal_data, reject_non_food, reject_other_unsafe, or uncertain. allow_food is permitted only for an ordinary food or cooking image with none of the rejected content; ordinary kitchen utensils used normally are not weapons."

        fun create(configuration: CloudflarePhotoModerationConfiguration? = null,
            clock: Clock = Clock.systemUTC()): CloudflarePhotoModeration {
            val config = configuration?.takeIf { it.valid() }
                ?: return CloudflarePhotoModeration(null, null, clock)
            return CloudflarePhotoModeration(config, HttpClient(OkHttp) {
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
                    socketFactory(PhotoModerationDirectSocketFactory(minOf(3_000, config.policy.timeoutMillis).toInt()))
                    cookieJar(CookieJar.NO_COOKIES)
                    cache(null)
                    authenticator(Authenticator.NONE)
                    proxyAuthenticator(Authenticator.NONE)
                    eventListener(EventListener.NONE)
                    connectionPool(ConnectionPool())
                    dispatcher(Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
                    addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                        .tag(PhotoModerationAttempt::class.java, PhotoModerationAttempt()).build()) }
                    addNetworkInterceptor { chain ->
                        val attempt = chain.request().tag(PhotoModerationAttempt::class.java)
                            ?: throw IOException("photo_moderation_attempt_missing")
                        if (!attempt.started.compareAndSet(false, true)) throw IOException("photo_moderation_follow_up_rejected")
                        chain.proceed(chain.request())
                    }
                } }
            }, clock)
        }

        internal fun forTests(configuration: CloudflarePhotoModerationConfiguration?, engine: HttpClientEngine,
            clock: Clock): CloudflarePhotoModeration {
            val config = configuration?.takeIf { it.valid() }
                ?: return CloudflarePhotoModeration(null, null, clock)
            return CloudflarePhotoModeration(config,
                HttpClient(engine) { followRedirects = false; expectSuccess = false }, clock)
        }

        private fun request(image: ByteArray, policy: CloudflarePhotoModerationPolicy): ByteArray {
            require(image.isNotEmpty() && image.size <= policy.maxImageBytes)
            val document = buildJsonObject {
                put("task", "query")
                put("image", "data:image/png;base64,${Base64.getEncoder().encodeToString(image)}")
                put("question", QUESTION)
                put("reasoning", false)
                put("temperature", 0)
                put("top_p", 0.1)
                put("max_tokens", policy.maxOutputTokens)
                put("stream", false)
            }
            return WireDocument.parse(document.toString(), WireLimits(policy.maxRequestBytes, 8)).encodeUtf8()
        }

        private fun parse(bytes: ByteArray, display: EncodedPhotoVariant, revision: String,
            assessedAt: Instant): PhotoContentModerationResult = try {
            val root = Json.parseToJsonElement(WireDocument.decode(bytes, WireLimits(65_536, 16))
                .encodeUtf8().decodeToString()) as? JsonObject ?: throw InvalidPhotoModerationResponse()
            valid(root.keys == setOf("result", "success", "errors", "messages"))
            valid(root["success"] == JsonPrimitive(true))
            valid((root["errors"] as? JsonArray)?.isEmpty() == true &&
                (root["messages"] as? JsonArray)?.isEmpty() == true)
            val result = root["result"] as? JsonObject ?: throw InvalidPhotoModerationResponse()
            valid(result.keys.all { it in setOf("answer", "finish_reason", "metrics", "caption", "points", "objects", "reasoning") })
            valid(result["finish_reason"] == JsonPrimitive("stop"))
            for (key in listOf("caption", "points", "objects", "reasoning"))
                valid(result[key] in listOf(null, JsonNull))
            val answer = (result["answer"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw InvalidPhotoModerationResponse()
            return when (answer) {
                "allow_food" -> PhotoContentModerationResult.Approved(revision, assessedAt,
                    hash(display))
                "reject_child_safety" -> rejected(PhotoContentCategory.CHILD_SAFETY)
                "reject_adult_nudity" -> rejected(PhotoContentCategory.ADULT_NUDITY)
                "reject_sexual_content" -> rejected(PhotoContentCategory.SEXUAL_CONTENT)
                "reject_violence" -> rejected(PhotoContentCategory.VIOLENCE)
                "reject_weapon" -> rejected(PhotoContentCategory.WEAPON)
                "reject_dangerous_act" -> rejected(PhotoContentCategory.DANGEROUS_ACT)
                "reject_self_harm" -> rejected(PhotoContentCategory.SELF_HARM)
                "reject_hate_symbol" -> rejected(PhotoContentCategory.HATE_SYMBOL)
                "reject_drugs" -> rejected(PhotoContentCategory.DRUGS)
                "reject_personal_data" -> rejected(PhotoContentCategory.PERSONAL_DATA)
                "reject_non_food" -> rejected(PhotoContentCategory.NON_FOOD)
                "reject_other_unsafe" -> rejected(PhotoContentCategory.OTHER_UNSAFE)
                "uncertain" -> unavailable(PhotoModerationFailure.INVALID_RESPONSE)
                else -> throw InvalidPhotoModerationResponse()
            }
        } catch (failure: InvalidPhotoModerationResponse) { throw failure }
          catch (_: Exception) { throw InvalidPhotoModerationResponse() }

        private fun rejected(category: PhotoContentCategory) = PhotoContentModerationResult.Rejected(category)
        private fun unavailable(reason: PhotoModerationFailure) = PhotoContentModerationResult.Unavailable(reason)
        private fun valid(condition: Boolean) { if (!condition) throw InvalidPhotoModerationResponse() }
        private fun hash(display: EncodedPhotoVariant): String {
            val bytes = display.copyBytes()
            return try { MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) } }
            finally { bytes.fill(0) }
        }
    }
}

private class InvalidPhotoModerationResponse : IllegalArgumentException("Invalid photo moderation response")
private class PhotoModerationAttempt { val started = AtomicBoolean() }
private class PhotoModerationDirectSocketFactory(private val timeoutMillis: Int) : SocketFactory() {
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
