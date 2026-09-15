package com.feedme.contracts

import com.feedme.contracts.generated.GeneratedContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** These tests verify bundled metadata and caller projections, not payload validation or authorization. */
class ContractCatalogTest {
    private val catalog = ContractCatalog.bundled()

    @Test fun exactCanonicalCoverageIsPinned() {
        assertEquals("64cb5009fc71b7428e1756b0d82d3ccdae951121f7815b7904eef10b4fb754dd", catalog.sourceSha256)
        assertEquals(201, catalog.operations.size)
        assertEquals(201, catalog.operations.map { it.id }.toSet().size)
        assertEquals(155, catalog.operations.map { it.path }.toSet().size)
        assertEquals(188, catalog.schemaNames.size)
        assertEquals(mapOf("public" to 3, "user" to 123, "both" to 36, "admin" to 38, "webhook" to 1),
            catalog.operations.groupingBy { it.principal }.eachCount())
        assertNull(catalog.operation("unknownOperation"))
        assertNull(catalog.operationJson("unknownOperation"))
        assertNull(catalog.schema("UnknownSchema"))
    }

    @Test fun everyScreenBindingIsRetainedAndHttpBindingsResolve() {
        val bindings = catalog.screenBindings
        assertEquals(1031, bindings.size)
        assertEquals(1031, bindings.map { it.id }.toSet().size)
        assertEquals(98, bindings.map { it.screenId }.toSet().size)
        assertEquals(900, bindings.count { it.kind == BindingKind.ACTION })
        assertEquals(152, bindings.count { it.kind == BindingKind.ACTION && it.operationId != null })
        assertEquals(99, bindings.count { it.kind == BindingKind.HYDRATION && it.operationId != null })
        assertEquals(32, bindings.count { it.kind == BindingKind.HYDRATION && it.operationId == null })
        bindings.forEach { binding ->
            val operationId = binding.operationId
            if (operationId == null) {
                assertTrue(binding.request.startsWith("LOCAL ") || binding.request.startsWith("EXTERNAL "), binding.id)
            } else {
                val operation = assertNotNull(catalog.operation(operationId), binding.id)
                assertEquals("${operation.method} ${operation.path}", binding.request, binding.id)
            }
        }
        assertEquals(ScreenOperationBinding("AUTH_WELCOME", "AUTH_WELCOME.05", BindingKind.ACTION,
            "POST /v1/guest-sessions", "createGuestSession"), bindings.single { it.id == "AUTH_WELCOME.05" })
        assertEquals(ScreenOperationBinding("AUTH_WELCOME", "AUTH_WELCOME.load.0", BindingKind.HYDRATION,
            "GET /v1/config", "getClientConfig"), bindings.single { it.id == "AUTH_WELCOME.load.0" })
    }

    @Test fun callerMatrixKeepsEveryPrincipalClassDistinct() {
        val allowedCallers = mapOf(
            "public" to setOf(PrincipalClass.PUBLIC),
            "user" to setOf(PrincipalClass.ACCOUNT),
            "both" to setOf(PrincipalClass.ACCOUNT, PrincipalClass.GUEST),
            "admin" to setOf(PrincipalClass.STAFF),
            "webhook" to setOf(PrincipalClass.WEBHOOK),
        )
        val schemes = mapOf(PrincipalClass.ACCOUNT to "UserBearer", PrincipalClass.GUEST to "GuestBearer",
            PrincipalClass.STAFF to "StaffBearer", PrincipalClass.WEBHOOK to "WebhookAuthorization")
        catalog.operations.forEach { operation ->
            PrincipalClass.entries.forEach { caller ->
                val context = "${operation.id}: $caller"
                val requirements = operation.requirementsFor(caller)
                if (caller !in allowedCallers.getValue(operation.principal)) {
                    assertNull(requirements, context)
                } else {
                    assertNotNull(requirements, context)
                    val expectedSecurity = schemes[caller]?.let { listOf(SecurityAlternative(mapOf(it to emptyList()))) }
                        ?: emptyList()
                    assertEquals(expectedSecurity, requirements.security, context)
                    val accountSession = caller == PrincipalClass.ACCOUNT && operation.id != "bootstrapAccount"
                    assertEquals(if (accountSession) DeviceSessionPolicy.REQUIRED else DeviceSessionPolicy.OMIT,
                        requirements.deviceSession, context)
                    assertEquals(accountSession, "X-Device-Session" in requirements.requiredParameterHeaders, context)
                }
            }
        }
    }

    @Test fun staffAndWebhookOperationsAreAbsentFromMobileSurface() {
        assertEquals(162, catalog.operationsFor(ContractSurface.MOBILE).size)
        assertEquals(38, catalog.operationsFor(ContractSurface.STAFF).size)
        assertEquals(1, catalog.operationsFor(ContractSurface.WEBHOOK).size)
        catalog.operations.forEach { operation ->
            val expected = when (operation.principal) {
                "admin" -> ContractSurface.STAFF
                "webhook" -> ContractSurface.WEBHOOK
                else -> ContractSurface.MOBILE
            }
            ContractSurface.entries.forEach { surface ->
                assertEquals(if (surface == expected) operation else null,
                    catalog.operationFor(surface, operation.id), "${operation.id}: $surface")
            }
        }
        assertNull(catalog.operationFor(ContractSurface.MOBILE, "adminGetHealth"))
        assertNull(catalog.operationFor(ContractSurface.MOBILE, "receiveRevenueCatWebhook"))
        assertEquals("both", operation("getClientConfig").principal)
        assertTrue(operation("getServiceHealth").security.isEmpty())
    }

    @Test fun bootstrapRequiresAccountBearerAndIdempotencyWithoutAnExistingAppSession() {
        val bootstrap = operation("bootstrapAccount")
        assertEquals("POST", bootstrap.method)
        assertEquals("/v1/account/bootstrap", bootstrap.path)
        assertFalse(bootstrap.parameters.any { it.name == "X-Device-Session" })
        val requirements = assertNotNull(bootstrap.requirementsFor(PrincipalClass.ACCOUNT))
        assertEquals(DeviceSessionPolicy.OMIT, requirements.deviceSession)
        assertEquals(setOf("Idempotency-Key"), requirements.requiredParameterHeaders)
        assertEquals(listOf(SecurityAlternative(mapOf("UserBearer" to emptyList()))), requirements.security)
        assertNull(bootstrap.requirementsFor(PrincipalClass.GUEST))
    }

    @Test fun dualPrincipalHeadersDependOnTheCallerAndRetainWritePreconditions() {
        val update = operation("updatePreferences")
        assertFalse(update.parameters.single { it.name == "X-Device-Session" }.required)
        val account = assertNotNull(update.requirementsFor(PrincipalClass.ACCOUNT))
        val guest = assertNotNull(update.requirementsFor(PrincipalClass.GUEST))
        assertEquals(setOf("X-Device-Session", "Idempotency-Key", "If-Match"), account.requiredParameterHeaders)
        assertEquals(setOf("Idempotency-Key", "If-Match"), guest.requiredParameterHeaders)
        assertEquals(DeviceSessionPolicy.REQUIRED, account.deviceSession)
        assertEquals(DeviceSessionPolicy.OMIT, guest.deviceSession)
        assertEquals(listOf(SecurityAlternative(mapOf("UserBearer" to emptyList()))), account.security)
        assertEquals(listOf(SecurityAlternative(mapOf("GuestBearer" to emptyList()))), guest.security)
        assertEquals(listOf(SecurityAlternative(mapOf("UserBearer" to emptyList())),
            SecurityAlternative(mapOf("GuestBearer" to emptyList()))), update.security)
    }

    @Test fun idempotencyAndIfMatchMetadataArePreservedForEveryOperation() {
        assertEquals(133, catalog.operations.count { it.idempotencyRequired })
        assertEquals(61, catalog.operations.count { operation -> operation.parameters.any { it.name == "If-Match" } })
        catalog.operations.forEach { operation ->
            val idempotency = operation.parameters.singleOrNull { it.name == "Idempotency-Key" }
            assertEquals(operation.idempotencyRequired, idempotency != null, operation.id)
            if (idempotency != null) {
                assertTrue(idempotency.required, operation.id)
                assertEquals(ParameterLocation.HEADER, idempotency.location)
                assertEquals(json("""{"type":"string","format":"uuid"}"""), idempotency.schema.element())
                assertTrue(assertNotNull(idempotency.description).contains("Reuse on retries."))
            }
            operation.parameters.singleOrNull { it.name == "If-Match" }?.let { parameter ->
                assertTrue(parameter.required, operation.id)
                assertEquals(ParameterLocation.HEADER, parameter.location)
                assertEquals("^\"[0-9]+\"$", parameter.schema.element().jsonObject.getValue("pattern").jsonPrimitive.content)
                assertTrue(assertNotNull(parameter.description).contains("Missing 428, stale 412."))
            }
        }
    }

    @Test fun allDeclaredStatusCodesAndNoContentResponsesSurviveProjection() {
        val errors = setOf(400, 401, 403, 404, 409, 410, 412, 422, 428, 429, 500, 503)
        assertEquals(errors + setOf(200, 201, 202, 204), catalog.operations.flatMap { it.responses.keys }.toSet())
        assertEquals(mapOf(200 to 143, 201 to 27, 202 to 6, 204 to 25),
            catalog.operations.flatMap { it.responses.keys }.filter { it < 300 }.groupingBy { it }.eachCount())
        catalog.operations.forEach { operation ->
            assertEquals(errors, operation.responses.keys.filter { it >= 400 }.toSet(), operation.id)
            operation.responses.forEach { (status, response) ->
                assertEquals(status, response.status, operation.id)
                if (status == 204) {
                    assertTrue(response.content.isEmpty(), operation.id)
                    assertTrue(response.description.isNotBlank(), operation.id)
                } else if (status >= 400) {
                    assertEquals(setOf("application/problem+json"), response.content.keys, operation.id)
                    assertEquals(json("""{"${'$'}ref":"#/components/schemas/Problem"}"""),
                        response.content.getValue("application/problem+json").element(), operation.id)
                }
            }
        }
        assertEquals("Completed; no response body.", operation("removePantryItem").responses.getValue(204).description)
    }

    @Test fun etagAndRetryAfterRemainResponseHeaderMetadata() {
        val update = operation("updateMe")
        val etag = update.responses.getValue(200).headers.single { it.name == "ETag" }
        assertEquals("Quoted integer version for If-Match writes.", etag.description)
        assertEquals(json("""{"type":"string"}"""), etag.schema.element())
        assertFalse(etag.required)
        for (status in listOf(429, 503)) {
            val retry = update.responses.getValue(status).headers.single { it.name == "Retry-After" }
            assertEquals(json("""{"type":"integer","minimum":1}"""), retry.schema.element())
            assertEquals(setOf("application/problem+json"), update.responses.getValue(status).content.keys)
        }
        assertEquals("Rate limit; respect Retry-After", update.responses.getValue(429).description)
    }

    @Test fun schemaAndResponseProjectionsRetainAllBundledJsonFields() {
        val document = json(GeneratedContract.documentJson()).jsonObject
        document.getValue("components").jsonObject.getValue("schemas").jsonObject.forEach { (name, original) ->
            assertEquals(original, assertNotNull(catalog.schema(name)).element(), name)
        }
        document.getValue("paths").jsonObject.values.forEach { item ->
            item.jsonObject.values.forEach { operationJson ->
                val original = operationJson.jsonObject
                val operation = operation(original.getValue("operationId").jsonPrimitive.content)
                assertEquals(original, json(assertNotNull(catalog.operationJson(operation.id))))
                assertEquals(original.getValue("responses").jsonObject.keys.map { it.toInt() }.toSet(), operation.responses.keys)
                original.getValue("responses").jsonObject.forEach { (status, responseJson) ->
                    val response = resolveLocal(document, responseJson)
                    val projected = operation.responses.getValue(status.toInt())
                    assertEquals(response.getValue("description").jsonPrimitive.content, projected.description)
                    val expectedContent = (response["content"] as? JsonObject).orEmpty()
                        .mapValues { (_, media) -> media.jsonObject.getValue("schema") }
                    assertEquals(expectedContent, projected.content.mapValues { it.value.element() },
                        "${operation.id}: $status")
                    val headers = (response["headers"] as? JsonObject).orEmpty()
                    assertEquals(headers.keys, projected.headers.map { it.name }.toSet())
                    projected.headers.forEach { header ->
                        val expected = headers.getValue(header.name).jsonObject
                        assertEquals(expected.getValue("schema"), header.schema.element())
                        assertEquals(expected["description"]?.jsonPrimitive?.content, header.description)
                        assertEquals(expected["required"]?.jsonPrimitive?.boolean ?: false, header.required)
                    }
                }
            }
        }
    }

    @Test fun nullableAndAnyOfRemainExactSchemaMetadata() {
        val pantry = schema("PantryItem")
        val confirmedAt = pantry.getValue("properties").jsonObject.getValue("confirmedAt").jsonObject
        assertEquals(json("""["string","null"]"""), confirmedAt.getValue("type"))
        assertEquals("date-time", confirmedAt.getValue("format").jsonPrimitive.content)
        assertTrue("confirmedAt" in pantry.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val feedback = schema("FeedbackWrite")
        assertEquals(json("""[{"required":["cookSessionId"]},{"required":["target"]}]"""), feedback.getValue("anyOf"))
        assertEquals(JsonArray(emptyList()), feedback.getValue("required"))
        assertFalse(feedback.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(json("""[{"required":["planId"]},{"required":["recipeVersionId"]}]"""),
            schema("SaveRecipeRequest").getValue("anyOf"))
    }

    @Test fun conditionalAndUniqueItemRulesRemainExactSchemaMetadata() {
        assertEquals(json("""[{"if":{"properties":{"mode":{"const":"improve"}},"required":["mode"]},"then":{"required":["baseMeal"]}}]"""),
            schema("PlanRequest").getValue("allOf"))
        assertEquals(json("""[{"if":{"properties":{"kind":{"enum":["cookSession","plan","recipeVersion","ingredient"]}}},"then":{"required":["resourceId"]}},{"if":{"properties":{"kind":{"enum":["taste","preparation"]}}},"then":{"required":["tag"]}}]"""),
            schema("FeedbackTarget").getValue("allOf"))
        assertEquals(json("""{"type":"array","items":{"type":"string","format":"uuid"},"uniqueItems":true,"maxItems":1000}"""),
            schema("CollectionOrder").getValue("properties").jsonObject.getValue("orderedSavedRecipeIds"))
    }

    @Test fun originalReferencesResolveWithoutChangingStringFormatsIntoRuntimeTypes() {
        val guest = operation("createGuestSession")
        val body = assertNotNull(guest.requestBody)
        assertTrue(body.required)
        assertEquals(setOf("application/json"), body.content.keys)
        assertEquals(json("""{"${'$'}ref":"#/components/schemas/GuestRequest"}"""), body.content.getValue("application/json").element())
        assertEquals(schema("GuestRequest"), catalog.resolveSchema("#/components/schemas/GuestRequest").element())
        val refs = mutableSetOf<String>()
        catalog.schemaNames.forEach { name -> collectReferences(schema(name), refs) }
        assertTrue(refs.isNotEmpty())
        refs.forEach { reference ->
            assertEquals(schema(reference.removePrefix("#/components/schemas/")), catalog.resolveSchema(reference).element(), reference)
        }
        listOf("id" to "uuid", "createdAt" to "date-time").forEach { (name, format) ->
            val property = schema("PantryItem").getValue("properties").jsonObject.getValue(name).jsonObject
            assertEquals("string", property.getValue("type").jsonPrimitive.content)
            assertEquals(format, property.getValue("format").jsonPrimitive.content)
        }
        assertEquals(json("""{"type":"string","format":"uri"}"""), schema("Problem").getValue("properties").jsonObject.getValue("type"))
        assertEquals("SchemaDefinition(metadata)", assertNotNull(catalog.schema("Problem")).toString())
        assertFailsWith<IllegalArgumentException> { catalog.resolveSchema("https://example.invalid/schema.json") }
        assertFailsWith<IllegalArgumentException> { catalog.resolveSchema("#/components/responses/Error400") }
        assertFailsWith<IllegalStateException> { catalog.resolveSchema("#/components/schemas/UnknownSchema") }
    }

    @Test fun securityDescriptionsRetainVerificationRequirementsAsMetadata() {
        assertEquals(setOf("UserBearer", "GuestBearer", "StaffBearer", "WebhookAuthorization"), catalog.securitySchemeNames)
        val user = json(assertNotNull(catalog.securitySchemeJson("UserBearer"))).jsonObject
        assertEquals("Cognito access JWT", user.getValue("bearerFormat").jsonPrimitive.content)
        assertTrue(user.getValue("description").jsonPrimitive.content.contains("token_use=access"))
        val webhook = json(assertNotNull(catalog.securitySchemeJson("WebhookAuthorization"))).jsonObject
        assertEquals("Authorization", webhook.getValue("name").jsonPrimitive.content)
        assertTrue(webhook.getValue("description").jsonPrimitive.content.contains("raw-body HMAC"))
        val requirements = assertNotNull(operation("receiveRevenueCatWebhook").requirementsFor(PrincipalClass.WEBHOOK))
        assertEquals(setOf("X-RevenueCat-Webhook-Signature"), requirements.requiredParameterHeaders)
        assertNull(catalog.securitySchemeJson("UnknownScheme"))
    }

    private fun operation(id: String): OperationDefinition = assertNotNull(catalog.operation(id))
    private fun schema(name: String): JsonObject = assertNotNull(catalog.schema(name)).element().jsonObject
    private fun SchemaDefinition.element(): JsonElement = json(this.json)
    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)

    private fun resolveLocal(document: JsonObject, value: JsonElement): JsonObject {
        val objectValue = value.jsonObject
        val reference = objectValue["\$ref"]?.jsonPrimitive?.content ?: return objectValue
        return resolveLocal(document, reference.removePrefix("#/").split('/').fold(document as JsonElement) { node, key ->
            node.jsonObject.getValue(key.replace("~1", "/").replace("~0", "~"))
        })
    }

    private fun collectReferences(value: JsonElement, references: MutableSet<String>) {
        when (value) {
            is JsonObject -> {
                value["\$ref"]?.jsonPrimitive?.content?.let { references.add(it) }
                value.values.forEach { collectReferences(it, references) }
            }
            is JsonArray -> value.forEach { collectReferences(it, references) }
            else -> Unit
        }
    }
}
