package com.feedme.development.progress

import com.feedme.core.ports.FailureReason

/** UI observations only. A phase is never permission to open, erase or activate a native owner. */
internal enum class ProgressHostPhase {
    NEW, CHECKING, START_AVAILABLE, RESUME_AVAILABLE, ACTIVE, SETUP_RECOVERY,
    RECOVERY_REQUIRED, CLOSING, CLOSE_ONLY, CLOSED, UNSUPPORTED,
}

internal enum class ProgressConfirmationKind { RESET, ABORT_SETUP }

/** Ephemeral exact-owner consent. It contains no stored credential, scope, plan or native handle. */
internal class ProgressConfirmation internal constructor(
    val kind: ProgressConfirmationKind,
    internal val owner: Any,
    internal val generation: Any,
) {
    override fun toString() = "ProgressConfirmation(kind=$kind, details=<redacted>)"
}

internal data class ProgressHostState(
    val phase: ProgressHostPhase = ProgressHostPhase.NEW,
    val busy: Boolean = false,
    val failure: FailureReason? = null,
    val serviceOffline: Boolean = false,
    val confirmation: ProgressConfirmation? = null,
    val detail: String? = null,
    val recoveryOpen: Boolean = false,
    val canRetryAbort: Boolean = false,
    val canRetryCreate: Boolean = false,
    val canRetryRetirement: Boolean = false,
    val canReset: Boolean = false,
) {
    override fun toString() = "ProgressHostState(phase=$phase, busy=$busy, failure=$failure, details=<redacted>)"
}

/** Exact identity and generation are checked again by the admitted action, not just the view. */
internal class ProgressConsentOwner {
    private val owner = Any()
    private var generation = Any()
    private var current: ProgressConfirmation? = null
    private var closed = false

    fun prepare(kind: ProgressConfirmationKind): ProgressConfirmation? = if (closed) null else {
        generation = Any()
        ProgressConfirmation(kind, owner, generation).also { current = it }
    }
    fun owns(value: ProgressConfirmation): Boolean = !closed && current === value &&
        value.owner === owner && value.generation === generation
    fun consume(value: ProgressConfirmation): Boolean {
        if (!owns(value)) return false
        dismiss()
        return true
    }
    fun dismiss() { generation = Any(); current = null }
    fun close() { closed = true; dismiss() }
}
