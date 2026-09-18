package com.feedme.contracts

/**
 * Bounded exact decimal arithmetic for schema assertions, without platform numeric conversions.
 * The nonzero coefficient has no leading or trailing zeros and its value is coefficient * 10^-scale.
 */
internal class ExactDecimal private constructor(
    private val sign: Int,
    private val coefficient: String,
    private val scale: Int,
) : Comparable<ExactDecimal> {
    val isInteger: Boolean get() = sign == 0 || scale <= 0

    /** Internal equality key; may contain input data and must never enter diagnostics. */
    val canonicalKey: String
        get() = if (sign == 0) "0" else "${if (sign < 0) "-" else ""}${coefficient}e${-scale}"

    override fun compareTo(other: ExactDecimal): Int {
        if (sign != other.sign) return sign.compareTo(other.sign)
        if (sign == 0) return 0

        // Equal decimal positions allow a digit comparison with implicit right-hand zeros.
        // Neither operation expands an exponent into a potentially large string.
        val position = coefficient.length - scale
        val otherPosition = other.coefficient.length - other.scale
        if (position != otherPosition) return sign * position.compareTo(otherPosition)
        for (index in 0 until maxOf(coefficient.length, other.coefficient.length)) {
            val digit = coefficient.getOrNull(index) ?: '0'
            val otherDigit = other.coefficient.getOrNull(index) ?: '0'
            if (digit != otherDigit) return sign * digit.compareTo(otherDigit)
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is ExactDecimal &&
        sign == other.sign && coefficient == other.coefficient && scale == other.scale

    override fun hashCode(): Int = (31 * sign + coefficient.hashCode()) * 31 + scale

    override fun toString(): String = "ExactDecimal(redacted)"

    companion object {
        private const val MAX_TOKEN_LENGTH = 1000
        private const val MAX_RAW_SCALE = 10_000

        /** Returns null for invalid JSON number syntax or the server's numeric resource limits. */
        fun parse(token: String): ExactDecimal? {
            if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH) return null
            var index = 0
            val negative = token[index] == '-'
            if (negative) index++
            val integerStart = index
            when (token.getOrNull(index)) {
                '0' -> index++
                in '1'..'9' -> while (token.getOrNull(index) in '0'..'9') index++
                else -> return null
            }
            val integerEnd = index
            var fractionStart = index
            var fractionEnd = index
            if (token.getOrNull(index) == '.') {
                index++
                fractionStart = index
                while (token.getOrNull(index) in '0'..'9') index++
                fractionEnd = index
                if (fractionStart == fractionEnd) return null
            }
            var exponent = 0
            if (token.getOrNull(index) == 'e' || token.getOrNull(index) == 'E') {
                index++
                val negativeExponent = token.getOrNull(index) == '-'
                if (negativeExponent || token.getOrNull(index) == '+') index++
                val exponentStart = index
                while (token.getOrNull(index) in '0'..'9') {
                    exponent = exponent * 10 + (token[index] - '0')
                    // The fractional digits cannot offset any larger exponent into the scale range.
                    // Checking every digit also makes integer overflow impossible.
                    if (exponent > MAX_RAW_SCALE + MAX_TOKEN_LENGTH) return null
                    index++
                }
                if (index == exponentStart) return null
                if (negativeExponent) exponent = -exponent
            }
            if (index != token.length) return null
            val rawScale = fractionEnd - fractionStart - exponent
            if (rawScale !in -MAX_RAW_SCALE..MAX_RAW_SCALE) return null

            val digits = token.substring(integerStart, integerEnd) + token.substring(fractionStart, fractionEnd)
            val firstNonzero = digits.indexOfFirst { it != '0' }
            // Even zero must first pass the raw-scale limit, matching the server boundary.
            if (firstNonzero < 0) return ExactDecimal(0, "0", 0)
            var end = digits.length
            while (digits[end - 1] == '0') end--
            return ExactDecimal(
                sign = if (negative) -1 else 1,
                coefficient = digits.substring(firstNonzero, end),
                scale = rawScale - (digits.length - end),
            )
        }
    }
}
