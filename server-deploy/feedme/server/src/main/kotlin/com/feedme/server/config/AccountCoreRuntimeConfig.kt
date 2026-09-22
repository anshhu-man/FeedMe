package com.feedme.server.config

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.FeedMeAdultPolicy
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
import com.feedme.server.identity.AccountDeletionRules
import com.feedme.server.identity.AccountTermsNotice
import com.feedme.server.identity.AccountSessionPolicy
import com.feedme.server.identity.AccountSessionCursors
import com.feedme.server.identity.AccountNotificationPolicy
import com.feedme.server.identity.AccountNotificationInboxPolicy
import com.feedme.server.identity.NotificationInboxCursors
import com.feedme.server.media.access.AccountMediaAccessPolicy
import com.feedme.server.social.posts.RemixReadPolicy
import com.feedme.server.social.posts.RemixCursors
import com.feedme.server.identity.SupabaseAuthorityDeployment
import com.feedme.server.identity.SupabaseAuthErasureClient
import com.feedme.server.kitchen.KitchenCursorCodec
import com.feedme.server.kitchen.KitchenServicePolicy
import com.feedme.server.memory.SavedRecipeCursors
import com.feedme.server.memory.SavedRecipeServicePolicy
import com.feedme.server.memory.MemoryServicePolicy
import com.feedme.server.memory.MemoryCursors
import com.feedme.server.planning.AccountPlanningPolicy
import com.feedme.server.planning.PlanningCursors
import com.feedme.server.planning.PlanningServicePolicy
import com.feedme.server.reuse.AccountReusePolicy
import com.feedme.server.reuse.ReuseCursors
import com.feedme.server.social.BlockCursors
import com.feedme.server.social.BlockServicePolicy
import com.feedme.server.social.CircleCapabilities
import com.feedme.server.social.CircleLaunchPolicy
import com.feedme.server.social.posts.PostFeedCursors
import com.feedme.server.social.posts.PostReadPolicy
import com.feedme.server.social.posts.PostReadMediaSafetyPolicy
import com.feedme.server.social.posts.AccountPostAdmissionPolicy
import com.feedme.server.social.posts.AccountPostDeletionPolicy
import com.feedme.server.social.reciperequests.AccountRecipeRequestPolicy
import com.feedme.server.social.posts.PostPublicationPolicy
import com.feedme.server.social.drafts.PostDraftCursors
import com.feedme.server.social.drafts.PostDraftServicePolicy
import com.feedme.server.social.conversations.AccountConversationPolicy
import com.feedme.server.social.conversations.ConversationCursors
import com.feedme.server.social.reports.ReportServicePolicy
import java.net.URI
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
 * after the purpose-specific codecs take detached copies. Optional safety cursor keys are
 * HMAC-derived from the existing saved keyring under separate fixed purpose labels, as are
 * optional post-feed cursors and circle capabilities. Each codec also uses its own
 * cryptographic domain. No provider or database credential is a capability key.
 * Optional media serving has separate explicit storage credentials; no launch
 * configuration or secret value is inferred.
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
    val makeAgainEnabled: Boolean,
    internal val collectionMutationsEnabled: Boolean,
    val safetyPolicy: BlockServicePolicy?,
    val blockCursors: BlockCursors?,
    val postReadPolicy: PostReadPolicy?,
    val postFeedCursors: PostFeedCursors?,
    internal val circlePolicy: AccountCircleRuntimePolicy?,
    val reportPolicy: ReportServicePolicy?,
    internal val mealIntent: AccountMealIntentRuntimeConfig?,
    internal val media: AccountMediaRuntimeConfig?,
    internal val memoryPolicy: MemoryServicePolicy?,
    internal val memoryCursors: MemoryCursors?,
    internal val reusePolicy: AccountReusePolicy?,
    internal val reuseCursors: ReuseCursors?,
    internal val postAuthoring: AccountPostAuthoringRuntimePolicy?,
    internal val conversationPolicy: AccountConversationPolicy?,
    internal val conversationCursors: ConversationCursors?,
    internal val postDeletionPolicy: AccountPostDeletionPolicy?,
    internal val recipeRequestPolicy: AccountRecipeRequestPolicy?,
    internal val sessionPolicy: AccountSessionPolicy?,
    internal val sessionCursors: AccountSessionCursors?,
    internal val notificationPolicy: AccountNotificationPolicy?,
    internal val notificationInboxPolicy: AccountNotificationInboxPolicy?,
    internal val notificationInboxCursors: NotificationInboxCursors?,
    internal val mediaAccessPolicy: AccountMediaAccessPolicy?,
    internal val remixReadPolicy: RemixReadPolicy?,
    internal val remixCursors: RemixCursors?,
    internal val postRecipePolicy: AccountCorePostRecipePolicy?,
    internal val exportPolicy: com.feedme.server.export.AccountExportPolicy?,
    internal val exportStorage: AccountExportRuntimeConfig?,
    internal val staffPolicy: com.feedme.server.staff.SupabaseStaffAdmissionPolicy?,
    internal val deletionRules: AccountDeletionRules?,
    val databaseParallelism: Int,
    private val database: Database,
    internal val memoryRankingEnabled: Boolean = false,
    internal val staffModerationCursors: com.feedme.server.staff.StaffModerationCursors? = null,
    internal val postPlacementPolicy: com.feedme.server.social.posts.AccountPostPlacementPolicy? = null,
    internal val postReactionPolicy: com.feedme.server.social.posts.AccountPostReactionPolicy? = null,
    internal val reactionNotificationPolicy: com.feedme.server.identity.AccountReactionNotificationPolicy? = null,
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
        private val allowed = setOf(CONFIG, PASSWORD, KEYS) + AccountMealIntentRuntimeConfig.ENVIRONMENT_KEYS +
            AccountMediaRuntimeConfig.ENVIRONMENT_KEYS + AccountExportRuntimeConfig.ENVIRONMENT_KEYS
        private val conflicting = setOf("FEEDME_MINIMUM_APP_VERSION", "DATABASE_URL", "JDBC_DATABASE_URL",
            "JDBC_DATABASE_USERNAME", "JDBC_DATABASE_PASSWORD", "PGHOST", "PGHOSTADDR", "PGPORT", "PGDATABASE",
            "PGUSER", "PGPASSWORD", "PGSERVICE", "PGSERVICEFILE", "PGSSLMODE", "PGSSLROOTCERT", "PGOPTIONS", "PGPASSFILE")

        fun fromEnvironment(values: Map<String, String>): AccountCoreRuntimeConfig = try {
            require(values.keys.none { it.startsWith("FEEDME_ACCOUNT_") && it !in allowed ||
                it.startsWith("FEEDME_SERVER_") || it.startsWith("FEEDME_MIGRATION_") ||
                it.startsWith("FEEDME_PANTRY_") || it in conflicting })
            val root = document(requireNotNull(values[CONFIG]), 65_536)
            exact(JsonObject(root - "termsNotice" - "safetyPolicy" - "deletionPolicy" - "postReadPolicy" - "circlePolicy" - "reportPolicy" - "memoryPolicy" - "reusePolicy" - "collectionMutationsEnabled" - "postAuthoringPolicy" - "conversationPolicy" - "postDeletionPolicy" - "postPlacementPolicy" - "postReactionPolicy" - "reactionNotificationPolicy" - "recipeRequestPolicy" - "sessionPolicy" - "notificationPolicy" - "notificationInboxPolicy" - "mediaAccessPolicy" - "remixReadPolicy" - "postRecipePolicy" - "exportPolicy" - "staffPolicy"), "version", "environment", "listener", "database", "deployment", "accountRules", "reconnectionRules", "keyPolicy",
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
            exact(JsonObject(r - "adultSelfAttestationEnabled"), "eligibilityPolicyVersion", "requiredTermsVersion", "acceptExactSubmittedTerms")
            val rules = AccountPendingProfileRules(text(r, "eligibilityPolicyVersion", 256),
                text(r, "requiredTermsVersion", 256), boolean(r, "acceptExactSubmittedTerms"),
                if ("adultSelfAttestationEnabled" in r) boolean(r, "adultSelfAttestationEnabled") else false)
            val mealIntent = AccountMealIntentRuntimeConfig.fromEnvironment(values)
            val media = AccountMediaRuntimeConfig.fromEnvironment(values, environment, deployment, rules)
            if (mealIntent != null || media != null) require(rules.adultSelfAttestationEnabled && rules.acceptExactSubmittedTerms &&
                rules.eligibilityPolicyVersion == FeedMeAdultPolicy.ELIGIBILITY_POLICY_VERSION &&
                rules.requiredTermsVersion == FeedMeAdultPolicy.TERMS_VERSION)
            val reconnection = root.getValue("reconnectionRules").let { value ->
                if (value == JsonNull) null else value.jsonObject.let { rule ->
                    exact(JsonObject(rule - "authenticationMethod"), "revision", "consentVersion", "maximumAuthenticationAgeSeconds", "newReconnectionsEnabled")
                    AccountDeviceReconnectionRules(text(rule, "revision", 128), text(rule, "consentVersion", 256),
                        number(rule, "maximumAuthenticationAgeSeconds"), boolean(rule, "newReconnectionsEnabled"),
                        if ("authenticationMethod" in rule) text(rule, "authenticationMethod", 8) else "password")
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
            // Explicit serving policy only: no worker/admin credential, deployment or
            // retention approval is inferred. Missing/null preserves the unavailable route.
            // Deletion is independent of meal eligibility, current Terms and reconnection.
            val deletionRules = root["deletionPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                // The implemented provider/media worker can erase only this approved
                // project. Never accept a deletion for an unsupported issuer, even locally.
                require(deployment.verification.issuer == SupabaseAuthErasureClient.APPROVED_ISSUER)
                exact(policy, "revision", "confirmationVersion", "maximumAuthenticationAgeSeconds")
                AccountDeletionRules(text(policy, "revision", 128), text(policy, "confirmationVersion", 256),
                    number(policy, "maximumAuthenticationAgeSeconds"))
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
            exact(JsonObject(s - "makeAgainEnabled"), "maxResponseBytes", "cursorLifetimeSeconds", "defaultCollectionName")
            val savedPolicy = SavedRecipeServicePolicy(integer(s, "maxResponseBytes", 1..262144),
                integer(s, "cursorLifetimeSeconds", 1..86400), text(s, "defaultCollectionName", 120))
            val makeAgainEnabled = if ("makeAgainEnabled" in s) boolean(s, "makeAgainEnabled") else false
            // Safety is independently configured: social creation need not be enabled.
            // Missing/null supplies no handler or policy defaults.
            val safetyPolicy = root["safetyPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "maxResponseBytes", "cursorLifetimeSeconds")
                BlockServicePolicy(integer(policy, "maxResponseBytes", 1..262144),
                    integer(policy, "cursorLifetimeSeconds", 1..86400))
            }
            // Complaints are independently configured. This does not turn on social
            // creation, require meal eligibility/Terms, assign staff or decide a report.
            val reportPolicy = root["reportPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "maxResponseBytes")
                ReportServicePolicy(integer(policy, "maxResponseBytes", 1..262144))
            }
            val memoryPolicy = root["memoryPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(JsonObject(policy - "rankingEnabled"), "maxResponseBytes", "maxProjectionFeedback", "cursorLifetimeSeconds", "maxSourcesPerMemory")
                MemoryServicePolicy(integer(policy, "maxResponseBytes", 1..262144),
                    integer(policy, "maxProjectionFeedback", 1..1000),
                    integer(policy, "cursorLifetimeSeconds", 1..86400).toLong(),
                    integer(policy, "maxSourcesPerMemory", 1..4096))
            }
            val memoryRankingEnabled = root["memoryPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let {
                if ("rankingEnabled" in it) boolean(it, "rankingEnabled") else false
            } ?: false
            require(!makeAgainEnabled || memoryPolicy != null) { "Make again requires explicit memory policy" }
            val reusePolicy = root["reusePolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "maxResponseBytes", "maxCandidates", "maxCatalogPages", "maxRelationships",
                    "proposalLifetimeSeconds", "cursorLifetimeSeconds", "maxRequestsPerUtcDay")
                AccountReusePolicy(integer(policy, "maxResponseBytes", 4096..262144),
                    integer(policy, "maxCandidates", 1..10000).toLong(),
                    integer(policy, "maxCatalogPages", 1..1000).toLong(),
                    integer(policy, "maxRelationships", 1..1000),
                    integer(policy, "proposalLifetimeSeconds", 60..86400),
                    integer(policy, "cursorLifetimeSeconds", 1..600),
                    integer(policy, "maxRequestsPerUtcDay", 1..1000))
            }
            // Explicit intermediate metadata reads only. This neither enables social
            // publication nor attests media/recipe rights or installs database privileges.
            val postReadPolicy = root["postReadPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "maxResponseBytes", "maxCandidates", "cursorLifetimeSeconds")
                PostReadPolicy(integer(policy, "maxResponseBytes", 1..262144),
                    integer(policy, "maxCandidates", 50..500), integer(policy, "cursorLifetimeSeconds", 1..86400))
            }
            val newCooking = boolean(root, "newCookingEnabled")
            val newCopies = boolean(root, "newCopiesEnabled")
            val collectionMutations = if (root["collectionMutationsEnabled"] == null || root["collectionMutationsEnabled"] == JsonNull)
                false else boolean(root, "collectionMutationsEnabled")
            val parallelism = integer(root, "databaseParallelism", 1..8)
            val rings = document(requireNotNull(values[KEYS]), 32_768)
            exact(rings, "ingredient", "kitchen", "planning", "saved")
            val ingredientCursors = cursor(rings.getValue("ingredient").jsonObject, ::IngredientSearchCursor)
            val kitchenCursors = cursor(rings.getValue("kitchen").jsonObject, ::KitchenCursorCodec)
            val planningCursors = cursor(rings.getValue("planning").jsonObject, ::PlanningCursors)
            val savedCursors = cursor(rings.getValue("saved").jsonObject, ::SavedRecipeCursors)
            val blockCursors = safetyPolicy?.let { derivedBlockCursors(rings.getValue("saved").jsonObject) }
            val postFeedCursors = postReadPolicy?.let { derivedPostFeedCursors(rings.getValue("saved").jsonObject) }
            val memoryCursors = memoryPolicy?.let { derivedMemoryCursors(rings.getValue("saved").jsonObject) }
            val reuseCursors = reusePolicy?.let { derivedReuseCursors(rings.getValue("saved").jsonObject) }
            val conversationPolicy = root["conversationPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "maxResponseBytes", "cursorLifetimeSeconds", "sendsEnabled", "maxThreadsPerAccount", "maxMessagesPer24Hours")
                AccountConversationPolicy(integer(policy, "maxResponseBytes", 1024..262144),
                    integer(policy, "cursorLifetimeSeconds", 1..86400), boolean(policy, "sendsEnabled"),
                    integer(policy, "maxThreadsPerAccount", 1..1000), integer(policy, "maxMessagesPer24Hours", 1..10000))
            }
            val conversationCursors = conversationPolicy?.let { derivedConversationCursors(rings.getValue("saved").jsonObject) }
            val sessionPolicy = root["sessionPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "maxResponseBytes", "cursorLifetimeSeconds")
                require(deployment.verification.issuer == SupabaseAuthErasureClient.APPROVED_ISSUER)
                AccountSessionPolicy(integer(policy, "maxResponseBytes", 4096..262144),
                    integer(policy, "cursorLifetimeSeconds", 1..600))
            }
            val sessionCursors = sessionPolicy?.let { derivedSessionCursors(rings.getValue("saved").jsonObject) }
            val remixReadPolicy = root["remixReadPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "maxResponseBytes", "maxDepth", "cursorLifetimeSeconds")
                require(postReadPolicy != null)
                RemixReadPolicy(integer(policy, "maxResponseBytes", 1..262144), integer(policy, "maxDepth", 20..200),
                    integer(policy, "cursorLifetimeSeconds", 1..60))
            }
            // RemixCursors derives its own encryption purpose from these configured keys.
            val remixCursors = remixReadPolicy?.let { cursor(rings.getValue("saved").jsonObject, ::RemixCursors) }
            val notificationPolicy = root["notificationPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "maxResponseBytes")
                AccountNotificationPolicy(integer(policy, "maxResponseBytes", 4096..262144))
            }
            // Explicit Inbox activation only. Message receipts do not imply notifications,
            // permission to deliver push, or authority to bypass current notification settings.
            val notificationInboxPolicy = root["notificationInboxPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "maxResponseBytes", "cursorLifetimeSeconds")
                require(notificationPolicy != null && conversationPolicy != null)
                AccountNotificationInboxPolicy(integer(policy, "maxResponseBytes", 4096..262144),
                    integer(policy, "cursorLifetimeSeconds", 1..86400))
            }
            val notificationInboxCursors = notificationInboxPolicy?.let {
                derivedNotificationInboxCursors(rings.getValue("saved").jsonObject)
            }
            val postDeletionPolicy = root["postDeletionPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "enabled")
                require(media != null && postReadPolicy != null)
                AccountPostDeletionPolicy(boolean(policy, "enabled"))
            }
            val postPlacementPolicy = root["postPlacementPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "enabled", "maxResponseBytes")
                require(postReadPolicy != null)
                com.feedme.server.social.posts.AccountPostPlacementPolicy(boolean(policy, "enabled"),
                    integer(policy, "maxResponseBytes", 4096..262144))
            }
            val postReactionPolicy = root["postReactionPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "enabled", "maxResponseBytes")
                require(postReadPolicy != null)
                com.feedme.server.social.posts.AccountPostReactionPolicy(boolean(policy, "enabled"),
                    integer(policy, "maxResponseBytes", 4096..262144))
            }
            val reactionNotificationPolicy = root["reactionNotificationPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "enabled", "coalesceSeconds", "maxEventAgeSeconds")
                require(notificationInboxPolicy != null && notificationPolicy != null && postReactionPolicy != null)
                val enabled = boolean(policy, "enabled")
                require(!enabled || postReactionPolicy.enabled)
                com.feedme.server.identity.AccountReactionNotificationPolicy(enabled,
                    integer(policy, "coalesceSeconds", 1..300), integer(policy, "maxEventAgeSeconds", 2..3600))
            }
            val recipeRequestPolicy = root["recipeRequestPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "enabled", "lifetimeSeconds", "maxRequestsPer24Hours", "maxResponseBytes")
                require(conversationPolicy != null && postReadPolicy != null)
                AccountRecipeRequestPolicy(boolean(policy, "enabled"), integer(policy, "lifetimeSeconds", 1..604800),
                    integer(policy, "maxRequestsPer24Hours", 1..100), integer(policy, "maxResponseBytes", 1024..262144))
            }
            val postAuthoring = root["postAuthoringPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                // No accepting media substitute. A single configured provider/store owns
                // upload, draft cleanup and publication checks. READY still needs real safety.
                require(media != null && postReadPolicy != null)
                exact(policy, "maxResponseBytes", "draftLifetimeSeconds", "cursorLifetimeSeconds",
                    "draftMutationsEnabled", "publishingEnabled", "saveDisclosureVersion",
                    "maximumDraftsPerAccount", "maximumPublicationsPer24Hours", "mediaSafety")
                val safety = policy.getValue("mediaSafety").takeUnless { it == JsonNull }?.jsonObject?.let { value ->
                    exact(value, "processingRevision", "codecRevision", "safetyRevision")
                    PostReadMediaSafetyPolicy(text(value, "processingRevision", 80), text(value, "codecRevision", 80),
                        text(value, "safetyRevision", 80))
                }
                val maxBytes = integer(policy, "maxResponseBytes", 1..262144)
                AccountPostAuthoringRuntimePolicy(AccountPostAdmissionPolicy(rules.eligibilityPolicyVersion,
                    rules.requiredTermsVersion, boolean(policy, "draftMutationsEnabled"), boolean(policy, "publishingEnabled"),
                    text(policy, "saveDisclosureVersion", 256), integer(policy, "maximumDraftsPerAccount", 1..1000),
                    integer(policy, "maximumPublicationsPer24Hours", 1..1000), safety),
                    PostDraftServicePolicy(maxBytes, integer(policy, "draftLifetimeSeconds", 1..2592000),
                        integer(policy, "cursorLifetimeSeconds", 1..86400)), PostPublicationPolicy(maxBytes),
                    derivedPostDraftCursors(rings.getValue("saved").jsonObject))
            }
            val circlePolicy = root["circlePolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "memberLimit", "invitationLifetimeHours", "circleCreationEnabled", "invitationCreationEnabled", "invitationEndpoint")
                AccountCircleRuntimePolicy(CircleLaunchPolicy(integer(policy, "memberLimit", 2..50),
                    integer(policy, "invitationLifetimeHours", 1..168)),
                    derivedCircleCapabilities(rings.getValue("saved").jsonObject, URI(text(policy, "invitationEndpoint", 2048))),
                    boolean(policy, "circleCreationEnabled"), boolean(policy, "invitationCreationEnabled"))
            }
            val mediaAccessPolicy = root["mediaAccessPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "enabled", "deliveryOrigin", "lifetimeSeconds", "maxObjectBytes", "maxRetainedBytes", "maxItems", "maxConcurrentFetches")
                require(media != null && postReadPolicy != null && postAuthoring?.admission?.mediaSafety != null)
                AccountMediaAccessPolicy(boolean(policy, "enabled"), text(policy, "deliveryOrigin", 2048),
                    integer(policy, "lifetimeSeconds", 1..60), integer(policy, "maxObjectBytes", 1..10000000),
                    number(policy, "maxRetainedBytes"), integer(policy, "maxItems", 1..1024),
                    integer(policy, "maxConcurrentFetches", 1..8))
            }
            val postRecipePolicy = root["postRecipePolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "makeMineEnabled", "saveEnabled", "disclosureVersion")
                require(postReadPolicy != null && postAuthoring?.admission?.mediaSafety != null)
                AccountCorePostRecipePolicy(boolean(policy, "makeMineEnabled"), boolean(policy, "saveEnabled"),
                    text(policy, "disclosureVersion", 128)).also {
                    require(!it.saveEnabled || newCopies)
                    require(!it.makeMineEnabled || planningOperational.newPlanningEnabled)
                    require(!it.saveEnabled || it.disclosureVersion == checkNotNull(postAuthoring).admission.saveDisclosureVersion)
                }
            }
            val exportPolicy = root["exportPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(policy, "enabled", "policyRevision", "recentAuthSeconds", "jobLifetimeSeconds", "downloadSeconds", "pollAfterSeconds", "maxResponseBytes")
                com.feedme.server.export.AccountExportPolicy(boolean(policy, "enabled"), text(policy, "policyRevision", 128),
                    integer(policy, "recentAuthSeconds", 1..900), integer(policy, "jobLifetimeSeconds", 60..86400),
                    integer(policy, "downloadSeconds", 1..60), integer(policy, "pollAfterSeconds", 1..60), integer(policy, "maxResponseBytes", 4096..262144))
            }
            val exportStorage = AccountExportRuntimeConfig.fromEnvironment(values, environment, deployment)
            require((exportPolicy == null) == (exportStorage == null)) { "Exports require explicit policy and private storage" }
            require(exportStorage == null || exportStorage.storage.bucket != media?.storage?.bucket) { "Exports require a separate private bucket" }
            val staffPolicy = root["staffPolicy"]?.takeUnless { it == JsonNull }?.jsonObject?.let { policy ->
                exact(JsonObject(policy - "catalogDraftsEnabled" - "catalogReviewsEnabled" - "catalogPublicationEnabled" - "moderationEnabled"), "policyVersion", "policyClientId", "maximumTotpAgeSeconds", "observationSeconds", "maxResponseBytes")
                com.feedme.server.staff.SupabaseStaffAdmissionPolicy(text(policy, "policyVersion", 128),
                    text(policy, "policyClientId", 256), integer(policy, "maximumTotpAgeSeconds", 1..900).toLong(),
                    integer(policy, "observationSeconds", 1..60).toLong(), integer(policy, "maxResponseBytes", 1024..16384),
                    if ("catalogDraftsEnabled" in policy) boolean(policy, "catalogDraftsEnabled") else false,
                    if ("catalogReviewsEnabled" in policy) boolean(policy, "catalogReviewsEnabled") else false,
                    if ("catalogPublicationEnabled" in policy) boolean(policy, "catalogPublicationEnabled") else false,
                    if ("moderationEnabled" in policy) boolean(policy, "moderationEnabled") else false)
            }
            val staffModerationCursors = if (staffPolicy?.moderationEnabled == true)
                derivedStaffModerationCursors(rings.getValue("saved").jsonObject) else null
            AccountCoreRuntimeConfig(listener, environment, deployment, rules, reconnection, notice, keyPolicy, ingredients, search, preferences,
                ingredientCursors, kitchenCursors, kitchenPolicy, planningOperational, planningPolicy, planningCursors,
                cookingPolicy, newCooking, savedPolicy, savedCursors, newCopies, makeAgainEnabled, collectionMutations, safetyPolicy, blockCursors,
                postReadPolicy, postFeedCursors, circlePolicy, reportPolicy, mealIntent, media, memoryPolicy, memoryCursors,
                reusePolicy, reuseCursors, postAuthoring, conversationPolicy, conversationCursors, postDeletionPolicy, recipeRequestPolicy, sessionPolicy, sessionCursors, notificationPolicy, notificationInboxPolicy, notificationInboxCursors, mediaAccessPolicy, remixReadPolicy, remixCursors,
                postRecipePolicy, exportPolicy, exportStorage, staffPolicy, deletionRules, parallelism, database, memoryRankingEnabled, staffModerationCursors,
                postPlacementPolicy, postReactionPolicy, reactionNotificationPolicy)
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

        private fun derivedSessionCursors(root: JsonObject): AccountSessionCursors = cursor(root) { current, savedKeys ->
            val derived = linkedMapOf<String, ByteArray>()
            try {
                for ((id, key) in savedKeys) derived[id] = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key, "HmacSHA256"))
                    doFinal("feedme:account-session-cursors:v1".toByteArray(Charsets.US_ASCII))
                }
                AccountSessionCursors(current, derived)
            } finally { derived.values.forEach { it.fill(0) } }
        }

        private fun derivedStaffModerationCursors(root: JsonObject): com.feedme.server.staff.StaffModerationCursors = cursor(root) { current, savedKeys ->
            val derived = linkedMapOf<String, ByteArray>()
            try {
                for ((id, key) in savedKeys) derived[id] = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key, "HmacSHA256"))
                    doFinal("feedme:staff-moderation-cursors:v1".toByteArray(Charsets.US_ASCII))
                }
                com.feedme.server.staff.StaffModerationCursors(current, derived)
            } finally { derived.values.forEach { it.fill(0) } }
        }

        private fun derivedNotificationInboxCursors(root: JsonObject): NotificationInboxCursors = cursor(root) { current, savedKeys ->
            val derived = linkedMapOf<String, ByteArray>()
            try {
                for ((id, key) in savedKeys) derived[id] = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key, "HmacSHA256"))
                    doFinal("feedme:account-notification-inbox-cursors:v1".toByteArray(Charsets.US_ASCII))
                }
                NotificationInboxCursors(current, derived)
            } finally { derived.values.forEach { it.fill(0) } }
        }

        private fun derivedConversationCursors(root: JsonObject): ConversationCursors = cursor(root) { current, savedKeys ->
            val derived = linkedMapOf<String, ByteArray>()
            try {
                for ((id, key) in savedKeys) derived[id] = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key, "HmacSHA256"))
                    doFinal("feedme:account-conversation-cursors:v1".toByteArray(Charsets.US_ASCII))
                }
                ConversationCursors(current, derived)
            } finally { derived.values.forEach { it.fill(0) } }
        }

        private fun derivedPostDraftCursors(root: JsonObject): PostDraftCursors = cursor(root) { current, savedKeys ->
            val derived = linkedMapOf<String, ByteArray>()
            try {
                for ((id, key) in savedKeys) derived[id] = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key, "HmacSHA256"))
                    doFinal("feedme:account-post-draft-cursors:v1".toByteArray(Charsets.US_ASCII))
                }
                PostDraftCursors(current, derived)
            } finally { derived.values.forEach { it.fill(0) } }
        }

        private fun derivedReuseCursors(root: JsonObject): ReuseCursors = cursor(root) { current, savedKeys ->
            val derived = linkedMapOf<String, ByteArray>()
            try {
                for ((id, key) in savedKeys) derived[id] = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key, "HmacSHA256"))
                    doFinal("feedme:account-reuse-cursors:v1".toByteArray(Charsets.US_ASCII))
                }
                ReuseCursors(current, derived)
            } finally { derived.values.forEach { it.fill(0) } }
        }

        private fun derivedMemoryCursors(root: JsonObject): MemoryCursors = cursor(root) { current, savedKeys ->
            val derived = linkedMapOf<String, ByteArray>()
            try {
                for ((id, key) in savedKeys) derived[id] = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key, "HmacSHA256"))
                    doFinal("feedme:account-memory-cursors:v1".toByteArray(Charsets.US_ASCII))
                }
                MemoryCursors(current, derived)
            } finally { derived.values.forEach { it.fill(0) } }
        }

        private fun derivedBlockCursors(root: JsonObject): BlockCursors = cursor(root) { current, savedKeys ->
            val derived = linkedMapOf<String, ByteArray>()
            try {
                for ((id, key) in savedKeys) derived[id] = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key, "HmacSHA256"))
                    doFinal("feedme:account-block-cursors:v1".toByteArray(Charsets.US_ASCII))
                }
                BlockCursors(current, derived)
            } finally { derived.values.forEach { it.fill(0) } }
        }

        private fun derivedPostFeedCursors(root: JsonObject): PostFeedCursors = cursor(root) { current, savedKeys ->
            val derived = linkedMapOf<String, ByteArray>()
            try {
                for ((id, key) in savedKeys) derived[id] = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key, "HmacSHA256"))
                    doFinal("feedme:account-post-feed-cursors:v1".toByteArray(Charsets.US_ASCII))
                }
                PostFeedCursors(current, derived)
            } finally { derived.values.forEach { it.fill(0) } }
        }

        private fun derivedCircleCapabilities(root: JsonObject, endpoint: URI): CircleCapabilities = cursor(root) { current, savedKeys ->
            val derived = linkedMapOf<String, ByteArray>()
            try {
                for ((id, key) in savedKeys) derived[id] = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key, "HmacSHA256"))
                    doFinal("feedme:account-circle-capabilities:v1".toByteArray(Charsets.US_ASCII))
                }
                CircleCapabilities(current, derived, endpoint)
            } finally { derived.values.forEach { it.fill(0) } }
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

/** Explicit circle launch choices only; no new production endpoint/key or serving grant is
 * invented. invitationEndpoint is the HTTPS native/join link base (without query/fragment),
 * not the JSON preview API. Native accepted-link configuration must use the same base.
 * Existing saved application signing keys are purpose-separated, with old IDs retained for
 * the invitation lifetime. Missing/null circlePolicy leaves the entire route group closed. */
internal class AccountCircleRuntimePolicy(val launch: CircleLaunchPolicy, val capabilities: CircleCapabilities,
    val circleCreationEnabled: Boolean, val invitationCreationEnabled: Boolean) {
    override fun toString() = "AccountCircleRuntimePolicy(<redacted>)"
}
