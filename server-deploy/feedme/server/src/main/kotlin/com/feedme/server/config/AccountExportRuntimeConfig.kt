package com.feedme.server.config

import com.feedme.core.ports.SecretText
import com.feedme.server.export.AccountExportEncryption
import com.feedme.server.identity.SupabaseAuthorityDeployment
import com.feedme.server.media.supabase.SupabaseStorageHttpConfiguration
import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.*

/** Backend-only export storage settings. Parsing neither provisions a bucket nor exports data. */
internal class AccountExportRuntimeConfig private constructor(
    val storage: SupabaseStorageHttpConfiguration,
    val publicOrigin: String,
    val keyId: String,
    private val wrappingKey: SecretText,
    val maxCapabilities: Int,
    val maxRowsPerSection: Int,
    val maxPlaintextBytes: Int,
) {
    fun encryption(): AccountExportEncryption = wrappingKey.use { encoded ->
        val bytes = Base64.getDecoder().decode(encoded)
        try { AccountExportEncryption(keyId, bytes) } finally { bytes.fill(0) }
    }
    override fun toString() = "AccountExportRuntimeConfig(<redacted>)"

    companion object {
        const val CONFIG = "FEEDME_ACCOUNT_EXPORT_CONFIG"
        const val API_KEY = "FEEDME_ACCOUNT_EXPORT_STORAGE_API_KEY"
        const val BEARER = "FEEDME_ACCOUNT_EXPORT_STORAGE_BEARER"
        const val WRAPPING_KEY = "FEEDME_ACCOUNT_EXPORT_WRAPPING_KEY"
        val ENVIRONMENT_KEYS = setOf(CONFIG, API_KEY, BEARER, WRAPPING_KEY)

        fun fromEnvironment(values: Map<String, String>, environment: String,
            deployment: SupabaseAuthorityDeployment): AccountExportRuntimeConfig? {
            try {
                if (ENVIRONMENT_KEYS.none(values::containsKey)) return null
                require(values.keys.containsAll(setOf(CONFIG, API_KEY, WRAPPING_KEY)))
                val root = AccountCoreRuntimeConfig.document(values.getValue(CONFIG), 8192)
                require(root.keys == setOf("provider", "projectOrigin", "bucket", "publicOrigin", "keyId",
                    "connectTimeoutMillis", "readTimeoutMillis", "callTimeoutMillis", "maxCapabilities", "maxRowsPerSection", "maxPlaintextBytes"))
                fun text(key: String): String = root.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
                fun number(key: String, range: LongRange): Long = root.getValue(key).jsonPrimitive.let {
                    require(!it.isString && it.content.matches(Regex("0|[1-9][0-9]{0,18}")))
                    it.content.toLong().also { n -> require(n in range) }
                }
                require(text("provider") == "supabase")
                val project = text("projectOrigin")
                require(project + "/auth/v1" == deployment.verification.issuer)
                val origin = text("publicOrigin")
                val uri = URI(origin)
                require(origin.length in 1..512 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                    uri.host == uri.host.lowercase() && uri.port in setOf(-1, 443) && uri.rawPath.isEmpty() &&
                    uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null &&
                    uri.toASCIIString() == origin && '%' !in origin && '\\' !in origin)
                val keyId = text("keyId").also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) }
                val encoded = values.getValue(WRAPPING_KEY)
                val key = Base64.getDecoder().decode(encoded)
                try { require(key.size == 32 && Base64.getEncoder().encodeToString(key) == encoded) } finally { key.fill(0) }
                val bytes = number("maxPlaintextBytes", 1024L..4_194_304L).toInt()
                val storage = SupabaseStorageHttpConfiguration(environment, project, text("bucket"),
                    SecretText(values.getValue(API_KEY)), values[BEARER]?.let(::SecretText),
                    number("connectTimeoutMillis", 100L..10_000L), number("readTimeoutMillis", 100L..30_000L),
                    number("callTimeoutMillis", 100L..60_000L), bytes.toLong() + 16L, 60, 0)
                return AccountExportRuntimeConfig(storage, origin, keyId, SecretText(encoded),
                    number("maxCapabilities", 1L..128L).toInt(), number("maxRowsPerSection", 1L..10_000L).toInt(), bytes)
            } catch (_: Exception) { throw IllegalArgumentException("Account export configuration unavailable") }
        }
    }
}
