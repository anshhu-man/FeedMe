package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandResult
import com.feedme.server.db.PgTransactions
import com.feedme.server.db.StoredReply
import com.feedme.server.media.*
import com.feedme.server.social.VerifiedSocialAccount
import com.feedme.server.social.drafts.*
import io.ktor.http.*
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import java.lang.reflect.Proxy
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Ingress/response boundaries only. Every database/authority fixture throws if invoked. */
class PostDraftHttpInputTest {
    @Test fun exactFiveCanonicalOperationsRequireAccountAndOriginalMutationMetadata() {
        val canonical = PostDraftHttpInputTest::class.java.getResourceAsStream("/feedme-openapi.json")!!.use {
            Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
        }.getValue("paths").jsonObject.filterKeys { it == "/v1/post-drafts" || it == "/v1/post-drafts/{draftId}" }
            .values.flatMap { path -> path.jsonObject.filterKeys { it in setOf("get", "post", "patch", "put", "delete") }.values }
            .map { it.jsonObject }
        assertEquals(5, canonical.size)
        assertEquals(setOf("createPostDraft", "getPostDraft", "listPostDrafts", "updatePostDraft", "deletePostDraft"), postDraftHttpOperations)
        assertEquals(postDraftHttpOperations, canonical.map { it.getValue("operationId").jsonPrimitive.content }.toSet())
        for (operation in postDraftHttpOperations) {
            val parsed = input(operation)
            val contract = canonical.single { it.getValue("operationId").jsonPrimitive.content == operation }
            assertEquals("user", contract.getValue("x-principal").jsonPrimitive.content)
            assertEquals(operation !in reads, contract.getValue("x-idempotency-required").jsonPrimitive.boolean)
            assertEquals(operation in bodies, parsed.hasBody)
            assertEquals(if (operation in reads) null else uuid, parsed.key)
            assertEquals(if (operation in setOf("createPostDraft", "listPostDrafts")) null else uuid, parsed.draftId)
            assertEquals(if (operation in versions) "\"1\"" else null, parsed.ifMatch)
        }
        denied(400) { input("publishPost") }
    }

    @Test fun allOperationsRequireBearerAndDeviceWithoutGuestOrPublicInference() {
        for (operation in postDraftHttpOperations) for (name in listOf("Authorization", "X-Device-Session"))
            denied(401) { input(operation, headers = defaults(operation).filterNot { it.first == name }) }
        for (token in listOf("Basic secret", "Bearer", "Bearer ", "Bearer one two", "Bearer =bad", "Bearer a=b", "Bearer " + "a".repeat(16385)))
            denied(401) { input(headers = replace("Authorization", token)) }
        denied(400) { input(headers = replace("Authorization", "Bearer a,Bearer b")) }
        val accepted = input(headers = replace("Authorization", "bEaReR  abc+/=="))
        assertEquals("abc+/==", accepted.bearer.token.use { it })
        assertEquals(uuid, accepted.bearer.deviceSessionId)
    }

    @Test fun duplicateSecurityFramingAndConditionalHeadersAreRejectedCaseInsensitively() {
        for (name in listOf("Authorization", "X-Device-Session", "Idempotency-Key", "If-Match", "If-None-Match",
            "Content-Type", "Content-Length", "Transfer-Encoding", "Content-Encoding")) {
            val original = defaults("updatePostDraft")
            val value = original.firstOrNull { it.first == name }?.second ?: "0"
            denied(400) { input("updatePostDraft", headers = original.filterNot { it.first == name } +
                listOf(name to value, name.lowercase() to value)) }
        }
    }

    @Test fun deviceKeyAndPathAcceptOnlyWholeCanonicalUuidSpellings() {
        for (bad in listOf("1-1-1-1-1", "guest", " $ID", "$ID ", "$ID/child", "$ID%2fchild", "$ID,$ID")) {
            for (name in listOf("X-Device-Session", "Idempotency-Key")) denied(400) { input(headers = replace(name, bad)) }
            denied(400) { input("getPostDraft", paths = parametersOf("draftId", bad)) }
        }
        assertEquals(uuid, input("getPostDraft", paths = parametersOf("draftId", ID.uppercase())).draftId)
        denied(400) { input("getPostDraft", paths = Parameters.Empty) }
        denied(400) { input("getPostDraft", paths = parametersOf("draftId", listOf(ID, ID))) }
        denied(400) { input("getPostDraft", paths = Parameters.build { append("draftId", ID); append("ownerId", ID) }) }
        for (operation in setOf("createPostDraft", "listPostDrafts"))
            denied(400) { input(operation, paths = parametersOf("draftId", ID)) }
    }

    @Test fun mutationKeysAreMandatoryAndReadsNeverHonorThem() {
        for (operation in postDraftHttpOperations - reads)
            denied(400) { input(operation, headers = defaults(operation).filterNot { it.first == "Idempotency-Key" }) }
        for (operation in reads) denied(400) { input(operation, extra = listOf("Idempotency-Key" to ID)) }
        val second = UUID.randomUUID()
        assertEquals(second, input(headers = replace("Idempotency-Key", second.toString())).key)
    }

    @Test fun originalQuotedVersionIsPreservedWithoutNumericRebase() {
        for (operation in versions) {
            denied(428) { input(operation, headers = defaults(operation).filterNot { it.first == "If-Match" }) }
            for (bad in listOf("*", "1", "W/\"1\"", "\"-1\"", "\"1.0\"", "\"1e0\"", "\"1\",\"2\"", "\"" + "1".repeat(65) + "\""))
                denied(400) { input(operation, headers = replace("If-Match", bad, operation)) }
            for (etag in listOf("\"0001\"", "\"9007199254740993\"", "\"" + "1".repeat(64) + "\""))
                assertEquals(etag, input(operation, headers = replace("If-Match", etag, operation)).ifMatch)
        }
        for (operation in postDraftHttpOperations - versions) denied(400) { input(operation, extra = listOf("If-Match" to "\"1\"")) }
        for (operation in postDraftHttpOperations) denied(400) { input(operation, extra = listOf("If-None-Match" to "\"1\"")) }
    }

    @Test fun paginationIsExclusiveUniqueAndBoundedWithoutOwnerFilters() {
        assertEquals(20, input("listPostDrafts").limit)
        assertEquals(1, input("listPostDrafts", query = parametersOf("limit", "1")).limit)
        assertEquals(50, input("listPostDrafts", query = parametersOf("limit", "50")).limit)
        assertEquals("opaque-original", input("listPostDrafts", query = parametersOf("cursor", "opaque-original")).cursor)
        // Real Ktor ApplicationCall.parameters includes query entries as well as route paths.
        val merged = Parameters.build { append("limit", "1"); append("cursor", "opaque-original") }
        val parsed = input("listPostDrafts", query = merged, paths = merged)
        assertEquals(1, parsed.limit); assertEquals("opaque-original", parsed.cursor); assertNull(parsed.draftId)
        for (bad in listOf("0", "51", "100", "+1", "1e0", "1.0", " 1", "-1"))
            denied(400) { input("listPostDrafts", query = parametersOf("limit", bad)) }
        for (name in listOf("cursor", "limit"))
            denied(400) { input("listPostDrafts", query = parametersOf(name, listOf("1", "1"))) }
        for (bad in listOf("", "a".repeat(2049), "a\nb", "a\u007fb", "\uD800"))
            denied(400) { input("listPostDrafts", query = parametersOf("cursor", bad)) }
        assertEquals(2048, input("listPostDrafts", query = parametersOf("cursor", "a".repeat(2048))).cursor!!.length)
        for (name in listOf("ownerId", "accountId", "clientDraftId", "draftId", "q", "status")) {
            val query = parametersOf(name, ID)
            denied(400) { input("listPostDrafts", query = query) }
            denied(400) { input("listPostDrafts", query = query, paths = query) }
        }
        for (operation in postDraftHttpOperations - "listPostDrafts")
            denied(400) { input(operation, query = parametersOf("limit", "1")) }
    }

    @Test fun framingRejectsConflictingLengthsTransferAndCompression() {
        for (bad in listOf("-1", "+1", "65537", "18446744073709551615", "1,1", " 1", "1.0"))
            denied(400) { input(extra = listOf("Content-Length" to bad)) }
        denied(400) { input(extra = listOf("Content-Length" to "1", "Transfer-Encoding" to "chunked")) }
        for (bad in listOf("gzip", "identity", "chunked,chunked", "chunked "))
            denied(400) { input(extra = listOf("Transfer-Encoding" to bad)) }
        for (bad in listOf("gzip", "br", "identity,identity")) denied(400) { input(extra = listOf("Content-Encoding" to bad)) }
        assertNotNull(input(extra = listOf("Transfer-Encoding" to "CHUNKED", "Content-Encoding" to "IDENTITY")))
        assertEquals(65536L, input(extra = listOf("Content-Length" to "65536")).contentLength)
    }

    @Test fun onlyCanonicalJsonAndOptionalUtf8CharsetAreAcceptedForWrites() {
        for (operation in bodies) {
            for (bad in listOf("text/plain", "multipart/form-data", "application/json;charset=utf-16", "application/json;charset=utf-8;charset=utf-8", "application/json;boundary=x"))
                denied(400) { input(operation, headers = replace("Content-Type", bad, operation)) }
            denied(400) { input(operation, headers = defaults(operation).filterNot { it.first == "Content-Type" }) }
            for (media in listOf("application/json", "Application/JSON; charset=\"UTF-8\"", "application/json;charset=utf-8"))
                assertEquals(media, input(operation, headers = replace("Content-Type", media, operation)).mediaType)
        }
    }

    @Test fun readAndDeleteRejectBothDeclaredAndActualBodies() = runBlocking<Unit> {
        for (operation in postDraftHttpOperations - bodies) {
            for (header in listOf("Content-Type" to "application/json", "Content-Length" to "1", "Transfer-Encoding" to "chunked"))
                denied(400) { input(operation, extra = listOf(header)) }
            assertNull(input(operation, extra = listOf("Content-Length" to "0")).body(byteArrayOf(), validator))
            assertNull(input(operation).readBody(ByteReadChannel(byteArrayOf()), validator))
            denied(400) { input(operation).body("{}".encodeToByteArray(), validator) }
            deniedSuspend(400) { input(operation).readBody(ByteReadChannel(byteArrayOf(32)), validator) }
        }
    }

    @Test fun originalBytesRejectDuplicateKeysMalformedUtf8DepthAndTrailingContent() {
        for (bad in listOf("", "{", "{} trailing", "{\"caption\":\"first\",\"caption\":\"second\"}",
            "{\"caption\":\"\\uD800\"}", "[".repeat(33) + "0" + "]".repeat(33)))
            denied(400) { input().body(bad.encodeToByteArray(), validator) }
        for (bad in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())))
            denied(400) { input().body(bad, validator) }
        denied(400) { input().body(ByteArray(65537) { 32 }, validator) }
    }

    @Test fun createRequiresExactClientLifecycleAndDoesNotInventDraftFields() {
        val parsed = input().body(create.encodeToByteArray(), validator)!!
        assertEquals(setOf("clientDraftId"), parsed.keys)
        assertEquals(ID, parsed.getValue("clientDraftId").jsonPrimitive.content)
        for (bad in listOf("{}", "{\"clientDraftId\":\"bad\"}", create.dropLast(1) + ",\"ownerId\":\"$ID\"}",
            create.dropLast(1) + ",\"status\":\"published\"}", create.dropLast(1) + ",\"publishedPostId\":\"$ID\"}"))
            denied(422) { input().body(bad.encodeToByteArray(), validator) }
    }

    @Test fun patchPreservesAbsentVersusExplicitFieldsAndOriginalUnicodeText() {
        val parsed = input("updatePostDraft")
        assertTrue(parsed.body("{}".encodeToByteArray(), validator)!!.isEmpty())
        val original = """{"caption":"Crème 🥗","altText":"\\n is text","keepOnPlate":false,"removeAttachment":true,"mediaIds":[]}"""
        assertEquals(Json.parseToJsonElement(original).jsonObject, assertNotNull(parsed.body(original.encodeToByteArray(), validator)))
        assertEquals(false, parsed.body("{\"allowRecipeSaves\":false}".encodeToByteArray(), validator)!!.getValue("allowRecipeSaves").jsonPrimitive.boolean)
        for (bad in listOf("{\"caption\":null}", "{\"keepOnPlate\":\"false\"}", "{\"mediaIds\":[\"bad\"]}",
            "{\"caption\":\"${"x".repeat(501)}\"}", "{\"version\":9007199254740993}"))
            denied(422) { parsed.body(bad.encodeToByteArray(), validator) }
    }

    @Test fun streamingChecksActualByteCapAndDeclaredLengthBeforeProjection() = runBlocking<Unit> {
        deniedSuspend(400) { input().readBody(ByteReadChannel(ByteArray(65537) { 32 }), validator) }
        deniedSuspend(400) { input().readBody(ByteReadChannel(byteArrayOf()), validator) }
        deniedSuspend(400) { input(extra = listOf("Content-Length" to "1")).readBody(ByteReadChannel(create.encodeToByteArray()), validator) }
        assertNotNull(input(extra = listOf("Content-Length" to "65536")).readBody(ByteReadChannel(create.padEnd(65536, ' ').encodeToByteArray()), validator))
        val utf8 = create.dropLast(1) + ",\"caption\":\"🥗\"}"
        assertNotNull(input(extra = listOf("Content-Length" to utf8.encodeToByteArray().size.toString())).readBody(ByteReadChannel(utf8.encodeToByteArray()), validator))
        deniedSuspend(400) { input(extra = listOf("Content-Length" to utf8.length.toString())).readBody(ByteReadChannel(utf8.encodeToByteArray()), validator) }
    }

    @Test fun cancellationWhileWaitingForBodyDoesNotProduceAnEmptyOrValidatedRequest() = runBlocking<Unit> {
        val channel = ByteChannel(autoFlush = true)
        var returned = false
        val read = launch(start = CoroutineStart.UNDISPATCHED) { input().readBody(channel, validator); returned = true }
        assertTrue(read.isActive)
        read.cancelAndJoin()
        channel.cancel(CancellationException("Test channel closed"))
        assertTrue(read.isCancelled); assertFalse(returned)
    }

    @Test fun createGetAndUpdateResponsesRequireExactStatusSchemaAndVersion() {
        for (operation in setOf("createPostDraft", "getPostDraft", "updatePostDraft")) {
            val status = if (operation == "createPostDraft") 201 else 200
            assertEquals(draft.toString(), validatePostDraftReply(operation, StoredReply(status, draft, "\"1\""), validator, 65536))
            for (bad in listOf(StoredReply(if (status == 201) 200 else 201, draft, "\"1\""), StoredReply(status, draft),
                StoredReply(status, draft, "\"2\""), StoredReply(status, JsonObject(draft - "clientDraftId"), "\"1\"")))
                assertFailsWith<IllegalStateException> { validatePostDraftReply(operation, bad, validator, 65536) }
            assertFailsWith<IllegalStateException> { validatePostDraftReply(operation, StoredReply(status, draft, "\"1\""), validator, 1) }
        }
    }

    @Test fun publishedGetIsObservableButNeverAPositiveCreateOrUpdateReply() {
        val published = JsonObject(draft + mapOf("status" to JsonPrimitive("published"), "publishedPostId" to JsonPrimitive(ID)))
        assertEquals(published.toString(), validatePostDraftReply("getPostDraft", StoredReply(200, published, "\"1\""), validator, 65536))
        for ((operation, status) in listOf("createPostDraft" to 201, "updatePostDraft" to 200))
            assertFailsWith<IllegalStateException> { validatePostDraftReply(operation, StoredReply(status, published, "\"1\""), validator, 65536) }
        for (state in listOf("discarded", "expired"))
            assertFailsWith<IllegalStateException> { validatePostDraftReply("getPostDraft", StoredReply(200,
                JsonObject(draft + ("status" to JsonPrimitive(state))), "\"1\""), validator, 65536) }
    }

    @Test fun responseEtagComparisonNeverRoundsLargeOrExponentialVersions() {
        for ((version, etag) in listOf("9007199254740993" to "\"9007199254740993\"", "1e3" to "\"0001000\"")) {
            val body = JsonObject(draft + ("version" to Json.parseToJsonElement(version)))
            assertEquals(body.toString(), validatePostDraftReply("getPostDraft", StoredReply(200, body, etag), validator, 65536))
            assertFailsWith<IllegalStateException> { validatePostDraftReply("getPostDraft", StoredReply(200, body, "\"9007199254740992\""), validator, 65536) }
        }
        for (etag in listOf("1", "W/\"1\"", "*", "\"1.0\"", "\"1e0\"", "\"-1\"", "\"1\",\"1\""))
            assertFailsWith<IllegalStateException> { validatePostDraftReply("getPostDraft", StoredReply(200, draft, etag), validator, 65536) }
    }

    @Test fun listHeadIsMandatoryIndependentFromItemVersionAndZeroForAnEmptyOwner() {
        val empty = page(emptyList())
        assertEquals(empty.toString(), validatePostDraftReply("listPostDrafts", StoredReply(200, empty, "\"0\""), validator, 65536))
        val populated = page(listOf(draft))
        assertEquals(populated.toString(), validatePostDraftReply("listPostDrafts", StoredReply(200, populated, "\"9007199254740993\""), validator, 65536))
        for (bad in listOf(StoredReply(200, populated), StoredReply(201, populated, "\"1\""),
            StoredReply(200, JsonObject(populated - "serverTime"), "\"1\""), StoredReply(200, draft, "\"1\"")))
            assertFailsWith<IllegalStateException> { validatePostDraftReply("listPostDrafts", bad, validator, 65536) }
    }

    @Test fun deleteReplyIsExactlyBodylessAndNeverCarriesAnEtag() {
        assertEquals("", validatePostDraftReply("deletePostDraft", StoredReply(204), validator, 65536))
        assertFailsWith<IllegalArgumentException> { StoredReply(204, draft) }
        assertFailsWith<IllegalStateException> { validatePostDraftReply("deletePostDraft", StoredReply(204, etag = "\"1\""), validator, 65536) }
        assertFailsWith<IllegalStateException> { validatePostDraftReply("deletePostDraft", StoredReply(200, draft, "\"1\""), validator, 65536) }
        assertFailsWith<IllegalStateException> { validatePostDraftReply("publishPost", StoredReply(204), validator, 65536) }
    }

    @Test fun durableCommandResultsPreserveOriginalReplyAndFiniteFailures() {
        val original = StoredReply(201, draft, "\"0001\"")
        assertSame(original, draftReply(CommandResult.Applied(original)))
        assertSame(original, draftReply(CommandResult.Replayed(original)))
        for ((result, status, code) in listOf(Triple(CommandResult.Mismatch, 409, "IDEMPOTENCY_MISMATCH"),
            Triple(CommandResult.ReceiptExpired, 410, "IDEMPOTENCY_EXPIRED"), Triple(CommandResult.IncompleteReceipt, 409, "COMMAND_INCOMPLETE")))
            assertEquals(code, denied(status) { draftReply(result) }.code)
    }

    @Test fun verifiedPrincipalMustMatchConfiguredEnvironmentAndExactDevice() = runBlocking<Unit> {
        val actor = account()
        assertSame(actor, configuration { PortResult.Value(actor) }.authenticate(input()))
        for (wrong in listOf(VerifiedSocialAccount("other", uuid, uuid), VerifiedSocialAccount("test", uuid, UUID.randomUUID())))
            deniedSuspend(401) { configuration { PortResult.Value(wrong) }.authenticate(input()) }
    }

    @Test fun verificationFailuresAreFiniteSanitizedAndRetryMetadataRemainsBoundedByPurpose() = runBlocking<Unit> {
        for ((reason, status) in listOf(FailureReason.UNAUTHENTICATED to 401, FailureReason.STALE_SESSION to 401,
            FailureReason.INVALID_DATA to 401, FailureReason.NOT_FOUND to 401, FailureReason.FORBIDDEN to 403,
            FailureReason.RATE_LIMITED to 429, FailureReason.UNAVAILABLE to 503, FailureReason.OUTCOME_UNKNOWN to 503)) {
            val failure = deniedSuspend(status) { configuration { PortResult.Failure(reason, 7) }.authenticate(input()) }
            assertEquals(if (status in setOf(429, 503)) 7L else null, failure.retryAfterSeconds)
        }
        val failure = deniedSuspend(503) { configuration { error("PRIVATE-TOKEN-CAPTION-CANARY") }.authenticate(input()) }
        assertFalse(failure.toString().contains("PRIVATE-TOKEN-CAPTION-CANARY")); assertNull(failure.cause)
    }

    @Test fun cancellationCannotBeReclassifiedAsAuthFailureOrSuccessfulPrincipal() = runBlocking<Unit> {
        assertFailsWith<CancellationException> { configuration { throw CancellationException("synthetic cancellation") }.authenticate(input()) }
        val job = Job()
        val config = configuration { job.cancel(); withContext(NonCancellable) { PortResult.Value(account()) } }
        assertFailsWith<CancellationException> { withContext(job) { config.authenticate(input()) } }
    }

    @Test fun configurationRequiresMatchingExplicitEnvironmentWithoutDatabaseAcquisition() {
        val configured = configuration { error("Unused verifier") }
        for (environment in listOf("", "Test", "other", "a".repeat(41)))
            assertFailsWith<IllegalArgumentException> { PostDraftHttpConfiguration(environment, configured.store, configured.verifier, Dispatchers.IO) }
        assertEquals("PostDraftHttpConfiguration(<redacted>)", configured.toString())
    }

    @Test fun diagnosticsRedactBearerDeviceDraftAndPrecondition() {
        val parsed = input("updatePostDraft")
        assertEquals("PostDraftHttpInput(<redacted>)", parsed.toString())
        assertEquals("SocialHttpBearer(<redacted>)", parsed.bearer.toString())
        val failure = denied(400) { input(headers = replace("Idempotency-Key", "PRIVATE-CAPTION-CANARY")) }
        for (value in listOf(parsed, parsed.bearer, failure)) for (privateValue in listOf(ID, "synthetic-secret", "PRIVATE-CAPTION-CANARY"))
            assertFalse(value.toString().contains(privateValue))
    }

    private fun input(operation: String = "createPostDraft", headers: List<Pair<String, String>> = defaults(operation),
        extra: List<Pair<String, String>> = emptyList(), query: Parameters = Parameters.Empty,
        paths: Parameters = if (operation in setOf("createPostDraft", "listPostDrafts")) Parameters.Empty else parametersOf("draftId", ID)) =
        PostDraftHttpInput.parse(operation, Headers.build { (headers + extra).forEach { append(it.first, it.second) } }, query, paths)
    private fun defaults(operation: String = "createPostDraft") = buildList {
        add("Authorization" to "Bearer synthetic-secret"); add("X-Device-Session" to ID)
        if (operation !in reads) add("Idempotency-Key" to ID)
        if (operation in bodies) add("Content-Type" to "application/json")
        if (operation in versions) add("If-Match" to "\"1\"")
    }
    private fun replace(name: String, value: String, operation: String = "createPostDraft") =
        defaults(operation).filterNot { it.first == name } + (name to value)
    private fun denied(status: Int, action: () -> Any?) = assertFailsWith<PostDraftHttpFailure> { action() }.also { assertEquals(status, it.status) }
    private suspend fun deniedSuspend(status: Int, action: suspend () -> Any?) = assertFailsWith<PostDraftHttpFailure> { action() }.also { assertEquals(status, it.status) }
    private fun account() = VerifiedSocialAccount("test", uuid, uuid)
    private fun configuration(verifier: suspend (SocialHttpBearer) -> PortResult<VerifiedSocialAccount>): PostDraftHttpConfiguration {
        fun <T> forbidden(type: Class<T>): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ ->
            error("Unexpected database or authority access") })
        val transactions = PgTransactions(forbidden(DataSource::class.java))
        val media = MediaStore("test", transactions, forbidden(MediaAuthority::class.java),
            MediaUploadCapabilities { error("Unexpected capability issuance") }, MediaObjectVerifier { error("Unexpected object access") },
            MediaServicePolicy(1_000_000, setOf("image/jpeg"), 3600, 60, 4096, 65536, 256, setOf("https://upload.example.test")))
        val store = PostDraftStore("test", transactions, forbidden(PostDraftAuthority::class.java), media,
            PostDraftServicePolicy(65536, 3600, 60), PostDraftCursors("fixture", mapOf("fixture" to ByteArray(32) { 7 })))
        return PostDraftHttpConfiguration("test", store, SocialHttpVerifier(verifier), Dispatchers.IO)
    }
    private fun page(items: List<JsonElement>) = buildJsonObject {
        put("items", JsonArray(items)); put("nextCursor", JsonNull); put("serverTime", "2026-09-14T00:00:00Z")
    }
    companion object {
        private const val ID = "00000000-0000-4000-8000-000000000011"
        private val uuid = UUID.fromString(ID)
        private val reads = setOf("getPostDraft", "listPostDrafts")
        private val bodies = setOf("createPostDraft", "updatePostDraft")
        private val versions = setOf("updatePostDraft", "deletePostDraft")
        private val validator = ContractBodyValidator.bundled()
        private const val create = """{"clientDraftId":"00000000-0000-4000-8000-000000000011"}"""
        private val draft = Json.parseToJsonElement("""{"id":"00000000-0000-4000-8000-000000000011","version":1,
            "createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","clientDraftId":"00000000-0000-4000-8000-000000000011",
            "status":"draft","caption":"Synthetic caption","mediaIds":[],"keepOnPlate":false,"expiresAt":"2026-09-15T00:00:00Z"}""").jsonObject
    }
}
