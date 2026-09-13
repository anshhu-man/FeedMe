package com.feedme.session

import com.feedme.core.ports.*

/** Opaque OS identity, never an account, recipe, timer label, token or command body. */
enum class NativeWorkKind { TIMER, WORKER }

class NativeWorkTicket internal constructor(val id: String, val kind: NativeWorkKind) {
    init { requireCredentialUuid(id) }
    override fun toString() = "NativeWorkTicket(kind=$kind, identity=<redacted>)"

    companion object {
        /** OS delivery carries an untrusted identity, never a session or effect permission. */
        fun fromNativeIdentity(id: String, kind: NativeWorkKind): PortResult<NativeWorkTicket> = try {
            PortResult.Value(NativeWorkTicket(id, kind))
        } catch (_: IllegalArgumentException) {
            PortResult.Failure(FailureReason.INVALID_DATA)
        }
    }
}

/** Native cancellation is an acknowledged command, not proof that an executing callback stopped. */
fun interface NativeWorkCancellationPort {
    suspend fun cancel(ticket: NativeWorkTicket): PortResult<Unit>
}

/** Supplied by the native composition root; must produce fresh UUIDs, never recycled IDs. */
fun interface NativeWorkIdSource { fun nextId(): String }

/** Captured while an independently verified lease is current; cannot be restored from OS extras. */
class SessionWorkBinding internal constructor(
    internal val lease: SessionLease,
    val originBinding: String,
) {
    override fun toString() = "SessionWorkBinding(<redacted>)"
}

/** Callback policy must check the independent retirement barrier and current domain eligibility. */
fun interface NativeWorkExecutionPolicy {
    suspend fun allowed(scope: StorageScope, originBinding: String, logicalId: String, ticket: NativeWorkTicket): PortResult<Boolean>
}

/** Checks independent retirement recovery and verified owner admission; never autoactivates. */
fun interface NativeWorkAdmissionPolicy {
    suspend fun allowed(scope: StorageScope): PortResult<Boolean>
}

enum class NativeWorkPhase { RESERVED, INSTALLED, CANCELLING }

/** Diagnostic projection only: not permission to schedule or dispatch effects outside the gate. */
class NativeWorkStatus internal constructor(
    val ticket: NativeWorkTicket,
    val phase: NativeWorkPhase,
) {
    override fun toString() = "NativeWorkStatus(phase=$phase, identity=<redacted>)"
}

class SessionWorkSnapshot internal constructor(
    val revision: Long,
    val scope: StorageScope?,
    val originBinding: String?,
    val retiring: Boolean,
    entries: List<NativeWorkStatus>,
) {
    val entries = entries.toList()
    override fun toString() = "SessionWorkSnapshot(revision=$revision, retiring=$retiring, details=<redacted>)"
}

internal class SessionWorkEntry(
    val id: String,
    val kind: NativeWorkKind,
    val logicalId: String,
    val phase: NativeWorkPhase,
) {
    fun phase(value: NativeWorkPhase) = SessionWorkEntry(id, kind, logicalId, value)
    fun ticket() = NativeWorkTicket(id, kind)
    override fun toString() = "SessionWorkEntry(<redacted>)"
}

internal sealed interface SessionWorkState {
    data object Idle : SessionWorkState
    class Origin(
        val scope: StorageScope,
        val origin: String,
        val retiring: Boolean,
        entries: List<SessionWorkEntry>,
    ) : SessionWorkState {
        val entries = entries.toList()
        fun withEntries(values: List<SessionWorkEntry>) = Origin(scope, origin, retiring, values)
        fun retiring() = Origin(scope, origin, true, entries)
        override fun toString() = "SessionWorkOrigin(retiring=$retiring, details=<redacted>)"
    }
}

internal class SessionWorkFormatException : Exception("Native work data unavailable")
