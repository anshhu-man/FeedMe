package com.feedme.server.config

import com.feedme.server.ai.CloudflareJsonConfiguration
import com.feedme.server.ai.CloudflareJsonModel
import com.feedme.server.ai.CloudflareJsonPolicy
import kotlinx.serialization.json.*

/** Optional backend-only capability. Parsing neither contacts Cloudflare nor proves its
 * billing plan. freeTierAccountVerified records the operator's explicit verification of the
 * Workers Free account/hard quota; upgrading that account is outside this program's authority.
 * No paid endpoint/model, fallback, retry, default credential or automatic enablement exists.
 * Remove all three inputs to disable. JVM/environment strings cannot be securely erased.
 */
internal class AccountMealIntentRuntimeConfig private constructor(
    val modelConfiguration: CloudflareJsonConfiguration,
    val minimumStartIntervalMillis: Long,
) {
    override fun toString() = "AccountMealIntentRuntimeConfig(<redacted>)"

    companion object {
        const val CONFIG = "FEEDME_ACCOUNT_AI_CONFIG"
        const val ACCOUNT_ID = "FEEDME_ACCOUNT_AI_ACCOUNT_ID"
        const val API_TOKEN = "FEEDME_ACCOUNT_AI_API_TOKEN"
        val ENVIRONMENT_KEYS = setOf(CONFIG, ACCOUNT_ID, API_TOKEN)

        /** Absent is disabled; partial, malformed or unsupported input is a redacted refusal. */
        fun fromEnvironment(values: Map<String, String>): AccountMealIntentRuntimeConfig? {
            try {
                require(values.keys.none { it.startsWith("FEEDME_ACCOUNT_AI_") && it !in ENVIRONMENT_KEYS })
                if (ENVIRONMENT_KEYS.none(values::containsKey)) return null
                require(ENVIRONMENT_KEYS.all(values::containsKey))
                val root = AccountCoreRuntimeConfig.document(values.getValue(CONFIG), 4_096)
                require(root.keys == setOf("provider", "billingMode", "freeTierAccountVerified", "model",
                    "maxRequestBytes", "maxResponseBytes", "maxOutputTokens", "timeoutMillis", "minimumStartIntervalMillis"))
                require(root["provider"] == JsonPrimitive("cloudflare") &&
                    root["billingMode"] == JsonPrimitive("free-only") &&
                    root["freeTierAccountVerified"] == JsonPrimitive(true) &&
                    root["model"] == JsonPrimitive(CloudflareJsonModel.MODEL))
                fun number(name: String, range: LongRange): Long {
                    val value = root.getValue(name).jsonPrimitive
                    require(!value.isString && value.content.matches(Regex("0|[1-9][0-9]{0,18}")))
                    return value.content.toLong().also { require(it in range) }
                }
                val policy = CloudflareJsonPolicy(
                    number("maxRequestBytes", 1_024L..65_536L).toInt(),
                    number("maxResponseBytes", 1_024L..262_144L).toInt(),
                    number("maxOutputTokens", 1L..4_096L).toInt(),
                    number("timeoutMillis", 1L..30_000L))
                val model = CloudflareJsonConfiguration(values.getValue(ACCOUNT_ID), values.getValue(API_TOKEN), policy)
                require(model.valid())
                return AccountMealIntentRuntimeConfig(model, number("minimumStartIntervalMillis", 1_000L..60_000L))
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Account meal interpretation configuration unavailable")
            } catch (_: IllegalStateException) {
                throw IllegalArgumentException("Account meal interpretation configuration unavailable")
            } catch (_: NoSuchElementException) {
                throw IllegalArgumentException("Account meal interpretation configuration unavailable")
            }
        }
    }
}
