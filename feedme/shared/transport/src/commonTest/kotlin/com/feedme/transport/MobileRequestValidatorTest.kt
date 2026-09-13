package com.feedme.transport

import com.feedme.contracts.PrincipalClass
import com.feedme.core.ports.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileRequestValidatorTest {
    @Test fun validAccountIntentNeedsNoCredentialsButActualPreparationStillNeedsDeviceBinding() {
        val call = updateCook()
        assertTrue(validator.accepts(call, PrincipalClass.ACCOUNT))
        assertTrue(validator.accepts(call, PrincipalClass.GUEST))
        val preparation = RequestPreparation()
        assertNull(preparation.prepare(call, PrincipalClass.ACCOUNT))
        assertNull(preparation.prepare(call, PrincipalClass.ACCOUNT, "invalid-device"))
        val account = assertNotNull(preparation.prepare(call, PrincipalClass.ACCOUNT, uuid))
        assertEquals(uuid, account.headers["X-Device-Session"])
        assertNull(assertNotNull(preparation.prepare(call, PrincipalClass.GUEST)).headers["X-Device-Session"])
    }

    @Test fun unknownNonMobileAndWrongPrincipalIntentsAreRejected() {
        assertFalse(validator.accepts(ApiCall("unknownOperation"), PrincipalClass.ACCOUNT))
        assertFalse(validator.accepts(ApiCall("adminGetHealth"), PrincipalClass.STAFF))
        assertFalse(validator.accepts(ApiCall("receiveRevenueCatWebhook"), PrincipalClass.WEBHOOK))
        assertFalse(validator.accepts(ApiCall("getMe"), PrincipalClass.GUEST))
        assertFalse(validator.accepts(ApiCall("getMe"), PrincipalClass.PUBLIC))
        assertFalse(validator.accepts(ApiCall("getServiceHealth"), PrincipalClass.ACCOUNT))
        assertTrue(validator.accepts(ApiCall("getServiceHealth"), PrincipalClass.PUBLIC))
        assertTrue(validator.accepts(ApiCall("getMe"), PrincipalClass.ACCOUNT))
    }

    @Test fun missingUnknownAndUnsafePathParametersAreRejected() {
        assertFalse(validator.accepts(updateCook(path = emptyMap()), PrincipalClass.ACCOUNT))
        assertFalse(validator.accepts(updateCook(path = mapOf("sessionId" to uuid, "extra" to uuid)), PrincipalClass.ACCOUNT))
        for (id in listOf("..", "%2e%2e", "https://other.example", "$uuid/next", "not-a-uuid", "\uD800")) {
            assertFalse(validator.accepts(updateCook(path = mapOf("sessionId" to id)), PrincipalClass.ACCOUNT))
        }
    }

    @Test fun scalarQueryRulesAndBudgetsApplyWithoutDeviceOrNetworkAccess() {
        fun accepts(query: Map<String, List<String>>) = validator.accepts(
            ApiCall("listRecipes", queryParameters = query), PrincipalClass.ACCOUNT)
        assertTrue(accepts(mapOf("limit" to listOf("50"), "q" to listOf("rice"))))
        for (query in listOf(
            mapOf("url" to listOf("https://other.example")),
            mapOf("limit" to listOf("50", "50")),
            mapOf("limit" to emptyList()),
            mapOf("limit" to listOf("01")),
            mapOf("limit" to listOf("51")),
            mapOf("q" to listOf("a".repeat(4097))),
            mapOf("q" to listOf("\uD800")),
        )) assertFalse(accepts(query))
        assertFalse(validator.accepts(ApiCall("previewInvitation"), PrincipalClass.PUBLIC))
    }

    @Test fun requiredKeyAndVersionRemainStrictWhileOnlyDeviceHeaderIsDeferred() {
        assertFalse(validator.accepts(updateCook(key = null), PrincipalClass.ACCOUNT))
        assertFalse(validator.accepts(updateCook(key = SecretText("invalid-key")), PrincipalClass.ACCOUNT))
        assertFalse(validator.accepts(updateCook(etag = null), PrincipalClass.ACCOUNT))
        for (etag in listOf("1", "*", "W/\"1\"", "\"1\", \"2\"", "\"1\"\u2028", "\"${"1".repeat(4095)}\"")) {
            assertFalse(validator.accepts(updateCook(etag = etag), PrincipalClass.ACCOUNT))
        }
        assertFalse(validator.accepts(ApiCall("getMe", idempotencyKey = SecretText(uuid)), PrincipalClass.ACCOUNT))
    }

    @Test fun completionUsesItsOwnCanonicalPreconditionsWithoutInventedIfMatch() {
        val body = json("""{"makeAgain":false,"deviceSequence":2}""")
        assertTrue(validator.accepts(ApiCall("completeCookSession", mapOf("sessionId" to uuid),
            body = body, idempotencyKey = SecretText(uuid)), PrincipalClass.ACCOUNT))
        assertFalse(validator.accepts(ApiCall("completeCookSession", mapOf("sessionId" to uuid),
            body = body, idempotencyKey = SecretText(uuid), ifMatch = "\"1\""), PrincipalClass.ACCOUNT))
    }

    @Test fun completeBodyAssertionsRejectSyntacticallyValidButInvalidQueuedPayloads() {
        for (body in listOf("{}", "null", "[]", """{"deviceSequence":-1}""",
            """{"deviceSequence":0,"status":"completed"}""", """{"deviceSequence":0,"unknown":true}""",
            """{"deviceSequence":0,"timers":[{}]}""", """{"deviceSequence":0.1}""")) {
            assertFalse(validator.accepts(updateCook(body = json(body)), PrincipalClass.ACCOUNT))
        }
        assertFalse(validator.accepts(updateCook(body = null), PrincipalClass.ACCOUNT))
        assertFalse(validator.accepts(ApiCall("getMe", body = json("{}")), PrincipalClass.ACCOUNT))
    }

    @Test fun malformedUtf8DuplicateMembersAndExcessiveNestingAreRejected() {
        for (body in listOf(
            json("""{"deviceSequence":0,"deviceSequence":1}"""),
            json("""{"deviceSequence":0,}"""),
            json("[".repeat(65) + "0" + "]".repeat(65)),
            PrivateBytes(byteArrayOf(0x22, 0x80.toByte(), 0x22)),
        )) assertFalse(validator.accepts(updateCook(body = body), PrincipalClass.ACCOUNT))
    }

    @Test fun pureValidationPreservesExactOwnedBytesAndDoesNotCreateDefaults() {
        val bytes = " { \"deviceSequence\": 1e0 } \n".encodeToByteArray()
        val call = updateCook(body = PrivateBytes(bytes))
        val expected = bytes.copyOf()
        bytes.fill(0)
        assertTrue(validator.accepts(call, PrincipalClass.ACCOUNT))
        assertTrue(validator.accepts(call, PrincipalClass.ACCOUNT))
        assertContentEquals(expected, call.body!!.copyForCodec())
        assertTrue(call.queryParameters.isEmpty())
    }

    @Test fun accountTransportRejectsInvalidIntentBeforeAnySecureStoreRead() = runTest {
        val scope = StorageScope("test", ActorKind.ACCOUNT, "owner")
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        val store = Store(null)
        val engine = MockEngine { error("Invalid intent must not reach network") }
        val transport = FeedMeTransport(ApiEndpoint.loopback("test", 8789), store, boundary,
            StandardTestDispatcher(testScheduler), EpochClock { 1000 }, ConnectivityPort { Connectivity.ONLINE }, HttpClient(engine))
        try {
            for (call in listOf(
                updateCook(path = mapOf("sessionId" to "not-a-uuid")),
                updateCook(key = null),
                updateCook(etag = "*"),
                updateCook(body = json("{}")),
                ApiCall("listRecipes", queryParameters = mapOf("limit" to listOf("51"))),
                ApiCall("getServiceHealth"),
                ApiCall("adminGetHealth"),
                ApiCall("unknownOperation"),
            )) assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(transport.execute(lease, call)).reason)
            assertEquals(0, store.reads)
            assertTrue(engine.requestHistory.isEmpty())
        } finally { transport.close() }
    }

    @Test fun publicTransportUsesCompleteIntentValidationWithoutSecureAccess() = runTest {
        val store = Store(null)
        val engine = MockEngine { error("Invalid public intent must not reach network") }
        val transport = FeedMeTransport(ApiEndpoint.loopback("test", 8789), store, SessionBoundary(),
            StandardTestDispatcher(testScheduler), EpochClock { 1000 }, ConnectivityPort { Connectivity.ONLINE }, HttpClient(engine))
        try {
            for (call in listOf(ApiCall("getMe"), ApiCall("getServiceHealth", body = json("{}")),
                ApiCall("previewInvitation", queryParameters = mapOf("token" to listOf("short"))),
                ApiCall("createGuestSession", body = json("{}"), idempotencyKey = SecretText(uuid)))) {
                assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(transport.executePublic(call)).reason)
            }
            assertEquals(0, store.reads)
            assertTrue(engine.requestHistory.isEmpty())
        } finally { transport.close() }
    }

    @Test fun actualTransportRejectsMissingOrInvalidDeviceAndBindsOnlyRealDevice() = runTest {
        val scope = StorageScope("test", ActorKind.ACCOUNT, "owner")
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        val store = Store(null)
        var exchanges = 0
        val engine = MockEngine { request ->
            exchanges++
            assertEquals(uuid, request.headers["X-Device-Session"])
            assertEquals("Bearer access-token", request.headers["Authorization"])
            respond("""{"type":"https://example.test/problems/auth","title":"Sign in","status":401,"code":"UNAUTHENTICATED","traceId":"trace"}""",
                HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/problem+json"))
        }
        val transport = FeedMeTransport(ApiEndpoint.loopback("test", 8789), store, boundary,
            StandardTestDispatcher(testScheduler), EpochClock { 1000 }, ConnectivityPort { Connectivity.ONLINE },
            HttpClient(engine) { expectSuccess = false; followRedirects = false })
        try {
            val call = ApiCall("getMe")
            assertTrue(validator.accepts(call, PrincipalClass.ACCOUNT))
            for (device in listOf(null, SecretText("invalid-device"))) {
                store.value = StoredCredentials.Account(scope, SecretText("access-token"), null, 2000, device)
                assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(transport.execute(lease, call)).reason)
            }
            assertEquals(0, exchanges)
            store.value = StoredCredentials.Account(scope, SecretText("access-token"), null, 2000, SecretText(uuid))
            assertEquals(401, assertIs<PortResult.Value<ApiReply>>(transport.execute(lease, call)).value.status)
            assertEquals(1, exchanges)
            assertEquals(3, store.reads)
        } finally { transport.close() }
    }

    private class Store(var value: StoredCredentials?) : SecureCredentialStore {
        var reads = 0
        override suspend fun read(scope: StorageScope): PortResult<StoredCredentials?> {
            reads++
            return PortResult.Value(value)
        }
        override suspend fun replace(scope: StorageScope, credentials: StoredCredentials): PortResult<Unit> = error("No credential writes")
        override suspend fun erase(scope: StorageScope): PortResult<Unit> = error("No credential writes")
    }

    private fun updateCook(path: Map<String, String> = mapOf("sessionId" to uuid), body: PrivateBytes? = json("""{"deviceSequence":1}"""),
        key: SecretText? = SecretText(uuid), etag: String? = "\"1\"") =
        ApiCall("updateCookSession", path, body = body, idempotencyKey = key, ifMatch = etag)

    private fun json(value: String) = PrivateBytes(value.encodeToByteArray())

    companion object {
        private val validator = MobileRequestValidator()
        private const val uuid = "123e4567-e89b-12d3-a456-426614174000"
    }
}
