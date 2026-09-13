package com.feedme.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactDecimalTest {
    @Test fun acceptsEveryJsonNumberForm() {
        for (token in listOf("0", "-0", "12", "-12", "0.25", "-0.25", "12e3", "12E+3", "12e-3", "1.20E+0003")) {
            assertNotNull(ExactDecimal.parse(token), token)
        }
    }

    @Test fun rejectsMissingNumberParts() {
        for (token in listOf("", "-", ".", ".1", "-.1", "1.", "1e", "1E", "1e+", "1e-", "1.e2")) {
            assertNull(ExactDecimal.parse(token), token)
        }
    }

    @Test fun rejectsNonJsonSpelling() {
        for (token in listOf("+1", "01", "-01", "00.1", "NaN", "Infinity", "-Infinity", "0x10", "1_000", "1f")) {
            assertNull(ExactDecimal.parse(token), token)
        }
    }

    @Test fun rejectsWhitespaceAndTrailingContent() {
        for (token in listOf(" 1", "1 ", "1\n", "1\t", "1,2", "1.2.3", "1e2e3", "1e+-2", "1e--2", "1\u0000")) {
            assertNull(ExactDecimal.parse(token), token)
        }
    }

    @Test fun acceptsOnlyAsciiDigits() {
        for (token in listOf("١", "１", "1.٢", "1e٢", "1\u0661")) assertNull(ExactDecimal.parse(token), token)
    }

    @Test fun acceptsExactlyOneThousandCharacters() {
        assertNotNull(ExactDecimal.parse("9".repeat(1000)))
        assertNotNull(ExactDecimal.parse("-" + "9".repeat(999)))
        assertNotNull(ExactDecimal.parse("0." + "1".repeat(998)))
        assertNotNull(ExactDecimal.parse("1e+" + "0".repeat(996) + "1"))
    }

    @Test fun wholeTokenCountsTowardLengthLimit() {
        for (token in listOf("9".repeat(1001), "-" + "9".repeat(1000), "0." + "1".repeat(999), "1e+" + "0".repeat(997) + "1")) {
            assertNull(ExactDecimal.parse(token))
        }
    }

    @Test fun positiveAndNegativeScaleLimitsAreInclusive() {
        assertNotNull(ExactDecimal.parse("1e10000"))
        assertNotNull(ExactDecimal.parse("1e-10000"))
        assertNull(ExactDecimal.parse("1e10001"))
        assertNull(ExactDecimal.parse("1e-10001"))
    }

    @Test fun fractionalDigitsOffsetPositiveExponentBeforeCheckingScale() {
        assertNotNull(ExactDecimal.parse("1.0e10001"))
        assertNull(ExactDecimal.parse("1.0e10002"))
        assertNotNull(ExactDecimal.parse("1.234e10003"))
        assertNull(ExactDecimal.parse("1.234e10004"))
    }

    @Test fun fractionalDigitsIncreaseScaleForNegativeExponent() {
        assertNotNull(ExactDecimal.parse("1.0e-9999"))
        assertNull(ExactDecimal.parse("1.0e-10000"))
        assertNotNull(ExactDecimal.parse("1.234e-9997"))
        assertNull(ExactDecimal.parse("1.234e-9998"))
    }

    @Test fun rawScaleIsCheckedBeforeTrailingZeroNormalization() {
        assertNull(ExactDecimal.parse("1.0000000000e-9991"))
        assertTrue(decimal("1.0e10001").isInteger)
        assertLess("1e10000", "1.0e10001")
    }

    @Test fun zeroDoesNotBypassScaleLimits() {
        for (token in listOf("0e10001", "-0e-10001", "0.0e-10000", "0e999999999999999999999999")) {
            assertNull(ExactDecimal.parse(token), token)
        }
        assertNotNull(ExactDecimal.parse("0e10000"))
        assertNotNull(ExactDecimal.parse("-0.0e-9999"))
    }

    @Test fun unboundedExponentsCannotOverflowIntoTheAcceptedRange() {
        for (exponent in listOf("2147483647", "2147483648", "4294967296", "9223372036854775808", "9".repeat(998))) {
            assertNull(ExactDecimal.parse("1e$exponent"))
            assertNull(ExactDecimal.parse("1e-$exponent"))
        }
    }

    @Test fun leadingExponentZerosDoNotChangeScale() {
        assertEquivalent("1e00000010000", "1e10000")
        assertEquivalent("1e-0000010000", "1e-10000")
        assertEquivalent("1e+0000000", "1")
        assertNull(ExactDecimal.parse("1e00000010001"))
    }

    @Test fun everySignedZeroHasOneEqualityKey() {
        for (token in listOf("-0", "0.0", "-0.000", "0e10000", "0e-10000", "-0.0e10001")) {
            assertEquivalent("0", token)
            assertTrue(decimal(token).isInteger)
        }
    }

    @Test fun trailingZerosAndExponentSpellingsHaveOneEqualityKey() {
        for (token in listOf("123", "123.0", "12300e-2", "0.123e3", "1.23000E+2", "123e-000")) {
            assertEquivalent("123", token)
        }
        assertEquivalent("-0.0012300", "-123e-5")
    }

    @Test fun signRemainsPartOfNonzeroEquality() {
        assertNotEquals(decimal("1").canonicalKey, decimal("-1").canonicalKey)
        assertNotEquals(decimal("0.1"), decimal("-0.1"))
    }

    @Test fun mathematicalIntegersIncludeDecimalAndExponentForms() {
        for (token in listOf("1", "-1", "1.0", "-1.000", "123.00", "1e400", "120e-1", "0.0001e4", "123.4e1")) {
            assertTrue(decimal(token).isInteger, token)
        }
    }

    @Test fun mathematicalFractionsRemainNoninteger() {
        for (token in listOf("0.1", "-0.1", "1e-400", "120e-3", "1.001", "1.000000000000000000001", "123.4e-1")) {
            assertFalse(decimal(token).isInteger, token)
        }
    }

    @Test fun machineIntegerBoundariesCompareExactly() {
        assertLess("9007199254740992", "9007199254740993")
        assertLess("9223372036854775807", "9223372036854775808")
        assertLess("-9223372036854775809", "-9223372036854775808")
    }

    @Test fun precisionPastMachineFractionsRemainsSignificant() {
        assertLess("0.0999999999999999999999999999999", "0.1")
        assertLess("0.1", "0.1000000000000000000000000000001")
        assertLess("1", "1." + "0".repeat(996) + "1")
        assertLess("-1." + "0".repeat(995) + "1", "-1")
    }

    @Test fun numbersBeyondFloatingPointRangeRemainOrdered() {
        assertLess("1e400", "2e400")
        assertLess("9e9999", "1e10000")
        assertLess("-1e10000", "-9e9999")
        assertLess("1e-10000", "2e-10000")
    }

    @Test fun tinyNegativeValuesRemainLessThanEitherZero() {
        assertLess("-1e-10000", "-0")
        assertLess("-1e-10000", "0")
        assertLess("-0", "1e-10000")
        assertLess("0", "1e-10000")
        assertLess("-2e-10000", "-1e-10000")
    }

    @Test fun magnitudeComparisonCrossesTheDecimalPoint() {
        assertLess("0.09999", "0.1")
        assertLess("0.99999", "1")
        assertLess("9.9999", "10")
        assertLess("-10", "-9.9999")
        assertLess("-0.1", "-0.09999")
    }

    @Test fun equalMagnitudeUsesImplicitTrailingZeros() {
        assertLess("12", "12.01")
        assertLess("12.01", "12.1")
        assertLess("12.1", "12.10001")
        assertLess("-12.10001", "-12.1")
        assertEquivalent("12.10000", "121e-1")
    }

    @Test fun thousandDigitCoefficientsCompareWithoutLosingLastDigit() {
        val smaller = "9".repeat(999) + "8"
        val larger = "9".repeat(1000)
        assertLess(smaller, larger)
        assertLess(larger, "1e1000")
        assertEquivalent("1" + "0".repeat(999), "1e999")
    }

    @Test fun equalityKeysStayCompactAtExponentLimits() {
        assertTrue(decimal("1e10000").canonicalKey.length < 20)
        assertTrue(decimal("1e-10000").canonicalKey.length < 20)
        assertTrue(decimal("1.0e10001").canonicalKey.length < 20)
    }

    @Test fun sortedValuesAreAntisymmetricAndTransitivelyOrdered() {
        val tokens = listOf(
            "-1e10000", "-999999999999999999999999", "-1", "-0.1", "-1e-10000",
            "0", "1e-10000", "0.1", "1", "999999999999999999999999", "1e10000",
        )
        for (left in tokens.indices) for (right in tokens.indices) {
            val comparison = decimal(tokens[left]).compareTo(decimal(tokens[right]))
            assertEquals(left.compareTo(right).coerceIn(-1, 1), comparison.coerceIn(-1, 1))
        }
    }

    @Test fun diagnosticsNeverExposeTheCoefficientOrExponent() {
        val value = decimal("-123456789123456789e4321")
        assertEquals("ExactDecimal(redacted)", value.toString())
        assertFalse(value.toString().contains("123456789"))
        assertFalse(value.toString().contains("4321"))
    }

    private fun decimal(token: String): ExactDecimal = assertNotNull(ExactDecimal.parse(token), token)

    private fun assertLess(left: String, right: String) {
        assertTrue(decimal(left) < decimal(right), "$left < $right")
        assertTrue(decimal(right) > decimal(left), "$right > $left")
    }

    private fun assertEquivalent(left: String, right: String) {
        val leftValue = decimal(left)
        val rightValue = decimal(right)
        assertEquals(0, leftValue.compareTo(rightValue))
        assertEquals(leftValue.canonicalKey, rightValue.canonicalKey)
        assertEquals(leftValue, rightValue)
        assertEquals(leftValue.hashCode(), rightValue.hashCode())
    }
}
