package com.feedme.server.social

import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

/** Construct only after a trusted adapter verifies provider identity and its registered device session. */
class VerifiedSocialAccount(val environment: String, val accountId: UUID, val deviceSessionId: UUID) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }
    override fun toString() = "VerifiedSocialAccount(<redacted>)"
}

/**
 * Required integration, with NO permissive default and NO JWT/provider implementation here.
 * All calls run in the caller's PostgreSQL transaction. Implementations must perform DB work
 * only, never commit, call remote services or emit effects. lockPrincipal must revalidate and
 * lock current account eligibility, suspension/deletion and device-session binding/revocation.
 * Acquire principal locks before durable command locks; circle locks precede block-pair locks.
 * Block/unblock writers must share those pair locks AND atomically invalidate affected invite
 * relationships as required by F42; merely removing a boolean block must never resurrect an
 * invite cancelled by blocking. This persistence slice does not implement that writer.
 * Account lifecycle handlers must respect
 * the same ordering. readProfile returns only approved public/circle display fields, not email,
 * provider subject, preferences, credentials or a fabricated profile for a missing account.
 * It must lock current profile eligibility until commit, return CIRCLE_UNAVAILABLE for hidden or
 * ineligible accounts, and reserve STORAGE_UNAVAILABLE/NOT_CONFIGURED for actual adapter failure.
 * lockUnblockedPair must throw CIRCLE_UNAVAILABLE for a block in either direction.
 * A production adapter and cross-module lock-order review remain mandatory before HTTP wiring.
 */
interface SocialIdentityPolicy {
    fun lockPrincipal(connection: Connection, principal: VerifiedSocialAccount)
    fun requireCreationEnabled(connection: Connection, principal: VerifiedSocialAccount, invitations: Boolean)
    fun lockUnblockedPair(connection: Connection, environment: String, first: UUID, second: UUID)
    fun readProfile(connection: Connection, environment: String, accountId: UUID): SocialProfileSummary
}

class SocialProfileSummary(val userId: UUID, val displayName: String, val handle: String, val avatarMediaId: UUID? = null) {
    internal fun json() = buildJsonObject {
        put("userId", userId.toString()); put("displayName", displayName); put("handle", handle)
        avatarMediaId?.let { put("avatarMediaId", it.toString()) }
    }
    override fun toString() = "SocialProfileSummary(<redacted>)"
}

enum class SocialFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), CIRCLE_UNAVAILABLE(404), INVITATION_UNAVAILABLE(404),
    INVITATION_USED(409), INVITATION_EXPIRED(410), CIRCLE_FULL(409), OWNER_TRANSFER_REQUIRED(409),
    NOT_OWNER(403), VERSION_CONFLICT(412), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}

/** Safe to map to a canonical Problem; never retain SQL/provider/token/profile values or causes. */
class SocialFailure(val code: SocialFailureCode) : RuntimeException("Social operation unavailable: ${code.name}")

/** Reviewed launch policy, not additional JSON-schema constraints. F23 proposes 50 and seven days. */
class CircleLaunchPolicy(val memberLimit: Int, val invitationLifetimeHours: Int) {
    init { require(memberLimit in 2..50 && invitationLifetimeHours in 1..168) }
    override fun toString() = "CircleLaunchPolicy(memberLimit=$memberLimit, invitationLifetimeHours=$invitationLifetimeHours)"
}
