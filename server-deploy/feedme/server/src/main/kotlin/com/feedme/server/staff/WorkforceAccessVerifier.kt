package com.feedme.server.staff

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.server.auth.KtorSupabaseJwksExchange
import com.feedme.server.auth.SupabaseJwksExchange
import com.feedme.server.auth.SupabaseJwksHttpPolicy
import com.feedme.server.auth.SupabaseJwksHttpResponse
import com.feedme.server.auth.SupabaseJwksSnapshot
import com.feedme.server.auth.SupabaseJwksSource
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import java.math.BigInteger
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

enum class WorkforceSigningAlgorithm { ES256, RS256 }

/** Explicit workforce profile; neither consumer configuration nor JWT claims select it.
 * RFC 9068 access-token typing and RFC 8176's explicit mfa assertion are mandatory here.
 * An accepted acr is an additional constraint, never an alternative to amr=mfa.
 */
class WorkforceAccessConfiguration(
    val issuer: String,
    val jwksEndpoint: URI,
    val consumerIssuer: String,
    val audience: String,
    val clientId: String,
    algorithms: Set<WorkforceSigningAlgorithm>,
    val maximumTokenLifetimeSeconds: Long,
    val maximumAuthenticationAgeSeconds: Long,
    val maximumJwksAgeSeconds: Long,
    val allowedFutureClockSkewSeconds: Long,
    acceptedAcr: Set<String> = emptySet(),
) {
    private val retainedAlgorithms = algorithms.toSet()
    private val retainedAcr = acceptedAcr.toSet()
    val algorithms: Set<WorkforceSigningAlgorithm> get() = retainedAlgorithms.toSet()
    val acceptedAcr: Set<String> get() = retainedAcr.toSet()
    init {
        require(workforceHttps(issuer) && workforceHttps(consumerIssuer) && issuer != consumerIssuer &&
            workforceHttps(jwksEndpoint.toASCIIString()) && retainedAlgorithms.isNotEmpty() &&
            workforceText(audience, 256) && audience != "authenticated" && workforceText(clientId, 256) &&
            maximumTokenLifetimeSeconds in 1..900 && maximumAuthenticationAgeSeconds in 1..600 &&
            maximumJwksAgeSeconds in 1..600 && allowedFutureClockSkewSeconds in 0..60 &&
            retainedAcr.size <= 16 && retainedAcr.all { workforceText(it, 256) }) {
            "Invalid workforce verification configuration"
        }
    }
    override fun toString() = "WorkforceAccessConfiguration(<redacted>)"
}

/** Signature-bound identity facts only. No staff UUID, membership, role, editorial approval,
 * publication permission, provider-session revocation check or replay consumption is implied.
 * The actual operation must bind its configured policy and recheck validUntil using DB time.
 */
class VerifiedWorkforceSubject internal constructor(
    val issuer: String, val subject: String, val clientId: String, val audience: String,
    val issuedAt: Instant, val expiresAt: Instant, val authenticatedAt: Instant,
    val validUntil: Instant, val tokenId: String,
) {
    override fun toString() = "VerifiedWorkforceSubject(<redacted>)"
}

/** SupabaseJwksSource/Snapshot are reused solely as detached public-byte/endpoint containers.
 * No Supabase claim parser, issuer convention, audience or accepting authority is reused.
 * The caller owns its configured source; use HttpsWorkforceJwksSource for production I/O.
 */
class WorkforceAccessVerifier(private val configuration: WorkforceAccessConfiguration,
    private val keys: SupabaseJwksSource, private val clock: Clock) {
    suspend fun verify(token: SecretText): PortResult<VerifiedWorkforceSubject> {
        currentCoroutineContext().ensureActive()
        val parsed = try { token.use(::parse) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return denied() }
        val start = try { clock.instant() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return unavailable() }
        val snapshot = try {
            when (val result = keys.load(configuration.jwksEndpoint)) {
                is PortResult.Value -> result.value
                is PortResult.Failure -> return unavailable()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
          catch (_: Exception) { return unavailable() }
        currentCoroutineContext().ensureActive()
        val loaded = try { clock.instant() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return unavailable() }
        if (loaded < start || !current(snapshot, loaded)) return unavailable()
        val published = try { publicKeys(snapshot) } catch (_: Exception) { return unavailable() }
        val key = published.singleOrNull { it.keyID == parsed.keyId && it.algorithm?.name == parsed.algorithm.name }
            ?: return denied()
        val signed = try {
            when (key) {
                is ECKey -> parsed.jws.verify(ECDSAVerifier(key.toECPublicKey()))
                is RSAKey -> parsed.jws.verify(RSASSAVerifier(key.toRSAPublicKey()))
                else -> false
            }
        } catch (_: Exception) { false }
        if (!signed) return denied()
        currentCoroutineContext().ensureActive()
        val finished = try { clock.instant() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return unavailable() }
        if (finished < loaded || !current(snapshot, finished)) return unavailable()
        return try { PortResult.Value(claims(parsed.payload, finished)) }
            catch (_: Exception) { denied() }
    }

    private class Parsed(val jws: JWSObject, val payload: ByteArray, val keyId: String,
        val algorithm: WorkforceSigningAlgorithm)
    private fun parse(raw: String): Parsed {
        require(raw.length in 1..16_384 && raw.all { it.code in 33..126 })
        val parts = raw.split('.'); require(parts.size == 3)
        val header = workforceObject(workforceBase64(parts[0], 2_048), 2_048)
        require(header.keys == setOf("alg", "typ", "kid") &&
            header.workforceString("typ") in setOf("at+jwt", "application/at+jwt"))
        val algorithm = WorkforceSigningAlgorithm.valueOf(header.workforceString("alg"))
        require(algorithm in configuration.algorithms)
        val id = header.workforceString("kid"); require(id.matches(KEY_ID))
        val payload = workforceBase64(parts[1], 12_288)
        workforceObject(payload, 12_288) // Duplicate/malformed claims are rejected before key I/O too.
        val signature = workforceBase64(parts[2], 512)
        require(if (algorithm == WorkforceSigningAlgorithm.ES256) signature.size == 64 else signature.size in 256..512)
        return Parsed(JWSObject.parse(raw), payload, id, algorithm)
    }

    private fun current(snapshot: SupabaseJwksSnapshot, now: Instant): Boolean = try {
        snapshot.endpoint.toASCIIString() == configuration.jwksEndpoint.toASCIIString() &&
            snapshot.fetchedAt <= now && now < snapshot.fetchedAt.plusSeconds(configuration.maximumJwksAgeSeconds)
    } catch (_: Exception) { false }

    private fun publicKeys(snapshot: SupabaseJwksSnapshot): List<JWK> {
        val document = workforceObject(snapshot.bytes(), 65_536)
        require(document.keys == setOf("keys"))
        val values = document.getValue("keys") as? JsonArray ?: error("Invalid public keys")
        require(values.size in 1..8)
        val keys = values.map { value ->
            val fields = value as? JsonObject ?: error("Invalid public key")
            val parameters = when (fields.workforceString("kty")) {
                "EC" -> setOf("crv", "x", "y")
                "RSA" -> setOf("n", "e")
                else -> error("Unsupported public key")
            }
            require(fields.keys.all { it in parameters + setOf("kty", "kid", "alg", "use", "key_ops", "ext") })
            if (fields.containsKey("ext")) require(fields["ext"] == JsonPrimitive(true) || fields["ext"] == JsonPrimitive(false))
            if (fields.containsKey("use")) require(fields.workforceString("use") == "sig")
            if (fields.containsKey("key_ops")) require(fields["key_ops"] == JsonArray(listOf(JsonPrimitive("verify"))))
            when (fields.workforceString("kty")) {
                "EC" -> for (coordinate in listOf("x", "y")) require(workforceBase64(fields.workforceString(coordinate), 32).size == 32)
                "RSA" -> {
                    val n = workforceBase64(fields.workforceString("n"), 512)
                    val e = workforceBase64(fields.workforceString("e"), 4)
                    require(n.isNotEmpty() && n.first() != 0.toByte() && e.isNotEmpty() && e.first() != 0.toByte())
                    require(BigInteger(1, e).let { it >= BigInteger.valueOf(3) && it.testBit(0) })
                }
            }
            val key = JWK.parse(fields.toString())
            require(!key.isPrivate && key.keyID?.matches(KEY_ID) == true &&
                (key.keyUse == null || key.keyUse == KeyUse.SIGNATURE) &&
                (key.keyOperations == null || key.keyOperations == setOf(KeyOperation.VERIFY)) &&
                (key.keyUse == KeyUse.SIGNATURE || key.keyOperations == setOf(KeyOperation.VERIFY)) &&
                key.algorithm?.name in configuration.algorithms.map { it.name })
            when (key) {
                is ECKey -> require(key.algorithm?.name == "ES256" && key.curve == Curve.P_256)
                is RSAKey -> require(key.algorithm?.name == "RS256" && key.toRSAPublicKey().modulus.bitLength() in 2048..4096)
                else -> error("Unsupported public key")
            }
            key
        }
        require(keys.map { it.keyID }.distinct().size == keys.size)
        return keys
    }

    private fun claims(bytes: ByteArray, now: Instant): VerifiedWorkforceSubject {
        val body = workforceObject(bytes, 12_288)
        require(body.workforceString("iss") == configuration.issuer && body.workforceString("client_id") == configuration.clientId)
        val audience = when (val value = body.getValue("aud")) {
            is JsonPrimitive -> listOf(value.takeIf { it.isString }?.content ?: error("Invalid audience"))
            is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("Invalid audience") }
            else -> error("Invalid audience")
        }
        require(audience == listOf(configuration.audience))
        val subject = body.workforceString("sub"); val tokenId = body.workforceString("jti")
        require(workforceText(subject, 256) && workforceText(tokenId, 256))
        val issued = body.workforceInstant("iat"); val expires = body.workforceInstant("exp")
        val authenticated = body.workforceInstant("auth_time")
        val latest = now.plusSeconds(configuration.allowedFutureClockSkewSeconds)
        require(issued <= latest && authenticated <= issued && authenticated <= now && expires > now && expires > issued &&
            expires <= issued.plusSeconds(configuration.maximumTokenLifetimeSeconds))
        if (body.containsKey("nbf")) require(body.workforceInstant("nbf").let { it <= latest && it < expires })
        val methods = body.getValue("amr") as? JsonArray ?: error("Invalid authentication methods")
        require(methods.size in 1..16)
        val names = methods.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("Invalid method") }
        require(names.all { workforceText(it, 128) } && names.distinct().size == names.size && "mfa" in names)
        if (body.containsKey("acr")) require(workforceText(body.workforceString("acr"), 256))
        if (configuration.acceptedAcr.isNotEmpty()) require(body.workforceString("acr") in configuration.acceptedAcr)
        val validUntil = minOf(expires, authenticated.plusSeconds(configuration.maximumAuthenticationAgeSeconds))
        require(now < validUntil)
        return VerifiedWorkforceSubject(configuration.issuer, subject, configuration.clientId, configuration.audience,
            issued, expires, authenticated, validUntil, tokenId)
    }
    override fun toString() = "WorkforceAccessVerifier(<redacted>)"
    private fun denied() = PortResult.Failure(FailureReason.UNAUTHENTICATED)
    private fun unavailable() = PortResult.Failure(FailureReason.UNAVAILABLE)
    private companion object { val KEY_ID = Regex("[A-Za-z0-9_.-]{1,128}") }
}

/** Explicit resource policy; this source deliberately has no positive or stale cache. */
class WorkforceJwksHttpPolicy(val connectTimeoutMillis: Long, val socketTimeoutMillis: Long,
    val totalTimeoutMillis: Long, val minimumFetchIntervalMillis: Long, val maximumAdmittedCalls: Int) {
    internal val enginePolicy = SupabaseJwksHttpPolicy(connectTimeoutMillis, socketTimeoutMillis,
        totalTimeoutMillis, 1, minimumFetchIntervalMillis, maximumAdmittedCalls)
    override fun toString() = "WorkforceJwksHttpPolicy(<redacted>)"
}

/** Real bounded HTTPS transport, not a test-only acceptance seam. The shared exchange supplies
 * stock TLS/hostname checks, exact GET, no redirects/retries/proxies/cookies, and 64 KiB streaming.
 * One explicit endpoint is allowed; token-controlled URLs never reach it. Nothing is cached.
 */
class HttpsWorkforceJwksSource private constructor(private val configuration: WorkforceAccessConfiguration,
    private val policy: WorkforceJwksHttpPolicy, private val clock: Clock, private val nanos: () -> Long,
    private val exchange: SupabaseJwksExchange) : SupabaseJwksSource, AutoCloseable {
    private val closed = AtomicBoolean()
    private val mutex = Mutex()
    private val admitted = Semaphore(policy.maximumAdmittedCalls)
    private var lastWall: Instant? = null
    private var lastNanos: Long? = null
    private var lastAttempt: Long? = null
    override suspend fun load(exactEndpoint: URI): PortResult<SupabaseJwksSnapshot> {
        if (closed.get() || exactEndpoint.toASCIIString() != configuration.jwksEndpoint.toASCIIString() || !admitted.tryAcquire())
            return unavailable()
        return try {
            withTimeoutOrNull(policy.totalTimeoutMillis) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (closed.get()) return@withLock unavailable()
                    val start = observe()
                    if (lastAttempt?.let { (start.second - it) / 1_000_000 < policy.minimumFetchIntervalMillis } == true)
                        return@withLock unavailable()
                    lastAttempt = start.second
                    val response = when (val result = exchange.get(configuration.jwksEndpoint)) {
                        is PortResult.Value -> result.value
                        is PortResult.Failure -> return@withLock unavailable()
                    }
                    currentCoroutineContext().ensureActive()
                    val end = observe()
                    if (closed.get()) return@withLock unavailable()
                    val snapshot = validate(response, start.first, end.first)
                    currentCoroutineContext().ensureActive()
                    if (closed.get()) unavailable() else PortResult.Value(snapshot)
                }
            } ?: unavailable()
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
          catch (_: Exception) { unavailable() }
        finally { admitted.release() }
    }
    private fun observe(): Pair<Instant, Long> {
        val wall = clock.instant(); val mono = nanos()
        check(lastWall?.let { wall >= it } != false && lastNanos?.let { mono - it >= 0 } != false)
        lastWall = wall; lastNanos = mono
        return wall to mono
    }
    private fun validate(response: SupabaseJwksHttpResponse, start: Instant, end: Instant): SupabaseJwksSnapshot {
        check(response.endpoint.toASCIIString() == configuration.jwksEndpoint.toASCIIString() && response.status == 200)
        val headers = response.headers
        check(headers.size <= 64 && headers.entries.sumOf { it.key.length + it.value.sumOf(String::length) } <= 16_384 &&
            headers.keys.map { it.lowercase() }.distinct().size == headers.size)
        fun header(name: String): String? {
            val values = headers.entries.singleOrNull { it.key.equals(name, true) }?.value ?: return null
            check(values.size == 1)
            return values.single().also { check(it.length in 1..1024 && it.none(Char::isISOControl)) }
        }
        check(header("Content-Type")?.matches(JSON_MEDIA) == true && header("Content-Encoding")?.lowercase() in setOf(null, "identity"))
        val bytes = response.bytes()
        check(bytes.size in 1..65_536)
        header("Content-Length")?.let { check(workforceNumber(it) == bytes.size.toLong()) }
        val document = workforceObject(bytes, 65_536)
        val keyArray = document["keys"] as? JsonArray ?: error("Invalid public keys")
        check(document.keys == setOf("keys") && keyArray.size in 1..8)
        val age = header("Age")?.let(::workforceNumber) ?: 0L
        check(age <= configuration.maximumJwksAgeSeconds)
        val date = header("Date")?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
        val delay = Duration.between(start, end)
        check(!delay.isNegative && delay.toMillis() <= policy.totalTimeoutMillis)
        val fetchedAt = minOf(start, minOf(end.minusSeconds(age), date ?: end).minus(delay))
        check(fetchedAt <= end && end < fetchedAt.plusSeconds(configuration.maximumJwksAgeSeconds))
        return SupabaseJwksSnapshot(configuration.jwksEndpoint, fetchedAt, bytes)
    }
    override fun close() { if (closed.compareAndSet(false, true)) exchange.close() }
    override fun toString() = "HttpsWorkforceJwksSource(<redacted>)"
    private fun unavailable() = PortResult.Failure(FailureReason.UNAVAILABLE)
    companion object {
        fun create(configuration: WorkforceAccessConfiguration, policy: WorkforceJwksHttpPolicy, clock: Clock) =
            HttpsWorkforceJwksSource(configuration, policy, clock, System::nanoTime, KtorSupabaseJwksExchange.create(policy.enginePolicy))
        internal fun forExchange(configuration: WorkforceAccessConfiguration, policy: WorkforceJwksHttpPolicy,
            clock: Clock, nanos: () -> Long, exchange: SupabaseJwksExchange) =
            HttpsWorkforceJwksSource(configuration, policy, clock, nanos, exchange)
        private val JSON_MEDIA = Regex("application/(?:json|jwk-set\\+json)(?: *; *charset *= *(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
    }
}

private fun workforceHttps(value: String): Boolean = try {
    val uri = URI(value)
    value.length in 1..2_048 && uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.host == uri.host.lowercase() &&
        uri.port in setOf(-1, 443) && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.toASCIIString() == value && uri.normalize() == uri
} catch (_: Exception) { false }
private fun workforceText(value: String, maximum: Int) = value.isNotBlank() && value.length <= maximum && value.none(Char::isISOControl)
private fun workforceObject(bytes: ByteArray, maximum: Int): JsonObject =
    Json.parseToJsonElement(WireDocument.decode(bytes, WireLimits(maximum, 16, 20)).encodeUtf8().decodeToString()) as? JsonObject
        ?: error("Object required")
private fun JsonObject.workforceString(name: String): String =
    (getValue(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("String required")
private fun workforceNumber(value: String): Long {
    require(value.matches(Regex("0|[1-9][0-9]{0,18}")))
    return value.toLong()
}
private fun JsonObject.workforceInstant(name: String): Instant {
    val value = getValue(name) as? JsonPrimitive ?: error("Time required")
    require(!value.isString)
    return Instant.ofEpochSecond(workforceNumber(value.content))
}
private fun workforceBase64(value: String, maximum: Int): ByteArray {
    require(value.length <= (maximum * 4 + 2) / 3 && value.matches(Regex("[A-Za-z0-9_-]+")))
    val bytes = Base64.getUrlDecoder().decode(value)
    require(bytes.size <= maximum && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
    return bytes
}
