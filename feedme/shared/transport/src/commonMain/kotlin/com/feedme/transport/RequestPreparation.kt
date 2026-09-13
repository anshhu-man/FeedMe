package com.feedme.transport

import com.feedme.contracts.ContractCatalog
import com.feedme.contracts.ContractSurface
import com.feedme.contracts.DeviceSessionPolicy
import com.feedme.contracts.OperationDefinition
import com.feedme.contracts.ParameterDefinition
import com.feedme.contracts.ParameterLocation
import com.feedme.contracts.PrincipalClass
import com.feedme.contracts.SchemaDefinition
import com.feedme.contracts.WireDecodingException
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.ApiCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Paths are individual, unencoded segments. Only the HTTP adapter constructs the final URL. */
internal class PreparedCall(
    val operation: OperationDefinition,
    val pathSegments: List<String>,
    val query: Map<String, List<String>>,
    val body: ByteArray?,
    val headers: Map<String, String>,
) {
    override fun toString(): String = "PreparedCall(operationId=${operation.id}, parameters=<redacted>, body=<redacted>)"
}

/**
 * Bounded mobile request preflight, not authentication, authorization, or complete body validation.
 * Scalar rules are compiled from bundled parameter metadata. Unsupported metadata is a configuration
 * defect and remains visible; invalid caller data returns null without carrying that data in errors.
 */
internal class RequestPreparation(catalog: ContractCatalog = ContractCatalog.bundled()) {
    private val plans = catalog.operationsFor(ContractSurface.MOBILE).associate { it.id to RequestPlan(it) }

    /** Checks only caller-controlled intent; it cannot produce a network-ready request. */
    fun acceptsIntent(call: ApiCall, principal: PrincipalClass): Boolean =
        validate(call, principal, deviceSessionId = null, deferDeviceSession = true) != null

    fun prepare(call: ApiCall, principal: PrincipalClass, deviceSessionId: String? = null): PreparedCall? =
        validate(call, principal, deviceSessionId, deferDeviceSession = false)?.let {
            PreparedCall(it.operation, it.pathSegments, it.query, it.body, it.headers)
        }

    private fun validate(call: ApiCall, principal: PrincipalClass, deviceSessionId: String?,
        deferDeviceSession: Boolean): ValidatedIntent? {
        // Read-only collection interfaces can still wrap mutable caller-owned collections.
        val path = call.pathParameters.toMap()
        val query = call.queryParameters.mapValues { (_, values) -> values.toList() }
        val idempotencyKey = call.idempotencyKey?.use { it }
        val ifMatch = call.ifMatch
        val body = call.body?.copyForCodec()

        val plan = plans[call.operationId] ?: return null
        val requirements = plan.operation.requirementsFor(principal) ?: return null
        if (!plan.pathRules.keys.containsAll(path.keys) || !plan.queryRules.keys.containsAll(query.keys)) return null
        if (plan.pathRules.any { (name, rule) -> rule.required && name !in path }) return null
        if (plan.queryRules.any { (name, rule) -> rule.required && name !in query }) return null

        val headers = mutableMapOf<String, String>()
        if (idempotencyKey != null) headers[IDEMPOTENCY_KEY] = idempotencyKey
        if (ifMatch != null) headers[IF_MATCH] = ifMatch
        val deferredHeaders = if (deferDeviceSession && requirements.deviceSession == DeviceSessionPolicy.REQUIRED)
            setOf(DEVICE_SESSION) else emptySet()
        if (requirements.deviceSession == DeviceSessionPolicy.REQUIRED && !deferDeviceSession) {
            headers[DEVICE_SESSION] = deviceSessionId ?: return null
        }
        if (!plan.headerRules.keys.containsAll(headers.keys)) return null
        if (!headers.keys.containsAll(requirements.requiredParameterHeaders - deferredHeaders)) return null

        val budget = ParameterBudget()
        for ((name, value) in path) {
            if (!budget.accept(value) || value == "." || value == ".." || !plan.pathRules.getValue(name).accept(value)) return null
        }
        for ((name, values) in query) {
            // The bundled parameters are scalar; repeated identical values are duplicates too.
            if (values.size != 1) return null
            val value = values.single()
            if (!budget.accept(value) || !plan.queryRules.getValue(name).accept(value)) return null
        }
        for ((name, value) in headers) {
            if (!budget.accept(value) || !plan.headerRules.getValue(name).accept(value)) return null
            // Regex end anchors can match before a final Unicode line separator on some targets.
            // The HTTP precondition is always exactly one quoted decimal version.
            if (name == IF_MATCH && !quotedVersionToken.matches(value)) return null
        }

        val declaredBody = plan.operation.requestBody
        if (body == null) {
            if (declaredBody?.required == true) return null
        } else {
            if (declaredBody == null) return null
            try {
                // Preserve the caller's exact bytes. Syntax/resource checks deliberately do not
                // apply JSON Schema defaults or claim complete request schema validation.
                WireDocument.decode(body)
            } catch (_: WireDecodingException) {
                return null
            }
        }
        val segments = plan.segments.map { segment ->
            if (segment.startsWith('{')) path.getValue(segment.substring(1, segment.lastIndex)) else segment
        }
        return ValidatedIntent(plan.operation, segments, query, body, headers.toMap())
    }
}

/** Intermediate validation data never escapes as a prepared call when device binding is deferred. */
private class ValidatedIntent(
    val operation: OperationDefinition,
    val pathSegments: List<String>,
    val query: Map<String, List<String>>,
    val body: ByteArray?,
    val headers: Map<String, String>,
)

private const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val IF_MATCH = "If-Match"
private const val DEVICE_SESSION = "X-Device-Session"
private val supportedHeaders = setOf(IDEMPOTENCY_KEY, IF_MATCH, DEVICE_SESSION)
private val integerToken = Regex("0|-?[1-9][0-9]*")
private val uuidToken = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val quotedVersionToken = Regex("\"[0-9]+\"")
private val placeholder = Regex("\\{[A-Za-z][A-Za-z0-9]*\\}")

private class RequestPlan(val operation: OperationDefinition) {
    val pathRules = rules(ParameterLocation.PATH)
    val queryRules = rules(ParameterLocation.QUERY)
    val headerRules = rules(ParameterLocation.HEADER)
    val segments = operation.path.removePrefix("/").split('/')

    init {
        metadata(operation.path.startsWith('/') && segments.all { it.isNotEmpty() && it != "." && it != ".." })
        metadata(segments.all { segment ->
            if ('{' in segment || '}' in segment) placeholder.matches(segment)
            else segment.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        })
        val names = segments.filter { it.startsWith('{') }.map { it.substring(1, it.lastIndex) }
        metadata(names.size == names.toSet().size && names.toSet() == pathRules.keys)
        metadata(pathRules.values.all { it.required })
        metadata(headerRules.keys.all { it in supportedHeaders })
        metadata(operation.idempotencyRequired == (headerRules[IDEMPOTENCY_KEY]?.required == true))
        operation.requestBody?.let { metadata(it.content.keys == setOf("application/json")) }
    }

    private fun rules(location: ParameterLocation): Map<String, ParameterRule> {
        val parameters = operation.parameters.filter { it.location == location }
        metadata(parameters.map { it.name }.toSet().size == parameters.size)
        return parameters.associate { it.name to ParameterRule(it) }
    }
}

private class ParameterRule(parameter: ParameterDefinition) {
    val required = parameter.required
    private val scalar = ScalarRule(parameter.schema)
    fun accept(value: String): Boolean = scalar.accept(value)
}

/** This small validator covers the exact scalar vocabulary used by bundled mobile parameters. */
private class ScalarRule(schema: SchemaDefinition) {
    private val shape = Json.parseToJsonElement(schema.json).jsonObject
    private val type = shape.getValue("type").jsonPrimitive.content
    private val format = shape["format"]?.jsonPrimitive?.content
    private val minimum = shape["minimum"]?.jsonPrimitive?.content
    private val maximum = shape["maximum"]?.jsonPrimitive?.content
    private val minLength = shape["minLength"]?.jsonPrimitive?.int
    private val maxLength = shape["maxLength"]?.jsonPrimitive?.int
    private val choices = (shape["enum"] as? JsonArray)?.map {
        metadata(it.jsonPrimitive.isString)
        it.jsonPrimitive.content
    }?.toSet()
    private val pattern = shape["pattern"]?.jsonPrimitive?.content?.let(::Regex)

    init {
        metadata(shape.keys.all { it in setOf("type", "format", "minimum", "maximum", "minLength", "maxLength", "enum", "pattern", "default", "description") })
        metadata(type == "string" || type == "integer")
        metadata(format == null || (type == "string" && format == "uuid"))
        metadata(minimum == null || (type == "integer" && integerToken.matches(minimum)))
        metadata(maximum == null || (type == "integer" && integerToken.matches(maximum)))
        metadata(minimum == null || maximum == null || compareInteger(minimum, maximum) <= 0)
        metadata(minLength == null || (type == "string" && minLength >= 0))
        metadata(maxLength == null || (type == "string" && maxLength >= 0))
        metadata(minLength == null || maxLength == null || minLength <= maxLength)
        metadata(choices == null || (type == "string" && choices.isNotEmpty()))
        metadata(pattern == null || type == "string")
        metadata(shape["enum"] == null || shape["enum"] is JsonArray)
    }

    fun accept(value: String): Boolean {
        if (type == "integer") {
            if (!integerToken.matches(value)) return false
            if (minimum != null && compareInteger(value, minimum) < 0) return false
            if (maximum != null && compareInteger(value, maximum) > 0) return false
        } else {
            // ParameterBudget has already rejected unpaired surrogates.
            val length = value.length - value.count { it.isLowSurrogate() }
            if (minLength != null && length < minLength) return false
            if (maxLength != null && length > maxLength) return false
            if (choices != null && value !in choices) return false
            if (format == "uuid" && !uuidToken.matches(value)) return false
            // JSON Schema patterns search the string unless the schema itself uses anchors.
            if (pattern != null && !pattern.containsMatchIn(value)) return false
        }
        return true
    }
}

/** Compare canonical integer lexemes without overflow, floating-point rounding, or truncation. */
private fun compareInteger(left: String, right: String): Int {
    val leftNegative = left.startsWith('-')
    val rightNegative = right.startsWith('-')
    if (leftNegative != rightNegative) return if (leftNegative) -1 else 1
    val magnitude = if (left.length != right.length) left.length.compareTo(right.length) else left.compareTo(right)
    return if (leftNegative) -magnitude else magnitude
}

private class ParameterBudget {
    private var remaining = 16 * 1024

    fun accept(value: String): Boolean {
        // Resource ceilings count UTF-16 units; schema lengths separately count Unicode scalars.
        if (value.length > 4096 || value.length > remaining) return false
        remaining -= value.length
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isISOControl() || char.isLowSurrogate()) return false
            if (char.isHighSurrogate()) {
                if (index >= value.length || !value[index++].isLowSurrogate()) return false
            }
        }
        return true
    }
}

private fun metadata(condition: Boolean) {
    check(condition) { "Unsupported bundled request metadata" }
}
