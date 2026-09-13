package com.feedme.session

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.StorageScope
import com.feedme.storage.StateRetirementTarget
import kotlinx.serialization.json.*

/** Private owner record, not credentials or proof that the current native incarnations match. */
internal class SessionActivationRecord(
    val scope: StorageScope,
    val credentialIncarnation: String,
    val originBinding: String,
    val dataTarget: StateRetirementTarget,
    val configurationBinding: String,
) {
    override fun toString() = "SessionActivationRecord(<redacted>)"
}

internal class SessionActivationFormatException : Exception("Session activation data unavailable")

/** The runtime must compare the complete target with its current capture, not merely decode it. */
internal object SessionActivationCodec {
    const val MAX_BYTES = 4096
    private const val HEX = "0123456789abcdef"

    fun encode(value: SessionActivationRecord): PrivateBytes = checked {
        if (value.scope.actorKind == ActorKind.DEMO) invalid()
        uuid(value.credentialIncarnation)
        uuid(value.originBinding)
        digest(value.configurationBinding)
        val target = value.dataTarget.copyForStorage()
        val targetHex = try { hex(target) } finally { target.fill(0) }
        val root = buildJsonObject {
            put("version", 1)
            put("scope", buildJsonObject {
                put("environment", value.scope.environment)
                put("actorKind", value.scope.actorKind.name)
                put("actorId", value.scope.actorId)
            })
            put("credentialIncarnation", value.credentialIncarnation)
            put("originBinding", value.originBinding)
            put("dataTarget", targetHex)
            put("configurationBinding", value.configurationBinding)
        }
        val bytes = root.toString().encodeToByteArray(throwOnInvalidSequence = true)
        try {
            if (bytes.size > MAX_BYTES) invalid()
            PrivateBytes(bytes).also { decode(it) }
        } finally { bytes.fill(0) }
    }

    fun decode(bytes: PrivateBytes): SessionActivationRecord = checked {
        val root = document(bytes)
        exact(root, setOf("version", "scope", "credentialIncarnation", "originBinding", "dataTarget", "configurationBinding"))
        val version = root["version"] as? JsonPrimitive ?: invalid()
        if (version.isString || version.content != "1") invalid()
        SessionActivationRecord(
            decodeScope(root["scope"]),
            uuid(string(root["credentialIncarnation"])),
            uuid(string(root["originBinding"])),
            target(string(root["dataTarget"])),
            digest(string(root["configurationBinding"])),
        )
    }

    private fun document(bytes: PrivateBytes): JsonObject {
        val copy = bytes.copyForCodec()
        val document = try { WireDocument.decode(copy, WireLimits(maxBytes = MAX_BYTES, maxDepth = 8)) }
        finally { copy.fill(0) }
        val encoded = document.encodeUtf8()
        return try {
            Json.parseToJsonElement(encoded.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: invalid()
        } finally { encoded.fill(0) }
    }

    private fun decodeScope(value: JsonElement?): StorageScope {
        val scope = value as? JsonObject ?: invalid()
        exact(scope, setOf("environment", "actorKind", "actorId"))
        val kind = ActorKind.entries.firstOrNull { it.name == string(scope["actorKind"]) && it != ActorKind.DEMO } ?: invalid()
        return StorageScope(string(scope["environment"]), kind, string(scope["actorId"]))
    }

    private fun uuid(value: String): String {
        if (value.length != 36) invalid()
        return requireCredentialUuid(value)
    }

    private fun digest(value: String): String {
        if (value.length != 64 || value.any { it !in HEX }) invalid()
        return value
    }

    private fun target(value: String): StateRetirementTarget {
        if (value.length != StateRetirementTarget.ENCODED_SIZE * 2 || value.any { it !in HEX }) invalid()
        val bytes = ByteArray(StateRetirementTarget.ENCODED_SIZE) { index ->
            ((HEX.indexOf(value[index * 2]) shl 4) or HEX.indexOf(value[index * 2 + 1])).toByte()
        }
        return try { StateRetirementTarget(bytes) } finally { bytes.fill(0) }
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 255
            append(HEX[value ushr 4])
            append(HEX[value and 15])
        }
    }

    private fun string(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    private fun exact(value: JsonObject, keys: Set<String>) { if (value.keys != keys) invalid() }
    private inline fun <T> checked(action: () -> T): T = try { action() }
    catch (_: Exception) { invalid() }
    private fun invalid(): Nothing = throw SessionActivationFormatException()
}
