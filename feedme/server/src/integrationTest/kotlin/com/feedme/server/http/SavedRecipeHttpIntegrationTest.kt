package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.memory.*
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

/** Real Ktor/CIO/PG; token verification, editorial evidence and positive copy policy are TEST ONLY. */
class SavedRecipeHttpIntegrationTest {
    @Test fun actualSaveGetListsCollectionAndDeleteFollowAllSixCanonicalContracts() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val created = client.save(f); success(created, 201, "saveRecipe"); val saved = json(created)
        val read = client.get("/v1/saved-recipes/${saved.text("id")}") { auth(f) }; success(read, 200, "getSavedRecipe"); assertEquals(saved, json(read))
        val list = client.get("/v1/saved-recipes?limit=20") { auth(f) }; success(list, 200, "listSavedRecipes")
        assertEquals(JsonArray(listOf(saved)), json(list)["items"])
        val collections = client.get("/v1/collections") { auth(f) }; success(collections, 200, "listCollections")
        val collection = json(collections).getValue("items").jsonArray.single().jsonObject
        val detail = client.get("/v1/collections/${collection.text("id")}?limit=1") { auth(f) }; success(detail, 200, "getCollection")
        assertEquals(JsonArray(listOf(saved.getValue("id"))), json(detail)["savedRecipeIds"]); assertEquals(JsonNull, json(detail)["nextItemCursor"])
        val removed = client.remove(f, saved.text("id")); success(removed, 204, "deleteSavedRecipe")
        problem(client.get("/v1/saved-recipes/${saved.text("id")}") { auth(f) }, 404, "SAVED_RECIPE_UNAVAILABLE")
        assertEquals(listOf(1, 0, 2, 4), f.effects())
    }
    @Test fun originalSaveKeyReplaysWholeSemanticBodyVersionAndOneDomainEffect() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val key = UUID.randomUUID(); val first = client.save(f, key); same(first, client.save(f, key), 201, "saveRecipe")
        val changed = JsonObject(f.test.catalogInput() + ("title" to JsonPrimitive("changed"))).toString()
        problem(client.save(f, key, changed), 409, "IDEMPOTENCY_MISMATCH")
        assertEquals(listOf(1, 1, 1, 2), f.effects())
    }
    @Test fun configuredPlanningAndSaveShareActualPinnedPlanWithoutEnablingOtherOperations() = testApplication {
        val f = Fixture(); val plan = f.test.seedPlan()
        val planning = PlanningHttpConfiguration("test", f.test.base.plans, PlanningHttpVerifier { bearer ->
            bearer.token.use { token -> if (token == f.token(f.test.account)) PortResult.Value(f.test.base.planningActor(f.test.cooking(f.test.account)))
                else PortResult.Failure(FailureReason.UNAUTHENTICATED) }
        }, Dispatchers.IO)
        application { feedMeLocalService(local, planning = planning, savedRecipe = f.configuration()) }
        val read = client.get("/v1/plans/${plan.text("id")}") { auth(f) }; assertEquals(200, read.status.value); assertEquals(plan, json(read))
        val created = client.save(f, body = f.test.planInput(plan).toString()); success(created, 201, "saveRecipe")
        assertEquals(plan["recipeSnapshot"], json(created)["snapshot"]); assertEquals("ownPlan", json(created).text("sourceType"))
        problem(client.post("/v1/cook-sessions"), 503, "OPERATION_NOT_IMPLEMENTED")
        assertEquals(0, f.test.count("cooking.cook_sessions"))
    }
    @Test fun deletionOriginalPreconditionIsNotRebasedAndResaveIsNewIncarnation() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val saveKey = UUID.randomUUID(); val saved = json(client.save(f, saveKey)); val id = saved.text("id"); val deleteKey = UUID.randomUUID()
        problem(client.remove(f, id, etag = "\"2\""), 412, "VERSION_CONFLICT")
        success(client.remove(f, id, deleteKey), 204, "deleteSavedRecipe"); success(client.remove(f, id, deleteKey), 204, "deleteSavedRecipe")
        problem(client.save(f, saveKey), 404, "SAVED_RECIPE_UNAVAILABLE")
        val fresh = json(client.save(f)); assertNotEquals(saved["id"], fresh["id"])
        problem(client.remove(f, id, deleteKey), 412, "VERSION_CONFLICT")
        assertEquals(fresh, json(client.get("/v1/saved-recipes/${fresh.text("id")}") { auth(f) }))
    }
    @Test fun unknownApplicationCommitReceiptReturns503ThenSameKeyReconcilesOneCopy() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val key = UUID.randomUUID(); f.test.faults.loseCommit = true
        problem(client.save(f, key), 503, "OUTCOME_UNKNOWN")
        assertEquals(listOf(1, 1, 1, 2), f.effects()); success(client.save(f, key), 201, "saveRecipe")
        assertEquals(listOf(1, 1, 1, 2), f.effects())
    }
    @Test fun callerCancellationAfterActualCommitCannotBecomeDefiniteNoncommitOrReplacement() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val committed = CountDownLatch(1); val release = CountDownLatch(1); val key = UUID.randomUUID()
        f.test.faults.afterCommit = { committed.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        coroutineScope {
            val pending = async { client.save(f, key) }
            try {
                assertTrue(withContext(Dispatchers.IO) { committed.await(10, TimeUnit.SECONDS) })
                pending.cancel(); release.countDown(); assertFailsWith<CancellationException> { pending.await() }
            } finally { release.countDown(); pending.cancel() }
        }
        success(client.save(f, key), 201, "saveRecipe"); assertEquals(listOf(1, 1, 1, 2), f.effects())
    }
    @Test fun privateIdsAndMissingIdsUseSameUnavailableShapeAcrossAccountAndGuest() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val own = json(client.save(f)); val other = f.test.principal(); val guest = f.test.principal(CommandActor.GUEST, f.test.account.principalId)
        for (actor in listOf(other, guest)) for (id in listOf(own.text("id"), UUID.randomUUID().toString())) {
            problem(client.get("/v1/saved-recipes/$id") { auth(f, actor) }, 404, "SAVED_RECIPE_UNAVAILABLE")
            problem(client.remove(f, id, actor = actor), 404, "SAVED_RECIPE_UNAVAILABLE")
        }
        assertEquals(listOf(1, 1, 1, 2), f.effects())
    }
    @Test fun guestCollectionContinuationUsesVerifiedInternalGuestIdentityNotDeviceHeader() = testApplication {
        val f = Fixture(); val guest = f.test.principal(CommandActor.GUEST)
        repeat(21) { f.test.changeRecipe(guest) { JsonObject(it + ("id" to JsonPrimitive(UUID.randomUUID().toString()))) }
            f.test.store.saveRecipe(guest, UUID.randomUUID(), f.test.catalogInput(guest)) }
        application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val initial = client.get("/v1/collections") { auth(f, guest) }; success(initial, 200, "listCollections")
        val collection = json(initial).getValue("items").jsonArray.single().jsonObject
        val next = client.get("/v1/collections/${collection.text("id")}") { auth(f, guest); parameter("cursor", collection.text("nextItemCursor")); parameter("limit", 1) }
        success(next, 200, "getCollection"); assertEquals(1, json(next).getValue("savedRecipeIds").jsonArray.size); assertEquals(JsonNull, json(next)["nextItemCursor"])
        problem(client.get("/v1/collections/${collection.text("id")}") { auth(f) }, 404, "COLLECTION_UNAVAILABLE")
        problem(client.get("/v1/collections") { auth(f, guest); header("X-Device-Session", UUID.randomUUID()) }, 401, "UNAUTHENTICATED")
    }
    @Test fun verifiedPrincipalMustMatchEnvironmentTokenKindAndActualSuppliedDevice() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        problem(client.post("/v1/saved-recipes") { header(HttpHeaders.Authorization, "Bearer ${f.token(f.test.account)}"); header("Idempotency-Key", UUID.randomUUID()); contentType(ContentType.Application.Json); setBody(f.input()) }, 401, "UNAUTHENTICATED")
        f.verify = { PortResult.Value(VerifiedSavedRecipePrincipal("foreign", CommandActor.ACCOUNT, f.test.account.principalId, f.test.account.deviceSessionId)) }
        problem(client.save(f), 401, "UNAUTHENTICATED")
        f.verify = { PortResult.Value(f.test.secondDevice()) }; problem(client.save(f), 401, "UNAUTHENTICATED")
        f.verify = { PortResult.Value(f.test.principal(CommandActor.GUEST)) }; problem(client.save(f), 401, "UNAUTHENTICATED")
        assertEquals(listOf(0, 0, 0, 0), f.effects())
    }
    @Test fun currentRevocationAndCopyRecallWinBeforeCachedSaveDisclosure() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val key = UUID.randomUUID(); val saved = json(client.save(f, key)); val before = f.effects(); f.test.authority.recalled = true
        problem(client.save(f, key), 409, "RECIPE_RECALLED")
        problem(client.get("/v1/saved-recipes") { auth(f) }, 409, "RECIPE_RECALLED")
        problem(client.get("/v1/saved-recipes/${saved.text("id")}") { auth(f) }, 409, "RECIPE_RECALLED")
        f.test.authority.recalled = false; f.test.sql("UPDATE cooking_test.sessions SET active=false WHERE session_id='${f.test.account.deviceSessionId}'")
        problem(client.save(f, key), 401, "UNAUTHENTICATED"); assertEquals(before, f.effects())
    }
    @Test fun recalledCopyRemovalUsesOriginalEtagWithoutRequiringContentReadOrNewCopyPermission() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val saveKey = UUID.randomUUID(); val created = client.save(f, saveKey)
        success(created, 201, "saveRecipe")
        val saved = json(created); val id = saved.text("id"); val etag = checkNotNull(created.headers[HttpHeaders.ETag])
        val before = f.effects(); f.test.authority.recalled = true
        problem(client.get("/v1/saved-recipes/$id") { auth(f) }, 409, "RECIPE_RECALLED")
        problem(client.get("/v1/saved-recipes") { auth(f) }, 409, "RECIPE_RECALLED")
        problem(client.save(f, saveKey), 409, "RECIPE_RECALLED")
        assertEquals(before, f.effects())
        f.test.authority.newCopiesAllowed = false; f.test.authority.existingCopiesAllowed = false
        val contentCalls = f.test.authority.newCalls to f.test.authority.existingCalls
        val deleteKey = UUID.randomUUID()
        success(client.remove(f, id, deleteKey, etag), 204, "deleteSavedRecipe")
        val removedEffects = f.effects()
        success(client.remove(f, id, deleteKey, etag), 204, "deleteSavedRecipe")
        assertEquals(removedEffects, f.effects())
        assertEquals(listOf(1, 0, 2, 4), removedEffects)
        assertEquals(contentCalls, f.test.authority.newCalls to f.test.authority.existingCalls)
        assertTrue(f.test.authority.recalled)
        problem(client.get("/v1/saved-recipes/$id") { auth(f) }, 404, "SAVED_RECIPE_UNAVAILABLE")
    }
    @Test fun recalledRemovalStillRequiresCurrentOwnerOriginalVersionKeyAndEmptyBody() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val created = client.save(f); success(created, 201, "saveRecipe")
        val id = json(created).text("id"); val etag = checkNotNull(created.headers[HttpHeaders.ETag])
        val path = "/v1/saved-recipes/$id"; val before = f.effects()
        f.test.authority.recalled = true
        val contentCalls = f.test.authority.newCalls to f.test.authority.existingCalls
        problem(client.remove(f, id, etag = "\"2\""), 412, "VERSION_CONFLICT")
        problem(client.remove(f, id, etag = etag, actor = f.test.principal()), 404, "SAVED_RECIPE_UNAVAILABLE")
        problem(client.delete(path) { auth(f); header(HttpHeaders.IfMatch, etag) }, 400, "INVALID_REQUEST")
        problem(client.delete(path) { auth(f); header("Idempotency-Key", UUID.randomUUID()) }, 428, "PRECONDITION_REQUIRED")
        problem(client.delete(path) {
            auth(f); header("Idempotency-Key", UUID.randomUUID()); header(HttpHeaders.IfMatch, etag); setBody("{}")
        }, 400, "INVALID_REQUEST")
        f.test.sql("UPDATE cooking_test.sessions SET active=false WHERE session_id='${f.test.account.deviceSessionId}'")
        problem(client.remove(f, id, etag = etag), 401, "UNAUTHENTICATED")
        assertEquals(before, f.effects())
        assertEquals(contentCalls, f.test.authority.newCalls to f.test.authority.existingCalls)
    }
    @Test fun ownCopySurvivesOrdinaryRetirementWhileTrueMakeAgainRemainsUnavailable() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val key = UUID.randomUUID(); val first = client.save(f, key); val id = json(first).text("id")
        f.test.changeRecipe { JsonObject(it + ("reviewStatus" to JsonPrimitive("retired"))) }
        same(first, client.save(f, key), 201, "saveRecipe")
        success(client.get("/v1/saved-recipes/$id") { auth(f) }, 200, "getSavedRecipe")
        problem(client.save(f, body = JsonObject(f.test.catalogInput() + ("markMakeAgain" to JsonPrimitive(true))).toString()), 503, "NOT_CONFIGURED")
        success(client.remove(f, id), 204, "deleteSavedRecipe")
    }
    @Test fun strictOriginalJsonRejectsDuplicatesWrongSourceUnknownFieldsAndOversizedBody() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val id = f.test.recipe().text("id")
        problem(client.save(f, body = "{\"recipeVersionId\":\"$id\",\"recipeVersionId\":\"$id\"}"), 400, "INVALID_REQUEST")
        problem(client.save(f, body = "{\"title\":\"no source\"}"), 422, "INPUT_INVALID")
        problem(client.save(f, body = "{\"recipeVersionId\":\"$id\",\"unsupported\":true}"), 422, "INPUT_INVALID")
        problem(client.save(f, body = "{\"recipeVersionId\":\"$id\",\"title\":\"" + "x".repeat(65536) + "\"}"), 400, "INVALID_REQUEST")
        assertEquals(listOf(0, 0, 0, 0), f.effects())
    }
    @Test fun queryControlsAreUniqueOperationSpecificAndUnicodeScalarBounded() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        success(client.get("/v1/saved-recipes") { auth(f); parameter("q", "🍜".repeat(100)) }, 200, "listSavedRecipes")
        for (url in listOf("/v1/saved-recipes?limit=0", "/v1/saved-recipes?limit=51", "/v1/saved-recipes?limit=1&limit=2", "/v1/collections?q=recipe", "/v1/saved-recipes?unknown=1", "/v1/saved-recipes?q=a&q=b"))
            problem(client.get(url) { auth(f) }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/saved-recipes") { auth(f); parameter("q", "🍜".repeat(101)) }, 400, "INVALID_REQUEST")
        assertEquals(listOf(0, 0, 0, 0), f.effects())
    }
    @Test fun cursorContinuationCannotCrossFilterOwnerPurposeOrLibraryVersion() = testApplication {
        val f = Fixture(); repeat(3) { f.test.changeRecipe { JsonObject(it + ("id" to JsonPrimitive(UUID.randomUUID().toString()))) }; f.test.store.saveRecipe(f.test.account, UUID.randomUUID(), f.test.catalogInput()) }
        application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        val page = client.get("/v1/saved-recipes?limit=1") { auth(f) }; success(page, 200, "listSavedRecipes"); val cursor = json(page).text("nextCursor")
        success(client.get("/v1/saved-recipes") { auth(f); parameter("cursor", cursor); parameter("limit", 1) }, 200, "listSavedRecipes")
        problem(client.get("/v1/saved-recipes") { auth(f); parameter("cursor", cursor); parameter("q", "") }, 409, "CURSOR_INVALID")
        problem(client.get("/v1/collections") { auth(f); parameter("cursor", cursor) }, 409, "CURSOR_INVALID")
        val item = json(page).getValue("items").jsonArray.single().jsonObject
        success(client.remove(f, item.text("id")), 204, "deleteSavedRecipe")
        problem(client.get("/v1/saved-recipes") { auth(f); parameter("cursor", cursor) }, 409, "CURSOR_INVALID")
    }
    @Test fun undeclaredConditionalReadsBodiesAndKeysNeverReturn304OrMutate() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }; val saved = json(client.save(f)); val before = f.effects()
        problem(client.get("/v1/saved-recipes/${saved.text("id")}") { auth(f); header(HttpHeaders.IfNoneMatch, "\"1\"") }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/saved-recipes") { auth(f); header("Idempotency-Key", UUID.randomUUID()) }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/collections") { auth(f); setBody("x") }, 400, "INVALID_REQUEST")
        problem(client.save(f) { header(HttpHeaders.IfMatch, "\"1\"") }, 400, "INVALID_REQUEST")
        assertEquals(before, f.effects())
    }
    @Test fun deleteRequiresKeyAndStrongOriginalPreconditionAndNoBody() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }; val saved = json(client.save(f)); val path = "/v1/saved-recipes/${saved.text("id")}"; val before = f.effects()
        problem(client.delete(path) { auth(f); header("Idempotency-Key", UUID.randomUUID()) }, 428, "PRECONDITION_REQUIRED")
        problem(client.delete(path) { auth(f); header(HttpHeaders.IfMatch, "\"1\"") }, 400, "INVALID_REQUEST")
        problem(client.remove(f, saved.text("id"), etag = "W/\"1\""), 400, "INVALID_REQUEST")
        problem(client.delete(path) { auth(f); header(HttpHeaders.IfMatch, "\"1\""); header("Idempotency-Key", UUID.randomUUID()); setBody("{}") }, 400, "INVALID_REQUEST")
        assertEquals(before, f.effects())
    }
    @Test fun malformedPathsAndDuplicateHeadersDoNotSelectOrEnumeratePrivateResources() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        problem(client.get("/v1/saved-recipes/not-a-uuid") { auth(f) }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/collections/%2E%2E") { auth(f) }, 400, "INVALID_REQUEST")
        problem(client.save(f) { header(HttpHeaders.Authorization, "Bearer duplicate") }, 400, "INVALID_REQUEST")
        problem(client.save(f) { header("X-Device-Session", UUID.randomUUID()) }, 400, "INVALID_REQUEST")
        problem(client.save(f) { header("Idempotency-Key", UUID.randomUUID()) }, 400, "INVALID_REQUEST")
        assertEquals(listOf(0, 0, 0, 0), f.effects())
    }
    @Test fun authenticationFailureMetadataAndExceptionsAreSanitized() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        f.verify = { PortResult.Failure(FailureReason.RATE_LIMITED, 7) }; val limited = client.save(f)
        problem(limited, 429, "RATE_LIMITED"); assertEquals("7", limited.headers[HttpHeaders.RetryAfter])
        f.verify = { PortResult.Failure(FailureReason.FORBIDDEN) }; problem(client.save(f), 403, "FORBIDDEN")
        f.verify = { error("PRIVATE-VERIFIER-FAILURE") }; problem(client.save(f), 503, "AUTHENTICATION_UNAVAILABLE")
        assertEquals(listOf(0, 0, 0, 0), f.effects())
    }
    @Test fun responseBudgetIsEnforcedBeforeMutationNotOnlyAfterHttpReplyConstruction() = testApplication {
        val f = Fixture(128); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        problem(client.save(f), 422, "RESPONSE_TOO_LARGE")
        assertEquals(listOf(0, 0, 0, 0), f.effects()); assertEquals(0, f.test.count("memory.collections"))
    }
    @Test fun actualCioAcceptsSaveAndRejectsDuplicatedWireAuthorizationWithoutSecondEffect() = runBlocking<Unit> {
        val f = Fixture(); val server = embeddedServer(CIO, port = 0, host = local.host) { feedMeLocalService(local, savedRecipe = f.configuration()) }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            fun send(duplicate: Boolean): java.net.http.HttpResponse<String> {
                val request = java.net.http.HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/saved-recipes"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer ${f.token(f.test.account)}")
                    .header("X-Device-Session", f.test.account.deviceSessionId.toString()).header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(f.input()))
                if (duplicate) request.header("Authorization", "Bearer duplicate")
                return http.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            }
            val good = send(false); assertEquals(201, good.statusCode()); assertEquals(BodyValidationResult.Valid, validator.validateResponse("saveRecipe", 201, good.body().encodeToByteArray(), "application/json"))
            val bad = send(true); assertEquals(400, bad.statusCode()); assertEquals("INVALID_REQUEST", parse(bad.body()).text("code"))
            assertEquals(listOf(1, 1, 1, 2), f.effects())
        } finally { server.stop(gracePeriodMillis = 100, timeoutMillis = 1000) }
    }
    @Test fun actualCioRejectsAmbiguousFramingUnsupportedEncodingAndPreservesNoEffects() = runBlocking<Unit> {
        val f = Fixture(); val server = embeddedServer(CIO, port = 0, host = local.host) { feedMeLocalService(local, savedRecipe = f.configuration()) }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port; val body = f.input()
            fun raw(extra: String): String = java.net.Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 10000
                val wire = "POST /v1/saved-recipes HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nAuthorization: Bearer ${f.token(f.test.account)}\r\nX-Device-Session: ${f.test.account.deviceSessionId}\r\nIdempotency-Key: ${UUID.randomUUID()}\r\nContent-Type: application/json\r\n$extra\r\n\r\n$body"
                socket.getOutputStream().write(wire.toByteArray(Charsets.UTF_8)); socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().readText()
            }
            val size = body.toByteArray().size
            for (extra in listOf("Content-Length: $size\r\nContent-Length: $size", "Content-Length: $size\r\nTransfer-Encoding: chunked", "Content-Length: $size\r\nContent-Encoding: gzip")) {
                val response = raw(extra)
                // CIO may reject ambiguous framing before application routing with HTTP/1.0.
                // Require the actual 400 status, not an invented application Problem at this layer.
                assertTrue(Regex("^HTTP/1\\.[01] 400 [^\\r\\n]+\\r\\n").containsMatchIn(response.take(100)), response.take(200))
            }
            assertEquals(listOf(0, 0, 0, 0), f.effects())
        } finally { server.stop(gracePeriodMillis = 100, timeoutMillis = 1000) }
    }
    @Test fun defaultModuleLeavesAllSixOperationsUnavailableWithoutVerifierOrStoreReads() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local) }
        problem(client.save(f), 503, "OPERATION_NOT_IMPLEMENTED")
        for (path in listOf("/v1/saved-recipes", "/v1/collections", "/v1/collections/${UUID.randomUUID()}")) problem(client.get(path), 503, "OPERATION_NOT_IMPLEMENTED")
        assertEquals(0, f.verifications); assertEquals(listOf(0, 0, 0, 0), f.effects())
    }
    @Test fun configuredSaveDoesNotEnableFeedbackSocialCopyCustomCollectionsOrOtherGroups() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, savedRecipe = f.configuration()) }
        for (path in listOf("/v1/feedback", "/v1/collections", "/v1/posts")) problem(client.post(path), 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.get("/v1/preferences"), 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.post("/v1/cook-sessions"), 503, "OPERATION_NOT_IMPLEMENTED")
        assertEquals(0, f.verifications); assertEquals(listOf(0, 0, 0, 0), f.effects())
        client.save(f); val custom = UUID.randomUUID()
        f.test.sql("INSERT INTO memory.collections SELECT environment,actor_kind,principal_id,'$custom',1,false,'Pre-existing organization',NULL,created_at,updated_at FROM memory.collections")
        val before = f.effects()
        problem(client.save(f, body = JsonObject(f.test.catalogInput() + ("collectionId" to JsonPrimitive(custom.toString()))).toString()), 503, "NOT_CONFIGURED")
        assertEquals(before, f.effects()); assertEquals(1, f.test.count("memory.collection_items"))
    }

    private class Fixture(maxBytes: Int = 65536) {
        val test = SavedRecipeTestFixture(cluster.database()); val store = if (maxBytes == 65536) test.store else test.newStore(maxBytes)
        private val actors = linkedMapOf<String, VerifiedSavedRecipePrincipal>(); var verifications = 0
        var verify: (suspend (SavedRecipeHttpBearer) -> PortResult<VerifiedSavedRecipePrincipal>)? = null
        fun token(actor: VerifiedSavedRecipePrincipal): String = "synthetic-${actor.deviceSessionId ?: actor.guestSessionId}".also { actors[it] = actor }
        fun configuration() = SavedRecipeHttpConfiguration("test", store, SavedRecipeHttpVerifier { bearer ->
            verifications++; verify?.invoke(bearer) ?: bearer.token.use { token -> actors[token]?.let { PortResult.Value(it) } ?: PortResult.Failure(FailureReason.UNAUTHENTICATED) }
        }, Dispatchers.IO)
        fun input() = test.catalogInput().toString()
        fun effects() = listOf(test.count("memory.saved_recipes"), test.count("memory.collection_items"), test.count("platform.idempotency"), test.count("platform.outbox"))
    }
    companion object {
        @JvmField @ClassRule val timeout: Timeout = Timeout.seconds(240)
        private lateinit var cluster: PostgresTestCluster
        @JvmStatic @BeforeClass fun start() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun stop() { cluster.close() }
        private val local = LocalServerConfig.fromEnvironment(emptyMap())
        private val validator = ContractBodyValidator.bundled()
        private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
        private suspend fun json(response: HttpResponse) = parse(response.bodyAsText())
        private fun HttpRequestBuilder.auth(f: Fixture, actor: VerifiedSavedRecipePrincipal = f.test.account) {
            header(HttpHeaders.Authorization, "Bearer ${f.token(actor)}"); actor.deviceSessionId?.let { header("X-Device-Session", it) }
        }
        private suspend fun HttpClient.save(f: Fixture, key: UUID = UUID.randomUUID(), body: String = f.input(),
            actor: VerifiedSavedRecipePrincipal = f.test.account, extras: HttpRequestBuilder.() -> Unit = {}) = post("/v1/saved-recipes") {
            auth(f, actor); header("Idempotency-Key", key); contentType(ContentType.Application.Json); setBody(body); extras()
        }
        private suspend fun HttpClient.remove(f: Fixture, id: String, key: UUID = UUID.randomUUID(), etag: String = "\"1\"", actor: VerifiedSavedRecipePrincipal = f.test.account) = delete("/v1/saved-recipes/$id") {
            auth(f, actor); header("Idempotency-Key", key); header(HttpHeaders.IfMatch, etag)
        }
        private suspend fun same(first: HttpResponse, second: HttpResponse, status: Int, op: String) {
            success(first, status, op); success(second, status, op); assertEquals(json(first), json(second)); assertEquals(first.headers[HttpHeaders.ETag], second.headers[HttpHeaders.ETag])
        }
        private suspend fun success(response: HttpResponse, status: Int, op: String) {
            val text = response.bodyAsText(); assertEquals(status, response.status.value, text); headers(response)
            if (status == 204) { assertEquals("", text); assertNull(response.headers[HttpHeaders.ETag]); return }
            assertEquals("application/json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            assertEquals(BodyValidationResult.Valid, validator.validateResponse(op, status, text.encodeToByteArray(), "application/json"))
            if (op in setOf("saveRecipe", "getSavedRecipe", "getCollection")) assertEquals("\"${json(response).text("version")}\"", response.headers[HttpHeaders.ETag])
            else assertNull(response.headers[HttpHeaders.ETag])
        }
        private suspend fun problem(response: HttpResponse, status: Int, code: String) {
            val text = response.bodyAsText(); assertEquals(status, response.status.value, text); headers(response)
            val body = parse(text); assertEquals(code, body.text("code")); assertEquals(status, body.getValue("status").jsonPrimitive.int)
            assertEquals(response.headers["X-Trace-Id"], body.text("traceId")); assertNull(response.headers[HttpHeaders.ETag])
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
