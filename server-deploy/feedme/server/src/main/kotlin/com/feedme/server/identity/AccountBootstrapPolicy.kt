package com.feedme.server.identity

import com.feedme.server.auth.VerifiedSupabaseSubject
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

enum class AccountEligibility(val wire: String) { PENDING("pending"), ELIGIBLE("eligible"), INELIGIBLE("ineligible") }
enum class AccountFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), POLICY_BLOCKED(403), ACCOUNT_UNAVAILABLE(404),
    HANDLE_UNAVAILABLE(409), VERSION_CONFLICT(412), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
class AccountFailure(val code: AccountFailureCode) : RuntimeException("Account operation unavailable: ${code.name}")

/** Locked app facts, not request identity. The profile's canonical id is the app account id.
 * Neither the separate private principal nor FeedMe device id is a provider session id. */
class AccountPolicyFacts internal constructor(val accountId: UUID, val principalId: UUID,
    val eligibility: AccountEligibility, val eligibilityPolicyVersion: String,
    val acceptedTermsVersion: String?) {
    override fun toString() = "AccountPolicyFacts(<redacted>)"
}

/** Required, independently evaluated policy output. No entitlement/feature is inferred from
 * JWT presence, provider metadata, an age checkbox or a completed profile. */
class AccountPolicySnapshot(val eligibility: AccountEligibility, val eligibilityPolicyVersion: String,
    val requiredTermsVersion: String, flags: JsonArray, entitlements: JsonArray) {
    val flags: JsonArray = Json.parseToJsonElement(flags.toString()).jsonArray
    val entitlements: JsonArray = Json.parseToJsonElement(entitlements.toString()).jsonArray
    init {
        require(eligibilityPolicyVersion.length in 1..256 && requiredTermsVersion.length in 1..256)
        require(eligibilityPolicyVersion.none(Char::isISOControl) && requiredTermsVersion.none(Char::isISOControl))
        require(this.flags.size <= 128 && this.entitlements.size <= 128)
    }
    override fun toString() = "AccountPolicySnapshot(<redacted>)"
}

class AccountBootstrapDecision(val policy: AccountPolicySnapshot, val acceptSubmittedTerms: Boolean) {
    override fun toString() = "AccountBootstrapDecision(<redacted>)"
}

/** Actual locked previous installation binding, never constructed from request identifiers. */
class AccountRegisteredDevice internal constructor(val deviceSessionId: UUID,
    val providerSessionId: UUID,val platform: String,val installationHash: String,val version: Long) {
    override fun toString() = "AccountRegisteredDevice(<redacted>)"
}

/** Mandatory production integration, NO implementation or accepting defaults in this slice.
 * All methods run inside the caller's PostgreSQL transaction, do DB work only, and never
 * commit, perform network I/O or emit external effects. Before acquiring app roots, lockProvider
 * MUST lock authoritative current issuer/subject/session state, reject revoked/deleted/banned,
 * unconfirmed, anonymous and recovery-only identities, and require current verification data.
 * A signature-valid VerifiedSupabaseSubject alone is insufficient. Provider-state/lifecycle
 * writers must use these same roots and ordering; stale/unavailable authority fails closed.
 *
 * Global order: provider policy roots -> exact issuer/subject advisory lock -> locked
 * account/principal roots -> device/idempotency -> profile -> handle claim. Bootstrap
 * can resolve its newly registered or cached device under its receipt lock; both orders remain
 * safe ONLY because every account/device/receipt writer first holds that same exclusive account
 * root. Never introduce a device-first revoker. Every onboarding-policy writer must honor this
 * order. V011 advances the profile version atomically when represented eligibility changes;
 * separate policy writers still own their required policy audit/events. A snapshot represents the actual locked
 * eligibility, current terms requirement, rollout flags and entitlements, not client declarations.
 * Existing-account snapshots must agree with persisted policy facts, or fail until the explicit
 * policy writer reconciles them. No policy changes are silently performed by GET.
 */
interface AccountBootstrapPolicy {
    fun lockProvider(connection: Connection, subject: VerifiedSupabaseSubject)
    fun bootstrap(connection: Connection, subject: VerifiedSupabaseSubject, input: JsonObject,
        existing: AccountPolicyFacts?): AccountBootstrapDecision
    fun current(connection: Connection, subject: VerifiedSupabaseSubject, account: AccountPolicyFacts): AccountPolicySnapshot
    /** Explicit independent decision for this same-account installation replacement. Current
     * signed-in identity plus installationId possession is not sufficient consent/authority.
     * The previous device is locked and belongs to account; the incoming provider session is
     * independently current. Return false when replacement is not authorized. */
    fun allowDeviceReplacement(connection: Connection, subject: VerifiedSupabaseSubject,
        account: AccountPolicyFacts, previous: AccountRegisteredDevice): Boolean
    /** Check actual preference/equipment prerequisites for onward steps, current independent
     * eligibility/terms, and actual owned ready-media authority if avatarMediaId changes. No
     * request boolean, step string or guessed media id can stand in for these checks. */
    fun authorizeProfileUpdate(connection: Connection, subject: VerifiedSupabaseSubject,
        account: AccountPolicyFacts, before: JsonObject, proposed: JsonObject)
}
