package com.feedme.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

enum class ContractRejectionReason {
    INPUT_SYNTAX, RESOURCE_LIMIT, SCHEMA_VIOLATION, MISSING_BODY, UNEXPECTED_BODY,
    UNKNOWN_SCHEMA, UNKNOWN_OPERATION, UNKNOWN_STATUS, UNSUPPORTED_MEDIA,
}

/** No instance fields, raw values, validator nodes or error causes are exposed. */
sealed interface ContractValidationResult {
    data object Valid : ContractValidationResult
    data class Rejected(val reason: ContractRejectionReason) : ContractValidationResult
}

/**
 * Complete assertions for the current pinned contract vocabulary, NOT a general JSON Schema
 * implementation. All unknown keywords/formats/patterns or recursive schema changes fail at startup.
 * Validation grants no identity, ownership, content approval, entitlement or business authorization.
 * Instances contain immutable compiled rules and can validate independent documents concurrently.
 */
class CanonicalBodyValidator private constructor(
    private val named: Map<String, SchemaRule>,
    private val operations: Map<String, OperationBodies>,
) {
    fun validateSchema(name: String, bytes: ByteArray): ContractValidationResult =
        named[name]?.let { validate(it, bytes) } ?: rejected(ContractRejectionReason.UNKNOWN_SCHEMA)

    fun validateRequest(operationId: String, body: ByteArray?, mediaType: String?): ContractValidationResult =
        operations[operationId]?.let { validateBody(it.request, body, mediaType) }
            ?: rejected(ContractRejectionReason.UNKNOWN_OPERATION)

    fun validateResponse(operationId: String, status: Int, body: ByteArray?, mediaType: String?): ContractValidationResult {
        val operation = operations[operationId] ?: return rejected(ContractRejectionReason.UNKNOWN_OPERATION)
        val shape = operation.responses[status] ?: return rejected(ContractRejectionReason.UNKNOWN_STATUS)
        return validateBody(shape, body, mediaType)
    }

    private fun validateBody(shape: BodyShape, bytes: ByteArray?, mediaType: String?): ContractValidationResult {
        if (shape.media.isEmpty()) return if (bytes == null) ContractValidationResult.Valid
            else rejected(ContractRejectionReason.UNEXPECTED_BODY)
        if (bytes == null) return if (shape.required) rejected(ContractRejectionReason.MISSING_BODY) else ContractValidationResult.Valid
        val rule = normalizeMedia(mediaType)?.let(shape.media::get) ?: return rejected(ContractRejectionReason.UNSUPPORTED_MEDIA)
        return validate(rule, bytes)
    }

    private fun validate(rule: SchemaRule, bytes: ByteArray): ContractValidationResult = try {
        val document = WireDocument.decode(bytes).detachedJson()
        val budget = ValidationBudget()
        validateNumberProfile(document, budget)
        if (rule.accepts(document, budget)) ContractValidationResult.Valid else rejected(ContractRejectionReason.SCHEMA_VIOLATION)
    } catch (failure: WireDecodingException) {
        rejected(if (failure.reason in setOf(WireFailure.BYTE_LIMIT, WireFailure.DEPTH_LIMIT, WireFailure.NUMBER_LIMIT))
            ContractRejectionReason.RESOURCE_LIMIT else ContractRejectionReason.INPUT_SYNTAX)
    } catch (_: ContractResourceLimit) {
        rejected(ContractRejectionReason.RESOURCE_LIMIT)
    } catch (_: Exception) {
        throw IllegalStateException("Canonical contract validation failed")
    }

    private class BodyShape(val required: Boolean, val media: Map<String, SchemaRule>)
    private class OperationBodies(val request: BodyShape, val responses: Map<Int, BodyShape>)

    companion object {
        // One private compiled graph for the pinned, app-bundled contract. Initialization is
        // synchronized; the mutable compiler exists only during initialization. Validation
        // continues to decode each input and allocate its own budget/result, never caching data.
        private val bundledValidator: CanonicalBodyValidator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            compile(ContractCatalog.bundled())
        }

        fun bundled(): CanonicalBodyValidator = bundledValidator

        // Explicit catalogs remain independent: never ignore caller-supplied metadata or
        // cache it by source hash. Public catalog collections are not a shared authority.
        fun bundled(catalog: ContractCatalog): CanonicalBodyValidator = compile(catalog)

        private fun compile(catalog: ContractCatalog): CanonicalBodyValidator = try {
            val compiler = CanonicalSchemaProgram { reference ->
                Json.parseToJsonElement(catalog.resolveSchema(reference).json).jsonObject
            }
            fun schema(definition: SchemaDefinition) = compiler.compile(Json.parseToJsonElement(definition.json))
            val named = catalog.schemaNames.associateWith { name -> schema(checkNotNull(catalog.schema(name))) }
            val noBody = BodyShape(false, emptyMap())
            val operations = catalog.operations.associate { operation ->
                val request = operation.requestBody?.let { BodyShape(it.required, it.content.mapValues { schema(it.value) }) } ?: noBody
                val responses = operation.responses.mapValues { (_, response) ->
                    BodyShape(response.content.isNotEmpty(), response.content.mapValues { schema(it.value) })
                }
                operation.id to OperationBodies(request, responses)
            }
            check(named.size == 193 && operations.size == 205)
            CanonicalBodyValidator(named, operations)
        } catch (_: Exception) {
            throw IllegalStateException("Pinned common validator initialization failed")
        }
    }
}

private fun rejected(reason: ContractRejectionReason) = ContractValidationResult.Rejected(reason)

/** Complete bounded HTTP media parser; quoted delimiters are not mistaken for parameters. */
private fun normalizeMedia(value: String?): String? {
    if (value == null || value.length > 4096 || value.any { it == '\r' || it == '\n' }) return null
    var cursor = 0
    fun whitespace() { while (cursor < value.length && (value[cursor] == ' ' || value[cursor] == '\t')) cursor++ }
    fun tokenCharacter(char: Char) = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "!#$%&'*+-.^_`|~"
    fun readToken(): String? {
        val start = cursor
        while (cursor < value.length && tokenCharacter(value[cursor])) cursor++
        return value.substring(start, cursor).takeIf { it.isNotEmpty() }
    }
    fun parameterValue(): String? {
        if (cursor == value.length || value[cursor] != '"') return readToken()
        cursor++
        val decoded = StringBuilder()
        while (cursor < value.length) {
            val char = value[cursor++]
            when {
                char == '"' -> return decoded.toString()
                char == '\\' -> {
                    if (cursor == value.length) return null
                    val escaped = value[cursor++]
                    if (escaped != '\t' && escaped.code !in 32..255) return null
                    if (escaped.code == 127) return null
                    decoded.append(escaped)
                }
                char == '\t' || char.code == 32 || char.code == 33 || char.code in 35..91 ||
                    char.code in 93..126 || char.code in 128..255 -> decoded.append(char)
                else -> return null
            }
        }
        return null
    }
    whitespace()
    val type = readToken() ?: return null
    if (cursor == value.length || value[cursor++] != '/') return null
    val subtype = readToken() ?: return null
    val parameters = mutableSetOf<String>()
    while (true) {
        whitespace()
        if (cursor == value.length) return "$type/$subtype".lowercase()
        if (value[cursor++] != ';') return null
        whitespace()
        val name = readToken()?.lowercase() ?: return null
        if (!parameters.add(name)) return null
        whitespace()
        if (cursor == value.length || value[cursor++] != '=') return null
        whitespace()
        val parameter = parameterValue() ?: return null
        if (name == "charset" && !parameter.equals("utf-8", true)) return null
    }
}
