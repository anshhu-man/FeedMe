package com.feedme.server.config

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.auth.SupabaseJwksHttpPolicy
import com.feedme.server.auth.SupabaseSigningAlgorithm
import com.feedme.server.auth.SupabaseUserAccessConfiguration
import com.feedme.server.catalog.IngredientCatalogLimits
import com.feedme.server.catalog.IngredientSearchMode
import com.feedme.server.identity.AccountPendingProfileRules
import com.feedme.server.identity.SupabaseAuthorityDeployment
import com.feedme.server.kitchen.KitchenCursorCodec
import com.feedme.server.kitchen.KitchenServicePolicy
import java.net.URI
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.postgresql.ds.PGSimpleDataSource

/** Explicit local-only pantry launcher settings. Parsing and dataSource construction perform
 * no network/file access, migration, provisioning or eligibility/terms acceptance. The real
 * configured assembly still verifies provider/database compatibility and current authority.
 * Default Main does not consume this configuration. No live or deployment profile exists.
 * Password/key strings supplied by the environment cannot be securely erased on the JVM;
 * temporary decoded key arrays are wiped after the cursor codec takes its own copy. */
class AccountPantryRuntimeConfig private constructor(
    val listener: LocalServerConfig,
    val environment: String,
    val deployment: SupabaseAuthorityDeployment,
    val accountRules: AccountPendingProfileRules,
    val keyPolicy: SupabaseJwksHttpPolicy,
    val catalogLimits: IngredientCatalogLimits,
    val searchMode: IngredientSearchMode,
    val cursors: KitchenCursorCodec,
    val pantryPolicy: KitchenServicePolicy,
    val databaseParallelism: Int,
    private val database: Database,
) {
    internal fun dataSource(): DataSource = PGSimpleDataSource().also {
        it.setServerNames(arrayOf("127.0.0.1")); it.setPortNumbers(intArrayOf(database.port))
        it.setDatabaseName(database.name); it.setUser(database.user); it.setPassword(database.password)
        it.setSslMode("disable"); it.setGssEncMode("disable")
        it.setConnectTimeout(database.connectTimeout); it.setLoginTimeout(database.loginTimeout)
        it.setSocketTimeout(database.socketTimeout); it.setApplicationName("feedme-local-account-pantry")
    }

    override fun toString() = "AccountPantryRuntimeConfig(<redacted>)"

    private class Database(val port: Int, val name: String, val user: String, val password: String,
        val connectTimeout: Int, val loginTimeout: Int, val socketTimeout: Int)

    companion object {
        private const val CONFIG = "FEEDME_PANTRY_RUNTIME_CONFIG"
        private const val PASSWORD = "FEEDME_PANTRY_DB_PASSWORD"
        private const val KEYS = "FEEDME_PANTRY_CURSOR_KEYS"
        private val allowed = setOf(CONFIG, PASSWORD, KEYS)
        private val conflicting = setOf("PORT", "FEEDME_MINIMUM_APP_VERSION", "DATABASE_URL", "JDBC_DATABASE_URL",
            "JDBC_DATABASE_USERNAME", "JDBC_DATABASE_PASSWORD", "PGHOST", "PGHOSTADDR", "PGPORT", "PGDATABASE",
            "PGUSER", "PGPASSWORD", "PGSERVICE", "PGSERVICEFILE", "PGSSLMODE", "PGSSLROOTCERT", "PGOPTIONS", "PGPASSFILE")

        fun fromEnvironment(values: Map<String, String>): AccountPantryRuntimeConfig = try {
            require(values.keys.none { it.startsWith("FEEDME_PANTRY_") && it !in allowed ||
                it.startsWith("FEEDME_SERVER_") || it.startsWith("FEEDME_MIGRATION_") || it in conflicting })
            val root = document(requireNotNull(values[CONFIG]), 32_768)
            exact(root, "version", "environment", "listener", "database", "deployment", "accountRules",
                "keyPolicy", "catalogLimits", "searchMode", "pantryPolicy", "databaseParallelism")
            require(number(root, "version") == 1L && text(root, "environment", 40) == "local")
            val listening = root.getValue("listener").jsonObject
            exact(listening, "host", "port", "minimumAppVersion", "maximumInFlightRequests")
            require(text(listening, "host", 64) == "127.0.0.1")
            val listener = LocalServerConfig.fromEnvironment(mapOf(
                "FEEDME_SERVER_MODE" to "local", "FEEDME_SERVER_HOST" to "127.0.0.1",
                "FEEDME_SERVER_PORT" to integer(listening, "port", 1024..65535).toString(),
                "FEEDME_MINIMUM_APP_VERSION" to text(listening, "minimumAppVersion", 64),
                "FEEDME_SERVER_MAX_IN_FLIGHT_REQUESTS" to integer(listening, "maximumInFlightRequests", 1..4096).toString()))

            val db = root.getValue("database").jsonObject
            exact(db, "host", "port", "name", "user", "connectTimeoutSeconds", "loginTimeoutSeconds", "socketTimeoutSeconds")
            require(text(db, "host", 64) == "127.0.0.1")
            val name = text(db, "name", 63).also { require(it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_-]{0,62}"))) }
            val user = text(db, "user", 63).also { require(it.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,62}"))) }
            val password = requireNotNull(values[PASSWORD]).also {
                require(it.length in 1..4096 && !it.isBlank() && it.none(Char::isISOControl))
            }
            val database = Database(integer(db, "port", 1024..65535), name, user, password,
                integer(db, "connectTimeoutSeconds", 1..10), integer(db, "loginTimeoutSeconds", 1..15),
                integer(db, "socketTimeoutSeconds", 1..60))

            val d = root.getValue("deployment").jsonObject
            exact(d, "verification", "databaseName", "authSourceRevision", "migrationVersions", "reviewedAt", "validUntil",
                "timeboxSeconds", "inactivitySeconds", "singleSessionPerUser", "lowAssuranceTimeoutSeconds")
            require(text(d, "databaseName", 63) == name)
            val v = d.getValue("verification").jsonObject
            exact(v, "issuer", "jwksEndpoint", "audience", "algorithms", "maximumTokenLifetimeSeconds",
                "allowedFutureClockSkewSeconds", "maximumJwksAgeSeconds")
            val algorithms = strings(v, "algorithms", 2, 16)
            require(algorithms.isNotEmpty() && algorithms.distinct().size == algorithms.size)
            val verification = SupabaseUserAccessConfiguration(text(v, "issuer", 2048), URI(text(v, "jwksEndpoint", 2048)),
                text(v, "audience", 64), algorithms.map { SupabaseSigningAlgorithm.valueOf(it) }.toSet(),
                number(v, "maximumTokenLifetimeSeconds"), number(v, "allowedFutureClockSkewSeconds"), number(v, "maximumJwksAgeSeconds"))
            val deployment = SupabaseAuthorityDeployment(verification, name, text(d, "authSourceRevision", 128),
                strings(d, "migrationVersions", 512, 14), instant(d, "reviewedAt"), instant(d, "validUntil"),
                nullableNumber(d, "timeboxSeconds"), nullableNumber(d, "inactivitySeconds"), boolean(d, "singleSessionPerUser"),
                nullableNumber(d, "lowAssuranceTimeoutSeconds"))

            val rules = root.getValue("accountRules").jsonObject
            exact(rules, "eligibilityPolicyVersion", "requiredTermsVersion", "acceptExactSubmittedTerms")
            val accountRules = AccountPendingProfileRules(text(rules, "eligibilityPolicyVersion", 256),
                text(rules, "requiredTermsVersion", 256), boolean(rules, "acceptExactSubmittedTerms"))
            val k = root.getValue("keyPolicy").jsonObject
            exact(k, "connectTimeoutMillis", "socketTimeoutMillis", "totalTimeoutMillis", "cacheSeconds",
                "minimumFetchIntervalMillis", "maximumAdmittedCalls")
            val keyPolicy = SupabaseJwksHttpPolicy(number(k, "connectTimeoutMillis"), number(k, "socketTimeoutMillis"),
                number(k, "totalTimeoutMillis"), number(k, "cacheSeconds"), number(k, "minimumFetchIntervalMillis"),
                integer(k, "maximumAdmittedCalls", 1..32))
            require(keyPolicy.cacheSeconds <= verification.maximumJwksAgeSeconds)
            val limits = root.getValue("catalogLimits").jsonObject
            exact(limits, "maxReleaseBytes", "maxIngredients", "cursorLifetimeSeconds")
            val catalogLimits = IngredientCatalogLimits(integer(limits, "maxReleaseBytes", 1..1_048_576),
                integer(limits, "maxIngredients", 1..1024), integer(limits, "cursorLifetimeSeconds", 1..3600))
            val search = IngredientSearchMode.entries.single { it.wire == text(root, "searchMode", 64) }
            val p = root.getValue("pantryPolicy").jsonObject
            exact(p, "maxResponseBytes", "cursorLifetimeSeconds")
            val pantryPolicy = KitchenServicePolicy(integer(p, "maxResponseBytes", 1..262144),
                integer(p, "cursorLifetimeSeconds", 1..86400))
            val parallelism = integer(root, "databaseParallelism", 1..8)
            val cursors = cursorKeys(requireNotNull(values[KEYS]))
            AccountPantryRuntimeConfig(listener, "local", deployment, accountRules, keyPolicy, catalogLimits,
                search, cursors, pantryPolicy, parallelism, database)
        } catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw IllegalArgumentException("Account pantry runtime configuration unavailable") }

        private fun cursorKeys(raw: String): KitchenCursorCodec {
            val root = document(raw, 8192); exact(root, "currentKeyId", "keys")
            val current = text(root, "currentKeyId", 32)
            val encoded = root.getValue("keys").jsonObject
            require(encoded.size in 1..8 && current in encoded && encoded.keys.all { it.matches(Regex("[a-z0-9_-]{1,32}")) })
            val decoded = linkedMapOf<String, ByteArray>()
            try {
                for ((id, value) in encoded) {
                    val key = value.jsonPrimitive.let { require(it.isString); it.content }
                    require(key.matches(Regex("[A-Za-z0-9_-]{43}")))
                    val bytes = Base64.getUrlDecoder().decode(key)
                    decoded[id] = bytes
                    require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == key)
                }
                return KitchenCursorCodec(current, decoded)
            } finally { decoded.values.forEach { it.fill(0) } }
        }

        private fun document(raw: String, limit: Int): JsonObject {
            val checked = WireDocument.parse(raw, WireLimits(limit, 12, 32))
            return Json.parseToJsonElement(checked.encodeUtf8().decodeToString()).jsonObject
        }
        private fun exact(value: JsonObject, vararg fields: String) { require(value.keys == fields.toSet()) }
        private fun text(value: JsonObject, field: String, max: Int): String = value.getValue(field).jsonPrimitive.let {
            require(it.isString && it.content.length in 1..max && !it.content.isBlank() && it.content.none(Char::isISOControl)); it.content
        }
        private fun number(value: JsonObject, field: String): Long = value.getValue(field).jsonPrimitive.let {
            require(!it.isString && it.content.matches(Regex("0|[1-9][0-9]{0,18}"))); it.content.toLong()
        }
        private fun integer(value: JsonObject, field: String, range: IntRange): Int = number(value, field).let {
            require(it in range.first.toLong()..range.last.toLong()); it.toInt()
        }
        private fun nullableNumber(value: JsonObject, field: String): Long? =
            if (value.getValue(field) == JsonNull) null else number(value, field)
        private fun boolean(value: JsonObject, field: String): Boolean = value.getValue(field).jsonPrimitive.let {
            require(!it.isString); it.boolean
        }
        private fun strings(value: JsonObject, field: String, max: Int, length: Int): List<String> =
            value.getValue(field).jsonArray.also { require(it.size <= max) }.map {
                it.jsonPrimitive.let { s -> require(s.isString && s.content.length in 1..length && s.content.none(Char::isISOControl)); s.content }
            }
        private fun instant(value: JsonObject, field: String): Instant = text(value, field, 40).let {
            Instant.parse(it).also { parsed -> require(parsed.toString() == it) }
        }
    }
}
