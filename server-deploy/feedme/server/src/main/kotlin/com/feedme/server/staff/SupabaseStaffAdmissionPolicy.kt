package com.feedme.server.staff

/** Optional staff admission, with separately opted-in draft authoring/editorial review. This does not configure an
 * OAuth client or grant any staff operation. policyClientId is the exact V040 registry
 * binding selected by the operator, NOT a client_id claim synthesized from a user JWT. */
internal class SupabaseStaffAdmissionPolicy(
    val policyVersion: String,
    val policyClientId: String,
    val maximumTotpAgeSeconds: Long,
    val observationSeconds: Long,
    val maxResponseBytes: Int,
    val catalogDraftsEnabled: Boolean = false,
    val catalogReviewsEnabled: Boolean = false,
    val catalogPublicationEnabled: Boolean = false,
    val moderationEnabled: Boolean = false,
) {
    init {
        require(!catalogReviewsEnabled || catalogDraftsEnabled) { "Staff reviews require draft access" }
        require(!catalogPublicationEnabled || catalogReviewsEnabled) { "Staff publication requires review access" }
        require(policyVersion.length in 1..128 && policyVersion.isNotBlank() && policyVersion.none(Char::isISOControl) &&
            policyClientId.length in 1..256 && policyClientId.isNotBlank() && policyClientId.none(Char::isISOControl) &&
            maximumTotpAgeSeconds in 1..900 && observationSeconds in 1..60 &&
            observationSeconds <= maximumTotpAgeSeconds && maxResponseBytes in 1024..16384) {
            "Invalid Supabase staff admission policy"
        }
    }
    override fun toString() = "SupabaseStaffAdmissionPolicy(<redacted>)"
}

internal enum class SupabaseStaffFailureCode(val status: Int) {
    STAFF_UNAUTHENTICATED(401), STAFF_MFA_REQUIRED(403), STAFF_ACCESS_DENIED(403),
    STAFF_NOT_CONFIGURED(503), STAFF_UNAVAILABLE(503),
}

internal class SupabaseStaffFailure(val code: SupabaseStaffFailureCode) :
    RuntimeException("Supabase staff admission unavailable: ${code.name}")
