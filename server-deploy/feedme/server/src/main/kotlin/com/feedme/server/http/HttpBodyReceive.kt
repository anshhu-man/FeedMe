package com.feedme.server.http

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Engineering receive bound, not an operation, database, authentication or response deadline. */
internal const val HTTP_BODY_RECEIVE_TIMEOUT_MILLIS = 10_000L

/**
 * Receives at most [maximumBytes], with one absolute deadline (progress does not reset it).
 * The caller owns/wipes the returned exact bytes and validates them outside the receive timer.
 * Incomplete/oversized input is cancelled, never drained by awaiting further peer traffic.
 * Only our own timeout maps to the caller's existing malformed-request response; an outer
 * cancellation/timeout keeps its identity and cannot become a successful HTTP response.
 */
internal suspend fun readBoundedHttpBody(
    channel: ByteReadChannel,
    maximumBytes: Int,
    contentLength: Long?,
    invalid: () -> Nothing,
    timeoutMillis: Long = HTTP_BODY_RECEIVE_TIMEOUT_MILLIS,
): ByteArray {
    require(maximumBytes in 0 until Int.MAX_VALUE && timeoutMillis > 0)
    val scratch = ByteArray(maximumBytes + 1)
    var complete = false
    try {
        val size = withTimeoutOrNull(timeoutMillis) {
            var received = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = channel.readAvailable(scratch, received, scratch.size - received)
                if (count == -1) {
                    channel.closedCause?.let { throw it }
                    break
                }
                received += count
                if (received == scratch.size) invalid()
            }
            currentCoroutineContext().ensureActive()
            if (contentLength != null && contentLength != received.toLong()) invalid()
            received
        }
        currentCoroutineContext().ensureActive()
        if (size == null) invalid()
        // No private copy can be abandoned by withTimeout's prompt cancellation boundary.
        val exact = scratch.copyOf(size)
        complete = true
        return exact
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        currentCoroutineContext().ensureActive()
        invalid()
    } finally {
        scratch.fill(0)
        if (!complete) {
            // Cleanup must not replace a domain refusal or caller cancellation. Ktor's cancel
            // is synchronous; it neither waits for peer EOF nor closes a global resource.
            runCatching { channel.cancel(CancellationException("Request body receive stopped")) }
        }
    }
}
