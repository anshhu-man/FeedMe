package com.feedme.server.config

import com.feedme.core.ports.SecretText
import com.feedme.server.identity.AccountPendingProfileRules
import com.feedme.server.identity.SupabaseAuthorityDeployment
import com.feedme.server.media.AccountMediaAdmissionPolicy
import com.feedme.server.media.MediaServicePolicy
import com.feedme.server.media.SUPABASE_MEDIA_PROTOCOL
import com.feedme.server.media.supabase.SupabaseStorageHttpConfiguration
import kotlinx.serialization.json.*

/** Explicit backend-only private photo upload configuration. No provider provisioning,
 * billing change, policy acceptance, readiness claim or secret fallback occurs here. */
internal class AccountMediaRuntimeConfig private constructor(
    val storage: SupabaseStorageHttpConfiguration,
    val service: MediaServicePolicy,
    val admission: AccountMediaAdmissionPolicy,
) {
    override fun toString() = "AccountMediaRuntimeConfig(<redacted>)"

    companion object {
        const val CONFIG = "FEEDME_ACCOUNT_MEDIA_CONFIG"
        const val API_KEY = "FEEDME_ACCOUNT_MEDIA_STORAGE_API_KEY"
        const val BEARER = "FEEDME_ACCOUNT_MEDIA_STORAGE_BEARER"
        val ENVIRONMENT_KEYS = setOf(CONFIG, API_KEY, BEARER)

        fun fromEnvironment(values: Map<String, String>, environment: String,
            deployment: SupabaseAuthorityDeployment, rules: AccountPendingProfileRules): AccountMediaRuntimeConfig? {
            try {
                if (ENVIRONMENT_KEYS.none(values::containsKey)) return null
                require(values.containsKey(CONFIG) && values.containsKey(API_KEY))
                val root = AccountCoreRuntimeConfig.document(values.getValue(CONFIG), 8_192)
                require(root.keys == setOf("provider", "projectOrigin", "bucket", "uploadsEnabled",
                    "maximumReservationsPer24Hours", "maximumOwnedSourceBytes", "maxSourceBytes",
                    "reservationLifetimeSeconds", "capabilityLifetimeSeconds", "maxCapabilityBytes",
                    "maxResponseBytes", "connectTimeoutMillis", "readTimeoutMillis", "callTimeoutMillis",
                    "clockSkewSeconds"))
                require(root["provider"] == JsonPrimitive("supabase"))
                fun text(name: String): String = root.getValue(name).jsonPrimitive.let {
                    require(it.isString); it.content
                }
                fun number(name: String, range: LongRange): Long = root.getValue(name).jsonPrimitive.let {
                    require(!it.isString && it.content.matches(Regex("0|[1-9][0-9]{0,18}")))
                    it.content.toLong().also { value -> require(value in range) }
                }
                val enabled = root.getValue("uploadsEnabled").jsonPrimitive.let {
                    require(!it.isString); requireNotNull(it.booleanOrNull)
                }
                val origin = text("projectOrigin")
                require(origin + "/auth/v1" == deployment.verification.issuer)
                val bytes = number("maxSourceBytes", 1L..786_432L)
                val reservation = number("reservationLifetimeSeconds", 1L..86_400L)
                val capability = number("capabilityLifetimeSeconds", 1L..reservation)
                val capabilityBytes = number("maxCapabilityBytes", 1L..65_536L).toInt()
                val responseBytes = number("maxResponseBytes", 4_096L..262_144L).toInt()
                require(responseBytes.toLong() >= capabilityBytes.toLong() + 2_048)
                val connectMillis = number("connectTimeoutMillis", 100L..10_000L)
                val readMillis = number("readTimeoutMillis", 100L..30_000L)
                val callMillis = number("callTimeoutMillis", 100L..60_000L)
                // Provider JWT iat is integer-second precision; a zero tolerance would
                // reject an otherwise synchronized mint started at a fractional second.
                val skew = number("clockSkewSeconds", 1L..120L)
                // Supabase signed upload URLs have a fixed two-hour lifetime, not a
                // caller-selected short TTL. Reserve headroom before minting; never
                // pretend the remote bearer expires at a shorter local review deadline.
                require(capability >= 7_200 + (callMillis + 999) / 1_000 + skew + 1)
                val storage = SupabaseStorageHttpConfiguration(environment, origin, text("bucket"),
                    SecretText(values.getValue(API_KEY)), values[BEARER]?.let(::SecretText),
                    connectMillis, readMillis, callMillis, bytes, 7_200, skew)
                val service = MediaServicePolicy(bytes, setOf("image/jpeg", "image/png"),
                    reservation.toInt(), capability.toInt(), capabilityBytes, responseBytes, 128, setOf(origin),
                    allowedProtocols = setOf(SUPABASE_MEDIA_PROTOCOL))
                val admission = AccountMediaAdmissionPolicy(rules.eligibilityPolicyVersion, rules.requiredTermsVersion,
                    enabled, number("maximumReservationsPer24Hours", 1L..10_000L).toInt(),
                    number("maximumOwnedSourceBytes", bytes..1_000_000_000_000L))
                return AccountMediaRuntimeConfig(storage, service, admission)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Account photo upload configuration unavailable")
            } catch (_: IllegalStateException) {
                throw IllegalArgumentException("Account photo upload configuration unavailable")
            } catch (_: NoSuchElementException) {
                throw IllegalArgumentException("Account photo upload configuration unavailable")
            }
        }
    }
}
