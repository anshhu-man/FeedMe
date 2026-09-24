package com.feedme.server.config

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.FeedMeAdultPolicy
import java.io.InputStream
import java.io.PrintStream
import java.time.Clock
import kotlin.system.exitProcess
import kotlinx.serialization.json.*

/** Local syntax/policy check only. The supplied environment is not installed or read from
 * this process. No DataSource, file, provider, listener, deployment or approval is created.
 * JVM strings cannot be securely erased; never put this input in arguments or logs.
 */
fun main(args: Array<String>) {
    val code = runAccountRuntimeExport(args, System.`in`, System.out, Clock.systemUTC())
    if (code != 0) exitProcess(code)
}

internal const val ACCOUNT_RUNTIME_EXPORT_MAX_BYTES = 131_072
private const val EXPORT_REFUSAL = "{\"status\":\"config-invalid-local\",\"code\":\"ACCOUNT_RUNTIME_CONFIGURATION_REJECTED\"}"
private val exportRequiredKeys = setOf("FEEDME_ACCOUNT_RUNTIME_CONFIG", "FEEDME_ACCOUNT_DB_PASSWORD", "FEEDME_ACCOUNT_CURSOR_KEYS")
private val exportAllowedKeys = exportRequiredKeys + AccountMealIntentRuntimeConfig.ENVIRONMENT_KEYS +
    AccountMediaRuntimeConfig.ENVIRONMENT_KEYS + AccountExportRuntimeConfig.ENVIRONMENT_KEYS +
    GuestCoreRuntimeConfig.ENVIRONMENT_KEYS + "PORT"

/** The clock seam is test-only; production always uses the actual UTC clock. This result
 * is neither current dependency health nor evidence that an operator reviewed these facts.
 */
internal fun validateAccountRuntimeExport(bytes: ByteArray, clock: Clock): String = try {
    val root = Json.parseToJsonElement(WireDocument.decode(bytes,
        WireLimits(ACCOUNT_RUNTIME_EXPORT_MAX_BYTES, 2, 32)).encodeUtf8().decodeToString()).jsonObject
    require(root.keys.containsAll(exportRequiredKeys) && root.keys.all { it in exportAllowedKeys })
    val environment = root.mapValues { (_, value) ->
        val text = value as? JsonPrimitive
        require(text != null && text.isString)
        text.content
    }
    // Use the actual production parser, including its nested duplicate-key, TLS, cursor,
    // complete optional AI inputs, PORT and exact configuration-field validation.
    val config = AccountCoreRuntimeConfig.fromEnvironment(environment)
    require(config.environment == "production" && config.listener.isContainer)
    val rules = config.accountRules
    require(rules.adultSelfAttestationEnabled && rules.acceptExactSubmittedTerms &&
        rules.eligibilityPolicyVersion == FeedMeAdultPolicy.ELIGIBILITY_POLICY_VERSION &&
        rules.requiredTermsVersion == FeedMeAdultPolicy.TERMS_VERSION)
    require(config.termsNotice?.termsVersion == FeedMeAdultPolicy.TERMS_VERSION)
    val now = clock.instant()
    require(config.deployment.reviewedAt <= now && now < config.deployment.validUntil)
    val staff = config.staffPolicy
    val guest = config.guest
    val directMakeMine = config.reusePolicy != null
    val savedMakeMine = config.reusePolicy != null
    val postMakeMine = directMakeMine && config.postReadPolicy != null &&
        config.postRecipePolicy?.makeMineEnabled == true
    val accountExportEnabled = config.exportPolicy?.enabled == true && config.exportStorage != null
    val makeAgain = config.makeAgainEnabled && config.memoryPolicy != null
    val inboxReadAcknowledgements = config.notificationPolicy != null &&
        config.conversationPolicy != null && config.notificationInboxPolicy != null
    val onboardingPreferences = config.preferencePolicy.dietaryPatterns.isNotEmpty() &&
        config.preferencePolicy.equipmentIds.isNotEmpty()
    val socialPhotoUpload = config.media?.admission?.uploadsEnabled == true &&
        config.postAuthoring?.admission?.draftMutationsEnabled == true &&
        config.postAuthoring.admission.publishingEnabled &&
        config.postAuthoring.admission.mediaSafety != null &&
        config.mediaAccessPolicy?.enabled == true && config.safetyPolicy != null && config.reportPolicy != null
    val kitchenCircleInvitations = config.circlePolicy?.circleCreationEnabled == true &&
        config.circlePolicy.invitationCreationEnabled && config.safetyPolicy != null && config.reportPolicy != null
    val guestCookingJourney = guest?.newSessionsEnabled == true && guest.bootstrapReplayEnabled &&
        guest.kitchenEnabled && guest.cookingEnabled && guest.savedEnabled && guest.feedbackEnabled && guest.planning != null
    val fullV1Scope = config.planningOperational.newPlanningEnabled && config.newCookingEnabled &&
        config.newCopiesEnabled && config.mealIntent != null && directMakeMine && savedMakeMine && postMakeMine &&
        accountExportEnabled && makeAgain && inboxReadAcknowledgements && onboardingPreferences &&
        config.deletionRules != null && socialPhotoUpload && kitchenCircleInvitations && guestCookingJourney
    buildJsonObject {
        put("status", "config-valid-local")
        put("adultAdmissionEnabled", true)
        put("planningEnabled", config.planningOperational.newPlanningEnabled)
        put("cookingEnabled", config.newCookingEnabled)
        put("savedCopiesEnabled", config.newCopiesEnabled)
        put("aiConfigured", config.mealIntent != null)
        put("mediaConfigured", config.media != null)
        put("accountExportConfigured", config.exportPolicy != null && config.exportStorage != null)
        put("accountExportEnabled", accountExportEnabled)
        put("accountDeletionConfigured", config.deletionRules != null)
        put("directMakeMineConfigured", directMakeMine)
        put("savedMakeMineConfigured", savedMakeMine)
        put("postMakeMineConfigured", postMakeMine)
        put("makeAgainEnabled", makeAgain)
        put("inboxReadAcknowledgementsConfigured", inboxReadAcknowledgements)
        put("onboardingPreferencesConfigured", onboardingPreferences)
        put("socialPhotoUploadEnabled", socialPhotoUpload)
        put("kitchenCircleInvitationsEnabled", kitchenCircleInvitations)
        put("guestCookingJourneyConfigured", guestCookingJourney)
        put("fullV1ScopeConfigured", fullV1Scope)
        put("guestRuntimeConfigured", guest != null)
        put("guestNewSessionsEnabled", guest?.newSessionsEnabled == true)
        put("guestBootstrapReplayEnabled", guest?.bootstrapReplayEnabled == true)
        put("guestKitchenConfigured", guest?.kitchenEnabled == true)
        put("guestCookingConfigured", guest?.cookingEnabled == true)
        put("guestSavedConfigured", guest?.savedEnabled == true)
        put("guestFeedbackConfigured", guest?.feedbackEnabled == true)
        put("guestPlanningConfigured", guest?.planning != null)
        put("staffSessionConfigured", staff != null)
        put("staffCatalogDraftsEnabled", staff?.catalogDraftsEnabled == true)
        put("staffCatalogReviewsEnabled", staff?.catalogReviewsEnabled == true)
        put("staffCatalogPublicationEnabled", staff?.catalogPublicationEnabled == true)
        put("staffModerationEnabled", staff?.moderationEnabled == true)
        put("providerReviewValidUntil", config.deployment.validUntil.toString())
    }.toString()
} catch (failure: InterruptedException) {
    Thread.currentThread().interrupt()
    throw IllegalArgumentException("Account runtime export validation refused")
} catch (_: Exception) {
    throw IllegalArgumentException("Account runtime export validation refused")
}

/** One bounded stdin read; over-limit input is rejected, never truncated into acceptance.
 * The sole output is a fixed-schema redacted JSON line, including for invalid arguments or
 * read failures. Input bytes owned here are wiped; caller/process streams remain open.
 */
internal fun runAccountRuntimeExport(args: Array<String>, input: InputStream, output: PrintStream, clock: Clock): Int {
    var bytes: ByteArray? = null
    val result = try {
        require(args.contentEquals(arrayOf("--check-stdin")))
        bytes = input.readNBytes(ACCOUNT_RUNTIME_EXPORT_MAX_BYTES + 1)
        validateAccountRuntimeExport(bytes, clock) to 0
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        EXPORT_REFUSAL to 2
    } catch (_: Exception) {
        EXPORT_REFUSAL to 2
    } finally { bytes?.fill(0) }
    output.println(result.first)
    return result.second
}
