package com.feedme.session

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.*
import com.feedme.storage.StateRetirementTarget
import kotlinx.serialization.json.*

/** Local protocol, not a server DTO. No raw credentials or unconstrained exception text. */
internal object RetirementCodec {
    fun encode(state: RetirementState): PrivateBytes = try { PrivateBytes(buildJsonObject {
        put("version", 1)
        when (state) {
            RetirementState.Idle -> put("state", "idle")
            is RetirementState.Complete -> { put("state", "complete"); put("operationId", state.operationId) }
            is RetirementState.PendingCreate -> {
                put("state", "credential-create-pending")
                val encoded = state.plan.copyForStorage().copyForCodec()
                try { put("plan", hex(encoded)) } finally { encoded.fill(0) }
                put("abortRequested", state.abortRequested)
            }
            is RetirementState.PendingSetup -> {
                put("state", "session-setup-pending")
                val encoded = state.plan.copyForStorage().copyForCodec()
                try { put("plan", hex(encoded)) } finally { encoded.fill(0) }
                put("abortRequested", state.abortRequested)
            }
            is RetirementState.InFlight -> {
                put("state", if (state is RetirementState.Pending) "pending" else "setup-discard-pending")
                put("operationId", state.operationId)
                put("scope", buildJsonObject {
                    put("environment", state.scope.environment); put("actorKind", state.scope.actorKind.name); put("actorId", state.scope.actorId)
                })
                put("origin", state.origin?.let(::JsonPrimitive) ?: JsonNull)
                put("credentialIncarnation", state.credentialIncarnation?.let(::JsonPrimitive) ?: JsonNull)
                put("dataTarget", state.target?.let { target -> JsonPrimitive(hex(target.copyForStorage())) } ?: JsonNull)
                put("done", JsonArray(state.done.sortedBy { it.name }.map { JsonPrimitive(it.name) }))
            }
        }
    }.toString().encodeToByteArray(throwOnInvalidSequence = true)).also { decode(it) }
    } catch (failure: RetirementFailure) { throw failure }
    catch (_: Exception) { invalid() }

    fun decode(bytes: PrivateBytes): RetirementState = try {
        val document = WireDocument.decode(bytes.copyForCodec(), WireLimits(maxBytes = 32_768, maxDepth = 8))
        val root = Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
        if (root["version"] !is JsonPrimitive || root.getValue("version").jsonPrimitive.isString ||
            root.getValue("version").jsonPrimitive.content != "1") invalid()
        when (string(root["state"])) {
            "idle" -> { exact(root, setOf("version", "state")); RetirementState.Idle }
            "complete" -> { exact(root, setOf("version", "state", "operationId")); RetirementState.Complete(retirementUuid(string(root["operationId"]))) }
            "credential-create-pending" -> {
                exact(root, setOf("version", "state", "plan", "abortRequested"))
                val requested = root["abortRequested"] as? JsonPrimitive ?: invalid()
                if (requested.isString || requested.booleanOrNull == null) invalid()
                val raw = unhex(string(root["plan"]))
                val plan = try { requireRetirement(CredentialCreatePlan.fromStorage(PrivateBytes(raw))) } finally { raw.fill(0) }
                RetirementState.PendingCreate(plan, requested.boolean)
            }
            "session-setup-pending" -> {
                exact(root, setOf("version", "state", "plan", "abortRequested"))
                val requested = root["abortRequested"] as? JsonPrimitive ?: invalid()
                if (requested.isString || requested.booleanOrNull == null) invalid()
                val raw = unhex(string(root["plan"]), SessionSetupPlanCodec.MAX_BYTES)
                val plan = try { requireRetirement(SessionSetupPlan.fromStorage(PrivateBytes(raw))) } finally { raw.fill(0) }
                RetirementState.PendingSetup(plan, requested.boolean)
            }
            "pending", "setup-discard-pending" -> {
                exact(root, setOf("version", "state", "operationId", "scope", "origin", "credentialIncarnation", "dataTarget", "done"))
                val scope = root.getValue("scope") as? JsonObject ?: invalid()
                exact(scope, setOf("environment", "actorKind", "actorId"))
                val kind = ActorKind.entries.firstOrNull { it.name == string(scope["actorKind"]) && it != ActorKind.DEMO } ?: invalid()
                val owner = StorageScope(string(scope["environment"]), kind, string(scope["actorId"]))
                val target = if (root["dataTarget"] == JsonNull) null else StateRetirementTarget(unhex(string(root["dataTarget"])))
                val origin = if (root["origin"] == JsonNull) null else retirementUuid(string(root["origin"]))
                val credential = if (root["credentialIncarnation"] == JsonNull) null else retirementUuid(string(root["credentialIncarnation"]))
                val done = (root.getValue("done") as? JsonArray ?: invalid()).map { item ->
                    RetirementStep.entries.firstOrNull { it.name == string(item) } ?: invalid()
                }
                if (done.distinct().size != done.size) invalid()
                val operation = retirementUuid(string(root["operationId"]))
                if (string(root["state"]) == "pending") RetirementState.Pending(operation, owner,
                    origin ?: invalid(), credential ?: invalid(), target ?: invalid(), done.toSet())
                else RetirementState.SetupDiscardPending(operation, owner, origin, credential, target, done.toSet())
            }
            else -> invalid()
        }
    } catch (failure: RetirementFailure) { throw failure }
    catch (_: Exception) { invalid() }

    private fun exact(value: JsonObject, keys: Set<String>) { if (value.keys != keys) invalid() }
    private fun string(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) { val n = byte.toInt() and 255; append(HEX[n ushr 4]); append(HEX[n and 15]) }
    }
    private fun unhex(value: String, maxBytes: Int = 4096): ByteArray {
        if (value.length % 2 != 0 || value.length !in 2..maxBytes * 2 || value.any { it !in HEX }) invalid()
        return ByteArray(value.length / 2) { i -> ((HEX.indexOf(value[i * 2]) shl 4) or HEX.indexOf(value[i * 2 + 1])).toByte() }
    }
    private fun invalid(): Nothing = failRetirement(FailureReason.INVALID_DATA)
    private const val HEX = "0123456789abcdef"
}
