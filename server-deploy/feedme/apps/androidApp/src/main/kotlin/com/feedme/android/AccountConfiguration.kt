package com.feedme.android

import com.feedme.app.onboarding.EmailAccountFormPolicy
import com.feedme.app.onboarding.OnboardingPreferenceChoice
import com.feedme.app.onboarding.OnboardingPreferenceChoices
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.FeedMeAdultPolicy
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.SecretText
import com.feedme.session.EmailAccountConfiguration
import com.feedme.session.AccountPhotoUploadConfiguration
import com.feedme.session.AndroidAccountDeletionConfiguration
import com.feedme.session.OnboardingProfilePolicy
import com.feedme.session.PreAccountBootstrapConfiguration
import com.feedme.session.AccountPasswordRecoveryController
import com.feedme.session.PasswordRecoveryPolicy
import com.feedme.session.GuestBootstrapSelection
import com.feedme.core.ports.EpochClock
import com.feedme.core.ports.ConnectivityPort
import kotlinx.coroutines.CoroutineDispatcher
import com.feedme.transport.*
import java.net.URI
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Bundled client inputs, not provider evidence or consent. Never reads credentials or a URL
 * from an Intent. Parsing is bounded and inert; the owner supplies the actual installation ID. */
internal class AccountConfiguration private constructor(
    val bootstrap: PreAccountBootstrapConfiguration,
    val form: EmailAccountFormPolicy,
    val termsUrl: String,
    val privacyUrl: String,
    val preferenceChoices: OnboardingPreferenceChoices?,
    val privateMeal: PrivateMealConfiguration?,
    private val provider: SupabaseEmailConfiguration,
    private val providerPolicy: SupabaseProviderIdentityPolicy,
    private val refreshPolicy: SupabaseRefreshIdentityPolicy?,
    private val endpoint: ApiEndpoint,
    private val apiOrigin: String,
    private val environment: String,
    private val pendingMillis: Long,
    private val resendMillis: Long,
    private val profile: OnboardingProfilePolicy,
    private val appVersion: String,
    private val signupTermsVersion: String?,
    val googleOAuthRedirectUrl: String?,
    val accountDeletion: AndroidAccountDeletionConfiguration?,
    val circleInvitationLinkBase: String?,
    val photoUpload: AccountPhotoUploadConfiguration?,
    val passwordRecoveryPolicy: PasswordRecoveryPolicy?,
) {
    val accountRefreshConfigured: Boolean get() = refreshPolicy != null
    /** Public expected Supabase issuer, not identity evidence from a browser callback. */
    val googleOAuthProviderIssuer: String get() = provider.providerIssuer

    /** Same immutable, digest-bound API selection used by the account runtime. This is only
     * guest composition input; it grants no token, lease, catalog access or live readiness. */
    fun guestSelection() = GuestBootstrapSelection(bootstrap.binding, environment, apiOrigin)

    fun guestSessionConfiguration() = GuestCurrentSessionConfiguration(endpoint, bootstrap.binding,
        GuestCurrentSessionPolicy(provider.attemptMillis, providerPolicy.evidenceMillis,
            providerPolicy.allowedFutureClockSkewSeconds * 1_000))

    /** No installation/native credential is needed for a purpose-isolated reset. */
    fun createPasswordRecovery(dispatcher: CoroutineDispatcher, clock: EpochClock,
        connectivity: ConnectivityPort, current: () -> Boolean): AccountPasswordRecoveryController =
        AccountPasswordRecoveryController.create(provider, providerPolicy, checkNotNull(passwordRecoveryPolicy),
            dispatcher, clock, connectivity, current)

    fun forInstallation(installationId: SecretText): EmailAccountConfiguration {
        val body = buildJsonObject {
            put("installationId", installationId.use { it })
            put("platform", "android")
            put("appVersion", appVersion)
            // A configured legal version is not the user's action-specific consent receipt.
            // In particular, password login must never manufacture fresh signup consent.
        }.toString().encodeToByteArray()
        return try { EmailAccountConfiguration(provider, providerPolicy, bootstrap, endpoint,
            environment, installationId, PrivateBytes(body), pendingMillis, resendMillis, profile, refreshPolicy,
            signupTermsVersion = signupTermsVersion, googleSignInEnabled = false,
            googleOAuthEnabled = googleOAuthRedirectUrl != null, photoUpload = photoUpload) }
        finally { body.fill(0) }
    }

    override fun toString() = "AccountConfiguration(<redacted>)"

    companion object {
        const val MAX_BYTES = 16_384
        private val fields = setOf("schema", "environment", "providerOrigin", "publishableKey", "apiOrigin",
            "providerAlgorithms", "maxTokenLifetimeSeconds", "futureSkewSeconds", "evidenceMillis",
            "maxResponseBytes", "attemptMillis", "pendingSignupMillis", "resendCooldownMillis",
            "profileConsentMillis", "profileRetryDelayMillis", "ageDeclarationText", "passwordGuidance",
            "termsUrl", "privacyUrl")

        fun parse(bytes: ByteArray, appVersion: String): AccountConfiguration {
            require(appVersion.matches(Regex("[A-Za-z0-9.+_-]{1,60}")))
            // WireDocument rejects duplicate keys, malformed UTF-8 and excess nesting first.
            WireDocument.decode(bytes, WireLimits(maxBytes = MAX_BYTES, maxDepth = 4, maxNumberLength = 12))
            val obj = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            require(obj.keys.containsAll(fields) && obj.keys.all {
                // The Android product has no native-picker fallback. Historical native
                // transport support must not make an unusable public opt-in look configured.
                it in fields || it in setOf("onboardingPreferenceChoices", "privateMeal", "refreshPolicy", "signupTermsVersion", "googleOAuthRedirectUrl", "accountDeletion", "circleInvitationLinkBase", "photoUpload", "passwordRecoveryPolicy")
            })
            val signupTermsVersion = obj["signupTermsVersion"]?.let { supplied ->
                require(supplied is JsonPrimitive && supplied.isString)
                supplied.content.also { require(it.length in 1..256 && it.isNotBlank() && it.none(Char::isISOControl)) }
            }
            val googleOAuthRedirectUrl = obj["googleOAuthRedirectUrl"]?.let { supplied ->
                require(supplied is JsonPrimitive && supplied.isString)
                // Exact literals only: no URI normalization, queries or interchangeable app schemes.
                // The Activity separately binds this configured callback to its actual package.
                require(supplied.content in setOf("com.anshhuman.feedme://auth/callback",
                    "com.anshhuman.feedme.debug://auth/callback"))
                require(signupTermsVersion == FeedMeAdultPolicy.TERMS_VERSION)
                supplied.content
            }
            val privateMeal = obj["privateMeal"]?.let(PrivateMealConfiguration::parse)
            val passwordRecoveryPolicy = obj["passwordRecoveryPolicy"]?.let { supplied ->
                require(supplied is JsonObject && supplied.keys == setOf("codeTemplateReviewed", "resendCooldownMillis", "attemptMillis", "recoverySessionMillis"))
                val reviewed = supplied.getValue("codeTemplateReviewed") as? JsonPrimitive ?: error("Invalid recovery policy")
                require(!reviewed.isString && reviewed.booleanOrNull == true)
                fun limit(name: String): Long {
                    val value = supplied.getValue(name) as? JsonPrimitive ?: error("Invalid recovery policy")
                    require(!value.isString && value.content.matches(Regex("[1-9][0-9]{0,11}")))
                    return value.content.toLong()
                }
                PasswordRecoveryPolicy(true, limit("resendCooldownMillis"), limit("attemptMillis"), limit("recoverySessionMillis"))
            }
            val photoUpload = obj["photoUpload"]?.let { supplied ->
                require(privateMeal != null && supplied is JsonObject &&
                    supplied.keys == setOf("bucket", "maxPhotoBytes", "maxResponseBytes", "attemptMillis"))
                val bucket = supplied.getValue("bucket") as? JsonPrimitive ?: error("Invalid upload policy")
                require(bucket.isString)
                fun limit(name: String, maximum: Long): Long {
                    val value = supplied.getValue(name) as? JsonPrimitive ?: error("Invalid upload policy")
                    require(!value.isString && value.content.matches(Regex("[1-9][0-9]{0,11}")))
                    return value.content.toLong().also { require(it in 1..maximum) }
                }
                AccountPhotoUploadConfiguration(bucket.content, limit("maxPhotoBytes", 786_432).toInt(),
                    limit("maxResponseBytes", 65_536).toInt(), limit("attemptMillis", 300_000))
            }
            val circleInvitationLinkBase = obj["circleInvitationLinkBase"]?.let { supplied ->
                require(privateMeal != null && supplied is JsonPrimitive && supplied.isString)
                supplied.content.also { link ->
                    // Exact reviewed HTTPS app-link destination; not a link or token read
                    // from an Intent. Parsing performs no lookup or provider request.
                    com.feedme.mealflow.circles.CircleInvitationPreviewPolicy(setOf(link), 32768, 60000)
                }
            }
            // Public reviewed metadata, not a live-readiness assertion. Omission stays off;
            // no proof policy, endpoint or confirmation version is inferred from signup.
            val accountDeletion = obj["accountDeletion"]?.let { supplied ->
                require(googleOAuthRedirectUrl != null)
                require(supplied is JsonObject && supplied.keys == setOf("confirmationVersion", "maximumProofAgeMillis"))
                val version = supplied.getValue("confirmationVersion") as? JsonPrimitive ?: error("Invalid deletion policy")
                val age = supplied.getValue("maximumProofAgeMillis") as? JsonPrimitive ?: error("Invalid deletion policy")
                require(version.isString && !age.isString && age.content.matches(Regex("[1-9][0-9]{0,5}")))
                AndroidAccountDeletionConfiguration(version.content, age.content.toLong())
            }
            val choicesObject = obj["onboardingPreferenceChoices"]?.let {
                require(it is JsonObject && it.keys == setOf("dietaryPatterns", "equipment")); it
            }
            fun choices(name: String): List<OnboardingPreferenceChoice> {
                val entries = choicesObject!!.getValue(name) as? JsonArray ?: error("Invalid choice metadata")
                require(entries.size <= 1_000)
                return entries.map { item ->
                    require(item is JsonObject && item.keys == setOf("id", "label", "selectable"))
                    val id = item.getValue("id") as? JsonPrimitive ?: error("Invalid choice metadata")
                    val label = item.getValue("label") as? JsonPrimitive ?: error("Invalid choice metadata")
                    val selectable = item.getValue("selectable") as? JsonPrimitive ?: error("Invalid choice metadata")
                    require(id.isString && label.isString && !selectable.isString)
                    OnboardingPreferenceChoice(id.content, label.content, requireNotNull(selectable.booleanOrNull))
                }
            }
            val preferenceChoices = choicesObject?.let { OnboardingPreferenceChoices(choices("dietaryPatterns"), choices("equipment")) }
            fun string(name: String): String {
                val item = obj.getValue(name) as? JsonPrimitive ?: error("Invalid client configuration")
                require(item.isString)
                return item.content.also { require(it.isNotBlank() && it.none(Char::isISOControl)) }
            }
            fun number(name: String): Long {
                val item = obj.getValue(name) as? JsonPrimitive ?: error("Invalid client configuration")
                require(!item.isString && item.content.matches(Regex("0|[1-9][0-9]{0,11}")))
                return item.content.toLong()
            }
            require(number("schema") == 1L)
            val environment = string("environment")
            require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
            val algorithmItems = obj.getValue("providerAlgorithms") as? JsonArray ?: error("Invalid algorithms")
            require(algorithmItems.size in 1..2)
            val algorithms = algorithmItems.map {
                require(it is JsonPrimitive && it.isString)
                SupabaseProviderAlgorithm.valueOf(it.content)
            }
            require(algorithms.toSet().size == algorithms.size)
            val identityPolicy = SupabaseProviderIdentityPolicy(algorithms.toSet(), number("maxTokenLifetimeSeconds"),
                number("futureSkewSeconds"), number("evidenceMillis"))
            val refreshPolicy = obj["refreshPolicy"]?.let { supplied ->
                require(supplied is JsonObject && supplied.keys == setOf("maximumJwksAgeSeconds", "totalTimeoutMillis"))
                fun boundedNumber(name: String): Long {
                    val value = supplied.getValue(name) as? JsonPrimitive ?: error("Invalid refresh policy")
                    require(!value.isString && value.content.matches(Regex("0|[1-9][0-9]{0,11}")))
                    return value.content.toLong()
                }
                SupabaseRefreshIdentityPolicy(identityPolicy, boundedNumber("maximumJwksAgeSeconds"), boundedNumber("totalTimeoutMillis"))
            }
            val apiOrigin = string("apiOrigin")
            val api = ApiEndpoint.https(environment, apiOrigin)
            val ageDeclaration = string("ageDeclarationText")
            require(ageDeclaration == FeedMeAdultPolicy.AGE_DECLARATION)
            val form = EmailAccountFormPolicy(ageDeclaration, string("passwordGuidance"))
            val terms = legalUrl(string("termsUrl")); val privacy = legalUrl(string("privacyUrl"))
            val profile = OnboardingProfilePolicy(number("profileConsentMillis"), number("profileRetryDelayMillis"))
            val pending = number("pendingSignupMillis"); val resend = number("resendCooldownMillis")
            require(pending in 60_000..3_600_000 && resend in 1_000..300_000)
            val maximum = number("maxResponseBytes").also { require(it in 4096..262144) }.toInt()

            // Stable across file whitespace/key/algorithm order; every exact launch-policy,
            // endpoint and publishable-key input participates. No values appear in diagnostics.
            val canonical = buildJsonObject {
                put("domain", "feedme-android-account-config-v1")
                fields.sorted().forEach { key ->
                    put(key, if (key == "providerAlgorithms") JsonArray(algorithms.map { it.name }.sorted().map(::JsonPrimitive))
                        else obj.getValue(key))
                }
                preferenceChoices?.let { supplied ->
                    fun values(items: List<OnboardingPreferenceChoice>) = JsonArray(items.map { item -> buildJsonObject {
                        put("id", item.id); put("label", item.label); put("selectable", item.selectable)
                    } })
                    putJsonObject("onboardingPreferenceChoices") {
                        put("dietaryPatterns", values(supplied.dietaryPatterns)); put("equipment", values(supplied.equipment))
                    }
                }
                privateMeal?.let { put("privateMeal", it.canonical) }
                // Absent stays byte-identical to the legacy binding. Adding/changing this
                // explicit policy creates a different binding; never migrate native state.
                refreshPolicy?.let { supplied -> putJsonObject("refreshPolicy") {
                    put("maximumJwksAgeSeconds", supplied.maximumJwksAgeSeconds)
                    put("totalTimeoutMillis", supplied.totalTimeoutMillis)
                } }
                // Version metadata is bound configuration, not a consent receipt. Only the
                // shared affirmative signup action may retain it for that exact signup.
                signupTermsVersion?.let { put("signupTermsVersion", it) }
                googleOAuthRedirectUrl?.let { put("googleOAuthRedirectUrl", it) }
                circleInvitationLinkBase?.let { put("circleInvitationLinkBase", it) }
                passwordRecoveryPolicy?.let { supplied -> putJsonObject("passwordRecoveryPolicy") {
                    put("codeTemplateReviewed", supplied.codeTemplateReviewed)
                    put("resendCooldownMillis", supplied.resendCooldownMillis)
                    put("attemptMillis", supplied.attemptMillis)
                    put("recoverySessionMillis", supplied.recoverySessionMillis)
                } }
                photoUpload?.let { supplied -> putJsonObject("photoUpload") {
                    put("bucket", supplied.bucket); put("maxPhotoBytes", supplied.maxPhotoBytes)
                    put("maxResponseBytes", supplied.maxResponseBytes); put("attemptMillis", supplied.attemptMillis)
                } }
                accountDeletion?.let { supplied -> putJsonObject("accountDeletion") {
                    put("confirmationVersion", supplied.confirmationVersion)
                    put("maximumProofAgeMillis", supplied.maximumProofAgeMillis)
                } }
            }.toString().encodeToByteArray()
            val digest = try { MessageDigest.getInstance("SHA-256").digest(canonical) } finally { canonical.fill(0) }
            val binding = try { digest.joinToString("") { "%02x".format(it.toInt() and 255) } } finally { digest.fill(0) }
            val provider = SupabaseEmailConfiguration.https(binding, string("providerOrigin"),
                SecretText(string("publishableKey")), maximum, number("attemptMillis"))
            return AccountConfiguration(PreAccountBootstrapConfiguration(binding, provider.providerIssuer), form,
                terms, privacy, preferenceChoices, privateMeal, provider, identityPolicy, refreshPolicy, api, apiOrigin, environment,
                pending, resend, profile, appVersion, signupTermsVersion, googleOAuthRedirectUrl, accountDeletion,
                circleInvitationLinkBase, photoUpload, passwordRecoveryPolicy)
        }

        private fun legalUrl(value: String): String {
            require(value.length <= 2048 && value.none(Char::isWhitespace))
            val uri = URI(value)
            require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null)
            val host = requireNotNull(uri.host)
            ApiEndpoint.https("legal", "https://" + host + if (uri.port == -1) "" else ":${uri.port}")
            return value
        }
    }
}

/** A public OAuth web-client identifier, not a client secret or an authorization grant. */
internal fun isGoogleWebClientId(value: String): Boolean = value.length <= 256 &&
    value.matches(Regex("[0-9]{6,30}-[a-z0-9]{16,128}\\.apps\\.googleusercontent\\.com"))

/** API-27-compatible bounded asset read; never allocates from an untrusted declared length. */
internal fun readAccountConfiguration(input: InputStream): ByteArray {
    val buffer = ByteArray(AccountConfiguration.MAX_BYTES + 1)
    try {
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) return buffer.copyOf(count)
            if (read == 0) {
                val next = input.read()
                if (next < 0) return buffer.copyOf(count)
                buffer[count++] = next.toByte()
            } else count += read
        }
        error("Client configuration too large")
    } finally { buffer.fill(0) }
}
