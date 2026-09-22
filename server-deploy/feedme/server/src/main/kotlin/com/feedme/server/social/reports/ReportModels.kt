package com.feedme.server.social.reports

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ReportServicePolicy(val maxResponseBytes: Int) {
    init { require(maxResponseBytes in 1..262144) }
    override fun toString() = "ReportServicePolicy(<redacted>)"
}
enum class ReportFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), TARGET_UNAVAILABLE(404), REPORT_UNAVAILABLE(404),
    NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
class ReportFailure(val code: ReportFailureCode) : RuntimeException("Report operation unavailable: ${code.name}")

/** Detached restricted material captured by actual current target authority, not an identifier
 * or a caller-manufactured access grant. It is NEVER included in reporter replies or events. */
class ReportTargetEvidence(val targetType: String, val targetId: UUID, val targetOwnerId: UUID,
    val version: Long, material: JsonObject, val validUntil: Instant?) {
    val material: JsonObject
    init {
        require(targetType in REPORT_TARGET_TYPES && version > 0)
        val bytes = material.toString().encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.size <= 262144)
        this.material = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
    }
    override fun toString() = "ReportTargetEvidence(<redacted>)"
}

/** Mandatory real object admission on the caller's transaction. Authenticate current audience,
 * ownership/placement and target version; acquire the SAME fences as mutation/removal writers.
 * Acquire foreign roots without waiting if they reverse account/target lock order; propagate
 * retryable SQL to the owning transaction. No network, hidden transaction, commit, mutation or
 * accepting fallback. Safety reporting need not require onboarding/Terms/social creation.
 * Repeated capture must return the same exact material/deadline or the new report rolls back.
 * Unsupported target integration is NOT_CONFIGURED, not a false target-not-found or approval. */
fun interface ReportTargetAuthority {
    fun capture(connection: Connection, reporterId: UUID, targetType: String, targetId: UUID): ReportTargetEvidence
}

internal val REPORT_TARGET_TYPES = setOf("post", "message", "user", "shortcut")
