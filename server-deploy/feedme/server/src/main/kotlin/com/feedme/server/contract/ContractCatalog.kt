package com.feedme.server.contract

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ContractOperation(
    val method: String,
    val path: String,
    val id: String,
    val principal: String,
    val module: String,
    val featureIds: Set<String>,
)

/** Routing metadata only; this does not validate request bodies or implement authorization. */
class ContractCatalog private constructor(val document: JsonObject, val operations: List<ContractOperation>) {
    companion object {
        const val SOURCE_SHA256 = "dd4f07fff326276e257fa3bba64ae61386c33c880145a8c8b431bc50a8202707"
        private val methods = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
        private val principals = setOf("public", "user", "both", "guest", "admin", "webhook")
        private val featureIds = (1..54).mapTo(linkedSetOf()) { "F${it.toString().padStart(2, '0')}" }

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
                    val id = op.getValue("operationId").jsonPrimitive.content
                    val operationFeatureIds = op.getValue("x-feature-ids").jsonArray
                        .map { it.jsonPrimitive.content }
                    check(operationFeatureIds.distinct().size == operationFeatureIds.size)
                    check(operationFeatureIds.all { it in featureIds })
                    if (principal == "guest" || id == "getCurrentGuestSession" || path == "/v1/guest-sessions/current") {
                        check(principal == "guest" && id == "getCurrentGuestSession" && method == "get" && path == "/v1/guest-sessions/current")
                        check(op.getValue("security").toString() == "[{\"GuestBearer\":[]}]")
                        check(op.getValue("parameters").toString() == "[]" && "requestBody" !in op)
                        check(op.getValue("x-idempotency-required").jsonPrimitive.content == "false")
                    }
                    check("503" in op.getValue("responses").jsonObject) { "Unimplemented operation must declare service unavailable" }
                    ContractOperation(method.uppercase(), path, op.getValue("operationId").jsonPrimitive.content,
                        principal, op.getValue("x-module").jsonPrimitive.content, operationFeatureIds.toSet())
                }
            }
            check(paths.size == 160 && operations.size == 207)
            check(operations.map { it.id }.distinct().size == operations.size)
            check(operations.flatMap { it.featureIds }.toSet() == featureIds)
            check(operations.single { it.id == "getServiceHealth" }.let {
                it.method == "GET" && it.path == "/v1/health" && it.principal == "public"
            })
            return ContractCatalog(document, operations)
        }
    }
}
