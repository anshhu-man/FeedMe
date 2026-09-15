package com.feedme.contracts

import kotlin.test.*

class CanonicalBodyValidatorReuseTest {
    private val valid = "{\"planId\":\"123e4567-e89b-12d3-a456-426614174000\"}".encodeToByteArray()

    @Test fun defaultConstructionReusesExactlyOneCompiledValidator() {
        val first = CanonicalBodyValidator.bundled()
        repeat(32) { assertSame(first, CanonicalBodyValidator.bundled()) }
        assertEquals(ContractValidationResult.Valid, first.validateRequest("createCookSession", valid, "application/json"))
    }

    @Test fun explicitCatalogRulesAreCompiledIndependentlyAndDoNotContaminateDefaults() {
        val shared = CanonicalBodyValidator.bundled()
        val catalog = ContractCatalog.bundled()
        val media = checkNotNull(catalog.operation("createCookSession")?.requestBody).content as MutableMap<String, SchemaDefinition>
        media["application/json"] = SchemaDefinition("false")
        val explicit = CanonicalBodyValidator.bundled(catalog = catalog)
        assertNotSame(shared, explicit)
        assertEquals(ContractRejectionReason.SCHEMA_VIOLATION,
            assertIs<ContractValidationResult.Rejected>(explicit.validateRequest("createCookSession", valid, "application/json")).reason)
        assertSame(shared, CanonicalBodyValidator.bundled())
        assertEquals(ContractValidationResult.Valid, shared.validateRequest("createCookSession", valid, "application/json"))
        val fresh = CanonicalBodyValidator.bundled(ContractCatalog.bundled())
        assertNotSame(shared, fresh); assertNotSame(explicit, fresh)
        assertEquals(ContractValidationResult.Valid, fresh.validateRequest("createCookSession", valid, "application/json"))
    }

    @Test fun unsupportedExplicitSchemaStillFailsWithoutReplacingDefaultGraph() {
        val shared = CanonicalBodyValidator.bundled()
        val catalog = ContractCatalog.bundled()
        val media = checkNotNull(catalog.operation("createCookSession")?.requestBody).content as MutableMap<String, SchemaDefinition>
        media["application/json"] = SchemaDefinition("{\"unsupportedKeyword\":true}")
        assertFailsWith<IllegalStateException> { CanonicalBodyValidator.bundled(catalog) }
        assertSame(shared, CanonicalBodyValidator.bundled())
        assertEquals(ContractValidationResult.Valid, shared.validateRequest("createCookSession", valid, "application/json"))
    }

    @Test fun sharedValidatorKeepsSyntaxSchemaMediaAndResourceFailuresPerCall() {
        val shared = CanonicalBodyValidator.bundled()
        val original = valid.copyOf()
        repeat(20) {
            assertEquals(ContractRejectionReason.INPUT_SYNTAX, rejected(shared.validateRequest("createCookSession", "{".encodeToByteArray(), "application/json")))
            assertEquals(ContractRejectionReason.SCHEMA_VIOLATION, rejected(shared.validateRequest("createCookSession", "{\"planId\":true}".encodeToByteArray(), "application/json")))
            assertEquals(ContractRejectionReason.UNSUPPORTED_MEDIA, rejected(shared.validateRequest("createCookSession", valid, "text/plain")))
            assertEquals(ContractRejectionReason.RESOURCE_LIMIT, rejected(shared.validateSchema("Health", "{\"n\":1e10001}".encodeToByteArray())))
            assertEquals(ContractValidationResult.Valid, shared.validateRequest("createCookSession", valid, "application/json"))
            assertEquals(ContractValidationResult.Valid, shared.validateResponse("deletePost", 204, null, null))
            assertEquals(ContractRejectionReason.UNEXPECTED_BODY, rejected(shared.validateResponse("deletePost", 204, "null".encodeToByteArray(), null)))
        }
        assertContentEquals(original, valid)
    }

    private fun rejected(result: ContractValidationResult) = assertIs<ContractValidationResult.Rejected>(result).reason
}
