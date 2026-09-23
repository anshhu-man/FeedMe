package com.feedme.app.mealflow

import com.feedme.core.ports.*
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import com.feedme.mealflow.*
import com.feedme.mealflow.timers.*
import com.feedme.mealflow.social.*
import com.feedme.mealflow.reports.*
import com.feedme.mealflow.memory.*
import com.feedme.mealflow.collections.*
import com.feedme.mealflow.conversation.*
import com.feedme.mealflow.postdeletion.*
import com.feedme.mealflow.postplacement.*
import com.feedme.mealflow.reactions.*
import com.feedme.mealflow.reciperequests.*
import com.feedme.mealflow.sessioncontrols.*
import com.feedme.mealflow.notifications.*
import com.feedme.mealflow.remix.*
import com.feedme.mealflow.profile.AccountProfileEditPhase
import com.feedme.mealflow.profile.AccountProfileEditState
import com.feedme.app.reports.ReportFormMemory
import com.feedme.app.blueprint.BlueprintIngredientAvailability
import com.feedme.app.blueprint.BlueprintScreenId
import com.feedme.app.release.V1MobileReleaseScope
import com.feedme.mealflow.circles.CirclesController
import com.feedme.mealflow.circles.CirclesReadPolicy
import com.feedme.mealflow.circles.CirclesScreen
import com.feedme.mealflow.circles.CirclesState
import com.feedme.mealflow.circles.CirclesPhase
import com.feedme.mealflow.circles.CirclesListReturnTicket
import com.feedme.mealflow.circles.CircleCreateController
import com.feedme.mealflow.circles.CircleCreatePolicy
import com.feedme.mealflow.circles.CircleCreateScreen
import com.feedme.mealflow.circles.CircleCreateState
import com.feedme.mealflow.circles.CircleLeaveController
import com.feedme.mealflow.circles.CircleLeavePolicy
import com.feedme.mealflow.circles.CircleLeaveScreen
import com.feedme.mealflow.circles.CircleLeaveState
import com.feedme.mealflow.circles.CircleMemberRemovalController
import com.feedme.mealflow.circles.CircleMemberRemovalPolicy
import com.feedme.mealflow.circles.CircleMemberRemovalScreen
import com.feedme.mealflow.circles.CircleMemberRemovalState
import com.feedme.mealflow.circles.CircleOwnershipTransferController
import com.feedme.mealflow.circles.CircleOwnershipTransferPolicy
import com.feedme.mealflow.circles.CircleOwnershipTransferScreen
import com.feedme.mealflow.circles.CircleOwnershipTransferState
import com.feedme.mealflow.circles.CircleEditController
import com.feedme.mealflow.circles.CircleEditPolicy
import com.feedme.mealflow.circles.CircleEditScreen
import com.feedme.mealflow.circles.CircleEditState
import com.feedme.mealflow.circles.CircleInvitationController
import com.feedme.mealflow.circles.CircleInvitationPolicy
import com.feedme.mealflow.circles.CircleIssuedInvitationsController
import com.feedme.mealflow.circles.CircleIssuedInvitationsPolicy
import com.feedme.mealflow.circles.CircleIssuedInvitationsScreen
import com.feedme.mealflow.circles.CircleIssuedInvitationsState
import com.feedme.mealflow.circles.CircleInvitationScreen
import com.feedme.mealflow.circles.CircleInvitationState
import com.feedme.mealflow.circles.CircleInvitationPreviewController
import com.feedme.mealflow.circles.CircleInvitationPreviewPolicy
import com.feedme.mealflow.circles.CircleInvitationPreviewScreen
import com.feedme.mealflow.circles.CircleInvitationPreviewPhase
import com.feedme.mealflow.circles.CircleInvitationPreviewState
import com.feedme.session.PrivateSessionAccess
import com.feedme.session.PrivateSessionAccessMode
import com.feedme.transport.SupabasePhotoUploadPort
import com.feedme.transport.SocialPhotoDeliveryPort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicit anonymous invitation transport and routing policy; neither grants account access.
 * Construction does not open a link or contact the service. */
class CircleInvitationPreviewConfiguration(val transport: PublicTransport, val policy: CircleInvitationPreviewPolicy) {
    override fun toString() = "CircleInvitationPreviewConfiguration(<redacted>)"
}

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
    val cleanupMinutes: String = "",
    val savedRecipeId: String? = null,
    val sourceRecipeVersionId: String? = null,
    val savedMakeMine: Boolean = false,
    val sourcePostId: String? = null,
    val sourcePostVersion: String? = null,
    val requiredPreparationTags: List<String> = emptyList(),
) {
    internal fun detached() = copy(ingredientIds = ingredientIds.toList(), equipmentIds = equipmentIds.toList(),
        exclusions = exclusions.toList(), tasteTags = tasteTags.toList(), baseIngredientIds = baseIngredientIds?.toList(),
        requiredPreparationTags = requiredPreparationTags.toList())
    fun draft() = ManualMealDraft(mode, energy, servings, ingredientIds, equipmentIds, exclusions, tasteTags,
        totalMinutes.takeIf { it.isNotEmpty() }, activeMinutes.takeIf { it.isNotEmpty() },
        if (mode == MealMode.IMPROVE) ManualBaseMeal(baseDescription, basePreparation, baseIngredientIds) else null,
        preferencesPendingSync, maxCleanupMinutes = cleanupMinutes.takeIf { it.isNotEmpty() }, savedRecipeId = savedRecipeId,
        sourceRecipeVersionId = sourceRecipeVersionId, savedMakeMine = savedMakeMine,
        sourcePostId = sourcePostId, sourcePostVersion = sourcePostVersion,
        requiredPreparationTags = requiredPreparationTags)
    override fun toString() = "MealFormValues(<redacted>)"
    companion object {
        fun from(draft: ManualMealDraft) = MealFormValues(draft.mode, draft.energy, draft.servings,
            draft.maxTotalMinutes.orEmpty(), draft.maxActiveMinutes.orEmpty(), draft.ingredientIds,
            draft.equipmentIds, draft.hardExcludedIngredientIds, draft.tasteTags, draft.baseMeal?.description.orEmpty(),
            draft.baseMeal?.preparation ?: BasePreparation.UNKNOWN, draft.baseMeal?.ingredientIds,
            draft.preferencesPendingSync, cleanupMinutes = draft.maxCleanupMinutes.orEmpty(), savedRecipeId = draft.savedRecipeId,
            sourceRecipeVersionId = draft.sourceRecipeVersionId, savedMakeMine = draft.savedMakeMine,
            sourcePostId = draft.sourcePostId, sourcePostVersion = draft.sourcePostVersion,
            requiredPreparationTags = draft.requiredPreparationTags)
    }
}

class MealFormState internal constructor(values: MealFormValues?, val dirty: Boolean,
    val failure: FailureReason?, val busy: Boolean = false, val searchText: String = "") {
    private val copy = values?.detached()
    val values get() = copy?.detached()
    override fun toString() = "MealFormState(dirty=$dirty, private=<redacted>)"
}

/** One exact local source-selection review. Never a Plan, network consent or cooking grant. */
class SavedCookingConfirmation internal constructor(internal val source: SavedCookingSource,
    internal val cookbook: CookbookState, internal val query: String, internal val meal: MealRequestState,
    internal val form: MealFormState, internal val formTicket: Long, internal val navigation: CookingNavigation,
    internal val cooking: CookingFlowState) {
    val title: String get() = source.title
    override fun toString() = "SavedCookingConfirmation(<redacted>)"
}

/** RAM-only review owned by the retained experience, not by an Activity composition. */
class SocialRecipeSaveReview internal constructor(internal val origin: SocialReadState,
    val prepared: PreparedPostRecipeSave) {
    override fun toString() = "SocialRecipeSaveReview(<redacted>)"
}

/** One real pantry render/visit. Never an ingredient identity, account grant or write receipt. */
class PantryRenderedAction internal constructor(internal val kitchen: KitchenInputState,
    internal val form: MealFormState, internal val picker: IngredientPickerState,
    internal val visit: Any, internal val siblings: List<Any?>) {
    override fun toString() = "PantryRenderedAction(<redacted>)"
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
        if (listOf(next.servings, next.totalMinutes, next.activeMinutes, next.cleanupMinutes).any { it.length > 128 } ||
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

/** Explicit discard acknowledges only the already retained draft, never partial input.
 * The returned generation also fences the caller's later navigation continuation. */
internal suspend fun discardMealForm(form: MealFormOwner, retained: ManualMealDraft,
    acknowledge: suspend (ManualMealDraft) -> PortResult<Unit>): PortResult<Long> {
    val capture = form.capture() ?: return PortResult.Failure(FailureReason.STALE_SESSION)
    val result = acknowledge(retained)
    if (result is PortResult.Failure) return result
    if (!form.matches(capture.first))
        return PortResult.Failure(if (form.current()) FailureReason.CONFLICT else FailureReason.STALE_SESSION)
    form.discardEdits(retained)
    return form.capture()?.first?.let { PortResult.Value(it) }
        ?: PortResult.Failure(FailureReason.STALE_SESSION)
}

/** In-memory navigation only, never a journal/schema/consent or acknowledgement claim. */
class ReviewedPostsConfiguration(val publicationPolicy: PostPublicationClientPolicy,
    val attachmentPolicy: ReviewedAttachmentPolicy,
    val integrationForAccess: (AuthenticatedMealPlanningAccess) -> ReviewedKitchenIntegration)

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
        if (!current(ticket)) return false
        opening = null; mutable.value = ReviewedPostsNavigation(true, false, failure)
        return true
    }
    fun current(ticket: Any): Boolean = !closed && boundary.isCurrent(lease) && generation === ticket
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
/** Exact local owner observations, not a cache grant or a request to retry an original. */
internal class MealOfflineRecoveryTicket internal constructor(internal val owner: Any,
    internal val meal: MealRequestState, internal val form: MealFormState,
    internal val cookbook: CookbookState, internal val query: String,
    internal val cooking: CookingFlowState, internal val navigation: CookingNavigation,
    internal val home: BlueprintMealLandingVisit? = null) {
    override fun toString() = "MealOfflineRecoveryTicket(<redacted>)"
}
internal class MealOfflineAvailability internal constructor(val downloadedMeals: Int,
    val canResumeCooking: Boolean, val canRetryConnection: Boolean) {
    override fun toString() = "MealOfflineAvailability(downloadedMeals=$downloadedMeals)"
}

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
    private val ownedKitchenBundle: MealKitchenControllers? = null,
    /** Explicit optional read-only owner over the SAME borrowed session/access. */
    val circles: CirclesController? = null,
    val invitationPreview: CircleInvitationPreviewController? = null,
    val reports: ReportController? = null,
    private val clock: EpochClock,
    interpretText: (suspend (String) -> PortResult<MealInterpretationProposal>)? = null,
    val socialReads: SocialReadController? = null,
    val blocks: com.feedme.mealflow.blocks.BlocksController? = null,
    val localPhotoDraftsEnabled: Boolean = false,
    val circleMemberRemoval: CircleMemberRemovalController? = null,
    val mealMemory: com.feedme.mealflow.memory.MealMemoryController? = null,
    val collections: CollectionReadController? = null,
    val reviewedAttachments: ReviewedAttachmentController? = null,
    val conversations: ThreadController? = null,
    val postDeletion: PostDeletionController? = null,
    val recipeRequests: RecipeRequestController? = null,
    val sessionControls: SessionControlsController? = null,
    val notifications: NotificationSettingsController? = null,
    val remixes: RemixTrailController? = null,
    val profileEdit: com.feedme.mealflow.profile.AccountProfileEditController? = null,
    val accountExports: com.feedme.mealflow.exports.AccountExportController? = null,
    val circleOwnershipTransfer: CircleOwnershipTransferController? = null,
    val notificationReads: NotificationReadController? = null,
    val postPlacement: PostPlacementController? = null,
    val reactions: ReactionController? = null,
) {
    /** RAM-only typing belongs to this retained account owner, never Android saved state. */
    val reportFormMemory = ReportFormMemory()
    private var reportBlockTransition = false
    private var postPlacementTransition = false
    private var reactionTransition = false
    private val reportFormSubscription = boundary.onInvalidated(session.lease) { reportFormMemory.clear() }
    /** Navigation only: retained across UI attachments, with no persistence or network effects. */
    internal val blueprintLanding = BlueprintMealLandingOwner()
    private val blueprintLandingSubscription = boundary.onInvalidated(session.lease) { blueprintLanding.invalidate() }
    /** Actual shared entry, not a second composition. The caller must not close its children. */
    val reviewedPosts: ReviewedPostEntry? get() = ownedKitchenBundle?.reviewedPosts
    val circleCreate: CircleCreateController? get() = ownedKitchenBundle?.circleCreate
    val circleLeave: CircleLeaveController? get() = ownedKitchenBundle?.circleLeave
    val circleDelete: CircleLeaveController? get() = ownedKitchenBundle?.circleDelete
    val circleEdit: CircleEditController? get() = ownedKitchenBundle?.circleEdit
    val circleInvitation: CircleInvitationController? get() = ownedKitchenBundle?.circleInvitation
    val circleIssuedInvitations: CircleIssuedInvitationsController? get() = ownedKitchenBundle?.circleIssuedInvitations
    val invitationRequiresSignIn: Boolean get() = session.scope.actorKind != ActorKind.ACCOUNT
    val supportsSavedCooking: Boolean get() = session.scope.actorKind == ActorKind.ACCOUNT
    /** Current account identity for original safety-screen labels only, not write authority. */
    val reporterAccountId: String? get() = session.scope.actorId.takeIf {
        session.scope.actorKind == ActorKind.ACCOUNT && !closed && form.current()
    }
    val localPhotoOwnerId: String? get() = reporterAccountId.takeIf { localPhotoDraftsEnabled }
    private val reviewedPostNavigation = ReviewedPostsNavigationOwner(boundary, session.lease)
    val reviewedPostsNavigation: StateFlow<ReviewedPostsNavigation> = reviewedPostNavigation.states
    val forms: StateFlow<MealFormState> = form.states
    internal val interpretation = MealInterpretationOwner(boundary, session.lease, dispatcher, interpretText)
    /** A separate RAM-only review owner for the original Make Mine free-text field.
     * It can edit only the proposed adaptation form after explicit confirmation. */
    internal val adaptationInterpretation =
        AdaptationInterpretationOwner(boundary, session.lease, dispatcher, interpretText)
    private val adaptationForm = AdaptationFormOwner(boundary, session.lease)
    val adaptationForms: StateFlow<AdaptationFormState> = adaptationForm.states
    private val rootRecipeForm = RootRecipeFormOwner(boundary, session.lease)
    val rootRecipeForms: StateFlow<RootRecipeFormState> = rootRecipeForm.states
    private val mutableCatalogQuery = MutableStateFlow("")
    val catalogQuery: StateFlow<String> = mutableCatalogQuery.asStateFlow()
    val cookingNavigation: StateFlow<CookingNavigation> = cookingUi.states
    private val mutableKitchenPage = MutableStateFlow<KitchenInputPage?>(null)
    private var kitchenVisit: Any = Any()
    val kitchenPage: StateFlow<KitchenInputPage?> = mutableKitchenPage.asStateFlow()
    private val timerOwner = MutableStateFlow<CookingTimerFlowController?>(null)
    val timerController: StateFlow<CookingTimerFlowController?> = timerOwner.asStateFlow()
    val timers: CookingTimerFlowController? get() = timerOwner.value
    val timerConfirmation: StateFlow<CookingTimerRemoval?> = timerUi.states
    private var action: Any? = null
    private val offlineOwner = Any()
    private var offlineAvailability: Pair<MealOfflineRecoveryTicket, MealOfflineAvailability>? = null
    private var acceptingRootRecipe = false
    private var handingOffSavedRoot = false
    private var savedRootReturn: Pair<CookbookReturnTicket, MealSavedRootSource>? = null
    /** RAM navigation only. Social's Back origin is separate from a later Saved-tab return. */
    private var savedTabReturn: CookbookListReturnTicket? = null
    private enum class SocialReturn { MEAL, SAVED, CIRCLES, COOKING, MEMORY }
    /** Exact entry context only for an explicit Cook-tab departure. Back never restores
     * these observations and may uncover a cook whose background sync has advanced. */
    private class CookingSocialReturn(val cooking: CookingFlowState, val navigation: CookingNavigation,
        val meal: MealRequestState, val form: MealFormState, val formGeneration: Long,
        val cookbook: CookbookState, val query: String)
    private var cookingSocialReturn: CookingSocialReturn? = null
    /** Body-free owner publication only: returning requires a new authorized history read. */
    private var memorySocialReturn: MealMemoryState? = null
    internal val socialMemoryReturnActive: Boolean get() = socialReturn == SocialReturn.MEMORY
    internal val socialCookAvailable: Boolean get() = if (!socialMemoryReturnActive) true else {
        val hidden = memorySocialReturn
        val saved = cookbook.states.value
        !closed && form.current() && action == null && !forms.value.busy && hidden != null &&
            hidden.screen == MealMemoryScreen.HIDDEN && mealMemory?.isCurrent(hidden) == true &&
            (saved.screen == CookbookScreen.HIDDEN || cookbook.canSuspendList(saved))
    }
    private var socialReturn = SocialReturn.MEAL
        set(value) {
            field = value
            if (value != SocialReturn.COOKING) cookingSocialReturn = null
            if (value != SocialReturn.MEMORY) memorySocialReturn = null
        }
    private var circlesTabReturn: CirclesListReturnTicket? = null
    private var savedReturnsToCircles: CirclesListReturnTicket? = null
    private var reuseRootReturn: Pair<MealReuseReturnTicket, MealCatalogRecipe>? = null
    /** Session-local input/navigation retention only, never source or request authority. */
    private class PlanSourceReview(val source: MealRootSource, val parent: MealPlanSnapshot,
        val main: MealFormValues, val formGeneration: Long, val origin: MealFlowScreen,
        val adaptation: AdaptationFormState?, val proposed: MealFormValues) {
        var transferred = false
    }
    private var planSourceReview: PlanSourceReview? = null
    private var collectionSavedReturn: Pair<CollectionReturnTicket, String>? = null
    private var collectionSavedOpening: Pair<CollectionReadState, Job>? = null
    private var closed = false
    private var cookingRestored = false
    private var cookbookRestored = false
    private val mutableSavedCookingConfirmation = MutableStateFlow<SavedCookingConfirmation?>(null)
    val savedCookingConfirmation: StateFlow<SavedCookingConfirmation?> = mutableSavedCookingConfirmation.asStateFlow()
    private val mutablePostSaveReview = MutableStateFlow<SocialRecipeSaveReview?>(null)
    val postSaveReview: StateFlow<SocialRecipeSaveReview?> = mutablePostSaveReview.asStateFlow()
    private val mutableCookbookQuery = MutableStateFlow("")
    val cookbookQuery: StateFlow<String> = mutableCookbookQuery.asStateFlow()
    private val cookbookSubscription = boundary.onInvalidated(session.lease) {
        offlineAvailability = null
        mutableCookbookQuery.value = ""; mutableCatalogQuery.value = ""; mutableSavedCookingConfirmation.value = null
        mutablePostSaveReview.value = null
        savedRootReturn = null
        savedTabReturn = null; socialReturn = SocialReturn.MEAL
        circlesTabReturn = null; savedReturnsToCircles = null
        reuseRootReturn = null
        planSourceReview = null
        collectionSavedReturn = null
        collectionSavedOpening?.second?.cancel(); collectionSavedOpening = null
    }
    fun edit(transform: (MealFormValues) -> MealFormValues) {
        if (!reportsHidden() || acceptingRootRecipe || mutableSavedCookingConfirmation.value != null || meals.states.value.screen in ROOT_RECIPE_SCREENS) return
        planSourceReview = null
        adaptationInterpretation.clear(); adaptationForm.leave(); cookingUi.dismiss()
        // Normal input controls may change constraints, never silently change the Saved target.
        form.edit { before -> transform(before).copy(savedRecipeId = before.savedRecipeId,
            sourceRecipeVersionId = before.sourceRecipeVersionId, savedMakeMine = before.savedMakeMine,
            sourcePostId = before.sourcePostId, sourcePostVersion = before.sourcePostVersion) }
    }
    /** Separate retained text; never the ingredient search field or existing-meal description. */
    internal fun editMealInterpretation(expected: MealInterpretationState, text: String) {
        if (interpretationRouteAvailable()) interpretation.edit(expected, text)
    }

    internal suspend fun requestMealInterpretation(expected: MealInterpretationState,
        expectedMeal: MealRequestState, expectedForm: MealFormState,
        expectedLanding: BlueprintMealLandingVisit): PortResult<MealInterpretationState> = withContext(dispatcher) {
        if (!interpretationRouteAvailable() || meals.states.value !== expectedMeal || forms.value !== expectedForm ||
            !blueprintLanding.current(expectedLanding, true)) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        val formTicket = form.capture()?.first ?: return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        val captured = interpretationContext()
        interpretation.request(expected, MealInterpretationTarget(expectedForm) {
            interpretationRouteAvailable() && form.matches(formTicket) && forms.value === expectedForm &&
                meals.states.value === expectedMeal && blueprintLanding.current(expectedLanding, true) &&
                interpretationContext().zip(captured).all { (now, then) -> now === then }
        })
    }

    internal fun confirmMealInterpretation(expected: MealInterpretationState): PortResult<MealFormState> {
        if (!interpretationRouteAvailable()) return PortResult.Failure(FailureReason.CONFLICT)
        val rendered = forms.value
        val result = interpretation.confirm(expected) { next ->
            if (!interpretationRouteAvailable() || forms.value !== rendered) false
            else {
                form.edit { next }
                form.current() && forms.value !== rendered && forms.value.values == next && forms.value.dirty
            }
        }
        return when (result) {
            is PortResult.Failure -> result
            is PortResult.Value -> PortResult.Value(forms.value)
        }
    }

    internal fun dismissMealInterpretation(expected: MealInterpretationState) = interpretation.dismiss(expected)

    internal fun editAdaptationInterpretation(expected: AdaptationInterpretationState,
        expectedMeal: MealRequestState, expectedMain: MealFormState,
        expectedAdaptation: AdaptationFormState, text: String) {
        if (adaptationInterpretationEntryCurrent(expectedMeal, expectedMain, expectedAdaptation))
            adaptationInterpretation.edit(expected, text)
    }

    internal suspend fun requestAdaptationInterpretation(expected: AdaptationInterpretationState,
        expectedMeal: MealRequestState, expectedMain: MealFormState,
        expectedAdaptation: AdaptationFormState): PortResult<AdaptationInterpretationState> = withContext(dispatcher) {
        if (!adaptationInterpretationEntryCurrent(expectedMeal, expectedMain, expectedAdaptation) ||
            !adaptationInterpretation.reviewCurrent(expected))
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        val captured = adaptationInterpretationContext()
        adaptationInterpretation.request(expected, AdaptationInterpretationTarget(expectedAdaptation) {
            adaptationInterpretationTargetCurrent(expectedMeal, expectedMain, expectedAdaptation) &&
                adaptationInterpretationContext().zip(captured).all { (now, then) -> now === then }
        })
    }

    internal fun confirmAdaptationInterpretation(expected: AdaptationInterpretationState,
        expectedMeal: MealRequestState, expectedMain: MealFormState,
        expectedAdaptation: AdaptationFormState): PortResult<AdaptationFormState> {
        if (!adaptationInterpretationTargetCurrent(expectedMeal, expectedMain, expectedAdaptation) ||
            !adaptationInterpretation.reviewCurrent(expected)) return PortResult.Failure(FailureReason.CONFLICT)
        val result = adaptationInterpretation.confirm(expected) { next ->
            if (!adaptationInterpretationTargetCurrent(expectedMeal, expectedMain, expectedAdaptation)) false
            else when (val edited = adaptationForm.edit(expectedAdaptation) { next }) {
                is PortResult.Failure -> false
                is PortResult.Value -> adaptationForms.value === edited.value && edited.value.values == next
            }
        }
        return when (result) {
            is PortResult.Failure -> result
            is PortResult.Value -> PortResult.Value(adaptationForms.value)
        }
    }

    internal fun dismissAdaptationInterpretation(expected: AdaptationInterpretationState) =
        adaptationInterpretation.dismiss(expected)

    private fun adaptationInterpretationEntryCurrent(expectedMeal: MealRequestState,
        expectedMain: MealFormState, expectedAdaptation: AdaptationFormState): Boolean =
        adaptationInterpretation.enabled && adaptationInterpretationTargetCurrent(expectedMeal, expectedMain, expectedAdaptation) &&
            !adaptationInterpretation.states.value.busy && adaptationInterpretation.states.value.proposal == null

    /** The response target remains current while its own owner publishes busy/review state.
     * No ordinary adaptation action may use this predicate as an editing grant. */
    private fun adaptationInterpretationTargetCurrent(expectedMeal: MealRequestState,
        expectedMain: MealFormState, expectedAdaptation: AdaptationFormState): Boolean =
        action == null && meals.states.value === expectedMeal && forms.value === expectedMain &&
            adaptationForms.value === expectedAdaptation && !expectedMain.busy && !expectedMain.dirty &&
            currentAdaptationForm(expectedAdaptation) &&
            !interpretation.states.value.busy && interpretation.states.value.proposal == null &&
            mutableSavedCookingConfirmation.value == null && mutablePostSaveReview.value == null &&
            !rootRecipeForms.value.visible && cookingChildRoutesHidden()

    private fun adaptationInterpretationContext(): List<Any?> =
        adaptationMealStagingObservations() + listOf(interpretation.states.value, meals.states.value,
            forms.value, adaptationForms.value, rootRecipeForms.value)

    private fun interpretationRouteAvailable(): Boolean = adaptationRouteAvailable() &&
        action == null && !acceptingRootRecipe && !handingOffSavedRoot &&
        timerConfirmation.value == null && mutableSavedCookingConfirmation.value == null &&
        circleCreate?.states?.value?.screen?.let { it != CircleCreateScreen.HIDDEN } != true &&
        circleEdit?.states?.value?.screen?.let { it != CircleEditScreen.HIDDEN } != true &&
        blueprintLanding.states.value.let { it.active && !it.home } &&
        blueprintMealLandingEligible(MealScreenState.from(meals.states.value), forms.value)

    private fun interpretationContext(): List<Any?> = pantrySiblings() + listOf(kitchenVisit,
        kitchen.states.value, ingredients.states.value, forms.value, blueprintLanding.states.value,
        reports?.states?.value, timers?.states?.value)

    fun searchText(value: String) { if (reportsHidden() && !acceptingRootRecipe && mutableSavedCookingConfirmation.value == null && meals.states.value.screen !in ROOT_RECIPE_SCREENS) form.searchText(value) }
    /** Local-only compatibility helper; it never acknowledges controller draft readiness.
     * Interactive discard-and-leave uses [discardUnsavedEditsConfirmed] instead. */
    fun discardUnsavedEdits() {
        if (!reportsHidden() || acceptingRootRecipe || mutableSavedCookingConfirmation.value != null || meals.states.value.screen in ROOT_RECIPE_SCREENS) return
        adaptationForm.leave(); cookingUi.dismiss(); form.discardEdits(meals.states.value.draft)
    }
    suspend fun discardUnsavedEditsConfirmed(expectedMeal: MealRequestState,
        expectedForm: MealFormState): PortResult<Long> = perform(admission = {
        !acceptingRootRecipe && meals.states.value === expectedMeal && forms.value === expectedForm &&
            !expectedForm.busy && expectedForm.values != null
    }) {
        // First-use discard returns to the same empty defaults as the legacy local action;
        // acknowledge that canonical empty draft instead of inventing a meal request.
        val retained = expectedMeal.draft ?: MealFormValues().draft()
        val savedRoute = cookbook.states.value; val navigation = cookingNavigation.value
        val kitchenRoute = kitchenPage.value
        discardMealForm(form, retained) { exact ->
            when (val acknowledged = meals.edit(exact)) {
                is PortResult.Failure -> acknowledged
                is PortResult.Value -> if (meals.states.value === acknowledged.value &&
                    cookbook.states.value === savedRoute && cookingNavigation.value === navigation &&
                    kitchenPage.value == kitchenRoute && meals.draftReadiness.isReady) PortResult.Value(Unit)
                    else cookingActionFailure()
            }
        }
    }
    fun openKitchen(page: KitchenInputPage) {
        if (reportsHidden() && !closed && !acceptingRootRecipe && form.current() && mutableSavedCookingConfirmation.value == null && meals.states.value.screen !in ROOT_RECIPE_SCREENS) {
            kitchenVisit = Any(); mutableKitchenPage.value = page
        }
    }
    fun leaveKitchen() { kitchenVisit = Any(); mutableKitchenPage.value = null }

    internal val accountKitchenPreferencesAvailable: Boolean
        get() = !closed && form.current() && session.scope.actorKind == ActorKind.ACCOUNT
    /** Explicit navigation only. Reviewed restore reads historical draft/publication state;
     * it never prepares a review, upgrades a legacy row or sends a server action. */
    suspend fun openPostDrafts(): PortResult<PostDraftState> = perform { openPostDraftsOwned() }

    internal fun recipeShareAvailable(expected: MealRequestState, expectedForm: MealFormState,
        navigation: CookingNavigation): Boolean = reviewedPosts != null && localPhotoOwnerId != null &&
        mainMealActionCurrent(expected, expectedForm) && cookingNavigation.value === navigation &&
        expected.screen == MealFlowScreen.RECIPE && expected.phase == MealFlowPhase.READY &&
        !expectedForm.busy && !expectedForm.dirty && meals.recipeShareSource(expected) != null

    /** One Share click keeps a blank local draft and its exact recipe reference together.
     * This is not an attachment, publication, completed cook or choice of audience. */
    internal suspend fun openRecipeShare(expected: MealRequestState, expectedForm: MealFormState,
        navigation: CookingNavigation): PortResult<PostDraftState> = perform(admission = {
            recipeShareAvailable(expected, expectedForm, navigation)
        }) {
            val formTicket = form.capture()?.first ?: return@perform cookingActionFailure()
            val cookingBefore = cooking.states.value
            openPostDraftsOwned(recipeSource = expected) {
                !closed && form.savedCurrent(formTicket) && meals.states.value === expected &&
                    cooking.states.value === cookingBefore && cookingNavigation.value === navigation &&
                    localPhotoOwnerId != null
            }
        }

    /** Source capture is local history, not a Plan, fresh rights or a publication grant.
     * The independent main meal may keep unapplied text while this Saved copy is shared. */
    internal fun savedRecipeShareAvailable(expected: CookbookState, expectedQuery: String,
        expectedMeal: MealRequestState, expectedForm: MealFormState, navigation: CookingNavigation): Boolean =
        reviewedPosts != null && localPhotoOwnerId != null && session.scope.actorKind == ActorKind.ACCOUNT &&
            action == null && !acceptingRootRecipe && !handingOffSavedRoot && reportsHidden() &&
            cookbookActionCurrent(expected, expectedQuery) && expected.screen == CookbookScreen.DETAIL &&
            meals.states.value === expectedMeal && expectedMeal.screen !in ROOT_RECIPE_SCREENS &&
            forms.value === expectedForm && !expectedForm.busy && expectedForm.values != null &&
            cookingNavigation.value === navigation && mutablePostSaveReview.value == null &&
            !adaptationForms.value.visible && !rootRecipeForms.value.visible &&
            !interpretation.states.value.busy && interpretation.states.value.proposal == null &&
            cookbook.recipeShareSource(expected) != null

    internal suspend fun openSavedRecipeShare(expected: CookbookState, expectedQuery: String,
        expectedMeal: MealRequestState, expectedForm: MealFormState, navigation: CookingNavigation,
        presentationCurrent: () -> Boolean): PortResult<PostDraftState> = perform(admission = {
            presentationCurrent() && savedRecipeShareAvailable(expected, expectedQuery, expectedMeal, expectedForm, navigation)
        }) {
            val captured = form.capture() ?: return@perform cookingActionFailure()
            if (captured.second != expectedForm.values) return@perform cookingActionFailure()
            val cookingBefore = cooking.states.value
            // Keep the real Saved detail underneath the independent composer. Its ordinary
            // actions are fenced by reviewed navigation; outer Back reveals actual state,
            // never a copied snapshot. The UI visit intentionally ends at this handoff.
            openPostDraftsOwned(savedRecipeSource = expected) {
                !closed && form.matches(captured.first) && forms.value.values == captured.second &&
                    forms.value.dirty == expectedForm.dirty && forms.value.searchText == expectedForm.searchText &&
                    cookbook.states.value === expected && cookbookQuery.value == expectedQuery &&
                    meals.states.value === expectedMeal && cooking.states.value === cookingBefore &&
                    cookingNavigation.value === navigation && localPhotoOwnerId != null
            }
        }

    /** Explicit Share starts a separate blank local draft for the original CAPTURE page.
     * It never chooses a photo, attaches this meal, saves remotely or publishes. Recovery
     * and legacy states stay in their existing draft UI; the completed cook is retained. */
    internal suspend fun openCookingShare(expected: CookingFlowState, navigation: CookingNavigation)
        : PortResult<PostDraftState> = perform(admission = {
            reviewedPosts != null && localPhotoOwnerId != null && cookingActionCurrent(expected, navigation) &&
                CookingScreenState.from(expected).let {
                    it.done && it.serverAcknowledged && it.localPendingCount == 0 && it.pending.isEmpty()
                }
        }) { openPostDraftsOwned {
            !closed && form.current() && cooking.states.value === expected && cookingNavigation.value === navigation &&
                localPhotoOwnerId != null
        } }

    /** Called only while the outer meal action owns admission and busy state. */
    private suspend fun openPostDraftsOwned(recipeSource: MealRequestState? = null,
        savedRecipeSource: CookbookState? = null,
        captureOrigin: (() -> Boolean)? = null): PortResult<PostDraftState> {
        if (recipeSource != null && savedRecipeSource != null) return PortResult.Failure(FailureReason.CONFLICT)
        val entry = reviewedPosts
        if (entry == null) return if (captureOrigin != null) PortResult.Failure(FailureReason.NOT_CONFIGURED)
            else postDrafts?.restoreLocal() ?: PortResult.Failure(FailureReason.UNAVAILABLE)
        val ticket = reviewedPostNavigation.begin(currentCoroutineContext()[Job])
            ?: return PortResult.Failure(FailureReason.STALE_SESSION)
        return try {
            val result = entry.restore()
            currentCoroutineContext().ensureActive()
            var failure = (result as? PortResult.Failure)?.reason
            if (failure == null && captureOrigin != null) {
                val draft = entry.drafts.states.value
                val publication = entry.publications.states.value
                if (!reviewedPostNavigation.current(ticket) || !captureOrigin()) failure = FailureReason.CONFLICT
                else if (draft.screen == PostDraftScreen.LOCAL_LIST && draft.phase == PostDraftPhase.READY &&
                    draft.journalFormat == PostDraftJournalFormat.CURRENT && !draft.busy &&
                    draft.issue == PostDraftIssue.NONE && draft.failureReason == null && draft.pending == null &&
                    draft.reviewedAllocation == null && draft.publicationHold == null && draft.reviewedSave == null &&
                    draft.reviewedRetry == null && draft.discardConfirmation == null && draft.unsubmittedRemainders.isEmpty() &&
                    publication.pending == null && publication.allocation == null && publication.review == null &&
                    publication.retry == null && publication.unsentCancellation == null && publication.failure == null &&
                    publication.phase != PostComposerPhase.UNAVAILABLE) {
                    // This exact Share click owns one local creation. Capacity/uncertain writes
                    // are handled by the existing journal; never retry or replace a prior draft.
                    val sourceTicket = when {
                        recipeSource != null -> meals.recipeShareSource(recipeSource) {
                            reviewedPostNavigation.current(ticket) && captureOrigin()
                        }
                        savedRecipeSource != null -> cookbook.recipeShareSource(savedRecipeSource) {
                            reviewedPostNavigation.current(ticket) && captureOrigin()
                        }
                        else -> null
                    }
                    failure = if ((recipeSource != null || savedRecipeSource != null) && sourceTicket == null) FailureReason.CONFLICT
                        else (if (sourceTicket == null) entry.drafts.newLocalDraft()
                            else entry.drafts.newLocalDraftFromRecipe(sourceTicket))
                            .let { (it as? PortResult.Failure)?.reason }
                    currentCoroutineContext().ensureActive()
                    if (!captureOrigin()) failure = FailureReason.CONFLICT
                }
            }
            if (!reviewedPostNavigation.finish(ticket, failure))
                return PortResult.Failure(if (form.current()) FailureReason.CONFLICT else FailureReason.STALE_SESSION)
            if (failure != null) PortResult.Failure(failure) else PortResult.Value(entry.drafts.states.value)
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
        val drafts = reviewedPosts?.drafts ?: return@withContext PortResult.Value(Unit)
        when (val result = drafts.back()) {
            is PortResult.Failure -> result
            is PortResult.Value -> {
                // Back can cancel the opening job just after a blank local draft was
                // retained. Editor → list is not enough for this OUTER departure: hide
                // that cached child too, or it would keep the cooking underlay fenced.
                if (drafts.states.value !== result.value) return@withContext PortResult.Failure(FailureReason.CONFLICT)
                if (result.value.screen == PostDraftScreen.HIDDEN) PortResult.Value(Unit)
                else when (val hidden = drafts.back()) {
                    is PortResult.Failure -> hidden
                    is PortResult.Value -> if (drafts.states.value === hidden.value && hidden.value.screen == PostDraftScreen.HIDDEN)
                        PortResult.Value(Unit) else PortResult.Failure(FailureReason.CONFLICT)
                }
            }
        }
    }
    /** Explicit read-only entry has no meal action/busy ownership across the network wait.
     * Circle Back fences its own late reply, leaving local meal actions immediately available.
     * A cached shortcut cannot replace another actual active journey. */
    suspend fun openCircles(): PortResult<CirclesState> {
        val owner = circles
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            val retained = circlesTabReturn
            if (!reportsHidden() || !socialHidden() || !invitationRoutesHidden() || action != null ||
                (owner.states.value.screen != CirclesScreen.HIDDEN && retained == null) ||
                circleCreate?.states?.value?.screen?.let { it != CircleCreateScreen.HIDDEN } == true ||
                circleEdit?.states?.value?.screen?.let { it != CircleEditScreen.HIDDEN } == true ||
                reviewedPostNavigation.states.value.visible || cookingNavigation.value.visible ||
                cookingNavigation.value.confirmation != null || kitchenPage.value != null ||
                cookbook.states.value.screen != CookbookScreen.HIDDEN || timers?.states?.value?.visible == true ||
                postDrafts?.states?.value?.screen?.let { it != PostDraftScreen.HIDDEN } == true)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            // Consume before the fresh read. Back can retire its LOADING state without
            // a meal-form busy lock, and failure never starts a replacement read.
            circlesTabReturn = null; savedReturnsToCircles = null
            when {
                retained != null && owner.canResumeNavigation(retained) -> owner.resumeNavigation(retained)
                retained != null && owner.states.value.screen != CirclesScreen.HIDDEN -> PortResult.Value(owner.states.value)
                else -> owner.openList()
            }
        }
        // The outer dispatcher hop can delay delivery after the controller's own fence.
        // Only StateFlow identity is read here; no dispatcher-confined boundary access.
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    internal fun circlesTabDestinations(expected: CirclesState, expectedMeal: MealRequestState,
        expectedForm: MealFormState, landing: BlueprintMealLandingVisit,
        navigation: CookingNavigation): Set<BlueprintScreenId> {
        if (closed || !form.current() || session.scope.actorKind != ActorKind.ACCOUNT ||
            circles?.canSuspendForNavigation(expected) != true || action != null || acceptingRootRecipe || handingOffSavedRoot ||
            forms.value !== expectedForm || expectedForm.busy || expectedForm.values == null ||
            meals.states.value !== expectedMeal || cookingNavigation.value !== navigation ||
            !blueprintLanding.current(landing, true) || !socialHidden() || !reportEntryAvailable() ||
            timerConfirmation.value != null || mutableSavedCookingConfirmation.value != null || mutablePostSaveReview.value != null)
            return emptySet()
        return V1MobileReleaseScope.admitScreens(buildSet {
            if (blueprintCookingHomeEligible(MealScreenState.from(expectedMeal), expectedForm))
                add(BlueprintScreenId.HOME)
            if (expectedMeal.screen !in ROOT_RECIPE_SCREENS) add(BlueprintScreenId.COOKBOOK)
            val stableMeal = when (expectedMeal.screen) {
                MealFlowScreen.REQUEST -> expectedMeal.phase in setOf(MealFlowPhase.EDITING, MealFlowPhase.OFFLINE_DRAFT)
                MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE -> expectedMeal.phase == MealFlowPhase.READY
                else -> false
            }
            if (stableMeal && expectedMeal.issue == MealFlowIssue.NONE && expectedMeal.failureReason == null &&
                expectedForm.failure == null && expectedMeal.retryAtMillis == null && !expectedMeal.pendingMatchesDraft &&
                expectedMeal.pendingAdaptation == null && expectedMeal.pendingRootDraft == null &&
                expectedMeal.proposal?.dismissed != false && expectedMeal.adaptation?.dismissed != false &&
                expectedMeal.rootProposal?.dismissed != false && !interpretation.states.value.busy &&
                interpretation.states.value.proposal == null && !adaptationForms.value.visible && !rootRecipeForms.value.visible &&
                socialReads != null) {
                add(BlueprintScreenId.TODAY); add(BlueprintScreenId.INBOX); add(BlueprintScreenId.PROFILE_PLATE)
            }
        })
    }

    /** Circle departure destroys its private observations. The return ticket contains only
     * bounded screen context; showing Circles again performs fresh authorized reads. */
    internal suspend fun navigateCirclesTab(destination: BlueprintScreenId, expected: CirclesState,
        expectedMeal: MealRequestState, expectedForm: MealFormState, landing: BlueprintMealLandingVisit,
        navigation: CookingNavigation, presentationCurrent: () -> Boolean): PortResult<Unit> = withContext(dispatcher) {
        if (!V1MobileReleaseScope.allowsScreen(destination) || !presentationCurrent() ||
            destination !in circlesTabDestinations(expected, expectedMeal, expectedForm, landing, navigation))
            return@withContext cookingActionFailure()
        if (destination != BlueprintScreenId.HOME && !blueprintLanding.claim(landing, true))
            return@withContext cookingActionFailure()
        val departingLanding = blueprintLanding.states.value
        val owner = checkNotNull(circles)
        val ticket = when (val suspended = owner.suspendForNavigation(expected)) {
            is PortResult.Failure -> return@withContext suspended
            is PortResult.Value -> suspended.value
        }
        circlesTabReturn = ticket
        if (closed || !form.current() || !blueprintLanding.current(departingLanding, true) || forms.value !== expectedForm || meals.states.value !== expectedMeal ||
            cookingNavigation.value !== navigation || !owner.canResumeNavigation(ticket) || !reportEntryAvailable() ||
            !cookingChildRoutesHidden()) return@withContext cookingActionFailure()
        socialReturn = SocialReturn.MEAL; savedReturnsToCircles = null
        val result = when (destination) {
            BlueprintScreenId.HOME -> return@withContext returnMealToHome(expectedMeal, expectedForm, departingLanding)
            BlueprintScreenId.COOKBOOK -> openCookbook(expectedMeal = expectedMeal, expectedForm = expectedForm,
                navigation = navigation, returnToCircles = ticket)
            BlueprintScreenId.TODAY -> { socialReturn = SocialReturn.CIRCLES; checkNotNull(socialReads).openToday() }
            BlueprintScreenId.INBOX -> { socialReturn = SocialReturn.CIRCLES; checkNotNull(socialReads).openInbox() }
            BlueprintScreenId.PROFILE_PLATE -> { socialReturn = SocialReturn.CIRCLES; checkNotNull(socialReads).openProfilePlate() }
            else -> return@withContext cookingActionFailure()
        }
        when (result) {
            is PortResult.Failure -> result
            is PortResult.Value -> PortResult.Value(Unit)
        }
    }

    /** Canonical Cook-tab destination. Cached route navigation preserves the selected Plan,
     * durable request and unsaved form; unresolved/source-specific journeys remain ineligible. */
    private suspend fun returnMealToHome(expectedMeal: MealRequestState, expectedForm: MealFormState,
        expectedLanding: BlueprintMealLandingVisit): PortResult<Unit> {
        if (closed || !form.current() || action != null || forms.value !== expectedForm ||
            meals.states.value !== expectedMeal || !blueprintLanding.current(expectedLanding, true) ||
            !blueprintCookingHomeEligible(MealScreenState.from(expectedMeal), expectedForm) ||
            !cookingChildRoutesHidden()) return cookingActionFailure()
        val request = if (expectedMeal.screen == MealFlowScreen.REQUEST) expectedMeal else when (val returned = meals.backToDraft()) {
            is PortResult.Failure -> return returned
            is PortResult.Value -> returned.value
        }
        if (closed || !form.current() || action != null || forms.value !== expectedForm ||
            meals.states.value !== request || !blueprintLanding.current(expectedLanding, true) ||
            !blueprintMealLandingEligible(MealScreenState.from(request), expectedForm) ||
            !cookingChildRoutesHidden()) return cookingActionFailure()
        val moved = if (expectedLanding.home && !expectedLanding.offline)
            blueprintLanding.claim(expectedLanding, true) else blueprintLanding.showHome(expectedLanding, true)
        return if (moved) PortResult.Value(Unit) else cookingActionFailure()
    }
    // Account overlays retire underlying action callbacks until explicit departure.
    private fun reportsHidden(exceptMemory: Boolean = false, exceptCollections: Boolean = false): Boolean = reports?.states?.value?.screen?.let { it == ReportScreen.HIDDEN } != false &&
        reactions?.states?.value?.phase?.let { it == ReactionPhase.HIDDEN } != false &&
        postPlacement?.states?.value?.phase?.let { it == PostPlacementPhase.HIDDEN } != false &&
        profileEdit?.states?.value?.phase?.let { it == AccountProfileEditPhase.HIDDEN } != false &&
        circleMemberRemoval?.states?.value?.screen?.let { it == CircleMemberRemovalScreen.HIDDEN } != false &&
        circleOwnershipTransfer?.states?.value?.screen?.let { it == CircleOwnershipTransferScreen.HIDDEN } != false &&
        (exceptMemory || mealMemory?.states?.value?.screen?.let { it == MealMemoryScreen.HIDDEN } != false) &&
        (exceptCollections || collections?.states?.value?.screen?.let { it == CollectionReadScreen.HIDDEN } != false)
    private fun socialHidden(): Boolean = socialReads?.states?.value?.screen?.let { it == SocialReadScreen.HIDDEN } != false &&
        conversations?.states?.value?.phase?.let { it == ThreadPhase.HIDDEN } != false &&
        postDeletion?.states?.value?.phase?.let { it == PostDeletionPhase.HIDDEN } != false &&
        postPlacement?.states?.value?.phase?.let { it == PostPlacementPhase.HIDDEN } != false &&
        recipeRequests?.states?.value?.phase?.let { it == RecipeRequestPhase.HIDDEN } != false &&
        reactions?.states?.value?.phase?.let { it == ReactionPhase.HIDDEN } != false &&
        sessionControls?.states?.value?.phase?.let { it == SessionControlsPhase.HIDDEN } != false &&
        notifications?.states?.value?.phase?.let { it == NotificationSettingsPhase.HIDDEN } != false &&
        notificationReads?.states?.value?.phase?.let { it == NotificationReadPhase.HIDDEN } != false &&
        remixes?.states?.value?.phase?.let { it == RemixTrailPhase.HIDDEN } != false

    /** Reads only after an explicit original-screen action. Keep the completed cook and
     * meal draft intact underneath the independently retained feedback/memory owner. */
    internal suspend fun openMealFeedback(expected: CookingFlowState, navigation: CookingNavigation,
        reuse: Boolean = false): PortResult<com.feedme.mealflow.memory.MealMemoryState> = withContext(dispatcher) {
        val owner = mealMemory ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (action != null || !cookingActionCurrent(expected, navigation)) return@withContext cookingActionFailure()
        val view = CookingScreenState.from(expected)
        val cookId = view.sessionId ?: return@withContext cookingActionFailure()
        val planId = view.plan?.id?.value ?: return@withContext cookingActionFailure()
        if (!view.done || !view.serverAcknowledged || view.localPendingCount != 0 || view.pending.isNotEmpty())
            return@withContext cookingActionFailure()
        if (reuse) owner.openReuse(cookId, planId) else owner.openFeedback(cookId, planId)
    }

    internal suspend fun openCookingMemory(expected: CookingFlowState, navigation: CookingNavigation)
        : PortResult<com.feedme.mealflow.memory.MealMemoryState> = withContext(dispatcher) {
        val owner = mealMemory ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (action != null || !cookingActionCurrent(expected, navigation)) return@withContext cookingActionFailure()
        owner.openHistory()
    }

    internal suspend fun openHomeMemory(expected: MealRequestState, expectedForm: MealFormState,
        claimedLanding: BlueprintMealLandingVisit): PortResult<com.feedme.mealflow.memory.MealMemoryState> = withContext(dispatcher) {
        val owner = mealMemory ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (closed || !form.current() || action != null || !reportsHidden() || !socialHidden() ||
            meals.states.value !== expected || forms.value !== expectedForm || !claimedLanding.home ||
            !blueprintLanding.current(claimedLanding, true) ||
            !blueprintMealLandingEligible(MealScreenState.from(expected), expectedForm) ||
            cookingNavigation.value.visible || !cookingChildRoutesHidden()) return@withContext cookingActionFailure()
        owner.openHistory()
    }

    /** An explicit next-use card opens a real recipe without replacing the current meal.
     * Keep the option owner live during resolution; hide it only after the paired source
     * has been retained. The return ticket never grants planning, saving or cooking rights. */
    internal suspend fun openReuseRecipe(expectedMemory: MealMemoryState, selection: MealReuseSelection,
        expectedMeal: MealRequestState, expectedForm: MealFormState, expectedCooking: CookingFlowState,
        navigation: CookingNavigation): PortResult<MealRequestState> = withContext(dispatcher) {
        val owner = mealMemory ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        fun originCurrent() = !closed && form.current() && !forms.value.dirty &&
            owner.states.value === expectedMemory && owner.isCurrent(selection) &&
            cooking.states.value === expectedCooking && cookingNavigation.value === navigation &&
            navigation.confirmation == null && cookingChildRoutesHidden(exceptMemory = true) && savedCookingIdle()
        if (action != null || acceptingRootRecipe || handingOffSavedRoot || mutableSavedCookingConfirmation.value != null ||
            meals.states.value !== expectedMeal || forms.value !== expectedForm || expectedForm.busy ||
            expectedForm.values == null || !originCurrent()) return@withContext cookingActionFailure()
        val captured = form.capture() ?: return@withContext cookingActionFailure()
        val token = Any(); action = token; acceptingRootRecipe = true; form.busy(true)
        try {
            val opened = meals.openReuseRecipe(selection, expectedMeal)
            if (opened is PortResult.Failure) return@withContext opened
            val current = (opened as PortResult.Value).value
            val source = current.catalogSource ?: return@withContext cookingActionFailure()
            if (!originCurrent() || !form.matches(captured.first) || meals.states.value !== current)
                return@withContext cookingActionFailure()
            val labelIds = recipeLabelIds(source.recipe)
            if (labelIds.isNotEmpty()) withTimeoutOrNull(2_000L) { ingredients.resolveLabels(labelIds) }
            if (!originCurrent() || !form.matches(captured.first) || meals.states.value !== current)
                return@withContext cookingActionFailure()
            when (val hidden = owner.hideReuseSource(expectedMemory, selection)) {
                is PortResult.Failure -> hidden
                is PortResult.Value -> {
                    planSourceReview = null
                    reuseRootReturn = hidden.value to source
                    cookingUi.leave()
                    rootRecipeForm.leave()
                    opened
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PortResult.Failure(FailureReason.UNAVAILABLE) }
        finally {
            acceptingRootRecipe = false
            if (action === token) { action = null; form.busy(false) }
        }
    }

    /** Explicit account-only tab entry. Preserve meal drafts and leave their exact rendered
     * callbacks behind before any network wait. Construction/restoration never opens a feed. */
    internal suspend fun openSocial(destination: com.feedme.app.blueprint.BlueprintScreenId,
        expectedMeal: MealRequestState, expectedForm: MealFormState,
        expectedLanding: BlueprintMealLandingVisit): PortResult<SocialReadState> = withContext(dispatcher) {
        val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (destination !in setOf(com.feedme.app.blueprint.BlueprintScreenId.TODAY,
                com.feedme.app.blueprint.BlueprintScreenId.INBOX, com.feedme.app.blueprint.BlueprintScreenId.PROFILE_PLATE) ||
            !reportsHidden() || action != null || reader.states.value.screen != SocialReadScreen.HIDDEN ||
            meals.states.value !== expectedMeal || forms.value !== expectedForm ||
            !expectedLanding.home || !blueprintLanding.current(expectedLanding, true) ||
            !blueprintMealLandingEligible(MealScreenState.from(expectedMeal), expectedForm) ||
            cookingNavigation.value.visible || !cookingChildRoutesHidden())
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        if (!blueprintLanding.claim(expectedLanding, true))
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        socialReturn = SocialReturn.MEAL
        when (destination) {
            com.feedme.app.blueprint.BlueprintScreenId.TODAY -> reader.openToday()
            com.feedme.app.blueprint.BlueprintScreenId.INBOX -> reader.openInbox()
            else -> reader.openProfilePlate()
        }
    }

    /** Main-tab admission does not require clean text: browsing never saves, discards or
     * replaces the form/Plan. In-flight work and another child still own their UI. */
    internal fun mainMealTabDestinations(expectedMeal: MealRequestState, expectedForm: MealFormState,
        expectedLanding: BlueprintMealLandingVisit): Set<BlueprintScreenId> {
        if (closed || !form.current() || action != null || expectedForm.busy || expectedForm.values == null ||
            meals.states.value !== expectedMeal || forms.value !== expectedForm ||
            !blueprintLanding.current(expectedLanding, true) ||
            expectedMeal.screen !in setOf(MealFlowScreen.REQUEST, MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE) ||
            expectedMeal.phase in setOf(MealFlowPhase.LOADING, MealFlowPhase.RESOLVING, MealFlowPhase.UNAVAILABLE) ||
            expectedMeal.issue == MealFlowIssue.SESSION_UNAVAILABLE ||
            interpretation.states.value.busy || interpretation.states.value.proposal != null ||
            adaptationForms.value.visible || rootRecipeForms.value.visible ||
            !reportEntryAvailable() || !cookingChildRoutesHidden() ||
            mutablePostSaveReview.value != null || mutableSavedCookingConfirmation.value != null)
            return emptySet()
        return V1MobileReleaseScope.admitScreens(buildSet {
            // Cook is already the selected tab on recommendations/recipe. Do not reset
            // its selected Plan just to make that active-tab affordance navigate.
            if (blueprintCookingHomeEligible(MealScreenState.from(expectedMeal), expectedForm) &&
                !(expectedLanding.home && !expectedLanding.offline &&
                    blueprintMealLandingEligible(MealScreenState.from(expectedMeal), expectedForm)))
                add(BlueprintScreenId.HOME)
            if (cookbookEntryCurrent(cookbook.states.value)) add(BlueprintScreenId.COOKBOOK)
            val stableMeal = when (expectedMeal.screen) {
                MealFlowScreen.REQUEST -> expectedMeal.phase in setOf(MealFlowPhase.EDITING, MealFlowPhase.OFFLINE_DRAFT)
                MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE -> expectedMeal.phase == MealFlowPhase.READY
                else -> false
            }
            if (stableMeal && expectedMeal.issue == MealFlowIssue.NONE && expectedMeal.failureReason == null &&
                expectedForm.failure == null && expectedMeal.retryAtMillis == null && !expectedMeal.pendingMatchesDraft &&
                expectedMeal.pendingAdaptation == null && expectedMeal.pendingRootDraft == null &&
                expectedMeal.proposal?.dismissed != false && expectedMeal.adaptation?.dismissed != false &&
                expectedMeal.rootProposal?.dismissed != false && session.scope.actorKind == ActorKind.ACCOUNT && socialReads != null) {
                add(BlueprintScreenId.TODAY); add(BlueprintScreenId.INBOX); add(BlueprintScreenId.PROFILE_PLATE)
            }
        })
    }

    /** One actual click retires its landing visit before any suspension. Social Back/Cook
     * uncovers the retained meal screen; no fabricated return state or automatic restore. */
    internal suspend fun navigateMainMealTab(destination: BlueprintScreenId, expectedMeal: MealRequestState,
        expectedForm: MealFormState, expectedLanding: BlueprintMealLandingVisit): PortResult<Unit> = withContext(dispatcher) {
        if (!V1MobileReleaseScope.allowsScreen(destination) ||
            destination !in mainMealTabDestinations(expectedMeal, expectedForm, expectedLanding))
            return@withContext cookingActionFailure()
        if (destination == BlueprintScreenId.HOME) {
            return@withContext returnMealToHome(expectedMeal, expectedForm, expectedLanding)
        }
        if (!blueprintLanding.claim(expectedLanding, true)) return@withContext cookingActionFailure()
        socialReturn = SocialReturn.MEAL
        savedReturnsToCircles = null
        val result = when (destination) {
            BlueprintScreenId.COOKBOOK -> openCookbook(cookbook.states.value, cookbookQuery.value,
                expectedMeal, expectedForm, cookingNavigation.value)
            BlueprintScreenId.TODAY -> checkNotNull(socialReads).openToday()
            BlueprintScreenId.INBOX -> checkNotNull(socialReads).openInbox()
            BlueprintScreenId.PROFILE_PLATE -> checkNotNull(socialReads).openProfilePlate()
            else -> return@withContext cookingActionFailure()
        }
        when (result) {
            is PortResult.Failure -> result
            is PortResult.Value -> PortResult.Value(Unit)
        }
    }

    /** Original Saved tabs never replace an active command, confirmation or cooking flow. */
    internal fun savedTabDestinations(expected: CookbookState, expectedQuery: String,
        expectedMeal: MealRequestState, expectedForm: MealFormState,
        expectedLanding: BlueprintMealLandingVisit, navigation: CookingNavigation): Set<BlueprintScreenId> {
        if (action != null || expectedForm.busy || expectedForm.values == null ||
            !cookbookActionCurrent(expected, expectedQuery) || !cookbook.canSuspendList(expected) ||
            meals.states.value !== expectedMeal || forms.value !== expectedForm ||
            cookingNavigation.value !== navigation || !blueprintLanding.current(expectedLanding, true) ||
            !reportsHidden() || mutablePostSaveReview.value != null)
            return emptySet()
        return V1MobileReleaseScope.admitScreens(buildSet {
            if (blueprintCookingHomeEligible(MealScreenState.from(expectedMeal), expectedForm))
                add(BlueprintScreenId.HOME)
            val stableMeal = when (expectedMeal.screen) {
                MealFlowScreen.REQUEST -> expectedMeal.phase in setOf(MealFlowPhase.EDITING, MealFlowPhase.OFFLINE_DRAFT)
                MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE -> expectedMeal.phase == MealFlowPhase.READY
                else -> false
            }
            if (!navigation.visible && stableMeal && expectedMeal.issue == MealFlowIssue.NONE &&
                expectedMeal.failureReason == null && expectedForm.failure == null && expectedMeal.retryAtMillis == null &&
                !expectedMeal.pendingMatchesDraft && expectedMeal.pendingAdaptation == null && expectedMeal.pendingRootDraft == null &&
                expectedMeal.proposal?.dismissed != false && expectedMeal.adaptation?.dismissed != false &&
                expectedMeal.rootProposal?.dismissed != false && !interpretation.states.value.busy &&
                interpretation.states.value.proposal == null && !adaptationForms.value.visible && !rootRecipeForms.value.visible &&
                session.scope.actorKind == ActorKind.ACCOUNT && socialReads != null) {
                add(BlueprintScreenId.TODAY); add(BlueprintScreenId.INBOX); add(BlueprintScreenId.PROFILE_PLATE)
            }
        })
    }

    internal suspend fun navigateSavedTab(destination: BlueprintScreenId, expected: CookbookState,
        expectedQuery: String, expectedMeal: MealRequestState, expectedForm: MealFormState,
        expectedLanding: BlueprintMealLandingVisit, navigation: CookingNavigation,
        presentationCurrent: () -> Boolean): PortResult<Unit> = withContext(dispatcher) {
        if (!V1MobileReleaseScope.allowsScreen(destination) || !presentationCurrent() ||
            destination !in savedTabDestinations(expected, expectedQuery,
                expectedMeal, expectedForm, expectedLanding, navigation)) return@withContext cookingActionFailure()
        // Retire the old underlay before a sibling read. Cook/Home performs its exact cached
        // route move only after Saved is hidden, with no form edit, Plan replacement or Save.
        if (destination != BlueprintScreenId.HOME && !blueprintLanding.claim(expectedLanding, true))
            return@withContext cookingActionFailure()
        val departingLanding = blueprintLanding.states.value
        when (val hidden = cookbook.suspendList(expected)) {
            is PortResult.Failure -> return@withContext hidden
            is PortResult.Value -> savedTabReturn = hidden.value
        }
        if (closed || !form.current() || !blueprintLanding.current(departingLanding, true) || meals.states.value !== expectedMeal || forms.value !== expectedForm ||
            cookingNavigation.value !== navigation || !reportsHidden() || !cookingChildRoutesHidden() ||
            savedTabReturn?.let(cookbook::canResumeList) != true) return@withContext cookingActionFailure()
        socialReturn = if (destination == BlueprintScreenId.HOME) SocialReturn.MEAL else SocialReturn.SAVED
        if (destination == BlueprintScreenId.HOME) savedReturnsToCircles = null
        if (destination == BlueprintScreenId.HOME)
            return@withContext returnMealToHome(expectedMeal, expectedForm, departingLanding)
        // Do not hold form.busy over the read: Social Back can retire a late network reply.
        val reader = checkNotNull(socialReads)
        val opened = when (destination) {
            BlueprintScreenId.TODAY -> reader.openToday()
            BlueprintScreenId.INBOX -> reader.openInbox()
            BlueprintScreenId.PROFILE_PLATE -> reader.openProfilePlate()
            else -> return@withContext cookingActionFailure()
        }
        when (opened) {
            is PortResult.Failure -> opened
            is PortResult.Value -> PortResult.Value(Unit)
        }
    }

    /** The exact self-Plate is only an entry observation. The profile owner keeps its own
     * account reads, edits and durable originals after this feed observation expires. */
    internal fun canOpenSocialProfileEdit(expected: SocialReadState): Boolean {
        val owner = profileEdit ?: return false
        val selfId = (expected.selfProfile?.field("id") as? WireField.Value)?.value?.stringOrNull()
        return !closed && form.current() && !forms.value.busy &&
            session.scope.actorKind == ActorKind.ACCOUNT && socialReads?.isCurrent(expected) == true &&
            expected.screen == SocialReadScreen.PROFILE_PLATE &&
            expected.phase in setOf(SocialReadPhase.READY, SocialReadPhase.EMPTY) &&
            !selfId.isNullOrBlank() && expected.profileUserId == selfId &&
            owner.states.value.phase == AccountProfileEditPhase.HIDDEN && reportEntryAvailable() &&
            mutablePostSaveReview.value == null && mutableSavedCookingConfirmation.value == null && timerConfirmation.value == null &&
            circles?.states?.value?.screen?.let { it != CirclesScreen.HIDDEN } != true &&
            conversations?.states?.value?.phase?.let { it != ThreadPhase.HIDDEN } != true &&
            postDeletion?.states?.value?.phase?.let { it != PostDeletionPhase.HIDDEN } != true &&
            recipeRequests?.states?.value?.phase?.let { it != RecipeRequestPhase.HIDDEN } != true &&
            notificationReads?.states?.value?.phase?.let { it != NotificationReadPhase.HIDDEN } != true &&
            remixes?.states?.value?.phase?.let { it != RemixTrailPhase.HIDDEN } != true
    }

    internal suspend fun openSocialProfileEdit(expected: SocialReadState): PortResult<AccountProfileEditState> = withContext(dispatcher) {
        if (!canOpenSocialProfileEdit(expected)) return@withContext cookingActionFailure()
        // open discovers existing originals or loads the actual profile. It never saves,
        // resets an existing draft, or advances an onboarding checkpoint.
        checkNotNull(profileEdit).open()
    }

    /** Explicit Back retires only this editor, then reads My Plate again. No receipt or
     * unconfirmed input is merged into the old social snapshot as an optimistic profile. */
    internal suspend fun closeSocialProfileEdit(expected: AccountProfileEditState,
        expectedSocial: SocialReadState): PortResult<Unit> = withContext(dispatcher) {
        val owner = profileEdit ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (owner.states.value !== expected || expected.phase == AccountProfileEditPhase.HIDDEN ||
            reader.states.value !== expectedSocial || expectedSocial.screen != SocialReadScreen.PROFILE_PLATE)
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        when (val departed = owner.leave(expected)) {
            is PortResult.Failure -> departed
            is PortResult.Value -> {
                if (!closed && form.current() && owner.states.value === departed.value &&
                    reader.states.value === expectedSocial) reader.openProfilePlate()
                PortResult.Value(Unit)
            }
        }
    }

    /** Presentation continuation is only an admission fence. Real account/controllers still
     * authorize each endpoint; never hold this old view predicate after intentional departure. */
    internal suspend fun dispatchSocialPresentation(presentationCurrent: () -> Boolean,
        dispatch: suspend () -> Unit) = withContext(dispatcher) {
        if (!closed && form.current() && presentationCurrent()) dispatch()
    }

    internal suspend fun leaveSocial(expected: SocialReadState): PortResult<Unit> = withContext(dispatcher) {
        val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (action != null || mutablePostSaveReview.value != null ||
            profileEdit?.states?.value?.phase?.let { it != AccountProfileEditPhase.HIDDEN } == true ||
            notificationReads?.states?.value?.phase?.let { it != NotificationReadPhase.HIDDEN } == true) return@withContext cookingActionFailure()
        reader.leave(expected)
    }

    private suspend fun leaveSocialForNavigation(expected: SocialReadState): PortResult<SocialReadState> = withContext(dispatcher) {
        val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (action != null || mutablePostSaveReview.value != null ||
            profileEdit?.states?.value?.phase?.let { it != AccountProfileEditPhase.HIDDEN } == true ||
            notificationReads?.states?.value?.phase?.let { it != NotificationReadPhase.HIDDEN } == true) return@withContext cookingActionFailure()
        reader.leaveForNavigation(expected)
    }

    /** An observed notification is only a navigation seed. THREAD performs its own fresh
     * account/membership/block reads; no notification or message read ACK is sent here. */
    internal suspend fun openNotificationThread(expected: SocialReadState, notificationId: String): PortResult<com.feedme.mealflow.conversation.ThreadState> =
        withContext(dispatcher) {
            val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            val threads = conversations ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (action != null || forms.value.busy || !reportsHidden() || !reader.isCurrent(expected) ||
                threads.states.value.phase != ThreadPhase.HIDDEN ||
                notificationReads?.states?.value?.phase?.let { it != NotificationReadPhase.HIDDEN } == true)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            val chosen = reader.selectNotificationThread(expected, notificationId)
            if (chosen !is PortResult.Value) return@withContext chosen as PortResult.Failure
            if (!chosen.value.isCurrentForNavigation || threads.states.value.phase != ThreadPhase.HIDDEN)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            val result = threads.open(chosen.value.threadId)
            if (!form.current() || !chosen.value.isCurrentForNavigation) {
                if (result is PortResult.Value && threads.states.value === result.value) threads.leave(result.value)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            }
            result
        }

    /** Resolve the real post source while the exact Social selection is still live. */
    internal suspend fun openSocialMakeMine(expected: SocialReadState) = perform(adaptation = true,
        admission = { socialRecipeEntryCurrent(expected) && meals.states.value.postMakeMineAvailable && !forms.value.dirty }) {
        val reader = checkNotNull(socialReads)
        val selection = reader.selectPostAction(expected, "makeMine")
        if (selection is PortResult.Failure) return@perform selection
        val opened = meals.openPostMakeMineSource((selection as PortResult.Value).value, meals.states.value)
        if (opened is PortResult.Value) {
            if (!reader.isCurrent(expected)) return@perform cookingActionFailure()
            val source = opened.value.postSource ?: return@perform cookingActionFailure()
            val left = reader.leave(expected)
            if (left is PortResult.Failure) return@perform left
            socialReturn = SocialReturn.MEAL
            savedReturnsToCircles = null
            planSourceReview = null
            rootRecipeForm.leave()
            val ids = recipeLabelIds(source.recipe)
            if (ids.isNotEmpty()) withTimeoutOrNull(2_000L) { ingredients.resolveLabels(ids) }
        }
        opened
    }

    internal suspend fun prepareSocialSave(expected: SocialReadState) = perform(adaptation = true,
        admission = { socialRecipeEntryCurrent(expected) && cookbook.states.value.screen == CookbookScreen.HIDDEN &&
            cookbook.states.value.pending == null }) {
        val selected = checkNotNull(socialReads).selectPostAction(expected, "save")
        if (selected is PortResult.Failure) return@perform selected
        when (val prepared = cookbook.preparePostSave((selected as PortResult.Value).value)) {
            is PortResult.Failure -> prepared
            is PortResult.Value -> {
                if (!socialReads.isCurrent(expected) || !prepared.value.isCurrent) return@perform cookingActionFailure()
                PortResult.Value(SocialRecipeSaveReview(expected, prepared.value).also { mutablePostSaveReview.value = it })
            }
        }
    }

    internal fun cancelSocialSave(expected: SocialRecipeSaveReview): PortResult<Unit> {
        if (closed || !form.current() || action != null || mutablePostSaveReview.value !== expected) return cookingActionFailure()
        mutablePostSaveReview.value = null
        return PortResult.Value(Unit)
    }

    internal suspend fun confirmSocialSave(expected: SocialRecipeSaveReview): PortResult<CookbookState> = withContext(dispatcher) {
        val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (closed || !form.current() || action != null || mutablePostSaveReview.value !== expected ||
            !expected.prepared.isCurrent || !reader.isCurrent(expected.origin)) return@withContext cookingActionFailure()
        val token = Any(); action = token; form.busy(true)
        try {
            val result = cookbook.confirmPostSave(expected.prepared)
            if (result is PortResult.Failure) form.fail(result.reason)
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { form.fail(FailureReason.UNAVAILABLE); PortResult.Failure(FailureReason.UNAVAILABLE) }
        finally {
            // A sent/unknown original belongs to Cookbook recovery. Never manufacture a
            // success or keep a second actionable Save review in front of that original.
            if (action === token) {
                if (cookbook.states.value.pending != null || cookbook.states.value.screen != CookbookScreen.HIDDEN) {
                    withContext(NonCancellable) { reader.leave(reader.states.value) }
                    mutablePostSaveReview.value = null
                    savedTabReturn = null; socialReturn = SocialReturn.MEAL; savedReturnsToCircles = null
                }
                action = null; form.busy(false)
            }
        }
    }

    private fun socialRecipeEntryCurrent(expected: SocialReadState) = !closed && form.current() &&
        socialReads?.isCurrent(expected) == true && expected.screen in setOf(SocialReadScreen.POST, SocialReadScreen.STORY) &&
        expected.phase == SocialReadPhase.READY && !cookingNavigation.value.visible && cookingNavigation.value.confirmation == null &&
        meals.states.value.screen !in ROOT_RECIPE_SCREENS && reportsHidden() && mutableSavedCookingConfirmation.value == null &&
        mutablePostSaveReview.value == null

    /** One explicit handoff consumes the rendered social observation before opening a sibling. */
    internal suspend fun returnFromSocial(expected: SocialReadState): PortResult<Unit> =
        if (socialReturn == SocialReturn.MEMORY) leaveMemorySocial(expected, destination = null)
        else if (socialReturn == SocialReturn.COOKING) leaveCookingSocial(expected, destination = null)
        else leaveSocialFor(expected, when (socialReturn) {
            SocialReturn.SAVED -> BlueprintScreenId.COOKBOOK
            SocialReturn.CIRCLES -> BlueprintScreenId.CIRCLES
            SocialReturn.MEAL, SocialReturn.COOKING, SocialReturn.MEMORY -> BlueprintScreenId.HOME
        }, if (socialReturn == SocialReturn.SAVED) savedReturnsToCircles else null, preserveOfflineHome = true)

    /** Back rereads history; sibling tabs expose actual retained owners, never snapshots. */
    private suspend fun leaveMemorySocial(expected: SocialReadState, destination: BlueprintScreenId?): PortResult<Unit> = withContext(dispatcher) {
        val owner = mealMemory ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val hiddenMemory = memorySocialReturn ?: return@withContext cookingActionFailure()
        fun current() = !closed && form.current() && socialReturn == SocialReturn.MEMORY &&
            memorySocialReturn === hiddenMemory && owner.isCurrent(hiddenMemory) &&
            hiddenMemory.screen == MealMemoryScreen.HIDDEN && action == null &&
            !forms.value.busy && !acceptingRootRecipe && !handingOffSavedRoot &&
            mutableSavedCookingConfirmation.value == null && mutablePostSaveReview.value == null
        if (destination !in setOf(null, BlueprintScreenId.HOME, BlueprintScreenId.COOKBOOK) || !current() ||
            destination == BlueprintScreenId.HOME && !socialCookAvailable)
            return@withContext cookingActionFailure()
        when (val left = leaveSocialForNavigation(expected)) {
            is PortResult.Failure -> left
            is PortResult.Value -> {
                val hiddenSocial = left.value
                if (!current() || reader.states.value !== hiddenSocial || hiddenSocial.screen != SocialReadScreen.HIDDEN || !cookbookRouteCurrent())
                    return@withContext cookingActionFailure()
                if (destination == BlueprintScreenId.HOME) {
                    val saved = cookbook.states.value
                    if (saved.screen != CookbookScreen.HIDDEN) {
                        if (!cookbook.canSuspendList(saved)) return@withContext cookingActionFailure()
                        // Preserve the actual Saved page/query; detail or recovery was
                        // rejected before Social departure, never hidden destructively.
                        when (val suspended = cookbook.suspendList(saved)) {
                            is PortResult.Failure -> return@withContext suspended
                            is PortResult.Value -> {
                                if (!current() || !cookbook.canResumeList(suspended.value) ||
                                    reader.states.value !== hiddenSocial || !cookbookRouteCurrent())
                                    return@withContext cookingActionFailure()
                                savedTabReturn = suspended.value
                            }
                        }
                    }
                }
                socialReturn = SocialReturn.MEAL
                when (destination) {
                    null -> {
                        // No action/form-busy lock over GET: the actual Memory Back can
                        // retire this loading visit and its late network result.
                        when (val opened = owner.openHistory()) {
                            is PortResult.Failure -> opened
                            is PortResult.Value -> if (!closed && form.current() && owner.states.value === opened.value)
                                PortResult.Value(Unit) else cookingActionFailure()
                        }
                    }
                    BlueprintScreenId.COOKBOOK -> {
                        val actual = cookbook.states.value
                        if (actual.screen != CookbookScreen.HIDDEN) PortResult.Value(Unit)
                        else openMemorySocialSaved(hiddenSocial, hiddenMemory)
                    }
                    else -> PortResult.Value(Unit)
                }
            }
        }
    }

    /** Hidden Saved follows its existing recovery-first path without touching meal editors. */
    private suspend fun openMemorySocialSaved(hiddenSocial: SocialReadState,
        hiddenMemory: MealMemoryState): PortResult<Unit> {
        val expectedMeal = meals.states.value; val expectedForm = forms.value
        val expectedCooking = cooking.states.value; val navigation = cookingNavigation.value
        val expectedCookbook = cookbook.states.value; val query = cookbookQuery.value
        val capture = form.capture() ?: return cookingActionFailure()
        val token = Any(); action = token; form.busy(true)
        fun current() = !closed && form.current() && action === token && form.matches(capture.first) &&
            forms.value.values == expectedForm.values && forms.value.dirty == expectedForm.dirty &&
            forms.value.searchText == expectedForm.searchText && meals.states.value === expectedMeal &&
            cooking.states.value === expectedCooking && cookingNavigation.value === navigation && cookbookQuery.value == query &&
            socialReads?.states?.value === hiddenSocial && hiddenSocial.screen == SocialReadScreen.HIDDEN &&
            mealMemory?.states?.value === hiddenMemory && hiddenMemory.screen == MealMemoryScreen.HIDDEN &&
            cookbookRouteCurrent() && !acceptingRootRecipe && !handingOffSavedRoot &&
            mutableSavedCookingConfirmation.value == null && mutablePostSaveReview.value == null
        try {
            if (!current() || cookbook.states.value !== expectedCookbook) return cookingActionFailure()
            val result = openCookbookOwned(query)
            if (!current() || result is PortResult.Value && cookbook.states.value !== result.value) return cookingActionFailure()
            return when (result) {
                is PortResult.Failure -> { form.fail(result.reason); result }
                is PortResult.Value -> PortResult.Value(Unit)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (current()) form.fail(FailureReason.UNAVAILABLE)
            return PortResult.Failure(FailureReason.UNAVAILABLE)
        } finally { if (action === token) { action = null; form.busy(false) } }
    }

    /** null is Back; explicit tab destinations must still own the exact retained parents. */
    private suspend fun leaveCookingSocial(expected: SocialReadState, destination: BlueprintScreenId?): PortResult<Unit> = withContext(dispatcher) {
        val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val retained = cookingSocialReturn ?: return@withContext cookingActionFailure()
        if (destination !in setOf(null, BlueprintScreenId.HOME, BlueprintScreenId.COOKBOOK) ||
            socialReturn != SocialReturn.COOKING || destination != null && !cookingSocialReturnCurrent(retained))
            return@withContext cookingActionFailure()
        when (val left = leaveSocialForNavigation(expected)) {
            is PortResult.Failure -> left
            is PortResult.Value -> {
                if (closed || !form.current() || socialReturn != SocialReturn.COOKING || cookingSocialReturn !== retained ||
                    reader.states.value !== left.value || left.value.screen != SocialReadScreen.HIDDEN ||
                    destination != null && !cookingSocialReturnCurrent(retained)) return@withContext cookingActionFailure()
                // Back exposes the ACTUAL retained cook, including any later sync. Cook
                // deliberately hides only this exact navigation, never the cooking owner.
                if (destination == BlueprintScreenId.HOME) cookingUi.leave()
                socialReturn = SocialReturn.MEAL
                if (destination == BlueprintScreenId.COOKBOOK) {
                    // The original Saved tab is another overlay above this cook. Its
                    // normal hidden-entry gate is intentionally not used: it would reject
                    // visible cooking after Social had already departed.
                    val hidden = left.value
                    val token = Any(); action = token; form.busy(true)
                    fun current() = !closed && form.current() && action === token &&
                        form.matches(retained.formGeneration) && forms.value.values == retained.form.values &&
                        forms.value.dirty == retained.form.dirty && forms.value.searchText == retained.form.searchText &&
                        cooking.states.value === retained.cooking && cookingNavigation.value === retained.navigation &&
                        meals.states.value === retained.meal && cookbookQuery.value == retained.query &&
                        reader.states.value === hidden && hidden.screen == SocialReadScreen.HIDDEN &&
                        cookbookRouteCurrent() && !acceptingRootRecipe && !handingOffSavedRoot &&
                        mutableSavedCookingConfirmation.value == null && mutablePostSaveReview.value == null
                    try {
                        if (!current() || cookbook.states.value !== retained.cookbook) return@withContext cookingActionFailure()
                        val opened = openCookbookOwned(retained.query)
                        if (!current() || opened is PortResult.Value && cookbook.states.value !== opened.value)
                            return@withContext cookingActionFailure()
                        when (opened) {
                            is PortResult.Failure -> { form.fail(opened.reason); opened }
                            is PortResult.Value -> PortResult.Value(Unit)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if (current()) form.fail(FailureReason.UNAVAILABLE)
                        PortResult.Failure(FailureReason.UNAVAILABLE)
                    } finally { if (action === token) { action = null; form.busy(false) } }
                } else PortResult.Value(Unit)
            }
        }
    }

    internal suspend fun leaveSocialFor(expected: SocialReadState,
        destination: com.feedme.app.blueprint.BlueprintScreenId,
        savedCircleOrigin: CirclesListReturnTicket? = null,
        preserveOfflineHome: Boolean = false): PortResult<Unit> = withContext(dispatcher) {
        if (destination !in setOf(com.feedme.app.blueprint.BlueprintScreenId.HOME,
                com.feedme.app.blueprint.BlueprintScreenId.COOKBOOK, com.feedme.app.blueprint.BlueprintScreenId.CIRCLES,
                com.feedme.app.blueprint.BlueprintScreenId.CAPTURE))
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        // These sibling entries do not support an owned cooking overlay. Reject a
        // stale callback before departing Social or changing the retained source.
        if (cookingNavigation.value.visible && destination in setOf(BlueprintScreenId.CIRCLES, BlueprintScreenId.CAPTURE))
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        if (socialReturn == SocialReturn.MEMORY) {
            if (destination in setOf(BlueprintScreenId.CIRCLES, BlueprintScreenId.CAPTURE))
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            return@withContext leaveMemorySocial(expected, destination)
        }
        if (socialReturn == SocialReturn.COOKING && destination in setOf(BlueprintScreenId.HOME, BlueprintScreenId.COOKBOOK))
            return@withContext leaveCookingSocial(expected, destination)
        val offlineHome = blueprintLanding.states.value.takeIf {
            !preserveOfflineHome && destination == BlueprintScreenId.HOME && it.offline
        }
        when (val departed = leaveSocial(expected)) {
            is PortResult.Failure -> departed
            is PortResult.Value -> {
                // Explicit Cook leaves the Offline subroute; ordinary Back retains it.
                // Never substitute a newer Home visit after the Social departure.
                if (offlineHome != null && (!form.current() || !blueprintLanding.closeOffline(offlineHome, !closed)))
                    return@withContext cookingActionFailure()
                socialReturn = SocialReturn.MEAL
                savedReturnsToCircles = null
                when (destination) {
                com.feedme.app.blueprint.BlueprintScreenId.CAPTURE -> when (val opened = openPostDrafts()) {
                    is PortResult.Failure -> opened
                    is PortResult.Value -> PortResult.Value(Unit)
                }
                com.feedme.app.blueprint.BlueprintScreenId.COOKBOOK -> when (val opened = openCookbook(returnToCircles = savedCircleOrigin)) {
                    is PortResult.Failure -> opened
                    is PortResult.Value -> PortResult.Value(Unit)
                }
                com.feedme.app.blueprint.BlueprintScreenId.CIRCLES -> when (val opened = openCircles()) {
                    is PortResult.Failure -> opened
                    is PortResult.Value -> PortResult.Value(Unit)
                }
                else -> PortResult.Value(Unit)
                }
            }
        }
    }

    /** Thread stays owned underneath the independent report form. This entry neither sends
     * a message nor submits a complaint, and never hides/restores the thread or its draft. */
    internal suspend fun reportThreadMessage(expected: ThreadState, message: ThreadMessage,
        presentationCurrent: () -> Boolean): PortResult<ReportState> {
        val reader = conversations
        val owner = reports
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (reader == null || owner == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            fun entryCurrent() = presentationCurrent() && reportEntryAvailable() && reader.states.value === expected &&
                recipeRequests?.states?.value?.phase?.let { it != RecipeRequestPhase.HIDDEN } != true &&
                postDeletion?.states?.value?.phase?.let { it != PostDeletionPhase.HIDDEN } != true &&
                remixes?.states?.value?.phase?.let { it != RemixTrailPhase.HIDDEN } != true &&
                sessionControls?.states?.value?.phase?.let { it != SessionControlsPhase.HIDDEN } != true &&
                notifications?.states?.value?.phase?.let { it != NotificationSettingsPhase.HIDDEN } != true &&
                notificationReads?.states?.value?.phase?.let { it != NotificationReadPhase.HIDDEN } != true
            if (!entryCurrent()) return@withContext PortResult.Failure(FailureReason.CONFLICT)
            when (val selection = reader.prepareMessageReport(expected, message)) {
                is PortResult.Failure -> selection
                is PortResult.Value -> {
                    if (!entryCurrent()) return@withContext PortResult.Failure(FailureReason.CONFLICT)
                    // Opening intentionally unmounts Thread UI. Only the retained actual
                    // source selection, not the obsolete UI visit, fences the local write.
                    owner.open(selection.value)
                }
            }
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    internal suspend fun openReaction(expected: SocialReadState, post: SocialPostSnapshot,
        removeRequested: Boolean, presentationCurrent: () -> Boolean): PortResult<ReactionState> = withContext(dispatcher) {
        val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val owner = reactions ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        fun entryCurrent() = !closed && form.current() && !reactionTransition && presentationCurrent() &&
            reportEntryAvailable() && reader.states.value === expected && owner.states.value.phase == ReactionPhase.HIDDEN &&
            conversations?.states?.value?.phase?.let { it != ThreadPhase.HIDDEN } != true &&
            postDeletion?.states?.value?.phase?.let { it != PostDeletionPhase.HIDDEN } != true &&
            recipeRequests?.states?.value?.phase?.let { it != RecipeRequestPhase.HIDDEN } != true &&
            remixes?.states?.value?.phase?.let { it != RemixTrailPhase.HIDDEN } != true
        if (!entryCurrent()) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        when (val selection = reader.prepareReaction(expected, post, removeRequested)) {
            is PortResult.Failure -> selection
            is PortResult.Value -> {
                if (!entryCurrent()) return@withContext PortResult.Failure(FailureReason.CONFLICT)
                when (val opened = owner.open(selection.value)) {
                    is PortResult.Failure -> opened
                    is PortResult.Value -> if (removeRequested && owner.states.value === opened.value &&
                        opened.value.phase == ReactionPhase.READY && opened.value.currentReaction?.postId == post.id)
                        owner.prepareRemove(opened.value) else opened
                }
            }
        }
    }

    internal suspend fun resumeReaction(expected: SocialReadState,
        presentationCurrent: () -> Boolean): PortResult<ReactionState> = withContext(dispatcher) {
        val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val owner = reactions ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (reactionTransition || !presentationCurrent() || !reportEntryAvailable() || reader.states.value !== expected ||
            expected.screen !in setOf(SocialReadScreen.TODAY, SocialReadScreen.PROFILE_PLATE) ||
            expected.phase == SocialReadPhase.LOADING || owner.states.value.phase != ReactionPhase.HIDDEN)
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        owner.open()
    }

    internal suspend fun closeReaction(expected: ReactionState): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        val owner = reactions ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (reactionTransition || owner.states.value !== expected) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        reactionTransition = true
        try {
            socialReads?.discardRetainedObservations()
            owner.leave(expected)
        } finally { reactionTransition = false }
    }

    internal suspend fun openPostPlacement(expected: SocialReadState, post: SocialPostSnapshot,
        keepOnPlate: Boolean, presentationCurrent: () -> Boolean): PortResult<PostPlacementState> = withContext(dispatcher) {
        val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val owner = postPlacement ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        fun entryCurrent() = !closed && form.current() && !postPlacementTransition && presentationCurrent() &&
            reportEntryAvailable() && reader.states.value === expected &&
            owner.states.value.phase == PostPlacementPhase.HIDDEN &&
            conversations?.states?.value?.phase?.let { it != ThreadPhase.HIDDEN } != true &&
            postDeletion?.states?.value?.phase?.let { it != PostDeletionPhase.HIDDEN } != true &&
            recipeRequests?.states?.value?.phase?.let { it != RecipeRequestPhase.HIDDEN } != true &&
            remixes?.states?.value?.phase?.let { it != RemixTrailPhase.HIDDEN } != true
        if (!entryCurrent()) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        when (val selection = reader.preparePostPlacement(expected, post, keepOnPlate)) {
            is PortResult.Failure -> selection
            is PortResult.Value -> if (!entryCurrent()) PortResult.Failure(FailureReason.CONFLICT)
                else owner.open(selection.value)
        }
    }

    /** A possible placement change invalidates old post/feed/media observations. Clear
     * before uncovering the parent; no refresh, retry or new placement is inferred. */
    internal suspend fun closePostPlacement(expected: PostPlacementState): PortResult<Unit> =
        withContext(NonCancellable + dispatcher) {
            val owner = postPlacement ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (postPlacementTransition || owner.states.value !== expected)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            postPlacementTransition = true
            try {
                socialReads?.discardRetainedObservations()
                owner.leave(expected)
            } finally { postPlacementTransition = false }
        }

    internal suspend fun resumePostPlacement(expected: SocialReadState,
        presentationCurrent: () -> Boolean): PortResult<PostPlacementState> = withContext(dispatcher) {
        val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val owner = postPlacement ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (postPlacementTransition || !presentationCurrent() || !reportEntryAvailable() ||
            reader.states.value !== expected || expected.screen !in setOf(SocialReadScreen.TODAY, SocialReadScreen.PROFILE_PLATE) ||
            expected.phase == SocialReadPhase.LOADING || owner.states.value.phase != PostPlacementPhase.HIDDEN)
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        // No post read is required: removal may already have ended its last surface.
        owner.open()
    }

    suspend fun reportSocialPost(expected: SocialReadState): PortResult<ReportState> = withContext(dispatcher) {
        val reader = socialReads
        val owner = reports
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (reader == null || owner == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (!reportEntryAvailable() || reader.states.value !== expected ||
            circles?.states?.value?.screen?.let { it != CirclesScreen.HIDDEN } == true)
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        when (val selection = reader.prepareReportPost(expected)) {
            is PortResult.Failure -> selection
            is PortResult.Value -> owner.open(selection.value)
        }
    }

    /** Only an exact observed member can seed a new target. A membership ID is not a user ID.
     * The report owner preserves any existing original; opening never submits a report. */
    suspend fun reportCircleMember(memberId: String, expected: CirclesState): PortResult<ReportState> {
        val owner = reports
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || circles == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (!reportEntryAvailable() || circles.states.value !== expected)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            when (val selection = circles.prepareReportMember(memberId, expected)) {
                is PortResult.Failure -> selection
                is PortResult.Value -> owner.open(selection.value)
            }
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    /** The report has its own receipt. Opening this sibling is local recovery/review only;
     * a separate explicit block confirmation is required by the block owner. */
    internal suspend fun openReportBlock(expected: ReportState,
        presentationCurrent: () -> Boolean): PortResult<com.feedme.mealflow.blocks.BlocksState> = withContext(dispatcher) {
        val reporter = reports ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val blocker = blocks ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (reportBlockTransition || !presentationCurrent() || !reporter.canPrepareBlockTarget(expected) ||
            blocker.states.value.screen != com.feedme.mealflow.blocks.BlocksScreen.HIDDEN)
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        when (val target = reporter.prepareBlockTarget(expected)) {
            is PortResult.Failure -> target
            is PortResult.Value -> {
                if (!presentationCurrent() || reporter.states.value !== expected || closed || !form.current())
                    PortResult.Failure(FailureReason.CONFLICT)
                else blocker.openForBlock(target.value)
            }
        }
    }

    /** Do not uncover pre-block post/message/roster observations after a possible command.
     * All departures are local and exact-state checked. Report receipts, cooking, Saved,
     * thread drafts and encrypted uncertain originals remain owned and unchanged. */
    internal suspend fun closeReportBlock(expected: com.feedme.mealflow.blocks.BlocksState): PortResult<Unit> =
        withContext(NonCancellable + dispatcher) {
            val blocker = blocks ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (reportBlockTransition || blocker.states.value !== expected)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            reportBlockTransition = true
            try {
                socialReads?.let { owner ->
                    val before = owner.states.value
                    if (before.screen != SocialReadScreen.HIDDEN) {
                        val result = owner.back(before)
                        if (result is PortResult.Failure) return@withContext result
                    }
                }
                conversations?.let { owner ->
                    val before = owner.states.value
                    if (before.phase != ThreadPhase.HIDDEN) {
                        val result = owner.leave(before)
                        if (result is PortResult.Failure) return@withContext result
                    }
                }
                circles?.let { owner ->
                    val before = owner.states.value
                    if (before.screen != CirclesScreen.HIDDEN) {
                        val result = owner.back(before)
                        if (result is PortResult.Failure) return@withContext result
                    }
                }
                // The Report overlay remains visible during all source cleanup above.
                blocker.leave(expected)
            } finally { reportBlockTransition = false }
        }

    /** Explicit local-only recovery remains available when circle reads fail. No target GET,
     * sending, retry or status refresh is inferred by restoring a retained report. */
    suspend fun resumeReport(): PortResult<ReportState> {
        val owner = reports
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (!reportEntryAvailable()) return@withContext PortResult.Failure(FailureReason.CONFLICT)
            owner.restore()
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    private fun reportEntryAvailable(): Boolean = reportsHidden() && action == null && invitationRoutesHidden() &&
        circleCreate?.states?.value?.screen?.let { it != CircleCreateScreen.HIDDEN } != true &&
        circleEdit?.states?.value?.screen?.let { it != CircleEditScreen.HIDDEN } != true &&
        circleLeave?.states?.value?.screen?.let { it != CircleLeaveScreen.HIDDEN } != true &&
        circleDelete?.states?.value?.screen?.let { it != CircleLeaveScreen.HIDDEN } != true &&
        !reviewedPostNavigation.states.value.visible && !cookingNavigation.value.visible &&
        cookingNavigation.value.confirmation == null && kitchenPage.value == null &&
        cookbook.states.value.screen == CookbookScreen.HIDDEN && timers?.states?.value?.visible != true &&
        postDrafts?.states?.value?.screen?.let { it != PostDraftScreen.HIDDEN } != true

    /** Exact roster selection or explicit retained-original recovery; opening never removes. */
    suspend fun openCircleMemberRemoval(memberId: String?, expected: CirclesState): PortResult<CircleMemberRemovalState> = withContext(dispatcher) {
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        val owner = circleMemberRemoval ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val browser = circles ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (!reportEntryAvailable() || !socialHidden() || browser.states.value !== expected ||
            expected.screen == CirclesScreen.HIDDEN || expected.phase in setOf(CirclesPhase.LOADING, CirclesPhase.UNAVAILABLE))
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        if (memberId == null) owner.open()
        else when (val selection = browser.prepareMemberRemoval(memberId, expected)) {
            is PortResult.Failure -> selection
            is PortResult.Value -> owner.open(selection.value)
        }
    }
    /** Original transfer action selects a real roster member; opening never transfers. */
    suspend fun openCircleOwnershipTransfer(memberId: String?, expected: CirclesState): PortResult<CircleOwnershipTransferState> = withContext(dispatcher) {
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        val owner = circleOwnershipTransfer ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val browser = circles ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (!reportEntryAvailable() || !socialHidden() || browser.states.value !== expected ||
            expected.screen == CirclesScreen.HIDDEN || expected.phase in setOf(CirclesPhase.LOADING, CirclesPhase.UNAVAILABLE))
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        if (memberId == null) owner.open()
        else when (val selection = browser.prepareOwnershipTransfer(memberId, expected)) {
            is PortResult.Failure -> selection
            is PortResult.Value -> owner.open(selection.value)
        }
    }
    /** Explicit LIST-only entry. The existing list remains a read observation behind this
     * local form, so Back returns to CIRCLES without a hidden GET or fabricated refresh. */
    suspend fun openCircleCreation(expected: CirclesState): PortResult<CircleCreateState> {
        val owner = circleCreate
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || circles == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (!invitationRoutesHidden() || circles.states.value !== expected || expected.screen != CirclesScreen.LIST ||
                expected.phase in setOf(CirclesPhase.LOADING, CirclesPhase.UNAVAILABLE) ||
                owner.states.value.screen != CircleCreateScreen.HIDDEN || action != null ||
                circleEdit?.states?.value?.screen?.let { it != CircleEditScreen.HIDDEN } == true ||
                reviewedPostNavigation.states.value.visible || cookingNavigation.value.visible ||
                cookingNavigation.value.confirmation != null || kitchenPage.value != null ||
                cookbook.states.value.screen != CookbookScreen.HIDDEN || timers?.states?.value?.visible == true ||
                postDrafts?.states?.value?.screen?.let { it != PostDraftScreen.HIDDEN } == true)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            owner.open()
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }
    /** A fresh acknowledged result starts one explicit GET on the SAME actual access. The
     * creator stays visible until that GET succeeds; Back revokes a held handoff immediately. */
    suspend fun openCreatedCircle(expected: CircleCreateState): PortResult<CirclesState> {
        val browser = circles
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            val owner = circleCreate
            if (owner == null || browser == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            val receipt = expected.result
            if (owner.states.value !== expected || expected.screen != CircleCreateScreen.FORM ||
                receipt == null || !receipt.isCurrentForNavigation || action != null)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            when (val detail = browser.openAcknowledgedCircle(receipt)) {
                is PortResult.Failure -> detail
                is PortResult.Value -> {
                    if (owner.states.value !== expected || !receipt.isCurrentForNavigation)
                        return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
                    when (val hidden = owner.back(expected)) {
                        is PortResult.Failure -> hidden
                        is PortResult.Value -> detail
                    }
                }
            }
        }
        return if (result is PortResult.Value && browser?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }
    /** Actual read owner obtains the fresh baseline; the edit child performs local work only.
     * Neither this entry nor Resume owns the meal form's busy lock across a read/store wait. */
    suspend fun openCircleLeave(expected: CirclesState): PortResult<CircleLeaveState> = enterCircleLeave(expected, false)
    suspend fun resumeCircleLeave(expected: CirclesState): PortResult<CircleLeaveState> = enterCircleLeave(expected, true)

    private fun circleLeaveEntryAllowed(): Boolean = action == null &&
        circleIssuedInvitations?.states?.value?.screen?.let { it != CircleIssuedInvitationsScreen.HIDDEN } != true &&
        invitationPreview?.states?.value?.screen?.let { it != CircleInvitationPreviewScreen.HIDDEN } != true &&
        circleInvitation?.states?.value?.screen?.let { it != CircleInvitationScreen.HIDDEN } != true &&
        circleEdit?.states?.value?.screen?.let { it != CircleEditScreen.HIDDEN } != true &&
        circleDelete?.states?.value?.screen?.let { it != CircleLeaveScreen.HIDDEN } != true &&
        circleCreate?.states?.value?.screen?.let { it != CircleCreateScreen.HIDDEN } != true &&
        !reviewedPostNavigation.states.value.visible && !cookingNavigation.value.visible &&
        cookingNavigation.value.confirmation == null && kitchenPage.value == null &&
        cookbook.states.value.screen == CookbookScreen.HIDDEN && timers?.states?.value?.visible != true &&
        postDrafts?.states?.value?.screen?.let { it != PostDraftScreen.HIDDEN } != true

    private suspend fun enterCircleLeave(expected: CirclesState, resume: Boolean): PortResult<CircleLeaveState> {
        val owner = circleLeave
        val browser = circles
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || browser == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (browser.states.value !== expected || owner.states.value.screen != CircleLeaveScreen.HIDDEN ||
                !circleLeaveEntryAllowed() ||
                (resume && (expected.screen != CirclesScreen.LIST || expected.phase in setOf(CirclesPhase.LOADING, CirclesPhase.UNAVAILABLE))) ||
                (!resume && (expected.screen != CirclesScreen.DETAIL || expected.phase != CirclesPhase.READY)))
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            if (resume) owner.open() else when (val selection = browser.prepareLeave(expected)) {
                is PortResult.Failure -> selection
                is PortResult.Value -> {
                    if (closed || !form.current() || !selection.value.isCurrentForNavigation ||
                        !circleLeaveEntryAllowed() || owner.states.value.screen != CircleLeaveScreen.HIDDEN)
                        PortResult.Failure(FailureReason.STALE_SESSION)
                    else owner.open(selection.value)
                }
            }
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    suspend fun openCircleDelete(expected: CirclesState): PortResult<CircleLeaveState> = enterCircleDelete(expected, false)
    suspend fun resumeCircleDelete(expected: CirclesState): PortResult<CircleLeaveState> = enterCircleDelete(expected, true)

    private fun circleDeleteEntryAllowed(): Boolean = action == null &&
        circleIssuedInvitations?.states?.value?.screen?.let { it != CircleIssuedInvitationsScreen.HIDDEN } != true &&
        invitationPreview?.states?.value?.screen?.let { it != CircleInvitationPreviewScreen.HIDDEN } != true &&
        circleInvitation?.states?.value?.screen?.let { it != CircleInvitationScreen.HIDDEN } != true &&
        circleEdit?.states?.value?.screen?.let { it != CircleEditScreen.HIDDEN } != true &&
        circleCreate?.states?.value?.screen?.let { it != CircleCreateScreen.HIDDEN } != true &&
        circleLeave?.states?.value?.screen?.let { it != CircleLeaveScreen.HIDDEN } != true &&
        !reviewedPostNavigation.states.value.visible && !cookingNavigation.value.visible &&
        cookingNavigation.value.confirmation == null && kitchenPage.value == null &&
        cookbook.states.value.screen == CookbookScreen.HIDDEN && timers?.states?.value?.visible != true &&
        postDrafts?.states?.value?.screen?.let { it != PostDraftScreen.HIDDEN } != true

    private suspend fun enterCircleDelete(expected: CirclesState, resume: Boolean): PortResult<CircleLeaveState> {
        val owner = circleDelete
        val browser = circles
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || browser == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (browser.states.value !== expected || owner.states.value.screen != CircleLeaveScreen.HIDDEN ||
                !circleDeleteEntryAllowed() ||
                (resume && (expected.screen != CirclesScreen.LIST || expected.phase in setOf(CirclesPhase.LOADING, CirclesPhase.UNAVAILABLE))) ||
                (!resume && (expected.screen != CirclesScreen.DETAIL || expected.phase != CirclesPhase.READY)))
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            if (resume) owner.open() else when (val selection = browser.prepareDelete(expected)) {
                is PortResult.Failure -> selection
                is PortResult.Value -> {
                    if (closed || !form.current() || !selection.value.isCurrentForNavigation ||
                        !circleDeleteEntryAllowed() || owner.states.value.screen != CircleLeaveScreen.HIDDEN)
                        PortResult.Failure(FailureReason.STALE_SESSION)
                    else owner.open(selection.value)
                }
            }
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    suspend fun openCircleEdit(expected: CirclesState): PortResult<CircleEditState> = enterCircleEdit(expected, false)
    suspend fun resumeCircleEdit(expected: CirclesState): PortResult<CircleEditState> = enterCircleEdit(expected, true)

    private fun circleEditEntryAllowed(): Boolean = invitationRoutesHidden() && action == null &&
        circleCreate?.states?.value?.screen?.let { it != CircleCreateScreen.HIDDEN } != true &&
        !reviewedPostNavigation.states.value.visible && !cookingNavigation.value.visible &&
        cookingNavigation.value.confirmation == null && kitchenPage.value == null &&
        cookbook.states.value.screen == CookbookScreen.HIDDEN && timers?.states?.value?.visible != true &&
        postDrafts?.states?.value?.screen?.let { it != PostDraftScreen.HIDDEN } != true

    private suspend fun enterCircleEdit(expected: CirclesState, resume: Boolean): PortResult<CircleEditState> {
        val owner = circleEdit
        val browser = circles
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || browser == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (browser.states.value !== expected || owner.states.value.screen != CircleEditScreen.HIDDEN ||
                !circleEditEntryAllowed() ||
                (resume && (expected.screen != CirclesScreen.LIST || expected.phase in setOf(CirclesPhase.LOADING, CirclesPhase.UNAVAILABLE))) ||
                (!resume && (expected.screen != CirclesScreen.DETAIL || expected.phase != CirclesPhase.READY)))
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            if (resume) owner.open() else when (val selection = browser.prepareEdit(expected)) {
                is PortResult.Failure -> selection
                is PortResult.Value -> {
                    if (closed || !form.current() || !selection.value.isCurrentForNavigation ||
                        !circleEditEntryAllowed() || owner.states.value.screen != CircleEditScreen.HIDDEN)
                        PortResult.Failure(FailureReason.STALE_SESSION)
                    else owner.open(selection.value)
                }
            }
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    /** Explicit fresh detail inspection is not conflict resolution or a new command. */
    suspend fun reviewCircleEditConflict(expected: CircleEditState): PortResult<CircleEditState> =
        freshCircleEdit(expected, false)

    /** Caller obtains explicit consent before replacing the completed kept draft. */
    suspend fun editLatestCircleDetails(expected: CircleEditState): PortResult<CircleEditState> =
        freshCircleEdit(expected, true)

    private suspend fun freshCircleEdit(expected: CircleEditState, startNew: Boolean): PortResult<CircleEditState> {
        val owner = circleEdit
        val browser = circles
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || browser == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            val target = expected.target
            if (owner.states.value !== expected || expected.screen != CircleEditScreen.FORM ||
                target == null || !target.isCurrentForNavigation || !circleEditEntryAllowed() ||
                (startNew && (!expected.completionAcknowledged || expected.pending != null)) ||
                (!startNew && expected.pending?.canReviewConflict != true))
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            when (val selected = browser.prepareEdit(target)) {
                is PortResult.Failure -> selected
                is PortResult.Value -> {
                    if (closed || !form.current() || owner.states.value !== expected || !target.isCurrentForNavigation ||
                        !selected.value.isCurrentForNavigation || !circleEditEntryAllowed())
                        PortResult.Failure(FailureReason.STALE_SESSION)
                    else if (startNew) owner.startNew(selected.value, expected)
                    else owner.reviewConflict(selected.value, expected)
                }
            }
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    /** Only a live actual update ACK can hand off to fresh detail. Keep editor mounted until
     * the GET succeeds, then Back locally; the GET itself never creates an update ACK. */
    suspend fun openEditedCircle(expected: CircleEditState): PortResult<CirclesState> {
        val owner = circleEdit
        val browser = circles
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || browser == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            val receipt = expected.result
            if (owner.states.value !== expected || expected.screen != CircleEditScreen.FORM ||
                receipt == null || !receipt.isCurrentForNavigation || !circleEditEntryAllowed())
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            when (val detail = browser.openAcknowledgedEdit(receipt)) {
                is PortResult.Failure -> detail
                is PortResult.Value -> {
                    if (owner.states.value !== expected || !receipt.isCurrentForNavigation)
                        return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
                    when (val hidden = owner.back(expected)) {
                        is PortResult.Failure -> hidden
                        is PortResult.Value -> detail
                    }
                }
            }
        }
        return if (result is PortResult.Value && browser?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    /** New issuance requires an explicit fresh DETAIL selection; local history recovery
     * does not fetch an invitation inventory or convert retained metadata into a grant. */
    suspend fun openCircleIssuedInvitations(expected: CirclesState): PortResult<CircleIssuedInvitationsState> =
        enterCircleIssuedInvitations(expected, false)
    suspend fun resumeCircleIssuedInvitations(expected: CirclesState): PortResult<CircleIssuedInvitationsState> =
        enterCircleIssuedInvitations(expected, true)

    private fun issuedInvitationEntryAllowed(): Boolean = action == null &&
        circleLeave?.states?.value?.screen?.let { it != CircleLeaveScreen.HIDDEN } != true &&
        circleDelete?.states?.value?.screen?.let { it != CircleLeaveScreen.HIDDEN } != true &&
        invitationPreview?.states?.value?.screen?.let { it != CircleInvitationPreviewScreen.HIDDEN } != true &&
        circleInvitation?.states?.value?.screen?.let { it != CircleInvitationScreen.HIDDEN } != true &&
        circleCreate?.states?.value?.screen?.let { it != CircleCreateScreen.HIDDEN } != true &&
        circleEdit?.states?.value?.screen?.let { it != CircleEditScreen.HIDDEN } != true &&
        !reviewedPostNavigation.states.value.visible && !cookingNavigation.value.visible &&
        cookingNavigation.value.confirmation == null && kitchenPage.value == null &&
        cookbook.states.value.screen == CookbookScreen.HIDDEN && timers?.states?.value?.visible != true &&
        postDrafts?.states?.value?.screen?.let { it != PostDraftScreen.HIDDEN } != true

    private suspend fun enterCircleIssuedInvitations(expected: CirclesState, resume: Boolean): PortResult<CircleIssuedInvitationsState> {
        val owner = circleIssuedInvitations
        val browser = circles
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || browser == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (browser.states.value !== expected || owner.states.value.screen != CircleIssuedInvitationsScreen.HIDDEN ||
                !issuedInvitationEntryAllowed() ||
                (resume && (expected.screen != CirclesScreen.LIST || expected.phase in setOf(CirclesPhase.LOADING, CirclesPhase.UNAVAILABLE))) ||
                (!resume && (expected.screen !in setOf(CirclesScreen.DETAIL, CirclesScreen.MEMBERS) || expected.phase != CirclesPhase.READY)))
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            if (resume) owner.open() else when (val selection = browser.prepareEdit(expected)) {
                is PortResult.Failure -> selection
                is PortResult.Value -> {
                    if (closed || !form.current() || !selection.value.isCurrentForNavigation ||
                        !issuedInvitationEntryAllowed() || owner.states.value.screen != CircleIssuedInvitationsScreen.HIDDEN)
                        PortResult.Failure(FailureReason.STALE_SESSION)
                    else owner.open(selection.value)
                }
            }
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    /** User consent starts a fresh read of the exact already-open DETAIL, then retires only
     * the actually acknowledged completion. The later Issue review/confirmation is separate. */
    suspend fun startNewCircleIssuedInvitation(expected: CircleIssuedInvitationsState,
        expectedCircle: CirclesState): PortResult<CircleIssuedInvitationsState> {
        val owner = circleIssuedInvitations
        val browser = circles
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || browser == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (!issuedInvitationEntryAllowed() || owner.states.value !== expected || browser.states.value !== expectedCircle ||
                expected.screen != CircleIssuedInvitationsScreen.HISTORY || !expected.completionAcknowledged || expected.pending != null ||
                expectedCircle.screen != CirclesScreen.DETAIL || expectedCircle.phase != CirclesPhase.READY ||
                expectedCircle.selected == null)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            when (val selection = browser.prepareEdit(expectedCircle)) {
                is PortResult.Failure -> selection
                is PortResult.Value -> {
                    if (closed || !form.current() || owner.states.value !== expected ||
                        !selection.value.isCurrentForNavigation || !issuedInvitationEntryAllowed())
                        PortResult.Failure(FailureReason.STALE_SESSION)
                    else owner.startNew(selection.value, expected)
                }
            }
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    private fun invitationRoutesHidden(exceptMemory: Boolean = false, exceptCollections: Boolean = false): Boolean =
        reportsHidden(exceptMemory, exceptCollections) &&
        circleLeave?.states?.value?.screen?.let { it != CircleLeaveScreen.HIDDEN } != true &&
        circleDelete?.states?.value?.screen?.let { it != CircleLeaveScreen.HIDDEN } != true &&
        circleIssuedInvitations?.states?.value?.screen?.let { it != CircleIssuedInvitationsScreen.HIDDEN } != true &&
        invitationPreview?.states?.value?.screen?.let { it != CircleInvitationPreviewScreen.HIDDEN } != true &&
        circleInvitation?.states?.value?.screen?.let { it != CircleInvitationScreen.HIDDEN } != true

    private fun invitationEntryAllowed(): Boolean =
        reportsHidden() && socialHidden() &&
        circleLeave?.states?.value?.screen?.let { it != CircleLeaveScreen.HIDDEN } != true &&
        circleDelete?.states?.value?.screen?.let { it != CircleLeaveScreen.HIDDEN } != true &&
        circleIssuedInvitations?.states?.value?.screen?.let { it != CircleIssuedInvitationsScreen.HIDDEN } != true && action == null &&
        circleCreate?.states?.value?.screen?.let { it != CircleCreateScreen.HIDDEN } != true &&
        circleEdit?.states?.value?.screen?.let { it != CircleEditScreen.HIDDEN } != true &&
        !reviewedPostNavigation.states.value.visible && !cookingNavigation.value.visible &&
        cookingNavigation.value.confirmation == null && kitchenPage.value == null &&
        cookbook.states.value.screen == CookbookScreen.HIDDEN && timers?.states?.value?.visible != true &&
        postDrafts?.states?.value?.screen?.let { it != PostDraftScreen.HIDDEN } != true

    /** Explicit user-pasted link from the current circle list. Retire that list before
     * reading the public preview; opening a link never accepts or persists an invitation. */
    suspend fun openCircleInvitationLink(expected: CirclesState,
        link: SecretText): PortResult<CircleInvitationPreviewState> = withContext(dispatcher) {
        val browser = circles
        val preview = invitationPreview
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (browser == null || preview == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (!invitationEntryAllowed() || !invitationRoutesHidden() || browser.states.value !== expected ||
            expected.screen != CirclesScreen.LIST || expected.phase in setOf(CirclesPhase.LOADING, CirclesPhase.UNAVAILABLE))
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        when (val departure = browser.back(expected)) {
            is PortResult.Failure -> departure
            is PortResult.Value -> {
                if (closed || !form.current() || browser.states.value !== departure.value ||
                    departure.value.screen != CirclesScreen.HIDDEN || !invitationEntryAllowed() || !invitationRoutesHidden())
                    PortResult.Failure(FailureReason.STALE_SESSION)
                else preview.openLink(link)
            }
        }
    }

    /** Explicit trusted-app link entry. Only an allowlisted anonymous preview GET is possible.
     * No account command, ID, persistence or redemption is triggered by this method. */
    suspend fun openInvitationLink(link: SecretText): PortResult<CircleInvitationPreviewState> {
        val preview = invitationPreview
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (preview == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (!invitationEntryAllowed() || !invitationRoutesHidden() ||
                circles?.states?.value?.screen?.let { it != CirclesScreen.HIDDEN } == true)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            preview.openLink(link)
        }
        return if (result is PortResult.Value && (preview?.states?.value !== result.value || form.states.value.values == null))
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    /** Explicit continuation to the CURRENT ACCOUNT owner. Reading a public preview or
     * finishing login never invokes this action. The user's Review joining action may
     * prepare a fresh review, but never allocates or confirms an acceptance command. */
    suspend fun reviewInvitation(expected: CircleInvitationPreviewState): PortResult<CircleInvitationState> {
        val owner = circleInvitation
        val preview = invitationPreview
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || preview == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            val selected = expected.selection
            if (!invitationEntryAllowed() || preview.states.value !== expected ||
                expected.screen != CircleInvitationPreviewScreen.PREVIEW || expected.phase != CircleInvitationPreviewPhase.READY ||
                selected == null || !selected.isCurrentForNavigation ||
                owner.states.value.screen != CircleInvitationScreen.HIDDEN ||
                circles?.states?.value?.screen?.let { it != CirclesScreen.HIDDEN } == true)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            when (val opened = owner.open(selected)) {
                is PortResult.Failure -> opened
                is PortResult.Value -> {
                    if (closed || !form.current() || owner.states.value !== opened.value ||
                        preview.states.value !== expected || !selected.isCurrentForNavigation)
                        PortResult.Failure(FailureReason.STALE_SESSION)
                    else if (opened.value.pending == null && opened.value.result == null &&
                        !opened.value.completionPending && !opened.value.completionAcknowledged)
                        owner.reviewAccept(opened.value)
                    else opened // Retained original/result recovery is never silently replaced.
                }
            }
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    /** Explicit consent to use a currently previewed invitation after the prior completion
     * was freshly acknowledged. Never replaces an unresolved original, or joins automatically. */
    suspend fun startNewCircleInvitation(expected: CircleInvitationState): PortResult<CircleInvitationState> {
        val owner = circleInvitation
        val preview = invitationPreview
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || preview == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            val publicState = preview.states.value
            val selection = publicState.selection
            if (!invitationEntryAllowed() || owner.states.value !== expected ||
                expected.screen != CircleInvitationScreen.PREVIEW || !expected.completionAcknowledged ||
                expected.pending != null || selection == null || !selection.isCurrentForNavigation ||
                expected.nextPreview !== selection.preview)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            when (val next = owner.startNew(selection, expected)) {
                is PortResult.Failure -> next
                is PortResult.Value -> {
                    if (closed || !form.current() || owner.states.value !== next.value ||
                        preview.states.value !== publicState || !selection.isCurrentForNavigation)
                        PortResult.Failure(FailureReason.STALE_SESSION)
                    else owner.reviewAccept(next.value)
                }
            }
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    /** Explicit local recovery from CIRCLES. No invitation preview request or redemption. */
    suspend fun resumeCircleInvitation(expected: CirclesState): PortResult<CircleInvitationState> {
        val owner = circleInvitation
        val browser = circles
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || browser == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (!invitationEntryAllowed() || !invitationRoutesHidden() || browser.states.value !== expected ||
                expected.screen != CirclesScreen.LIST || expected.phase in setOf(CirclesPhase.LOADING, CirclesPhase.UNAVAILABLE))
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            owner.open()
        }
        return if (result is PortResult.Value && owner?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    /** The actual accepted circleId is used only through the ACK-bound browser handoff.
     * Keep the account route mounted until the fresh detail read succeeds. */
    suspend fun openJoinedCircle(expected: CircleInvitationState): PortResult<CirclesState> {
        val owner = circleInvitation
        val browser = circles
        val result = withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (owner == null || browser == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            val receipt = expected.result
            val publicState = invitationPreview?.states?.value
            if (!invitationEntryAllowed() || owner.states.value !== expected || expected.screen != CircleInvitationScreen.PREVIEW ||
                receipt == null || !receipt.isCurrentForNavigation) return@withContext PortResult.Failure(FailureReason.CONFLICT)
            when (val detail = browser.openAcknowledgedInvitation(receipt)) {
                is PortResult.Failure -> detail
                is PortResult.Value -> {
                    if (closed || !form.current() || owner.states.value !== expected || !receipt.isCurrentForNavigation ||
                        invitationPreview?.states?.value !== publicState)
                        return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
                    if (publicState != null && publicState.screen != CircleInvitationPreviewScreen.HIDDEN) {
                        when (val hidden = invitationPreview!!.back(publicState)) {
                            is PortResult.Failure -> return@withContext hidden
                            else -> Unit
                        }
                    }
                    when (val hidden = owner.back(expected)) { is PortResult.Failure -> hidden; else -> detail }
                }
            }
        }
        return if (result is PortResult.Value && browser?.states?.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    internal fun exitTicket(): Long? = form.capture()?.first
    internal fun canExitAfterSave(ticket: Long) = !closed && form.savedCurrent(ticket)

    /** Restore only local eligible records. Screen attachment never submits/fetches automatically. */
    suspend fun restore() = perform(adaptation = true) {
        // An Activity attachment borrows this same owner. Its unsent root form is not a
        // durable request and must neither be overwritten nor fabricated on a new owner.
        val retainedRoot = rootRecipeForms.value.takeIf {
            meals.states.value.screen == MealFlowScreen.ROOT_MAKE_MINE && retainedUnsentRootCurrent(it, meals.states.value)
        }
        val inputs = kitchen.restore()
        if (inputs is PortResult.Failure) return@perform inputs
        if (retainedRoot != null && !rootRecipeForm.matches(retainedRoot)) return@perform cookingActionFailure()
        var result = meals.restore()
        if (result is PortResult.Value) {
            form.adopt(result.value.draft); ingredients.restore()
            adoptAdaptation(result.value)
            if (retainedRoot != null && (meals.states.value !== result.value || !rootRecipeForm.matches(retainedRoot)))
                return@perform cookingActionFailure()
            if (retainedRoot != null && retainedUnsentRootCurrent(retainedRoot, result.value)) {
                // The ordinary read above still validates persisted source/recall state.
                // Reopen uses the existing local controller admission, never a GET or write.
                val reopened = meals.openRootMakeMine(result.value)
                if (reopened is PortResult.Failure) return@perform reopened
                val current = (reopened as PortResult.Value).value
                if (!retainedUnsentRootCurrent(retainedRoot, current)) return@perform cookingActionFailure()
                adoptRootRecipe(current, reuse = true)
                result = reopened
            } else adoptRootRecipe(result.value)
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

    private fun retainedUnsentRootCurrent(retained: RootRecipeFormState, state: MealRequestState): Boolean =
        !closed && form.current() && meals.states.value === state && rootRecipeForm.matches(retained) &&
            retained.visible && retained.values != null && state.pendingRootDraft == null && state.rootProposal == null &&
            state.rootSource?.let { source -> retained.source?.let { sameRootSource(it, source) } } == true
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
    suspend fun openSimplification(expected: MealRequestState) = perform(admission = {
        adaptationRouteAvailable() && !forms.value.dirty && meals.states.value === expected && expected.simplificationAvailable &&
            expected.screen in setOf(MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE)
    }) { meals.openSimplification() }
    /** Bind the tap to the exact parent and ADAPT view that displayed these choices. Queued
     * callbacks may not borrow a newer selected meal after Back or another local action. */
    suspend fun requestSimplification(expected: MealRequestState, goal: SimplificationGoal, allowDifferentMeal: Boolean) = perform(admission = {
        adaptationRouteAvailable() && !forms.value.dirty && meals.states.value === expected && expected.simplificationAvailable &&
            expected.screen == MealFlowScreen.ADAPT
    }) { meals.requestSimplification(goal, allowDifferentMeal) }
    suspend fun reopenSimplification(expected: MealSimplificationProposal) = perform(admission = {
        adaptationRouteAvailable() && !forms.value.dirty && meals.states.value.proposal === expected &&
            meals.states.value.screen in setOf(MealFlowScreen.REQUEST, MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE)
    }) { meals.reopenSimplification() }
    /** A proposal is not a start/save command. The controller owns durable parent/child and
     * pending-cooking CAS checks; the form fence rejects edits during any suspended write. */
    suspend fun acceptSimplification(expected: MealSimplificationProposal) = perform(admission = {
        !forms.value.dirty && currentSimplificationProposal(expected)
    }) { meals.acceptSimplification(expected) }
    suspend fun keepOriginal(expected: MealSimplificationProposal) = perform(admission = {
        currentSimplificationProposal(expected)
    }) { meals.keepOriginal(expected) }
    suspend fun editSimplificationLimits(expected: MealSimplificationProposal) = perform(admission = {
        currentSimplificationProposal(expected)
    }) {
        when (val kept = meals.keepOriginal(expected)) {
            is PortResult.Failure -> kept
            is PortResult.Value -> meals.backToDraft()
        }
    }
    suspend fun anotherAfterSimplification(expected: MealSimplificationProposal) = perform(admission = {
        !forms.value.dirty && currentSimplificationProposal(expected)
    }) {
        when (val kept = meals.keepOriginal(expected)) {
            is PortResult.Failure -> kept
            is PortResult.Value -> meals.nextAlternative()
        }
    }

    private fun currentSimplificationProposal(expected: MealSimplificationProposal): Boolean {
        val meal = meals.states.value
        return adaptationRouteAvailable() && meal.screen == MealFlowScreen.VARIANT && meal.proposal === expected &&
            !expected.dismissed && meal.plan?.let { sameAdaptationParent(expected.parent, it) } == true
    }

    /** A separate form owns proposed values. Opening never edits the original meal draft. */
    suspend fun openAdaptation(expected: MealRequestState): PortResult<MealRequestState> = perform(adaptation = true) {
        if (!adaptationRouteAvailable() || forms.value.dirty || meals.states.value !== expected ||
            expected.screen !in setOf(MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE) || !expected.adaptationAvailable)
            return@perform PortResult.Failure(FailureReason.CONFLICT)
        val parent = expected.plan ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
        val draft = expected.draft ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
        val result = meals.openAdaptation()
        if (result is PortResult.Value && meals.states.value === result.value && form.current()) {
            adaptationInterpretation.clear()
            adaptationForm.open(parent, AdaptationFormValues(MealFormValues.from(draft)), expected.screen, reuse = true)
        }
        result
    }

    /** RECIPE.06 only preselects the local swap intent. The user must still select an
     * exact parent ingredient and explicitly request a reviewed replacement. Retained
     * proposed values survive; the selected original and its durable command never change. */
    suspend fun openMissingIngredientAdaptation(expected: MealRequestState,
        expectedForm: MealFormState): PortResult<MealRequestState> = perform(adaptation = true, admission = {
        adaptationRouteAvailable() && meals.states.value === expected && forms.value === expectedForm &&
            !expectedForm.dirty && !expectedForm.busy && expected.screen == MealFlowScreen.RECIPE &&
            expected.adaptationAvailable
    }) {
        val parent = expected.plan ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
        val draft = expected.draft ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
        val capture = form.capture() ?: return@perform PortResult.Failure(FailureReason.STALE_SESSION)
        val result = meals.openAdaptation()
        if (result is PortResult.Value) {
            if (meals.states.value !== result.value || !form.savedCurrent(capture.first) || !adaptationRouteAvailable())
                return@perform PortResult.Failure(if (form.current()) FailureReason.CONFLICT else FailureReason.STALE_SESSION)
            adaptationInterpretation.clear()
            val opened = adaptationForm.open(parent, AdaptationFormValues(MealFormValues.from(draft),
                reason = MealAdaptationReason.MISSING_INGREDIENT), expected.screen, reuse = true)
            // A new swap intent cannot borrow a generic draft's old target/replacement.
            // Reopening an already-missing same-parent draft keeps its explicit choices.
            if (opened.values?.reason != MealAdaptationReason.MISSING_INGREDIENT) {
                val edited = adaptationForm.edit(opened) { it.copy(reason = MealAdaptationReason.MISSING_INGREDIENT,
                    replaceIngredientId = null, requestedReplacementId = null) }
                if (edited is PortResult.Failure) return@perform edited
            }
        }
        result
    }

    fun editAdaptation(expected: AdaptationFormState, transform: (AdaptationFormValues) -> AdaptationFormValues): PortResult<AdaptationFormState> {
        if (!form.current()) return PortResult.Failure(FailureReason.STALE_SESSION)
        if (action != null || !editableAdaptation(expected)) return PortResult.Failure(FailureReason.CONFLICT)
        return adaptationForm.edit(expected, transform)
    }

    /** Borrowed PANTRY presentation edits only the version's ingredient selection. Its
     * catalog reads must not flip the main form's busy identity or mutate ADAPT scratch. */
    fun canEditAdaptationIngredients(expectedMeal: MealRequestState, expectedMain: MealFormState,
        expectedAdaptation: AdaptationFormState): Boolean =
        adaptationMealStagingRouteAvailable() && action == null && meals.states.value === expectedMeal &&
            forms.value === expectedMain && !expectedMain.busy && !expectedMain.dirty &&
            editableAdaptation(expectedAdaptation) &&
            expectedMeal.phase != MealFlowPhase.UNAVAILABLE && expectedMeal.pendingRootDraft == null &&
            expectedMeal.rootProposal == null

    suspend fun searchAdaptationIngredientChoices(expectedMeal: MealRequestState, expectedMain: MealFormState,
        expectedAdaptation: AdaptationFormState, query: String,
        childCurrent: () -> Boolean): PortResult<IngredientPickerState> = withContext(dispatcher) {
        if (!childCurrent() || !canEditAdaptationIngredients(expectedMeal, expectedMain, expectedAdaptation))
            return@withContext cookingActionFailure()
        val route = adaptationMealStagingObservations()
        val result = ingredients.search(query)
        if (!childCurrent() || !canEditAdaptationIngredients(expectedMeal, expectedMain, expectedAdaptation) ||
            adaptationMealStagingObservations().zip(route).any { (now, then) -> now !== then })
            return@withContext cookingActionFailure()
        result
    }

    suspend fun moreAdaptationIngredientChoices(expectedMeal: MealRequestState, expectedMain: MealFormState,
        expectedAdaptation: AdaptationFormState, expectedPicker: IngredientPickerState,
        childCurrent: () -> Boolean): PortResult<IngredientPickerState> = withContext(dispatcher) {
        if (!childCurrent() || !canEditAdaptationIngredients(expectedMeal, expectedMain, expectedAdaptation) ||
            ingredients.states.value !== expectedPicker || !expectedPicker.searchHasMore ||
            expectedPicker.searchPhase != IngredientPickerPhase.READY)
            return@withContext cookingActionFailure()
        val route = adaptationMealStagingObservations()
        val result = ingredients.nextSearchPage()
        if (!childCurrent() || !canEditAdaptationIngredients(expectedMeal, expectedMain, expectedAdaptation) ||
            adaptationMealStagingObservations().zip(route).any { (now, then) -> now !== then })
            return@withContext cookingActionFailure()
        result
    }

    fun applyAdaptationIngredientChoices(expectedMeal: MealRequestState, expectedMain: MealFormState,
        expectedAdaptation: AdaptationFormState, selectedIds: List<String>,
        childCurrent: () -> Boolean): PortResult<AdaptationFormState> {
        if (!childCurrent() || !canEditAdaptationIngredients(expectedMeal, expectedMain, expectedAdaptation))
            return cookingActionFailure()
        val original = expectedAdaptation.values ?: return cookingActionFailure()
        val next = selectedIds.toList()
        if (next.size > 128 || next.any { it.isBlank() || it.length > 200 || it.any(Char::isISOControl) } ||
            next.distinctBy { it.lowercase() }.size != next.size)
            return PortResult.Failure(FailureReason.INVALID_DATA)
        val known = ingredients.states.value.knownIngredients
        // Existing exact IDs may be retained or removed even when labels are unavailable.
        // New selections require a named real current catalog observation, not free text.
        if (next.filter { it !in original.meal.ingredientIds }.any { id ->
                known.singleOrNull { it.id == id && !it.historical && it.name.isNotBlank() } == null ||
                    original.meal.exclusions.any { it.equals(id, true) }
            }) return PortResult.Failure(FailureReason.CONFLICT)
        if (!childCurrent() || !canEditAdaptationIngredients(expectedMeal, expectedMain, expectedAdaptation))
            return cookingActionFailure()
        return adaptationForm.edit(expectedAdaptation) { before ->
            before.copy(meal = before.meal.copy(ingredientIds = next))
        }
    }

    /** Explicit NEW stages only the reviewed meal constraints, not the separate swap intent.
     * Ordinary Saved cooking keeps its exact Saved identity; root/source adaptations must use
     * their own immutable-source workflow instead of being converted to an ordinary request. */
    fun canStageAdaptationMealRequest(expectedMeal: MealRequestState, expectedMain: MealFormState,
        expectedAdaptation: AdaptationFormState): Boolean {
        if (!adaptationMealStagingRouteAvailable() || action != null || meals.states.value !== expectedMeal ||
            forms.value !== expectedMain || expectedMain.dirty || expectedMain.busy ||
            !editableAdaptation(expectedAdaptation) || !adaptationInterpretationReadyForStructuredAction() ||
            !meals.draftReadiness.isReady ||
            !(expectedMeal.phase in setOf(MealFlowPhase.READY, MealFlowPhase.OFFLINE_DRAFT) || expectedMeal.phase == MealFlowPhase.NEEDS_CONFIRMATION &&
                expectedMeal.issue == MealFlowIssue.CONTEXT_CHANGED) ||
            expectedMeal.pendingAdaptation != null || expectedMeal.pendingRootDraft != null ||
            expectedMeal.rootProposal != null || expectedMeal.rootSource != null) return false
        val retained = expectedMeal.draft?.let(MealFormValues::from) ?: return false
        val main = expectedMain.values ?: return false
        val proposed = expectedAdaptation.values?.meal ?: return false
        if (main != retained || retained.sourceRecipeVersionId != null || retained.sourcePostId != null ||
            retained.sourcePostVersion != null || retained.savedMakeMine) return false
        // These fields are immutable in the Make Mine editor. Check them again at the
        // ordinary-request boundary, without clearing identities or changing the base meal.
        return proposed.mode == retained.mode && proposed.baseDescription == retained.baseDescription &&
            proposed.basePreparation == retained.basePreparation && proposed.baseIngredientIds == retained.baseIngredientIds &&
            proposed.preferencesPendingSync == retained.preferencesPendingSync && proposed.savedRecipeId == retained.savedRecipeId &&
            proposed.sourceRecipeVersionId == null && proposed.sourcePostId == null &&
            proposed.sourcePostVersion == null && !proposed.savedMakeMine
    }

    suspend fun stageAdaptationMealRequest(expectedMeal: MealRequestState, expectedMain: MealFormState,
        expectedAdaptation: AdaptationFormState): PortResult<MealRequestState> = perform(adaptation = true, admission = {
        canStageAdaptationMealRequest(expectedMeal, expectedMain, expectedAdaptation)
    }) {
        val capture = form.capture() ?: return@perform PortResult.Failure(FailureReason.STALE_SESSION)
        val ownedMain = forms.value // perform has only set busy; no main input was edited.
        val ownedAction = action
        val route = adaptationMealStagingObservations()
        val proposed = expectedAdaptation.values?.meal ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
        val result = meals.edit(proposed.draft())
        if (result is PortResult.Value) {
            val accepted = result.value
            if (action !== ownedAction || forms.value !== ownedMain || !form.savedCurrent(capture.first) ||
                !adaptationForm.matches(expectedAdaptation) || !adaptationMealStagingRouteAvailable() ||
                adaptationMealStagingObservations().zip(route).any { (now, then) -> now !== then } ||
                meals.states.value !== accepted || accepted.screen != MealFlowScreen.REQUEST ||
                accepted.phase !in setOf(MealFlowPhase.EDITING, MealFlowPhase.OFFLINE_DRAFT) ||
                !meals.draftReadiness.isReady || accepted.draft?.let(MealFormValues::from) != proposed ||
                expectedAdaptation.parent?.let { parent -> accepted.plan?.let { sameAdaptationParent(parent, it) } } != true)
                return@perform PortResult.Failure(if (form.current()) FailureReason.CONFLICT else FailureReason.STALE_SESSION)
            // Only this acknowledged exact local write may replace the clean main inputs.
            // leave retains the adaptation's original parent, reason and swap/taste metadata.
            form.adopt(accepted.draft)
            adaptationInterpretation.clear()
            adaptationForm.leave()
        }
        result
    }

    private fun adaptationMealStagingRouteAvailable() = adaptationRouteAvailable() && cookingChildRoutesHidden() &&
        !acceptingRootRecipe && !handingOffSavedRoot && mutableSavedCookingConfirmation.value == null &&
        mutablePostSaveReview.value == null && !rootRecipeForms.value.visible &&
        circleCreate?.states?.value?.screen?.let { it != CircleCreateScreen.HIDDEN } != true &&
        circleEdit?.states?.value?.screen?.let { it != CircleEditScreen.HIDDEN } != true &&
        profileEdit?.states?.value?.phase?.let { it != com.feedme.mealflow.profile.AccountProfileEditPhase.HIDDEN } != true &&
        accountExports?.states?.value?.phase?.let { it != com.feedme.mealflow.exports.AccountExportPhase.HIDDEN } != true

    /** Hidden-and-returned siblings are a different visit, even when the final route matches. */
    private fun adaptationMealStagingObservations(): List<Any?> = pantrySiblings().drop(1) + listOf(kitchenVisit,
        rootRecipeForms.value, socialReads?.states?.value, conversations?.states?.value, postDeletion?.states?.value, postPlacement?.states?.value, reactions?.states?.value,
        recipeRequests?.states?.value, sessionControls?.states?.value, notifications?.states?.value, remixes?.states?.value,
        reports?.states?.value, mealMemory?.states?.value, collections?.states?.value,
        profileEdit?.states?.value, accountExports?.states?.value)

    fun adaptationSearchText(expected: AdaptationFormState, text: String): PortResult<AdaptationFormState> {
        if (!form.current()) return PortResult.Failure(FailureReason.STALE_SESSION)
        if (action != null || !editableAdaptation(expected)) return PortResult.Failure(FailureReason.CONFLICT)
        return adaptationForm.searchText(expected, text)
    }
    suspend fun searchAdaptation(expected: AdaptationFormState): PortResult<IngredientPickerState> = perform(adaptation = true) {
        if (!editableAdaptation(expected)) return@perform PortResult.Failure(FailureReason.CONFLICT)
        ingredients.search(expected.searchText)
    }
    suspend fun moreAdaptationIngredients(expected: AdaptationFormState): PortResult<IngredientPickerState> = perform(adaptation = true) {
        if (!editableAdaptation(expected)) return@perform PortResult.Failure(FailureReason.CONFLICT)
        ingredients.nextSearchPage()
    }
    suspend fun requestAdaptation(expected: MealRequestState,
        expectedForm: AdaptationFormState): PortResult<MealRequestState> = perform(adaptation = true, admission = {
        meals.states.value === expected && editableAdaptation(expectedForm) &&
            adaptationInterpretationReadyForStructuredAction()
    }) {
        val values = expectedForm.values ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
        val result = meals.requestAdaptation(values.meal.draft(), values.reason, values.replaceIngredientId,
            values.requestedReplacementId, values.retainTasteTag)
        if (result is PortResult.Value && meals.states.value === result.value && adaptationForm.matches(expectedForm))
            adoptAdaptation(result.value)
        else if (result is PortResult.Failure && adaptationForm.matches(expectedForm)) adaptationForm.fail(result.reason)
        result
    }
    suspend fun retryAdaptation(expected: MealRequestState): PortResult<MealRequestState> = perform(adaptation = true) {
        if (!adaptationRouteAvailable() || meals.states.value !== expected || expected.pendingAdaptation == null || forms.value.dirty)
            return@perform PortResult.Failure(FailureReason.CONFLICT)
        val result = meals.retrySubmitted()
        if (result is PortResult.Value && meals.states.value === result.value) adoptAdaptation(result.value)
        result
    }
    suspend fun reopenAdaptation(expected: MealRequestState): PortResult<MealRequestState> = perform(adaptation = true) {
        if (!adaptationRouteAvailable() || forms.value.dirty || meals.states.value !== expected || expected.adaptation == null ||
            expected.screen !in setOf(MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE))
            return@perform PortResult.Failure(FailureReason.CONFLICT)
        val origin = expected.screen
        val result = meals.reopenAdaptation()
        if (result is PortResult.Value && meals.states.value === result.value) adoptAdaptation(result.value, origin, reuseDirty = true)
        result
    }
    suspend fun keepAdaptationOriginal(expected: MealRequestState): PortResult<MealRequestState> = perform(adaptation = true) {
        if (!currentAdaptationProposal(expected)) return@perform PortResult.Failure(FailureReason.CONFLICT)
        val result = meals.keepOriginal(expected.adaptation!!)
        if (result !is PortResult.Value || meals.states.value !== result.value) return@perform result
        adaptationInterpretation.clear()
        adaptationForm.leave()
        returnToAdaptationOrigin()
    }
    suspend fun acceptAdaptation(expected: MealRequestState): PortResult<MealRequestState> = perform(adaptation = true) {
        if (forms.value.dirty || !currentAdaptationProposal(expected)) return@perform PortResult.Failure(FailureReason.CONFLICT)
        val result = meals.acceptAdaptation(expected.adaptation!!)
        if (result !is PortResult.Value || meals.states.value !== result.value) return@perform result
        adaptationInterpretation.clear()
        adaptationForm.leave()
        if (forms.value.dirty) return@perform PortResult.Failure(FailureReason.CONFLICT)
        form.adopt(result.value.draft)
        // Navigation only. This does not prepare a cook, enqueue a save or publish a post.
        meals.returnToRecipe()
    }
    suspend fun editAdaptationRequest(expected: MealRequestState): PortResult<MealRequestState> = perform(adaptation = true) {
        if (forms.value.dirty || !currentAdaptationProposal(expected)) return@perform PortResult.Failure(FailureReason.CONFLICT)
        val proposal = expected.adaptation!!
        val result = meals.openAdaptation()
        if (result is PortResult.Value && meals.states.value === result.value) {
            adaptationInterpretation.clear()
            adaptationForm.open(proposal.parent, AdaptationFormValues.from(proposal), adaptationForms.value.returnScreen,
                reuse = adaptationForms.value.dirty)
        }
        result
    }
    /** Read-only immediate navigation stays responsive while transport is suspended. */
    suspend fun viewAdaptationOriginal(expected: MealRequestState, expectedForm: AdaptationFormState? = null): PortResult<MealRequestState> = withContext(dispatcher) {
        if (!form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        val editing = meals.states.value === expected && expectedForm != null && editableAdaptation(expectedForm)
        if (!currentAdaptationProposal(expected) && !editing) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        adaptationInterpretation.clear()
        adaptationForm.leave()
        meals.returnToRecipe()
    }
    suspend fun backAdaptation(expected: MealRequestState): PortResult<MealRequestState> = withContext(dispatcher) {
        if (!form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (!adaptationRouteAvailable() || meals.states.value !== expected ||
            expected.screen !in setOf(MealFlowScreen.ADAPT_MINE, MealFlowScreen.VARIANT_MINE))
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        adaptationInterpretation.clear()
        adaptationForm.leave()
        returnToAdaptationOrigin()
    }
    private suspend fun returnToAdaptationOrigin() = if (adaptationForms.value.returnScreen == MealFlowScreen.RECIPE)
        meals.returnToRecipe() else meals.returnToRecommendations()
    private fun currentAdaptationProposal(expected: MealRequestState) = adaptationRouteAvailable() &&
        meals.states.value === expected && expected.screen == MealFlowScreen.VARIANT_MINE &&
        expected.adaptation?.let { proposal -> expected.plan?.let { sameAdaptationParent(proposal.parent, it) } } == true
    private fun currentAdaptationForm(expected: AdaptationFormState): Boolean {
        val meal = meals.states.value
        return adaptationRouteAvailable() && !forms.value.dirty && adaptationForm.matches(expected) && expected.visible &&
            meal.screen == MealFlowScreen.ADAPT_MINE && meal.pendingAdaptation == null &&
            meal.phase !in setOf(MealFlowPhase.LOADING, MealFlowPhase.RESOLVING) && meal.plan?.plan?.status == "ready" &&
            expected.parent?.let { parent -> meal.plan?.let { sameAdaptationParent(parent, it) } } == true
    }
    private fun editableAdaptation(expected: AdaptationFormState): Boolean =
        currentAdaptationForm(expected) && !adaptationInterpretation.states.value.busy &&
            adaptationInterpretation.states.value.proposal == null
    private fun adaptationInterpretationReadyForStructuredAction(): Boolean =
        adaptationInterpretation.states.value.let { !it.busy && it.proposal == null && it.text.isNullOrBlank() }
    private fun adaptationRouteAvailable() = !closed && form.current() && kitchenPage.value == null &&
        !cookingNavigation.value.visible && cookingNavigation.value.confirmation == null &&
        cookbook.states.value.screen == CookbookScreen.HIDDEN && timers?.states?.value?.visible != true &&
        !reviewedPostsNavigation.value.visible && postDrafts?.states?.value?.screen?.let { it != PostDraftScreen.HIDDEN } != true &&
        circles?.states?.value?.screen?.let { it != CirclesScreen.HIDDEN } != true && invitationRoutesHidden()
    private fun adoptAdaptation(state: MealRequestState, origin: MealFlowScreen? = null, reuseDirty: Boolean = false) {
        if (meals.states.value !== state || !form.current()) return
        adaptationInterpretation.clear()
        val destination = origin ?: adaptationForms.value.returnScreen
        state.pendingAdaptation?.let { pending ->
            adaptationForm.open(pending.parent, AdaptationFormValues.from(pending), destination,
                visible = state.screen == MealFlowScreen.ADAPT_MINE)
            return
        }
        state.adaptation?.let { proposal ->
            adaptationForm.open(proposal.parent, AdaptationFormValues.from(proposal), destination,
                visible = state.screen == MealFlowScreen.ADAPT_MINE, reuse = reuseDirty && adaptationForms.value.dirty)
        }
    }
    /** Back is not queued behind a network task. The controller fences late replies itself. */
    suspend fun back(from: MealFlowScreen = meals.states.value.screen) = withContext(dispatcher) {
        if (acceptingRootRecipe || meals.states.value.screen in ROOT_RECIPE_SCREENS) return@withContext cookingActionFailure()
        if (from in setOf(MealFlowScreen.ADAPT, MealFlowScreen.VARIANT) &&
            (!adaptationRouteAvailable() || meals.states.value.screen != from))
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        if (from in setOf(MealFlowScreen.ADAPT_MINE, MealFlowScreen.VARIANT_MINE)) {
            val expected = meals.states.value
            return@withContext if (expected.screen == from) backAdaptation(expected) else PortResult.Failure(FailureReason.CONFLICT)
        }
        val result = if (from in setOf(MealFlowScreen.RECIPE, MealFlowScreen.ADAPT, MealFlowScreen.VARIANT))
            meals.returnToRecommendations() else meals.backToDraft()
        if (result is PortResult.Failure) form.fail(result.reason)
        result
    }
    suspend fun search() = perform { ingredients.search(forms.value.searchText) }
    suspend fun moreIngredients() = perform { ingredients.nextSearchPage() }
    suspend fun pantry() = perform { ingredients.refreshPantry() }
    suspend fun morePantry() = perform { ingredients.nextPantryPage() }

    /** Review an exact Saved copy in a separate root form. A current-source GET and local
     * source retention are not a meal request, selection, cooking or saving consent.
     * Unfinished main edits must be explicitly resolved first; entry never commits them. */
    suspend fun openSavedMakeMine(expected: CookbookState, expectedQuery: String,
        expectedMeal: MealRequestState, expectedForm: MealFormState, navigation: CookingNavigation) =
        perform(adaptation = true, admission = {
            savedCookingEntryCurrent(expected, expectedQuery, expectedMeal, expectedForm, navigation,
                allowSavedRootResume = true)
        }) {
            val captured = cookbook.captureMakeMineSource(expected)
            if (captured is PortResult.Failure) return@perform captured
            val source = (captured as PortResult.Value).value
            val capture = form.capture() ?: return@perform cookingActionFailure()
            acceptingRootRecipe = true; handingOffSavedRoot = true
            try {
                val result = meals.selectSavedMakeMineSource(source, expectedMeal)
                if (result is PortResult.Failure) return@perform result
                val current = (result as PortResult.Value).value
                if (!form.matches(capture.first) || forms.value.dirty || cookingNavigation.value !== navigation ||
                    !source.isCurrent || meals.states.value !== current) return@perform cookingActionFailure()
                when (val hidden = cookbook.hideMakeMineSource(expected, source)) {
                    is PortResult.Failure -> { meals.leaveCatalog(current); hidden }
                    is PortResult.Value -> {
                        planSourceReview = null
                        savedRootReturn = hidden.value to (current.savedSource ?: return@perform cookingActionFailure())
                        adoptRootRecipe(current, reuse = true)
                        result
                    }
                }
            } finally { acceptingRootRecipe = false; handingOffSavedRoot = false }
        }

    suspend fun openCatalog(expected: MealRequestState, expectedForm: MealFormState) = perform(adaptation = true,
        admission = { catalogActionCurrent(expected) && forms.value === expectedForm && !expectedForm.dirty &&
            (expected.screen !in ROOT_RECIPE_SCREENS || expected.screen == MealFlowScreen.CATALOG) }) {
        meals.openCatalog(expected).also { if (it is PortResult.Value) adoptRootRecipe(it.value) }
    }

    /** A fresh lookup of an accepted Plan's actual source, not a transformed recipe-ID guess.
     * A Make Mine origin keeps its meal inputs and separate swap intent until explicit review. */
    fun canReviewPlanSource(expectedMeal: MealRequestState, expectedMain: MealFormState,
        expectedAdaptation: AdaptationFormState? = null): Boolean {
        if (!adaptationMealStagingRouteAvailable() || action != null || meals.states.value !== expectedMeal ||
            forms.value !== expectedMain || expectedMain.dirty || expectedMain.busy ||
            !meals.draftReadiness.isReady || !meals.canReopenPlanSource(expectedMeal)) return false
        val retained = expectedMeal.draft?.let(MealFormValues::from) ?: return false
        if (expectedMain.values != retained) return false
        return if (expectedMeal.screen == MealFlowScreen.ADAPT_MINE) {
            expectedAdaptation != null && editableAdaptation(expectedAdaptation) &&
                adaptationInterpretationReadyForStructuredAction() &&
                expectedAdaptation.values?.meal?.let { samePlanSourceInputs(it, retained) } == true
        } else expectedAdaptation == null && !adaptationForms.value.visible &&
            expectedMeal.screen in setOf(MealFlowScreen.REQUEST, MealFlowScreen.RECIPE, MealFlowScreen.RECOMMENDATIONS)
    }

    suspend fun reviewPlanSource(expectedMeal: MealRequestState, expectedMain: MealFormState,
        expectedAdaptation: AdaptationFormState? = null): PortResult<MealRequestState> = perform(adaptation = true,
        admission = { canReviewPlanSource(expectedMeal, expectedMain, expectedAdaptation) }) {
        val capture = form.capture() ?: return@perform cookingActionFailure()
        val ownedMain = forms.value
        val ownedAction = action
        val route = adaptationMealStagingObservations()
        val parent = expectedMeal.plan ?: return@perform cookingActionFailure()
        val original = expectedMeal.draft?.let(MealFormValues::from) ?: return@perform cookingActionFailure()
        val proposed = expectedAdaptation?.values?.meal ?: original
        val result = meals.reopenPlanSource(expectedMeal)
        if (result is PortResult.Value) {
            val current = result.value
            val source = current.rootSource ?: return@perform cookingActionFailure()
            if (action !== ownedAction || forms.value !== ownedMain || !form.savedCurrent(capture.first) ||
                !adaptationMealStagingRouteAvailable() ||
                adaptationMealStagingObservations().zip(route).any { (now, then) -> now !== then } ||
                expectedAdaptation?.let { !adaptationForm.matches(it) } == true ||
                meals.states.value !== current || current.screen !in PLAN_SOURCE_SCREENS ||
                current.draft?.let(MealFormValues::from) != original ||
                current.plan?.let { sameAdaptationParent(parent, it) } != true ||
                !sourceMatchesPlanInputs(source, proposed)) return@perform cookingActionFailure()
            if (expectedAdaptation != null) adaptationForm.leave()
            planSourceReview = PlanSourceReview(source, parent, original, capture.first, expectedMeal.screen,
                adaptationForms.value.takeIf { expectedAdaptation != null }, proposed)
            // No main edit, root-form adoption or new request is part of this lookup ACK.
        }
        result
    }

    private fun samePlanSourceInputs(first: MealFormValues, second: MealFormValues) =
        first.mode == second.mode && first.baseDescription == second.baseDescription &&
        first.basePreparation == second.basePreparation && first.baseIngredientIds == second.baseIngredientIds &&
        first.preferencesPendingSync == second.preferencesPendingSync && first.savedRecipeId == second.savedRecipeId &&
        first.savedMakeMine == second.savedMakeMine && first.sourceRecipeVersionId == second.sourceRecipeVersionId &&
        first.sourcePostId == second.sourcePostId && first.sourcePostVersion == second.sourcePostVersion

    private fun sourceMatchesPlanInputs(source: MealRootSource, values: MealFormValues): Boolean = when (source) {
        is MealPostRootSource -> values.sourcePostId == source.postId && values.sourcePostVersion == source.postVersion &&
            values.savedRecipeId == null && values.sourceRecipeVersionId == null && !values.savedMakeMine
        is MealSavedRootSource -> values.savedRecipeId == source.savedRecipeId && values.savedMakeMine &&
            values.sourceRecipeVersionId == null && values.sourcePostId == null && values.sourcePostVersion == null
        is MealCatalogRecipe -> values.sourceRecipeVersionId == source.recipe.id.value && values.savedRecipeId == null &&
            !values.savedMakeMine && values.sourcePostId == null && values.sourcePostVersion == null
    }

    private fun planSourceReviewCurrent(retained: PlanSourceReview, expected: MealRequestState): Boolean =
        planSourceReviewInputsCurrent(retained, expected) &&
        expected.rootSource?.let { sameRootSource(retained.source, it) && sourceMatchesPlanInputs(it, retained.proposed) } == true

    private fun planSourceReviewInputsCurrent(retained: PlanSourceReview, expected: MealRequestState): Boolean =
        planSourceReview === retained && catalogActionCurrent(expected) && cookingChildRoutesHidden() &&
        form.savedCurrent(retained.formGeneration) && forms.value.values == retained.main &&
        expected.draft?.let(MealFormValues::from) == retained.main &&
        expected.plan?.let { sameAdaptationParent(retained.parent, it) } == true &&
        retained.adaptation?.let { adaptationForm.matches(it) } != false

    /** Explanatory copy only; retained inputs do not grant or submit a new request. */
    fun planSourceReviewNotice(expected: MealRequestState): String? {
        val retained = planSourceReview ?: return null
        if (retained.adaptation == null || !planSourceReviewCurrent(retained, expected)) return null
        return if (retained.transferred)
            "Your Make Mine meal inputs are in this source form. Your previous swap choice remains separate; it is not being executed."
        else "Your Make Mine meal inputs are retained for the Make Mine button. Your previous swap choice remains separate; reviewing this source sends no new meal request."
    }

    fun catalogSearchText(expected: MealRequestState, text: String): PortResult<Unit> {
        if (action != null || !catalogActionCurrent(expected) || expected.screen != MealFlowScreen.CATALOG) return cookingActionFailure()
        if (!isValidRecipeCatalogQuery(text)) return PortResult.Failure(FailureReason.INVALID_DATA)
        mutableCatalogQuery.value = text
        return PortResult.Value(Unit)
    }
    suspend fun browseRecipes(expected: MealRequestState, query: String, more: Boolean = false) = perform(adaptation = true,
        admission = { catalogActionCurrent(expected) && expected.screen == MealFlowScreen.CATALOG && catalogQuery.value == query }) {
        meals.browseRecipes(expected, query, more)
    }
    suspend fun openCatalogRecipe(expected: MealRequestState, recipe: MealCatalogRecipe): PortResult<MealRequestState> = withContext(dispatcher) {
        val result = perform(adaptation = true,
            admission = { catalogActionCurrent(expected) && expected.screen == MealFlowScreen.CATALOG && !forms.value.dirty }) {
            val opened = meals.openCatalogRecipe(expected, recipe)
            if (opened is PortResult.Value) {
                planSourceReview = null
                rootRecipeForm.leave()
                // Opening this exact recipe is the user's lookup action. Use its observed
                // ingredient IDs, not the unrelated selected meal or a catalog search.
                // Metadata failure must not undo a successfully opened recipe; the picker
                // keeps downloaded labels and its explicit retry state independently.
                if (catalogActionCurrent(opened.value)) opened.value.catalogSource?.recipe?.let { source ->
                    val ids = recipeLabelIds(source)
                    if (ids.isNotEmpty()) withTimeoutOrNull(2_000L) { ingredients.resolveLabels(ids) }
                }
            }
            opened
        }
        // Back can retire the selection while names load. Check after perform so that an
        // obsolete completion cannot place a new error on the user's current screen.
        if (result is PortResult.Value && !catalogActionCurrent(result.value)) cookingActionFailure() else result
    }
    suspend fun refreshCatalogSource(expected: MealRequestState) = perform(adaptation = true,
        admission = { catalogActionCurrent(expected) && (expected.screen in setOf(MealFlowScreen.CATALOG_RECIPE, MealFlowScreen.SAVED_RECIPE, MealFlowScreen.POST_RECIPE) ||
            expected.postSourceNeedsRefresh && expected.screen in ROOT_RECIPE_SCREENS) }) {
        val retained = planSourceReview?.takeIf { planSourceReviewInputsCurrent(it, expected) }
        val rootBefore = rootRecipeForms.value
        val retainedInputs = retained?.let { handoff ->
            rootBefore.values?.takeIf { handoff.transferred && sourceMatchesPlanInputs(handoff.source, it) }
                ?: handoff.proposed
        }
        val refreshed = when {
            expected.postSource != null || expected.postSourceNeedsRefresh -> meals.refreshPostSource(expected)
            expected.savedSource != null -> meals.refreshSavedSource(expected)
            else -> meals.refreshCatalogSource(expected)
        }
        if (retained != null && refreshed is PortResult.Value &&
            planSourceReviewInputsCurrent(retained, refreshed.value) && rootRecipeForm.matches(rootBefore)) {
            val source = refreshed.value.rootSource
            if (source != null && retainedInputs != null && sourceMatchesPlanInputs(source, retainedInputs)) {
                if (!sameRootSource(retained.source, source)) {
                    // A deliberate refresh can renew a source observation. Preserve inputs,
                    // but transfer them only on the next explicit Make Mine action.
                    planSourceReview = PlanSourceReview(source, retained.parent, retained.main, retained.formGeneration,
                        retained.origin, retained.adaptation, retainedInputs)
                }
            } else if (source != null) {
                // This is a different real source/version. The adaptation owner still holds
                // every previous meal/swap input; none is silently assigned to the new source.
                planSourceReview = null
            }
        }
        if (refreshed is PortResult.Value && (expected.postSource != null || expected.postSourceNeedsRefresh))
            adoptRootRecipe(refreshed.value, reuse = true)
        refreshed
    }
    suspend fun openRootMakeMine(expected: MealRequestState) = perform(adaptation = true,
        admission = { catalogActionCurrent(expected) && expected.screen in setOf(MealFlowScreen.CATALOG_RECIPE, MealFlowScreen.SAVED_RECIPE, MealFlowScreen.POST_RECIPE) && !forms.value.dirty }) {
        val retained = planSourceReview
        if (retained != null && !planSourceReviewCurrent(retained, expected)) return@perform cookingActionFailure()
        val capture = form.capture() ?: return@perform cookingActionFailure()
        val ownedMain = forms.value
        val result = meals.openRootMakeMine(expected)
        if (result is PortResult.Value) {
            if (retained != null) {
                if (forms.value !== ownedMain || !form.savedCurrent(capture.first) ||
                    !planSourceReviewCurrent(retained, result.value)) return@perform cookingActionFailure()
                if (!retained.transferred) {
                    rootRecipeForm.open(result.value.rootSource ?: return@perform cookingActionFailure(), retained.proposed, true)
                    retained.transferred = true
                } else adoptRootRecipe(result.value, reuse = true)
            } else adoptRootRecipe(result.value, reuse = true)
        }
        result
    }
    fun editRootRecipe(expected: RootRecipeFormState, transform: (MealFormValues) -> MealFormValues): PortResult<RootRecipeFormState> {
        if (action != null || !editableRootRecipe(expected)) return cookingActionFailure()
        return rootRecipeForm.edit(expected, transform)
    }
    fun rootRecipeSearchText(expected: RootRecipeFormState, text: String): PortResult<RootRecipeFormState> {
        if (action != null || !editableRootRecipe(expected)) return cookingActionFailure()
        return rootRecipeForm.searchText(expected, text)
    }
    suspend fun searchRootIngredients(expected: RootRecipeFormState, more: Boolean = false) = perform(adaptation = true,
        admission = { editableRootRecipe(expected) }) {
        if (more) ingredients.nextSearchPage() else ingredients.search(expected.searchText)
    }
    suspend fun requestRootMakeMine(expected: MealRequestState, expectedForm: RootRecipeFormState) = perform(adaptation = true,
        admission = { meals.states.value === expected && editableRootRecipe(expectedForm) }) {
        val result = meals.requestRootMakeMine(expected, expectedForm.values!!.draft())
        // A sent original may exist after a transport failure. Display those exact retained inputs.
        if (rootRecipeForm.matches(expectedForm) && (meals.states.value.pendingRootDraft != null || meals.states.value.rootProposal != null))
            adoptRootRecipe(meals.states.value)
        result
    }
    suspend fun retryRootMakeMine(expected: MealRequestState) = perform(adaptation = true,
        admission = { catalogActionCurrent(expected) && expected.screen == MealFlowScreen.ROOT_MAKE_MINE &&
            expected.pendingRootDraft != null && !forms.value.dirty }) {
        meals.retrySubmitted().also { adoptRootRecipe(meals.states.value) }
    }
    suspend fun reopenRootProposal(expected: MealRequestState) = perform(adaptation = true,
        admission = { catalogActionCurrent(expected) && expected.screen in setOf(MealFlowScreen.CATALOG_RECIPE, MealFlowScreen.SAVED_RECIPE) && expected.rootProposal != null }) {
        meals.reopenRootProposal(expected).also { if (it is PortResult.Value) adoptRootRecipe(it.value) }
    }
    suspend fun keepRootSource(expected: MealRequestState) = perform(adaptation = true,
        admission = { catalogActionCurrent(expected) && expected.screen == MealFlowScreen.ROOT_VARIANT && expected.rootProposal != null }) {
        val result = meals.keepRootSource(expected.rootProposal!!)
        if (result is PortResult.Value) {
            rootRecipeForm.leave()
            returnSavedRoot(result.value) ?: result
        } else result
    }
    suspend fun acceptRootProposal(expected: MealRequestState) = perform(adaptation = true,
        admission = { catalogActionCurrent(expected) && expected.screen == MealFlowScreen.ROOT_VARIANT &&
            expected.rootProposal != null && !forms.value.dirty }) {
        acceptingRootRecipe = true
        try {
            val result = meals.acceptRootProposal(expected.rootProposal!!)
            if (result is PortResult.Value && meals.states.value === result.value && !forms.value.dirty) {
                rootRecipeForm.leave(); savedRootReturn = null; reuseRootReturn = null; planSourceReview = null; form.adopt(result.value.draft)
            }
            result
        } finally {
            acceptingRootRecipe = false
        }
    }
    suspend fun editRootRequest(expected: MealRequestState) = perform(adaptation = true,
        admission = { catalogActionCurrent(expected) && expected.screen == MealFlowScreen.ROOT_VARIANT &&
            expected.rootProposal != null && !forms.value.dirty }) {
        val draft = expected.rootProposal!!.draft
        val discarded = meals.discardRootProposal(expected)
        if (discarded is PortResult.Failure) return@perform discarded
        val current = (discarded as PortResult.Value).value
        if (!catalogActionCurrent(current)) return@perform cookingActionFailure()
        val opened = meals.openRootMakeMine(current)
        if (opened is PortResult.Value && meals.states.value === opened.value) opened.value.rootSource?.let {
            rootRecipeForm.open(it, MealFormValues.from(draft), true)
        }
        opened
    }
    suspend fun clearDirectRecipeSource(expected: MealRequestState, expectedForm: MealFormState) = perform(admission = {
        catalogActionCurrent(expected) && forms.value === expectedForm && expected.screen == MealFlowScreen.REQUEST &&
            !expectedForm.dirty && (expectedForm.values?.sourceRecipeVersionId != null || expectedForm.values?.sourcePostId != null) && savedCookingIdle()
    }) {
        val capture = form.capture() ?: return@perform cookingActionFailure()
        val result = meals.clearDirectRecipeSource(expected, capture.second.copy(sourceRecipeVersionId = null,
            sourcePostId = null, sourcePostVersion = null).draft())
        if (result is PortResult.Value && form.matches(capture.first) && !forms.value.dirty) {
            planSourceReview = null
            form.adopt(result.value.draft)
        }
        result
    }
    /** Back changes only navigation. An unknown original is never discarded or retried. */
    suspend fun backCatalog(expected: MealRequestState): PortResult<MealRequestState> = withContext(dispatcher) {
        if (!catalogActionCurrent(expected) || expected.screen !in ROOT_RECIPE_SCREENS) return@withContext cookingActionFailure()
        val planReturn = planSourceReview
        if (action == null && expected.screen in PLAN_SOURCE_SCREENS && planReturn != null &&
            planSourceReviewCurrent(planReturn, expected)) {
            // Returning to ADAPT would need its existing immutable-parent admission. Keep
            // its inputs hidden and return to REQUEST instead of manufacturing that grant.
            val result = if (planReturn.origin in setOf(MealFlowScreen.RECIPE, MealFlowScreen.RECOMMENDATIONS))
                meals.returnToRecipe() else meals.backToDraft()
            if (result is PortResult.Value && meals.states.value === result.value &&
                form.savedCurrent(planReturn.formGeneration)) {
                rootRecipeForm.leave()
                // Keep the same source/input handoff for a later explicit source review.
                // Back neither consumes proposed inputs nor discards retained root edits.
            }
            return@withContext result
        }
        if (action == null && expected.postSourceNeedsRefresh) {
            // Expiry intentionally removes the proposal projection. Back is still local
            // navigation and must not require a network refresh or discard an original.
            return@withContext meals.leaveCatalog(expected).also { if (it is PortResult.Value) rootRecipeForm.leave() }
        }
        val reuseReturn = reuseRootReturn
        if (action == null && expected.screen == MealFlowScreen.CATALOG_RECIPE && reuseReturn != null &&
            expected.catalogSource?.let { sameRootSource(it, reuseReturn.second) } == true) {
            val left = meals.leaveCatalog(expected)
            if (left is PortResult.Failure) return@withContext left
            rootRecipeForm.leave(); reuseRootReturn = null
            return@withContext when (val restored = mealMemory?.restoreReuseSource(reuseReturn.first)) {
                is PortResult.Failure -> restored
                else -> left
            }
        }
        if (expected.savedSource?.let { source -> savedRootReturn?.second?.let { sameRootSource(it, source) } } == true && action == null) {
            val retained = if (expected.screen == MealFlowScreen.ROOT_VARIANT)
                meals.keepRootSource(expected.rootProposal ?: return@withContext cookingActionFailure())
                else PortResult.Value(expected)
            if (retained is PortResult.Failure) return@withContext retained
            val restored = returnSavedRoot((retained as PortResult.Value).value)
            if (restored != null) { rootRecipeForm.leave(); return@withContext restored }
        }
        val result = when (expected.screen) {
            MealFlowScreen.ROOT_VARIANT -> if (action == null) meals.keepRootSource(expected.rootProposal
                ?: return@withContext cookingActionFailure()) else return@withContext cookingActionFailure()
            MealFlowScreen.ROOT_MAKE_MINE -> if (expected.pendingRootDraft == null && action == null) meals.openCatalog(expected)
                else meals.leaveCatalog(expected)
            MealFlowScreen.CATALOG_RECIPE -> meals.returnToCatalog(expected)
            MealFlowScreen.SAVED_RECIPE -> meals.leaveCatalog(expected)
            else -> meals.leaveCatalog(expected)
        }
        if (result is PortResult.Value) rootRecipeForm.leave()
        result
    }
    /** Only the live exact Saved origin can restore its overlay. After restart the retained
     * SAVED_RECIPE view remains a source observation, never fabricated cookbook authority. */
    private suspend fun returnSavedRoot(expected: MealRequestState): PortResult<MealRequestState>? {
        val retained = savedRootReturn ?: return null
        val source = expected.savedSource ?: return null
        if (!sameRootSource(retained.second, source)) return null
        handingOffSavedRoot = true; acceptingRootRecipe = true
        return try {
            val left = meals.leaveCatalog(expected)
            if (left is PortResult.Failure) left else when (val restored = cookbook.restoreMakeMineSource(retained.first)) {
                // Do not repeatedly retry an origin that an intervening Saved action retired.
                // The retained source remains resumable from Cook, without inventing a view.
                is PortResult.Failure -> { savedRootReturn = null; restored }
                is PortResult.Value -> { savedRootReturn = null; left }
            }
        } finally { handingOffSavedRoot = false; acceptingRootRecipe = false }
    }
    private fun catalogActionCurrent(expected: MealRequestState) = session.scope.actorKind == ActorKind.ACCOUNT &&
        !handingOffSavedRoot && adaptationRouteAvailable() && mutableSavedCookingConfirmation.value == null && meals.states.value === expected
    private fun editableRootRecipe(expected: RootRecipeFormState): Boolean {
        val meal = meals.states.value
        return catalogActionCurrent(meal) && !forms.value.dirty && rootRecipeForm.matches(expected) && expected.visible &&
            meal.screen == MealFlowScreen.ROOT_MAKE_MINE && meal.pendingRootDraft == null && meal.rootProposal == null &&
            meal.phase !in setOf(MealFlowPhase.LOADING, MealFlowPhase.RESOLVING) &&
            meal.rootSource?.let { expected.source?.let { old -> sameRootSource(old, it) } } == true
    }
    private fun adoptRootRecipe(state: MealRequestState, reuse: Boolean = false) {
        if (meals.states.value !== state || !form.current()) return
        val source = state.rootSource ?: return
        val draft = state.pendingRootDraft ?: state.rootProposal?.draft
        val values = draft?.let(MealFormValues::from) ?: (forms.value.values ?: return).let { original ->
            // A new catalog root is not an adaptation of the main form's prepared base.
            // Retained sent/proposed drafts above and reused unsent edits remain exact.
            original.copy(mode = if (original.mode == MealMode.IMPROVE) MealMode.AUTO else original.mode,
                baseDescription = "", basePreparation = BasePreparation.UNKNOWN, baseIngredientIds = null,
                savedRecipeId = source.savedRecipeId, savedMakeMine = source.savedRecipeId != null,
                sourceRecipeVersionId = if (source.savedRecipeId == null && source.sourcePostId == null) source.recipe.id.value else null,
                sourcePostId = source.sourcePostId, sourcePostVersion = (source as? MealPostRootSource)?.postVersion)
        }
        val retainedInputReview = planSourceReview?.let { it.transferred && planSourceReviewCurrent(it, state) } == true
        rootRecipeForm.open(source, values, state.screen == MealFlowScreen.ROOT_MAKE_MINE,
            (reuse || retainedInputReview) && draft == null)
    }

    /** Explicit read for the exact rendered recipe. Never changes a meal, pantry or command. */
    suspend fun loadMealIngredientNames(expected: MealRequestState, expectedForm: MealFormState) =
        perform(adaptation = true, admission = {
            !closed && form.current() && meals.states.value === expected && forms.value === expectedForm &&
                expected.screen in setOf(MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE,
                    MealFlowScreen.VARIANT, MealFlowScreen.VARIANT_MINE, MealFlowScreen.CATALOG_RECIPE, MealFlowScreen.SAVED_RECIPE,
                    MealFlowScreen.POST_RECIPE, MealFlowScreen.ROOT_MAKE_MINE, MealFlowScreen.ROOT_VARIANT) &&
                !cookingNavigation.value.visible && cookingNavigation.value.confirmation == null && cookingChildRoutesHidden()
        }) { ingredients.resolveLabels(mealLabelIds(MealScreenState.from(expected))) }

    suspend fun loadCookingIngredientNames(expected: CookingFlowState, navigation: CookingNavigation) =
        perform(admission = { cookingActionCurrent(expected, navigation) }) {
            val view = CookingScreenState.from(expected)
            if (!view.instructionsVisible && !view.preparedRecipeVisible) return@perform PortResult.Failure(FailureReason.CONFLICT)
            val ids = cookingLabelIds(view)
            ingredients.resolveLabels(ids)
        }

    suspend fun loadSavedIngredientNames(expected: CookbookState, expectedQuery: String) =
        perform(admission = { cookbookActionCurrent(expected, expectedQuery) && expected.screen == CookbookScreen.DETAIL }) {
            val recipe = expected.selected?.let(::SavedRecipePresentation)?.recipe
                ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
            ingredients.resolveLabels(recipeLabelIds(recipe))
        }

    /** Compatibility for explicit non-UI callers editing the local query before opening Saved.
     * Rendered fields use the exact-state overload below; neither overload reads or writes storage. */
    fun cookbookSearchText(value: String): PortResult<Unit> {
        val expected = cookbook.states.value
        if (expected.screen != CookbookScreen.HIDDEN) return cookbookSearchText(value, expected)
        if (!cookbookEntryCurrent(expected)) return cookingActionFailure()
        if (value.length > 200) return PortResult.Failure(FailureReason.INVALID_DATA)
        mutableCookbookQuery.value = value
        return PortResult.Value(Unit)
    }
    /** Do not bind the old query here: several input events may precede the next composition. */
    fun cookbookSearchText(value: String, expected: CookbookState): PortResult<Unit> {
        if (!cookbookActionCurrent(expected) || expected.screen != CookbookScreen.LIST) return cookingActionFailure()
        if (value.length > 200) return PortResult.Failure(FailureReason.INVALID_DATA)
        mutableCookbookQuery.value = value
        return PortResult.Value(Unit)
    }
    /** Explicit local browsing; entering the cookbook does not fetch, save or drain any command. */
    suspend fun openCookbook(expected: CookbookState = cookbook.states.value, expectedQuery: String = cookbookQuery.value,
        expectedMeal: MealRequestState = meals.states.value, expectedForm: MealFormState = forms.value,
        navigation: CookingNavigation = cookingNavigation.value,
        returnToCircles: CirclesListReturnTicket? = null) = perform(admission = {
        (cookbookEntryCurrent(expected) || cookbookActionCurrent(expected)) && cookbookQuery.value == expectedQuery && meals.states.value === expectedMeal &&
            forms.value === expectedForm && cookingNavigation.value === navigation
    }) {
        openCookbookOwned(expectedQuery, returnToCircles)
    }

    /** Caller owns navigation admission. No meal/adaptation editor is changed here. */
    private suspend fun openCookbookOwned(expectedQuery: String,
        returnToCircles: CirclesListReturnTicket? = null): PortResult<CookbookState> {
        collectionSavedReturn = null
        savedReturnsToCircles = returnToCircles?.takeIf { it === circlesTabReturn }
        val retained = savedTabReturn
        val actual = cookbook.states.value
        return if (actual.screen != CookbookScreen.HIDDEN) PortResult.Value(actual)
        else if (actual.pending != null) {
            savedTabReturn = null; savedReturnsToCircles = null
            cookbook.backToList()
        }
        else if (retained == null) cookbook.searchDownloaded(expectedQuery)
        else if (cookbook.canResumeList(retained)) {
            // A failed local denial check must remain a failure; never silently search.
            cookbook.resumeList(retained).also { if (it is PortResult.Value) savedTabReturn = null }
        } else {
            savedTabReturn = null
            val current = cookbook.states.value
            when {
                current.screen != CookbookScreen.HIDDEN -> PortResult.Value(current)
                current.pending != null -> cookbook.backToList()
                current.localOnly -> cookbook.searchDownloaded(current.query.orEmpty())
                else -> cookbook.load(current.query)
            }
        }
    }

    /** Capturing does not inspect storage or connectivity. The account-owned read below is
     * the only source of downloaded/resumable availability for the original Offline screen. */
    internal fun captureOfflineRecovery(expectedMeal: MealRequestState, expectedForm: MealFormState,
        expectedCookbook: CookbookState, expectedQuery: String, expectedCooking: CookingFlowState,
        expectedNavigation: CookingNavigation, expectedHome: BlueprintMealLandingVisit? = null): MealOfflineRecoveryTicket? {
        val ticket = MealOfflineRecoveryTicket(offlineOwner, expectedMeal, expectedForm, expectedCookbook,
            expectedQuery, expectedCooking, expectedNavigation, expectedHome)
        return ticket.takeIf { offlineCurrent(it) }
    }

    private fun offlineOriginCurrent(ticket: MealOfflineRecoveryTicket): Boolean =
        ticket.home?.let { home ->
            home.home && home.offline && blueprintLanding.current(home, true) &&
                ticket.meal.phase == MealFlowPhase.OFFLINE_DRAFT &&
                blueprintMealLandingEligible(MealScreenState.from(ticket.meal), ticket.form)
        } ?: (ticket.meal.phase == MealFlowPhase.ERROR &&
            (ticket.meal.failureReason ?: ticket.form.failure) == FailureReason.OFFLINE)

    private fun offlineCurrent(ticket: MealOfflineRecoveryTicket, operation: Any? = null): Boolean =
        ticket.owner === offlineOwner && !closed && form.current() && action === operation && !forms.value.busy &&
            meals.states.value === ticket.meal && forms.value === ticket.form &&
            cookbook.states.value === ticket.cookbook && cookbookQuery.value == ticket.query &&
            cooking.states.value === ticket.cooking && cookingNavigation.value === ticket.navigation &&
            ticket.meal.screen in setOf(MealFlowScreen.REQUEST, MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE) &&
            offlineOriginCurrent(ticket) &&
            !ticket.navigation.visible && ticket.navigation.confirmation == null &&
            !acceptingRootRecipe && !handingOffSavedRoot && !adaptationForms.value.visible && !rootRecipeForms.value.visible &&
            cookingChildRoutesHidden() && reportsHidden() && mutableSavedCookingConfirmation.value == null &&
            mutablePostSaveReview.value == null

    internal fun canRetryOfflineConnection(ticket: MealOfflineRecoveryTicket): Boolean =
        offlineCurrent(ticket) && meals.canCheckAccountConnection(ticket.meal)

    internal suspend fun inspectOfflineRecovery(ticket: MealOfflineRecoveryTicket,
        presentationCurrent: () -> Boolean): PortResult<MealOfflineAvailability> = withContext(dispatcher) {
        if (!offlineCurrent(ticket) || !presentationCurrent()) return@withContext cookingActionFailure()
        val own = Any(); action = own
        fun current() = offlineCurrent(ticket, own) && presentationCurrent()
        try {
            val downloaded = when (val result = cookbook.inspectDownloaded(ticket.cookbook, ::current)) {
                is PortResult.Failure -> return@withContext result
                is PortResult.Value -> result.value
            }
            if (!current()) return@withContext cookingActionFailure()
            val resumable = when (val result = cooking.inspectOfflineResume(ticket.cooking, ::current)) {
                is PortResult.Failure -> return@withContext result
                is PortResult.Value -> result.value
            }
            if (!current()) return@withContext cookingActionFailure()
            val observation = MealOfflineAvailability(downloaded, resumable, meals.canCheckAccountConnection(ticket.meal))
            offlineAvailability = ticket to observation
            PortResult.Value(observation)
        } finally { if (action === own) action = null }
    }

    internal suspend fun openOfflineSaved(ticket: MealOfflineRecoveryTicket,
        presentationCurrent: () -> Boolean): PortResult<CookbookState> = withContext(dispatcher) {
        if (!offlineCurrent(ticket) || !presentationCurrent() ||
            offlineAvailability?.takeIf { it.first === ticket }?.second?.downloadedMeals?.let { it > 0 } != true)
            return@withContext cookingActionFailure()
        val own = Any(); action = own
        try {
            val result = cookbook.openDownloadedRecovery(ticket.cookbook, ticket.query) { offlineCurrent(ticket, own) && presentationCurrent() }
            if (result is PortResult.Value) {
                // Deliberate LIST replaces only the Cookbook observation. Do not require
                // the disposed Offline render after that publication or restore any parent.
                if (closed || !form.current() || action !== own || !offlineOriginCurrent(ticket) || meals.states.value !== ticket.meal ||
                    forms.value !== ticket.form || cooking.states.value !== ticket.cooking ||
                    cookingNavigation.value !== ticket.navigation || cookbook.states.value !== result.value)
                    return@withContext cookingActionFailure()
                savedTabReturn = null; savedReturnsToCircles = null; collectionSavedReturn = null
                offlineAvailability = null
            }
            result
        } finally { if (action === own) action = null }
    }

    internal suspend fun resumeOfflineCooking(ticket: MealOfflineRecoveryTicket,
        presentationCurrent: () -> Boolean): PortResult<CookingNavigation> = withContext(dispatcher) {
        if (!offlineCurrent(ticket) || !presentationCurrent() ||
            offlineAvailability?.takeIf { it.first === ticket }?.second?.canResumeCooking != true)
            return@withContext cookingActionFailure()
        val own = Any(); action = own
        fun current() = offlineCurrent(ticket, own) && presentationCurrent()
        try {
            when (val checked = cooking.inspectOfflineResume(ticket.cooking, ::current)) {
                is PortResult.Failure -> return@withContext checked
                is PortResult.Value -> if (!checked.value) return@withContext PortResult.Failure(FailureReason.CONFLICT)
            }
            if (!current()) return@withContext cookingActionFailure()
            // A hidden active pin can be on its retained recipe overview. The existing
            // cached return changes only its local screen, never progress/status/timers.
            val shownCooking = if (ticket.cooking.screen == CookingFlowScreen.COOK) ticket.cooking
                else when (val returned = cooking.returnToCooking(ticket.cooking, ::current)) {
                    is PortResult.Failure -> return@withContext returned
                    is PortResult.Value -> returned.value
                }
            if (closed || !form.current() || action !== own || !offlineOriginCurrent(ticket) || meals.states.value !== ticket.meal ||
                forms.value !== ticket.form || cookbook.states.value !== ticket.cookbook ||
                cookbookQuery.value != ticket.query || cookingNavigation.value !== ticket.navigation ||
                cooking.states.value !== shownCooking || !cookingChildRoutesHidden()) return@withContext cookingActionFailure()
            val shown = cookingUi.show() ?: return@withContext cookingActionFailure()
            if (closed || !form.current() || action !== own || !offlineOriginCurrent(ticket) || cookingNavigation.value !== shown ||
                cooking.states.value !== shownCooking || meals.states.value !== ticket.meal || forms.value !== ticket.form)
                return@withContext cookingActionFailure()
            offlineAvailability = null
            PortResult.Value(shown)
        } finally { if (action === own) action = null }
    }

    internal suspend fun retryOfflineConnection(ticket: MealOfflineRecoveryTicket,
        presentationCurrent: () -> Boolean): PortResult<Unit> = withContext(dispatcher) {
        if (!offlineCurrent(ticket) || !presentationCurrent() || !meals.canCheckAccountConnection(ticket.meal))
            return@withContext cookingActionFailure()
        val own = Any(); action = own
        try {
            meals.checkAccountConnection(ticket.meal) { offlineCurrent(ticket, own) && presentationCurrent() }
        } finally { if (action === own) action = null }
    }

    /** MEMORY.04 is navigation, not reuse of a memory observation. Expiry/error therefore
     * does not require another history read before leaving. Kept commands are not sent. */
    internal fun memorySavedAvailable(expected: MealMemoryState, expectedCookbook: CookbookState,
        expectedQuery: String, expectedMeal: MealRequestState, expectedForm: MealFormState,
        expectedCooking: CookingFlowState, navigation: CookingNavigation): Boolean =
        !closed && form.current() && action == null && !acceptingRootRecipe && !handingOffSavedRoot &&
            mealMemory?.isCurrent(expected) == true && expected.screen == MealMemoryScreen.MEMORY &&
            expected.phase !in setOf(MealMemoryPhase.LOADING, MealMemoryPhase.WORKING, MealMemoryPhase.UNAVAILABLE) &&
            expected.review == null && expected.pending == null &&
            cookbook.states.value === expectedCookbook && !expectedCookbook.busy && expectedCookbook.deleteConfirmation == null &&
            cookbookQuery.value == expectedQuery && meals.states.value === expectedMeal &&
            forms.value === expectedForm && !expectedForm.busy && expectedForm.values != null &&
            cooking.states.value === expectedCooking && cookingNavigation.value === navigation &&
            mutableSavedCookingConfirmation.value == null && mutablePostSaveReview.value == null &&
            cookbookRouteCurrent(exceptMemory = true)

    internal fun canOpenMemorySocial(destination: BlueprintScreenId, expected: MealMemoryState,
        expectedCookbook: CookbookState, expectedQuery: String, expectedMeal: MealRequestState,
        expectedForm: MealFormState, expectedCooking: CookingFlowState, navigation: CookingNavigation): Boolean =
        destination in setOf(BlueprintScreenId.TODAY, BlueprintScreenId.INBOX, BlueprintScreenId.PROFILE_PLATE) &&
            session.scope.actorKind == ActorKind.ACCOUNT && socialReads != null &&
            memorySavedAvailable(expected, expectedCookbook, expectedQuery, expectedMeal, expectedForm, expectedCooking, navigation)

    /** An expired list may depart, but neither its rows nor their authorization are retained.
     * The original raw UI visit is consumed before the exact owner's hidden publication. */
    internal suspend fun openMemorySocial(destination: BlueprintScreenId, expected: MealMemoryState,
        expectedCookbook: CookbookState, expectedQuery: String, expectedMeal: MealRequestState,
        expectedForm: MealFormState, expectedCooking: CookingFlowState, navigation: CookingNavigation,
        presentationCurrent: () -> Boolean): PortResult<SocialReadState> = withContext(dispatcher) {
        val owner = mealMemory ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val reader = socialReads ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (!presentationCurrent() || !canOpenMemorySocial(destination, expected, expectedCookbook, expectedQuery,
                expectedMeal, expectedForm, expectedCooking, navigation)) return@withContext cookingActionFailure()
        val capture = form.capture() ?: return@withContext cookingActionFailure()
        val token = Any(); action = token
        fun parentsCurrent() = !closed && form.current() && action === token && form.matches(capture.first) &&
            forms.value === expectedForm && meals.states.value === expectedMeal &&
            cookbook.states.value === expectedCookbook && cookbookQuery.value == expectedQuery &&
            cooking.states.value === expectedCooking && cookingNavigation.value === navigation &&
            !acceptingRootRecipe && !handingOffSavedRoot && mutableSavedCookingConfirmation.value == null &&
            mutablePostSaveReview.value == null
        val hidden: MealMemoryState
        try {
            if (!presentationCurrent() || !parentsCurrent() || owner.states.value !== expected ||
                !cookbookRouteCurrent(exceptMemory = true)) return@withContext cookingActionFailure()
            hidden = when (val left = owner.leaveForNavigation(expected)) {
                is PortResult.Failure -> return@withContext left
                is PortResult.Value -> left.value
            }
            if (!parentsCurrent() || !owner.isCurrent(hidden) || hidden.screen != MealMemoryScreen.HIDDEN ||
                !cookbookRouteCurrent()) return@withContext cookingActionFailure()
            memorySocialReturn = hidden
            socialReturn = SocialReturn.MEMORY
        } finally { if (action === token) action = null }
        // The social reader's own Back can retire LOADING. No form busy or old Memory
        // presentation predicate is held over this explicit authorized read.
        val result = when (destination) {
            BlueprintScreenId.TODAY -> reader.openToday()
            BlueprintScreenId.INBOX -> reader.openInbox()
            BlueprintScreenId.PROFILE_PLATE -> reader.openProfilePlate()
            else -> return@withContext cookingActionFailure()
        }
        if (!form.current() || socialReturn != SocialReturn.MEMORY || memorySocialReturn !== hidden ||
            !owner.isCurrent(hidden) || result is PortResult.Value && reader.states.value !== result.value)
            cookingActionFailure() else result
    }

    /** MEMORY.02 borrows the existing saved-preference editor. The click requires the exact
     * Memory list; the admitted child deliberately survives that list's observation expiry.
     * claim returns the actual UI visit predicate, not a permission or a replacement owner. */
    internal suspend fun openMemoryPreferences(expected: MealMemoryState, expectedCookbook: CookbookState,
        expectedQuery: String, expectedMeal: MealRequestState, expectedForm: MealFormState,
        expectedCooking: CookingFlowState, navigation: CookingNavigation,
        presentationCurrent: () -> Boolean,
        claim: (continuation: () -> Boolean) -> (() -> Boolean)?): PortResult<KitchenInputState> = withContext(dispatcher) {
        val owner = mealMemory ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (!accountKitchenPreferencesAvailable || !presentationCurrent() ||
            !memorySavedAvailable(expected, expectedCookbook, expectedQuery, expectedMeal, expectedForm, expectedCooking, navigation))
            return@withContext cookingActionFailure()
        val memoryVisitCurrent = owner.preferenceEditorContinuation(expected) ?: return@withContext cookingActionFailure()
        val captured = form.capture() ?: return@withContext cookingActionFailure()
        fun continuationCurrent(): Boolean {
            val memory = owner.states.value
            return accountKitchenPreferencesAvailable && memoryVisitCurrent() && form.matches(captured.first) &&
                forms.value.values == expectedForm.values && forms.value.dirty == expectedForm.dirty &&
                forms.value.searchText == expectedForm.searchText && !forms.value.busy &&
                meals.states.value === expectedMeal && cookbook.states.value === expectedCookbook &&
                cookbookQuery.value == expectedQuery && cooking.states.value === expectedCooking &&
                cookingNavigation.value === navigation && action == null && !acceptingRootRecipe && !handingOffSavedRoot &&
                mutableSavedCookingConfirmation.value == null && mutablePostSaveReview.value == null &&
                cookbookRouteCurrent(exceptMemory = true) && owner.isCurrent(memory) &&
                memory.screen == MealMemoryScreen.MEMORY && memory.review == null && memory.pending == null &&
                memory.phase !in setOf(MealMemoryPhase.LOADING, MealMemoryPhase.WORKING, MealMemoryPhase.UNAVAILABLE)
        }
        if (!presentationCurrent() || !continuationCurrent()) return@withContext cookingActionFailure()
        val visitCurrent = claim(::continuationCurrent) ?: return@withContext cookingActionFailure()
        // Claim intentionally disposes the old Memory UI. Only the independent admitted
        // child and account/parent lifetime govern this explicit read from here onwards.
        if (!visitCurrent() || !continuationCurrent()) return@withContext cookingActionFailure()
        val result = kitchen.loadPreferences()
        if (!visitCurrent() || !continuationCurrent()) cookingActionFailure() else result
    }

    /** Child-owned preference read/recovery. No main-form adoption or meal submission. */
    internal suspend fun loadMemoryPersonalization(expected: KitchenInputState,
        visitCurrent: () -> Boolean): PortResult<KitchenInputState> = withContext(dispatcher) {
        if (!accountKitchenPreferencesAvailable || !visitCurrent() || kitchen.states.value !== expected)
            return@withContext cookingActionFailure()
        val restored = kitchen.restore()
        if (restored is PortResult.Failure || !visitCurrent()) return@withContext restored
        val result = kitchen.loadPreferences()
        if (!visitCurrent()) cookingActionFailure() else result
    }

    internal suspend fun pauseMemoryPersonalization(expected: KitchenInputState,
        visitCurrent: () -> Boolean): PortResult<KitchenInputState> = withContext(dispatcher) {
        if (!accountKitchenPreferencesAvailable || !visitCurrent() || kitchen.states.value !== expected)
            return@withContext cookingActionFailure()
        val queued = kitchen.savePersonalizationPause(expected, visitCurrent)
        if (queued is PortResult.Failure) return@withContext queued
        val retained = (queued as PortResult.Value).value
        if (!visitCurrent() || kitchen.states.value !== retained) return@withContext cookingActionFailure()
        val original = retained.pending.singleOrNull { it.operationId == "updatePreferences" }
            ?: return@withContext cookingActionFailure()
        if (!isPersonalizationPauseOriginal(original) || !samePersonalizationDocument(original.base, expected.preferences) ||
            !samePersonalizationDocument(retained.preferenceDraft, expected.preferenceDraft) ||
            expected.pending.any { it.commandId == original.commandId }) return@withContext cookingActionFailure()
        if (!original.canSynchronize) return@withContext queued
        synchronizeMemoryPersonalization(retained, original, visitCurrent)
    }

    internal suspend fun synchronizeMemoryPersonalization(expected: KitchenInputState,
        original: KitchenInputPending, visitCurrent: () -> Boolean): PortResult<KitchenInputState> = withContext(dispatcher) {
        if (!accountKitchenPreferencesAvailable || !visitCurrent() || kitchen.states.value !== expected ||
            expected.pending.none { it === original } || !isPersonalizationPauseOriginal(original) || !original.canSynchronize)
            return@withContext cookingActionFailure()
        val result = kitchen.synchronize(original.commandId)
        if (!visitCurrent()) return@withContext cookingActionFailure()
        if (result is PortResult.Value && kitchen.states.value === result.value &&
            result.value.pending.none { it.commandId == original.commandId } &&
            result.value.failureReason == null && result.value.personalizationEnabled == false) {
            // A replay receipt can be historical. Re-read explicitly after its durable ACK
            // before describing the setting as the latest checked server observation.
            val refreshed = kitchen.loadPreferences()
            if (!visitCurrent()) cookingActionFailure() else refreshed
        } else result
    }

    /** Retained Saved is revealed unchanged; hidden Saved uses its existing local entry and
     * recovery path. Unlike perform(), this navigation never leaves an adaptation editor. */
    internal suspend fun openMemorySaved(expected: MealMemoryState, expectedCookbook: CookbookState,
        expectedQuery: String, expectedMeal: MealRequestState, expectedForm: MealFormState,
        expectedCooking: CookingFlowState, navigation: CookingNavigation,
        presentationCurrent: () -> Boolean): PortResult<CookbookState> = withContext(dispatcher) {
        val owner = mealMemory ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (!presentationCurrent() || !memorySavedAvailable(expected, expectedCookbook, expectedQuery,
                expectedMeal, expectedForm, expectedCooking, navigation)) return@withContext cookingActionFailure()
        val capture = form.capture() ?: return@withContext cookingActionFailure()
        val token = Any(); action = token; form.busy(true)
        var departed: MealMemoryState? = null
        fun retainedParentCurrent() = !closed && form.current() && action === token && form.matches(capture.first) &&
            forms.value.values == expectedForm.values && forms.value.dirty == expectedForm.dirty &&
            forms.value.searchText == expectedForm.searchText && meals.states.value === expectedMeal &&
            cookbookQuery.value == expectedQuery && cooking.states.value === expectedCooking &&
            cookingNavigation.value === navigation && mutableSavedCookingConfirmation.value == null &&
            mutablePostSaveReview.value == null && !acceptingRootRecipe && !handingOffSavedRoot
        fun destinationCurrent() = retainedParentCurrent() && departed?.let {
            owner.states.value === it && it.screen == MealMemoryScreen.HIDDEN && owner.isCurrent(it)
        } == true && cookbookRouteCurrent()
        try {
            // Busy publication must not hide a newer parent change or retired UI visit.
            if (!presentationCurrent() || !retainedParentCurrent() || owner.states.value !== expected ||
                cookbook.states.value !== expectedCookbook || !cookbookRouteCurrent(exceptMemory = true))
                return@withContext cookingActionFailure()
            when (val left = owner.leaveForSaved(expected)) {
                is PortResult.Failure -> return@withContext left
                is PortResult.Value -> departed = left.value
            }
            // Intentional departure retires the old UI predicate. Never revive it or
            // reconstruct Memory after a late result; retain the exact hidden successor.
            if (!destinationCurrent() || cookbook.states.value !== expectedCookbook)
                return@withContext cookingActionFailure()
            val result = if (expectedCookbook.screen != CookbookScreen.HIDDEN) PortResult.Value(expectedCookbook)
                else openCookbookOwned(expectedQuery)
            if (!destinationCurrent() || result is PortResult.Value && cookbook.states.value !== result.value)
                return@withContext cookingActionFailure()
            if (result is PortResult.Failure) form.fail(result.reason)
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (destinationCurrent()) form.fail(FailureReason.UNAVAILABLE)
            PortResult.Failure(FailureReason.UNAVAILABLE)
        } finally { if (action === token) { action = null; form.busy(false) } }
    }
    /** Original Collections tab, using the same account owner as Saved. Entering performs
     * only the explicitly requested collection read and leaves the Saved journal untouched. */
    internal suspend fun openCollections(expected: CookbookState, expectedQuery: String,
        expectedMeal: MealRequestState, expectedForm: MealFormState): PortResult<CollectionReadState> =
        perform(admission = {
            collections?.states?.value?.screen == CollectionReadScreen.HIDDEN &&
                cookbookActionCurrent(expected, expectedQuery) && expected.screen == CookbookScreen.LIST &&
                !expected.busy && expected.pending == null && expected.deleteConfirmation == null &&
                meals.states.value === expectedMeal && forms.value === expectedForm && !expectedForm.busy
        }) {
            collectionSavedReturn = null
            checkNotNull(collections).open()
        }

    /** Original COOKBOOK.05 entry. Memory owns the read and its departure fence; Saved's
     * current selection, search text and draft remain untouched beneath the overlay. No
     * meal-form busy ownership is retained across the read, so Back can retire a late reply. */
    internal suspend fun openCookbookMemory(expected: CookbookState, expectedQuery: String,
        expectedMeal: MealRequestState, expectedForm: MealFormState): PortResult<MealMemoryState> {
        val owner = mealMemory ?: return PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val result = withContext(dispatcher) {
            if (action != null || acceptingRootRecipe || owner.states.value.screen != MealMemoryScreen.HIDDEN ||
                !cookbookActionCurrent(expected, expectedQuery) || expected.screen != CookbookScreen.LIST ||
                expected.busy || expected.pending != null || expected.deleteConfirmation != null ||
                expected.failureReason != null || expected.phase !in setOf(CookbookPhase.IDLE, CookbookPhase.READY) ||
                meals.states.value !== expectedMeal || forms.value !== expectedForm || expectedForm.busy || expectedForm.values == null)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            owner.openHistory()
        }
        // A Back or a newer memory entry can run while the outer dispatcher return waits.
        return if (result is PortResult.Value && owner.states.value !== result.value)
            PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    internal suspend fun openCollectionSaved(expected: CollectionReadState, selection: CollectionSavedRecipeSelection,
        expectedCookbook: CookbookState, expectedQuery: String, expectedMeal: MealRequestState,
        expectedForm: MealFormState): PortResult<CookbookState> = withContext(dispatcher) {
        val owner = collections ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        fun originCurrent() = !closed && form.current() && owner.states.value === expected &&
            owner.isCurrent(selection) && owner.canLeaveForSaved(expected) &&
            cookbookQuery.value == expectedQuery && meals.states.value === expectedMeal &&
            cookbookRouteCurrent(exceptCollections = true) && mutableSavedCookingConfirmation.value == null
        if (action != null || acceptingRootRecipe || handingOffSavedRoot || !originCurrent() ||
            cookbook.states.value !== expectedCookbook || expectedCookbook.screen != CookbookScreen.LIST ||
            expectedCookbook.busy || expectedCookbook.pending != null || expectedCookbook.deleteConfirmation != null ||
            forms.value !== expectedForm || expectedForm.busy) return@withContext cookingActionFailure()
        val capture = form.capture() ?: return@withContext cookingActionFailure()
        val token = Any(); action = token; form.busy(true)
        val opening = expected to checkNotNull(currentCoroutineContext()[Job])
        collectionSavedOpening = opening
        try {
            val opened = cookbook.open(selection.savedRecipeId)
            if (opened is PortResult.Failure) return@withContext opened
            val current = (opened as PortResult.Value).value
            if (!originCurrent() || !form.matches(capture.first) || cookbook.states.value !== current ||
                current.screen != CookbookScreen.DETAIL || current.selected?.id?.equals(selection.savedRecipeId, ignoreCase = true) != true ||
                current.pending != null || current.deleteConfirmation != null) return@withContext cookingActionFailure()
            when (val hidden = owner.hideForSaved(expected, selection)) {
                is PortResult.Failure -> hidden
                is PortResult.Value -> {
                    collectionSavedReturn = hidden.value to selection.savedRecipeId
                    opened
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PortResult.Failure(FailureReason.UNAVAILABLE) }
        finally {
            if (collectionSavedOpening === opening) collectionSavedOpening = null
            if (action === token) { action = null; form.busy(false) }
        }
    }
    internal suspend fun backFromCollections(expected: CollectionReadState): PortResult<Unit> =
        withContext(NonCancellable + dispatcher) {
            val owner = collections ?: return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (closed || owner.states.value !== expected || owner.commands.states.value.phase == CollectionCommandPhase.WORKING)
                return@withContext PortResult.Failure(FailureReason.CONFLICT)
            val opening = collectionSavedOpening
            if (opening != null) {
                opening.second.cancel()
                collectionSavedOpening = null
                val returned = cookbook.backToList()
                if (returned is PortResult.Failure) return@withContext returned
            } else if (expected.screen == CollectionReadScreen.DETAIL) {
                return@withContext when (val returned = owner.back(expected)) {
                    is PortResult.Failure -> returned
                    is PortResult.Value -> PortResult.Value(Unit)
                }
            }
            when (val left = owner.commands.leave(owner.commands.states.value)) {
                is PortResult.Failure -> left
                is PortResult.Value -> owner.leave(expected)
            }
        }
    suspend fun searchCookbook(expected: CookbookState = cookbook.states.value, expectedQuery: String = cookbookQuery.value) =
        perform(admission = { cookbookActionCurrent(expected, expectedQuery) && expected.screen == CookbookScreen.LIST }) { collectionSavedReturn = null; cookbook.load(expectedQuery) }
    suspend fun moreCookbook(expected: CookbookState = cookbook.states.value, expectedQuery: String = cookbookQuery.value) =
        perform(admission = { cookbookActionCurrent(expected, expectedQuery) && expected.screen == CookbookScreen.LIST }) { cookbook.more() }
    suspend fun searchDownloadedCookbook(expected: CookbookState = cookbook.states.value, expectedQuery: String = cookbookQuery.value) =
        perform(admission = { cookbookActionCurrent(expected, expectedQuery) && expected.screen == CookbookScreen.LIST }) { collectionSavedReturn = null; cookbook.searchDownloaded(expectedQuery) }
    /** Explicit non-UI lookup by ID. Rendered rows use the displayed-target overload below. */
    suspend fun openSavedRecipe(id: String): PortResult<CookbookState> {
        val expected = cookbook.states.value
        val expectedQuery = cookbookQuery.value
        return perform(admission = { cookbookQuery.value == expectedQuery &&
            (cookbookActionCurrent(expected) || cookbookEntryCurrent(expected)) }) { collectionSavedReturn = null; cookbook.open(id) }
    }
    suspend fun openSavedRecipe(id: String, expected: CookbookState, expectedQuery: String = cookbookQuery.value) =
        perform(admission = { cookbookQuery.value == expectedQuery &&
            cookbookActionCurrent(expected) && cookbookTargetCurrent(expected, id) }) { collectionSavedReturn = null; cookbook.open(id) }
    suspend fun refreshSavedRecipe(expected: CookbookState = cookbook.states.value, expectedQuery: String = cookbookQuery.value) =
        perform(admission = { cookbookActionCurrent(expected, expectedQuery) && expected.screen == CookbookScreen.DETAIL }) { cookbook.refreshDetail() }
    suspend fun downloadSavedRecipe(expected: CookbookState = cookbook.states.value, expectedQuery: String = cookbookQuery.value) =
        perform(admission = { cookbookActionCurrent(expected, expectedQuery) && expected.screen == CookbookScreen.DETAIL }) { cookbook.download() }
    suspend fun retryCookbookOriginal(expected: CookbookState = cookbook.states.value, expectedQuery: String = cookbookQuery.value) =
        perform(admission = { cookbookActionCurrent(expected, expectedQuery) }) { cookbook.retryOriginal() }
    suspend fun discardCookbookUnsent(expected: CookbookState = cookbook.states.value, expectedQuery: String = cookbookQuery.value) =
        perform(admission = { cookbookActionCurrent(expected, expectedQuery) }) { cookbook.discardUnsent() }
    suspend fun prepareSavedRecipeDelete(id: String, expected: CookbookState = cookbook.states.value, expectedQuery: String = cookbookQuery.value) =
        perform(admission = { cookbookActionCurrent(expected, expectedQuery) && cookbookTargetCurrent(expected, id) }) { cookbook.prepareDelete(id) }
    suspend fun confirmSavedRecipeDelete(ticket: PreparedCookbookDelete, expected: CookbookState = cookbook.states.value,
        expectedQuery: String = cookbookQuery.value) = perform(admission = {
        cookbookActionCurrent(expected, expectedQuery, confirmation = ticket) && expected.screen == CookbookScreen.DETAIL
    }) { cookbook.confirmDelete(ticket) }
    /** Navigation bypasses the busy task so the controller can fence a suspended read or command. */
    suspend fun dismissSavedRecipeDelete(expected: CookbookState = cookbook.states.value,
        ticket: PreparedCookbookDelete? = expected.deleteConfirmation) = withContext(dispatcher) {
        if (ticket == null || !cookbookActionCurrent(expected, confirmation = ticket) || expected.screen != CookbookScreen.DETAIL)
            return@withContext cookingActionFailure()
        cookbook.dismissDelete()
    }
    suspend fun backFromCookbook(expected: CookbookState = cookbook.states.value) = withContext(dispatcher) {
        if (!cookbookActionCurrent(expected, confirmation = expected.deleteConfirmation)) return@withContext cookingActionFailure()
        val retained = collectionSavedReturn
        val returnToCollection = retained != null && expected.screen == CookbookScreen.DETAIL &&
            expected.selected?.id?.equals(retained.second, ignoreCase = true) == true && expected.pending == null && expected.deleteConfirmation == null &&
            !expected.busy && action == null
        val circleOrigin = savedReturnsToCircles
        val returnToCircles = circleOrigin != null && circleOrigin === circlesTabReturn &&
            circles?.canResumeNavigation(circleOrigin) == true && expected.screen == CookbookScreen.LIST &&
            expected.pending == null && expected.deleteConfirmation == null && !expected.busy && action == null &&
            mutablePostSaveReview.value == null && mutableSavedCookingConfirmation.value == null
        val left = cookbook.back()
        val departed = when (left) {
            is PortResult.Failure -> return@withContext left
            is PortResult.Value -> left.value
        }
        if (returnToCollection) {
            collectionSavedReturn = null
            when (val restored = collections?.restoreFromSaved(checkNotNull(retained).first)) {
                is PortResult.Failure -> restored
                else -> left
            }
        } else {
            if (expected.screen != CookbookScreen.DETAIL) {
                collectionSavedReturn = null
                savedReturnsToCircles = null
            }
            if (returnToCircles && cookbook.states.value === departed && departed.screen == CookbookScreen.HIDDEN &&
                departed.pending == null && circlesTabReturn === circleOrigin) {
                when (val restored = openCircles()) {
                    is PortResult.Failure -> return@withContext restored
                    is PortResult.Value -> Unit
                }
            }
            left
        }
    }
    private fun cookbookActionCurrent(expected: CookbookState, expectedQuery: String? = null,
        confirmation: PreparedCookbookDelete? = null, savedSelection: SavedCookingConfirmation? = null) =
        !handingOffSavedRoot && mutableSavedCookingConfirmation.value === savedSelection && !closed && form.current() && cookbook.states.value === expected &&
        expected.screen != CookbookScreen.HIDDEN && expected.deleteConfirmation === confirmation &&
        (expectedQuery == null || cookbookQuery.value == expectedQuery) && cookbookRouteCurrent()
    private fun cookbookTargetCurrent(expected: CookbookState, id: String) = when (expected.screen) {
        CookbookScreen.LIST -> expected.items.any { it.id.equals(id, ignoreCase = true) }
        CookbookScreen.DETAIL -> expected.selected?.id?.equals(id, ignoreCase = true) == true
        CookbookScreen.HIDDEN -> false
    }
    private fun cookbookEntryCurrent(expected: CookbookState) = !closed && form.current() && cookbook.states.value === expected &&
        expected.screen == CookbookScreen.HIDDEN && expected.deleteConfirmation == null && !cookingNavigation.value.visible &&
        cookbookRouteCurrent()
    private fun cookbookRouteCurrent(exceptCollections: Boolean = false, exceptMemory: Boolean = false) = socialHidden() && kitchenPage.value == null && timers?.states?.value?.visible != true &&
        timerConfirmation.value == null && cookingNavigation.value.confirmation == null && !reviewedPostsNavigation.value.visible &&
        postDrafts?.states?.value?.screen?.let { it != PostDraftScreen.HIDDEN } != true &&
        circles?.states?.value?.screen?.let { it != CirclesScreen.HIDDEN } != true &&
        invitationRoutesHidden(exceptMemory = exceptMemory, exceptCollections = exceptCollections)

    /** Pure review preparation. Existing unsaved edits, pending originals and live cooking
     * must be handled in their own flows first; selecting a copy never overwrites them. */
    suspend fun prepareSavedCooking(expected: CookbookState, expectedQuery: String,
        expectedMeal: MealRequestState, expectedForm: MealFormState,
        navigation: CookingNavigation): PortResult<SavedCookingConfirmation> = withContext(dispatcher) {
        if (!savedCookingEntryCurrent(expected, expectedQuery, expectedMeal, expectedForm, navigation))
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        val capture = form.capture() ?: return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        when (val selected = cookbook.captureCookingSource(expected)) {
            is PortResult.Failure -> selected
            is PortResult.Value -> PortResult.Value(SavedCookingConfirmation(selected.value, expected, expectedQuery,
                expectedMeal, expectedForm, capture.first, navigation, cooking.states.value).also {
                mutableSavedCookingConfirmation.value = it
            })
        }
    }

    suspend fun dismissSavedCooking(intent: SavedCookingConfirmation): PortResult<Unit> = withContext(dispatcher) {
        if (mutableSavedCookingConfirmation.value !== intent || closed || !form.current() || action != null)
            return@withContext PortResult.Failure(FailureReason.CONFLICT)
        mutableSavedCookingConfirmation.value = null
        PortResult.Value(Unit)
    }

    suspend fun confirmSavedCooking(intent: SavedCookingConfirmation): PortResult<MealRequestState> = perform(savedSelection = intent, admission = {
        savedCookingConfirmationCurrent(intent)
    }) {
        val values = intent.form.values ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
        val result = meals.selectSavedCookingSource(intent.source, intent.meal,
            values.copy(savedRecipeId = intent.source.savedRecipeId, sourceRecipeVersionId = null, savedMakeMine = false,
                sourcePostId = null, sourcePostVersion = null).draft())
        if (result is PortResult.Failure) return@perform result
        if (mutableSavedCookingConfirmation.value !== intent || !intent.source.isCurrent ||
            !form.matches(intent.formTicket) || forms.value.dirty || cooking.states.value !== intent.cooking ||
            cookingNavigation.value !== intent.navigation || !cookbookActionCurrent(intent.cookbook, intent.query, savedSelection = intent))
            return@perform PortResult.Failure(FailureReason.CONFLICT)
        // Hide the exact selected Saved route only after local source persistence acknowledges.
        // The request page still needs an explicit Find meal action; no HTTP occurs here.
        // Retain this modal's input fence through the final suspended local route operation.
        when (val hidden = cookbook.hideForCookingSource(intent.cookbook)) {
            is PortResult.Failure -> hidden
            is PortResult.Value -> {
                if (mutableSavedCookingConfirmation.value !== intent || !form.matches(intent.formTicket) ||
                    forms.value.dirty || cooking.states.value !== intent.cooking || cookingNavigation.value !== intent.navigation)
                    return@perform PortResult.Failure(FailureReason.CONFLICT)
                planSourceReview = null
                form.adopt((result as PortResult.Value).value.draft)
                mutableSavedCookingConfirmation.value = null
                result
            }
        }
    }

    suspend fun clearSavedCookingSource(expectedMeal: MealRequestState,
        expectedForm: MealFormState): PortResult<MealRequestState> = perform(admission = {
        !closed && form.current() && meals.states.value === expectedMeal && forms.value === expectedForm &&
            expectedMeal.screen == MealFlowScreen.REQUEST && !cookingNavigation.value.visible &&
            cookingNavigation.value.confirmation == null && cookingChildRoutesHidden() && !expectedForm.dirty &&
            expectedForm.values?.savedRecipeId != null && savedCookingIdle() &&
            expectedMeal.phase !in setOf(MealFlowPhase.LOADING, MealFlowPhase.RESOLVING, MealFlowPhase.UNAVAILABLE)
    }) {
        val capture = form.capture() ?: return@perform PortResult.Failure(FailureReason.STALE_SESSION)
        val result = meals.clearSavedCookingSource(expectedMeal, capture.second.copy(savedRecipeId = null, savedMakeMine = false).draft())
        if (result is PortResult.Value && form.matches(capture.first) && !forms.value.dirty) {
            planSourceReview = null
            form.adopt(result.value.draft); mutableSavedCookingConfirmation.value = null
            result
        } else if (result is PortResult.Failure) result else PortResult.Failure(FailureReason.CONFLICT)
    }

    private fun savedCookingIdle() = cooking.states.value.let { state ->
        state.phase in setOf(CookingFlowPhase.IDLE, CookingFlowPhase.COMPLETED, CookingFlowPhase.ABANDONED) &&
            state.pending.isEmpty()
    }
    private fun savedCookingEntryCurrent(expected: CookbookState, query: String, meal: MealRequestState,
        rendered: MealFormState, navigation: CookingNavigation, confirmation: SavedCookingConfirmation? = null,
        allowSavedRootResume: Boolean = false) = session.scope.actorKind == ActorKind.ACCOUNT &&
        !closed && form.current() && action == null && cookbookActionCurrent(expected, query, savedSelection = confirmation) &&
        expected.screen == CookbookScreen.DETAIL && expected.pending == null && !expected.busy &&
        meals.states.value === meal && forms.value === rendered && !rendered.dirty && !rendered.busy &&
        rendered.values != null && (meal.phase !in setOf(MealFlowPhase.LOADING, MealFlowPhase.RESOLVING, MealFlowPhase.UNAVAILABLE) ||
            allowSavedRootResume && savedRootResumeMatches(expected, meal)) &&
        cookingNavigation.value === navigation && !navigation.visible && navigation.confirmation == null && savedCookingIdle()
    /** Reopening the same retained original is not permission to select a fresh source or
     * replace an unresolved ordinary cook-again command. The controller verifies it again. */
    private fun savedRootResumeMatches(expected: CookbookState, meal: MealRequestState): Boolean {
        if (meal.phase != MealFlowPhase.RESOLVING) return false
        val source = meal.savedSource ?: return false
        val pending = meal.pendingRootDraft ?: return false
        val selected = expected.selected ?: return false
        val copy = selected.savedRecipe ?: return false
        return pending.savedMakeMine && pending.savedRecipeId == source.savedRecipeId &&
            selected.id == source.savedRecipeId && (selected.etag == null || selected.etag == source.etag) &&
            copy.document.encodeUtf8().contentEquals(source.savedRecipe.document.encodeUtf8())
    }
    private fun savedCookingConfirmationCurrent(intent: SavedCookingConfirmation) =
        mutableSavedCookingConfirmation.value === intent && intent.source.isCurrent &&
            form.matches(intent.formTicket) && cooking.states.value === intent.cooking &&
            savedCookingEntryCurrent(intent.cookbook, intent.query, intent.meal, intent.form, intent.navigation, intent)
    suspend fun saveSelectedRecipe(expectedMeal: MealRequestState = meals.states.value,
        expectedForm: MealFormState = forms.value) = perform(admission = {
        mainMealActionCurrent(expectedMeal, expectedForm)
    }) { saveSelectedRecipeOwned(null) }
    /** A cooked A is never substituted with the meal request's later B by a same-label Save tap. */
    suspend fun saveCookingRecipe(expected: CookingFlowState = cooking.states.value,
        navigation: CookingNavigation = cookingNavigation.value, expectedMeal: MealRequestState = meals.states.value,
        expectedForm: MealFormState = forms.value) = perform(admission = {
        cookingActionCurrent(expected, navigation) && meals.states.value === expectedMeal && forms.value === expectedForm
    }) {
        val pin = expected.plan?.id?.value ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
        saveSelectedRecipeOwned(pin)
    }
    /** The original completion button is an explicit compound intent, never plain Save.
     * Admission is checked again by the cookbook against this exact retained Plan. */
    internal fun cookingMakeAgainAvailable(expected: CookingFlowState, navigation: CookingNavigation,
        expectedMeal: MealRequestState, expectedForm: MealFormState): Boolean =
        cookbook.makeAgainEnabled && supportsSavedCooking && cookingActionCurrent(expected, navigation) &&
            meals.states.value === expectedMeal && forms.value === expectedForm && !expectedForm.dirty &&
            cookbook.states.value.pending == null && expected.plan?.id?.value != null &&
            expected.plan?.id?.value == expectedMeal.plan?.plan?.id?.value &&
            expected.plan?.sourcePostId !is WireField.Value &&
            CookingScreenState.from(expected).let {
                it.done && it.instructionsVisible && it.recipe?.reviewStatus == "published" &&
                    it.recipe?.reviewedAt is WireField.Value && it.serverAcknowledged &&
                    it.localPendingCount == 0 && it.pending.isEmpty()
            }

    internal suspend fun makeCookingAgain(expected: CookingFlowState, navigation: CookingNavigation,
        expectedMeal: MealRequestState, expectedForm: MealFormState): PortResult<CookbookState> =
        perform(admission = { cookingMakeAgainAvailable(expected, navigation, expectedMeal, expectedForm) }) {
            val pin = expected.plan ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
            if (!meals.draftReadiness.isReady) {
                val saved = persistForm()
                if (saved is PortResult.Failure) return@perform saved
            }
            if (forms.value.dirty || cooking.states.value !== expected || cookingNavigation.value !== navigation ||
                meals.states.value.plan?.plan?.id?.value != pin.id.value)
                return@perform PortResult.Failure(FailureReason.CONFLICT)
            // One original command owns both effects. The memory screen always discards
            // observations on leave and reads fresh on entry; no speculative preference
            // is written into its state while this command is pending or unknown.
            cookbook.saveSelectedPlanMakeAgain(pin)
        }
    suspend fun openCookingCookbook(expected: CookingFlowState, navigation: CookingNavigation,
        expectedCookbook: CookbookState = cookbook.states.value, expectedQuery: String = cookbookQuery.value) =
        perform(admission = { cookingActionCurrent(expected, navigation) && cookbook.states.value === expectedCookbook &&
            cookbookQuery.value == expectedQuery }) { savedReturnsToCircles = null; cookbook.searchDownloaded(expectedQuery) }

    internal fun canOpenCookingSocial(destination: BlueprintScreenId, expected: CookingFlowState, navigation: CookingNavigation,
        expectedMeal: MealRequestState, expectedForm: MealFormState,
        expectedCookbook: CookbookState, expectedQuery: String): Boolean =
        destination in setOf(BlueprintScreenId.TODAY, BlueprintScreenId.INBOX, BlueprintScreenId.PROFILE_PLATE) &&
            session.scope.actorKind == ActorKind.ACCOUNT && socialReads != null && action == null &&
            !acceptingRootRecipe && !handingOffSavedRoot && cookingActionCurrent(expected, navigation) &&
            meals.states.value === expectedMeal && forms.value === expectedForm && !expectedForm.busy &&
            expectedForm.values != null && cookbook.states.value === expectedCookbook &&
            !expectedCookbook.busy && expectedCookbook.deleteConfirmation == null && cookbookQuery.value == expectedQuery &&
            mutableSavedCookingConfirmation.value == null && mutablePostSaveReview.value == null &&
            CookingScreenState.from(expected).let { it.done && it.instructionsVisible &&
                it.screen == com.feedme.mealflow.CookingFlowScreen.COOK && !it.sessionId.isNullOrBlank() }

    /** Original completion social tabs read over the same account. No cook cursor,
     * completion original, Plan, source, dirty text or timers are changed or sent. */
    internal suspend fun openCookingSocial(destination: BlueprintScreenId, expected: CookingFlowState, navigation: CookingNavigation,
        expectedMeal: MealRequestState, expectedForm: MealFormState,
        expectedCookbook: CookbookState, expectedQuery: String,
        presentationCurrent: () -> Boolean): PortResult<SocialReadState> = withContext(dispatcher) {
        if (!presentationCurrent() || !canOpenCookingSocial(destination, expected, navigation, expectedMeal, expectedForm,
                expectedCookbook, expectedQuery)) return@withContext cookingActionFailure()
        val captured = form.capture() ?: return@withContext cookingActionFailure()
        val retained = CookingSocialReturn(expected, navigation, expectedMeal, expectedForm,
            captured.first, expectedCookbook, expectedQuery)
        if (!presentationCurrent() || !cookingSocialReturnCurrent(retained) ||
            !canOpenCookingSocial(destination, expected, navigation, expectedMeal, expectedForm, expectedCookbook, expectedQuery))
            return@withContext cookingActionFailure()
        cookingSocialReturn = retained; socialReturn = SocialReturn.COOKING
        val reader = checkNotNull(socialReads)
        // Intentional entry retires the completion UI predicate. Do not keep form.busy
        // across the GET: Social Back must remain able to cancel/retire a late response.
        val result = when (destination) {
            BlueprintScreenId.TODAY -> reader.openToday()
            BlueprintScreenId.INBOX -> reader.openInbox()
            BlueprintScreenId.PROFILE_PLATE -> reader.openProfilePlate()
            else -> return@withContext cookingActionFailure()
        }
        if (!form.current() || socialReturn != SocialReturn.COOKING || cookingSocialReturn !== retained ||
            result is PortResult.Value && reader.states.value !== result.value) cookingActionFailure() else result
    }

    private fun cookingSocialReturnCurrent(retained: CookingSocialReturn): Boolean =
        !closed && form.current() && form.matches(retained.formGeneration) && forms.value === retained.form &&
            !retained.form.busy && action == null && !acceptingRootRecipe && !handingOffSavedRoot &&
            cooking.states.value === retained.cooking && cookingNavigation.value === retained.navigation &&
            retained.navigation.visible && retained.navigation.confirmation == null &&
            meals.states.value === retained.meal && cookbook.states.value === retained.cookbook &&
            cookbookQuery.value == retained.query && reportsHidden() &&
            mutableSavedCookingConfirmation.value == null && mutablePostSaveReview.value == null
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
    suspend fun prepareCooking(expectedMeal: MealRequestState = meals.states.value,
        expectedForm: MealFormState = forms.value, navigation: CookingNavigation = cookingNavigation.value) = perform(admission = {
        mainMealActionCurrent(expectedMeal, expectedForm) && cookingNavigation.value === navigation
    }) {
        val selected = expectedMeal.plan?.plan
        val formTicket = form.capture()?.first
        val navigationTicket = cookingUi.ticket()
        if (forms.value.dirty || meals.states.value.screen != MealFlowScreen.RECIPE || selected == null || formTicket == null)
            return@perform PortResult.Failure(FailureReason.CONFLICT)
        val result = cooking.prepareStart(selected.id.value)
        if (result is PortResult.Value && cookingUi.matches(navigationTicket) && cookingNavigation.value === navigation &&
            meals.states.value === expectedMeal && cookingChildRoutesHidden() && form.savedCurrent(formTicket) &&
            meals.states.value.plan?.plan?.id == selected.id && result.value.phase == CookingFlowPhase.START_CONFIRMATION &&
            result.value.plan?.id == selected.id) {
            cookingUi.present(CookingConfirmation(CookingConfirmationKind.START, selected.id.value, null, null, formTicket, result.value))
        }
        result
    }
    fun showCooking(expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value): PortResult<CookingNavigation> {
        if (!cookingActionCurrent(expected, navigation, visible = false)) return cookingActionFailure()
        cookingUi.show(); return PortResult.Value(cookingNavigation.value)
    }
    fun leaveCooking(expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value): PortResult<CookingNavigation> {
        if (!cookingActionCurrent(expected, navigation)) return cookingActionFailure()
        cookingUi.leave(); return PortResult.Value(cookingNavigation.value)
    }
    /** MEAL_DONE.05 ends this visible journey, not the retained cook or its pending sync.
     * Only cached navigation is used; completion, Save, feedback and timers are untouched. */
    internal suspend fun doneCookingForNow(expected: CookingFlowState, navigation: CookingNavigation,
        expectedMeal: MealRequestState, expectedForm: MealFormState, landing: BlueprintMealLandingVisit,
        presentationCurrent: () -> Boolean): PortResult<CookingNavigation> = withContext(dispatcher) {
        fun current(meal: MealRequestState) = presentationCurrent() && action == null && !forms.value.busy &&
            cookingActionCurrent(expected, navigation) && meals.states.value === meal && forms.value === expectedForm &&
            blueprintLanding.current(landing, true) && mutableSavedCookingConfirmation.value == null &&
            mutablePostSaveReview.value == null
        if (!current(expectedMeal) || !CookingScreenState.from(expected).done) return@withContext cookingActionFailure()
        if (blueprintCookingHomeEligible(MealScreenState.from(expectedMeal), expectedForm)) {
            val returned = when (val result = meals.backToDraft()) {
                is PortResult.Failure -> return@withContext result
                is PortResult.Value -> result.value
            }
            if (!current(returned) || !blueprintMealLandingEligible(MealScreenState.from(returned), expectedForm))
                return@withContext cookingActionFailure()
            val moved = if (landing.home && !landing.offline) blueprintLanding.claim(landing, true) else blueprintLanding.showHome(landing, true)
            if (!moved) return@withContext cookingActionFailure()
        }
        // A source-specific or unresolved main journey remains visible instead of being
        // covered by Home. Its draft, original command and selected Plan are not discarded.
        cookingUi.leave()
        PortResult.Value(cookingNavigation.value)
    }
    fun dismissCookingConfirmation(navigation: CookingNavigation = cookingNavigation.value): PortResult<CookingNavigation> {
        if (closed || !form.current() || cookingNavigation.value !== navigation || navigation.confirmation == null)
            return cookingActionFailure()
        cookingUi.dismiss(); return PortResult.Value(cookingNavigation.value)
    }
    /** Reopens the same retained proposal, without preparing a new plan or dispatching. */
    fun reviewCookingStart(expected: CookingFlowState = cooking.states.value,
        navigation: CookingNavigation = cookingNavigation.value): PortResult<CookingNavigation> {
        if (action != null || !cookingActionCurrent(expected, navigation)) return cookingActionFailure()
        val ticket = form.capture()?.first ?: return cookingActionFailure()
        val plan = expected.plan ?: return cookingActionFailure()
        if (forms.value.dirty || expected.phase != CookingFlowPhase.START_CONFIRMATION ||
            plan.id != meals.states.value.plan?.plan?.id) return cookingActionFailure()
        cookingUi.present(CookingConfirmation(CookingConfirmationKind.START, plan.id.value, null, null, ticket, expected))
        return PortResult.Value(cookingNavigation.value)
    }
    fun requestCookingEnd(kind: CookingConfirmationKind, expected: CookingFlowState = cooking.states.value,
        navigation: CookingNavigation = cookingNavigation.value): PortResult<CookingNavigation> {
        if (action != null || !cookingActionCurrent(expected, navigation)) return cookingActionFailure()
        val pin = expected.cooking ?: return cookingActionFailure()
        val ticket = form.capture()?.first ?: return cookingActionFailure()
        if (kind == CookingConfirmationKind.START ||
            (kind == CookingConfirmationKind.ABANDON && !expected.canStop) ||
            (kind == CookingConfirmationKind.COMPLETE && !expected.canComplete)) return cookingActionFailure()
        cookingUi.present(CookingConfirmation(kind, pin.plan.id.value, pin.id, pin.progress.deviceSequence, ticket, expected))
        return PortResult.Value(cookingNavigation.value)
    }
    private fun cookingConfirmationCurrent(intent: CookingConfirmation): Boolean {
        val state = cooking.states.value
        if (!cookingUi.owns(intent) || !form.matches(intent.formTicket) ||
            state !== intent.cookingState || !cookingChildRoutesHidden()) return false
        if (intent.kind == CookingConfirmationKind.START) {
            if (!form.savedCurrent(intent.formTicket) || state.phase != CookingFlowPhase.START_CONFIRMATION ||
                state.plan?.id?.value != intent.planId || meals.states.value.plan?.plan?.id?.value != intent.planId)
                return false
        } else {
            val pin = state.cooking
            if (pin?.id != intent.sessionId || pin?.plan?.id?.value != intent.planId || pin?.progress?.deviceSequence != intent.sequence ||
                (intent.kind == CookingConfirmationKind.ABANDON && !state.canStop) ||
                (intent.kind == CookingConfirmationKind.COMPLETE && !state.canComplete))
                return false
        }
        return true
    }
    suspend fun confirmCooking(intent: CookingConfirmation) = perform(admission = { cookingConfirmationCurrent(intent) }) {
        // Consume this view's consent before the first suspended operation. No old dialog retry.
        cookingUi.dismiss()
        when (intent.kind) {
            CookingConfirmationKind.START -> cooking.confirmStart()
            CookingConfirmationKind.ABANDON -> cooking.abandon()
            CookingConfirmationKind.COMPLETE -> cooking.complete()
        }
    }
    suspend fun retryCookingStart(expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value) =
        perform(admission = { cookingActionCurrent(expected, navigation) }) { cooking.retryStart() }
    suspend fun discardCookingStart(expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value) =
        perform(admission = { cookingActionCurrent(expected, navigation) }) { cookingUi.dismiss(); cooking.discardUnsentStart() }
    suspend fun refreshCooking(expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value) =
        perform(admission = { cookingActionCurrent(expected, navigation) }) { cooking.refresh() }
    suspend fun synchronizeCooking(expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value) =
        perform(admission = { cookingActionCurrent(expected, navigation) }) { cooking.synchronize() }
    suspend fun moveCooking(stepId: String, expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value) =
        perform(admission = { cookingActionCurrent(expected, navigation) }) { cooking.moveTo(stepId) }
    /** One explicit Next gesture. Acknowledge this exact step locally before moving its cursor.
     * If acknowledgement fails or navigation changes, do not advance or replace the intent.
     * The last step is only marked; completion, timers, saving and sharing remain separate. */
    suspend fun advanceCooking(expected: CookingFlowState, navigation: CookingNavigation): PortResult<CookingFlowState> =
        perform(admission = { cookingActionCurrent(expected, navigation) }) {
            val pin = expected.cooking ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
            val steps = expected.plan?.recipeSnapshot?.valueOrNull()?.steps.orEmpty()
            val step = steps.singleOrNull { it.stepId.value == pin.progress.currentStepId }
                ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
            if (!expected.canEdit || steps.map { it.stepId.value }.distinct().size != steps.size)
                return@perform PortResult.Failure(FailureReason.CONFLICT)
            val next = steps.getOrNull(steps.indexOf(step) + 1)
            val marked = if (step.stepId.value in pin.progress.completedStepIds) PortResult.Value(expected)
                else cooking.markStepComplete(step.stepId.value)
            if (marked !is PortResult.Value) return@perform marked
            val acknowledged = marked.value
            val currentPin = acknowledged.cooking
            if (!cookingActionCurrent(acknowledged, navigation) || currentPin?.id != pin.id ||
                currentPin.plan.id != pin.plan.id || currentPin.progress.currentStepId != step.stepId.value ||
                step.stepId.value !in currentPin.progress.completedStepIds)
                return@perform PortResult.Failure(FailureReason.CONFLICT)
            if (next == null) marked else cooking.moveTo(next.stepId.value)
        }
    suspend fun completeCookingStep(stepId: String, expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value) =
        perform(admission = { cookingActionCurrent(expected, navigation) }) { cooking.markStepComplete(stepId) }
    suspend fun pauseCooking(expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value) =
        perform(admission = { cookingActionCurrent(expected, navigation) }) { cooking.pause() }

    /** Original COOK.06: acknowledge the existing local pause before leaving. This neither
     * synchronizes the cook nor clears its pin, timers, selected main plan or request draft. */
    internal suspend fun pauseAndLeaveCooking(expected: CookingFlowState, navigation: CookingNavigation,
        expectedMeal: MealRequestState, expectedForm: MealFormState, landing: BlueprintMealLandingVisit): PortResult<CookingFlowState> =
        perform(adaptation = true, admission = {
            // Preserve any underlying source/adaptation form; this operation only pauses the pin.
            expected.screen == CookingFlowScreen.COOK && expected.canStop &&
                expected.cooking?.progress?.status == com.feedme.kitchen.CookingStatus.ACTIVE &&
                cookingActionCurrent(expected, navigation) && meals.states.value === expectedMeal &&
                forms.value === expectedForm && blueprintLanding.current(landing, true)
        }) {
            val before = expected.cooking ?: return@perform cookingActionFailure()
            val formTicket = form.capture()?.first ?: return@perform cookingActionFailure()
            val admittedForm = forms.value
            fun current(state: CookingFlowState, meal: MealRequestState) =
                cookingActionCurrent(state, navigation) && meals.states.value === meal &&
                    forms.value === admittedForm && form.matches(formTicket) && blueprintLanding.current(landing, true)
            val result = when (val pausedResult = cooking.pause()) {
                is PortResult.Failure -> return@perform pausedResult
                is PortResult.Value -> pausedResult
            }
            val acknowledged = result.value
            val paused = acknowledged.cooking ?: return@perform cookingActionFailure()
            if (!current(acknowledged, expectedMeal) || acknowledged.failureReason != null ||
                paused.id != before.id || paused.plan.id != before.plan.id ||
                paused.progress.status != com.feedme.kitchen.CookingStatus.PAUSED ||
                paused.progress.currentStepId != before.progress.currentStepId ||
                paused.progress.completedStepIds != before.progress.completedStepIds ||
                paused.progress.timers.map { it.document.encodeUtf8().toList() } !=
                    before.progress.timers.map { it.document.encodeUtf8().toList() } ||
                paused.progress.personalNotes?.map { it.encodeUtf8().toList() } !=
                    before.progress.personalNotes?.map { it.encodeUtf8().toList() }) return@perform cookingActionFailure()
            if (blueprintCookingHomeEligible(MealScreenState.from(expectedMeal), expectedForm)) {
                // Existing cached navigation publishes REQUEST without rewriting any journal.
                val returned = when (val draftResult = meals.backToDraft()) {
                    is PortResult.Failure -> return@perform draftResult
                    is PortResult.Value -> draftResult
                }
                if (!current(acknowledged, returned.value) ||
                    !blueprintMealLandingEligible(MealScreenState.from(returned.value), expectedForm))
                    return@perform cookingActionFailure()
                val moved = if (landing.home && !landing.offline) blueprintLanding.claim(landing, true) else blueprintLanding.showHome(landing, true)
                if (!moved) return@perform cookingActionFailure()
            }
            // Unresolved/source-specific main state remains the visible destination, unchanged.
            cookingUi.leave()
            result
        }
    suspend fun resumeCooking(expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value) =
        perform(admission = { cookingActionCurrent(expected, navigation) }) { cooking.resume() }
    suspend fun readRetainedCooking(expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value) =
        perform(admission = { cookingActionCurrent(expected, navigation) }) { cooking.restore() }
    suspend fun reopenCooking(expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value) = withContext(dispatcher) {
        if (!cookingActionCurrent(expected, navigation, visible = false)) return@withContext cookingActionFailure()
        cooking.returnToCooking()
    }
    /** Immediate cached Back also fences a suspended start/step operation; never writes a record. */
    suspend fun backFromCooking(expected: CookingFlowState = cooking.states.value, navigation: CookingNavigation = cookingNavigation.value) = withContext(dispatcher) {
        if (!cookingActionCurrent(expected, navigation)) return@withContext cookingActionFailure()
        if (expected.screen == CookingFlowScreen.COOK) cookingUi.dismiss() else cookingUi.leave()
        val result = cooking.backToRecipe()
        if (result is PortResult.Failure) form.fail(result.reason)
        result
    }

    private fun cookingActionCurrent(expected: CookingFlowState, navigation: CookingNavigation, visible: Boolean = true) =
        !closed && form.current() && cooking.states.value === expected && cookingNavigation.value === navigation &&
            (!visible || navigation.visible) && navigation.confirmation == null && cookingChildRoutesHidden()
    private fun cookingChildRoutesHidden(exceptMemory: Boolean = false) = socialHidden() && kitchenPage.value == null && cookbook.states.value.screen == CookbookScreen.HIDDEN &&
        timers?.states?.value?.visible != true && timerConfirmation.value == null && !reviewedPostsNavigation.value.visible &&
        postDrafts?.states?.value?.screen?.let { it != PostDraftScreen.HIDDEN } != true &&
        circles?.states?.value?.screen?.let { it != CirclesScreen.HIDDEN } != true && invitationRoutesHidden(exceptMemory)
    private fun mainMealActionCurrent(expected: MealRequestState, expectedForm: MealFormState) = !closed && form.current() &&
        meals.states.value === expected && forms.value === expectedForm &&
        expected.screen in setOf(MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE) &&
        !cookingNavigation.value.visible && cookingNavigation.value.confirmation == null && cookingChildRoutesHidden()
    private fun cookingActionFailure() = PortResult.Failure(if (closed || !form.current()) FailureReason.STALE_SESSION else FailureReason.CONFLICT)

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

    suspend fun openTimers(expected: CookingFlowState = cooking.states.value,
        navigation: CookingNavigation = cookingNavigation.value) = perform(admission = {
        cookingActionCurrent(expected, navigation, visible = false)
    }) {
        val owner = timers ?: return@perform PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val pin = cooking.states.value.cooking ?: return@perform PortResult.Failure(FailureReason.CONFLICT)
        if (forms.value.dirty || cookingNavigation.value.confirmation != null)
            return@perform PortResult.Failure(FailureReason.CONFLICT)
        timerUi.dismiss()
        owner.open(pin.id, pin.progress.currentStepId).also { if (it is PortResult.Value) cookingUi.show() }
    }
    /** Capture during rendering. Delayed UI callbacks pass this exact binding, never a fresh one. */
    fun captureTimerAction(owner: CookingTimerFlowController? = timers, state: CookingTimerFlowState? = owner?.states?.value,
        navigation: CookingNavigation = cookingNavigation.value, removal: CookingTimerRemoval? = timerConfirmation.value): CookingTimerRenderedAction? =
        if (owner == null || state == null) null else CookingTimerRenderedAction(owner, state, navigation, removal)

    private fun timerRouteCurrent(expected: CookingTimerRenderedAction?, removal: CookingTimerRemoval? = expected?.removal): Boolean =
        expected != null && !closed && form.current() && timers === expected.owner && expected.state.visible &&
            expected.owner.states.value.visible && cookingNavigation.value === expected.navigation && expected.navigation.visible &&
            expected.navigation.confirmation == null && timerConfirmation.value === removal &&
            kitchenPage.value == null && cookbook.states.value.screen == CookbookScreen.HIDDEN && !reviewedPostsNavigation.value.visible &&
            postDrafts?.states?.value?.screen?.let { it != PostDraftScreen.HIDDEN } != true &&
            circles?.states?.value?.screen?.let { it != CirclesScreen.HIDDEN } != true && invitationRoutesHidden()
    private fun timerActionCurrent(expected: CookingTimerRenderedAction?, removal: CookingTimerRemoval? = expected?.removal): Boolean =
        timerRouteCurrent(expected, removal) && expected != null && !expected.state.busy && expected.owner.matchesRenderedIntent(expected.state)
    /** Read-only presentation admission; clock ticks may change without granting new command authority. */
    fun timerDisplayIsCurrent(expected: CookingTimerRenderedAction?): Boolean = expected != null &&
        expected.removal == null && reportsHidden() && timerRouteCurrent(expected) &&
        expected.owner.matchesRenderedIntent(expected.state)
    private suspend fun performTimer(expected: CookingTimerRenderedAction?, block: suspend (CookingTimerRenderedAction) -> PortResult<CookingTimerFlowState>) =
        perform(admission = { expected?.removal == null && timerActionCurrent(expected) }) { block(checkNotNull(expected)) }

    /** Read-only ticks do not acquire the form action owner or submit/synchronize anything. */
    suspend fun tickTimers(expectedOwner: CookingTimerFlowController? = timers): PortResult<CookingTimerFlowState> = withContext(dispatcher) {
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (expectedOwner == null) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (timers !== expectedOwner) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        expectedOwner.tick()
    }
    suspend fun refreshTimers(expected: CookingTimerRenderedAction? = captureTimerAction()) = withContext(dispatcher) {
        if (expected?.removal != null || !timerActionCurrent(expected)) return@withContext cookingActionFailure()
        checkNotNull(expected).owner.tick()
    }
    suspend fun timerDuration(text: String, expected: CookingTimerRenderedAction? = captureTimerAction()) =
        performTimer(expected) { it.owner.setDurationText(text, it.state) }
    suspend fun startTimer(expected: CookingTimerRenderedAction? = captureTimerAction()) = performTimer(expected) { it.owner.start(it.state) }
    suspend fun pauseTimer(id: String, expected: CookingTimerRenderedAction? = captureTimerAction()) = performTimer(expected) { it.owner.pause(id, it.state) }
    suspend fun resumeTimer(id: String, expected: CookingTimerRenderedAction? = captureTimerAction()) = performTimer(expected) { it.owner.resume(id, it.state) }
    suspend fun resetTimer(id: String, expected: CookingTimerRenderedAction? = captureTimerAction()) = performTimer(expected) { it.owner.reset(id, it.state) }
    suspend fun cancelTimerAlert(id: String, expected: CookingTimerRenderedAction? = captureTimerAction()) = performTimer(expected) { it.owner.cancelAlert(id, it.state) }
    fun requestTimerRemoval(id: String, expected: CookingTimerRenderedAction? = captureTimerAction()) {
        if (expected?.removal != null || !timerActionCurrent(expected)) return
        val state = checkNotNull(expected).owner.states.value
        val pin = state.snapshot ?: return
        if (!state.visible || !state.canStop || state.busy || state.pendingAction != null || pin.timers.none { it.timerId == id }) return
        timerUi.present(pin.sessionId, pin.planId, pin.localRevision, id, expected)
    }
    /** An obsolete modal may be dismissed, but its callback cannot dismiss a newer modal. */
    fun dismissTimerRemoval(intent: CookingTimerRemoval? = timerConfirmation.value) {
        if (!closed && form.current() && intent != null && timerUi.owns(intent)) timerUi.dismiss()
    }
    private fun timerRemovalCurrent(intent: CookingTimerRemoval): Boolean {
        val expected = intent.action ?: return false
        if (!timerUi.owns(intent) || expected.removal != null || !timerActionCurrent(expected, intent)) return false
        val state = expected.owner.states.value
        val pin = state.snapshot ?: return false
        return state.canStop && state.pendingAction == null &&
            pin.sessionId == intent.sessionId && pin.planId == intent.planId && pin.localRevision == intent.revision &&
            pin.timers.any { it.timerId == intent.timerId }
    }
    suspend fun confirmTimerRemoval(intent: CookingTimerRemoval) = perform(admission = { timerRemovalCurrent(intent) }) {
        val expected = checkNotNull(intent.action)
        timerUi.dismiss()
        expected.owner.cancel(intent.timerId, expected.state)
    }
    /** Back is immediate navigation, not a queued mutation, pause, reset, cancellation or discard. */
    suspend fun backFromTimers(expected: CookingTimerRenderedAction? = captureTimerAction()) = withContext(dispatcher) {
        // Do not compare semantic revisions here: Back must remain available while a mutation
        // changes the same visit's pending state. The controller checks the exact visit token.
        if (!timerRouteCurrent(expected)) return@withContext cookingActionFailure()
        val rendered = checkNotNull(expected)
        if (rendered.removal != null) {
            dismissTimerRemoval(rendered.removal)
            PortResult.Value(rendered.owner.states.value)
        } else rendered.owner.back(rendered.state)
    }

    suspend fun loadKitchenPreferences() = perform { kitchen.loadPreferences() }

    private fun pantrySiblings(): List<Any?> = listOf(meals.states.value, cookingNavigation.value,
        cookbook.states.value, timerOwner.value, timerConfirmation.value, mutableSavedCookingConfirmation.value,
        reviewedPostNavigation.states.value, postDrafts?.states?.value, circles?.states?.value,
        circleCreate?.states?.value, circleEdit?.states?.value, circleLeave?.states?.value, circleDelete?.states?.value,
        circleMemberRemoval?.states?.value,
        circleOwnershipTransfer?.states?.value,
        invitationPreview?.states?.value, circleInvitation?.states?.value, circleIssuedInvitations?.states?.value,
        blueprintLanding.states.value)

    fun capturePantryAction(expected: KitchenInputState, expectedForm: MealFormState,
        expectedPicker: IngredientPickerState): PantryRenderedAction? =
        if (!closed && form.current() && reportsHidden() && kitchenPage.value == KitchenInputPage.PANTRY &&
            kitchen.states.value === expected && forms.value === expectedForm && ingredients.states.value === expectedPicker)
            PantryRenderedAction(expected, expectedForm, expectedPicker, kitchenVisit, pantrySiblings()) else null

    fun pantryActionCurrent(expected: PantryRenderedAction?): Boolean = expected != null &&
        pantryVisitCurrent(expected) && kitchen.states.value === expected.kitchen && forms.value === expected.form &&
        ingredients.states.value === expected.picker

    private fun pantryVisitCurrent(expected: PantryRenderedAction): Boolean = !closed && form.current() &&
        reportsHidden() && kitchenPage.value == KitchenInputPage.PANTRY && kitchenVisit === expected.visit &&
        pantrySiblings().zip(expected.siblings).all { (now, then) -> now === then }

    fun pantryMealHandoffCurrent(expected: PantryRenderedAction?): Boolean = pantryActionCurrent(expected) &&
        action == null && !acceptingRootRecipe && !handingOffSavedRoot &&
        mutableSavedCookingConfirmation.value == null &&
            blueprintMealLandingEligible(MealScreenState.from(meals.states.value), checkNotNull(expected).form)

    /** A saved-preference child may intentionally change kitchen/picker observations, but
     * never borrow a different account, meal form, Pantry visit or sibling navigation. */
    internal fun pantryPreferenceContinuationCurrent(expected: PantryRenderedAction?): Boolean =
        expected != null && accountKitchenPreferencesAvailable && pantryVisitCurrent(expected) &&
            forms.value === expected.form && action == null && !acceptingRootRecipe && !handingOffSavedRoot &&
            mutableSavedCookingConfirmation.value == null &&
            blueprintMealLandingEligible(MealScreenState.from(meals.states.value), expected.form)

    /** PANTRY.03 is a local form edit, not a pantry confirmation, durable Save or Find.
     * Exact source and navigation fences are checked on the serialized owner dispatcher. */
    suspend fun useBlueprintPantryIngredients(expected: PantryRenderedAction,
        selectedIds: List<String>): PortResult<MealFormState> {
        val exactIds = selectedIds.toList()
        return withContext(dispatcher) {
            if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            if (!pantryMealHandoffCurrent(expected)) return@withContext PortResult.Failure(FailureReason.CONFLICT)
            val before = expected.form.values ?: return@withContext PortResult.Failure(FailureReason.CONFLICT)
            val next = blueprintPantryMealValues(expected.kitchen, expected.picker, before, exactIds)
                ?: return@withContext PortResult.Failure(FailureReason.INVALID_DATA)
            val landing = blueprintLanding.states.value
            if (!blueprintLanding.current(landing, true)) return@withContext PortResult.Failure(FailureReason.CONFLICT)
            form.edit { next }
            val changed = forms.value
            if (changed === expected.form || changed.failure != null || changed.values != next)
                return@withContext PortResult.Failure(changed.failure ?: FailureReason.CONFLICT)
            adaptationForm.leave(); cookingUi.dismiss()
            if (landing.home) blueprintLanding.showRequest(landing, true) else blueprintLanding.claim(landing, true)
            leaveKitchen()
            PortResult.Value(changed)
        }
    }

    /** Search is explicit. No ingredient is selected or persisted by looking up a label. */
    suspend fun searchPantryIngredient(expected: PantryRenderedAction) = perform(admission = {
        pantryActionCurrent(expected) && !expected.form.busy
    }) { ingredients.search(expected.form.searchText) }
    suspend fun morePantryIngredients(expected: PantryRenderedAction) = perform(admission = {
        pantryActionCurrent(expected) && !expected.form.busy && expected.picker.searchQuery == expected.form.searchText
    }) { ingredients.nextSearchPage() }

    /** One Add click retains the exact selected catalog object and one immutable write. A
     * failed local acknowledgement, newer visit or edit never proceeds to enqueue/send. */
    suspend fun addBlueprintPantryIngredient(expected: PantryRenderedAction, ingredient: IngredientOption,
        availability: BlueprintIngredientAvailability): PortResult<KitchenInputState> = perform(admission = {
        pantryActionCurrent(expected) && !expected.form.busy && blueprintPantryHealthy(expected.kitchen) &&
            blueprintPantryIngredientIsCurrent(expected.picker, ingredient, expected.form.searchText)
    }) {
        val generation = form.capture()?.first ?: return@perform cookingActionFailure()
        val admittedForm = forms.value
        val current = expected.kitchen.pantryItems.singleOrNull { kitchenIngredientId(it).equals(ingredient.id, true) }
        val write = blueprintPantryWrite(current, ingredient.id, availability, clock.nowMillis())
        val edited = kitchen.editPantry(write)
        if (edited is PortResult.Failure) return@perform edited
        val acknowledged = (edited as PortResult.Value).value
        if (!pantryVisitCurrent(expected) || !form.matches(generation) || forms.value !== admittedForm ||
            ingredients.states.value !== expected.picker || kitchen.states.value !== acknowledged ||
            !acknowledged.draftAcknowledged || acknowledged.pantryDrafts.singleOrNull {
                kitchenIngredientId(it).equals(ingredient.id, true)
            }?.encodeUtf8()?.contentEquals(write.encodeUtf8()) != true) return@perform cookingActionFailure()
        val queued = kitchen.savePantry(ingredient.id)
        if (!pantryVisitCurrent(expected) || !form.matches(generation) ||
            forms.value !== admittedForm || ingredients.states.value !== expected.picker)
            return@perform cookingActionFailure()
        sendSavedKitchenCommand(queued, "upsertPantryItem", ingredient.id)
    }

    suspend fun removeBlueprintPantryIngredient(expected: PantryRenderedAction, exact: WireDocument) = perform(admission = {
        pantryActionCurrent(expected) && !expected.form.busy && blueprintPantryHealthy(expected.kitchen) &&
            expected.kitchen.pantryItems.any { it === exact } && expected.kitchen.pantryItems.count {
                kitchenIngredientId(it).equals(kitchenIngredientId(exact), true)
            } == 1
    }) {
        val admittedForm = forms.value
        val queued = kitchen.removePantry(kitchenIngredientId(exact))
        if (!pantryVisitCurrent(expected) || forms.value !== admittedForm) return@perform cookingActionFailure()
        sendSavedKitchenCommand(queued, "removePantryItem", kitchenIngredientId(exact))
    }
    /** One explicit Pantry entry/refresh loads its rows and their current catalog names.
     * Labels are metadata only: this never selects ingredients, confirms stock or sends a
     * retained pantry command. Back/reopen while the first read is suspended prevents the
     * follow-up lookup from borrowing the new visit. */
    suspend fun loadBlueprintPantry(expected: PantryRenderedAction, nextPage: Boolean = false): PortResult<KitchenInputState> =
        perform(admission = { pantryActionCurrent(expected) && !expected.form.busy }) {
            val admittedForm = forms.value
            fun visitCurrent() = pantryVisitCurrent(expected) && forms.value === admittedForm
            val preferences = kitchen.loadPreferences()
            if (preferences is PortResult.Failure) return@perform preferences
            val currentPreferences = (preferences as PortResult.Value).value
            if (!visitCurrent() || kitchen.states.value !== currentPreferences || ingredients.states.value !== expected.picker)
                return@perform PortResult.Failure(FailureReason.CONFLICT)
            if (currentPreferences.preferencesHistorical || !blueprintPantryHealthy(currentPreferences)) return@perform preferences
            val loaded = if (nextPage) kitchen.nextPantryPage() else kitchen.loadPantry()
            if (loaded is PortResult.Failure) return@perform loaded
            val rows = (loaded as PortResult.Value).value
            fun sourceCurrent() = visitCurrent() && kitchen.states.value === rows
            if (!sourceCurrent() || ingredients.states.value !== expected.picker)
                return@perform PortResult.Failure(FailureReason.CONFLICT)
            if (!blueprintPantryHealthy(rows) || rows.preferencesHistorical) return@perform loaded
            val currentIds = rows.pantryItems.map(::kitchenIngredientId).filter { it in rows.observedPantryIngredientIds }
            if (currentIds.isEmpty()) return@perform loaded
            val labels = ingredients.resolveCurrentLabels(currentIds)
            if (!sourceCurrent()) return@perform PortResult.Failure(FailureReason.CONFLICT)
            if (labels is PortResult.Failure) return@perform labels
            loaded
        }

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
    private suspend fun <T> perform(adaptation: Boolean = false, admission: () -> Boolean = { true },
        savedSelection: SavedCookingConfirmation? = null,
        block: suspend () -> PortResult<T>): PortResult<T> = withContext(dispatcher) {
        if (closed || !form.current()) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
        if (handingOffSavedRoot) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        if (!reportsHidden()) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        if (!adaptation && meals.states.value.screen in ROOT_RECIPE_SCREENS) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        if (action != null) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        if (mutablePostSaveReview.value != null) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        if (mutableSavedCookingConfirmation.value !== savedSelection) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        // Validate a rendered action before even publishing busy/failure state. A stale
        // callback must not borrow the current meal/session after coroutine dispatch.
        if (!admission()) return@withContext PortResult.Failure(FailureReason.CONFLICT)
        val token = Any(); action = token; form.busy(true)
        try {
            val result = block()
            if (!adaptation && result is PortResult.Value && adaptationForms.value.visible) adaptationForm.leave()
            if (result is PortResult.Failure) form.fail(result.reason)
            if (!form.current()) PortResult.Failure(FailureReason.STALE_SESSION) else result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { form.fail(FailureReason.UNAVAILABLE); PortResult.Failure(FailureReason.UNAVAILABLE) }
        finally {
            rootRecipeForm.reconcileSource(meals.states.value.rootSource)
            if (action === token) { action = null; form.busy(false) }
        }
    }
    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        offlineAvailability = null
        savedRootReturn = null
        savedTabReturn = null; socialReturn = SocialReturn.MEAL
        circlesTabReturn = null; savedReturnsToCircles = null
        reuseRootReturn = null
        planSourceReview = null
        collectionSavedReturn = null
        collectionSavedOpening?.second?.cancel(); collectionSavedOpening = null
        mutableSavedCookingConfirmation.value = null
        mutablePostSaveReview.value = null
        closed = true; mutableKitchenPage.value = null; cookingUi.close(); timerUi.close(); form.close(); adaptationForm.close()
        rootRecipeForm.close(); mutableCatalogQuery.value = ""
        reviewedPostNavigation.close()
        reportFormMemory.clear(); reportFormSubscription.close()
        blueprintLanding.invalidate(); blueprintLandingSubscription.close()
        interpretation.close(); adaptationInterpretation.close()
        mutableCookbookQuery.value = ""; cookbookSubscription.close()
        // These close methods redact and unsubscribe only; borrowed native ownership is untouched.
        var failure: PortResult.Failure? = null
        fun observe(result: PortResult<Unit>?) { if (failure == null && result is PortResult.Failure) failure = result }
        observe(circles?.close())
        observe(socialReads?.close()); observe(conversations?.close()); observe(postDeletion?.close()); observe(recipeRequests?.close())
        observe(postPlacement?.close())
        observe(reactions?.close())
        observe(remixes?.close())
        observe(sessionControls?.close())
        observe(notifications?.close())
        observe(notificationReads?.close())
        observe(profileEdit?.close())
        observe(accountExports?.close())
        observe(blocks?.close())
        observe(circleMemberRemoval?.close())
        observe(circleOwnershipTransfer?.close())
        observe(mealMemory?.close()); observe(collections?.close()); observe(reviewedAttachments?.close())
        observe(reports?.close())
        observe(invitationPreview?.close())
        observe(timerOwner.value?.close()); timerOwner.value = null
        if (ownedKitchenBundle != null) observe(ownedKitchenBundle.close())
        else { observe(postDrafts?.close()); observe(cookbook.close()); observe(cooking.close()) }
        observe(kitchen.close()); observe(ingredients.close()); observe(meals.close())
        failure ?: PortResult.Value(Unit)
    }
    override fun toString() = "MealFlowExperience(<redacted>)"
    companion object {
        private val PLAN_SOURCE_SCREENS = setOf(MealFlowScreen.CATALOG_RECIPE,
            MealFlowScreen.SAVED_RECIPE, MealFlowScreen.POST_RECIPE)
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
            integrationForAccess: (AuthenticatedMealPlanningAccess) -> ReviewedKitchenIntegration,
            circlesPolicy: CirclesReadPolicy? = null, circleCreatePolicy: CircleCreatePolicy? = null,
            circleEditPolicy: CircleEditPolicy? = null,
            invitationPreview: CircleInvitationPreviewConfiguration? = null,
            circleInvitationPolicy: CircleInvitationPolicy? = null,
            circleIssuedInvitationsPolicy: CircleIssuedInvitationsPolicy? = null,
            circleLeavePolicy: CircleLeavePolicy? = null,
            reportsPolicy: ReportClientPolicy? = null, mealInterpretationEnabled: Boolean = false,
            photoUploadPort: SupabasePhotoUploadPort? = null,
            memoryPolicy: com.feedme.mealflow.memory.MealMemoryPolicy? = null,
            collectionPolicy: CollectionReadPolicy? = null): MealFlowExperience {
            require(boundary.isCurrent(session.lease) && session.scope.actorKind == ActorKind.ACCOUNT) { "Current account session required" }
            val interpretationSender = interpretationSender(mealInterpretationEnabled, session, transport, boundary, connectivity)
            require(circleIssuedInvitationsPolicy == null || (circlesPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) { "Issuing invitations requires explicit circle browsing and an account" }
            require(circleLeavePolicy == null || (circlesPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) { "Self-leave requires circle browsing and an account" }
            require(circleCreatePolicy == null || circlesPolicy != null) { "Creation UI requires explicit circle browsing" }
            require(circleEditPolicy == null || circlesPolicy != null) { "Editing UI requires explicit circle browsing" }
            require(circleInvitationPolicy == null || (circlesPolicy != null && invitationPreview != null && session.scope.actorKind == ActorKind.ACCOUNT)) { "Joining requires explicit public preview, circle browsing and an account" }
            val access = AuthenticatedMealPlanningAccess.fromSession(session, transport)
            val integration = ConstructedReviewedIntegration(integrationForAccess(access))
            require(boundary.isCurrent(session.lease)) { "Current private session required" }
            val meals = MealRequestController(access, boundary, dispatcher, clock, connectivity, ids, mealPolicy)
            val retained = MealKitchenControllers.createWithReviewedPosts(access, boundary, dispatcher, clock, connectivity,
                ids, meals, meals.draftReadiness, cookingPolicy, cookbookPolicy, draftPolicy, publicationPolicy, integration, circleCreatePolicy, circleEditPolicy, circleInvitationPolicy, circleIssuedInvitationsPolicy, circleLeavePolicy,
                photoUploadPort = photoUploadPort)
            return MealFlowExperience(meals,
                IngredientPickerController(access, boundary, dispatcher, clock, connectivity, ingredientPolicy),
                KitchenInputController(access, boundary, dispatcher, clock, connectivity, ids, kitchenPolicy),
                retained.cooking, retained.cookbook,
                MealFormOwner(boundary, session.lease, meals.draftReadiness), CookingUiOwner(boundary, session.lease), dispatcher, choices,
                session, boundary, CookingTimerUiOwner(boundary, session.lease), retained.postDrafts, retained,
                circlesPolicy?.let { CirclesController(access, boundary, dispatcher, connectivity, it) },
                invitationPreview = invitationPreview?.let { CircleInvitationPreviewController(it.transport, dispatcher, clock, connectivity, it.policy) },
                reports = reportsPolicy?.let { ReportController(access, boundary, dispatcher, clock, connectivity, ids, it) }, clock = clock,
                interpretText = interpretationSender,
                mealMemory = memoryPolicy?.let { com.feedme.mealflow.memory.MealMemoryController(access, boundary, dispatcher, clock, connectivity, ids, it) },
                collections = collectionPolicy?.let { CollectionReadController(access, boundary, dispatcher, clock, connectivity, ids, it) })
        }

        fun fromSession(session: PrivateSessionAccess, transport: AccountTransport, boundary: SessionBoundary,
            dispatcher: CoroutineDispatcher, clock: EpochClock, connectivity: ConnectivityPort,
            ids: MealOperationIds, mealPolicy: MealFlowPolicy, ingredientPolicy: IngredientPickerPolicy,
            choices: MealInputChoices, kitchenPolicy: KitchenInputPolicy, cookingPolicy: CookingFlowPolicy,
            cookbookPolicy: CookbookPolicy, draftPolicy: PostDraftClientPolicy? = null,
            circlesPolicy: CirclesReadPolicy? = null, circleCreatePolicy: CircleCreatePolicy? = null,
            circleEditPolicy: CircleEditPolicy? = null,
            invitationPreview: CircleInvitationPreviewConfiguration? = null,
            circleInvitationPolicy: CircleInvitationPolicy? = null,
            circleIssuedInvitationsPolicy: CircleIssuedInvitationsPolicy? = null,
            circleLeavePolicy: CircleLeavePolicy? = null,
            reportsPolicy: ReportClientPolicy? = null, mealInterpretationEnabled: Boolean = false,
            socialReadPolicy: SocialReadPolicy? = null,
            blocksPolicy: com.feedme.mealflow.blocks.BlocksPolicy? = null,
            localPhotoPolicy: LocalPhotoDraftPolicy? = null,
            circleMemberRemovalPolicy: CircleMemberRemovalPolicy? = null,
            photoUploadPort: SupabasePhotoUploadPort? = null,
            memoryPolicy: com.feedme.mealflow.memory.MealMemoryPolicy? = null,
            collectionPolicy: CollectionReadPolicy? = null,
            reviewedPostsConfiguration: ReviewedPostsConfiguration? = null,
            conversationPolicy: ThreadPolicy? = null,
            postDeletionPolicy: PostDeletionPolicy? = null,
            recipeRequestPolicy: RecipeRequestPolicy? = null,
            sessionControlsPolicy: SessionControlsPolicy? = null,
            notificationSettingsPolicy: NotificationSettingsPolicy? = null,
            photoDeliveryPort: SocialPhotoDeliveryPort? = null,
            socialPhotoPolicy: SocialPhotoPolicy? = null,
            remixTrailPolicy: RemixTrailPolicy? = null,
            profileEditPolicy: com.feedme.mealflow.profile.AccountProfileEditPolicy? = null,
            accountExportPolicy: com.feedme.mealflow.exports.AccountExportPolicy? = null,
            exportDeliveryPort: com.feedme.transport.AccountExportDeliveryPort? = null,
            circleOwnershipTransferPolicy: CircleOwnershipTransferPolicy? = null,
            postPlacementPolicy: PostPlacementPolicy? = null,
            reactionPolicy: ReactionPolicy? = null): MealFlowExperience {
            require(boundary.isCurrent(session.lease)) { "Current private session required" }
            require(reviewedPostsConfiguration == null || (draftPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) {
                "Reviewed posts require account drafts and an explicit native integration"
            }
            val interpretationSender = interpretationSender(mealInterpretationEnabled, session, transport, boundary, connectivity)
            require(reportsPolicy == null || session.scope.actorKind == ActorKind.ACCOUNT) { "Reports require an account session" }
            require(memoryPolicy == null || session.scope.actorKind == ActorKind.ACCOUNT) { "Meal memory requires an account session" }
            require(collectionPolicy == null || session.scope.actorKind == ActorKind.ACCOUNT) { "Collections require an account session" }
            require(sessionControlsPolicy == null || session.scope.actorKind == ActorKind.ACCOUNT) { "Device sessions require an account" }
            require(notificationSettingsPolicy == null || session.scope.actorKind == ActorKind.ACCOUNT) { "Notification settings require an account" }
            require(profileEditPolicy == null || session.scope.actorKind == ActorKind.ACCOUNT) { "Profile editing requires an account" }
            require(accountExportPolicy == null || session.scope.actorKind == ActorKind.ACCOUNT) { "Account exports require an account" }
            require(exportDeliveryPort == null || exportDeliveryPort.environment == session.scope.environment) { "Export delivery requires the same environment" }
            require(socialReadPolicy == null || session.scope.actorKind == ActorKind.ACCOUNT) { "Social reads require an account session" }
            require(remixTrailPolicy == null || (socialReadPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) {
                "Remix reading requires the same account social owner"
            }
            require((photoDeliveryPort == null) == (socialPhotoPolicy == null)) { "Photo delivery requires explicit limits" }
            require(photoDeliveryPort == null || (socialReadPolicy != null && photoDeliveryPort.environment == session.scope.environment)) {
                "Photo delivery requires same-environment social reading"
            }
            require(conversationPolicy == null || (socialReadPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) {
                "Conversations require account inbox ownership"
            }
            require(postDeletionPolicy == null || (socialReadPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) {
                "Post deletion requires account post ownership"
            }
            require(postPlacementPolicy == null || (socialReadPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) {
                "Post placement requires account post ownership"
            }
            require(reactionPolicy == null || (socialReadPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) {
                "Reactions require account post reading"
            }
            require(recipeRequestPolicy == null || (socialReadPolicy != null && conversationPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) {
                "Recipe requests require account post and conversation ownership"
            }
            require(blocksPolicy == null || session.scope.actorKind == ActorKind.ACCOUNT) { "Blocked accounts require an account session" }
            require(localPhotoPolicy == null || (draftPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) { "Local photos require account drafts" }
            require(photoUploadPort == null || (localPhotoPolicy != null && photoUploadPort.environment == session.scope.environment)) { "Photo uploads require explicit same-environment photo drafts" }
            require(circleMemberRemovalPolicy == null || (circlesPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) { "Member removal requires account circle browsing" }
            require(circleOwnershipTransferPolicy == null || (circlesPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) { "Ownership transfer requires account circle browsing" }
            require(circlesPolicy == null || session.scope.actorKind == ActorKind.ACCOUNT) { "Circle reads require an account session" }
            require(circleIssuedInvitationsPolicy == null || (circlesPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) { "Issuing invitations requires explicit circle browsing and an account" }
            require(circleLeavePolicy == null || (circlesPolicy != null && session.scope.actorKind == ActorKind.ACCOUNT)) { "Self-leave requires circle browsing and an account" }
            require(circleCreatePolicy == null || circlesPolicy != null) { "Creation UI requires explicit circle browsing" }
            require(circleEditPolicy == null || circlesPolicy != null) { "Editing UI requires explicit circle browsing" }
            require(circleInvitationPolicy == null || (circlesPolicy != null && invitationPreview != null && session.scope.actorKind == ActorKind.ACCOUNT)) { "Joining requires explicit public preview, circle browsing and an account" }
            val access = AuthenticatedMealPlanningAccess.fromSession(session, transport)
            val reviewedIntegration = reviewedPostsConfiguration?.let { ConstructedReviewedIntegration(it.integrationForAccess(access)) }
            val meals = MealRequestController(access, boundary, dispatcher, clock, connectivity, ids, mealPolicy)
            val retained = if (reviewedIntegration != null) MealKitchenControllers.createWithReviewedPosts(access, boundary, dispatcher,
                clock, connectivity, ids, meals, meals.draftReadiness, cookingPolicy, cookbookPolicy, checkNotNull(draftPolicy),
                checkNotNull(reviewedPostsConfiguration).publicationPolicy, reviewedIntegration,
                circleCreatePolicy, circleEditPolicy, circleInvitationPolicy, circleIssuedInvitationsPolicy, circleLeavePolicy,
                photoUploadPort = photoUploadPort)
            else if (draftPolicy == null) MealKitchenControllers.create(access, boundary, dispatcher, clock, connectivity, ids, meals,
                meals.draftReadiness, cookingPolicy, cookbookPolicy, circleCreatePolicy, circleEditPolicy, circleInvitationPolicy, circleIssuedInvitationsPolicy, circleLeavePolicy)
            else MealKitchenControllers.createWithDrafts(access, boundary, dispatcher, clock, connectivity, ids, meals,
                meals.draftReadiness, cookingPolicy, cookbookPolicy, draftPolicy, circleCreatePolicy, circleEditPolicy, circleInvitationPolicy, circleIssuedInvitationsPolicy, circleLeavePolicy,
                localPhotoPolicy = localPhotoPolicy, photoUploadPort = photoUploadPort)
            val socialReader = socialReadPolicy?.let { SocialReadController(access, boundary, dispatcher, connectivity, clock, it,
                photoDelivery = photoDeliveryPort, photoPolicy = socialPhotoPolicy) }
            val circleReader = circlesPolicy?.let { CirclesController(access, boundary, dispatcher, connectivity, it) }
            return MealFlowExperience(meals,
                IngredientPickerController(access, boundary, dispatcher, clock, connectivity, ingredientPolicy),
                KitchenInputController(access, boundary, dispatcher, clock, connectivity, ids, kitchenPolicy),
                retained.cooking, retained.cookbook,
                MealFormOwner(boundary, session.lease, meals.draftReadiness), CookingUiOwner(boundary, session.lease), dispatcher, choices,
                session, boundary, CookingTimerUiOwner(boundary, session.lease), retained.postDrafts, retained,
                circles = circleReader,
                invitationPreview = invitationPreview?.let { CircleInvitationPreviewController(it.transport, dispatcher, clock, connectivity, it.policy) },
                reports = reportsPolicy?.let { ReportController(access, boundary, dispatcher, clock, connectivity, ids, it) }, clock = clock,
                interpretText = interpretationSender,
                socialReads = socialReader,
                blocks = blocksPolicy?.let { com.feedme.mealflow.blocks.BlocksController(access, boundary, dispatcher, clock, connectivity, ids, it) },
                localPhotoDraftsEnabled = localPhotoPolicy != null,
                circleMemberRemoval = circleMemberRemovalPolicy?.let { CircleMemberRemovalController(access, boundary, dispatcher, clock, connectivity, ids, it) },
                circleOwnershipTransfer = circleOwnershipTransferPolicy?.let { CircleOwnershipTransferController(
                    access, boundary, dispatcher, clock, connectivity, ids, it,
                    onAcknowledged = { circleReader?.discardRetainedObservations(); socialReader?.discardRetainedObservations() }) },
                mealMemory = memoryPolicy?.let { com.feedme.mealflow.memory.MealMemoryController(access, boundary, dispatcher, clock, connectivity, ids, it) },
                collections = collectionPolicy?.let { CollectionReadController(access, boundary, dispatcher, clock, connectivity, ids, it) },
                reviewedAttachments = reviewedIntegration?.let { integration -> ReviewedAttachmentController(access, boundary,
                    dispatcher, clock, connectivity, checkNotNull(retained.reviewedPosts), integration,
                    checkNotNull(reviewedPostsConfiguration).attachmentPolicy, savedReadObserver = retained.cookbook) },
                conversations = conversationPolicy?.let { ThreadController(access, boundary, dispatcher, clock, connectivity, ids, it) },
                postDeletion = postDeletionPolicy?.let { PostDeletionController(access, boundary, dispatcher, clock, connectivity, ids, it,
                    onAcknowledged = { socialReader?.discardRetainedObservations() }) },
                postPlacement = postPlacementPolicy?.let { PostPlacementController(access, boundary, dispatcher, clock, connectivity, ids, it,
                    onAcknowledged = { socialReader?.discardRetainedObservations() }) },
                reactions = reactionPolicy?.let { ReactionController(access, boundary, dispatcher, clock, connectivity, ids, it,
                    onAcknowledged = { socialReader?.discardRetainedObservations() }) },
                recipeRequests = recipeRequestPolicy?.let { RecipeRequestController(access, boundary, dispatcher, clock, connectivity, ids, it) },
                sessionControls = sessionControlsPolicy?.let { SessionControlsController(access, boundary, dispatcher, clock, connectivity, ids, it) },
                notifications = notificationSettingsPolicy?.let { NotificationSettingsController(access, boundary, dispatcher, clock, connectivity, ids, it) },
                notificationReads = socialReadPolicy?.takeIf { it.notificationReadAcknowledgementsEnabled }?.let {
                    NotificationReadController(access, boundary, dispatcher, clock, connectivity, ids, it) },
                accountExports = accountExportPolicy?.let { com.feedme.mealflow.exports.AccountExportController(
                    access, boundary, dispatcher, clock, connectivity, ids, it, exportDeliveryPort) },
                remixes = remixTrailPolicy?.let { RemixTrailController(access, boundary, dispatcher, clock, connectivity, it) },
                profileEdit = profileEditPolicy?.let { com.feedme.mealflow.profile.AccountProfileEditController(
                    access, boundary, dispatcher, clock, connectivity, ids, it,
                    onAcknowledged = { socialReader?.discardRetainedObservations() }) })
        }

        /** Explicit feature configuration only; never inferred from the presence of API keys.
         * No client/provider request, local write or capability check occurs during creation. */
        private fun interpretationSender(enabled: Boolean, session: PrivateSessionAccess, transport: AccountTransport,
            boundary: SessionBoundary, connectivity: ConnectivityPort): (suspend (String) -> PortResult<MealInterpretationProposal>)? {
            if (!enabled) return null
            require(session.scope.actorKind == ActorKind.ACCOUNT && session.mode == PrivateSessionAccessMode.ONLINE) {
                "Interpretation requires an online account composition"
            }
            val client = MealInterpretationClient(transport, boundary, session.lease)
            return { text ->
                if (connectivity.current() != Connectivity.ONLINE) PortResult.Failure(FailureReason.OFFLINE)
                else client.interpret(text)
            }
        }
    }
}
