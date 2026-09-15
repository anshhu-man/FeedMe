package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.serialization.json.*

internal class CachedIngredient(val document: WireDocument, val checked: Long) {
    val id = uuid(document.json().jsonObject.getValue("id").jsonPrimitive.content)
    fun option(historical: Boolean): IngredientOption {
        val item = document.json().jsonObject
        return IngredientOption(id, item.getValue("name").jsonPrimitive.content,
            item.getValue("aliases").jsonArray.map { it.jsonPrimitive.content },
            item.getValue("category").jsonPrimitive.content, document, checked, historical)
    }
    override fun toString() = "CachedIngredient(<redacted>)"
}
internal class IngredientCache(val clock: Long, val items: List<CachedIngredient>)
internal class IngredientCacheEntry(val record: PrivateRecord?, val data: IngredientCache)

/** Catalog LABEL cache only. No search text, pantry stock, selection, credential or plan intent. */
internal class IngredientPickerCache(private val origin: String, private val policy: IngredientPickerPolicy) {
    private val validator = CanonicalBodyValidator.bundled()
    fun decode(bytes: PrivateBytes): IngredientCache {
        val root = WireDocument.decode(bytes.copyForCodec(), WireLimits(MAX_BYTES, 16)).json().jsonObject
        keys(root, "schema", "origin", "clock", "items")
        if (long(root.getValue("schema")) != 1L || string(root.getValue("origin")) != origin) mealFail(FailureReason.INVALID_DATA)
        val time = long(root.getValue("clock"))
        val items = root.getValue("items").jsonArray.map { value -> value.jsonObject.let { item ->
            keys(item, "document", "checked")
            val doc = WireDocument.parse(string(item.getValue("document")), WireLimits(32_768, 12))
            validate(doc)
            CachedIngredient(doc, long(item.getValue("checked"))).also { if (it.checked > time) mealFail(FailureReason.INVALID_DATA) }
        } }
        if (items.size > policy.maxCachedIngredients || items.map { it.id }.distinct().size != items.size) mealFail(FailureReason.INVALID_DATA)
        return IngredientCache(time, items)
    }
    fun encode(value: IngredientCache): PrivateBytes = PrivateBytes(raw(value)).also { decode(it) }
    fun prune(value: IngredientCache, now: Long) = IngredientCache(now, value.items.filter {
        if (it.checked > now) mealFail(FailureReason.CONFLICT)
        now - it.checked <= policy.cacheRetentionMillis
    })
    fun merge(prior: IngredientCache, incoming: List<CachedIngredient>, now: Long): IngredientCache {
        val items = prune(prior, now).items.associateBy { it.id }.toMutableMap()
        for (next in incoming) {
            val old = items[next.id]
            if (old != null) {
                val version = pickerCompareVersion(next.document, old.document)
                if (version < 0 || (version == 0 && JsonObject(next.document.json().jsonObject - "version") !=
                        JsonObject(old.document.json().jsonObject - "version"))) mealFail(FailureReason.CONFLICT)
            }
            items[next.id] = next
        }
        val kept = mutableListOf<CachedIngredient>()
        var size = raw(IngredientCache(now, emptyList())).size
        for (item in items.values.sortedWith(compareByDescending<CachedIngredient> { it.checked }.thenBy { it.id })) {
            val encodedSize = itemJson(item).toString().encodeToByteArray().size + if (kept.isEmpty()) 0 else 1
            if (kept.size == policy.maxCachedIngredients || size + encodedSize > MAX_BYTES) break
            kept += item; size += encodedSize
        }
        return IngredientCache(now, kept)
    }
    fun validate(document: WireDocument) {
        if (document.encodeUtf8().size > 32_768 || validator.validateSchema("Ingredient", document.encodeUtf8()) != ContractValidationResult.Valid)
            mealFail(FailureReason.INVALID_DATA)
        pickerVersion(document)
    }
    private fun raw(value: IngredientCache) = buildJsonObject {
        put("schema", 1); put("origin", origin); put("clock", value.clock)
        put("items", JsonArray(value.items.map(::itemJson)))
    }.toString().encodeToByteArray()
    private fun itemJson(item: CachedIngredient) = buildJsonObject {
        put("document", item.document.encodeUtf8().decodeToString()); put("checked", item.checked)
    }
    private companion object { const val MAX_BYTES = 524_288 }
}

/** Exact positive integer normalization, including JSON integer-valued exponent spellings. */
internal fun pickerVersion(document: WireDocument): String {
    val token = document.json().jsonObject.getValue("version").jsonPrimitive
    if (token.isString || token.content.length > 1000) mealFail(FailureReason.INVALID_DATA)
    val parts = token.content.lowercase().split('e'); val mantissa = parts[0]
    if (mantissa.startsWith('-')) mealFail(FailureReason.INVALID_DATA)
    val power = (parts.getOrNull(1)?.toIntOrNull() ?: if (parts.size == 1) 0 else mealFail(FailureReason.INVALID_DATA)).toLong() -
        mantissa.substringAfter('.', "").length
    if (power !in -1000L..1000L) mealFail(FailureReason.INVALID_DATA)
    var digits = mantissa.replace(".", "").trimStart('0'); var exponent = power.toInt()
    while (digits.endsWith('0')) { digits = digits.dropLast(1); exponent++ }
    if (digits.isEmpty() || exponent < 0 || digits.length + exponent > 2000) mealFail(FailureReason.INVALID_DATA)
    return digits + "0".repeat(exponent)
}
internal fun pickerCompareVersion(left: WireDocument, right: WireDocument): Int {
    val a = pickerVersion(left); val b = pickerVersion(right)
    return if (a.length == b.length) a.compareTo(b) else a.length.compareTo(b.length)
}
