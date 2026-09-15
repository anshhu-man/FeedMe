package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.PgTransactions
import com.feedme.server.db.StoredReply
import com.feedme.server.social.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import java.lang.reflect.Proxy
import java.net.URI
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class SocialHttpInputTest {
    @Test fun exactCanonicalOperationMatrixHasFourteenRoutesAndTenDurableCommands() {
        val document = SocialHttpInputTest::class.java.getResourceAsStream("/feedme-openapi.json")!!.use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
        val canonical = document.getValue("paths").jsonObject.values.flatMap {
            it.jsonObject.filterKeys { method -> method in setOf("get", "post", "patch", "put", "delete", "head", "options", "trace") }.values
        }
            .map { it.jsonObject }.filter { it["x-module"]?.jsonPrimitive?.content == "circles" }
        assertEquals(14, canonical.size); assertEquals(canonical.map { it.getValue("operationId").jsonPrimitive.content }.toSet(), socialHttpOperations)
        for (operation in socialHttpOperations) {
            val input = input(operation)
            assertEquals(operation in bodies, input.hasBody)
            assertEquals(operation !in reads, input.key != null)
            assertEquals(operation in versions, input.ifMatch != null)
            val schema = canonical.single { it.getValue("operationId").jsonPrimitive.content == operation }
            assertEquals(operation !in reads, schema.getValue("x-idempotency-required").jsonPrimitive.boolean)
        }
    }

    @Test fun secretsIdentifiersAndCapabilityNeverAppearInMetadataDiagnostics() {
        val account = input(); val preview = input("previewInvitation")
        assertEquals("synthetic-secret", account.bearer!!.token.use { it })
        assertEquals(ID, account.bearer.deviceSessionId.toString())
        for (value in listOf(account, account.bearer, preview, preview.invitationToken)) {
            for (secret in listOf("synthetic-secret", TOKEN, ID)) assertFalse(value.toString().contains(secret))
        }
    }

    @Test fun mandatoryAccountBearerAndDeviceAreNeverInferredFromHeaderPresence() {
        for (name in listOf("Authorization", "X-Device-Session"))
            denied(401) { input(headers = defaults().filterNot { it.first == name }) }
        for (token in listOf("Basic secret", "Bearer", "Bearer one two", "Bearer =bad", "Bearer " + "x".repeat(16385)))
            denied(401) { input(headers = defaults().filterNot { it.first == "Authorization" } + ("Authorization" to token)) }
    }

    @Test fun duplicateSecurityPreconditionAndFramingHeadersAreAlwaysRejected() {
        for (name in listOf("Authorization", "X-Device-Session", "Idempotency-Key", "If-Match", "If-None-Match", "Content-Type", "Content-Length", "Transfer-Encoding", "Content-Encoding")) {
            val original = defaults("updateCircle").toMutableList()
            val value = original.firstOrNull { it.first == name }?.second ?: when (name) {
                "Content-Length" -> "20"; "Transfer-Encoding" -> "chunked"; "Content-Encoding" -> "identity"; else -> "\"1\""
            }
            original.removeAll { it.first == name }; original += name to value; original += name.lowercase() to value
            denied(400) { input("updateCircle", headers = original) }
        }
        for (operation in listOf("createCircle", "previewInvitation")) {
            val folded = defaults(operation).filterNot { it.first == "Authorization" } +
                ("Authorization" to "Bearer synthetic-secret, Bearer synthetic-secret")
            denied(400) { input(operation, headers = folded) }
        }
    }

    @Test fun publicPreviewIgnoresWellFormedAccountGuestAndDeviceHeadersWithoutPrincipal() {
        for (token in listOf("synthetic-account", "synthetic-guest", "synthetic-invitation")) {
            val parsed = input("previewInvitation", extra = listOf("Authorization" to "Bearer $token", "X-Device-Session" to ID))
            assertNull(parsed.bearer); assertNull(parsed.key); assertEquals(TOKEN, parsed.invitationToken!!.use { it })
        }
        assertNull(input("previewInvitation", extra = listOf("X-Device-Session" to ID)).bearer)
    }

    @Test fun publicPreviewRejectsMalformedOrDuplicateControlsWithoutUsingThem() {
        for (extra in listOf(listOf("Authorization" to "Basic secret"), listOf("X-Device-Session" to "1-1-1-1-1"),
            listOf("Authorization" to "Bearer one", "authorization" to "Bearer two"),
            listOf("X-Device-Session" to ID, "x-device-session" to ID)))
            denied(400) { input("previewInvitation", extra = extra) }
    }

    @Test fun sixVersionedMutationsRequireExactQuotedIntegerPrecondition() {
        for (operation in versions) {
            denied(428) { input(operation, headers = defaults(operation).filterNot { it.first == "If-Match" }) }
            for (version in listOf("*", "W/\"1\"", "1", "\"-1\"", "\"1\", \"2\"", "\"" + "1".repeat(65) + "\""))
                denied(400) { input(operation, headers = defaults(operation).filterNot { it.first == "If-Match" } + ("If-Match" to version)) }
            assertEquals("\"0001\"", input(operation, headers = defaults(operation).filterNot { it.first == "If-Match" } + ("If-Match" to "\"0001\"")).ifMatch)
        }
    }

    @Test fun readsAndUnversionedCommandsNeverHonorUndeclaredConditionals() {
        for (operation in socialHttpOperations) denied(400) { input(operation, extra = listOf("If-None-Match" to "\"1\"")) }
        for (operation in socialHttpOperations - versions) denied(400) { input(operation, extra = listOf("If-Match" to "\"1\"")) }
        for (operation in reads) denied(400) { input(operation, extra = listOf("Idempotency-Key" to ID)) }
    }

    @Test fun uuidPathsAndCommandDeviceIdsRejectShortFormsOrEncodedSegments() {
        for (bad in listOf("1-1-1-1-1", "$ID/child", "$ID%2fchild", " $ID", "$ID ")) {
            denied(400) { input("getCircle", paths = parametersOf("circleId", bad)) }
            denied(400) { input(headers = defaults().filterNot { it.first == "Idempotency-Key" } + ("Idempotency-Key" to bad)) }
            denied(400) { input(headers = defaults().filterNot { it.first == "X-Device-Session" } + ("X-Device-Session" to bad)) }
        }
        assertEquals(UUID.fromString(ID), input("getCircle", paths = parametersOf("circleId", ID.uppercase())).circleId)
        denied(400) { input("getCircle", paths = parametersOf("circleId", listOf(ID, ID))) }
    }

    @Test fun paginationQueriesAreUniqueOperationSpecificAndBounded() {
        for (operation in listOf("listCircles", "listCircleMembers")) {
            assertEquals(20, input(operation).limit); assertEquals(50, input(operation, query = listOf("limit" to "50")).limit)
            for (bad in listOf("0", "51", "01", "+1", "1e0", "1.0", " 1"))
                denied(400) { input(operation, query = listOf("limit" to bad)) }
            denied(400) { input(operation, query = listOf("limit" to "1", "limit" to "1")) }
            denied(400) { input(operation, query = listOf("cursor" to "one", "cursor" to "two")) }
            denied(400) { input(operation, query = listOf("cursor" to "a".repeat(2049))) }
        }
        denied(400) { input("getCircle", query = listOf("limit" to "1")) }
        denied(400) { input("listCircles", query = listOf("userId" to ID)) }
    }

    @Test fun previewRequiresOneBoundedQueryCapabilityAndNoHiddenTargetParameter() {
        for (query in listOf(emptyList(), listOf("token" to " ".repeat(32)), listOf("token" to "x".repeat(31)), listOf("token" to "x".repeat(513)),
            listOf("token" to TOKEN, "token" to TOKEN), listOf("token" to TOKEN, "targetId" to ID)))
            denied(400) { input("previewInvitation", query = query) }
        assertEquals("x".repeat(512), input("previewInvitation", query = listOf("token" to "x".repeat(512))).invitationToken!!.use { it })
    }

    @Test fun bodylessReadAndDeleteNeverConsumeUnexpectedJson() = runBlocking<Unit> {
        for (operation in socialHttpOperations - bodies) {
            assertNull(input(operation).readBody(ByteReadChannel(ByteArray(0)), validator))
            deniedSuspend(400) { input(operation).readBody(ByteReadChannel(byteArrayOf(32)), validator) }
            denied(400) { input(operation, extra = listOf("Content-Length" to "1")) }
            denied(400) { input(operation, extra = listOf("Content-Type" to "application/json")) }
            denied(400) { input(operation, extra = listOf("Transfer-Encoding" to "chunked")) }
        }
    }

    @Test fun framingRejectsAmbiguousOverflowNegativeAndOversizeLengths() {
        for (length in listOf("-1", "+1", "1,1", "65537", "18446744073709551615", " 1"))
            denied(400) { input(extra = listOf("Content-Length" to length)) }
        denied(400) { input(extra = listOf("Content-Length" to "1", "Transfer-Encoding" to "chunked")) }
        denied(400) { input(extra = listOf("Transfer-Encoding" to "gzip,chunked")) }
        assertEquals(65536L, input(extra = listOf("Content-Length" to "65536")).contentLength)
    }

    @Test fun mediaCharsetAndContentEncodingAreNotGuessed() {
        for (encoding in listOf("gzip", "br", "identity,identity")) denied(400) { input(extra = listOf("Content-Encoding" to encoding)) }
        for (media in listOf("text/json", "application/problem+json", "application/json; charset=utf-16", "application/json; charset=utf-8; charset=utf-8"))
            denied(400) { input(headers = defaults().filterNot { it.first == "Content-Type" } + ("Content-Type" to media)) }
        assertNotNull(input(headers = defaults().filterNot { it.first == "Content-Type" } + ("Content-Type" to "Application/JSON; charset=\"UTF-8\"")))
    }

    @Test fun malformedDuplicateAndInvalidUnicodeAreRejectedBeforeSchemaProjection() {
        for (body in listOf("{", """{"name":"one","name":"two"}""", """{"name":"\uD800"}""", """{"name":"ok"} trailing"""))
            denied(400) { input().body(body.encodeToByteArray(), validator) }
        denied(400) { input().body(byteArrayOf(0xc3.toByte(), 0x28), validator) }
    }

    @Test fun schemaInvalidRolesOwnershipFieldsAndNonemptyLeaveAreTyped() {
        for ((operation, body) in listOf("createCircle" to """{"name":"","ownerId":"$ID"}""",
            "updateCircleMember" to """{"role":"owner"}""", "transferCircleOwnership" to """{"newOwnerUserId":"$ID"}""",
            "leaveCircle" to """{"confirmed":true}"""))
            denied(422) { input(operation).body(body.encodeToByteArray(), validator) }
        assertEquals(JsonObject(emptyMap()), input("leaveCircle").body("{}".encodeToByteArray(), validator))
    }

    @Test fun exactJsonIntegerLexemeReachesDurableProjectionWithoutDoubleRounding() {
        val parsed = input("createInvitation").body("""{"targetType":"circle","targetId":"$ID","expiresInHours":1.0000000000000000000}""".encodeToByteArray(), validator)!!
        assertEquals("1.0000000000000000000", parsed.getValue("expiresInHours").jsonPrimitive.content)
        denied(422) { input("createInvitation").body("""{"targetType":"circle","targetId":"$ID","expiresInHours":1.0000000000000000001}""".encodeToByteArray(), validator) }
    }

    @Test fun streamingLimitAndLengthMatchUseActualReceivedBytes() = runBlocking<Unit> {
        deniedSuspend(400) { input().readBody(ByteReadChannel(ByteArray(65537) { 32 }), validator) }
        deniedSuspend(400) { input().readBody(ByteReadChannel(ByteArray(0)), validator) }
        deniedSuspend(400) { input(extra = listOf("Content-Length" to "1")).readBody(ByteReadChannel(BODY.encodeToByteArray()), validator) }
        val exact = BODY + " ".repeat(65536 - BODY.encodeToByteArray().size)
        assertNotNull(input(extra = listOf("Content-Length" to "65536")).readBody(ByteReadChannel(exact.encodeToByteArray()), validator))
    }

    @Test fun cancellationBeforeBodyOrAfterVerifierNeverPublishesInputOrPrincipal() = runBlocking<Unit> {
        val task = async { currentCoroutineContext().cancel(); input().readBody(ByteReadChannel(BODY.encodeToByteArray()), validator) }
        assertFailsWith<CancellationException> { task.await() }
        val config = configuration(SocialHttpVerifier { currentCoroutineContext().cancel(); PortResult.Value(actor) })
        val verification = async { config.authenticate(input()) }
        assertFailsWith<CancellationException> { verification.await() }
    }

    @Test fun mandatoryVerifierEnvironmentAndDeviceBindingRejectForeignResults() = runBlocking<Unit> {
        for (principal in listOf(VerifiedSocialAccount("other", actor.accountId, actor.deviceSessionId),
            VerifiedSocialAccount("test", actor.accountId, UUID.randomUUID())))
            deniedSuspend(401) { configuration(SocialHttpVerifier { PortResult.Value(principal) }).authenticate(input()) }
        assertSame(actor, configuration(SocialHttpVerifier { PortResult.Value(actor) }).authenticate(input()))
    }

    @Test fun verifierFailuresAreTypedAndSanitizedButCancellationPropagates() = runBlocking<Unit> {
        for ((reason, status) in listOf(FailureReason.UNAUTHENTICATED to 401, FailureReason.STALE_SESSION to 401,
            FailureReason.FORBIDDEN to 403, FailureReason.RATE_LIMITED to 429, FailureReason.STORAGE_FAILURE to 503)) {
            val error = assertFailsWith<SocialHttpFailure> { configuration(SocialHttpVerifier { PortResult.Failure(reason) }).authenticate(input()) }
            assertEquals(status, error.status); assertFalse(error.toString().contains("synthetic-secret"))
        }
        deniedSuspend(503) { configuration(SocialHttpVerifier { error("PRIVATE-VERIFIER-CANARY") }).authenticate(input()) }
        assertFailsWith<CancellationException> { configuration(SocialHttpVerifier { throw CancellationException("test") }).authenticate(input()) }
    }

    @Test fun responseValidationRequiresExactStatusSchemaVersionAndAbsent204Body() {
        assertNull(validateSocialReply("deleteCircle", StoredReply(204, null, null), validator))
        assertFails { validateSocialReply("deleteCircle", StoredReply(204, JsonObject(emptyMap()), null), validator) }
        assertFails { validateSocialReply("leaveCircle", StoredReply(200, JsonObject(emptyMap()), "\"1\""), validator) }
        assertFails { validateSocialReply("createCircle", StoredReply(201, JsonObject(emptyMap()), "\"1\""), validator) }
        assertFails { validateSocialReply("previewInvitation", StoredReply(200, buildJsonObject { put("status", "unavailable") }, "\"1\""), validator) }
    }

    companion object {
        private const val ID = "aaaaaaaa-0000-4000-8000-000000000001"
        private const val TOKEN = "synthetic-invite-capability-" + "12345678901234567890"
        private const val BODY = """{"name":"Synthetic circle"}"""
        private val actor = VerifiedSocialAccount("test", UUID.randomUUID(), UUID.fromString(ID))
        private val validator = ContractBodyValidator.bundled()
        private val bodies = setOf("createCircle", "updateCircle", "updateCircleMember", "leaveCircle", "transferCircleOwnership", "createInvitation", "acceptInvitation")
        private val reads = setOf("listCircles", "getCircle", "listCircleMembers", "previewInvitation")
        private val versions = setOf("updateCircle", "deleteCircle", "updateCircleMember", "removeCircleMember", "transferCircleOwnership", "revokeInvitation")
        private fun defaults(op: String = "createCircle") = buildList {
            if (op != "previewInvitation") { add("Authorization" to "Bearer synthetic-secret"); add("X-Device-Session" to ID) }
            if (op !in reads) add("Idempotency-Key" to ID)
            if (op in bodies) add("Content-Type" to "application/json")
            if (op in versions) add("If-Match" to "\"1\"")
        }
        private fun input(op: String = "createCircle", headers: List<Pair<String,String>> = defaults(op),
            extra: List<Pair<String,String>> = emptyList(), query: List<Pair<String,String>> = if (op == "previewInvitation") listOf("token" to TOKEN) else emptyList(),
            paths: Parameters = Parameters.build { for (key in listOf("circleId", "userId", "invitationId")) append(key, ID) }) =
            SocialHttpInput.parse(op, Headers.build { (headers + extra).forEach { (k,v) -> append(k,v) } },
                Parameters.build { query.forEach { (k,v) -> append(k,v) } }, paths)
        private fun configuration(verifier: SocialHttpVerifier): SocialHttpConfiguration {
            val source = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, _, _ -> error("Unexpected database I/O") } as DataSource
            val policy = object : SocialIdentityPolicy {
                override fun lockPrincipal(connection: Connection, principal: VerifiedSocialAccount): Unit = error("Unexpected authority")
                override fun requireCreationEnabled(connection: Connection, principal: VerifiedSocialAccount, invitations: Boolean): Unit = error("Unexpected authority")
                override fun lockUnblockedPair(connection: Connection, environment: String, first: UUID, second: UUID): Unit = error("Unexpected authority")
                override fun readProfile(connection: Connection, environment: String, accountId: UUID): SocialProfileSummary = error("Unexpected authority")
            }
            return SocialHttpConfiguration("test", CirclesStore("test", PgTransactions(source), policy,
                CircleCapabilities("test", mapOf("test" to ByteArray(32) { 7 }), URI("https://example.invalid/invite")), CircleLaunchPolicy(50,168)), verifier, Dispatchers.IO)
        }
        private fun denied(status: Int, block: () -> Unit) { assertEquals(status, assertFailsWith<SocialHttpFailure>(block = block).status) }
        private suspend fun deniedSuspend(status: Int, block: suspend () -> Unit) {
            val error = try { block(); fail("Expected denial") } catch (error: SocialHttpFailure) { error }
            assertEquals(status, error.status)
        }
    }
}
