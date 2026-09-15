package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.PostgresTestCluster
import com.feedme.server.social.VerifiedSocialAccount
import com.feedme.server.social.drafts.PostDraftTestFixture
import com.feedme.server.social.posts.PostPublicationPolicy
import com.feedme.server.social.posts.PostPublicationTestFixture
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import java.net.Socket
import java.time.Duration
import java.time.Instant
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

/** Actual HTTP/SQL transactions; identity/content/media providers remain explicitly synthetic. */
class PostPublicationHttpIntegrationTest {
    @Test fun directTextPublishCreatesOneCanonicalPostWithoutImplicitServerDraft() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postPublication = f.configuration()) }
        val body = f.input(); val response = client.publish(f, body = body); success(response)
        val post = json(response)
        assertEquals(parse(body)["caption"], post["caption"])
        assertEquals(f.test.account.accountId.toString(), post["author"]!!.jsonObject.text("userId"))
        assertEquals(Duration.ofHours(24), Duration.between(Instant.parse(post.text("publishedAt")), Instant.parse(post.text("expiresAt"))))
        assertFalse(post["keepOnPlate"]!!.jsonPrimitive.boolean)
        assertEquals(1, f.test.count("social.posts")); assertEquals(1, f.test.count("social.post_publications"))
        assertEquals(0, f.test.count("platform.post_drafts")); assertEquals(1, f.test.count("platform.idempotency"))
        assertEquals(1, f.test.count("platform.outbox")); assertEquals(0, f.test.count("social.post_media"))
    }

    @Test fun savedReviewedDraftPublishesExactVersionAndBecomesObservableAsPublished() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postDraft = f.draftConfiguration(), postPublication = f.configuration()) }
        val selection = f.test.body(); val saved = f.test.drafts.create(selection)
        val body = JsonObject(selection + mapOf("draftId" to saved.getValue("id"), "draftVersion" to saved.getValue("version")))
        val response = client.publish(f, body = body.toString()); success(response)
        val read = client.get("/v1/post-drafts/${saved.text("id")}") { auth(f) }
        assertEquals(200, read.status.value, read.bodyAsText()); assertEquals("\"2\"", read.headers[HttpHeaders.ETag])
        val publishedDraft = json(read)
        assertEquals("published", publishedDraft.text("status")); assertEquals(json(response)["id"], publishedDraft["publishedPostId"])
        assertEquals(saved["caption"], publishedDraft["caption"]); assertEquals(saved["expiresAt"], publishedDraft["expiresAt"])
        assertEquals(1, f.test.count("social.posts")); assertEquals(2, f.test.count("platform.outbox"))
        assertEquals(BodyValidationResult.Valid, validator.validateResponse("getPostDraft", 200, read.bodyAsText().encodeToByteArray(), "application/json"))
    }

    @Test fun halfPairIsSemantic422AndNoPairCannotBypassAnExistingDraft() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postPublication = f.configuration()) }
        val selection = f.test.body()
        for (pair in listOf(mapOf("draftId" to JsonPrimitive(uuid().toString())), mapOf("draftVersion" to JsonPrimitive(1)))) {
            val input = JsonObject(selection + pair).toString()
            assertEquals(BodyValidationResult.Valid, validator.validateRequest("publishPost", input.encodeToByteArray(), "application/json"))
            problem(client.publish(f, body = input), 422, "INPUT_INVALID")
        }
        f.test.drafts.create(selection)
        problem(client.publish(f, body = selection.toString()), 409, "PUBLICATION_CONFLICT")
        assertEquals(0, f.test.count("social.posts")); assertEquals(0, f.test.count("social.post_publications"))
        assertEquals(1, f.test.count("platform.idempotency")); assertEquals(1, f.test.count("platform.outbox"))
    }

    @Test fun originalKeyReplaysButChangedBodyOrReplacementKeyCannotDuplicatePublication() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postPublication = f.configuration()) }
        val key = uuid(); val input = f.input(); val original = client.publish(f, key, input); success(original)
        val replay = client.publish(f, key, input); success(replay)
        assertEquals(json(original), json(replay)); assertEquals(original.headers[HttpHeaders.ETag], replay.headers[HttpHeaders.ETag])
        val changed = JsonObject(parse(input) + ("caption" to JsonPrimitive("Different reviewed choice"))).toString()
        problem(client.publish(f, key, changed), 409, "IDEMPOTENCY_MISMATCH")
        problem(client.publish(f, body = input), 409, "PUBLICATION_CONFLICT")
        assertEquals(1, f.test.count("social.posts")); assertEquals(1, f.test.count("platform.idempotency")); assertEquals(1, f.test.count("platform.outbox"))
    }

    @Test fun savedDraftRequiresCurrentVersionExactOptionalFieldsAndUnexpiredState() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postPublication = f.configuration()) }
        val selection = f.test.body(); val reviewed = f.test.saved(selection)
        problem(client.publish(f, body = JsonObject(reviewed + ("draftVersion" to JsonPrimitive(99))).toString()), 412, "VERSION_CONFLICT")
        problem(client.publish(f, body = JsonObject(reviewed + ("altText" to JsonPrimitive(""))).toString()), 409, "PUBLICATION_CONFLICT")
        f.test.drafts.expire(UUID.fromString(reviewed.text("draftId")))
        problem(client.publish(f, body = reviewed.toString()), 410, "DRAFT_EXPIRED")
        problem(client.publish(f, body = selection.toString()), 409, "PUBLICATION_CONFLICT")
        assertEquals(0, f.test.count("social.posts")); assertEquals(1, f.test.count("platform.idempotency"))
    }

    @Test fun readyPhotoPublishRetainsSelectedMediaAndDiscardsOnlyUnusedUploads() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postPublication = f.configuration(), media = f.mediaConfiguration()) }
        val root = f.test.client(); val unused = f.test.drafts.prepare(root)
        val selected = f.test.readyPhoto(root)
        val body = JsonObject(f.test.body(root) + ("mediaIds" to buildJsonArray { add(selected.toString()) })).toString()
        val key = uuid(); f.test.authority.mediaSafe = false
        problem(client.publish(f, key, body), 403, "FORBIDDEN")
        assertEquals(0, f.test.count("social.posts"))
        assertEquals("awaitingUpload", f.test.value("SELECT state FROM platform.media_assets WHERE id='${unused.text("id")}'"))
        f.test.authority.mediaSafe = true
        val original = client.publish(f, key, body); success(original)
        assertEquals("ready", f.test.value("SELECT state FROM platform.media_assets WHERE id='$selected'"))
        assertEquals("deleted", f.test.value("SELECT state FROM platform.media_assets WHERE id='${unused.text("id")}'"))
        assertEquals(1, f.test.count("social.post_media")); assertEquals(1, f.test.count("social.post_publication_discard_media"))
        val replay = client.publish(f, key, body); success(replay); assertEquals(json(original), json(replay))
        val uploadBody = f.test.mediaFixture.prepareBody(PostDraftTestFixture.mediaActor(f.test.account), root)
        val denied = client.post("/v1/media") { auth(f); header("Idempotency-Key", uuid()); contentType(ContentType.Application.Json); setBody(uploadBody.toString()) }
        problem(denied, 409, "DRAFT_UNAVAILABLE")
        assertEquals(2, f.test.count("platform.media_assets"))
    }

    @Test fun unknownCommitIsRecoveredOnlyByTheExactOriginalPublish() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postPublication = f.configuration()) }
        val key = uuid(); val input = f.input(); f.test.faults.loseCommit = true
        problem(client.publish(f, key, input), 503, "OUTCOME_UNKNOWN")
        success(client.publish(f, key, input))
        problem(client.publish(f, body = input), 409, "PUBLICATION_CONFLICT")
        assertEquals(1, f.test.count("social.posts")); assertEquals(1, f.test.count("social.post_publications"))
        assertEquals(1, f.test.count("platform.idempotency")); assertEquals(1, f.test.count("platform.outbox"))
    }

    @Test fun currentDeviceRevocationDeniesNewPublishAndOriginalReceiptDisclosure() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postPublication = f.configuration()) }
        val key = uuid(); val input = f.input(); success(client.publish(f, key, input))
        val next = f.input(); f.test.sql("UPDATE cooking_test.sessions SET active=false")
        problem(client.publish(f, key, input), 401, "UNAUTHENTICATED")
        problem(client.publish(f, body = next), 401, "UNAUTHENTICATED")
        assertEquals(1, f.test.count("social.posts")); assertEquals(1, f.test.count("platform.outbox"))
    }

    @Test fun malformedIngressAndIfMatchCannotCreateAnyPublicationEffects() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postPublication = f.configuration()) }
        val input = f.input()
        for (body in listOf(input.dropLast(1) + ",\"caption\":\"duplicate\"}", " ".repeat(65537), "{\"caption\":\"\\uD800\"}"))
            problem(client.publish(f, body = body), 400, "INVALID_REQUEST")
        problem(client.publish(f, body = "{}"), 422, "INPUT_INVALID")
        problem(client.publish(f, body = input, extras = { header(HttpHeaders.IfMatch, "\"1\"") }), 400, "INVALID_REQUEST")
        problem(client.publish(f, body = input, extras = { parameter("draftVersion", "1") }), 400, "INVALID_REQUEST")
        problem(client.post("/v1/posts") { auth(f); contentType(ContentType.Application.Json); setBody(input) }, 400, "INVALID_REQUEST")
        assertEquals(0, f.test.count("social.posts")); assertEquals(0, f.test.count("platform.idempotency")); assertEquals(0, f.test.count("platform.outbox"))
    }

    @Test fun responseBudgetAndUnavailableOrMismatchedVerifierNeverPartiallyPublish() = testApplication {
        val f = Fixture(1); application { feedMeLocalService(local, postPublication = f.configuration()) }
        problem(client.publish(f), 422, "RESPONSE_TOO_LARGE")
        f.verify = { error("PRIVATE-caption-provider-token") }
        problem(client.publish(f), 503, "AUTHENTICATION_UNAVAILABLE")
        f.verify = { PortResult.Value(VerifiedSocialAccount("other", f.test.account.accountId, it.deviceSessionId)) }
        problem(client.publish(f), 401, "UNAUTHENTICATED")
        f.verify = { PortResult.Value(VerifiedSocialAccount("test", f.test.account.accountId, uuid())) }
        problem(client.publish(f), 401, "UNAUTHENTICATED")
        assertEquals(0, f.test.count("social.posts")); assertEquals(0, f.test.count("social.post_publications"))
        assertEquals(0, f.test.count("platform.idempotency")); assertEquals(0, f.test.count("platform.outbox"))
    }

    @Test fun callerCancellationAfterCommitDoesNotRollBackOrCreateAReplacementPost() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postPublication = f.configuration()) }
        val key = uuid(); val input = f.input(); val committed = CountDownLatch(1); val release = CountDownLatch(1)
        f.test.faults.afterCommit = { committed.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        coroutineScope {
            val pending = async { client.publish(f, key, input) }
            try {
                assertTrue(withContext(Dispatchers.IO) { committed.await(10, TimeUnit.SECONDS) })
                pending.cancel(); release.countDown(); assertFailsWith<CancellationException> { pending.await() }
            } finally { release.countDown(); pending.cancel() }
        }
        success(client.publish(f, key, input))
        assertEquals(1, f.test.count("social.posts")); assertEquals(1, f.test.count("platform.outbox"))
    }

    @Test fun actualCioRejectsAmbiguousFramingThenPublishesOnce() = runBlocking<Unit> {
        val f = Fixture(); val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            feedMeLocalService(local, postPublication = f.configuration())
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port; val input = f.input(); val length = input.encodeToByteArray().size
            val raw = "POST /v1/posts HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer ${f.token(f.test.account)}\r\nX-Device-Session: ${f.test.account.deviceSessionId}\r\nIdempotency-Key: ${uuid()}\r\nContent-Type: application/json\r\nContent-Length: $length\r\nContent-Length: $length\r\nConnection: close\r\n\r\n$input"
            val response = withContext(Dispatchers.IO) { Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000; socket.getOutputStream().write(raw.encodeToByteArray()); socket.getOutputStream().flush()
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            } }
            assertTrue(Regex("^HTTP/1\\.[01] 400 [^\\r\\n]+\\r\\n").containsMatchIn(response.take(100)))
            assertEquals(0, f.test.count("social.posts"))
            HttpClient(io.ktor.client.engine.cio.CIO).use { http ->
                success(http.post("http://127.0.0.1:$port/v1/posts") {
                    auth(f); header("Idempotency-Key", uuid()); contentType(ContentType.Application.Json); setBody(input)
                })
            }
            assertEquals(1, f.test.count("social.posts"))
        } finally { server.stop(gracePeriodMillis = 100, timeoutMillis = 1000) }
    }

    @Test fun publicationCompositionDoesNotImplicitlyEnableFeedsDraftsOrMediaDelivery() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, postPublication = f.configuration()) }
        for (path in listOf("/v1/post-drafts", "/v1/posts/today", "/v1/posts/${uuid()}", "/v1/profiles/${uuid()}/plate"))
            problem(client.get(path), 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.post("/v1/media"), 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.post("/v1/media/${uuid()}/access"), 503, "OPERATION_NOT_IMPLEMENTED")
        assertEquals(0, f.verifications); assertEquals(0, f.test.count("social.posts"))
    }

    private class Fixture(maxBytes: Int = 65536) {
        val test = PostPublicationTestFixture(cluster.database())
        val store = test.newStore(PostPublicationPolicy(maxBytes))
        private val accounts = linkedMapOf<String, VerifiedSocialAccount>()
        var verifications = 0
        var verify: (suspend (SocialHttpBearer) -> PortResult<VerifiedSocialAccount>)? = null
        fun token(actor: VerifiedSocialAccount) = "synthetic-${actor.deviceSessionId}".also { accounts[it] = actor }
        private fun verifier() = SocialHttpVerifier { bearer ->
            verifications++; verify?.invoke(bearer) ?: bearer.token.use {
                accounts[it]?.let { actor -> PortResult.Value(actor) } ?: PortResult.Failure(FailureReason.UNAUTHENTICATED)
            }
        }
        fun configuration() = PostPublicationHttpConfiguration("test", store, verifier(), Dispatchers.IO)
        fun draftConfiguration() = PostDraftHttpConfiguration("test", test.drafts.store, verifier(), Dispatchers.IO)
        fun mediaConfiguration() = MediaHttpConfiguration("test", test.drafts.mediaStore, MediaHttpVerifier { bearer ->
            bearer.token.use { accounts[it]?.let { actor -> PortResult.Value(PostDraftTestFixture.mediaActor(actor)) }
                ?: PortResult.Failure(FailureReason.UNAUTHENTICATED) }
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
        private suspend fun HttpClient.publish(f: Fixture, key: UUID = uuid(), body: String = f.input(), extras: HttpRequestBuilder.() -> Unit = {}) = post("/v1/posts") {
            auth(f); header("Idempotency-Key", key); contentType(ContentType.Application.Json); setBody(body); extras()
        }
        private suspend fun success(reply: HttpResponse) {
            val text = reply.bodyAsText(); assertEquals(201, reply.status.value, text); headers(reply)
            assertEquals("application/json", reply.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            assertEquals(BodyValidationResult.Valid, validator.validateResponse("publishPost", 201, text.encodeToByteArray(), "application/json"))
            assertEquals("\"${json(reply).text("version")}\"", reply.headers[HttpHeaders.ETag])
        }
        private suspend fun problem(reply: HttpResponse, status: Int, code: String) {
            val text = reply.bodyAsText(); assertEquals(status, reply.status.value, text); headers(reply); val body = parse(text)
            assertEquals(code, body.text("code")); assertEquals(status, body["status"]!!.jsonPrimitive.int)
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
