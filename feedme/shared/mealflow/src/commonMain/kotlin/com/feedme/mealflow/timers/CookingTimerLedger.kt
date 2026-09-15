package com.feedme.mealflow.timers

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.kitchen.*
import kotlinx.serialization.json.*

enum class CookingTimerAlertPhase { DESIRED, MAPPED, ARMED, CANCELLING, QUIET }
class CookingTimerView(val timerId: String, val generation: Long, val timing: CookingTimerObservation?,
    val alertPhase: CookingTimerAlertPhase, val alertAcknowledgedInThisOwner: Boolean,
    val stepId: String? = null, val status: String? = null, val durationSeconds: Long? = null,
    val deliveryPhase: CookingTimerDeliveryPhase = CookingTimerDeliveryPhase.UNAVAILABLE) {
    override fun toString() = "CookingTimerView(phase=$alertPhase, private=<redacted>)"
}
internal data class TimerSlot(val id: String, val generation: Long, val commandId: String,
    val phase: CookingTimerAlertPhase, val anchor: CookingTimerAnchor?, val ticket: String?)
internal object CookingTimerLedger {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun id(value: String): String = value.lowercase().also { if (!uuid.matches(it)) fail(FailureReason.INVALID_DATA) }
    fun encode(slots: List<TimerSlot>): PrivateBytes = PrivateBytes(buildJsonObject {
        put("version", 1); put("slots", JsonArray(slots.map { slot -> buildJsonObject {
            put("id", slot.id); put("generation", slot.generation); put("command", slot.commandId); put("phase", slot.phase.name)
            put("ticket", slot.ticket?.let(::JsonPrimitive) ?: JsonNull)
            put("anchor", slot.anchor?.let { a -> buildJsonObject {
                put("epoch", a.epochMillis); put("elapsed", a.elapsedMillis); put("continuity", a.continuity); put("remaining", a.remainingMillis)
            } } ?: JsonNull)
        } }))
    }.toString().encodeToByteArray()).also(::decode)
    fun decode(bytes: PrivateBytes): List<TimerSlot> {
        val root = WireDocument.decode(bytes.copyForCodec(), WireLimits(maxBytes = 16000, maxDepth = 8)).json().jsonObject
        exact(root, setOf("version", "slots")); if (long(root, "version") != 1L) fail(FailureReason.INVALID_DATA)
        val values = root.getValue("slots").jsonArray
        if (values.size > 32) fail(FailureReason.INVALID_DATA)
        val result = values.map {
            val r = it.jsonObject; exact(r, setOf("id", "generation", "command", "phase", "ticket", "anchor"))
            val ticket = if (r.getValue("ticket") == JsonNull) null else id(string(r, "ticket"))
            val phase = CookingTimerAlertPhase.valueOf(string(r, "phase"))
            if ((phase in setOf(CookingTimerAlertPhase.MAPPED, CookingTimerAlertPhase.ARMED, CookingTimerAlertPhase.CANCELLING)) != (ticket != null)) fail(FailureReason.INVALID_DATA)
            val anchor = r.getValue("anchor").let { a -> if (a == JsonNull) null else a.jsonObject.let { b ->
                exact(b, setOf("epoch", "elapsed", "continuity", "remaining"))
                CookingTimerAnchor(long(b, "epoch"), long(b, "elapsed"), string(b, "continuity"), long(b, "remaining"))
            } }
            TimerSlot(id(string(r, "id")), long(r, "generation").also { n -> if (n < 1) fail(FailureReason.INVALID_DATA) },
                id(string(r, "command")), phase, anchor, ticket)
        }
        if (result.map { it.id }.distinct().size != result.size || result.mapNotNull { it.ticket }.distinct().size != result.count { it.ticket != null }) fail(FailureReason.INVALID_DATA)
        return result
    }
    private fun exact(root: JsonObject, keys: Set<String>) { if (root.keys != keys) fail(FailureReason.INVALID_DATA) }
    private fun string(root: JsonObject, key: String) = root.getValue(key).jsonPrimitive.let { if (!it.isString) fail(FailureReason.INVALID_DATA); it.content }
    private fun long(root: JsonObject, key: String) = root.getValue(key).jsonPrimitive.let {
        if (it.isString || !it.content.matches(Regex("0|[1-9][0-9]*"))) fail(FailureReason.INVALID_DATA)
        it.content.toLongOrNull() ?: fail(FailureReason.INVALID_DATA)
    }
}
internal fun WireDocument.json(): JsonElement = Json.parseToJsonElement(encodeUtf8().decodeToString())
internal class TimerFailure(val reason: FailureReason) : Exception("Cooking timer unavailable")
internal fun fail(reason: FailureReason): Nothing = throw TimerFailure(reason)
internal fun <T> value(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> fail(result.reason)
}
