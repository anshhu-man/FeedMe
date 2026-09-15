package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One trusted application composition root, not identity verification. Create once for the
 * application's boundary and approved immutable configuration. Reserve BEFORE constructing or
 * opening native stores. The process registry also excludes legacy borrowed runtimes; an
 * inactive boundary alone is deliberately insufficient. All lifecycle calls belong to the
 * application's serialized dispatcher. No provider, native storage or lease is acquired here.
 */
class SessionApplicationComposition private constructor(
    internal val boundary: SessionBoundary,
    internal val dispatcher: CoroutineDispatcher,
    internal val configuration: String,
) {
    internal var generation: Any = Any()
    internal var closed = false
    internal var invalidated = false
    internal var reservation: SessionCompositionReservation? = null

    /** Synchronous, so cancellation cannot lose an acquired reservation on a return handoff. */
    fun reserve(): PortResult<SessionCompositionReservation> = SessionCompositions.reserve(this)

    /**
     * Permanently makes this root close-only and synchronously clears its lease/private observers.
     * Never releases resources. After truthful release, close this root and explicitly create a
     * new one before acquiring more resources, even when configuration bytes did not change.
     */
    fun invalidate(): PortResult<Unit> = SessionCompositions.invalidate(this)

    /** Only an unreserved root can close; never replace a root to bypass failed native close. */
    fun close(): PortResult<Unit> = SessionCompositions.closeRoot(this)

    override fun toString() = "SessionApplicationComposition(<redacted>)"

    companion object {
        fun create(boundary: SessionBoundary, dispatcher: CoroutineDispatcher,
            configurationBinding: String): PortResult<SessionApplicationComposition> {
            if (!configurationBinding.matches(Regex("[0-9a-f]{64}")))
                return PortResult.Failure(FailureReason.INVALID_DATA)
            return SessionCompositions.install(SessionApplicationComposition(boundary, dispatcher, configurationBinding))
        }
    }
}

/**
 * Opaque application-lifetime exclusion, not authentication or confirmation. Native startup
 * recovery takes ownership of it. For openReserved's borrowed stores, retain it until the
 * runtime AND every supplied native store have acknowledged close; runtime.close alone does
 * not release it. Caller release is refused while a runtime or recovery owner is using it.
 */
class SessionCompositionReservation internal constructor(
    internal val root: SessionApplicationComposition,
    internal val generation: Any,
) {
    internal var claimant: Any? = null
    internal var released = false
    fun release(): PortResult<Unit> = SessionCompositions.release(this, null)
    override fun toString() = "SessionCompositionReservation(<redacted>)"
}

internal class SessionRuntimeRegistration(
    val boundary: SessionBoundary, val owner: Any, val reservation: SessionCompositionReservation?,
)

/** Synchronous short critical sections only; never hold this mutex across I/O or suspension. */
internal object SessionCompositions {
    private val mutex = Mutex()
    private val roots = mutableMapOf<SessionBoundary, SessionApplicationComposition>()
    private val runtimes = mutableMapOf<SessionBoundary, SessionRuntimeRegistration>()

    fun install(root: SessionApplicationComposition): PortResult<SessionApplicationComposition> = locked {
        if (roots.containsKey(root.boundary) || runtimes.containsKey(root.boundary) || root.boundary.current() != null)
            conflict()
        roots[root.boundary] = root
        root
    }

    fun reserve(root: SessionApplicationComposition): PortResult<SessionCompositionReservation> = locked {
        if (roots[root.boundary] !== root || root.closed || root.invalidated) stale()
        if (root.reservation != null || runtimes.containsKey(root.boundary) || root.boundary.current() != null) conflict()
        SessionCompositionReservation(root, root.generation).also { root.reservation = it }
    }

    fun invalidate(root: SessionApplicationComposition): PortResult<Unit> = locked {
        if (roots[root.boundary] !== root || root.closed) stale()
        root.invalidated = true
        root.generation = Any()
        root.boundary.clear()
    }

    fun closeRoot(root: SessionApplicationComposition): PortResult<Unit> = locked {
        if (root.closed) return@locked Unit
        if (roots[root.boundary] !== root) stale()
        if (root.reservation != null || runtimes.containsKey(root.boundary)) conflict()
        root.generation = Any(); root.closed = true
        roots.remove(root.boundary)
        Unit
    }

    fun claim(reservation: SessionCompositionReservation, owner: Any): PortResult<Unit> = locked {
        valid(reservation)
        if (reservation.claimant != null || runtimes.containsKey(reservation.root.boundary)) conflict()
        reservation.claimant = owner
    }

    fun check(reservation: SessionCompositionReservation, owner: Any): PortResult<Unit> = locked {
        valid(reservation)
        if (reservation.claimant !== owner || reservation.root.boundary.current() != null) stale()
    }

    /** Caller-return publication checks only this registry, never dispatcher-owned boundary state. */
    fun checkClaim(reservation: SessionCompositionReservation, owner: Any): PortResult<Unit> = locked {
        valid(reservation)
        if (reservation.claimant !== owner) stale()
    }

    fun release(reservation: SessionCompositionReservation, owner: Any?): PortResult<Unit> = locked {
        if (reservation.released) return@locked Unit
        // Invalidating a root fences operations, but the exact old owner must still be closable.
        if (roots[reservation.root.boundary] !== reservation.root || reservation.root.reservation !== reservation) stale()
        if (reservation.claimant !== owner || runtimes[reservation.root.boundary]?.reservation === reservation) conflict()
        reservation.claimant = null; reservation.released = true; reservation.root.reservation = null
    }

    fun admitRuntime(boundary: SessionBoundary, owner: Any,
        reservation: SessionCompositionReservation?): PortResult<SessionRuntimeRegistration> = locked {
        if (runtimes.containsKey(boundary)) conflict()
        if (reservation == null) {
            if (roots.containsKey(boundary)) conflict()
        } else {
            valid(reservation)
            if (reservation.root.boundary !== boundary || reservation.claimant != null) conflict()
            reservation.claimant = owner
        }
        SessionRuntimeRegistration(boundary, owner, reservation).also { runtimes[boundary] = it }
    }

    fun checkRuntime(registration: SessionRuntimeRegistration): PortResult<Unit> = locked {
        if (runtimes[registration.boundary] !== registration) stale()
        registration.reservation?.let {
            valid(it)
            if (it.claimant !== registration.owner) stale()
        }
    }

    fun releaseRuntime(registration: SessionRuntimeRegistration): PortResult<Unit> = locked {
        releaseRuntimeLocked(registration)
    }

    /**
     * Failed borrowed-runtime construction has no returned runtime to retry a transient tryLock
     * failure. Await this exact logical registration's release, with no native I/O or broad reset.
     * SessionWorkRegistry.close already acknowledged its local ownership release before this call.
     */
    suspend fun releaseRuntimeAfterClose(registration: SessionRuntimeRegistration): PortResult<Unit> =
        withContext(NonCancellable) {
            mutex.withLock {
                try { releaseRuntimeLocked(registration); PortResult.Value(Unit) }
                catch (failed: CompositionFailure) { PortResult.Failure(failed.reason) }
            }
        }

    private fun releaseRuntimeLocked(registration: SessionRuntimeRegistration) {
        val current = runtimes[registration.boundary]
        if (current == null) return
        if (current !== registration) stale()
        registration.reservation?.let {
            if (it.claimant !== registration.owner) stale()
            it.claimant = null // Application still owns this reservation and all borrowed stores.
        }
        runtimes.remove(registration.boundary)
    }

    private fun valid(reservation: SessionCompositionReservation) {
        val root = reservation.root
        if (reservation.released || root.closed || root.invalidated || roots[root.boundary] !== root ||
            root.reservation !== reservation || root.generation !== reservation.generation) stale()
    }

    private fun <T> locked(action: () -> T): PortResult<T> {
        if (!mutex.tryLock()) return PortResult.Failure(FailureReason.CONFLICT)
        return try { PortResult.Value(action()) }
        catch (failure: CompositionFailure) { PortResult.Failure(failure.reason) }
        finally { mutex.unlock() }
    }
    private class CompositionFailure(val reason: FailureReason) : Exception("Session composition unavailable")
    private fun conflict(): Nothing = throw CompositionFailure(FailureReason.CONFLICT)
    private fun stale(): Nothing = throw CompositionFailure(FailureReason.STALE_SESSION)
}
