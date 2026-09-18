package com.feedme.contracts

import kotlinx.serialization.json.*

/** Only the pinned vocabulary is supported. Unknown keywords/configuration fail at initialization. */
internal class CanonicalSchemaProgram(private val resolve: (String) -> JsonObject) {
    private val references = mutableMapOf<String, SchemaRule>()
    private val compiling = mutableSetOf<String>()

    fun compile(schema: JsonElement): SchemaRule {
        if (schema is JsonPrimitive) {
            metadata(!schema.isString && schema.booleanOrNull != null)
            val allowed = schema.boolean
            return SchemaRule { _, budget -> budget.spend(); allowed }
        }
        val shape = schema as? JsonObject ?: configurationFailure()
        metadata(shape.keys.all { it in KEYWORDS })
        val ref = shape["\$ref"]?.let { value ->
            val name = value.schemaString()
            metadata(name.startsWith("#/components/schemas/") && name.removePrefix("#/components/schemas/").isNotEmpty())
            references[name] ?: run {
                // The current pinned graph is acyclic. A future recursive schema requires review,
                // not an unbounded recursion or silently accepted constraint.
                metadata(compiling.add(name))
                compile(resolve(name)).also { references[name] = it; compiling.remove(name) }
            }
        }
        val types = shape["type"]?.let { value ->
            val names = if (value is JsonArray) value.map { it.schemaString() } else listOf(value.schemaString())
            metadata(names.isNotEmpty() && names.toSet().size == names.size && names.all { it in TYPES })
            names.toSet()
        }
        val required = shape["required"]?.schemaStrings()?.also { metadata(it.size == it.toSet().size) }.orEmpty()
        val properties = shape["properties"]?.let { value ->
            (value as? JsonObject ?: configurationFailure()).mapValues { compile(it.value) }
        }.orEmpty()
        val additional = shape["additionalProperties"]?.let(::compile)
        val items = shape["items"]?.let(::compile)
        val minItems = shape.nonnegativeInt("minItems")
        val maxItems = shape.nonnegativeInt("maxItems")
        val unique = shape["uniqueItems"]?.let { value ->
            val p = value as? JsonPrimitive ?: configurationFailure()
            metadata(!p.isString && p.booleanOrNull != null); p.boolean
        } ?: false
        val minLength = shape.nonnegativeInt("minLength")
        val maxLength = shape.nonnegativeInt("maxLength")
        val pattern = shape["pattern"]?.schemaString()?.let { text ->
            // Reviewed, bounded ASCII expressions only. Do not inherit arbitrary engine dialects.
            metadata(text in PATTERNS)
            Regex(text)
        }
        val format = shape["format"]?.schemaString()?.also { metadata(CanonicalFormats.supports(it)) }
        val minimum = shape["minimum"]?.schemaNumber()
        val maximum = shape["maximum"]?.schemaNumber()
        val choices = shape["enum"]?.let { value ->
            (value as? JsonArray ?: configurationFailure()).also { metadata(it.isNotEmpty()) }
                .map { equalityKey(it, ValidationBudget()) }
                .also { metadata(it.size == it.toSet().size) }
        }
        val constant = shape["const"]?.let { equalityKey(it, ValidationBudget()) }
        val all = shape["allOf"]?.schemaRules(::compile).orEmpty()
        val any = shape["anyOf"]?.schemaRules(::compile)
        val condition = shape["if"]?.let(::compile)
        val then = shape["then"]?.let(::compile)
        val otherwise = shape["else"]?.let(::compile)
        shape["description"]?.schemaString()
        // default is annotation only; never insert it into an instance.

        return SchemaRule { value, budget ->
            budget.spend()
            if (ref != null && !ref.accepts(value, budget)) return@SchemaRule false
            if (types != null && !types.any { matchesType(value, it) }) return@SchemaRule false
            if (choices != null && equalityKey(value, budget) !in choices) return@SchemaRule false
            if (constant != null && equalityKey(value, budget) != constant) return@SchemaRule false
            if (value is JsonObject) {
                if (required.any { it !in value }) return@SchemaRule false
                for ((name, child) in value) {
                    budget.spend()
                    val rule = properties[name] ?: additional
                    if (rule != null && !rule.accepts(child, budget)) return@SchemaRule false
                }
            }
            if (value is JsonArray) {
                if (minItems != null && value.size < minItems) return@SchemaRule false
                if (maxItems != null && value.size > maxItems) return@SchemaRule false
                if (unique) {
                    val seen = mutableSetOf<Any>()
                    for (child in value) if (!seen.add(equalityKey(child, budget))) return@SchemaRule false
                }
                if (items != null) for (child in value) if (!items.accepts(child, budget)) return@SchemaRule false
            }
            if (value is JsonPrimitive && value.isString) {
                val text = value.content
                val length = text.length - text.count { it.isLowSurrogate() }
                if (minLength != null && length < minLength) return@SchemaRule false
                if (maxLength != null && length > maxLength) return@SchemaRule false
                // Reviewed expressions are anchored at both ends. Full matching avoids
                // JVM/native '$' accepting a prefix before a final Unicode line terminator.
                if (pattern != null && !pattern.matches(text)) return@SchemaRule false
                if (format != null && !CanonicalFormats.accepts(format, text)) return@SchemaRule false
            }
            if (isNumber(value) && (minimum != null || maximum != null)) {
                val number = number(value)
                if (minimum != null && number < minimum) return@SchemaRule false
                if (maximum != null && number > maximum) return@SchemaRule false
            }
            if (!all.all { it.accepts(value, budget) }) return@SchemaRule false
            if (any != null && !any.any { it.accepts(value, budget) }) return@SchemaRule false
            if (condition != null) {
                val selected = if (condition.accepts(value, budget)) then else otherwise
                if (selected != null && !selected.accepts(value, budget)) return@SchemaRule false
            }
            true
        }
    }

    companion object {
        private val KEYWORDS = setOf("\$ref", "type", "additionalProperties", "properties", "format", "minimum", "maximum",
            "items", "required", "enum", "minLength", "maxLength", "pattern", "description", "uniqueItems", "maxItems",
            "allOf", "if", "const", "then", "else", "anyOf", "minItems", "default")
        private val TYPES = setOf("object", "array", "string", "number", "integer", "boolean", "null")
        private val PATTERNS = setOf("^[a-z0-9_]{3,24}$", "^[a-fA-F0-9]{64}$", "^[a-z][a-z0-9-]{0,39}$",
            "^[0-9a-f]{64}$") // Terms descriptor SHA: exactly 64 lowercase ASCII hex characters.
    }
}

internal fun interface SchemaRule {
    fun accepts(value: JsonElement, budget: ValidationBudget): Boolean
}

/** Evaluation work limit is an explicit runtime policy, independent of schema constraints. */
internal class ValidationBudget(private var remaining: Int = 2_000_000) {
    fun spend() { if (--remaining < 0) throw ContractResourceLimit() }
}
internal class ContractResourceLimit : RuntimeException("Contract resource limit")

/** Walk also checks numbers in unrestricted webhook/unknown-field content, as the server does. */
internal fun validateNumberProfile(value: JsonElement, budget: ValidationBudget) {
    budget.spend()
    when (value) {
        is JsonObject -> value.values.forEach { validateNumberProfile(it, budget) }
        is JsonArray -> value.forEach { validateNumberProfile(it, budget) }
        else -> if (isNumber(value)) number(value)
    }
}

private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
    "object" -> value is JsonObject
    "array" -> value is JsonArray
    "string" -> value is JsonPrimitive && value.isString
    "number" -> isNumber(value)
    "integer" -> isNumber(value) && number(value).isInteger
    "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
    "null" -> value === JsonNull
    else -> configurationFailure()
}

private fun isNumber(value: JsonElement) = value is JsonPrimitive && value !== JsonNull && !value.isString && value.booleanOrNull == null
private fun number(value: JsonElement): ExactDecimal = ExactDecimal.parse(value.jsonPrimitive.content) ?: throw ContractResourceLimit()

// Structural equality is type-sensitive; numeric spelling and object property order do not matter.
private data class ScalarKey(val type: Int, val value: String) { override fun toString() = "ScalarKey(redacted)" }
private data class ArrayKey(val values: List<Any>) { override fun toString() = "ArrayKey(redacted)" }
private data class ObjectKey(val values: Map<String, Any>) { override fun toString() = "ObjectKey(redacted)" }
private fun equalityKey(value: JsonElement, budget: ValidationBudget): Any {
    budget.spend()
    return when (value) {
        is JsonObject -> ObjectKey(value.mapValues { equalityKey(it.value, budget) })
        is JsonArray -> ArrayKey(value.map { equalityKey(it, budget) })
        JsonNull -> ScalarKey(0, "")
        is JsonPrimitive -> if (value.isString) ScalarKey(1, value.content)
            else if (value.booleanOrNull != null) ScalarKey(2, value.content)
            else ScalarKey(3, number(value).canonicalKey)
    }
}

private fun JsonElement.schemaString(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: configurationFailure()
private fun JsonElement.schemaStrings(): List<String> = (this as? JsonArray)?.map { it.schemaString() } ?: configurationFailure()
private fun JsonElement.schemaNumber(): ExactDecimal {
    metadata(isNumber(this)); return ExactDecimal.parse(jsonPrimitive.content) ?: configurationFailure()
}
private fun JsonObject.nonnegativeInt(name: String): Int? = this[name]?.let {
    metadata(isNumber(it))
    it.jsonPrimitive.intOrNull?.takeIf { n -> n >= 0 } ?: configurationFailure()
}
private fun JsonElement.schemaRules(compile: (JsonElement) -> SchemaRule): List<SchemaRule> =
    (this as? JsonArray ?: configurationFailure()).also { metadata(it.isNotEmpty()) }.map(compile)
private fun metadata(condition: Boolean) { if (!condition) configurationFailure() }
private fun configurationFailure(): Nothing = throw IllegalStateException("Unsupported pinned contract schema")
