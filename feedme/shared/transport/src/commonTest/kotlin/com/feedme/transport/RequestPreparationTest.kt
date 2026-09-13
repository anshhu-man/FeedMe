package com.feedme.transport

import com.feedme.contracts.ContractCatalog
import com.feedme.contracts.ContractSurface
import com.feedme.contracts.PrincipalClass
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.ApiCall
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.SecretText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestPreparationTest {
    private val preparation = RequestPreparation(catalog)

    @Test fun publicHealthHasOnlyItsDeclaredPathAndNoDefaultsOrCredentials() {
        val prepared = assertNotNull(preparation.prepare(ApiCall("getServiceHealth"), PrincipalClass.PUBLIC, "ignored-account-session"))
        assertEquals("GET", prepared.operation.method)
        assertEquals(listOf("v1", "health"), prepared.pathSegments)
        assertTrue(prepared.query.isEmpty())
        assertTrue(prepared.headers.isEmpty())
        assertNull(prepared.body)
    }

    @Test fun onlyCanonicalMobileOperationsAndAllowedPrincipalClassesArePrepared() {
        assertNull(preparation.prepare(ApiCall("notAnOperation"), PrincipalClass.PUBLIC))
        assertNull(preparation.prepare(ApiCall("adminGetHealth"), PrincipalClass.STAFF))
        assertNull(preparation.prepare(ApiCall("receiveRevenueCatWebhook"), PrincipalClass.WEBHOOK))
        PrincipalClass.entries.filter { it != PrincipalClass.PUBLIC }.forEach {
            assertNull(preparation.prepare(ApiCall("getServiceHealth"), it, session))
        }
        assertNull(preparation.prepare(ApiCall("getMe"), PrincipalClass.GUEST, session))
        assertNull(preparation.prepare(ApiCall("getMe"), PrincipalClass.PUBLIC, session))
        assertNull(preparation.prepare(ApiCall("listRecipes"), PrincipalClass.PUBLIC))
        assertEquals(ContractSurface.MOBILE, assertNotNull(catalog.operation("listRecipes")).surface)
    }

    @Test fun accountReadsRequireTheRegisteredDeviceSession() {
        assertNull(preparation.prepare(ApiCall("getMe"), PrincipalClass.ACCOUNT))
        assertNull(preparation.prepare(ApiCall("getMe"), PrincipalClass.ACCOUNT, "invalid"))
        assertNull(preparation.prepare(ApiCall("getMe"), PrincipalClass.ACCOUNT, "$session\r\nInjected: yes"))
        val prepared = assertNotNull(preparation.prepare(ApiCall("getMe"), PrincipalClass.ACCOUNT, session))
        assertEquals(mapOf("X-Device-Session" to session), prepared.headers)
        assertFalse(prepared.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
    }

    @Test fun dualPrincipalReadsRequireAccountSessionButOmitItForGuests() {
        assertNull(preparation.prepare(ApiCall("listRecipes"), PrincipalClass.ACCOUNT))
        val guest = assertNotNull(preparation.prepare(ApiCall("listRecipes"), PrincipalClass.GUEST, "ignored\r\n"))
        assertTrue(guest.headers.isEmpty())
        assertTrue(guest.query.isEmpty())
        val account = assertNotNull(preparation.prepare(ApiCall("listRecipes"), PrincipalClass.ACCOUNT, session))
        assertEquals(mapOf("X-Device-Session" to session), account.headers)
    }

    @Test fun accountBootstrapOmitsExistingSessionAndRequiresExplicitCommandKeyAndBody() {
        val call = ApiCall("bootstrapAccount", body = json("{}"), idempotencyKey = key())
        val bootstrap = assertNotNull(preparation.prepare(call, PrincipalClass.ACCOUNT, "ignored-invalid-session"))
        assertEquals(listOf("v1", "account", "bootstrap"), bootstrap.pathSegments)
        assertEquals(mapOf("Idempotency-Key" to command), bootstrap.headers)
        assertNotNull(preparation.prepare(call, PrincipalClass.ACCOUNT))
        assertNull(preparation.prepare(call, PrincipalClass.GUEST))
        assertNull(preparation.prepare(call, PrincipalClass.PUBLIC))
        assertNull(preparation.prepare(ApiCall("bootstrapAccount", body = json("{}")), PrincipalClass.ACCOUNT))
        assertNull(preparation.prepare(ApiCall("bootstrapAccount", idempotencyKey = key()), PrincipalClass.ACCOUNT))
    }

    @Test fun publicGuestBootstrapRequiresItsKeyWithoutAttachingDeviceSession() {
        val prepared = assertNotNull(preparation.prepare(
            ApiCall("createGuestSession", body = json("{}"), idempotencyKey = key()), PrincipalClass.PUBLIC, session))
        assertEquals(mapOf("Idempotency-Key" to command), prepared.headers)
        assertNull(preparation.prepare(ApiCall("createGuestSession", body = json("{}")), PrincipalClass.PUBLIC))
    }

    @Test fun scalarQueryValuesStayExactAndOmittedDefaultsStayAbsent() {
        val query = mapOf("q" to listOf("café & greens?x=1#fragment%20"), "limit" to listOf("50"), "cursor" to listOf("a/b+c=="))
        val prepared = assertNotNull(preparation.prepare(ApiCall("listRecipes", queryParameters = query), PrincipalClass.GUEST))
        assertEquals(query, prepared.query)
        assertEquals(listOf("v1", "recipes"), prepared.pathSegments)
        val post = assertNotNull(preparation.prepare(ApiCall("getPost", pathParameters = mapOf("postId" to objectId)), PrincipalClass.ACCOUNT, session))
        assertTrue(post.query.isEmpty())
    }

    @Test fun unknownAndMissingParametersAreRejectedInTheirOwnLocation() {
        assertNull(preparation.prepare(ApiCall("listRecipes", queryParameters = mapOf("url" to listOf("https://other.example"))), PrincipalClass.GUEST))
        assertNull(preparation.prepare(ApiCall("listRecipes", queryParameters = mapOf("Authorization" to listOf("Bearer secret"))), PrincipalClass.GUEST))
        assertNull(preparation.prepare(ApiCall("listRecipes", pathParameters = mapOf("q" to "rice")), PrincipalClass.GUEST))
        assertNull(preparation.prepare(ApiCall("getPost"), PrincipalClass.ACCOUNT, session))
        assertNull(preparation.prepare(ApiCall("getPost", pathParameters = mapOf("postId" to objectId, "extra" to objectId)), PrincipalClass.ACCOUNT, session))
        assertNull(preparation.prepare(ApiCall("previewInvitation"), PrincipalClass.PUBLIC))
        assertNull(preparation.prepare(ApiCall("listRecipes", queryParameters = mapOf("Limit" to listOf("20"))), PrincipalClass.GUEST))
    }

    @Test fun duplicateOrEmptyScalarQueryListsAreRejected() {
        listOf(emptyList(), listOf("20", "20"), listOf("20", "30")).forEach { values ->
            assertNull(preparation.prepare(ApiCall("listRecipes", queryParameters = mapOf("limit" to values)), PrincipalClass.GUEST))
        }
        assertNotNull(preparation.prepare(ApiCall("listRecipes", queryParameters = mapOf("q" to listOf(""))), PrincipalClass.GUEST))
    }

    @Test fun integersUseCanonicalDecimalNotationAndExactSchemaBounds() {
        listOf("1", "20", "50").forEach { assertNotNull(query("limit", it), it) }
        listOf("", "0", "51", "-1", "-0", "+1", "01", "1.0", "1e1", " 1", "1 ", "１", "١", "9".repeat(4096)).forEach {
            assertNull(query("limit", it), it.take(30))
        }
    }

    @Test fun enumValuesAreExactAndCaseSensitive() {
        listOf("general", "makeMine", "tonight", "reuse").forEach {
            assertNotNull(preparation.prepare(ApiCall("listPlans", queryParameters = mapOf("intent" to listOf(it))), PrincipalClass.GUEST))
        }
        listOf("", "TONIGHT", "unknown", "tonight ").forEach {
            assertNull(preparation.prepare(ApiCall("listPlans", queryParameters = mapOf("intent" to listOf(it))), PrincipalClass.GUEST))
        }
        assertNull(preparation.prepare(ApiCall("listPlans", queryParameters = mapOf("status" to listOf("tonight"))), PrincipalClass.GUEST))
    }

    @Test fun lengthsCountUnicodeCodePointsWithExactBoundaries() {
        assertNotNull(query("q", "😀".repeat(100)))
        assertNull(query("q", "😀".repeat(101)))
        assertNotNull(query("cursor", "😀".repeat(2048)))
        assertNull(query("cursor", "a".repeat(2049)))
        listOf(32, 512).forEach { length ->
            assertNotNull(preparation.prepare(ApiCall("previewInvitation", queryParameters = mapOf("token" to listOf("😀".repeat(length)))), PrincipalClass.PUBLIC))
        }
        listOf(31, 513).forEach { length ->
            assertNull(preparation.prepare(ApiCall("previewInvitation", queryParameters = mapOf("token" to listOf("😀".repeat(length)))), PrincipalClass.PUBLIC))
        }
    }

    @Test fun unpairedUnicodeIsRejectedBeforeUrlEncoding() {
        listOf("\uD800", "\uDC00", "a\uD800b", "\uDC00\uD800").forEach { invalid ->
            assertNull(query("q", invalid))
            assertNull(preparation.prepare(ApiCall("getCookSession", pathParameters = mapOf("sessionId" to invalid)), PrincipalClass.GUEST))
        }
    }

    @Test fun pathValuesReplaceOnlyExactDeclaredSegments() {
        val prepared = assertNotNull(preparation.prepare(
            ApiCall("getPlanExplanation", pathParameters = mapOf("planId" to objectId)), PrincipalClass.GUEST))
        assertEquals(listOf("v1", "plans", objectId, "explanation"), prepared.pathSegments)
        val upper = objectId.uppercase()
        assertEquals(upper, assertNotNull(preparation.prepare(
            ApiCall("getCookSession", pathParameters = mapOf("sessionId" to upper)), PrincipalClass.GUEST)).pathSegments.last())
    }

    @Test fun pathTraversalUrlsSeparatorsAndNonUuidFormsAreRejected() {
        listOf(".", "..", "../health", "%2e%2e", "%252e%252e", "https://other.example/v1/health", "//other.example", "$objectId/health", "$objectId?x=y", "$objectId#x", "$objectId\\health", "{$objectId}", objectId.replace("-", "")).forEach {
            assertNull(preparation.prepare(ApiCall("getCookSession", pathParameters = mapOf("sessionId" to it)), PrincipalClass.GUEST))
        }
        assertNull(preparation.prepare(ApiCall("getToday", queryParameters = mapOf("circleId" to listOf("not-a-uuid"))), PrincipalClass.ACCOUNT, session))
        assertNotNull(preparation.prepare(ApiCall("getToday", queryParameters = mapOf("circleId" to listOf(objectId))), PrincipalClass.ACCOUNT, session))
    }

    @Test fun mutationHeadersAreExplicitRequiredAndSchemaValidated() {
        val call = ApiCall("updatePreferences", body = json("{}"), idempotencyKey = key(), ifMatch = "\"12\"")
        val guest = assertNotNull(preparation.prepare(call, PrincipalClass.GUEST, session))
        assertEquals(mapOf("Idempotency-Key" to command, "If-Match" to "\"12\""), guest.headers)
        val account = assertNotNull(preparation.prepare(call, PrincipalClass.ACCOUNT, session))
        assertEquals(guest.headers + ("X-Device-Session" to session), account.headers)
        assertNull(preparation.prepare(ApiCall("updatePreferences", body = json("{}"), ifMatch = "\"12\""), PrincipalClass.GUEST))
        assertNull(preparation.prepare(ApiCall("updatePreferences", body = json("{}"), idempotencyKey = key()), PrincipalClass.GUEST))
        listOf("12", "*", "W/\"12\"", "\"-1\"", "\"1.0\"", "\"1\", \"2\"", "\"\"", "\"12\"\u2028", "\"12\"\u2029").forEach {
            assertNull(preparation.prepare(ApiCall("updatePreferences", body = json("{}"), idempotencyKey = key(), ifMatch = it), PrincipalClass.GUEST))
        }
        listOf("not-a-uuid", "{$command}", command.replace("-", "")).forEach {
            assertNull(preparation.prepare(ApiCall("createGuestSession", body = json("{}"), idempotencyKey = SecretText(it)), PrincipalClass.PUBLIC))
        }
    }

    @Test fun undeclaredHeadersAreRejectedAndUnboundedHeaderStringsHaveResourceCeilings() {
        assertNull(preparation.prepare(ApiCall("getServiceHealth", idempotencyKey = key()), PrincipalClass.PUBLIC))
        assertNull(preparation.prepare(ApiCall("getServiceHealth", ifMatch = "\"1\""), PrincipalClass.PUBLIC))
        assertNotNull(preparation.prepare(ApiCall("updatePreferences", body = json("{}"), idempotencyKey = key(), ifMatch = "\"${"1".repeat(4094)}\""), PrincipalClass.GUEST))
        assertNull(preparation.prepare(ApiCall("updatePreferences", body = json("{}"), idempotencyKey = key(), ifMatch = "\"${"1".repeat(4095)}\""), PrincipalClass.GUEST))
    }

    @Test fun absentBodiesAreNeverSynthesizedAndUndeclaredBodiesAreRejected() {
        val removal = ApiCall("removePantryItem", pathParameters = mapOf("ingredientId" to objectId), idempotencyKey = key(), ifMatch = "\"1\"")
        assertNull(assertNotNull(preparation.prepare(removal, PrincipalClass.GUEST)).body)
        assertNull(preparation.prepare(ApiCall("getServiceHealth", body = json("null")), PrincipalClass.PUBLIC))
        assertNull(preparation.prepare(ApiCall("createPlan", idempotencyKey = key()), PrincipalClass.GUEST))
    }

    @Test fun bodySyntaxChecksPreserveBytesAndDoNotClaimFullSchemaValidation() {
        listOf("null", "[]", " { \"unknownFutureField\": null, \"number\": 1e400 } \n", "{}").forEach { raw ->
            val prepared = assertNotNull(preparation.prepare(ApiCall("createPlan", body = json(raw), idempotencyKey = key()), PrincipalClass.GUEST))
            assertContentEquals(raw.encodeToByteArray(), prepared.body)
        }
    }

    @Test fun malformedAndExcessiveBodiesAreRejectedWithWireDocumentPolicy() {
        listOf("", " ", "{", "{}{}", "{\"secret\":1,\"secret\":2}", "{\"secret\":true,}", "[".repeat(65) + "0" + "]".repeat(65), "9".repeat(1001)).forEach {
            assertNull(preparation.prepare(ApiCall("createPlan", body = json(it), idempotencyKey = key()), PrincipalClass.GUEST))
        }
        assertNull(preparation.prepare(ApiCall("createPlan", body = PrivateBytes(byteArrayOf(0x22, 0x80.toByte(), 0x22)), idempotencyKey = key()), PrincipalClass.GUEST))
        val exact = "\"" + "a".repeat(WireLimits().maxBytes - 2) + "\""
        assertNotNull(preparation.prepare(ApiCall("createPlan", body = json(exact), idempotencyKey = key()), PrincipalClass.GUEST))
        assertNull(preparation.prepare(ApiCall("createPlan", body = json(exact + " "), idempotencyKey = key()), PrincipalClass.GUEST))
    }

    @Test fun callerAndPreparedBuffersAreDetachedAndDebugOutputIsRedacted() {
        val raw = "{\"secret\":\"private-body\"}"
        val bytes = raw.encodeToByteArray()
        val call = ApiCall("createPlan", body = PrivateBytes(bytes), idempotencyKey = key())
        bytes.fill(0)
        val prepared = assertNotNull(preparation.prepare(call, PrincipalClass.GUEST))
        assertContentEquals(raw.encodeToByteArray(), prepared.body)
        prepared.body!!.fill(0)
        assertContentEquals(raw.encodeToByteArray(), assertNotNull(preparation.prepare(call, PrincipalClass.GUEST)).body)
        val parameters = linkedMapOf("q" to mutableListOf("private-query"))
        val read = ApiCall("listRecipes", queryParameters = parameters)
        val detached = assertNotNull(preparation.prepare(read, PrincipalClass.GUEST))
        parameters.getValue("q")[0] = "changed"
        assertEquals(listOf("private-query"), detached.query["q"])
        listOf(command, session, "private-body", "private-query").forEach {
            assertFalse(prepared.toString().contains(it))
            assertFalse(detached.toString().contains(it))
        }
    }

    private fun query(name: String, value: String): PreparedCall? =
        preparation.prepare(ApiCall("listRecipes", queryParameters = mapOf(name to listOf(value))), PrincipalClass.GUEST)

    private fun json(raw: String) = PrivateBytes(raw.encodeToByteArray())
    private fun key() = SecretText(command)

    companion object {
        private val catalog = ContractCatalog.bundled()
        private const val session = "03b943e1-84d3-4d3e-91cb-c80373bdbde5"
        private const val command = "f2692b76-c1da-4f41-af20-4da5349567b4"
        private const val objectId = "3b88fab2-1c4b-42c3-b52c-83c6ab9eaeab"
    }
}
