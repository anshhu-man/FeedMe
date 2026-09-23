package com.feedme.server.staff

internal class StaffModerationPolicy(val maxResponseBytes: Int, val cursorLifetimeSeconds: Long,
    val entitlementPipelineEnabled: Boolean, val incidentRegistryEnabled: Boolean) {
    init { require(maxResponseBytes in 4096..262144 && cursorLifetimeSeconds in 1..86400) }
    override fun toString() = "StaffModerationPolicy(<redacted>)"
}

internal enum class StaffModerationFailureCode(val status: Int) {
    INPUT_INVALID(422), CASE_UNAVAILABLE(404), CASE_CONFLICT(409), VERSION_CONFLICT(412),
    FLAG_UNAVAILABLE(404), FLAG_CONFLICT(409), REVIEW_REQUIRED(422),
    CURSOR_EXPIRED(410), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
internal class StaffModerationFailure(val code: StaffModerationFailureCode) :
    RuntimeException("Staff moderation unavailable: ${code.name}")
