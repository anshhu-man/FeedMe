package com.feedme.session

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import kotlinx.serialization.json.*
import kotlin.test.*

/** Structural parsing never substitutes for the issuing native store's MAC verification. */
class CredentialCreatePlanCodecTest {
    @Test fun canonicalRoundTripPreservesEveryExactFieldAndRevision() {
        val record = record()
        val wire = CredentialCreatePlanCodec.encode(record)
        assertEquals(record, CredentialCreatePlanCodec.decode(wire))
        assertEquals(8L, record.snapshotRevision)
        assertEquals(9L, record.abortedRevision)
        assertEquals("{\"version\":1,\"purpose\":\"credential-create\",\"expectedSlotRevision\":7," +
            "\"incarnation\":\"$INCARNATION\",\"target\":\"$TARGET\",\"payloadMac\":\"$PAYLOAD\"," +
            "\"authenticationMac\":\"$AUTH\"}", wire.copyForCodec().decodeToString())
        assertContentEquals(wire.copyForCodec(), CredentialCreatePlanCodec.encode(CredentialCreatePlanCodec.decode(wire)).copyForCodec())
    }

    @Test fun publicStorageRoundTripReturnsDetachedCanonicalBytes() {
        val original = raw().encodeToByteArray()
        val supplied = PrivateBytes(original)
        val plan = value(CredentialCreatePlan.fromStorage(supplied))
        original.fill(0)
        val first = plan.copyForStorage().copyForCodec()
        assertEquals(raw(), first.decodeToString())
        first.fill(0)
        assertEquals(raw(), plan.copyForStorage().copyForCodec().decodeToString())
        assertContentEquals(supplied.copyForCodec(), plan.copyForStorage().copyForCodec())
    }

    @Test fun structuralFactoryAcceptsWellFormedUnverifiedMacWithoutClaimingAuthentication() {
        val unsignedAuthority = record().copy(authenticationMac = "0".repeat(64))
        val plan = value(CredentialCreatePlan.fromStorage(CredentialCreatePlanCodec.encode(unsignedAuthority)))
        assertEquals(unsignedAuthority, CredentialCreatePlanCodec.decode(plan.copyForStorage()))
        assertEquals("CredentialCreatePlan(<redacted>)", plan.toString())
    }

    @Test fun unsignedSigningProjectionOmitsOnlyAuthenticationMac() {
        val unsigned = CredentialCreatePlanCodec.encodeUnsigned(record()).copyForCodec().decodeToString()
        val root = Json.parseToJsonElement(unsigned).jsonObject
        assertEquals(json().keys - "authenticationMac", root.keys)
        for ((key, value) in root) assertEquals(json()[key], value)
        assertEquals(raw().substringBeforeLast(",\"authenticationMac\"") + "}", unsigned)
        assertContentEquals(CredentialCreatePlanCodec.encodeUnsigned(record()).copyForCodec(),
            CredentialCreatePlanCodec.encodeUnsigned(record().copy(authenticationMac = "f".repeat(64))).copyForCodec())
        invalid(unsigned)
    }

    @Test fun everyExactFieldIsMandatoryAndUnknownFieldsAreRejected() {
        val root = json()
        for (key in root.keys) invalid(JsonObject(root - key).toString())
        for (key in listOf("scope", "credentials", "accessToken", "refreshToken", "operation", "revision", "unknown"))
            invalid(JsonObject(root + (key to JsonPrimitive("private-canary"))).toString())
    }

    @Test fun duplicatePlainAndEscapedKeysAreRejected() {
        for (key in json().keys) {
            val declaration = "\"$key\":${json().getValue(key)}"
            invalid(raw().replace(declaration, "$declaration,$declaration"))
        }
        invalid(raw().replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"))
        invalid(raw().replace("\"target\":", "\"\\u0074arget\":\"$TARGET\",\"target\":"))
    }

    @Test fun versionAndPurposeAreExactLocalDiscriminators() {
        for (literal in listOf("0", "2", "-1", "-0", "01", "+1", "1.0", "1e0", "1E+0", "\"1\"", "null", "true", "[]", "{}"))
            invalid(raw().replace("\"version\":1", "\"version\":$literal"))
        for (purpose in listOf("", "credential-create ", "CREDENTIAL-CREATE", "credential-refresh", "credential-abort", "create", "credential\\u0000-create"))
            invalid(JsonObject(json() + ("purpose" to JsonPrimitive(purpose))).toString())
        for (literal in listOf("null", "1", "true", "[]", "{}"))
            invalid(raw().replace("\"purpose\":\"credential-create\"", "\"purpose\":$literal"))
    }

    @Test fun revisionReservesTwoMonotonicSlotsAndSupportsExactLargestSafeValue() {
        for (revision in listOf(1L, 7L, Long.MAX_VALUE - 2)) {
            val actual = CredentialCreatePlanCodec.decode(CredentialCreatePlanCodec.encode(record().copy(expectedSlotRevision = revision)))
            assertEquals(revision, actual.expectedSlotRevision)
            assertEquals(revision + 1, actual.snapshotRevision)
            assertEquals(revision + 2, actual.abortedRevision)
        }
        for (revision in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
            sanitized(assertFailsWith<CredentialCreatePlanFormatException> { CredentialCreatePlanCodec.encode(record().copy(expectedSlotRevision = revision)) })
            invalid(raw().replace("\"expectedSlotRevision\":7", "\"expectedSlotRevision\":$revision"))
        }
    }

    @Test fun revisionRejectsRemoteEquivalentNumberSpellingsAndOverflow() {
        for (literal in listOf("-0", "00", "07", "+7", "7.0", "7e0", "7E+0", "0.7e1", "7.5", "\"7\"", "null", "true", "[]", "{}",
            "9223372036854775808", "1".repeat(1001)))
            invalid(raw().replace("\"expectedSlotRevision\":7", "\"expectedSlotRevision\":$literal"))
    }

    @Test fun incarnationRequiresAnExactLowercaseLocalUuid() {
        for (id in listOf("", INCARNATION.uppercase(), INCARNATION.replace("-", ""), "$INCARNATION ", " $INCARNATION",
            INCARNATION.dropLast(1), INCARNATION + "0", "g" + INCARNATION.drop(1), "private-owner", "\uD800")) {
            sanitized(assertFailsWith<CredentialCreatePlanFormatException> { CredentialCreatePlanCodec.encode(record().copy(incarnation = id)) })
            if (id != "\uD800") invalid(JsonObject(json() + ("incarnation" to JsonPrimitive(id))).toString())
        }
    }

    @Test fun targetAndMacsRequireExactlySixtyFourLowercaseHexCharacters() {
        for (key in listOf("target", "payloadMac", "authenticationMac")) {
            for (value in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), " " + "a".repeat(63), "a".repeat(63) + "\n"))
                invalid(JsonObject(json() + (key to JsonPrimitive(value))).toString())
            for (value in listOf("0".repeat(64), "f".repeat(64))) {
                val root = JsonObject(json() + (key to JsonPrimitive(value)))
                assertEquals(root, Json.parseToJsonElement(CredentialCreatePlanCodec.encode(CredentialCreatePlanCodec.decode(bytes(root.toString()))).copyForCodec().decodeToString()))
            }
        }
    }

    @Test fun stringFieldsRejectNullNumbersArraysObjectsAndBooleans() {
        for (key in listOf("incarnation", "target", "payloadMac", "authenticationMac"))
            for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())))
                invalid(JsonObject(json() + (key to value)).toString())
    }

    @Test fun rootAndMalformedDocumentsFailWithSanitizedMessages() {
        for (wire in listOf("", " ", "null", "true", "1", "\"private-canary\"", "[]", "[{}]", "{}", "{", "{}{}", "//private-canary\n{}",
            raw().dropLast(1) + ",}", raw().replace("\"version\":1", "\"version\":NaN"))) invalid(wire)
    }

    @Test fun malformedUtf8AndEscapedUnpairedSurrogatesAreRejected() {
        for (bad in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xf0.toByte(), 0x9f.toByte()))) {
            sanitized(assertFailsWith<CredentialCreatePlanFormatException> { CredentialCreatePlanCodec.decode(PrivateBytes(bad)) })
            assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(CredentialCreatePlan.fromStorage(PrivateBytes(bad))).reason)
        }
        for (escape in listOf("\\uD800", "\\uDC00", "\\uD800x", "\\uDC00\\uD800")) invalid(raw().replace(INCARNATION, escape))
    }

    @Test fun wireByteBoundIsEnforcedBeforeUnboundedWhitespaceOrNestedContent() {
        assertEquals(4096, CredentialCreatePlanCodec.MAX_BYTES)
        val atLimit = raw() + " ".repeat(CredentialCreatePlanCodec.MAX_BYTES - raw().encodeToByteArray().size)
        assertEquals(record(), CredentialCreatePlanCodec.decode(bytes(atLimit)))
        invalid(atLimit + " ")
        invalid(raw().dropLast(1) + ",\"unknown\":" + "[".repeat(10) + "0" + "]".repeat(10) + "}")
    }

    @Test fun publicFactoryRejectsWhitespaceOrderAndEscapedEquivalentNoncanonicalBytes() {
        val variants = listOf(" " + raw(), raw() + "\n", raw().replace(",", ", "),
            JsonObject(json().entries.reversed().associate { it.key to it.value }).toString(),
            raw().replace("credential-create", "credential-\\u0063reate"),
            raw().replace("\"version\"", "\"\\u0076ersion\""))
        for (wire in variants) {
            assertEquals(record(), CredentialCreatePlanCodec.decode(bytes(wire)))
            assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(CredentialCreatePlan.fromStorage(bytes(wire))).reason)
        }
    }

    @Test fun encodeAndUnsignedEncodeValidateEveryInMemoryField() {
        val malformed = listOf(record().copy(expectedSlotRevision = 0), record().copy(incarnation = "wrong"),
            record().copy(target = "wrong"), record().copy(payloadMac = "wrong"), record().copy(authenticationMac = "wrong"))
        for (record in malformed) {
            sanitized(assertFailsWith<CredentialCreatePlanFormatException> { CredentialCreatePlanCodec.encode(record) })
            sanitized(assertFailsWith<CredentialCreatePlanFormatException> { CredentialCreatePlanCodec.encodeUnsigned(record) })
            sanitized(assertFailsWith<CredentialCreatePlanFormatException> { CredentialCreatePlan.create(record) })
        }
    }

    @Test fun redactedObjectsAndErrorsNeverEchoCapabilityFieldsOrPrivateCanaries() {
        val plan = CredentialCreatePlan.create(record())
        assertEquals("CredentialCreatePlan(<redacted>)", plan.toString())
        assertEquals("CredentialCreatePlanRecord(<redacted>)", record().toString())
        for (text in listOf(plan.toString(), record().toString(), plan.copyForStorage().toString()))
            for (secret in listOf(INCARNATION, TARGET, PAYLOAD, AUTH, "private-canary")) assertFalse(text.contains(secret))
        invalid(raw().replace(INCARNATION, "private-canary"))
    }

    private fun invalid(raw: String) {
        sanitized(assertFailsWith<CredentialCreatePlanFormatException> { CredentialCreatePlanCodec.decode(bytes(raw)) })
        assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(CredentialCreatePlan.fromStorage(bytes(raw))).reason)
    }
    private fun sanitized(error: CredentialCreatePlanFormatException) {
        assertEquals("Credential create plan unavailable", error.message)
        assertNull(error.cause)
    }
    private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
    private fun raw() = CredentialCreatePlanCodec.encode(record()).copyForCodec().decodeToString()
    private fun json() = Json.parseToJsonElement(raw()).jsonObject
    private fun record() = CredentialCreatePlanRecord(7, INCARNATION, TARGET, PAYLOAD, AUTH)

    companion object {
        private const val INCARNATION = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        private val TARGET = "a1".repeat(32)
        private val PAYLOAD = "b2".repeat(32)
        private val AUTH = "c3".repeat(32)
    }
}
