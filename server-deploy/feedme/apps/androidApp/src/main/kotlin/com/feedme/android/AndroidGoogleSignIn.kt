package com.feedme.android

import android.app.Activity
import android.content.MutableContextWrapper
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.session.GoogleAccountCredentialRequest
import com.feedme.session.GoogleAccountCredentials
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Explicit foreground Google picker only. The calling Activity must use its lifecycle
 * coroutine scope; this object never launches a scope, retries, saves tokens or admits an
 * account. Supabase verification and the retained session owner remain authoritative. */
internal class AndroidGoogleSignIn(activity: Activity, private val webClientId: String) : GoogleAccountCredentialRequest {
    private val activity = WeakReference(activity)
    private val requesting = AtomicBoolean(false)

    override suspend fun request(): PortResult<GoogleAccountCredentials> = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        if (!isGoogleWebClientId(webClientId)) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val owner = activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (!requesting.compareAndSet(false, true)) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        // Do not keep a foreground Activity in a provider-owned context after completion.
        val context = MutableContextWrapper(owner)
        try {
            val rawNonce = googleSignInNonce()
            val option = GetSignInWithGoogleOption.Builder(webClientId)
                .setNonce(googleSignInNonceHash(rawNonce)).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val result = CredentialManager.create(owner.applicationContext).getCredential(context, request)
            currentCoroutineContext().ensureActive()
            if (owner.isFinishing || owner.isDestroyed || activity.get() !== owner)
                return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            val credential = result.credential as? CustomCredential
                ?: return@withContext PortResult.Failure(FailureReason.INVALID_DATA)
            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                return@withContext PortResult.Failure(FailureReason.INVALID_DATA)
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
            // Structural bound only. Never decode a local JWT into account authority.
            if (token.length !in 1..32_768 || token.any(Char::isWhitespace) || token.any(Char::isISOControl))
                return@withContext PortResult.Failure(FailureReason.INVALID_DATA)
            PortResult.Value(GoogleAccountCredentials(SecretText(token), SecretText(rawNonce)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GetCredentialCancellationException) {
            throw CancellationException("Google sign-in cancelled.")
        } catch (_: GoogleIdTokenParsingException) {
            PortResult.Failure(FailureReason.INVALID_DATA)
        } catch (_: GetCredentialException) {
            PortResult.Failure(FailureReason.UNAVAILABLE)
        } catch (_: Exception) {
            // No exception message/cause may carry provider data into diagnostics or UI.
            PortResult.Failure(FailureReason.UNAVAILABLE)
        } finally {
            context.baseContext = owner.applicationContext
            requesting.set(false)
        }
    }

    override fun toString() = "AndroidGoogleSignIn(<redacted>)"
}

/** 256 fresh random bits; Google receives SHA-256 while Supabase receives the raw string. */
internal fun googleSignInNonce(): String {
    val bytes = ByteArray(32)
    return try { SecureRandom().nextBytes(bytes); bytes.hex() } finally { bytes.fill(0) }
}

internal fun googleSignInNonceHash(rawNonce: String): String {
    require(rawNonce.matches(Regex("[0-9a-f]{64}")))
    val bytes = rawNonce.toByteArray(Charsets.US_ASCII)
    val hash = try { MessageDigest.getInstance("SHA-256").digest(bytes) } finally { bytes.fill(0) }
    return try { hash.hex() } finally { hash.fill(0) }
}

private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
