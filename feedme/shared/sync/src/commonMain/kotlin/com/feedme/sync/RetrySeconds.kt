package com.feedme.sync

/**
 * Only called for a schema-bound nonnegative INTEGER token. Exact decimal/exponent conversion,
 * without Double rounding or shortening an unrepresentable server delay. Huge values saturate.
 */
internal fun saturatingRetrySeconds(token: String): Long = boundedIntegerLongOrNull(token) ?: Long.MAX_VALUE

/** A narrower client persistence resource bound; never rewrites an unrepresentable sequence. */
internal fun boundedIntegerLongOrNull(token: String): Long? {
    val parts = token.removePrefix("-").lowercase().split('e')
    val mantissa = parts[0]
    val fractionDigits = if ('.' in mantissa) mantissa.length - mantissa.indexOf('.') - 1 else 0
    val digits = mantissa.replace(".", "").trimStart('0')
    if (digits.isEmpty()) return 0
    val exponentToken = parts.getOrNull(1) ?: "0"
    val exponent = exponentToken.toIntOrNull() ?: if (exponentToken.startsWith('-')) Int.MIN_VALUE else Int.MAX_VALUE
    val scale = exponent.toLong() - fractionDigits
    val length = digits.length + scale
    if (length > 19) return null
    // A nonzero validated integer cannot have length <= 0 or discarded nonzero digits.
    check(length > 0) { "Invalid bound retry delay" }
    val integer = if (scale >= 0) digits + "0".repeat(scale.toInt()) else digits.take(length.toInt())
    return integer.toLongOrNull()
}
