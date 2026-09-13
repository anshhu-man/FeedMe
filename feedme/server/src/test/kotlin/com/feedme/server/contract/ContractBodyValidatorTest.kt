package com.feedme.server.contract

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ContractBodyValidatorTest {
    @Test fun `all 149 canonical cases from the 158 case spike pass without byte mutation`() {
        val cases = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/schema-validator-cases.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        assertEquals(158, cases.size)
        var tested = 0
        for (entry in cases) {
            val case = entry.jsonObject
            val name = case.getValue("ref").jsonPrimitive.content.substringAfterLast('/')
            if (name.startsWith("__Spike")) continue // Nine synthetic schemas belong to the separate spike.
            val text = case["inputJson"]?.jsonPrimitive?.content ?: case.getValue("input").toString()
            val bytes = text.toByteArray(Charsets.UTF_8)
            val original = bytes.copyOf()
            val expected = if (case.getValue("expected").jsonPrimitive.boolean) BodyValidationResult.Valid
                else BodyValidationResult.Rejected(BodyRejectionReason.SCHEMA_VIOLATION)
            assertEquals(expected, validator.validateSchema(name, bytes), case.getValue("label").jsonPrimitive.content)
            assertContentEquals(original, bytes)
            tested++
        }
        assertEquals(149, tested)
    }

    @Test fun `every operation request and declared response is mapped including shared Problem statuses`() {
        var requests = 0
        var responses = 0
        var problems = 0
        for (operation in catalog.operations) {
            val node = catalog.document.getValue("paths").jsonObject.getValue(operation.path).jsonObject
                .getValue(operation.method.lowercase()).jsonObject
            if (node.containsKey("requestBody")) {
                assertRejected(BodyRejectionReason.MISSING_BODY, validator.validateRequest(operation.id, null, "application/json"))
                assertRejected(BodyRejectionReason.INPUT_SYNTAX, validator.validateRequest(operation.id, byteArrayOf(), "application/json"))
                requests++
            } else {
                assertEquals(BodyValidationResult.Valid, validator.validateRequest(operation.id, null, null))
                assertRejected(BodyRejectionReason.UNEXPECTED_BODY, validator.validateRequest(operation.id, byteArrayOf(), null))
            }
            for ((statusText, response) in node.getValue("responses").jsonObject) {
                val status = statusText.toInt()
                val responseNode = response.jsonObject
                if (status == 204) {
                    assertEquals(BodyValidationResult.Valid, validator.validateResponse(operation.id, status, null, null))
                    assertRejected(BodyRejectionReason.UNEXPECTED_BODY, validator.validateResponse(operation.id, status, byteArrayOf(), null))
                    assertRejected(BodyRejectionReason.UNEXPECTED_BODY, validator.validateResponse(operation.id, status, "null".toByteArray(), "application/json"))
                } else {
                    assertRejected(BodyRejectionReason.MISSING_BODY, validator.validateResponse(operation.id, status, null, null))
                }
                if (responseNode["\$ref"]?.jsonPrimitive?.content?.startsWith("#/components/responses/Error") == true) {
                    assertEquals(BodyValidationResult.Valid, validator.validateResponse(operation.id, status,
                        problem(status), "application/problem+json"))
                    assertRejected(BodyRejectionReason.UNSUPPORTED_MEDIA, validator.validateResponse(operation.id, status,
                        problem(status), "application/json"))
                    problems++
                }
                responses++
            }
        }
        assertEquals(201, catalog.operations.size)
        assertEquals(110, requests)
        assertEquals(2613, responses)
        assertEquals(2412, problems)
    }

    @Test fun `unknown names operations statuses and media produce stable reasons`() {
        assertRejected(BodyRejectionReason.UNKNOWN_SCHEMA, validator.validateSchema("unknown-sensitive-name", healthy))
        assertRejected(BodyRejectionReason.UNKNOWN_OPERATION, validator.validateRequest("unknown", null, null))
        assertRejected(BodyRejectionReason.UNKNOWN_OPERATION, validator.validateResponse("unknown", 200, healthy, "application/json"))
        assertRejected(BodyRejectionReason.UNKNOWN_STATUS, validator.validateResponse("getServiceHealth", 299, healthy, "application/json"))
        for (media in listOf(null, "text/plain", "application/problem+json", "application/json; charset=iso-8859-1",
            "application/json; charset=utf-8; charset=utf-8", "application/json; broken", "application/json\r\nX-Secret: bad",
            "application/json; profile=\"a;b", "application/json; profile=\"a\"junk", "application/json; profile\"a\"",
            "application/json; profile=", "application/json;", "application/json; profile=\"a\u0001b\"")) {
            assertRejected(BodyRejectionReason.UNSUPPORTED_MEDIA, validator.validateResponse("getServiceHealth", 200, healthy, media))
        }
        for (media in listOf("application/json", "Application/JSON", " application/json ; charset=UTF-8", "application/json; charset=\"utf-8\"",
            "application/json; profile=\"a;b\"", "application/json; profile=\"a\\;b\"",
            "application/json; profile=\"a\\\"b\"; charset=utf-8", "application/json; profile=\"a\\\\b\"")) {
            assertEquals(BodyValidationResult.Valid, validator.validateResponse("getServiceHealth", 200, healthy, media))
        }
        assertRejected(BodyRejectionReason.INPUT_SYNTAX, validator.validateResponse("getServiceHealth", 200, byteArrayOf(), "application/json"))
        assertRejected(BodyRejectionReason.SCHEMA_VIOLATION, validator.validateResponse("getServiceHealth", 200, "null".toByteArray(), "application/json"))
    }

    @Test fun `exact body and schema numbers survive production validation`() {
        for (value in listOf("9223372036854775808", "1e400", "9".repeat(1000))) {
            assertEquals(BodyValidationResult.Valid, validator.validateSchema("Health", healthWithRevision(value)))
        }
        assertRejected(BodyRejectionReason.SCHEMA_VIOLATION, validator.validateSchema("Health", healthWithRevision("-1e-400")))
        assertRejected(BodyRejectionReason.SCHEMA_VIOLATION, validator.validateSchema("Health", healthWithRevision("1.00000000000000000001")))
        assertRejected(BodyRejectionReason.SCHEMA_VIOLATION, validator.validateSchema("Health", healthWithRevision("\"1\"")))
        // Raw numbers exercise a non-integer canonical decimal minimum in the retained cases too.
        val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/schema-validator-cases.json"))
            .bufferedReader().use { it.readText() }).jsonArray.first {
                it.jsonObject["label"]?.jsonPrimitive?.content == "Constraint.servings:exact-below-minimum"
            }.jsonObject.getValue("inputJson").jsonPrimitive.content
        assertRejected(BodyRejectionReason.SCHEMA_VIOLATION, validator.validateSchema("Constraint", fixture.toByteArray()))
        assertEquals(BodyValidationResult.Valid, validator.validateSchema("Constraint",
            fixture.replace("0.099999999999999999999", "0.1").toByteArray()))
    }

    @Test fun `malformed JSON and invalid Unicode are syntax rejections with safe diagnostics`() {
        val malformed = listOf("", "   ", "{} {}", "NaN", "Infinity", "01", "[1,]", "/* secret */ {}",
            """{"sensitive-field":"secret-value","sensitive-field":2}""",
            """{"sensitive-field":"\uD800"}""", """{"\uDC00":"secret-value"}""")
        for (text in malformed) {
            val result = validator.validateSchema("Health", text.toByteArray())
            assertRejected(BodyRejectionReason.INPUT_SYNTAX, result)
            assertEquals("Rejected(reason=INPUT_SYNTAX)", result.toString())
            assertFalse(result.toString().contains("secret"))
        }
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) {
            assertRejected(BodyRejectionReason.INPUT_SYNTAX, validator.validateSchema("Health", bytes))
        }
        assertEquals(BodyValidationResult.Valid, validator.validateSchema("Health", healthy.toString(Charsets.UTF_8)
            .replace("0.1.0", "\\uD83D\\uDE00").toByteArray()))
        val schemaFailure = validator.validateSchema("Health", """{"secret-key":"secret-value"}""".toByteArray())
        assertEquals("Rejected(reason=SCHEMA_VIOLATION)", schemaFailure.toString())
    }

    @Test fun `operational resource limits are distinct from schema violations`() {
        for (bytes in listOf(ByteArray(ContractBodyValidator.MAX_BODY_BYTES + 1) { 32 },
            ("[".repeat(65) + "0" + "]".repeat(65)).toByteArray(),
            healthWithRevision("9".repeat(1001)), healthWithRevision("1e10001"), healthWithRevision("1e-10001"),
            healthWithRevision("1e2147483648"), healthWithRevision("1e999999999999999999"))) {
            val result = validator.validateSchema("Health", bytes)
            assertRejected(BodyRejectionReason.RESOURCE_LIMIT, result)
            assertEquals("Rejected(reason=RESOURCE_LIMIT)", result.toString())
        }
        // Bounds also apply to unknown values inside unrestricted webhook objects.
        val webhook = """{"api_version":"1","event":{"id":"e","type":"t","event_timestamp_ms":0,"environment":"SANDBOX"},"extra":1e10001}"""
        assertRejected(BodyRejectionReason.RESOURCE_LIMIT, validator.validateSchema("RevenueCatWebhook", webhook.toByteArray()))
        val safe = webhook.replace("1e10001", "1e400")
        assertEquals(BodyValidationResult.Valid, validator.validateSchema("RevenueCatWebhook", safe.toByteArray()))
        // The parser's field-name limit is explicit and consistent with the overall byte cap.
        val longName = safe.replace("\"extra\"", "\"" + "x".repeat(60_000) + "\"").toByteArray()
        assertTrue(longName.size < ContractBodyValidator.MAX_BODY_BYTES)
        assertEquals(BodyValidationResult.Valid, validator.validateSchema("RevenueCatWebhook", longName))
    }

    @Test fun `compiled schemas support concurrent mixed validation without retaining caller data`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val work = (0 until 400).map { index -> Callable {
                if (index % 2 == 0) validator.validateSchema("Health", healthy)
                else validator.validateResponse("getServiceHealth", 503, problem(503), "application/problem+json")
            } }
            for (result in pool.invokeAll(work)) assertEquals(BodyValidationResult.Valid, result.get(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertEquals(BodyValidationResult.Valid, validator.validateSchema("Health", healthy))
    }

    private fun assertRejected(reason: BodyRejectionReason, result: BodyValidationResult) =
        assertEquals(BodyValidationResult.Rejected(reason), result)

    companion object {
        private val catalog by lazy { ContractCatalog.bundled() }
        private val validator by lazy { ContractBodyValidator.bundled(catalog) }
        private val healthy = """{"status":"healthy","serverTime":"2026-09-13T00:00:00Z","minimumAppVersion":"0.1.0"}""".toByteArray()
        private fun healthWithRevision(value: String) =
            (healthy.toString(Charsets.UTF_8).dropLast(1) + ",\"flagsRevision\":" + value + "}").toByteArray()
        private fun problem(status: Int) = JsonObject(mapOf("type" to JsonPrimitive("https://example.com/error"),
            "title" to JsonPrimitive("Unavailable"), "status" to JsonPrimitive(status), "code" to JsonPrimitive("unavailable"),
            "traceId" to JsonPrimitive("trace-example"))).toString().toByteArray()
    }
}
