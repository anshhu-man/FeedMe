package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** The private native protocol is stricter than remote JSON numbers and is never a token parser. */
class CredentialCodecTest {
    @Test fun accountRoundTripPreservesEveryPrivateValueAndExactOwner() {
        val original = snapshot()
        val wire = CredentialCodec.encodeSnapshot(original)
        val decoded = CredentialCodec.decodeSnapshot(wire)
        assertEquals(INCARNATION, decoded.incarnation)
        assertEquals(7L, decoded.revision)
        assertEquals(ACCOUNT, decoded.scope)
        val credentials = assertIs<StoredCredentials.Account>(decoded.credentials)
        assertEquals(ACCESS, credentials.accessToken.use { it })
        assertEquals(REFRESH, credentials.refreshToken!!.use { it })
        assertEquals(DEVICE, credentials.deviceSessionId!!.use { it })
        assertEquals(EXPIRY, credentials.expiresAtMillis)
        assertContentEquals(wire.copyForCodec(), CredentialCodec.encodeSnapshot(decoded).copyForCodec())
        val root = json(wire)
        assertEquals(setOf("version", "incarnation", "revision", "credentials"), root.keys)
        assertEquals(setOf("scope", "expiresAtMillis", "kind", "accessToken", "refreshToken", "deviceSessionId"), root.credentials().keys)
        assertEquals(setOf("environment", "actorKind", "actorId"), root.credentials().getValue("scope").jsonObject.keys)
        assertEquals("account", root.credentials().getValue("kind").jsonPrimitive.content)
    }

    @Test fun nullableRefreshAndDeviceAreExplicitAndNeverDefaultedFromMissingFields() {
        for (refresh in listOf(null, SecretText(REFRESH))) for (device in listOf(null, SecretText(DEVICE))) {
            val wire = CredentialCodec.encodeSnapshot(snapshot(account(refresh = refresh, device = device)))
            val root = json(wire)
            val credentials = assertIs<StoredCredentials.Account>(CredentialCodec.decodeSnapshot(wire).credentials)
            assertEquals(refresh?.use { it }, credentials.refreshToken?.use { it })
            assertEquals(device?.use { it }, credentials.deviceSessionId?.use { it })
            if (refresh == null) assertEquals(JsonNull, root.credentials()["refreshToken"])
            if (device == null) assertEquals(JsonNull, root.credentials()["deviceSessionId"])
            invalidSnapshot(withCredentials(root, root.credentials() - "refreshToken"))
            invalidSnapshot(withCredentials(root, root.credentials() - "deviceSessionId"))
        }
    }

    @Test fun guestRoundTripKeepsOpaqueSessionIdentityAndHasNoAccountFields() {
        val sessionId = "opaque-guest-session/not-a-uuid"
        val wire = CredentialCodec.encodeSnapshot(snapshot(guest(sessionId = sessionId)))
        val decoded = CredentialCodec.decodeSnapshot(wire)
        val credentials = assertIs<StoredCredentials.Guest>(decoded.credentials)
        assertEquals(GUEST, decoded.scope)
        assertEquals(sessionId, credentials.guestSessionId.use { it })
        assertEquals(GUEST_TOKEN, credentials.guestToken.use { it })
        assertEquals(EXPIRY, credentials.expiresAtMillis)
        assertEquals(setOf("scope", "expiresAtMillis", "kind", "guestSessionId", "guestToken"), json(wire).credentials().keys)
        assertContentEquals(wire.copyForCodec(), CredentialCodec.encodeSnapshot(decoded).copyForCodec())
    }

    @Test fun manifestRetainsDurableEmptyRevisionAndExactOccupiedScopeWithoutSecrets() {
        for (owner in listOf(null, ACCOUNT, GUEST)) for (revision in listOf(1L, 2L, Long.MAX_VALUE)) {
            val original = CredentialManifest(revision, owner, if (owner == null) null else INCARNATION)
            val wire = CredentialCodec.encodeManifest(original)
            val decoded = CredentialCodec.decodeManifest(wire)
            assertEquals(revision, decoded.revision)
            assertEquals(owner, decoded.scope)
            assertEquals(original.incarnation, decoded.incarnation)
            val root = json(wire)
            assertEquals(setOf("version", "revision", "scope", "incarnation"), root.keys)
            if (owner == null) {
                assertEquals(JsonNull, root["scope"])
                assertEquals(JsonNull, root["incarnation"])
            }
            val raw = wire.copyForCodec().decodeToString()
            for (secret in listOf(ACCESS, REFRESH, GUEST_TOKEN, DEVICE)) assertFalse(raw.contains(secret))
            assertContentEquals(wire.copyForCodec(), CredentialCodec.encodeManifest(decoded).copyForCodec())
        }
    }

    @Test fun snapshotsRequireEveryExactKeyAndRejectAdditionalKeysAtBothCredentialKinds() {
        for (root in listOf(snapshotJson(), snapshotJson(guest()))) {
            for (key in root.keys) invalidSnapshot(JsonObject(root - key).toString())
            for (key in root.credentials().keys) invalidSnapshot(withCredentials(root, root.credentials() - key))
            for (key in listOf("unknown", "password", "refresh_token", "identityProof")) {
                invalidSnapshot(JsonObject(root + (key to JsonPrimitive("private-extra"))).toString())
                invalidSnapshot(withCredentials(root, root.credentials() + (key to JsonPrimitive("private-extra"))))
            }
        }
    }

    @Test fun manifestRequiresEveryExactKeyAndDoesNotAcceptCredentialPayloads() {
        val root = manifestJson()
        for (key in root.keys) invalidManifest(JsonObject(root - key).toString())
        for (key in listOf("credentials", "accessToken", "refreshToken", "deviceSessionId", "unknown"))
            invalidManifest(JsonObject(root + (key to JsonPrimitive(ACCESS))).toString())
        invalidManifest(snapshotJson().toString())
        invalidSnapshot(root.toString())
    }

    @Test fun documentAndCredentialContainersMustBeObjects() {
        for (raw in listOf("", " ", "null", "true", "1", "\"private-text\"", "[]", "[{}]", "{}")) {
            invalidSnapshot(raw)
            invalidManifest(raw)
        }
        for (raw in listOf("null", "[]", "true", "7", "\"private-text\""))
            invalidSnapshot(JsonObject(snapshotJson() + ("credentials" to Json.parseToJsonElement(raw))).toString())
    }

    @Test fun duplicatePlainAndEscapedKeysFailAtRootCredentialAndScopeLevels() {
        val snapshot = snapshotJson().toString()
        val manifest = manifestJson().toString()
        for (replacement in listOf("\"version\":1,\"version\":1", "\"version\":1,\"\\u0076ersion\":1")) {
            invalidSnapshot(snapshot.replace("\"version\":1", replacement))
            invalidManifest(manifest.replace("\"version\":1", replacement))
        }
        invalidSnapshot(snapshot.replace("\"accessToken\":", "\"accessToken\":\"earlier-private-token\",\"accessToken\":"))
        invalidSnapshot(snapshot.replace("\"actorId\":", "\"actorId\":\"other-owner\",\"\\u0061ctorId\":"))
        invalidManifest(manifest.replace("\"actorId\":", "\"actorId\":\"other-owner\",\"actorId\":"))
    }

    @Test fun malformedJsonUtf8AndUnicodeAreRejectedWithSanitizedErrors() {
        for (raw in listOf("{", "{\"version\":1,}", "{version:1}", "//private-comment\n{}", "{}{}", "{\"version\":NaN}")) {
            invalidSnapshot(raw)
            invalidManifest(raw)
        }
        for (bad in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xf0.toByte(), 0x9f.toByte()))) {
            assertSanitized(assertFailsWith<CredentialFormatException> { CredentialCodec.decodeSnapshot(PrivateBytes(bad)) })
            assertSanitized(assertFailsWith<CredentialFormatException> { CredentialCodec.decodeManifest(PrivateBytes(bad)) })
        }
        for (escaped in listOf("\\uD800", "\\uDC00", "\\uD800x", "\\uDC00\\uD800")) {
            invalidSnapshot(snapshotJson().toString().replace(ACCESS, escaped))
            invalidManifest(manifestJson().toString().replace(ACCOUNT.actorId, escaped))
        }
    }

    @Test fun schemaVersionRequiresTheExactLocalIntegerOne() {
        for (number in listOf("0", "2", "-1", "-0", "01", "+1", "1.0", "1e0", "1E+0", "\"1\"", "null", "true", "[]", "{}")) {
            invalidSnapshot(replaceRootNumber(snapshotJson(), "version", number))
            invalidManifest(replaceRootNumber(manifestJson(), "version", number))
        }
    }

    @Test fun localRevisionIsPositiveLongAndRejectsRemoteEquivalentNumberSpellings() {
        for (number in listOf("0", "-1", "-0", "01", "+1", "1.0", "1e0", "1E+3", "\"7\"", "null", "true", "[]", "{}",
            "9223372036854775808", "1".repeat(1001))) {
            invalidSnapshot(replaceRootNumber(snapshotJson(), "revision", number))
            invalidManifest(replaceRootNumber(manifestJson(), "revision", number))
        }
        for (revision in listOf(1L, 7L, Long.MAX_VALUE)) {
            val root = snapshotJson()
            assertEquals(revision, CredentialCodec.decodeSnapshot(bytes(replaceRootNumber(root, "revision", revision.toString()))).revision)
        }
    }

    @Test fun expiryIsAnExactNonnegativeLongNotAClockDecisionOrFloatingPointValue() {
        val root = snapshotJson()
        for (number in listOf("-1", "-0", "00", "+0", "0.0", "1.5", "1e0", "1E+3", "\"0\"", "null", "true", "[]", "{}", "9223372036854775808"))
            invalidSnapshot(root.toString().replace("\"expiresAtMillis\":$EXPIRY", "\"expiresAtMillis\":$number"))
        for (expiry in listOf(0L, 1L, EXPIRY, Long.MAX_VALUE)) for (credentials in listOf(account(expiry = expiry), guest(expiry = expiry)))
            assertEquals(expiry, CredentialCodec.decodeSnapshot(CredentialCodec.encodeSnapshot(snapshot(credentials))).credentials.expiresAtMillis)
    }

    @Test fun actorKindAndCredentialDiscriminatorMustAgreeExactlyAndNeverAcceptDemo() {
        for (root in listOf(snapshotJson(), snapshotJson(guest()))) {
            for (kind in listOf("DEMO", "account", "guest", "ACCOUNT ", "ADMIN", ""))
                invalidSnapshot(withSnapshotScope(root, root.scope() + ("actorKind" to JsonPrimitive(kind))))
            val wrong = if (root.credentials().getValue("kind").jsonPrimitive.content == "account") "GUEST" else "ACCOUNT"
            invalidSnapshot(withSnapshotScope(root, root.scope() + ("actorKind" to JsonPrimitive(wrong))))
            for (kind in listOf("ACCOUNT", "GUEST", "demo", "account ", "", "refresh"))
                invalidSnapshot(withCredentials(root, root.credentials() + ("kind" to JsonPrimitive(kind))))
            for (kind in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList())))
                invalidSnapshot(withCredentials(root, root.credentials() + ("kind" to kind)))
        }
        for (kind in listOf("DEMO", "account", "guest", "ACCOUNT ", "ADMIN"))
            invalidManifest(withManifestScope(manifestJson().getValue("scope").jsonObject + ("actorKind" to JsonPrimitive(kind))))
    }

    @Test fun scopesRequireExactKeysStringsAndCoreOwnershipBounds() {
        val root = snapshotJson()
        val scope = root.scope()
        for (key in scope.keys) assertInvalidScope(scope - key)
        assertInvalidScope(scope + ("unknown" to JsonPrimitive(true)))
        for (field in listOf("environment", "actorId")) {
            for (value in listOf("", " ", "\n", "private\u0000owner", "a".repeat(201)))
                assertInvalidScope(scope + (field to JsonPrimitive(value)))
            for (value in listOf(JsonNull, JsonPrimitive(12), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())))
                assertInvalidScope(scope + (field to value))
        }
        for (value in listOf(JsonNull, JsonPrimitive(12), JsonPrimitive(true), JsonPrimitive("ACCOUNT"), JsonArray(emptyList()))) {
            invalidSnapshot(withCredentials(root, root.credentials() + ("scope" to value)))
            invalidManifest(JsonObject(manifestJson() + ("scope" to value)).toString())
        }
    }

    @Test fun validUnicodeAndWhitespaceOwnershipAndSecretsArePreservedWithoutNormalization() {
        val scope = StorageScope(" staging-é ", ActorKind.ACCOUNT, " chef-🍳-e\u0301 ")
        val token = "  token/é/🍳/e\u0301/\\/\"  "
        val credentials = account(scope = scope, access = token, refresh = SecretText(token))
        val decoded = CredentialCodec.decodeSnapshot(CredentialCodec.encodeSnapshot(snapshot(credentials)))
        assertEquals(scope, decoded.scope)
        val account = assertIs<StoredCredentials.Account>(decoded.credentials)
        assertEquals(token, account.accessToken.use { it })
        assertEquals(token, account.refreshToken!!.use { it })
        val manifest = CredentialCodec.decodeManifest(CredentialCodec.encodeManifest(CredentialManifest(2, scope, INCARNATION)))
        assertEquals(scope, manifest.scope)
        val longestScope = ACCOUNT.copy(environment = "e".repeat(200), actorId = "é".repeat(200))
        assertEquals(longestScope, CredentialCodec.decodeSnapshot(CredentialCodec.encodeSnapshot(snapshot(account(scope = longestScope)))).scope)
    }

    @Test fun secretsAreMandatoryStringScalarsAndRejectBlankOrControlText() {
        for ((root, fields) in listOf(snapshotJson() to listOf("accessToken", "refreshToken", "deviceSessionId"),
            snapshotJson(guest()) to listOf("guestSessionId", "guestToken"))) {
            for (field in fields) {
                for (value in listOf("", " ", "\t", "\n", "private\u0000token", "private\u007ftoken", "private\u0085token"))
                    invalidSnapshot(withCredentials(root, root.credentials() + (field to JsonPrimitive(value))))
                for (value in listOf(JsonPrimitive(123), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())))
                    invalidSnapshot(withCredentials(root, root.credentials() + (field to value)))
                if (field !in setOf("refreshToken", "deviceSessionId"))
                    invalidSnapshot(withCredentials(root, root.credentials() + (field to JsonNull)))
            }
        }
    }

    @Test fun externalDeviceUuidAcceptsMixedCaseAndPreservesItsExactSpelling() {
        for (device in listOf(DEVICE, DEVICE.uppercase(), "123E4567-e89B-12d3-A456-426614174AbC")) {
            val decoded = CredentialCodec.decodeSnapshot(CredentialCodec.encodeSnapshot(snapshot(account(device = SecretText(device)))))
            assertEquals(device, assertIs<StoredCredentials.Account>(decoded.credentials).deviceSessionId!!.use { it })
        }
        for (device in listOf("", "private-session", DEVICE.dropLast(1), "{$DEVICE}", "$DEVICE ", " $DEVICE", "g" + DEVICE.drop(1))) {
            val root = snapshotJson()
            invalidSnapshot(withCredentials(root, root.credentials() + ("deviceSessionId" to JsonPrimitive(device))))
        }
    }

    @Test fun localIncarnationIsAnExactLowercaseUuidAndCannotBeNullWhenOccupied() {
        for (incarnation in listOf("", INCARNATION.uppercase(), " $INCARNATION", "$INCARNATION ", INCARNATION.dropLast(1), "g" + INCARNATION.drop(1))) {
            invalidSnapshot(JsonObject(snapshotJson() + ("incarnation" to JsonPrimitive(incarnation))).toString())
            invalidManifest(JsonObject(manifestJson() + ("incarnation" to JsonPrimitive(incarnation))).toString())
        }
        for (incarnation in listOf(JsonNull, JsonPrimitive(123), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap()))) {
            invalidSnapshot(JsonObject(snapshotJson() + ("incarnation" to incarnation)).toString())
            invalidManifest(JsonObject(manifestJson() + ("incarnation" to incarnation)).toString())
        }
    }

    @Test fun manifestNeverAcceptsHalfOccupiedSlotOrNullAndMissingAsEquivalent() {
        val occupied = manifestJson()
        invalidManifest(JsonObject(occupied + ("scope" to JsonNull)).toString())
        invalidManifest(JsonObject(occupied + ("incarnation" to JsonNull)).toString())
        val empty = json(CredentialCodec.encodeManifest(CredentialManifest(1, null, null)))
        for (key in listOf("scope", "incarnation")) invalidManifest(JsonObject(empty - key).toString())
        assertFailsWith<IllegalArgumentException> { CredentialManifest(1, ACCOUNT, null) }
        assertFailsWith<IllegalArgumentException> { CredentialManifest(1, null, INCARNATION) }
    }

    @Test fun eachOpaqueSecretUsesUtf8ByteLimitRatherThanUtf16CharacterCount() {
        for (token in listOf("a".repeat(16_384), "é".repeat(8192), "🍳".repeat(4096))) {
            assertEquals(CredentialCodec.MAX_SECRET_BYTES, token.encodeToByteArray().size)
            for (credentials in listOf(account(access = token), account(refresh = SecretText(token)),
                guest(sessionId = token), guest(token = token))) {
                val decoded = CredentialCodec.decodeSnapshot(CredentialCodec.encodeSnapshot(snapshot(credentials)))
                assertEquals(credentials.expiresAtMillis, decoded.credentials.expiresAtMillis)
                when (val actual = decoded.credentials) {
                    is StoredCredentials.Account -> assertEquals(token, if (credentials is StoredCredentials.Account && credentials.accessToken.use { it } == token)
                        actual.accessToken.use { it } else actual.refreshToken!!.use { it })
                    is StoredCredentials.Guest -> assertEquals(token, if (credentials is StoredCredentials.Guest && credentials.guestSessionId.use { it } == token)
                        actual.guestSessionId.use { it } else actual.guestToken.use { it })
                }
            }
            val oversized = token + "x"
            for ((root, field) in listOf(snapshotJson() to "accessToken", snapshotJson() to "refreshToken",
                snapshotJson(guest()) to "guestSessionId", snapshotJson(guest()) to "guestToken"))
                invalidSnapshot(withCredentials(root, root.credentials() + (field to JsonPrimitive(oversized))))
            for (credentials in listOf(account(access = oversized), account(refresh = SecretText(oversized)),
                guest(sessionId = oversized), guest(token = oversized)))
                assertFails { CredentialCodec.encodeSnapshot(snapshot(credentials)) }
        }
    }

    @Test fun encodedDocumentMustFitByteLimitEvenWhenEachEscapedSecretFitsItsOwnLimit() {
        val credentials = account(access = "\"".repeat(16_384), refresh = SecretText("\\".repeat(16_384)))
        assertSanitized(assertFailsWith<CredentialFormatException> { CredentialCodec.encodeSnapshot(snapshot(credentials)) })
        val shorter = "\"\\".repeat(4000)
        val decoded = CredentialCodec.decodeSnapshot(CredentialCodec.encodeSnapshot(snapshot(account(access = shorter))))
        assertEquals(shorter, assertIs<StoredCredentials.Account>(decoded.credentials).accessToken.use { it })
    }

    @Test fun maximumWireBytesAreInclusiveAndTrailingPaddingCannotBypassTheBound() {
        for (raw in listOf(snapshotJson().toString(), manifestJson().toString())) {
            val padded = raw + " ".repeat(CredentialCodec.MAX_BYTES - raw.encodeToByteArray().size)
            assertEquals(CredentialCodec.MAX_BYTES, padded.encodeToByteArray().size)
            if (raw.contains("credentials")) {
                assertEquals(7L, CredentialCodec.decodeSnapshot(bytes(padded)).revision)
                invalidSnapshot(padded + " ")
            } else {
                assertEquals(7L, CredentialCodec.decodeManifest(bytes(padded)).revision)
                invalidManifest(padded + " ")
            }
        }
        val nested = "[".repeat(9) + "0" + "]".repeat(9)
        invalidSnapshot(nested)
        invalidManifest(nested)
    }

    @Test fun encodeDoesNotReplaceMalformedUnicodeOrStoreInvalidDeviceIds() {
        for (text in listOf("\uD800", "\uDC00", "private\uD800token", "\uD800x", "\uDC00\uD800")) {
            for (credentials in listOf(account(access = text), account(refresh = SecretText(text)),
                account(device = SecretText(text)), guest(sessionId = text), guest(token = text))) {
                assertSanitized(assertFailsWith<CredentialFormatException> {
                    CredentialCodec.encodeSnapshot(snapshot(credentials))
                })
            }
            for (scope in listOf(ACCOUNT.copy(environment = text), ACCOUNT.copy(actorId = text))) {
                assertSanitized(assertFailsWith<CredentialFormatException> {
                    CredentialCodec.encodeSnapshot(snapshot(account(scope = scope)))
                })
                assertSanitized(assertFailsWith<CredentialFormatException> {
                    CredentialCodec.encodeManifest(CredentialManifest(2, scope, INCARNATION))
                })
            }
        }
        assertSanitized(assertFailsWith<CredentialFormatException> {
            CredentialCodec.encodeSnapshot(snapshot(account(device = SecretText("private-not-a-device-uuid"))))
        })
    }

    @Test fun byteOwnershipIsDetachedAndDecodingDoesNotZeroTheCallersRecord() {
        val expected = CredentialCodec.encodeSnapshot(snapshot()).copyForCodec()
        val source = expected.copyOf()
        val privateBytes = PrivateBytes(source)
        source.fill(0)
        val callerCopy = privateBytes.copyForCodec()
        CredentialCodec.decodeSnapshot(privateBytes)
        assertContentEquals(expected, callerCopy)
        callerCopy.fill(0)
        assertContentEquals(expected, privateBytes.copyForCodec())
        val manifest = CredentialCodec.encodeManifest(CredentialManifest(7, ACCOUNT, INCARNATION))
        val saved = manifest.copyForCodec()
        CredentialCodec.decodeManifest(manifest)
        assertContentEquals(saved, manifest.copyForCodec())
    }

    @Test fun modelDebugStringsAndFormatErrorsNeverExposeIdentityOrCredentialMaterial() {
        val models = listOf(snapshot(), snapshot(guest()), CredentialManifest(7, ACCOUNT, INCARNATION),
            CredentialSlotState(7, ACCOUNT, INCARNATION), CredentialCodec.encodeSnapshot(snapshot()),
            account(), guest(), SecretText(ACCESS))
        for (model in models) for (secret in listOf(INCARNATION, ACCOUNT.actorId, GUEST.actorId, ACCESS, REFRESH, GUEST_TOKEN, DEVICE))
            assertFalse(model.toString().contains(secret))
        val error = assertFailsWith<CredentialFormatException> {
            CredentialCodec.decodeSnapshot(bytes("{\"password\":\"$ACCESS\",\"owner\":\"${ACCOUNT.actorId}\"}"))
        }
        assertSanitized(error)
        assertNull(error.cause)
    }

    @Test fun metadataModelsRejectInvalidRevisionsIncarnationsAndDemoSlots() {
        for (revision in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { CredentialSnapshot(INCARNATION, revision, account()) }
            assertFailsWith<IllegalArgumentException> { CredentialManifest(revision, null, null) }
            assertFailsWith<IllegalArgumentException> { CredentialSlotState(revision, null, null) }
        }
        for (incarnation in listOf("", INCARNATION.uppercase(), "$INCARNATION ")) {
            assertFailsWith<IllegalArgumentException> { CredentialSnapshot(incarnation, 1, account()) }
            assertFailsWith<IllegalArgumentException> { CredentialManifest(1, ACCOUNT, incarnation) }
            assertFailsWith<IllegalArgumentException> { CredentialSlotState(1, ACCOUNT, incarnation) }
        }
        assertFailsWith<IllegalArgumentException> { CredentialSlotState(1, ACCOUNT, null) }
        assertFailsWith<IllegalArgumentException> { CredentialSlotState(1, null, INCARNATION) }
        assertFailsWith<IllegalArgumentException> { CredentialSlotState(1, ACCOUNT.copy(actorKind = ActorKind.DEMO), INCARNATION) }
        assertFailsWith<IllegalArgumentException> { CredentialManifest(1, ACCOUNT.copy(actorKind = ActorKind.DEMO), INCARNATION) }
    }

    companion object {
        private val ACCOUNT = StorageScope("test", ActorKind.ACCOUNT, "private-account-owner")
        private val GUEST = StorageScope("test", ActorKind.GUEST, "private-guest-owner")
        private const val INCARNATION = "123e4567-e89b-12d3-a456-426614174abc"
        private const val DEVICE = "123e4567-e89b-12d3-a456-426614174def"
        private const val ACCESS = "private-access-token"
        private const val REFRESH = "private-refresh-token"
        private const val GUEST_TOKEN = "private-guest-token"
        private const val EXPIRY = 1_800_000_000_000L
        private fun account(scope: StorageScope = ACCOUNT, access: String = ACCESS, refresh: SecretText? = SecretText(REFRESH),
            expiry: Long = EXPIRY, device: SecretText? = SecretText(DEVICE)) =
            StoredCredentials.Account(scope, SecretText(access), refresh, expiry, device)
        private fun guest(sessionId: String = "opaque-guest-session", token: String = GUEST_TOKEN, expiry: Long = EXPIRY) =
            StoredCredentials.Guest(GUEST, SecretText(sessionId), SecretText(token), expiry)
        private fun snapshot(credentials: StoredCredentials = account()) = CredentialSnapshot(INCARNATION, 7, credentials)
        private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
        private fun json(bytes: PrivateBytes) = Json.parseToJsonElement(bytes.copyForCodec().decodeToString()).jsonObject
        private fun snapshotJson(credentials: StoredCredentials = account()) = json(CredentialCodec.encodeSnapshot(snapshot(credentials)))
        private fun manifestJson() = json(CredentialCodec.encodeManifest(CredentialManifest(7, ACCOUNT, INCARNATION)))
        private fun JsonObject.credentials() = getValue("credentials").jsonObject
        private fun JsonObject.scope() = credentials().getValue("scope").jsonObject
        private fun withCredentials(root: JsonObject, credentials: Map<String, JsonElement>) =
            JsonObject(root + ("credentials" to JsonObject(credentials))).toString()
        private fun withSnapshotScope(root: JsonObject, scope: Map<String, JsonElement>) =
            withCredentials(root, root.credentials() + ("scope" to JsonObject(scope)))
        private fun withManifestScope(scope: Map<String, JsonElement>) =
            JsonObject(manifestJson() + ("scope" to JsonObject(scope))).toString()
        private fun assertInvalidScope(scope: Map<String, JsonElement>) {
            invalidSnapshot(withSnapshotScope(snapshotJson(), scope))
            invalidManifest(withManifestScope(scope))
        }
        private fun replaceRootNumber(root: JsonObject, field: String, value: String) =
            root.toString().replace("\"$field\":${root.getValue(field)}", "\"$field\":$value")
        private fun invalidSnapshot(raw: String) {
            assertSanitized(assertFailsWith<CredentialFormatException> { CredentialCodec.decodeSnapshot(bytes(raw)) })
        }
        private fun invalidManifest(raw: String) {
            assertSanitized(assertFailsWith<CredentialFormatException> { CredentialCodec.decodeManifest(bytes(raw)) })
        }
        private fun assertSanitized(error: CredentialFormatException) {
            assertEquals("Credential data unavailable", error.message)
            for (secret in listOf(INCARNATION, ACCOUNT.actorId, ACCESS, REFRESH, GUEST_TOKEN, DEVICE))
                assertFalse(error.toString().contains(secret))
        }
    }
}
