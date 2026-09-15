package com.feedme.server

import com.feedme.contracts.CanonicalBodyValidator
import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.ContractValidationResult
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.contract.ContractCatalog
import java.math.BigDecimal
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential proof against the pinned networknt production boundary. Synthetic data only.
 * Schema/number assertions remain independent; the three shared format assertions also need golden tests.
 */
class CanonicalValidatorParityTest {
    @Test fun all149CanonicalCasesPreserveTheirOriginalBytesAndOracleResult() {
        val cases = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/schema-validator-cases.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        assertEquals(158, cases.size)
        var count = 0
        for (entry in cases) {
            val case = entry.jsonObject
            val name = case.getValue("ref").jsonPrimitive.content.substringAfterLast('/')
            if (name.startsWith("__Spike")) continue
            // inputJson is deliberately not parsed/re-serialized: these include exact large decimals.
            val bytes = (case["inputJson"]?.jsonPrimitive?.content ?: case.getValue("input").toString()).encodeToByteArray()
            val expected = if (case.getValue("expected").jsonPrimitive.boolean) "VALID" else "SCHEMA_VIOLATION"
            val label = case.getValue("label").jsonPrimitive.content
            assertEquals(expected, oracle.validateSchema(name, bytes).key(), "canonical oracle: $label")
            schemaParity(name, bytes, "canonical: $label")
            count++
        }
        assertEquals(149, count)
    }

    @Test fun everyNamedSchemaHasAnOracleValidBaselineAndSystematicConstraintMutations() {
        assertEquals(188, schemas.size)
        val categories = mutableMapOf<String, Int>()
        val differences = mutableListOf<String>()
        var mutations = 0
        for ((name, rawSchema) in schemas) {
            val baseline = fixture(rawSchema)
            assertEquals("VALID", oracle.validateSchema(name, baseline.bytes()).key(), "baseline oracle: $name")
            collectSchemaDifference(name, baseline.bytes(), "baseline: $name", differences)
            walk(rawSchema, baseline) { path, rule, replacement ->
                categories[rule] = categories.getOrDefault(rule, 0) + 1
                collectSchemaDifference(name, replace(baseline, path, replacement).bytes(),
                    "$name/${path.joinToString("/")}:$rule", differences)
                mutations++
            }
        }
        for (category in listOf("type", "nullable", "required", "closed", "enum", "const", "minimum",
            "maximum", "minLength", "maxLength", "minItems", "maxItems", "uniqueItems", "pattern", "additionalProperties")) {
            assertTrue(categories.getOrDefault(category, 0) > 0, "missing mutation category: $category")
        }
        // Conditional Plan.mode removes two unconditional-required mutations: Plan and the
        // Plan item nested in PlanPage. Its ready/recalled requirements are exercised by the
        // conditional-branch test below and the explicit PlanModeParityTest status/mode matrix.
        // Optional bounded nullable item cursor adds eight mutations to Collection and eight
        // more to the Collection nested in CollectionPage; all prior mutations remain covered.
        assertEquals(8437, mutations, "pinned canonical schema mutation coverage")
        assertTrue(differences.isEmpty(), "${differences.size} schema differences; first 40:\n${differences.take(40).joinToString("\n")}")
    }

    @Test fun everyOperationRequestAndDeclaredResponseUsesItsCanonicalBodyAndMediaMapping() {
        var requests = 0
        var responses = 0
        var problemResponses = 0
        var noBodyResponses = 0
        for (operation in catalog.operations) {
            val node = catalog.document.getValue("paths").jsonObject.getValue(operation.path).jsonObject
                .getValue(operation.method.lowercase()).jsonObject
            val request = node["requestBody"]?.let(::resolve)
            checkBodyMapping(request, "${operation.id}:request") { bytes, media ->
                requestParity(operation.id, bytes, media)
            }
            if (request != null) requests++
            for ((statusText, rawResponse) in node.getValue("responses").jsonObject) {
                val status = statusText.toInt()
                val response = resolve(rawResponse)
                checkBodyMapping(response, "${operation.id}:$status") { bytes, media ->
                    responseParity(operation.id, status, bytes, media)
                }
                val media = response["content"]?.jsonObject
                if (media?.containsKey("application/problem+json") == true) problemResponses++
                if (media.isNullOrEmpty()) noBodyResponses++
                responses++
            }
            requestParity(operation.id, null, null)
            responseParity(operation.id, 299, null, null)
        }
        assertEquals(201, catalog.operations.size)
        assertEquals(110, requests)
        assertEquals(2613, responses)
        assertEquals(2412, problemResponses)
        assertEquals(25, noBodyResponses)
    }

    @Test fun everyConditionalAndAnyOfBranchMatchesTheOracleIncludingCombinedSelectors() {
        for ((name, node) in schemas) {
            val schema = resolve(node)
            val baseline = fixture(schema).jsonObject
            schema["anyOf"]?.jsonArray?.let { alternatives ->
                val selectors = alternatives.flatMap { it.jsonObject.getValue("required").jsonArray }
                    .map { it.jsonPrimitive.content }.toSet()
                val without = JsonObject(baseline.filterKeys { it !in selectors })
                schemaParity(name, without.bytes(), "$name:anyOf-none")
                for (selector in selectors) {
                    val value = JsonObject(without + (selector to baseline.getValue(selector)))
                    assertEquals("VALID", oracle.validateSchema(name, value.bytes()).key(), "$name:anyOf-$selector oracle")
                    schemaParity(name, value.bytes(), "$name:anyOf-$selector")
                }
                // anyOf permits selectors together; interpreting it as oneOf changes the canonical contract.
                schemaParity(name, baseline.bytes(), "$name:anyOf-together")
            }
            for ((branchIndex, branchNode) in schema["allOf"]?.jsonArray.orEmpty().withIndex()) {
                val branch = branchNode.jsonObject
                val condition = branch.getValue("if").jsonObject
                val predicate = condition.getValue("properties").jsonObject.entries.single()
                val predicateSchema = predicate.value.jsonObject
                val matches = predicateSchema["enum"]?.jsonArray
                    ?: JsonArray(listOf(predicateSchema.getValue("const")))
                val requirements = branch.getValue("then").jsonObject.getValue("required").jsonArray
                    .map { it.jsonPrimitive.content }
                for ((valueIndex, matching) in matches.withIndex()) {
                    val triggered = JsonObject(baseline + (predicate.key to matching))
                    assertEquals("VALID", oracle.validateSchema(name, triggered.bytes()).key(), "$name:if-$branchIndex-$valueIndex oracle")
                    schemaParity(name, triggered.bytes(), "$name:if-$branchIndex-$valueIndex")
                    for (required in requirements) {
                        val missing = JsonObject(triggered - required)
                        assertEquals("SCHEMA_VIOLATION", oracle.validateSchema(name, missing.bytes()).key(), "$name:then-missing-$required oracle")
                        schemaParity(name, missing.bytes(), "$name:then-$branchIndex-$valueIndex-missing-$required")
                    }
                }
                val missingPredicate = JsonObject(baseline - predicate.key)
                schemaParity(name, missingPredicate.bytes(), "$name:if-$branchIndex-absent-predicate")
            }
        }
    }

    @Test fun actualHealthDateTimeAndProblemUriFieldsMatchTheAuthoritativeFormatImplementation() {
        val differences = mutableListOf<String>()
        for ((index, value) in dateTimes.withIndex()) {
            val body = JsonObject(fixture(schemas.getValue("Health")).jsonObject + ("serverTime" to JsonPrimitive(value))).bytes()
            collectSchemaDifference("Health", body, "date-time-$index", differences)
        }
        for ((index, value) in uris.withIndex()) {
            val body = JsonObject(fixture(schemas.getValue("Problem")).jsonObject + ("type" to JsonPrimitive(value))).bytes()
            collectSchemaDifference("Problem", body, "uri-$index", differences)
        }
        assertEquals(emptyList(), differences)
    }

    @Test fun reviewedFormatRegressionsHaveExplicitExpectationsAtBothProductionBoundaries() {
        fun verify(format: String, schema: String, field: String, value: String, expected: Boolean, label: String) {
            val body = JsonObject(fixture(schemas.getValue(schema)).jsonObject + (field to JsonPrimitive(value))).bytes()
            val expectedResult = if (expected) "VALID" else "SCHEMA_VIOLATION"
            assertEquals(expected, CanonicalFormats.accepts(format, value), "$label:format")
            assertEquals(expectedResult, oracle.validateSchema(schema, body).key(), "$label:server")
            assertEquals(expectedResult, common.validateSchema(schema, body).key(), "$label:common")
        }
        for ((index, value) in listOf("2026-09-13T00:00:00-00:00", "2026-09-13T00:00:00.123456789012345678901Z",
            "2026-09-13T00:00:00+23:59", "2016-12-31T23:59:60Z").withIndex()) {
            verify("date-time", "Health", "serverTime", value, true, "reviewed-time-valid-$index")
        }
        for ((index, value) in listOf("2026-09-13T00:00:00Zx", "2026-09-13T00:00:00Z\n", "2026-09-13T00:00:00Z ",
            "2016-06-30T23:59:60Z", "2025-02-29T00:00:00Z").withIndex()) {
            verify("date-time", "Health", "serverTime", value, false, "reviewed-time-invalid-$index")
        }
        verify("uri", "Problem", "type", "https://[v1.fe]/", true, "reviewed-uri-ipvfuture")
        for ((index, value) in listOf("https://example.com:abc/", "https://a@b@c/", "https://example.com/%GG").withIndex()) {
            verify("uri", "Problem", "type", value, false, "reviewed-uri-invalid-$index")
        }
    }

    @Test fun exactRawNumbersUnicodeDuplicatesSyntaxResourceBoundsAndMediaHaveParity() {
        val differences = mutableListOf<String>()
        val numeric = listOf("0", "-0", "0.0", "-0.000", "1.0", "1e0", "10e-1", "100000e-5",
            "1.00000000000000000001", "0.99999999999999999999", "-1e-400", "1e-400", "1e400",
            "9223372036854775808", "9007199254740993", "999999999999999999999999999999999999999999999999",
            "9".repeat(1000), "9".repeat(1001), "1e10000", "1e-10000", "1e10001", "1e-10001",
            "0e10001", "0e-10001", "0.00e10002", "1e2147483648", "1e-2147483648", "1e999999999999999999")
        for ((index, token) in numeric.withIndex()) {
            collectSchemaDifference("Health", healthWithRevision(token), "number-$index", differences)
        }
        for ((name, field, tokens) in listOf(
            Triple("Constraint", "servings", listOf("0.099999999999999999999", "0.1", "0.100000000000000000001", "1e-1")),
            Triple("ContributionWrite", "quantity", listOf("0.000999999999999999999", "0.001", "0.001000000000000000001", "1e-3")),
            Triple("FlagChangeWrite", "rolloutPercent", listOf("-1e-400", "0", "100", "1e2", "100.000000000000000000001")),
        )) {
            val baseline = fixture(schemas.getValue(name)).jsonObject
            for ((index, token) in tokens.withIndex()) {
                // Raw JSON insertion retains every digit; no floating-point conversion happens here.
                val body = JsonObject(baseline + (field to Json.parseToJsonElement(token))).bytes()
                collectSchemaDifference(name, body, "$name:exact-bound-$index", differences)
            }
        }
        for ((index, raw) in listOf("", " ", "{} {}", "null null", "NaN", "Infinity", "-Infinity", "01", "-01", "1.",
            ".1", "+1", "1e", "[1,]", "{\"x\":1,}", "/* synthetic */ {}", "true false", "\uFEFF{}",
            "{\"x\":1,\"x\":2}", "{\"x\":1,\"\\u0078\":2}", "{\"x\":\"\\uD800\"}", "{\"\\uDC00\":1}",
            "{\"x\":\"\\uD800\\uD800\"}", "{\"x\":\"\\uDC00\\uD800\"}", "\"\\uD83D\\uDE00\"").withIndex()) {
            collectSchemaDifference("Health", raw.encodeToByteArray(), "syntax-$index", differences)
        }
        val bytesCorpus = listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()), byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0x7b, 0x7d),
            ByteArray(ContractBodyValidator.MAX_BODY_BYTES + 1) { 32 })
        for ((index, bytes) in bytesCorpus.withIndex()) collectSchemaDifference("Health", bytes, "bytes-$index", differences)
        for (depth in listOf(63, 64, 65)) {
            collectSchemaDifference("Health", ("[".repeat(depth) + "0" + "]".repeat(depth)).encodeToByteArray(), "depth-$depth", differences)
        }
        val uuid = "11111111-1111-4111-8111-111111111111"
        for ((index, second) in listOf("\"$uuid\"", "\"\\u0031${uuid.drop(1)}\"",
            "\"22222222-2222-4222-8222-222222222222\"").withIndex()) {
            collectSchemaDifference("CollectionOrder", "{\"orderedSavedRecipeIds\":[\"$uuid\",$second]}".encodeToByteArray(),
                "unique-uuid-$index", differences)
        }
        assertEquals(emptyList(), differences)
        for (media in mediaTypes) responseParity("getServiceHealth", 200, healthy, media)
        schemaParity("unknown-synthetic-schema", healthy, "unknown-schema")
        requestParity("unknown-synthetic-operation", null, null)
        responseParity("unknown-synthetic-operation", 200, healthy, "application/json")
    }

    private fun checkBodyMapping(node: JsonObject?, label: String, compare: (ByteArray?, String?) -> String) {
        val content = node?.get("content")?.jsonObject
        if (content.isNullOrEmpty()) {
            assertEquals("VALID", compare(null, null), "$label:no-body")
            assertEquals("UNEXPECTED_BODY", compare(byteArrayOf(), null), "$label:empty-is-present")
            assertEquals("UNEXPECTED_BODY", compare("null".encodeToByteArray(), "application/json"), "$label:null-is-present")
            return
        }
        compare(null, null)
        for ((media, definition) in content) {
            val schema = definition.jsonObject.getValue("schema")
            val baseline = fixture(schema)
            assertEquals("VALID", compare(baseline.bytes(), media), "$label:baseline")
            assertEquals("VALID", compare(baseline.bytes(), " ${media.uppercase(Locale.ROOT)} ; charset=\"UTF-8\""), "$label:normalized-media")
            compare(byteArrayOf(), media)
            compare("null".encodeToByteArray(), media)
            compare("[]".encodeToByteArray(), media)
            compare(baseline.bytes(), "text/plain")
            compare(baseline.bytes(), null)
            compare(baseline.bytes(), "application/json; charset=iso-8859-1")
            if (baseline is JsonObject) {
                val required = resolve(schema)["required"]?.jsonArray.orEmpty()
                for (field in required) compare(JsonObject(baseline - field.jsonPrimitive.content).bytes(), media)
                compare(JsonObject(baseline + ("syntheticUnexpectedProperty" to JsonPrimitive(true))).bytes(), media)
            }
        }
    }

    private fun schemaParity(name: String, bytes: ByteArray, label: String) {
        val original = bytes.copyOf()
        assertEquals(oracle.validateSchema(name, bytes).key(), common.validateSchema(name, bytes).key(), label)
        assertContentEquals(original, bytes, "$label:immutable-input")
    }

    private fun collectSchemaDifference(name: String, bytes: ByteArray, label: String, differences: MutableList<String>) {
        val original = bytes.copyOf()
        val expected = oracle.validateSchema(name, bytes).key()
        val actual = common.validateSchema(name, bytes).key()
        if (expected != actual) differences += "$label: expected=$expected actual=$actual"
        assertContentEquals(original, bytes, "$label:immutable-input")
    }

    private fun requestParity(operation: String, bytes: ByteArray?, media: String?): String {
        val original = bytes?.copyOf()
        val expected = oracle.validateRequest(operation, bytes, media).key()
        assertEquals(expected, common.validateRequest(operation, bytes, media).key(), "$operation:request")
        assertContentEquals(original, bytes, "$operation:request-immutable-input")
        return expected
    }

    private fun responseParity(operation: String, status: Int, bytes: ByteArray?, media: String?): String {
        val original = bytes?.copyOf()
        val expected = oracle.validateResponse(operation, status, bytes, media).key()
        assertEquals(expected, common.validateResponse(operation, status, bytes, media).key(), "$operation:$status")
        assertContentEquals(original, bytes, "$operation:response-immutable-input")
        return expected
    }

    /** All current optional properties may coexist, including anyOf selectors and then-required fields. */
    private fun fixture(raw: JsonElement, seed: Int = 1, depth: Int = 0): JsonElement {
        check(depth < 40) { "Unexpected recursive canonical fixture" }
        val schema = resolve(raw)
        schema["const"]?.let { return it }
        schema["enum"]?.jsonArray?.let { return it.first { value -> value != JsonNull } }
        return when (types(schema).firstOrNull { it != "null" }) {
            "object" -> {
                val properties = schema["properties"]?.jsonObject.orEmpty()
                val values = properties.mapValues { (_, child) -> fixture(child, seed, depth + 1) }.toMutableMap()
                val additional = schema["additionalProperties"]
                if (additional is JsonObject) values["syntheticMapKey"] = fixture(additional, seed, depth + 1)
                JsonObject(values)
            }
            "array" -> JsonArray(List(maxOf(1, schema["minItems"]?.jsonPrimitive?.int ?: 0)) { index ->
                fixture(schema.getValue("items"), seed + index, depth + 1)
            })
            "string" -> JsonPrimitive(when (schema["format"]?.jsonPrimitive?.content) {
                "uuid" -> "${seed.toString().padStart(8, '0')}-1111-4111-8111-111111111111"
                "date-time" -> "2026-09-13T00:00:00Z"
                "uri" -> "https://example.com/synthetic/$seed"
                else -> when (schema["pattern"]?.jsonPrimitive?.content) {
                    "^[a-z0-9_]{3,24}$" -> "fixture_$seed"
                    "^[a-fA-F0-9]{64}$" -> "a".repeat(64)
                    null -> "x".repeat(maxOf(1, schema["minLength"]?.jsonPrimitive?.int ?: 0))
                    else -> error("Unrecognized canonical fixture pattern")
                }
            })
            "integer", "number" -> schema["minimum"] ?: JsonPrimitive(0)
            "boolean" -> JsonPrimitive(false)
            "null" -> JsonNull
            null -> JsonObject(emptyMap())
            else -> error("Unrecognized canonical fixture type")
        }
    }

    private fun walk(raw: JsonElement, value: JsonElement, path: List<String> = emptyList(),
        emit: (List<String>, String, JsonElement) -> Unit) {
        val schema = resolve(raw)
        val allowed = types(schema)
        val wrongType = when {
            "boolean" !in allowed -> JsonPrimitive(true)
            "string" !in allowed -> JsonPrimitive("synthetic-wrong-type")
            else -> JsonArray(emptyList())
        }
        if (allowed.isNotEmpty()) emit(path, "type", wrongType)
        if ("null" in allowed) emit(path, "nullable", JsonNull)
        schema["enum"]?.jsonArray?.let { entries ->
            entries.forEach { emit(path, "enum", it) }
            emit(path, "enum", JsonPrimitive("synthetic-outside-enum"))
        }
        schema["const"]?.let { emit(path, "const", if (it == JsonPrimitive(false)) JsonPrimitive(true) else JsonNull) }
        for (keyword in listOf("minimum", "maximum")) schema[keyword]?.jsonPrimitive?.let { bound ->
            // Exact decimal arithmetic is used only to synthesize tests, never to round a wire body.
            val exact = BigDecimal(bound.content)
            for (delta in listOf(BigDecimal.ONE.negate(), BigDecimal.ZERO, BigDecimal.ONE)) {
                emit(path, keyword, Json.parseToJsonElement(exact.add(delta).toPlainString()))
            }
        }
        if (value is JsonPrimitive && value.isString) {
            for (keyword in listOf("minLength", "maxLength")) schema[keyword]?.jsonPrimitive?.int?.let { bound ->
                for (length in listOf((bound - 1).coerceAtLeast(0), bound, bound + 1)) {
                    emit(path, keyword, JsonPrimitive("x".repeat(length)))
                    emit(path, keyword, JsonPrimitive("\uD83D\uDE00".repeat(length)))
                }
            }
            if (schema.containsKey("pattern")) {
                for (text in listOf("", "!", value.content + "\n", "\n" + value.content)) emit(path, "pattern", JsonPrimitive(text))
            }
        }
        if (value is JsonObject) {
            for (field in schema["required"]?.jsonArray.orEmpty()) {
                emit(path, "required", JsonObject(value - field.jsonPrimitive.content))
            }
            val additional = schema["additionalProperties"]
            if (additional?.jsonPrimitiveOrNull()?.booleanOrNull == false) {
                emit(path, "closed", JsonObject(value + ("syntheticUnexpectedProperty" to JsonPrimitive(true))))
            } else if (additional != null) {
                emit(path, "additionalProperties", JsonObject(value + ("syntheticUnknownValue" to JsonArray(listOf(JsonPrimitive(7))))))
            }
            for ((name, child) in schema["properties"]?.jsonObject.orEmpty()) {
                value[name]?.let { walk(child, it, path + name, emit) }
            }
        }
        if (value is JsonArray) {
            val itemSchema = schema.getValue("items")
            for (keyword in listOf("minItems", "maxItems")) schema[keyword]?.jsonPrimitive?.int?.let { bound ->
                for (size in listOf((bound - 1).coerceAtLeast(0), bound, bound + 1)) {
                    emit(path, keyword, JsonArray(List(size) { index -> fixture(itemSchema, index + 1) }))
                }
            }
            if (schema["uniqueItems"]?.jsonPrimitive?.booleanOrNull == true) {
                emit(path, "uniqueItems", JsonArray(listOf(value.first(), value.first())))
            }
            value.firstOrNull()?.let { walk(itemSchema, it, path + "0", emit) }
        }
    }

    private fun replace(value: JsonElement, path: List<String>, replacement: JsonElement): JsonElement {
        if (path.isEmpty()) return replacement
        val key = path.first()
        return when (value) {
            is JsonObject -> JsonObject(value + (key to replace(value.getValue(key), path.drop(1), replacement)))
            is JsonArray -> JsonArray(value.mapIndexed { index, child ->
                if (index == key.toInt()) replace(child, path.drop(1), replacement) else child
            })
            else -> error("Invalid synthetic fixture path")
        }
    }

    private fun resolve(raw: JsonElement): JsonObject {
        var result = raw.jsonObject
        val seen = mutableSetOf<String>()
        while (result.containsKey("\$ref")) {
            val reference = result.getValue("\$ref").jsonPrimitive.content
            check(reference.startsWith("#/") && seen.add(reference))
            var target: JsonElement = catalog.document
            for (part in reference.removePrefix("#/").split('/')) {
                target = target.jsonObject.getValue(part.replace("~1", "/").replace("~0", "~"))
            }
            result = JsonObject(target.jsonObject + (result - "\$ref"))
        }
        return result
    }

    private fun types(schema: JsonObject): List<String> = when (val type = schema["type"]) {
        is JsonArray -> type.map { it.jsonPrimitive.content }
        is JsonPrimitive -> listOf(type.content)
        else -> emptyList()
    }

    private fun JsonElement.bytes() = toString().encodeToByteArray()
    private fun JsonElement.jsonPrimitiveOrNull() = this as? JsonPrimitive
    private fun BodyValidationResult.key() = when (this) {
        BodyValidationResult.Valid -> "VALID"
        is BodyValidationResult.Rejected -> reason.name
    }
    private fun ContractValidationResult.key() = when (this) {
        ContractValidationResult.Valid -> "VALID"
        is ContractValidationResult.Rejected -> reason.name
    }

    companion object {
        private val catalog by lazy { ContractCatalog.bundled() }
        private val schemas by lazy { catalog.document.getValue("components").jsonObject.getValue("schemas").jsonObject }
        private val oracle by lazy { ContractBodyValidator.bundled(catalog) }
        private val common by lazy { CanonicalBodyValidator.bundled() }
        private val healthy = """{"status":"healthy","serverTime":"2026-09-13T00:00:00Z","minimumAppVersion":"0.1.0"}""".encodeToByteArray()
        private fun healthWithRevision(token: String) =
            (healthy.decodeToString().dropLast(1) + ",\"flagsRevision\":" + token + "}").encodeToByteArray()
        private val dateTimes = listOf(
            "2026-09-13T00:00:00Z", "2026-09-13t00:00:00z", "2026-09-13T00:00:00+00:00", "2026-09-13T00:00:00-00:00",
            "2026-09-13T05:30:00+05:30", "2026-09-13T00:00:00.1Z", "2026-09-13T00:00:00.123456789Z",
            "2026-09-13T00:00:00.123456789012345678901Z", "2024-02-29T12:00:00Z", "2000-02-29T00:00:00Z",
            "1900-02-29T00:00:00Z", "2025-02-29T00:00:00Z", "2026-04-31T00:00:00Z", "0000-01-01T00:00:00Z",
            "9999-12-31T23:59:59Z", "2016-12-31T23:59:60Z", "2016-12-31T23:59:60+00:00", "2017-01-01T00:59:60+01:00",
            "2016-06-30T23:59:60Z", "2026-09-13T12:34:60Z", "2026-09-13T24:00:00Z", "2026-09-13T23:60:00Z",
            "2026-09-13T00:00:00+23:59", "2026-09-13T00:00:00+24:00", "2026-09-13T00:00:00+00:60",
            "2026-09-13 00:00:00Z", "2026-09-13T00:00:00", "2026-09-13", "20260913T000000Z", "2026-9-13T0:00:00Z",
            "2026-09-13T00:00:00.Z", "2026-09-13T00:00:00,1Z", "2026-09-13T00:00:00+0530", "2026-09-13T00:00:00Z\n",
            " 2026-09-13T00:00:00Z", "2026-09-13T00:00:00Z ", "2026-09-13T00:00:00Zx", "", "2026-00-13T00:00:00Z",
            "2026-13-13T00:00:00Z", "2026-09-00T00:00:00Z", "2026-09-13T-1:00:00Z", "+2026-09-13T00:00:00Z",
        )
        private val uris = listOf(
            "https://example.com/error", "https://example.com", "HTTP://EXAMPLE.COM/a", "urn:example:problem", "about:blank",
            "mailto:synthetic@example.com", "tel:+12025550123", "file:///synthetic/path", "data:text/plain,synthetic",
            "custom:opaque", "custom:", "custom:/", "custom://", "http://", "https:///path", "https://example.com:443/path",
            "https://example.com:99999/", "https://example.com:/", "https://example.com:abc/", "https://user:pass@example.com/",
            "https://[::1]/", "https://[2001:db8::1]:8080/", "https://[v1.fe]/", "https://[invalid]/", "https://127.0.0.1/",
            "https://999.999.999.999/", "https://example.com/a%20b?q=a%2Fb#frag", "https://example.com/%", "https://example.com/%2",
            "https://example.com/%GG", "https://example.com/a b", "https://example.com/é", "https://例え.テスト/", "https://example.com/😀",
            "https://example.com/a\\b", "https://example.com/[]", "https://example.com/{x}", "https://example.com/|", "https://example.com/^",
            "https://example.com/#a#b", "https://example.com/?q=a?b", "https://example.com/!$&'()*+,;=:@", "https://example.com/\n",
            "https://example.com/\u0000", " https://example.com/", "/relative", "//example.com/path", "relative", "", "1custom:value",
            "a+b.c-d:value", ":value", "https://a@b@c/", "https://example.com/#", "https://example.com/?",
        )
        private val mediaTypes = listOf(null, "application/json", "Application/JSON", " application/json ; charset=UTF-8",
            "application/json; charset=\"utf-8\"", "application/json; profile=\"a;b\"", "application/json; profile=\"a\\;b\"",
            "application/json; profile=\"a\\\"b\"; charset=utf-8", "application/json; profile=\"a\\\\b\"", "text/plain",
            "application/problem+json", "application/json; charset=iso-8859-1", "application/json; charset=utf-8; charset=utf-8",
            "application/json; broken", "application/json\r\nX-Synthetic: invalid", "application/json; profile=\"a;b",
            "application/json; profile=\"a\"junk", "application/json; profile\"a\"", "application/json; profile=", "application/json;",
            "application/json; profile=\"a\u0001b\"", "application/json; charset=\"UTF-8\"; profile=ok", "application/json" + " ".repeat(4096))
    }
}
