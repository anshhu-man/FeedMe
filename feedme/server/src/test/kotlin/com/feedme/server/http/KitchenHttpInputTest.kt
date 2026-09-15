package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandActor
import com.feedme.server.db.PgTransactions
import com.feedme.server.db.StoredReply
import com.feedme.server.kitchen.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class KitchenHttpInputTest {
    @Test fun sixCanonicalOperationsKeepTheirActualMethodsAndMutationControls() {
        val paths = KitchenHttpInputTest::class.java.getResourceAsStream("/feedme-openapi.json")!!.use {
            Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject.getValue("paths").jsonObject
        }
        val expected = mapOf("getPreferences" to ("get" to "/v1/preferences"),
            "updatePreferences" to ("patch" to "/v1/preferences"), "listPantry" to ("get" to "/v1/pantry/items"),
            "upsertPantryItem" to ("post" to "/v1/pantry/items"), "removePantryItem" to ("delete" to "/v1/pantry/items/{ingredientId}"),
            "searchIngredients" to ("get" to "/v1/ingredients"))
        assertEquals(expected.keys, kitchenHttpOperations)
        for ((operation, location) in expected) {
            val spec = paths.getValue(location.second).jsonObject.getValue(location.first).jsonObject
            assertEquals(operation, spec.getValue("operationId").jsonPrimitive.content)
            assertEquals(operation in mutations, spec.getValue("x-idempotency-required").jsonPrimitive.boolean)
            assertEquals("both", spec.getValue("x-principal").jsonPrimitive.content)
            val value = input(operation)
            assertEquals(operation in bodies, value.hasBody)
            assertEquals(operation in mutations, value.key != null)
            assertEquals(operation in versions, value.ifMatch != null)
            assertEquals(operation == "removePantryItem", value.ingredientId != null)
        }
    }

    @Test fun metadataDiagnosticsRedactTokenQueryAndPrivateIdentifiers() {
        val value = input("searchIngredients", query = listOf("q" to "PRIVATE-QUERY", "cursor" to "PRIVATE-CURSOR"))
        assertEquals("synthetic-secret", value.bearer.token.use { it })
        for (item in listOf(value, value.bearer, value.bearer.token))
            for (secret in listOf("synthetic-secret", ID, "PRIVATE-QUERY", "PRIVATE-CURSOR")) assertFalse(item.toString().contains(secret))
    }

    @Test fun bearerIsRequiredButDevicePresenceDoesNotClassifyTheToken() {
        denied(401) { input(headers = defaults().filterNot { it.first == "Authorization" }) }
        assertNull(input(headers = defaults().filterNot { it.first == "X-Device-Session" }).bearer.deviceSessionId)
        for (text in listOf("Basic secret", "Bearer", "Bearer two tokens", "Bearer =bad", "Bearer " + "x".repeat(16385)))
            denied(401) { input(headers = defaults().filterNot { it.first == "Authorization" } + ("Authorization" to text)) }
    }

    @Test fun duplicateAndFoldedSecurityOrFramingHeadersAreRejected() {
        for (name in listOf("Authorization", "X-Device-Session", "Idempotency-Key", "If-Match",
            "If-None-Match", "Content-Type", "Content-Length", "Transfer-Encoding", "Content-Encoding")) {
            val headers = defaults().toMutableList()
            val value = headers.firstOrNull { it.first == name }?.second ?: when (name) {
                "Content-Length" -> "2"; "Transfer-Encoding" -> "chunked"; "Content-Encoding" -> "identity"; else -> "\"1\""
            }
            headers.removeAll { it.first == name }; headers += name to value; headers += name.lowercase() to value
            denied(400) { input(headers = headers) }
        }
        denied(400) { input(headers = defaults().filterNot { it.first == "Authorization" } +
            ("Authorization" to "Bearer synthetic-secret, Bearer synthetic-secret")) }
    }

    @Test fun onlyTwoMutationsRequireQuotedIntegerIfMatchAndMissingIs428() {
        for (operation in versions) {
            denied(428) { input(operation, headers = defaults(operation).filterNot { it.first == "If-Match" }) }
            for (value in listOf("*", "W/\"1\"", "1", "\"-1\"", "\"1.0\"", "\"1\",\"2\"", "\"" + "1".repeat(65) + "\""))
                denied(400) { input(operation, headers = defaults(operation).filterNot { it.first == "If-Match" } + ("If-Match" to value)) }
            assertEquals("\"0001\"", input(operation, headers = defaults(operation).filterNot { it.first == "If-Match" } + ("If-Match" to "\"0001\"")).ifMatch)
        }
        for (operation in kitchenHttpOperations - versions) denied(400) { input(operation, extra = listOf("If-Match" to "\"1\"")) }
    }

    @Test fun readsCannotAcquireCommandKeysOrUnspecifiedConditionalResponses() {
        for (operation in kitchenHttpOperations) denied(400) { input(operation, extra = listOf("If-None-Match" to "\"1\"")) }
        for (operation in kitchenHttpOperations - mutations) denied(400) { input(operation, extra = listOf("Idempotency-Key" to ID)) }
        for (operation in mutations) denied(400) { input(operation, headers = defaults(operation).filterNot { it.first == "Idempotency-Key" }) }
    }

    @Test fun uuidPathsDeviceAndKeysRejectShorthandOrInjectedSegments() {
        for (bad in listOf("1-1-1-1-1", ID + "/child", ID + "%2Fchild", " " + ID, ID + " ")) {
            denied(400) { input("removePantryItem", paths = parametersOf("ingredientId", bad)) }
            for (name in listOf("X-Device-Session", "Idempotency-Key"))
                denied(400) { input(headers = defaults().filterNot { it.first == name } + (name to bad)) }
        }
        assertEquals(UUID.fromString(ID), input("removePantryItem", paths = parametersOf("ingredientId", ID.uppercase())).ingredientId)
        denied(400) { input("removePantryItem", paths = parametersOf("ingredientId", listOf(ID, ID))) }
    }

    @Test fun paginationIsUniqueBoundedAndOnlyOnCanonicalReadOperations() {
        for (operation in listOf("listPantry", "searchIngredients")) {
            assertEquals(20, input(operation).limit)
            assertEquals(50, input(operation, query = listOf("limit" to "50")).limit)
            for (bad in listOf("0", "51", "01", "+1", "1e0", "1.0", " 1"))
                denied(400) { input(operation, query = listOf("limit" to bad)) }
            denied(400) { input(operation, query = listOf("limit" to "1", "limit" to "1")) }
            denied(400) { input(operation, query = listOf("cursor" to "a", "cursor" to "b")) }
            denied(400) { input(operation, query = listOf("cursor" to "a".repeat(2049))) }
        }
        denied(400) { input("getPreferences", query = listOf("limit" to "1")) }
        denied(400) { input("listPantry", query = listOf("q" to "private")) }
        denied(400) { input("searchIngredients", query = listOf("ownerId" to ID)) }
    }

    @Test fun searchKeepsAbsentEmptyAndUnicodeQueriesDistinctWithoutExceedingScalarLimit() {
        assertNull(input("searchIngredients").query)
        assertEquals("", input("searchIngredients", query = listOf("q" to "")).query)
        val emoji = "\uD83E\uDD51".repeat(100)
        assertEquals(emoji, input("searchIngredients", query = listOf("q" to emoji)).query)
        for (bad in listOf("a".repeat(101), emoji + "x", "\uD800", "x\u0000y"))
            denied(400) { input("searchIngredients", query = listOf("q" to bad)) }
        denied(400) { input("searchIngredients", query = listOf("q" to "a", "q" to "a")) }
    }

    @Test fun readAndDeleteBodiesMustBeGenuinelyAbsent() = runBlocking<Unit> {
        for (operation in kitchenHttpOperations - bodies) {
            assertNull(input(operation).readBody(ByteReadChannel(ByteArray(0)), validator))
            deniedSuspend(400) { input(operation).readBody(ByteReadChannel(byteArrayOf(32)), validator) }
            for (header in listOf("Content-Type" to "application/json", "Content-Length" to "1", "Transfer-Encoding" to "chunked"))
                denied(400) { input(operation, extra = listOf(header)) }
        }
    }

    @Test fun framingRejectsLengthOverflowAmbiguityAndTransferEncodingConflicts() {
        for (length in listOf("-1", "+1", "1,1", "65537", "18446744073709551615", " 1"))
            denied(400) { input(extra = listOf("Content-Length" to length)) }
        denied(400) { input(extra = listOf("Content-Length" to "2", "Transfer-Encoding" to "chunked")) }
        denied(400) { input(extra = listOf("Transfer-Encoding" to "gzip,chunked")) }
        assertEquals(65536L, input(extra = listOf("Content-Length" to "65536")).contentLength)
    }

    @Test fun mediaCharsetAndCompressionAreNeverGuessed() {
        for (value in listOf("text/json", "application/problem+json", "application/json;charset=utf-16", "application/json;charset=utf-8;charset=utf-8"))
            denied(400) { input(headers = defaults().filterNot { it.first == "Content-Type" } + ("Content-Type" to value)) }
        for (value in listOf("gzip", "br", "identity,identity")) denied(400) { input(extra = listOf("Content-Encoding" to value)) }
        assertNotNull(input(headers = defaults().filterNot { it.first == "Content-Type" } + ("Content-Type" to "Application/JSON; charset=\"UTF-8\"")))
    }

    @Test fun duplicateMalformedUnicodeAndInvalidUtf8FailBeforeJsonProjection() {
        for (body in listOf("{", """{"defaultServings":1,"defaultServings":2}""", """{"consentVersion":"\uD800"}""", "{} trailing"))
            denied(400) { input().body(body.encodeToByteArray(), validator) }
        denied(400) { input().body(byteArrayOf(0xc3.toByte(), 0x28), validator) }
    }

    @Test fun schemasRejectPrivateOwnerFieldsUnknownSelectionsAndMissingPantryInputs() {
        for (body in listOf("""{"ownerId":"private"}""", """{"hardExcludedIngredientIds":["unknown"]}""", """{"preferredTasteTags":["unsafe"]}""", """{"defaultServings":0}"""))
            denied(422) { input().body(body.encodeToByteArray(), validator) }
        denied(422) { input("upsertPantryItem").body("{}".encodeToByteArray(), validator) }
        assertEquals(JsonObject(emptyMap()), input().body("{}".encodeToByteArray(), validator))
    }

    @Test fun exactDecimalAndIntegerLexemesReachCommandProjectionWithoutRounding() {
        val decimal = "1.2300000000000000000000000000000000001"
        assertEquals(decimal, input().body(("{\"defaultServings\":" + decimal + "}").encodeToByteArray(), validator)!!.getValue("defaultServings").jsonPrimitive.content)
        val version = "9007199254740993"
        val pantry = "{\"ingredientId\":\"" + ID + "\",\"presence\":\"low\",\"expectedVersion\":" + version + ",\"quantity\":0.0000001}"
        val parsed = input("upsertPantryItem").body(pantry.encodeToByteArray(), validator)!!
        assertEquals(version, parsed.getValue("expectedVersion").jsonPrimitive.content)
        assertEquals("0.0000001", parsed.getValue("quantity").jsonPrimitive.content)
    }

    @Test fun streamingEnforcesActualBytesAndDeclaredLengthAtTheExactLimit() = runBlocking<Unit> {
        deniedSuspend(400) { input().readBody(ByteReadChannel(ByteArray(65537) { 32 }), validator) }
        deniedSuspend(400) { input().readBody(ByteReadChannel(ByteArray(0)), validator) }
        deniedSuspend(400) { input(extra = listOf("Content-Length" to "1")).readBody(ByteReadChannel("{}".encodeToByteArray()), validator) }
        val exact = "{}" + " ".repeat(65534)
        assertNotNull(input(extra = listOf("Content-Length" to "65536")).readBody(ByteReadChannel(exact.encodeToByteArray()), validator))
    }

    @Test fun responseSchemasStatusesEtagsAndConfiguredBoundsAreCheckedExactly() {
        val preference = objectJson(PREFERENCE)
        assertNotNull(validateKitchenReply("getPreferences", StoredReply(200, preference, "\"1\""), validator, 65536))
        assertFailsWith<IllegalStateException> { validateKitchenReply("getPreferences", StoredReply(200, preference, "\"2\""), validator, 65536) }
        assertFailsWith<IllegalStateException> { validateKitchenReply("getPreferences", StoredReply(201, preference, "\"1\""), validator, 65536) }
        assertFailsWith<IllegalStateException> { validateKitchenReply("getPreferences", StoredReply(200, preference, "\"1\""), validator, 1) }
        assertFailsWith<IllegalStateException> { validateKitchenReply("getPreferences", StoredReply(200, objectJson("{}"), "\"1\""), validator, 65536) }
    }

    @Test fun collectionReadsAndDeletionNeverInventEtagOrEmptyJsonBody() {
        val page = objectJson("""{"items":[],"nextCursor":null,"serverTime":"2026-09-14T00:00:00Z"}""")
        for (operation in listOf("listPantry", "searchIngredients")) {
            assertNotNull(validateKitchenReply(operation, StoredReply(200, page), validator, 65536))
            assertFailsWith<IllegalStateException> { validateKitchenReply(operation, StoredReply(200, page, "\"1\""), validator, 65536) }
        }
        assertNull(validateKitchenReply("removePantryItem", StoredReply(204), validator, 65536))
        assertFailsWith<IllegalArgumentException> { StoredReply(204, objectJson("{}")) }
        assertFailsWith<IllegalStateException> { validateKitchenReply("removePantryItem", StoredReply(204, etag = "\"1\""), validator, 65536) }
    }

    @Test fun verifiedAccountAndGuestMustMatchEnvironmentAndActualDeviceBinding() = runBlocking<Unit> {
        val account = VerifiedKitchenPrincipal("test", CommandActor.ACCOUNT, UUID.fromString(ID), UUID.fromString(ID))
        assertSame(account, configuration { PortResult.Value(account) }.authenticate(input()))
        val guest = VerifiedKitchenPrincipal("test", CommandActor.GUEST, UUID.fromString(ID), null)
        val guestInput = input(headers = defaults().filterNot { it.first == "X-Device-Session" })
        assertSame(guest, configuration { PortResult.Value(guest) }.authenticate(guestInput))
        deniedSuspend(401) { configuration { PortResult.Value(guest) }.authenticate(input()) }
        deniedSuspend(401) { configuration { PortResult.Value(account) }.authenticate(guestInput) }
        for (actor in listOf(VerifiedKitchenPrincipal("other", CommandActor.ACCOUNT, account.principalId, account.deviceSessionId),
            VerifiedKitchenPrincipal("test", CommandActor.ACCOUNT, account.principalId, UUID.randomUUID())))
            deniedSuspend(401) { configuration { PortResult.Value(actor) }.authenticate(input()) }
    }

    @Test fun verifierFailuresAreSanitizedWithExactRetryMetadataAndNoStoreCalls() = runBlocking<Unit> {
        for ((reason, status) in listOf(FailureReason.UNAUTHENTICATED to 401, FailureReason.STALE_SESSION to 401,
            FailureReason.FORBIDDEN to 403, FailureReason.RATE_LIMITED to 429, FailureReason.UNAVAILABLE to 503)) {
            val failure = deniedSuspend(status) { configuration { PortResult.Failure(reason, 7) }.authenticate(input()) }
            assertEquals(if (status in setOf(429, 503)) 7L else null, failure.retryAfterSeconds)
        }
        val failure = deniedSuspend(503) { configuration { error("PRIVATE-PROVIDER-CANARY") }.authenticate(input()) }
        assertFalse(failure.toString().contains("PRIVATE-PROVIDER-CANARY"))
    }

    @Test fun cancellationCannotBeSwallowedByVerifierIntoAPrincipal() = runBlocking<Unit> {
        val job = Job()
        val configuration = configuration {
            job.cancel()
            withContext(NonCancellable) { PortResult.Value(VerifiedKitchenPrincipal("test", CommandActor.ACCOUNT, UUID.fromString(ID), UUID.fromString(ID))) }
        }
        assertFailsWith<CancellationException> { withContext(job) { configuration.authenticate(input()) } }
    }

    private fun input(operation: String = "updatePreferences", headers: List<Pair<String, String>> = defaults(operation),
        extra: List<Pair<String, String>> = emptyList(), query: List<Pair<String, String>> = emptyList(),
        paths: Parameters = parametersOf("ingredientId", ID)): KitchenHttpInput =
        KitchenHttpInput.parse(operation, Headers.build { (headers + extra).forEach { append(it.first, it.second) } },
            Parameters.build { query.forEach { append(it.first, it.second) } }, paths)
    private fun defaults(operation: String = "updatePreferences"): List<Pair<String, String>> = buildList {
        add("Authorization" to "Bearer synthetic-secret"); add("X-Device-Session" to ID)
        if (operation in mutations) add("Idempotency-Key" to ID)
        if (operation in versions) add("If-Match" to "\"1\"")
        if (operation in bodies) add("Content-Type" to "application/json")
    }
    private fun denied(status: Int, action: () -> Any?): KitchenHttpFailure =
        assertFailsWith<KitchenHttpFailure> { action() }.also { assertEquals(status, it.status) }
    private suspend fun deniedSuspend(status: Int, action: suspend () -> Any?): KitchenHttpFailure =
        assertFailsWith<KitchenHttpFailure> { action() }.also { assertEquals(status, it.status) }
    private fun configuration(verifier: suspend (KitchenHttpBearer) -> PortResult<VerifiedKitchenPrincipal>): KitchenHttpConfiguration {
        val source = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, _, _ ->
            error("Database must not be touched in HTTP authentication tests")
        } as DataSource
        val authority = object : KitchenAuthority {
            override fun lockPrincipal(connection: Connection, principal: VerifiedKitchenPrincipal) = error("Unexpected database authority")
            override fun requireProvisioningAllowed(connection: Connection, principal: VerifiedKitchenPrincipal) = error("Unexpected provisioning")
            override fun validatePreferences(connection: Connection, principal: VerifiedKitchenPrincipal, proposed: JsonObject) = error("Unexpected validation")
            override fun validatePantryItem(connection: Connection, principal: VerifiedKitchenPrincipal, proposed: JsonObject) = error("Unexpected validation")
        }
        return KitchenHttpConfiguration("test", KitchenStore("test", PgTransactions(source), authority,
            KitchenCursorCodec("test", mapOf("test" to ByteArray(32) { 1 })), KitchenServicePolicy(65536, 600)),
            KitchenHttpVerifier(verifier), KitchenIngredientSearch { _, _, _, _, _ -> error("Unexpected search") }, Dispatchers.IO)
    }
    private fun objectJson(text: String) = Json.parseToJsonElement(text).jsonObject
    companion object {
        private const val ID = "00000000-0000-4000-8000-000000000011"
        private val bodies = setOf("updatePreferences", "upsertPantryItem")
        private val mutations = bodies + "removePantryItem"
        private val versions = setOf("updatePreferences", "removePantryItem")
        private const val PREFERENCE = """{"id":"00000000-0000-4000-8000-000000000021","version":1,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","hardExcludedIngredientIds":[],"dietaryPatterns":[],"dislikedIngredientIds":[],"equipmentIds":[]}"""
        private val validator = ContractBodyValidator.bundled()
    }
}
