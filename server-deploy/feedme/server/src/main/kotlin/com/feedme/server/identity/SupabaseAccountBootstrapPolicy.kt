package com.feedme.server.identity

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.core.FeedMeAdultPolicy
import java.sql.Connection
import kotlinx.serialization.json.*

/** Explicit server-owned rules for the first account/profile vertical, not a rollout or
 * automatic eligibility grant. First-use eligibility stays PENDING unless the explicit
 * adult self-attestation policy is enabled and the exact new declaration/Terms are supplied.
 * That policy records a declaration, not verified age. Editable provider metadata never
 * supplies it. Existing eligibility is read from locked
 * identity.users facts by AccountProfileStore, never silently promoted/reconciled here.
 * No flags or entitlements are enabled by this bounded policy. A broader policy requires
 * its own authoritative readers/writers, tests and explicit configured assembly.
 */
class AccountPendingProfileRules(
    val eligibilityPolicyVersion: String,
    val requiredTermsVersion: String,
    val acceptExactSubmittedTerms: Boolean,
    val adultSelfAttestationEnabled: Boolean = false,
) {
    init {
        require(listOf(eligibilityPolicyVersion, requiredTermsVersion).all {
            it.length in 1..256 && it.none(Char::isISOControl)
        }) { "Invalid account policy configuration" }
        require(!adultSelfAttestationEnabled ||
            (eligibilityPolicyVersion == FeedMeAdultPolicy.ELIGIBILITY_POLICY_VERSION &&
                requiredTermsVersion == FeedMeAdultPolicy.TERMS_VERSION)) { "Invalid adult self-attestation policy" }
    }
    override fun toString() = "AccountPendingProfileRules(<redacted>)"
}

/** Actual provider rows and locked FeedMe facts; no policy callback supplied by a request.
 * The caller retains its transaction throughout each method. AccountProfileStore enforces
 * explicit transaction-bound decisions for optional onward steps. These decisions do not
 * grant eligibility/terms, media authority, device replacement or social admission.
 */
class SupabaseAccountBootstrapPolicy(
    private val authority: SupabasePostgresAuthority,
    private val rules: AccountPendingProfileRules,
) : AccountBootstrapPolicy {
    override fun lockProvider(connection: Connection, subject: VerifiedSupabaseSubject) = authority.lockCurrent(connection, subject)

    override fun bootstrap(connection: Connection, subject: VerifiedSupabaseSubject, input: JsonObject,
        existing: AccountPolicyFacts?): AccountBootstrapDecision {
        currentProvider(connection, subject)
        val submitted = (input["termsVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val acceptsTerms = rules.acceptExactSubmittedTerms && submitted == rules.requiredTermsVersion
        val declared = input["eligibilityDeclaration"] == JsonPrimitive(FeedMeAdultPolicy.BOOTSTRAP_DECLARATION)
        val state = if (existing == null && rules.adultSelfAttestationEnabled && acceptsTerms && declared)
            AccountEligibility.ELIGIBLE else null
        return AccountBootstrapDecision(snapshot(existing, state), acceptsTerms)
    }

    override fun current(connection: Connection, subject: VerifiedSupabaseSubject, account: AccountPolicyFacts): AccountPolicySnapshot {
        currentProvider(connection, subject)
        return snapshot(account)
    }

    override fun allowDeviceReplacement(connection: Connection, subject: VerifiedSupabaseSubject,
        account: AccountPolicyFacts, previous: AccountRegisteredDevice): Boolean {
        currentProvider(connection, subject)
        snapshot(account)
        return false
    }

    override fun authorizeProfileUpdate(connection: Connection, subject: VerifiedSupabaseSubject,
        account: AccountPolicyFacts, before: JsonObject, proposed: JsonObject) {
        currentProvider(connection, subject)
        snapshot(account)
        if (before["id"] != proposed["id"] || before["eligibility"] != proposed["eligibility"] ||
            before["avatarMediaId"] != proposed["avatarMediaId"]) blocked()
        val old = (before["onboardingStep"] as? JsonPrimitive)?.content
        val next = (proposed["onboardingStep"] as? JsonPrimitive)?.content
        val steps=listOf("profile","preferences","equipment","ready")
        if(old !in steps || next !in steps || steps.indexOf(next) !in steps.indexOf(old)..minOf(steps.indexOf(old)+1,3)) blocked()
        if(next!="profile" && (proposed["displayName"] !is JsonPrimitive || proposed["handle"] !is JsonPrimitive)) blocked()
        if(next=="ready" && (account.eligibility!=AccountEligibility.ELIGIBLE || account.acceptedTermsVersion!=rules.requiredTermsVersion)) blocked()
    }

    private fun currentProvider(connection: Connection, subject: VerifiedSupabaseSubject) = authority.lockCurrent(connection, subject)
    private fun snapshot(account: AccountPolicyFacts?, newEligibility: AccountEligibility? = null): AccountPolicySnapshot {
        if (account != null && account.eligibilityPolicyVersion != rules.eligibilityPolicyVersion) blocked()
        return AccountPolicySnapshot(account?.eligibility ?: newEligibility ?: AccountEligibility.PENDING,
            rules.eligibilityPolicyVersion, rules.requiredTermsVersion, JsonArray(emptyList()), JsonArray(emptyList()))
    }
    override fun toString() = "SupabaseAccountBootstrapPolicy(<redacted>)"
    private fun blocked(): Nothing = throw AccountFailure(AccountFailureCode.POLICY_BLOCKED)
}
