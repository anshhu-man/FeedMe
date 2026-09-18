package com.feedme.server.config

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.auth.SupabaseJwksHttpPolicy
import com.feedme.server.auth.SupabaseSigningAlgorithm
import com.feedme.server.auth.SupabaseUserAccessConfiguration
import com.feedme.server.catalog.ConfiguredIngredientPreferencePolicy
import com.feedme.server.catalog.IngredientCatalogLimits
import com.feedme.server.catalog.IngredientSearchCursor
import com.feedme.server.catalog.IngredientSearchMode
import com.feedme.server.catalog.PreferenceConsentPolicy
import com.feedme.server.cooking.CookingServicePolicy
import com.feedme.server.identity.AccountPendingProfileRules
import com.feedme.server.identity.AccountDeviceReconnectionRules
import com.feedme.server.identity.AccountTermsNotice
import com.feedme.server.identity.SupabaseAuthorityDeployment
import com.feedme.server.kitchen.KitchenCursorCodec
import com.feedme.server.kitchen.KitchenServicePolicy
import com.feedme.server.memory.SavedRecipeCursors
import com.feedme.server.memory.SavedRecipeServicePolicy
import com.feedme.server.planning.AccountPlanningPolicy
import com.feedme.server.planning.PlanningCursors
import com.feedme.server.planning.PlanningServicePolicy
import java.net.URI
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.postgresql.ds.PGSimpleDataSource

/** Explicit settings for the account core, not deployment, tenant or content attestation.
 * Parsing and DataSource construction do not read files, connect, migrate, provision, accept
 * terms or grant eligibility. A remote target always requests PostgreSQL verify-full with an
 * explicit trust-root path; this validates the path shape, NOT its contents or availability.
 * A container listener does not establish HTTPS termination. The configured assembly must
 * still perform all compatibility, current provider/account and operation-specific checks.
 * Environment/JVM strings cannot be erased; temporary decoded cursor key arrays are wiped
 * after the four purpose-specific codecs take detached copies. There are no launch defaults.
 */
class AccountCoreRuntimeConfig private constructor(
    val listener: ServerStartupConfig,
    val environment: String,
    val deployment: SupabaseAuthorityDeployment,
    val accountRules: AccountPendingProfileRules,
    val reconnectionRules: AccountDeviceReconnectionRules?,
    val termsNotice: AccountTermsNotice?,
    val keyPolicy: SupabaseJwksHttpPolicy,
    val ingredientLimits: IngredientCatalogLimits,
    val searchMode: IngredientSearchMode,
    val preferencePolicy: ConfiguredIngredientPreferencePolicy,
    val ingredientCursors: IngredientSearchCursor,
    val kitchenCursors: KitchenCursorCodec,
    val kitchenPolicy: KitchenServicePolicy,
    val planningOperational: AccountPlanningPolicy,
    val planningPolicy: PlanningServicePolicy,
    val planningCursors: PlanningCursors,
    val cookingPolicy: CookingServicePolicy,
    val newCookingEnabled: Boolean,
    val savedPolicy: SavedRecipeServicePolicy,
    val savedCursors: SavedRecipeCursors,
    val newCopiesEnabled: Boolean,
    val databaseParallelism: Int,
    private val database: Database,
) {
    internal fun dataSource(): DataSource = database.dataSource("feedme-account-core")

    override fun toString() = "AccountCoreRuntimeConfig(<redacted>)"

    internal class Database(val host: String, val port: Int, val name: String, val user: String,
        val password: String, val sslMode: String, val sslRootCert: String?, val connectTimeout: Int,
        val loginTimeout: Int, val socketTimeout: Int) {
        fun dataSource(applicationName: String): DataSource = PGSimpleDataSource().also {
            it.setServerNames(arrayOf(host)); it.setPortNumbers(intArrayOf(port))
            it.setDatabaseName(name); it.setUser(user); it.setPassword(password)
            it.setSslMode(sslMode); it.setGssEncMode("disable")
            sslRootCert?.let(it::setSslRootCert)
            it.setConnectTimeout(connectTimeout); it.setLoginTimeout(loginTimeout)
            it.setSocketTimeout(socketTimeout); it.setApplicationName(applicationName)
        }
        override fun toString() = "AccountDatabase(<redacted>)"
    }

    companion object {
        private const val CONFIG = "FEEDME_ACCOUNT_RUNTIME_CONFIG"
        private const val PASSWORD = "FEEDME_ACCOUNT_DB_PASSWORD"
        private const val KEYS = "FEEDME_ACCOUNT_CURSOR_KEYS"
        private val allowed = setOf(CONFIG, PASSWORD, KEYS)
        private val conflicting = setOf("FEEDME_MINIMUM_APP_VERSION", "DATABASE_URL", "JDBC_DATABASE_URL",
            "JDBC_DATABASE_USERNAME", "JDBC_DATABASE_PASSWORD", "PGHOST", "PGHOSTADDR", "PGPORT", "PGDATABASE",
            "PGUSER", "PGPASSWORD", "PGSERVICE", "PGSERVICEFILE", "PGSSLMODE", "PGSSLROOTCERT", "PGOPTIONS", "PGPASSFILE")

        fun fromEnvironment(values: Map<String, String>): AccountCoreRuntimeConfig = try {
            require(values.keys.none { it.startsWith("FEEDME_ACCOUNT_") && it !in allowed ||
                it.startsWith("FEEDME_SERVER_") || it.startsWith("FEEDME_MIGRATION_") ||
                it.startsWith("FEEDME_PANTRY_") || it in conflicting })
            val root = document(requireNotNull(values[CONFIG]), 65_536)
            exact(JsonObject(root - "termsNotice"), "version", "environment", "listener", "database", "deployment", "accountRules", "reconnectionRules", "keyPolicy",
                "ingredientLimits", "searchMode", "preferencePolicy", "kitchenPolicy", "planningOperational",
                "planningPolicy", "cookingPolicy", "newCookingEnabled", "savedPolicy", "newCopiesEnabled", "databaseParallelism")
            require(number(root, "version") == 1L)
            val environment = text(root, "environment", 40).also { require(it in setOf("local", "staging", "production")) }
            val listener = listener(root.getValue("listener").jsonObject, environment)
            // Hosting platforms may supply PORT. It confirms the explicit container
            // setting, never overrides it, enables container mode or changes local binding.
            values["PORT"]?.let { require(listener.isContainer && it == listener.port.toString()) }
            val database = database(root.getValue("database").jsonObject, environment, requireNotNull(values[PASSWORD]))
            val deployment = deployment(root.getValue("deployment").jsonObject, database.name)
            val r = root.getValue("accountRules").jsonObject
            exact(r, "eligibilityPolicyVersion", "requiredTermsVersion", "acceptExactSubmittedTerms")
            val rules = AccountPendingProfileRules(text(r, "eligibilityPolicyVersion", 256),
                text(r, "requiredTermsVersion", 256), boolean(r, "acceptExactSubmittedTerms"))
            val reconnection = root.getValue("reconnectionRules").let { value ->
                if (value == JsonNull) null else value.jsonObject.let { rule ->
                    exact(rule, "revision", "consentVersion", "maximumAuthenticationAgeSeconds", "newReconnectionsEnabled")
                    AccountDeviceReconnectionRules(text(rule, "revision", 128), text(rule, "consentVersion", 256),
                        number(rule, "maximumAuthenticationAgeSeconds"), boolean(rule, "newReconnectionsEnabled"))
                }
            }
            // Missing/null keeps the dedicated Terms capability unavailable. No legal
            // notice, URL, acceptance, eligibility or new-write decision is defaulted.
            val notice = root["termsNotice"]?.takeUnless { it == JsonNull }?.jsonObject?.let { value ->
                exact(value, "termsVersion", "termsUrl", "privacyUrl")
                AccountTermsNotice(text(value, "termsVersion", 256), text(value, "termsUrl", 2048),
                    text(value, "privacyUrl", 2048)).also {
                    require(it.termsVersion == rules.requiredTermsVersion)
                }
            }
            val keyPolicy = keyPolicy(root.getValue("keyPolicy").jsonObject)
            require(keyPolicy.cacheSeconds <= deployment.verification.maximumJwksAgeSeconds)
            val i = root.getValue("ingredientLimits").jsonObject
            exact(i, "maxReleaseBytes", "maxIngredients", "cursorLifetimeSeconds")
            val ingredients = IngredientCatalogLimits(integer(i, "maxReleaseBytes", 1..1_048_576),
                integer(i, "maxIngredients", 1..1024), integer(i, "cursorLifetimeSeconds", 1..3600))
            val search = IngredientSearchMode.entries.single { it.wire == text(root, "searchMode", 64) }
            val preferences = preferencePolicy(root.getValue("preferencePolicy").jsonObject, environment)
            val kitchen = root.getValue("kitchenPolicy").jsonObject
            exact(kitchen, "maxResponseBytes", "cursorLifetimeSeconds")
            val kitchenPolicy = KitchenServicePolicy(integer(kitchen, "maxResponseBytes", 1..262144),
                integer(kitchen, "cursorLifetimeSeconds", 1..86400))
            val operational = root.getValue("planningOperational").jsonObject
            exact(operational, "revision", "newPlanningEnabled", "maxPlansPerUtcDay")
            val planningOperational = AccountPlanningPolicy(text(operational, "revision", 128),
                boolean(operational, "newPlanningEnabled"), integer(operational, "maxPlansPerUtcDay", 1..10000))
            val p = root.getValue("planningPolicy").jsonObject
            exact(p, "rankingVersion", "heatEnabled", "improveEnabled", "planRetentionSeconds", "cursorLifetimeSeconds")
            val planningPolicy = PlanningServicePolicy(text(p, "rankingVersion", 128), boolean(p, "heatEnabled"),
                boolean(p, "improveEnabled"), integer(p, "planRetentionSeconds", 60..2_592_000),
                integer(p, "cursorLifetimeSeconds", 1..600))
            val c = root.getValue("cookingPolicy").jsonObject
            exact(c, "maxResponseBytes", "sessionRetentionSeconds")
            val cookingPolicy = CookingServicePolicy(integer(c, "maxResponseBytes", 1..262144),
                integer(c, "sessionRetentionSeconds", 60..2_592_000))
            val s = root.getValue("savedPolicy").jsonObject
            exact(s, "maxResponseBytes", "cursorLifetimeSeconds", "defaultCollectionName")
            val savedPolicy = SavedRecipeServicePolicy(integer(s, "maxResponseBytes", 1..262144),
                integer(s, "cursorLifetimeSeconds", 1..86400), text(s, "defaultCollectionName", 120))
            val newCooking = boolean(root, "newCookingEnabled")
            val newCopies = boolean(root, "newCopiesEnabled")
            val parallelism = integer(root, "databaseParallelism", 1..8)
            val rings = document(requireNotNull(values[KEYS]), 32_768)
            exact(rings, "ingredient", "kitchen", "planning", "saved")
            val ingredientCursors = cursor(rings.getValue("ingredient").jsonObject, ::IngredientSearchCursor)
            val kitchenCursors = cursor(rings.getValue("kitchen").jsonObject, ::KitchenCursorCodec)
            val planningCursors = cursor(rings.getValue("planning").jsonObject, ::PlanningCursors)
            val savedCursors = cursor(rings.getValue("saved").jsonObject, ::SavedRecipeCursors)
            AccountCoreRuntimeConfig(listener, environment, deployment, rules, reconnection, notice, keyPolicy, ingredients, search, preferences,
                ingredientCursors, kitchenCursors, kitchenPolicy, planningOperational, planningPolicy, planningCursors,
                cookingPolicy, newCooking, savedPolicy, savedCursors, newCopies, parallelism, database)
        } catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw IllegalArgumentException("Account core runtime configuration unavailable") }

        internal fun listener(value: JsonObject, environment: String): ServerStartupConfig {
            exact(value, "mode", "host", "port", "minimumAppVersion", "maximumInFlightRequests")
            val mode = text(value, "mode", 16)
            val host = text(value, "host", 64)
            require(mode in setOf("local", "container"))
            require(if (mode == "local") host == "127.0.0.1" else host == "0.0.0.0" && environment != "local")
            val common = mapOf("FEEDME_SERVER_MODE" to mode,
                "FEEDME_MINIMUM_APP_VERSION" to text(value, "minimumAppVersion", 64),
                "FEEDME_SERVER_MAX_IN_FLIGHT_REQUESTS" to integer(value, "maximumInFlightRequests", 1..4096).toString())
            val port = integer(value, "port", 1024..65535).toString()
            return ServerStartupConfig.fromEnvironment(common + if (mode == "local")
                mapOf("FEEDME_SERVER_HOST" to host, "FEEDME_SERVER_PORT" to port) else mapOf("PORT" to port))
        }

        internal fun database(value: JsonObject, environment: String, password: String): Database {
            exact(value, "host", "port", "name", "user", "sslMode", "sslRootCert",
                "connectTimeoutSeconds", "loginTimeoutSeconds", "socketTimeoutSeconds")
            val host = text(value, "host", 253)
            val ssl = text(value, "sslMode", 16)
            val cert = if (value.getValue("sslRootCert") == JsonNull) null else text(value, "sslRootCert", 4096)
            if (environment == "local") require(host == "127.0.0.1" && ssl == "disable" && cert == null)
            else {
                val labels = host.split('.')
                require(host.length in 3..253 && labels.size >= 2 && labels.all {
                    it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
                } && labels.last().any { it in 'a'..'z' } && labels.last() !in setOf("localhost", "local", "localdomain"))
                require(ssl == "verify-full" && cert != null)
                val path = Path.of(cert)
                require(path.isAbsolute && path.fileName != null && path.normalize() == path)
            }
            val name = text(value, "name", 63).also { require(it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_-]{0,62}"))) }
            val user = text(value, "user", 63).also { require(it.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,62}"))) }
            require(password.length in 1..4096 && !password.isBlank() && password.none(Char::isISOControl))
            password.encodeToByteArray(throwOnInvalidSequence = true).fill(0)
            return Database(host, integer(value, "port", 1024..65535), name, user, password, ssl, cert,
                integer(value, "connectTimeoutSeconds", 1..10), integer(value, "loginTimeoutSeconds", 1..15),
                integer(value, "socketTimeoutSeconds", 1..60))
        }

        internal fun deployment(d: JsonObject, databaseName: String): SupabaseAuthorityDeployment {
            exact(d, "verification", "databaseName", "authSourceRevision", "migrationVersions", "reviewedAt", "validUntil",
                "timeboxSeconds", "inactivitySeconds", "singleSessionPerUser", "lowAssuranceTimeoutSeconds")
            require(text(d, "databaseName", 63) == databaseName)
            val v = d.getValue("verification").jsonObject
            exact(v, "issuer", "jwksEndpoint", "audience", "algorithms", "maximumTokenLifetimeSeconds",
                "allowedFutureClockSkewSeconds", "maximumJwksAgeSeconds")
            val algorithms = strings(v, "algorithms", 2, 16)
            require(algorithms.isNotEmpty() && algorithms.distinct().size == algorithms.size)
            val verification = SupabaseUserAccessConfiguration(text(v, "issuer", 2048), URI(text(v, "jwksEndpoint", 2048)),
                text(v, "audience", 64), algorithms.map { SupabaseSigningAlgorithm.valueOf(it) }.toSet(),
                number(v, "maximumTokenLifetimeSeconds"), number(v, "allowedFutureClockSkewSeconds"), number(v, "maximumJwksAgeSeconds"))
            return SupabaseAuthorityDeployment(verification, databaseName, text(d, "authSourceRevision", 128),
                strings(d, "migrationVersions", 512, 14), instant(d, "reviewedAt"), instant(d, "validUntil"),
                nullableNumber(d, "timeboxSeconds"), nullableNumber(d, "inactivitySeconds"), boolean(d, "singleSessionPerUser"),
                nullableNumber(d, "lowAssuranceTimeoutSeconds"))
        }

        private fun preferencePolicy(value: JsonObject, environment: String): ConfiguredIngredientPreferencePolicy {
            exact(value, "revision", "dietaryPatterns", "equipmentIds", "preferredTasteTags", "defaultEnergies",
                "minimumDefaultServings", "maximumDefaultServings", "consent")
            val consent = value.getValue("consent").jsonObject
            exact(consent, "currentVersion", "acceptNewConsent")
            fun choices(field: String): Set<String> = strings(value, field, 256, 128).also {
                require(it.distinct().size == it.size)
            }.toSet()
            fun decimal(field: String): BigDecimal = value.getValue(field).jsonPrimitive.let {
                require(!it.isString && it != JsonNull)
                BigDecimal(it.content).also { number -> require(number.precision() <= 32 && number.scale() in -32..32) }
            }
            return ConfiguredIngredientPreferencePolicy(environment, text(value, "revision", 128),
                choices("dietaryPatterns"), choices("equipmentIds"), choices("preferredTasteTags"), choices("defaultEnergies"),
                decimal("minimumDefaultServings"), decimal("maximumDefaultServings"),
                PreferenceConsentPolicy(text(consent, "currentVersion", 256), boolean(consent, "acceptNewConsent")))
        }

        private fun <T> cursor(root: JsonObject, create: (String, Map<String, ByteArray>) -> T): T {
            exact(root, "currentKeyId", "keys")
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
                return create(current, decoded)
            } finally { decoded.values.forEach { it.fill(0) } }
        }

        internal fun keyPolicy(k: JsonObject): SupabaseJwksHttpPolicy {
            exact(k, "connectTimeoutMillis", "socketTimeoutMillis", "totalTimeoutMillis", "cacheSeconds",
                "minimumFetchIntervalMillis", "maximumAdmittedCalls")
            return SupabaseJwksHttpPolicy(number(k, "connectTimeoutMillis"), number(k, "socketTimeoutMillis"),
                number(k, "totalTimeoutMillis"), number(k, "cacheSeconds"), number(k, "minimumFetchIntervalMillis"),
                integer(k, "maximumAdmittedCalls", 1..32))
        }

        internal fun document(raw: String, limit: Int): JsonObject = Json.parseToJsonElement(
            WireDocument.parse(raw, WireLimits(limit, 12, 32)).encodeUtf8().decodeToString()).jsonObject
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
                it.jsonPrimitive.let { s -> require(s.isString && s.content.length in 1..length &&
                    !s.content.isBlank() && s.content.none(Char::isISOControl)); s.content }
            }
        private fun instant(value: JsonObject, field: String): Instant = text(value, field, 40).let {
            Instant.parse(it).also { parsed -> require(parsed.toString() == it) }
        }
    }
}
