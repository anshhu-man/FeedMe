package com.feedme.android

import com.feedme.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Testable host ownership mechanics only, never a factory for private authority. The actual
 * AccountEntry supplies its exact coordinator/owner methods. Every acquisition retains its
 * returned object before the next await. All calls use the serialized UI/identity dispatcher. */
internal interface EntryPrivateOperations<R : Any, H : Any, C : Any, P : Any> {
    suspend fun complete(): PortResult<R>
    suspend fun take(ready: R): PortResult<H>
    suspend fun handoffCurrent(handoff: H): Boolean
    suspend fun connect(handoff: H): PortResult<C>
    suspend fun connectionCurrent(connection: C): Boolean
    fun currentNow(handoff: H, connection: C): Boolean
    fun create(connection: C): P
    /** Product is retained before this optional suspended integration; it cannot publish early. */
    suspend fun prepareProduct(product: P): PortResult<Unit> = PortResult.Value(Unit)
    suspend fun closeProduct(product: P): PortResult<Unit>
    suspend fun closeConnection(connection: C): PortResult<Unit>
    suspend fun abandonHandoff(handoff: H): PortResult<Unit>
    suspend fun abandonReady(ready: R): PortResult<Unit>
}

internal class EntryPrivateDelivery<R : Any, H : Any, C : Any, P : Any>(
    private val operations: EntryPrivateOperations<R, H, C, P>,
    private val hostCurrent: () -> Boolean,
) {
    private val cleanup = Mutex()
    private var ready: R? = null
    private var handoff: H? = null
    private var connection: C? = null
    private var product: P? = null
    private var started = false
    private var retired = false
    private var constructionUncertain = false
    val requiresClose: Boolean get() = constructionUncertain || ready != null || handoff != null || connection != null || product != null

    fun retire() { retired = true }
    private fun check() {
        if (retired || !hostCurrent()) throw DeliveryFailure(FailureReason.STALE_SESSION)
    }
    private suspend fun checkCaller() { currentCoroutineContext().ensureActive(); check() }
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> throw DeliveryFailure(result.reason)
    }

    /** publish must synchronously retain this exact product and change the exact route. It
     * must not suspend, trigger another acquisition or invoke external callbacks. */
    suspend fun deliver(publish: (P) -> Unit): PortResult<Unit> {
        if (started) return PortResult.Failure(FailureReason.CONFLICT)
        started = true
        try {
            checkCaller()
            val observed = value(operations.complete()).also { ready = it }
            checkCaller()
            val claimed = value(operations.take(observed)).also { handoff = it; ready = null }
            checkCaller()
            if (!operations.handoffCurrent(claimed)) throw DeliveryFailure(FailureReason.STALE_SESSION)
            checkCaller()
            val connected = value(operations.connect(claimed)).also { connection = it }
            checkCaller()
            if (!operations.connectionCurrent(connected)) throw DeliveryFailure(FailureReason.STALE_SESSION)
            checkCaller()
            val built = try { operations.create(connected).also { product = it } }
                catch (failure: Throwable) { constructionUncertain = true; throw failure }
            checkCaller()
            value(operations.prepareProduct(built))
            checkCaller()
            if (!operations.handoffCurrent(claimed)) throw DeliveryFailure(FailureReason.STALE_SESSION)
            checkCaller()
            if (!operations.connectionCurrent(connected)) throw DeliveryFailure(FailureReason.STALE_SESSION)
            checkCaller()
            if (!operations.currentNow(claimed, connected)) throw DeliveryFailure(FailureReason.STALE_SESSION)
            // There is no suspension between the final currentness checks and publication.
            publish(built)
            return PortResult.Value(Unit)
        } catch (cancelled: CancellationException) {
            try { abandon() } catch (_: Throwable) { }
            throw cancelled
        } catch (failure: Exception) {
            val closed = abandon()
            return if (closed is PortResult.Failure) closed
            else PortResult.Failure((failure as? DeliveryFailure)?.reason ?: FailureReason.UNAVAILABLE)
        }
    }

    /** Lost host delivery owns only its exact acquired objects. Never require the vanished
     * attachment to authorize this cleanup. ACK-before-forget; failure retains retry handles. */
    private suspend fun abandon(): PortResult<Unit> = withContext(NonCancellable) {
        cleanup.withLock {
            retired = true
            try {
                product?.let { value(operations.closeProduct(it)); product = null }
                connection?.let { value(operations.closeConnection(it)); connection = null }
                handoff?.let { value(operations.abandonHandoff(it)); handoff = null }
                ready?.let { value(operations.abandonReady(it)); ready = null }
                if (constructionUncertain) PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else PortResult.Value(Unit)
            } catch (failed: Exception) { PortResult.Failure((failed as? DeliveryFailure)?.reason ?: FailureReason.STORAGE_FAILURE) }
        }
    }

    /** Actual native-owner retirement closes these dependents first; the owner then closes
     * coordinator/runtime/native resources. It—not a stale attachment—owns that final ACK.
     * Caller MUST first retire native admission and join any in-flight deliver invocation. */
    suspend fun closeBeforeOwner(): PortResult<Unit> = withContext(NonCancellable) {
        cleanup.withLock {
            retired = true
            try {
                product?.let { value(operations.closeProduct(it)); product = null }
                connection?.let { value(operations.closeConnection(it)); connection = null }
                PortResult.Value(Unit)
            } catch (failed: Exception) { PortResult.Failure((failed as? DeliveryFailure)?.reason ?: FailureReason.STORAGE_FAILURE) }
        }
    }
    override fun toString() = "EntryPrivateDelivery(<redacted>)"
    private class DeliveryFailure(val reason: FailureReason) : Exception("Private destination unavailable")
}
