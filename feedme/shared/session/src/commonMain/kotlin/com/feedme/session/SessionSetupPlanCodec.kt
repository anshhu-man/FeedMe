package com.feedme.session

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.StorageScope
import com.feedme.storage.StateActivationPlan
import kotlinx.serialization.json.*

/** Bounded canonical persistence only; native plan proofs and hidden owner bindings stay native. */
internal object SessionSetupPlanCodec {
    const val MAX_BYTES = 16_000
    private const val HEX = "0123456789abcdef"
    private val KEYS = setOf("version", "purpose", "operationId", "scope", "configurationBinding", "credentialPlan", "dataPlan", "workOriginPlan")
    private val SCOPE_KEYS = setOf("environment", "actorKind", "actorId")

    fun encode(record: SessionSetupPlanRecord): PrivateBytes = checked {
        validate(record)
        val credential = record.credentialPlan.copyForStorage().copyForCodec()
        val data = record.dataPlan.copyForStorage()
        val work = record.workOriginPlan.copyForStorage().copyForCodec()
        try {
            encoded(buildJsonObject {
                put("version", 1)
                put("purpose", "session-setup")
                put("operationId", record.operationId)
                put("scope", buildJsonObject {
                    put("environment", record.scope.environment)
                    put("actorKind", record.scope.actorKind.name)
                    put("actorId", record.scope.actorId)
                })
                put("configurationBinding", record.configurationBinding)
                put("credentialPlan", hex(credential))
                put("dataPlan", hex(data))
                put("workOriginPlan", hex(work))
            })
        } finally { credential.fill(0); data.fill(0); work.fill(0) }
    }

    fun decode(bytes: PrivateBytes): SessionSetupPlanRecord = checked {
        val root = document(bytes)
        if (root.keys != KEYS) invalid()
        val version = root["version"] as? JsonPrimitive ?: invalid()
        if (version.isString || version.content != "1" || string(root["purpose"]) != "session-setup") invalid()
        val scope = root["scope"] as? JsonObject ?: invalid()
        if (scope.keys != SCOPE_KEYS) invalid()
        val kind = ActorKind.entries.firstOrNull { it.name == string(scope["actorKind"]) && it != ActorKind.DEMO } ?: invalid()
        val owner = StorageScope(string(scope["environment"]), kind, string(scope["actorId"]))
        val credentialBytes = unhex(string(root["credentialPlan"]), CredentialCreatePlanCodec.MAX_BYTES)
        val credential = try { required(CredentialCreatePlan.fromStorage(PrivateBytes(credentialBytes))) }
        finally { credentialBytes.fill(0) }
        val dataBytes = unhex(string(root["dataPlan"]), StateActivationPlan.ENCODED_SIZE, exact = true)
        val data = try { required(StateActivationPlan.fromStorage(dataBytes)) }
        finally { dataBytes.fill(0) }
        val workBytes = unhex(string(root["workOriginPlan"]), SessionWorkOriginPlanCodec.MAX_BYTES)
        val work = try { required(SessionWorkOriginPlan.fromStorage(PrivateBytes(workBytes))) }
        finally { workBytes.fill(0) }
        SessionSetupPlanRecord(string(root["operationId"]), owner, string(root["configurationBinding"]), credential, data, work)
            .also(::validate)
    }

    private fun validate(record: SessionSetupPlanRecord) {
        requireCredentialUuid(record.operationId)
        if (record.scope.actorKind == ActorKind.DEMO || record.configurationBinding.length != 64 ||
            record.configurationBinding.any { it !in HEX }) invalid()
        for (value in listOf(record.scope.environment, record.scope.actorId)) {
            val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
            bytes.fill(0)
        }
        // Do not trust the size-only legacy public data constructor as structural validation.
        val data = record.dataPlan.copyForStorage()
        try { required(StateActivationPlan.fromStorage(data)) } finally { data.fill(0) }
        required(CredentialCreatePlan.fromStorage(record.credentialPlan.copyForStorage()))
        val work = required(SessionWorkOriginPlan.fromStorage(record.workOriginPlan.copyForStorage()))
        if (SessionWorkOriginPlanCodec.decode(work.copyForStorage()).scope != record.scope) invalid()
        // There is deliberately no guessed credential/data scope comparison or MAC validation.
    }

    private fun document(bytes: PrivateBytes): JsonObject {
        val copy = bytes.copyForCodec()
        val document = try { WireDocument.decode(copy, WireLimits(maxBytes = MAX_BYTES, maxDepth = 4)) }
        finally { copy.fill(0) }
        val normalized = document.encodeUtf8()
        return try { Json.parseToJsonElement(normalized.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: invalid() }
        finally { normalized.fill(0) }
    }
    private fun encoded(root: JsonObject): PrivateBytes {
        val bytes = root.toString().encodeToByteArray(throwOnInvalidSequence = true)
        return try { if (bytes.size > MAX_BYTES) invalid(); PrivateBytes(bytes) } finally { bytes.fill(0) }
    }
    private fun unhex(value: String, maximum: Int, exact: Boolean = false): ByteArray {
        if (value.length !in 2..maximum * 2 || value.length % 2 != 0 ||
            (exact && value.length != maximum * 2) || value.any { it !in HEX }) invalid()
        return ByteArray(value.length / 2) { index ->
            ((HEX.indexOf(value[index * 2]) shl 4) or HEX.indexOf(value[index * 2 + 1])).toByte()
        }
    }
    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) { val value = byte.toInt() and 255; append(HEX[value ushr 4]); append(HEX[value and 15]) }
    }
    private fun string(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    private fun <T> required(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> invalid()
    }
    private inline fun <T> checked(action: () -> T): T = try { action() }
    catch (_: Exception) { invalid() }
    private fun invalid(): Nothing = throw SessionSetupPlanFormatException()
}
