package com.feedme.server.http

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.kitchen.*
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
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/** Actual HTTP + PostgreSQL; authentication/catalog/reviewer authority below is TEST ONLY. */
class KitchenHttpIntegrationTest {
    @Test fun missingPreferencesNeverProvisionOnGetAndExplicitSeedRemainsCanonical() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        problem(client.get("/v1/preferences") { account(f) }, 404, "PREFERENCES_UNAVAILABLE")
        assertEquals(0, f.count("profile.preferences")); assertEquals(0, f.count("platform.outbox"))
        val seeded = f.store.provisionPreferences(f.account, seed()).body
        val read = client.get("/v1/preferences") { account(f) }; success(read, 200, "getPreferences")
        assertEquals(seeded, objectBody(read)); assertEquals("\"1\"", read.headers[HttpHeaders.ETag])
        assertEquals(1, f.count("profile.preferences")); assertEquals(1, f.count("platform.outbox"))
        problem(client.post("/v1/preferences") { account(f); contentType(ContentType.Application.Json); setBody(seed().toString()) }, 404, "ROUTE_NOT_FOUND")
    }

    @Test fun preferencePatchUsesOriginalKeyAndExactVersionWithoutRebasingAnOldReply() = testApplication {
        val f = Fixture(); f.provision(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        val key = UUID.randomUUID(); val first = client.preferences(f, key)
        success(first, 200, "updatePreferences"); assertEquals("\"2\"", first.headers[HttpHeaders.ETag])
        replayEquals(first, client.preferences(f, key), "updatePreferences")
        problem(client.preferences(f, key, body = """{"defaultEnergy":"happy"}"""), 409, "IDEMPOTENCY_MISMATCH")
        problem(client.preferences(f), 412, "VERSION_CONFLICT")
        success(client.preferences(f, version = "\"2\"", body = "{}"), 200, "updatePreferences")
        problem(client.preferences(f, key), 412, "VERSION_CONFLICT")
        assertEquals(2, f.count("platform.idempotency")); assertEquals(3, f.count("platform.outbox"))
    }

    @Test fun verifiedGuestAndAccountOwnDifferentInputsEvenWithIdenticalPrincipalUuid() = testApplication {
        val f = Fixture(); f.provision(); f.store.provisionPreferences(f.guest, seed())
        application { feedMeLocalService(local, kitchen = f.configuration()) }
        val a = objectBody(client.get("/v1/preferences") { account(f) })
        val g = objectBody(client.get("/v1/preferences") { guest() }); assertNotEquals(a.string("id"), g.string("id"))
        success(client.pantry(f), 200, "upsertPantryItem")
        val guestPage = client.get("/v1/pantry/items") { guest() }; success(guestPage, 200, "listPantry")
        assertTrue(objectBody(guestPage).getValue("items").jsonArray.isEmpty())
        problem(client.delete("/v1/pantry/items/" + INGREDIENT) {
            guest(); header("Idempotency-Key", UUID.randomUUID()); header(HttpHeaders.IfMatch, "\"1\"")
        }, 404, "PANTRY_ITEM_UNAVAILABLE")
        problem(client.get("/v1/preferences") { guest(); header("X-Device-Session", f.account.deviceSessionId) }, 401, "UNAUTHENTICATED")
        problem(client.get("/v1/preferences") { header(HttpHeaders.Authorization, "Bearer synthetic-account") }, 401, "UNAUTHENTICATED")
        assertEquals(1, f.count("pantry.pantry_items"))
    }

    @Test fun pantryWritesPreserveExactDecimalsAndExplicitExpectedVersions() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        val key = UUID.randomUUID()
        val body = """{"ingredientId":"$INGREDIENT","presence":"low","quantity":0.0000001234567890123456789,"unit":"g"}"""
        val first = client.pantry(f, key, body); success(first, 200, "upsertPantryItem")
        val item = objectBody(first)
        assertEquals("0.0000001234567890123456789".toBigDecimal(), item.getValue("quantity").jsonPrimitive.content.toBigDecimal())
        assertEquals(JsonNull, item["confirmedAt"]); assertEquals(JsonPrimitive(false), item["staple"])
        replayEquals(first, client.pantry(f, key, body), "upsertPantryItem")
        problem(client.pantry(f), 412, "VERSION_CONFLICT")
        success(client.pantry(f, body = write(expected = 1)), 200, "upsertPantryItem")
        problem(client.pantry(f, key, body), 412, "VERSION_CONFLICT")
        val exponentKey = UUID.randomUUID()
        val exp = """{"ingredientId":"$OTHER","presence":"low","quantity":1e2,"unit":"g"}"""
        val normal = client.pantry(f, exponentKey, exp)
        replayEquals(normal, client.pantry(f, exponentKey, exp.replace("1e2", "100.0")), "upsertPantryItem")
        assertEquals(3, f.count("platform.idempotency"))
    }

    @Test fun deleteReturnsEmpty204AndOldKeysOrVersionsCannotDeleteARecreatedItem() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        val createKey = UUID.randomUUID(); val original = objectBody(client.pantry(f, createKey)); val deleteKey = UUID.randomUUID()
        success(client.remove(f, deleteKey), 204, "removePantryItem")
        success(client.remove(f, deleteKey), 204, "removePantryItem")
        problem(client.pantry(f, createKey), 404, "PANTRY_ITEM_UNAVAILABLE")
        val recreated = client.pantry(f); success(recreated, 200, "upsertPantryItem")
        assertEquals("\"3\"", recreated.headers[HttpHeaders.ETag]); assertNotEquals(original.string("id"), objectBody(recreated).string("id"))
        problem(client.remove(f, deleteKey), 412, "VERSION_CONFLICT")
        problem(client.remove(f), 412, "VERSION_CONFLICT")
        assertEquals(3, f.count("platform.idempotency")); assertEquals(3, f.count("platform.outbox"))
    }

    @Test fun retiredCatalogReferencesRemainOwnedReportsAndCanBeRemovedWithoutBeingSelectable() = testApplication {
        val f = Fixture(); f.store.provisionPreferences(f.account, JsonObject(seed() + ("hardExcludedIngredientIds" to arr(INGREDIENT))))
        application { feedMeLocalService(local, kitchen = f.configuration()) }
        val key = UUID.randomUUID(); val first = client.pantry(f, key)
        f.sql("UPDATE kitchen_http_test.ingredients SET active=false WHERE id='" + INGREDIENT + "'")
        success(client.get("/v1/preferences") { account(f) }, 200, "getPreferences")
        success(client.get("/v1/pantry/items") { account(f) }, 200, "listPantry")
        replayEquals(first, client.pantry(f, key), "upsertPantryItem")
        problem(client.pantry(f, body = write(expected = 1)), 422, "INGREDIENT_UNAVAILABLE")
        val search = client.get("/v1/ingredients?q=pea") { account(f) }; success(search, 200, "searchIngredients")
        assertTrue(objectBody(search).getValue("items").jsonArray.isEmpty())
        success(client.remove(f), 204, "removePantryItem")
        success(client.preferences(f, body = """{"hardExcludedIngredientIds":[]}"""), 200, "updatePreferences")
    }

    @Test fun currentRevocationAndGuestExpiryAreCheckedBeforeCachedResponses() = testApplication {
        val f = Fixture(); f.provision(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        val key = UUID.randomUUID(); success(client.pantry(f, key), 200, "upsertPantryItem")
        f.sql("UPDATE kitchen_http_test.principals SET active=false WHERE kind='account'")
        problem(client.pantry(f, key), 401, "UNAUTHENTICATED")
        problem(client.get("/v1/preferences") { account(f) }, 401, "UNAUTHENTICATED")
        f.sql("UPDATE kitchen_http_test.principals SET expires_at=clock_timestamp()-interval '1 second' WHERE kind='guest'")
        problem(client.get("/v1/pantry/items") { guest() }, 401, "UNAUTHENTICATED")
        assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("pantry.pantry_items"))
    }

    @Test fun malformedSchemaDuplicateUnicodeAndOversizeInputCannotMutateDatabase() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        for ((body, status) in listOf("{" to 400, "{}" to 422, write().dropLast(1) + ""","presence":"low"}""" to 400,
            write().dropLast(1) + ""","unit":"\uD800"}""" to 400, " ".repeat(65537) to 400,
            write().dropLast(1) + ""","ownerId":"private"}""" to 422))
            problem(client.pantry(f, body = body), status, if (status == 422) "INPUT_INVALID" else "INVALID_REQUEST")
        assertEquals(0, f.authority.locks); assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.count("pantry.pantry_items"))
    }

    @Test fun paginationBindsOwnerAndQueriesCannotSmugglePrivateSelectionOrConditional304() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        client.pantry(f); client.pantry(f, body = write(OTHER))
        val first = client.get("/v1/pantry/items?limit=1") { account(f) }; success(first, 200, "listPantry")
        val cursor = objectBody(first).getValue("nextCursor").jsonPrimitive.content
        val second = client.get("/v1/pantry/items") { account(f); parameter("cursor", cursor); parameter("limit", 1) }
        success(second, 200, "listPantry")
        assertNotEquals(objectBody(first)["items"], objectBody(second)["items"])
        problem(client.get("/v1/pantry/items") { guest(); parameter("cursor", cursor) }, 422, "CURSOR_INVALID")
        val locks = f.authority.locks
        for (path in listOf("/v1/pantry/items?limit=1&limit=1", "/v1/pantry/items?ownerId=x",
            "/v1/ingredients?q=a&q=b", "/v1/ingredients?limit=0", "/v1/preferences?limit=1"))
            problem(client.get(path) { account(f) }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/pantry/items") { account(f); header(HttpHeaders.IfNoneMatch, "\"1\"") }, 400, "INVALID_REQUEST")
        assertEquals(locks, f.authority.locks)
    }

    @Test fun ingredientSearchRunsCurrentTransactionAndPreservesAbsentEmptyAndUnicodeQuery() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        val peas = client.get("/v1/ingredients?q=pea") { account(f) }; success(peas, 200, "searchIngredients")
        assertEquals(INGREDIENT, objectBody(peas).getValue("items").jsonArray.single().jsonObject.string("id"))
        success(client.get("/v1/ingredients") { account(f) }, 200, "searchIngredients"); assertNull(f.lastQuery)
        success(client.get("/v1/ingredients?q=") { account(f) }, 200, "searchIngredients"); assertEquals("", f.lastQuery)
        val supplementary = "\uD83C\uDF73".repeat(100)
        success(client.get("/v1/ingredients") { account(f); parameter("q", supplementary) }, 200, "searchIngredients")
        assertEquals(supplementary, f.lastQuery)
        problem(client.get("/v1/ingredients") { account(f); parameter("q", supplementary + "x") }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/ingredients?cursor=untrusted") { account(f) }, 422, "CURSOR_INVALID")
        f.invalidSearch = true
        problem(client.get("/v1/ingredients") { account(f) }, 503, "STORAGE_UNAVAILABLE")
        assertEquals(0, f.count("platform.idempotency"))
    }

    @Test fun verifierFailureIsSanitizedAndCannotReachCurrentDatabaseAuthority() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        f.verification = { PortResult.Failure(FailureReason.RATE_LIMITED, 9) }
        val limited = client.pantry(f); problem(limited, 429, "RATE_LIMITED")
        assertEquals("9", limited.headers[HttpHeaders.RetryAfter])
        f.verification = { error("PRIVATE-PROVIDER-CANARY") }
        problem(client.pantry(f), 503, "AUTHENTICATION_UNAVAILABLE")
        f.verification = { PortResult.Value(VerifiedKitchenPrincipal("other", f.account.kind, f.account.principalId, f.account.deviceSessionId)) }
        problem(client.pantry(f), 401, "UNAUTHENTICATED")
        assertEquals(0, f.authority.locks); assertEquals(0, f.count("platform.idempotency"))
    }

    @Test fun explicitResponseCapRejectsMutationBeforeDomainReceiptAndOutboxCommit() = testApplication {
        val f = Fixture(maxBytes = 512); f.provision(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        val before = f.snapshot()
        problem(client.preferences(f, body = buildJsonObject { put("consentVersion", "x".repeat(1000)) }.toString()), 422, "RESPONSE_TOO_LARGE")
        problem(client.pantry(f, body = write().dropLast(1) + ""","unit":"""" + "x".repeat(1000) + """"}"""), 422, "RESPONSE_TOO_LARGE")
        assertEquals(before, f.snapshot())
    }

    @Test fun actualCommitWithLostApplicationReceiptRequiresOriginalKeyAndHasOneEffect() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        val key = UUID.randomUUID(); f.faults.loseCommit = true
        problem(client.pantry(f, key), 503, "OUTCOME_UNKNOWN")
        assertEquals(1, f.count("pantry.pantry_items"))
        success(client.pantry(f, key), 200, "upsertPantryItem")
        assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun outboxFailureRollsBackMutationAndNeverLeaksSqlFailure() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        f.faults.failOutbox = true; problem(client.pantry(f), 503, "STORAGE_UNAVAILABLE")
        assertEquals(0, f.count("pantry.pantry_items")); assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.count("platform.outbox"))
    }

    @Test fun cancelledHttpCallerAfterActualCommitDoesNotImplyRollbackOrDuplicateRetry() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        val committed = CountDownLatch(1); val release = CountDownLatch(1); val key = UUID.randomUUID()
        f.faults.afterCommit = { committed.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        coroutineScope {
            val call = async { client.pantry(f, key) }
            try {
                assertTrue(withContext(Dispatchers.IO) { committed.await(5, TimeUnit.SECONDS) })
                call.cancel(); release.countDown(); call.join(); assertTrue(call.isCancelled)
            } finally { release.countDown(); call.cancelAndJoin() }
        }
        assertEquals(1, f.count("pantry.pantry_items")); success(client.pantry(f, key), 200, "upsertPantryItem")
        assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun actualCioSocketAcceptsCanonicalRequestAndRejectsDuplicateAuthorization() = runBlocking {
        val f = Fixture()
        val server = embeddedServer(CIO, port = 0, host = local.host) { feedMeLocalService(local, kitchen = f.configuration()) }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            fun send(duplicate: Boolean): java.net.http.HttpResponse<String> {
                val request = java.net.http.HttpRequest.newBuilder(URI("http://127.0.0.1:" + port + "/v1/pantry/items"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer synthetic-account")
                    .header("X-Device-Session", f.account.deviceSessionId.toString()).header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(write()))
                if (duplicate) request.header("Authorization", "Bearer synthetic-account")
                return http.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            }
            val good = send(false); assertEquals(200, good.statusCode())
            assertEquals("no-store", good.headers().firstValue("Cache-Control").orElse(null))
            assertEquals(BodyValidationResult.Valid, validator.validateResponse("upsertPantryItem", 200, good.body().encodeToByteArray(), "application/json"))
            val bad = send(true); assertEquals(400, bad.statusCode()); assertEquals("INVALID_REQUEST", parse(bad.body()).string("code"))
            assertEquals(1, f.count("pantry.pantry_items")); assertEquals(1, f.count("platform.idempotency"))
        } finally { server.stop(gracePeriodMillis = 100, timeoutMillis = 1000) }
    }

    @Test fun unconfiguredServiceNeverInvokesVerifierOrEnablesKitchenWrites() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local) }
        problem(client.pantry(f), 503, "OPERATION_NOT_IMPLEMENTED")
        assertEquals(0, f.verifications); assertEquals(0, f.count("pantry.pantry_items"))
    }

    @Test fun kitchenConfigurationDoesNotActivateLoginCookingOrSocialOperations() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, kitchen = f.configuration()) }
        for (path in listOf("/v1/circles", "/v1/config"))
            problem(client.get(path) { account(f) }, 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.post("/v1/cook-sessions") { account(f) }, 503, "OPERATION_NOT_IMPLEMENTED")
        assertEquals(0, f.verifications); assertEquals(0, f.count("platform.idempotency"))
    }

    @Test fun actualPersistedPreferenceVersionGatesPlanningSelectionAndPreservesHistoricalRead() = testApplication {
        val f = Fixture(); f.provision()
        application { feedMeLocalService(local, kitchen = f.configuration(), planning = f.planning()) }
        val key = UUID.randomUUID(); val created = client.plan(f, key); success(created, 201, "createPlan")
        val id = objectBody(created).string("id")
        success(client.preferences(f), 200, "updatePreferences")
        problem(client.plan(f, key), 409, "PREFERENCE_CHANGED")
        problem(client.plan(f), 409, "PREFERENCE_CHANGED")
        success(client.plan(f, version = 2), 201, "createPlan")
        // Historical GET is not selecting an old plan for new cooking.
        success(client.get("/v1/plans/" + id) { account(f) }, 200, "getPlan")
        assertEquals(2, f.count("planning.plans")); assertEquals(3, f.count("platform.idempotency"))
    }

    @Test fun realPantryMutationChangesPlanningSnapshotWithoutTurningUnconfirmedStockIntoFact() = testApplication {
        val f = Fixture(); f.provision()
        application { feedMeLocalService(local, kitchen = f.configuration(), planning = f.planning()) }
        val key = UUID.randomUUID(); success(client.plan(f, key), 201, "createPlan")
        success(client.pantry(f), 200, "upsertPantryItem")
        problem(client.plan(f, key), 409, "INPUTS_CHANGED")
        val fresh = client.plan(f); success(fresh, 201, "createPlan")
        assertEquals("ready", objectBody(fresh).string("status"))
        assertEquals("UNCERTAIN", f.lastPlanningItems.single().jsonObject.string("availability"))
        assertEquals(2, f.count("planning.plans"))
    }

    private class Fixture(maxBytes: Int = 65536) {
        val source = cluster.database(); val faults = Faults(); val authority = SyntheticAuthority()
        val account: VerifiedKitchenPrincipal; val guest: VerifiedKitchenPrincipal; val store: KitchenStore
        var verifications = 0; var lastQuery: String? = null; var invalidSearch = false
        var lastPlanningItems = emptyList<JsonElement>()
        var verification: (suspend (KitchenHttpBearer) -> PortResult<VerifiedKitchenPrincipal>)? = null
        init {
            PlatformMigrations(source).migrate()
            sql("CREATE SCHEMA kitchen_http_test; CREATE TABLE kitchen_http_test.principals(kind text,id uuid,device uuid,active boolean NOT NULL,expires_at timestamptz NOT NULL,PRIMARY KEY(kind,id));" +
                "CREATE TABLE kitchen_http_test.ingredients(id uuid PRIMARY KEY,name text NOT NULL,active boolean NOT NULL)")
            sql("INSERT INTO kitchen_http_test.ingredients VALUES('" + INGREDIENT + "','peas',true),('" + OTHER + "','rice',true)")
            account = principal(CommandActor.ACCOUNT, UUID.randomUUID()); guest = principal(CommandActor.GUEST, account.principalId)
            store = KitchenStore("test", PgTransactions(faults.wrap(source)), authority,
                KitchenCursorCodec("test", mapOf("test" to ByteArray(32) { 7 })), KitchenServicePolicy(maxBytes, 600))
        }
        fun configuration() = KitchenHttpConfiguration("test", store, KitchenHttpVerifier { bearer ->
            verifications++; verification?.invoke(bearer) ?: bearer.token.use { token -> when (token) {
                "synthetic-account" -> PortResult.Value(account); "synthetic-guest" -> PortResult.Value(guest)
                else -> PortResult.Failure(FailureReason.UNAUTHENTICATED)
            } }
        }, KitchenIngredientSearch { c, _, q, cursor, limit ->
            check(!c.autoCommit); lastQuery = q
            if (cursor != null) throw KitchenFailure(KitchenFailureCode.CURSOR_INVALID)
            if (invalidSearch) buildJsonObject { put("private", "PRIVATE-SEARCH-CANARY") }
            else {
                val items = c.prepareStatement("SELECT id,name FROM kitchen_http_test.ingredients WHERE active AND (?::text IS NULL OR name LIKE '%'||?||'%') ORDER BY id LIMIT ? FOR SHARE").use { s ->
                    s.setString(1, q); s.setString(2, q); s.setInt(3, limit)
                    s.executeQuery().use { r -> buildList { while (r.next()) add(ingredient(r.getString(1), r.getString(2))) } }
                }
                buildJsonObject { put("items", JsonArray(items)); put("nextCursor", JsonNull); put("serverTime", TIME) }
            }
        }, Dispatchers.IO)
        private fun principal(kind: CommandActor, id: UUID) = VerifiedKitchenPrincipal("test", kind, id,
            if (kind == CommandActor.ACCOUNT) UUID.randomUUID() else null).also { p ->
            source.connection.use { c -> c.prepareStatement("INSERT INTO kitchen_http_test.principals VALUES(?,?,?,true,clock_timestamp()+interval '1 day')").use {
                it.setString(1, kind.name.lowercase()); it.setObject(2, id); it.setObject(3, p.deviceSessionId); it.executeUpdate()
            } }
        }
        fun provision() { store.provisionPreferences(account, seed()) }
        fun sql(text: String) { source.connection.use { c -> c.createStatement().use { it.execute(text) } } }
        fun value(text: String) = source.connection.use { c -> c.createStatement().use { s -> s.executeQuery(text).use { r -> check(r.next()); r.getString(1) } } }
        fun count(table: String) = value("SELECT count(*) FROM " + table).toInt()
        fun snapshot() = listOf("profile.preferences", "pantry.pantry_items", "platform.idempotency", "platform.outbox").associateWith {
            value("SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM " + it + " r")
        }
        /** TEST ONLY bridge: actual rows/shared exclusive principal lock, synthetic catalog.
         * Nonempty dietary presets are rejected, not silently ignored or treated as reviewed.
         * This fixture is deliberately not a production planning/catalog policy implementation.
         */
        fun planning(): PlanningHttpConfiguration {
            val planningAuthority = object : PlanningAuthority {
                override fun lockPrincipal(connection: Connection, principal: VerifiedPlanningPrincipal) {
                    try { authority.lockPrincipal(connection, VerifiedKitchenPrincipal(principal.environment, principal.kind, principal.principalId, principal.deviceSessionId)) }
                    catch (_: KitchenFailure) { throw PlanningServiceFailure(PlanningFailureCode.UNAUTHENTICATED) }
                }
                override fun requireNewPlanningEnabledAndQuota(connection: Connection, principal: VerifiedPlanningPrincipal) = Unit
                override fun lockCurrentSnapshot(connection: Connection, principal: VerifiedPlanningPrincipal, request: WireDocument): PlanningEvidenceSnapshot {
                    val prefs = connection.prepareStatement("SELECT version,fields FROM profile.preferences WHERE environment=? AND actor_kind=? AND principal_id=? FOR UPDATE").use { s ->
                        owner(s, principal)
                        s.executeQuery().use { r -> check(r.next()); r.getLong(1) to parse(r.getString(2)) }
                    }
                    check(prefs.second.getValue("dietaryPatterns").jsonArray.isEmpty())
                    val revisions = mutableListOf<String>(); val items = mutableListOf<JsonElement>()
                    connection.prepareStatement("SELECT ingredient_id,version,deleted,fields FROM pantry.pantry_items WHERE environment=? AND actor_kind=? AND principal_id=? ORDER BY ingredient_id FOR UPDATE").use { s ->
                        owner(s, principal); s.executeQuery().use { r -> while (r.next()) {
                            revisions.add(r.getString(1) + ":" + r.getLong(2) + ":" + r.getBoolean(3))
                            if (!r.getBoolean(3)) {
                                val fields = parse(r.getString(4))
                                items.add(buildJsonObject {
                                    put("ingredientId", r.getString(1))
                                    // Rough available/low/usuallyHave is NEVER confirmed merely by persistence.
                                    put("availability", if (fields.string("presence") == "out") "UNAVAILABLE" else "UNCERTAIN")
                                })
                            }
                        } }
                    }
                    lastPlanningItems = items
                    val revision = MessageDigest.getInstance("SHA-256").digest(revisions.joinToString("|").encodeToByteArray()).joinToString("") { "%02x".format(it) }
                    return PlanningEvidenceSnapshot.fromAuthoritativeDocument(WireDocument.parse(buildJsonObject {
                        put("version", 1); put("baseMeal", JsonNull)
                        put("preferences", buildJsonObject { put("revision", prefs.first.toString()); put("excludedIngredientIds", prefs.second.getValue("hardExcludedIngredientIds")); put("dislikedIngredientIds", prefs.second.getValue("dislikedIngredientIds")) })
                        put("pantry", buildJsonObject { put("revision", revision); put("items", JsonArray(items)) })
                        put("catalog", buildJsonObject {
                            put("revision", "synthetic-catalog-1"); put("taxonomyRevision", "synthetic-taxonomy-1"); put("candidates", JsonArray(listOf(candidate())))
                            put("ingredients", buildJsonArray { for (id in listOf(INGREDIENT, OTHER)) add(buildJsonObject { put("ingredientId", id); put("componentIds", arr()) }) })
                        })
                    }.toString()))
                }
            }
            val plans = PlansStore("test", PgTransactions(source), planningAuthority,
                PlanningServicePolicy("test-rank-1", false, true, 86400, 600), PlanningCursors("test", mapOf("test" to ByteArray(32) { 7 })))
            return PlanningHttpConfiguration("test", plans, PlanningHttpVerifier { bearer ->
                bearer.token.use { if (it == "synthetic-account") PortResult.Value(VerifiedPlanningPrincipal("test", account.kind, account.principalId, account.deviceSessionId))
                    else PortResult.Failure(FailureReason.UNAUTHENTICATED) }
            }, Dispatchers.IO)
        }
        private fun owner(s: java.sql.PreparedStatement, p: VerifiedPlanningPrincipal) {
            s.setString(1, p.environment); s.setString(2, p.kind.name.lowercase()); s.setObject(3, p.principalId)
        }
    }
    private class SyntheticAuthority : KitchenAuthority {
        var locks = 0
        override fun lockPrincipal(connection: Connection, principal: VerifiedKitchenPrincipal) {
            locks++
            connection.prepareStatement("SELECT active,device,expires_at>clock_timestamp() FROM kitchen_http_test.principals WHERE kind=? AND id=? FOR UPDATE").use { s ->
                s.setString(1, principal.kind.name.lowercase()); s.setObject(2, principal.principalId)
                s.executeQuery().use { r -> if (!r.next() || !r.getBoolean(1) || r.getObject(2, UUID::class.java) != principal.deviceSessionId || !r.getBoolean(3))
                    throw KitchenFailure(KitchenFailureCode.UNAUTHENTICATED) }
            }
        }
        override fun requireProvisioningAllowed(connection: Connection, principal: VerifiedKitchenPrincipal) = Unit
        override fun validatePreferences(connection: Connection, principal: VerifiedKitchenPrincipal, proposed: JsonObject) {
            if (proposed.getValue("dietaryPatterns").jsonArray.isNotEmpty()) throw KitchenFailure(KitchenFailureCode.INPUT_INVALID)
            listOf("hardExcludedIngredientIds", "dislikedIngredientIds").flatMap { proposed.getValue(it).jsonArray.map { id -> id.jsonPrimitive.content } }
                .distinct().sorted().forEach { eligible(connection, it) }
        }
        override fun validatePantryItem(connection: Connection, principal: VerifiedKitchenPrincipal, proposed: JsonObject) {
            eligible(connection, proposed.string("ingredientId"))
            if (proposed["unit"]?.jsonPrimitive?.content?.let { it !in setOf("g", "ml") } == true) throw KitchenFailure(KitchenFailureCode.INPUT_INVALID)
        }
        private fun eligible(connection: Connection, id: String) {
            connection.prepareStatement("SELECT active FROM kitchen_http_test.ingredients WHERE id=? FOR SHARE").use { s ->
                s.setObject(1, UUID.fromString(id)); s.executeQuery().use { r ->
                    if (!r.next() || !r.getBoolean(1)) throw KitchenFailure(KitchenFailureCode.INGREDIENT_UNAVAILABLE)
                }
            }
        }
    }
    /** Application acknowledgement fault after a real COMMIT, not physical/fsync failure. */
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
                    try {
                        val result = method.invoke(actual, *(args ?: emptyArray()))
                        if (method.name == "commit") { val hook = afterCommit; afterCommit = null; hook?.invoke() }
                        if (method.name == "commit" && loseCommit) { loseCommit = false; throw SQLException("PRIVATE-COMMIT-CANARY", "08006") }
                        result
                    } catch (failure: InvocationTargetException) { throw failure.targetException }
                } as Connection
            }
        }
    }
    companion object {
        private const val INGREDIENT = "00000000-0000-4000-8000-000000000011"
        private const val OTHER = "00000000-0000-4000-8000-000000000012"
        private const val TIME = "2026-09-14T00:00:00Z"
        private val local = LocalServerConfig.fromEnvironment(emptyMap())
        private val validator = ContractBodyValidator.bundled()
        private lateinit var cluster: PostgresTestCluster
        @JvmField @ClassRule val timeout = Timeout(10, TimeUnit.MINUTES)
        @JvmStatic @BeforeClass fun start() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun stop() { if (::cluster.isInitialized) cluster.close() }
        private fun arr(vararg values: String) = JsonArray(values.map(::JsonPrimitive))
        private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
        private suspend fun objectBody(response: HttpResponse) = parse(response.bodyAsText())
        private fun HttpRequestBuilder.account(f: Fixture) { header(HttpHeaders.Authorization, "Bearer synthetic-account"); header("X-Device-Session", f.account.deviceSessionId) }
        private fun HttpRequestBuilder.guest() { header(HttpHeaders.Authorization, "Bearer synthetic-guest") }
        private suspend fun HttpClient.pantry(f: Fixture, key: UUID = UUID.randomUUID(), body: String = write()) = post("/v1/pantry/items") {
            account(f); header("Idempotency-Key", key); contentType(ContentType.Application.Json); setBody(body)
        }
        private suspend fun HttpClient.preferences(f: Fixture, key: UUID = UUID.randomUUID(), version: String = "\"1\"", body: String = """{"defaultEnergy":"little"}""") = patch("/v1/preferences") {
            account(f); header("Idempotency-Key", key); header(HttpHeaders.IfMatch, version); contentType(ContentType.Application.Json); setBody(body)
        }
        private suspend fun HttpClient.remove(f: Fixture, key: UUID = UUID.randomUUID()) = delete("/v1/pantry/items/" + INGREDIENT) {
            account(f); header("Idempotency-Key", key); header(HttpHeaders.IfMatch, "\"1\"")
        }
        private suspend fun HttpClient.plan(f: Fixture, key: UUID = UUID.randomUUID(), version: Int = 1) = post("/v1/plans") {
            account(f); header("Idempotency-Key", key); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("mode", "assemble"); put("preferenceVersion", version); put("constraints", buildJsonObject {
                put("ingredientIds", arr(INGREDIENT)); put("energy", "assemble"); put("equipmentIds", arr("bowl")); put("servings", 1)
                put("hardExcludedIngredientIds", arr()); put("tasteTags", arr())
            }) }.toString())
        }
        private fun write(id: String = INGREDIENT, expected: Int? = null) = buildJsonObject {
            put("ingredientId", id); put("presence", "available"); expected?.let { put("expectedVersion", it) }
        }.toString()
        private fun seed() = buildJsonObject {
            put("hardExcludedIngredientIds", arr()); put("dietaryPatterns", arr()); put("dislikedIngredientIds", arr()); put("equipmentIds", arr("bowl"))
        }
        private fun ingredient(id: String, name: String) = buildJsonObject {
            put("id", id); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME); put("name", name)
            put("aliases", arr()); put("category", "synthetic"); put("supportedUnits", arr("g"))
        }
        /** Copied shape from the existing planning fixture, never a content approval. */
        private fun candidate() = buildJsonObject {
            put("recipe", buildJsonObject {
                put("id", "00000000-0000-4000-8000-000000000021"); put("version", 1)
                put("createdAt", TIME); put("updatedAt", TIME); put("recipeId", "00000000-0000-4000-8000-000000000031")
                put("title", "Synthetic test recipe"); put("reviewStatus", "published"); put("reviewedAt", TIME)
                put("estimateBasis", "reviewerEstimate"); put("servings", 1)
                put("ingredients", buildJsonArray { add(buildJsonObject { put("ingredientId", INGREDIENT); put("quantity", 1); put("unit", "g"); put("optional", false) }) })
                put("steps", buildJsonArray { add(buildJsonObject {
                    put("stepId", "mix"); put("position", 1); put("instruction", "Synthetic step.")
                    put("ingredientIds", arr(INGREDIENT)); put("requiredEquipmentIds", arr("bowl")); put("mandatorySafetyStep", false)
                }) })
                put("activeMinutes", 5); put("totalMinutes", 10); put("utensilCount", 1); put("equipmentIds", arr("bowl"))
                put("modes", arr("assemble")); put("tasteTags", arr("crunch")); put("preparationTags", arr("noHeat", "oneBowl")); put("cleanupMinutes", 2)
            })
            put("review", buildJsonObject {
                put("reviewReference", "synthetic-review"); put("policyVersion", "test-rank-1")
                put("kind", "MEAL"); put("minimumEnergy", "ASSEMBLE"); put("heatingRequired", false)
                put("substantialPreparation", false); put("freeCatalogEligible", true); put("compatibleBaseTypes", arr())
                put("linearQuantityScalingReviewed", true); put("stepsValidForScalingRange", true)
                put("effortValidForScalingRange", true); put("scalableUnits", arr("g"))
            })
        }
        private suspend fun replayEquals(first: HttpResponse, replay: HttpResponse, operation: String) {
            success(first, 200, operation); success(replay, 200, operation)
            // JSON object key order is not a representation change; all values/version stay exact.
            assertEquals(objectBody(first), objectBody(replay)); assertEquals(first.headers[HttpHeaders.ETag], replay.headers[HttpHeaders.ETag])
        }
        private suspend fun success(response: HttpResponse, status: Int, operation: String) {
            val text = response.bodyAsText(); assertEquals(status, response.status.value, text); headers(response)
            if (status == 204) {
                assertEquals("", text); assertNull(response.headers[HttpHeaders.ContentType]); assertNull(response.headers[HttpHeaders.ETag])
                assertEquals(BodyValidationResult.Valid, validator.validateResponse(operation, status, null, null))
            } else {
                assertEquals("application/json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                assertEquals(BodyValidationResult.Valid, validator.validateResponse(operation, status, text.encodeToByteArray(), "application/json"))
            }
        }
        private suspend fun problem(response: HttpResponse, status: Int, code: String) {
            val text = response.bodyAsText(); assertEquals(status, response.status.value, text); headers(response)
            val body = parse(text); assertEquals(code, body.string("code")); assertEquals(status, body.getValue("status").jsonPrimitive.int)
            assertEquals(response.headers["X-Trace-Id"], body.string("traceId")); assertNull(response.headers[HttpHeaders.ETag])
            assertEquals("application/problem+json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            assertEquals(BodyValidationResult.Valid, validator.validateSchema("Problem", text.encodeToByteArray()))
            for (canary in listOf("synthetic-account", "synthetic-guest", "PRIVATE-")) assertFalse(text.contains(canary))
        }
        private fun headers(response: HttpResponse) {
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl]); assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertNotNull(UUID.fromString(response.headers["X-Trace-Id"])); assertNull(response.headers[HttpHeaders.SetCookie]); assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }
}
