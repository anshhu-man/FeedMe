package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandResult
import com.feedme.server.db.StoredReply
import com.feedme.server.social.VerifiedSocialAccount
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Wire/auth/receipt boundaries only; none of these tests pretends to publish to a database. */
class PostPublicationHttpInputTest {
    @Test fun exactCanonicalOperationKeepsOptionalDraftPairAndHasNoIfMatch() {
        val api = javaClass.getResourceAsStream("/feedme-openapi.json")!!.use {
            Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
        }
        val operation = api["paths"]!!.jsonObject["/v1/posts"]!!.jsonObject["post"]!!.jsonObject
        assertEquals("publishPost", operation["operationId"]!!.jsonPrimitive.content)
        assertEquals("user", operation["x-principal"]!!.jsonPrimitive.content)
        assertTrue(operation["x-idempotency-required"]!!.jsonPrimitive.boolean)
        assertFalse(operation["parameters"]!!.toString().contains("If-Match"))
        val schema = api["components"]!!.jsonObject["schemas"]!!.jsonObject["PostWrite"]!!.jsonObject
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(setOf("caption", "mediaIds", "audience", "keepOnPlate", "allowRecipeSaves", "saveDisclosureVersion", "clientDraftId"), required.toSet())
        assertFalse("draftId" in required || "draftVersion" in required)
        assertEquals(UUID.fromString(ID), input().key)
    }

    @Test fun accountBearerAndRegisteredDeviceAreRequiredWithoutGuestFallback() {
        for (name in listOf("Authorization", "X-Device-Session")) denied(401) { input(headers = defaults().filterNot { it.first == name }) }
        for (token in listOf("Basic secret", "Bearer", "Bearer ", "Bearer a b", "Bearer =bad", "Bearer a=b", "Bearer " + "a".repeat(16385)))
            denied(401) { input(headers = replace("Authorization", token)) }
        denied(400) { input(headers = replace("Authorization", "Bearer a,Bearer b")) }
        assertEquals("abc+/==", input(headers = replace("Authorization", "bEaReR  abc+/==")).bearer.token.use { it })
    }

    @Test fun commandKeyIsMandatoryAndWholeUuidSpellingsAreRequired() {
        denied(400) { input(headers = defaults().filterNot { it.first == "Idempotency-Key" }) }
        for (name in listOf("X-Device-Session", "Idempotency-Key")) {
            for (bad in listOf("1-1-1-1-1", "guest", " $ID", "$ID ", "$ID/child", "$ID,$ID"))
                denied(400) { input(headers = replace(name, bad)) }
            assertNotNull(input(headers = replace(name, ID.uppercase())))
        }
    }

    @Test fun duplicateSecurityAndFramingHeadersFailCaseInsensitively() {
        for (name in listOf("Authorization", "X-Device-Session", "Idempotency-Key", "If-Match", "If-None-Match",
            "Content-Type", "Content-Length", "Transfer-Encoding", "Content-Encoding")) {
            val value = defaults().firstOrNull { it.first == name }?.second ?: "0"
            denied(400) { input(headers = defaults().filterNot { it.first == name } + listOf(name to value, name.lowercase() to value)) }
        }
        // Ktor refuses this value while constructing Headers, before our parser can receive it.
        assertFailsWith<IllegalHeaderValueException> { input(headers = replace("Authorization", "Bearer hidden\nvalue")) }
    }

    @Test fun neitherConditionalHeadersNorQueryAndPathIdentityOverridesAreAccepted() {
        for (name in listOf("If-Match", "If-None-Match")) for (value in listOf("\"1\"", "*", ""))
            denied(400) { input(extra = listOf(name to value)) }
        for (name in listOf("ownerId", "draftId", "draftVersion", "clientDraftId", "limit")) {
            val params = parametersOf(name, ID)
            denied(400) { input(query = params) }
            denied(400) { input(paths = params) }
            denied(400) { input(query = params, paths = params) }
        }
    }

    @Test fun framingAndJsonMediaTypeAreStrictAndBounded() {
        for (length in listOf("-1", "+1", "65537", "18446744073709551615", "1,1", " 1", "1.0"))
            denied(400) { input(extra = listOf("Content-Length" to length)) }
        denied(400) { input(extra = listOf("Content-Length" to "1", "Transfer-Encoding" to "chunked")) }
        for (value in listOf("gzip", "identity", "chunked,chunked", "chunked "))
            denied(400) { input(extra = listOf("Transfer-Encoding" to value)) }
        for (value in listOf("gzip", "br", "identity,identity")) denied(400) { input(extra = listOf("Content-Encoding" to value)) }
        assertNotNull(input(extra = listOf("Transfer-Encoding" to "CHUNKED", "Content-Encoding" to "IDENTITY")))
        denied(400) { input(headers = defaults().filterNot { it.first == "Content-Type" }) }
        for (value in listOf("text/plain", "multipart/form-data", "application/json;charset=utf-16", "application/json;charset=utf-8;charset=utf-8"))
            denied(400) { input(headers = replace("Content-Type", value)) }
        for (value in listOf("application/json", "Application/JSON; charset=\"UTF-8\"", "application/json;charset=utf-8"))
            assertEquals(value, input(headers = replace("Content-Type", value)).mediaType)
    }

    @Test fun duplicateJsonFieldsMalformedUtf8DepthAndTrailingTextCannotNormalizeIntoACommand() {
        for (bad in listOf("", "{", "{} trailing", request.dropLast(1) + ",\"caption\":\"second\"}",
            "{\"caption\":\"\\uD800\"}", "[".repeat(33) + "0" + "]".repeat(33)))
            denied(400) { input().body(bad.encodeToByteArray(), validator) }
        for (bad in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())))
            denied(400) { input().body(bad, validator) }
        denied(400) { input().body(ByteArray(65537) { 32 }, validator) }
    }

    @Test fun fullPostWriteIsRequiredAndOwnerOrServerStateCannotBeInjected() {
        val parsed = input().body(request.encodeToByteArray(), validator)
        assertEquals("Crème 🥗", parsed["caption"]!!.jsonPrimitive.content)
        for (required in listOf("caption", "mediaIds", "audience", "keepOnPlate", "allowRecipeSaves", "saveDisclosureVersion", "clientDraftId"))
            denied(422) { input().body(JsonObject(parsed - required).toString().encodeToByteArray(), validator) }
        for (name in listOf("ownerId", "status", "publishedPostId", "version"))
            denied(422) { input().body(JsonObject(parsed + (name to JsonPrimitive(ID))).toString().encodeToByteArray(), validator) }
        denied(422) { input().body(JsonObject(parsed + ("caption" to JsonPrimitive("x".repeat(501)))).toString().encodeToByteArray(), validator) }
        denied(422) { input().body(JsonObject(parsed + ("keepOnPlate" to JsonPrimitive("false"))).toString().encodeToByteArray(), validator) }
    }

    @Test fun directSavedAndSchemaValidHalfPairPreserveExactInputForSemanticValidation() {
        val direct = input().body(request.encodeToByteArray(), validator)
        assertFalse("draftId" in direct || "draftVersion" in direct)
        for (fields in listOf("\"draftId\":\"$ID\"", "\"draftVersion\":9007199254740993",
            "\"draftId\":\"$ID\",\"draftVersion\":9007199254740993")) {
            val body = request.dropLast(1) + ",$fields}"
            assertEquals(Json.parseToJsonElement(body), input().body(body.encodeToByteArray(), validator))
        }
        for (version in listOf("0", "-1", "1.5", "\"1\""))
            denied(422) { input().body((request.dropLast(1) + ",\"draftVersion\":$version}").encodeToByteArray(), validator) }
    }

    @Test fun absentOptionalFieldsStayAbsentAndClientAudienceBindingsRemainUntrustedData() {
        val original = request.dropLast(1) + ",\"altText\":\"\",\"sourcePostId\":\"$ID\"}"
        assertEquals(Json.parseToJsonElement(original), input().body(original.encodeToByteArray(), validator))
        val direct = input().body(request.encodeToByteArray(), validator)
        assertFalse("altText" in direct || "attachment" in direct || "sourcePostId" in direct)
        val body = JsonObject(direct + ("audience" to buildJsonObject {
            put("kind", "circles"); put("circleIds", buildJsonArray { add(ID) })
            put("bindings", buildJsonArray { add(buildJsonObject { put("circleId", ID); put("authorMembershipGeneration", 999) }) })
        }))
        // Parsing is not authority. The same-transaction store must replace these client claims.
        assertEquals(body, input().body(body.toString().encodeToByteArray(), validator))
    }

    @Test fun streamingChecksExactBytesAndDeclaredLengthIncludingUtf8AndFullCap() = runBlocking<Unit> {
        deniedSuspend(400) { input().readBody(ByteReadChannel(byteArrayOf()), validator) }
        deniedSuspend(400) { input().readBody(ByteReadChannel(ByteArray(65537) { 32 }), validator) }
        deniedSuspend(400) { input(extra = listOf("Content-Length" to "1")).readBody(ByteReadChannel(request.encodeToByteArray()), validator) }
        deniedSuspend(400) { input(extra = listOf("Content-Length" to request.length.toString())).readBody(ByteReadChannel(request.encodeToByteArray()), validator) }
        val padded = request + " ".repeat(65536 - request.encodeToByteArray().size)
        assertEquals(input().body(request.encodeToByteArray(), validator), input(extra = listOf("Content-Length" to "65536"))
            .readBody(ByteReadChannel(padded.encodeToByteArray()), validator))
    }

    @Test fun cancelledOrBrokenBodyReadCannotProduceAValidatedRequest() = runBlocking<Unit> {
        val channel = ByteChannel(autoFlush = true)
        var returned = false
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { input().readBody(channel, validator); returned = true }
        assertTrue(pending.isActive); pending.cancelAndJoin(); channel.cancel(CancellationException("fixture closed"))
        assertFalse(returned)
        val broken = ByteChannel(autoFlush = true); broken.cancel(IOException("PRIVATE-ERROR"))
        val failure = deniedSuspend(400) { input().readBody(broken, validator) }
        assertFalse(failure.toString().contains("PRIVATE-ERROR")); assertNull(failure.cause)
    }

    @Test fun exactOriginal201RequiresCanonicalPostVersionAndOwner() {
        assertEquals(post.toString(), validatePostPublicationReply(StoredReply(201, post, "\"1\""), validator, 65536, uuid))
        for (reply in listOf(StoredReply(200, post, "\"1\""), StoredReply(201, post), StoredReply(201, post, "\"2\""),
            StoredReply(201, JsonObject(post - "author"), "\"1\"")))
            assertFailsWith<IllegalStateException> { validatePostPublicationReply(reply, validator, 65536, uuid) }
        assertFailsWith<IllegalStateException> { validatePostPublicationReply(StoredReply(201, post, "\"1\""), validator, 1, uuid) }
        assertFailsWith<IllegalStateException> { validatePostPublicationReply(StoredReply(201, post, "\"1\""), validator, 65536, UUID.randomUUID()) }
    }

    @Test fun publicationReceiptCannotSubstituteLaterPostStatusOrExtendTodayLifetime() {
        for ((name, value) in listOf("status" to "hidden", "status" to "deleted", "updatedAt" to "2026-09-14T01:00:00Z",
            "createdAt" to "2026-09-13T00:00:00Z", "expiresAt" to "2026-09-15T00:00:01Z", "expiresAt" to "2026-09-14T23:59:59Z")) {
            val body = JsonObject(post + (name to JsonPrimitive(value)))
            assertFailsWith<IllegalStateException> { validatePostPublicationReply(StoredReply(201, body, "\"1\""), validator, 65536, uuid) }
        }
        for (retained in listOf(true, false)) {
            val body = JsonObject(post + ("keepOnPlate" to JsonPrimitive(retained)))
            assertEquals(body.toString(), validatePostPublicationReply(StoredReply(201, body, "\"1\""), validator, 65536, uuid))
        }
    }

    @Test fun receiptVersionNeverRoundsLargeIntegersOrUsesWeakTags() {
        val body = JsonObject(post + ("version" to Json.parseToJsonElement("9007199254740993")))
        assertEquals(body.toString(), validatePostPublicationReply(StoredReply(201, body, "\"9007199254740993\""), validator, 65536, uuid))
        for (etag in listOf("\"9007199254740992\"", "1", "W/\"1\"", "*", "\"1.0\"", "\"1e0\"", "\"-1\"", "\"1\",\"1\""))
            assertFailsWith<IllegalStateException> { validatePostPublicationReply(StoredReply(201, body, etag), validator, 65536, uuid) }
    }

    @Test fun originalCommandResultMappingNeverTurnsAnUncertainOrExpiredReceiptIntoSuccess() {
        val original = StoredReply(201, post, "\"1\"")
        assertSame(original, publicationReply(CommandResult.Applied(original)))
        assertSame(original, publicationReply(CommandResult.Replayed(original)))
        for ((result, status, code) in listOf(Triple(CommandResult.Mismatch, 409, "IDEMPOTENCY_MISMATCH"),
            Triple(CommandResult.ReceiptExpired, 410, "IDEMPOTENCY_EXPIRED"), Triple(CommandResult.IncompleteReceipt, 409, "COMMAND_INCOMPLETE")))
            assertEquals(code, denied(status) { publicationReply(result) }.code)
    }

    @Test fun verifierMustReturnExactEnvironmentAndRegisteredDevice() = runBlocking<Unit> {
        val actor = VerifiedSocialAccount("test", uuid, uuid)
        assertSame(actor, authenticatePostPublication(input(), "test", SocialHttpVerifier { PortResult.Value(actor) }))
        for (wrong in listOf(VerifiedSocialAccount("other", uuid, uuid), VerifiedSocialAccount("test", uuid, UUID.randomUUID())))
            deniedSuspend(401) { authenticatePostPublication(input(), "test", SocialHttpVerifier { PortResult.Value(wrong) }) }
    }

    @Test fun authFailuresAreFiniteAndProviderSecretsNeverBecomeDiagnostics() = runBlocking<Unit> {
        for ((reason, status) in listOf(FailureReason.UNAUTHENTICATED to 401, FailureReason.STALE_SESSION to 401,
            FailureReason.INVALID_DATA to 401, FailureReason.NOT_FOUND to 401, FailureReason.FORBIDDEN to 403,
            FailureReason.RATE_LIMITED to 429, FailureReason.UNAVAILABLE to 503, FailureReason.OUTCOME_UNKNOWN to 503)) {
            val failure = deniedSuspend(status) { authenticatePostPublication(input(), "test", SocialHttpVerifier { PortResult.Failure(reason, 7) }) }
            assertEquals(if (status in setOf(429, 503)) 7L else null, failure.retryAfterSeconds)
        }
        val failure = deniedSuspend(503) { authenticatePostPublication(input(), "test", SocialHttpVerifier { error("PRIVATE-TOKEN-CAPTION") }) }
        assertNull(failure.cause); assertFalse(failure.toString().contains("PRIVATE-TOKEN-CAPTION"))
    }

    @Test fun authCancellationCannotReturnAPrincipalEvenWhenProviderSuppressesCancellation() = runBlocking<Unit> {
        assertFailsWith<CancellationException> { authenticatePostPublication(input(), "test", SocialHttpVerifier { throw CancellationException("fixture") }) }
        val job = Job()
        assertFailsWith<CancellationException> { withContext(job) {
            authenticatePostPublication(input(), "test", SocialHttpVerifier {
                job.cancel(); withContext(NonCancellable) { PortResult.Value(VerifiedSocialAccount("test", uuid, uuid)) }
            })
        } }
    }

    @Test fun defaultApplicationDoesNotEnablePublishingOrPostReading() = testApplication {
        application { feedMeLocalService(LocalServerConfig.fromEnvironment(emptyMap())) }
        for ((method, path, operation) in listOf(Triple(HttpMethod.Post, "/v1/posts", "publishPost"),
            Triple(HttpMethod.Get, "/v1/posts/$ID", "getPost"))) {
            val response = client.request(path) { this.method = method }
            assertEquals(503, response.status.value)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val body = response.bodyAsText()
            assertEquals(BodyValidationResult.Valid, validator.validateResponse(operation, 503, body.encodeToByteArray(), "application/problem+json"))
            assertEquals("OPERATION_NOT_IMPLEMENTED", Json.parseToJsonElement(body).jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    @Test fun diagnosticStringsNeverIncludeDraftKeyBearerOrCaption() {
        val parsed = input()
        assertEquals("PostPublicationHttpInput(<redacted>)", parsed.toString())
        val failure = denied(400) { input(headers = replace("Idempotency-Key", "PRIVATE-CAPTION")) }
        for (value in listOf(parsed, parsed.bearer, failure)) for (secret in listOf(ID, "synthetic-secret", "PRIVATE-CAPTION", "Crème"))
            assertFalse(value.toString().contains(secret))
    }

    private fun input(headers: List<Pair<String, String>> = defaults(), extra: List<Pair<String, String>> = emptyList(),
        query: Parameters = Parameters.Empty, paths: Parameters = Parameters.Empty) = PostPublicationHttpInput.parse(
        Headers.build { (headers + extra).forEach { append(it.first, it.second) } }, query, paths)
    private fun defaults() = listOf("Authorization" to "Bearer synthetic-secret", "X-Device-Session" to ID,
        "Idempotency-Key" to ID, "Content-Type" to "application/json")
    private fun replace(name: String, value: String) = defaults().filterNot { it.first == name } + (name to value)
    private fun denied(status: Int, action: () -> Any?) = assertFailsWith<PostPublicationHttpFailure> { action() }.also { assertEquals(status, it.status) }
    private suspend fun deniedSuspend(status: Int, action: suspend () -> Any?) = assertFailsWith<PostPublicationHttpFailure> { action() }.also { assertEquals(status, it.status) }

    companion object {
        private const val ID = "00000000-0000-4000-8000-000000000011"
        private val uuid = UUID.fromString(ID)
        private val validator = ContractBodyValidator.bundled()
        private const val request = """{"caption":"Crème 🥗","mediaIds":[],"audience":{"kind":"self","circleIds":[]},"keepOnPlate":false,"allowRecipeSaves":false,"saveDisclosureVersion":"fixture-v1","clientDraftId":"00000000-0000-4000-8000-000000000011"}"""
        private val post = Json.parseToJsonElement("""{"id":"$ID","version":1,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","author":{"userId":"$ID","displayName":"Synthetic fixture","handle":"fixture"},"caption":"Crème 🥗","mediaIds":[],"audience":{"kind":"self","circleIds":[]},"status":"published","publishedAt":"2026-09-14T00:00:00Z","expiresAt":"2026-09-15T00:00:00Z","keepOnPlate":false,"savePolicy":{"allowFutureSaves":false,"policyVersion":1,"disclosureVersion":"fixture-v1"},"aclVersion":1,"capabilities":[],"reactionCounts":[]}""").jsonObject
    }
}
