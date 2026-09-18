package com.feedme.server.http

import com.feedme.server.contract.ContractCatalog
import io.ktor.server.application.*
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.util.AttributeKey
import java.io.IOException
import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.*

/** HTTP outcome only: SUCCESS never asserts a committed command or client acknowledgement.
 * Without a sent final response, cancellation/failure cannot establish mutation outcome. */
enum class HttpObservationOutcome {
    SUCCESS, REDIRECTION, CLIENT_ERROR, SERVER_ERROR, OTHER_RESPONSE, OUTCOME_UNKNOWN,
    CANCELLED_OUTCOME_UNKNOWN, FAILED_OUTCOME_UNKNOWN,
}

/** Constructed only by the installed observer from server-owned, bounded metadata. Neither
 * arbitrary diagnostic strings nor request/exception objects are accepted by the sink. */
class HttpObservation internal constructor(
    val traceId: UUID,
    val operationId: String,
    val status: Int?,
    val outcome: HttpObservationOutcome,
    val durationMillis: Long,
) {
    fun jsonLine(): String = buildJsonObject {
        put("event", "feedme.http.completed.v1")
        put("traceId", traceId.toString()); put("operationId", operationId)
        put("status", status?.let(::JsonPrimitive) ?: JsonNull)
        put("outcome", outcome.name); put("durationMillis", durationMillis)
    }.toString()
    override fun toString() = "HttpObservation(<redacted>)"
}

/** Best-effort diagnostics, not a durable audit or mutation receipt. Implementations must
 * return promptly and retain no request context. Runtime owners use the bounded asynchronous
 * adapter; direct synchronous destinations must not be installed on a live request path. */
fun interface HttpObservationSink { fun record(observation: HttpObservation) }

/** Explicit destination: does not install/change any global or framework logging settings.
 * One bounded JSON line per call; stdout backpressure is synchronous, never an unbounded
 * in-memory queue. Use only behind BoundedHttpObservationSink on a live request path.
 * The embedding runtime owns stream availability, collection and retention. */
class JsonLineHttpObservationSink(private val output: PrintStream = System.out) : HttpObservationSink {
    override fun record(observation: HttpObservation) {
        output.println(observation.jsonLine())
        if (output.checkError()) throw IOException("HTTP observation output unavailable")
    }
    override fun toString() = "JsonLineHttpObservationSink(<redacted>)"
}

/** Install BEFORE StatusPages and response-context plugins: its CallFailed hook must wrap
 * their handlers, so an exception converted to a sent 500 is observed as that final response.
 * Only application-admitted calls are covered, not malformed sockets rejected by the engine.
 * No endpoint, authentication, provider, product flag or diagnostic exporter is enabled. */
fun Application.installHttpObservability(catalog: ContractCatalog, sink: HttpObservationSink) =
    installHttpObservability(catalog, sink, System::nanoTime)

internal fun Application.installHttpObservability(catalog: ContractCatalog, sink: HttpObservationSink,
    monotonicNanos: () -> Long) {
    install(httpObservability) {
        operationIds = catalog.operations.map { it.id }.toSet()
        require(operationIds.all { it.length in 1..128 && it.matches(Regex("[A-Za-z][A-Za-z0-9]*")) })
        this.sink = sink
        this.monotonicNanos = monotonicNanos
    }
}

/** The sole trace source; never parse or adopt an incoming trace/header/body identifier. */
internal fun ApplicationCall.httpObservationTraceId(): String = attributes[observationKey].traceId.toString()

/** Called by the canonical dispatcher with its pinned operation ID, not a request value.
 * Unknown values remain the fixed UNMATCHED category and are never retained or printed. */
internal fun ApplicationCall.markHttpOperation(operationId: String) {
    attributes.getOrNull(observationKey)?.markOperation(operationId)
}

/** Fixed classification for an explicitly authored ambiguous-commit response; no error text. */
internal fun ApplicationCall.markHttpOutcomeUnknown() {
    attributes.getOrNull(observationKey)?.outcomeUnknown = true
}

private class ObservationConfiguration {
    lateinit var operationIds: Set<String>
    lateinit var sink: HttpObservationSink
    var monotonicNanos: () -> Long = System::nanoTime
}

private val observationKey = AttributeKey<ObservationState>("FeedMeHttpObservation")
private val httpObservability = createApplicationPlugin("FeedMeHttpObservability", ::ObservationConfiguration) {
    val operations = pluginConfig.operationIds.toSet()
    val sink = pluginConfig.sink
    val nanos = pluginConfig.monotonicNanos
    on(CallSetup) { call ->
        call.attributes.put(observationKey, ObservationState(UUID.randomUUID(), operations, nanos(), nanos, sink))
    }
    on(ResponseSent) { call ->
        // Pinned Ktor ResponseSent runs AFTER the engine's send pipeline returns. Headers
        // merely committed before a failed send must never be reported as a sent response.
        if (call.response.isSent) call.attributes.getOrNull(observationKey)?.sent(call.response.status()?.value)
    }
    on(CallFailed) { call, cause ->
        val state = call.attributes.getOrNull(observationKey)
        if (call.response.isSent) state?.sent(call.response.status()?.value)
        else state?.notSent(cause is CancellationException || cause is InterruptedException)
        // Diagnostics never consume an actual call failure or turn cancellation into HTTP 500.
        // Fatal JVM Errors are also not suppressed. The sent guard prevents duplicate records.
        throw cause
    }
}

private class ObservationState(
    val traceId: UUID,
    private val operationIds: Set<String>,
    private val startedNanos: Long,
    private val nanos: () -> Long,
    private val sink: HttpObservationSink,
) {
    private val emitted = AtomicBoolean(false)
    @Volatile private var operationId = "UNMATCHED"
    @Volatile var outcomeUnknown = false

    fun markOperation(value: String) {
        if (!emitted.get() && value in operationIds) operationId = value
    }
    fun sent(value: Int?) {
        val status = value?.takeIf { it in 100..599 }
        val outcome = when {
            outcomeUnknown || status == null -> HttpObservationOutcome.OUTCOME_UNKNOWN
            status in 200..299 -> HttpObservationOutcome.SUCCESS
            status in 300..399 -> HttpObservationOutcome.REDIRECTION
            status in 400..499 -> HttpObservationOutcome.CLIENT_ERROR
            status in 500..599 -> HttpObservationOutcome.SERVER_ERROR
            else -> HttpObservationOutcome.OTHER_RESPONSE
        }
        emit(status, outcome)
    }
    fun notSent(cancelled: Boolean) = emit(null, if (cancelled) HttpObservationOutcome.CANCELLED_OUTCOME_UNKNOWN
        else HttpObservationOutcome.FAILED_OUTCOME_UNKNOWN)

    private fun emit(status: Int?, outcome: HttpObservationOutcome) {
        if (!emitted.compareAndSet(false, true)) return
        // nanoTime subtraction handles ordinary counter wrap; clamp negative/test-clock
        // regressions and long-lived calls to a finite diagnostic range (one day).
        val millis = ((nanos() - startedNanos).coerceAtLeast(0) / 1_000_000).coerceAtMost(86_400_000)
        val observation = HttpObservation(traceId, operationId, status, outcome, millis)
        try { sink.record(observation) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        // A sink is synchronous/non-suspending. Its own CancellationException is a sink
        // failure, not authority to cancel a request. The call's original cancellation is
        // independently propagated by CallFailed; there is no NonCancellable work/retry.
        catch (_: CancellationException) { }
        catch (_: Exception) { }
    }
}
