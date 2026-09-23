package com.feedme.android

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import androidx.core.net.toUri
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.session.GoogleOAuthCredentialRequest
import com.feedme.session.GoogleOAuthCredentials
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Activity-owned browser request. Backgrounding for the browser is permitted; finishing,
 * recreation, explicit Back or return without a matching callback retires the attempt.
 * No Activity reference, code, verifier or consent is persisted across process loss. */
internal class AndroidSupabaseOAuth(
    activity: Activity,
    private val owner: GoogleOAuthBrowserOwner,
    private val issuer: String,
    private val redirect: String,
    private val isCurrent: () -> Boolean,
    /** Internal native-test seam. Production AccountActivity always uses the system default. */
    private val browserLauncher: (Activity, Intent) -> Unit = { host, intent -> host.startActivity(intent) },
) : GoogleOAuthCredentialRequest {
    private val activity = WeakReference(activity)
    private var active: GoogleOAuthBrowserAttempt? = null
    private var departed = false
    private var closed = false

    override suspend fun request(): PortResult<GoogleOAuthCredentials> = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        val host = activity.get()
        if (closed || host == null || host.isFinishing || host.isDestroyed || !isCurrent())
            return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (redirect != "${host.packageName}://auth/callback")
            return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (active != null) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        val prepared = owner.begin(issuer, redirect, onReturn = {
            val manager = host.getSystemService(ActivityManager::class.java)
            manager.appTasks.singleOrNull { it.taskInfo.taskId == host.taskId }?.moveToFront()
        }) {
            !closed && !host.isFinishing && !host.isDestroyed && activity.get() === host && isCurrent()
        }
        if (prepared is PortResult.Failure) return@withContext prepared
        val attempt = (prepared as PortResult.Value).value
        active = attempt
        departed = false
        try {
            val url = attempt.authorizationUrl ?: return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            // Android's system browser, never an embedded WebView or a Google-token shortcut.
            browserLauncher(host, Intent(Intent.ACTION_VIEW, url.use { it }.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
            val result = withTimeoutOrNull(GoogleOAuthBrowserOwner.LIFETIME_MILLIS) { attempt.completion.await() }
                ?: return@withContext PortResult.Failure(FailureReason.UNAUTHENTICATED)
            currentCoroutineContext().ensureActive()
            if (closed || host.isFinishing || host.isDestroyed || !isCurrent())
                PortResult.Failure(FailureReason.STALE_SESSION) else result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            owner.unavailable(attempt)
            PortResult.Failure(FailureReason.UNAVAILABLE)
        } finally {
            owner.cancel(attempt)
            if (active === attempt) { active = null; departed = false }
        }
    }

    fun backgrounded() { if (active != null) departed = true }
    fun returned() { if (departed) active?.let(owner::cancel) }
    fun close() { closed = true; active?.let(owner::cancel); activity.clear() }
    override fun toString() = "AndroidSupabaseOAuth(<redacted>)"
}
