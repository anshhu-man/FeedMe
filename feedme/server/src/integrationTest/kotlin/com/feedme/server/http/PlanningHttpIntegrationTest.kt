package com.feedme.server.http

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.planning.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import javax.sql.DataSource
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/** Actual Ktor ingress + PostgreSQL transactions. Tokens/catalog/profile facts are TEST ONLY. */
class PlanningHttpIntegrationTest {
    @Test fun configuredCreateReadAndExplanationReturnCanonicalPrivateResponses() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }
        val created = client.create(f); success(created, 201, "createPlan")
        val plan = objectBody(created); assertEquals("ready", plan.string("status")); assertEquals("\"1\"", created.headers[HttpHeaders.ETag])
        val read = client.get("/v1/plans/${plan.string("id")}") { account(f) }
        success(read, 200, "getPlan"); assertEquals(plan, objectBody(read))
        val reason = client.get("/v1/plans/${plan.string("id")}/explanation?limit=1") { account(f) }
        success(reason, 200, "getPlanExplanation"); assertNull(reason.headers[HttpHeaders.ETag])
        val first = objectBody(reason); assertEquals(1, first.getValue("items").jsonArray.size)
        val next = first["nextCursor"]?.jsonPrimitive?.contentOrNull
        assertNotNull(next)
        val second = client.get("/v1/plans/${plan.string("id")}/explanation") { account(f); parameter("cursor", next); parameter("limit", "1") }
        success(second, 200, "getPlanExplanation"); assertNotEquals(first["items"], objectBody(second)["items"])
        assertEquals(1, f.count("planning.plans")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun sameKeyBodyReplayIsExactButChangedBodyCannotCreateAnotherPlan() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }; val key = UUID.randomUUID()
        val first = client.create(f, key); val replay = client.create(f, key)
        success(replay, 201, "createPlan"); assertEquals(first.bodyAsText(), replay.bodyAsText())
        problem(client.create(f, key, request().toString().replace("\"servings\":1", "\"servings\":2")), 409, "IDEMPOTENCY_MISMATCH")
        assertEquals(1, f.count("planning.plans")); assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun durableAlternativesUseCanonicalNoIfMatchBodyAndDoNotAdvanceTwiceOnRetry() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }
        val first = objectBody(client.create(f)); val key = UUID.randomUUID(); val path = "/v1/plans/${first.string("id")}/alternatives"
        val body = buildJsonObject { put("reason", "alternative"); put("constraints", first.getValue("constraints")); put("continuationCursor", first.getValue("nextAlternativeCursor")); put("excludeRecipeVersionIds", arr(first.string("recipeVersionId"))) }.toString()
        suspend fun next() = client.post(path) { account(f); header("Idempotency-Key", key); contentType(ContentType.Application.Json); setBody(body) }
        val child = next(); success(child, 200, "nextPlan"); val replay = next(); assertEquals(child.bodyAsText(), replay.bodyAsText())
        assertEquals(first.string("id"), objectBody(child).string("parentPlanId")); assertEquals(VERSION2, objectBody(child).string("recipeVersionId"))
        problem(client.post(path) { account(f); header("Idempotency-Key", UUID.randomUUID()); header(HttpHeaders.IfMatch, "\"1\""); contentType(ContentType.Application.Json); setBody(body) }, 400, "INVALID_REQUEST")
        assertEquals(2, f.count("planning.plans")); assertEquals(2, f.count("platform.outbox"))
    }

    @Test fun actualVerifiedGuestKindCannotBorrowDeviceOrAccountOwnership() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }
        val accountPlan = objectBody(client.create(f))
        val guest = client.post("/v1/plans") { header(HttpHeaders.Authorization, "Bearer synthetic-guest"); header("Idempotency-Key", UUID.randomUUID()); contentType(ContentType.Application.Json); setBody(request().toString()) }
        success(guest, 201, "createPlan")
        problem(client.get("/v1/plans/${accountPlan.string("id")}") { header(HttpHeaders.Authorization, "Bearer synthetic-guest") }, 404, "PLAN_UNAVAILABLE")
        problem(client.get("/v1/plans/${UUID.randomUUID()}") { header(HttpHeaders.Authorization, "Bearer synthetic-guest") }, 404, "PLAN_UNAVAILABLE")
        problem(client.get("/v1/not-a-real-route?private=PRIVATE-URI-CANARY") { header(HttpHeaders.Authorization, "Bearer synthetic-guest") }, 404, "ROUTE_NOT_FOUND")
        problem(client.get("/v1/plans/${objectBody(guest).string("id")}") { account(f) }, 404, "PLAN_UNAVAILABLE")
        problem(client.get("/v1/plans/${accountPlan.string("id")}") { header(HttpHeaders.Authorization, "Bearer synthetic-guest"); header("X-Device-Session", f.account.deviceSessionId) }, 401, "UNAUTHENTICATED")
        problem(client.get("/v1/plans/${accountPlan.string("id")}") { header(HttpHeaders.Authorization, "Bearer synthetic-account") }, 401, "UNAUTHENTICATED")
        assertEquals(2, f.count("planning.plans"))
    }

    @Test fun verifierOutputEnvironmentAndDeviceMustMatchAdmittedContext() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }
        for (principal in listOf(VerifiedPlanningPrincipal("other", CommandActor.ACCOUNT, f.account.principalId, f.account.deviceSessionId),
            VerifiedPlanningPrincipal("test", CommandActor.ACCOUNT, f.account.principalId, UUID.randomUUID()))) {
            f.verification = { PortResult.Value(principal) }; problem(client.create(f), 401, "UNAUTHENTICATED")
        }
        assertEquals(0, f.authority.reads); assertEquals(0, f.count("platform.idempotency"))
    }

    @Test fun verifierFailuresAndExceptionsAreSanitizedAndCannotReachDatabaseAuthority() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }
        for ((reason, status, code) in listOf(Triple(FailureReason.UNAUTHENTICATED, 401, "UNAUTHENTICATED"), Triple(FailureReason.FORBIDDEN, 403, "FORBIDDEN"), Triple(FailureReason.RATE_LIMITED, 429, "RATE_LIMITED"), Triple(FailureReason.UNAVAILABLE, 503, "AUTHENTICATION_UNAVAILABLE"))) {
            f.verification = { PortResult.Failure(reason) }; val response = client.create(f); problem(response, status, code)
            assertNull(response.headers[HttpHeaders.RetryAfter]); assertFalse(objectBody(response).containsKey("retryAfterSeconds"))
        }
        for ((reason, status, code) in listOf(Triple(FailureReason.RATE_LIMITED, 429, "RATE_LIMITED"), Triple(FailureReason.UNAVAILABLE, 503, "AUTHENTICATION_UNAVAILABLE"))) for (delay in listOf(0L, 9L, Long.MAX_VALUE)) {
            f.verification = { PortResult.Failure(reason, delay) }; val response = client.create(f); problem(response, status, code)
            assertEquals(delay, objectBody(response).getValue("retryAfterSeconds").jsonPrimitive.long)
            assertEquals(delay.takeIf { it > 0 }?.toString(), response.headers[HttpHeaders.RetryAfter])
        }
        f.verification = { error("synthetic-secret PRIVATE-PROVIDER-CANARY") }
        problem(client.create(f), 503, "AUTHENTICATION_UNAVAILABLE")
        assertEquals(0, f.authority.reads); assertEquals(0, f.count("planning.plans"))
    }

    @Test fun currentSessionRevocationIsRecheckedBeforeSuccessfulIdempotentReplay() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }; val key = UUID.randomUUID()
        val plan = objectBody(client.create(f, key)); f.sql("UPDATE http_test.principals SET active=false WHERE kind='account'")
        problem(client.create(f, key), 401, "UNAUTHENTICATED")
        problem(client.get("/v1/plans/${plan.string("id")}") { account(f) }, 401, "UNAUTHENTICATED")
        assertEquals(1, f.count("planning.plans")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun expiredGuestAndUnknownTokenDoNotAuthorizeFreshOrCachedPlans() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }
        f.sql("UPDATE http_test.principals SET expires_at=clock_timestamp()-interval '1 second' WHERE kind='guest'")
        problem(client.post("/v1/plans") { header(HttpHeaders.Authorization, "Bearer synthetic-guest"); header("Idempotency-Key", UUID.randomUUID()); contentType(ContentType.Application.Json); setBody(request().toString()) }, 401, "UNAUTHENTICATED")
        problem(client.post("/v1/plans") { header(HttpHeaders.Authorization, "Bearer unknown-sensitive-token"); header("Idempotency-Key", UUID.randomUUID()); contentType(ContentType.Application.Json); setBody(request().toString()) }, 401, "UNAUTHENTICATED")
        assertEquals(0, f.count("platform.idempotency"))
    }

    @Test fun malformedSchemaDuplicateUnicodeAndOversizeBodiesHaveNoDurableEffects() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }
        for ((body, status) in listOf("{" to 400, "{}" to 422, request().toString().replace("\"mode\":\"assemble\"", "\"mode\":\"assemble\",\"mode\":\"assemble\"") to 400,
            request().toString().dropLast(1) + ",\"naturalLanguage\":\"\\uD800\"}" to 400, " ".repeat(65537) to 400))
            problem(client.create(f, body = body), status, if (status == 422) "INPUT_INVALID" else "INVALID_REQUEST")
        assertEquals(0, f.authority.reads); assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.count("planning.plans"))
    }

    @Test fun duplicateQueriesAndConditionalReadsCannotCauseUnvalidated304OrDisclosure() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }
        val plan = objectBody(client.create(f)); val path = "/v1/plans/${plan.string("id")}"; val reads = f.authority.reads
        for (suffix in listOf("?limit=1", "/explanation?limit=1&limit=1", "/explanation?cursor=a&cursor=b", "/explanation?limit=1e0", "/explanation?planId=${plan.string("id")}"))
            problem(client.get(path + suffix) { account(f) }, 400, "INVALID_REQUEST")
        for (headerName in listOf(HttpHeaders.IfNoneMatch, HttpHeaders.IfMatch)) problem(client.get(path) { account(f); header(headerName, "\"1\"") }, 400, "INVALID_REQUEST")
        assertEquals(reads, f.authority.reads)
    }

    @Test fun decodedUuidPathsAreExactAndMalformedOrExtraSegmentsNeverSelectAnotherResource() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }
        val plan = objectBody(client.create(f)); val id = plan.string("id")
        success(client.get("/v1/plans/" + "%${id.first().code.toString(16)}" + id.drop(1)) { account(f) }, 200, "getPlan")
        for (path in listOf("1-1-1-1-1", "$id%252Fextra", "$id%2Fextra", "$id/extra")) {
            val response = client.get("/v1/plans/$path") { account(f) }
            assertTrue(response.status.value in setOf(400, 404)); assertFalse(response.bodyAsText().contains("Synthetic private"))
        }
        assertEquals(1, f.count("planning.plans"))
    }

    @Test fun catalogRecallDeniesReadExplanationAndCachedReadySelection() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }; val key = UUID.randomUUID()
        val plan = objectBody(client.create(f, key)); f.change { text -> text.replace("\"reviewStatus\":\"published\"", "\"reviewStatus\":\"recalled\"") }
        problem(client.create(f, key), 409, "RECIPE_RECALLED")
        for (suffix in listOf("", "/explanation")) problem(client.get("/v1/plans/${plan.string("id")}$suffix") { account(f) }, 409, "RECIPE_RECALLED")
        assertEquals(1, f.count("planning.plans")); assertEquals(1, f.count("platform.idempotency"))
    }

    @Test fun simulatedCommitReceiptLossReturnsUnknownAndExactRetryRecoversOneActualCommit() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }; val key = UUID.randomUUID(); f.faults.loseCommit = true
        problem(client.create(f, key), 503, "OUTCOME_UNKNOWN")
        assertEquals(1, f.count("planning.plans")); val replay = client.create(f, key); success(replay, 201, "createPlan")
        assertEquals(1, f.count("planning.plans")); assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun preCommitDatabaseFailureRollsBackPlanReceiptAndOutboxWithoutLeakingCause() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }; f.faults.failOutbox = true
        problem(client.create(f), 503, "STORAGE_UNAVAILABLE")
        assertEquals(0, f.count("planning.plans")); assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.count("platform.outbox"))
    }

    @Test fun cancelledHttpCallerAfterActualCommitKeepsOneDurableResultForExactRetry() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }
        val committed = CountDownLatch(1); val release = CountDownLatch(1); val key = UUID.randomUUID()
        f.faults.afterCommit = { committed.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        coroutineScope {
            val call = async { client.create(f, key) }
            try {
                assertTrue(withContext(Dispatchers.IO) { committed.await(5, TimeUnit.SECONDS) })
                call.cancel(); release.countDown(); call.join(); assertTrue(call.isCancelled)
            } finally { release.countDown(); call.cancelAndJoin() }
        }
        // This is a cancelled HTTP caller after a real acknowledged COMMIT, not rollback proof.
        assertEquals(1, f.count("planning.plans")); success(client.create(f, key), 201, "createPlan")
        assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun unconfiguredAndOtherProductOperationsStayClosedEvenWithPlanningCredentials() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local) }
        problem(client.create(f), 503, "OPERATION_NOT_IMPLEMENTED"); assertEquals(0, f.verifications)
        assertEquals(0, f.count("planning.plans"))
    }

    @Test fun configuredServiceStillDoesNotEnablePreferenceRecipeOrSocialRoutes() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, planning = f.configuration()) }
        for (path in listOf("/v1/preferences", "/v1/circles", "/v1/config")) problem(client.get(path) { account(f) }, 503, "OPERATION_NOT_IMPLEMENTED")
        assertEquals(0, f.verifications)
    }

    @Test fun actualCioSocketServesPlanningAndRejectsDuplicateAuthorizationWithoutSecondEffect() = runBlocking {
        val f = Fixture(); val server = embeddedServer(CIO, port = 0, host = local.host) { feedMeLocalService(local, planning = f.configuration()) }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            fun send(duplicate: Boolean): java.net.http.HttpResponse<String> {
                val builder = java.net.http.HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/plans")).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer synthetic-account").header("X-Device-Session", f.account.deviceSessionId.toString())
                    .header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(request().toString()))
                if (duplicate) builder.header("Authorization", "Bearer synthetic-account")
                return client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            }
            val good = send(false); assertEquals(201, good.statusCode()); assertEquals("no-store", good.headers().firstValue("Cache-Control").orElse(null))
            assertEquals(BodyValidationResult.Valid, validator.validateResponse("createPlan", 201, good.body().encodeToByteArray(), "application/json"))
            val bad = send(true); assertEquals(400, bad.statusCode()); assertEquals("INVALID_REQUEST", parse(bad.body()).string("code"))
            assertEquals(1, f.count("planning.plans"))
        } finally { server.stop(gracePeriodMillis = 100, timeoutMillis = 1000) }
    }

    private class Fixture {
        val source = cluster.database(); val faults = Faults(); val authority = SyntheticAuthority(); val account: VerifiedPlanningPrincipal; val guest: VerifiedPlanningPrincipal
        var verifications = 0
        var verification: (suspend (PlanningHttpBearer) -> PortResult<VerifiedPlanningPrincipal>)? = null
        val store: PlansStore
        init {
            PlatformMigrations(source).migrate()
            sql("CREATE SCHEMA http_test; CREATE TABLE http_test.principals(kind text NOT NULL,id uuid NOT NULL,device uuid NULL,active boolean NOT NULL,expires_at timestamptz NOT NULL,PRIMARY KEY(kind,id)); CREATE TABLE http_test.inputs(kind text NOT NULL,id uuid NOT NULL,snapshot_text text NOT NULL,PRIMARY KEY(kind,id))")
            account = principal(CommandActor.ACCOUNT); guest = principal(CommandActor.GUEST)
            store = PlansStore("test", PgTransactions(faults.wrap(source)), authority, PlanningServicePolicy("test-rank-1", false, true, 86400, 600), PlanningCursors("test", mapOf("test" to ByteArray(32) { 7 })))
        }
        fun configuration() = PlanningHttpConfiguration("test", store, PlanningHttpVerifier { bearer ->
            verifications++; verification?.invoke(bearer) ?: bearer.token.use { token -> when (token) {
                "synthetic-account" -> PortResult.Value(account); "synthetic-guest" -> PortResult.Value(guest); else -> PortResult.Failure(FailureReason.UNAUTHENTICATED)
            } }
        }, Dispatchers.IO)
        private fun principal(kind: CommandActor) = VerifiedPlanningPrincipal("test", kind, UUID.randomUUID(), if (kind == CommandActor.ACCOUNT) UUID.randomUUID() else null).also { p ->
            source.connection.use { c -> c.prepareStatement("INSERT INTO http_test.principals VALUES(?,?,?,true,clock_timestamp()+interval '1 day')").use { s -> s.setString(1, kind.name.lowercase()); s.setObject(2, p.principalId); s.setObject(3, p.deviceSessionId); s.executeUpdate() }
                c.prepareStatement("INSERT INTO http_test.inputs VALUES(?,?,?)").use { s -> s.setString(1, kind.name.lowercase()); s.setObject(2, p.principalId); s.setString(3, evidence().toString()); s.executeUpdate() } }
        }
        fun sql(sql: String) { source.connection.use { c -> c.createStatement().use { it.execute(sql) } } }
        fun count(table: String): Int = source.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $table").use { r -> r.next(); r.getInt(1) } } }
        fun change(transform: (String) -> String) { source.connection.use { c ->
            val original = c.createStatement().use { s -> s.executeQuery("SELECT snapshot_text FROM http_test.inputs LIMIT 1").use { r -> r.next(); r.getString(1) } }
            c.prepareStatement("UPDATE http_test.inputs SET snapshot_text=?").use { s -> s.setString(1, transform(original)); s.executeUpdate() }
        } }
    }
    private class SyntheticAuthority : PlanningAuthority {
        var reads = 0
        override fun lockPrincipal(connection: Connection, principal: VerifiedPlanningPrincipal) {
            connection.prepareStatement("SELECT active,device,expires_at>clock_timestamp() FROM http_test.principals WHERE kind=? AND id=? FOR SHARE").use { s ->
                s.setString(1, principal.kind.name.lowercase()); s.setObject(2, principal.principalId); s.executeQuery().use { r ->
                    if (!r.next() || !r.getBoolean(1) || r.getObject(2, UUID::class.java) != principal.deviceSessionId || !r.getBoolean(3)) throw PlanningServiceFailure(PlanningFailureCode.UNAUTHENTICATED)
                }
            }
        }
        override fun requireNewPlanningEnabledAndQuota(connection: Connection, principal: VerifiedPlanningPrincipal) = Unit
        override fun lockCurrentSnapshot(connection: Connection, principal: VerifiedPlanningPrincipal, request: WireDocument): PlanningEvidenceSnapshot {
            reads++
            return connection.prepareStatement("SELECT snapshot_text FROM http_test.inputs WHERE kind=? AND id=? FOR SHARE").use { s ->
                s.setString(1, principal.kind.name.lowercase()); s.setObject(2, principal.principalId); s.executeQuery().use { r -> check(r.next()); PlanningEvidenceSnapshot.fromAuthoritativeDocument(WireDocument.parse(r.getString(1))) }
            }
        }
    }
    /** Post-COMMIT Java exception is simulated lost application receipt, not an OS fsync failure. */
    private class Faults {
        @Volatile var loseCommit = false; @Volatile var failOutbox = false
        @Volatile var afterCommit: (() -> Unit)? = null
        fun wrap(source: DataSource): DataSource = object : DataSource by source {
            override fun getConnection(): Connection {
                val actual = source.connection
                return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "prepareStatement" && (args?.firstOrNull() as? String)?.contains("INSERT INTO platform.outbox") == true && failOutbox) {
                        failOutbox = false; throw SQLException("PRIVATE-SQL-CANARY", "XX000")
                    }
                    try { val result = method.invoke(actual, *(args ?: emptyArray()))
                        if (method.name == "commit") { val hook = afterCommit; afterCommit = null; hook?.invoke() }
                        if (method.name == "commit" && loseCommit) { loseCommit = false; throw SQLException("PRIVATE-COMMIT-CANARY", "08006") }; result
                    } catch (failure: InvocationTargetException) { throw failure.targetException }
                } as Connection
            }
        }
    }
    companion object {
        private const val INGREDIENT = "00000000-0000-4000-8000-000000000011"
        private const val VERSION = "00000000-0000-4000-8000-000000000021"
        private const val VERSION2 = "00000000-0000-4000-8000-000000000022"
        private const val TIME = "2026-09-13T10:00:00Z"
        private val local = LocalServerConfig.fromEnvironment(emptyMap())
        private val validator = ContractBodyValidator.bundled()
        private lateinit var cluster: PostgresTestCluster
        @JvmField @ClassRule val timeout = Timeout(10, TimeUnit.MINUTES)
        @JvmStatic @BeforeClass fun start() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun stop() { if (::cluster.isInitialized) cluster.close() }
        private fun arr(vararg strings: String) = JsonArray(strings.map(::JsonPrimitive))
        private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
        private suspend fun objectBody(response: HttpResponse) = parse(response.bodyAsText())
        private fun HttpRequestBuilder.account(f: Fixture) { header(HttpHeaders.Authorization, "Bearer synthetic-account"); header("X-Device-Session", f.account.deviceSessionId) }
        private suspend fun HttpClient.create(f: Fixture, key: UUID = UUID.randomUUID(), body: String = PlanningHttpIntegrationTest.request().toString()) = post("/v1/plans") {
            account(f); header("Idempotency-Key", key); contentType(ContentType.Application.Json); setBody(body)
        }
        private suspend fun success(response: HttpResponse, status: Int, operation: String) {
            assertEquals(status, response.status.value, response.bodyAsText()); headers(response)
            assertEquals("application/json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            assertEquals(BodyValidationResult.Valid, validator.validateResponse(operation, status, response.bodyAsText().encodeToByteArray(), "application/json"))
        }
        private suspend fun problem(response: HttpResponse, status: Int, code: String) {
            assertEquals(status, response.status.value, response.bodyAsText()); headers(response); val text = response.bodyAsText(); val body = parse(text)
            assertEquals(code, body.string("code")); assertEquals(status, body.getValue("status").jsonPrimitive.int)
            assertEquals(response.headers["X-Trace-Id"], body.string("traceId")); assertNull(response.headers[HttpHeaders.ETag])
            assertEquals("application/problem+json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            assertEquals(BodyValidationResult.Valid, validator.validateSchema("Problem", text.encodeToByteArray()))
            for (canary in listOf("synthetic-account", "synthetic-guest", "synthetic-secret", "PRIVATE-", "Synthetic private")) assertFalse(text.contains(canary))
        }
        private fun headers(response: HttpResponse) {
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl]); assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertNotNull(UUID.fromString(response.headers["X-Trace-Id"])); assertNull(response.headers[HttpHeaders.SetCookie]); assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
        private fun request() = buildJsonObject { put("mode", "assemble"); put("preferenceVersion", 1); put("constraints", buildJsonObject {
            put("ingredientIds", arr(INGREDIENT)); put("energy", "assemble"); put("equipmentIds", arr("bowl")); put("servings", 1); put("hardExcludedIngredientIds", arr()); put("tasteTags", arr())
        }) }
        private fun candidate(id: String) = buildJsonObject {
            put("recipe", buildJsonObject {
                put("id", id); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME); put("recipeId", "00000000-0000-4000-8000-000000000031"); put("title", "Synthetic private recipe")
                put("reviewStatus", "published"); put("reviewedAt", TIME); put("estimateBasis", "reviewerEstimate"); put("servings", 1)
                put("ingredients", buildJsonArray { add(buildJsonObject { put("ingredientId", INGREDIENT); put("quantity", 1); put("unit", "g"); put("optional", false) }) })
                put("steps", buildJsonArray { add(buildJsonObject { put("stepId", "mix"); put("position", 1); put("instruction", "Mix ingredients."); put("ingredientIds", arr(INGREDIENT)); put("requiredEquipmentIds", arr("bowl")); put("mandatorySafetyStep", false) }) })
                put("activeMinutes", 5); put("totalMinutes", 10); put("utensilCount", 1); put("equipmentIds", arr("bowl")); put("modes", arr("assemble")); put("tasteTags", arr("crunch")); put("preparationTags", arr("noHeat", "oneBowl")); put("cleanupMinutes", 2)
            })
            put("review", buildJsonObject { put("reviewReference", "synthetic-review"); put("policyVersion", "test-rank-1"); put("kind", "MEAL"); put("minimumEnergy", "ASSEMBLE"); put("heatingRequired", false); put("substantialPreparation", false); put("freeCatalogEligible", true); put("compatibleBaseTypes", arr()); put("linearQuantityScalingReviewed", true); put("stepsValidForScalingRange", true); put("effortValidForScalingRange", true); put("scalableUnits", arr("g")) })
        }
        private fun evidence() = buildJsonObject {
            put("version", 1); put("preferences", buildJsonObject { put("revision", "1"); put("excludedIngredientIds", arr()); put("dislikedIngredientIds", arr()) })
            put("pantry", buildJsonObject { put("revision", "pantry-1"); put("items", arr()) }); put("baseMeal", JsonNull)
            put("catalog", buildJsonObject { put("revision", "catalog-1"); put("taxonomyRevision", "taxonomy-1"); put("candidates", JsonArray(listOf(candidate(VERSION), candidate(VERSION2)))); put("ingredients", buildJsonArray { add(buildJsonObject { put("ingredientId", INGREDIENT); put("componentIds", arr()) }) }) })
        }
    }
}
