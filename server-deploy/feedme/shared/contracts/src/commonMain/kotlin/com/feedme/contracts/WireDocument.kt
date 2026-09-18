package com.feedme.contracts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Transport resource policy, not additional JSON Schema constraints. Never truncates input. */
data class WireLimits(val maxBytes: Int = 1_048_576, val maxDepth: Int = 64, val maxNumberLength: Int = 1000) {
    init {
        require(maxBytes in 1..4_194_304 && maxDepth in 1..128 && maxNumberLength in 1..4096) {
            "Wire limits outside supported resource policy"
        }
    }
}

enum class WireFailure { BYTE_LIMIT, DEPTH_LIMIT, NUMBER_LIMIT, INVALID_UTF8, INVALID_UNICODE, MALFORMED_JSON, DUPLICATE_KEY }
class WireDecodingException(val reason: WireFailure) : IllegalArgumentException("Wire decode rejected: $reason")
enum class WireKind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

/** Missing and explicit null must never collapse into the same optional value. */
sealed class WireField<out T> {
    data object Missing : WireField<Nothing>()
    data object Null : WireField<Nothing>()
    class Value<T>(val value: T) : WireField<T>() {
        override fun toString(): String = "WireField.Value(redacted)"
    }
}

/** HTTP absence is independent of the JSON value null. Empty bytes are not guessed to be absent. */
sealed class WireBody {
    data object Absent : WireBody()
    class Present(val document: WireDocument) : WireBody() {
        override fun toString(): String = "WireBody.Present(redacted)"
    }

    companion object {
        fun decode(bytes: ByteArray?, limits: WireLimits = WireLimits()): WireBody =
            if (bytes == null) Absent else Present(WireDocument.decode(bytes, limits))
    }
}

/**
 * Bounded, syntactically strict, immutable wire JSON. Not a schema-valid or authorized object.
 * Original UTF-8 text and numeric lexemes survive round trips; no Double/Long conversion occurs.
 * No generated DTO defaults, unknown-field dropping, implicit null or array deduplication occurs.
 */
class WireDocument private constructor(private val raw: String, private val element: JsonElement) {
    val kind: WireKind get() = when (element) {
        is JsonObject -> WireKind.OBJECT
        is JsonArray -> WireKind.ARRAY
        JsonNull -> WireKind.NULL
        is JsonPrimitive -> when {
            element.isString -> WireKind.STRING
            element.booleanOrNull != null -> WireKind.BOOLEAN
            else -> WireKind.NUMBER
        }
    }

    fun encodeUtf8(): ByteArray = raw.encodeToByteArray()
    fun stringOrNull(): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun numberTokenOrNull(): String? = (element as? JsonPrimitive)?.takeIf { kind == WireKind.NUMBER }?.content
    fun booleanOrNull(): Boolean? = (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    fun elementsOrNull(): List<WireDocument>? = (element as? JsonArray)?.map { child(it) }

    fun field(name: String): WireField<WireDocument> {
        require(element is JsonObject) { "Wire object required for field access" }
        return when (val value = element[name]) {
            null -> WireField.Missing
            JsonNull -> WireField.Null
            else -> WireField.Value(child(value))
        }
    }

    /** Internal projections get a fresh tree, so casting a map cannot mutate this document. */
    internal fun detachedJson(): JsonElement = Json.parseToJsonElement(raw)
    override fun toString(): String = "WireDocument(redacted)"

    companion object {
        fun decode(bytes: ByteArray, limits: WireLimits = WireLimits()): WireDocument {
            if (bytes.size > limits.maxBytes) reject(WireFailure.BYTE_LIMIT)
            val raw = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                reject(WireFailure.INVALID_UTF8)
            }
            return parse(raw, limits)
        }

        fun parse(raw: String, limits: WireLimits = WireLimits()): WireDocument {
            // Char count is a cheap first bound before allocating a UTF-8 measurement buffer.
            if (raw.length > limits.maxBytes) reject(WireFailure.BYTE_LIMIT)
            val size = try { raw.encodeToByteArray(throwOnInvalidSequence = true).size }
                catch (_: CharacterCodingException) { reject(WireFailure.INVALID_UNICODE) }
            if (size > limits.maxBytes) reject(WireFailure.BYTE_LIMIT)
            guardTokens(raw, limits)
            val element = try {
                Json.parseToJsonElement(raw)
            } catch (_: SerializationException) {
                reject(WireFailure.MALFORMED_JSON)
            } catch (_: IllegalArgumentException) {
                reject(WireFailure.MALFORMED_JSON)
            }
            return WireDocument(raw, element)
        }

        internal fun fromElement(value: JsonElement): WireDocument = parse(value.toString())
        private fun child(value: JsonElement): WireDocument = WireDocument(value.toString(), value)
    }
}

private fun reject(reason: WireFailure): Nothing = throw WireDecodingException(reason)
private val jsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
private class Scope(val objectScope: Boolean, val keys: MutableSet<String> = mutableSetOf(), var expectingKey: Boolean = objectScope)
private fun Char.jsonSpace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'
private fun Char.delimiter(): Boolean = jsonSpace() || this in "{}[],:\""

/**
 * Lexical safeguards run before the library's complete structural parser. They bound nesting,
 * reject duplicate decoded property names and reject non-JSON primitive tokens. This guard is
 * intentionally not an alternate structural parser or a JSON Schema validator.
 */
private fun guardTokens(raw: String, limits: WireLimits) {
    val stack = mutableListOf<Scope>()
    var index = 0
    while (index < raw.length) {
        when (val c = raw[index]) {
            '{', '[' -> {
                stack.add(Scope(c == '{'))
                if (stack.size > limits.maxDepth) reject(WireFailure.DEPTH_LIMIT)
                index++
            }
            '}', ']' -> {
                if (stack.isEmpty() || stack.last().objectScope != (c == '}')) reject(WireFailure.MALFORMED_JSON)
                stack.removeAt(stack.lastIndex)
                index++
            }
            ':' -> { stack.lastOrNull()?.let { if (it.objectScope) it.expectingKey = false }; index++ }
            ',' -> { stack.lastOrNull()?.let { if (it.objectScope) it.expectingKey = true }; index++ }
            '"' -> {
                val start = index++
                var ended = false
                while (index < raw.length) {
                    val next = raw[index++]
                    if (next == '"') { ended = true; break }
                    if (next.code < 0x20) reject(WireFailure.MALFORMED_JSON)
                    if (next == '\\') {
                        if (index >= raw.length) reject(WireFailure.MALFORMED_JSON)
                        when (raw[index++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> repeat(4) {
                                if (index >= raw.length || raw[index++].digitToIntOrNull(16) == null) reject(WireFailure.MALFORMED_JSON)
                            }
                            else -> reject(WireFailure.MALFORMED_JSON)
                        }
                    }
                }
                if (!ended) reject(WireFailure.MALFORMED_JSON)
                val text = try { Json.parseToJsonElement(raw.substring(start, index)).jsonPrimitive.content }
                    catch (_: SerializationException) { reject(WireFailure.MALFORMED_JSON) }
                // Interoperable Unicode scalar strings only; do not replace lone surrogates on encode.
                try { text.encodeToByteArray(throwOnInvalidSequence = true) }
                    catch (_: CharacterCodingException) { reject(WireFailure.INVALID_UNICODE) }
                val scope = stack.lastOrNull()
                if (scope?.expectingKey == true) {
                    if (!scope.keys.add(text)) reject(WireFailure.DUPLICATE_KEY)
                }
            }
            else -> {
                if (c.jsonSpace()) { index++; continue }
                val start = index
                while (index < raw.length && !raw[index].delimiter()) index++
                val token = raw.substring(start, index)
                if (token !in setOf("true", "false", "null")) {
                    if (token.length > limits.maxNumberLength) reject(WireFailure.NUMBER_LIMIT)
                    if (!jsonNumber.matches(token)) reject(WireFailure.MALFORMED_JSON)
                }
            }
        }
    }
    if (stack.isNotEmpty()) reject(WireFailure.MALFORMED_JSON)
}
