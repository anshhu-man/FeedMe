package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.StateRetirementTarget
import kotlinx.serialization.json.*
import kotlin.test.*

class RetirementCodecTest {
    @Test fun initialStateMatchesNativeControlFormatExactly() {
        assertEquals("{\"version\":1,\"state\":\"idle\"}", RetirementCodec.encode(RetirementState.Idle).copyForCodec().decodeToString())
        assertSame(RetirementState.Idle, RetirementCodec.decode(bytes("{\"version\":1,\"state\":\"idle\"}")))
    }

    @Test fun pendingRoundTripsExactOpaqueTargetsAndIncarnations() {
        val original = pending(setOf(RetirementStep.CREDENTIALS))
        val decoded = assertIs<RetirementState.Pending>(RetirementCodec.decode(RetirementCodec.encode(original)))
        assertEquals(original.scope, decoded.scope)
        assertEquals(ID, decoded.operationId); assertEquals(ORIGIN, decoded.origin); assertEquals(CREDENTIALS, decoded.credentialIncarnation)
        assertEquals(original.done, decoded.done)
        assertContentEquals(original.target.copyForStorage(), decoded.target.copyForStorage())
    }

    @Test fun completedStateRemovesOwnerOriginAndTargets() {
        val raw = RetirementCodec.encode(RetirementState.Complete(ID)).copyForCodec().decodeToString()
        assertEquals(setOf("version", "state", "operationId"), Json.parseToJsonElement(raw).jsonObject.keys)
        assertFalse(raw.contains("private-owner")); assertFalse(raw.contains(ORIGIN)); assertFalse(raw.contains(CREDENTIALS))
        assertEquals(ID, assertIs<RetirementState.Complete>(RetirementCodec.decode(bytes(raw))).operationId)
    }

    @Test fun schemaVersionIsExactAndUnknownKeysAreRejected() {
        for (version in listOf("0", "2", "1.0", "1e0", "\"1\"", "true", "null", "-0")) invalid("{\"version\":$version,\"state\":\"idle\"}")
        for (raw in listOf("{}", "[]", "null", "true", "{\"version\":1}", "{\"version\":1,\"state\":\"idle\",\"token\":\"secret\"}",
            "{\"version\":1,\"state\":\"active\"}", "{\"version\":1,\"state\":\"complete\"}")) invalid(raw)
    }

    @Test fun duplicateKeysMalformedUtf8AndSurrogatesFailClosed() {
        for (raw in listOf("{\"version\":1,\"version\":1,\"state\":\"idle\"}",
            "{\"version\":1,\"\\u0073tate\":\"idle\",\"state\":\"pending\"}",
            "{\"version\":1,\"state\":\"\\uD800\"}")) invalid(raw)
        assertFailsWith<RetirementFailure> { RetirementCodec.decode(PrivateBytes(byteArrayOf(0x7b, 0x22, 0x80.toByte(), 0x22, 0x3a, 0x31, 0x7d))) }
    }

    @Test fun everyPendingFieldIsRequiredAndAdditionalSecretFieldsRejected() {
        val root = json()
        for (key in root.keys) invalid(JsonObject(root - key).toString())
        for (key in listOf("accessToken", "refreshToken", "guestToken", "password", "unknown"))
            invalid(JsonObject(root + (key to JsonPrimitive("private-token"))).toString())
    }

    @Test fun ownerKindAndScopeRemainStrictAndGuestIsNotAccount() {
        val root = json(); val scope = root.getValue("scope").jsonObject
        for (kind in listOf("DEMO", "ACCOUNT\n", "account", "ADMIN")) invalid(withScope(root, scope + ("actorKind" to JsonPrimitive(kind))))
        for (field in listOf("environment", "actorId")) {
            for (value in listOf("", " ", "a\n", "a".repeat(201))) invalid(withScope(root, scope + (field to JsonPrimitive(value))))
            invalid(withScope(root, scope + (field to JsonPrimitive(123))))
        }
        invalid(withScope(root, scope + ("extra" to JsonPrimitive(true))))
        val guest = RetirementCodec.decode(bytes(withScope(root, scope + ("actorKind" to JsonPrimitive("GUEST")))))
        assertEquals(ActorKind.GUEST, assertIs<RetirementState.Pending>(guest).scope.actorKind)
    }

    @Test fun operationAndIncarnationIdsAreExactLowercaseUuids() {
        val root = json()
        for (key in listOf("operationId", "origin", "credentialIncarnation")) {
            for (value in listOf("", ID.uppercase(), "$ID\n", "$ID ", ID.dropLast(1), "private-token"))
                invalid(JsonObject(root + (key to JsonPrimitive(value))).toString())
            invalid(JsonObject(root + (key to JsonNull)).toString())
        }
    }

    @Test fun opaqueTargetIsBoundedExactHexNotAPathOrCredential() {
        val root = json()
        for (value in listOf("", "00", "0".repeat(273), "0".repeat(276), "G".repeat(274), "A".repeat(274), "a".repeat(8193), "/tmp/key", "secret-token"))
            invalid(JsonObject(root + ("dataTarget" to JsonPrimitive(value))).toString())
        invalid(JsonObject(root + ("dataTarget" to JsonNull)).toString())
    }

    @Test fun completedStepSetDoesNotAcceptDuplicatesOrUnknownSteps() {
        val root = json()
        for (done in listOf("null", "{}", "true", "[1]", "[\"credentials\"]", "[\"CREDENTIALS\",\"CREDENTIALS\"]", "[\"REMOTE_LOGOUT\"]"))
            invalid(JsonObject(root + ("done" to Json.parseToJsonElement(done))).toString())
        assertEquals(RetirementStep.entries.toSet(), assertIs<RetirementState.Pending>(RetirementCodec.decode(
            RetirementCodec.encode(pending(RetirementStep.entries.toSet())))).done)
    }

    @Test fun sizeAndNestingRemainBounded() {
        invalid(" ".repeat(32_769) + "{\"version\":1,\"state\":\"idle\"}")
        invalid("[".repeat(9) + "0" + "]".repeat(9))
    }

    @Test fun privateModelViewsAndBytesRemainDetachedAndRedacted() {
        val source = ByteArray(137) { it.toByte() }; val target = StateRetirementTarget(source)
        source.fill(0); val first = target.copyForStorage(); first.fill(0)
        assertNotEquals(first.toList(), target.copyForStorage().toList())
        assertFalse(target.toString().contains(ID)); assertFalse(pending().toString().contains("private-owner"))
        val remaining = mutableSetOf(RetirementStep.CREDENTIALS)
        val failures = mutableMapOf(RetirementStep.CREDENTIALS to FailureReason.NOT_CONFIGURED)
        val progress = LocalRetirementProgress(LocalRetirementPhase.PENDING, ID, remaining, failures)
        remaining.clear(); failures.clear()
        assertEquals(setOf(RetirementStep.CREDENTIALS), progress.remaining)
        assertEquals(FailureReason.NOT_CONFIGURED, progress.failures[RetirementStep.CREDENTIALS])
        assertFalse(progress.toString().contains(ID))
    }

    @Test fun setupDiscardRoundTripsAllSixAnchoredOptionalTargetCombinations() {
        for (origin in listOf(null, ORIGIN)) for (credentials in listOf(null, CREDENTIALS)) {
            if (origin == null && credentials == null) continue
            for (data in listOf(false, true)) {
                val original = discard(origin, credentials, data)
                val decoded = assertIs<RetirementState.SetupDiscardPending>(RetirementCodec.decode(RetirementCodec.encode(original)))
                assertEquals(ID, decoded.operationId)
                assertEquals(original.scope, decoded.scope)
                assertEquals(origin, decoded.origin)
                assertEquals(credentials, decoded.credentialIncarnation)
                assertEquals(original.requiredSteps, decoded.requiredSteps)
                assertEquals(emptySet(), decoded.done)
                if (data) assertContentEquals(original.target!!.copyForStorage(), decoded.target!!.copyForStorage()) else assertNull(decoded.target)
            }
        }
    }

    @Test fun setupDiscardHasDistinctTagAndExplicitNullsWithoutPlaceholderTargets() {
        val root = discardJson(null, CREDENTIALS, false)
        assertEquals("setup-discard-pending", root.getValue("state").jsonPrimitive.content)
        assertEquals(setOf("version", "state", "operationId", "scope", "origin", "credentialIncarnation", "dataTarget", "done"), root.keys)
        assertEquals(JsonNull, root["origin"])
        assertEquals(JsonNull, root["dataTarget"])
        assertEquals(CREDENTIALS, root.getValue("credentialIncarnation").jsonPrimitive.content)
        for (field in root.keys) invalid(JsonObject(root - field).toString())
        invalid(JsonObject(root + ("extra" to JsonPrimitive("private-token"))).toString())
    }

    @Test fun setupDiscardNeverAcceptsDataOnlyOrNoAnchorAndNeverMarksAbsentTargetsDone() {
        for (data in listOf(false, true)) {
            val root = discardJson(ORIGIN, null, data)
            invalid(JsonObject(root + ("origin" to JsonNull)).toString())
            assertFailsWith<RetirementFailure> { discard(null, null, data) }
        }
        val root = discardJson(null, CREDENTIALS, false)
        for (step in listOf("NATIVE_WORK", "PRIVATE_DATA"))
            invalid(JsonObject(root + ("done" to JsonArray(listOf(JsonPrimitive(step))))).toString())
        assertFailsWith<RetirementFailure> { discard(null, CREDENTIALS, false).withDone(RetirementStep.PRIVATE_DATA) }
    }

    @Test fun setupDiscardCheckpointSetDerivesOnlyPresentResourcesAndIsDetached() {
        val source = mutableSetOf(RetirementStep.CREDENTIALS)
        val state = RetirementState.SetupDiscardPending(ID, pending().scope, null, CREDENTIALS, null, source)
        source.clear()
        assertEquals(setOf(RetirementStep.CREDENTIALS), state.done)
        assertEquals(setOf(RetirementStep.CREDENTIALS), state.requiredSteps)
        val all = discard().requiredSteps
        var progressed = discard()
        all.forEach { progressed = progressed.withDone(it) }
        val decoded = assertIs<RetirementState.SetupDiscardPending>(RetirementCodec.decode(RetirementCodec.encode(progressed)))
        assertEquals(all, decoded.done)
        assertEquals(all, decoded.requiredSteps)
    }

    @Test fun setupDiscardIdsScopeAndNullableTargetTypesAreStrict() {
        val root = discardJson()
        for (field in listOf("operationId", "origin", "credentialIncarnation")) {
            for (value in listOf(JsonPrimitive(""), JsonPrimitive(ID.uppercase()), JsonPrimitive("$ID "), JsonPrimitive(1), JsonPrimitive(true)))
                invalid(JsonObject(root + (field to value)).toString())
        }
        invalid(JsonObject(root + ("operationId" to JsonNull)).toString())
        for (value in listOf(JsonPrimitive(""), JsonPrimitive("null"), JsonPrimitive("A".repeat(274)), JsonPrimitive(1)))
            invalid(JsonObject(root + ("dataTarget" to value)).toString())
        val scope = root.getValue("scope").jsonObject
        invalid(withScope(root, scope + ("actorKind" to JsonPrimitive("DEMO"))))
        // Keep the invalid surrogate escaped on the wire; the fixture's UTF-8 encoder must not
        // replace an in-memory surrogate before the strict decoder receives it.
        invalid(root.toString().replace("\"actorId\":\"private-owner\"", "\"actorId\":\"\\uD800\""))
        assertFailsWith<RetirementFailure> {
            RetirementCodec.encode(RetirementState.SetupDiscardPending(ID,
                StorageScope("test", ActorKind.ACCOUNT, "\uD800"), ORIGIN, CREDENTIALS, null, emptySet()))
        }
    }

    @Test fun setupDiscardGuestScopeDoesNotBecomeAnAccount() {
        val root = discardJson()
        val scope = root.getValue("scope").jsonObject
        val decoded = assertIs<RetirementState.SetupDiscardPending>(RetirementCodec.decode(bytes(
            withScope(root, scope + ("actorKind" to JsonPrimitive("GUEST"))))))
        assertEquals(ActorKind.GUEST, decoded.scope.actorKind)
        assertEquals(pending().scope.actorId, decoded.scope.actorId)
    }

    @Test fun setupDiscardRejectsDuplicateOrUnknownCompletionSteps() {
        val root = discardJson()
        for (done in listOf("null", "{}", "[1]", "[\"CREDENTIALS\",\"CREDENTIALS\"]", "[\"REMOTE_LOGOUT\"]"))
            invalid(JsonObject(root + ("done" to Json.parseToJsonElement(done))).toString())
    }

    @Test fun setupDiscardCannotBeParsedAsLegacyLogoutByChangingItsTagWhenTargetsAreAbsent() {
        val root = discardJson(null, CREDENTIALS, false)
        invalid(JsonObject(root + ("state" to JsonPrimitive("pending"))).toString())
        assertIs<RetirementState.Pending>(RetirementCodec.decode(RetirementCodec.encode(pending())))
        assertIs<RetirementState.SetupDiscardPending>(RetirementCodec.decode(RetirementCodec.encode(discard())))
    }

    @Test fun setupDiscardDuplicateKeysAndOversizedPayloadRemainRejected() {
        val raw = RetirementCodec.encode(discard()).copyForCodec().decodeToString()
        invalid(raw.replace("\"state\":", "\"state\":\"idle\",\"state\":"))
        invalid(" ".repeat(32_769) + raw)
    }

    @Test fun setupDiscardViewsAndCompletionDoNotExposeTargetIdentity() {
        val state = discard()
        for (privateValue in listOf(ID, ORIGIN, CREDENTIALS, "private-owner")) assertFalse(state.toString().contains(privateValue))
        val completed = Json.parseToJsonElement(RetirementCodec.encode(RetirementState.Complete(ID)).copyForCodec().decodeToString()).jsonObject
        assertEquals(setOf("version", "state", "operationId"), completed.keys)
    }

    @Test fun credentialCreateHasDistinctStrictOpaquePlanTagAndExplicitConfirmationBoolean() {
        for (requested in listOf(false, true)) {
            val original = RetirementState.PendingCreate(createPlan(), requested)
            val raw = RetirementCodec.encode(original)
            val root = Json.parseToJsonElement(raw.copyForCodec().decodeToString()).jsonObject
            assertEquals(setOf("version", "state", "plan", "abortRequested"), root.keys)
            assertEquals("credential-create-pending", root.getValue("state").jsonPrimitive.content)
            val decoded = assertIs<RetirementState.PendingCreate>(RetirementCodec.decode(raw))
            assertEquals(requested, decoded.abortRequested)
            assertContentEquals(original.plan.copyForStorage().copyForCodec(), decoded.plan.copyForStorage().copyForCodec())
            for (key in root.keys) invalid(JsonObject(root - key).toString())
            invalid(JsonObject(root + ("scope" to json().getValue("scope"))).toString())
            for (bad in listOf("null", "0", "1", "\"false\"", "[]", "{}"))
                invalid(JsonObject(root + ("abortRequested" to Json.parseToJsonElement(bad))).toString())
        }
    }

    @Test fun credentialCreateRejectsMalformedNoncanonicalAndUnboundedEmbeddedPlans() {
        val root = Json.parseToJsonElement(RetirementCodec.encode(RetirementState.PendingCreate(createPlan(), false)).copyForCodec().decodeToString()).jsonObject
        for (bad in listOf("", "00", "a", "A".repeat(800), "0".repeat(8194), "/tmp/credentials", "private-token"))
            invalid(JsonObject(root + ("plan" to JsonPrimitive(bad))).toString())
        val raw = createPlan().copyForStorage().copyForCodec().decodeToString()
        for (bad in listOf(" $raw", raw.replace("credential-create", "credential-refresh"), raw.replace("\"expectedSlotRevision\":1", "\"expectedSlotRevision\":0"))) {
            val hex = bad.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            invalid(JsonObject(root + ("plan" to JsonPrimitive(hex))).toString())
        }
    }

    @Test fun credentialCreateCannotMasqueradeAsCleanupOrCompletedStateAndViewsAreRedacted() {
        val state = RetirementState.PendingCreate(createPlan(), false)
        val root = Json.parseToJsonElement(RetirementCodec.encode(state).copyForCodec().decodeToString()).jsonObject
        for (tag in listOf("pending", "setup-discard-pending", "complete", "idle"))
            invalid(JsonObject(root + ("state" to JsonPrimitive(tag))).toString())
        for (privateValue in listOf(ID, "a".repeat(64), "b".repeat(64), "c".repeat(64))) assertFalse(state.toString().contains(privateValue))
    }

    @Test fun accessGateAllowsOnlyIdleAndCompletedAndBlocksEveryPendingKind() {
        assertFalse(RetirementState.Idle.blocksAccess())
        assertFalse(RetirementState.Complete(ID).blocksAccess())
        assertTrue(pending().blocksAccess())
        assertTrue(discard().blocksAccess())
        assertTrue(RetirementState.PendingCreate(createPlan(), false).blocksAccess())
        assertTrue(RetirementState.PendingCreate(createPlan(), true).blocksAccess())
    }

    companion object {
        const val ID = "123e4567-e89b-12d3-a456-426614174000"
        const val ORIGIN = "123e4567-e89b-12d3-a456-426614174001"
        const val CREDENTIALS = "123e4567-e89b-12d3-a456-426614174002"
        private fun pending(done: Set<RetirementStep> = emptySet()) = RetirementState.Pending(ID,
            StorageScope("test", ActorKind.ACCOUNT, "private-owner"), ORIGIN, CREDENTIALS, StateRetirementTarget(ByteArray(137) { it.toByte() }), done)
        private fun discard(origin: String? = ORIGIN, credentials: String? = CREDENTIALS, data: Boolean = true) =
            RetirementState.SetupDiscardPending(ID, pending().scope, origin, credentials, if (data) pending().target else null, emptySet())
        private fun discardJson(origin: String? = ORIGIN, credentials: String? = CREDENTIALS, data: Boolean = true) =
            Json.parseToJsonElement(RetirementCodec.encode(discard(origin, credentials, data)).copyForCodec().decodeToString()).jsonObject
        private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
        private fun createPlan() = CredentialCreatePlan.create(CredentialCreatePlanRecord(1, ID, "a".repeat(64), "b".repeat(64), "c".repeat(64)))
        private fun json() = Json.parseToJsonElement(RetirementCodec.encode(pending()).copyForCodec().decodeToString()).jsonObject
        private fun withScope(root: JsonObject, scope: Map<String, JsonElement>) = JsonObject(root + ("scope" to JsonObject(scope))).toString()
        private fun invalid(raw: String) { assertFailsWith<RetirementFailure> { RetirementCodec.decode(bytes(raw)) } }
    }
}
