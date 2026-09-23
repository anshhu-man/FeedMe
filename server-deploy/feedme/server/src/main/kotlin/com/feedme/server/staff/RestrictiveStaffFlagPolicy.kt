package com.feedme.server.staff

import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The legacy direct flag operation is an emergency kill switch, not a second
 * rollout path. Exposure increases and partial rollout changes must use the
 * proposal plus distinct-approver workflow.
 *
 * Keeping this rule independent of HTTP and storage makes it impossible for a
 * future route or database adapter to reinterpret a permissive FlagWrite body.
 */
internal object RestrictiveStaffFlagPolicy {
    private val key = Regex("[a-z][a-z0-9_.-]{1,100}")
    private val requiredBodyKeys = setOf("enabled", "rolloutPercent", "reason")

    fun directUpdate(flagKey: String, currentEnabled: Boolean, currentRevision: Long,
        expectedRevision: Long, body: JsonObject): RestrictiveStaffFlagDecision {
        if (!key.matches(flagKey) || currentRevision !in 1 until Long.MAX_VALUE ||
            expectedRevision < 1 || body.keys != requiredBodyKeys) invalid()
        if (expectedRevision != currentRevision) fail(RestrictiveStaffFlagFailureCode.VERSION_CONFLICT)

        val enabledValue = body["enabled"]?.jsonPrimitive ?: invalid()
        if (enabledValue.isString) invalid()
        val enabled = enabledValue.booleanOrNull ?: invalid()
        val rolloutValue = body["rolloutPercent"]?.jsonPrimitive ?: invalid()
        if (rolloutValue.isString) invalid()
        val rollout = rolloutValue.contentOrNull?.let {
            try { BigDecimal(it) } catch (_: NumberFormatException) { invalid() }
        } ?: invalid()
        val reasonValue = body["reason"]?.jsonPrimitive ?: invalid()
        if (!reasonValue.isString) invalid()
        val reason = reasonValue.contentOrNull ?: invalid()
        if (reason.length !in 10..1000 || reason.isBlank() || reason.any(Char::isISOControl)) invalid()

        // Direct PATCH is intentionally narrower than the wire schema: only a
        // complete kill is legal. A partial reduction can later be raised again
        // without independent review and therefore belongs to the proposal path.
        if (enabled || rollout.compareTo(BigDecimal.ZERO) != 0)
            fail(RestrictiveStaffFlagFailureCode.REVIEW_REQUIRED)
        if (!currentEnabled) fail(RestrictiveStaffFlagFailureCode.ALREADY_RESTRICTED)

        return RestrictiveStaffFlagDecision(flagKey, currentRevision,
            currentRevision + 1, enabled = false, rolloutPercent = BigDecimal.ZERO,
            reason = reason)
    }

    private fun invalid(): Nothing = fail(RestrictiveStaffFlagFailureCode.INPUT_INVALID)
    private fun fail(code: RestrictiveStaffFlagFailureCode): Nothing =
        throw RestrictiveStaffFlagFailure(code)
}

internal data class RestrictiveStaffFlagDecision(val flagKey: String, val baseRevision: Long,
    val revision: Long, val enabled: Boolean, val rolloutPercent: BigDecimal, val reason: String)

internal enum class RestrictiveStaffFlagFailureCode {
    INPUT_INVALID,
    VERSION_CONFLICT,
    REVIEW_REQUIRED,
    ALREADY_RESTRICTED,
}

internal class RestrictiveStaffFlagFailure(val code: RestrictiveStaffFlagFailureCode) :
    RuntimeException("Restrictive staff flag update unavailable: ${code.name}")
