package com.feedme.development.progress

import com.feedme.core.ports.*
import com.feedme.session.*

/** Isolated development variant only. These are declared fixtures, never provider credentials. */
internal object ProgressIdentity : NativeSessionVerifier {
    val scope = StorageScope("feedme-progress-synthetic-v1", ActorKind.ACCOUNT, "00000000-0000-4000-8000-000000000101")
    val configurationBinding = "52bdd8f1f99b326db22e9f19a74cf2497e02a8302fdb434f7f4a621759875647"
    const val deviceSessionId = "00000000-0000-4000-8000-000000000102"
    private const val ACCESS = "FEEDME-SYNTHETIC-PROGRESS-ACCESS-NOT-A-REAL-TOKEN"
    private const val REFRESH = "FEEDME-SYNTHETIC-PROGRESS-REFRESH-NOT-A-REAL-TOKEN"

    override suspend fun acquire(): PortResult<StoredCredentials> = PortResult.Value(
        StoredCredentials.Account(scope, SecretText(ACCESS), SecretText(REFRESH), Long.MAX_VALUE, SecretText(deviceSessionId)))

    override suspend fun restore(snapshot: CredentialSnapshot): PortResult<PrivateSessionAccessMode> =
        if (matches(snapshot.credentials, requireRefresh = true)) PortResult.Value(PrivateSessionAccessMode.ONLINE)
        else PortResult.Failure(FailureReason.UNAUTHENTICATED)

    // Transport views intentionally omit refresh credentials. Neither path accepts arbitrary users.
    fun matches(credentials: StoredCredentials?, requireRefresh: Boolean = false): Boolean {
        val account = credentials as? StoredCredentials.Account ?: return false
        return account.scope == scope && account.expiresAtMillis == Long.MAX_VALUE &&
            account.accessToken.use { it == ACCESS } && account.deviceSessionId?.use { it == deviceSessionId } == true &&
            (!requireRefresh || account.refreshToken?.use { it == REFRESH } == true)
    }
}
