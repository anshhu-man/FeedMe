package com.feedme.kitchen

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.serialization.json.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Trusted composition supplies continuity only when elapsed readings are comparable. No native default. */
fun interface CookingTimerClock { fun read(): CookingTimerClockReading }
class CookingTimerClockReading(val epochMillis: Long, val elapsedMillis: Long, val continuity: String?) {
    init { require(epochMillis in 0..253402300799999L && elapsedMillis >= 0); require(continuity == null || continuity.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
    override fun toString() = "CookingTimerClockReading(<redacted>)"
}
class CookingTimerAnchor(val epochMillis: Long, val elapsedMillis: Long, val continuity: String, val remainingMillis: Long) {
    init { require(epochMillis in 0..253402300799999L && elapsedMillis >= 0 && remainingMillis in 0..CookingTimerReducer.MAX_DURATION_SECONDS * 1000); require(continuity.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
    override fun toString() = "CookingTimerAnchor(<redacted>)"
}
enum class CookingTimerTiming { RUNNING, PAUSED, DUE, UNCERTAIN }
class CookingTimerObservation(val timing: CookingTimerTiming, val remainingMillis: Long?)
sealed interface CookingTimerAction {
    val timerId: String
    class Start(override val timerId: String, val stepId: String, val durationSeconds: Long) : CookingTimerAction
    class Pause(override val timerId: String) : CookingTimerAction
    class Resume(override val timerId: String) : CookingTimerAction
    class Reset(override val timerId: String) : CookingTimerAction
    class Cancel(override val timerId: String) : CookingTimerAction
}
class CookingTimerReduction(timers: List<WireDocument>, val anchor: CookingTimerAnchor?) {
    val timers = timers.toList()
    override fun toString() = "CookingTimerReduction(<redacted>)"
}

/** Application bounds: 32 simultaneous timers, at most 24h each, 2s wall/elapsed drift tolerance.
 * Pure estimates only; no recipe permission, scheduler, clock continuity inference or automatic advance.
 */
@OptIn(ExperimentalTime::class)
object CookingTimerReducer {
    const val MAX_TIMERS = 32
    const val MAX_DURATION_SECONDS = 86_400L
    const val CLOCK_DRIFT_MILLIS = 2_000L
    private val validator = CanonicalBodyValidator.bundled()
    fun observe(timer: WireDocument, anchor: CookingTimerAnchor?, now: CookingTimerClockReading): PortResult<CookingTimerObservation> = checked {
        val root = valid(timer)
        val duration = seconds(root, "durationSeconds") * 1000
        when (root.getValue("status").jsonPrimitive.content) {
            "done" -> CookingTimerObservation(CookingTimerTiming.DUE, 0)
            "paused" -> CookingTimerObservation(CookingTimerTiming.PAUSED, seconds(root, "pausedRemainingSeconds") * 1000)
            else -> {
                val uncertain = CookingTimerObservation(CookingTimerTiming.UNCERTAIN, null)
                if (anchor == null || now.continuity == null || anchor.continuity != now.continuity || now.elapsedMillis < anchor.elapsedMillis || anchor.remainingMillis > duration)
                    uncertain
                else {
                    val elapsed = now.elapsedMillis - anchor.elapsedMillis
                    val wall = now.epochMillis - anchor.epochMillis
                    val deadline = root["endAt"]?.jsonPrimitive?.content
                    if (deadline != utc(anchor.epochMillis + anchor.remainingMillis) || wall < 0 ||
                        (if (wall >= elapsed) wall - elapsed else elapsed - wall) > CLOCK_DRIFT_MILLIS) uncertain
                    else (anchor.remainingMillis - minOf(elapsed, anchor.remainingMillis)).let {
                        CookingTimerObservation(if (it == 0L) CookingTimerTiming.DUE else CookingTimerTiming.RUNNING, it)
                    }
                }
            }
        }
    }
    fun reduce(timers: List<WireDocument>, anchor: CookingTimerAnchor?, action: CookingTimerAction,
        pinnedStepIds: Set<String>, now: CookingTimerClockReading): PortResult<CookingTimerReduction> = checked {
        if (timers.size > MAX_TIMERS || pinnedStepIds.isEmpty()) bad()
        val values = timers.map(::valid)
        val ids = values.map { normalizedId(it.getValue("timerId").jsonPrimitive.content) }
        if (ids.distinct().size != ids.size || values.any { it.getValue("stepId").jsonPrimitive.content !in pinnedStepIds }) bad()
        val id = normalizedId(action.timerId)
        val index = ids.indexOf(id)
        var newAnchor: CookingTimerAnchor? = null
        val next = if (action is CookingTimerAction.Start) {
            if (index >= 0 || timers.size == MAX_TIMERS || action.stepId !in pinnedStepIds || action.durationSeconds !in 1..MAX_DURATION_SECONDS) bad()
            val continuity = now.continuity ?: kitchenFail(FailureReason.CONFLICT)
            newAnchor = CookingTimerAnchor(now.epochMillis, now.elapsedMillis, continuity, action.durationSeconds * 1000)
            values + buildJsonObject {
                put("timerId", id); put("stepId", action.stepId); put("status", "running")
                put("durationSeconds", action.durationSeconds); put("endAt", utc(checkedDeadline(now.epochMillis, action.durationSeconds * 1000)))
            }
        } else {
            if (index < 0) kitchenFail(FailureReason.NOT_FOUND)
            val old = values[index]
            val duration = seconds(old, "durationSeconds")
            if (action is CookingTimerAction.Cancel) values.filterIndexed { i, _ -> i != index }
            else {
                val changed = old.toMutableMap().apply { remove("endAt"); remove("pausedRemainingSeconds") }
                when (action) {
                    is CookingTimerAction.Pause -> {
                        if (old.getValue("status").jsonPrimitive.content != "running") kitchenFail(FailureReason.CONFLICT)
                        val remaining = kitchenValue(observe(timers[index], anchor, now)).remainingMillis ?: kitchenFail(FailureReason.CONFLICT)
                        changed["status"] = JsonPrimitive("paused")
                        changed["pausedRemainingSeconds"] = JsonPrimitive((remaining + 999) / 1000)
                    }
                    is CookingTimerAction.Resume -> {
                        if (old.getValue("status").jsonPrimitive.content != "paused") kitchenFail(FailureReason.CONFLICT)
                        val remaining = seconds(old, "pausedRemainingSeconds") * 1000
                        if (remaining == 0L) kitchenFail(FailureReason.CONFLICT)
                        val continuity = now.continuity ?: kitchenFail(FailureReason.CONFLICT)
                        newAnchor = CookingTimerAnchor(now.epochMillis, now.elapsedMillis, continuity, remaining)
                        changed["status"] = JsonPrimitive("running"); changed["endAt"] = JsonPrimitive(utc(checkedDeadline(now.epochMillis, remaining)))
                    }
                    is CookingTimerAction.Reset -> { changed["status"] = JsonPrimitive("paused"); changed["pausedRemainingSeconds"] = JsonPrimitive(duration) }
                    else -> bad()
                }
                values.mapIndexed { i, value -> if (i == index) JsonObject(changed) else value }
            }
        }
        CookingTimerReduction(next.map { WireDocument.parse(it.toString()).also(::valid) }, newAnchor)
    }
    private fun valid(doc: WireDocument): JsonObject {
        if (validator.validateSchema("TimerState", doc.encodeUtf8()) != ContractValidationResult.Valid) bad()
        val value = doc.json().jsonObject
        val duration = seconds(value, "durationSeconds")
        if (duration !in 1..MAX_DURATION_SECONDS) bad()
        if (value["pausedRemainingSeconds"] != null && seconds(value, "pausedRemainingSeconds") > duration) bad()
        if (value.getValue("status").jsonPrimitive.content == "paused" && value["pausedRemainingSeconds"] == null) bad()
        if (value.getValue("status").jsonPrimitive.content == "running" && value["endAt"] == null) bad()
        return value
    }
    private fun seconds(root: JsonObject, name: String) = wireLong(WireDocument.parse(root.toString()), name)
    private fun checkedDeadline(now: Long, remaining: Long): Long = (now + remaining).also { if (it !in now..253402300799999L) bad() }
    private fun utc(value: Long) = Instant.fromEpochMilliseconds(value).toString()
    private fun bad(): Nothing = kitchenFail(FailureReason.INVALID_DATA)
    private fun <T> checked(block: () -> T): PortResult<T> = try { PortResult.Value(block()) }
    catch (e: KitchenFailure) { PortResult.Failure(e.reason) }
    catch (_: Exception) { PortResult.Failure(FailureReason.INVALID_DATA) }
}
