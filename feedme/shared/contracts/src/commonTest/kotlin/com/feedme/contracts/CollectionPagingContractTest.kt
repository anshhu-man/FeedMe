package com.feedme.contracts

import kotlin.test.*

class CollectionPagingContractTest {
    private val catalog = ContractCatalog.bundled()
    private val validator = CanonicalBodyValidator.bundled(catalog)
    private val base = """{"id":"00000000-0000-4000-8000-000000000001","version":1,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","name":"Cookbook","savedRecipeIds":[]"""
    @Test fun legacyCompleteAndExplicitTerminalAndContinuationAreDistinctValidShapes() {
        for (suffix in listOf("}", ",\"nextItemCursor\":null}", ",\"nextItemCursor\":\"opaque-page\"}"))
            assertEquals(ContractValidationResult.Valid, validator.validateSchema("Collection", (base + suffix).encodeToByteArray()))
    }
    @Test fun cursorIsBoundedTypedAndUnknownFieldsRemainRejected() {
        for (suffix in listOf(",\"nextItemCursor\":false}", ",\"nextItemCursor\":\"${"x".repeat(2049)}\"}", ",\"hasMore\":true}"))
            assertIs<ContractValidationResult.Rejected>(validator.validateSchema("Collection", (base + suffix).encodeToByteArray()))
    }
    @Test fun basicGuestContinuationPreservesAccountDeviceAndGuestHeaderPolicies() {
        val op = assertNotNull(catalog.operation("getCollection"))
        assertEquals(setOf("cursor", "limit"), op.parameters.filter { it.location == ParameterLocation.QUERY }.map { it.name }.toSet())
        assertEquals(DeviceSessionPolicy.REQUIRED, assertNotNull(op.requirementsFor(PrincipalClass.ACCOUNT)).deviceSession)
        assertEquals(DeviceSessionPolicy.OMIT, assertNotNull(op.requirementsFor(PrincipalClass.GUEST)).deviceSession)
        assertNull(op.requirementsFor(PrincipalClass.PUBLIC))
        for (id in listOf("createCollection", "updateCollection", "deleteCollection"))
            assertNull(assertNotNull(catalog.operation(id)).requirementsFor(PrincipalClass.GUEST))
    }
}
