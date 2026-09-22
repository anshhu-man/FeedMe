package com.feedme.server.ai

import com.feedme.server.config.AccountMealIntentRuntimeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

/** Explicit operator check: one synthetic request, no database or real user data.
 * Configuration uses the same backend-only parser as the actual account runtime.
 * Never invoke this on ordinary app startup, retry it automatically, or log output.
 */
fun main(args: Array<String>) {
    if (!args.contentEquals(arrayOf("--check-free"))) {
        System.err.println("Use --check-free with the backend AI environment securely configured. One request consumes free quota; no credentials belong in arguments.")
        exitProcess(2)
    }
    val result = try {
        val configuration = AccountMealIntentRuntimeConfig.fromEnvironment(System.getenv())
        if (configuration == null) "NOT_CONFIGURED" else {
            CloudflareJsonModel.create(configuration.modelConfiguration).use { model ->
                runBlocking { cloudflareStructuredOutputProbe(model) }
            }
        }
    } catch (_: CancellationException) {
        "CANCELLED"
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        "CANCELLED"
    } catch (_: Exception) {
        "CONFIGURATION_OR_PROVIDER_UNAVAILABLE"
    }
    // Only fixed statuses leave this process. No exception, provider body or configuration.
    println("FeedMe synthetic structured-output probe: $result")
    exitProcess(if (result == "STRUCTURED_OUTPUT_ACCEPTED") 0 else 1)
}

internal suspend fun cloudflareStructuredOutputProbe(model: HostedJsonModel): String {
    val schema = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", buildJsonArray { add(JsonPrimitive("status")) })
        put("properties", buildJsonObject {
            put("status", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add(JsonPrimitive("feedme_probe_ready")) })
            })
        })
    }
    return when (val result = model.complete(
        "This is a synthetic integration check, not a user request. Return only the JSON object required by the schema, with status feedme_probe_ready.",
        buildJsonObject { put("operation", "synthetic-structured-output-check") },
        schema,
    )) {
        is HostedJsonResult.Value -> if (result.value == JsonObject(mapOf("status" to JsonPrimitive("feedme_probe_ready"))))
            "STRUCTURED_OUTPUT_ACCEPTED" else "INVALID_RESPONSE"
        is HostedJsonResult.Unavailable -> result.reason.name
    }
}
