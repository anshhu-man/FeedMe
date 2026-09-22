package com.feedme.server.staff

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.SecretText
import com.feedme.server.config.PlatformMigrationConfig
import java.net.URI
import kotlinx.serialization.json.*

/** Explicit, separate operator process configuration. No consumer runtime credential,
 * database URL, staff provider, enrollment, policy or accepting environment is inferred. */
internal class StaffPublicationConfig private constructor(
    val database: PlatformMigrationConfig,
    val authentication: WorkforceAccessConfiguration,
    val accessToken: SecretText,
) {
    override fun toString() = "StaffPublicationConfig(<redacted>)"
    companion object {
        private const val PREFIX = "FEEDME_STAFF_"
        private val databaseKeys = setOf("ENVIRONMENT", "DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD", "DB_SSL_ROOT_CERT")
        private val allowed = databaseKeys + setOf("AUTH_CONFIG", "ACCESS_TOKEN")
        fun fromEnvironment(values: Map<String, String>): StaffPublicationConfig {
            require(values.keys.none { it.startsWith("FEEDME_MIGRATION_") ||
                (it.startsWith(PREFIX) && it.removePrefix(PREFIX) !in allowed) })
            // Reuse the exact hostname/verify-full/explicit trust-root and competing-PG-env
            // checks, without invoking the migration engine or using its ambient credentials.
            val databaseValues = values.filterKeys { !it.startsWith(PREFIX) }.toMutableMap()
            for (key in databaseKeys) values[PREFIX + key]?.let { databaseValues["FEEDME_MIGRATION_$key"] = it }
            val database = PlatformMigrationConfig.fromEnvironment(databaseValues)
            val raw = requireNotNull(values[PREFIX + "AUTH_CONFIG"])
            val fields = Json.parseToJsonElement(WireDocument.decode(raw.encodeToByteArray(throwOnInvalidSequence = true),
                WireLimits(16_384, 8, 20)).encodeUtf8().decodeToString()).jsonObject
            require(fields.keys == setOf("issuer", "jwksEndpoint", "consumerIssuer", "audience", "clientId", "algorithms",
                "maximumTokenLifetimeSeconds", "maximumAuthenticationAgeSeconds", "maximumJwksAgeSeconds"))
            val algorithms = fields.getValue("algorithms").jsonArray.map {
                require(it.jsonPrimitive.isString); WorkforceSigningAlgorithm.valueOf(it.jsonPrimitive.content)
            }
            require(algorithms.distinct().size == algorithms.size)
            val authentication = WorkforceAccessConfiguration(fields.text("issuer"), URI(fields.text("jwksEndpoint")),
                fields.text("consumerIssuer"), fields.text("audience"), fields.text("clientId"), algorithms.toSet(),
                fields.seconds("maximumTokenLifetimeSeconds"), fields.seconds("maximumAuthenticationAgeSeconds"),
                fields.seconds("maximumJwksAgeSeconds"), allowedFutureClockSkewSeconds = 0)
            val token = requireNotNull(values[PREFIX + "ACCESS_TOKEN"])
            require(token.length in 1..16_384 && token.all { it.code in 33..126 })
            return StaffPublicationConfig(database, authentication, SecretText(token))
        }
        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
        private fun JsonObject.seconds(key: String) = getValue(key).jsonPrimitive.let {
            require(!it.isString && it.content.matches(Regex("[1-9][0-9]{0,4}"))); it.content.toLong()
        }
    }
}
