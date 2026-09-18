package com.feedme.server.config

import com.feedme.server.auth.SupabaseJwksHttpPolicy
import com.feedme.server.identity.SupabaseAuthorityDeployment
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import javax.sql.DataSource

/** Closed operational configuration, not an account runtime or permission to launch.
 * No product-policy placeholders, cursor keys, content, user or consent authority. */
internal class DependencyHoldConfig private constructor(
    val listener: ServerStartupConfig,
    val deployment: SupabaseAuthorityDeployment,
    val keyPolicy: SupabaseJwksHttpPolicy,
    val databaseParallelism: Int,
    private val database: AccountCoreRuntimeConfig.Database,
) {
    fun dataSource(): DataSource = database.dataSource("feedme-dependency-hold")
    override fun toString() = "DependencyHoldConfig(<redacted>)"

    companion object {
        const val CONFIG = "FEEDME_ACCOUNT_DEPENDENCY_HOLD_CONFIG"
        private const val PASSWORD = "FEEDME_ACCOUNT_DB_PASSWORD"
        fun fromEnvironment(values: Map<String, String>): DependencyHoldConfig = try {
            require(values.keys.none { key ->
                key.startsWith("FEEDME_ACCOUNT_") && key !in setOf(CONFIG, PASSWORD) ||
                    key.startsWith("FEEDME_SERVER_") || key.startsWith("FEEDME_MIGRATION_") ||
                    key.startsWith("FEEDME_PANTRY_") || key == "FEEDME_MINIMUM_APP_VERSION" ||
                    key in setOf("DATABASE_URL", "JDBC_DATABASE_URL", "JDBC_DATABASE_USERNAME", "JDBC_DATABASE_PASSWORD") ||
                    key in setOf("PGHOST", "PGHOSTADDR", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSERVICE",
                        "PGSERVICEFILE", "PGSSLMODE", "PGSSLROOTCERT", "PGOPTIONS", "PGPASSFILE")
            })
            val root = AccountCoreRuntimeConfig.document(requireNotNull(values[CONFIG]), 32_768)
            require(root.keys == setOf("version", "mode", "environment", "listener", "database", "deployment", "keyPolicy", "databaseParallelism"))
            require(root["version"] == JsonPrimitive(1) && root["mode"] == JsonPrimitive("dependency-hold") &&
                root["environment"] == JsonPrimitive("production"))
            val listener = AccountCoreRuntimeConfig.listener(root.getValue("listener").jsonObject, "production")
            require(listener.isContainer)
            values["PORT"]?.let { require(it == listener.port.toString()) }
            val database = AccountCoreRuntimeConfig.database(root.getValue("database").jsonObject, "production",
                requireNotNull(values[PASSWORD]))
            require(database.user.matches(Regex("feedme_api(?:\\.[a-z0-9]{20})?")))
            val deployment = AccountCoreRuntimeConfig.deployment(root.getValue("deployment").jsonObject, database.name)
            val policy = AccountCoreRuntimeConfig.keyPolicy(root.getValue("keyPolicy").jsonObject)
            require(policy.cacheSeconds <= deployment.verification.maximumJwksAgeSeconds)
            val parallelism = root.getValue("databaseParallelism").jsonPrimitive
            require(!parallelism.isString && parallelism.content.matches(Regex("[12]")))
            DependencyHoldConfig(listener, deployment, policy, parallelism.content.toInt(), database)
        } catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw IllegalArgumentException("Dependency hold configuration unavailable") }
    }
}
