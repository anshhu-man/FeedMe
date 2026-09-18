package com.feedme.planning

/** Exact bounded implementation arithmetic. Unsupported magnitudes/ratios fail, never round. */
internal class PlanningDecimal private constructor(val coefficient: Long, val scale: Int) : Comparable<PlanningDecimal> {
    override fun compareTo(other: PlanningDecimal): Int {
        val common = maxOf(scale, other.scale)
        return digits(coefficient, common - scale).let { a ->
            val b = digits(other.coefficient, common - other.scale)
            if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
        }
    }
    fun scaled(quantity: PlanningDecimal, original: PlanningDecimal): String? {
        if (original.coefficient == 0L) return null
        var numerator = coefficient * quantity.coefficient // Both coefficients <= 999,999,999.
        var denominator = original.coefficient
        var divisor = gcd(numerator, denominator)
        numerator /= divisor; denominator /= divisor
        val power = original.scale - scale - quantity.scale
        repeat(kotlin.math.abs(power)) {
            if (power > 0) {
                divisor = gcd(denominator, 10); denominator /= divisor
                numerator = multiply(numerator, 10 / divisor) ?: return null
            } else {
                divisor = gcd(numerator, 10); numerator /= divisor
                denominator = multiply(denominator, 10 / divisor) ?: return null
            }
        }
        var twos = 0; var fives = 0
        while (denominator % 2L == 0L) { denominator /= 2; twos++ }
        while (denominator % 5L == 0L) { denominator /= 5; fives++ }
        if (denominator != 1L) return null
        val places = maxOf(twos, fives)
        if (places > 12) return null
        repeat(places - twos) { numerator = multiply(numerator, 2) ?: return null }
        repeat(places - fives) { numerator = multiply(numerator, 5) ?: return null }
        return decimalText(numerator.toString(), places)
    }
    fun text() = decimalText(coefficient.toString(), scale)
    override fun toString() = "PlanningDecimal(<redacted>)"
    companion object {
        fun parse(token: String): PlanningDecimal? {
            if (token.length > 64 || !token.matches(Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) return null
            val parts = token.lowercase().split('e'); val mantissa = parts[0]
            val exponent = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 0
            if (exponent !in -12..12) return null
            var digits = mantissa.replace(".", "").trimStart('0')
            var scale = (mantissa.substringAfter('.', "").length) - exponent
            if (digits.isEmpty()) return PlanningDecimal(0, 0)
            while (digits.endsWith('0')) { digits = digits.dropLast(1); scale-- }
            if (digits.length > 9 || scale !in -6..6) return null
            return PlanningDecimal(digits.toLong(), scale)
        }
        private fun gcd(a: Long, b: Long): Long { var x = a; var y = b; while (y != 0L) { val r = x % y; x = y; y = r }; return x }
        private fun multiply(a: Long, b: Long): Long? = if (a > Long.MAX_VALUE / b) null else a * b
        private fun digits(coefficient: Long, zeros: Int) = if (coefficient == 0L) "0" else coefficient.toString() + "0".repeat(zeros)
        private fun decimalText(digits: String, places: Int): String {
            if (places <= 0) return if (digits == "0") "0" else digits + "0".repeat(-places)
            val padded = digits.padStart(places + 1, '0')
            return (padded.dropLast(places) + "." + padded.takeLast(places)).trimEnd('0').trimEnd('.')
        }
    }
}
