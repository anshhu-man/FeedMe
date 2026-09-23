package com.feedme.app.circles

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.circles.CircleIssuedInvitationLink
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException

/** Explicit, separately configured platform action for INVITE.02 only. The platform must
 * switch to its UI dispatcher BEFORE calling request.useForLaunch, then launch the chooser
 * synchronously in that callback. No suspension, recipient choice, logging, persistence or
 * automatic retry is permitted between capability admission and native chooser launch.
 * A chooser opening/cancellation is not proof of sending, receipt or continuing circle access. */
fun interface CircleInvitationSharePort {
    suspend fun openChooser(request: CircleInvitationShareRequest): PortResult<CircleInvitationShareOutcome>
}

enum class CircleInvitationShareOutcome { OPENED, CANCELLED }

internal suspend fun circleInvitationOpenChooser(port: CircleInvitationSharePort,
    request: CircleInvitationShareRequest): PortResult<CircleInvitationShareOutcome> = try {
    when (val result = port.openChooser(request)) {
        is PortResult.Failure -> result
        is PortResult.Value -> if (result.value == CircleInvitationShareOutcome.OPENED && !request.chooserLaunched)
            PortResult.Failure(FailureReason.INVALID_DATA) else result
    }
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { PortResult.Failure(FailureReason.UNAVAILABLE) }

/** A single explicit share action, not a transferable or stored URL. Only the actual UI
 * owner can construct it from a currently displayed domain-minted invitation link. An old
 * platform callback cannot launch after Back, account loss, replacement or action completion.
 * This is a UI/native launch fence; server authorization remains authoritative. */
@OptIn(ExperimentalAtomicApi::class)
class CircleInvitationShareRequest internal constructor(
    private val link: CircleIssuedInvitationLink,
    private val current: () -> Boolean,
) {
    private val active = AtomicBoolean(true)
    private val claimed = AtomicBoolean(false)
    private val launched = AtomicBoolean(false)

    /** Exact purpose is fixed by this class; never pass the URL to a browser, clipboard,
     * network client or chosen recipient. Implementations must offer the native chooser. */
    fun useForLaunch(launchChooser: (String) -> Unit): PortResult<Unit> {
        if (!active.load() || !current() || !link.isCurrentForNavigation)
            return PortResult.Failure(FailureReason.STALE_SESSION)
        if (!claimed.compareAndSet(false, true)) return PortResult.Failure(FailureReason.CONFLICT)
        return when (val result = link.useIfCurrent { value ->
            if (!active.load() || !current()) PortResult.Failure(FailureReason.STALE_SESSION)
            else {
                launchChooser(value)
                launched.store(true)
                PortResult.Value(Unit)
            }
        }) {
            is PortResult.Failure -> result
            is PortResult.Value -> result.value
        }
    }

    internal val chooserLaunched: Boolean get() = launched.load()
    internal fun invalidate() { active.store(false) }
    override fun toString() = "CircleInvitationShareRequest(INVITE.02, <redacted>)"
}
