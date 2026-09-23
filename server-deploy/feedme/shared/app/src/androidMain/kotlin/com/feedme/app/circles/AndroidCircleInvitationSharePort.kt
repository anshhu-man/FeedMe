package com.feedme.app.circles

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Attach to one live Activity generation, never to the retained meal experience. The
 * owning UI must retire [isCurrentActivity] before replacing this Activity attachment.
 * Android only tells us that startActivity returned; this adapter never claims delivery
 * or infers chooser cancellation from an Activity result. No manifest permission is needed. */
class AndroidCircleInvitationSharePort(
    private val activity: Activity,
    private val isCurrentActivity: () -> Boolean,
) : CircleInvitationSharePort {
    override suspend fun openChooser(request: CircleInvitationShareRequest): PortResult<CircleInvitationShareOutcome> =
        withContext(Dispatchers.Main.immediate) {
            if (!live()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            try {
                when (val used = request.useForLaunch { text ->
                    // No suspension from the exact one-use capability check through launch.
                    if (!live()) throw StaleActivity()
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    activity.startActivity(Intent.createChooser(send, null))
                }) {
                    is PortResult.Failure -> used
                    is PortResult.Value -> PortResult.Value(CircleInvitationShareOutcome.OPENED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StaleActivity) {
                PortResult.Failure(FailureReason.STALE_SESSION)
            } catch (_: ActivityNotFoundException) {
                PortResult.Failure(FailureReason.NOT_CONFIGURED)
            } catch (_: SecurityException) {
                PortResult.Failure(FailureReason.FORBIDDEN)
            } catch (_: RuntimeException) {
                // The request stays consumed. Never reconstruct its URL or retry launch.
                PortResult.Failure(FailureReason.UNAVAILABLE)
            }
        }

    private fun live(): Boolean = isCurrentActivity() && !activity.isFinishing &&
        !activity.isDestroyed && activity.window?.decorView?.isAttachedToWindow == true &&
        activity.hasWindowFocus()

    private class StaleActivity : RuntimeException()
    override fun toString() = "AndroidCircleInvitationSharePort(<redacted>)"
}
