package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.StateActivationPlan
import kotlinx.serialization.json.*
import kotlin.test.*

/** Canonical persistence tests only: synthetic nested proofs do not authenticate native owners. */
class SessionSetupPlanCodecTest {
    @Test fun canonicalRoundTripPreservesExactNestedPlanBytesAndEveryVisibleField() {
        val original = record()
        val encoded = SessionSetupPlanCodec.encode(original)
        val root = json(encoded)
        assertEquals(listOf("version", "purpose", "operationId", "scope", "configurationBinding", "credentialPlan", "dataPlan", "workOriginPlan"), root.keys.toList())
        assertEquals(JsonPrimitive(1), root["version"])
        assertEquals(JsonPrimitive("session-setup"), root["purpose"])
        assertEquals(hex(original.credentialPlan.copyForStorage().copyForCodec()), root.getValue("credentialPlan").jsonPrimitive.content)
        assertEquals(hex(original.dataPlan.copyForStorage()), root.getValue("dataPlan").jsonPrimitive.content)
        assertEquals(hex(original.workOriginPlan.copyForStorage().copyForCodec()), root.getValue("workOriginPlan").jsonPrimitive.content)
        assertRecord(original, SessionSetupPlanCodec.decode(encoded))
        assertContentEquals(encoded.copyForCodec(), SessionSetupPlanCodec.encode(SessionSetupPlanCodec.decode(encoded)).copyForCodec())
    }

    @Test fun opaqueFactoryAndStorageCopiesAreDetachedFromCallerArrays() {
        val source = raw().encodeToByteArray()
        val supplied = PrivateBytes(source)
        val plan = value(SessionSetupPlan.fromStorage(supplied))
        source.fill(0)
        val first = plan.copyForStorage().copyForCodec()
        assertEquals(raw(), first.decodeToString())
        first.fill(0)
        assertEquals(raw(), plan.copyForStorage().copyForCodec().decodeToString())
        assertContentEquals(supplied.copyForCodec(), plan.copyForStorage().copyForCodec())
    }

    @Test fun structuralAcceptanceDoesNotAuthenticateOpaqueCredentialOrDataOwnerBindings() {
        val credential = CredentialCreatePlan.create(credentialRecord().copy(target = "f".repeat(64), authenticationMac = "0".repeat(64)))
        val data = dataBytes().also { "e".repeat(64).encodeToByteArray().copyInto(it, 2); it.fill(0, 138, 170) }
        val work = SessionWorkOriginPlan.create(workRecord().copy(proof = PrivateBytes(ByteArray(64))))
        val original = record().copy(credentialPlan = credential, dataPlan = StateActivationPlan(data), workOriginPlan = work)
        val parsed = value(SessionSetupPlan.fromStorage(SessionSetupPlanCodec.encode(original)))
        assertRecord(original, SessionSetupPlanCodec.decode(parsed.copyForStorage()))
        // Scope/MAC authority remains exclusively with the respective native issuers.
        assertEquals(SCOPE, SessionSetupPlanCodec.decode(parsed.copyForStorage()).scope)
    }

    @Test fun accountGuestAndUnicodeScopesPreserveExactNonNormalizedIdentity() {
        for (kind in listOf(ActorKind.ACCOUNT, ActorKind.GUEST)) {
            val scope = StorageScope(" 测试环境 ", kind, " Chef-\uD83C\uDF5C ")
            val original = record(scope)
            assertRecord(original, SessionSetupPlanCodec.decode(SessionSetupPlanCodec.encode(original)))
        }
        val maximum = record(StorageScope("界".repeat(200), ActorKind.GUEST, "\uD83C\uDF5C".repeat(100)))
        assertRecord(maximum, SessionSetupPlanCodec.decode(SessionSetupPlanCodec.encode(maximum)))
    }

    @Test fun allRootFieldsAreMandatoryAndUnknownAuthorityProgressOrCredentialFieldsAreRejected() {
        for (key in json().keys) invalid(JsonObject(json() - key).toString())
        for (key in listOf("state", "abortRequested", "done", "lease", "accessToken", "refreshToken", "guestToken", "dataTarget", "unknown"))
            invalid(JsonObject(json() + (key to JsonPrimitive("private-canary"))).toString())
    }

    @Test fun versionAndPurposeHaveNoEquivalentOrLegacyFallback() {
        for (literal in listOf("0", "2", "-1", "-0", "01", "+1", "1.0", "1e0", "1E+0", "\"1\"", "null", "true", "[]", "{}"))
            invalid(raw().replace("\"version\":1", "\"version\":$literal"))
        for (purpose in listOf("", "SESSION-SETUP", "session-setup ", "credential-create", "setup-discard", "session-setup\u0000"))
            invalid(change("purpose", JsonPrimitive(purpose)))
        for (literal in nonStrings()) invalid(change("purpose", literal))
    }

    @Test fun operationRequiresAnExactCanonicalUuidNotAnotherAuthorityType() {
        for (operation in listOf("", OPERATION.uppercase(), OPERATION.replace("-", ""), "$OPERATION ", " $OPERATION",
            OPERATION.dropLast(1), OPERATION + "0", "g" + OPERATION.drop(1), "private-canary", "\uD800")) {
            malformed(record().copy(operationId = operation))
            if (operation != "\uD800") invalid(change("operationId", JsonPrimitive(operation)))
        }
        for (literal in nonStrings()) invalid(change("operationId", literal))
    }

    @Test fun configurationBindingRequiresExactlyTheExistingLowercaseDigestShape() {
        for (config in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), " " + "a".repeat(63), "a".repeat(63) + "\n")) {
            malformed(record().copy(configurationBinding = config))
            invalid(change("configurationBinding", JsonPrimitive(config)))
        }
        for (literal in nonStrings()) invalid(change("configurationBinding", literal))
        for (config in listOf("0".repeat(64), "f".repeat(64))) {
            val original = record().copy(configurationBinding = config)
            assertRecord(original, SessionSetupPlanCodec.decode(SessionSetupPlanCodec.encode(original)))
        }
    }

    @Test fun outerScopeRequiresExactFieldsSafeBoundedStringsAndNonDemoKind() {
        val scope = json().getValue("scope").jsonObject
        for (key in scope.keys) invalid(change("scope", JsonObject(scope - key)))
        invalid(change("scope", JsonObject(scope + ("unknown" to JsonNull))))
        for (kind in listOf("DEMO", "account", "GUEST ", "", "ROOT"))
            invalid(change("scope", JsonObject(scope + ("actorKind" to JsonPrimitive(kind)))))
        for (key in scope.keys) for (literal in nonStrings()) invalid(change("scope", JsonObject(scope + (key to literal))))
        for (key in listOf("environment", "actorId")) for (text in listOf("", " ", "x".repeat(201), "x\n", "x\u0000", "x\u0085"))
            invalid(change("scope", JsonObject(scope + (key to JsonPrimitive(text)))))
        for (literal in nonStrings() + JsonPrimitive("scope")) invalid(change("scope", literal))
    }

    @Test fun malformedUtf8AndUnpairedUnicodeAreNeverLossilyAccepted() {
        for (bad in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xf0.toByte(), 0x9f.toByte()))) {
            sanitized(assertFailsWith<SessionSetupPlanFormatException> { SessionSetupPlanCodec.decode(PrivateBytes(bad)) })
            assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(SessionSetupPlan.fromStorage(PrivateBytes(bad))).reason)
        }
        for (surrogate in listOf("\uD800", "\uDC00", "\uD800x", "\uDC00\uD800")) {
            malformed(record().copy(scope = SCOPE.copy(environment = surrogate)))
            malformed(record().copy(scope = SCOPE.copy(actorId = surrogate)))
        }
        for (escape in listOf("\\uD800", "\\uDC00", "\\uD800x", "\\uDC00\\uD800")) {
            invalid(raw().replace(OPERATION, escape))
            invalid(raw().replace(SCOPE.actorId, escape))
            invalid(raw().replace(SCOPE.environment, escape))
        }
    }

    @Test fun visibleWorkScopeMustMatchEveryOuterOwnershipField() {
        for (scope in listOf(SCOPE.copy(actorKind = ActorKind.GUEST), SCOPE.copy(environment = "other-environment"), SCOPE.copy(actorId = "other-owner"))) {
            malformed(record().copy(scope = scope))
            val work = SessionWorkOriginPlan.create(workRecord(scope))
            malformed(record().copy(workOriginPlan = work))
            invalid(change("workOriginPlan", JsonPrimitive(hex(work.copyForStorage().copyForCodec()))))
            invalid(change("scope", json(SessionSetupPlanCodec.encode(record(scope))).getValue("scope")))
        }
    }

    @Test fun nestedPlansMustBeLowercaseHexStringsNotEmbeddedObjectsOrRawSecrets() {
        for (key in listOf("credentialPlan", "dataPlan", "workOriginPlan")) {
            for (literal in nonStrings() + json()) invalid(change(key, literal))
            val canonical = json().getValue(key).jsonPrimitive.content
            for (text in listOf("", "0", "gg", canonical.uppercase(), canonical.dropLast(1), " $canonical", "$canonical\n", "private-canary"))
                invalid(change(key, JsonPrimitive(text)))
        }
    }

    @Test fun nestedByteBoundsRejectOversizedCredentialWorkAndNon170ByteData() {
        for (key in listOf("credentialPlan", "workOriginPlan")) invalid(change(key, JsonPrimitive("00".repeat(4097))))
        for (count in listOf(1, 32, 169, 171, 4096)) invalid(change("dataPlan", JsonPrimitive("00".repeat(count))))
        assertEquals(170, StateActivationPlan.ENCODED_SIZE)
    }

    @Test fun credentialAndWorkPlansMustRetainTheirOwnExactCanonicalBytes() {
        val credential = record().credentialPlan.copyForStorage().copyForCodec().decodeToString()
        val work = record().workOriginPlan.copyForStorage().copyForCodec().decodeToString()
        for ((key, nested) in listOf("credentialPlan" to credential, "workOriginPlan" to work)) {
            val root = Json.parseToJsonElement(nested).jsonObject
            for (variant in listOf(" $nested", "$nested\n", nested.replace(",", ", "),
                JsonObject(root.entries.reversed().associate { it.key to it.value }).toString(),
                nested.replace("\"version\"", "\"\\u0076ersion\"")))
                invalid(change(key, JsonPrimitive(hex(variant.encodeToByteArray()))))
        }
    }

    @Test fun malformedCredentialPlanCannotBeSmuggledThroughTheCompositeWrapper() {
        val original = record().credentialPlan.copyForStorage().copyForCodec().decodeToString()
        val root = Json.parseToJsonElement(original).jsonObject
        for (key in root.keys) invalidNested("credentialPlan", JsonObject(root - key).toString())
        for ((key, value) in mapOf("purpose" to JsonPrimitive("credential-refresh"), "expectedSlotRevision" to JsonPrimitive(0),
            "incarnation" to JsonPrimitive("private-canary"), "target" to JsonPrimitive("A".repeat(64)),
            "authenticationMac" to JsonPrimitive("0".repeat(63))))
            invalidNested("credentialPlan", JsonObject(root + (key to value)).toString())
        invalidNested("credentialPlan", JsonObject(root + ("unknown" to JsonNull)).toString())
    }

    @Test fun malformedWorkPlanCannotBeSmuggledThroughTheCompositeWrapper() {
        val root = Json.parseToJsonElement(record().workOriginPlan.copyForStorage().copyForCodec().decodeToString()).jsonObject
        for (key in root.keys) invalidNested("workOriginPlan", JsonObject(root - key).toString())
        for ((key, value) in mapOf("expectedRevision" to JsonPrimitive(Long.MAX_VALUE), "origin" to JsonPrimitive("private-canary"),
            "proof" to JsonPrimitive("0".repeat(127))))
            invalidNested("workOriginPlan", JsonObject(root + (key to value)).toString())
        invalidNested("workOriginPlan", JsonObject(root + ("entries" to JsonArray(emptyList()))).toString())
    }

    @Test fun dataPlanReceivesStrictStructuralValidationEvenFromItsSizeOnlyConstructor() {
        val variants = listOf<(ByteArray) -> Unit>(
            { it[0] = 2 }, { it[1] = 2 }, { it[2] = 'G'.code.toByte() }, { it[106] = 'G'.code.toByte() },
            { it[73] = 1 }, { it[74] = 1 }, { it[66] = 0x80.toByte() },
            { it[1] = 1; "c".repeat(32).encodeToByteArray().copyInto(it, 74) },
            { it[1] = 1; it[73] = 1; "b".repeat(32).encodeToByteArray().copyInto(it, 74) },
        )
        for (mutate in variants) {
            val invalidData = dataBytes().also(mutate)
            malformed(record().copy(dataPlan = StateActivationPlan(invalidData)))
            invalid(change("dataPlan", JsonPrimitive(hex(invalidData))))
        }
        val inactive = dataBytes().also { it[1] = 1; it[73] = 4; "c".repeat(32).encodeToByteArray().copyInto(it, 74) }
        val original = record().copy(dataPlan = StateActivationPlan(inactive))
        assertRecord(original, SessionSetupPlanCodec.decode(SessionSetupPlanCodec.encode(original)))
    }

    @Test fun duplicateOuterScopeAndEmbeddedPlanKeysAreRejectedBeforeNormalization() {
        for ((key, value) in json()) {
            val declaration = "\"$key\":$value"
            invalid(raw().replace(declaration, "$declaration,$declaration"))
        }
        invalid(raw().replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"))
        invalid(raw().replace("\"actorId\":", "\"\\u0061ctorId\":\"${SCOPE.actorId}\",\"actorId\":"))
        for ((key, nested) in listOf("credentialPlan" to record().credentialPlan.copyForStorage(), "workOriginPlan" to record().workOriginPlan.copyForStorage())) {
            val raw = nested.copyForCodec().decodeToString()
            invalidNested(key, raw.replace("\"version\":1", "\"version\":1,\"version\":1"))
        }
    }

    @Test fun malformedRootsAndDeepDocumentsNeverReturnPartialSetupIntent() {
        for (wire in listOf("", " ", "null", "true", "1", "\"private-canary\"", "[]", "[{}]", "{}", "{", "{}{}", "//private-canary\n{}",
            raw().dropLast(1) + ",}", raw().replace("\"version\":1", "\"version\":NaN"),
            raw().dropLast(1) + ",\"unknown\":" + "[".repeat(12) + "0" + "]".repeat(12) + "}")) invalid(wire)
    }

    @Test fun maximumCompositeWireBoundIs16000BytesAndCanonicalFactoryRejectsPadding() {
        assertEquals(16_000, SessionSetupPlanCodec.MAX_BYTES)
        val padded = raw() + " ".repeat(SessionSetupPlanCodec.MAX_BYTES - raw().encodeToByteArray().size)
        assertEquals(16_000, padded.encodeToByteArray().size)
        assertRecord(record(), SessionSetupPlanCodec.decode(bytes(padded)))
        assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(SessionSetupPlan.fromStorage(bytes(padded))).reason)
        invalid(padded + " ")
    }

    @Test fun publicFactoryRejectsEquivalentOuterOrderWhitespaceAndEscapes() {
        val variants = listOf(" " + raw(), raw() + "\n", raw().replace(",", ", "),
            JsonObject(json().entries.reversed().associate { it.key to it.value }).toString(),
            raw().replace("session-setup", "session-\\u0073etup"), raw().replace("\"version\"", "\"\\u0076ersion\""))
        for (wire in variants) {
            assertRecord(record(), SessionSetupPlanCodec.decode(bytes(wire)))
            assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(SessionSetupPlan.fromStorage(bytes(wire))).reason)
        }
    }

    @Test fun encodingAndInternalCreationValidateAllVisibleFieldsAndNestedDataStructure() {
        malformed(record().copy(operationId = "private-canary"))
        malformed(record().copy(configurationBinding = "invalid"))
        malformed(record().copy(scope = SCOPE.copy(actorKind = ActorKind.DEMO)))
        malformed(record().copy(scope = SCOPE.copy(environment = "\uD800")))
        malformed(record().copy(dataPlan = StateActivationPlan(ByteArray(170))))
        malformed(record().copy(workOriginPlan = SessionWorkOriginPlan.create(workRecord(SCOPE.copy(actorId = "other-owner")))) )
    }

    @Test fun planRecordPayloadAndErrorsNeverEchoAnyNestedCapabilityOrPrivateIdentity() {
        val original = record()
        val plan = SessionSetupPlan.create(original)
        assertEquals("SessionSetupPlan(<redacted>)", plan.toString())
        assertEquals("SessionSetupPlanRecord(<redacted>)", original.toString())
        val secrets = listOf(OPERATION, CREDENTIAL_ID, WORK_ORIGIN, SCOPE.environment, SCOPE.actorId, CONFIGURATION,
            hex(original.credentialPlan.copyForStorage().copyForCodec()), hex(original.dataPlan.copyForStorage()),
            hex(original.workOriginPlan.copyForStorage().copyForCodec()))
        for (view in listOf(plan, original, plan.copyForStorage())) for (secret in secrets) assertFalse(view.toString().contains(secret))
        invalid(raw().replace(OPERATION, "private-canary"))
    }

    private fun malformed(record: SessionSetupPlanRecord) {
        sanitized(assertFailsWith<SessionSetupPlanFormatException> { SessionSetupPlanCodec.encode(record) })
        sanitized(assertFailsWith<SessionSetupPlanFormatException> { SessionSetupPlan.create(record) })
    }
    private fun invalid(raw: String) {
        sanitized(assertFailsWith<SessionSetupPlanFormatException> { SessionSetupPlanCodec.decode(bytes(raw)) })
        val failure = assertIs<PortResult.Failure>(SessionSetupPlan.fromStorage(bytes(raw)))
        assertEquals(FailureReason.INVALID_DATA, failure.reason)
        assertNull(failure.retryAfterSeconds)
    }
    private fun sanitized(error: SessionSetupPlanFormatException) {
        assertEquals("Session setup plan unavailable", error.message)
        assertNull(error.cause)
    }
    private fun invalidNested(key: String, raw: String) = invalid(change(key, JsonPrimitive(hex(raw.encodeToByteArray()))))
    private fun assertRecord(expected: SessionSetupPlanRecord, actual: SessionSetupPlanRecord) {
        assertEquals(expected.operationId, actual.operationId)
        assertEquals(expected.scope, actual.scope)
        assertEquals(expected.configurationBinding, actual.configurationBinding)
        assertContentEquals(expected.credentialPlan.copyForStorage().copyForCodec(), actual.credentialPlan.copyForStorage().copyForCodec())
        assertContentEquals(expected.dataPlan.copyForStorage(), actual.dataPlan.copyForStorage())
        assertContentEquals(expected.workOriginPlan.copyForStorage().copyForCodec(), actual.workOriginPlan.copyForStorage().copyForCodec())
    }
    private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
    private fun raw() = SessionSetupPlanCodec.encode(record()).copyForCodec().decodeToString()
    private fun json(bytes: PrivateBytes = SessionSetupPlanCodec.encode(record())) = Json.parseToJsonElement(bytes.copyForCodec().decodeToString()).jsonObject
    private fun change(key: String, value: JsonElement) = JsonObject(json() + (key to value)).toString()
    private fun nonStrings(): List<JsonElement> = listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap()))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun record(scope: StorageScope = SCOPE) = SessionSetupPlanRecord(OPERATION, scope, CONFIGURATION,
        CredentialCreatePlan.create(credentialRecord()), StateActivationPlan(dataBytes()), SessionWorkOriginPlan.create(workRecord(scope)))
    private fun credentialRecord() = CredentialCreatePlanRecord(7, CREDENTIAL_ID, "a".repeat(64), "b".repeat(64), "c".repeat(64))
    private fun workRecord(scope: StorageScope = SCOPE) = SessionWorkOriginPlanRecord(9, scope, WORK_ORIGIN, PrivateBytes(ByteArray(64) { 44 }))
    private fun dataBytes(): ByteArray = ByteArray(StateActivationPlan.ENCODED_SIZE).also {
        it[0] = 1
        "a".repeat(64).encodeToByteArray().copyInto(it, 2)
        "b".repeat(32).encodeToByteArray().copyInto(it, 106)
        for (index in 138 until 170) it[index] = (index - 138).toByte()
    }

    companion object {
        private val SCOPE = StorageScope("private-environment", ActorKind.ACCOUNT, "private-owner")
        private const val OPERATION = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        private const val CREDENTIAL_ID = "11111111-2222-4333-8444-555555555555"
        private const val WORK_ORIGIN = "22222222-3333-4444-8555-666666666666"
        private val CONFIGURATION = "d4".repeat(32)
    }
}
