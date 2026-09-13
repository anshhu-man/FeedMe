package com.feedme.server

import com.feedme.server.contract.ContractCatalog
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContractCatalogTest {
    @Test fun canonicalRoutesHaveCompleteStableCoverage() {
        val catalog = ContractCatalog.bundled()
        assertEquals(201, catalog.operations.size)
        assertEquals(155, catalog.operations.map { it.path }.distinct().size)
        assertEquals(201, catalog.operations.map { it.id }.distinct().size)
        assertEquals(mapOf("public" to 3, "user" to 124, "both" to 35, "admin" to 38, "webhook" to 1),
            catalog.operations.groupingBy { it.principal }.eachCount())
    }

    @Test fun privateConfigurationAndStaffHealthNeverBecomePublic() {
        val catalog = ContractCatalog.bundled()
        assertEquals("both", catalog.operations.single { it.id == "getClientConfig" }.principal)
        assertEquals("admin", catalog.operations.single { it.id == "adminGetHealth" }.principal)
        val health = catalog.document.getValue("paths").jsonObject.getValue("/v1/health").jsonObject.getValue("get").jsonObject
        assertTrue(health.getValue("security").jsonArray.isEmpty())
    }

    @Test fun changedBytesCannotSilentlyChangeRoutes() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/feedme-openapi.json")).use { it.readBytes() }
        assertFailsWith<IllegalStateException> { ContractCatalog.fromPinnedBytes(bytes + "\n".toByteArray()) }
        assertFailsWith<IllegalStateException> { ContractCatalog.fromPinnedBytes("{}".toByteArray()) }
    }

    @Test fun healthAndProblemsArePinnedToCanonicalShapes() {
        val schemas = ContractCatalog.bundled().document.getValue("components").jsonObject.getValue("schemas").jsonObject
        val health = schemas.getValue("Health").jsonObject
        assertEquals(setOf("status", "serverTime", "minimumAppVersion"), health.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(setOf("status", "serverTime", "minimumAppVersion", "flagsRevision"), health.getValue("properties").jsonObject.keys)
        val problem = schemas.getValue("Problem").jsonObject
        assertEquals(setOf("type", "title", "status", "code", "traceId"), problem.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("false", health.getValue("additionalProperties").jsonPrimitive.content)
        assertEquals("false", problem.getValue("additionalProperties").jsonPrimitive.content)
    }
}
