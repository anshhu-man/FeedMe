package com.feedme.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanonicalFormatsTest {
    @Test fun onlyTheFormatsInTheCanonicalBundleAreEnabled() {
        listOf("uri", "uuid", "date-time").forEach { assertTrue(CanonicalFormats.supports(it)) }
        listOf("", "URI", "UUID", "Date-Time", "date", "time", "uri-reference", "iri", "email", "secret\nformat")
            .forEach { format ->
                assertFalse(CanonicalFormats.supports(format))
                val failure = assertFailsWith<IllegalArgumentException> {
                    CanonicalFormats.accepts(format, "private instance text")
                }
                assertEquals("Unsupported canonical format", failure.message)
                assertNull(failure.cause)
            }
    }

    @Test fun uuidAcceptsItsExactAsciiHexGrammarWithoutVersionOrVariantRestrictions() {
        accepted("uuid",
            "c48f2cd7-76a3-4438-bb2a-8cf462b573ac",
            "C48F2CD7-76A3-4438-BB2A-8CF462B573AC",
            "C48f2cD7-76a3-4438-bB2a-8cF462b573Ac",
            "00000000-0000-0000-0000-000000000000",
            "ffffffff-ffff-ffff-ffff-ffffffffffff",
        )
        val template = "c48f2cd7-76a3-4438-bb2a-8cf462b573ac"
        "0123456789abcdefABCDEF".forEach { digit ->
            assertTrue(CanonicalFormats.accepts("uuid", template.replaceRange(14, 15, digit.toString())))
            assertTrue(CanonicalFormats.accepts("uuid", template.replaceRange(19, 20, digit.toString())))
        }
    }

    @Test fun uuidRejectsWrappersNonHexAndWrongHyphenPositions() {
        val valid = "c48f2cd7-76a3-4438-bb2a-8cf462b573ac"
        rejected("uuid", "", "urn:uuid:$valid", "{$valid}", " $valid", "$valid\n", "$valid-",
            valid.dropLast(1), valid.replace("-", ""), "c48f2cd776a34438bb2a8cf462b573ac----",
            "g48f2cd7-76a3-4438-bb2a-8cf462b573ac", "৪48f2cd7-76a3-4438-bb2a-8cf462b573ac")
        valid.indices.forEach { index ->
            assertFalse(CanonicalFormats.accepts("uuid", valid.replaceRange(index, index + 1, "_")))
        }
    }

    @Test fun dateTimeSupportsGregorianLeapYearsAsciiCaseFractionAndNumericOffsets() {
        accepted("date-time",
            "2026-09-13T08:17:42Z", "2026-09-13t08:17:42z",
            "2026-09-13T08:17:42.0Z", "2026-09-13T08:17:42.12345678901234567890123456789Z",
            "2026-09-13T08:17:42+05:30", "2026-09-13T08:17:42-00:00",
            "2026-09-13T08:17:42+23:59", "2026-09-13T08:17:42-23:59",
            "0000-02-29T00:00:00Z", "0400-02-29T00:00:00Z", "2000-02-29T00:00:00Z",
            "2024-02-29T00:00:00Z", "9999-12-31T23:59:59Z",
        )
        listOf("01-31", "02-28", "03-31", "04-30", "05-31", "06-30",
            "07-31", "08-31", "09-30", "10-31", "11-30", "12-31").forEach { date ->
            assertTrue(CanonicalFormats.accepts("date-time", "2023-${date}T00:00:00Z"), date)
        }
    }

    @Test fun dateTimeRejectsImpossibleDatesTimesOffsetsAndIsoExtensions() {
        rejected("date-time",
            "2023-02-29T00:00:00Z", "1900-02-29T00:00:00Z", "2100-02-29T00:00:00Z",
            "2024-02-30T00:00:00Z", "2026-04-31T00:00:00Z", "2026-00-01T00:00:00Z",
            "2026-13-01T00:00:00Z", "2026-09-00T00:00:00Z", "2026-09-32T00:00:00Z",
            "2026-09-13T24:00:00Z", "2026-09-13T23:60:00Z", "2026-09-13T23:59:61Z",
            "2026-09-13T00:00:00+24:00", "2026-09-13T00:00:00-24:00",
            "2026-09-13T00:00:00+05:60", "2026-09-13T00:00:00+05",
            "2026-09-13T00:00:00+0530", "2026-09-13T00:00:00+05:30Z",
            "2026-09-13T00:00:00Zjunk", "2026-09-13T00:00:00Z\n",
            "2026-09-13T00:00:00", "2026-09-13T00:00Z", "2026-09-13",
            "2026-09-13 00:00:00Z", "2026-09-13T00:00:00.Z", "2026-09-13T00:00:00,1Z",
            "2026-9-13T00:00:00Z", "2026-09-3T00:00:00Z", "26-09-13T00:00:00Z",
            "+02026-09-13T00:00:00Z", "2026-256T00:00:00Z", "2026-W37-7T00:00:00Z",
            "２０２６-09-13T00:00:00Z", "2026-09-13T00:00:00.৪Z", "",
        )
    }

    @Test fun leapSecondPositionIsCheckedInUtcIncludingMonthAndYearRollover() {
        accepted("date-time",
            "2016-12-31T23:59:60Z", "2016-12-31t23:59:60.0001z",
            "2016-12-31T15:59:60-08:00", "2017-01-01T05:29:60+05:30",
            "2017-01-01T00:19:60+00:20", "2017-01-01T23:58:60+23:59",
            "2016-12-31T00:00:60-23:59", "2015-07-01T00:59:60+01:00",
        )
        rejected("date-time",
            "2016-12-31T23:58:60Z", "2016-12-31T22:59:60Z", "2016-12-31T24:59:60+01:00",
            "2016-12-31T15:59:60+08:00", "2017-01-01T05:28:60+05:30",
            "2016-12-30T23:59:60Z", "2017-01-02T05:29:60+05:30",
        )
    }

    @Test fun leapSecondsRequireAnEventInThePinnedIersTable() {
        accepted("date-time", "1972-06-30T23:59:60Z", "1973-01-01T00:59:60+01:00",
            "1998-12-31T23:59:60Z", "2005-12-31T23:59:60Z", "2008-12-31T23:59:60Z",
            "2012-06-30T23:59:60Z", "2015-06-30T23:59:60Z", "2016-12-31T23:59:60Z")
        rejected("date-time", "1971-12-31T23:59:60Z", "2016-06-30T23:59:60Z",
            "2016-07-01T05:29:60+05:30", "2026-12-31T23:59:60Z", "2099-12-31T23:59:60Z",
            "2099-04-30T23:59:60Z", "2100-05-01T00:29:60+00:30", "0000-01-01T00:59:60+01:00")
    }

    @Test fun uriAcceptsEveryGenericHierPartAndAllSchemes() {
        accepted("uri",
            "https://example.test/recipe?scale=2#step-3", "https://example.test/path_(one)",
            "HTTP://EXAMPLE.TEST", "a+1-b.c:", "urn:uuid:c48f2cd7-76a3-4438-bb2a-8cf462b573ac",
            "mailto:person@example.test", "tel:+1-800-555-0100", "data:text/plain,a%20b",
            "file:///tmp/recipe.json", "x:", "x:/", "x:/a//b", "x:rootless/a:b@c",
            "x://", "x:///", "x:////", "x:?", "x:#", "x:?#", "x://?q#f",
            "x://user:password@example.test:/path", "x://@", "x://:", "x://@:00080",
            "x://example.test:999999999999999999999999999999999999999999",
            "x://-._~!$&'()*+,;=", "x://name%40host", "x://999.999.999.999",
            "x://087.10.0.1", "x://127.0.0.1", "x://127.1", "x://localhost",
            "x://-.~_!$&'()*+,;=:%40:80%2f::::::@example.test",
        )
    }

    @Test fun uriDistinguishesComponentGrammarsAndEncodedDelimiters() {
        accepted("uri", "x:%00%FF%C0%AF", "x://user%3A%40:name@host%2F.test:80/%2f%3f%23",
            "x:/-._~!$&'()*+,;=:@/path?/-._~!$&'()*+,;=:@??#/-._~!$&'()*+,;=:@/?",
            "x://host/?a=b&c=%5B1%5D#%5Bfragment%5D")
        rejected("uri", "", "path", "/path", "//example.test/path", "#fragment", "?query",
            ":path", "1x:path", "x_y:path", "x,y:path", " x:path", "x :path",
            "x://user@@host", "x://us[er@host", "x://host:abc", "x://host:+80",
            "x://host:80:90", "x://::1", "x://host]", "x://ho[st", "x://host\\path",
            "x:/[::1]", "x:/path?q=[1]", "x:/path#[]", "x:/path#one#two",
            "x:/%", "x:/%A", "x:/%AG", "x://user%Q0@host", "x://ho%st/",
            "x:/?q=%1", "x:/#%XZ", "x://host:１２", "x:é", "x://é.test", "x:?é", "x:#😀")
        (0..32).plus(127).forEach { code ->
            assertFalse(CanonicalFormats.accepts("uri", "x:/path${code.toChar()}"))
        }
        "\\\"<>^`{|}".forEach { character ->
            assertFalse(CanonicalFormats.accepts("uri", "x:/path$character"))
        }
    }

    @Test fun uriAcceptsIpv6CompressionInEveryPositionAndIpv4Tails() {
        val groups = listOf("2001", "DB8", "0", "0", "7", "8", "9", "a")
        for (start in groups.indices) {
            for (end in start + 1..groups.size) {
                val address = groups.take(start).joinToString(":") + "::" + groups.drop(end).joinToString(":")
                assertTrue(CanonicalFormats.accepts("uri", "x://[$address]:80/path"), address)
            }
        }
        accepted("uri", "x://[2001:DB8:0:0:7:8:9:a]", "x://[::]", "x://[::1]",
            "x://[::ffff:192.0.2.128]", "x://[1:2:3:4:5:6:192.0.2.128]",
            "x://[1:2:3:4:5::192.0.2.128]", "x://[::255.255.255.255]", "x://[::0.0.0.0]")
    }

    @Test fun uriRejectsMalformedIpv6IncludingUnbracketedAddressesAndZoneExtensions() {
        listOf("", ":", ":::1", "1:::2", "1::2::3", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9",
            "1:2:3:4:5:6:7:8:", ":1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7:8::",
            "::1:2:3:4:5:6:7:8", "::10000", "::gggg", "::ffff:256.1.2.3", "::ffff:01.2.3.4",
            "::ffff:1.2.3", "::ffff:1.2.3.4.5", "::ffff:1.2.3.-1", "::ffff:1.2.3.4:5",
            "1:2:3:4:5:6::1.2.3.4", "192.0.2.1::", "fe80::1%25en0", "fe80::1%en0")
            .forEach { literal -> assertFalse(CanonicalFormats.accepts("uri", "x://[$literal]"), literal) }
        rejected("uri", "x://[::1", "x://[::1]extra", "x://[::1]]", "x://[[::1]]", "x://[::1]:abc")
    }

    @Test fun uriSupportsIpvFutureGrammarIncludingUppercaseV() {
        accepted("uri", "x://[v1.a]", "x://[VfF.abc:def]", "x://[v123.-._~!$&'()*+,;=:]")
        rejected("uri", "x://[v.a]", "x://[v1.]", "x://[vG.a]", "x://[v1.%41]",
            "x://[v1.a@b]", "x://[v1.a/b]", "x://[v1.a?b]", "x://[v1.a#b]", "x://[v1.é]")
    }

    @Test fun longInputsHaveNoRegexBacktrackingOrPlatformPrecisionLimit() {
        val longPath = "x:/" + "a".repeat(1_000_000)
        assertTrue(CanonicalFormats.accepts("uri", longPath))
        assertFalse(CanonicalFormats.accepts("uri", "$longPath%"))
        val fraction = "2026-09-13T08:17:42." + "9".repeat(1_000_000)
        assertTrue(CanonicalFormats.accepts("date-time", "${fraction}Z"))
        assertFalse(CanonicalFormats.accepts("date-time", "${fraction}x"))
    }

    private fun accepted(format: String, vararg values: String) {
        values.forEach { assertTrue(CanonicalFormats.accepts(format, it), "$format should accept $it") }
    }

    private fun rejected(format: String, vararg values: String) {
        values.forEach { assertFalse(CanonicalFormats.accepts(format, it), "$format should reject $it") }
    }
}
