package com.feedme.contracts

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/** Wire syntax and resource limits do not imply schema validity or authorization. */
class WireDocumentTest {
    @Test fun originalBytesRetainWhitespaceEscapesOrderingAndNumberSpelling() {
        val raw = " \r\n{\"z\":1.2300E+009, \"text\":\"a\\/b\\u0063\",\"a\":-0}\t\n"
        val bytes = raw.encodeToByteArray()
        val document = WireDocument.decode(bytes)
        assertContentEquals(bytes, document.encodeUtf8())
        assertEquals(raw, WireDocument.parse(raw).encodeUtf8().decodeToString())
        assertEquals("1.2300E+009", document.value("z").numberTokenOrNull())
        assertEquals("a/bc", document.value("text").stringOrNull())
        assertEquals("-0", document.value("a").numberTokenOrNull())
    }

    @Test fun numbersNeverRoundThroughMachineIntegersOrFloatingPoint() {
        val tokens = listOf(
            "9007199254740993", "9223372036854775808", "-9223372036854775809",
            "9".repeat(300), "-" + "9".repeat(300),
            "0." + "0".repeat(300) + "123456789012345678901234567890",
            "1e309", "1E-999999", "-1.230000e+0009", "-0", "0.000", "1e0",
        )
        tokens.forEach { token ->
            val root = WireDocument.parse(token)
            assertEquals(WireKind.NUMBER, root.kind, token)
            assertEquals(token, root.numberTokenOrNull(), token)
            assertEquals(token, root.encodeUtf8().decodeToString(), token)
            val objectValue = WireDocument.parse("{\"n\":$token}").value("n")
            assertEquals(token, objectValue.numberTokenOrNull(), token)
            assertEquals(token, objectValue.encodeUtf8().decodeToString(), token)
            val arrayValue = assertNotNull(WireDocument.parse("[$token]").elementsOrNull()).single()
            assertEquals(token, arrayValue.numberTokenOrNull(), token)
        }
    }

    @Test fun missingExplicitNullAndPresentValuesRemainDistinct() {
        val document = WireDocument.parse("""{"nullValue":null,"emptyString":"","falseValue":false,"zero":0,"object":{},"array":[]}""")
        assertEquals(WireField.Missing, document.field("missing"))
        assertEquals(WireField.Null, document.field("nullValue"))
        assertEquals("", document.value("emptyString").stringOrNull())
        assertEquals(false, document.value("falseValue").booleanOrNull())
        assertEquals("0", document.value("zero").numberTokenOrNull())
        assertEquals(WireKind.OBJECT, document.value("object").kind)
        assertEquals(emptyList(), document.value("array").elementsOrNull())
    }

    @Test fun absentHttpBodyIsDifferentFromJsonNullAndEmptyBytes() {
        assertEquals(WireBody.Absent, WireBody.decode(null))
        val present = assertIs<WireBody.Present>(WireBody.decode("null".encodeToByteArray()))
        assertEquals(WireKind.NULL, present.document.kind)
        assertEquals("null", present.document.encodeUtf8().decodeToString())
        assertRejected(WireFailure.MALFORMED_JSON) { WireBody.decode(byteArrayOf()) }
        assertRejected(WireFailure.MALFORMED_JSON) { WireBody.decode(" \t\r\n".encodeToByteArray()) }
        val emptyString = assertIs<WireBody.Present>(WireBody.decode("\"\"".encodeToByteArray()))
        assertEquals("", emptyString.document.stringOrNull())
    }

    @Test fun syntaxParsingDoesNotApplySchemaDefaultsOrDropUnknownFields() {
        val raw = """{"futureField":{"arbitrary":"not-a-uuid"},"mode":"unknown-enum","count":-12.5,"enabled":null}"""
        val document = WireDocument.parse(raw)
        assertEquals(raw, document.encodeUtf8().decodeToString())
        assertEquals("not-a-uuid", document.value("futureField").value("arbitrary").stringOrNull())
        assertEquals("unknown-enum", document.value("mode").stringOrNull())
        assertEquals("-12.5", document.value("count").numberTokenOrNull())
        assertEquals(WireField.Null, document.field("enabled"))
        assertEquals(WireField.Missing, document.field("version"))
        assertEquals(WireField.Missing, WireDocument.parse("{}").field("enabled"))
    }

    @Test fun arraysPreserveOrderDuplicatesAndExplicitNulls() {
        val raw = """["same","same",null,2,2,{"a":1},{"a":1}]"""
        val document = WireDocument.parse(raw)
        val elements = assertNotNull(document.elementsOrNull())
        assertEquals(7, elements.size)
        assertEquals(listOf("\"same\"", "\"same\"", "null", "2", "2", "{\"a\":1}", "{\"a\":1}"),
            elements.map { it.encodeUtf8().decodeToString() })
        assertEquals(WireKind.NULL, elements[2].kind)
        assertEquals(raw, document.encodeUtf8().decodeToString())
    }

    @Test fun accessorsDoNotCoerceValuesAcrossJsonKinds() {
        val examples = listOf(
            "{}" to WireKind.OBJECT, "[]" to WireKind.ARRAY, "\"true\"" to WireKind.STRING,
            "42" to WireKind.NUMBER, "true" to WireKind.BOOLEAN, "false" to WireKind.BOOLEAN,
            "null" to WireKind.NULL,
        )
        examples.forEach { (raw, kind) ->
            val document = WireDocument.parse(raw)
            assertEquals(kind, document.kind, raw)
            if (kind != WireKind.STRING) assertNull(document.stringOrNull(), raw)
            if (kind != WireKind.NUMBER) assertNull(document.numberTokenOrNull(), raw)
            if (kind != WireKind.BOOLEAN) assertNull(document.booleanOrNull(), raw)
            if (kind != WireKind.ARRAY) assertNull(document.elementsOrNull(), raw)
            if (kind != WireKind.OBJECT) {
                val exception = assertFailsWith<IllegalArgumentException> { document.field("absent") }
                assertEquals("Wire object required for field access", exception.message)
                assertNull(exception.cause)
            }
        }
        assertNull(WireDocument.parse("\"42\"").numberTokenOrNull())
        assertNull(WireDocument.parse("\"true\"").booleanOrNull())
        assertNull(WireDocument.parse("0").booleanOrNull())
    }

    @Test fun validUnicodeAndAstralCharactersRoundTripWithoutReplacement() {
        val text = "café Ελληνικά हिन्दी 中文 😀 𝄞"
        val raw = "{\"😀\":\"$text\"}"
        val bytes = raw.encodeToByteArray()
        val document = WireDocument.decode(bytes)
        assertEquals(text, document.value("😀").stringOrNull())
        assertContentEquals(bytes, document.encodeUtf8())
        assertEquals("😀𝄞", WireDocument.parse("\"\\uD83D\\uDE00\\uD834\\uDD1E\"").stringOrNull())
        assertEquals("\u0000\b\u000c\n\r\t/\\\"", WireDocument.parse("\"\\u0000\\b\\f\\n\\r\\t\\/\\\\\\\"\"").stringOrNull())
    }

    @Test fun invalidUtf8IsRejectedInsteadOfReplaced() {
        val malformed = listOf(
            listOf(0x80), listOf(0xc0, 0xaf), listOf(0xc2), listOf(0xc2, 0x20),
            listOf(0xe2, 0x82), listOf(0xe0, 0x80, 0xaf), listOf(0xed, 0xa0, 0x80),
            listOf(0xf0, 0x9f, 0x98), listOf(0xf0, 0x80, 0x80, 0xaf),
            listOf(0xf4, 0x90, 0x80, 0x80), listOf(0xf8, 0x80, 0x80, 0x80, 0x80), listOf(0xff),
        )
        malformed.forEach { sequence ->
            val bytes = (listOf(0x22) + sequence + listOf(0x22)).map { it.toByte() }.toByteArray()
            assertRejected(WireFailure.INVALID_UTF8) { WireDocument.decode(bytes) }
        }
    }

    @Test fun rawUnpairedUtf16SurrogatesCannotSilentlyBecomeReplacementBytes() {
        listOf("\uD800", "\uDC00", "\uD800x", "x\uDC00", "\uD800\uD800", "\uDC00\uD800").forEach { invalid ->
            assertRejected(WireFailure.INVALID_UNICODE) { WireDocument.parse("\"$invalid\"") }
            assertRejected(WireFailure.INVALID_UNICODE) { WireDocument.parse("{\"$invalid\":0}") }
        }
    }

    @Test fun unicodeScalarInteropPolicyRejectsEscapedUnpairedSurrogates() {
        listOf("\\uD800", "\\uDC00", "\\uD800x", "x\\uDC00", "\\uD800\\uD800", "\\uDC00\\uD800").forEach { invalid ->
            assertRejected(WireFailure.INVALID_UNICODE) { WireDocument.parse("\"$invalid\"") }
            assertRejected(WireFailure.INVALID_UNICODE) { WireDocument.parse("{\"$invalid\":0}") }
            assertRejected(WireFailure.INVALID_UNICODE) { WireDocument.parse("{\"value\":\"$invalid\"}") }
        }
        assertEquals("😀", WireDocument.parse("\"\\uD83D\\uDE00\"").stringOrNull())
        assertEquals("ok", WireDocument.parse("{\"\\uD83D\\uDE00\":\"ok\"}").value("😀").stringOrNull())
    }

    @Test fun byteLimitMeasuresUtf8BytesAndAllowsTheExactBoundary() {
        val raw = "\"é😀\""
        val bytes = raw.encodeToByteArray()
        assertEquals(8, bytes.size)
        val exact = WireLimits(maxBytes = bytes.size)
        assertContentEquals(bytes, WireDocument.parse(raw, exact).encodeUtf8())
        assertContentEquals(bytes, WireDocument.decode(bytes, exact).encodeUtf8())
        val tooSmall = WireLimits(maxBytes = bytes.size - 1)
        assertRejected(WireFailure.BYTE_LIMIT) { WireDocument.parse(raw, tooSmall) }
        assertRejected(WireFailure.BYTE_LIMIT) { WireDocument.decode(bytes, tooSmall) }
        assertRejected(WireFailure.BYTE_LIMIT) { WireBody.decode(bytes, tooSmall) }
    }

    @Test fun defaultByteLimitIsOneMebibyteWithNoTruncation() {
        val limits = WireLimits()
        assertEquals(1_048_576, limits.maxBytes)
        val raw = "\"" + "a".repeat(limits.maxBytes - 2) + "\""
        assertEquals(raw, WireDocument.parse(raw).encodeUtf8().decodeToString())
        assertRejected(WireFailure.BYTE_LIMIT) { WireDocument.parse(raw + " ") }
        assertRejected(WireFailure.BYTE_LIMIT) { WireDocument.decode((raw + " ").encodeToByteArray()) }
    }

    @Test fun containerDepthAllowsTheExactLimitAndRejectsOneMore() {
        val limits = WireLimits(maxDepth = 3)
        listOf("[[[0]]]", "{\"a\":{\"b\":{\"c\":0}}}", "[{\"a\":[null]}]").forEach { raw ->
            assertEquals(raw, WireDocument.parse(raw, limits).encodeUtf8().decodeToString())
        }
        listOf("[[[[0]]]]", "{\"a\":{\"b\":{\"c\":{}}}}", "[{\"a\":[[]]}]").forEach { raw ->
            assertRejected(WireFailure.DEPTH_LIMIT) { WireDocument.parse(raw, limits) }
        }
        assertEquals("0", WireDocument.parse("0", WireLimits(maxDepth = 1)).numberTokenOrNull())
        assertEquals(WireKind.ARRAY, WireDocument.parse("[]", WireLimits(maxDepth = 1)).kind)
    }

    @Test fun defaultDepthLimitIsSixtyFourAndQuotedBracketsDoNotConsumeDepth() {
        assertEquals(64, WireLimits().maxDepth)
        val exact = "[".repeat(64) + "0" + "]".repeat(64)
        assertEquals(exact, WireDocument.parse(exact).encodeUtf8().decodeToString())
        assertRejected(WireFailure.DEPTH_LIMIT) { WireDocument.parse("[$exact]") }
        val quoted = "\"" + "[{".repeat(150) + "\\\"" + "}]".repeat(150) + "\""
        assertEquals(WireKind.STRING, WireDocument.parse(quoted, WireLimits(maxDepth = 1)).kind)
    }

    @Test fun numberLengthBoundsTheWholeTokenIncludingSignsAndExponent() {
        val limits = WireLimits(maxNumberLength = 7)
        listOf("1234567", "-123456", "0.12345", "1e12345", "-1.2E+3").forEach { token ->
            assertEquals(token, WireDocument.parse(token, limits).numberTokenOrNull())
            assertEquals(token, WireDocument.parse("{\"n\":$token}", limits).value("n").numberTokenOrNull())
        }
        listOf("12345678", "-1234567", "0.123456", "1e123456", "-1.2E+34").forEach { token ->
            assertRejected(WireFailure.NUMBER_LIMIT) { WireDocument.parse(token, limits) }
            assertRejected(WireFailure.NUMBER_LIMIT) { WireDocument.parse("[$token]", limits) }
        }
        assertEquals("12345678", WireDocument.parse("\"12345678\"", limits).stringOrNull())
        assertEquals(true, WireDocument.parse("true", WireLimits(maxNumberLength = 1)).booleanOrNull())
        assertEquals(WireKind.NULL, WireDocument.parse("null", WireLimits(maxNumberLength = 1)).kind)
    }

    @Test fun defaultNumberLimitIsOneThousandCharacters() {
        assertEquals(1000, WireLimits().maxNumberLength)
        val exact = "9".repeat(1000)
        assertEquals(exact, WireDocument.parse(exact).numberTokenOrNull())
        assertRejected(WireFailure.NUMBER_LIMIT) { WireDocument.parse(exact + "9") }
    }

    @Test fun unsupportedResourcePoliciesAreRejectedBeforeUse() {
        listOf(0, -1, 4_194_305).forEach { value ->
            assertFailsWith<IllegalArgumentException> { WireLimits(maxBytes = value) }
        }
        listOf(0, -1, 129).forEach { value ->
            assertFailsWith<IllegalArgumentException> { WireLimits(maxDepth = value) }
        }
        listOf(0, -1, 4097).forEach { value ->
            assertFailsWith<IllegalArgumentException> { WireLimits(maxNumberLength = value) }
        }
        assertEquals(1, WireLimits(maxBytes = 1, maxDepth = 1, maxNumberLength = 1).maxBytes)
        assertEquals(4096, WireLimits(maxBytes = 4_194_304, maxDepth = 128, maxNumberLength = 4096).maxNumberLength)
    }

    @Test fun duplicateObjectKeysRejectAtEveryDepthAndAfterEscapeDecoding() {
        listOf(
            """{"a":1,"a":2}""",
            """{"outer":{"a":1,"a":2}}""",
            """[{"a":1,"a":2}]""",
            """{"a":1,"\u0061":2}""",
            """{"\u0061":1,"a":2}""",
            """{"a\"b":1,"a\u0022b":2}""",
            """{"a/b":1,"a\/b":2}""",
            """{"😀":1,"\uD83D\uDE00":2}""",
            """{"":1,"":2}""",
            """{"a":null,"a":null}""",
        ).forEach { raw -> assertRejected(WireFailure.DUPLICATE_KEY, raw) { WireDocument.parse(raw) } }
    }

    @Test fun distinctObjectScopesAndRepeatedStringValuesAreAllowed() {
        listOf(
            """{"a":{"a":1},"b":{"a":2}}""",
            """[{"a":1},{"a":2},"a","a"]""",
            """{"a":"same","b":"same"}""",
            """{"a":1,"A":2,"é":3,"e\u0301":4}""",
        ).forEach { raw -> assertEquals(raw, WireDocument.parse(raw).encodeUtf8().decodeToString()) }
    }

    @Test fun nonJsonPrimitivesAndInvalidNumberGrammarReject() {
        listOf(
            "NaN", "nan", "Infinity", "-Infinity", "+Infinity", "undefined", "True", "False", "NULL",
            "+1", "01", "-01", "00", ".1", "1.", "1e", "1e+", "1e-", "1e1.5", "--1",
            "0x10", "0o10", "0b10", "1_000", "1f", "１", "١", "tru", "falsehood", "nullx",
        ).forEach { token ->
            assertRejected(WireFailure.MALFORMED_JSON, token) { WireDocument.parse(token) }
            assertRejected(WireFailure.MALFORMED_JSON, "[$token]") { WireDocument.parse("[$token]") }
            assertRejected(WireFailure.MALFORMED_JSON, "{\"x\":$token}") { WireDocument.parse("{\"x\":$token}") }
        }
    }

    @Test fun onlyOneCompleteJsonDocumentIsAccepted() {
        listOf("", " \r\n\t", "{}{}", "[] []", "true false", "null 0", "1 2", "\"a\"\"b\"",
            "{} []", "{} true", "[]0", "0{}", "null[]").forEach { raw ->
            assertRejected(WireFailure.MALFORMED_JSON, raw) { WireDocument.parse(raw) }
        }
    }

    @Test fun commentsTrailingCommasAndMissingSeparatorsReject() {
        listOf(
            "/* comment */{}", "{} // comment", "[1,/* comment */2]", "{\"a\":1,// comment\n\"b\":2}",
            "[1,]", "{\"a\":1,}", "[,]", "{,}", "[1,,2]", "{\"a\":1,,\"b\":2}",
            "[1 2]", "[true false]", "[{}{}]", "[\"a\"\"b\"]", "{\"a\":1 \"b\":2}",
            "{\"a\" 1}", "{\"a\"::1}", "{\"a\":}", "{a:1}", "{'a':1}",
            ":", ",", "[", "{", "]", "}", "[}", "{]", "{\"a\":[1}", "[1",
        ).forEach { raw -> assertRejected(WireFailure.MALFORMED_JSON, raw) { WireDocument.parse(raw) } }
    }

    @Test fun invalidEscapesUnescapedControlCharactersAndNonJsonWhitespaceReject() {
        listOf("\"\\x20\"", "\"\\v\"", "\"\\0\"", "\"\\u12\"", "\"\\uZZZZ\"",
            "\"unterminated", "\"ends in backslash\\").forEach { raw ->
            assertRejected(WireFailure.MALFORMED_JSON, raw) { WireDocument.parse(raw) }
        }
        (0..31).forEach { code ->
            assertRejected(WireFailure.MALFORMED_JSON) { WireDocument.parse("\"a${code.toChar()}b\"") }
        }
        listOf('\u000b', '\u000c', '\u00a0', '\u2003', '\uFEFF').forEach { whitespace ->
            assertRejected(WireFailure.MALFORMED_JSON) { WireDocument.parse("${whitespace}null") }
            assertRejected(WireFailure.MALFORMED_JSON) { WireDocument.parse("null$whitespace") }
        }
    }

    @Test fun decodingAndEncodingDoNotShareCallerOwnedByteBuffers() {
        val raw = """{"secret":"wire-secret","values":[1,2]}"""
        val input = raw.encodeToByteArray()
        val document = WireDocument.decode(input)
        input.fill(0)
        assertEquals(raw, document.encodeUtf8().decodeToString())
        val output = document.encodeUtf8()
        output.fill(0)
        assertEquals(raw, document.encodeUtf8().decodeToString())
        assertNotSame(document.encodeUtf8(), document.encodeUtf8())
        assertEquals("wire-secret", document.value("secret").stringOrNull())
    }

    @Test fun detachedJsonAndArrayProjectionsCannotMutateTheOriginal() {
        val raw = """{"secret":"original","nested":{"secret":"nested"},"items":[{"secret":"array"}]}"""
        val document = WireDocument.parse(raw)
        val detached = document.detachedJson().jsonObject
        val independent = document.detachedJson().jsonObject
        assertNotSame(detached, independent)
        assertNotSame(detached.getValue("nested"), independent.getValue("nested"))
        assertNotSame(detached.getValue("items"), independent.getValue("items"))
        // Some targets expose mutable entries behind read-only JsonObject views; others reject casts.
        listOf(detached, detached.getValue("nested").jsonObject,
            detached.getValue("items").jsonArray.single().jsonObject).forEach { projection ->
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val entry = projection.entries.first { it.key == "secret" } as MutableMap.MutableEntry<String, JsonElement>
                entry.setValue(JsonPrimitive("changed"))
            }
        }
        val items = assertNotNull(document.value("items").elementsOrNull())
        runCatching { (items as MutableList<WireDocument>).clear() }
        assertEquals(raw, document.encodeUtf8().decodeToString())
        assertEquals("original", document.value("secret").stringOrNull())
        assertEquals("nested", document.value("nested").value("secret").stringOrNull())
        assertEquals("array", assertNotNull(document.value("items").elementsOrNull()).single().value("secret").stringOrNull())
        assertEquals(independent, document.detachedJson())
    }

    @Test fun printableWrappersAndFailureMessagesDoNotContainInputOrCauses() {
        val secret = "sensitive-bearer-token"
        val document = WireDocument.parse("{\"secret\":\"$secret\"}")
        assertEquals("WireDocument(redacted)", document.toString())
        assertEquals("WireField.Value(redacted)", document.field("secret").toString())
        assertEquals("WireBody.Present(redacted)", WireBody.Present(document).toString())
        listOf("{\"secret\":\"$secret\",}", "{\"$secret\":1,\"$secret\":2}", "\"$secret\\q\"").forEach { raw ->
            val exception = assertFailsWith<WireDecodingException> { WireDocument.parse(raw) }
            assertFalse(exception.toString().contains(secret))
            assertEquals("Wire decode rejected: ${exception.reason}", exception.message)
            assertNull(exception.cause)
        }
    }

    private fun WireDocument.value(name: String): WireDocument =
        assertIs<WireField.Value<WireDocument>>(field(name)).value

    private fun assertRejected(reason: WireFailure, context: String? = null, block: () -> Unit) {
        val exception = assertFailsWith<WireDecodingException>(message = context, block = block)
        assertEquals(reason, exception.reason, context)
        assertEquals("Wire decode rejected: $reason", exception.message)
        assertNull(exception.cause)
    }
}
