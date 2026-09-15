package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Advisory routing only: none of these observations authorizes authentication or cleanup. */
enum class SessionStartupControlKind { TERMINAL, PENDING_SETUP, RECOVERY_REQUIRED }

/**
 * Trusted native composition only. The caller retains an already-open, existing-only CONTROL
 * owner under its actual composition reservation and closes it before opening other owners.
 * This codec boundary exposes no raw state, identity, plan or mutation capability to UI.
 * TERMINAL still requires the ordinary runtime's fresh recovery/identity checks. A missing or
 * failed read never means a new install or permission to fall back to initializing factories.
 */
object SessionStartupControlInspector {
    suspend fun inspect(control: SessionControlStore): PortResult<SessionStartupControlKind> = try {
        val first = requireRetirement(control.read()) ?: failRetirement(FailureReason.NOT_FOUND)
        val state = RetirementCodec.decode(first.payload)
        val second = requireRetirement(control.read()) ?: failRetirement(FailureReason.CONFLICT)
        currentCoroutineContext().ensureActive()
        val left = first.payload.copyForCodec()
        val right = second.payload.copyForCodec()
        try {
            if (first.revision != second.revision || !left.contentEquals(right))
                failRetirement(FailureReason.CONFLICT)
        } finally { left.fill(0); right.fill(0) }
        PortResult.Value(when (state) {
            RetirementState.Idle, is RetirementState.Complete -> SessionStartupControlKind.TERMINAL
            is RetirementState.PendingSetup -> SessionStartupControlKind.PENDING_SETUP
            else -> SessionStartupControlKind.RECOVERY_REQUIRED
        })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RetirementFailure) { PortResult.Failure(failure.reason) }
    catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
}
