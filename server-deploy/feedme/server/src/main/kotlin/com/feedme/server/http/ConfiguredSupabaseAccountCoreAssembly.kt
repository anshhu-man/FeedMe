package com.feedme.server.http

import com.feedme.server.auth.*
import com.feedme.server.ai.*
import com.feedme.server.catalog.*
import com.feedme.server.config.AccountCoreRuntimeConfig
import com.feedme.server.cooking.*
import com.feedme.server.db.*
import com.feedme.server.export.*
import com.feedme.server.guest.*
import com.feedme.server.identity.*
import com.feedme.server.kitchen.*
import com.feedme.server.memory.*
import com.feedme.server.media.*
import com.feedme.server.media.supabase.SupabaseStorageHttp
import com.feedme.server.media.supabase.SupabaseDerivativeHttp
import com.feedme.server.media.access.AccountMediaAccessStore
import com.feedme.server.planning.*
import com.feedme.server.reuse.*
import com.feedme.server.social.AccountBlockStore
import com.feedme.server.social.AccountBlockCompatibility
import com.feedme.server.social.AccountSocialIdentityPolicy
import com.feedme.server.social.CirclesStore
import com.feedme.server.social.CircleServingCompatibility
import com.feedme.server.social.SocialBlockRelationships
import com.feedme.server.social.posts.AccountPostReadStore
import com.feedme.server.social.posts.AccountPostRecipeSourceStore
import com.feedme.server.social.posts.PostRecipeServingCompatibility
import com.feedme.server.social.posts.AccountRemixReadStore
import com.feedme.server.social.posts.PostgresPostReadContentAuthority
import com.feedme.server.social.posts.PostReadServingCompatibility
import com.feedme.server.social.posts.AccountPostContentAuthority
import com.feedme.server.social.posts.PostPublicationStore
import com.feedme.server.social.posts.PostReadMediaSafety
import com.feedme.server.social.posts.PostAuthoringServingCompatibility
import com.feedme.server.social.posts.SupabaseAccountPostHttpVerifier
import com.feedme.server.social.posts.AccountPostDeletionStore
import com.feedme.server.social.posts.PostDeletionServingCompatibility
import com.feedme.server.social.reciperequests.AccountRecipeRequestStore
import com.feedme.server.social.reciperequests.RecipeRequestEligibility
import com.feedme.server.social.reciperequests.RecipeRequestServingCompatibility
import com.feedme.server.social.drafts.PostDraftStore
import com.feedme.server.social.conversations.AccountConversationStore
import com.feedme.server.social.conversations.ConversationServingCompatibility
import com.feedme.server.social.reports.AccountReportStore
import com.feedme.server.social.reports.PostgresReportTargets
import com.feedme.server.social.reports.ReportServingCompatibility
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
    val guest: GuestHttpConfiguration?,
    val blocks: AccountBlockHttpConfiguration?,
    val mealIntent: AccountMealIntentHttpConfiguration?,
    val deletion: AccountDeletionHttpConfiguration?,
    val postReads: AccountPostReadHttpConfiguration?,
    val social: SocialHttpConfiguration?,
    val reports: AccountReportHttpConfiguration?,
    val media: MediaHttpConfiguration?,
    val memory: AccountMemoryHttpConfiguration?,
    val reuse: AccountReuseHttpConfiguration?,
    val postDrafts: PostDraftHttpConfiguration?,
    val postPublication: PostPublicationHttpConfiguration?,
    val conversations: AccountConversationHttpConfiguration?,
    val postDeletion: AccountPostDeletionHttpConfiguration?,
    val postPlacement: AccountPostPlacementHttpConfiguration?,
    val postReactions: AccountPostReactionHttpConfiguration?,
    val recipeRequests: AccountRecipeRequestHttpConfiguration?,
    val sessions: AccountSessionHttpConfiguration?,
    val notifications: AccountNotificationHttpConfiguration?,
    val notificationInbox: AccountNotificationInboxHttpConfiguration?,
    val mediaAccess: AccountMediaAccessHttpConfiguration?,
    val remixes: AccountRemixReadHttpConfiguration?,
    val postRecipes: AccountPostRecipeSourceHttpConfiguration?,
    val exports: AccountExportHttpConfiguration?,
    val exportDelivery: AccountExportDeliveryHttpConfiguration?,
    val staff: SupabaseStaffHttpConfiguration?,
    internal val exportWorker: AccountExportWorker?,
    val dependencyHealth: AccountCoreDependencyHealth,
    private val authority: SupabasePostgresAuthority,
    private val keys: HttpsSupabaseJwksSource,
    private val guestReplayCipher: GuestReplayCipher?,
    private val model: CloudflareJsonModel?,
    private val mediaStorage: SupabaseStorageHttp?,
    private val mediaAccessOwner: AccountMediaAccessStore?,
    private val exportDownloads: AccountExportDownloadService?,
    private val exportObjects: SupabaseExportObjects?,
    private val exportEncryption: AccountExportEncryption?,
    internal val reactionNotificationStore: AccountReactionNotificationStore?,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { exportDownloads?.close() } finally { try { exportWorker?.close() } finally {
        try { exportObjects?.close() } finally { try { exportEncryption?.close() } finally {
        try { mediaAccessOwner?.close() } finally { try { mediaStorage?.close() } finally {
            try { guestReplayCipher?.close() } finally { try { model?.close() } finally {
                try { authority.close() } finally { keys.close() }
            } }
        } } } } } }
    }
    override fun toString() = "ConfiguredSupabaseAccountCoreAssembly(<redacted>)"

    companion object {
        fun open(config: AccountCoreRuntimeConfig, database: DataSource,
            databaseDispatcher: CoroutineDispatcher, clock: Clock): ConfiguredSupabaseAccountCoreAssembly =
            openWithKeySource(config, database, databaseDispatcher, clock, HttpsSupabaseJwksSource::create)

        /** Internal transport-fixture seam. Public runtime configuration cannot select it;
         * production always uses the owned HTTPS source above. No verifier/authority/store
         * is substituted: the same composition and resource ownership run below. */
        internal fun openWithKeySource(config: AccountCoreRuntimeConfig, database: DataSource,
            databaseDispatcher: CoroutineDispatcher, clock: Clock,
            keySourceFactory: (SupabaseUserAccessConfiguration, SupabaseJwksHttpPolicy, Clock) -> HttpsSupabaseJwksSource,
        ): ConfiguredSupabaseAccountCoreAssembly {
            val transactions = PgTransactions(database)
            val authority = SupabasePostgresAuthority(config.deployment)
            var keys: HttpsSupabaseJwksSource? = null
            var guestReplayCipher: GuestReplayCipher? = null
            var model: CloudflareJsonModel? = null
            var mediaStorage: SupabaseStorageHttp? = null
            var mediaAccessOwner: AccountMediaAccessStore? = null
            var unownedDerivativeObjects: SupabaseDerivativeHttp? = null
            var exportDownloads: AccountExportDownloadService? = null
            var exportObjects: SupabaseExportObjects? = null
            var exportEncryption: AccountExportEncryption? = null
            var exportWorker: AccountExportWorker? = null
            try {
                // Inspection is strictly non-mutating. A separate governed migration step
                // must have completed; never repair a live database during server startup.
                check(PlatformMigrations(database).inspect().status == PlatformMigrationInspectionStatus.CURRENT)
                val ingredients = IngredientCatalogStore(config.environment, transactions, ReadOnlyCatalogAuthority,
                    config.ingredientLimits, config.searchMode)
                val recipes = RecipeCatalogStore(config.environment, transactions, ReadOnlyCatalogAuthority)
                val journal = RecipeCatalogJournal(config.environment, transactions, ReadOnlyCatalogAuthority)
                val substitutions = RecipeSubstitutionJournal(config.environment, transactions, journal, ReadOnlyCatalogAuthority)
                val rights = RecipeCopyRightsStore(config.environment, transactions, journal, ReadOnlyCatalogAuthority)
                val guest = config.guest?.let { guestConfig ->
                    val cipher = guestConfig.replayCipher()
                    guestReplayCipher = cipher
                    val sessions = GuestSessionStore(config.environment, transactions, guestConfig.sessions, cipher,
                        ConfiguredGuestSessionAuthority(config.environment, guestConfig.sessions,
                            guestConfig.newSessionsEnabled, guestConfig.bootstrapReplayEnabled))
                    val search = GuestIngredientSearchStore(sessions,
                        PostgresIngredientSearch(ingredients, config.ingredientCursors),
                        guestConfig.maxSearchResponseBytes)
                    val kitchen = if (guestConfig.kitchenEnabled) GuestKitchenStore(
                        config.environment, transactions, sessions, ingredients,
                        config.preferencePolicy, config.kitchenCursors, config.kitchenPolicy) else null
                    val preparations = guestConfig.planning?.let { policy ->
                        GuestPlanningStore(config.environment, transactions, sessions, journal, policy)
                    }
                    val plans = preparations?.let {
                        GuestPlansStore(config.environment, transactions, it, config.planningCursors)
                    }
                    val cooking = if (guestConfig.cookingEnabled) GuestCookingStore(
                        config.environment, transactions, checkNotNull(preparations), config.cookingPolicy) else null
                    val saved = if (guestConfig.savedEnabled) GuestSavedRecipeStore(
                        config.environment, transactions, checkNotNull(preparations), rights,
                        config.savedCursors, config.savedPolicy) else null
                    val feedback = if (guestConfig.feedbackEnabled) GuestFeedbackStore(
                        config.environment, transactions, sessions, journal, ingredients,
                        checkNotNull(guestConfig.planning),
                        FeedbackServicePolicy(checkNotNull(config.memoryPolicy).maxResponseBytes)) else null
                    GuestHttpConfiguration(sessions, search, databaseDispatcher,
                        kitchen = kitchen, plans = plans, cooking = cooking, saved = saved,
                        feedback = feedback)
                }
                transactions.run { c ->
                    authority.checkCompatibility(c)
                    ingredients.checkCompatibility(c); recipes.checkCompatibility(c)
                    journal.checkCompatibility(c); rights.checkCompatibility(c); substitutions.checkCompatibility(c)
                    config.planningOperational.checkCompatibility(c)
                    if (config.guest != null) GuestServingCompatibility.check(c,
                        config.guest.planning != null, config.guest.kitchenEnabled,
                        config.guest.cookingEnabled, config.guest.savedEnabled,
                        config.guest.feedbackEnabled)
                    if (config.safetyPolicy != null) AccountBlockCompatibility.check(c)
                    if (config.postReadPolicy != null) PostReadServingCompatibility.check(c)
                    config.circlePolicy?.let { CircleServingCompatibility.check(c, it.circleCreationEnabled, it.invitationCreationEnabled) }
                    if (config.reportPolicy != null) ReportServingCompatibility.check(c)
                    if (config.media != null) MediaServingCompatibility.check(c)
                    if (config.memoryPolicy != null) MemoryServingCompatibility.check(c)
                    if (config.makeAgainEnabled) AccountMakeAgainServingCompatibility.check(c)
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
                    if (config.reactionNotificationPolicy != null) AccountReactionNotificationCompatibility.check(c)
                    if (config.exportPolicy != null) AccountExportServingCompatibility.check(c)
                    if (config.staffPolicy != null) com.feedme.server.staff.SupabaseStaffServingCompatibility.check(c)
                    if (config.staffPolicy?.catalogDraftsEnabled == true) com.feedme.server.staff.SupabaseStaffRecipeServingCompatibility.check(c)
                    if (config.staffPolicy?.catalogPublicationEnabled == true) com.feedme.server.staff.SupabaseStaffRecipeQualificationServingCompatibility.check(c)
                    if (config.staffPolicy?.moderationEnabled == true) {
                        com.feedme.server.staff.SupabaseStaffModerationServingCompatibility.check(c)
                        com.feedme.server.staff.StaffModerationServingCompatibility.check(c)
                    }
                    config.postRecipePolicy?.let {
                        PostRecipeServingCompatibility.check(c, it.makeMineEnabled)
                        if (it.saveEnabled) PostRecipeSaveServingCompatibility.check(c)
                    }
                    if (config.mealIntent != null) c.createStatement().use { statement ->
                        // Compile/privilege probe only: no consent records, mutation or provider
                        // request. Older restricted grants must not appear AI-compatible.
                        statement.executeQuery("SELECT environment,user_id,submitted_terms_version,accepted_terms_version," +
                            "eligibility_declaration,eligibility_state,eligibility_policy_version " +
                            "FROM identity.bootstrap_consents WHERE false").use { check(!it.next()) }
                    }
                }
                val keySource = keySourceFactory(config.deployment.verification, config.keyPolicy, clock)
                keys = keySource
                val verifier = SupabaseUserAccessVerifier(config.deployment.verification, keySource, clock)
                val reconnection = config.reconnectionRules?.let { SupabaseAccountDeviceReconnection(config.environment, authority, it) }
                reconnection?.let { owner -> transactions.run(owner::checkCompatibility) }
                val accounts = AccountProfileStore(config.environment, transactions,
                    SupabaseAccountBootstrapPolicy(authority, config.accountRules), reconnection, config.termsNotice)
                val sessions = config.sessionPolicy?.let { policy ->
                    AccountSessionHttpConfiguration(AccountSessionStore(config.environment, transactions, accounts,
                        policy, checkNotNull(config.sessionCursors)), verifier, databaseDispatcher)
                }
                val notifications = config.notificationPolicy?.let { policy ->
                    AccountNotificationHttpConfiguration(AccountNotificationStore(config.environment, transactions, accounts,
                        policy), verifier, databaseDispatcher)
                }
                val exports = config.exportPolicy?.let { policy ->
                    val storage = checkNotNull(config.exportStorage)
                    val encrypted = storage.encryption()
                    exportEncryption = encrypted
                    val objects = SupabaseExportObjects.create(storage.storage)
                    exportObjects = objects
                    val store = AccountExportStore(config.environment, transactions, accounts, authority, policy,
                        downloads = { checkNotNull(exportDownloads) })
                    val delivery = AccountExportDownloadService(store, objects, encrypted, storage.publicOrigin,
                        clock, policy.downloadSeconds, storage.maxCapabilities)
                    exportDownloads = delivery
                    // Explicit owner only: construction and listener startup do not claim,
                    // snapshot, upload, expire, or start a background export loop.
                    exportWorker = AccountExportWorker(store, AccountExportInventory(storage.maxRowsPerSection,
                        storage.maxPlaintextBytes, recipeRights = rights), objects, encrypted, clock)
                    AccountExportHttpConfiguration(store, verifier, databaseDispatcher)
                }
                val exportDelivery = exportDownloads?.let { AccountExportDeliveryHttpConfiguration(it, databaseDispatcher) }
                val staff = config.staffPolicy?.let { policy ->
                    val admission = com.feedme.server.staff.SupabaseStaffAdmissionStore(config.environment, transactions, authority, policy)
                    SupabaseStaffHttpConfiguration(admission, verifier, databaseDispatcher,
                        if (policy.catalogDraftsEnabled) com.feedme.server.staff.SupabaseStaffRecipeDraftStore(
                            config.environment, transactions, admission,
                            reviews = com.feedme.server.staff.SupabaseStaffRecipeReviewWorkflow(
                                config.environment, transactions, ingredients, journal),
                            publications = com.feedme.server.staff.SupabaseStaffRecipePublicationWorkflow(
                                config.environment, transactions, admission, ingredients, journal)) else null,
                        if (policy.moderationEnabled) com.feedme.server.staff.SupabaseStaffModerationStore(
                            config.environment, transactions, admission,
                            // The free V1 has no entitlement processor or incident registry.
                            // Turning either on must first supply a measured health source.
                            com.feedme.server.staff.StaffModerationPolicy(262144, 300,
                                entitlementPipelineEnabled = false, incidentRegistryEnabled = false),
                            checkNotNull(config.staffModerationCursors)) else null)
                }
                val deletionStore = config.deletionRules?.let { rules ->
                    AccountDeletionStore(config.environment, transactions, authority, rules)
                }
                val deletion = deletionStore?.let { store ->
                    transactions.run { connection -> AccountDeletionServingCompatibility.check(connection, store) }
                    AccountDeletionHttpConfiguration(AccountDeletionService(store,
                        AccountDeletionProofVerifier(verifier), databaseDispatcher), verifier)
                }
                val preferenceCatalog = PostgresPendingPreferencesCatalog(ingredients, config.ingredientCursors, config.preferencePolicy)
                val preferences = AccountPreferencesStore(config.environment, transactions, accounts,
                    preferenceCatalog, config.kitchenCursors, config.kitchenPolicy)
                val pantry = AccountPantryStore(config.environment, transactions, accounts,
                    ingredients, config.kitchenCursors, config.kitchenPolicy)
                var postRecipeSourceStore: AccountPostRecipeSourceStore? = null
                var postRecipeCopies: AccountPostRecipeCopyAuthority? = null
                val memoryStore = config.memoryPolicy?.let { policy ->
                    AccountMemoryStore(config.environment, transactions, accounts, journal, ingredients,
                        checkNotNull(config.memoryCursors), policy)
                }
                val planning = AccountPlanningStore(config.environment, transactions, accounts, recipes,
                    config.planningOperational, config.planningPolicy, config.planningCursors, rights,
                    journal = journal, substitutions = substitutions,
                    postRecipeSources = if (config.postRecipePolicy?.makeMineEnabled == true) ({ checkNotNull(postRecipeSourceStore) }) else null,
                    memoryRanking = if (config.memoryRankingEnabled) checkNotNull(memoryStore) else null)
                val cooking = AccountCookingStore(config.environment, transactions, accounts, planning,
                    config.cookingPolicy, config.newCookingEnabled)
                val memory = config.memoryPolicy?.let { policy ->
                    AccountMemoryHttpConfiguration(
                        AccountFeedbackStore(config.environment, transactions, accounts, journal, ingredients,
                            planning, FeedbackServicePolicy(policy.maxResponseBytes)),
                        checkNotNull(memoryStore), verifier, databaseDispatcher)
                }
                val reuse = config.reusePolicy?.let { policy ->
                    AccountReuseHttpConfiguration(AccountReuseStore(config.environment, transactions, accounts,
                        journal, ingredients, planning, config.planningPolicy, policy,
                        checkNotNull(config.reuseCursors)), verifier, databaseDispatcher)
                }
                val blocks = config.safetyPolicy?.let { policy ->
                    AccountBlockHttpConfiguration(AccountBlockStore(config.environment, transactions, accounts,
                        checkNotNull(config.blockCursors), policy), verifier, databaseDispatcher)
                }
                val social = config.circlePolicy?.let { policy ->
                    val identity = AccountSocialIdentityPolicy(config.environment, accounts, SocialBlockRelationships(),
                        config.accountRules.eligibilityPolicyVersion, config.accountRules.requiredTermsVersion,
                        policy.circleCreationEnabled, policy.invitationCreationEnabled)
                    SocialHttpConfiguration(config.environment, CirclesStore(config.environment, transactions, identity,
                        policy.capabilities, policy.launch), SupabaseAccountSocialHttpVerifier(verifier, transactions,
                        identity, databaseDispatcher), databaseDispatcher)
                }
                val reports = config.reportPolicy?.let { policy ->
                    AccountReportHttpConfiguration(AccountReportStore(config.environment, transactions, accounts,
                        PostgresReportTargets(config.environment, recipeMessageCatalog = journal), policy), verifier, databaseDispatcher)
                }
                val mealIntent = config.mealIntent?.let { policy ->
                    val hosted = CloudflareJsonModel.create(policy.modelConfiguration)
                    model = hosted
                    val audience = PostgresAdultMealIntentAudience(config.environment, transactions, accounts, databaseDispatcher)
                    val source = PostgresAccountMealIntentSource(config.environment, transactions, accounts,
                        ingredients, databaseDispatcher, requireAdultConsent = true)
                    AccountMealIntentHttpConfiguration(AccountMealIntentService(true, audience, source,
                        MealIntentInterpreter(hosted)), verifier, clock, policy.minimumStartIntervalMillis)
                }
                var mediaStore: MediaStore? = null
                val media = config.media?.let { policy ->
                    val owner = AccountMediaAuthority(config.environment, accounts, policy.admission, policy.service.maxSourceBytes)
                    val storage = SupabaseStorageHttp.create(policy.storage, clock)
                    mediaStorage = storage
                    // This runtime explicitly supports Supabase only. Legacy source grants
                    // and verifiers cannot silently select another provider or accept bytes.
                    val store = MediaStore(config.environment, transactions, owner,
                        MediaUploadCapabilities { throw MediaFailure(MediaFailureCode.NOT_CONFIGURED) },
                        MediaObjectVerifier { throw MediaFailure(MediaFailureCode.NOT_CONFIGURED) },
                        policy.service, storage)
                    mediaStore = store
                    MediaHttpConfiguration(config.environment, store,
                        SupabaseAccountMediaHttpVerifier(verifier, transactions, owner, databaseDispatcher), databaseDispatcher)
                }
                val postIdentity = config.postReadPolicy?.let {
                    AccountSocialIdentityPolicy(config.environment, accounts, SocialBlockRelationships(),
                        config.accountRules.eligibilityPolicyVersion, config.accountRules.requiredTermsVersion,
                        circleCreationEnabled = false, invitationCreationEnabled = false)
                }
                val postAuthority = config.postAuthoring?.let { policy ->
                    AccountPostContentAuthority(config.environment, accounts, checkNotNull(postIdentity), journal,
                        planning, policy.admission)
                }
                val postVerifier = postAuthority?.let {
                    SupabaseAccountPostHttpVerifier(verifier, transactions, it, databaseDispatcher)
                }
                val postDrafts = config.postAuthoring?.let { policy ->
                    PostDraftHttpConfiguration(config.environment, PostDraftStore(config.environment, transactions,
                        checkNotNull(postAuthority).drafts, checkNotNull(mediaStore), policy.drafts, policy.cursors),
                        checkNotNull(postVerifier), databaseDispatcher)
                }
                val postPublication = config.postAuthoring?.let { policy ->
                    PostPublicationHttpConfiguration(config.environment, PostPublicationStore(config.environment, transactions,
                        checkNotNull(postAuthority).publication, checkNotNull(mediaStore), policy.publication),
                        checkNotNull(postVerifier), databaseDispatcher)
                }
                val conversationIdentity = config.conversationPolicy?.let {
                    AccountSocialIdentityPolicy(config.environment, accounts, SocialBlockRelationships(),
                        config.accountRules.eligibilityPolicyVersion, config.accountRules.requiredTermsVersion,
                        circleCreationEnabled = false, invitationCreationEnabled = false)
                }
                var reactionNotificationStore: AccountReactionNotificationStore? = null
                val notificationInboxStore = config.notificationInboxPolicy?.let { policy ->
                    checkNotNull(config.notificationPolicy)
                    AccountNotificationInboxStore(config.environment, transactions, accounts,
                        checkNotNull(conversationIdentity), checkNotNull(config.notificationInboxCursors), policy,
                        reactions = if (config.reactionNotificationPolicy != null) ({ reactionNotificationStore }) else null)
                }
                val notificationInbox = notificationInboxStore?.let {
                    AccountNotificationInboxHttpConfiguration(it, verifier, databaseDispatcher)
                }
                val conversationStore = config.conversationPolicy?.let { policy ->
                    AccountConversationStore(config.environment, transactions, accounts,
                        checkNotNull(conversationIdentity), checkNotNull(config.conversationCursors), policy,
                        recipeMessageCatalog = journal.takeIf { config.recipeRequestPolicy != null },
                        notificationInbox = notificationInboxStore)
                }
                val conversations = conversationStore?.let { AccountConversationHttpConfiguration(it, verifier, databaseDispatcher) }
                val postDeletionStore = config.postDeletionPolicy?.let { policy ->
                    AccountPostDeletionStore(config.environment, transactions, accounts, checkNotNull(mediaStore), policy)
                }
                val postDeletion = postDeletionStore?.let { AccountPostDeletionHttpConfiguration(it, verifier, databaseDispatcher) }
                val recipeRequestEligibility = config.recipeRequestPolicy?.let { policy ->
                    RecipeRequestEligibility(config.environment, checkNotNull(conversationStore), policy)
                }
                var postPlacementStore: com.feedme.server.social.posts.AccountPostPlacementStore? = null
                var postReactionStore: com.feedme.server.social.posts.AccountPostReactionStore? = null
                val postReadStore = config.postReadPolicy?.let { policy ->
                    val identity = checkNotNull(postIdentity)
                    AccountPostReadStore(config.environment, transactions, identity,
                        CirclesStore.forPostReads(config.environment, transactions, identity), checkNotNull(config.postFeedCursors),
                        PostgresPostReadContentAuthority(config.environment, postAuthority,
                            config.postAuthoring?.admission?.mediaSafety?.let { PostReadMediaSafety(config.environment, it) },
                            conversationStore, postDeletionStore, recipeRequestEligibility,
                            postRecipes = if (config.postRecipePolicy?.makeMineEnabled == true) ({ postRecipeSourceStore }) else null,
                            postCopies = if (config.postRecipePolicy?.saveEnabled == true) ({ postRecipeCopies }) else null,
                            placement = if (config.postPlacementPolicy != null) ({ postPlacementStore }) else null,
                            reactions = if (config.postReactionPolicy != null) ({ postReactionStore }) else null), policy)
                }
                postPlacementStore = config.postPlacementPolicy?.let { policy ->
                    com.feedme.server.social.posts.AccountPostPlacementStore(config.environment, transactions,
                        checkNotNull(postIdentity), checkNotNull(postReadStore), policy)
                }
                val postPlacement = postPlacementStore?.let { AccountPostPlacementHttpConfiguration(it, verifier, databaseDispatcher) }
                postReactionStore = config.postReactionPolicy?.let { policy ->
                    com.feedme.server.social.posts.AccountPostReactionStore(config.environment, transactions, accounts,
                        checkNotNull(postIdentity), checkNotNull(postReadStore), policy)
                }
                val postReactions = postReactionStore?.let { AccountPostReactionHttpConfiguration(it, verifier, databaseDispatcher) }
                reactionNotificationStore = config.reactionNotificationPolicy?.let { policy ->
                    AccountReactionNotificationStore(config.environment, transactions, checkNotNull(postIdentity),
                        checkNotNull(postReadStore), policy) { c, accountId ->
                        // Background work has no invented user session. Resolve the actual
                        // immutable provider identity and enforce current provider review.
                        c.prepareStatement("SELECT provider_issuer,provider_subject FROM identity.users " +
                            "WHERE environment=? AND id=? FOR SHARE NOWAIT").use { s ->
                            s.setString(1, config.environment); s.setObject(2, accountId)
                            s.executeQuery().use { r ->
                                check(r.next())
                                val issuer = r.getString(1)
                                val subject = r.getObject(2, java.util.UUID::class.java)
                                check(!r.next())
                                authority.lockAcceptedWorkAccount(c, issuer, subject)
                            }
                        }
                    }
                }
                val postReads = postReadStore?.let { AccountPostReadHttpConfiguration(it, verifier, databaseDispatcher) }
                val postRecipes = config.postRecipePolicy?.let { policy ->
                    val source = AccountPostRecipeSourceStore(config.environment, transactions, accounts, checkNotNull(postReadStore), journal, authority)
                    postRecipeSourceStore = source
                    if (policy.saveEnabled) postRecipeCopies = AccountPostRecipeCopyAuthority(config.environment, source, rights, policy.disclosureVersion)
                    AccountPostRecipeSourceHttpConfiguration(source, verifier, databaseDispatcher)
                }
                val makeAgain = if (config.makeAgainEnabled) AccountMakeAgainStore(
                    config.environment, transactions, accounts, rights, config.savedCursors,
                    config.savedPolicy, planning, journal, ingredients,
                    FeedbackServicePolicy(checkNotNull(config.memoryPolicy).maxResponseBytes), config.newCopiesEnabled)
                    else null
                val saved = AccountSavedRecipeStore(config.environment, transactions, accounts, rights,
                    config.savedCursors, config.savedPolicy, config.newCopiesEnabled, planning = planning,
                    collectionMutationsEnabled = config.collectionMutationsEnabled, postCopies = postRecipeCopies,
                    makeAgain = makeAgain)
                val remixes = config.remixReadPolicy?.let { policy ->
                    val store = AccountRemixReadStore(config.environment, transactions, checkNotNull(postIdentity),
                        checkNotNull(postReadStore), checkNotNull(config.remixCursors), policy)
                    store.checkCompatibility()
                    AccountRemixReadHttpConfiguration(store, verifier, databaseDispatcher)
                }
                val mediaAccess = config.mediaAccessPolicy?.let { policy ->
                    val objects = SupabaseDerivativeHttp.create(checkNotNull(config.media).storage, clock)
                    unownedDerivativeObjects = objects
                    val owner = AccountMediaAccessStore(config.environment, transactions, checkNotNull(postReadStore),
                        objects, policy, config.deployment.validUntil, clock)
                    mediaAccessOwner = owner
                    unownedDerivativeObjects = null // The access owner now owns and closes the adapter.
                    AccountMediaAccessHttpConfiguration(owner, verifier, databaseDispatcher)
                }
                val recipeRequests = config.recipeRequestPolicy?.let { policy ->
                    AccountRecipeRequestHttpConfiguration(AccountRecipeRequestStore(config.environment, transactions, accounts,
                        checkNotNull(postIdentity), checkNotNull(conversationStore), checkNotNull(postReadStore), journal,
                        policy, checkNotNull(recipeRequestEligibility)), verifier, databaseDispatcher)
                }
                val health = AccountCoreDependencyHealth(config, transactions, authority, ingredients,
                    journal, rights, verifier, databaseDispatcher, deletionStore)
                return ConfiguredSupabaseAccountCoreAssembly(
                    AccountHttpConfiguration(accounts, verifier, databaseDispatcher),
                    AccountPreferencesHttpConfiguration(preferences, verifier, databaseDispatcher),
                    AccountPantryHttpConfiguration(pantry, verifier, databaseDispatcher),
                    AccountPlanningHttpConfiguration(planning, verifier, databaseDispatcher,
                        AccountRecipeCatalogStore(config.environment, transactions, accounts, journal,
                            config.planningCursors, config.planningPolicy.cursorLifetimeSeconds)),
                    AccountCookingHttpConfiguration(cooking, verifier, databaseDispatcher),
                    AccountSavedRecipeHttpConfiguration(saved, verifier, databaseDispatcher), guest, blocks, mealIntent, deletion, postReads, social, reports, media, memory, reuse, postDrafts, postPublication, conversations, postDeletion, postPlacement, postReactions, recipeRequests, sessions, notifications, notificationInbox, mediaAccess, remixes,
                    postRecipes, exports, exportDelivery, staff, exportWorker, health, authority, keySource,
                    guestReplayCipher, model, mediaStorage, mediaAccessOwner, exportDownloads, exportObjects,
                    exportEncryption, reactionNotificationStore)
            } catch (failure: Throwable) {
                // Retire every owned resource even if construction or cleanup is interrupted.
                // The caller owns the DataSource and dispatcher; never close them here.
                val failures = mutableListOf(failure)
                for (close in listOf<() -> Unit>({ exportDownloads?.close() }, { exportWorker?.close() },
                    { exportObjects?.close() }, { exportEncryption?.close() }, { unownedDerivativeObjects?.close() },
                    { mediaAccessOwner?.close() }, { mediaStorage?.close() }, { guestReplayCipher?.close() },
                    { model?.close() }, { authority.close() }, { keys?.close() })) {
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
    RecipeChangesetPublicationAuthority, RecipeCopyRightsPublicationAuthority, RecipeSubstitutionPublicationAuthority {
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
    override fun lockPublication(connection: Connection, environment: String, original: RecipeSubstitutionPublication): Unit = deny()
    override fun revalidatePublication(connection: Connection, environment: String, original: RecipeSubstitutionPublication): Unit = deny()
}
