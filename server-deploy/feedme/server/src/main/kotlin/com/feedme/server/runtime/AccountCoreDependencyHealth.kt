package com.feedme.server.runtime

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.catalog.*
import com.feedme.server.config.AccountCoreRuntimeConfig
import com.feedme.server.db.PgTransactions
import com.feedme.server.identity.SupabasePostgresAuthority
import com.feedme.server.memory.requireAccountSavedMaterial
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Current core dependency checks, NOT whole-V1 release readiness, account eligibility,
 * recipe suitability for any person or permission to perform an operation. Successful HTTP
 * health remains explicitly "degraded" while non-core route groups are unconfigured.
 * No positive health cache, administrative writes, account enumeration or diagnostic details
 * are exposed. One admitted probe, bounded total coroutine time, owned DB dispatcher and
 * existing JDBC timeouts prevent a public health request from creating unbounded work.
 */
class AccountCoreDependencyHealth internal constructor(
    private val config: AccountCoreRuntimeConfig,
    private val transactions: PgTransactions,
    private val authority: SupabasePostgresAuthority,
    private val ingredients: IngredientCatalogStore,
    private val recipes: RecipeCatalogStore,
    private val rights: RecipeCopyRightsStore,
    private val verifier: SupabaseUserAccessVerifier,
    private val databaseDispatcher: CoroutineDispatcher,
) {
    private val admission = Semaphore(1)
    internal suspend fun available(): Boolean {
        if (!admission.tryAcquire()) return false
        try {
            if (!config.planningOperational.newPlanningEnabled || !config.newCookingEnabled || !config.newCopiesEnabled) return false
            return withTimeoutOrNull(10_000) {
                // Check real current content before any remote key request. An empty
                // database never starts fetching keys or reports healthy merely by binding.
                val content = runInterruptible(databaseDispatcher) { transactions.run(::checkContent) }
                if (!content || !verifier.keyMaterialAvailable()) false
                else runInterruptible(databaseDispatcher) {
                    // Provider review, recipes and copy grants may expire or change during
                    // external I/O. Recheck all current DB content and grant deadlines; no
                    // network I/O occurs under these locks and no prior positive is reused.
                    transactions.run(::checkContent)
                }
            } == true
        } catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { return false }
        finally { admission.release() }
    }

    private fun checkContent(c: Connection): Boolean {
        authority.checkCompatibility(c)
        config.planningOperational.checkCompatibility(c)
        ingredients.checkCompatibility(c)
        val selectable = ingredients.current(c).original.items.filter { it.reviewed && it.published && it.freeAccess }.map { it.id }.toSet()
        if (selectable.isEmpty()) return false
        val catalog = recipes.lockCurrent(c)
        // This format-one planning adapter has a finite, checked 128-entry catalog. At
        // least one current published free recipe must have searchable ingredient IDs and
        // a real current Saved-copy grant. A license string never supplies that grant.
        val candidates = catalog.original.entries.filter { entry ->
            entry.status == "published" && entry.recall == null && entry.review["freeCatalogEligible"] == JsonPrimitive(true) &&
                entry.recipe.getValue("ingredients").jsonArray.all {
                    UUID.fromString(it.jsonObject.getValue("ingredientId").jsonPrimitive.content) in selectable
                }
        }.sortedBy { it.recipeVersionId.toString() }
        for (entry in candidates) {
            try {
                val grant = rights.openNew(c, entry.recipeVersionId, guest = false)
                requireAccountSavedMaterial(grant.source.entry, entry.recipe, scaling = false)
                grant.revalidate(c)
                val now = c.createStatement().use { statement -> statement.executeQuery("SELECT clock_timestamp()").use {
                    check(it.next()); it.getObject(1, OffsetDateTime::class.java).toInstant()
                } }
                grant.checkAt(c, now)
                return true
            } catch (failure: RecipeCopyRightsFailure) {
                if (failure.code !in setOf(RecipeCopyRightsFailureCode.NOT_ALLOWED, RecipeCopyRightsFailureCode.EXPIRED,
                        RecipeCopyRightsFailureCode.RECALLED)) throw failure
            }
        }
        return false
    }
    override fun toString() = "AccountCoreDependencyHealth(<redacted>)"
}
