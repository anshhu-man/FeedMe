package com.feedme.session

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.StorageScope
import kotlinx.serialization.json.*

/** Canonical, bounded private intent. Its proof is verified only by the issuing native work store. */
internal object SessionWorkOriginPlanCodec {
    const val MAX_BYTES = 4096
    const val PROOF_BYTES = 64
    private val KEYS = setOf("version", "expectedRevision", "scope", "origin", "proof")
    private val SCOPE_KEYS = setOf("environment", "actorKind", "actorId")
    private const val HEX = "0123456789abcdef"

    fun encode(record: SessionWorkOriginPlanRecord): PrivateBytes = checked {
        validate(record)
        encoded(buildJsonObject {
            unsignedFields(record)
            val proof = record.proof.copyForCodec()
            try { put("proof", proof.toHex()) } finally { proof.fill(0) }
        })
    }

    /** The native capability adds fixed purpose, owner and record-identity framing. */
    fun encodeUnsigned(record: SessionWorkOriginPlanRecord): PrivateBytes = checked {
        validate(record)
        encoded(buildJsonObject { unsignedFields(record) })
    }

    fun decode(bytes: PrivateBytes): SessionWorkOriginPlanRecord = checked {
        val copy = bytes.copyForCodec()
        val document = try { WireDocument.decode(copy, WireLimits(maxBytes = MAX_BYTES, maxDepth = 3)) }
        finally { copy.fill(0) }
        val normalized = document.encodeUtf8()
        val root = try {
            Json.parseToJsonElement(normalized.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: invalid()
        } finally { normalized.fill(0) }
        if (root.keys != KEYS || integer(root["version"]) != 1L) invalid()
        val scope = root["scope"] as? JsonObject ?: invalid()
        if (scope.keys != SCOPE_KEYS) invalid()
        val kind = ActorKind.entries.firstOrNull { it.name == string(scope["actorKind"]) && it != ActorKind.DEMO } ?: invalid()
        val encodedProof = string(root["proof"])
        if (encodedProof.length != PROOF_BYTES * 2 || encodedProof.any { it !in HEX }) invalid()
        val proof = ByteArray(PROOF_BYTES) { index ->
            ((HEX.indexOf(encodedProof[index * 2]) shl 4) or HEX.indexOf(encodedProof[index * 2 + 1])).toByte()
        }
        try {
            SessionWorkOriginPlanRecord(integer(root["expectedRevision"]),
                StorageScope(string(scope["environment"]), kind, string(scope["actorId"])),
                string(root["origin"]), PrivateBytes(proof)).also(::validate)
        } finally { proof.fill(0) }
    }

    private fun JsonObjectBuilder.unsignedFields(record: SessionWorkOriginPlanRecord) {
        put("version", 1)
        put("expectedRevision", record.expectedRevision)
        put("scope", buildJsonObject {
            put("environment", record.scope.environment)
            put("actorKind", record.scope.actorKind.name)
            put("actorId", record.scope.actorId)
        })
        put("origin", record.origin)
    }

    private fun validate(record: SessionWorkOriginPlanRecord) {
        if (record.expectedRevision !in 1..Long.MAX_VALUE - 2 || record.scope.actorKind == ActorKind.DEMO) invalid()
        // StorageScope already bounds length/control characters but permits malformed UTF-16.
        for (value in listOf(record.scope.environment, record.scope.actorId)) {
            val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
            bytes.fill(0)
        }
        requireCredentialUuid(record.origin)
        val proof = record.proof.copyForCodec()
        try { if (proof.size != PROOF_BYTES) invalid() } finally { proof.fill(0) }
    }

    private fun integer(value: JsonElement?): Long {
        val primitive = value as? JsonPrimitive ?: invalid()
        if (primitive.isString || !primitive.content.matches(Regex("[1-9][0-9]*"))) invalid()
        return primitive.content.toLongOrNull() ?: invalid()
    }
    private fun string(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) { val value = byte.toInt() and 255; append(HEX[value ushr 4]); append(HEX[value and 15]) }
    }
    private fun encoded(value: JsonObject): PrivateBytes {
        val bytes = value.toString().encodeToByteArray(throwOnInvalidSequence = true)
        return try { if (bytes.size > MAX_BYTES) invalid(); PrivateBytes(bytes) } finally { bytes.fill(0) }
    }
    private inline fun <T> checked(action: () -> T): T = try { action() }
    catch (_: Exception) { invalid() }
    private fun invalid(): Nothing = throw SessionWorkOriginPlanFormatException()
}
