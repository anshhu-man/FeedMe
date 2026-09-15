package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.cooking.*
import com.feedme.server.db.*
import com.feedme.server.planning.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import java.net.URI
import java.time.Duration
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

/** Real HTTP and PostgreSQL; identities, review, current catalog and input evidence are TEST ONLY. */
class CookingHttpIntegrationTest {
    @Test fun createGetAndPinnedPlanReturnActualCanonicalStateWithoutGetEffects() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, cooking = f.configuration(), planning = f.planning()) }
        problem(client.get("/v1/cook-sessions/" + UUID.randomUUID()) { auth(f) }, 404, "COOK_SESSION_UNAVAILABLE")
        assertEquals(0, f.test.count("cooking.cook_sessions"))
        val created = client.start(f); success(created, 201, "createCookSession")
        val body = json(created); val id = body.string("id")
        assertEquals(f.plan.string("id"), body.string("planId")); assertEquals("mix", body.string("currentStepId"))
        assertEquals(0, body.getValue("deviceSequence").jsonPrimitive.int)
        val before = f.effects()
        val read = client.get("/v1/cook-sessions/" + id) { auth(f) }; success(read, 200, "getCookSession")
        assertEquals(body, json(read)); assertEquals("\"1\"", read.headers[HttpHeaders.ETag])
        val plan = client.get("/v1/plans/" + body.string("planId")) { auth(f) }; success(plan, 200, "getPlan")
        assertEquals(f.plan, json(plan)); assertEquals(before, f.effects())
    }

    @Test fun createReplaysExactCurrentStateAndNeverRebasesAnOldCommand() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, cooking = f.configuration()) }
        val key = UUID.randomUUID(); val first = client.start(f, key)
        same(first, client.start(f, key), 201, "createCookSession")
        problem(client.start(f, key, body = f.startBody(4)), 409, "IDEMPOTENCY_MISMATCH")
        val id = json(first).string("id")
        success(client.progress(f, id), 200, "updateCookSession")
        problem(client.start(f, key), 412, "VERSION_CONFLICT")
        assertEquals(listOf(1, 2, 2), f.effects())
    }

    @Test fun progressAndStatusOnlyCompletionPreserveExactStepsTimersNotesAndDatabaseTime() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, cooking = f.configuration()) }
        val id = json(client.start(f)).string("id")
        val timer = buildJsonObject {
            put("timerId", UUID.randomUUID().toString()); put("stepId", "mix"); put("status", "paused")
            put("durationSeconds", 90); put("pausedRemainingSeconds", 37)
        }
        val changed = patchBody(1) + mapOf("currentStepId" to JsonPrimitive("serve"),
            "completedStepIds" to CookingTestFixture.arr("mix"), "timers" to JsonArray(listOf(timer)),
            "personalNotes" to buildJsonArray { add(buildJsonObject { put("text", "PRIVATE-NOTE"); put("label", "myNote") }) })
        val key = UUID.randomUUID(); val patch = client.progress(f, id, key, body = JsonObject(changed).toString())
        success(patch, 200, "updateCookSession"); same(patch, client.progress(f, id, key, body = JsonObject(changed).toString()), 200, "updateCookSession")
        val completionBody = JsonObject(completeBody(2) + ("finishedAtClient" to JsonPrimitive("2000-01-01T00:00:00Z"))).toString()
        val done = client.finish(f, id, body = completionBody); success(done, 200, "completeCookSession")
        val result = json(done); assertEquals("completed", result.string("status")); assertEquals("\"3\"", done.headers[HttpHeaders.ETag])
        for (field in listOf("currentStepId", "completedStepIds", "timers", "personalNotes"))
            assertEquals(json(patch)[field], result[field])
        assertEquals(result["updatedAt"], result["completedAt"]); assertNotEquals("2000-01-01T00:00:00Z", result.string("completedAt"))
        assertFalse(f.test.value("SELECT string_agg(payload::text,'') FROM platform.outbox").contains("PRIVATE-NOTE"))
        assertEquals(1, f.test.value("SELECT count(*) FROM platform.outbox WHERE event_type='cooking.session.completed.v1'").toInt())
    }

    @Test fun completionIsOnceOnlyAndMakeAgainCannotBeSilentlyIgnored() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, cooking = f.configuration()) }
        val id = json(client.start(f)).string("id"); val before = f.effects()
        problem(client.finish(f, id, body = completeBody(1, true).toString()), 503, "NOT_CONFIGURED")
        assertEquals(before, f.effects())
        val key = UUID.randomUUID(); val first = client.finish(f, id, key)
        same(first, client.finish(f, id, key), 200, "completeCookSession")
        problem(client.finish(f, id), 409, "TERMINAL_CONFLICT")
        problem(client.progress(f, id, etag = "\"2\"", body = patchBody(2).toString()), 409, "TERMINAL_CONFLICT")
        assertEquals(listOf(1, 2, 2), f.effects())
    }

    @Test fun verifiedGuestAndAccountCannotReadOrStartEachOthersPlans() = testApplication {
        val f = Fixture(); val guest = f.test.principal(CommandActor.GUEST, f.test.account.principalId)
        val guestPlan = f.test.seedPlan(guest); application { feedMeLocalService(local, cooking = f.configuration()) }
        val accountSession = json(client.start(f)).string("id")
        problem(client.get("/v1/cook-sessions/" + accountSession) { auth(f, guest) }, 404, "COOK_SESSION_UNAVAILABLE")
        problem(client.start(f, actor = guest), 404, "PLAN_UNAVAILABLE")
        val guestStart = client.start(f, body = buildJsonObject { put("planId", guestPlan.string("id")) }.toString(), actor = guest)
        success(guestStart, 201, "createCookSession")
        problem(client.get("/v1/cook-sessions/" + json(guestStart).string("id")) { auth(f) }, 404, "COOK_SESSION_UNAVAILABLE")
        problem(client.get("/v1/cook-sessions/" + accountSession) { auth(f, guest); header("X-Device-Session", f.test.account.deviceSessionId) }, 401, "UNAUTHENTICATED")
        assertEquals(2, f.test.count("cooking.cook_sessions"))
    }

    @Test fun secondDeviceRequiresFreshVersionAndNextAggregateSequence() = testApplication {
        val f = Fixture(); val other = f.test.secondDevice(); application { feedMeLocalService(local, cooking = f.configuration()) }
        val id = json(client.start(f)).string("id")
        val key = UUID.randomUUID(); success(client.progress(f, id, key), 200, "updateCookSession")
        problem(client.progress(f, id, key, actor = other), 409, "COMMAND_CONFLICT")
        problem(client.progress(f, id, actor = other), 412, "VERSION_CONFLICT")
        problem(client.finish(f, id, actor = other), 409, "SEQUENCE_CONFLICT")
        success(client.progress(f, id, etag = "\"2\"", body = patchBody(2).toString(), actor = other), 200, "updateCookSession")
        success(client.finish(f, id, body = completeBody(3).toString()), 200, "completeCookSession")
        assertEquals(2, f.test.count("cooking.device_cursors")); assertEquals(4, f.test.count("cooking.step_events"))
    }

    @Test fun revocationGuestExpiryAndChangedInputAreCheckedBeforeNewOrCachedDisclosure() = testApplication {
        val f = Fixture(); val guest = f.test.principal(CommandActor.GUEST)
        application { feedMeLocalService(local, cooking = f.configuration()) }
        val key = UUID.randomUUID(); val first = client.start(f, key); val id = json(first).string("id")
        f.test.changeEvidence { e ->
            JsonObject(e + ("preferences" to JsonObject(e.getValue("preferences").jsonObject + ("revision" to JsonPrimitive("2")))))
        }
        problem(client.start(f), 409, "INPUTS_CHANGED")
        // An owned historical pin is not a request to rerank or replace the recipe.
        success(client.get("/v1/cook-sessions/" + id) { auth(f) }, 200, "getCookSession")
        f.test.sql("UPDATE cooking_test.sessions SET active=false WHERE session_id='" + f.test.account.deviceSessionId + "'")
        problem(client.start(f, key), 401, "UNAUTHENTICATED")
        problem(client.get("/v1/cook-sessions/" + id) { auth(f) }, 401, "UNAUTHENTICATED")
        f.test.sql("UPDATE cooking_test.sessions SET expires_at=clock_timestamp()-interval '1 second' WHERE session_id='" + guest.guestSessionId + "'")
        problem(client.start(f, actor = guest), 401, "UNAUTHENTICATED")
    }

    @Test fun retirementPreservesOwnedPinsButRecallDeniesTheirBodiesAndMutations() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, cooking = f.configuration(), planning = f.planning()) }
        val key = UUID.randomUUID(); val created = client.start(f, key); val id = json(created).string("id")
        f.test.changeRecipe { JsonObject(it + mapOf("version" to JsonPrimitive(2), "reviewStatus" to JsonPrimitive("retired"))) }
        success(client.get("/v1/cook-sessions/" + id) { auth(f) }, 200, "getCookSession")
        problem(client.start(f), 404, "RECIPE_UNAVAILABLE")
        same(created, client.start(f, key), 201, "createCookSession")
        f.test.changeRecipe { JsonObject(it + mapOf("version" to JsonPrimitive(3), "reviewStatus" to JsonPrimitive("recalled"),
            "recallReasonCode" to JsonPrimitive("safetyReview"))) }
        problem(client.get("/v1/cook-sessions/" + id) { auth(f) }, 409, "RECIPE_RECALLED")
        problem(client.start(f, key), 409, "RECIPE_RECALLED")
        problem(client.progress(f, id), 409, "RECIPE_RECALLED")
        problem(client.get("/v1/plans/" + f.plan.string("id")) { auth(f) }, 409, "RECIPE_RECALLED")
        assertEquals(listOf(1, 1, 1), f.effects())
    }

    @Test fun exactOwnedPinExtendsHistoricalPlanReadNotNewStartAndNotBeyondSessionExpiry() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, cooking = f.configuration(), planning = f.planning()) }
        val id = json(client.start(f)).string("id"); f.test.expirePlans()
        success(client.get("/v1/cook-sessions/" + id) { auth(f) }, 200, "getCookSession")
        val plan = client.get("/v1/plans/" + f.plan.string("id")) { auth(f) }; success(plan, 200, "getPlan"); assertEquals(f.plan, json(plan))
        problem(client.start(f), 410, "PLAN_EXPIRED")
        f.test.expireSession(UUID.fromString(id))
        problem(client.get("/v1/cook-sessions/" + id) { auth(f) }, 410, "SESSION_EXPIRED")
        problem(client.get("/v1/plans/" + f.plan.string("id")) { auth(f) }, 410, "PLAN_EXPIRED")
    }

    @Test fun malformedBodiesAndUndeclaredControlsHaveZeroCookingEffects() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, cooking = f.configuration()) }
        for ((body, status) in listOf("{" to 400, "{}" to 422, " ".repeat(65537) to 400,
            (f.startBody().dropLast(1) + ",\"deviceSequence\":0,\"deviceSequence\":1}") to 400,
            (f.startBody().dropLast(1) + ",\"recipeSnapshot\":{}}") to 422,
            (f.startBody().dropLast(1) + ",\"unknown\":\"\\uD800\"}") to 400))
            problem(client.start(f, body = body), status, if (status == 422) "INPUT_INVALID" else "INVALID_REQUEST")
        problem(client.post("/v1/cook-sessions?ownerId=private") { auth(f); header("Idempotency-Key", UUID.randomUUID()); contentType(ContentType.Application.Json); setBody(f.startBody()) }, 400, "INVALID_REQUEST")
        problem(client.start(f, extras = { header(HttpHeaders.IfMatch, "\"1\"") }), 400, "INVALID_REQUEST")
        assertEquals(listOf(0, 0, 0), f.effects())
    }

    @Test fun completionHasNoConditionalHeaderAndPatchCannotSkipItsPrecondition() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, cooking = f.configuration()) }
        val id = json(client.start(f)).string("id")
        problem(client.patch("/v1/cook-sessions/" + id) { auth(f); header("Idempotency-Key", UUID.randomUUID()); contentType(ContentType.Application.Json); setBody(patchBody(1).toString()) }, 428, "PRECONDITION_REQUIRED")
        problem(client.finish(f, id, extras = { header(HttpHeaders.IfMatch, "\"1\"") }), 400, "INVALID_REQUEST")
        problem(client.get("/v1/cook-sessions/" + id) { auth(f); header(HttpHeaders.IfNoneMatch, "\"1\"") }, 400, "INVALID_REQUEST")
        assertEquals(listOf(1, 1, 1), f.effects())
    }

    @Test fun responseBoundIsEnforcedBeforeSessionReceiptOrOutboxCommits() = testApplication {
        val f = Fixture(maxBytes = 128); application { feedMeLocalService(local, cooking = f.configuration()) }
        problem(client.start(f), 422, "RESPONSE_TOO_LARGE")
        assertEquals(listOf(0, 0, 0), f.effects()); assertEquals(0, f.test.count("cooking.device_cursors"))
    }

    @Test fun outboxFailureRollsBackButLostCommitReceiptRequiresOriginalIdentity() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, cooking = f.configuration()) }
        val key = UUID.randomUUID(); f.test.faults.outboxFailure = true
        problem(client.start(f, key), 503, "STORAGE_UNAVAILABLE"); assertEquals(listOf(0, 0, 0), f.effects())
        f.test.faults.loseCommit = true
        problem(client.start(f, key), 503, "OUTCOME_UNKNOWN"); assertEquals(listOf(1, 1, 1), f.effects())
        val replay = client.start(f, key); success(replay, 201, "createCookSession")
        assertEquals(listOf(1, 1, 1), f.effects())
    }

    @Test fun cancelledCallerAfterCommitCannotUndoOrDuplicateSession() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, cooking = f.configuration()) }
        val committed = CountDownLatch(1); val release = CountDownLatch(1); val key = UUID.randomUUID()
        f.test.faults.afterCommit = { committed.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        coroutineScope {
            val pending = async { client.start(f, key) }
            try {
                assertTrue(withContext(Dispatchers.IO) { committed.await(10, TimeUnit.SECONDS) })
                pending.cancel(); release.countDown()
                assertFailsWith<CancellationException> { pending.await() }
            } finally { release.countDown(); pending.cancel() }
        }
        success(client.start(f, key), 201, "createCookSession")
        assertEquals(listOf(1, 1, 1), f.effects())
    }

    @Test fun verifierFailuresAreSanitizedWithRetryMetadataAndNoDomainEffects() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, cooking = f.configuration()) }
        f.verify = { PortResult.Failure(FailureReason.RATE_LIMITED, 7) }
        val limited = client.start(f); problem(limited, 429, "RATE_LIMITED"); assertEquals("7", limited.headers[HttpHeaders.RetryAfter])
        f.verify = { error("PRIVATE-VERIFIER-DETAIL") }
        problem(client.start(f), 503, "AUTHENTICATION_UNAVAILABLE"); assertEquals(listOf(0, 0, 0), f.effects())
    }

    @Test fun actualCioSocketAcceptsCanonicalCreateAndRejectsDuplicateAuthorization() = runBlocking {
        val f = Fixture()
        val server = embeddedServer(CIO, port = 0, host = local.host) { feedMeLocalService(local, cooking = f.configuration()) }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            fun send(duplicate: Boolean): java.net.http.HttpResponse<String> {
                val request = java.net.http.HttpRequest.newBuilder(URI("http://127.0.0.1:" + port + "/v1/cook-sessions"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + f.token(f.test.account))
                    .header("X-Device-Session", f.test.account.deviceSessionId.toString()).header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(f.startBody()))
                if (duplicate) request.header("Authorization", "Bearer " + f.token(f.test.account))
                return http.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            }
            val good = send(false); assertEquals(201, good.statusCode())
            assertEquals(BodyValidationResult.Valid, validator.validateResponse("createCookSession", 201, good.body().encodeToByteArray(), "application/json"))
            val bad = send(true); assertEquals(400, bad.statusCode()); assertEquals("INVALID_REQUEST", parse(bad.body()).string("code"))
            assertEquals(listOf(1, 1, 1), f.effects())
        } finally { server.stop(gracePeriodMillis = 100, timeoutMillis = 1000) }
    }

    @Test fun defaultServiceAndUnrelatedProductOperationsStayExplicitlyUnavailable() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local) }
        problem(client.start(f), 503, "OPERATION_NOT_IMPLEMENTED")
        assertEquals(0, f.verifications); assertEquals(listOf(0, 0, 0), f.effects())
    }

    @Test fun configuredCookingDoesNotEnableSaveShareAuthOrTimerEndpoints() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, cooking = f.configuration()) }
        for (path in listOf("/v1/saved-recipes", "/v1/posts"))
            problem(client.post(path), 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.get("/v1/preferences"), 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.post("/v1/timers"), 404, "ROUTE_NOT_FOUND")
        assertEquals(0, f.verifications); assertEquals(listOf(0, 0, 0), f.effects())
    }

    private class Fixture(maxBytes: Int = 65536) {
        val test = CookingTestFixture(cluster.database()); val plan = test.seedPlan()
        val store = if (maxBytes == 65536) test.store else test.newStore(maxBytes)
        private val actors = linkedMapOf<String, VerifiedCookingPrincipal>()
        var verifications = 0
        var verify: (suspend (CookingHttpBearer) -> PortResult<VerifiedCookingPrincipal>)? = null
        fun token(actor: VerifiedCookingPrincipal): String {
            val token = "synthetic-" + (actor.deviceSessionId ?: actor.guestSessionId).toString()
            actors[token] = actor; return token
        }
        fun configuration() = CookingHttpConfiguration("test", store, CookingHttpVerifier { bearer ->
            verifications++; verify?.invoke(bearer) ?: bearer.token.use { token ->
                actors[token]?.let { PortResult.Value(it) } ?: PortResult.Failure(FailureReason.UNAUTHENTICATED)
            }
        }, Dispatchers.IO)
        fun planning() = PlanningHttpConfiguration("test", test.plans, PlanningHttpVerifier { bearer ->
            bearer.token.use { token -> actors[token]?.let { PortResult.Value(test.planningActor(it)) } ?: PortResult.Failure(FailureReason.UNAUTHENTICATED) }
        }, Dispatchers.IO)
        fun startBody(sequence: Long? = null) = buildJsonObject { put("planId", plan.string("id")); sequence?.let { put("deviceSequence", it) } }.toString()
        fun effects() = listOf(test.count("cooking.cook_sessions"), test.count("cooking.step_events"),
            test.value("SELECT count(*) FROM platform.outbox WHERE event_type LIKE 'cooking.%'").toInt())
    }
    companion object {
        @JvmField @ClassRule val timeout: Timeout = Timeout.seconds(180)
        private lateinit var cluster: PostgresTestCluster
        @JvmStatic @BeforeClass fun start() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun stop() { cluster.close() }
        private val local = LocalServerConfig.fromEnvironment(emptyMap())
        private val validator = ContractBodyValidator.bundled()
        private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
        private suspend fun json(response: HttpResponse) = parse(response.bodyAsText())
        private fun patchBody(sequence: Long) = buildJsonObject { put("deviceSequence", sequence); put("currentStepId", "serve") }
        private fun completeBody(sequence: Long, makeAgain: Boolean = false) = buildJsonObject { put("deviceSequence", sequence); put("makeAgain", makeAgain) }
        private fun HttpRequestBuilder.auth(f: Fixture, actor: VerifiedCookingPrincipal = f.test.account) {
            header(HttpHeaders.Authorization, "Bearer " + f.token(actor))
            actor.deviceSessionId?.let { header("X-Device-Session", it) }
        }
        private suspend fun HttpClient.start(f: Fixture, key: UUID = UUID.randomUUID(), body: String = f.startBody(),
            actor: VerifiedCookingPrincipal = f.test.account, extras: HttpRequestBuilder.() -> Unit = {}) =
            post("/v1/cook-sessions") { auth(f, actor); header("Idempotency-Key", key); contentType(ContentType.Application.Json); setBody(body); extras() }
        private suspend fun HttpClient.progress(f: Fixture, id: String, key: UUID = UUID.randomUUID(), etag: String = "\"1\"",
            body: String = patchBody(1).toString(), actor: VerifiedCookingPrincipal = f.test.account) =
            patch("/v1/cook-sessions/" + id) { auth(f, actor); header("Idempotency-Key", key); header(HttpHeaders.IfMatch, etag); contentType(ContentType.Application.Json); setBody(body) }
        private suspend fun HttpClient.finish(f: Fixture, id: String, key: UUID = UUID.randomUUID(), body: String = completeBody(1).toString(),
            actor: VerifiedCookingPrincipal = f.test.account, extras: HttpRequestBuilder.() -> Unit = {}) =
            post("/v1/cook-sessions/" + id + "/complete") { auth(f, actor); header("Idempotency-Key", key); contentType(ContentType.Application.Json); setBody(body); extras() }
        private suspend fun same(first: HttpResponse, second: HttpResponse, status: Int, operation: String) {
            success(first, status, operation); success(second, status, operation)
            assertEquals(json(first), json(second)); assertEquals(first.headers[HttpHeaders.ETag], second.headers[HttpHeaders.ETag])
        }
        private suspend fun success(response: HttpResponse, status: Int, operation: String) {
            val text = response.bodyAsText(); assertEquals(status, response.status.value, text); headers(response)
            assertEquals("application/json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            assertEquals(BodyValidationResult.Valid, validator.validateResponse(operation, status, text.encodeToByteArray(), "application/json"))
            assertEquals("\"" + json(response).getValue("version").jsonPrimitive.content + "\"", response.headers[HttpHeaders.ETag])
        }
        private suspend fun problem(response: HttpResponse, status: Int, code: String) {
            val text = response.bodyAsText(); assertEquals(status, response.status.value, text); headers(response)
            val body = parse(text); assertEquals(code, body.string("code")); assertEquals(status, body.getValue("status").jsonPrimitive.int)
            assertEquals(response.headers["X-Trace-Id"], body.string("traceId")); assertNull(response.headers[HttpHeaders.ETag])
            assertEquals("application/problem+json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            assertEquals(BodyValidationResult.Valid, validator.validateSchema("Problem", text.encodeToByteArray()))
            for (canary in listOf("synthetic-", "PRIVATE-", "Synthetic reviewed instruction")) assertFalse(text.contains(canary))
        }
        private fun headers(response: HttpResponse) {
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl]); assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertNotNull(UUID.fromString(response.headers["X-Trace-Id"])); assertNull(response.headers[HttpHeaders.SetCookie])
        }
    }
}
