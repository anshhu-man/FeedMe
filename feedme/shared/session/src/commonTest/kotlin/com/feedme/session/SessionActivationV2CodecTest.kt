package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.StateRetirementTarget
import kotlinx.serialization.json.*
import kotlin.test.*

/** Local schema compatibility only, never proof of native identity or durable completion. */
class SessionActivationV2CodecTest {
    @Test fun versionTwoPreservesExactOperationAndAllOriginalBindingFields() {
        val original = record()
        val encoded = SessionActivationCodec.encode(original)
        val root = json(encoded)
        assertEquals(JsonPrimitive(2), root["version"])
        assertEquals(setOf("version", "scope", "credentialIncarnation", "originBinding", "dataTarget", "configurationBinding", "setupOperationId"), root.keys)
        val decoded = SessionActivationCodec.decode(encoded)
        assertEquals(2, decoded.schemaVersion); assertEquals(OPERATION, decoded.setupOperationId)
        assertEquals(SCOPE, decoded.scope); assertEquals(CREDENTIAL, decoded.credentialIncarnation)
        assertEquals(ORIGIN, decoded.originBinding); assertEquals(CONFIGURATION, decoded.configurationBinding)
        assertContentEquals(original.dataTarget.copyForStorage(), decoded.dataTarget.copyForStorage())
        assertContentEquals(encoded.copyForCodec(), SessionActivationCodec.encode(decoded).copyForCodec())
    }

    @Test fun legacyVersionOneKeepsByteExactEncodingAndNeverInfersSetupOperation() {
        val legacy = record(operation = null)
        val expected = """{"version":1,"scope":{"environment":"activation-v2-test","actorKind":"ACCOUNT","actorId":"private-v2-owner"},"credentialIncarnation":"$CREDENTIAL","originBinding":"$ORIGIN","dataTarget":"${"25".repeat(137)}","configurationBinding":"$CONFIGURATION"}"""
        val encoded = SessionActivationCodec.encode(legacy)
        assertEquals(expected, encoded.copyForCodec().decodeToString())
        val decoded = SessionActivationCodec.decode(bytes(expected))
        assertEquals(1, decoded.schemaVersion); assertNull(decoded.setupOperationId)
        assertEquals(expected, SessionActivationCodec.encode(decoded).copyForCodec().decodeToString())
    }

    @Test fun operationIsRequiredCanonicalStringAndNeverNullOrCoerced() {
        val root = json()
        invalid(JsonObject(root - "setupOperationId"))
        for (value in listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(7), JsonArray(emptyList()), JsonObject(emptyMap()),
            JsonPrimitive(""), JsonPrimitive(OPERATION.uppercase()), JsonPrimitive(" $OPERATION"),
            JsonPrimitive(OPERATION.replace("-", "")), JsonPrimitive("private-token")))
            invalid(JsonObject(root + ("setupOperationId" to value)))
        for (operation in listOf("", OPERATION.uppercase(), "\uD800"))
            assertFailsWith<SessionActivationFormatException> { SessionActivationCodec.encode(record(operation)) }
    }

    @Test fun crossVersionFieldsAndUnknownProgressAuthorityAreRejected() {
        val modern = json(); val legacy = json(SessionActivationCodec.encode(record(null)))
        invalid(JsonObject(legacy + ("setupOperationId" to JsonPrimitive(OPERATION))))
        invalid(JsonObject(modern + ("version" to JsonPrimitive(1))))
        for (key in listOf("done", "sealed", "lease", "accessToken", "credentialPlan", "progress", "unknown"))
            invalid(JsonObject(modern + (key to JsonPrimitive("private-canary"))))
        for (key in modern.keys) invalid(JsonObject(modern - key))
    }

    @Test fun versionRequiresExactSupportedIntegerTokenWithoutNormalization() {
        val raw = SessionActivationCodec.encode(record()).copyForCodec().decodeToString()
        for (version in listOf("0", "3", "-1", "2.0", "2e0", "02", "+2", "\"2\"", "null", "true", "[]", "{}"))
            invalid(raw.replace("\"version\":2", "\"version\":$version"))
    }

    @Test fun duplicateKeysAndMalformedUnicodeCannotBeNormalizedIntoVersionTwo() {
        val root = json(); val raw = root.toString()
        for ((key, value) in root) {
            val field = "\"$key\":$value"
            invalid(raw.replace(field, "$field,$field"))
        }
        invalid(raw.replace("\"setupOperationId\":", "\"\\u0073etupOperationId\":\"$OPERATION\",\"setupOperationId\":"))
        invalid(raw.replace(SCOPE.actorId, "\\ud800"))
        invalid(raw.replace(OPERATION, "\\udc00"))
        assertFailsWith<SessionActivationFormatException> { SessionActivationCodec.decode(PrivateBytes(byteArrayOf(0x80.toByte()))) }
    }

    @Test fun boundedPayloadAndOpaqueTargetRemainDetachedWithoutClaimingAuthentication() {
        val target = ByteArray(137) { 37 }
        val original = SessionActivationRecord(SCOPE, CREDENTIAL, ORIGIN, StateRetirementTarget(target), CONFIGURATION, OPERATION)
        val saved = SessionActivationCodec.encode(original)
        target.fill(0)
        saved.copyForCodec().fill(0)
        val decoded = SessionActivationCodec.decode(saved)
        assertContentEquals(ByteArray(137) { 37 }, decoded.dataTarget.copyForStorage())
        val raw = saved.copyForCodec().decodeToString()
        val padded = raw + " ".repeat(SessionActivationCodec.MAX_BYTES - saved.copyForCodec().size)
        assertEquals(OPERATION, SessionActivationCodec.decode(bytes(padded)).setupOperationId)
        invalid(padded + " ")
        // Structural target bytes carry no validated native MAC or owner assertion.
        assertEquals(OPERATION, decoded.setupOperationId)
    }

    @Test fun recordAndFormatErrorsNeverExposeOperationIdentityOrPrivatePayload() {
        val value = record()
        assertEquals("SessionActivationRecord(<redacted>)", value.toString())
        val error = assertFailsWith<SessionActivationFormatException> { SessionActivationCodec.decode(bytes("private-canary")) }
        assertEquals("Session activation data unavailable", error.message); assertNull(error.cause)
        for (view in listOf(value, SessionActivationCodec.encode(value), error))
            for (marker in listOf(SCOPE.actorId, OPERATION, CREDENTIAL, ORIGIN, CONFIGURATION, "private-canary"))
                assertFalse(view.toString().contains(marker))
    }

    private fun record(operation: String? = OPERATION) = SessionActivationRecord(SCOPE, CREDENTIAL, ORIGIN,
        StateRetirementTarget(ByteArray(137) { 37 }), CONFIGURATION, operation)
    private fun json(value: PrivateBytes = SessionActivationCodec.encode(record())) = Json.parseToJsonElement(value.copyForCodec().decodeToString()).jsonObject
    private fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
    private fun invalid(root: JsonObject) = invalid(root.toString())
    private fun invalid(raw: String) { assertFailsWith<SessionActivationFormatException> { SessionActivationCodec.decode(bytes(raw)) } }
    companion object {
        private val SCOPE = StorageScope("activation-v2-test", ActorKind.ACCOUNT, "private-v2-owner")
        private const val OPERATION = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        private const val CREDENTIAL = "11111111-2222-4333-8444-555555555555"
        private const val ORIGIN = "22222222-3333-4444-8555-666666666666"
        private val CONFIGURATION = "d".repeat(64)
    }
}
