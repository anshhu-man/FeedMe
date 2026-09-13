package com.feedme.kitchen

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PrivateBytes
import kotlinx.serialization.json.*

/** Strict local metadata only. Canonical body documents are validated separately and never trimmed. */
internal object PrivateJson {
    fun decode(bytes: PrivateBytes, keys: Set<String>): JsonObject = try {
        val document = WireDocument.decode(bytes.copyForCodec(), WireLimits(maxBytes = 64 * 1024, maxDepth = 16))
        val root = Json.parseToJsonElement(document.encodeUtf8().decodeToString()) as? JsonObject ?: invalid()
        if (root.keys != keys) invalid()
        root
    } catch (failure: KitchenFailure) { throw failure }
    catch (_: Exception) { invalid() }

    fun encode(value: JsonObject): PrivateBytes = PrivateBytes(value.toString().encodeToByteArray(throwOnInvalidSequence = true))
        .also { decode(it, value.keys) }
    fun string(value: JsonElement): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    fun long(value: JsonElement): Long {
        val token = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content ?: invalid()
        if (!token.matches(Regex("0|[1-9][0-9]*"))) invalid()
        return token.toLongOrNull() ?: invalid()
    }
    fun boolean(value: JsonElement): Boolean = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: invalid()
    fun nullableString(value: JsonElement): String? = if (value == JsonNull) null else string(value)
    fun strings(value: JsonElement): List<String> = (value as? JsonArray)?.map(::string) ?: invalid()
    fun hash(value: JsonElement): String = string(value).also { if (!it.matches(Regex("[0-9a-f]{64}"))) invalid() }
    fun uuid(value: JsonElement): String = string(value).also { if (!it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) invalid() }
    fun invalid(): Nothing = kitchenFail(FailureReason.INVALID_DATA)
}

internal fun WireDocument.json(): JsonElement = Json.parseToJsonElement(encodeUtf8().decodeToString())

/** Numeric values outside this repository's Long persistence range fail; raw bytes stay untouched. */
internal fun wireLong(document: WireDocument, field: String, minimum: Long = 0): Long {
    val value = document.json().jsonObject.getValue(field).jsonPrimitive
    if (value.isString) PrivateJson.invalid()
    val parts = value.content.removePrefix("-").lowercase().split('e')
    val mantissa = parts[0]
    val digits = mantissa.replace(".", "").trimStart('0')
    if (digits.isEmpty()) return if (minimum == 0L) 0 else PrivateJson.invalid()
    if (value.content.startsWith('-')) PrivateJson.invalid()
    val fractional = if ('.' in mantissa) mantissa.length - mantissa.indexOf('.') - 1 else 0
    val exponent = (parts.getOrNull(1) ?: "0").toIntOrNull() ?: PrivateJson.invalid()
    val length = digits.length.toLong() + exponent - fractional
    if (length !in 1..19) PrivateJson.invalid()
    val integer = if (length >= digits.length) digits + "0".repeat(length.toInt() - digits.length)
        else digits.take(length.toInt()).also { if (digits.drop(length.toInt()).any { it != '0' }) PrivateJson.invalid() }
    return (integer.toLongOrNull() ?: PrivateJson.invalid()).also { if (it < minimum) PrivateJson.invalid() }
}
