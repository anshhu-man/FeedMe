package com.feedme.kitchen

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import kotlinx.serialization.json.*

/** Exact nonnegative integral value; a 4,096-digit local metadata bound, never Double conversion. */
internal fun integerValue(token: String): String {
    val parts = token.removePrefix("-").lowercase().split('e')
    val mantissa = parts[0]
    if (!token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) PrivateJson.invalid()
    val digits = mantissa.replace(".", "").trimStart('0')
    if (digits.isEmpty()) return "0"
    if (token.startsWith('-')) PrivateJson.invalid()
    val fractional = if ('.' in mantissa) mantissa.length - mantissa.indexOf('.') - 1 else 0
    val exponent = (parts.getOrNull(1) ?: "0").toIntOrNull() ?: PrivateJson.invalid()
    val length = digits.length.toLong() + exponent - fractional
    if (length !in 1..4096) PrivateJson.invalid()
    return if (length >= digits.length) digits + "0".repeat(length.toInt() - digits.length)
        else digits.take(length.toInt()).also { if (digits.drop(length.toInt()).any { it != '0' }) PrivateJson.invalid() }
}

internal fun documentVersion(document: WireDocument): String {
    val value = document.json().jsonObject.getValue("version").jsonPrimitive
    if (value.isString) PrivateJson.invalid()
    return integerValue(value.content).also { if (it == "0") PrivateJson.invalid() }
}

internal fun compareVersions(left: String, right: String): Int =
    if (left.length != right.length) left.length.compareTo(right.length) else left.compareTo(right)

/** A missing ETag permits cached reading only, never a fabricated write precondition. */
internal fun checkedEtag(document: WireDocument, etag: String?): String? {
    if (etag == null) return null
    if (etag.length > 256 || !etag.matches(Regex("\"[0-9]+\""))) PrivateJson.invalid()
    val numeric = etag.substring(1, etag.length - 1).trimStart('0').ifEmpty { "0" }
    if (numeric != documentVersion(document)) kitchenFail(FailureReason.INVALID_DATA)
    return etag
}

/**
 * Conservative explicit lifecycle mask, not authority to expand licenses or rewrite instructions.
 * Review/recall metadata is not included in the immutable recipe-content comparison. Full original
 * canonical documents are still stored; removing a field here never drops it from the owned data.
 */
internal fun immutableRecipe(document: WireDocument): JsonObject = JsonObject(document.json().jsonObject - setOf(
    "version", "updatedAt", "reviewStatus", "recallReasonCode", "reviewedAt", "reviewerLabel",
))
