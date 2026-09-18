package com.feedme.server.auth

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

enum class SupabaseSigningAlgorithm { ES256, RS256 }

/** Explicit user-token profile; no project, secret, clock-skew or algorithm accepting defaults. */
class SupabaseUserAccessConfiguration(
    val issuer: String,
    val jwksEndpoint: URI,
    val audience: String,
    algorithms: Set<SupabaseSigningAlgorithm>,
    val maximumTokenLifetimeSeconds: Long,
    val allowedFutureClockSkewSeconds: Long,
    val maximumJwksAgeSeconds: Long,
) {
    private val accepted = algorithms.toSet()
    val algorithms: Set<SupabaseSigningAlgorithm> get() = accepted.toSet()
    init {
        val origin = try { URI(issuer) } catch (_: Exception) { throw IllegalArgumentException("Invalid Supabase verification configuration") }
        require(origin.scheme == "https" && !origin.host.isNullOrEmpty() && origin.host == origin.host.lowercase() &&
            origin.rawPath == "/auth/v1" && origin.rawQuery == null && origin.rawFragment == null && origin.rawUserInfo == null &&
            origin.port in setOf(-1, 443) && origin.toASCIIString() == issuer &&
            jwksEndpoint.toASCIIString() == "$issuer/.well-known/jwks.json" &&
            audience == "authenticated" && accepted.isNotEmpty() &&
            maximumTokenLifetimeSeconds in 60..86_400 && allowedFutureClockSkewSeconds in 0..60 && maximumJwksAgeSeconds in 1..600) {
            "Invalid Supabase verification configuration"
        }
    }
    override fun toString() = "SupabaseUserAccessConfiguration(<redacted>)"
}

/** Public keys only; source must fetch from the exact configured HTTPS URI, with no redirects.
 * The trusted source owns bounded network/cache/concurrency policy and must never serve stale
 * material as newly fetched. No source implementation or default is installed by this foundation.
 */
fun interface SupabaseJwksSource {
    suspend fun load(exactEndpoint: URI): PortResult<SupabaseJwksSnapshot>
}

class SupabaseJwksSnapshot(val endpoint: URI, val fetchedAt: Instant, document: ByteArray) {
    private val bytes = document.copyOf().also { require(it.size in 1..65_536) { "Invalid public key document size" } }
    internal fun bytes() = bytes.copyOf()
    override fun toString() = "SupabaseJwksSnapshot(<redacted>)"
}

class SupabaseAuthenticationMethod internal constructor(val method: String, val atEpochSeconds: Long) {
    override fun toString() = "SupabaseAuthenticationMethod(<redacted>)"
}

/** Signature/claim facts only. NOT a FeedMe account/device, confirmed email, current provider
 * session, object permission, eligible profile or authorization to enter the main application.
 * Provider session_id is deliberately distinct from the X-Device-Session header.
 */
class VerifiedSupabaseSubject internal constructor(
    val issuer: String,
    val subject: UUID,
    val providerSessionId: UUID,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val assuranceLevel: String,
    methods: List<SupabaseAuthenticationMethod>?,
) {
    private val retainedMethods = methods?.toList()
    val authenticationMethods: List<SupabaseAuthenticationMethod>? get() = retainedMethods?.toList()
    override fun toString() = "VerifiedSupabaseSubject(<redacted>)"
}

/** No decoded claim is returned before Nimbus validates the original compact JWS signature.
 * Keys can only come from the required configured source, never jku/jwk/x5u/token issuer URLs.
 * No HTTP verifier adapter or Main route configuration is installed here.
 */
class SupabaseUserAccessVerifier(
    private val configuration: SupabaseUserAccessConfiguration,
    private val keys: SupabaseJwksSource,
    private val clock: Clock,
) {
    /** Dependency evidence only. Uses the exact same key validation as token verification;
     * never creates a subject, authenticates a caller or grants account eligibility. */
    internal suspend fun keyMaterialAvailable(): Boolean {
        return try {
            val started = clock.instant()
            val snapshot = when (val loaded = keys.load(configuration.jwksEndpoint)) {
                is PortResult.Value -> loaded.value
                is PortResult.Failure -> return false
            }
            val now = clock.instant()
            now >= started && current(snapshot, now) && publicKeys(snapshot).isNotEmpty()
        } catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { false }
    }

    suspend fun verify(token: SecretText): PortResult<VerifiedSupabaseSubject> {
        val parsed = try { token.use(::parseCompact) } catch (_: Exception) { return denied() }
        val started = try { clock.instant() } catch (_: Exception) { return unavailable() }
        val snapshot = try {
            when (val loaded = keys.load(configuration.jwksEndpoint)) {
                is PortResult.Value -> loaded.value
                is PortResult.Failure -> return if (loaded.reason == FailureReason.NOT_CONFIGURED) loaded else unavailable()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return unavailable() }
        val loadedAt = try { clock.instant() } catch (_: Exception) { return unavailable() }
        if (loadedAt < started || !current(snapshot, loadedAt)) return unavailable()
        val published = try { publicKeys(snapshot) } catch (_: Exception) { return unavailable() }
        val key = published.singleOrNull { it.keyID == parsed.keyId && it.algorithm?.name == parsed.algorithm.name } ?: return denied()
        val signatureValid = try {
            when (key) {
                is ECKey -> parsed.jws.verify(ECDSAVerifier(key.toECPublicKey()))
                is RSAKey -> parsed.jws.verify(RSASSAVerifier(key.toRSAPublicKey()))
                else -> false
            }
        } catch (_: Exception) { false }
        if (!signatureValid) return denied()
        val finished = try { clock.instant() } catch (_: Exception) { return unavailable() }
        if (finished < loadedAt || !current(snapshot, finished)) return unavailable()
        return try { PortResult.Value(verifiedClaims(parsed.payload, finished.epochSecond)) }
        catch (_: Exception) { denied() }
    }

    private class Parsed(val jws: JWSObject, val payload: ByteArray, val keyId: String, val algorithm: SupabaseSigningAlgorithm)
    private fun parseCompact(raw: String): Parsed {
        require(raw.length in 1..16_384 && raw.all { it.code in 33..126 })
        val parts = raw.split('.'); require(parts.size == 3 && parts.all { it.isNotEmpty() })
        val header = jsonObject(decode(parts[0], 2_048), 2_048)
        require(header.keys == setOf("alg", "typ", "kid") && header.text("typ") == "JWT")
        val algorithm = SupabaseSigningAlgorithm.valueOf(header.text("alg"))
        require(algorithm in configuration.algorithms)
        val kid = header.text("kid"); require(kid.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
        val payload = decode(parts[1], 12_288); decode(parts[2], 1_024)
        return Parsed(JWSObject.parse(raw), payload, kid, algorithm)
    }

    private fun current(snapshot: SupabaseJwksSnapshot, now: Instant): Boolean = try {
        snapshot.endpoint.toASCIIString() == configuration.jwksEndpoint.toASCIIString() &&
            snapshot.fetchedAt <= now && now < snapshot.fetchedAt.plusSeconds(configuration.maximumJwksAgeSeconds)
    } catch (_: Exception) { false }

    private fun publicKeys(snapshot: SupabaseJwksSnapshot): List<JWK> {
        val document = jsonObject(snapshot.bytes(), 65_536)
        require(document.keys == setOf("keys"))
        val array = document.getValue("keys") as? JsonArray ?: error("Invalid key set")
        require(array.size in 1..8)
        val result = array.map { raw ->
            val fields = raw as? JsonObject ?: error("Invalid key")
            val publicParameters = when (fields.text("kty")) {
                "EC" -> setOf("crv", "x", "y")
                "RSA" -> setOf("n", "e")
                else -> error("Unsupported public key")
            }
            require(fields.keys.all { it in publicParameters + setOf("kty", "kid", "alg", "use", "key_ops") })
            if (fields.containsKey("use")) require(fields.text("use") == "sig")
            if (fields.containsKey("key_ops")) require(fields.getValue("key_ops") == JsonArray(listOf(JsonPrimitive("verify"))))
            val key = JWK.parse(fields.toString())
            require(!key.isPrivate && (key.keyUse == null || key.keyUse == KeyUse.SIGNATURE) &&
                (key.keyOperations == null || key.keyOperations == setOf(KeyOperation.VERIFY)) &&
                (key.keyUse == KeyUse.SIGNATURE || key.keyOperations == setOf(KeyOperation.VERIFY)) &&
                key.keyID?.matches(Regex("[A-Za-z0-9_.-]{1,128}")) == true &&
                key.algorithm?.name in configuration.algorithms.map { it.name })
            when (key) {
                is ECKey -> require(key.algorithm?.name == "ES256" && key.curve == Curve.P_256)
                is RSAKey -> require(key.algorithm?.name == "RS256" && key.toRSAPublicKey().modulus.bitLength() in 2048..4096)
                else -> error("Unsupported public key")
            }
            key
        }
        require(result.map { it.keyID }.toSet().size == result.size)
        return result
    }

    private fun verifiedClaims(bytes: ByteArray, now: Long): VerifiedSupabaseSubject {
        val c = jsonObject(bytes, 12_288)
        require(c.text("iss") == configuration.issuer && c.text("role") == "authenticated")
        val audience = when (val a = c.getValue("aud")) {
            is JsonPrimitive -> listOf(a.takeIf { it.isString }?.content ?: error("Invalid audience"))
            is JsonArray -> a.map { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content ?: error("Invalid audience") }
            else -> error("Invalid audience")
        }
        require(audience == listOf(configuration.audience))
        require(c.getValue("is_anonymous") == JsonPrimitive(false))
        require(c.keys.none { it in setOf("ref", "token_use") })
        val subject = uuid(c.text("sub")); val session = uuid(c.text("session_id"))
        val iat = c.seconds("iat"); val exp = c.seconds("exp")
        require(iat <= Math.addExact(now, configuration.allowedFutureClockSkewSeconds) && exp > now && exp > iat &&
            exp - iat <= configuration.maximumTokenLifetimeSeconds)
        if (c.containsKey("nbf")) require(c.seconds("nbf") <= Math.addExact(now, configuration.allowedFutureClockSkewSeconds) && c.seconds("nbf") < exp)
        val aal = c.text("aal"); require(aal in setOf("aal1", "aal2"))
        c.text("email"); c.text("phone") // Required typed claims, deliberately not confirmation/eligibility proof.
        if (c.containsKey("jti")) c.text("jti")
        for (name in listOf("app_metadata", "user_metadata")) if (c.containsKey(name)) require(c.getValue(name) is JsonObject)
        val methods = if (!c.containsKey("amr")) null else {
            val list = c.getValue("amr") as? JsonArray ?: error("Invalid authentication methods")
            // Supabase documents an optional array without a nonempty requirement. Empty
            // facts cannot establish ordinary sign-in, recent reauthentication or eligibility.
            require(list.size in 0..16)
            list.map { item ->
                val method = item as? JsonObject ?: error("Invalid authentication method")
                require(method.keys == setOf("method", "timestamp"))
                val name = method.text("method"); require(name in knownMethods)
                val timestamp = method.seconds("timestamp"); require(timestamp <= Math.addExact(now, configuration.allowedFutureClockSkewSeconds))
                SupabaseAuthenticationMethod(name, timestamp)
            }
        }
        return VerifiedSupabaseSubject(configuration.issuer, subject, session, iat, exp, aal, methods)
    }

    private fun JsonObject.text(name: String): String =
        (getValue(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid string claim")
    private fun JsonObject.seconds(name: String): Long {
        val primitive = getValue(name) as? JsonPrimitive ?: error("Invalid time claim")
        require(!primitive.isString && primitive.content.matches(Regex("0|[1-9][0-9]{0,18}")))
        return primitive.content.toLong()
    }
    private fun uuid(value: String): UUID {
        require(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
        return UUID.fromString(value)
    }
    private fun jsonObject(bytes: ByteArray, maximumBytes: Int): JsonObject {
        val strict = WireDocument.decode(bytes, WireLimits(maximumBytes, 16, 20))
        return Json.parseToJsonElement(strict.encodeUtf8().decodeToString()) as? JsonObject ?: error("Object required")
    }
    private fun decode(value: String, maximum: Int): ByteArray {
        require(value.matches(Regex("[A-Za-z0-9_-]+")))
        val bytes = Base64.getUrlDecoder().decode(value)
        require(bytes.size <= maximum && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
        return bytes
    }
    private fun denied() = PortResult.Failure(FailureReason.UNAUTHENTICATED)
    private fun unavailable() = PortResult.Failure(FailureReason.UNAVAILABLE)
    override fun toString() = "SupabaseUserAccessVerifier(<redacted>)"

    private companion object {
        val knownMethods = setOf("oauth", "password", "otp", "totp", "recovery", "invite", "sso/saml", "magiclink", "email/signup", "email_change", "token_refresh", "anonymous")
    }
}
