package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.StateRetirementTarget

/** Must retire only this captured native credential incarnation; never a newer login by scope. */
fun interface CredentialRetirementPort {
    suspend fun retire(scope: StorageScope, credentialIncarnation: String): PortResult<Unit>
}

/**
 * Native timers/work/push eligibility tagged with this exact origin; no remote API invocation.
 * Must remain retryable after credential/data keys are erased: keep cancellation identifiers in
 * an independent native ledger, never retrieve them solely from the retiring recipe database.
 */
fun interface SessionWorkRetirementPort {
    suspend fun retire(scope: StorageScope, originBinding: String): PortResult<Unit>
}

/** Confirmed incomplete setup only: never cancel populated work through this capability. */
interface EmptySessionWorkRetirementPort : SessionWorkRetirementPort {
    suspend fun retireEmpty(scope: StorageScope, originBinding: String): PortResult<Unit>
}

/** Captured only while the existing independently verified lease is current. Not an auth token. */
class RetirementBinding internal constructor(
    internal val lease: SessionLease,
    internal val origin: String,
    internal val credentialIncarnation: String,
    internal val dataTarget: StateRetirementTarget,
    internal val controlRevision: Long,
    internal val priorCompletedId: String?,
) {
    override fun toString() = "RetirementBinding(<redacted>)"
}

enum class LocalRetirementPhase { IDLE, PENDING, COMPLETE }
enum class RetirementStep { NATIVE_WORK, CREDENTIALS, PRIVATE_DATA }

class LocalRetirementProgress internal constructor(
    val phase: LocalRetirementPhase,
    val operationId: String?,
    remaining: Set<RetirementStep>,
    failures: Map<RetirementStep, FailureReason> = emptyMap(),
) {
    private val remainingSteps = remaining.toSet()
    private val issues = failures.toMap()
    val remaining: Set<RetirementStep> get() = remainingSteps.toSet()
    val failures: Map<RetirementStep, FailureReason> get() = issues.toMap()
    override fun toString() = "LocalRetirementProgress(phase=$phase, remaining=$remainingSteps, identity=<redacted>)"
}

internal sealed interface RetirementState {
    data object Idle : RetirementState
    class Complete(val operationId: String) : RetirementState
    /** Creation ownership, not permission to restore or an already-confirmed cleanup intent. */
    class PendingCreate(val plan: CredentialCreatePlan, val abortRequested: Boolean) : RetirementState {
        override fun toString() = "PendingCredentialCreate(<redacted>)"
    }
    /** Composite setup ownership. Never a legacy logout, empty discard or credential-only plan. */
    class PendingSetup(val plan: SessionSetupPlan, val abortRequested: Boolean) : RetirementState {
        override fun toString() = "PendingSessionSetup(<redacted>)"
    }
    sealed interface InFlight : RetirementState {
        val operationId: String
        val scope: StorageScope
        val origin: String?
        val credentialIncarnation: String?
        val target: StateRetirementTarget?
        val done: Set<RetirementStep>
        val requiredSteps: Set<RetirementStep>
        fun withDone(step: RetirementStep): InFlight
    }
    class Pending(
        override val operationId: String, override val scope: StorageScope, override val origin: String,
        override val credentialIncarnation: String, override val target: StateRetirementTarget, done: Set<RetirementStep>,
    ) : InFlight {
        override val done = done.toSet()
        override val requiredSteps: Set<RetirementStep> get() = RetirementStep.entries.toSet()
        override fun withDone(step: RetirementStep) = Pending(operationId, scope, origin, credentialIncarnation, target, done + step)
        override fun toString() = "PendingRetirement(<redacted>)"
    }
    class SetupDiscardPending(
        override val operationId: String, override val scope: StorageScope, override val origin: String?,
        override val credentialIncarnation: String?, override val target: StateRetirementTarget?, done: Set<RetirementStep>,
    ) : InFlight {
        override val done = done.toSet()
        override val requiredSteps: Set<RetirementStep> get() = buildSet {
            if (origin != null) add(RetirementStep.NATIVE_WORK)
            if (credentialIncarnation != null) add(RetirementStep.CREDENTIALS)
            if (target != null) add(RetirementStep.PRIVATE_DATA)
        }
        init {
            retirementUuid(operationId)
            if (scope.actorKind == ActorKind.DEMO || (origin == null && credentialIncarnation == null)) failRetirement(FailureReason.INVALID_DATA)
            origin?.let(::retirementUuid)
            credentialIncarnation?.let(::retirementUuid)
            if (!requiredSteps.containsAll(this.done)) failRetirement(FailureReason.INVALID_DATA)
        }
        override fun withDone(step: RetirementStep) = SetupDiscardPending(operationId, scope, origin, credentialIncarnation, target, done + step)
        override fun toString() = "PendingSetupDiscard(<redacted>)"
    }
}

/** Only these two terminal control states permit separate identity composition checks. */
internal fun RetirementState.blocksAccess(): Boolean = this != RetirementState.Idle && this !is RetirementState.Complete

internal class RetirementFailure(val reason: FailureReason) : Exception("Session retirement unavailable")
internal fun failRetirement(reason: FailureReason): Nothing = throw RetirementFailure(reason)
internal fun <T> requireRetirement(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> failRetirement(result.reason)
}
internal fun retirementUuid(value: String): String = value.also {
    if (!it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) failRetirement(FailureReason.INVALID_DATA)
}
