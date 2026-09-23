package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeTheme
import com.feedme.app.FeedMeDetails
import com.feedme.app.FeedMeWordmark
import com.feedme.app.circles.FeedMeCirclesFlow
import com.feedme.app.circles.CirclesTabNavigation
import com.feedme.app.circles.CircleDirectPeoplePicker
import com.feedme.app.circles.FeedMeCircleDirectPeoplePicker
import com.feedme.app.reports.FeedMeReportFlow
import com.feedme.app.blueprint.BlueprintScreenId
import com.feedme.app.blueprint.BlueprintDiscoveryAction
import com.feedme.app.blueprint.BlueprintPreferencePage
import com.feedme.app.onboarding.OnboardingPreferenceChoices
import com.feedme.mealflow.social.SocialReadScreen
import com.feedme.mealflow.social.SocialReadState
import com.feedme.mealflow.social.SocialReadPhase
import com.feedme.mealflow.reports.ReportScreen
import com.feedme.app.circles.FeedMeCircleCreateFlow
import com.feedme.app.circles.FeedMeCircleEditFlow
import com.feedme.app.circles.FeedMeCircleLeaveFlow
import com.feedme.mealflow.circles.CircleLeaveScreen
import com.feedme.mealflow.circles.CircleMemberRemovalScreen
import com.feedme.app.circles.FeedMeCircleMemberRemovalFlow
import com.feedme.app.circles.FeedMeCircleOwnershipTransferFlow
import com.feedme.mealflow.circles.CircleOwnershipTransferScreen
import com.feedme.app.circles.FeedMeCircleInvitationPreviewFlow
import com.feedme.app.circles.FeedMeCircleInvitationFlow
import com.feedme.app.circles.FeedMeCircleIssuedInvitationsFlow
import com.feedme.app.circles.CircleInvitationSharePort
import com.feedme.mealflow.circles.CircleIssuedInvitationsState
import com.feedme.mealflow.circles.CircleIssuedInvitationsScreen
import com.feedme.mealflow.circles.CirclesPhase
import com.feedme.mealflow.circles.CircleInvitationPreviewState
import com.feedme.mealflow.circles.CircleInvitationPreviewScreen
import com.feedme.mealflow.circles.CircleInvitationState
import com.feedme.mealflow.circles.CircleInvitationScreen
import com.feedme.mealflow.circles.CirclesScreen
import com.feedme.mealflow.circles.CircleCreateScreen
import com.feedme.mealflow.circles.CircleCreateState
import com.feedme.mealflow.circles.CircleEditScreen
import com.feedme.mealflow.circles.CircleEditState
import com.feedme.mealflow.circles.CirclesState
import com.feedme.contracts.*
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.*
import com.feedme.mealflow.collections.*
import com.feedme.mealflow.social.PostDraftScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private class MealExitIntent(val from: MealFlowScreen)
private class CircleEditHandoff(val state: CircleEditState)
private class CircleEditEntry(val state: CirclesState)
private class CircleLeaveEntry(val state: CirclesState)
private class ReportEntry(val state: CirclesState)
private class InvitationHandoff(val state: CircleInvitationState)
private class InvitationEntry(val state: CircleInvitationPreviewState)
private class InvitationResume(val state: CirclesState)
private class IssuedInvitationEntry(val state: CirclesState)
private class IssuedInvitationHandoff(val state: CircleIssuedInvitationsState)
private class MemoryPreferenceVisit(val current: () -> Boolean, val initialRead: Job,
    val personalization: Boolean = false) {
    var initialLoaded by mutableStateOf(false)
    var initialFailure by mutableStateOf<FailureReason?>(null)
}

/** Real retained-controller host, never a demo runtime or authentication entry point.
 * The application owns experience lifetime. Detaching/recreating UI does NOT close native state.
 * onExit must navigate through the owning app root and close experience on actual disposal. */
@Composable
fun FeedMeMealFlow(experience: MealFlowExperience, onExit: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) =
    FeedMeMealFlow(experience, onExit, null, platformBackHandler)

/** Explicit host-fenced route. The original overload remains source-compatible with trailing Back lambdas. */
@Composable
fun FeedMeMealFlow(experience: MealFlowExperience, onExit: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean,
    onAccountSettings: (() -> Unit)? = null) =
    FeedMeMealFlow(experience, onExit, null, platformBackHandler, hostIsCurrent, onAccountSettings)

/** Native sharing is supplied by the current UI attachment, never retained with the session.
 * Optional social/circle child hosts have their own admission contracts; the initial account
 * product route supplies none of those optional owners. This guard does not expand authority. */
@Composable
fun FeedMeMealFlow(experience: MealFlowExperience, onExit: () -> Unit,
    invitationSharePort: CircleInvitationSharePort?,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) =
    FeedMeMealFlow(experience, onExit, invitationSharePort, platformBackHandler, { true })

/** Explicit host predicate is required when composing sharing and attachment admission. */
@Composable
fun FeedMeMealFlow(experience: MealFlowExperience, onExit: () -> Unit,
    invitationSharePort: CircleInvitationSharePort?,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean,
    onAccountSettings: (() -> Unit)? = null,
    onNotificationSettings: (() -> Unit)? = null,
    preferenceChoices: OnboardingPreferenceChoices? = null) {
    val currentHost by rememberUpdatedState(hostIsCurrent)
    var memoryPreferences by remember(experience) { mutableStateOf<MemoryPreferenceVisit?>(null) }
    val memoryPreferenceVisit = memoryPreferences
    DisposableEffect(experience, memoryPreferenceVisit) {
        onDispose { memoryPreferenceVisit?.initialRead?.cancel() }
    }
    val reportsOwner = experience.reports
    val reportState = reportsOwner?.states?.collectAsState()?.value
    val reportVisible = reportState != null && reportState.screen != ReportScreen.HIDDEN
    val hiddenReportState = reportState?.takeIf { it.screen == ReportScreen.HIDDEN }
    val blockOwner = experience.blocks
    val blockState = blockOwner?.states?.collectAsState()?.value
    val blockVisible = blockState != null && blockState.screen != com.feedme.mealflow.blocks.BlocksScreen.HIDDEN
    val hiddenBlockState = blockState?.takeIf { it.screen == com.feedme.mealflow.blocks.BlocksScreen.HIDDEN }
    var reportBlockFailure by remember(experience) { mutableStateOf<Pair<com.feedme.mealflow.reports.ReportState, FailureReason>?>(null) }
    val memberRemovalOwner = experience.circleMemberRemoval
    val memberRemovalState = memberRemovalOwner?.states?.collectAsState()?.value
    val memberRemovalVisible = memberRemovalState != null && memberRemovalState.screen != CircleMemberRemovalScreen.HIDDEN
    val hiddenMemberRemovalState = memberRemovalState?.takeIf { it.screen == CircleMemberRemovalScreen.HIDDEN }
    val transferOwner = experience.circleOwnershipTransfer
    val transferState = transferOwner?.states?.collectAsState()?.value
    val transferVisible = transferState != null && transferState.screen != CircleOwnershipTransferScreen.HIDDEN
    val hiddenTransferState = transferState?.takeIf { it.screen == CircleOwnershipTransferScreen.HIDDEN }
    val socialOwner = experience.socialReads
    val socialState = socialOwner?.states?.collectAsState()?.value
    val socialVisible = socialState != null && socialState.screen != SocialReadScreen.HIDDEN
    val hiddenSocialState = socialState?.takeIf { it.screen == SocialReadScreen.HIDDEN }
    val profileEditOwner = experience.profileEdit
    val profileEditState = profileEditOwner?.states?.collectAsState()?.value
    val profileEditVisible = profileEditState != null && profileEditState.phase != com.feedme.mealflow.profile.AccountProfileEditPhase.HIDDEN
    val hiddenProfileEditState = profileEditState?.takeIf { it.phase == com.feedme.mealflow.profile.AccountProfileEditPhase.HIDDEN }
    val notificationReadOwner = experience.notificationReads
    val notificationReadState = notificationReadOwner?.states?.collectAsState()?.value
    val notificationReadVisible = notificationReadState != null && notificationReadState.phase != com.feedme.mealflow.notifications.NotificationReadPhase.HIDDEN
    val hiddenNotificationReadState = notificationReadState?.takeIf { it.phase == com.feedme.mealflow.notifications.NotificationReadPhase.HIDDEN }
    val conversationOwner = experience.conversations
    val conversationState = conversationOwner?.states?.collectAsState()?.value
    val conversationVisible = conversationState != null && conversationState.phase != com.feedme.mealflow.conversation.ThreadPhase.HIDDEN
    val hiddenConversationState = conversationState?.takeIf { it.phase == com.feedme.mealflow.conversation.ThreadPhase.HIDDEN }
    val postDeletionOwner = experience.postDeletion
    val postDeletionState = postDeletionOwner?.states?.collectAsState()?.value
    val postDeletionVisible = postDeletionState != null && postDeletionState.phase != com.feedme.mealflow.postdeletion.PostDeletionPhase.HIDDEN
    val hiddenPostDeletionState = postDeletionState?.takeIf { it.phase == com.feedme.mealflow.postdeletion.PostDeletionPhase.HIDDEN }
    val postPlacementOwner = experience.postPlacement
    val postPlacementState = postPlacementOwner?.states?.collectAsState()?.value
    val postPlacementVisible = postPlacementState != null && postPlacementState.phase != com.feedme.mealflow.postplacement.PostPlacementPhase.HIDDEN
    val hiddenPostPlacementState = postPlacementState?.takeIf { it.phase == com.feedme.mealflow.postplacement.PostPlacementPhase.HIDDEN }
    var postPlacementFailure by remember(experience) { mutableStateOf<Pair<SocialReadState, FailureReason>?>(null) }
    val reactionOwner = experience.reactions
    val reactionState = reactionOwner?.states?.collectAsState()?.value
    val reactionVisible = reactionState != null && reactionState.phase != com.feedme.mealflow.reactions.ReactionPhase.HIDDEN
    val hiddenReactionState = reactionState?.takeIf { it.phase == com.feedme.mealflow.reactions.ReactionPhase.HIDDEN }
    var reactionFailure by remember(experience) { mutableStateOf<Pair<SocialReadState, FailureReason>?>(null) }
    val recipeRequestOwner = experience.recipeRequests
    val recipeRequestState = recipeRequestOwner?.states?.collectAsState()?.value
    val recipeRequestVisible = recipeRequestState != null && recipeRequestState.phase != com.feedme.mealflow.reciperequests.RecipeRequestPhase.HIDDEN
    val hiddenRecipeRequestState = recipeRequestState?.takeIf { it.phase == com.feedme.mealflow.reciperequests.RecipeRequestPhase.HIDDEN }
    val remixOwner = experience.remixes
    val remixState = remixOwner?.states?.collectAsState()?.value
    val remixVisible = remixState != null && remixState.phase != com.feedme.mealflow.remix.RemixTrailPhase.HIDDEN
    val hiddenRemixState = remixState?.takeIf { it.phase == com.feedme.mealflow.remix.RemixTrailPhase.HIDDEN }
    var remixOpening by remember(experience) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var remixOpenFailure by remember(experience) { mutableStateOf<Pair<com.feedme.mealflow.remix.RemixTrailState, FailureReason>?>(null) }
    DisposableEffect(experience) { onDispose { remixOpening?.cancel() } }
    var directPeoplePicker by remember(experience) { mutableStateOf<CircleDirectPeoplePicker?>(null) }
    var pickerEpoch by remember(experience) { mutableStateOf(Any()) }
    val pickerSnapshot = directPeoplePicker
    val pickerEpochSnapshot = pickerEpoch
    DisposableEffect(experience) { onDispose { directPeoplePicker?.close(); directPeoplePicker = null } }
    val memoryOwner = experience.mealMemory
    val memoryState = memoryOwner?.states?.collectAsState()?.value
    val memoryVisible = memoryState != null && memoryState.screen != com.feedme.mealflow.memory.MealMemoryScreen.HIDDEN
    val hiddenMemoryState = memoryState?.takeIf { it.screen == com.feedme.mealflow.memory.MealMemoryScreen.HIDDEN }
    val collectionOwner = experience.collections
    val collectionState = collectionOwner?.states?.collectAsState()?.value
    val collectionCommandState = collectionOwner?.commands?.states?.collectAsState()?.value
    val collectionVisible = collectionState != null && collectionState.screen != CollectionReadScreen.HIDDEN
    val hiddenCollectionState = collectionState?.takeIf { it.screen == CollectionReadScreen.HIDDEN }
    val currentSettings by rememberUpdatedState(onAccountSettings)
    val currentNotificationSettings by rememberUpdatedState(onNotificationSettings)
    // A hidden observation can never become current again after opening and returning.
    // This also fences a queued main/circle callback before recomposition catches up.
    val host = remember(experience, reportVisible, hiddenReportState, socialVisible, hiddenSocialState,
        memberRemovalVisible, hiddenMemberRemovalState, transferVisible, hiddenTransferState, memoryVisible, hiddenMemoryState,
        collectionVisible, hiddenCollectionState, conversationVisible, hiddenConversationState,
        postDeletionVisible, hiddenPostDeletionState, recipeRequestVisible, hiddenRecipeRequestState,
        remixVisible, hiddenRemixState, pickerSnapshot, pickerEpochSnapshot, notificationReadVisible, hiddenNotificationReadState,
        profileEditVisible, hiddenProfileEditState, memoryPreferenceVisit, blockVisible, hiddenBlockState,
        postPlacementVisible, hiddenPostPlacementState, reactionVisible, hiddenReactionState) {
        MealFlowHostActions {
            currentHost() && (reportsOwner == null || if (reportVisible)
                reportsOwner.states.value.screen != ReportScreen.HIDDEN
            else reportsOwner.states.value === hiddenReportState) &&
                (blockOwner == null || if (blockVisible) blockOwner.states.value.screen != com.feedme.mealflow.blocks.BlocksScreen.HIDDEN
                else blockOwner.states.value === hiddenBlockState) &&
                (socialOwner == null || if (socialVisible) socialOwner.states.value.screen != SocialReadScreen.HIDDEN
                else socialOwner.states.value === hiddenSocialState) &&
                (profileEditOwner == null || if (profileEditVisible)
                    profileEditOwner.states.value.phase != com.feedme.mealflow.profile.AccountProfileEditPhase.HIDDEN
                else profileEditOwner.states.value === hiddenProfileEditState) &&
                (notificationReadOwner == null || if (notificationReadVisible)
                    notificationReadOwner.states.value.phase != com.feedme.mealflow.notifications.NotificationReadPhase.HIDDEN
                else notificationReadOwner.states.value === hiddenNotificationReadState) &&
                (memberRemovalOwner == null || if (memberRemovalVisible)
                    memberRemovalOwner.states.value.screen != CircleMemberRemovalScreen.HIDDEN
                else memberRemovalOwner.states.value === hiddenMemberRemovalState) &&
                (transferOwner == null || if (transferVisible)
                    transferOwner.states.value.screen != CircleOwnershipTransferScreen.HIDDEN
                else transferOwner.states.value === hiddenTransferState) &&
                (memoryOwner == null || if (memoryVisible)
                    memoryOwner.states.value.screen != com.feedme.mealflow.memory.MealMemoryScreen.HIDDEN
                else memoryOwner.states.value === hiddenMemoryState) &&
                (collectionOwner == null || if (collectionVisible)
                    collectionOwner.states.value.screen != CollectionReadScreen.HIDDEN
                else collectionOwner.states.value === hiddenCollectionState) &&
                (conversationOwner == null || if (conversationVisible)
                    conversationOwner.states.value.phase != com.feedme.mealflow.conversation.ThreadPhase.HIDDEN
                else conversationOwner.states.value === hiddenConversationState) &&
                (postDeletionOwner == null || if (postDeletionVisible)
                    postDeletionOwner.states.value.phase != com.feedme.mealflow.postdeletion.PostDeletionPhase.HIDDEN
                else postDeletionOwner.states.value === hiddenPostDeletionState) &&
                (postPlacementOwner == null || if (postPlacementVisible)
                    postPlacementOwner.states.value.phase != com.feedme.mealflow.postplacement.PostPlacementPhase.HIDDEN
                else postPlacementOwner.states.value === hiddenPostPlacementState) &&
                (reactionOwner == null || if (reactionVisible)
                    reactionOwner.states.value.phase != com.feedme.mealflow.reactions.ReactionPhase.HIDDEN
                else reactionOwner.states.value === hiddenReactionState) &&
                (recipeRequestOwner == null || if (recipeRequestVisible)
                    recipeRequestOwner.states.value.phase != com.feedme.mealflow.reciperequests.RecipeRequestPhase.HIDDEN
                else recipeRequestOwner.states.value === hiddenRecipeRequestState) &&
                (remixOwner == null || if (remixVisible) remixOwner.states.value.phase != com.feedme.mealflow.remix.RemixTrailPhase.HIDDEN
                else remixOwner.states.value === hiddenRemixState) && directPeoplePicker === pickerSnapshot &&
                pickerEpoch === pickerEpochSnapshot && memoryPreferences === memoryPreferenceVisit
        }
    }
    DisposableEffect(host) { onDispose { host.retire() } }
    val guardedBackHandler: @Composable (Boolean, () -> Unit) -> Unit = { enabled, action ->
        platformBackHandler(enabled && host.isCurrent()) { host.run(action) }
    }
    // Event closures must retain the values rendered by this composition, not read
    // a local delegated State getter again when an old callback eventually runs.
    val meal = experience.meals.states.collectAsState().value
    // A status/busy update is not a new meal visit. Async Offline callbacks retain
    // this projection until the actual controller observation changes.
    val presentedMeal = remember(meal) { MealScreenState.from(meal) }
    val picker = experience.ingredients.states.collectAsState().value
    val form = experience.forms.collectAsState().value
    val landing = experience.blueprintLanding.states.collectAsState().value
    val interpretation = experience.interpretation.states.collectAsState().value
    val adaptationInterpretation = experience.adaptationInterpretation.states.collectAsState().value
    val adaptationForm = experience.adaptationForms.collectAsState().value
    val rootRecipeForm = experience.rootRecipeForms.collectAsState().value
    val catalogQuery = experience.catalogQuery.collectAsState().value
    val kitchen = experience.kitchen.states.collectAsState().value
    val kitchenPage by experience.kitchenPage.collectAsState()
    val cooking = experience.cooking.states.collectAsState().value
    val feedbackEntryReady = memoryOwner != null && CookingScreenState.from(cooking).let {
        it.done && it.serverAcknowledged && it.localPendingCount == 0 && it.pending.isEmpty()
    }
    val cookingNavigation = experience.cookingNavigation.collectAsState().value
    val cookbook = experience.cookbook.states.collectAsState().value
    val cookbookQuery = experience.cookbookQuery.collectAsState().value
    val savedCookingIntent = experience.savedCookingConfirmation.collectAsState().value
    val postSaveReview = experience.postSaveReview.collectAsState().value
    val timerOwner = experience.timerController.collectAsState().value
    val timerState = timerOwner?.states?.collectAsState()?.value
    val timerConfirmation = experience.timerConfirmation.collectAsState().value
    val timerAction = experience.captureTimerAction(timerOwner, timerState, cookingNavigation, timerConfirmation)
    val postDraftOwner = experience.postDrafts
    val postDraftState = postDraftOwner?.states?.collectAsState()?.value
    val circlesOwner = experience.circles
    val circlesState = circlesOwner?.states?.collectAsState()?.value
    val circleCreator = experience.circleCreate
    val circleCreateState = circleCreator?.states?.collectAsState()?.value
    val circleEditor = experience.circleEdit
    val circleLeaver = experience.circleLeave
    val circleLeaveState = circleLeaver?.states?.collectAsState()?.value
    val circleDeleter = experience.circleDelete
    val circleDeleteState = circleDeleter?.states?.collectAsState()?.value
    val circleEditState = circleEditor?.states?.collectAsState()?.value
    val invitationPreview = experience.invitationPreview
    val invitationPreviewState = invitationPreview?.states?.collectAsState()?.value
    val issuedOwner = experience.circleIssuedInvitations
    val issuedState = issuedOwner?.states?.collectAsState()?.value
    val invitationOwner = experience.circleInvitation
    val invitationState = invitationOwner?.states?.collectAsState()?.value
    val reviewedPostOwner = experience.reviewedPosts
    val reviewedPostNavigation by experience.reviewedPostsNavigation.collectAsState()
    val scope = rememberCoroutineScope()
    var removalEntry by remember(experience) { mutableStateOf<Any?>(null) }
    var removalEntryFailure by remember(experience) { mutableStateOf<Pair<CirclesState, FailureReason>?>(null) }
    var transferEntry by remember(experience) { mutableStateOf<Any?>(null) }
    var transferEntryFailure by remember(experience) { mutableStateOf<Pair<CirclesState, FailureReason>?>(null) }
    var socialReportFailure by remember(experience) { mutableStateOf<Pair<com.feedme.mealflow.social.SocialReadState, FailureReason>?>(null) }
    var socialRecipeFailure by remember(experience) { mutableStateOf<Pair<com.feedme.mealflow.social.SocialReadState, String>?>(null) }
    var socialProfileFailure by remember(experience) { mutableStateOf<Pair<com.feedme.mealflow.social.SocialReadState, String>?>(null) }
    var openingCircle by remember(experience) { mutableStateOf<CircleCreateState?>(null) }
    var circleOpenFailure by remember(experience) { mutableStateOf<Pair<CircleCreateState, FailureReason>?>(null) }
    var editHandoff by remember(experience) { mutableStateOf<CircleEditHandoff?>(null) }
    var editFailure by remember(experience) { mutableStateOf<Pair<CircleEditState, FailureReason>?>(null) }
    var editEntry by remember(experience) { mutableStateOf<CircleEditEntry?>(null) }
    var editEntryFailure by remember(experience) { mutableStateOf<Pair<CirclesState, FailureReason>?>(null) }
    var leaveEntry by remember(experience) { mutableStateOf<CircleLeaveEntry?>(null) }
    var leaveEntryFailure by remember(experience) { mutableStateOf<Pair<CirclesState, FailureReason>?>(null) }
    var deleteEntry by remember(experience) { mutableStateOf<CircleLeaveEntry?>(null) }
    var deleteEntryFailure by remember(experience) { mutableStateOf<Pair<CirclesState, FailureReason>?>(null) }
    var reportEntry by remember(experience) { mutableStateOf<ReportEntry?>(null) }
    var reportEntryFailure by remember(experience) { mutableStateOf<Pair<CirclesState, FailureReason>?>(null) }
    var invitationHandoff by remember(experience) { mutableStateOf<InvitationHandoff?>(null) }
    var invitationFailure by remember(experience) { mutableStateOf<Pair<CircleInvitationState, FailureReason>?>(null) }
    var invitationEntry by remember(experience) { mutableStateOf<InvitationEntry?>(null) }
    var invitationEntryFailure by remember(experience) { mutableStateOf<Pair<CircleInvitationPreviewState, FailureReason>?>(null) }
    var invitationResume by remember(experience) { mutableStateOf<InvitationResume?>(null) }
    var invitationResumeFailure by remember(experience) { mutableStateOf<Pair<CirclesState, FailureReason>?>(null) }
    var issuedEntry by remember(experience) { mutableStateOf<IssuedInvitationEntry?>(null) }
    var issuedEntryFailure by remember(experience) { mutableStateOf<Pair<CirclesState, FailureReason>?>(null) }
    var issuedHandoff by remember(experience) { mutableStateOf<IssuedInvitationHandoff?>(null) }
    var issuedFailure by remember(experience) { mutableStateOf<Pair<CircleIssuedInvitationsState, FailureReason>?>(null) }
    var exitIntent by remember(experience) { mutableStateOf<MealExitIntent?>(null) }
    var savedCookingFailure by remember(experience) { mutableStateOf<Pair<CookbookState, FailureReason>?>(null) }
    var savedMakeMineFailure by remember(experience) { mutableStateOf<Pair<CookbookState, FailureReason>?>(null) }
    var savedTabFailure by remember(experience) { mutableStateOf<Pair<CookbookState, String>?>(null) }
    var savedShareFailure by remember(experience) { mutableStateOf<Pair<CookbookState, String>?>(null) }
    var threadReportFailure by remember(experience) {
        mutableStateOf<Pair<com.feedme.mealflow.conversation.ThreadState, FailureReason>?>(null)
    }
    var completionNavigationFailure by remember(experience) { mutableStateOf<Pair<com.feedme.mealflow.CookingFlowState, String>?>(null) }
    var circlesTabFailure by remember(experience) { mutableStateOf<Pair<CirclesState, String>?>(null) }
    var reuseOpening by remember(experience) { mutableStateOf<com.feedme.mealflow.memory.MealMemoryState?>(null) }
    var reuseOpenFailure by remember(experience) { mutableStateOf<Pair<com.feedme.mealflow.memory.MealMemoryState, FailureReason>?>(null) }
    var memoryNavigationOpening by remember(experience) { mutableStateOf<com.feedme.mealflow.memory.MealMemoryState?>(null) }
    var memorySavedFailure by remember(experience) { mutableStateOf<Pair<com.feedme.mealflow.memory.MealMemoryState, FailureReason>?>(null) }
    var memorySocialFailure by remember(experience) { mutableStateOf<Pair<com.feedme.mealflow.memory.MealMemoryState, FailureReason>?>(null) }
    var memoryPreferencesFailure by remember(experience) { mutableStateOf<Pair<com.feedme.mealflow.memory.MealMemoryState, FailureReason>?>(null) }
    var collectionOpening by remember(experience) { mutableStateOf<CollectionReadState?>(null) }
    var collectionOpenFailure by remember(experience) { mutableStateOf<Pair<CollectionReadState, FailureReason>?>(null) }
    // Returning from reporting must not reopen cooking journals or perform unrelated I/O.
    LaunchedEffect(experience) { if (!reportVisible && !blockVisible && !memberRemovalVisible && !transferVisible && !socialVisible && !profileEditVisible && !memoryVisible && !collectionVisible && !conversationVisible && !postDeletionVisible && !postPlacementVisible && !reactionVisible && !recipeRequestVisible && !remixVisible && pickerSnapshot == null) host.await { experience.restore() } }
    LaunchedEffect(experience, host, timerOwner) {
        while (isActive && host.isCurrent()) {
            delay(1000)
            if (host.isCurrent() && timerOwner?.states?.value?.visible == true) host.await { experience.tickTimers(timerOwner) }
        }
    }
    if (!host.isCurrent()) {
        if (!currentHost()) {
            // Losing account authority must redact private content, not leave a blank,
            // inescapable page. Native onExit still checks the exact route/attachment;
            // this local departure does not send logout or discard queued originals.
            BlueprintRetiredMealScreen(isRetired = { !currentHost() }, onExit = onExit,
                platformBackHandler = platformBackHandler)
        } else guardedBackHandler(false) {}
        return
    }
    if (memoryPreferenceVisit != null) {
        val childCurrent = { host.isCurrent() && memoryPreferences === memoryPreferenceVisit && memoryPreferenceVisit.current() }
        // Close only this visit, even after private authority is lost. The actual
        // Memory owner (including an expired observation) remains underneath.
        val closeChild = { if (memoryPreferences === memoryPreferenceVisit) {
                memoryPreferences = null
                // This is only the entry GET, never the borrowed editor's save/retry job.
                memoryPreferenceVisit.initialRead.cancel()
            }; Unit }
        if (memoryPreferenceVisit.personalization) FeedMePersonalizationPauseFlow(experience,
            hostIsCurrent = childCurrent, initialLoaded = memoryPreferenceVisit.initialLoaded,
            initialFailure = memoryPreferenceVisit.initialFailure, onClose = closeChild,
            platformBackHandler = platformBackHandler)
        else FeedMeKitchenPreferencesFlow(experience, BlueprintPreferencePage.FOOD_PREFS, preferenceChoices,
            hostIsCurrent = childCurrent, onClose = closeChild,
            platformBackHandler = platformBackHandler, returnLabel = "Memory")
        return
    }
    if (profileEditOwner != null && profileEditVisible && profileEditState != null &&
        socialOwner != null && socialState?.screen == SocialReadScreen.PROFILE_PLATE) {
        FeedMeAccountProfileEditFlow(profileEditOwner,
            // Feed expiry is not editor expiry. The editor uses its own fresh account
            // observation; only the account/route/child lifetime is retained here.
            hostIsCurrent = { host.isCurrent() && socialOwner.states.value.screen == SocialReadScreen.PROFILE_PLATE },
            onClose = {
                if (host.isCurrent() && socialOwner.states.value.screen == SocialReadScreen.PROFILE_PLATE) {
                    // A discard confirmation may have just advanced the editor state
                    // before Compose redraws. Capture that actual departure, not an old render.
                    val closing = profileEditOwner.states.value
                    val returning = socialOwner.states.value
                    host.launch(scope) { experience.closeSocialProfileEdit(closing, returning) }
                }
            }, platformBackHandler = platformBackHandler, returnLabel = "My Plate")
        return
    }
    if (pickerSnapshot != null) {
        FeedMeCircleDirectPeoplePicker(pickerSnapshot,
            onSelected = { selection ->
                if (host.isCurrent() && directPeoplePicker === pickerSnapshot && selection.isCurrentForNavigation && conversationOwner != null) scope.launch {
                    if (currentHost() && directPeoplePicker === pickerSnapshot && selection.isCurrentForNavigation) {
                        val recipient = selection.recipientUserId
                        // Use ends the picker, not the subsequent conversation review.
                        // The real thread owner immediately owns its loading/Back lifecycle.
                        pickerSnapshot.close(); directPeoplePicker = null; pickerEpoch = Any()
                        conversationOwner.prepareDirect(recipient)
                    }
                }
            },
            onCancel = { if (directPeoplePicker === pickerSnapshot) { directPeoplePicker = null; pickerEpoch = Any() } },
            platformBackHandler = guardedBackHandler)
        return
    }
    if (remixOwner != null && remixVisible && remixState != null) {
        FeedMeRemixTrailFlow(remixOwner, hostIsCurrent = host::isCurrent,
            onClose = { remixOpening?.cancel(); remixOpenFailure = null },
            onOpenPost = { expected, selection ->
                if (host.isCurrent() && remixOwner.isCurrent(expected) && socialOwner != null && remixOpening == null) {
                    val source = socialOwner.states.value
                    val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        try {
                            if (!currentHost() || !remixOwner.isCurrent(expected) || !socialOwner.isCurrent(source)) return@launch
                            remixOpenFailure = null
                            val result = socialOwner.openRemixPost(selection, source)
                            if (!currentHost() || !remixOwner.isCurrent(expected)) return@launch
                            when (result) {
                                is PortResult.Value -> if (socialOwner.isCurrent(result.value)) remixOwner.leave(expected)
                                is PortResult.Failure -> remixOpenFailure = expected to result.reason
                            }
                        } finally { remixOpening = null }
                    }
                    remixOpening = job; job.start()
                }
            }, platformBackHandler = platformBackHandler,
            openPostFailure = remixOpenFailure?.takeIf { it.first === remixState }?.second,
            postOpening = remixOpening != null)
        return
    }
    if (recipeRequestOwner != null && recipeRequestVisible && recipeRequestState != null) {
        FeedMeRecipeRequestFlow(recipeRequestOwner, hostIsCurrent = host::isCurrent,
            onClose = {}, platformBackHandler = guardedBackHandler,
            onOpenThread = if (conversationOwner == null) null else ({ threadId ->
                val hidden = recipeRequestOwner.states.value
                if (currentHost() && hidden.phase == com.feedme.mealflow.reciperequests.RecipeRequestPhase.HIDDEN) scope.launch {
                    if (currentHost() && recipeRequestOwner.isCurrent(hidden)) conversationOwner.open(threadId)
                }
            }))
        return
    }
    if (reactionOwner != null && reactionVisible && reactionState != null) {
        FeedMeReactionFlow(reactionOwner, hostIsCurrent = host::isCurrent,
            platformBackHandler = guardedBackHandler,
            onClose = {
                if (host.isCurrent() && reactionOwner.states.value === reactionState)
                    host.launch(scope) { experience.closeReaction(reactionState) }
            })
        return
    }
    if (postPlacementOwner != null && postPlacementVisible && postPlacementState != null) {
        FeedMePostPlacementFlow(postPlacementOwner, hostIsCurrent = host::isCurrent,
            platformBackHandler = guardedBackHandler,
            onClose = {
                if (host.isCurrent() && postPlacementOwner.states.value === postPlacementState)
                    host.launch(scope) { experience.closePostPlacement(postPlacementState) }
            })
        return
    }
    if (postDeletionOwner != null && postDeletionVisible && postDeletionState != null) {
        FeedMePostDeletionFlow(postDeletionOwner, hostIsCurrent = host::isCurrent,
            onClose = {}, platformBackHandler = guardedBackHandler,
            onDeleted = {
                // The child has already hidden itself; its prior host is intentionally
                // retired. A verified-ACK callback already redacted every social snapshot
                // before COMPLETE. This explicit departure may now read a fresh My Plate.
                val hidden = postDeletionOwner.states.value
                val cleared = socialOwner?.states?.value
                if (currentHost() && hidden.phase == com.feedme.mealflow.postdeletion.PostDeletionPhase.HIDDEN &&
                    socialOwner != null && cleared != null && socialOwner.isCurrent(cleared)) scope.launch {
                    if (currentHost() && postDeletionOwner.isCurrent(hidden) && socialOwner.isCurrent(cleared))
                        socialOwner.openProfilePlate()
                }
            })
        return
    }
    // Reports may overlay an independently retained Thread. Its expiry/recovery must not
    // mask the report owner or cause Back to reconstruct a private message observation.
    if (blockOwner != null && blockVisible && blockState != null) {
        com.feedme.app.blocks.FeedMeBlocksFlow(blockOwner,
            platformBackHandler = guardedBackHandler, hostIsCurrent = host::isCurrent,
            onClose = {
                if (host.isCurrent() && blockOwner.states.value === blockState)
                    host.launch(scope) { experience.closeReportBlock(blockState) }
            })
        return
    }
    if (reportsOwner != null && reportVisible) {
        FeedMeReportFlow(reportsOwner, experience.reportFormMemory, onExit = {},
            platformBackHandler = guardedBackHandler, hostIsCurrent = host::isCurrent,
            reporterAccountId = experience.reporterAccountId, blueprintIsCurrent = host::isCurrent,
            onBlock = if (blockOwner == null) null else ({ expected, presentationCurrent ->
                if (presentationCurrent()) host.launch(scope) {
                    reportBlockFailure = null
                    val result = experience.openReportBlock(expected, presentationCurrent)
                    if (result is PortResult.Failure && host.isCurrent() && reportsOwner.states.value === expected)
                        reportBlockFailure = expected to result.reason
                }
                Unit
            }), blockOpenStatus = reportBlockFailure?.takeIf { it.first === reportState }?.let {
                "Blocking could not be opened. Your report receipt is kept; no block request was sent by this action."
            })
        return
    }
    if (conversationOwner != null && conversationVisible && conversationState != null) {
        FeedMeThreadFlow(conversationOwner, hostIsCurrent = host::isCurrent,
            onClose = {}, platformBackHandler = guardedBackHandler,
            onOpenRecipeRequest = if (recipeRequestOwner == null) null else ({ expected, requestId ->
                host.launch(scope) {
                    if (conversationOwner.canOpenRecipeRequest(expected, requestId)) recipeRequestOwner.openRequest(requestId)
                }
                Unit
            }),
            onReportMessage = if (reportsOwner == null) null else ({ expected, message, presentationCurrent ->
                if (presentationCurrent()) host.launch(scope) {
                    if (presentationCurrent()) {
                        threadReportFailure = null
                        val result = experience.reportThreadMessage(expected, message, presentationCurrent)
                        if (result is PortResult.Failure && host.isCurrent() && conversationOwner.states.value === expected)
                            threadReportFailure = expected to result.reason
                    }
                }
                Unit
            }),
            reportEntryFailure = threadReportFailure?.takeIf { it.first === conversationState }?.second)
        return
    }
    if (collectionOwner != null && collectionVisible && collectionState != null && collectionCommandState != null) {
        val openingSaved = collectionOpening === collectionState
        fun collectionBack() {
            if (!host.isCurrent() || collectionOwner.states.value !== collectionState) return
            host.launch(scope) {
                if (collectionOwner.states.value !== collectionState) return@launch
                experience.backFromCollections(collectionState)
            }
        }
        BlueprintCollectionFlow(collectionOwner, collectionState, collectionCommandState,
            busy = form.busy || openingSaved,
            isCurrent = { host.isCurrent() && collectionOwner.isCurrent(collectionState) },
            onAction = { operation ->
                host.launch(scope) {
                    if (!openingSaved && collectionOwner.states.value === collectionState && collectionOwner.isCurrent(collectionState)) operation()
                }
            }, onBack = ::collectionBack,
            onOpenSaved = { expected, selection ->
                if (host.isCurrent() && !openingSaved && !form.busy && expected === collectionState &&
                    collectionOwner.canLeaveForSaved(expected) && collectionOwner.isCurrent(selection)) {
                    collectionOpening = expected; collectionOpenFailure = null
                    host.launch(scope) {
                        try {
                            val result = experience.openCollectionSaved(expected, selection, cookbook, cookbookQuery, meal, form)
                            if (host.isCurrent() && result is PortResult.Failure && collectionOwner.states.value === expected)
                                collectionOpenFailure = expected to result.reason
                        } finally { if (collectionOpening === expected) collectionOpening = null }
                    }
                }
                Unit
            }, savedChoices = cookbook.items,
            openSavedStatus = collectionOpenFailure?.takeIf { it.first === collectionState }?.second?.let(::mealFailureText),
            platformBackHandler = guardedBackHandler)
        return
    }
    if (memoryOwner != null && memoryVisible && memoryState != null) {
        val memoryBusy = memoryState.phase in setOf(com.feedme.mealflow.memory.MealMemoryPhase.LOADING,
            com.feedme.mealflow.memory.MealMemoryPhase.WORKING) || reuseOpening === memoryState || memoryNavigationOpening === memoryState
        fun openMemoryPreferenceReview(expected: com.feedme.mealflow.memory.MealMemoryState,
            presentationCurrent: () -> Boolean, personalization: Boolean) {
            if (host.isCurrent() && !memoryBusy && memoryNavigationOpening !== expected &&
                expected === memoryState && memoryPreferences == null && presentationCurrent()) scope.launch {
                if (!host.isCurrent() || memoryNavigationOpening === expected || !presentationCurrent()) return@launch
                memoryPreferencesFailure = null
                val initialRead = checkNotNull(currentCoroutineContext()[Job])
                var claimed: MemoryPreferenceVisit? = null
                val result = experience.openMemoryPreferences(expected, cookbook, cookbookQuery, meal, form,
                    cooking, cookingNavigation, presentationCurrent) { continuation ->
                    if (!host.isCurrent() || memoryPreferences != null || memoryNavigationOpening === expected ||
                        !presentationCurrent()) null else {
                        val visit = MemoryPreferenceVisit(continuation, initialRead, personalization)
                        claimed = visit; memoryPreferences = visit
                        val active = { currentHost() && memoryPreferences === visit && visit.current() }
                        active
                    }
                }
                claimed?.takeIf { memoryPreferences === it && currentHost() && it.current() }?.let { visit ->
                    visit.initialLoaded = result is PortResult.Value && !result.value.preferencesHistorical &&
                        result.value.failureReason == null && result.value.phase != KitchenInputPhase.OFFLINE &&
                        experience.kitchen.states.value === result.value
                    visit.initialFailure = (result as? PortResult.Failure)?.reason ?: if (visit.initialLoaded) null else FailureReason.CONFLICT
                }
                if (result is PortResult.Failure && host.isCurrent() && memoryOwner.states.value === expected)
                    memoryPreferencesFailure = expected to result.reason
            }
        }
        fun memoryBack() {
            if (!host.isCurrent() || memoryOwner.states.value !== memoryState || memoryNavigationOpening === memoryState) return
            host.launch(scope) {
                if (memoryNavigationOpening === memoryState) return@launch
                if (!memoryBusy && memoryOwner.isCurrent(memoryState) &&
                    memoryState.phase != com.feedme.mealflow.memory.MealMemoryPhase.UNAVAILABLE &&
                    (memoryState.review != null || memoryState.screen == com.feedme.mealflow.memory.MealMemoryScreen.MEMORY_DETAIL))
                    memoryOwner.back(memoryState)
                else memoryOwner.leave(memoryState)
            }
        }
        BlueprintMealMemoryFlow(memoryOwner, memoryState, busy = memoryBusy,
            isCurrent = { host.isCurrent() && memoryOwner.isCurrent(memoryState) &&
                memoryState.phase != com.feedme.mealflow.memory.MealMemoryPhase.UNAVAILABLE },
            onAction = { operation ->
                host.launch(scope) {
                    if (!memoryBusy && memoryNavigationOpening !== memoryState && memoryOwner.states.value === memoryState && memoryOwner.isCurrent(memoryState) &&
                        memoryState.phase != com.feedme.mealflow.memory.MealMemoryPhase.UNAVAILABLE) operation()
                }
            }, onBack = ::memoryBack,
            onCookbook = if (!experience.memorySavedAvailable(memoryState, cookbook, cookbookQuery, meal, form,
                cooking, cookingNavigation)) null else ({ expected, presentationCurrent ->
                if (host.isCurrent() && !memoryBusy && memoryNavigationOpening == null && expected === memoryState && presentationCurrent()) {
                    memoryNavigationOpening = expected; memorySavedFailure = null
                    // Cleanup also runs when the host retires before queued admission.
                    // After Memory leaves, its old host is intentionally no longer current.
                    scope.launch {
                        try {
                            if (!host.isCurrent() || !presentationCurrent()) return@launch
                            val result = experience.openMemorySaved(expected, cookbook, cookbookQuery, meal, form,
                                cooking, cookingNavigation, presentationCurrent)
                            if (result is PortResult.Failure && host.isCurrent() && memoryOwner.states.value === expected)
                                memorySavedFailure = expected to result.reason
                        } finally { if (memoryNavigationOpening === expected) memoryNavigationOpening = null }
                    }
                }
                Unit
            }),
            cookbookOpenStatus = memorySavedFailure?.takeIf { it.first === memoryState }?.second?.let(::mealFailureText),
            onSocial = if (memoryPreferences != null || !experience.canOpenMemorySocial(BlueprintScreenId.TODAY,
                memoryState, cookbook, cookbookQuery, meal, form, cooking, cookingNavigation)) null
                else ({ destination, expected, presentationCurrent ->
                    if (host.isCurrent() && !memoryBusy && memoryNavigationOpening == null && memoryPreferences == null &&
                        expected === memoryState && presentationCurrent()) {
                        memoryNavigationOpening = expected; memorySocialFailure = null
                        // The actual hidden successor owns the handoff after Memory departs;
                        // this raw UI predicate ends at that intentional replacement.
                        scope.launch {
                            try {
                                if (!host.isCurrent() || !presentationCurrent()) return@launch
                                val result = experience.openMemorySocial(destination, expected, cookbook, cookbookQuery,
                                    meal, form, cooking, cookingNavigation, presentationCurrent)
                                if (result is PortResult.Failure && host.isCurrent() && memoryOwner.states.value === expected)
                                    memorySocialFailure = expected to result.reason
                            } finally { if (memoryNavigationOpening === expected) memoryNavigationOpening = null }
                        }
                    }
                    Unit
                }),
            socialOpenStatus = memorySocialFailure?.takeIf { it.first === memoryState }?.second?.let(::mealFailureText),
            onPreferences = if (preferenceChoices == null || !experience.accountKitchenPreferencesAvailable ||
                !experience.memorySavedAvailable(memoryState, cookbook, cookbookQuery, meal, form, cooking, cookingNavigation))
                null else ({ expected, presentationCurrent ->
                    openMemoryPreferenceReview(expected, presentationCurrent, personalization = false)
                }),
            preferencesOpenStatus = memoryPreferencesFailure?.takeIf { it.first === memoryState }?.second?.let(::mealFailureText),
            onPausePersonalization = if (!experience.accountKitchenPreferencesAvailable ||
                !experience.memorySavedAvailable(memoryState, cookbook, cookbookQuery, meal, form, cooking, cookingNavigation)) null
                else ({ expected, presentationCurrent -> openMemoryPreferenceReview(expected, presentationCurrent, personalization = true) }),
            personalizationPreferences = personalizationReference(kitchen),
            personalizationPaused = observedPersonalization(kitchen)?.not(),
            personalizationStatus = when (observedPersonalization(kitchen)) {
                false -> "Last checked: learned suggestions paused. Saved meals and dietary choices are unchanged."
                true -> "Pause learned suggestions checks your current setting before asking you to confirm."
                null -> "Check your learned-suggestion setting with Pause learned suggestions."
            },
            ingredientLabels = MealPickerPresentation.from(picker).knownIngredients.associate { it.id to it.name },
            onOpenReuse = { expected, selection ->
                if (host.isCurrent() && !memoryBusy && expected === memoryState && memoryOwner.isCurrent(selection)) {
                    reuseOpening = expected; reuseOpenFailure = null
                    host.launch(scope) {
                        try {
                            val result = experience.openReuseRecipe(expected, selection, meal, form, cooking, cookingNavigation)
                            if (host.isCurrent() && result is PortResult.Failure && memoryOwner.states.value === expected)
                                reuseOpenFailure = expected to result.reason
                        } finally { if (reuseOpening === expected) reuseOpening = null }
                    }
                }
                Unit
            }, reuseOpenStatus = reuseOpenFailure?.takeIf { it.first === memoryState }?.second?.let(::mealFailureText),
            departureAllowed = { host.isCurrent() && memoryOwner.states.value === memoryState &&
                reuseOpening !== memoryState && memoryNavigationOpening !== memoryState },
            platformBackHandler = platformBackHandler)
        return
    }
    if (transferOwner != null && transferVisible && transferState != null) {
        FeedMeCircleOwnershipTransferFlow(transferOwner,
            onClose = {
                if (host.isCurrent() && transferOwner.states.value === transferState)
                    host.launch(scope) { transferOwner.leave(transferState) }
            }, platformBackHandler = guardedBackHandler, hostIsCurrent = host::isCurrent)
        return
    }
    if (memberRemovalOwner != null && memberRemovalVisible && memberRemovalState != null) {
        FeedMeCircleMemberRemovalFlow(memberRemovalOwner,
            onClose = {
                if (host.isCurrent() && memberRemovalOwner.states.value === memberRemovalState)
                    host.launch(scope) { memberRemovalOwner.leave(memberRemovalState) }
            }, platformBackHandler = guardedBackHandler, hostIsCurrent = host::isCurrent)
        return
    }
    if (postSaveReview != null) {
        FeedMePostSaveReview(experience, postSaveReview, form, host::isCurrent,
            onConfirm = { host.launch(scope) { experience.confirmSocialSave(postSaveReview) } },
            onCancel = { host.run { experience.cancelSocialSave(postSaveReview) } },
            platformBackHandler = guardedBackHandler)
        return
    }
    if (socialOwner != null && socialVisible && socialState != null) {
        // Carry the child's exact local visit through the last queue, not just the click.
        var dispatchGuard: (() -> Boolean)? = null
        fun launchSocial(action: suspend () -> Unit) {
            val continuation = dispatchGuard ?: return
            if (continuation()) host.launch(scope) { experience.dispatchSocialPresentation(continuation, action) }
        }
        fun depart(destination: BlueprintScreenId) {
            launchSocial { experience.leaveSocialFor(socialState, destination) }
        }
        FeedMeSocialReadingFlow(socialOwner, hostIsCurrent = { host.isCurrent() && !experience.forms.value.busy && experience.postSaveReview.value == null },
            homeAvailable = experience.socialCookAvailable,
            dispatchHost = { continuation, callback ->
                if (continuation()) {
                    val previous = dispatchGuard
                    dispatchGuard = continuation
                    try { callback() } finally { dispatchGuard = previous }
                }
            },
            onEditProfile = if (!experience.canOpenSocialProfileEdit(socialState)) null else ({ expected ->
                launchSocial {
                    socialProfileFailure = null
                    val result = experience.openSocialProfileEdit(expected)
                    if (result is PortResult.Failure && host.isCurrent() && socialOwner.states.value === expected)
                        socialProfileFailure = expected to "Couldn’t open profile editing. Refresh My Plate and try again. Existing edits and save recovery are unchanged."
                }; Unit
            }),
            profileActionStatus = socialProfileFailure?.takeIf { it.first === socialState }?.second,
            onMakeMine = if (!meal.postMakeMineAvailable) null else ({ expected ->
                launchSocial {
                    socialRecipeFailure = null
                    val result = experience.openSocialMakeMine(expected)
                    if (result is PortResult.Failure && host.isCurrent() && socialOwner.states.value === expected)
                        socialRecipeFailure = expected to if (experience.forms.value.dirty)
                            "Save or finish your meal draft in Cook before making a version. Your edits are unchanged."
                        else "Couldn’t open this recipe. Refresh the post and try again; your current meal is unchanged."
                }; Unit
            }),
            onSaveRecipe = { expected -> launchSocial {
                socialRecipeFailure = null
                val result = experience.prepareSocialSave(expected)
                if (result is PortResult.Failure && host.isCurrent() && socialOwner.states.value === expected)
                    socialRecipeFailure = expected to "Couldn’t prepare this save. Check Saved for an earlier pending action, or refresh this post. No new save was sent."
            }; Unit },
            recipeActionStatus = socialRecipeFailure?.takeIf { it.first === socialState }?.second,
            onNotificationSettings = if (onNotificationSettings == null) null else ({
                // Native navigation retains this Inbox owner. Returning does not recreate
                // a notification read, mark anything read, or extend its observation expiry.
                host.run { currentNotificationSettings?.invoke() }
            }),
            onHome = { depart(BlueprintScreenId.HOME) },
            onSaved = { depart(BlueprintScreenId.COOKBOOK) },
            onSettings = if (onAccountSettings == null) null else ({
                launchSocial {
                    val result = experience.leaveSocial(socialState)
                    if (result is PortResult.Value && currentHost()) currentSettings?.invoke()
                }
            }),
            onCircles = if (circlesOwner == null || cookingNavigation.visible || experience.socialMemoryReturnActive) null else ({ depart(BlueprintScreenId.CIRCLES) }),
            onCreatePost = if (postDraftOwner == null || cookingNavigation.visible || experience.socialMemoryReturnActive) null else ({ depart(BlueprintScreenId.CAPTURE) }),
            onReportPost = if (reportsOwner == null) null else ({ expected ->
                launchSocial {
                    socialReportFailure = null
                    val result = experience.reportSocialPost(expected)
                    if (result is PortResult.Failure && host.isCurrent() && socialOwner.states.value === expected)
                        socialReportFailure = expected to result.reason
                }
                Unit
            }),
            reportFailure = socialReportFailure?.takeIf { it.first === socialState }?.second,
            onReaction = if (reactionOwner == null) null else ({ expected, post, remove, presentationCurrent ->
                if (presentationCurrent()) host.launch(scope) {
                    reactionFailure = null
                    val result = experience.openReaction(expected, post, remove, presentationCurrent)
                    if (result is PortResult.Failure && host.isCurrent() && socialOwner.states.value === expected)
                        reactionFailure = expected to result.reason
                }
                Unit
            }),
            onResumeReaction = if (reactionOwner == null) null else ({ expected, presentationCurrent ->
                if (presentationCurrent()) host.launch(scope) {
                    reactionFailure = null
                    val result = experience.resumeReaction(expected, presentationCurrent)
                    if (result is PortResult.Failure && host.isCurrent() && socialOwner.states.value === expected)
                        reactionFailure = expected to result.reason
                }
                Unit
            }),
            reactionStatus = reactionFailure?.takeIf { it.first === socialState }?.let {
                "Reaction review could not be opened. No reaction was sent by this action; any retained original is kept."
            },
            onPostPlacement = if (postPlacementOwner == null) null else ({ expected, post, keep, presentationCurrent ->
                if (presentationCurrent()) host.launch(scope) {
                    postPlacementFailure = null
                    val result = experience.openPostPlacement(expected, post, keep, presentationCurrent)
                    if (result is PortResult.Failure && host.isCurrent() && socialOwner.states.value === expected)
                        postPlacementFailure = expected to result.reason
                }
                Unit
            }),
            onResumePostPlacement = if (postPlacementOwner == null) null else ({ expected, presentationCurrent ->
                if (presentationCurrent()) host.launch(scope) {
                    postPlacementFailure = null
                    val result = experience.resumePostPlacement(expected, presentationCurrent)
                    if (result is PortResult.Failure && host.isCurrent() && socialOwner.states.value === expected)
                        postPlacementFailure = expected to result.reason
                }
                Unit
            }),
            postPlacementStatus = postPlacementFailure?.takeIf { it.first === socialState }?.let {
                "Plate review could not be opened. This action sent no placement change; any retained original is kept."
            },
            onRemixTrail = if (remixOwner == null) null else ({ expected ->
                launchSocial {
                    val post = expected.selectedPost
                    if (socialOwner.isCurrent(expected) && expected.screen == SocialReadScreen.POST &&
                        expected.phase == SocialReadPhase.READY && post != null) remixOwner.open(post.id)
                }
                Unit
            }),
            onAskRecipe = if (recipeRequestOwner == null) null else ({ expected ->
                launchSocial {
                    val post = expected.selectedPost
                    val capabilities = (post?.document?.field("capabilities") as? WireField.Value)?.value
                    if (socialOwner.isCurrent(expected) && expected.screen in setOf(SocialReadScreen.POST, SocialReadScreen.STORY) && post != null &&
                        capabilities?.elementsOrNull()?.any { it.stringOrNull() == "askRecipe" } == true)
                        recipeRequestOwner.openPost(post.id)
                }
                Unit
            }),
            onDeletePost = if (postDeletionOwner == null) null else ({ expected ->
                launchSocial {
                    val post = expected.selectedPost
                    val author = (post?.document?.field("author") as? WireField.Value)?.value
                    val self = (expected.selfProfile?.field("id") as? WireField.Value)?.value?.stringOrNull()
                    val capabilities = (post?.document?.field("capabilities") as? WireField.Value)?.value
                    if (socialOwner.isCurrent(expected) && expected.screen == SocialReadScreen.POST && post != null &&
                        self != null && (author?.field("userId") as? WireField.Value)?.value?.stringOrNull() == self &&
                        capabilities?.elementsOrNull()?.any { it.stringOrNull() == "delete" } == true)
                        postDeletionOwner.open(post.id)
                }
                Unit
            }),
            onOpenThread = if (conversationOwner == null) null else ({ expected, id ->
                launchSocial {
                    if (socialOwner.isCurrent(expected) && expected.screen == SocialReadScreen.INBOX &&
                        expected.threads.any { (it.field("id") as? WireField.Value)?.value?.stringOrNull() == id }) conversationOwner.open(id)
                }
                Unit
            }),
            notificationReads = notificationReadOwner,
            onOpenNotificationThread = if (conversationOwner == null) null else ({ expected, id ->
                launchSocial { experience.openNotificationThread(expected, id) }; Unit
            }),
            onStartDirect = if (conversationOwner == null) null else ({ expected, recipient ->
                launchSocial {
                    val post = expected.selectedPost
                    val author = (post?.document?.field("author") as? WireField.Value)?.value
                    val capabilities = (post?.document?.field("capabilities") as? WireField.Value)?.value
                    if (socialOwner.isCurrent(expected) && post != null &&
                        (author?.field("userId") as? WireField.Value)?.value?.stringOrNull() == recipient &&
                        capabilities?.elementsOrNull()?.any { it.stringOrNull() == "reply" } == true)
                        conversationOwner.prepareDirect(recipient)
                }
                Unit
            }),
            onClose = { launchSocial { experience.returnFromSocial(socialState) } }, platformBackHandler = guardedBackHandler)
        return
    }
    fun leave(from: MealFlowScreen) {
        if (!host.isCurrent()) return
        if (from in ROOT_RECIPE_SCREENS) {
            if (meal.screen == from) host.launch(scope) { experience.backCatalog(meal) }
        } else if (from in setOf(MealFlowScreen.ADAPT_MINE, MealFlowScreen.VARIANT_MINE)) {
            if (meal.screen != from) return
            val expected = meal
            host.launch(scope) { experience.backAdaptation(expected) }
        } else if (from == MealFlowScreen.REQUEST) onExit() else host.launch(scope) { experience.back(from) }
    }
    fun back() {
        if (!host.isCurrent()) return
        // Saved owns a separate rendered Back below. A callback from an older main,
        // timer or kitchen view must never borrow a newer Saved selection or dialog.
        if (cookbook.screen != CookbookScreen.HIDDEN || experience.cookbook.states.value.screen != CookbookScreen.HIDDEN) return
        if (experience.savedCookingConfirmation.value != null) return
        if (timerState?.visible == true) { host.launch(scope) { experience.backFromTimers(timerAction) }; return }
        // A callback from a non-timer page cannot borrow the newer timer page or modal.
        if (experience.timerConfirmation.value != null || experience.timers?.states?.value?.visible == true) return
        if (experience.cookingNavigation.value.confirmation != null) { experience.dismissCookingConfirmation(cookingNavigation); return }
        if (experience.kitchenPage.value != null) { exitIntent = null; experience.leaveKitchen(); return }
        if (experience.cookingNavigation.value.visible) {
            host.launch(scope) { experience.backFromCooking(cooking, cookingNavigation) }
            return
        }
        val from = experience.meals.states.value.screen
        if (experience.forms.value.dirty) exitIntent = MealExitIntent(from) else { exitIntent = null; leave(from) }
    }
    // Explicit experience navigation, never selected-root visibility, owns this wrapper's
    // lifetime. Applying a publication may hide/remove its draft while result/recovery UI
    // must remain mounted. Attachment and Back never restore or migrate legacy records.
    if (reviewedPostOwner != null && reviewedPostNavigation.visible && form.values != null) {
        if (reviewedPostNavigation.opening) {
            val leavePosts = { host.launch(scope) { experience.leavePostDrafts() }; Unit }
            guardedBackHandler(true, leavePosts)
            FeedMeTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = leavePosts, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Back") }
                    Text("Opening retained drafts", style = MaterialTheme.typography.titleLarge)
                    Text("Reading local history does not Save, Publish or upgrade an older draft.")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                reviewedPostNavigation.failure?.let {
                    Text(reviewedEntryFailureText(it).orEmpty(), Modifier.fillMaxWidth().padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Box(Modifier.weight(1f)) {
                    FeedMeReviewedPostFlow(reviewedPostOwner, guardedBackHandler,
                        ReviewedPostLegacyFormatUiPolicy.CURRENT_FORMAT_ONLY,
                        onExit = { host.launch(scope) { experience.leavePostDrafts() } },
                        attachmentController = experience.reviewedAttachments,
                        localPhotoOwnerId = experience.localPhotoOwnerId,
                        recentCookSessionId = cooking.cooking?.takeIf { CookingScreenState.from(cooking).let {
                            it.done && it.serverAcknowledged && it.localPendingCount == 0 && it.pending.isEmpty()
                        } }?.id,
                        recentPlanId = cooking.cooking?.takeIf { CookingScreenState.from(cooking).let {
                            it.done && it.serverAcknowledged && it.localPendingCount == 0 && it.pending.isEmpty()
                        } }?.plan?.id?.value,
                        ingredientLabels = picker.knownIngredients.associate { it.id to it.name })
                }
            }
        }
        return
    }
    if (reviewedPostOwner == null && postDraftOwner != null && postDraftState?.screen != PostDraftScreen.HIDDEN && form.values != null) {
        FeedMePostDraftFlow(postDraftOwner, guardedBackHandler, currentFormatOnly = experience.localPhotoDraftsEnabled,
            localPhotoOwnerId = experience.localPhotoOwnerId)
        return
    }
    if (circleLeaver != null && circleLeaveState?.screen != CircleLeaveScreen.HIDDEN && form.values != null) {
        FeedMeCircleLeaveFlow(circleLeaver, guardedBackHandler, hostIsCurrent = host::isCurrent)
        return
    }
    if (circleDeleter != null && circleDeleteState?.screen != CircleLeaveScreen.HIDDEN && form.values != null) {
        FeedMeCircleLeaveFlow(circleDeleter, guardedBackHandler, hostIsCurrent = host::isCurrent)
        return
    }
    if (issuedOwner != null && issuedState?.screen != CircleIssuedInvitationsScreen.HIDDEN && form.values != null) {
        val detail = circlesState?.takeIf { it.screen == CirclesScreen.DETAIL && it.phase == CirclesPhase.READY && it.selected != null }
        FeedMeCircleIssuedInvitationsFlow(issuedOwner,
            onStartNew = if (detail == null) null else { expected ->
                if (host.isCurrent() && issuedHandoff?.state !== expected && issuedOwner.states.value === expected && circlesOwner?.states?.value === detail) {
                    val ticket = IssuedInvitationHandoff(expected)
                    issuedHandoff = ticket; issuedFailure = null
                    host.launch(scope) {
                        try {
                            if (host.isCurrent() && issuedOwner.states.value === expected && circlesOwner?.states?.value === detail) {
                                val result = host.await { experience.startNewCircleIssuedInvitation(expected, detail) }
                                if (host.isCurrent() && result is PortResult.Failure && issuedOwner.states.value === expected)
                                    issuedFailure = expected to result.reason
                            }
                        } finally { if (host.isCurrent() && issuedHandoff === ticket) issuedHandoff = null }
                    }
                }
                Unit
            },
            newCircleName = detail?.selected?.name,
            handoffBusy = issuedHandoff?.state === issuedState,
            handoffFailure = issuedFailure?.takeIf { it.first === issuedState }?.second,
            sharePort = invitationSharePort, hostIsCurrent = host::isCurrent,
            blueprintIsCurrent = host::isCurrent, platformBackHandler = guardedBackHandler)
        return
    }
    // Explicit invitation owners, not URL/Compose effects, select these routes.
    // A revoked private session redacts the host; public-only standalone UI remains separate.
    if (invitationOwner != null && invitationState?.screen != CircleInvitationScreen.HIDDEN && form.values != null) {
        fun handoffInvitation(expected: CircleInvitationState, action: suspend () -> PortResult<*>) {
            if (!host.isCurrent() || invitationHandoff?.state === expected || invitationOwner.states.value !== expected) return
            val ticket = InvitationHandoff(expected)
            invitationHandoff = ticket; invitationFailure = null
            host.launch(scope) {
                try {
                    if (host.isCurrent() && invitationOwner.states.value === expected) {
                        val result = host.await { action() }
                        if (host.isCurrent() && result is PortResult.Failure && invitationOwner.states.value === expected)
                            invitationFailure = expected to result.reason
                    }
                } finally { if (host.isCurrent() && invitationHandoff === ticket) invitationHandoff = null }
            }
        }
        FeedMeCircleInvitationFlow(invitationOwner,
            onOpenCircle = if (circlesOwner == null) null else { expected ->
                handoffInvitation(expected) { experience.openJoinedCircle(expected) }
            },
            onUseInvitation = if (invitationPreview == null) null else { expected ->
                handoffInvitation(expected) { experience.startNewCircleInvitation(expected) }
            },
            handoffBusy = invitationHandoff?.state === invitationState,
            handoffFailure = invitationFailure?.takeIf { it.first === invitationState }?.second,
            platformBackHandler = guardedBackHandler)
        return
    }
    if (invitationPreview != null && invitationPreviewState?.screen != CircleInvitationPreviewScreen.HIDDEN && form.values != null) {
        FeedMeCircleInvitationPreviewFlow(invitationPreview,
            onReviewJoining = if (invitationOwner == null) null else { expected ->
                if (host.isCurrent() && invitationEntry?.state !== expected && invitationPreview.states.value === expected) {
                    val ticket = InvitationEntry(expected)
                    invitationEntry = ticket; invitationEntryFailure = null
                    host.launch(scope) {
                        try {
                            if (host.isCurrent() && invitationPreview.states.value === expected) {
                                val result = host.await { experience.reviewInvitation(expected) }
                                if (host.isCurrent() && result is PortResult.Failure && invitationPreview.states.value === expected)
                                    invitationEntryFailure = expected to result.reason
                            }
                        } finally { if (host.isCurrent() && invitationEntry === ticket) invitationEntry = null }
                    }
                }
                Unit
            },
            requiresSignIn = experience.invitationRequiresSignIn,
            handoffBusy = invitationEntry?.state === invitationPreviewState,
            handoffFailure = invitationEntryFailure?.takeIf { it.first === invitationPreviewState }?.second,
            blueprintIsCurrent = host::isCurrent,
            platformBackHandler = guardedBackHandler)
        return
    }
    // Only explicit controller navigation mounts Circles. A revoked meal lease hides it;
    // attaching/recreating this route never issues another GET or selects a demo runtime.
    if (circleEditor != null && circleEditState?.screen != CircleEditScreen.HIDDEN && form.values != null) {
        fun handoff(expected: CircleEditState, action: suspend () -> PortResult<*>) {
            if (!host.isCurrent() || editHandoff?.state === expected || circleEditor.states.value !== expected) return
            val ticket = CircleEditHandoff(expected)
            editHandoff = ticket; editFailure = null
            // Root scope survives successful child-page replacement; exact ticket owns cleanup.
            host.launch(scope) {
                try {
                    if (host.isCurrent() && circleEditor.states.value === expected) {
                        val result = host.await { action() }
                        if (host.isCurrent() && result is PortResult.Failure && circleEditor.states.value === expected)
                            editFailure = expected to result.reason
                    }
                } finally { if (host.isCurrent() && editHandoff === ticket) editHandoff = null }
            }
        }
        FeedMeCircleEditFlow(circleEditor,
            onOpenCircle = if (circlesOwner == null) null else { expected -> handoff(expected) { experience.openEditedCircle(expected) } },
            onReviewConflict = if (circlesOwner == null) null else { expected -> handoff(expected) { experience.reviewCircleEditConflict(expected) } },
            onEditLatest = if (circlesOwner == null) null else { expected -> handoff(expected) { experience.editLatestCircleDetails(expected) } },
            handoffBusy = editHandoff?.state === circleEditState,
            handoffFailure = editFailure?.takeIf { it.first === circleEditState }?.second,
            platformBackHandler = guardedBackHandler, hostIsCurrent = host::isCurrent)
        return
    }
    if (circleCreator != null && circleCreateState?.screen != CircleCreateScreen.HIDDEN && form.values != null) {
        FeedMeCircleCreateFlow(circleCreator,
            onOpenCircle = if (circlesOwner == null) null else { expected ->
                if (host.isCurrent() && openingCircle !== expected && circleCreator.states.value === expected) {
                    // Claim before launching: duplicate callbacks from one rendered frame
                    // cannot start two GETs before the first coroutine gets its turn.
                    openingCircle = expected; circleOpenFailure = null
                    host.launch(scope) {
                        try {
                            if (host.isCurrent() && circleCreator.states.value === expected) {
                                val result = host.await { experience.openCreatedCircle(expected) }
                                if (host.isCurrent() && result is PortResult.Failure && circleCreator.states.value === expected)
                                    circleOpenFailure = expected to result.reason
                            }
                        } finally { if (host.isCurrent() && openingCircle === expected) openingCircle = null }
                    }
                }
                Unit
            },
            openingCircle = openingCircle === circleCreateState,
            openCircleFailure = circleOpenFailure?.takeIf { it.first === circleCreateState }?.second,
            blueprintIsCurrent = host::isCurrent,
            platformBackHandler = guardedBackHandler)
        return
    }
    if (circlesOwner != null && circlesState != null && circlesState.screen != CirclesScreen.HIDDEN && form.values != null) {
        fun enterReport(expected: CirclesState, memberId: String?) {
            if (!host.isCurrent() || reportEntry != null || circlesOwner.states.value !== expected) return
            val ticket = ReportEntry(expected)
            reportEntry = ticket; reportEntryFailure = null
            host.launch(scope) {
                try {
                    if (host.isCurrent() && circlesOwner.states.value === expected) {
                        val result = host.await {
                            if (memberId == null) experience.resumeReport()
                            else experience.reportCircleMember(memberId, expected)
                        }
                        if (host.isCurrent() && result is PortResult.Failure && circlesOwner.states.value === expected)
                            reportEntryFailure = expected to result.reason
                    }
                } finally { if (reportEntry === ticket) reportEntry = null }
            }
        }
        fun enterEdit(expected: CirclesState, resume: Boolean) {
            if (!host.isCurrent() || editEntry?.state === expected || circlesOwner.states.value !== expected) return
            val ticket = CircleEditEntry(expected)
            editEntry = ticket; editEntryFailure = null
            host.launch(scope) {
                try {
                    if (host.isCurrent() && circlesOwner.states.value === expected) {
                        val result = host.await { if (resume) experience.resumeCircleEdit(expected) else experience.openCircleEdit(expected) }
                        if (host.isCurrent() && result is PortResult.Failure && result.reason != FailureReason.STALE_SESSION &&
                            circlesOwner.states.value.screen != CirclesScreen.HIDDEN)
                            editEntryFailure = circlesOwner.states.value to result.reason
                    }
                } finally { if (host.isCurrent() && editEntry === ticket) editEntry = null }
            }
        }
        fun enterIssued(expected: CirclesState, resume: Boolean) {
            if (!host.isCurrent() || issuedEntry?.state === expected || circlesOwner.states.value !== expected) return
            val ticket = IssuedInvitationEntry(expected)
            issuedEntry = ticket; issuedEntryFailure = null
            host.launch(scope) {
                try {
                    if (host.isCurrent() && circlesOwner.states.value === expected) {
                        val result = host.await { if (resume) experience.resumeCircleIssuedInvitations(expected) else experience.openCircleIssuedInvitations(expected) }
                        if (host.isCurrent() && result is PortResult.Failure && result.reason != FailureReason.STALE_SESSION &&
                            circlesOwner.states.value.screen != CirclesScreen.HIDDEN)
                            issuedEntryFailure = circlesOwner.states.value to result.reason
                    }
                } finally { if (host.isCurrent() && issuedEntry === ticket) issuedEntry = null }
            }
        }
        fun enterLeave(expected: CirclesState, resume: Boolean) {
            if (!host.isCurrent() || leaveEntry != null || circlesOwner.states.value !== expected) return
            val ticket = CircleLeaveEntry(expected)
            leaveEntry = ticket; leaveEntryFailure = null
            host.launch(scope) {
                try {
                    if (host.isCurrent() && circlesOwner.states.value === expected) {
                        val result = host.await { if (resume) experience.resumeCircleLeave(expected) else experience.openCircleLeave(expected) }
                        if (host.isCurrent() && result is PortResult.Failure && result.reason != FailureReason.STALE_SESSION && circlesOwner.states.value.screen != CirclesScreen.HIDDEN)
                            leaveEntryFailure = circlesOwner.states.value to result.reason
                    }
                } finally { if (host.isCurrent() && leaveEntry === ticket) leaveEntry = null }
            }
        }
        fun enterDelete(expected: CirclesState, resume: Boolean) {
            if (!host.isCurrent() || deleteEntry != null || circlesOwner.states.value !== expected) return
            val ticket = CircleLeaveEntry(expected)
            deleteEntry = ticket; deleteEntryFailure = null
            host.launch(scope) {
                try {
                    if (host.isCurrent() && circlesOwner.states.value === expected) {
                        val result = host.await { if (resume) experience.resumeCircleDelete(expected) else experience.openCircleDelete(expected) }
                        if (host.isCurrent() && result is PortResult.Failure && result.reason != FailureReason.STALE_SESSION && circlesOwner.states.value.screen != CirclesScreen.HIDDEN)
                            deleteEntryFailure = circlesOwner.states.value to result.reason
                    }
                } finally { if (host.isCurrent() && deleteEntry === ticket) deleteEntry = null }
            }
        }
        fun enterMemberRemoval(expected: CirclesState, memberId: String?) {
            if (!host.isCurrent() || removalEntry != null || circlesOwner.states.value !== expected) return
            val ticket = Any(); removalEntry = ticket; removalEntryFailure = null
            val launched = host.launch(scope) {
                try {
                    if (host.isCurrent() && circlesOwner.states.value === expected) {
                        val result = host.await { experience.openCircleMemberRemoval(memberId, expected) }
                        if (host.isCurrent() && result is PortResult.Failure && circlesOwner.states.value === expected)
                            removalEntryFailure = expected to result.reason
                    }
                } finally { if (removalEntry === ticket) removalEntry = null }
            }
            if (launched == null && removalEntry === ticket) removalEntry = null
        }
        fun enterOwnershipTransfer(expected: CirclesState, memberId: String?) {
            if (!host.isCurrent() || transferEntry != null || circlesOwner.states.value !== expected) return
            val ticket = Any(); transferEntry = ticket; transferEntryFailure = null
            val launched = host.launch(scope) {
                try {
                    if (host.isCurrent() && circlesOwner.states.value === expected) {
                        val result = host.await { experience.openCircleOwnershipTransfer(memberId, expected) }
                        if (host.isCurrent() && result is PortResult.Failure && circlesOwner.states.value === expected)
                            transferEntryFailure = expected to result.reason
                    }
                } finally { if (transferEntry === ticket) transferEntry = null }
            }
            if (launched == null && transferEntry === ticket) transferEntry = null
        }
        FeedMeCirclesFlow(circlesOwner,
            tabNavigation = CirclesTabNavigation(experience.circlesTabDestinations(circlesState, meal, form,
                landing, cookingNavigation)) { expected, destination, presentationCurrent ->
                if (expected === circlesState && presentationCurrent()) host.launch(scope) {
                    if (presentationCurrent()) {
                        circlesTabFailure = null
                        val result = experience.navigateCirclesTab(destination, expected, meal, form,
                            landing, cookingNavigation, presentationCurrent)
                        if (result is PortResult.Failure && host.isCurrent() && circlesOwner.states.value === expected)
                            circlesTabFailure = expected to "Couldn’t open that tab. Your current circle view and pending actions are unchanged."
                    }
                }
            },
            navigationNotice = circlesTabFailure?.takeIf { it.first === circlesState }?.second,
            onTransferOwnership = if (transferOwner == null) null else { expected, member -> enterOwnershipTransfer(expected, member.id) },
            onResumeOwnershipTransfer = if (transferOwner == null) null else { expected -> enterOwnershipTransfer(expected, null) },
            transferEntryBusy = transferEntry != null,
            transferEntryFailure = transferEntryFailure?.takeIf { it.first === circlesState }?.second,
            onChooseDirectRecipient = if (conversationOwner == null) null else ({ expected ->
                if (host.isCurrent() && directPeoplePicker == null && circlesOwner.states.value === expected) {
                    CircleDirectPeoplePicker.open(circlesOwner, expected) { currentHost() }?.let {
                        directPeoplePicker = it; pickerEpoch = Any()
                    }
                }
            }),
            onRemoveMember = if (memberRemovalOwner == null) null else { expected, member -> enterMemberRemoval(expected, member.id) },
            onResumeMemberRemoval = if (memberRemovalOwner == null) null else { expected -> enterMemberRemoval(expected, null) },
            removalEntryBusy = removalEntry != null,
            removalEntryFailure = removalEntryFailure?.takeIf { it.first === circlesState }?.second,
            onOpenInvitationLink = if (invitationPreview == null) null else { expected, link ->
                host.launch(scope) { experience.openCircleInvitationLink(expected, link) }
                Unit
            },
            onStartCircle = if (circleCreator == null) null else { expected -> host.launch(scope) { experience.openCircleCreation(expected) }; Unit },
            onEditCircle = if (circleEditor == null) null else { expected -> enterEdit(expected, false) },
            onResumeEdit = if (circleEditor == null) null else { expected -> enterEdit(expected, true) },
            onResumeInvitation = if (invitationOwner == null) null else { expected ->
                if (host.isCurrent() && invitationResume?.state !== expected && circlesOwner.states.value === expected) {
                    val ticket = InvitationResume(expected)
                    invitationResume = ticket; invitationResumeFailure = null
                    host.launch(scope) {
                        try {
                            if (host.isCurrent() && circlesOwner.states.value === expected) {
                                val result = host.await { experience.resumeCircleInvitation(expected) }
                                if (host.isCurrent() && result is PortResult.Failure && circlesOwner.states.value === expected)
                                    invitationResumeFailure = expected to result.reason
                            }
                        } finally { if (host.isCurrent() && invitationResume === ticket) invitationResume = null }
                    }
                }
                Unit
            },
            onIssueInvitation = if (issuedOwner == null) null else { expected -> enterIssued(expected, false) },
            onResumeIssuedInvitations = if (issuedOwner == null) null else { expected -> enterIssued(expected, true) },
            issuedEntryBusy = issuedEntry?.state === circlesState,
            issuedEntryFailure = issuedEntryFailure?.takeIf { it.first === circlesState }?.second,
            invitationEntryBusy = invitationResume?.state === circlesState,
            invitationEntryFailure = invitationResumeFailure?.takeIf { it.first === circlesState }?.second,
            editEntryBusy = editEntry?.state === circlesState,
            editEntryFailure = editEntryFailure?.takeIf { it.first === circlesState }?.second,
            onLeaveCircle = if (circleLeaver == null) null else { expected -> enterLeave(expected, false) },
            onResumeLeave = if (circleLeaver == null) null else { expected -> enterLeave(expected, true) },
            leaveEntryBusy = leaveEntry != null,
            leaveEntryFailure = leaveEntryFailure?.takeIf { it.first === circlesState }?.second,
            onDeleteCircle = if (circleDeleter == null) null else { expected -> enterDelete(expected, false) },
            onResumeDelete = if (circleDeleter == null) null else { expected -> enterDelete(expected, true) },
            deleteEntryBusy = deleteEntry != null,
            deleteEntryFailure = deleteEntryFailure?.takeIf { it.first === circlesState }?.second,
            onReportMember = if (reportsOwner == null) null else { expected, member -> enterReport(expected, member.id) },
            onResumeReport = if (reportsOwner == null) null else { expected -> enterReport(expected, null) },
            reportEntryBusy = reportEntry != null,
            reportEntryFailure = reportEntryFailure?.takeIf { it.first === circlesState }?.second,
            hostIsCurrent = host::isCurrent,
            blueprintIsCurrent = host::isCurrent,
            platformBackHandler = guardedBackHandler)
        return
    }
    // Presentation callbacks retain this render, including child routes and the exact local
    // HOME/REQUEST visit. Returning to an unchanged form never revives an old callback.
    val blueprintRenderedCurrent: () -> Boolean = {
        host.isCurrent() && experience.blueprintLanding.current(landing, true) &&
            experience.interpretation.states.value === interpretation &&
            experience.adaptationInterpretation.states.value === adaptationInterpretation &&
            experience.meals.states.value === meal && experience.forms.value === form &&
            experience.adaptationForms.value === adaptationForm &&
            experience.cookbook.states.value === cookbook && cookbook.screen == CookbookScreen.HIDDEN &&
            experience.cookingNavigation.value === cookingNavigation && !cookingNavigation.visible &&
            cookingNavigation.confirmation == null && experience.kitchenPage.value == null &&
            experience.timerController.value?.states?.value?.visible != true && experience.timerConfirmation.value == null &&
            experience.savedCookingConfirmation.value == null && exitIntent == null &&
            !experience.reviewedPostsNavigation.value.visible && postDraftOwner?.states?.value === postDraftState &&
            circlesOwner?.states?.value === circlesState && circleCreator?.states?.value === circleCreateState &&
            circleEditor?.states?.value === circleEditState && circleLeaver?.states?.value === circleLeaveState &&
            circleDeleter?.states?.value === circleDeleteState &&
            invitationPreview?.states?.value === invitationPreviewState && issuedOwner?.states?.value === issuedState &&
            invitationOwner?.states?.value === invitationState
    }
    val offlineTicket = remember(experience, host, meal, form, cookbook, cookbookQuery,
        cooking, cookingNavigation, interpretation, landing) {
        when {
            interpretation.busy || interpretation.proposal != null -> null
            landing.home && landing.offline && meal.phase == MealFlowPhase.OFFLINE_DRAFT ->
                experience.captureOfflineRecovery(meal, form, cookbook, cookbookQuery, cooking, cookingNavigation, landing)
            meal.phase == MealFlowPhase.ERROR && (meal.failureReason ?: form.failure) == FailureReason.OFFLINE &&
                interpretation.text.isNullOrBlank() ->
                experience.captureOfflineRecovery(meal, form, cookbook, cookbookQuery, cooking, cookingNavigation)
            else -> null
        }
    }
    var offlineAvailability by remember(offlineTicket) { mutableStateOf<MealOfflineAvailability?>(null) }
    var offlineBusy by remember(offlineTicket) { mutableStateOf(false) }
    var offlineChecked by remember(offlineTicket) { mutableStateOf(false) }
    var offlineNotice by remember(offlineTicket) { mutableStateOf<String?>(null) }
    // Canonical OFFLINE hydration is a bounded local eligibility read only. A screen
    // entering does not probe the network, resume a cook or replay any saved command.
    LaunchedEffect(offlineTicket) {
        val ticket = offlineTicket ?: return@LaunchedEffect
        if (!blueprintRenderedCurrent()) return@LaunchedEffect
        offlineBusy = true
        try {
            val result = experience.inspectOfflineRecovery(ticket, blueprintRenderedCurrent)
            if (!blueprintRenderedCurrent()) return@LaunchedEffect
            offlineChecked = true
            when (result) {
                is PortResult.Value -> offlineAvailability = result.value
                is PortResult.Failure -> offlineNotice = "Downloaded meals couldn't be checked. Your current work is kept."
            }
        } finally { offlineBusy = false }
    }
    val landingEligible = blueprintMealLandingEligible(presentedMeal, form)
    val interpretationEntryCurrent: () -> Boolean = { blueprintRenderedCurrent() && experience.interpretation.enabled }
    val interpretationCurrent: () -> Boolean = { interpretationEntryCurrent() && experience.interpretation.reviewCurrent(interpretation) }
    val adaptationInterpretationEntryCurrent: () -> Boolean = {
        blueprintRenderedCurrent() && meal.screen == MealFlowScreen.ADAPT_MINE &&
            experience.adaptationInterpretation.enabled &&
            experience.adaptationInterpretation.reviewCurrent(adaptationInterpretation)
    }
    val mainBack: () -> Unit = if (adaptationInterpretation.proposal != null &&
        adaptationInterpretationEntryCurrent()) {
        { host.run {
            if (adaptationInterpretationEntryCurrent())
                experience.dismissAdaptationInterpretation(adaptationInterpretation)
        }; Unit }
    } else if (interpretation.proposal != null && interpretationEntryCurrent()) {
        { host.run { if (interpretationEntryCurrent()) experience.dismissMealInterpretation(interpretation) }; Unit }
    } else if (landingEligible) {
        {
            if (blueprintRenderedCurrent()) {
                if (landing.offline) experience.blueprintLanding.closeOffline(landing, true)
                else if (landing.home) {
                    // Dismissing the dirty-exit dialog must not revive its old Home callbacks.
                    if (experience.blueprintLanding.claim(landing, true)) back()
                } else experience.blueprintLanding.showHome(landing, true)
            }
            Unit
        }
    } else if (meal.screen in ROOT_RECIPE_SCREENS) {
        { host.launch(scope) { experience.backCatalog(meal) }; Unit }
    } else if (meal.screen in setOf(MealFlowScreen.ADAPT_MINE, MealFlowScreen.VARIANT_MINE)) {
        { host.launch(scope) { experience.backAdaptation(meal) }; Unit }
    } else ::back
    // Only the rendered main journey uses this callback. Child views retain their
    // own Back behavior; a stale main callback cannot dismiss a newer child view.
    val mainJourneyVisible = form.values == null || (timerState?.visible != true &&
        cookbook.screen == CookbookScreen.HIDDEN && kitchenPage == null && !cookingNavigation.visible)
    val mainJourneyBack = mainJourneyVisible && cookbook.deleteConfirmation == null &&
        timerConfirmation == null && cookingNavigation.confirmation == null && exitIntent == null && savedCookingIntent == null
    val cookingBack: () -> Unit = { host.launch(scope) { experience.backFromCooking(cooking, cookingNavigation) }; Unit }
    val cookingConfirmationBack: () -> Unit = { host.run { experience.dismissCookingConfirmation(cookingNavigation) }; Unit }
    val cookbookBack: () -> Unit = {
        val ticket = cookbook.deleteConfirmation
        host.launch(scope) {
            if (savedCookingIntent != null) {
                if (!form.busy) experience.dismissSavedCooking(savedCookingIntent)
            }
            else if (ticket != null) experience.dismissSavedRecipeDelete(cookbook, ticket)
            else experience.backFromCookbook(cookbook)
        }; Unit
    }
    val cookbookJourneyBack = form.values != null && timerState?.visible != true &&
        cookbook.screen != CookbookScreen.HIDDEN && timerConfirmation == null && exitIntent == null
    val cookingJourneyBack = form.values != null && timerState?.visible != true &&
        cookbook.screen == CookbookScreen.HIDDEN && kitchenPage == null && cookingNavigation.visible &&
        cookbook.deleteConfirmation == null && timerConfirmation == null && exitIntent == null
    val renderedBack: () -> Unit = when {
        cookbookJourneyBack -> cookbookBack
        mainJourneyBack -> mainBack
        cookingJourneyBack && cookingNavigation.confirmation != null -> cookingConfirmationBack
        cookingJourneyBack -> cookingBack
        else -> ::back
    }
    guardedBackHandler(true, renderedBack)
    fun renderedPlanActionCurrent() = meal.screen !in setOf(MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE) || blueprintRenderedCurrent()
    val parentActionHost = host
    // A single action factory serves both the normal journey and the manual More view.
    // The latter's immutable visit/form predicate is checked on click, queued entry and
    // after suspension by the existing host gate; it is never replaced by a newer predicate.
    fun preferenceEditor(page: BlueprintPreferencePage, returnLabel: String,
        continuation: () -> Boolean): MealPreferenceEditorHost? {
        val configuredChoices = preferenceChoices ?: return null
        if (!experience.accountKitchenPreferencesAvailable) return null
        val current = { host.isCurrent() && experience.accountKitchenPreferencesAvailable && continuation() }
        return MealPreferenceEditorHost(current = current,
            open = { visitCurrent -> host.launch(scope) {
                if (current() && visitCurrent()) experience.kitchen.loadPreferences()
            } },
            content = { visitCurrent, close ->
                FeedMeKitchenPreferencesFlow(experience, page, configuredChoices,
                    hostIsCurrent = { current() && visitCurrent() }, onClose = close,
                    platformBackHandler = platformBackHandler, returnLabel = returnLabel)
            })
    }
    fun screenActions(backAction: () -> Unit = mainBack, admission: () -> Boolean = { true },
        onFoodPreferences: (() -> Unit)? = null): MealScreenActions {
      val host = MealFlowHostActions { parentActionHost.isCurrent() && admission() }
      fun offlineCommand(successNotice: String? = null,
          operation: suspend (MealOfflineRecoveryTicket, () -> Boolean) -> PortResult<*>): ((() -> Boolean) -> Unit)? {
          val ticket = offlineTicket ?: return null
          return command@{ continuation ->
              fun visitCurrent() = parentActionHost.isCurrent() && admission() && blueprintRenderedCurrent() && continuation()
              if (offlineBusy || !visitCurrent()) return@command
              offlineBusy = true; offlineNotice = null
              scope.launch {
                  try {
                      if (!visitCurrent()) return@launch
                      val result = operation(ticket, ::visitCurrent)
                      if (!visitCurrent()) return@launch
                      when (result) {
                          is PortResult.Value -> offlineNotice = successNotice
                          is PortResult.Failure -> offlineNotice = when (result.reason) {
                              FailureReason.OFFLINE -> "Still offline. Your meals and pending work haven't changed."
                              FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.FORBIDDEN ->
                                  "Your account needs to be checked again. Return to sign-in."
                              else -> "This recovery action isn't available right now. Your pending work is kept."
                          }
                      }
                  } finally { offlineBusy = false }
              }
          }
      }
      fun adaptationIngredientsHost(): MealAdaptationIngredientsHost? {
        if (!experience.canEditAdaptationIngredients(meal, form, adaptationForm)) return null
        val current = { host.isCurrent() && blueprintRenderedCurrent() &&
            experience.canEditAdaptationIngredients(meal, form, adaptationForm) }
        return MealAdaptationIngredientsHost(current = current, states = experience.ingredients.states,
            search = { query, visitCurrent -> host.launch(scope) {
                if (current() && visitCurrent()) experience.searchAdaptationIngredientChoices(meal, form,
                    adaptationForm, query) { current() && visitCurrent() }
            } },
            more = { expectedPicker, visitCurrent -> host.launch(scope) {
                if (current() && visitCurrent()) experience.moreAdaptationIngredientChoices(meal, form,
                    adaptationForm, expectedPicker) { current() && visitCurrent() }
            } },
            apply = { ids, visitCurrent -> current() && visitCurrent() &&
                experience.applyAdaptationIngredientChoices(meal, form, adaptationForm, ids) {
                    current() && visitCurrent()
                } is PortResult.Value },
            platformBackHandler = platformBackHandler)
      }
      return MealScreenActions(
        back = { host.run(backAction) }, edit = { change -> host.run { experience.edit(change) } }, searchText = { text -> host.run { experience.searchText(text) } },
        search = { host.launch(scope) { experience.search() } }, moreIngredients = { host.launch(scope) { experience.moreIngredients() } },
        pantry = { host.launch(scope) { experience.pantry() } }, morePantry = { host.launch(scope) { experience.morePantry() } },
        context = { host.launch(scope) { experience.refreshContext() } }, saveDraft = { host.launch(scope) { experience.saveDraft() } },
        find = { host.launch(scope) { experience.findMeal() } }, retry = { host.launch(scope) { if (renderedPlanActionCurrent()) experience.retryOriginal() } },
        alternative = { host.launch(scope) { if (renderedPlanActionCurrent()) experience.alternative() } }, previous = { host.launch(scope) { if (renderedPlanActionCurrent()) experience.previous() } },
        recipe = { host.launch(scope) { if (renderedPlanActionCurrent()) experience.recipe() } },
        clearSavedSource = { host.launch(scope) { experience.clearSavedCookingSource(meal, form) } },
        clearDirectSource = { host.launch(scope) { experience.clearDirectRecipeSource(meal, form) } },
        reviewPlanSource = if (!experience.canReviewPlanSource(meal, form,
                adaptationForm.takeIf { meal.screen == MealFlowScreen.ADAPT_MINE })) null else ({
            host.launch(scope) {
                if (blueprintRenderedCurrent()) experience.reviewPlanSource(meal, form,
                    adaptationForm.takeIf { meal.screen == MealFlowScreen.ADAPT_MINE })
            }; Unit
        }),
        loadIngredientNames = { host.launch(scope) { experience.loadMealIngredientNames(meal, form) } },
        kitchen = { page -> host.run {
            if (page == KitchenInputPage.PREFERENCES && onFoodPreferences != null) {
                onFoodPreferences()
                return@run
            }
            experience.openKitchen(page)
            // The user's navigation action owns this one read; composition/recreation
            // never starts it. Retained edits/commands stay in their existing recovery UI.
            if (page == KitchenInputPage.PANTRY && blueprintPantryHealthy(experience.kitchen.states.value)) {
                experience.capturePantryAction(experience.kitchen.states.value, experience.forms.value,
                    experience.ingredients.states.value)?.let { exact ->
                    // REQUEST admission ends when its child opens. The attachment host
                    // owns dispatch now; the exact new pantry visit owns read admission.
                    parentActionHost.launch(scope) { experience.loadBlueprintPantry(exact) }
                }
            }
        } },
        cook = { host.launch(scope) { experience.prepareCooking(meal, form, cookingNavigation) } },
        retainedCooking = if (cooking.phase != CookingFlowPhase.IDLE) { { host.run { experience.showCooking(cooking, cookingNavigation) } } } else null,
        cookbook = {
            // Home consumes its rendered visit before invoking this port. Pin that new
            // token now so a queued open cannot follow a later Home/Request transition.
            val queuedVisit = experience.blueprintLanding.states.value
            host.launch(scope) {
                if (experience.blueprintLanding.current(queuedVisit, host.isCurrent()))
                    experience.openCookbook(cookbook, cookbookQuery, meal, form, cookingNavigation)
            }
        },
        save = { host.launch(scope) { experience.saveSelectedRecipe(meal, form) } },
        share = if (!experience.recipeShareAvailable(meal, form, cookingNavigation)) null else ({
            host.launch(scope) {
                if (blueprintRenderedCurrent()) experience.openRecipeShare(meal, form, cookingNavigation)
            }; Unit
        }),
        drafts = if (postDraftOwner == null) null else { { host.launch(scope) { experience.openPostDrafts() }; Unit } },
        circles = if (circlesOwner == null) null else { { host.launch(scope) { experience.openCircles() }; Unit } },
        reports = if (reportsOwner == null) null else { { host.launch(scope) { experience.resumeReport() }; Unit } },
        easier = { host.launch(scope) { experience.openSimplification(meal) } },
        requestEasier = { goal, different -> host.launch(scope) { experience.requestSimplification(meal, goal, different) } },
        reopenEasier = meal.proposal?.let { expected -> { host.launch(scope) { experience.reopenSimplification(expected) }; Unit } },
        acceptEasier = meal.proposal?.let { expected -> { host.launch(scope) { experience.acceptSimplification(expected) }; Unit } },
        keepOriginal = meal.proposal?.let { expected -> { host.launch(scope) { experience.keepOriginal(expected) }; Unit } },
        editEasierLimits = meal.proposal?.let { expected -> { host.launch(scope) { experience.editSimplificationLimits(expected) }; Unit } },
        anotherAfterEasier = meal.proposal?.let { expected -> { host.launch(scope) { experience.anotherAfterSimplification(expected) }; Unit } },
        adaptation = AdaptationScreenActions(
            open = { host.launch(scope) { experience.openAdaptation(meal) } },
            edit = { transform -> host.run { experience.editAdaptation(adaptationForm, transform) } },
            searchText = { text -> host.run { experience.adaptationSearchText(adaptationForm, text) } },
            search = { host.launch(scope) { experience.searchAdaptation(adaptationForm) } },
            moreIngredients = { host.launch(scope) { experience.moreAdaptationIngredients(adaptationForm) } },
            request = { host.launch(scope) { experience.requestAdaptation(meal, adaptationForm) } },
            reopen = { host.launch(scope) { experience.reopenAdaptation(meal) } },
            keepOriginal = { host.launch(scope) { experience.keepAdaptationOriginal(meal) } },
            accept = { host.launch(scope) { experience.acceptAdaptation(meal) } },
            editRequest = { host.launch(scope) { experience.editAdaptationRequest(meal) } },
            viewOriginal = { host.launch(scope) { experience.viewAdaptationOriginal(meal, adaptationForm) } },
            retry = { host.launch(scope) { experience.retryAdaptation(meal) } },
            missingIngredient = { host.launch(scope) {
                if (renderedPlanActionCurrent()) experience.openMissingIngredientAdaptation(meal, form)
            } },
            planNewMeal = if (!experience.canStageAdaptationMealRequest(meal, form, adaptationForm)) null else ({
                host.launch(scope) {
                    if (blueprintRenderedCurrent()) experience.stageAdaptationMealRequest(meal, form, adaptationForm)
                }; Unit
            }),
            ingredients = adaptationIngredientsHost()),
        catalog = if (meal.directRecipeMakeMineAvailable || meal.rootSource != null || meal.rootProposal != null || meal.pendingRootDraft != null)
            CatalogRecipeActions(
                open = { host.launch(scope) { experience.openCatalog(meal, form) } },
                query = { text -> host.run { experience.catalogSearchText(meal, text) } },
                browse = { host.launch(scope) { experience.browseRecipes(meal, catalogQuery) } },
                more = { host.launch(scope) { experience.browseRecipes(meal, catalogQuery, true) } },
                recipe = { recipe -> host.launch(scope) { experience.openCatalogRecipe(meal, recipe) } },
                refresh = { host.launch(scope) { experience.refreshCatalogSource(meal) } },
                makeMine = { host.launch(scope) { experience.openRootMakeMine(meal) } },
                edit = { transform -> host.run { experience.editRootRecipe(rootRecipeForm, transform) } },
                ingredientQuery = { text -> host.run { experience.rootRecipeSearchText(rootRecipeForm, text) } },
                ingredients = { host.launch(scope) { experience.searchRootIngredients(rootRecipeForm) } },
                moreIngredients = { host.launch(scope) { experience.searchRootIngredients(rootRecipeForm, true) } },
                request = { host.launch(scope) { experience.requestRootMakeMine(meal, rootRecipeForm) } },
                retry = { host.launch(scope) { experience.retryRootMakeMine(meal) } },
                accept = { host.launch(scope) { experience.acceptRootProposal(meal) } },
                keep = { host.launch(scope) { experience.keepRootSource(meal) } },
                reopen = { host.launch(scope) { experience.reopenRootProposal(meal) } },
                editRequest = { host.launch(scope) { experience.editRootRequest(meal) } },
                sourceReviewNotice = experience.planSourceReviewNotice(meal)) else null,
        blueprintCurrent = { host.isCurrent() && blueprintRenderedCurrent() },
        openOfflineRecovery = if (landing.home && !landing.offline && landingEligible &&
            meal.phase == MealFlowPhase.OFFLINE_DRAFT && !interpretation.busy && interpretation.proposal == null) ({
            if (blueprintRenderedCurrent()) experience.blueprintLanding.openOffline(landing, true)
            Unit
        }) else null,
        offlineRecovery = offlineTicket?.let { ticket ->
            val availability = offlineAvailability
            MealOfflineRecovery(
                savedMealsAvailable = availability?.let { it.downloadedMeals > 0 },
                cookingSessionAvailable = availability?.canResumeCooking,
                busy = offlineBusy,
                status = offlineNotice ?: when {
                    !offlineChecked -> "Checking downloaded meals on this device…"
                    availability == null -> "Offline recovery isn't available yet. Your current work is kept."
                    availability.downloadedMeals > 0 -> "Downloaded meals are available. Later server changes may be unknown offline."
                    availability.canResumeCooking -> "Saved steps and timers are available. Later server changes may be unknown offline."
                    else -> "No eligible downloaded meals or active cooking session were found."
                },
                openSaved = if (availability?.downloadedMeals?.let { it > 0 } == true)
                    offlineCommand(operation = experience::openOfflineSaved) else null,
                resumeCooking = if (availability?.canResumeCooking == true)
                    offlineCommand(operation = experience::resumeOfflineCooking) else null,
                retryConnection = if (experience.canRetryOfflineConnection(ticket))
                    offlineCommand("Account connection checked. Go back to continue. Nothing was resent.",
                        experience::retryOfflineConnection) else null,
            )
        },
        tabNavigation = MealTabNavigation(experience.mainMealTabDestinations(meal, form, landing)) { destination ->
            host.launch(scope) {
                if (blueprintRenderedCurrent()) experience.navigateMainMealTab(destination, meal, form, landing)
            }
        },
        equipmentPreferences = preferenceEditor(BlueprintPreferencePage.EQUIPMENT,
            if (meal.screen == MealFlowScreen.ADAPT_MINE) "Make Mine" else "Effort") {
            parentActionHost.isCurrent() && blueprintRenderedCurrent()
        },
        foodPreferences = preferenceEditor(BlueprintPreferencePage.FOOD_PREFS,
            if (meal.screen == MealFlowScreen.ADAPT_MINE) "Make Mine" else "meal options") {
            parentActionHost.isCurrent() && blueprintRenderedCurrent()
        },
        interpretation = interpretation,
        interpretationText = if (!experience.interpretation.enabled) null else { text -> host.run {
            if (interpretationEntryCurrent()) experience.editMealInterpretation(interpretation, text)
        } },
        interpret = if (!experience.interpretation.enabled) null else { { host.launch(scope) {
            if (interpretationCurrent()) experience.requestMealInterpretation(interpretation, meal, form, landing)
        }; Unit } },
        confirmInterpretation = if (!experience.interpretation.canConfirm(interpretation)) null else { { host.run {
            if (interpretationCurrent()) experience.confirmMealInterpretation(interpretation)
        }; Unit } },
        dismissInterpretation = if (!experience.interpretation.enabled) null else { { host.run {
            if (interpretationEntryCurrent()) experience.dismissMealInterpretation(interpretation)
        }; Unit } },
        interpretationCurrent = { host.isCurrent() && interpretationCurrent() },
        adaptationInterpretation = adaptationInterpretation,
        adaptationInterpretationText = if (!experience.adaptationInterpretation.enabled) null else { text -> host.run {
            if (adaptationInterpretationEntryCurrent()) experience.editAdaptationInterpretation(
                adaptationInterpretation, meal, form, adaptationForm, text)
        } },
        interpretAdaptation = if (!experience.adaptationInterpretation.enabled) null else { { host.launch(scope) {
            if (adaptationInterpretationEntryCurrent()) experience.requestAdaptationInterpretation(
                adaptationInterpretation, meal, form, adaptationForm)
        }; Unit } },
        confirmAdaptationInterpretation = if (!experience.adaptationInterpretation.canConfirm(adaptationInterpretation)) null
        else { { host.run {
            if (adaptationInterpretationEntryCurrent()) experience.confirmAdaptationInterpretation(
                adaptationInterpretation, meal, form, adaptationForm)
        }; Unit } },
        dismissAdaptationInterpretation = if (!experience.adaptationInterpretation.enabled) null else { { host.run {
            if (adaptationInterpretationEntryCurrent())
                experience.dismissAdaptationInterpretation(adaptationInterpretation)
        }; Unit } },
        adaptationInterpretationCurrent = {
            host.isCurrent() && adaptationInterpretationEntryCurrent()
        },
      )
    }
    val actions = screenActions()
    fun navigateSavedTab(destination: BlueprintScreenId, presentationCurrent: () -> Boolean) {
        if (!presentationCurrent()) return
        host.launch(scope) {
            if (!presentationCurrent()) return@launch
            savedTabFailure = null
            val result = experience.navigateSavedTab(destination, cookbook, cookbookQuery, meal, form,
                landing, cookingNavigation, presentationCurrent)
            if (result is PortResult.Failure && host.isCurrent() && experience.cookbook.states.value === cookbook)
                savedTabFailure = cookbook to "Couldn’t open that tab. Your Saved list and pending actions are unchanged."
        }
    }
    fun shareSavedRecipe(presentationCurrent: () -> Boolean) {
        if (!presentationCurrent()) return
        host.launch(scope) {
            if (!presentationCurrent()) return@launch
            savedShareFailure = null
            val result = experience.openSavedRecipeShare(cookbook, cookbookQuery, meal, form,
                cookingNavigation, presentationCurrent)
            if (result is PortResult.Failure && host.isCurrent() && experience.cookbook.states.value === cookbook)
                savedShareFailure = cookbook to "Couldn’t finish opening sharing. Your saved recipe is unchanged; any retained draft stays in Drafts."
        }
    }
    fun completedSocialAction(destination: BlueprintScreenId): ((() -> Boolean) -> Unit)? {
        if (!experience.canOpenCookingSocial(destination, cooking, cookingNavigation, meal, form, cookbook, cookbookQuery)) return null
        return { presentationCurrent ->
            if (presentationCurrent()) host.launch(scope) {
                if (presentationCurrent()) {
                    completionNavigationFailure = null
                    val result = experience.openCookingSocial(destination, cooking, cookingNavigation, meal, form,
                        cookbook, cookbookQuery, presentationCurrent)
                    if (result is PortResult.Failure && presentationCurrent())
                        completionNavigationFailure = cooking to "Couldn’t open this tab. Your completed meal and any unsaved input are unchanged. Try again when ready."
                }
            }
        }
    }
    FeedMeTheme {
      Box(Modifier.fillMaxSize()) {
        val inputPage = kitchenPage
        val pantryAction = if (inputPage == KitchenInputPage.PANTRY) experience.capturePantryAction(kitchen, form, picker) else null
        if (timerState?.visible == true && form.values != null) CookingTimerScreen(CookingTimerScreenState.from(timerState),
            CookingTimerScreenActions(back = { host.launch(scope) { experience.backFromTimers(timerAction) } }, duration = { host.launch(scope) { experience.timerDuration(it, timerAction) } },
                start = { host.launch(scope) { experience.startTimer(timerAction) } }, pause = { host.launch(scope) { experience.pauseTimer(it, timerAction) } },
                resume = { host.launch(scope) { experience.resumeTimer(it, timerAction) } }, reset = { host.launch(scope) { experience.resetTimer(it, timerAction) } },
                remove = { timer -> host.run { experience.requestTimerRemoval(timer, timerAction) } }, cleanup = { host.launch(scope) { experience.cancelTimerAlert(it, timerAction) } },
                refresh = { host.launch(scope) { experience.refreshTimers(timerAction) } },
                blueprintOwnerKey = timerOwner,
                blueprintIsCurrent = { host.isCurrent() && experience.timerDisplayIsCurrent(timerAction) } ))
        else if (cookbook.screen != CookbookScreen.HIDDEN && form.values != null) RetainedCookbookScreen(cookbook,
            cookbookQuery, MealPickerPresentation.from(picker), form.busy, CookbookScreenActions(
                blueprintIsCurrent = { host.isCurrent() && experience.cookbook.states.value === cookbook &&
                    experience.cookbookQuery.value == cookbookQuery && experience.forms.value === form },
                tabNavigation = MealTabNavigation(experience.savedTabDestinations(cookbook, cookbookQuery,
                    meal, form, landing, cookingNavigation)) { destination ->
                    navigateSavedTab(destination) { host.isCurrent() && experience.cookbook.states.value === cookbook }
                },
                guardedTabNavigate = ::navigateSavedTab,
                guardedRecipeAction = { selectedAction, presentationCurrent ->
                    if (selectedAction == BlueprintDiscoveryAction.RECIPE_SHARE) shareSavedRecipe(presentationCurrent)
                    else if (presentationCurrent()) host.launch(scope) {
                        if (presentationCurrent()) when (selectedAction) {
                            BlueprintDiscoveryAction.RECIPE_COOK -> {
                                val result = experience.prepareSavedCooking(cookbook, cookbookQuery, meal, form, cookingNavigation)
                                if (host.isCurrent() && experience.cookbook.states.value === cookbook)
                                    savedCookingFailure = (result as? PortResult.Failure)?.let { cookbook to it.reason }
                            }
                            BlueprintDiscoveryAction.RECIPE_ADAPT -> {
                                val result = experience.openSavedMakeMine(cookbook, cookbookQuery, meal, form, cookingNavigation)
                                if (host.isCurrent() && experience.cookbook.states.value === cookbook)
                                    savedMakeMineFailure = (result as? PortResult.Failure)?.let { cookbook to it.reason }
                            }
                            else -> Unit
                        }
                    }
                },
                back = cookbookBack, query = { text -> host.run { experience.cookbookSearchText(text, cookbook) } },
                search = { host.launch(scope) { experience.searchCookbook(cookbook, cookbookQuery) } }, local = { host.launch(scope) { experience.searchDownloadedCookbook(cookbook, cookbookQuery) } },
                more = { host.launch(scope) { experience.moreCookbook(cookbook, cookbookQuery) } }, open = { host.launch(scope) { experience.openSavedRecipe(it, cookbook, cookbookQuery) } },
                refresh = { host.launch(scope) { experience.refreshSavedRecipe(cookbook, cookbookQuery) } }, download = { host.launch(scope) { experience.downloadSavedRecipe(cookbook, cookbookQuery) } },
                delete = { host.launch(scope) { experience.prepareSavedRecipeDelete(it, cookbook, cookbookQuery) } }, retry = { host.launch(scope) { experience.retryCookbookOriginal(cookbook, cookbookQuery) } },
                discard = { host.launch(scope) { experience.discardCookbookUnsent(cookbook, cookbookQuery) } },
                loadIngredientNames = { host.launch(scope) { experience.loadSavedIngredientNames(cookbook, cookbookQuery) } },
                cookAgain = if (!experience.supportsSavedCooking) null else { { host.launch(scope) {
                    val result = experience.prepareSavedCooking(cookbook, cookbookQuery, meal, form, cookingNavigation)
                    if (host.isCurrent() && experience.cookbook.states.value === cookbook)
                        savedCookingFailure = (result as? PortResult.Failure)?.let { cookbook to it.reason }
                } } },
                makeMine = if (!meal.savedMakeMineAvailable && meal.savedSource?.savedRecipeId != cookbook.selected?.id) null else { { host.launch(scope) {
                    val result = experience.openSavedMakeMine(cookbook, cookbookQuery, meal, form, cookingNavigation)
                    if (host.isCurrent() && experience.cookbook.states.value === cookbook)
                        savedMakeMineFailure = (result as? PortResult.Failure)?.let { cookbook to it.reason }
                } } },
                shareRecipe = if (!experience.savedRecipeShareAvailable(cookbook, cookbookQuery, meal, form,
                    cookingNavigation)) null else { { shareSavedRecipe {
                        host.isCurrent() && experience.cookbook.states.value === cookbook &&
                            experience.cookbookQuery.value == cookbookQuery && experience.forms.value === form
                    }; Unit } },
                collections = collectionOwner?.let { { host.launch(scope) {
                    experience.openCollections(cookbook, cookbookQuery, meal, form)
                }; Unit } },
                memory = memoryOwner?.let { { host.launch(scope) {
                    experience.openCookbookMemory(cookbook, cookbookQuery, meal, form)
                }; Unit } }), choices = experience.choices,
            savedCookingFailure = savedCookingFailure?.takeIf { it.first === cookbook }?.second,
            savedMakeMineFailure = savedMakeMineFailure?.takeIf { it.first === cookbook }?.second,
            navigationNotice = listOfNotNull(savedTabFailure?.takeIf { it.first === cookbook }?.second,
                savedShareFailure?.takeIf { it.first === cookbook }?.second).joinToString("\n\n").takeIf(String::isNotBlank))
        else if (inputPage != null && form.values != null) BlueprintMealRefinementControls(
            form, experience.choices,
            current = { host.isCurrent() && experience.pantryMealHandoffCurrent(pantryAction) },
            edit = { change -> host.run {
                if (experience.pantryMealHandoffCurrent(pantryAction)) experience.edit(change)
            } },
            equipmentPreferences = preferenceEditor(BlueprintPreferencePage.EQUIPMENT, "Effort") {
                experience.pantryPreferenceContinuationCurrent(pantryAction)
            },
        ) { open, refinementOpen -> KitchenInputScreen(inputPage, kitchen,
            MealPickerPresentation.from(picker), experience.choices, form.busy, form.searchText,
            KitchenInputScreenActions(
                back = ::back, searchText = { text -> host.run { experience.searchText(text) } },
                search = { host.launch(scope) { experience.search() } }, moreIngredients = { host.launch(scope) { experience.moreIngredients() } },
                loadPreferences = { host.launch(scope) { experience.loadKitchenPreferences() } },
                loadPantry = { pantryAction?.let { exact -> host.launch(scope) { experience.loadBlueprintPantry(exact) } }; Unit },
                morePantry = { pantryAction?.let { exact -> host.launch(scope) { experience.loadBlueprintPantry(exact, nextPage = true) } }; Unit },
                editPreferences = { host.launch(scope) { experience.editKitchenPreferences(it) } },
                discardPreferenceDraft = { host.launch(scope) { experience.discardKitchenPreferences() } },
                savePreferences = { host.launch(scope) { experience.saveKitchenPreferences() } },
                editPantry = { host.launch(scope) { experience.editKitchenPantry(it) } },
                discardPantryDraft = { host.launch(scope) { experience.discardKitchenPantry(it) } },
                savePantry = { host.launch(scope) { experience.saveKitchenPantry(it) } },
                removePantry = { host.launch(scope) { experience.removeKitchenPantry(it) } },
                synchronize = { host.launch(scope) { experience.synchronizeKitchen(it) } },
                discardUnsent = { host.launch(scope) { experience.discardUnsentKitchen(it) } },
                blueprintOwnerKey = experience,
                blueprintIsCurrent = { host.isCurrent() && experience.pantryActionCurrent(pantryAction) },
                blueprintSearch = pantryAction?.let { exact -> { host.launch(scope) { experience.searchPantryIngredient(exact) }; Unit } },
                blueprintMoreIngredients = pantryAction?.let { exact -> { host.launch(scope) { experience.morePantryIngredients(exact) }; Unit } },
                blueprintAdd = pantryAction?.let { exact -> { ingredient, availability ->
                    host.launch(scope) { experience.addBlueprintPantryIngredient(exact, ingredient, availability) }; Unit } },
                blueprintRemove = pantryAction?.let { exact -> { row ->
                    host.launch(scope) { experience.removeBlueprintPantryIngredient(exact, row) }; Unit } },
                blueprintMealValues = form.values,
                blueprintUseIngredients = pantryAction?.takeIf(experience::pantryMealHandoffCurrent)?.let { exact -> { ids ->
                    host.launch(scope) { experience.useBlueprintPantryIngredients(exact, ids) }; Unit } },
                blueprintSetEffort = pantryAction?.takeIf(experience::pantryMealHandoffCurrent)?.let {
                    { open(BlueprintMealRefinementPage.EFFORT) }
                },
                blueprintRefinementOpen = refinementOpen,
            ), blueprintPicker = picker) }
        else if (cookingNavigation.visible && form.values != null) RetainedCookingScreen(CookingScreenState.from(cooking),
            MealPickerPresentation.from(picker), experience.choices, form.busy, CookingScreenActions(
                back = cookingBack, leave = { host.run { experience.leaveCooking(cooking, cookingNavigation) } },
                loadIngredientNames = { host.launch(scope) { experience.loadCookingIngredientNames(cooking, cookingNavigation) } },
                reviewStart = { host.run { experience.reviewCookingStart(cooking, cookingNavigation) } },
                retryStart = { host.launch(scope) { experience.retryCookingStart(cooking, cookingNavigation) } },
                discardStart = { host.launch(scope) { experience.discardCookingStart(cooking, cookingNavigation) } },
                readRetained = { host.launch(scope) { experience.readRetainedCooking(cooking, cookingNavigation) } },
                reopen = { host.launch(scope) { experience.reopenCooking(cooking, cookingNavigation) } },
                refresh = { host.launch(scope) { experience.refreshCooking(cooking, cookingNavigation) } },
                synchronize = { host.launch(scope) { experience.synchronizeCooking(cooking, cookingNavigation) } },
                move = { host.launch(scope) { experience.moveCooking(it, cooking, cookingNavigation) } },
                mark = { host.launch(scope) { experience.completeCookingStep(it, cooking, cookingNavigation) } },
                advance = { host.launch(scope) { experience.advanceCooking(cooking, cookingNavigation) } },
                blueprintIsCurrent = { host.isCurrent() && experience.cooking.states.value === cooking &&
                    experience.cookingNavigation.value === cookingNavigation && experience.forms.value === form },
                pause = { host.launch(scope) { experience.pauseCooking(cooking, cookingNavigation) } },
                pauseAndLeave = { host.launch(scope) {
                    experience.pauseAndLeaveCooking(cooking, cookingNavigation, meal, form, landing)
                } },
                doneForNow = { presentationCurrent ->
                    if (presentationCurrent()) host.launch(scope) {
                        if (presentationCurrent()) {
                            completionNavigationFailure = null
                            val result = experience.doneCookingForNow(cooking, cookingNavigation, meal, form,
                                landing, presentationCurrent)
                            if (result is PortResult.Failure && presentationCurrent())
                                completionNavigationFailure = cooking to "Couldn’t leave this view. Your completed cook and any unsynced progress are retained. Try again or use Back."
                        }
                    }
                },
                today = completedSocialAction(BlueprintScreenId.TODAY),
                inbox = completedSocialAction(BlueprintScreenId.INBOX),
                profile = completedSocialAction(BlueprintScreenId.PROFILE_PLATE),
                resume = { host.launch(scope) { experience.resumeCooking(cooking, cookingNavigation) } },
                abandon = { host.run { experience.requestCookingEnd(CookingConfirmationKind.ABANDON, cooking, cookingNavigation) } },
                complete = { host.run { experience.requestCookingEnd(CookingConfirmationKind.COMPLETE, cooking, cookingNavigation) } },
                timers = if (timerOwner == null) null else { { host.launch(scope) { experience.openTimers(cooking, cookingNavigation) }; Unit } },
                cookbook = { host.launch(scope) { experience.openCookingCookbook(cooking, cookingNavigation, cookbook, cookbookQuery) } },
                save = if (!form.dirty && cooking.plan?.id?.value != null && cooking.plan?.id?.value == meal.plan?.plan?.id?.value)
                    { { host.launch(scope) { experience.saveCookingRecipe(cooking, cookingNavigation, meal, form) }; Unit } } else null,
                feedback = if (!feedbackEntryReady) null else ({
                    host.launch(scope) { experience.openMealFeedback(cooking, cookingNavigation) }; Unit
                }),
                reuse = if (!feedbackEntryReady) null else ({
                    host.launch(scope) { experience.openMealFeedback(cooking, cookingNavigation, reuse = true) }; Unit
                }),
                history = if (memoryOwner == null) null else ({
                    host.launch(scope) { experience.openCookingMemory(cooking, cookingNavigation) }; Unit
                }),
                share = if (reviewedPostOwner == null || experience.localPhotoOwnerId == null ||
                    !CookingScreenState.from(cooking).let {
                        it.done && it.serverAcknowledged && it.localPendingCount == 0 && it.pending.isEmpty()
                    }) null else ({
                    host.launch(scope) { experience.openCookingShare(cooking, cookingNavigation) }; Unit
                }),
                makeAgain = if (!experience.cookingMakeAgainAvailable(cooking, cookingNavigation, meal, form)) null else ({
                    host.launch(scope) { experience.makeCookingAgain(cooking, cookingNavigation, meal, form) }; Unit
                }),
            ), savedAction = cookingSavedActionNotice(cookbook),
            navigationNotice = completionNavigationFailure?.takeIf { it.first === cooking }?.second)
        else if (landing.home && landing.offline && landingEligible && meal.phase == MealFlowPhase.OFFLINE_DRAFT)
            BlueprintHomeOfflineRecoveryScreen(landing, presentedMeal, form, actions, onBack = mainBack)
        else if (landing.home && landingEligible) BlueprintMealLanding(experience.blueprintLanding, landing,
            presentedMeal, form, actions, onAccountSettings = onAccountSettings,
            onSocialNavigate = if (socialOwner == null) null else ({ destination ->
                host.launch(scope) { experience.openSocial(destination, meal, form, landing) }
            }), onHistory = if (memoryOwner == null) null else ({
                val claimed = experience.blueprintLanding.states.value
                host.launch(scope) { experience.openHomeMemory(meal, form, claimed) }; Unit
            }))
        else if (landingEligible) BlueprintMealRequest(presentedMeal, form, experience.choices, actions,
            manualContent = { close, current, openFoodPreferences ->
                MealJourneyScreen(presentedMeal, MealPickerPresentation.from(picker), form,
                    experience.choices, screenActions(close, current, openFoodPreferences), adaptationForm, rootRecipeForm, catalogQuery)
            }, actionFactory = { current -> screenActions(admission = current) })
        else MealJourneyScreen(presentedMeal, MealPickerPresentation.from(picker), form, experience.choices, actions,
            adaptationForm, rootRecipeForm, catalogQuery)
        if (timerState != null && !timerState.visible && form.values != null && showCurrentTimerDue(timerState, cooking)) {
            Snackbar(Modifier.align(Alignment.BottomCenter).safeDrawingPadding(),
                containerColor = FeedMeColors.Ink, contentColor = FeedMeColors.Paper, action = {
                TextButton(modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = FeedMeColors.Lime), onClick = { host.launch(scope) {
                    if (host.isCurrent() && showCurrentTimerDue(timerState, cooking)) experience.openTimers(cooking, cookingNavigation)
                } }) { Text("View timers") }
            }) { Text("A timer reached its estimated end. No cooking step was advanced.") }
        }
        val removal = timerConfirmation
        val deletion = cookbook.deleteConfirmation
        if (savedCookingIntent != null && cookbook.screen == CookbookScreen.DETAIL && form.values != null) {
            AlertDialog(onDismissRequest = { if (!form.busy) host.launch(scope) { experience.dismissSavedCooking(savedCookingIntent) } },
                title = { Text("Cook this saved meal again?") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(savedCookingIntent.title)
                    Text("Keep today’s ingredients and limits, then review them for this recipe. We’ll check the saved copy and your current preferences before offering a new plan.")
                    Text("Nothing starts cooking, records a repeat preference or shares a post.", style = MaterialTheme.typography.bodySmall)
                    if (form.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    savedCookingFailure?.takeIf { it.first === cookbook }?.second?.let {
                        Text(savedCookingFailureText(it), style = MaterialTheme.typography.bodySmall)
                        Text("Choose Keep browsing, then review the recipe again.", style = MaterialTheme.typography.bodySmall)
                    }
                } },
                confirmButton = { TextButton(enabled = !form.busy, onClick = { host.launch(scope) {
                    val result = experience.confirmSavedCooking(savedCookingIntent)
                    if (host.isCurrent() && result is PortResult.Failure && experience.cookbook.states.value === cookbook)
                        savedCookingFailure = cookbook to result.reason
                } }) { Text("Review today’s choices") } },
                dismissButton = { TextButton(enabled = !form.busy, onClick = { host.launch(scope) { experience.dismissSavedCooking(savedCookingIntent) } }) { Text("Keep browsing") } })
        }
        if (deletion != null && cookbook.screen == CookbookScreen.DETAIL && form.values != null) {
            AlertDialog(onDismissRequest = { host.launch(scope) { experience.dismissSavedRecipeDelete(cookbook, deletion) } },
                title = { Text("Remove this saved copy?") },
                text = { Text(cookbookRemovalDescription(deletion.title, deletion.contentUnavailable)) },
                confirmButton = { TextButton(enabled = !form.busy, onClick = { host.launch(scope) { experience.confirmSavedRecipeDelete(deletion, cookbook, cookbookQuery) } }) { Text("Confirm removal from Saved") } },
                dismissButton = { TextButton(onClick = { host.launch(scope) { experience.dismissSavedRecipeDelete(cookbook, deletion) } }) { Text("Keep saved copy") } })
        }
        val timerPin = timerState?.snapshot
        if (removal != null && timerState?.visible == true && timerState.canStop && form.values != null &&
            timerPin != null && timerPin.sessionId == removal.sessionId && timerPin.planId == removal.planId && timerPin.localRevision == removal.revision &&
            timerPin.timers.any { it.timerId == removal.timerId }) {
            AlertDialog(onDismissRequest = { host.run { experience.dismissTimerRemoval(removal) } },
                title = { Text("Remove this timer?") },
                text = { Text("Remove this exact timer from local cooking progress and cancel its alert. This does not finish the step or meal; server sync remains explicit.") },
                confirmButton = { TextButton(enabled = !timerState.busy && !form.busy,
                    onClick = { host.launch(scope) { experience.confirmTimerRemoval(removal) } }) { Text("Confirm timer removal") } },
                dismissButton = { TextButton(onClick = { host.run { experience.dismissTimerRemoval(removal) } }) { Text("Keep timer") } })
        }
        val confirmation = cookingNavigation.confirmation
        if (confirmation != null && form.values != null) {
            // START never falls back to the old selected cooking pin. A stale ticket shows no dialog.
            val exact = cooking.plan?.takeIf { cooking === confirmation.cookingState && it.id.value == confirmation.planId }
            if (exact != null) AlertDialog(onDismissRequest = { host.run { experience.dismissCookingConfirmation(cookingNavigation) } },
                title = { Text(when (confirmation.kind) {
                    CookingConfirmationKind.START -> "Start this exact plan?"
                    CookingConfirmationKind.ABANDON -> "Stop this session?"
                    CookingConfirmationKind.COMPLETE -> "Are you done cooking?"
                }) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(exact.recipeSnapshot.valueOrNull()?.title ?: "Exact retained plan")
                        Text("${exact.recipeSnapshot.valueOrNull()?.servings?.jsonToken ?: "Unavailable"} servings")
                        Text(when (confirmation.kind) {
                            CookingConfirmationKind.START -> "Check the full recipe and any missing ingredients before starting. Starting a session is not a food-safety check."
                            CookingConfirmationKind.ABANDON -> "This records an explicit stop on this device. Back alone never stops a session; sync remains a separate action."
                            CookingConfirmationKind.COMPLETE -> "This records your completion on this device. It does not Save, Make Again, share or automatically synchronize."
                        })
                        if (confirmation.kind == CookingConfirmationKind.START && exact.missingIngredients.isNotEmpty()) {
                            val presentation = MealPlanPresentation(exact, true, picker.knownIngredients.associate { it.id to it.name })
                            Text("Missing ingredients: " + exact.missingIngredients.joinToString("\n", transform = presentation::ingredientLine))
                        }
                    }
                },
                confirmButton = { TextButton(enabled = !form.busy, onClick = { host.launch(scope) { experience.confirmCooking(confirmation) } }) {
                    Text(when (confirmation.kind) {
                        CookingConfirmationKind.START -> "Confirm cooking start"
                        CookingConfirmationKind.ABANDON -> "Confirm stop"
                        CookingConfirmationKind.COMPLETE -> "Confirm completion"
                    })
                } }, dismissButton = { TextButton(onClick = { host.run { experience.dismissCookingConfirmation(cookingNavigation) } }) {
                    Text(if (confirmation.kind == CookingConfirmationKind.START) "Review full recipe" else "Not now")
                } })
        }
        if (exitIntent != null && form.values != null) AlertDialog(onDismissRequest = { host.run { exitIntent = null } },
            title = { Text("Keep your changes?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("Unfinished edits are only in memory. Saving a valid draft keeps it on this device; it does not submit a meal.")
              form.failure?.let { Text(mealFailureText(it)) }
              Button(onClick = { host.run { exitIntent = null } },
                  modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Keep editing") }
              OutlinedButton(enabled = !form.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                if (!host.isCurrent()) return@OutlinedButton
                val request = exitIntent
                val ticket = experience.exitTicket()
                host.launch(scope) {
                    val saved = host.await { experience.saveDraft() }
                    // The suspended save does not own later edits, a dismissed dialog or a new session.
                    if (host.isCurrent() && saved is PortResult.Value && request != null && exitIntent === request &&
                        ticket != null && experience.canExitAfterSave(ticket)) { exitIntent = null; leave(request.from) }
                }
              }) { Text("Save draft and go back") }
              TextButton(enabled = !form.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                if (!host.isCurrent()) return@TextButton
                val request = exitIntent ?: return@TextButton
                val exactMeal = meal
                val exactForm = form
                host.launch(scope) {
                    val discarded = host.await { experience.discardUnsavedEditsConfirmed(exactMeal, exactForm) }
                    if (host.isCurrent() && discarded is PortResult.Value && exitIntent === request &&
                        experience.canExitAfterSave(discarded.value)) { exitIntent = null; leave(request.from) }
                }
            }) {
                Text("Discard unsaved edits")
              }
            } }, confirmButton = {})
      }
    }
}

/** Presentation events, not capabilities or transport operations. No default success handlers. */
class MealScreenActions(val back: () -> Unit, val edit: ((MealFormValues) -> MealFormValues) -> Unit,
    val searchText: (String) -> Unit, val search: () -> Unit, val moreIngredients: () -> Unit,
    val pantry: () -> Unit, val morePantry: () -> Unit, val context: () -> Unit, val saveDraft: () -> Unit,
    val find: () -> Unit, val retry: () -> Unit, val alternative: () -> Unit, val previous: () -> Unit, val recipe: () -> Unit,
    val kitchen: ((KitchenInputPage) -> Unit)? = null, val cook: (() -> Unit)? = null,
    val retainedCooking: (() -> Unit)? = null, val cookbook: (() -> Unit)? = null, val save: (() -> Unit)? = null,
    val drafts: (() -> Unit)? = null, val circles: (() -> Unit)? = null,
    val easier: (() -> Unit)? = null, val requestEasier: ((SimplificationGoal, Boolean) -> Unit)? = null,
    val reopenEasier: (() -> Unit)? = null, val acceptEasier: (() -> Unit)? = null,
    val keepOriginal: (() -> Unit)? = null, val editEasierLimits: (() -> Unit)? = null,
    val anotherAfterEasier: (() -> Unit)? = null, val adaptation: AdaptationScreenActions? = null,
    val loadIngredientNames: (() -> Unit)? = null, val clearSavedSource: (() -> Unit)? = null,
    val catalog: CatalogRecipeActions? = null, val clearDirectSource: (() -> Unit)? = null,
    val reports: (() -> Unit)? = null, val blueprintCurrent: () -> Boolean = { false },
    val interpretation: MealInterpretationState? = null,
    val interpretationText: ((String) -> Unit)? = null, val interpret: (() -> Unit)? = null,
    val confirmInterpretation: (() -> Unit)? = null, val dismissInterpretation: (() -> Unit)? = null,
    val interpretationCurrent: () -> Boolean = { false }, val share: (() -> Unit)? = null,
    val adaptationInterpretation: AdaptationInterpretationState? = null,
    val adaptationInterpretationText: ((String) -> Unit)? = null,
    val interpretAdaptation: (() -> Unit)? = null,
    val confirmAdaptationInterpretation: (() -> Unit)? = null,
    val dismissAdaptationInterpretation: (() -> Unit)? = null,
    val adaptationInterpretationCurrent: () -> Boolean = { false },
    val equipmentPreferences: MealPreferenceEditorHost? = null,
    val foodPreferences: MealPreferenceEditorHost? = null,
    /** Fresh source review for an accepted Plan; never sends or accepts a replacement. */
    val reviewPlanSource: (() -> Unit)? = null,
    /** Explicit owner-admitted original bottom tabs; absence retains legacy Saved only. */
    val tabNavigation: MealTabNavigation? = null,
    val offlineRecovery: MealOfflineRecovery? = null,
    val openOfflineRecovery: (() -> Unit)? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealJourneyScreen(meal: MealScreenState, picker: MealPickerPresentation, form: MealFormState,
    choices: MealInputChoices, actions: MealScreenActions, adaptationForm: AdaptationFormState? = null,
    rootRecipeForm: RootRecipeFormState? = null, catalogQuery: String = "") {
    if (meal.screen in setOf(MealFlowScreen.ADAPT, MealFlowScreen.VARIANT,
            MealFlowScreen.ADAPT_MINE, MealFlowScreen.VARIANT_MINE)) {
        BlueprintOwnedAdaptationScreen(meal, picker, form, choices, actions, adaptationForm)
        return
    }
    blueprintMealFlowPresentation(meal, picker, form, choices, actions)?.let { projection ->
        BlueprintIssuedMealScreen(meal, picker, form, choices, actions, projection)
        return
    }
    if (BlueprintOwnedCatalogRecipeScreen(meal, picker, form, choices, actions)) return
    if (BlueprintMealUnavailableScreen(meal, form, actions)) return
    val unavailable = form.values == null || meal.phase == MealFlowPhase.UNAVAILABLE
    val status = mealPhaseMessage(meal.phase)
    val statusPresentation = mealStatusPresentation(meal, form, rootRecipeForm)
    // A new destination or meal starts with its heading and recommendation. A status,
    // acknowledgement or form update for the same meal preserves the reading position.
    val scroll = key(meal.screen, meal.plan?.id?.value) { rememberScrollState() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().widthIn(max = 720.dp).verticalScroll(scroll)
        .padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.back, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Back") }
            FeedMeWordmark(compact = true)
            Pill(if (unavailable) "UNAVAILABLE" else "YOUR KITCHEN", FeedMeColors.Lime)
        }
        if (meal.screen in ROOT_RECIPE_SCREENS && (!unavailable || statusPresentation.sourceUnavailable)) {
            Text("BROWSE → MAKE MINE", style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Muted)
        } else if (meal.screen in setOf(MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.ADAPT, MealFlowScreen.VARIANT,
                MealFlowScreen.ADAPT_MINE, MealFlowScreen.VARIANT_MINE) && !unavailable) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(when (meal.screen) {
                    MealFlowScreen.ADAPT -> "Make dinner easier."
                    MealFlowScreen.ADAPT_MINE -> "Make it yours."
                    MealFlowScreen.VARIANT, MealFlowScreen.VARIANT_MINE -> "Your meal. Your call."
                    else -> status.first
                }, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(if (meal.screen in setOf(MealFlowScreen.ADAPT, MealFlowScreen.VARIANT, MealFlowScreen.ADAPT_MINE, MealFlowScreen.VARIANT_MINE))
                    "Your original meal stays selected until you choose a change." else status.second, color = FeedMeColors.Muted)
            }
        } else Hero(if (meal.screen == MealFlowScreen.REQUEST) "LESS EFFORT. MORE YOU." else "YOUR NEXT GOOD MEAL.",
            if (meal.screen == MealFlowScreen.REQUEST && !unavailable) "Dinner. With what you have." else status.first,
            if (meal.screen == MealFlowScreen.REQUEST && meal.phase == MealFlowPhase.EDITING && !unavailable)
                "Choose your ingredients, time and energy. Find one meal that fits."
            else status.second)
        statusPresentation.notices.forEach { InfoCard(it.title, it.message) }
        if (meal.postSourceNeedsRefresh && meal.screen in ROOT_RECIPE_SCREENS && meal.pendingRootDraft == null)
            actions.catalog?.refresh?.let { refresh ->
                OutlinedButton(onClick = refresh, enabled = !form.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Check shared recipe again")
                }
            }
        if (form.busy || meal.phase == MealFlowPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (unavailable) {
            if (!statusPresentation.sourceUnavailable)
                InfoCard("Access required", "This screen cannot sign you in, repair your account or approve a recipe. Use Back to return.")
        } else {
            val values = form.values!!
            if (form.dirty) InfoCard("Unsaved edits", "These edits are not the request already sent to the server. Save a valid draft to keep them on this device.")
            if (statusPresentation.originalRequestRetained) {
                InfoCard("Original request retained", if (meal.pendingRootDraft != null) "Your sent Make Mine inputs are retained separately. The current meal is unchanged."
                    else if (meal.pendingMatchesDraft) "The retained command matches your last saved draft."
                    else "Your edited draft does not replace the earlier unresolved command.")
                meal.retryAtMillis?.let { Text("Earliest retry: ${kotlin.time.Instant.fromEpochMilliseconds(it)}", style = MaterialTheme.typography.bodySmall) }
                val retryAction = if (meal.pendingRootDraft != null && meal.screen in ROOT_RECIPE_SCREENS)
                    actions.catalog?.retry?.takeIf { meal.screen == MealFlowScreen.ROOT_MAKE_MINE }
                else if (meal.pendingAdaptation != null || meal.screen in setOf(MealFlowScreen.ADAPT_MINE, MealFlowScreen.VARIANT_MINE))
                    actions.adaptation?.retry else actions.retry
                Primary("Retry original request", retryAction != null && !form.busy && meal.issue !in setOf(MealFlowIssue.REPLAY_EXPIRED,
                    MealFlowIssue.CONTEXT_CHANGED, MealFlowIssue.PREFERENCES_PENDING)) { retryAction?.invoke() }
            }
            IngredientNameLookup(mealLabelIds(meal), picker, form.busy, actions.loadIngredientNames)
            when (meal.screen) {
                MealFlowScreen.REQUEST -> RequestFields(values, form, picker, choices, actions,
                    contextRequired = meal.issue == MealFlowIssue.CONTEXT_REQUIRED)
                MealFlowScreen.ADAPT -> SimplificationChoices(meal, form, actions)
                MealFlowScreen.VARIANT -> SimplificationProposalCard(meal, form, picker, choices, actions)
                MealFlowScreen.ADAPT_MINE -> AdaptationRequestScreen(meal, form, adaptationForm, picker, choices, actions.adaptation)
                MealFlowScreen.VARIANT_MINE -> AdaptationProposalScreen(meal, form, picker, choices, actions.adaptation)
                MealFlowScreen.CATALOG, MealFlowScreen.CATALOG_RECIPE, MealFlowScreen.SAVED_RECIPE, MealFlowScreen.POST_RECIPE, MealFlowScreen.ROOT_MAKE_MINE, MealFlowScreen.ROOT_VARIANT ->
                    CatalogRecipeScreen(meal, picker, form, rootRecipeForm, choices, catalogQuery, actions.catalog)
                MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE -> {
                    val plan = meal.plan
                    if (plan == null) InfoCard("No plan yet", "Return to your draft to find a meal.")
                    else {
                        val presentation = MealPlanPresentation(plan, meal.historical,
                            picker.knownIngredients.associate { it.id to it.name })
                        PlanCard(presentation, meal.screen == MealFlowScreen.RECIPE, choices,
                            recipeAction = actions.recipe.takeIf { meal.screen == MealFlowScreen.RECOMMENDATIONS },
                            recipeEnabled = !form.busy && !form.dirty && presentation.recipeVisible)
                        actions.easier?.takeIf { meal.simplificationAvailable }?.let { action ->
                            OutlinedButton(onClick = action, enabled = !form.busy && !form.dirty,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Make it easier") }
                        }
                        actions.adaptation?.let { action ->
                            if (meal.adaptationAvailable) OutlinedButton(onClick = action.open, enabled = !form.busy && !form.dirty,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Make it mine") }
                            if (meal.adaptation != null) TextButton(onClick = action.reopen, enabled = !form.busy,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Review my saved version") }
                        }
                        if (meal.proposal != null) actions.reopenEasier?.let { action ->
                            TextButton(onClick = action, enabled = !form.busy,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Review saved proposal") }
                        }
                        if (meal.screen == MealFlowScreen.RECOMMENDATIONS) {
                            OutlinedButton(onClick = actions.alternative, enabled = !form.busy && !form.dirty && meal.alternativesAvailable,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Show another option") }
                            TextButton(onClick = actions.previous, enabled = !form.busy && meal.previousAvailable) {
                                Text("Previous option")
                            }
                        }
                        if (meal.screen == MealFlowScreen.RECIPE) actions.cook?.let {
                            Primary("Cook this plan", !form.busy && !form.dirty && presentation.recipeVisible, it)
                        }
                        if (meal.screen == MealFlowScreen.RECIPE) actions.save?.let {
                            OutlinedButton(onClick = it, enabled = !form.busy && !form.dirty && presentation.recipeVisible,
                                modifier = Modifier.fillMaxWidth()) { Text("Add this plan to Saved") }
                        }
                        if (meal.screen == MealFlowScreen.RECIPE) actions.share?.let {
                            OutlinedButton(onClick = it, enabled = !form.busy && !form.dirty && presentation.recipeVisible,
                                modifier = Modifier.fillMaxWidth()) { Text("Share my take") }
                        }
                        InfoCard("Preview, not cooking permission", "Cook this plan checks availability before you confirm. Saving a new copy has its own permission check; cooking from Saved doesn’t require another save. Nothing records repeat-preference feedback or shares a post.")
                    }
                }
            }
            if (meal.screen !in ROOT_RECIPE_SCREENS && meal.screen !in setOf(MealFlowScreen.ADAPT_MINE, MealFlowScreen.VARIANT_MINE)) {
                actions.catalog?.let { catalog -> OutlinedButton(onClick = catalog.open, enabled = !form.busy && !form.dirty,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (meal.savedSource != null) "Return to Saved · Make Mine" else if (meal.catalogSource != null || meal.rootProposal != null) "Return to Browse & Make Mine" else "Browse recipes · Make Mine")
                } }
                MealJourneyShortcuts(actions)
            }
        }
        Text("Made for real life. No perfect-kitchen energy required.", style = MaterialTheme.typography.bodySmall,
            color = FeedMeColors.Muted, modifier = Modifier.padding(vertical = 12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimplificationChoices(meal: MealScreenState, form: MealFormState, actions: MealScreenActions) {
    // Unsaved UI choices only. Once sent, the controller retains the exact goal/consent/key.
    var goal by remember(meal.plan?.id?.value) { mutableStateOf(SimplificationGoal.OVERALL) }
    var differentMeal by remember(meal.plan?.id?.value) { mutableStateOf(false) }
    val available = !form.busy && !form.dirty && meal.phase !in setOf(MealFlowPhase.LOADING, MealFlowPhase.RESOLVING)
    Text("What would help most?", style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SimplificationGoal.entries.forEach { option ->
            FilterChip(selected = goal == option, onClick = { goal = option }, enabled = available,
                modifier = Modifier.heightIn(min = 48.dp), label = { Text(simplificationGoalLabel(option)) })
        }
    }
    Text("A simpler version comes first. Your ingredient exclusions and limits still apply.", color = FeedMeColors.Muted)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = differentMeal, enabled = available,
        role = Role.Checkbox, onValueChange = { differentMeal = it }), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = differentMeal, onCheckedChange = null, enabled = available)
        Text("A different meal is okay if no simpler version fits.", Modifier.weight(1f))
    }
    actions.requestEasier?.let { request -> Primary("Find an easier option", available) { request(goal, differentMeal) } }
    TextButton(onClick = actions.back, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep my meal") }
    if (actions.retainedCooking != null) InfoCard("Already started cooking?",
        "This opens a separate proposal. It won't replace your steps, completed preparation or timers.")
}

@Composable
internal fun SimplificationProposalCard(meal: MealScreenState, form: MealFormState, picker: MealPickerPresentation,
    choices: MealInputChoices, actions: MealScreenActions) {
    val proposal = meal.proposal
    if (proposal == null) {
        InfoCard("No saved proposal", "Your original meal is unchanged. Go back to review it.")
        return
    }
    val labels = picker.knownIngredients.associate { it.id to it.name }
    val original = MealPlanPresentation(proposal.parent.plan, proposal.parent.historical, labels)
    val proposed = MealPlanPresentation(proposal.child.plan, proposal.child.historical, labels)
    val comparison = proposal.comparison
    val canUse = comparison != null && proposed.recipeVisible && !form.busy && !form.dirty &&
        meal.phase !in setOf(MealFlowPhase.RESOLVING, MealFlowPhase.LOADING)
    Text("You asked for ${simplificationGoalLabel(proposal.goal).lowercase()}.", style = MaterialTheme.typography.titleMedium)
    if (comparison == null || !proposed.recipeVisible) {
        InfoCard(when (proposal.child.plan.status) {
            "noMatch" -> "No supported easier option yet."
            "needsConfirmation" -> "Check these ingredients first."
            else -> "This proposal is unavailable."
        }, proposed.reasons.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: "No change has been selected. Keep your original meal or edit your limits.")
        if (proposal.child.plan.missingIngredients.isNotEmpty()) InfoCard("Needs your confirmation",
            proposal.child.plan.missingIngredients.joinToString("\n", transform = proposed::ingredientLine))
    } else {
        Pill(if (comparison.sameMeal) "SAME MEAL · DIFFERENT VERSION" else "A DIFFERENT MEAL", FeedMeColors.Lilac)
        Text(proposed.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        InfoCard("Before → proposed", listOf(
            simplificationEffortLine("Hands-on", comparison.before.activeMinutes, comparison.after.activeMinutes),
            simplificationEffortLine("Total", comparison.before.totalMinutes, comparison.after.totalMinutes),
            simplificationEffortLine("Cleanup", comparison.before.cleanupMinutes, comparison.after.cleanupMinutes),
            simplificationEffortLine("Utensils", comparison.before.utensilCount, comparison.after.utensilCount, ""),
            simplificationEffortLine("Servings", comparison.before.servings, comparison.after.servings, ""),
            simplificationEffortLine("Your selected energy", simplificationEnergyLabel(comparison.before.energy), simplificationEnergyLabel(comparison.after.energy), ""),
            simplificationEffortLine("Meal mode", simplificationModeLabel(comparison.before.mode), simplificationModeLabel(comparison.after.mode), ""),
        ).joinToString("\n"))
        InfoCard("Less work in", comparison.improvedDimensions.joinToString(transform = ::simplificationDimensionLabel))
        if (comparison.regressedDimensions.isNotEmpty()) InfoCard("The tradeoff",
            "These increase: " + comparison.regressedDimensions.joinToString(transform = ::simplificationDimensionLabel) +
                ". Review the before-and-after values before choosing.")
        if (proposed.changes.isNotEmpty()) InfoCard("What changed", proposed.changes.joinToString("\n"))
        if (proposed.reasons.isNotEmpty()) InfoCard("Why this was offered", proposed.reasons.joinToString("\n"))
        if (comparison.addedIngredientIds.isNotEmpty() || comparison.removedIngredientIds.isNotEmpty() || comparison.changedIngredientIds.isNotEmpty()) {
            fun ingredientNames(ids: List<String>) = ids.joinToString { proposed.ingredientName(it) ?: "Label unavailable ($it)" }
            InfoCard("Ingredient changes", listOfNotNull(
                comparison.addedIngredientIds.takeIf { it.isNotEmpty() }?.let { "Added: ${ingredientNames(it)}" },
                comparison.removedIngredientIds.takeIf { it.isNotEmpty() }?.let { "Removed: ${ingredientNames(it)}" },
                comparison.changedIngredientIds.takeIf { it.isNotEmpty() }?.let { "Amount or preparation changed: ${ingredientNames(it)}" },
            ).joinToString("\n"))
        }
        InfoCard("Original ingredients", original.previewIngredients.joinToString("\n"))
        InfoCard("Proposed ingredients", proposed.previewIngredients.joinToString("\n"))
        if (comparison.addedEquipmentIds.isNotEmpty() || comparison.removedEquipmentIds.isNotEmpty()) {
            fun equipmentNames(ids: List<String>) = ids.joinToString { id -> choices.equipment.firstOrNull { it.id == id }?.label ?: "Unresolved equipment ($id)" }
            InfoCard("Equipment changes", listOfNotNull(
                comparison.addedEquipmentIds.takeIf { it.isNotEmpty() }?.let { "Added: ${equipmentNames(it)}" },
                comparison.removedEquipmentIds.takeIf { it.isNotEmpty() }?.let { "Removed: ${equipmentNames(it)}" },
            ).joinToString("\n"))
        }
        if (comparison.addedStepIds.isNotEmpty() || comparison.removedStepIds.isNotEmpty() || comparison.changedStepIds.isNotEmpty())
            Text("Preparation changed. Read the proposed steps; completed work is not transferred.", color = FeedMeColors.Muted)
        FeedMeDetails("Read proposed recipe & version") { PlanCard(proposed, true, choices) }
    }
    FeedMeDetails("Review original meal & version") { PlanCard(original, true, choices) }
    InfoCard("Cooking stays separate", "Choosing this meal won't change a running cook. Finish or stop and sync an unfinished session before starting a separate one. Your steps and timers are not transferred.")
    if (comparison != null && proposed.recipeVisible) actions.acceptEasier?.let { Primary("Use this meal", canUse, it) }
    actions.keepOriginal?.let { action ->
        OutlinedButton(onClick = action, enabled = !form.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Keep original") }
    }
    if (comparison == null) actions.anotherAfterEasier?.let { action ->
        OutlinedButton(onClick = action, enabled = !form.busy && !form.dirty,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Something else") }
    }
    actions.editEasierLimits?.let { action ->
        TextButton(onClick = action, enabled = !form.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit limits") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealJourneyShortcuts(actions: MealScreenActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.retainedCooking?.let { OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("View retained cooking") } }
        actions.cookbook?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Saved") } }
        actions.drafts?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("My private drafts") } }
        actions.circles?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open my circles") } }
        actions.reports?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Resume report") } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestFields(values: MealFormValues, form: MealFormState, picker: MealPickerPresentation,
    choices: MealInputChoices, actions: MealScreenActions, contextRequired: Boolean) {
    actions.reviewPlanSource?.let { review ->
        TextButton(onClick = review, enabled = !form.busy && !form.dirty,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Review source for a new version") }
    }
    if (values.sourceRecipeVersionId != null || values.sourcePostId != null) {
        InfoCard("Your Make Mine version", "This draft belongs to your selected recipe version. To start an ordinary meal match, explicitly switch below.")
        actions.clearDirectSource?.let { clear -> TextButton(onClick = clear, enabled = !form.busy && !form.dirty,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Find a different meal instead") } }
        if (form.dirty) Text("Save your draft before switching away from this recipe.", style = MaterialTheme.typography.bodySmall)
    }
    if (values.savedRecipeId != null) {
        InfoCard(if (values.savedMakeMine) "Your Saved Make Mine version" else "Cooking from Saved",
            if (values.savedMakeMine) "This draft belongs to your selected version. Your original saved copy is unchanged. Switch explicitly before finding an ordinary meal."
            else "Review your choices for this saved recipe. We’ll check its current availability and your preferences before cooking.")
        actions.clearSavedSource?.let { clear ->
            TextButton(onClick = clear, enabled = !form.busy && !form.dirty, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Find a different meal instead")
            }
            if (form.dirty) Text("Save your draft before switching away from this recipe.",
                style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        }
    }
    SectionTitle("01", "What have you got?")
    Text("Pick ingredients you’ve checked for this meal. Pantry reports don’t confirm freshness or allergy safety.", color = FeedMeColors.Muted)
    Input("Search ingredient names", form.searchText, actions.searchText)
    OutlinedButton(onClick = actions.search, enabled = !form.busy && form.searchText.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Search ingredients") }
    if (picker.searchPhase == IngredientPickerPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (picker.issue != IngredientPickerIssue.NONE) InfoCard("Ingredient lookup", pickerMessage(picker))
    if (picker.searchPhase == IngredientPickerPhase.EMPTY) Text("No ingredient matches. Try another name; nothing has been added.")
    picker.searchResults.forEach { option -> RequestIngredientResult(option,
        selected = option.id in values.ingredientIds, excluded = option.id in values.exclusions,
        select = { actions.edit { form -> form.copy(ingredientIds = toggle(form.ingredientIds, option.id)) } },
        exclude = { actions.edit { form -> form.copy(exclusions = toggle(form.exclusions, option.id)) } }) }
    if (picker.searchHasMore) TextButton(enabled = !form.busy, onClick = actions.moreIngredients, modifier = Modifier.heightIn(min = 48.dp)) { Text("More ingredient matches") }
    if (values.ingredientIds.isNotEmpty()) {
        Text("Selected for this meal", style = MaterialTheme.typography.titleMedium)
        values.ingredientIds.forEach { id ->
            TextButton(onClick = { actions.edit { it.copy(ingredientIds = it.ingredientIds.filterNot { selected -> selected == id }) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Remove · ${picker.knownIngredients.firstOrNull { it.id == id }?.name ?: "Label unavailable ($id)"}")
            }
        }
    }
    RequestExplicitExclusions(values.exclusions, picker) { id ->
        actions.edit { current -> current.copy(exclusions = current.exclusions.filterNot { it == id }) }
    }
    InfoCard("Saved preferences stay in force", "Matching also applies your saved hard exclusions. Removing an explicit exclusion here does not remove a saved preference.")
    SectionTitle("02", "Time, energy and cleanup.")
    BlueprintMealRefinementControls(form, choices, actions)
    Input("Total minutes available · optional", values.totalMinutes, { text -> actions.edit { it.copy(totalMinutes = text) } })
    Text("Your energy today", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MealEnergy.entries.forEach { energy -> FilterChip(values.energy == energy,
            { actions.edit { it.copy(energy = energy) } }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(energyLabel(energy)) }) }
    }
    Input("Cleanup limit in minutes · optional", values.cleanupMinutes, { text -> actions.edit { it.copy(cleanupMinutes = text) } })
    Text("Leave blank for no limit. Zero means no cleanup time.", style = MaterialTheme.typography.bodySmall)
    Input("Servings", values.servings, { text -> actions.edit { it.copy(servings = text) } })
    Text(mealOptionsSummary(values, choices), style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
    Text(mealRefinementSummary(values, choices), style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
    FeedMeDetails("More meal choices · optional") {
        Text("What kind of meal?", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MealMode.entries.forEach { mode -> FilterChip(selected = values.mode == mode,
                onClick = { actions.edit { it.copy(mode = mode) } }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(modeLabel(mode)) }) }
        }
        if (values.mode == MealMode.IMPROVE) {
            Input("What’s already prepared?", values.baseDescription, { text -> actions.edit { it.copy(baseDescription = text) } }, singleLine = false)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasePreparation.entries.forEach { preparation -> FilterChip(values.basePreparation == preparation,
                    { actions.edit { it.copy(basePreparation = preparation) } }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(baseLabel(preparation)) }) }
            }
            InfoCard("No ingredient guessing", if (values.baseIngredientIds == null) "The ingredients in this existing meal are not confirmed. Its description alone cannot establish composition or safety."
                else "The existing meal’s ingredient IDs are retained from your saved explicit input. Availability choices above do not replace them.")
        }
        ChoiceSection("Equipment you can use", choices.equipment, values.equipmentIds, "Remove unavailable equipment",
            toggle = { id -> actions.edit { it.copy(equipmentIds = toggle(it.equipmentIds, id)) } },
            remove = { id -> actions.edit { it.copy(equipmentIds = withoutMealInputChoice(it.equipmentIds, id)) } })
        Input("Hands-on minutes · optional", values.activeMinutes, { text -> actions.edit { it.copy(activeMinutes = text) } })
        ChoiceSection("Taste · optional", choices.tastes, values.tasteTags, "Remove unavailable taste",
            toggle = { id -> actions.edit { it.copy(tasteTags = toggle(it.tasteTags, id)) } },
            remove = { id -> actions.edit { it.copy(tasteTags = withoutMealInputChoice(it.tasteTags, id)) } })
    }
    SectionTitle("03", "Let’s find your fit.")
    if (contextRequired) {
        Text("Load your saved preferences and pantry before finding a meal.", color = FeedMeColors.Muted)
        OutlinedButton(onClick = actions.context, enabled = !form.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Load preferences and pantry") }
    }
    Primary(if (values.savedRecipeId == null) "Find a meal  ↗" else "Check this saved meal  ↗",
        !form.busy && values.sourceRecipeVersionId == null && values.sourcePostId == null && !values.savedMakeMine, actions.find)
    OutlinedButton(onClick = actions.saveDraft, enabled = !form.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save draft on this device") }
    FeedMeDetails("Pantry, preferences & refresh · optional") {
        OutlinedButton(onClick = actions.context, enabled = !form.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Refresh preferences and pantry") }
        actions.kitchen?.let { open ->
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { open(KitchenInputPage.PANTRY) }, enabled = !form.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit pantry") }
                OutlinedButton(onClick = { open(KitchenInputPage.PREFERENCES) }, enabled = !form.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Food preferences") }
            }
        }
        OutlinedButton(onClick = actions.pantry, enabled = !form.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Check pantry reports") }
        picker.pantryItems.forEach { item ->
            InfoCard(item.name ?: "Ingredient label unavailable (${item.ingredientId})",
                "Reported: ${item.presence} · confirmation: ${item.confirmationStatus.valueOrNull() ?: "not recorded"}" +
                    (item.confirmedAt.valueOrNull()?.let { "\nConfirmed at: $it" } ?: "\nNo confirmation time") +
                    if (item.historical) "\nHistorical report, not confirmed for this meal." else "\nCheck it yourself before selecting it for this meal.")
        }
        if (picker.pantryHasMore) TextButton(enabled = !form.busy, onClick = actions.morePantry, modifier = Modifier.heightIn(min = 48.dp)) { Text("More pantry reports") }
    }
    Text("Only your selected ingredients and choices are used. Free-text interpretation and new dietary presets aren’t connected yet.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
}

/** One catalog result from the production request form, kept independently hostable so native
 * accessibility acceptance can exercise the exact control without relying on a long scroll. */
@Composable
internal fun RequestIngredientResult(option: IngredientRow, selected: Boolean, excluded: Boolean,
    select: () -> Unit, exclude: () -> Unit) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value = selected, role = Role.Checkbox,
                onValueChange = { select() }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = null)
                Text(option.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            if (option.historical) Text("Previously fetched label", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = exclude, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (excluded) "Remove explicit exclusion" else "Exclude this ingredient")
            }
        }
    }
}

/** Exact retained exclusions from the production request form. */
@Composable
internal fun RequestExplicitExclusions(exclusions: List<String>, picker: MealPickerPresentation,
    remove: (String) -> Unit) {
    if (exclusions.isEmpty()) return
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your explicit exclusions", style = MaterialTheme.typography.titleMedium)
            exclusions.forEachIndexed { index, id -> key(index, id) {
                val name = picker.knownIngredients.firstOrNull { it.id == id }?.name ?: "Label unavailable ($id)"
                TextButton(onClick = { remove(id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Remove exclusion · $name")
                }
            } }
        }
    }
}

@Composable
internal fun PlanCard(view: MealPlanPresentation, full: Boolean, choices: MealInputChoices,
    recipeAction: (() -> Unit)? = null, recipeEnabled: Boolean = false) {
    val recipe = view.recipe
    Surface(color = FeedMeColors.Lime, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (view.historical) "RETAINED PLAN · HISTORICAL" else "PLAN SNAPSHOT", style = MaterialTheme.typography.labelMedium)
            Text(view.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            if (full) recipe?.summary?.valueOrNull()?.let { Text(it) }
            Text(view.effortSummary)
            if (recipe != null) Text("${recipe.servings.jsonToken} servings")
            Text("Mode: ${view.plan.mode.valueOrNull() ?: "not resolved"} · status: ${view.plan.status}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (view.historical) InfoCard("Past facts, not a fresh check", "This retained plan does not prove current recipe rights, recall status, ingredient availability or permission to cook/save.")
    if (view.unresolvedIngredientCount > 0) Text("Unknown ingredient names are shown explicitly. Do not substitute a guess.", style = MaterialTheme.typography.bodySmall)
    if (!full) InfoCard("Ingredients", view.previewIngredients.takeIf { it.isNotEmpty() }?.joinToString("\n")
        ?: "Ingredients unavailable for this plan.")
    if (view.plan.missingIngredients.isNotEmpty()) InfoCard("Missing items", view.plan.missingIngredients.joinToString("\n", transform = view::ingredientLine))
    if (view.recipeVisible || view.reasons.isNotEmpty()) InfoCard("Why this fits", view.reasons.takeIf { it.isNotEmpty() }?.take(2)?.joinToString("\n")
        ?: "No fit explanation was recorded.")
    if (view.reasons.size > 2) FeedMeDetails("More about this match") {
        view.reasons.drop(2).forEach { Text(it) }
    }
    if (view.changes.isNotEmpty()) InfoCard("What changed", view.changes.joinToString("\n"))
    if (!full) recipeAction?.let { Primary("View recipe", recipeEnabled, it) }
    if (full && recipe != null && view.recipeVisible) {
        SectionTitle("01", "Ingredients, exactly as planned.")
        recipe.ingredients.forEach { Text(view.ingredientLine(it)) }
        Text("Equipment: " + recipe.equipmentIds.joinToString { recipeEquipmentLabel(it, choices) })
        recipe.waitingMinutes.valueOrNull()?.let { Text("Waiting: ${it.jsonToken} min") }
        recipe.cleanupMinutes.valueOrNull()?.let { Text("Cleanup: ${it.jsonToken} min") }
        recipe.estimateNote.valueOrNull()?.let { InfoCard("Estimate notes", it) }
        SectionTitle("02", "Read the whole plan.")
        recipe.steps.forEach { step ->
            Surface(color = if (step.mandatorySafetyStep) FeedMeColors.Lilac else FeedMeColors.Line,
                shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Step ${step.position.jsonToken}" + if (step.mandatorySafetyStep) " · Required safety step" else "", style = MaterialTheme.typography.titleMedium)
                    Text(step.instruction)
                    RecipeStepMetadata(recipe, step, view::ingredientLine, choices)
                }
            }
        }
        FeedMeDetails("Recipe source & version") {
            Text("Plan revision ${view.plan.version.jsonToken} · recipe revision ${recipe.version.jsonToken}\nCatalog ${view.plan.catalogRevision}", style = MaterialTheme.typography.bodySmall)
            recipe.reviewerLabel.valueOrNull()?.let { Text("Recorded reviewer: $it", style = MaterialTheme.typography.bodySmall) }
            recipe.contentLicense.valueOrNull()?.let { Text("Recorded license: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable internal fun Hero(eyebrow: String, title: String, copy: String) {
    Surface(color = FeedMeColors.Blue, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(eyebrow, color = FeedMeColors.Lime, style = MaterialTheme.typography.labelMedium)
            Text(title, color = Color.White, style = MaterialTheme.typography.displayMedium, modifier = Modifier.semantics { heading() })
            Text(copy, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
@Composable internal fun Pill(text: String, color: Color) { Surface(color = color, shape = RoundedCornerShape(50)) {
    Text(text, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
} }
@Composable internal fun SectionTitle(number: String, title: String) {
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(number, FeedMeColors.SoftBlue); Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
    }
}
@Composable internal fun InfoCard(title: String, text: String) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); Text(text, style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
        }
    }
}
@Composable private fun Input(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean = true) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine,
        shape = RoundedCornerShape(18.dp))
}
@Composable internal fun Primary(label: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) { Text(label) }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ChoiceSection(title: String, choices: List<MealInputChoice>, selected: List<String>,
    removalLabel: String, toggle: (String) -> Unit, remove: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (choices.isEmpty()) Text("No configured choices available.", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        choices.forEach { FilterChip(it.id in selected, { toggle(it.id) }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(it.label) }) }
    }
    val unresolved = selected.filter { id -> choices.none { it.id == id } }
    if (unresolved.isNotEmpty()) Text("These selected choices aren’t in the current list. Keep them or remove them from this meal.",
        style = MaterialTheme.typography.bodySmall)
    unresolved.forEachIndexed { index, id ->
        key(index, id) {
            TextButton(onClick = { remove(id) }, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = FeedMeColors.Ink)) {
                Text("$removalLabel · $id")
            }
        }
    }
}
/** Explicit removal from the latest form only. Exact IDs match the selection chips; a
 * repeated/stale removal must never toggle an absent choice back into the request. */
internal fun withoutMealInputChoice(selected: List<String>, id: String): List<String> =
    selected.filterNot { it == id }

/** Render entered refinements even while their optional editor is collapsed. No validation or
 * semantic default is inferred from empty or malformed text. */
internal fun mealRefinementSummary(values: MealFormValues, choices: MealInputChoices): String {
    val parts = listOfNotNull(values.totalMinutes.takeIf { it.isNotEmpty() }?.let { "Total minutes entered: $it" },
        values.activeMinutes.takeIf { it.isNotEmpty() }?.let { "Hands-on minutes entered: $it" },
        values.cleanupMinutes.takeIf { it.isNotEmpty() }?.let { "Cleanup minutes entered: $it" },
        values.tasteTags.takeIf { it.isNotEmpty() }?.joinToString { id ->
            choices.tastes.firstOrNull { it.id == id }?.label ?: "Unresolved taste ($id)"
        })
    return if (parts.isEmpty()) "Time & taste: not set" else parts.joinToString(" · ")
}
/** Keep active choices and unknown base composition visible while their editor is collapsed. */
internal fun mealOptionsSummary(values: MealFormValues, choices: MealInputChoices): String = buildString {
    append("Mode: "); append(modeLabel(values.mode)); append(" · Equipment: ")
    append(if (values.equipmentIds.isEmpty()) "not set" else values.equipmentIds.joinToString { id ->
        choices.equipment.firstOrNull { it.id == id }?.label ?: "Unresolved equipment ($id)"
    })
    if (values.mode == MealMode.IMPROVE) {
        append("\nExisting meal: "); append(values.baseDescription.ifEmpty { "not entered" })
        append(" · Preparation: "); append(baseLabel(values.basePreparation))
        append("\nExisting meal ingredients: ")
        append(values.baseIngredientIds?.let { "${it.size} recorded IDs; availability choices do not replace them." }
            ?: "not confirmed; the description does not establish composition or safety.")
    }
}
private fun toggle(values: List<String>, id: String) = if (id in values) values.filterNot { it == id } else values + id
private fun modeLabel(mode: MealMode) = when (mode) { MealMode.AUTO -> "Find my fit"; MealMode.COOK -> "Cook"; MealMode.ASSEMBLE -> "Assemble"; MealMode.IMPROVE -> "Improve a meal" }
private fun energyLabel(energy: MealEnergy) = when (energy) { MealEnergy.ASSEMBLE -> "Barely any energy"; MealEnergy.LITTLE -> "A little effort"; MealEnergy.HAPPY -> "Happy to cook" }
private fun baseLabel(value: BasePreparation) = when (value) { BasePreparation.ALREADY_PREPARED -> "Already prepared"; BasePreparation.PARTIALLY_PREPARED -> "Partly prepared"; BasePreparation.UNKNOWN -> "Not sure" }
private fun pickerMessage(state: MealPickerPresentation) = when (state.issue) {
    IngredientPickerIssue.NONE -> ""
    IngredientPickerIssue.OFFLINE -> "You’re offline. Any retained labels are historical; no ingredient has been selected automatically."
    IngredientPickerIssue.INVALID_QUERY -> "Enter a supported ingredient search. Your text has not become an ingredient ID."
    IngredientPickerIssue.INVALID_IDS -> "The requested ingredient references cannot be loaded within the configured limit."
    IngredientPickerIssue.UNRESOLVED -> "Some ingredient names remain unavailable. No missing name has been guessed."
    IngredientPickerIssue.CACHE_EXPIRED -> "Previously fetched labels expired. Search again online."
    IngredientPickerIssue.PAGE_LIMIT -> "This lookup reached its page limit. Refine the search; unfetched items are not known to be absent."
    IngredientPickerIssue.CONTEXT_CHANGED -> "The catalog or pantry changed while loading. Refresh before using the results."
    IngredientPickerIssue.INVALID_REPLY -> "The lookup response could not be verified."
    IngredientPickerIssue.STORAGE -> "The ingredient-label cache could not be saved."
    IngredientPickerIssue.SESSION_UNAVAILABLE -> "Your session changed. Private lookup state is unavailable."
    IngredientPickerIssue.RETRY_LATER -> "Try again later" + (state.retryAfterSeconds?.let { " (server delay: $it seconds)." } ?: ".")
}
