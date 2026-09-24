package com.feedme.server.identity

import com.feedme.core.ports.SecretText
import com.feedme.server.auth.VerifiedSupabaseSubject
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Candidate policy for the email-only support handoff. It does not accept an email
 * address, send mail, select an account, consume a challenge or authorize deletion.
 * A future durable store must atomically consume the exact challenge and persist the
 * resulting subject binding before this evidence can become operational authority.
 */
internal class AccountDeletionSupportOwnershipPolicy(
    val version: String,
    val maximumChallengeLifetimeSeconds: Long,
    val maximumOAuthAgeSeconds: Long,
    val maximumIdentityObservationAgeSeconds: Long,
) {
    init {
        require(version.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}")))
        require(maximumChallengeLifetimeSeconds in 60..3_600)
        require(maximumOAuthAgeSeconds in 30..900)
        require(maximumIdentityObservationAgeSeconds in 1..300)
    }

    override fun toString() = "AccountDeletionSupportOwnershipPolicy(<redacted>)"
}

/** A current trusted-provider observation, never token claims or user-editable metadata. */
internal class CurrentSupabaseGoogleIdentityObservation(
    val issuer: String,
    val subject: UUID,
    val providerSessionId: UUID,
    val provider: String,
    val oauthAuthenticatedAt: Instant,
    val observedAt: Instant,
    val validUntil: Instant,
) {
    init {
        require(issuer.startsWith("https://") && issuer.endsWith(".supabase.co/auth/v1"))
        require(subject != ZERO_UUID && providerSessionId != ZERO_UUID && subject != providerSessionId &&
            provider == "google" && oauthAuthenticatedAt <= observedAt && observedAt < validUntil)
    }

    override fun toString() = "CurrentSupabaseGoogleIdentityObservation(<redacted>)"
}

/** Only a digest is retained. The raw 256-bit challenge is caller-owned secret input. */
internal class AccountDeletionSupportOwnershipChallenge private constructor(
    val id: UUID,
    tokenSha256: ByteArray,
    val policyVersion: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val consumedAt: Instant?,
) {
    private val retainedTokenSha256 = tokenSha256.copyOf()

    init {
        require(id != ZERO_UUID && tokenSha256.size == SHA256_BYTES)
        require(policyVersion.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}")))
        require(issuedAt < expiresAt && consumedAt?.let { it >= issuedAt && it < expiresAt } != false)
    }

    internal fun matchesToken(candidateSha256: ByteArray): Boolean =
        MessageDigest.isEqual(retainedTokenSha256, candidateSha256)

    internal fun digestForPersistence(): ByteArray = retainedTokenSha256.copyOf()

    override fun toString() = "AccountDeletionSupportOwnershipChallenge(<redacted>)"

    companion object {
        fun issue(id: UUID, token: SecretText, policyVersion: String, issuedAt: Instant,
            expiresAt: Instant): AccountDeletionSupportOwnershipChallenge =
            AccountDeletionSupportOwnershipChallenge(id, challengeDigest(token), policyVersion,
                issuedAt, expiresAt, null)

        /** Persistence restoration seam; it deliberately accepts no email or support text. */
        fun restore(id: UUID, tokenSha256: ByteArray, policyVersion: String, issuedAt: Instant,
            expiresAt: Instant, consumedAt: Instant?): AccountDeletionSupportOwnershipChallenge =
            AccountDeletionSupportOwnershipChallenge(id, tokenSha256.copyOf(), policyVersion,
                issuedAt, expiresAt, consumedAt)

        /** Shared issuance seam; callers own and clear the returned digest copy. */
        internal fun digestForPersistence(token: SecretText): ByteArray = challengeDigest(token)
    }
}

/**
 * Short-lived same-subject result only. It is not a deletion receipt, account lookup,
 * device registration, consent record or completion claim.
 */
internal class AccountDeletionSupportOwnershipEvidence internal constructor(
    val challengeId: UUID,
    val policyVersion: String,
    val issuer: String,
    val subject: UUID,
    val providerSessionId: UUID,
    val verifiedAt: Instant,
    val validUntil: Instant,
) {
    override fun toString() = "AccountDeletionSupportOwnershipEvidence(<redacted>)"
}

/**
 * Returns null for every invalid/expired/mismatched input so public callers cannot use
 * challenge handling to enumerate accounts or linked identities.
 */
internal object AccountDeletionSupportOwnershipVerifier {
    fun verify(challenge: AccountDeletionSupportOwnershipChallenge, token: SecretText,
        subject: VerifiedSupabaseSubject, google: CurrentSupabaseGoogleIdentityObservation,
        now: Instant, policy: AccountDeletionSupportOwnershipPolicy): AccountDeletionSupportOwnershipEvidence? =
        try {
            if (challenge.policyVersion != policy.version || challenge.consumedAt != null ||
                now < challenge.issuedAt || now >= challenge.expiresAt ||
                challenge.expiresAt > challenge.issuedAt.plusSeconds(policy.maximumChallengeLifetimeSeconds) ||
                subject.issuer != google.issuer || subject.subject != google.subject ||
                subject.providerSessionId != google.providerSessionId ||
                subject.subject == subject.providerSessionId ||
                google.provider != "google" ||
                now.epochSecond < subject.issuedAtEpochSeconds || now.epochSecond >= subject.expiresAtEpochSeconds ||
                google.observedAt < challenge.issuedAt || google.observedAt > now ||
                google.observedAt.plusSeconds(policy.maximumIdentityObservationAgeSeconds) <= now ||
                now >= google.validUntil) return null

            val oauthAt = google.oauthAuthenticatedAt
            if (oauthAt < challenge.issuedAt || oauthAt > now ||
                oauthAt.plusSeconds(policy.maximumOAuthAgeSeconds) <= now) return null

            val supplied = challengeDigest(token)
            val matches = try { challenge.matchesToken(supplied) }
                finally { supplied.fill(0) }
            if (!matches) return null

            val validUntil = listOf(challenge.expiresAt, Instant.ofEpochSecond(subject.expiresAtEpochSeconds),
                oauthAt.plusSeconds(policy.maximumOAuthAgeSeconds),
                google.observedAt.plusSeconds(policy.maximumIdentityObservationAgeSeconds),
                google.validUntil).min()
            if (now >= validUntil) return null
            AccountDeletionSupportOwnershipEvidence(challenge.id, policy.version, subject.issuer,
                subject.subject, subject.providerSessionId, now, validUntil)
        } catch (_: DateTimeException) { null }
          catch (_: ArithmeticException) { null }
          catch (_: IllegalArgumentException) { null }
}

private fun challengeDigest(token: SecretText): ByteArray = token.use { raw ->
    require(raw.matches(Regex("[A-Za-z0-9_-]{43}")))
    val bytes = Base64.getUrlDecoder().decode(raw)
    try {
        require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == raw)
        MessageDigest.getInstance("SHA-256").digest(bytes)
    } finally { bytes.fill(0) }
}

private const val SHA256_BYTES = 32
private val ZERO_UUID: UUID = UUID(0, 0)
