package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.PostgresTestCluster
import com.feedme.server.social.VerifiedSocialAccount
import com.feedme.server.social.drafts.PostDraftTestFixture
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/** Actual Ktor/CIO and PostgreSQL. All identity/content/object providers are explicitly synthetic. */
class PostDraftHttpIntegrationTest {
    @Test fun fiveConfiguredOperationsPreserveCanonicalIdentityVersionsAndEmptyDeletion() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postDraft = f.configuration()) }
        val created = client.create(f); success(created, 201, "createPostDraft"); val draft = json(created)
        val id = draft.text("id")
        assertEquals("draft", draft.text("status")); assertEquals(false, draft["keepOnPlate"]!!.jsonPrimitive.boolean)
        val read = client.get("/v1/post-drafts/$id") { auth(f) }; success(read, 200, "getPostDraft"); assertEquals(draft, json(read))
        val page = client.get("/v1/post-drafts") { auth(f) }; success(page, 200, "listPostDrafts")
        assertEquals(listOf(draft), json(page)["items"]!!.jsonArray.toList())
        val updated = client.edit(f, id, """{"caption":"Dinner, handled."}"""); success(updated, 200, "updatePostDraft")
        assertEquals("\"2\"", updated.headers[HttpHeaders.ETag]); assertEquals(draft["clientDraftId"], json(updated)["clientDraftId"])
        success(client.remove(f, id, etag = "\"2\""), 204, "deletePostDraft")
        problem(client.get("/v1/post-drafts/$id") { auth(f) }, 404, "DRAFT_UNAVAILABLE")
        assertEquals(3, f.test.count("platform.outbox")); assertEquals(3, f.test.count("platform.idempotency"))
        assertEquals("discarded", f.test.value("SELECT status FROM platform.post_drafts"))
        assertEquals(0, f.test.mediaFixture.signer.authorizations.size)
    }

    @Test fun originalCreateAndUpdateKeysReplayExactlyButNeverAdoptLaterDraftVersion() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postDraft = f.configuration()) }
        val key = uuid(); val input = f.input(); val first = client.create(f, key, input)
        val repeated = client.create(f, key, input); success(repeated, 201, "createPostDraft"); assertEquals(json(first), json(repeated))
        problem(client.create(f, key, JsonObject(parse(input) + ("caption" to JsonPrimitive("different"))).toString()), 409, "IDEMPOTENCY_MISMATCH")
        val id = json(first).text("id"); val editKey = uuid(); val patch = """{"caption":"Fresh caption"}"""
        val changed = client.edit(f, id, patch, editKey); val replay = client.edit(f, id, patch, editKey)
        success(replay, 200, "updatePostDraft"); assertEquals(json(changed), json(replay))
        problem(client.create(f, key, input), 409, "DRAFT_CONFLICT")
        problem(client.edit(f, id, patch), 412, "VERSION_CONFLICT")
        assertEquals(1, f.test.count("platform.post_drafts")); assertEquals(2, f.test.count("platform.outbox"))
    }

    @Test fun ownerAndCurrentDeviceChecksAreRequiredAgainForReadsWritesAndCachedReplies() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postDraft = f.configuration()) }
        val key = uuid(); val input = f.input(); val original = client.create(f, key, input); val id = json(original).text("id")
        val foreign = f.test.principal()
        problem(client.get("/v1/post-drafts/$id") { auth(f, foreign) }, 404, "DRAFT_UNAVAILABLE")
        problem(client.delete("/v1/post-drafts/$id") { auth(f, foreign); header("Idempotency-Key", uuid()); header(HttpHeaders.IfMatch, "\"1\"") }, 404, "DRAFT_UNAVAILABLE")
        val otherPage = client.get("/v1/post-drafts") { auth(f, foreign) }; success(otherPage, 200, "listPostDrafts")
        assertTrue(json(otherPage)["items"]!!.jsonArray.isEmpty())
        f.test.sql("UPDATE cooking_test.sessions SET active=false")
        problem(client.create(f, key, input), 401, "UNAUTHENTICATED")
        problem(client.get("/v1/post-drafts/$id") { auth(f) }, 401, "UNAUTHENTICATED")
        problem(client.remove(f, id), 401, "UNAUTHENTICATED")
        assertEquals(1, f.test.count("platform.outbox")); assertEquals(1, f.test.count("platform.idempotency"))
    }

    @Test fun expiredDraftRemainsExplicitlyRemovableWithoutNewContentOrUploadPermission() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postDraft = f.configuration()) }
        val clientId = f.test.client(); val media = f.test.prepare(clientId)
        val body = JsonObject(f.test.body(clientId) + ("mediaIds" to buildJsonArray { add(media.getValue("id")) }))
        val key = uuid(); val created = client.create(f, key, body.toString()); success(created, 201, "createPostDraft")
        val id = json(created).text("id"); f.test.expire(UUID.fromString(id)); f.test.authority.enabled = false; f.test.authority.terms = false
        problem(client.get("/v1/post-drafts/$id") { auth(f) }, 410, "DRAFT_EXPIRED")
        problem(client.create(f, key, body.toString()), 410, "DRAFT_EXPIRED")
        val page = client.get("/v1/post-drafts") { auth(f) }; success(page, 200, "listPostDrafts"); assertTrue(json(page)["items"]!!.jsonArray.isEmpty())
        val deleteKey = uuid(); success(client.remove(f, id, deleteKey), 204, "deletePostDraft")
        success(client.remove(f, id, deleteKey), 204, "deletePostDraft")
        assertEquals("deleted", f.test.value("SELECT state FROM platform.media_assets")); assertEquals(1, f.test.count("platform.media_cleanup_jobs"))
        assertEquals(1, f.test.count("platform.post_draft_discard_media")); assertEquals(2, f.test.count("platform.outbox"))
    }

    @Test fun acknowledgedDraftDiscardFencesLaterActualMediaUploadForTheSameClientRoot() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postDraft = f.configuration(), media = f.mediaConfiguration()) }
        val clientId = f.test.client(); val created = client.create(f, body = f.test.body(clientId).toString()); val id = json(created).text("id")
        success(client.remove(f, id), 204, "deletePostDraft")
        val denied = client.post("/v1/media") {
            auth(f); header("Idempotency-Key", uuid()); contentType(ContentType.Application.Json)
            setBody(f.test.mediaFixture.prepareBody(PostDraftTestFixture.mediaActor(f.test.account), clientId).toString())
        }
        problem(denied, 409, "DRAFT_UNAVAILABLE")
        assertEquals(0, f.test.count("platform.media_assets")); assertEquals(0, f.test.mediaFixture.signer.authorizations.size)
        assertEquals(2, f.test.count("platform.idempotency"))
    }

    @Test fun lostCreateAndDeleteCommitRepliesRequireOriginalKeysAndDoNotDuplicateEffects() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postDraft = f.configuration()) }
        val key = uuid(); val input = f.input(); f.test.faults.loseCommit = true
        problem(client.create(f, key, input), 503, "OUTCOME_UNKNOWN")
        val restored = client.create(f, key, input); success(restored, 201, "createPostDraft")
        val id = json(restored).text("id"); val deleteKey = uuid(); f.test.faults.loseCommit = true
        problem(client.remove(f, id, deleteKey), 503, "OUTCOME_UNKNOWN")
        success(client.remove(f, id, deleteKey), 204, "deletePostDraft")
        assertEquals(1, f.test.count("platform.post_drafts")); assertEquals(2, f.test.count("platform.outbox")); assertEquals(2, f.test.count("platform.idempotency"))
    }

    @Test fun actualSignedPaginationIsOwnerLimitAndRevisionBound() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postDraft = f.configuration()) }
        repeat(3) { success(client.create(f), 201, "createPostDraft") }
        val first = client.get("/v1/post-drafts?limit=1") { auth(f) }; success(first, 200, "listPostDrafts")
        val firstBody = json(first); val cursor = firstBody.text("nextCursor")
        val second = client.get("/v1/post-drafts") { auth(f); parameter("limit", "1"); parameter("cursor", cursor) }
        success(second, 200, "listPostDrafts"); assertNotEquals(firstBody["items"], json(second)["items"])
        problem(client.get("/v1/post-drafts") { auth(f); parameter("limit", "2"); parameter("cursor", cursor) }, 409, "CURSOR_INVALID")
        problem(client.get("/v1/post-drafts") { auth(f, f.test.principal()); parameter("limit", "1"); parameter("cursor", cursor) }, 409, "CURSOR_INVALID")
        client.create(f)
        problem(client.get("/v1/post-drafts") { auth(f); parameter("limit", "1"); parameter("cursor", cursor) }, 409, "CURSOR_INVALID")
        assertEquals(4, f.test.count("platform.post_drafts"))
    }

    @Test fun malformedWireAndMissingPreconditionsCannotCreateAnyDurableCommand() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postDraft = f.configuration()) }
        val input = f.input()
        for (body in listOf(input.dropLast(1) + ",\"clientDraftId\":\"${uuid()}\"}", " ".repeat(65537), "{\"caption\":\"\\uD800\"}"))
            problem(client.create(f, body = body), 400, "INVALID_REQUEST")
        problem(client.create(f, body = "{}"), 422, "INPUT_INVALID")
        problem(client.create(f, extras = { header(HttpHeaders.IfMatch, "\"1\"") }), 400, "INVALID_REQUEST")
        problem(client.create(f, extras = { header(HttpHeaders.Authorization, "Bearer repeated") }), 400, "INVALID_REQUEST")
        problem(client.post("/v1/post-drafts") { auth(f); contentType(ContentType.Application.Json); setBody(input) }, 400, "INVALID_REQUEST")
        problem(client.delete("/v1/post-drafts/${uuid()}") { auth(f); header("Idempotency-Key", uuid()) }, 428, "PRECONDITION_REQUIRED")
        assertEquals(0, f.test.count("platform.post_drafts")); assertEquals(0, f.test.count("platform.idempotency"))
    }

    @Test fun responseBudgetAndVerifierMismatchOrFailureCannotPartiallyWrite() = testApplication {
        val f = Fixture(maxBytes = 1); application { feedMeLocalService(local, postDraft = f.configuration()) }
        problem(client.create(f), 422, "RESPONSE_TOO_LARGE"); assertEquals(0, f.test.count("platform.idempotency")); assertEquals(0, f.test.count("platform.post_drafts"))
        f.verify = { throw IllegalStateException("PRIVATE-caption-provider-token") }
        problem(client.create(f), 503, "AUTHENTICATION_UNAVAILABLE")
        f.verify = { PortResult.Value(VerifiedSocialAccount("other", f.test.account.accountId, it.deviceSessionId)) }
        problem(client.create(f), 401, "UNAUTHENTICATED")
        f.verify = { PortResult.Value(VerifiedSocialAccount("test", f.test.account.accountId, uuid())) }
        problem(client.create(f), 401, "UNAUTHENTICATED")
        assertEquals(0, f.test.count("platform.outbox"))
    }

    @Test fun cancelledCallerAfterCommitRetainsExactReplayableDraft() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postDraft = f.configuration()) }
        val key = uuid(); val input = f.input(); val committed = CountDownLatch(1); val release = CountDownLatch(1)
        f.test.faults.afterCommit = { committed.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        coroutineScope {
            val pending = async { client.create(f, key, input) }
            try {
                assertTrue(withContext(Dispatchers.IO) { committed.await(10, TimeUnit.SECONDS) })
                pending.cancel(); release.countDown(); assertFailsWith<CancellationException> { pending.await() }
            } finally { release.countDown(); pending.cancel() }
        }
        success(client.create(f, key, input), 201, "createPostDraft")
        assertEquals(1, f.test.count("platform.post_drafts")); assertEquals(1, f.test.count("platform.outbox"))
    }

    @Test fun actualCioRejectsAmbiguousFramingThenCreatesCanonicalDraft() = runBlocking<Unit> {
        val f = Fixture(); val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { feedMeLocalService(local, postDraft = f.configuration()) }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port; val input = f.input(); val length = input.encodeToByteArray().size
            val raw = "POST /v1/post-drafts HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer ${f.token(f.test.account)}\r\nX-Device-Session: ${f.test.account.deviceSessionId}\r\nIdempotency-Key: ${uuid()}\r\nContent-Type: application/json\r\nContent-Length: $length\r\nContent-Length: $length\r\nConnection: close\r\n\r\n$input"
            val response = withContext(Dispatchers.IO) { Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000; socket.getOutputStream().write(raw.encodeToByteArray()); socket.getOutputStream().flush()
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            } }
            assertTrue(Regex("^HTTP/1\\.[01] 400 [^\\r\\n]+\\r\\n").containsMatchIn(response.take(100)))
            assertEquals(0, f.test.count("platform.post_drafts"))
            HttpClient(io.ktor.client.engine.cio.CIO).use { http ->
                success(http.post("http://127.0.0.1:$port/v1/post-drafts") { auth(f); header("Idempotency-Key", uuid()); contentType(ContentType.Application.Json); setBody(input) }, 201, "createPostDraft")
            }
            assertEquals(1, f.test.count("platform.post_drafts"))
        } finally { server.stop(gracePeriodMillis = 100, timeoutMillis = 1000) }
    }

    @Test fun defaultModuleKeepsAllDraftOperationsClosedWithoutVerifierOrStorageAccess() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local) }
        problem(client.create(f), 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.get("/v1/post-drafts"), 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.get("/v1/post-drafts/${uuid()}"), 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.patch("/v1/post-drafts/${uuid()}"), 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.delete("/v1/post-drafts/${uuid()}"), 503, "OPERATION_NOT_IMPLEMENTED")
        assertEquals(0, f.verifications); assertEquals(0, f.test.count("platform.post_drafts"))
    }

    @Test fun draftConfigurationDoesNotEnablePostPublicationFeedOrProtectedMediaDelivery() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postDraft = f.configuration()) }
        for (path in listOf("/v1/posts", "/v1/media", "/v1/media/${uuid()}/access")) problem(client.post(path), 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.get("/v1/posts/today"), 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.get("/v1/profiles/${uuid()}/plate"), 503, "OPERATION_NOT_IMPLEMENTED")
        assertEquals(0, f.verifications); assertEquals(0, f.test.count("platform.outbox"))
    }

    private class Fixture(maxBytes: Int = 65536) {
        val test = PostDraftTestFixture(cluster.database())
        val store = test.newStore(test.policy(max = maxBytes))
        val accounts = linkedMapOf<String, VerifiedSocialAccount>(); var verifications = 0
        var verify: (suspend (SocialHttpBearer) -> PortResult<VerifiedSocialAccount>)? = null
        fun token(actor: VerifiedSocialAccount) = "synthetic-${actor.deviceSessionId}".also { accounts[it] = actor }
        fun configuration() = PostDraftHttpConfiguration("test", store, SocialHttpVerifier { bearer ->
            verifications++; verify?.invoke(bearer) ?: bearer.token.use { accounts[it]?.let { actor -> PortResult.Value(actor) } ?: PortResult.Failure(FailureReason.UNAUTHENTICATED) }
        }, Dispatchers.IO)
        fun mediaConfiguration() = MediaHttpConfiguration("test", test.mediaStore, MediaHttpVerifier { bearer ->
            bearer.token.use { accounts[it]?.let { actor -> PortResult.Value(PostDraftTestFixture.mediaActor(actor)) } ?: PortResult.Failure(FailureReason.UNAUTHENTICATED) }
        }, Dispatchers.IO)
        fun input() = test.body(caption = "PRIVATE-owner-caption").toString()
    }

    companion object {
        @JvmField @ClassRule val timeout: Timeout = Timeout.seconds(300)
        private lateinit var cluster: PostgresTestCluster
        @JvmStatic @BeforeClass fun start() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun stop() { cluster.close() }
        private val local = LocalServerConfig.fromEnvironment(emptyMap())
        private val validator = ContractBodyValidator.bundled()
        private fun uuid() = UUID.randomUUID()
        private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
        private suspend fun json(reply: HttpResponse) = parse(reply.bodyAsText())
        private fun HttpRequestBuilder.auth(f: Fixture, actor: VerifiedSocialAccount = f.test.account) {
            header(HttpHeaders.Authorization, "Bearer ${f.token(actor)}"); header("X-Device-Session", actor.deviceSessionId)
        }
        private suspend fun HttpClient.create(f: Fixture, key: UUID = uuid(), body: String = f.input(), extras: HttpRequestBuilder.() -> Unit = {}) = post("/v1/post-drafts") {
            auth(f); header("Idempotency-Key", key); contentType(ContentType.Application.Json); setBody(body); extras()
        }
        private suspend fun HttpClient.edit(f: Fixture, id: String, body: String, key: UUID = uuid(), etag: String = "\"1\"") = patch("/v1/post-drafts/$id") {
            auth(f); header("Idempotency-Key", key); header(HttpHeaders.IfMatch, etag); contentType(ContentType.Application.Json); setBody(body)
        }
        private suspend fun HttpClient.remove(f: Fixture, id: String, key: UUID = uuid(), etag: String = "\"1\"") = delete("/v1/post-drafts/$id") {
            auth(f); header("Idempotency-Key", key); header(HttpHeaders.IfMatch, etag)
        }
        private suspend fun success(reply: HttpResponse, status: Int, operation: String) {
            val text = reply.bodyAsText(); assertEquals(status, reply.status.value, text); headers(reply)
            if (status == 204) { assertEquals("", text); assertNull(reply.headers[HttpHeaders.ETag]); return }
            assertEquals("application/json", reply.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            assertEquals(BodyValidationResult.Valid, validator.validateResponse(operation, status, text.encodeToByteArray(), "application/json"))
            if (operation == "listPostDrafts") assertTrue(Regex("\"[0-9]+\"").matches(reply.headers[HttpHeaders.ETag]!!))
            else assertEquals("\"${json(reply).text("version")}\"", reply.headers[HttpHeaders.ETag])
        }
        private suspend fun problem(reply: HttpResponse, status: Int, code: String) {
            val text = reply.bodyAsText(); assertEquals(status, reply.status.value, text); headers(reply); val body = parse(text)
            assertEquals(code, body.text("code")); assertEquals(status, body.getValue("status").jsonPrimitive.int)
            assertEquals(reply.headers["X-Trace-Id"], body.text("traceId")); assertNull(reply.headers[HttpHeaders.ETag])
            assertEquals("application/problem+json", reply.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            assertEquals(BodyValidationResult.Valid, validator.validateSchema("Problem", text.encodeToByteArray()))
            for (secret in listOf("PRIVATE-", "synthetic-", "quarantine/", "object-version")) assertFalse(text.contains(secret))
        }
        private fun headers(reply: HttpResponse) {
            assertEquals("no-store", reply.headers[HttpHeaders.CacheControl]); assertEquals("nosniff", reply.headers["X-Content-Type-Options"])
            assertNotNull(UUID.fromString(reply.headers["X-Trace-Id"])); assertNull(reply.headers[HttpHeaders.SetCookie])
        }
    }
}
