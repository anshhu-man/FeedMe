package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.serialization.json.*
import kotlin.test.*

class SessionWorkCodecTest {
    @Test fun idleHasOnlyVersionAndDiscriminatorAndRoundTripsAsTheSingleton() {
        val encoded = SessionWorkCodec.encode(SessionWorkState.Idle)
        assertEquals("{\"version\":1,\"state\":\"idle\"}", encoded.copyForCodec().decodeToString())
        assertSame(SessionWorkState.Idle, SessionWorkCodec.decode(encoded))
    }

    @Test fun activeAccountAndGuestRoundTripExactOwnerOriginAndAllEntryFields() {
        for (scope in listOf(ACCOUNT, ACCOUNT.copy(actorKind = ActorKind.GUEST))) {
            val original = origin(scope = scope)
            val encoded = SessionWorkCodec.encode(original)
            val decoded = assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(encoded))
            assertOrigin(original, decoded)
            assertContentEquals(encoded.copyForCodec(), SessionWorkCodec.encode(decoded).copyForCodec())
            val root = json(encoded)
            assertEquals(setOf("version", "state", "scope", "origin", "entries"), root.keys)
            assertEquals(setOf("environment", "actorKind", "actorId"), root.getValue("scope").jsonObject.keys)
            assertEquals(setOf("id", "kind", "logicalId", "phase"), root.entry().keys)
        }
    }

    @Test fun retiringPreservesEveryPhaseIncludingPartiallyCancelledCheckpoints() {
        for (phase in NativeWorkPhase.entries) for (kind in NativeWorkKind.entries) {
            val original = origin(retiring = true, entries = listOf(entry(kind = kind, phase = phase)))
            val decoded = assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(SessionWorkCodec.encode(original)))
            assertOrigin(original, decoded)
            assertTrue(decoded.retiring)
            assertEquals("retiring", json(SessionWorkCodec.encode(decoded)).getValue("state").jsonPrimitive.content)
        }
    }

    @Test fun emptyActiveAndRetiringOriginsAreValidWithoutInventingEntries() {
        for (retiring in listOf(false, true)) {
            val original = origin(retiring = retiring, entries = emptyList())
            val decoded = assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(SessionWorkCodec.encode(original)))
            assertOrigin(original, decoded)
            assertTrue(decoded.entries.isEmpty())
        }
    }

    @Test fun entryOrderIsPreservedRatherThanSortedOrGroupedByKindAndPhase() {
        val entries = listOf(entry(3, NativeWorkKind.WORKER, "third", NativeWorkPhase.INSTALLED),
            entry(1, NativeWorkKind.TIMER, "first", NativeWorkPhase.CANCELLING),
            entry(2, NativeWorkKind.WORKER, "second", NativeWorkPhase.RESERVED))
        val original = origin(entries = entries)
        assertOrigin(original, assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(SessionWorkCodec.encode(original))))
    }

    @Test fun rootAndDiscriminatorHaveNoLenientOrUnknownFallback() {
        for (raw in listOf("", " ", "null", "true", "1", "\"idle\"", "[]", "[{}]", "{}")) invalid(raw)
        val root = originJson()
        for (state in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap()),
            JsonPrimitive("ACTIVE"), JsonPrimitive("RETIRING"), JsonPrimitive("active "), JsonPrimitive("pending"), JsonPrimitive("")))
            invalid(JsonObject(root + ("state" to state)).toString())
    }

    @Test fun versionIsTheExactLocalIntegerOneNotAnEquivalentRemoteNumber() {
        for (version in listOf("0", "2", "-1", "-0", "+1", "01", "1.0", "1e0", "1E+0", "\"1\"", "true", "null", "[]", "{}", "1".repeat(1001))) {
            invalid("{\"version\":$version,\"state\":\"idle\"}")
            invalid(originJson().toString().replace("\"version\":1", "\"version\":$version"))
        }
    }

    @Test fun idleRejectsExtraOrMissingFieldsEvenIfTheyWouldBeValidForAnOrigin() {
        invalid("{\"version\":1}")
        invalid("{\"state\":\"idle\"}")
        val root = json(SessionWorkCodec.encode(SessionWorkState.Idle))
        for ((field, value) in originJson()) if (field !in root)
            invalid(JsonObject(root + (field to value)).toString())
        invalid(JsonObject(root + ("unknown" to JsonNull)).toString())
    }

    @Test fun originFieldsAreAllRequiredAndCredentialExecutionOrDeadlineFieldsAreRejected() {
        for (retiring in listOf(false, true)) {
            val root = originJson(retiring = retiring)
            for (key in root.keys) invalid(JsonObject(root - key).toString())
            for (key in listOf("accessToken", "refreshToken", "guestToken", "className", "endpoint", "deadline", "expiresAtMillis", "unknown"))
                invalid(JsonObject(root + (key to JsonPrimitive("private-extra"))).toString())
        }
    }

    @Test fun scopeRequiresExactStringFieldsAndRejectsDemoOrUnknownActorKinds() {
        val root = originJson()
        val scope = root.getValue("scope").jsonObject
        for (key in scope.keys) invalid(withScope(root, scope - key))
        invalid(withScope(root, scope + ("unknown" to JsonPrimitive(true))))
        for (key in scope.keys) for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())))
            invalid(withScope(root, scope + (key to value)))
        for (kind in listOf("DEMO", "account", "guest", "ACCOUNT ", "ADMIN", ""))
            invalid(withScope(root, scope + ("actorKind" to JsonPrimitive(kind))))
        for (value in listOf(JsonNull, JsonPrimitive("private-owner"), JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList())))
            invalid(JsonObject(root + ("scope" to value)).toString())
    }

    @Test fun scopeOwnershipBoundsAreStrictButValidSpellingAndUnicodeArePreserved() {
        val root = originJson()
        val scope = root.getValue("scope").jsonObject
        for (key in listOf("environment", "actorId")) for (value in listOf("", " ", "private\nowner", "private\u0000owner", "a".repeat(201)))
            invalid(withScope(root, scope + (key to JsonPrimitive(value))))
        for (owner in listOf(StorageScope(" staging-é ", ActorKind.ACCOUNT, " chef-🍳-e\u0301 "),
            StorageScope("e".repeat(200), ActorKind.GUEST, "é".repeat(200)))) {
            val original = origin(scope = owner)
            assertOrigin(original, assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(SessionWorkCodec.encode(original))))
        }
    }

    @Test fun originMustBeAnExactLowercaseUuidString() {
        val root = originJson()
        for (id in invalidUuids()) invalid(JsonObject(root + ("origin" to JsonPrimitive(id))).toString())
        for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())))
            invalid(JsonObject(root + ("origin" to value)).toString())
    }

    @Test fun entriesMustBeAnArrayOfObjectsWithoutNullOrScalarPlaceholders() {
        val root = originJson()
        for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonPrimitive("entries"), JsonObject(emptyMap())))
            invalid(JsonObject(root + ("entries" to value)).toString())
        for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonPrimitive("entry"), JsonArray(emptyList())))
            invalid(withEntries(root, listOf(value)))
    }

    @Test fun everyEntryFieldIsRequiredAndExtraNativeConfigurationIsNeverStored() {
        val root = originJson()
        for (key in root.entry().keys) invalid(withEntry(root, root.entry() - key))
        for (key in listOf("scope", "origin", "className", "endpoint", "delayMillis", "deadline", "accessToken", "extras", "unknown"))
            invalid(withEntry(root, root.entry() + (key to JsonPrimitive("private-extra"))))
    }

    @Test fun kindsAndPhasesUseExactEnumsWithoutDefaultsOrCaseFolding() {
        val root = originJson()
        for ((field, invalidNames) in listOf("kind" to listOf("timer", "worker", "TIMER ", "JOB", "", "INSTALLED"),
            "phase" to listOf("reserved", "installed", "cancelling", "RESERVED ", "DONE", "", "TIMER"))) {
            for (name in invalidNames) invalid(withEntry(root, root.entry() + (field to JsonPrimitive(name))))
            for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())))
                invalid(withEntry(root, root.entry() + (field to value)))
        }
    }

    @Test fun nativeIdsMustBeExactLowercaseUuidStrings() {
        val root = originJson()
        for (id in invalidUuids()) invalid(withEntry(root, root.entry() + ("id" to JsonPrimitive(id))))
        for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())))
            invalid(withEntry(root, root.entry() + ("id" to value)))
    }

    @Test fun logicalIdsMustBeNonblankControlFreeStringScalars() {
        val root = originJson()
        for (logicalId in listOf("", " ", "\t", "\n", "private\u0000logical", "private\u007flogical", "private\u0085logical"))
            invalid(withEntry(root, root.entry() + ("logicalId" to JsonPrimitive(logicalId))))
        for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())))
            invalid(withEntry(root, root.entry() + ("logicalId" to value)))
    }

    @Test fun logicalIdLimitCountsUtf8BytesAndPreservesOpaqueSpelling() {
        for (logicalId in listOf("a".repeat(200), "é".repeat(100), "🍳".repeat(50))) {
            assertEquals(SessionWorkCodec.MAX_LOGICAL_ID_BYTES, logicalId.encodeToByteArray().size)
            val original = origin(entries = listOf(entry(logicalId = logicalId)))
            assertOrigin(original, assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(SessionWorkCodec.encode(original))))
            val root = originJson()
            invalid(withEntry(root, root.entry() + ("logicalId" to JsonPrimitive(logicalId + "x"))))
            invalidEncode(origin(entries = listOf(entry(logicalId = logicalId + "x"))))
        }
        val original = origin(entries = listOf(entry(logicalId = "  opaque:plan/id?#/é/🍳/e\u0301/\\/\"  ")))
        assertOrigin(original, assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(SessionWorkCodec.encode(original))))
    }

    @Test fun sixtyFourEntriesAreAllowedButASecondPageIsNeverSilentlyDropped() {
        val entries = (1..64).map { entry(it, logicalId = "private-logical-$it") }
        for (retiring in listOf(false, true)) {
            val original = origin(retiring = retiring, entries = entries)
            assertOrigin(original, assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(SessionWorkCodec.encode(original))))
            val root = json(SessionWorkCodec.encode(original))
            invalid(withEntries(root, root.getValue("entries").jsonArray + JsonObject(root.entry() +
                mapOf("id" to JsonPrimitive(id(65)), "logicalId" to JsonPrimitive("private-logical-65")))))
            invalidEncode(origin(retiring = retiring, entries = entries + entry(65, logicalId = "private-logical-65")))
        }
    }

    @Test fun duplicateNativeIdsAreRejectedEvenWhenKindsLogicalIdsAndPhasesDiffer() {
        val first = entry()
        val duplicate = entry(kind = NativeWorkKind.WORKER, logicalId = "other-private-logical", phase = NativeWorkPhase.CANCELLING)
        for (retiring in listOf(false, true)) {
            invalidEncode(origin(retiring = retiring, entries = listOf(first, duplicate)))
            val root = originJson(retiring = retiring)
            invalid(withEntries(root, listOf(root.entry(), JsonObject(root.entry() + mapOf(
                "kind" to JsonPrimitive("WORKER"), "logicalId" to JsonPrimitive("other-private-logical"), "phase" to JsonPrimitive("CANCELLING"))))))
        }
    }

    @Test fun duplicateKindAndLogicalIdRejectsDistinctNativeIdsAndDifferentPhases() {
        for (kind in NativeWorkKind.entries) for (retiring in listOf(false, true)) {
            val first = entry(kind = kind)
            val duplicate = entry(2, kind = kind, phase = NativeWorkPhase.INSTALLED)
            invalidEncode(origin(retiring = retiring, entries = listOf(first, duplicate)))
            val root = json(SessionWorkCodec.encode(origin(retiring = retiring, entries = listOf(first))))
            invalid(withEntries(root, listOf(root.entry(), JsonObject(root.entry() + mapOf(
                "id" to JsonPrimitive(id(2)), "phase" to JsonPrimitive("INSTALLED"))))))
        }
    }

    @Test fun differentKindsMayShareAnExactLogicalIdWithoutBeingDeduplicated() {
        val entries = listOf(entry(kind = NativeWorkKind.TIMER), entry(2, kind = NativeWorkKind.WORKER))
        for (retiring in listOf(false, true)) {
            val original = origin(retiring = retiring, entries = entries)
            val decoded = assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(SessionWorkCodec.encode(original)))
            assertOrigin(original, decoded)
            assertEquals(2, decoded.entries.size)
        }
    }

    @Test fun duplicatePlainAndEscapedJsonKeysFailAtEveryObjectLevel() {
        val raw = originJson().toString()
        invalid(raw.replace("\"version\":1", "\"version\":1,\"version\":1"))
        invalid(raw.replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"))
        invalid(raw.replace("\"actorId\":", "\"actorId\":\"other-private-owner\",\"\\u0061ctorId\":"))
        invalid(raw.replace("\"id\":", "\"id\":\"${id(1)}\",\"id\":"))
        invalid(raw.replace("\"logicalId\":", "\"logicalId\":\"other-private-logical\",\"\\u006cogicalId\":"))
    }

    @Test fun malformedJsonUtf8AndSurrogateStringsFailClosedWithSanitizedErrors() {
        for (raw in listOf("{", "{\"version\":1,}", "{version:1}", "//private-comment\n{}", "{}{}", "{\"version\":NaN}")) invalid(raw)
        for (bad in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xf0.toByte(), 0x9f.toByte())))
            sanitized(assertFailsWith<SessionWorkFormatException> { SessionWorkCodec.decode(PrivateBytes(bad)) })
        for (escaped in listOf("\\uD800", "\\uDC00", "\\uD800x", "\\uDC00\\uD800")) {
            invalid(originJson().toString().replace(LOGICAL_ID, escaped))
            invalid(originJson().toString().replace(ACCOUNT.actorId, escaped))
        }
    }

    @Test fun wireByteLimitIsInclusiveAndDepthRemainsBounded() {
        for (state in listOf(SessionWorkState.Idle, origin())) {
            val raw = SessionWorkCodec.encode(state).copyForCodec().decodeToString()
            val padded = raw + " ".repeat(SessionWorkCodec.MAX_BYTES - raw.encodeToByteArray().size)
            assertEquals(32_768, padded.encodeToByteArray().size)
            val decoded = SessionWorkCodec.decode(bytes(padded))
            if (state is SessionWorkState.Origin) assertOrigin(state, assertIs<SessionWorkState.Origin>(decoded))
            else assertSame(SessionWorkState.Idle, decoded)
            invalid(padded + " ")
        }
        invalid("[".repeat(9) + "0" + "]".repeat(9))
    }

    @Test fun encodingRejectsInvalidUnvalidatedModelsInsteadOfRepairingThem() {
        invalidEncode(origin(scope = ACCOUNT.copy(actorKind = ActorKind.DEMO)))
        for (uuid in invalidUuids()) {
            invalidEncode(SessionWorkState.Origin(ACCOUNT, uuid, false, emptyList()))
            invalidEncode(origin(entries = listOf(SessionWorkEntry(uuid, NativeWorkKind.TIMER, LOGICAL_ID, NativeWorkPhase.RESERVED))))
        }
        for (text in listOf("\uD800", "\uDC00", "private\uD800logical", "", " ", "private\nlogical"))
            invalidEncode(origin(entries = listOf(entry(logicalId = text))))
        for (text in listOf("\uD800", "\uDC00", "private\uD800owner"))
            invalidEncode(origin(scope = ACCOUNT.copy(actorId = text)))
    }

    @Test fun encodingEnforcesWholeDocumentBoundAfterEscapingWithoutTruncation() {
        // Every logical-ID byte needs escaping, including the six-bit unique suffix.
        val entries = (0 until 64).map { index ->
            val suffix = index.toString(2).padStart(6, '0').map { if (it == '0') '"' else '\\' }.joinToString("")
            entry(index + 1, NativeWorkKind.WORKER, "\"".repeat(194) + suffix, NativeWorkPhase.CANCELLING)
        }
        val scope = ACCOUNT.copy(environment = "\"".repeat(200), actorId = "\\".repeat(200))
        assertTrue(entries.all { it.logicalId.encodeToByteArray().size == 200 })
        invalidEncode(origin(scope = scope, entries = entries))
        val smaller = origin(scope = scope, entries = entries.take(32))
        assertOrigin(smaller, assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(SessionWorkCodec.encode(smaller))))
    }

    @Test fun decodedAndEncodedBytesDoNotAliasOrWipeCallerOwnedBuffers() {
        val expected = SessionWorkCodec.encode(origin()).copyForCodec()
        val source = expected.copyOf()
        val bytes = PrivateBytes(source)
        source.fill(0)
        val callerCopy = bytes.copyForCodec()
        SessionWorkCodec.decode(bytes)
        assertContentEquals(expected, callerCopy)
        callerCopy.fill(0)
        assertContentEquals(expected, bytes.copyForCodec())
    }

    @Test fun entriesAreDetachedAndPhaseChangesDoNotMutateEarlierState() {
        val source = mutableListOf(entry(), entry(2, logicalId = "other-private-logical"))
        val original = origin(entries = source)
        source.clear()
        assertEquals(2, original.entries.size)
        val retiring = original.retiring()
        val changed = original.withEntries(listOf(original.entries[0].phase(NativeWorkPhase.CANCELLING)))
        assertFalse(original.retiring)
        assertTrue(retiring.retiring)
        assertEquals(NativeWorkPhase.RESERVED, original.entries[0].phase)
        assertEquals(NativeWorkPhase.CANCELLING, changed.entries.single().phase)
        assertOrigin(original, assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(SessionWorkCodec.encode(original))))
    }

    @Test fun diagnosticModelsAndFormatExceptionsDoNotEchoPrivateIdentity() {
        val first = entry()
        val lease = SessionBoundary().activate(ACCOUNT)
        val models = listOf(origin(), origin(retiring = true), first, first.ticket(), NativeWorkStatus(first.ticket(), first.phase),
            SessionWorkBinding(lease, ORIGIN), SessionWorkCodec.encode(origin()))
        for (model in models) for (secret in listOf(ORIGIN, id(1), ACCOUNT.actorId, LOGICAL_ID))
            assertFalse(model.toString().contains(secret))
        val error = assertFailsWith<SessionWorkFormatException> {
            SessionWorkCodec.decode(bytes("{\"private\":\"$LOGICAL_ID/${ACCOUNT.actorId}/$ORIGIN\"}"))
        }
        sanitized(error)
        assertNull(error.cause)
    }

    companion object {
        private val ACCOUNT = StorageScope("test", ActorKind.ACCOUNT, "private-work-owner")
        private const val ORIGIN = "123e4567-e89b-12d3-a456-426614174abc"
        private const val LOGICAL_ID = "private-logical-id"
        private fun id(index: Int) = "123e4567-e89b-12d3-a456-${index.toString().padStart(12, '0')}"
        private fun entry(index: Int = 1, kind: NativeWorkKind = NativeWorkKind.TIMER, logicalId: String = LOGICAL_ID,
            phase: NativeWorkPhase = NativeWorkPhase.RESERVED) = SessionWorkEntry(id(index), kind, logicalId, phase)
        private fun origin(scope: StorageScope = ACCOUNT, retiring: Boolean = false, entries: List<SessionWorkEntry> = listOf(entry())) =
            SessionWorkState.Origin(scope, ORIGIN, retiring, entries)
        private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
        private fun json(bytes: PrivateBytes) = Json.parseToJsonElement(bytes.copyForCodec().decodeToString()).jsonObject
        private fun originJson(retiring: Boolean = false) = json(SessionWorkCodec.encode(origin(retiring = retiring)))
        private fun JsonObject.entry() = getValue("entries").jsonArray.first().jsonObject
        private fun withScope(root: JsonObject, scope: Map<String, JsonElement>) = JsonObject(root + ("scope" to JsonObject(scope))).toString()
        private fun withEntries(root: JsonObject, entries: List<JsonElement>) = JsonObject(root + ("entries" to JsonArray(entries))).toString()
        private fun withEntry(root: JsonObject, entry: Map<String, JsonElement>) = withEntries(root, listOf(JsonObject(entry)))
        private fun invalidUuids() = listOf("", ORIGIN.uppercase(), " $ORIGIN", "$ORIGIN ", ORIGIN.dropLast(1), "g" + ORIGIN.drop(1), "private-id")
        private fun assertOrigin(expected: SessionWorkState.Origin, actual: SessionWorkState.Origin) {
            assertEquals(expected.scope, actual.scope)
            assertEquals(expected.origin, actual.origin)
            assertEquals(expected.retiring, actual.retiring)
            assertEquals(expected.entries.map { listOf(it.id, it.kind.name, it.logicalId, it.phase.name) },
                actual.entries.map { listOf(it.id, it.kind.name, it.logicalId, it.phase.name) })
        }
        private fun invalid(raw: String) = sanitized(assertFailsWith<SessionWorkFormatException> { SessionWorkCodec.decode(bytes(raw)) })
        private fun invalidEncode(state: SessionWorkState) = sanitized(assertFailsWith<SessionWorkFormatException> { SessionWorkCodec.encode(state) })
        private fun sanitized(error: SessionWorkFormatException) {
            assertEquals("Native work data unavailable", error.message)
            for (secret in listOf(ORIGIN, id(1), ACCOUNT.actorId, LOGICAL_ID)) assertFalse(error.toString().contains(secret))
            assertNull(error.cause)
        }
    }
}
