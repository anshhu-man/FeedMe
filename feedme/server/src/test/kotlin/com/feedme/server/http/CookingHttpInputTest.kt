package com.feedme.server.http

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.cooking.*
import com.feedme.server.db.*
import com.feedme.server.planning.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class CookingHttpInputTest {
    @Test fun exactFourOperationsAndCanonicalControlPlacement() {
        assertEquals(setOf("createCookSession", "getCookSession", "updateCookSession", "completeCookSession"), cookingHttpOperations)
        for (operation in cookingHttpOperations) {
            val parsed = input(operation)
            assertEquals(operation, parsed.operation)
            assertEquals(operation != "getCookSession", parsed.hasBody)
            assertEquals(if (operation == "getCookSession") null else uuid, parsed.key)
            assertEquals(if (operation == "createCookSession") null else uuid, parsed.sessionId)
            assertEquals(if (operation == "updateCookSession") "\"1\"" else null, parsed.ifMatch)
        }
        denied(400) { input("saveRecipe") }
    }

    @Test fun authorizationIsMandatoryBoundedAndNotAList() {
        denied(401) { input(headers = defaults().filterNot { it.first == "Authorization" }) }
        for (value in listOf("Basic abc", "Bearer ", "Bearer a=b", "Bearer secret private", "Bearer " + "a".repeat(16385)))
            denied(401) { input(headers = defaults().filterNot { it.first == "Authorization" } + ("Authorization" to value)) }
        denied(400) { input(headers = defaults().filterNot { it.first == "Authorization" } + ("Authorization" to "Bearer a,Bearer b")) }
        assertNotNull(input(headers = defaults().filterNot { it.first == "Authorization" } + ("Authorization" to "bEaReR  abc+/==")))
    }

    @Test fun duplicateSecurityHeadersAreRejectedBeforeAnyVerification() {
        for (name in listOf("Authorization", "X-Device-Session", "Idempotency-Key", "If-Match", "Content-Type"))
            denied(400) { input(extra = listOf(name to defaults().first { it.first == name }.second)) }
        for (name in listOf("Content-Length", "Content-Encoding", "Transfer-Encoding", "If-None-Match"))
            denied(400) { input(extra = listOf(name to "0", name to "0")) }
    }

    @Test fun malformedDeviceAndCommandUuidsCannotBecomeTrustedIdentities() {
        for (name in listOf("X-Device-Session", "Idempotency-Key")) {
            for (value in listOf("1-1-1-1-1", "guest", ID + ", " + ID, " " + ID))
                denied(400) { input(headers = defaults().filterNot { it.first == name } + (name to value)) }
        }
        // A missing device header is parsed, not assumed to mean a verified guest.
        assertNull(input(headers = defaults().filterNot { it.first == "X-Device-Session" }).bearer.deviceSessionId)
    }

    @Test fun onlyMutationsRequireAnOriginalCommandKey() {
        for (operation in cookingHttpOperations - "getCookSession")
            denied(400) { input(operation, headers = defaults(operation).filterNot { it.first == "Idempotency-Key" }) }
        denied(400) { input("getCookSession", extra = listOf("Idempotency-Key" to ID)) }
    }

    @Test fun onlyPatchUsesIfMatchAndNoOperationInventsConditionalGet() {
        denied(428) { input(headers = defaults().filterNot { it.first == "If-Match" }) }
        for (operation in cookingHttpOperations - "updateCookSession")
            denied(400) { input(operation, extra = listOf("If-Match" to "\"1\"")) }
        for (operation in cookingHttpOperations)
            denied(400) { input(operation, extra = listOf("If-None-Match" to "\"1\"")) }
    }

    @Test fun weakWildcardMultiValueAndUnboundedEtagsAreRejected() {
        for (value in listOf("*", "1", "W/\"1\"", "\"-1\"", "\"1\",\"2\"", "\"" + "1".repeat(65) + "\""))
            denied(400) { input(headers = defaults().filterNot { it.first == "If-Match" } + ("If-Match" to value)) }
        assertEquals("\"9007199254740993\"", input(headers = defaults().filterNot { it.first == "If-Match" } +
            ("If-Match" to "\"9007199254740993\"")).ifMatch)
    }

    @Test fun NoQueryOrExtraPathCanAssertOwnerDeviceOrProgress() {
        for (operation in cookingHttpOperations) {
            for (name in listOf("ownerId", "deviceSequence", "cursor", "makeAgain", "guestSessionId"))
                denied(400) { input(operation, query = parametersOf(name, ID)) }
        }
        denied(400) { input(paths = Parameters.Empty) }
        denied(400) { input(paths = parametersOf("sessionId", "1-1-1-1-1")) }
        denied(400) { input(paths = Parameters.build { append("sessionId", ID); append("sessionId", ID) }) }
        denied(400) { input(paths = Parameters.build { append("sessionId", ID); append("ownerId", ID) }) }
        denied(400) { input("createCookSession", paths = parametersOf("sessionId", ID)) }
    }

    @Test fun RequestFramingRejectsAmbiguousUnsupportedAndOversizedControls() {
        for (value in listOf("-1", "+1", "65537", "18446744073709551615", "1,1"))
            denied(400) { input(extra = listOf("Content-Length" to value)) }
        denied(400) { input(extra = listOf("Content-Length" to "1", "Transfer-Encoding" to "chunked")) }
        for (value in listOf("gzip", "chunked,chunked", "identity"))
            denied(400) { input(extra = listOf("Transfer-Encoding" to value)) }
        assertNotNull(input(extra = listOf("Transfer-Encoding" to "chunked")))
        denied(400) { input("getCookSession", extra = listOf("Transfer-Encoding" to "chunked")) }
        denied(400) { input("getCookSession", extra = listOf("Content-Length" to "1")) }
    }

    @Test fun JsonMediaAndUtf8EncodingAreExplicit() {
        for (value in listOf("text/json", "application/problem+json", "application/json;charset=utf-16", "application/json;charset=utf-8;charset=utf-8"))
            denied(400) { input(headers = defaults().filterNot { it.first == "Content-Type" } + ("Content-Type" to value)) }
        for (value in listOf("gzip", "br", "identity,identity"))
            denied(400) { input(extra = listOf("Content-Encoding" to value)) }
        denied(400) { input(headers = defaults().filterNot { it.first == "Content-Type" }) }
        denied(400) { input("getCookSession", extra = listOf("Content-Type" to "application/json")) }
        assertNotNull(input(headers = defaults().filterNot { it.first == "Content-Type" } +
            ("Content-Type" to "Application/JSON; charset=\"UTF-8\"")))
    }

    @Test fun OriginalWireRejectsDuplicateMembersMalformedUnicodeAndExcessDepth() {
        for (body in listOf("{", "", """{"deviceSequence":1,"deviceSequence":2}""",
            """{"deviceSequence":1,"currentStepId":"\uD800"}""", "{} trailing",
            "[".repeat(33) + "0" + "]".repeat(33)))
            denied(400) { input().body(body.encodeToByteArray(), validator) }
        denied(400) { input().body(byteArrayOf(0xc3.toByte(), 0x28), validator) }
    }

    @Test fun StartAcceptsOnlyPlanIdentityAndOptionalExactSequence() {
        val start = input("createCookSession")
        assertEquals(ID, start.body(("""{"planId":"""" + ID + "\"}").encodeToByteArray(), validator)!!.getValue("planId").jsonPrimitive.content)
        for (body in listOf("{}", """{"planId":"bad"}""",
            """{"planId":"""" + ID + """","ownerId":"""" + ID + "\"}",
            """{"planId":"""" + ID + """","deviceSequence":-1}"""))
            denied(422) { start.body(body.encodeToByteArray(), validator) }
    }

    @Test fun PatchCannotCompleteAndCompletionCannotCarryProgressOrInventSave() {
        for (body in listOf("{}", """{"deviceSequence":1,"status":"completed"}""",
            """{"deviceSequence":1,"planId":"""" + ID + "\"}"))
            denied(422) { input().body(body.encodeToByteArray(), validator) }
        val complete = input("completeCookSession")
        for (body in listOf("""{"deviceSequence":1}""", """{"makeAgain":false}""",
            """{"deviceSequence":1,"makeAgain":false,"currentStepId":"s1"}""",
            """{"deviceSequence":1,"makeAgain":"false"}"""))
            denied(422) { complete.body(body.encodeToByteArray(), validator) }
        for (choice in listOf(false, true))
            assertEquals(choice, complete.body(("""{"deviceSequence":1,"makeAgain":""" + choice + "}").encodeToByteArray(), validator)!!
                .getValue("makeAgain").jsonPrimitive.boolean)
        // true is canonical wire, not permission to silently ignore the required save workflow.
    }

    @Test fun SequenceProjectionNeverRoundsThroughDouble() {
        for (sequence in listOf("9007199254740993", "1e3", "9223372036854775807")) {
            val body = input().body(("""{"deviceSequence":""" + sequence + "}").encodeToByteArray(), validator)!!
            assertEquals(sequence, body.getValue("deviceSequence").jsonPrimitive.content)
        }
        denied(422) { input().body("""{"deviceSequence":1.5}""".encodeToByteArray(), validator) }
    }

    @Test fun TimerWireRejectsInventedStatusFractionalSecondsAndPrivateAnchors() {
        for (timer in listOf(
            """{"timerId":"""" + ID + """","stepId":"s1","status":"cancelled","durationSeconds":3}""",
            """{"timerId":"""" + ID + """","stepId":"s1","status":"paused","durationSeconds":1.5}""",
            """{"timerId":"""" + ID + """","stepId":"s1","status":"running","durationSeconds":3,"nativeTicket":"private"}"""))
            denied(422) { input().body(("""{"deviceSequence":1,"timers":[""" + timer + "]}").encodeToByteArray(), validator) }
    }

    @Test fun StreamingBoundsActualBytesAndDeclaredLengthWithoutAcceptingAnEmptyBody() = runBlocking<Unit> {
        deniedSuspend(400) { input().readBody(ByteReadChannel(ByteArray(65537) { 32 }), validator) }
        deniedSuspend(400) { input().readBody(ByteReadChannel(ByteArray(0)), validator) }
        deniedSuspend(400) { input(extra = listOf("Content-Length" to "1")).readBody(ByteReadChannel("""{"deviceSequence":1}""".encodeToByteArray()), validator) }
        val body = """{"deviceSequence":1}""".padEnd(65536, ' ')
        assertNotNull(input(extra = listOf("Content-Length" to "65536")).readBody(ByteReadChannel(body.encodeToByteArray()), validator))
        assertNull(input("getCookSession").readBody(ByteReadChannel(ByteArray(0)), validator))
        deniedSuspend(400) { input("getCookSession").readBody(ByteReadChannel(byteArrayOf(32)), validator) }
    }

    @Test fun EverySuccessHasExactOperationStatusCanonicalBodyAndVersionMatchingEtag() {
        val session = obj(SESSION)
        for (operation in cookingHttpOperations) {
            val status = if (operation == "createCookSession") 201 else 200
            assertEquals(SESSION, validateCookingReply(operation, StoredReply(status, session, "\"1\""), validator, 65536))
            for (bad in listOf(StoredReply(status, session, "\"2\""), StoredReply(status, session), StoredReply(204),
                StoredReply(if (status == 201) 200 else 201, session, "\"1\""), StoredReply(status, obj("{}"), "\"1\"")))
                assertFailsWith<IllegalStateException> { validateCookingReply(operation, bad, validator, 65536) }
            assertFailsWith<IllegalStateException> { validateCookingReply(operation, StoredReply(status, session, "\"1\""), validator, 1) }
        }
    }

    @Test fun VerifiedAccountAndBoundedGuestMustMatchActualDeviceAndEnvironment() = runBlocking<Unit> {
        val account = account()
        val guest = VerifiedCookingPrincipal("test", CommandActor.GUEST, uuid, null, uuid)
        val guestInput = input(headers = defaults().filterNot { it.first == "X-Device-Session" })
        assertSame(account, configuration { PortResult.Value(account) }.authenticate(input()))
        assertSame(guest, configuration { PortResult.Value(guest) }.authenticate(guestInput))
        deniedSuspend(401) { configuration { PortResult.Value(guest) }.authenticate(input()) }
        deniedSuspend(401) { configuration { PortResult.Value(account) }.authenticate(guestInput) }
        for (actor in listOf(VerifiedCookingPrincipal("other", CommandActor.ACCOUNT, uuid, uuid),
            VerifiedCookingPrincipal("test", CommandActor.ACCOUNT, uuid, UUID.randomUUID())))
            deniedSuspend(401) { configuration { PortResult.Value(actor) }.authenticate(input()) }
    }

    @Test fun VerifierFailuresPreserveSafeRetryMetadataAndNeverReachDatabase() = runBlocking<Unit> {
        for ((reason, status) in listOf(FailureReason.UNAUTHENTICATED to 401, FailureReason.STALE_SESSION to 401,
            FailureReason.INVALID_DATA to 401, FailureReason.NOT_FOUND to 401, FailureReason.FORBIDDEN to 403,
            FailureReason.RATE_LIMITED to 429, FailureReason.UNAVAILABLE to 503)) {
            val failure = deniedSuspend(status) { configuration { PortResult.Failure(reason, 7) }.authenticate(input()) }
            assertEquals(if (status in setOf(429, 503)) 7L else null, failure.retryAfterSeconds)
        }
        val failure = deniedSuspend(503) { configuration { error("PRIVATE-PROVIDER-CANARY") }.authenticate(input()) }
        assertFalse(failure.toString().contains("PRIVATE-PROVIDER-CANARY"))
    }

    @Test fun VerifierCannotSwallowCancellationToPublishAPrincipal() = runBlocking<Unit> {
        val job = Job()
        val config = configuration {
            job.cancel()
            withContext(NonCancellable) { PortResult.Value(account()) }
        }
        assertFailsWith<CancellationException> { withContext(job) { config.authenticate(input()) } }
    }

    @Test fun CredentialInputAndConfigurationDiagnosticsAreRedacted() {
        val parsed = input()
        assertFalse(parsed.toString().contains(ID))
        assertFalse(parsed.toString().contains("synthetic-secret"))
        assertFalse(parsed.bearer.toString().contains("synthetic-secret"))
        assertEquals("CookingHttpConfiguration(<redacted>)", configuration { error("Unused") }.toString())
    }

    private fun input(operation: String = "updateCookSession", headers: List<Pair<String, String>> = defaults(operation),
        extra: List<Pair<String, String>> = emptyList(), query: Parameters = Parameters.Empty,
        paths: Parameters = if (operation == "createCookSession") Parameters.Empty else parametersOf("sessionId", ID)) =
        CookingHttpInput.parse(operation, Headers.build { (headers + extra).forEach { append(it.first, it.second) } }, query, paths)
    private fun defaults(operation: String = "updateCookSession") = buildList {
        add("Authorization" to "Bearer synthetic-secret"); add("X-Device-Session" to ID)
        if (operation != "getCookSession") { add("Idempotency-Key" to ID); add("Content-Type" to "application/json") }
        if (operation == "updateCookSession") add("If-Match" to "\"1\"")
    }
    private fun denied(status: Int, action: () -> Any?) =
        assertFailsWith<CookingHttpFailure> { action() }.also { assertEquals(status, it.status) }
    private suspend fun deniedSuspend(status: Int, action: suspend () -> Any?) =
        assertFailsWith<CookingHttpFailure> { action() }.also { assertEquals(status, it.status) }
    private fun account() = VerifiedCookingPrincipal("test", CommandActor.ACCOUNT, uuid, uuid)
    private fun configuration(verifier: suspend (CookingHttpBearer) -> PortResult<VerifiedCookingPrincipal>): CookingHttpConfiguration {
        val source = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, _, _ ->
            error("Database must not be touched by HTTP input tests")
        } as DataSource
        val transactions = PgTransactions(source)
        val planning = object : PlanningAuthority {
            override fun lockPrincipal(connection: Connection, principal: VerifiedPlanningPrincipal) = error("Unexpected authority")
            override fun requireNewPlanningEnabledAndQuota(connection: Connection, principal: VerifiedPlanningPrincipal) = error("Unexpected quota")
            override fun lockCurrentSnapshot(connection: Connection, principal: VerifiedPlanningPrincipal, request: WireDocument): PlanningEvidenceSnapshot = error("Unexpected catalog")
        }
        val authority = object : CookingAuthority {
            override fun lockPrincipal(connection: Connection, principal: VerifiedCookingPrincipal) = error("Unexpected authority")
            override fun requireNewCookingEnabled(connection: Connection, principal: VerifiedCookingPrincipal) = error("Unexpected create")
            override fun validatePersonalNotes(connection: Connection, principal: VerifiedCookingPrincipal, notes: JsonArray) = error("Unexpected notes")
        }
        val plans = PlansStore("test", transactions, planning, PlanningServicePolicy("test-v1", false, false, 600, 60),
            PlanningCursors("test", mapOf("test" to ByteArray(32) { 1 })))
        val store = CookingStore("test", transactions, authority, plans, CookingServicePolicy(65536, 3600))
        return CookingHttpConfiguration("test", store, CookingHttpVerifier(verifier), Dispatchers.IO)
    }
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    companion object {
        private const val ID = "00000000-0000-4000-8000-000000000011"
        private val uuid = UUID.fromString(ID)
        private val validator = ContractBodyValidator.bundled()
        private const val SESSION = """{"id":"00000000-0000-4000-8000-000000000011","version":1,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","planId":"00000000-0000-4000-8000-000000000021","status":"active","currentStepId":"s1","completedStepIds":[],"deviceSequence":0,"timers":[]}"""
    }
}
