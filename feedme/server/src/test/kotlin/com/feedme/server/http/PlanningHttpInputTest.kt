package com.feedme.server.http

import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.StoredReply
import com.feedme.server.db.*
import com.feedme.server.planning.*
import com.feedme.core.ports.*
import com.feedme.contracts.WireDocument
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

class PlanningHttpInputTest {
    @Test fun metadataDoesNotInferTokenKindAndIsRedacted() {
        val input = input(extra = listOf("X-Device-Session" to ID))
        assertEquals(ID, input.bearer.deviceSessionId.toString())
        assertEquals("synthetic-secret", input.bearer.token.use { it })
        assertNull(input.planId)
        for (value in listOf(input, input.bearer)) {
            assertFalse(value.toString().contains("synthetic-secret")); assertFalse(value.toString().contains(ID))
        }
    }

    @Test fun duplicateSecurityAndFramingHeadersAreRejectedEvenWhenIdentical() {
        for (name in listOf("Authorization", "Idempotency-Key", "Content-Type", "Content-Length", "Transfer-Encoding", "Content-Encoding", "X-Device-Session")) {
            val headers = defaults().toMutableList()
            val value = headers.firstOrNull { it.first == name }?.second ?: when (name) {
                "Content-Length" -> "100"; "Transfer-Encoding" -> "chunked"; "Content-Encoding" -> "identity"; else -> ID
            }
            headers.removeAll { it.first == name }; headers += name to value; headers += name.lowercase() to value
            denied(400) { input(headers = headers) }
        }
    }

    @Test fun bearerMissingOrNonBearerNeverBecomesGuestAuthorization() {
        denied(401) { input(headers = defaults().filterNot { it.first == "Authorization" }) }
        for (value in listOf("Basic secret", "Bearer", "Bearer one two", "Bearer =bad", "Bearer " + "x".repeat(16385)))
            denied(401) { input(headers = defaults().filterNot { it.first == "Authorization" } + ("Authorization" to value)) }
    }

    @Test fun canonicalUuidRejectsJavaShortFormsAndAcceptsExternalCaseWithoutRewritingToken() {
        for (value in listOf("1-1-1-1-1", "$ID/child", "$ID%2fchild", " $ID", "$ID "))
            denied(400) { input(extra = listOf("X-Device-Session" to value)) }
        assertEquals(ID.uppercase(), input(extra = listOf("X-Device-Session" to ID.uppercase())).bearer.deviceSessionId.toString().uppercase())
    }

    @Test fun noUndeclaredConditionalHeaderIsSilentlyHonored() {
        for (operation in planningHttpOperations) for (name in listOf("If-Match", "If-None-Match"))
            denied(400) { input(operation, extra = listOf(name to "\"1\"")) }
    }

    @Test fun queriesAreOperationSpecificUniqueAndCanonicalIntegers() {
        denied(400) { input(query = listOf("limit" to "1")) }
        denied(400) { input("getPlanExplanation", query = listOf("planId" to ID)) }
        denied(400) { input("getPlanExplanation", query = listOf("limit" to "1", "limit" to "1")) }
        denied(400) { input("getPlanExplanation", query = listOf("cursor" to "one", "cursor" to "two")) }
        for (limit in listOf("0", "51", "01", "+1", "1.0", "1e0", "-1", " 1"))
            denied(400) { input("getPlanExplanation", query = listOf("limit" to limit)) }
        assertEquals(20, input("getPlanExplanation").limit)
        assertEquals(50, input("getPlanExplanation", query = listOf("limit" to "50")).limit)
        denied(400) { input("getPlanExplanation", query = listOf("cursor" to "a".repeat(2049))) }
    }

    @Test fun framingRejectsConflictingUnknownNegativeAndOversizeLengths() {
        for (value in listOf("-1", "+1", "1,1", "65537", "18446744073709551615", " 1"))
            denied(400) { input(extra = listOf("Content-Length" to value)) }
        denied(400) { input(extra = listOf("Content-Length" to "1", "Transfer-Encoding" to "chunked")) }
        denied(400) { input(extra = listOf("Transfer-Encoding" to "gzip, chunked")) }
        assertEquals(65536L, input(extra = listOf("Content-Length" to "65536")).contentLength)
    }

    @Test fun encodedBodiesAndAmbiguousMediaAreNeverDecodedOrGuessed() {
        for (encoding in listOf("gzip", "br", "identity,identity")) denied(400) { input(extra = listOf("Content-Encoding" to encoding)) }
        for (type in listOf("text/json", "application/problem+json", "application/json; charset=utf-16", "application/json; charset=utf-8; charset=utf-8", "application/json,application/json"))
            denied(400) { input(headers = defaults().filterNot { it.first == "Content-Type" } + ("Content-Type" to type)) }
        input(headers = defaults().filterNot { it.first == "Content-Type" } + ("Content-Type" to "Application/JSON; charset=\"UTF-8\""))
    }

    @Test fun readsRejectBodiesAndIdempotencyKeys() = runBlocking<Unit> {
        denied(400) { input("getPlan", extra = listOf("Idempotency-Key" to ID)) }
        denied(400) { input("getPlan", extra = listOf("Content-Length" to "1")) }
        assertNull(input("getPlan").readBody(ByteReadChannel(ByteArray(0)), validator))
        deniedSuspend(400) { input("getPlan").readBody(ByteReadChannel(byteArrayOf(32)), validator) }
    }

    @Test fun originalNumericLexemeReachesServiceProjectionWithoutDoubleCoercion() {
        val parsed = input().body(request.replace("1.0000", "1.00000000000000000000000000000000001").encodeToByteArray(), validator)!!
        assertEquals("1.00000000000000000000000000000000001", parsed.getValue("constraints").jsonObject.getValue("servings").jsonPrimitive.content)
    }

    @Test fun malformedDuplicateAndInvalidUnicodeBodiesNeverNormalizeIntoValidInput() {
        for (body in listOf("{", request.replace("\"mode\":\"assemble\"", "\"mode\":\"assemble\",\"mode\":\"assemble\""), request.dropLast(1) + ",\"naturalLanguage\":\"\\uD800\"}"))
            denied(400) { input().body(body.encodeToByteArray(), validator) }
        denied(400) { input().body(byteArrayOf(0xc3.toByte(), 0x28), validator) }
    }

    @Test fun structurallyValidSchemaViolationsAreTypedBeforeAnyServiceUse() {
        for (body in listOf("{}", request.replace("\"assemble\"", "\"invented\""), request.dropLast(1) + ",\"ownerId\":\"$ID\"}"))
            denied(422) { input().body(body.encodeToByteArray(), validator) }
    }

    @Test fun streamingBoundAndDeclaredLengthAreCheckedAgainstActuallyReceivedBytes() = runBlocking<Unit> {
        deniedSuspend(400) { input().readBody(ByteReadChannel(ByteArray(65537) { 32 }), validator) }
        deniedSuspend(400) { input().readBody(ByteReadChannel(ByteArray(0)), validator) }
        deniedSuspend(400) { input(extra = listOf("Content-Length" to "1")).readBody(ByteReadChannel(request.encodeToByteArray()), validator) }
        val exact = request + " ".repeat(65536 - request.encodeToByteArray().size)
        assertNotNull(input(extra = listOf("Content-Length" to "65536")).readBody(ByteReadChannel(exact.encodeToByteArray()), validator))
    }

    @Test fun cancelledCallerCannotReturnBodyOrConvertCancellationIntoInputFailure() = runBlocking<Unit> {
        var entered = false
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            entered = true; currentCoroutineContext().cancel()
            input().readBody(ByteReadChannel(request.encodeToByteArray()), validator)
        }
        assertTrue(entered); assertFailsWith<CancellationException> { task.await() }
    }

    @Test fun verifierReturningAfterCancellingCallerCannotPublishVerifiedPrincipal() = runBlocking<Unit> {
        var verified = false
        val configuration = configuration(PlanningHttpVerifier {
            verified = true; currentCoroutineContext().cancel(); PortResult.Value(VerifiedPlanningPrincipal("test", CommandActor.GUEST, UUID.randomUUID(), null))
        })
        val task = async { configuration.authenticate(input()) }
        assertFailsWith<CancellationException> { task.await() }; assertTrue(verified)
    }

    @Test fun verifierCancellationPropagatesWithoutSanitizedAuthenticationFailure() = runBlocking<Unit> {
        val configuration = configuration(PlanningHttpVerifier { throw CancellationException("synthetic cancellation") })
        assertFailsWith<CancellationException> { configuration.authenticate(input()) }
    }

    @Test fun malformedResponseCannotBePromotedToSuccessfulCanonicalHttpReply() {
        assertFails { validatePlanningReply("createPlan", StoredReply(201, JsonObject(emptyMap()), "\"1\""), validator) }
        assertFails { validatePlanningReply("getPlanExplanation", StoredReply(200, buildJsonObject { put("items", JsonArray(emptyList())); put("nextCursor", JsonNull) }, "\"1\""), validator) }
    }

    companion object {
        private const val ID = "aaaaaaaa-0000-4000-8000-000000000001"
        private val validator = ContractBodyValidator.bundled()
        private val request = """{"mode":"assemble","preferenceVersion":1,"constraints":{"ingredientIds":["$ID"],"energy":"assemble","equipmentIds":["bowl"],"servings":1.0000,"hardExcludedIngredientIds":[]}}"""
        private fun configuration(verifier: PlanningHttpVerifier): PlanningHttpConfiguration {
            // No SQL expected in these verifier boundary tests: this is not an accepting adapter.
            val source = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, _, _ -> error("Unexpected database acquisition") } as DataSource
            val authority = object : PlanningAuthority {
                override fun lockPrincipal(connection: Connection, principal: VerifiedPlanningPrincipal): Unit = error("Unexpected authority")
                override fun requireNewPlanningEnabledAndQuota(connection: Connection, principal: VerifiedPlanningPrincipal): Unit = error("Unexpected authority")
                override fun lockCurrentSnapshot(connection: Connection, principal: VerifiedPlanningPrincipal, request: WireDocument): PlanningEvidenceSnapshot = error("Unexpected authority")
            }
            return PlanningHttpConfiguration("test", PlansStore("test", PgTransactions(source), authority,
                PlanningServicePolicy("test", false, true, 86400, 600), PlanningCursors("test", mapOf("test" to ByteArray(32) { 7 }))), verifier, Dispatchers.IO)
        }
        private fun defaults(operation: String = "createPlan") = buildList {
            add("Authorization" to "Bearer synthetic-secret")
            if (operation in setOf("createPlan", "nextPlan")) { add("Idempotency-Key" to ID); add("Content-Type" to "application/json") }
        }
        private fun input(operation: String = "createPlan", headers: List<Pair<String, String>> = defaults(operation), extra: List<Pair<String, String>> = emptyList(), query: List<Pair<String, String>> = emptyList()) =
            PlanningHttpInput.parse(operation, Headers.build { (headers + extra).forEach { (k,v) -> append(k,v) } },
                Parameters.build { query.forEach { (k,v) -> append(k,v) } }, parametersOf("planId", ID))
        private fun denied(status: Int, block: () -> Unit) { assertEquals(status, assertFailsWith<PlanningHttpFailure>(block = block).status) }
        private suspend fun deniedSuspend(status: Int, block: suspend () -> Unit) {
            val failure = try { block(); fail("Expected typed HTTP denial") } catch (failure: PlanningHttpFailure) { failure }
            assertEquals(status, failure.status)
        }
    }
}
