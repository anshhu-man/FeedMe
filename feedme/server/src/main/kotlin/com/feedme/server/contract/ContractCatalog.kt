package com.feedme.server.contract

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ContractOperation(val method: String, val path: String, val id: String, val principal: String, val module: String)

/** Routing metadata only; this does not validate request bodies or implement authorization. */
class ContractCatalog private constructor(val document: JsonObject, val operations: List<ContractOperation>) {
    companion object {
        const val SOURCE_SHA256 = "64cb5009fc71b7428e1756b0d82d3ccdae951121f7815b7904eef10b4fb754dd"
        private val methods = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
        private val principals = setOf("public", "user", "both", "admin", "webhook")

        fun bundled(): ContractCatalog {
            val bytes = checkNotNull(ContractCatalog::class.java.getResourceAsStream("/feedme-openapi.json")) {
                "Canonical contract resource missing"
            }.use { it.readBytes() }
            return fromPinnedBytes(bytes)
        }

        internal fun fromPinnedBytes(bytes: ByteArray): ContractCatalog {
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hash == SOURCE_SHA256) { "Canonical contract changed; review routing and contract tests before updating the lock" }
            val document = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            check(document.getValue("openapi").jsonPrimitive.content == "3.1.1")
            val paths = document.getValue("paths").jsonObject
            val operations = paths.flatMap { (path, item) ->
                check(path.startsWith("/v1/"))
                item.jsonObject.filterKeys { it in methods }.map { (method, value) ->
                    val op = value.jsonObject
                    val principal = op.getValue("x-principal").jsonPrimitive.content
                    check(principal in principals)
                    check("503" in op.getValue("responses").jsonObject) { "Unimplemented operation must declare service unavailable" }
                    ContractOperation(method.uppercase(), path, op.getValue("operationId").jsonPrimitive.content,
                        principal, op.getValue("x-module").jsonPrimitive.content)
                }
            }
            check(paths.size == 155 && operations.size == 201)
            check(operations.map { it.id }.distinct().size == operations.size)
            check(operations.single { it.id == "getServiceHealth" }.let {
                it.method == "GET" && it.path == "/v1/health" && it.principal == "public"
            })
            return ContractCatalog(document, operations)
        }
    }
}
