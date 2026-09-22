package com.feedme.server.contract

import com.feedme.contracts.CanonicalFormats
import com.networknt.schema.ExecutionContext
import com.networknt.schema.OutputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.dialect.Dialect
import com.networknt.schema.dialect.Dialects
import com.networknt.schema.format.Format
import com.networknt.schema.path.NodePath
import com.networknt.schema.path.PathType
import com.networknt.schema.regex.JoniRegularExpressionFactory
import com.networknt.schema.resource.SchemaLoader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.exc.StreamConstraintsException
import tools.jackson.core.json.JsonFactory
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

enum class BodyRejectionReason {
    INPUT_SYNTAX, RESOURCE_LIMIT, SCHEMA_VIOLATION, MISSING_BODY, UNEXPECTED_BODY,
    UNKNOWN_SCHEMA, UNKNOWN_OPERATION, UNKNOWN_STATUS, UNSUPPORTED_MEDIA,
}

/** Safe diagnostics contain no input names, values, library nodes or exception causes. */
sealed interface BodyValidationResult {
    data object Valid : BodyValidationResult
    data class Rejected(val reason: BodyRejectionReason) : BodyValidationResult
}

/**
 * Validates bytes against the pinned canonical contract. A valid body never grants authorization.
 * Instances are immutable after eager initialization and may be shared by concurrent callers.
 * Resource limits are an explicit wire profile, not extra numerical schema constraints.
 */
class ContractBodyValidator private constructor(
    private val mapper: JsonMapper,
    private val named: Map<String, Schema>,
    private val operations: Map<String, OperationBodies>,
) {
    fun validateSchema(name: String, bytes: ByteArray): BodyValidationResult =
        named[name]?.let { validate(it, bytes) } ?: rejected(BodyRejectionReason.UNKNOWN_SCHEMA)

    fun validateRequest(operationId: String, body: ByteArray?, mediaType: String?): BodyValidationResult {
        val operation = operations[operationId] ?: return rejected(BodyRejectionReason.UNKNOWN_OPERATION)
        return validateBody(operation.request, body, mediaType)
    }

    fun validateResponse(operationId: String, status: Int, body: ByteArray?, mediaType: String?): BodyValidationResult {
        val operation = operations[operationId] ?: return rejected(BodyRejectionReason.UNKNOWN_OPERATION)
        val shape = operation.responses[status] ?: return rejected(BodyRejectionReason.UNKNOWN_STATUS)
        return validateBody(shape, body, mediaType)
    }

    private fun validateBody(shape: BodyShape, bytes: ByteArray?, mediaType: String?): BodyValidationResult {
        if (shape.media.isEmpty()) return if (bytes == null) BodyValidationResult.Valid
            else rejected(BodyRejectionReason.UNEXPECTED_BODY)
        if (bytes == null) return if (shape.required) rejected(BodyRejectionReason.MISSING_BODY)
            else BodyValidationResult.Valid
        val schema = normalizeMedia(mediaType)?.let(shape.media::get)
            ?: return rejected(BodyRejectionReason.UNSUPPORTED_MEDIA)
        return validate(schema, bytes)
    }

    private fun validate(schema: Schema, bytes: ByteArray): BodyValidationResult {
        val input = try {
            parse(mapper, bytes)
        } catch (failure: InputRejected) {
            return rejected(failure.reason)
        }
        // Boolean output enables fail-fast and disables annotation reporting. No error rendering
        // occurs here; library diagnostics can contain attacker-controlled field names and values.
        val valid = try {
            schema.validate(input, OutputFormat.BOOLEAN) { context ->
                context.executionConfig { it.formatAssertionsEnabled(true).failFast(true) }
            }
        } catch (_: Exception) {
            throw IllegalStateException("Contract validation failed")
        }
        return if (valid) BodyValidationResult.Valid else rejected(BodyRejectionReason.SCHEMA_VIOLATION)
    }

    private class BodyShape(val required: Boolean, val media: Map<String, Schema>)
    private class OperationBodies(val request: BodyShape, val responses: Map<Int, BodyShape>)
    private class InputRejected(val reason: BodyRejectionReason) : RuntimeException(null, null, false, false)

    companion object {
        const val MAX_BODY_BYTES = 1_048_576
        const val MAX_NESTING_DEPTH = 64
        const val MAX_NUMBER_LENGTH = 1000
        const val MAX_DECIMAL_SCALE = 10_000
        private val noBody = BodyShape(false, emptyMap())

        /** Reads and verifies the same bundled canonical bytes as the routing catalog. */
        fun bundled(catalog: ContractCatalog = ContractCatalog.bundled()): ContractBodyValidator = try {
            val bytes = checkNotNull(ContractBodyValidator::class.java.getResourceAsStream("/feedme-openapi.json"))
                .use { it.readNBytes(MAX_BODY_BYTES + 1) }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hash == ContractCatalog.SOURCE_SHA256)
            val mapper = exactMapper()
            val document = parse(mapper, bytes).asObject()
            document.put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            // Reject unknown/local-missing references at startup, including OpenAPI response refs.
            fun checkReferences(node: JsonNode) {
                node.get("\$ref")?.let { ref ->
                    check(ref.isString && ref.asString().startsWith("#/"))
                    check(!document.at(ref.asString().removePrefix("#")).isMissingNode)
                }
                for (child in node) checkReferences(child)
            }
            checkReferences(document)
            // These explicit format overrides share the standards-reviewed wire profile with KMP.
            // Networknt retains independent schema/number assertions; its built-in date-time/URI
            // behavior is not the format oracle (for example, trailing time junk and malformed ports).
            fun canonicalFormat(name: String): Format = object : Format {
                override fun getName(): String = name
                override fun matches(executionContext: ExecutionContext, value: String): Boolean =
                    CanonicalFormats.accepts(name, value)
            }
            val dialect = Dialect.builder(Dialects.getDraft202012())
                .format(canonicalFormat("uri"))
                .format(canonicalFormat("uuid"))
                .format(canonicalFormat("date-time"))
                .build()
            val registry = SchemaRegistry.withDialect(dialect) { builder ->
                builder.nodeReader { it.jsonMapper(mapper) }
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().typeLoose(false)
                        .formatAssertionsEnabled(true).failFast(true)
                        .regularExpressionFactory(JoniRegularExpressionFactory.getInstance()).build())
                    .schemaLoader(SchemaLoader.builder().fetchRemoteResources(false).allow { false }.build())
            }
            // One document tree and a deduplicated schema cache; 2424 shared Problem response
            // references resolve to the same compiled schema, not thousands of copied wrappers.
            val root = registry.getSchema(SchemaLocation.of("urn:feedme:contract"), document)
            val compiled = mutableMapOf<String, Schema>()
            fun compile(pointer: String): Schema {
                val node = document.at(pointer)
                check(!node.isMissingNode)
                val reference = node.get("\$ref")
                val key = if (node.size() == 1 && reference != null) reference.asString().removePrefix("#") else pointer
                return compiled.getOrPut(key) {
                    var location = NodePath(PathType.JSON_POINTER)
                    for (part in key.removePrefix("/").split('/')) {
                        location = location.append(part.replace("~1", "/").replace("~0", "~"))
                    }
                    root.getRefSchema(location).also { it.initializeValidators() }
                }
            }
            fun pointerPart(value: String) = value.replace("~", "~0").replace("/", "~1")
            fun body(pointer: String, required: Boolean): BodyShape {
                var resolved = pointer
                val seen = mutableSetOf<String>()
                while (document.at(resolved).has("\$ref")) {
                    check(seen.add(resolved))
                    resolved = document.at(resolved).get("\$ref").asString().removePrefix("#")
                }
                val content = document.at(resolved).get("content") ?: return noBody
                val media = content.propertyNames().associateWith { type ->
                    compile("$resolved/content/${pointerPart(type)}/schema")
                }
                return BodyShape(required, media)
            }
            val schemas = document.at("/components/schemas").propertyNames().associateWith { name ->
                compile("/components/schemas/${pointerPart(name)}")
            }
            val operationBodies = catalog.operations.associate { operation ->
                val pointer = "/paths/${pointerPart(operation.path)}/${operation.method.lowercase(Locale.ROOT)}"
                val node = document.at(pointer)
                check(node.get("operationId").asString() == operation.id)
                val request = node.get("requestBody")?.let {
                    body("$pointer/requestBody", it.get("required")?.asBoolean() == true)
                } ?: noBody
                val responses = node.get("responses").propertyNames().associate { status ->
                    val response = body("$pointer/responses/$status", true)
                    check(status != "204" || response.media.isEmpty())
                    status.toInt() to response
                }
                operation.id to OperationBodies(request, responses)
            }
            check(operationBodies.size == 207 && schemas.size == 208)
            ContractBodyValidator(mapper, schemas.toMap(), operationBodies.toMap())
        } catch (_: Exception) {
            // Intentionally omit a cause: upstream exception text can contain schema content.
            throw IllegalStateException("Pinned contract validator initialization failed")
        }

        private fun exactMapper(): JsonMapper = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_NESTING_DEPTH)
                .maxDocumentLength(MAX_BODY_BYTES.toLong()).maxNumberLength(MAX_NUMBER_LENGTH)
                .maxStringLength(MAX_BODY_BYTES).maxNameLength(MAX_BODY_BYTES).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build())
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

        private fun parse(mapper: JsonMapper, bytes: ByteArray): JsonNode {
            if (bytes.size > MAX_BODY_BYTES) throw InputRejected(BodyRejectionReason.RESOURCE_LIMIT)
            try {
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                val node = mapper.readTree(text)
                if (node.isMissingNode) throw InputRejected(BodyRejectionReason.INPUT_SYNTAX)
                checkValues(node)
                return node
            } catch (failure: InputRejected) {
                throw failure
            } catch (_: CharacterCodingException) {
                throw InputRejected(BodyRejectionReason.INPUT_SYNTAX)
            } catch (_: StreamConstraintsException) {
                throw InputRejected(BodyRejectionReason.RESOURCE_LIMIT)
            } catch (failure: JacksonException) {
                // A syntactically valid exponent outside BigDecimal's representable scale is a
                // resource-domain rejection. Neither this cause nor its message leaves the boundary.
                val numericLimit = generateSequence(failure as Throwable?) { it.cause }
                    .take(8).any { it is NumberFormatException || it is ArithmeticException }
                throw InputRejected(if (numericLimit) BodyRejectionReason.RESOURCE_LIMIT else BodyRejectionReason.INPUT_SYNTAX)
            } catch (_: ArithmeticException) {
                throw InputRejected(BodyRejectionReason.RESOURCE_LIMIT)
            } catch (_: NumberFormatException) {
                throw InputRejected(BodyRejectionReason.RESOURCE_LIMIT)
            }
        }

        private fun checkValues(node: JsonNode) {
            if (node.isBigDecimal && kotlin.math.abs(node.decimalValue().scale().toLong()) > MAX_DECIMAL_SCALE) {
                throw InputRejected(BodyRejectionReason.RESOURCE_LIMIT)
            }
            if (node.isString) checkUnicode(node.asString())
            if (node.isObject) for (name in node.propertyNames()) checkUnicode(name)
            for (child in node) checkValues(child)
        }

        private fun checkUnicode(text: String) {
            var index = 0
            while (index < text.length) {
                val current = text[index++]
                if (current.isHighSurrogate()) {
                    if (index == text.length || !text[index++].isLowSurrogate()) {
                        throw InputRejected(BodyRejectionReason.INPUT_SYNTAX)
                    }
                } else if (current.isLowSurrogate()) throw InputRejected(BodyRejectionReason.INPUT_SYNTAX)
            }
        }

        private fun normalizeMedia(value: String?): String? {
            if (value == null || value.length > 4096 || value.any { it == '\r' || it == '\n' }) return null
            var cursor = 0
            fun whitespace() {
                while (cursor < value.length && (value[cursor] == ' ' || value[cursor] == '\t')) cursor++
            }
            fun tokenCharacter(char: Char) = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
                char in "!#$%&'*+-.^_`|~"
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
                            // HTTP quoted-pair = backslash followed by HTAB / SP / VCHAR / obs-text.
                            if (escaped != '\t' && escaped.code !in 32..255) return null
                            if (escaped.code == 127) return null
                            decoded.append(escaped)
                        }
                        char == '\t' || char.code == 32 || char.code == 33 ||
                            char.code in 35..91 || char.code in 93..126 || char.code in 128..255 -> decoded.append(char)
                        else -> return null
                    }
                }
                return null // A quoted string must close; no suffix is silently discarded.
            }
            whitespace()
            val type = readToken() ?: return null
            if (cursor == value.length || value[cursor++] != '/') return null
            val subtype = readToken() ?: return null
            val parameters = mutableSetOf<String>()
            while (true) {
                whitespace()
                if (cursor == value.length) return "$type/$subtype".lowercase(Locale.ROOT)
                if (value[cursor++] != ';') return null
                whitespace()
                val name = readToken()?.lowercase(Locale.ROOT) ?: return null
                if (!parameters.add(name)) return null
                whitespace()
                if (cursor == value.length || value[cursor++] != '=') return null
                whitespace()
                val parameter = parameterValue() ?: return null
                if (name == "charset" && !parameter.equals("utf-8", true)) return null
            }
        }

        private fun rejected(reason: BodyRejectionReason) = BodyValidationResult.Rejected(reason)
    }
}
