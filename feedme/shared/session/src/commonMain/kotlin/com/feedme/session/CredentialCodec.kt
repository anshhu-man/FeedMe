package com.feedme.session

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.contracts.CanonicalFormats
import com.feedme.core.ports.*
import kotlinx.serialization.json.*

/** Strict private native protocol, never a network DTO. No fallback, logging or error echo. */
internal object CredentialCodec {
    const val MAX_BYTES = 65_536
    const val MAX_SECRET_BYTES = 16_384

    fun encodeSnapshot(value: CredentialSnapshot): PrivateBytes = checked {
        // Validation while constructing JSON (including strict UTF-8 secret encoding) belongs
        // to this typed format boundary, not to a caller's generic native-storage failure path.
        encoded(buildJsonObject {
            put("version", 1); put("incarnation", value.incarnation); put("revision", value.revision)
            put("credentials", buildJsonObject {
                put("scope", scope(value.scope)); put("expiresAtMillis", value.credentials.expiresAtMillis)
                when (val credentials = value.credentials) {
                    is StoredCredentials.Account -> {
                        put("kind", "account"); put("accessToken", secret(credentials.accessToken))
                        put("refreshToken", credentials.refreshToken?.let(::secret) ?: JsonNull)
                        put("deviceSessionId", credentials.deviceSessionId?.let(::secret) ?: JsonNull)
                    }
                    is StoredCredentials.Guest -> {
                        put("kind", "guest"); put("guestSessionId", secret(credentials.guestSessionId))
                        put("guestToken", secret(credentials.guestToken))
                    }
                }
            })
        }).also { decodeSnapshot(it) }
    }

    fun decodeSnapshot(bytes: PrivateBytes): CredentialSnapshot = checked {
        val root = document(bytes)
        exact(root, setOf("version", "incarnation", "revision", "credentials")); version(root)
        val incarnation = requireCredentialUuid(string(root["incarnation"]))
        val revision = integer(root["revision"], minimum = 1)
        val value = root["credentials"] as? JsonObject ?: invalid()
        val owner = decodeScope(value["scope"])
        val expires = integer(value["expiresAtMillis"], minimum = 0)
        val credentials = when (string(value["kind"])) {
            "account" -> {
                exact(value, setOf("scope", "expiresAtMillis", "kind", "accessToken", "refreshToken", "deviceSessionId"))
                if (owner.actorKind != ActorKind.ACCOUNT) invalid()
                val device = nullableSecret(value["deviceSessionId"])
                device?.use { if (!CanonicalFormats.accepts("uuid", it)) invalid() }
                StoredCredentials.Account(owner, decodeSecret(value["accessToken"]), nullableSecret(value["refreshToken"]), expires, device)
            }
            "guest" -> {
                exact(value, setOf("scope", "expiresAtMillis", "kind", "guestSessionId", "guestToken"))
                if (owner.actorKind != ActorKind.GUEST) invalid()
                StoredCredentials.Guest(owner, decodeSecret(value["guestSessionId"]), decodeSecret(value["guestToken"]), expires)
            }
            else -> invalid()
        }
        CredentialSnapshot(incarnation, revision, credentials)
    }

    fun encodeManifest(value: CredentialManifest): PrivateBytes = checked {
        encoded(buildJsonObject {
            put("version", 1); put("revision", value.revision)
            put("scope", value.scope?.let(::scope) ?: JsonNull)
            put("incarnation", value.incarnation?.let(::JsonPrimitive) ?: JsonNull)
        }).also { decodeManifest(it) }
    }

    fun decodeManifest(bytes: PrivateBytes): CredentialManifest = checked {
        val root = document(bytes)
        exact(root, setOf("version", "revision", "scope", "incarnation")); version(root)
        val scope = root["scope"].let { if (it == JsonNull) null else decodeScope(it) }
        val incarnation = root["incarnation"].let { if (it == JsonNull) null else requireCredentialUuid(string(it)) }
        CredentialManifest(integer(root["revision"], 1), scope, incarnation)
    }

    private fun document(bytes: PrivateBytes): JsonObject {
        val copy = bytes.copyForCodec()
        val document = try { WireDocument.decode(copy, WireLimits(maxBytes = MAX_BYTES, maxDepth = 8)) }
        finally { copy.fill(0) }
        val normalized = document.encodeUtf8()
        return try { Json.parseToJsonElement(normalized.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: invalid() }
        finally { normalized.fill(0) }
    }

    private fun scope(value: StorageScope) = buildJsonObject {
        put("environment", value.environment); put("actorKind", value.actorKind.name); put("actorId", value.actorId)
    }
    private fun decodeScope(value: JsonElement?): StorageScope {
        val root = value as? JsonObject ?: invalid()
        exact(root, setOf("environment", "actorKind", "actorId"))
        val kind = ActorKind.entries.firstOrNull { it.name == string(root["actorKind"]) && it != ActorKind.DEMO } ?: invalid()
        return StorageScope(string(root["environment"]), kind, string(root["actorId"]))
    }
    private fun secret(value: SecretText): JsonPrimitive = value.use { text ->
        validateSecret(text); JsonPrimitive(text)
    }
    private fun decodeSecret(value: JsonElement?): SecretText = SecretText(string(value).also(::validateSecret))
    private fun nullableSecret(value: JsonElement?): SecretText? = if (value == JsonNull) null else decodeSecret(value)
    private fun validateSecret(value: String) {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        try { if (bytes.size !in 1..MAX_SECRET_BYTES || value.isBlank() || value.any(Char::isISOControl)) invalid() }
        finally { bytes.fill(0) }
    }
    private fun integer(value: JsonElement?, minimum: Long): Long {
        val primitive = value as? JsonPrimitive ?: invalid()
        if (primitive.isString || !primitive.content.matches(Regex("0|[1-9][0-9]*"))) invalid()
        return primitive.content.toLongOrNull()?.takeIf { it >= minimum } ?: invalid()
    }
    private fun version(value: JsonObject) { if (integer(value["version"], 1) != 1L) invalid() }
    private fun string(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    private fun exact(value: JsonObject, keys: Set<String>) { if (value.keys != keys) invalid() }
    private fun encoded(value: JsonObject): PrivateBytes = checked {
        val bytes = value.toString().encodeToByteArray(throwOnInvalidSequence = true)
        try { if (bytes.size > MAX_BYTES) invalid(); PrivateBytes(bytes) } finally { bytes.fill(0) }
    }
    private inline fun <T> checked(action: () -> T): T = try { action() }
    catch (_: Exception) { invalid() }
    private fun invalid(): Nothing = throw CredentialFormatException()
}
