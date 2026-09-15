package com.feedme.app.mealflow

import com.feedme.core.ports.*
import com.feedme.contracts.WireDocument
import com.feedme.mealflow.*
import com.feedme.mealflow.timers.*
import com.feedme.mealflow.social.*
import com.feedme.session.PrivateSessionAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Controller admission already validated the UUID; UUID casing is not a different target. */
internal fun sameKitchenCommandTarget(first: String?, second: String?): Boolean =
    if (first == null || second == null) first == second else first.equals(second, ignoreCase = true)

/** UI input only. Equipment/taste identifiers must come from the configured reviewed catalog. */
data class MealInputChoice(val id: String, val label: String) {
    init { require(id.isNotBlank() && id.length <= 200 && label.isNotBlank() && label.length <= 200) }
    override fun toString() = "MealInputChoice(<redacted>)"
}
class MealInputChoices(equipment: List<MealInputChoice>, tastes: List<MealInputChoice>) {
    val equipment = equipment.toList()
    val tastes = tastes.toList()
    init {
        require(equipment.size <= 128 && tastes.size <= 128)
        require(equipment.map { it.id }.distinct().size == equipment.size)
        require(tastes.map { it.id }.distinct().size == tastes.size)
    }
}

/** Unsaved partial text never enters SavedState/Bundle/preferences/logs. No numeric conversion. */
data class MealFormValues(
    val mode: MealMode = MealMode.AUTO,
    val energy: MealEnergy = MealEnergy.LITTLE,
    val servings: String = "1",
    val totalMinutes: String = "",
    val activeMinutes: String = "",
    val ingredientIds: List<String> = emptyList(),
    val equipmentIds: List<String> = emptyList(),
    val exclusions: List<String> = emptyList(),
    val tasteTags: List<String> = emptyList(),
    val baseDescription: String = "",
    val basePreparation: BasePreparation = BasePreparation.UNKNOWN,
    val baseIngredientIds: List<String>? = null,
    val preferencesPendingSync: Boolean = false,
) {
    internal fun detached() = copy(ingredientIds = ingredientIds.toList(), equipmentIds = equipmentIds.toList(),
        exclusions = exclusions.toList(), tasteTags = tasteTags.toList(), baseIngredientIds = baseIngredientIds?.toList())
    fun draft() = ManualMealDraft(mode, energy, servings, ingredientIds, equipmentIds, exclusions, tasteTags,
        totalMinutes.takeIf { it.isNotEmpty() }, activeMinutes.takeIf { it.isNotEmpty() },
        if (mode == MealMode.IMPROVE) ManualBaseMeal(baseDescription, basePreparation, baseIngredientIds) else null,
        preferencesPendingSync)
    override fun toString() = "MealFormValues(<redacted>)"
    companion object {
        fun from(draft: ManualMealDraft) = MealFormValues(draft.mode, draft.energy, draft.servings,
            draft.maxTotalMinutes.orEmpty(), draft.maxActiveMinutes.orEmpty(), draft.ingredientIds,
            draft.equipmentIds, draft.hardExcludedIngredientIds, draft.tasteTags, draft.baseMeal?.description.orEmpty(),
            draft.baseMeal?.preparation ?: BasePreparation.UNKNOWN, draft.baseMeal?.ingredientIds,
            draft.preferencesPendingSync)
    }
}

class MealFormState internal constructor(values: MealFormValues?, val dirty: Boolean,
    val failure: FailureReason?, val busy: Boolean = false, val searchText: String = "") {
    private val copy = values?.detached()
    val values get() = copy?.detached()
    override fun toString() = "MealFormState(dirty=$dirty, private=<redacted>)"
}

/** Owned on the actual serialized identity/UI dispatcher, like SessionBoundary. */
internal class MealFormOwner(private val boundary: SessionBoundary, private val lease: SessionLease,
    private val readiness: MealDraftReadiness? = null) {
    private val mutable = MutableStateFlow(MealFormState(MealFormValues(), false, null))
    val states = mutable.asStateFlow()
    private var generation = 0L
    private var disposed = false
    private val subscription = boundary.onInvalidated(lease) { redact() }
    fun current() = !disposed && boundary.isCurrent(lease)
    fun edit(transform: (MealFormValues) -> MealFormValues) {
        if (!current()) { redact(); return }
        val before = mutable.value.values ?: return
        readiness?.invalidate()
        val ticket = generation
        val next = transform(before).detached()
        // External transforms may synchronously invalidate the lease or perform a newer edit.
        if (!current()) { redact(); return }
        if (generation != ticket) return
        // Resource limit only; partial numeric/base text remains editable until explicit save.
        if (listOf(next.servings, next.totalMinutes, next.activeMinutes).any { it.length > 128 } ||
            next.baseDescription.length > 1000 || listOf(next.ingredientIds, next.equipmentIds, next.exclusions, next.tasteTags)
                .any { it.size > 128 || it.any { value -> value.length > 200 } }) {
            fail(FailureReason.INVALID_DATA); return
        }
        generation++
        mutable.value = MealFormState(next, true, null, mutable.value.busy, mutable.value.searchText)
    }
    fun searchText(value: String) {
        if (!current()) { redact(); return }
        if (value.length > 200) { fail(FailureReason.INVALID_DATA); return }
        val s = mutable.value
        mutable.value = MealFormState(s.values, s.dirty, null, s.busy, value)
    }
    fun adopt(draft: ManualMealDraft?) {
        if (!current()) { redact(); return }
        if (!mutable.value.dirty) {
            val s = mutable.value
            mutable.value = MealFormState(draft?.let(MealFormValues::from) ?: MealFormValues(), false, null, s.busy, s.searchText)
        }
    }
    fun capture(): Pair<Long, MealFormValues>? = if (current()) mutable.value.values?.let { generation to it } else null
    fun matches(ticket: Long) = current() && generation == ticket
    fun savedCurrent(ticket: Long) = matches(ticket) && !mutable.value.dirty
    fun acknowledged(ticket: Long): Boolean {
        if (!matches(ticket)) return false
        val s = mutable.value
        mutable.value = MealFormState(s.values, false, null, s.busy, s.searchText)
        return true
    }
    fun fail(reason: FailureReason?) {
        if (!current()) { redact(); return }
        val s = mutable.value
        mutable.value = MealFormState(s.values, s.dirty, reason, s.busy, s.searchText)
    }
    fun busy(value: Boolean) {
        if (!current()) { redact(); return }
        val s = mutable.value
        mutable.value = MealFormState(s.values, s.dirty, s.failure, value, s.searchText)
    }
    fun discardEdits(saved: ManualMealDraft?) {
        if (!current()) { redact(); return }
        generation++
        mutable.value = MealFormState(saved?.let(MealFormValues::from) ?: MealFormValues(), false, null)
    }
    private fun redact() { generation++; mutable.value = MealFormState(null, false, FailureReason.STALE_SESSION) }
    fun close() { redact(); disposed = true; subscription.close() }
}

/** A saved older draft is not acknowledgement of edits made while that save was suspended. */
internal suspend fun <T> persistMealForm(form: MealFormOwner,
    save: suspend (ManualMealDraft) -> PortResult<T>): PortResult<T> {
    val capture = form.capture() ?: return PortResult.Failure(FailureReason.STALE_SESSION)
    val result = save(capture.second.draft())
    if (result is PortResult.Value && !form.acknowledged(capture.first))
        return PortResult.Failure(if (form.current()) FailureReason.CONFLICT else FailureReason.STALE_SESSION)
    return result
}

/** In-memory navigation only, never a journal/schema/consent or acknowledgement claim. */
class ReviewedPostsNavigation internal constructor(val visible: Boolean, val opening: Boolean,
    val failure: FailureReason?) {
    override fun toString() = "ReviewedPostsNavigation(visible=$visible, opening=$opening)"
}

/** Same serialized dispatcher as the actual boundary. A late open cannot restore its route
 * after Back, replacement, cancellation or session invalidation. Only open jobs are cancelled;
 * no publication, retry or storage operation is inferred from this presentation owner. */
internal class ReviewedPostsNavigationOwner(private val boundary: SessionBoundary, private val lease: SessionLease) {
    private val mutable = MutableStateFlow(ReviewedPostsNavigation(false, false, null))
    val states = mutable.asStateFlow()
    private var generation: Any = Any()
    private var opening: Job? = null
    private var closed = false
    private val subscription = boundary.onInvalidated(lease) { leave() }
    fun begin(job: Job?): Any? {
        if (closed || !boundary.isCurrent(lease)) return null
        val ticket = Any(); generation = ticket; opening = job
        mutable.value = ReviewedPostsNavigation(true, true, null)
        return ticket
    }
    fun finish(ticket: Any, failure: FailureReason?): Boolean {
        if (closed || !boundary.isCurrent(lease) || generation !== ticket) return false
        opening = null; mutable.value = ReviewedPostsNavigation(true, false, failure)
        return true
    }
    fun cancelled(ticket: Any) { if (generation === ticket) leave() }
    fun leave() {
        generation = Any()
        val prior = opening; opening = null
        mutable.value = ReviewedPostsNavigation(false, false, null)
        prior?.cancel()
    }
    fun close() { if (!closed) { closed = true; leave(); subscription.close() } }
}

/** Resolve arbitrary construction-time property getters BEFORE any controller subscribes.
 * Every actual prerequisite call is still delegated to the required original integration. */
private class ConstructedReviewedIntegration(private val actual: ReviewedKitchenIntegration) : ReviewedKitchenIntegration {
    override val principals = actual.principals
    override val delivery = actual.delivery
    override suspend fun disclosure(context: ReviewedPostPrerequisiteContext) = actual.disclosure(context)
    override suspend fun requireNewPrivateSave(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveCheck) = actual.requireNewPrivateSave(context, check)
    override suspend fun requirePrivateSaveReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveReplayCheck) = actual.requirePrivateSaveReplay(context, check)
    override suspend fun requireNewPublication(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationCheck) = actual.requireNewPublication(context, check)
    override suspend fun requirePublicationReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationReplayCheck) = actual.requirePublicationReplay(context, check)
}

/**
 * Retained application-owned meal journey. Construct only from actual PrivateSessionAccess;
 * no fake credentials, storage activation, catalog or authority default. Keep this owner across
 * UI recreation; close it on actual journey/session disposal before dropping its reference.
 * Its stores, transport and native session are BORROWED and are never closed by this owner.
 * Construction/edit/searchText must use the same serialized identity/UI dispatcher as boundary.
 */
class MealFlowExperience private constructor(
    val meals: MealRequestController,
    val ingredients: IngredientPickerController,
    val kitchen: KitchenInputController,
    val cooking: CookingFlowController,
    val cookbook: CookbookController,
    private val form: MealFormOwner,
    private val cookingUi: CookingUiOwner,
    private val dispatcher: CoroutineDispatcher,
    val choices: MealInputChoices,
    private val session: PrivateSessionAccess,
    private val boundary: SessionBoundary,
    private val timerUi: CookingTimerUiOwner,
    val postDrafts: PostDraftController?,
    private val reviewedBundle: MealKitchenControllers? = null,
) {
    /** Actual shared entry, not a second composition. The caller must not close its children. */
    val reviewedPosts: ReviewedPostEntry? get() = reviewedBundle?.reviewedPosts
    private val reviewedPostNavigation = ReviewedPostsNavigationOwner(boundary, session.lease)
    val reviewedPostsNavigation: StateFlow<ReviewedPostsNavigation> = reviewedPostNavigation.states
    val forms: StateFlow<MealFormState> = form.states
    val cookingNavigation: StateFlow<CookingNavigation> = cookingUi.states
    private val mutableKitchenPage = MutableStateFlow<KitchenInputPage?>(null)
    val kitchenPage: StateFlow<KitchenInputPage?> = mutableKitchenPage.asStateFlow()
    private val timerOwner = MutableStateFlow<CookingTimerFlowController?>(null)
    val timerController: StateFlow<CookingTimerFlowController?> = timerOwner.asStateFlow()
    val timers: CookingTimerFlowController? get() = timerOwner.value
    val timerConfirmation: StateFlow<CookingTimerRemoval?> = timerUi.states
    private var action: Any? = null
    private var closed = false
    private var cookingRestored = false
    private var cookbookRestored = false
    private val mutableCookbookQuery = MutableStateFlow("")
    val cookbookQuery: StateFlow<String> = mutableCookbookQuery.asStateFlow()
    private val cookbookSubscription = boundary.onInvalidated(session.lease) { mutableCookbookQuery.value = "" }
    fun edit(transform: (MealFormValues) -> MealFormValues) { cookingUi.dismiss(); form.edit(transform) }
    fun searchText(value: String) = form.searchText(value)
    fun discardUnsavedEdits() { cookingUi.dismiss(); form.discardEdits(meals.states.value.draft) }
    fun openKitchen(page: KitchenInputPage) { if (!closed && form.current()) mutableKitchenPage.value = page }
    fun leaveKitchen() { mutableKitchenPage.value = null }
    /** Explicit navigation only. Reviewed restore reads historical draft/publication state;
     * it never prepares a review, upgrades a legacy row or sends a server action. */
    suspend fun openPostDrafts(): PortResult<PostDraftState> = perform {
        val entry = reviewedPosts
        if (entry == null) return@perform postDrafts?.restoreLocal() ?: PortResult.Failure(FailureReason.UNAVAILABLE)
        val ticket = reviewedPostNavigation.begin(currentCoroutineContext()[Job])
            ?: return@perform PortResult.Failure(FailureReason.STALE_SESSION)
        try {
            val result = entry.restore()
            currentCoroutineContext().ensureActive()
            if (!reviewedPostNavigation.finish(ticket, (result as? PortResult.Failure)?.reason))
                return@perform PortResult.Failure(if (form.current()) FailureReason.CONFLICT else FailureReason.STALE_SESSION)
            when (result) {
                is PortResult.Failure -> result
                is PortResult.Value -> PortResult.Value(entry.drafts.states.value)
            }
        } catch (cancelled: CancellationException) { reviewedPostNavigation.cancelled(ticket); throw cancelled }
        catch (_: Exception) {
            reviewedPostNavigation.finish(ticket, FailureReason.UNAVAILABLE)
            PortResult.Failure(FailureReason.UNAVAILABLE)
        }
    }
    /** Immediate outer Back. Cached draft Back revokes same-assembly review selection without
     * restoring, sending, cancelling an original, or reconstructing a historical ACK. */
    suspend fun leavePostDrafts(): PortResult<Unit> = withContext(dispatcher) {
        reviewedPostNavigation.leave()
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        when (val result = reviewedPosts?.drafts?.back()) {
            is PortResult.Failure -> result
            else -> PortResult.Value(Unit)
        }
    }
    internal fun exitTicket(): Long? = form.capture()?.first
    internal fun canExitAfterSave(ticket: Long) = !closed && form.savedCurrent(ticket)

    /** Restore only local eligible records. Screen attachment never submits/fetches automatically. */
    suspend fun restore() = perform {
        val inputs = kitchen.restore()
        if (inputs is PortResult.Failure) return@perform inputs
        val result = meals.restore()
        if (result is PortResult.Value) {
            form.adopt(result.value.draft); ingredients.restore()
            if (!cookingRestored) {
                val retained = cooking.restore()
                if (retained is PortResult.Failure) return@perform retained
                cookingRestored = true
            }
            if (!cookbookRestored) {
                val retained = cookbook.restore()
                if (retained is PortResult.Failure) return@perform retained
                cookbookRestored = true
            }
        }
        result
    }
    suspend fun refreshContext() = perform { meals.refreshContext() }
    suspend fun saveDraft() = perform { persistForm() }
    suspend fun findMeal() = perform {
        val saved = persistForm()
        if (saved is PortResult.Failure) return@perform saved
        meals.submit()
    }
    suspend fun retryOriginal() = perform { meals.retrySubmitted() }
    suspend fun alternative() = perform {
        if (forms.value.dirty) PortResult.Failure(FailureReason.CONFLICT) else meals.nextAlternative()
    }
    suspend fun previous() = perform { meals.previousPlan() }
    suspend fun recipe() = perform {
        if (forms.value.dirty) PortResult.Failure(FailureReason.CONFLICT) else meals.openRecipe()
    }
    /** Back is not queued behind a network task. The controller fences late replies itself. */
    suspend fun back(from: MealFlowScreen = meals.states.value.screen) = withContext(dispatcher) {
        val result = if (from == MealFlowScreen.RECIPE) meals.returnToRecommendations() else meals.backToDraft()
        if (result is PortResult.Failure) form.fail(result.reason)
        result
    }
    suspend fun search() = perform { ingredients.search(forms.value.searchText) }
    suspend fun moreIngredients() = perform { ingredients.nextSearchPage() }
    suspend fun pantry() = perform { ingredients.refreshPantry() }
    suspend fun morePantry() = perform { ingredients.nextPantryPage() }

    fun cookbookSearchText(value: String) {
        if (!closed && boundary.isCurrent(session.lease) && value.length <= 200) mutableCookbookQuery.value = value
    }
    /** Explicit local browsing; entering the cookbook does not fetch, save or drain any command. */
    suspend fun openCookbook() = perform { cookbook.searchDownloaded() }
    suspend fun searchCookbook() = perform { cookbook.load(cookbookQuery.value) }
    suspend fun moreCookbook() = perform { cookbook.more() }
    suspend fun searchDownloadedCookbook() = perform { cookbook.searchDownloaded(cookbookQuery.value) }
    suspend fun openSavedRecipe(id: String) = perform { cookbook.open(id) }
    suspend fun refreshSavedRecipe() = perform { cookbook.refreshDetail() }
    suspend fun downloadSavedRecipe() = perform { cookbook.download() }
    suspend fun retryCookbookOriginal() = perform { cookbook.retryOriginal() }
    suspend fun discardCookbookUnsent() = perform { cookbook.discardUnsent() }
    suspend fun prepareSavedRecipeDelete(id: String) = perform { cookbook.prepareDelete(id) }
    suspend fun confirmSavedRecipeDelete(ticket: PreparedCookbookDelete) = perform { cookbook.confirmDelete(ticket) }
    suspend fun dismissSavedRecipeDelete() = withContext(dispatcher) { cookbook.dismissDelete() }
    suspend fun backFromCookbook() = withContext(dispatcher) { cookbook.back() }
    suspend fun saveSelectedRecipe() = perform { saveSelectedRecipeOwned(null) }
    /** A cooked A is never substituted with the meal request's later B by a same-label Save tap. */
    suspend fun saveCookingRecipe() = perform {
        val pin = cooking.states.value.plan?.id?.value ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
        saveSelectedRecipeOwned(pin)
    }
    private suspend fun saveSelectedRecipeOwned(expectedPlan: String?): PortResult<CookbookState> {
        val selected = meals.states.value.plan?.plan?.id?.value
        if (forms.value.dirty || selected == null || expectedPlan?.let { it != selected } == true)
            return PortResult.Failure(FailureReason.CONFLICT)
        if (!meals.draftReadiness.isReady) {
            // An explicit Save after discarding an unsaved form fresh-acknowledges the exact clean
            // draft first. Rendering, discard, navigation and restore themselves never grant ACK.
            val saved = persistForm()
            if (saved is PortResult.Failure) return saved
        }
        if (forms.value.dirty || meals.states.value.plan?.plan?.id?.value != selected ||
            expectedPlan?.let { cooking.states.value.plan?.id?.value != it } == true)
            return PortResult.Failure(FailureReason.CONFLICT)
        return cookbook.saveSelectedPlan()
    }

    /** Only the currently selected, saved meal may become a new start proposal. */
    suspend fun prepareCooking() = perform {
        val selected = meals.states.value.plan?.plan
        val formTicket = form.capture()?.first
        val navigation = cookingUi.ticket()
        if (forms.value.dirty || meals.states.value.screen != MealFlowScreen.RECIPE || selected == null || formTicket == null)
            return@perform PortResult.Failure(FailureReason.CONFLICT)
        val result = cooking.prepareStart(selected.id.value)
        if (result is PortResult.Value && cookingUi.matches(navigation) && form.savedCurrent(formTicket) &&
            meals.states.value.plan?.plan?.id == selected.id && result.value.phase == CookingFlowPhase.START_CONFIRMATION &&
            result.value.plan?.id == selected.id) {
            cookingUi.present(CookingConfirmation(CookingConfirmationKind.START, selected.id.value, null, null, formTicket))
        }
        result
    }
    fun showCooking() { if (!closed && form.current()) cookingUi.show() }
    fun leaveCooking() = cookingUi.leave()
    fun dismissCookingConfirmation() = cookingUi.dismiss()
    /** Reopens the same retained proposal, without preparing a new plan or dispatching. */
    fun reviewCookingStart() {
        val state = cooking.states.value
        val ticket = form.capture()?.first ?: return
        if (!closed && !forms.value.dirty && state.phase == CookingFlowPhase.START_CONFIRMATION &&
            state.plan?.id == meals.states.value.plan?.plan?.id) state.plan?.let {
            cookingUi.present(CookingConfirmation(CookingConfirmationKind.START, it.id.value, null, null, ticket))
        }
    }
    fun requestCookingEnd(kind: CookingConfirmationKind) {
        val state = cooking.states.value
        val pin = state.cooking ?: return
        val ticket = form.capture()?.first ?: return
        if (closed || kind == CookingConfirmationKind.START ||
            (kind == CookingConfirmationKind.ABANDON && !state.canStop) ||
            (kind == CookingConfirmationKind.COMPLETE && !state.canComplete)) return
        cookingUi.present(CookingConfirmation(kind, pin.plan.id.value, pin.id, pin.progress.deviceSequence, ticket))
    }
    suspend fun confirmCooking(intent: CookingConfirmation) = perform {
        val state = cooking.states.value
        if (!cookingUi.owns(intent) || !form.matches(intent.formTicket))
            return@perform PortResult.Failure(FailureReason.CONFLICT)
        if (intent.kind == CookingConfirmationKind.START) {
            if (!form.savedCurrent(intent.formTicket) || state.phase != CookingFlowPhase.START_CONFIRMATION ||
                state.plan?.id?.value != intent.planId || meals.states.value.plan?.plan?.id?.value != intent.planId)
                return@perform PortResult.Failure(FailureReason.CONFLICT)
        } else {
            val pin = state.cooking
            if (pin?.id != intent.sessionId || pin?.plan?.id?.value != intent.planId || pin?.progress?.deviceSequence != intent.sequence ||
                (intent.kind == CookingConfirmationKind.ABANDON && !state.canStop) ||
                (intent.kind == CookingConfirmationKind.COMPLETE && !state.canComplete))
                return@perform PortResult.Failure(FailureReason.CONFLICT)
        }
        // Consume this view's consent before the first suspended operation. No old dialog retry.
        cookingUi.dismiss()
        when (intent.kind) {
            CookingConfirmationKind.START -> cooking.confirmStart()
            CookingConfirmationKind.ABANDON -> cooking.abandon()
            CookingConfirmationKind.COMPLETE -> cooking.complete()
        }
    }
    suspend fun retryCookingStart() = perform { cooking.retryStart() }
    suspend fun discardCookingStart() = perform { cookingUi.dismiss(); cooking.discardUnsentStart() }
    suspend fun refreshCooking() = perform { cooking.refresh() }
    suspend fun synchronizeCooking() = perform { cooking.synchronize() }
    suspend fun moveCooking(stepId: String) = perform { cooking.moveTo(stepId) }
    suspend fun completeCookingStep(stepId: String) = perform { cooking.markStepComplete(stepId) }
    suspend fun pauseCooking() = perform { cooking.pause() }
    suspend fun resumeCooking() = perform { cooking.resume() }
    suspend fun readRetainedCooking() = perform { cooking.restore() }
    suspend fun reopenCooking() = withContext(dispatcher) { cooking.returnToCooking() }
    /** Immediate cached Back also fences a suspended start/step operation; never writes a record. */
    suspend fun backFromCooking() = withContext(dispatcher) {
        cookingUi.dismiss()
        val result = cooking.backToRecipe()
        if (result is PortResult.Failure) form.fail(result.reason)
        result
    }

    /** One retained, exactly paired timer UI owner. The application supplies/owns the actual
     * facade and scheduler; attachment never activates native state or starts a timer. */
    suspend fun attachTimers(session: PrivateSessionAccess, facade: SessionCookingTimers,
        ids: MealOperationIds): PortResult<Unit> = perform {
        if (session !== this.session) return@perform PortResult.Failure(FailureReason.STALE_SESSION)
        if (timerOwner.value != null) return@perform PortResult.Failure(FailureReason.CONFLICT)
        when (val result = CookingTimerFlowController.create(session, boundary, dispatcher, facade, cooking, ids)) {
            is PortResult.Failure -> result
            is PortResult.Value -> {
                // Retain before any further suspension/check; actual close disposes only this UI owner.
                timerOwner.value = result.value
                if (closed || !form.current()) {
                    result.value.close(); timerOwner.value = null
                    PortResult.Failure(FailureReason.STALE_SESSION)
                } else PortResult.Value(Unit)
            }
        }
    }

    /** Called only from the application's actual runtime-gated native due callback. */
    fun onTimerDue(identity: CookingTimerDue): PortResult<Unit> =
        if (closed || !form.current()) PortResult.Failure(FailureReason.STALE_SESSION)
        else timers?.onDue(identity) ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)

    suspend fun openTimers() = perform {
        val owner = timers ?: return@perform PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val pin = cooking.states.value.cooking ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
        if (forms.value.dirty || cookingNavigation.value.confirmation != null)
            return@perform PortResult.Failure(FailureReason.CONFLICT)
        timerUi.dismiss()
        owner.open(pin.id, pin.progress.currentStepId).also { if (it is PortResult.Value) cookingUi.show() }
    }
    /** Read-only ticks do not acquire the form action owner or submit/synchronize anything. */
    suspend fun tickTimers(): PortResult<CookingTimerFlowState> =
        timers?.tick() ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
    suspend fun timerDuration(text: String) = perform {
        timers?.setDurationText(text) ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
    }
    suspend fun startTimer() = perform { timers?.start() ?: PortResult.Failure(FailureReason.NOT_CONFIGURED) }
    suspend fun pauseTimer(id: String) = perform { timers?.pause(id) ?: PortResult.Failure(FailureReason.NOT_CONFIGURED) }
    suspend fun resumeTimer(id: String) = perform { timers?.resume(id) ?: PortResult.Failure(FailureReason.NOT_CONFIGURED) }
    suspend fun resetTimer(id: String) = perform { timers?.reset(id) ?: PortResult.Failure(FailureReason.NOT_CONFIGURED) }
    suspend fun cancelTimerAlert(id: String) = perform { timers?.cancelAlert(id) ?: PortResult.Failure(FailureReason.NOT_CONFIGURED) }
    fun requestTimerRemoval(id: String) {
        if (closed || !form.current()) return
        val state = timers?.states?.value ?: return
        val pin = state.snapshot ?: return
        if (!state.visible || !state.canStop || state.busy || state.pendingAction != null || pin.timers.none { it.timerId == id }) return
        timerUi.present(pin.sessionId, pin.planId, pin.localRevision, id)
    }
    fun dismissTimerRemoval() = timerUi.dismiss()
    suspend fun confirmTimerRemoval(intent: CookingTimerRemoval) = perform {
        val owner = timers ?: return@perform PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val state = owner.states.value
        val pin = state.snapshot ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
        if (!timerUi.owns(intent) || !state.visible || !state.canStop || state.pendingAction != null ||
            pin.sessionId != intent.sessionId || pin.planId != intent.planId || pin.localRevision != intent.revision ||
            pin.timers.none { it.timerId == intent.timerId }) return@perform PortResult.Failure(FailureReason.CONFLICT)
        timerUi.dismiss()
        owner.cancel(intent.timerId)
    }
    /** Back is immediate navigation, not a queued mutation, pause, reset, cancellation or discard. */
    suspend fun backFromTimers() = withContext(dispatcher) {
        timerUi.dismiss()
        timers?.back() ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
    }

    suspend fun loadKitchenPreferences() = perform { kitchen.loadPreferences() }
    suspend fun loadKitchenPantry() = perform { kitchen.loadPantry() }
    suspend fun moreKitchenPantry() = perform { kitchen.nextPantryPage() }
    suspend fun editKitchenPreferences(patch: WireDocument) = perform { kitchen.editPreferences(patch) }
    suspend fun discardKitchenPreferences() = perform { kitchen.discardPreferenceDraft() }
    suspend fun saveKitchenPreferences() = perform { sendSavedKitchenCommand(kitchen.savePreferences(), "updatePreferences", null) }
    suspend fun editKitchenPantry(draft: WireDocument) = perform { kitchen.editPantry(draft) }
    suspend fun discardKitchenPantry(id: String) = perform { kitchen.discardPantryDraft(id) }
    suspend fun saveKitchenPantry(id: String) = perform { sendSavedKitchenCommand(kitchen.savePantry(id), "upsertPantryItem", id) }
    suspend fun removeKitchenPantry(id: String) = perform { sendSavedKitchenCommand(kitchen.removePantry(id), "removePantryItem", id) }
    suspend fun synchronizeKitchen(id: String) = perform { kitchen.synchronize(id) }
    suspend fun discardUnsentKitchen(id: String) = perform { kitchen.discardUnsent(id) }

    /** A Save click authorizes one enqueue + attempt of that exact saved target. Rendering,
     * navigation, restore and unrelated pending commands never start background transport. */
    private suspend fun sendSavedKitchenCommand(result: PortResult<KitchenInputState>, operation: String,
        ingredientId: String?): PortResult<KitchenInputState> {
        if (result is PortResult.Failure) return result
        val pending = (result as PortResult.Value).value.pending.singleOrNull {
            it.operationId == operation && sameKitchenCommandTarget(it.ingredientId, ingredientId)
        } ?: return result
        return if (pending.canSynchronize) kitchen.synchronize(pending.commandId) else result
    }

    private suspend fun persistForm(): PortResult<MealRequestState> = persistMealForm(form, meals::edit)
    private suspend fun <T> perform(block: suspend () -> PortResult<T>): PortResult<T> = withContext(dispatcher) {
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (action != null) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        val token = Any(); action = token; form.busy(true)
        try {
            val result = block()
            if (result is PortResult.Failure) form.fail(result.reason)
            if (!form.current()) PortResult.Failure(FailureReason.STALE_SESSION) else result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { form.fail(FailureReason.UNAVAILABLE); PortResult.Failure(FailureReason.UNAVAILABLE) }
        finally { if (action === token) { action = null; form.busy(false) } }
    }
    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        closed = true; mutableKitchenPage.value = null; cookingUi.close(); timerUi.close(); form.close()
        reviewedPostNavigation.close()
        mutableCookbookQuery.value = ""; cookbookSubscription.close()
        // These close methods redact and unsubscribe only; borrowed native ownership is untouched.
        var failure: PortResult.Failure? = null
        fun observe(result: PortResult<Unit>?) { if (failure == null && result is PortResult.Failure) failure = result }
        observe(timerOwner.value?.close()); timerOwner.value = null
        if (reviewedBundle != null) observe(reviewedBundle.close())
        else { observe(postDrafts?.close()); observe(cookbook.close()); observe(cooking.close()) }
        observe(kitchen.close()); observe(ingredients.close()); observe(meals.close())
        failure ?: PortResult.Value(Unit)
    }
    override fun toString() = "MealFlowExperience(<redacted>)"
    companion object {
        /** Explicit configured connection. This synchronous factory performs no store/transport,
         * ID, disclosure, principal-resolution or migration operation. The required builder must
         * be construction-only and must not acquire resources or mutate the native session.
         * It receives the ONE exact access used by all child controllers. A throwing builder or
         * integration getter leaves no child owner behind. Live providers are not configured by
         * choosing this factory. The interim experience UI deliberately excludes legacy upgrades. */
        fun fromSessionWithReviewedPosts(session: PrivateSessionAccess, transport: AccountTransport, boundary: SessionBoundary,
            dispatcher: CoroutineDispatcher, clock: EpochClock, connectivity: ConnectivityPort,
            ids: MealOperationIds, mealPolicy: MealFlowPolicy, ingredientPolicy: IngredientPickerPolicy,
            choices: MealInputChoices, kitchenPolicy: KitchenInputPolicy, cookingPolicy: CookingFlowPolicy,
            cookbookPolicy: CookbookPolicy, draftPolicy: PostDraftClientPolicy, publicationPolicy: PostPublicationClientPolicy,
            integrationForAccess: (AuthenticatedMealPlanningAccess) -> ReviewedKitchenIntegration): MealFlowExperience {
            require(boundary.isCurrent(session.lease) && session.scope.actorKind == ActorKind.ACCOUNT) { "Current account session required" }
            val access = AuthenticatedMealPlanningAccess.fromSession(session, transport)
            val integration = ConstructedReviewedIntegration(integrationForAccess(access))
            require(boundary.isCurrent(session.lease)) { "Current private session required" }
            val meals = MealRequestController(access, boundary, dispatcher, clock, connectivity, ids, mealPolicy)
            val retained = MealKitchenControllers.createWithReviewedPosts(access, boundary, dispatcher, clock, connectivity,
                ids, meals, meals.draftReadiness, cookingPolicy, cookbookPolicy, draftPolicy, publicationPolicy, integration)
            return MealFlowExperience(meals,
                IngredientPickerController(access, boundary, dispatcher, clock, connectivity, ingredientPolicy),
                KitchenInputController(access, boundary, dispatcher, clock, connectivity, ids, kitchenPolicy),
                retained.cooking, retained.cookbook,
                MealFormOwner(boundary, session.lease, meals.draftReadiness), CookingUiOwner(boundary, session.lease), dispatcher, choices,
                session, boundary, CookingTimerUiOwner(boundary, session.lease), retained.postDrafts, retained)
        }

        fun fromSession(session: PrivateSessionAccess, transport: AccountTransport, boundary: SessionBoundary,
            dispatcher: CoroutineDispatcher, clock: EpochClock, connectivity: ConnectivityPort,
            ids: MealOperationIds, mealPolicy: MealFlowPolicy, ingredientPolicy: IngredientPickerPolicy,
            choices: MealInputChoices, kitchenPolicy: KitchenInputPolicy, cookingPolicy: CookingFlowPolicy,
            cookbookPolicy: CookbookPolicy, draftPolicy: PostDraftClientPolicy? = null): MealFlowExperience {
            require(boundary.isCurrent(session.lease)) { "Current private session required" }
            val access = AuthenticatedMealPlanningAccess.fromSession(session, transport)
            val meals = MealRequestController(access, boundary, dispatcher, clock, connectivity, ids, mealPolicy)
            val retained = if (draftPolicy == null) MealKitchenControllers.create(access, boundary, dispatcher, clock, connectivity, ids, meals,
                meals.draftReadiness, cookingPolicy, cookbookPolicy)
            else MealKitchenControllers.createWithDrafts(access, boundary, dispatcher, clock, connectivity, ids, meals,
                meals.draftReadiness, cookingPolicy, cookbookPolicy, draftPolicy)
            return MealFlowExperience(meals,
                IngredientPickerController(access, boundary, dispatcher, clock, connectivity, ingredientPolicy),
                KitchenInputController(access, boundary, dispatcher, clock, connectivity, ids, kitchenPolicy),
                retained.cooking, retained.cookbook,
                MealFormOwner(boundary, session.lease, meals.draftReadiness), CookingUiOwner(boundary, session.lease), dispatcher, choices,
                session, boundary, CookingTimerUiOwner(boundary, session.lease), retained.postDrafts)
        }
    }
}
