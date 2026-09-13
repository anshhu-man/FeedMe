@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

@Suppress("UNCHECKED_CAST")
fun serializerFor(name: String): KSerializer<Any> {
    val type = Class.forName("feedme.contract.spike.models.$name")
    val companion = type.getField("Companion").get(null)
    return companion.javaClass.getMethod("serializer").invoke(companion) as KSerializer<Any>
}

fun main(args: Array<String>) {
    val data = Json.parseToJsonElement(File(args[0]).readText()).jsonObject
    // Match the generated ApiClient.JSON_DEFAULT configuration.
    val generatedJson = Json { ignoreUnknownKeys = true }
    val strictJson = Json { ignoreUnknownKeys = false }
    var requiredPresent = 0
    var requiredMissingRejected = 0
    var optionalNullLost = 0
    var stringPreserved = 0
    for (entry in data.getValue("nullable").jsonArray) {
        val c = entry.jsonObject
        val name = c.getValue("name").jsonPrimitive.content
        val field = c.getValue("property").jsonPrimitive.content
        val required = c.getValue("required").jsonPrimitive.boolean
        val serializer = serializerFor(name)
        val input = c.getValue("input").jsonObject
        val decoded = generatedJson.decodeFromJsonElement(serializer, input)
        val roundTrip = generatedJson.encodeToJsonElement(serializer, decoded).jsonObject
        val absent = JsonObject(input.filterKeys { it != field })
        val absentResult = runCatching { generatedJson.decodeFromJsonElement(serializer, absent) }
        val value = JsonPrimitive(if (field == "confirmedAt") "2026-09-13T00:00:00Z" else "next-page")
        val withValue = JsonObject(input + (field to value))
        if (generatedJson.encodeToJsonElement(serializer, generatedJson.decodeFromJsonElement(serializer, withValue)).jsonObject[field] == value) stringPreserved++
        if (required && roundTrip[field] == JsonNull) requiredPresent++
        if (required && absentResult.isFailure) requiredMissingRejected++
        if (!required && field !in roundTrip && absentResult.getOrNull() == decoded) optionalNullLost++
        println("nullable $name.$field required=$required nullPresent=${field in roundTrip} missingRejected=${absentResult.isFailure}")
    }
    println("TOTAL requiredNullPresent=$requiredPresent requiredMissingRejected=$requiredMissingRejected optionalNullLost=$optionalNullLost stringPreserved=$stringPreserved")
    var invalidAccepted = 0
    for (entry in data.getValue("conditional").jsonArray) {
        val c = entry.jsonObject
        val name = c.getValue("name").jsonPrimitive.content
        val input = c.getValue("input")
        val result = runCatching { generatedJson.decodeFromJsonElement(serializerFor(name), input) }
        if (result.isSuccess) invalidAccepted++
        println("conditional $name input=$input invalidAccepted=${result.isSuccess}")
    }
    println("TOTAL conditionalInvalidAccepted=$invalidAccepted")
    for (entry in data.getValue("unique").jsonArray) {
        val c = entry.jsonObject
        val name = c.getValue("name").jsonPrimitive.content
        val field = c.getValue("property").jsonPrimitive.content
        val serializer = serializerFor(name)
        val input = c.getValue("input")
        val decoded = generatedJson.decodeFromJsonElement(serializer, input)
        val output = generatedJson.encodeToJsonElement(serializer, decoded).jsonObject
        println("unique $name.$field duplicateAccepted=true outputCount=${output.getValue(field).jsonArray.size}")
    }
    val target = serializerFor("FeedbackTarget")
    val validTarget = Json.parseToJsonElement("""{"kind":"taste","tag":"fresh","unexpected":true}""")
    val permissive = generatedJson.decodeFromJsonElement(target, validTarget)
    println("strictObject generatedAcceptsUnknown=true unknownLost=${"unexpected" !in generatedJson.encodeToJsonElement(target, permissive).jsonObject} strictConfigRejects=${runCatching { strictJson.decodeFromJsonElement(target, validTarget) }.isFailure}")
    println("unknownEnum rejected=${runCatching { generatedJson.decodeFromString(target, """{"kind":"newFutureKind"}""") }.isFailure}")
    val uploadSerializer = serializerFor("Upload")
    val upload = data.getValue("upload").jsonObject
    val withMap = JsonObject(upload + ("uploadFields" to buildJsonObject { put("a", "one"); put("b", "two") }))
    val mapRoundTrip = generatedJson.encodeToJsonElement(uploadSerializer, generatedJson.decodeFromJsonElement(uploadSerializer, withMap)).jsonObject
    val badMap = JsonObject(upload + ("uploadFields" to buildJsonObject { put("a", 5) }))
    println("typedMap preserved=${withMap["uploadFields"] == mapRoundTrip["uploadFields"]} numericValueRejected=${runCatching { generatedJson.decodeFromJsonElement(uploadSerializer, badMap) }.isFailure}")
    // These are intentionally schema-invalid; the probe reports whether DTO decoding catches them.
    println("uuidFormat invalidAccepted=${runCatching { generatedJson.decodeFromString(serializerFor("PantryWrite"), """{"ingredientId":"not-a-uuid","presence":"available"}""") }.isSuccess}")
    println("minimum invalidAccepted=${runCatching { generatedJson.decodeFromString(serializerFor("PantryWrite"), """{"ingredientId":"12345678-1234-4234-8234-123456789abc","presence":"available","quantity":-1}""") }.isSuccess}")
}
