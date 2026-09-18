package com.feedme.contracts

import com.feedme.contracts.generated.GeneratedContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Static caller category, not an authenticated identity or proof of server authorization. */
enum class PrincipalClass { PUBLIC, ACCOUNT, GUEST, STAFF, WEBHOOK }
enum class ContractSurface { MOBILE, STAFF, WEBHOOK }
enum class ParameterLocation { PATH, QUERY, HEADER }
enum class DeviceSessionPolicy { OMIT, REQUIRED }
enum class BindingKind { ACTION, HYDRATION }

/** Lossless schema metadata. Deliberately has no `isValid` API until exact validation is verified. */
class SchemaDefinition internal constructor(val json: String) {
    override fun toString(): String = "SchemaDefinition(metadata)"
}

data class ParameterDefinition(
    val name: String,
    val location: ParameterLocation,
    val required: Boolean,
    val description: String?,
    val schema: SchemaDefinition,
)

data class ResponseHeaderDefinition(
    val name: String,
    val required: Boolean,
    val description: String?,
    val schema: SchemaDefinition,
)

/** Media-type keys remain exact. An empty response map means no declared body (including 204). */
data class BodyDefinition(val required: Boolean, val content: Map<String, SchemaDefinition>)
data class ResponseDefinition(
    val status: Int,
    val description: String,
    val headers: List<ResponseHeaderDefinition>,
    val content: Map<String, SchemaDefinition>,
)

/** Security-scheme names and scopes are metadata; this never verifies a token, role or signature. */
data class SecurityAlternative(val schemes: Map<String, List<String>>)
data class CallerRequirements(
    val security: List<SecurityAlternative>,
    val requiredParameterHeaders: Set<String>,
    val deviceSession: DeviceSessionPolicy,
)

@ConsistentCopyVisibility
data class OperationDefinition internal constructor(
    val id: String,
    val method: String,
    val path: String,
    val module: String,
    val featureIds: List<String>,
    val summary: String,
    val description: String,
    val principal: String,
    val idempotencyRequired: Boolean,
    val security: List<SecurityAlternative>,
    val parameters: List<ParameterDefinition>,
    val requestBody: BodyDefinition?,
    val responses: Map<Int, ResponseDefinition>,
) {
    val surface: ContractSurface get() = when (principal) {
        "admin" -> ContractSurface.STAFF
        "webhook" -> ContractSurface.WEBHOOK
        else -> ContractSurface.MOBILE
    }

    /** Pure preflight metadata. Success here must never stand in for live authorization. */
    fun requirementsFor(caller: PrincipalClass): CallerRequirements? {
        val allowed = when (principal) {
            "public" -> caller == PrincipalClass.PUBLIC
            "user" -> caller == PrincipalClass.ACCOUNT
            "both" -> caller == PrincipalClass.ACCOUNT || caller == PrincipalClass.GUEST
            "guest" -> caller == PrincipalClass.GUEST && id == "getCurrentGuestSession" &&
                method == "GET" && path == "/v1/guest-sessions/current" && !idempotencyRequired &&
                parameters.isEmpty() && requestBody == null && security == listOf(SecurityAlternative(mapOf("GuestBearer" to emptyList())))
            "admin" -> caller == PrincipalClass.STAFF
            "webhook" -> caller == PrincipalClass.WEBHOOK
            else -> false
        }
        if (!allowed) return null
        val deviceSession = if (caller == PrincipalClass.ACCOUNT && parameters.any { it.name == "X-Device-Session" })
            DeviceSessionPolicy.REQUIRED else DeviceSessionPolicy.OMIT
        val requiredHeaders = parameters.filter { it.location == ParameterLocation.HEADER && it.required }
            .map { it.name }.toMutableSet()
        if (deviceSession == DeviceSessionPolicy.REQUIRED) requiredHeaders.add("X-Device-Session")
        val scheme = when (caller) {
            PrincipalClass.PUBLIC -> null
            PrincipalClass.ACCOUNT -> "UserBearer"
            PrincipalClass.GUEST -> "GuestBearer"
            PrincipalClass.STAFF -> "StaffBearer"
            PrincipalClass.WEBHOOK -> "WebhookAuthorization"
        }
        return CallerRequirements(security.filter { scheme in it.schemes }, requiredHeaders.toSet(), deviceSession)
    }
}

data class ScreenOperationBinding(
    val screenId: String,
    val id: String,
    val kind: BindingKind,
    val request: String,
    val operationId: String?,
)

/**
 * Owned, generated-from-source metadata, not generated DTOs or an HTTP client.
 * Keeps all OpenAPI fields in the bundled document. Typed projections below are convenience views;
 * callers can inspect schema JSON without serializing through a lossy model.
 * No remote references, credential handling, network or user data are involved.
 */
class ContractCatalog private constructor(private val document: JsonObject, bindings: JsonArray) {
    val sourceSha256: String get() = GeneratedContract.SOURCE_SHA256
    val schemaNames: Set<String> = document.objectAt("components").objectAt("schemas").keys.toSet()
    val securitySchemeNames: Set<String> = document.objectAt("components").objectAt("securitySchemes").keys.toSet()
    val operations: List<OperationDefinition> = document.objectAt("paths").flatMap { (path, item) ->
        item.jsonObject.map { (method, value) -> operation(path, method, value.jsonObject) }
    }
    private val byId = operations.associateBy { it.id }
    val screenBindings: List<ScreenOperationBinding> = bindings.map { value ->
        val binding = value.jsonObject
        ScreenOperationBinding(binding.stringAt("screenId"), binding.stringAt("id"),
            BindingKind.valueOf(binding.stringAt("kind").uppercase()), binding.stringAt("request"),
            binding["operationId"]?.let { if (it == kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content })
    }

    fun operation(id: String): OperationDefinition? = byId[id]
    fun operationFor(surface: ContractSurface, id: String): OperationDefinition? = byId[id]?.takeIf { it.surface == surface }
    fun operationsFor(surface: ContractSurface): List<OperationDefinition> = operations.filter { it.surface == surface }
    fun schema(name: String): SchemaDefinition? = document.objectAt("components").objectAt("schemas")[name]?.asSchema()

    /** Full original operation fields, including examples/tags not in the convenience projection. */
    fun operationJson(id: String): String? = byId[id]?.let {
        document.objectAt("paths").objectAt(it.path).getValue(it.method.lowercase()).toString()
    }

    /** Original security descriptions include required verification beyond basic header presence. */
    fun securitySchemeJson(name: String): String? = document.objectAt("components").objectAt("securitySchemes")[name]?.toString()

    /** Preserve original local references; resolve on demand, never fetch external schema URLs. */
    fun resolveSchema(reference: String): SchemaDefinition {
        require(reference.startsWith("#/components/schemas/")) { "Not a component schema reference" }
        return resolveReference(reference).asSchema()
    }

    private fun operation(path: String, method: String, op: JsonObject): OperationDefinition {
        val parameters = (op["parameters"] as? JsonArray).orEmpty().map { value ->
            val p = resolve(value)
            ParameterDefinition(p.stringAt("name"), ParameterLocation.valueOf(p.stringAt("in").uppercase()),
                p.booleanAt("required"), p.optionalString("description"), p.getValue("schema").asSchema())
        }
        val responses = op.objectAt("responses").map { (status, value) ->
            val response = resolve(value)
            val headers = (response["headers"] as? JsonObject).orEmpty().map { (name, headerValue) ->
                val header = headerValue.jsonObject
                ResponseHeaderDefinition(name, header.booleanAt("required"), header.optionalString("description"),
                    header.getValue("schema").asSchema())
            }
            status.toInt() to ResponseDefinition(status.toInt(), response.stringAt("description"), headers, content(response))
        }.toMap()
        val security = (op.getValue("security") as JsonArray).map { alternative ->
            SecurityAlternative(alternative.jsonObject.mapValues { (_, scopes) -> (scopes as JsonArray).map { it.jsonPrimitive.content } })
        }
        if (op.stringAt("x-principal") == "guest" || op.stringAt("operationId") == "getCurrentGuestSession" || path == "/v1/guest-sessions/current") {
            check(op.stringAt("x-principal") == "guest" && op.stringAt("operationId") == "getCurrentGuestSession" &&
                method == "get" && path == "/v1/guest-sessions/current" && parameters.isEmpty() &&
                "requestBody" !in op && !op.booleanAt("x-idempotency-required") &&
                security == listOf(SecurityAlternative(mapOf("GuestBearer" to emptyList()))))
        }
        return OperationDefinition(op.stringAt("operationId"), method.uppercase(), path, op.stringAt("x-module"),
            (op.getValue("x-feature-ids") as JsonArray).map { it.jsonPrimitive.content }, op.stringAt("summary"),
            op.stringAt("description"), op.stringAt("x-principal"), op.booleanAt("x-idempotency-required"), security,
            parameters, op["requestBody"]?.jsonObject?.let { BodyDefinition(it.booleanAt("required"), content(it)) }, responses)
    }

    private fun content(value: JsonObject): Map<String, SchemaDefinition> =
        (value["content"] as? JsonObject).orEmpty().mapValues { (_, media) -> media.jsonObject.getValue("schema").asSchema() }

    private fun resolve(value: JsonElement): JsonObject {
        var result = value.jsonObject
        val seen = mutableSetOf<String>()
        while ("\$ref" in result) {
            val ref = result.stringAt("\$ref")
            check(seen.add(ref)) { "Cyclic bundled reference" }
            result = resolveReference(ref).jsonObject
        }
        return result
    }

    private fun resolveReference(reference: String): JsonElement {
        require(reference.startsWith("#/")) { "Only bundled local references are supported" }
        return reference.removePrefix("#/").split('/').fold(document as JsonElement) { node, key ->
            checkNotNull(node.jsonObject[key.replace("~1", "/").replace("~0", "~")]) { "Unknown bundled reference" }
        }
    }

    companion object {
        fun bundled(): ContractCatalog = ContractCatalog(Json.parseToJsonElement(GeneratedContract.documentJson()).jsonObject,
            Json.parseToJsonElement(GeneratedContract.bindingsJson()) as JsonArray)
    }
}

private fun JsonObject.objectAt(name: String): JsonObject = getValue(name).jsonObject
private fun JsonObject.stringAt(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.optionalString(name: String): String? = get(name)?.jsonPrimitive?.content
private fun JsonObject.booleanAt(name: String): Boolean = get(name)?.jsonPrimitive?.boolean ?: false
private fun JsonElement.asSchema(): SchemaDefinition = SchemaDefinition(toString())
