package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.StateRetirementTarget
import kotlinx.serialization.json.*
import kotlin.test.*

class SessionActivationCodecTest {
    @Test fun accountRecordRoundTripsEveryExactBindingAndOnlyTheSpecifiedFields() {
        val original = record()
        val encoded = SessionActivationCodec.encode(original)
        val decoded = SessionActivationCodec.decode(encoded)
        assertRecord(original, decoded)
        assertContentEquals(encoded.copyForCodec(), SessionActivationCodec.encode(decoded).copyForCodec())
        val root = json(encoded)
        assertEquals(setOf("version", "scope", "credentialIncarnation", "originBinding", "dataTarget", "configurationBinding"), root.keys)
        assertEquals(setOf("environment", "actorKind", "actorId"), root.getValue("scope").jsonObject.keys)
        assertEquals(274, root.getValue("dataTarget").jsonPrimitive.content.length)
        assertTrue(root.getValue("dataTarget").jsonPrimitive.content.all { it in HEX })
    }

    @Test fun guestOwnerIsPreservedWithoutInferringAnAccountOrProvider() {
        val original = record(scope = ACCOUNT.copy(actorKind = ActorKind.GUEST))
        val decoded = SessionActivationCodec.decode(SessionActivationCodec.encode(original))
        assertRecord(original, decoded)
        assertEquals(ActorKind.GUEST, decoded.scope.actorKind)
    }

    @Test fun targetEncodingPreservesExactBytesIncludingZeroAndHighBits() {
        for (bytes in listOf(ByteArray(137), ByteArray(137) { 0xff.toByte() }, ByteArray(137) { (it * 37).toByte() })) {
            val original = record(target = StateRetirementTarget(bytes))
            val decoded = SessionActivationCodec.decode(SessionActivationCodec.encode(original))
            assertContentEquals(bytes, decoded.dataTarget.copyForStorage())
            assertRecord(original, decoded)
        }
    }

    @Test fun exactTargetAndConfigurationChangesAreNotNormalizedOrTreatedAsEquivalent() {
        val first = record()
        val changedTarget = first.dataTarget.copyForStorage().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val second = record(target = StateRetirementTarget(changedTarget), configuration = "b".repeat(64))
        val before = SessionActivationCodec.decode(SessionActivationCodec.encode(first))
        val after = SessionActivationCodec.decode(SessionActivationCodec.encode(second))
        assertFalse(before.dataTarget.copyForStorage().contentEquals(after.dataTarget.copyForStorage()))
        assertNotEquals(before.configurationBinding, after.configurationBinding)
        // Structure is not target-MAC authentication: the runtime must compare a current capture.
        assertRecord(second, after)
    }

    @Test fun bothLocalIncarnationsRequireExactLowercaseUuidStrings() {
        val root = json()
        for (key in listOf("credentialIncarnation", "originBinding")) {
            for (id in listOf("", CREDENTIALS.uppercase(), " $CREDENTIALS", "$CREDENTIALS ", CREDENTIALS.dropLast(1), "g" + CREDENTIALS.drop(1), "private-token"))
                invalid(JsonObject(root + (key to JsonPrimitive(id))).toString())
            for (value in nonStrings()) invalid(JsonObject(root + (key to value)).toString())
        }
    }

    @Test fun configurationBindingIsAnExactLowercase64CharacterHexDigest() {
        val root = json()
        for (value in listOf("", "a".repeat(63), "a".repeat(65), CONFIGURATION.uppercase(), "g".repeat(64),
            "-$CONFIGURATION", "$CONFIGURATION ", "https://api.example.invalid", "0x" + "a".repeat(62)))
            invalid(JsonObject(root + ("configurationBinding" to JsonPrimitive(value))).toString())
        for (value in nonStrings()) invalid(JsonObject(root + ("configurationBinding" to value)).toString())
        for (value in listOf("0".repeat(64), "f".repeat(64), HEX.repeat(4))) {
            val original = record(configuration = value)
            assertRecord(original, SessionActivationCodec.decode(SessionActivationCodec.encode(original)))
        }
    }

    @Test fun targetIsExact137ByteLowercaseHexNotAPathBase64OrTruncatedCapability() {
        val root = json()
        val exact = root.getValue("dataTarget").jsonPrimitive.content
        for (value in listOf("", "00", "0".repeat(273), "0".repeat(275), "0".repeat(276), "g".repeat(274),
            "A".repeat(274), exact.uppercase(), "$exact ", "0x" + "0".repeat(272), "/tmp/private-target", "cHJpdmF0ZS10YXJnZXQ="))
            invalid(JsonObject(root + ("dataTarget" to JsonPrimitive(value))).toString())
        for (value in nonStrings()) invalid(JsonObject(root + ("dataTarget" to value)).toString())
    }

    @Test fun everyRootFieldIsRequiredAndCredentialsOrMutableRefreshRevisionAreForbidden() {
        val root = json()
        for (key in root.keys) invalid(JsonObject(root - key).toString())
        for (key in listOf("accessToken", "refreshToken", "guestToken", "provider", "endpoint", "credentialRevision", "refreshRevision", "expiresAtMillis", "unknown"))
            invalid(JsonObject(root + (key to JsonPrimitive("private-extra"))).toString())
        val raw = SessionActivationCodec.encode(record()).copyForCodec().decodeToString()
        for (key in listOf("accessToken", "refreshToken", "provider", "credentialRevision", "refreshRevision")) assertFalse(raw.contains(key))
    }

    @Test fun scopeRequiresAllExactKeysAndStringTypes() {
        val root = json()
        val scope = root.getValue("scope").jsonObject
        for (key in scope.keys) invalid(withScope(root, scope - key))
        invalid(withScope(root, scope + ("unknown" to JsonPrimitive(true))))
        for (key in scope.keys) for (value in nonStrings()) invalid(withScope(root, scope + (key to value)))
        for (value in nonStrings() + JsonPrimitive("private-scope")) invalid(JsonObject(root + ("scope" to value)).toString())
    }

    @Test fun scopeRejectsDemoUnknownKindsAndCaseFolding() {
        val root = json()
        val scope = root.getValue("scope").jsonObject
        for (kind in listOf("DEMO", "account", "guest", "ACCOUNT ", "GUEST\n", "ADMIN", ""))
            invalid(withScope(root, scope + ("actorKind" to JsonPrimitive(kind))))
    }

    @Test fun ownershipFieldsEnforceNonblankControlFreeCoreBounds() {
        val root = json()
        val scope = root.getValue("scope").jsonObject
        for (key in listOf("environment", "actorId")) for (value in listOf("", " ", "\t", "private\nowner", "private\u0000owner", "private\u007fowner", "a".repeat(201)))
            invalid(withScope(root, scope + (key to JsonPrimitive(value))))
    }

    @Test fun validUnicodeAndOwnershipWhitespaceArePreservedWithoutNormalization() {
        for (scope in listOf(StorageScope(" staging-é ", ActorKind.ACCOUNT, " chef-🍳-e\u0301 "),
            StorageScope("e".repeat(200), ActorKind.GUEST, "é".repeat(200)))) {
            val original = record(scope = scope)
            assertRecord(original, SessionActivationCodec.decode(SessionActivationCodec.encode(original)))
        }
    }

    @Test fun schemaVersionRequiresLiteralLocalIntegerOne() {
        val raw = json().toString()
        for (version in listOf("0", "2", "-1", "-0", "+1", "01", "1.0", "1e0", "1E+0", "\"1\"", "true", "null", "[]", "{}", "1".repeat(1001)))
            invalid(raw.replace("\"version\":1", "\"version\":$version"))
    }

    @Test fun topLevelNullScalarsArraysAndEmptyObjectsHaveNoFallback() {
        for (raw in listOf("", " ", "null", "true", "1", "\"private-activation\"", "[]", "[{}]", "{}")) invalid(raw)
    }

    @Test fun duplicateDecodedKeysFailAtRootAndScopeIncludingEscapedNames() {
        val raw = json().toString()
        invalid(raw.replace("\"version\":1", "\"version\":1,\"version\":1"))
        invalid(raw.replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"))
        invalid(raw.replace("\"actorId\":", "\"actorId\":\"other-private-owner\",\"\\u0061ctorId\":"))
        invalid(raw.replace("\"dataTarget\":", "\"dataTarget\":\"${"0".repeat(274)}\",\"dataTarget\":"))
        invalid(raw.replace("\"configurationBinding\":", "\"configurationBinding\":\"$CONFIGURATION\",\"\\u0063onfigurationBinding\":"))
    }

    @Test fun malformedJsonAndUtf8DoNotBecomePartialOrRepairedActivationRecords() {
        for (raw in listOf("{", "{\"version\":1,}", "{version:1}", "//private-comment\n{}", "{}{}", "{\"version\":NaN}")) invalid(raw)
        for (bad in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xf0.toByte(), 0x9f.toByte())))
            sanitized(assertFailsWith<SessionActivationFormatException> { SessionActivationCodec.decode(PrivateBytes(bad)) })
    }

    @Test fun loneOrReversedUnicodeSurrogatesAreRejectedAtEveryStringBoundary() {
        val raw = json().toString()
        for (escaped in listOf("\\uD800", "\\uDC00", "\\uD800x", "\\uDC00\\uD800")) {
            invalid(raw.replace(ACCOUNT.actorId, escaped))
            invalid(raw.replace(CREDENTIALS, escaped))
            invalid(raw.replace(CONFIGURATION, escaped))
        }
    }

    @Test fun maximum4096WireBytesAreInclusiveAndDepthIsBounded() {
        val raw = json().toString()
        val padded = raw + " ".repeat(SessionActivationCodec.MAX_BYTES - raw.encodeToByteArray().size)
        assertEquals(4096, padded.encodeToByteArray().size)
        assertRecord(record(), SessionActivationCodec.decode(bytes(padded)))
        invalid(padded + " ")
        invalid("[".repeat(9) + "0" + "]".repeat(9))
    }

    @Test fun encodingRevalidatesUntrustedModelValuesWithoutNormalization() {
        invalidEncode(record(scope = ACCOUNT.copy(actorKind = ActorKind.DEMO)))
        invalidEncode(record(credentials = CREDENTIALS.uppercase()))
        invalidEncode(record(origin = ORIGIN.uppercase()))
        invalidEncode(record(configuration = CONFIGURATION.uppercase()))
        invalidEncode(record(configuration = "a".repeat(4097)))
        for (text in listOf("\uD800", "\uDC00", "private\uD800owner")) {
            invalidEncode(record(scope = ACCOUNT.copy(actorId = text)))
            invalidEncode(record(scope = ACCOUNT.copy(environment = text)))
        }
    }

    @Test fun largestCoreScopeWithJsonEscapingStillRoundTripsWithoutDroppingWireFields() {
        val original = record(scope = ACCOUNT.copy(environment = "\"".repeat(200), actorId = "\\".repeat(200)))
        val encoded = SessionActivationCodec.encode(original)
        assertTrue(encoded.copyForCodec().size <= SessionActivationCodec.MAX_BYTES)
        assertRecord(original, SessionActivationCodec.decode(encoded))
    }

    @Test fun targetAndSerializedByteCopiesRemainDetachedAndCallerBuffersAreNotWiped() {
        val source = ByteArray(137) { it.toByte() }
        val expected = source.copyOf()
        val original = record(target = StateRetirementTarget(source))
        source.fill(0)
        val exported = original.dataTarget.copyForStorage()
        exported.fill(0)
        assertContentEquals(expected, original.dataTarget.copyForStorage())
        val encoded = SessionActivationCodec.encode(original)
        val callerCopy = encoded.copyForCodec()
        val saved = callerCopy.copyOf()
        val decoded = SessionActivationCodec.decode(encoded)
        assertContentEquals(saved, callerCopy)
        callerCopy.fill(0)
        assertContentEquals(saved, encoded.copyForCodec())
        decoded.dataTarget.copyForStorage().fill(0)
        assertContentEquals(expected, decoded.dataTarget.copyForStorage())
        assertContentEquals(expected, original.dataTarget.copyForStorage())
    }

    @Test fun privateModelAndErrorsNeverExposeScopeCapabilityOrConfiguration() {
        val original = record()
        val target = json().getValue("dataTarget").jsonPrimitive.content
        for (model in listOf(original, original.dataTarget, SessionActivationCodec.encode(original)))
            for (privateValue in listOf(ACCOUNT.actorId, CREDENTIALS, ORIGIN, CONFIGURATION, target))
                assertFalse(model.toString().contains(privateValue))
        val error = assertFailsWith<SessionActivationFormatException> {
            SessionActivationCodec.decode(bytes("{\"private\":\"${ACCOUNT.actorId}/$CREDENTIALS/$ORIGIN/$CONFIGURATION\"}"))
        }
        sanitized(error)
    }

    companion object {
        private const val HEX = "0123456789abcdef"
        private val ACCOUNT = StorageScope("test", ActorKind.ACCOUNT, "private-activation-owner")
        private const val CREDENTIALS = "123e4567-e89b-12d3-a456-426614174abc"
        private const val ORIGIN = "123e4567-e89b-12d3-a456-426614174def"
        private val CONFIGURATION = HEX.repeat(4)
        private fun record(scope: StorageScope = ACCOUNT, credentials: String = CREDENTIALS, origin: String = ORIGIN,
            target: StateRetirementTarget = StateRetirementTarget(ByteArray(137) { it.toByte() }), configuration: String = CONFIGURATION) =
            SessionActivationRecord(scope, credentials, origin, target, configuration)
        private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
        private fun json(bytes: PrivateBytes = SessionActivationCodec.encode(record())) = Json.parseToJsonElement(bytes.copyForCodec().decodeToString()).jsonObject
        private fun withScope(root: JsonObject, scope: Map<String, JsonElement>) = JsonObject(root + ("scope" to JsonObject(scope))).toString()
        private fun nonStrings(): List<JsonElement> = listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap()))
        private fun assertRecord(expected: SessionActivationRecord, actual: SessionActivationRecord) {
            assertEquals(expected.scope, actual.scope)
            assertEquals(expected.credentialIncarnation, actual.credentialIncarnation)
            assertEquals(expected.originBinding, actual.originBinding)
            assertEquals(expected.configurationBinding, actual.configurationBinding)
            assertContentEquals(expected.dataTarget.copyForStorage(), actual.dataTarget.copyForStorage())
        }
        private fun invalid(raw: String) = sanitized(assertFailsWith<SessionActivationFormatException> { SessionActivationCodec.decode(bytes(raw)) })
        private fun invalidEncode(value: SessionActivationRecord) = sanitized(assertFailsWith<SessionActivationFormatException> { SessionActivationCodec.encode(value) })
        private fun sanitized(error: SessionActivationFormatException) {
            assertEquals("Session activation data unavailable", error.message)
            assertNull(error.cause)
            for (privateValue in listOf(ACCOUNT.actorId, CREDENTIALS, ORIGIN, CONFIGURATION)) assertFalse(error.toString().contains(privateValue))
        }
    }
}
