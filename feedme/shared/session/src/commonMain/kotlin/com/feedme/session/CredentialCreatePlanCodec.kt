package com.feedme.session

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.PrivateBytes
import kotlinx.serialization.json.*

/** Canonical private protocol. MAC verification is exclusively the native issuing store's job. */
internal object CredentialCreatePlanCodec {
    const val MAX_BYTES = 4096
    private val HEX = Regex("[0-9a-f]{64}")
    private val KEYS = setOf("version", "purpose", "expectedSlotRevision", "incarnation", "target", "payloadMac", "authenticationMac")

    fun encode(record: CredentialCreatePlanRecord): PrivateBytes = checked {
        validate(record)
        encoded(buildJsonObject {
            unsignedFields(record)
            put("authenticationMac", record.authenticationMac)
        })
    }

    /** Domain/namespace separation is added by the native signer outside these canonical bytes. */
    fun encodeUnsigned(record: CredentialCreatePlanRecord): PrivateBytes = checked {
        validate(record)
        encoded(buildJsonObject { unsignedFields(record) })
    }

    fun decode(bytes: PrivateBytes): CredentialCreatePlanRecord = checked {
        val copy = bytes.copyForCodec()
        val document = try { WireDocument.decode(copy, WireLimits(maxBytes = MAX_BYTES, maxDepth = 3)) }
        finally { copy.fill(0) }
        val normalized = document.encodeUtf8()
        val root = try { Json.parseToJsonElement(normalized.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: invalid() }
        finally { normalized.fill(0) }
        if (root.keys != KEYS || integer(root["version"]) != 1L || string(root["purpose"]) != "credential-create") invalid()
        CredentialCreatePlanRecord(integer(root["expectedSlotRevision"]), string(root["incarnation"]),
            string(root["target"]), string(root["payloadMac"]), string(root["authenticationMac"])).also(::validate)
    }

    private fun JsonObjectBuilder.unsignedFields(record: CredentialCreatePlanRecord) {
        put("version", 1)
        put("purpose", "credential-create")
        put("expectedSlotRevision", record.expectedSlotRevision)
        put("incarnation", record.incarnation)
        put("target", record.target)
        put("payloadMac", record.payloadMac)
    }

    private fun validate(record: CredentialCreatePlanRecord) {
        if (record.expectedSlotRevision !in 1..Long.MAX_VALUE - 2) invalid()
        requireCredentialUuid(record.incarnation)
        if (!HEX.matches(record.target) || !HEX.matches(record.payloadMac) || !HEX.matches(record.authenticationMac)) invalid()
    }
    private fun integer(value: JsonElement?): Long {
        val primitive = value as? JsonPrimitive ?: invalid()
        if (primitive.isString || !primitive.content.matches(Regex("[1-9][0-9]*"))) invalid()
        return primitive.content.toLongOrNull() ?: invalid()
    }
    private fun string(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    private fun encoded(value: JsonObject): PrivateBytes {
        val bytes = value.toString().encodeToByteArray(throwOnInvalidSequence = true)
        return try { if (bytes.size > MAX_BYTES) invalid(); PrivateBytes(bytes) } finally { bytes.fill(0) }
    }
    private inline fun <T> checked(action: () -> T): T = try { action() }
    catch (_: Exception) { invalid() }
    private fun invalid(): Nothing = throw CredentialCreatePlanFormatException()
}
