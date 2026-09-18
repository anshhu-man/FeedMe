package com.feedme.server.http

import com.feedme.server.auth.*
import com.feedme.server.catalog.*
import com.feedme.server.config.AccountCoreRuntimeConfig
import com.feedme.server.cooking.*
import com.feedme.server.db.*
import com.feedme.server.identity.*
import com.feedme.server.kitchen.*
import com.feedme.server.memory.*
import com.feedme.server.planning.*
import com.feedme.server.runtime.AccountCoreDependencyHealth
import com.feedme.server.runtime.AccountCoreRuntimeFailure
import com.feedme.server.runtime.rethrowAccountCoreFailure
import java.sql.Connection
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

/** One account/provider lifetime across the core cooking journey. Every store shares the
 * exact same transactions and AccountProfileStore; there is no per-route authority fallback.
 * Configuration never grants eligibility, replacement consent or editorial/copy rights.
 * Existing catalog readers have explicitly denying administrative writers. No migration,
 * publication, provider provisioning, environment lookup or listener is performed here.
 */
class ConfiguredSupabaseAccountCoreAssembly private constructor(
    val account: AccountHttpConfiguration,
    val preferences: AccountPreferencesHttpConfiguration,
    val pantry: AccountPantryHttpConfiguration,
    val planning: AccountPlanningHttpConfiguration,
    val cooking: AccountCookingHttpConfiguration,
    val saved: AccountSavedRecipeHttpConfiguration,
    val dependencyHealth: AccountCoreDependencyHealth,
    private val authority: SupabasePostgresAuthority,
    private val keys: HttpsSupabaseJwksSource,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { authority.close() } finally { keys.close() }
    }
    override fun toString() = "ConfiguredSupabaseAccountCoreAssembly(<redacted>)"

    companion object {
        fun open(config: AccountCoreRuntimeConfig, database: DataSource,
            databaseDispatcher: CoroutineDispatcher, clock: Clock): ConfiguredSupabaseAccountCoreAssembly {
            val transactions = PgTransactions(database)
            val authority = SupabasePostgresAuthority(config.deployment)
            var keys: HttpsSupabaseJwksSource? = null
            try {
                // Inspection is strictly non-mutating. A separate governed migration step
                // must have completed; never repair a live database during server startup.
                check(PlatformMigrations(database).inspect().status == PlatformMigrationInspectionStatus.CURRENT)
                val ingredients = IngredientCatalogStore(config.environment, transactions, ReadOnlyCatalogAuthority,
                    config.ingredientLimits, config.searchMode)
                val recipes = RecipeCatalogStore(config.environment, transactions, ReadOnlyCatalogAuthority)
                val journal = RecipeCatalogJournal(config.environment, transactions, ReadOnlyCatalogAuthority)
                val rights = RecipeCopyRightsStore(config.environment, transactions, journal, ReadOnlyCatalogAuthority)
                transactions.run { c ->
                    authority.checkCompatibility(c)
                    ingredients.checkCompatibility(c); recipes.checkCompatibility(c)
                    journal.checkCompatibility(c); rights.checkCompatibility(c)
                    config.planningOperational.checkCompatibility(c)
                }
                val keySource = HttpsSupabaseJwksSource.create(config.deployment.verification, config.keyPolicy, clock)
                keys = keySource
                val verifier = SupabaseUserAccessVerifier(config.deployment.verification, keySource, clock)
                val reconnection = config.reconnectionRules?.let { SupabaseAccountDeviceReconnection(config.environment, authority, it) }
                reconnection?.let { owner -> transactions.run(owner::checkCompatibility) }
                val accounts = AccountProfileStore(config.environment, transactions,
                    SupabaseAccountBootstrapPolicy(authority, config.accountRules), reconnection)
                val preferenceCatalog = PostgresPendingPreferencesCatalog(ingredients, config.ingredientCursors, config.preferencePolicy)
                val preferences = AccountPreferencesStore(config.environment, transactions, accounts,
                    preferenceCatalog, config.kitchenCursors, config.kitchenPolicy)
                val pantry = AccountPantryStore(config.environment, transactions, accounts,
                    ingredients, config.kitchenCursors, config.kitchenPolicy)
                val planning = AccountPlanningStore(config.environment, transactions, accounts, recipes,
                    config.planningOperational, config.planningPolicy, config.planningCursors)
                val cooking = AccountCookingStore(config.environment, transactions, accounts, planning,
                    config.cookingPolicy, config.newCookingEnabled)
                val saved = AccountSavedRecipeStore(config.environment, transactions, accounts, rights,
                    config.savedCursors, config.savedPolicy, config.newCopiesEnabled)
                val health = AccountCoreDependencyHealth(config, transactions, authority, ingredients,
                    recipes, rights, verifier, databaseDispatcher)
                return ConfiguredSupabaseAccountCoreAssembly(
                    AccountHttpConfiguration(accounts, verifier, databaseDispatcher),
                    AccountPreferencesHttpConfiguration(preferences, verifier, databaseDispatcher),
                    AccountPantryHttpConfiguration(pantry, verifier, databaseDispatcher),
                    AccountPlanningHttpConfiguration(planning, verifier, databaseDispatcher),
                    AccountCookingHttpConfiguration(cooking, verifier, databaseDispatcher),
                    AccountSavedRecipeHttpConfiguration(saved, verifier, databaseDispatcher), health, authority, keySource)
            } catch (failure: Throwable) {
                // Retire every owned resource even if construction or cleanup is interrupted.
                // The caller owns the DataSource and dispatcher; never close them here.
                val failures = mutableListOf(failure)
                for (close in listOf<() -> Unit>({ authority.close() }, { keys?.close() })) {
                    try { close() } catch (cleanup: Throwable) {
                        failures += cleanup
                    }
                }
                rethrowAccountCoreFailure(*failures.toTypedArray())
            }
        }
    }
}

/** HTTP request service is not a catalog administrator. All publication paths deny. */
private object ReadOnlyCatalogAuthority : IngredientPublicationAuthority, RecipePublicationAuthority,
    RecipeChangesetPublicationAuthority, RecipeCopyRightsPublicationAuthority {
    private fun deny(): Nothing = throw AccountCoreRuntimeFailure()
    override fun lockPublication(connection: Connection, environment: String, original: IngredientReleaseRequest): Unit = deny()
    override fun revalidatePublication(connection: Connection, environment: String, original: IngredientReleaseRequest): Unit = deny()
    override fun lockPublication(connection: Connection, environment: String, original: RecipeCatalogRelease): Unit = deny()
    override fun revalidatePublication(connection: Connection, environment: String, original: RecipeCatalogRelease): Unit = deny()
    override fun lockPublication(connection: Connection, environment: String, original: RecipeCatalogChangeset): Unit = deny()
    override fun revalidatePublication(connection: Connection, environment: String, original: RecipeCatalogChangeset): Unit = deny()
    override fun lockPublication(connection: Connection, environment: String, original: RecipeCopyGrant): Unit = deny()
    override fun revalidatePublication(connection: Connection, environment: String, original: RecipeCopyGrant): Unit = deny()
    override fun lockRevocation(connection: Connection, environment: String, original: RecipeCopyRevocation): Unit = deny()
    override fun revalidateRevocation(connection: Connection, environment: String, original: RecipeCopyRevocation): Unit = deny()
}
