package com.feedme.server.contract

import com.feedme.contracts.ContractCatalog as SharedContractCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedContractParityTest {
    @Test fun generatedSharedMetadataMatchesEveryBundledServerRoute() {
        val shared = SharedContractCatalog.bundled()
        val server = ContractCatalog.bundled()
        assertEquals(ContractCatalog.SOURCE_SHA256, shared.sourceSha256)
        assertEquals(server.operations.size, shared.operations.size)
        assertEquals(server.operations.associate { it.id to listOf(it.method, it.path, it.principal, it.module) },
            shared.operations.associate { it.id to listOf(it.method, it.path, it.principal, it.module) })
        for ((name, schema) in server.document.getValue("components").let { it as kotlinx.serialization.json.JsonObject }
            .getValue("schemas").let { it as kotlinx.serialization.json.JsonObject }) {
            assertEquals(schema, kotlinx.serialization.json.Json.parseToJsonElement(checkNotNull(shared.schema(name)).json), name)
        }
    }
}
