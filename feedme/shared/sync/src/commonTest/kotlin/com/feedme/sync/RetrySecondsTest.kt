package com.feedme.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetrySecondsTest {
    @Test fun ordinaryIntegersAreExact() {
        listOf(0L, 1L, 30L, 9007199254740993L, Long.MAX_VALUE).forEach {
            assertEquals(it, saturatingRetrySeconds(it.toString()))
            assertEquals(it, boundedIntegerLongOrNull(it.toString()))
        }
    }
    @Test fun CanonicalIntegralDecimalAndExponentFormsAreExact() {
        mapOf("0.0" to 0L, "-0e100" to 0L, "1e3" to 1000L, "1.200e2" to 120L,
            "12000e-3" to 12L, "0.00010e5" to 10L, "9.007199254740993e15" to 9007199254740993L,
            "92233720368547758070e-1" to Long.MAX_VALUE).forEach { (raw, value) ->
            assertEquals(value, saturatingRetrySeconds(raw), raw)
        }
    }
    @Test fun UnrepresentableDelaysNeverBecomeShorterRetries() {
        listOf("9223372036854775808", "1e1000", "1e" + "9".repeat(900), "9".repeat(1000)).forEach {
            assertEquals(Long.MAX_VALUE, saturatingRetrySeconds(it))
            assertNull(boundedIntegerLongOrNull(it))
        }
    }
    @Test fun ZeroWithHugeExponentsRemainsZero() {
        listOf("0e" + "9".repeat(900), "0e-" + "9".repeat(900), "-0.000e-1000").forEach {
            assertEquals(0, saturatingRetrySeconds(it))
        }
    }
    @Test fun LeadingExponentZerosDoNotCauseSaturation() {
        assertEquals(1000, saturatingRetrySeconds("1e" + "0".repeat(900) + "3"))
    }
}
