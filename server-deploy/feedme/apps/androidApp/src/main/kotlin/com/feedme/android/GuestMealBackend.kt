package com.feedme.android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.feedme.app.guest.GuestCleanupLimit
import com.feedme.app.guest.GuestKitchenDraft
import com.feedme.app.guest.GuestPreparation
import com.feedme.app.mealflow.MealFlowExperience
import com.feedme.core.ports.Connectivity
import com.feedme.core.ports.ConnectivityPort
import com.feedme.core.ports.EpochClock
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.IngredientPickerPhase
import com.feedme.mealflow.IngredientPickerIssue
import com.feedme.mealflow.MealEnergy
import com.feedme.mealflow.MealMode
import com.feedme.mealflow.MealOperationIds
import com.feedme.session.AndroidGuestSessionOwner
import com.feedme.session.AndroidGuestSessionPhase
import com.feedme.session.GuestPrivateConnection
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The application-owned guest route borrows one exact native guest connection. It contains
 * no account/social owners and never turns local ingredient text into arbitrary catalog IDs. */
internal class GuestMealRoute(
    val connection: GuestPrivateConnection,
    val experience: MealFlowExperience,
) {
    override fun toString() = "GuestMealRoute(<redacted>)"
}

/** Small process-owner port so GuestEntry can fence Activity attachments independently of the
 * Android/native implementation. General failures are surfaced; only the native owner's exact
 * retained bind acknowledgement may be retried through [retryOpen]. */
internal interface GuestMealBackend {
    suspend fun open(): PortResult<GuestMealRoute>
    suspend fun retryOpen(): PortResult<GuestMealRoute>
    suspend fun find(route: GuestMealRoute, draft: GuestKitchenDraft): PortResult<Unit>
    suspend fun close(route: GuestMealRoute?): PortResult<Unit>
    fun current(route: GuestMealRoute): Boolean
    fun bindingRetryAvailable(): Boolean
}

/** Production guest composition. Configuration is the same immutable, digest-bound public
 * client selection used by account entry; it is not a live-service assertion or credential. */
internal class AndroidGuestMealBackend private constructor(
    private val application: Application,
    private val configuration: AccountConfiguration,
    private val product: PrivateMealConfiguration,
    private val dispatcher: CoroutineDispatcher,
    private val clock: EpochClock,
    private val connectivity: ConnectivityPort,
) : GuestMealBackend {
    private var owner: AndroidGuestSessionOwner? = null
    private var route: GuestMealRoute? = null
    private var closed = false

    override suspend fun open(): PortResult<GuestMealRoute> {
        if (closed || owner != null) return PortResult.Failure(FailureReason.CONFLICT)
        val selected = AndroidGuestSessionOwner.create(application, configuration.guestSelection(),
            configuration.guestSessionConfiguration(), dispatcher, clock, connectivity)
        owner = selected // Retain before the first native suspension, including failed open.
        return when (val opened = selected.open()) {
            is PortResult.Failure -> opened
            is PortResult.Value -> createRoute(selected, opened.value)
        }
    }

    override suspend fun retryOpen(): PortResult<GuestMealRoute> {
        if (closed || route != null) return PortResult.Failure(FailureReason.CONFLICT)
        val selected = owner ?: return PortResult.Failure(FailureReason.NOT_CONFIGURED)
        return when (val opened = selected.retryBinding()) {
            is PortResult.Failure -> opened
            is PortResult.Value -> createRoute(selected, opened.value)
        }
    }

    private suspend fun createRoute(selected: AndroidGuestSessionOwner,
        connection: GuestPrivateConnection): PortResult<GuestMealRoute> {
        if (closed || owner !== selected || !selected.isCurrent(connection))
            return PortResult.Failure(FailureReason.STALE_SESSION)
        return try {
            val experience = MealFlowExperience.fromSession(connection.access, connection.transport,
                connection.boundary, dispatcher, clock, connectivity,
                MealOperationIds { UUID.randomUUID().toString() }, product.meals,
                product.ingredients, product.choices, product.kitchen, product.cooking,
                product.cookbook, mealInterpretationEnabled = false)
            val created = GuestMealRoute(connection, experience)
            if (!selected.isCurrent(connection)) {
                experience.close()
                PortResult.Failure(FailureReason.STALE_SESSION)
            } else {
                route = created
                PortResult.Value(created)
            }
        } catch (_: Exception) {
            // The native owner remains retained so close can acknowledge every acquired child.
            PortResult.Failure(FailureReason.NOT_CONFIGURED)
        }
    }

    /** Resolve every comma/newline-separated name against fresh guest catalog search. Exact
     * label/alias equality is required across all returned pages; missing or ambiguous names
     * never become an empty/generic request. The explicit Find tap authorizes this sequence. */
    override suspend fun find(route: GuestMealRoute, draft: GuestKitchenDraft): PortResult<Unit> {
        if (!current(route)) return PortResult.Failure(FailureReason.STALE_SESSION)
        val terms = guestIngredientTerms(draft.ingredientsText)
            ?: return PortResult.Failure(FailureReason.INVALID_DATA)
        val ingredientIds = mutableListOf<String>()
        for (term in terms) {
            var searched = when (val result = route.experience.ingredients.search(term)) {
                is PortResult.Failure -> return result
                is PortResult.Value -> result.value
            }
            var pageCalls = 0
            while (searched.searchHasMore && pageCalls++ < 10) {
                searched = when (val result = route.experience.ingredients.nextSearchPage()) {
                    is PortResult.Failure -> return result
                    is PortResult.Value -> result.value
                }
                if (searched.issue == IngredientPickerIssue.PAGE_LIMIT && searched.searchHasMore)
                    return PortResult.Failure(FailureReason.NOT_FOUND)
            }
            if (searched.searchHasMore) return PortResult.Failure(FailureReason.NOT_FOUND)
            if (searched.searchPhase != IngredientPickerPhase.READY)
                return PortResult.Failure(if (searched.searchPhase == IngredientPickerPhase.OFFLINE)
                    FailureReason.OFFLINE else searched.failureReason ?: FailureReason.NOT_FOUND)
            val exact = searched.searchResults.filter { option ->
                !option.historical && (option.name.equals(term, ignoreCase = true) ||
                    option.aliases.any { it.equals(term, ignoreCase = true) })
            }.map { it.id }.distinct()
            if (exact.isEmpty()) return PortResult.Failure(FailureReason.NOT_FOUND)
            if (exact.size != 1) return PortResult.Failure(FailureReason.CONFLICT)
            if (exact.single() !in ingredientIds) ingredientIds += exact.single()
        }
        if (!current(route) || ingredientIds.isEmpty()) return PortResult.Failure(FailureReason.STALE_SESSION)
        val energy = when (draft.preparation) {
            GuestPreparation.ASSEMBLE -> MealEnergy.ASSEMBLE
            GuestPreparation.LITTLE -> MealEnergy.LITTLE
            GuestPreparation.HAPPY -> MealEnergy.HAPPY
            null -> return PortResult.Failure(FailureReason.INVALID_DATA)
        }
        val mode = if (draft.preparation == GuestPreparation.ASSEMBLE) MealMode.ASSEMBLE else MealMode.AUTO
        val cleanup = when (draft.cleanupLimit) {
            GuestCleanupLimit.ZERO_MINUTES -> "0"
            GuestCleanupLimit.FIVE_MINUTES -> "5"
            GuestCleanupLimit.TEN_MINUTES -> "10"
            GuestCleanupLimit.UNLIMITED -> ""
            null -> return PortResult.Failure(FailureReason.INVALID_DATA)
        }
        val servings = draft.servings?.toString()
            ?: return PortResult.Failure(FailureReason.INVALID_DATA)
        route.experience.edit { before -> before.copy(mode = mode, energy = energy,
            servings = servings, totalMinutes = draft.minutes.toString(), activeMinutes = "",
            ingredientIds = ingredientIds, equipmentIds = emptyList(), exclusions = emptyList(),
            tasteTags = emptyList(), cleanupMinutes = cleanup, requiredPreparationTags = emptyList()) }
        val staged = route.experience.forms.value
        val values = staged.values
        if (!current(route) || !staged.dirty || values == null || values.ingredientIds != ingredientIds ||
            values.energy != energy || values.mode != mode || values.servings != servings ||
            values.totalMinutes != draft.minutes.toString() || values.cleanupMinutes != cleanup)
            return PortResult.Failure(FailureReason.CONFLICT)
        return when (val submitted = route.experience.findMeal()) {
            is PortResult.Failure -> submitted
            is PortResult.Value -> if (current(route)) PortResult.Value(Unit)
                else PortResult.Failure(FailureReason.STALE_SESSION)
        }
    }

    override suspend fun close(route: GuestMealRoute?): PortResult<Unit> {
        if (closed) return PortResult.Value(Unit)
        val retained = this.route
        if (route != null && retained !== route) return PortResult.Failure(FailureReason.STALE_SESSION)
        val product = retained?.experience?.close()
        if (product is PortResult.Failure) return product
        val selected = owner
        val native = selected?.close() ?: PortResult.Value(Unit)
        if (native is PortResult.Value) {
            this.route = null
            owner = null
            closed = true
        }
        return native
    }

    override fun current(route: GuestMealRoute): Boolean = !closed && this.route === route &&
        owner?.isCurrent(route.connection) == true

    override fun bindingRetryAvailable(): Boolean = !closed && route == null &&
        owner?.states?.value?.phase == AndroidGuestSessionPhase.BINDING_RETRY_REQUIRED

    override fun toString() = "AndroidGuestMealBackend(<redacted>)"

    companion object {
        fun create(application: Application, configuration: AccountConfiguration?): AndroidGuestMealBackend? {
            val selected = configuration ?: return null
            val product = selected.privateMeal ?: return null
            val app = application.applicationContext as Application
            val clock = EpochClock { System.currentTimeMillis() }
            val connectivity = ConnectivityPort {
                try {
                    val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val network = manager.activeNetwork
                    if (network == null) Connectivity.OFFLINE else {
                        val capabilities = manager.getNetworkCapabilities(network)
                        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                            Connectivity.ONLINE else Connectivity.UNKNOWN
                    }
                } catch (_: Exception) { Connectivity.UNKNOWN }
            }
            return try {
                // Deriving the guest policy can reject a valid account-only configuration.
                // Guest entry then remains explicitly unconfigured instead of crashing.
                selected.guestSessionConfiguration()
                AndroidGuestMealBackend(app, selected, product, Dispatchers.Main.immediate,
                    clock, connectivity)
            } catch (_: Exception) { null }
        }
    }
}

/** Controlled natural-name surface for the first release. It is intentionally not a general
 * language parser: exact catalog matching or the separate reviewed AI flow must handle terms. */
internal fun guestIngredientTerms(text: String): List<String>? {
    val terms = text.split(Regex("[,;\\n\\r]+")).map(String::trim).filter(String::isNotEmpty)
    if (terms.isEmpty() || terms.size > 32 || terms.any { term ->
            term.length > 100 || term.any(Char::isISOControl) ||
                runCatching { term.encodeToByteArray(throwOnInvalidSequence = true) }.isFailure
        }) return null
    return terms.distinctBy { it.lowercase() }
}
