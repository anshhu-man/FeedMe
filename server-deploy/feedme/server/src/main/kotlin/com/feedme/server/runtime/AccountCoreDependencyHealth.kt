package com.feedme.server.runtime

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.catalog.*
import com.feedme.server.config.AccountCoreRuntimeConfig
import com.feedme.server.db.PgTransactions
import com.feedme.server.identity.SupabasePostgresAuthority
import com.feedme.server.identity.AccountDeletionStore
import com.feedme.server.identity.AccountDeletionServingCompatibility
import com.feedme.server.guest.GuestServingCompatibility
import com.feedme.server.memory.requireAccountSavedMaterial
import com.feedme.server.memory.MemoryServingCompatibility
import com.feedme.server.memory.CollectionServingCompatibility
import com.feedme.server.reuse.ReuseServingCompatibility
import com.feedme.server.social.AccountBlockCompatibility
import com.feedme.server.social.CircleServingCompatibility
import com.feedme.server.social.posts.PostReadServingCompatibility
import com.feedme.server.social.posts.PostAuthoringServingCompatibility
import com.feedme.server.social.posts.PostDeletionServingCompatibility
import com.feedme.server.social.reciperequests.RecipeRequestServingCompatibility
import com.feedme.server.identity.SessionServingCompatibility
import com.feedme.server.identity.NotificationServingCompatibility
import com.feedme.server.identity.NotificationInboxServingCompatibility
import com.feedme.server.social.conversations.ConversationServingCompatibility
import com.feedme.server.social.reports.ReportServingCompatibility
import com.feedme.server.media.MediaServingCompatibility
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
    private val recipes: RecipeCatalogJournal,
    private val rights: RecipeCopyRightsStore,
    private val verifier: SupabaseUserAccessVerifier,
    private val databaseDispatcher: CoroutineDispatcher,
    private val deletionStore: AccountDeletionStore? = null,
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
        deletionStore?.let { AccountDeletionServingCompatibility.check(c, it) }
        if (config.safetyPolicy != null) AccountBlockCompatibility.check(c)
        if (config.postReadPolicy != null) PostReadServingCompatibility.check(c)
        config.postRecipePolicy?.let { policy ->
            com.feedme.server.social.posts.PostRecipeServingCompatibility.check(c, policy.makeMineEnabled)
            if (policy.saveEnabled) com.feedme.server.memory.PostRecipeSaveServingCompatibility.check(c)
        }
        config.circlePolicy?.let { CircleServingCompatibility.check(c, it.circleCreationEnabled, it.invitationCreationEnabled) }
        if (config.reportPolicy != null) ReportServingCompatibility.check(c)
        if (config.media != null) MediaServingCompatibility.check(c)
        if (config.memoryPolicy != null) MemoryServingCompatibility.check(c)
        if (config.makeAgainEnabled) com.feedme.server.memory.AccountMakeAgainServingCompatibility.check(c)
        if (config.collectionMutationsEnabled) CollectionServingCompatibility.check(c)
        if (config.reusePolicy != null) ReuseServingCompatibility.check(c)
        if (config.postAuthoring != null) PostAuthoringServingCompatibility.check(c)
        if (config.conversationPolicy != null) ConversationServingCompatibility.check(c)
        if (config.postDeletionPolicy != null) PostDeletionServingCompatibility.check(c)
        if (config.postPlacementPolicy != null) com.feedme.server.social.posts.PostPlacementServingCompatibility.check(c)
        if (config.postReactionPolicy != null) com.feedme.server.social.posts.PostReactionServingCompatibility.check(c)
        if (config.recipeRequestPolicy != null) RecipeRequestServingCompatibility.check(c)
        if (config.sessionPolicy != null) SessionServingCompatibility.check(c)
        if (config.notificationPolicy != null) NotificationServingCompatibility.check(c)
        if (config.notificationInboxPolicy != null) NotificationInboxServingCompatibility.check(c)
        if (config.guest != null) GuestServingCompatibility.check(c,
            config.guest.planning != null, config.guest.kitchenEnabled,
            config.guest.cookingEnabled, config.guest.savedEnabled,
            config.guest.feedbackEnabled)
        if (config.reactionNotificationPolicy != null) com.feedme.server.identity.AccountReactionNotificationCompatibility.check(c)
        if (config.exportPolicy != null) com.feedme.server.export.AccountExportServingCompatibility.check(c)
        if (config.staffPolicy != null) com.feedme.server.staff.SupabaseStaffServingCompatibility.check(c)
        if (config.staffPolicy?.catalogDraftsEnabled == true) com.feedme.server.staff.SupabaseStaffRecipeServingCompatibility.check(c)
        if (config.staffPolicy?.catalogPublicationEnabled == true) com.feedme.server.staff.SupabaseStaffRecipeQualificationServingCompatibility.check(c)
        authority.checkCompatibility(c)
        config.planningOperational.checkCompatibility(c)
        ingredients.checkCompatibility(c)
        val selectable = ingredients.current(c).original.items.filter { it.reviewed && it.published && it.freeAccess }.map { it.id }.toSet()
        if (selectable.isEmpty()) return false
        val catalog = recipes.openView(c)
        // Use the same complete, bounded projection as ordinary account planning, including
        // unchanged history beneath a format-two delta. An unsupported >128-version history
        // must not look healthy when that planner cannot run. A license string supplies no
        // copy permission: a current real grant and the final clock fence remain mandatory.
        val candidates = catalog.planningCatalogDocument().getValue("candidates").jsonArray.map { it.jsonObject }
            .filter { candidate ->
                val recipe = candidate.getValue("recipe").jsonObject
                recipe["reviewStatus"] == JsonPrimitive("published") &&
                    candidate.getValue("review").jsonObject["freeCatalogEligible"] == JsonPrimitive(true) &&
                    recipe.getValue("ingredients").jsonArray.all {
                    UUID.fromString(it.jsonObject.getValue("ingredientId").jsonPrimitive.content) in selectable
                }
            }.map { it.getValue("recipe").jsonObject }
        for (recipe in candidates) {
            try {
                val grant = rights.openNew(c, UUID.fromString(recipe.getValue("id").jsonPrimitive.content), guest = false)
                requireAccountSavedMaterial(grant.source.entry, recipe, scaling = false)
                grant.revalidate(c)
                catalog.checkCurrent()
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
