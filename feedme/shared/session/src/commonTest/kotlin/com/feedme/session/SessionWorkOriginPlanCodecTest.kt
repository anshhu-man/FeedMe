package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Structural protocol tests, not native proof authentication or permission to select work. */
class SessionWorkOriginPlanCodecTest {
    @Test fun canonicalRoundTripPreservesExactScopeOriginRevisionAndProof() {
        val original = record()
        val encoded = SessionWorkOriginPlanCodec.encode(original)
        val expected = "{\"version\":1,\"expectedRevision\":7,\"scope\":{\"environment\":\"private-environment\"," +
            "\"actorKind\":\"ACCOUNT\",\"actorId\":\"private-owner\"},\"origin\":\"$ORIGIN\",\"proof\":\"$PROOF_HEX\"}"
        assertEquals(expected, encoded.copyForCodec().decodeToString())
        assertRecord(original, SessionWorkOriginPlanCodec.decode(encoded))
        assertContentEquals(encoded.copyForCodec(), SessionWorkOriginPlanCodec.encode(SessionWorkOriginPlanCodec.decode(encoded)).copyForCodec())
    }

    @Test fun publicOpaqueFactoryDetachesEveryStorageCopy() {
        val source = raw().encodeToByteArray()
        val input = PrivateBytes(source)
        val plan = value(SessionWorkOriginPlan.fromStorage(input))
        source.fill(0)
        val copy = plan.copyForStorage().copyForCodec()
        assertEquals(raw(), copy.decodeToString())
        copy.fill(0)
        assertEquals(raw(), plan.copyForStorage().copyForCodec().decodeToString())
        assertContentEquals(input.copyForCodec(), plan.copyForStorage().copyForCodec())
    }

    @Test fun structuralFactoryDoesNotClaimThatWellFormedProofIsAuthenticated() {
        val unverified = record().copy(proof = PrivateBytes(ByteArray(64)))
        val plan = value(SessionWorkOriginPlan.fromStorage(SessionWorkOriginPlanCodec.encode(unverified)))
        assertRecord(unverified, SessionWorkOriginPlanCodec.decode(plan.copyForStorage()))
        assertEquals("SessionWorkOriginPlan(<redacted>)", plan.toString())
    }

    @Test fun unsignedProjectionOmitsOnlyProofAndPreservesAllOtherCanonicalBytes() {
        val unsigned = SessionWorkOriginPlanCodec.encodeUnsigned(record()).copyForCodec().decodeToString()
        assertEquals(raw().substringBeforeLast(",\"proof\"") + "}", unsigned)
        assertEquals(json().keys - "proof", Json.parseToJsonElement(unsigned).jsonObject.keys)
        assertContentEquals(SessionWorkOriginPlanCodec.encodeUnsigned(record()).copyForCodec(),
            SessionWorkOriginPlanCodec.encodeUnsigned(record().copy(proof = PrivateBytes(ByteArray(64)))).copyForCodec())
        invalid(unsigned)
    }

    @Test fun everyPlanFieldIsMandatoryAndUnknownAuthorityFieldsAreRejected() {
        for (key in json().keys) invalid(JsonObject(json() - key).toString())
        for (key in listOf("purpose", "entries", "lease", "token", "credential", "retiring", "ownerTag", "unknown"))
            invalid(JsonObject(json() + (key to JsonPrimitive("private-canary"))).toString())
    }

    @Test fun duplicatePlainAndEscapedRootAndScopeKeysAreRejected() {
        for ((key, value) in json()) {
            val declaration = "\"$key\":$value"
            invalid(raw().replace(declaration, "$declaration,$declaration"))
        }
        for ((key, value) in json().getValue("scope").jsonObject) {
            val declaration = "\"$key\":$value"
            invalid(raw().replace(declaration, "$declaration,$declaration"))
        }
        invalid(raw().replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"))
        invalid(raw().replace("\"actorId\":", "\"\\u0061ctorId\":\"private-owner\",\"actorId\":"))
    }

    @Test fun versionIsExactlyLocalIntegerOne() {
        for (literal in listOf("0", "2", "-1", "-0", "01", "+1", "1.0", "1e0", "1E+0", "\"1\"", "null", "true", "[]", "{}"))
            invalid(raw().replace("\"version\":1", "\"version\":$literal"))
    }

    @Test fun predecessorRevisionReservesTwoMonotonicSlots() {
        for (revision in listOf(1L, 7L, Long.MAX_VALUE - 2))
            assertRecord(record().copy(expectedRevision = revision),
                SessionWorkOriginPlanCodec.decode(SessionWorkOriginPlanCodec.encode(record().copy(expectedRevision = revision))))
        for (revision in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
            malformed(record().copy(expectedRevision = revision))
            invalid(raw().replace("\"expectedRevision\":7", "\"expectedRevision\":$revision"))
        }
    }

    @Test fun predecessorRevisionRejectsEquivalentSpellingsAndOverflow() {
        for (literal in listOf("-0", "00", "07", "+7", "7.0", "7e0", "7E+0", "0.7e1", "7.5", "\"7\"", "null", "true", "[]", "{}",
            "9223372036854775808", "1".repeat(1001)))
            invalid(raw().replace("\"expectedRevision\":7", "\"expectedRevision\":$literal"))
    }

    @Test fun scopePreservesAccountGuestAndExactNonNormalizedIdentifiers() {
        for (kind in listOf(ActorKind.ACCOUNT, ActorKind.GUEST)) {
            val scope = StorageScope(" private-环境 ", kind, " Chef-\uD83C\uDF5C ")
            val original = record().copy(scope = scope)
            assertRecord(original, SessionWorkOriginPlanCodec.decode(SessionWorkOriginPlanCodec.encode(original)))
        }
        val maximum = record().copy(scope = StorageScope("界".repeat(200), ActorKind.GUEST, "\uD83C\uDF5C".repeat(100)))
        assertRecord(maximum, SessionWorkOriginPlanCodec.decode(SessionWorkOriginPlanCodec.encode(maximum)))
    }

    @Test fun scopeRequiresEveryExactFieldKindAndBoundedNonblankSafeStrings() {
        val scope = json().getValue("scope").jsonObject
        for (key in scope.keys) invalid(withScope(JsonObject(scope - key)))
        invalid(withScope(JsonObject(scope + ("unknown" to JsonPrimitive("private-canary")))))
        for (kind in listOf("DEMO", "account", "guest", "ACCOUNT ", "", "ROOT"))
            invalid(withScope(JsonObject(scope + ("actorKind" to JsonPrimitive(kind)))))
        for (key in scope.keys) for (literal in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())))
            invalid(withScope(JsonObject(scope + (key to literal))))
        for (key in listOf("environment", "actorId")) for (text in listOf("", " ", "x".repeat(201), "x\n", "x\u0000", "x\u0085"))
            invalid(withScope(JsonObject(scope + (key to JsonPrimitive(text)))))
        for (literal in listOf(JsonNull, JsonPrimitive("scope"), JsonPrimitive(1), JsonArray(emptyList()))) invalid(withScope(literal))
    }

    @Test fun originRequiresExactLowercaseUuidAndNeverASecretOrNumericValue() {
        for (origin in listOf("", ORIGIN.uppercase(), ORIGIN.replace("-", ""), "$ORIGIN ", " $ORIGIN", ORIGIN.dropLast(1),
            ORIGIN + "0", "g" + ORIGIN.drop(1), "private-canary", "\uD800")) {
            malformed(record().copy(origin = origin))
            if (origin != "\uD800") invalid(JsonObject(json() + ("origin" to JsonPrimitive(origin))).toString())
        }
        for (literal in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())))
            invalid(JsonObject(json() + ("origin" to literal)).toString())
    }

    @Test fun proofRequiresExactlySixtyFourBytesAndOneHundredTwentyEightLowercaseHexDigits() {
        assertEquals(64, SessionWorkOriginPlanCodec.PROOF_BYTES)
        for (count in listOf(0, 1, 32, 63, 65, 128)) malformed(record().copy(proof = PrivateBytes(ByteArray(count))))
        for (proof in listOf("", "a".repeat(127), "a".repeat(129), "A".repeat(128), "g".repeat(128), " " + "a".repeat(127), "a".repeat(127) + "\n"))
            invalid(JsonObject(json() + ("proof" to JsonPrimitive(proof))).toString())
        for (literal in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())))
            invalid(JsonObject(json() + ("proof" to literal)).toString())
        for (byte in listOf(0.toByte(), 255.toByte())) {
            val expected = record().copy(proof = PrivateBytes(ByteArray(64) { byte }))
            assertRecord(expected, SessionWorkOriginPlanCodec.decode(SessionWorkOriginPlanCodec.encode(expected)))
        }
    }

    @Test fun malformedRootAndDocumentsNeverProduceAPartialPlan() {
        for (wire in listOf("", " ", "null", "true", "1", "\"private-canary\"", "[]", "[{}]", "{}", "{", "{}{}", "//private-canary\n{}",
            raw().dropLast(1) + ",}", raw().replace("\"version\":1", "\"version\":NaN"))) invalid(wire)
    }

    @Test fun malformedUtf8AndUnpairedUtf16FailBeforeLossyEncoding() {
        for (bad in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xf0.toByte(), 0x9f.toByte()))) {
            sanitized(assertFailsWith<SessionWorkOriginPlanFormatException> { SessionWorkOriginPlanCodec.decode(PrivateBytes(bad)) })
            assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(SessionWorkOriginPlan.fromStorage(PrivateBytes(bad))).reason)
        }
        for (surrogate in listOf("\uD800", "\uDC00", "\uD800x", "\uDC00\uD800")) {
            malformed(record().copy(scope = ACCOUNT.copy(environment = surrogate)))
            malformed(record().copy(scope = ACCOUNT.copy(actorId = surrogate)))
        }
        for (escape in listOf("\\uD800", "\\uDC00", "\\uD800x", "\\uDC00\\uD800")) {
            invalid(raw().replace(ORIGIN, escape))
            invalid(raw().replace(ACCOUNT.actorId, escape))
            invalid(raw().replace(ACCOUNT.environment, escape))
        }
    }

    @Test fun publicFactoryRejectsEquivalentNoncanonicalWhitespaceOrderAndEscapes() {
        val variants = listOf(" " + raw(), raw() + "\n", raw().replace(",", ", "),
            JsonObject(json().entries.reversed().associate { it.key to it.value }).toString(),
            raw().replace("private-owner", "private-\\u006fwner"), raw().replace("\"version\"", "\"\\u0076ersion\""))
        for (wire in variants) {
            assertRecord(record(), SessionWorkOriginPlanCodec.decode(bytes(wire)))
            assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(SessionWorkOriginPlan.fromStorage(bytes(wire))).reason)
        }
    }

    @Test fun byteAndDepthBoundsPrecedeParsingUnboundedInput() {
        assertEquals(4096, SessionWorkOriginPlanCodec.MAX_BYTES)
        val atLimit = raw() + " ".repeat(SessionWorkOriginPlanCodec.MAX_BYTES - raw().encodeToByteArray().size)
        assertRecord(record(), SessionWorkOriginPlanCodec.decode(bytes(atLimit)))
        assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(SessionWorkOriginPlan.fromStorage(bytes(atLimit))).reason)
        invalid(atLimit + " ")
        invalid(raw().dropLast(1) + ",\"unknown\":" + "[".repeat(10) + "0" + "]".repeat(10) + "}")
    }

    @Test fun allEncodingEntryPointsRejectInvalidInMemoryFields() {
        malformed(record().copy(expectedRevision = 0))
        malformed(record().copy(scope = ACCOUNT.copy(actorKind = ActorKind.DEMO)))
        malformed(record().copy(scope = ACCOUNT.copy(actorId = "\uD800")))
        malformed(record().copy(origin = "private-canary"))
        malformed(record().copy(proof = PrivateBytes(ByteArray(63))))
    }

    @Test fun selectedWorkLedgerContainsOnlyExactCanonicalPlanProvenance() {
        val plan = SessionWorkOriginPlan.create(record())
        val state = SessionWorkState.SetupSelected(plan)
        val encoded = SessionWorkCodec.encode(state)
        val root = Json.parseToJsonElement(encoded.copyForCodec().decodeToString()).jsonObject
        assertEquals(setOf("version", "state", "plan"), root.keys)
        assertEquals(JsonPrimitive(1), root["version"])
        assertEquals(JsonPrimitive("setup-selected"), root["state"])
        assertEquals(hex(plan.copyForStorage()), root.getValue("plan").jsonPrimitive.content)
        val decoded = assertIs<SessionWorkState.SetupSelected>(SessionWorkCodec.decode(encoded))
        assertContentEquals(plan.copyForStorage().copyForCodec(), decoded.plan.copyForStorage().copyForCodec())
        assertContentEquals(encoded.copyForCodec(), SessionWorkCodec.encode(decoded).copyForCodec())
        assertSame(SessionWorkState.Idle, SessionWorkCodec.decode(SessionWorkCodec.encode(SessionWorkState.Idle)))
    }

    @Test fun selectedLedgerRejectsMissingExtraDuplicateAndOrdinaryOriginFields() {
        val root = selectedJson()
        for (key in root.keys) invalidWork(JsonObject(root - key).toString())
        for ((key, value) in mapOf("scope" to json().getValue("scope"), "origin" to JsonPrimitive(ORIGIN),
            "entries" to JsonArray(emptyList()), "retiring" to JsonPrimitive(false), "unknown" to JsonNull))
            invalidWork(JsonObject(root + (key to value)).toString())
        val declaration = "\"plan\":${root.getValue("plan")}"
        invalidWork(root.toString().replace(declaration, "$declaration,$declaration"))
        invalidWork(root.toString().replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"))
        for (state in listOf("active", "retiring", "idle", "SETUP-SELECTED", "setup-selected "))
            invalidWork(JsonObject(root + ("state" to JsonPrimitive(state))).toString())
    }

    @Test fun selectedLedgerRejectsMalformedHexAndNoncanonicalOrInvalidNestedPlan() {
        val root = selectedJson()
        for (literal in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), json(), JsonArray(emptyList())))
            invalidWork(JsonObject(root + ("plan" to literal)).toString())
        val encoded = root.getValue("plan").jsonPrimitive.content
        for (value in listOf("", "0", "zz", encoded.uppercase(), encoded.dropLast(1), "00".repeat(4097),
            hex(bytes(" " + raw())), hex(bytes(raw().replace("\"expectedRevision\":7", "\"expectedRevision\":0")))))
            invalidWork(JsonObject(root + ("plan" to JsonPrimitive(value))).toString())
    }

    @Test fun plansStatesProofCopiesAndErrorsNeverEchoPrivateFields() {
        val plan = SessionWorkOriginPlan.create(record())
        assertEquals("SessionWorkOriginPlan(<redacted>)", plan.toString())
        assertEquals("SessionWorkOriginPlanRecord(<redacted>)", record().toString())
        assertEquals("SessionWorkSetupSelected(<redacted>)", SessionWorkState.SetupSelected(plan).toString())
        for (view in listOf(plan, record(), SessionWorkState.SetupSelected(plan), plan.copyForStorage()))
            for (privateValue in listOf(ORIGIN, ACCOUNT.environment, ACCOUNT.actorId, PROOF_HEX)) assertFalse(view.toString().contains(privateValue))
        invalid(raw().replace(ORIGIN, "private-canary"))
    }

    private fun malformed(record: SessionWorkOriginPlanRecord) {
        sanitized(assertFailsWith<SessionWorkOriginPlanFormatException> { SessionWorkOriginPlanCodec.encode(record) })
        sanitized(assertFailsWith<SessionWorkOriginPlanFormatException> { SessionWorkOriginPlanCodec.encodeUnsigned(record) })
        sanitized(assertFailsWith<SessionWorkOriginPlanFormatException> { SessionWorkOriginPlan.create(record) })
    }
    private fun invalid(raw: String) {
        sanitized(assertFailsWith<SessionWorkOriginPlanFormatException> { SessionWorkOriginPlanCodec.decode(bytes(raw)) })
        assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(SessionWorkOriginPlan.fromStorage(bytes(raw))).reason)
    }
    private fun invalidWork(raw: String) {
        val error = assertFailsWith<SessionWorkFormatException> { SessionWorkCodec.decode(bytes(raw)) }
        assertEquals("Native work data unavailable", error.message)
        assertNull(error.cause)
    }
    private fun sanitized(error: SessionWorkOriginPlanFormatException) {
        assertEquals("Work origin plan unavailable", error.message)
        assertNull(error.cause)
    }
    private fun assertRecord(expected: SessionWorkOriginPlanRecord, actual: SessionWorkOriginPlanRecord) {
        assertEquals(expected.expectedRevision, actual.expectedRevision)
        assertEquals(expected.scope, actual.scope)
        assertEquals(expected.origin, actual.origin)
        assertContentEquals(expected.proof.copyForCodec(), actual.proof.copyForCodec())
    }
    private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
    private fun raw() = SessionWorkOriginPlanCodec.encode(record()).copyForCodec().decodeToString()
    private fun json() = Json.parseToJsonElement(raw()).jsonObject
    private fun withScope(scope: JsonElement) = JsonObject(json() + ("scope" to scope)).toString()
    private fun selectedJson() = Json.parseToJsonElement(SessionWorkCodec.encode(SessionWorkState.SetupSelected(
        SessionWorkOriginPlan.create(record()))).copyForCodec().decodeToString()).jsonObject
    private fun hex(bytes: PrivateBytes) = bytes.copyForCodec().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun record() = SessionWorkOriginPlanRecord(7, ACCOUNT, ORIGIN, PrivateBytes(ByteArray(64) { it.toByte() }))

    companion object {
        private val ACCOUNT = StorageScope("private-environment", ActorKind.ACCOUNT, "private-owner")
        private const val ORIGIN = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        private val PROOF_HEX = (0 until 64).joinToString("") { it.toString(16).padStart(2, '0') }
    }
}
