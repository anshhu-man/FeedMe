package com.feedme.server

import com.feedme.server.catalog.IngredientCatalogFailure
import com.feedme.server.catalog.IngredientCatalogFailureCode
import com.feedme.server.catalog.IngredientCatalogStore
import com.feedme.server.catalog.IngredientPublicationAuthority
import com.feedme.server.catalog.IngredientReleaseRequest
import com.feedme.server.config.AccountPantryRuntimeConfig
import com.feedme.server.db.PgTransactions
import com.feedme.server.runtime.AccountPantryRuntime
import java.sql.Connection
import java.time.Clock
import kotlin.system.exitProcess

/** Separate opt-in loopback launcher. The default Main/packaged server stays unconfigured.
 * All provider, policy and key material is explicitly operator supplied; this launcher
 * never creates accounts, grants eligibility, publishes content or runs migrations.
 */
fun main(args: Array<String>) {
    try {
        require(args.isEmpty())
        val config = AccountPantryRuntimeConfig.fromEnvironment(System.getenv())
        val database = config.dataSource()
        val deniedPublication = object : IngredientPublicationAuthority {
            override fun lockPublication(connection: Connection, environment: String, original: IngredientReleaseRequest): Unit = deny()
            override fun revalidatePublication(connection: Connection, environment: String, original: IngredientReleaseRequest): Unit = deny()
            private fun deny(): Nothing = throw IngredientCatalogFailure(IngredientCatalogFailureCode.AUTHORITY_DENIED)
        }
        val catalog = IngredientCatalogStore(config.environment, PgTransactions(database), deniedPublication,
            config.catalogLimits, config.searchMode)
        val runtime = AccountPantryRuntime.start(config.listener, config.environment, database,
            config.deployment, config.accountRules, config.keyPolicy, catalog, config.cursors,
            config.pantryPolicy, config.databaseParallelism, Clock.systemUTC())
        val shutdown = Thread({
            try { runtime.close() }
            catch (_: Exception) { System.err.println("FeedMe local pantry shutdown incomplete; retain original command identities.") }
        }, "feedme-pantry-shutdown")
        try {
            Runtime.getRuntime().addShutdownHook(shutdown)
            println("FeedMe local pantry listener started; only pantry routes are configured, production readiness is unavailable.")
            runtime.awaitTermination()
        } finally {
            try { runtime.close() }
            finally { runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) } }
        }
    } catch (failure: Exception) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        // Never print configuration, connection errors, provider claims or exception causes.
        System.err.println("FeedMe local pantry startup/shutdown failed; explicit valid local configuration is required.")
        exitProcess(1)
    }
}
