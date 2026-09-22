package com.feedme.server.planning

import com.feedme.contracts.CanonicalFormats
import com.feedme.planning.PlanningMemory
import com.feedme.planning.PlanningPreferences
import java.util.UUID
import kotlinx.serialization.json.*

/** Bounded historical decision inputs, not current memory/source authorization. The live
 * account adapter obtains these only through the actual owner/projection evidence reader. */
internal object PlanningMemorySnapshot {
    fun fromMemories(memories: List<JsonObject>): JsonArray = JsonArray(memories.map { row ->
        buildJsonObject {
            put("id", row.getValue("id")); put("version", row.getValue("version").jsonPrimitive.content)
            put("kind", row.getValue("kind")); put("value", row.getValue("value"))
            put("context", row.getValue("context"))
        }
    }).also(::validate)

    fun validate(value: JsonArray) {
        require(value.size <= 50)
        val ids = value.map { item ->
            val row = item.jsonObject
            require(row.keys == setOf("id", "version", "kind", "value", "context"))
            val id = id(row.text("id"))
            require(row.text("version").matches(Regex("[1-9][0-9]{0,18}")))
            require(row.text("kind") in setOf("taste", "effort", "repeat"))
            require(row.text("value") in setOf("prefer", "show_less"))
            val context = row.getValue("context").jsonObject
            require(context.keys.all { it in setOf("recipeVersionId", "ingredientId", "tasteTag", "effortAspect") })
            context["recipeVersionId"]?.let { id(context.text("recipeVersionId")) }
            context["ingredientId"]?.let { id(context.text("ingredientId")) }
            context["tasteTag"]?.let { require(context.text("tasteTag") in setOf("crunch", "fresh", "creamy", "heat")) }
            context["effortAspect"]?.let { require(context.text("effortAspect") in setOf("chopping", "activeCooking", "cleanup")) }
            id
        }
        require(ids.distinct().size == ids.size && ids == ids.sorted())
    }

    fun preferences(p: JsonObject): PlanningPreferences = PlanningPreferences(p.text("revision"),
        p.strings("excludedIngredientIds").toSet(), p.strings("dislikedIngredientIds").toSet(),
        p["personalizationEnabled"]?.let { p.bool("personalizationEnabled") } ?: true,
        (p["memories"] as? JsonArray).orEmpty().map { item ->
            val row = item.jsonObject; val context = row.getValue("context").jsonObject
            PlanningMemory(row.text("id"), row.text("version"), row.text("kind"), row.text("value"),
                context["recipeVersionId"]?.let { context.text("recipeVersionId") },
                context["ingredientId"]?.let { context.text("ingredientId") },
                context["tasteTag"]?.let { context.text("tasteTag") },
                context["effortAspect"]?.let { context.text("effortAspect") })
        })

    fun normalized(p: JsonObject) = JsonObject(p + mapOf(
        "personalizationEnabled" to (p["personalizationEnabled"] ?: JsonPrimitive(true)),
        "memories" to (p["memories"] ?: JsonArray(emptyList()))))

    private fun id(value: String): String {
        require(CanonicalFormats.accepts("uuid", value) && value == UUID.fromString(value).toString())
        return value
    }
}
