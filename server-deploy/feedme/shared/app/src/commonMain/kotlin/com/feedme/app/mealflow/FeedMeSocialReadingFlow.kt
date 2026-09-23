package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeTheme
import com.feedme.app.blueprint.*
import com.feedme.app.release.V1MobileReleaseScope
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import com.feedme.contracts.WireKind
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.social.*
import com.feedme.mealflow.notifications.NotificationReadController
import com.feedme.mealflow.notifications.NotificationReadPhase
import kotlinx.coroutines.launch

/** Original screens over bounded, current authorized observations. Mounting performs no I/O.
 * Media IDs, observed capabilities and notification targets never become access grants here.
 * Host callbacks name their actual destinations; unavailable functions remain disabled. */
@Composable
fun FeedMeSocialReadingFlow(controller: SocialReadController, hostIsCurrent: () -> Boolean,
    onHome: () -> Unit, onSaved: () -> Unit, onClose: () -> Unit,
    onSettings: (() -> Unit)? = null, onCircles: (() -> Unit)? = null,
    onCreatePost: (() -> Unit)? = null,
    onReportPost: ((SocialReadState) -> Unit)? = null, reportFailure: FailureReason? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    onOpenThread: ((SocialReadState, String) -> Unit)? = null,
    onStartDirect: ((SocialReadState, String) -> Unit)? = null,
    onDeletePost: ((SocialReadState) -> Unit)? = null,
    onAskRecipe: ((SocialReadState) -> Unit)? = null,
    onRemixTrail: ((SocialReadState) -> Unit)? = null,
    onNotificationSettings: (() -> Unit)? = null,
    onMakeMine: ((SocialReadState) -> Unit)? = null,
    onSaveRecipe: ((SocialReadState) -> Unit)? = null,
    recipeActionStatus: String? = null,
    onOpenNotificationThread: ((SocialReadState, String) -> Unit)? = null,
    notificationReads: NotificationReadController? = null,
    onEditProfile: ((SocialReadState) -> Unit)? = null,
    profileActionStatus: String? = null,
    dispatchHost: ((isCurrent: () -> Boolean, action: () -> Unit) -> Unit)? = null,
    homeAvailable: Boolean = true,
    onPostPlacement: ((SocialReadState, SocialPostSnapshot, Boolean, () -> Boolean) -> Unit)? = null,
    postPlacementStatus: String? = null,
    onResumePostPlacement: ((SocialReadState, () -> Boolean) -> Unit)? = null,
    onReaction: ((SocialReadState, SocialPostSnapshot, Boolean, () -> Boolean) -> Unit)? = null,
    onResumeReaction: ((SocialReadState, () -> Boolean) -> Unit)? = null,
    reactionStatus: String? = null) {
    val state = controller.states.collectAsState().value
    val photoState = controller.photos.collectAsState().value
    val scope = rememberCoroutineScope()
    val currentHomeAvailable by rememberUpdatedState(homeAvailable)
    val visit = remember(controller, state) { SocialReadingVisit() }
    DisposableEffect(visit) { onDispose { visit.retire() } }
    val renderedEpoch = visit.epoch
    var details by remember(controller, state) { mutableStateOf(false) }
    val readState = notificationReads?.states?.collectAsState()?.value
    val readVisible = readState != null && readState.phase != NotificationReadPhase.HIDDEN
    fun localCurrent(epoch: Any = renderedEpoch) = visit.attached && visit.epoch === epoch && controller.states.value === state
    fun sourceCurrent() = visit.attached && hostIsCurrent() && controller.isCurrent(state)
    fun readDialogHidden() = notificationReads?.states?.value?.phase?.let { it == NotificationReadPhase.HIDDEN } != false
    fun underlayCurrent(epoch: Any = renderedEpoch) = localCurrent(epoch) && sourceCurrent() &&
        readDialogHidden() && !visit.more && !visit.reactionActors && visit.notification == null
    fun current() = underlayCurrent()
    fun departureCurrent() = localCurrent() && hostIsCurrent() && readDialogHidden() &&
        !visit.more && !visit.reactionActors && visit.notification == null
    fun recoveryRoute() = (onResumePostPlacement != null || onResumeReaction != null) &&
        state.screen in setOf(SocialReadScreen.TODAY, SocialReadScreen.PROFILE_PLATE) &&
        state.phase !in setOf(SocialReadPhase.LOADING, SocialReadPhase.UNAVAILABLE)
    fun recoveryCurrent(epoch: Any = renderedEpoch) = recoveryRoute() && localCurrent(epoch) &&
        hostIsCurrent() && readDialogHidden() && !visit.more && !visit.reactionActors && visit.notification == null
    fun dispatch(guard: () -> Boolean, action: () -> Unit) {
        if (!guard()) return
        // Pass the exact presentation continuation through any host-owned queue.
        // This is local admission only; controllers still own all private authority.
        if (dispatchHost != null) dispatchHost(guard, action) else action()
    }
    fun moreCurrent() = localCurrent() && sourceCurrent() && readDialogHidden() && visit.more && !visit.reactionActors && visit.notification == null
    fun recoveryMoreCurrent() = recoveryRoute() && localCurrent() && hostIsCurrent() && readDialogHidden() &&
        visit.more && !visit.reactionActors && visit.notification == null
    fun openMore() { if (current() || recoveryCurrent()) visit.change(more = true) }
    fun closeMore() { if (localCurrent() && visit.more) visit.change() }
    fun fromMore(action: suspend (Any) -> Unit) {
        if (!moreCurrent()) return
        // Own close advances the local visit once. A newer overlay/close or source
        // publication retires this queued continuation without dispatching its action.
        val continuation = visit.change()
        scope.launch { if (underlayCurrent(continuation)) action(continuation) }
    }
    suspend fun prepareNotificationRead(id: String?, epoch: Any = renderedEpoch) {
        val owner = notificationReads ?: return
        if (!underlayCurrent(epoch)) return
        val chosen = controller.selectNotificationRead(state, id)
        if (chosen is PortResult.Value && underlayCurrent(epoch)) owner.prepare(chosen.value)
    }
    fun read(action: suspend () -> Unit) {
        if (current()) scope.launch { if (current()) action() }
    }
    fun exit(action: () -> Unit) {
        // The real experience owns leave + sibling handoff as one admission decision.
        // Retiring here first would stale that exact owner callback and cancel navigation.
        dispatch(::current, action)
    }
    fun home() {
        // Availability can change without replacing this Social observation. Carry it
        // through the host queue rather than reviving an old rendered Cook callback.
        dispatch({ current() && currentHomeAvailable }, onHome)
    }
    fun back() {
        if (!localCurrent()) return
        if (visit.more) { visit.change(); return }
        if (visit.reactionActors) { visit.change(); return }
        if (visit.notification != null) { visit.change(); return }
        // Local departure must still cancel a loading read or an observation whose
        // deadline passed before its redaction timer delivered the next state.
        if (!departureCurrent()) return
        if (state.screen in setOf(SocialReadScreen.POST, SocialReadScreen.STORY) ||
            state.screen == SocialReadScreen.PROFILE_PLATE && state.profileReturnsToToday) scope.launch {
            if (departureCurrent()) controller.back(state)
        } else dispatch(::departureCurrent, onClose)
    }
    fun navigate(destination: BlueprintScreenId) {
        if (!V1MobileReleaseScope.allowsScreen(destination)) return
        when (destination) {
            BlueprintScreenId.HOME -> home()
            BlueprintScreenId.COOKBOOK -> exit(onSaved)
            BlueprintScreenId.TODAY -> read { controller.openToday() }
            BlueprintScreenId.PROFILE_PLATE -> read { controller.openProfilePlate() }
            BlueprintScreenId.INBOX -> read { controller.openInbox() }
            else -> Unit
        }
    }
    val active = current()
    val visible = sourceCurrent() && state.phase in setOf(SocialReadPhase.READY, SocialReadPhase.EMPTY)
    val availablePhotos = if (visible && photoState.owner === state) photoState.photos else emptyList()
    var photosNotDisplayed = 0
    @Composable fun boundMedia(post: SocialPostSnapshot): BlueprintSocialMedia? {
        val photo = availablePhotos.singleOrNull { it.postId == post.id && it.postVersion == post.version && it.isCurrent() }
        val painter = rememberSocialPhotoPainter(photo) { sourceCurrent() && photoState.owner === state }
        if (photo != null && painter == null) photosNotDisplayed++
        return if (photo != null && painter != null) {
            val version = photo.postVersion.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val acl = photo.aclVersion.toLongOrNull()?.takeIf { it > 0 } ?: return null
            BlueprintSocialMedia.BoundAccess(photo.postId, version, photo.mediaId, acl, painter, photo.description)
        } else null
    }
    val posts = if (visible) state.posts.mapNotNull { post -> key(post.id, post.version) {
        socialPost(post, state.screen == SocialReadScreen.TODAY, boundMedia(post))
    } } else emptyList()
    // Viewer identity always comes from getMe. A viewed author's fresh Post.author
    // summary is not a User document and must never turn their Plate into our account.
    val viewerId = state.selfProfile?.socialText("id").takeIf { visible }
    val profile = if (visible && state.screen == SocialReadScreen.PROFILE_PLATE) {
        if (viewerId != null && state.profileUserId == viewerId) state.selfProfile?.let(::socialProfile)
        else state.viewedAuthor?.takeIf { it.userId == state.profileUserId }?.let { author ->
            socialPerson(author.userId, author.displayName)?.let { BlueprintSocialProfile(it, current = true) }
        }
    } else null
    val ownProfile = visible && state.screen == SocialReadScreen.PROFILE_PLATE && !viewerId.isNullOrBlank() &&
        state.profileUserId == viewerId && profile?.person?.userId == viewerId
    val profileAccountControls = state.screen != SocialReadScreen.PROFILE_PLATE || ownProfile
    val canEditProfile = ownProfile && onEditProfile != null
    val reactionPost = state.selectedPost?.takeIf { visible &&
        state.screen in setOf(SocialReadScreen.POST, SocialReadScreen.STORY) &&
        it.document.socialText("status") == "published" && !viewerId.isNullOrBlank() }
    val ownReaction = reactionPost?.let { actual ->
        actual.document.member("myReaction")?.let { socialOwnReaction(it, actual.id, viewerId) }
    }
    val canSetReaction = onReaction != null && reactionPost?.document?.members("capabilities")?.any { it.stringOrNull() == "react" } == true
    val reactAction = if (state.screen == SocialReadScreen.STORY) BlueprintSocialAction.STORY_REACT else BlueprintSocialAction.POST_REACT
    val removeReactionAction = if (state.screen == SocialReadScreen.STORY) BlueprintSocialAction.STORY_REMOVE_REACTION else BlueprintSocialAction.POST_REMOVE_REACTION
    val reactionActions = buildSet {
        if (canSetReaction) add(reactAction)
        if (onReaction != null && ownReaction != null) add(removeReactionAction)
    }
    fun reviewReaction(remove: Boolean, presentationCurrent: () -> Boolean) {
        val actual = reactionPost ?: return
        fun entryCurrent() = presentationCurrent() && sourceCurrent() && state.selectedPost === actual &&
            controller.states.value === state && (if (remove) ownReaction != null else canSetReaction)
        dispatch(::entryCurrent) { onReaction?.invoke(state, actual, remove, ::entryCurrent) }
    }
    val authorReactions = if (visible && state.screen == SocialReadScreen.POST)
        state.selectedPost?.let { socialReactionActors(it, viewerId) } else null
    val reportAction = if (state.screen == SocialReadScreen.STORY) BlueprintSocialAction.STORY_REPORT else BlueprintSocialAction.POST_REPORT
    val canReport = onReportPost != null && controller.canReportPost(state)
    val directRecipient = if (visible && onStartDirect != null && state.screen in setOf(SocialReadScreen.POST, SocialReadScreen.STORY))
        state.selectedPost?.document?.takeIf { it.members("capabilities").any { value -> value.stringOrNull() == "reply" } }
            ?.member("author")?.socialText("userId")?.takeIf { it != state.selfProfile?.socialText("id") } else null
    val replyAction = if (state.screen == SocialReadScreen.STORY) BlueprintSocialAction.STORY_REPLY else BlueprintSocialAction.POST_REPLY
    val canDelete = visible && onDeletePost != null && state.screen == SocialReadScreen.POST &&
        state.selectedPost?.document?.let { doc ->
            doc.member("author")?.socialText("userId") == state.selfProfile?.socialText("id") &&
                doc.members("capabilities").any { it.stringOrNull() == "delete" }
        } == true
    // This is only an entry affordance. The root and mutation owner recheck the exact
    // actual source; an edit capability never enables unrelated post-editing fields.
    val placementPost = state.selectedPost?.takeIf { actual ->
        visible && onPostPlacement != null && state.screen == SocialReadScreen.POST &&
            actual.document.socialText("status") == "published" && !viewerId.isNullOrBlank() &&
            actual.document.member("author")?.socialText("userId") == viewerId &&
            actual.document.members("capabilities").any { it.stringOrNull() == "edit" } &&
            actual.document.member("keepOnPlate")?.booleanOrNull() != null
    }
    val placementKeep = placementPost?.document?.member("keepOnPlate")?.booleanOrNull()
    val placementAction = placementKeep?.let { if (it) BlueprintSocialAction.REVIEW_REMOVE_FROM_PLATE
        else BlueprintSocialAction.KEEP_ON_PLATE }
    val placementActions = placementAction?.let { setOf(it) } ?: emptySet()
    fun reviewPlacement(keep: Boolean, presentationCurrent: () -> Boolean) {
        val actual = placementPost ?: return
        fun entryCurrent() = presentationCurrent() && sourceCurrent() && state.selectedPost === actual &&
            controller.states.value === state && placementKeep != keep
        dispatch(::entryCurrent) { onPostPlacement?.invoke(state, actual, keep, ::entryCurrent) }
    }
    val canAsk = visible && onAskRecipe != null && state.screen in setOf(SocialReadScreen.POST, SocialReadScreen.STORY) &&
        state.selectedPost?.document?.let { doc -> doc.member("author")?.socialText("userId") != state.selfProfile?.socialText("id") &&
            doc.members("capabilities").any { it.stringOrNull() == "askRecipe" } } == true
    val canReadRemixes = visible && onRemixTrail != null && state.screen == SocialReadScreen.POST && state.selectedPost != null
    val canNextStory = visible && controller.canNextStory(state)
    val canViewAuthor = visible && controller.canOpenStoryAuthor(state)
    val recipeCapabilities = if (visible && state.screen in setOf(SocialReadScreen.POST, SocialReadScreen.STORY))
        state.selectedPost?.document?.members("capabilities")?.mapNotNull { it.stringOrNull() }.orEmpty() else emptyList()
    val makeMineAction = if (state.screen == SocialReadScreen.STORY) BlueprintSocialAction.STORY_MAKE_MINE else BlueprintSocialAction.POST_MAKE_MINE
    val saveAction = if (state.screen == SocialReadScreen.STORY) BlueprintSocialAction.STORY_SAVE else BlueprintSocialAction.POST_SAVE
    val recipeActions = buildSet {
        if (onMakeMine != null && "makeMine" in recipeCapabilities) add(makeMineAction)
        if (onSaveRecipe != null && "saveRecipe" in recipeCapabilities) add(saveAction)
    }
    val selected = if (visible) state.selectedPost?.let { socialPost(it, state.screen == SocialReadScreen.STORY, boundMedia(it)) }
        ?.let { it.copy(ownReaction = ownReaction,
            reactionLabel = ownReaction?.let { own -> "Your reaction: ${reactionKindLabel(own.kind)}" },
            allowedActions = it.allowedActions + recipeActions + placementActions + reactionActions + (if (canReport) setOf(reportAction) else emptySet()) +
            (if (directRecipient != null) setOf(replyAction) else emptySet()) +
            (if (canDelete) setOf(BlueprintSocialAction.REVIEW_DELETE_POST) else emptySet()) +
            (if (canAsk) setOf(BlueprintSocialAction.ASK_RECIPE) else emptySet()) +
            (if (canViewAuthor) setOf(BlueprintSocialAction.VIEW_AUTHOR) else emptySet()) +
            (if (canReadRemixes) setOf(BlueprintSocialAction.VIEW_TAKES) else emptySet())) } else null
    val emptyForeignProfilePage = visible && state.screen == SocialReadScreen.PROFILE_PLATE &&
        !viewerId.isNullOrBlank() && !state.profileUserId.isNullOrBlank() && state.profileUserId != viewerId &&
        state.posts.isEmpty() && state.viewedAuthor == null
    val projectionOmitted = visible && (posts.size != state.posts.size || state.selectedPost != null && selected == null ||
        state.screen == SocialReadScreen.PROFILE_PLATE && profile == null && !emptyForeignProfilePage)
    val status = socialReadStatus(state, sourceCurrent(), projectionOmitted) +
        (if (visible && photoState.owner === state) when {
            photoState.loading -> "\nLoading authorized photos…"
            photoState.unavailableCount > 0 -> "\nSome photos are unavailable or expired. Refresh this view to check again."
            photosNotDisplayed > 0 -> "\nA photo is loading or can’t be displayed on this device."
            photoState.limitReached -> "\nThis view reached its bounded photo-display limit."
            else -> ""
        } else "") +
        (if (reportFailure != null) "\nCouldn’t open reporting. No new report was sent; any retained original is kept." else "") +
        (if (emptyForeignProfilePage) "\nThis authorized page returned no posts or author profile details." +
            (if (state.hasMore) " More → Load more checks the next available page." else " Back leaves this view.")
        else "") +
        recipeActionStatus?.let { "\n$it" }.orEmpty() + profileActionStatus?.let { "\n$it" }.orEmpty() +
        postPlacementStatus?.let { "\n$it" }.orEmpty() + reactionStatus?.let { "\n$it" }.orEmpty()
    val navigation = socialNavigationActions(state.screen, homeAvailable)
    fun socialAction(action: BlueprintSocialAction, post: BlueprintSocialPost? = null) {
        if (action in setOf(BlueprintSocialAction.TODAY_BACK, BlueprintSocialAction.PROFILE_BACK,
                BlueprintSocialAction.POST_BACK, BlueprintSocialAction.STORY_BACK)) { back(); return }
        if (!current()) return
        when (action) {
            BlueprintSocialAction.TODAY_BACK, BlueprintSocialAction.PROFILE_BACK,
            BlueprintSocialAction.POST_BACK, BlueprintSocialAction.STORY_BACK -> back()
            BlueprintSocialAction.TODAY_COOK, BlueprintSocialAction.PROFILE_COOK,
            BlueprintSocialAction.POST_COOK, BlueprintSocialAction.HELP_COOK -> home()
            BlueprintSocialAction.TODAY_COOKBOOK, BlueprintSocialAction.PROFILE_COOKBOOK,
            BlueprintSocialAction.POST_COOKBOOK -> exit(onSaved)
            BlueprintSocialAction.TODAY_TODAY, BlueprintSocialAction.PROFILE_TODAY,
            BlueprintSocialAction.POST_TODAY -> read { controller.openToday() }
            BlueprintSocialAction.TODAY_INBOX, BlueprintSocialAction.PROFILE_INBOX,
            BlueprintSocialAction.POST_INBOX -> read { controller.openInbox() }
            BlueprintSocialAction.TODAY_PLATE, BlueprintSocialAction.PROFILE_PLATE,
            BlueprintSocialAction.POST_PLATE -> read { controller.openProfilePlate() }
            BlueprintSocialAction.REFRESH_TODAY -> read { controller.refresh(state) }
            BlueprintSocialAction.NEXT_PLATE -> if (canNextStory) read {
                // The controller consumes this visit guard on its own dispatcher before
                // replacing the Story observation; intentional loading then retires it.
                controller.nextStory(state, ::current)
            }
            BlueprintSocialAction.VIEW_AUTHOR -> if (canViewAuthor) read {
                controller.openStoryAuthor(state, ::current)
            }
            BlueprintSocialAction.OPEN_PLATE, BlueprintSocialAction.OPEN_POST -> post?.let { chosen ->
                if (posts.any { it === chosen }) read { controller.openPost(chosen.id, state, story = action == BlueprintSocialAction.OPEN_PLATE) }
            }
            BlueprintSocialAction.CHOOSE_CIRCLE -> onCircles?.let(::exit)
            BlueprintSocialAction.MY_CIRCLES -> if (ownProfile) onCircles?.let(::exit)
            BlueprintSocialAction.PROFILE_SETTINGS -> if (ownProfile) onSettings?.let(::exit)
            BlueprintSocialAction.EDIT_PROFILE -> if (canEditProfile) exit { onEditProfile?.invoke(state) }
            BlueprintSocialAction.SHARE_MEAL -> onCreatePost?.let(::exit)
            BlueprintSocialAction.PROFILE_SHARE -> if (ownProfile) onCreatePost?.let(::exit)
            BlueprintSocialAction.POST_REPORT, BlueprintSocialAction.STORY_REPORT ->
                if (controller.canReportPost(state)) exit { onReportPost?.invoke(state) }
            BlueprintSocialAction.POST_REPLY, BlueprintSocialAction.STORY_REPLY -> directRecipient?.let { recipient ->
                exit { onStartDirect?.invoke(state, recipient) }
            }
            BlueprintSocialAction.REVIEW_DELETE_POST -> if (canDelete) exit { onDeletePost?.invoke(state) }
            BlueprintSocialAction.KEEP_ON_PLATE -> if (placementAction == action) reviewPlacement(true, ::current)
            BlueprintSocialAction.REVIEW_REMOVE_FROM_PLATE -> if (placementAction == action) reviewPlacement(false, ::current)
            BlueprintSocialAction.POST_REACT, BlueprintSocialAction.STORY_REACT ->
                if (canSetReaction) reviewReaction(false, ::current)
            BlueprintSocialAction.POST_REMOVE_REACTION, BlueprintSocialAction.STORY_REMOVE_REACTION ->
                if (ownReaction != null) reviewReaction(true, ::current)
            BlueprintSocialAction.ASK_RECIPE -> if (canAsk) exit { onAskRecipe?.invoke(state) }
            BlueprintSocialAction.VIEW_TAKES -> if (canReadRemixes) exit { onRemixTrail?.invoke(state) }
            BlueprintSocialAction.POST_MAKE_MINE, BlueprintSocialAction.STORY_MAKE_MINE ->
                if (action in recipeActions) exit { onMakeMine?.invoke(state) }
            BlueprintSocialAction.POST_SAVE, BlueprintSocialAction.STORY_SAVE ->
                if (action in recipeActions) exit { onSaveRecipe?.invoke(state) }
            else -> Unit
        }
    }
    platformBackHandler(localCurrent() && hostIsCurrent() && readDialogHidden() && state.screen != SocialReadScreen.HIDDEN, ::back)
    when (state.screen) {
        SocialReadScreen.TODAY -> {
            val view = BlueprintTodayState(posts, enabled = active, moreEnabled = active,
                localMoreEnabled = recoveryCurrent(),
                endReached = visible && !state.hasMore && !state.pageLimitReached && !projectionOmitted,
                allowedActions = navigation + buildSet {
                    add(BlueprintSocialAction.OPEN_PLATE)
                    if (homeAvailable) add(BlueprintSocialAction.HELP_COOK)
                    if (state.phase != SocialReadPhase.LOADING) add(BlueprintSocialAction.REFRESH_TODAY)
                    if (onCircles != null) add(BlueprintSocialAction.CHOOSE_CIRCLE)
                    if (onCreatePost != null) add(BlueprintSocialAction.SHARE_MEAL)
                }, status = status)
            BlueprintTodayScreen(view, onAction = { expected, action, post ->
                if (expected === view && view.allows(action, post)) socialAction(action, post)
            }, onMore = { expected -> if (expected === view) openMore() }, modifier = modifier)
        }
        SocialReadScreen.PROFILE_PLATE -> {
            val view = BlueprintProfilePlateState(profile, viewerId, posts, enabled = active,
                moreEnabled = active, localMoreEnabled = recoveryCurrent(), allowedActions = navigation + buildSet {
                    add(BlueprintSocialAction.OPEN_POST)
                    if (ownProfile && onCircles != null) add(BlueprintSocialAction.MY_CIRCLES)
                    if (ownProfile && onSettings != null) add(BlueprintSocialAction.PROFILE_SETTINGS)
                    if (canEditProfile) add(BlueprintSocialAction.EDIT_PROFILE)
                    if (ownProfile && onCreatePost != null) add(BlueprintSocialAction.PROFILE_SHARE)
                }, status = status)
            BlueprintProfilePlateScreen(view, onAction = { expected, action, post ->
                if (expected === view && view.allows(action, post)) socialAction(action, post)
            }, onMore = { expected -> if (expected === view) openMore() }, modifier = modifier)
        }
        SocialReadScreen.POST -> {
            val view = BlueprintPostState(post = selected, viewerUserId = viewerId,
                detailsExpanded = details, moreEnabled = active, enabled = active,
                allowedActions = navigation + recipeActions + placementActions + reactionActions + (if (canReport) setOf(reportAction) else emptySet()) +
                    (if (directRecipient != null) setOf(replyAction) else emptySet()) +
                    (if (canDelete) setOf(BlueprintSocialAction.REVIEW_DELETE_POST) else emptySet()) +
                    (if (canReadRemixes) setOf(BlueprintSocialAction.VIEW_TAKES) else emptySet()), status = status)
            BlueprintPostScreen(view, onAction = { expected, action ->
                if (expected === view && view.allows(action)) socialAction(action)
            }, onEdit = { expected, edit ->
                if (expected === view && current() && edit is BlueprintPostEdit.Details) {
                    details = edit.expanded
                    visit.change()
                }
            }, onMore = { expected -> if (expected === view) openMore() }, modifier = modifier)
        }
        SocialReadScreen.STORY -> {
            val view = BlueprintStoryState(post = selected, enabled = active, moreEnabled = active,
                allowedActions = setOf(BlueprintSocialAction.STORY_BACK) + recipeActions + reactionActions +
                    (if (canReport) setOf(reportAction) else emptySet()) + (if (directRecipient != null) setOf(replyAction) else emptySet()) +
                    (if (canAsk) setOf(BlueprintSocialAction.ASK_RECIPE) else emptySet()) +
                    (if (canViewAuthor) setOf(BlueprintSocialAction.VIEW_AUTHOR) else emptySet()) +
                    (if (canNextStory) setOf(BlueprintSocialAction.NEXT_PLATE) else emptySet()), status = status,
                observedNextPostId = state.nextStoryPostId.takeIf { canNextStory }, viewerUserId = viewerId)
            BlueprintStoryScreen(view, onAction = { expected, action ->
                if (expected === view && view.allows(action)) socialAction(action)
            }, onMore = { expected -> if (expected === view) openMore() }, modifier = modifier)
        }
        SocialReadScreen.INBOX -> {
            val view = socialInbox(state, visible, active, status, onOpenThread != null, onNotificationSettings != null,
                onOpenNotificationThread != null, notificationReads != null, homeAvailable)
            BlueprintInboxScreen(view, onAction = { intent ->
                val target = intent.thread
                if (current() && intent.action == BlueprintConversationAction.OPEN_THREAD && intent.context === view.context &&
                    target != null && view.intent(intent.action, target) != null) exit { onOpenThread?.invoke(state, target.id) }
                if (current() && intent.action == BlueprintConversationAction.NOTIFICATIONS && intent.context === view.context &&
                    view.intent(intent.action) != null) exit { onNotificationSettings?.invoke() }
                if (current() && intent.action == BlueprintConversationAction.OPEN_ACTIVITY && intent.context === view.context &&
                    intent.notification != null && view.intent(intent.action, intent.notification)?.let {
                        it.observedThreadId == intent.observedThreadId && it.observedPostId == intent.observedPostId
                    } == true) {
                    state.notifications.singleOrNull { it.socialText("id") == intent.notification.id &&
                        ((it.socialText("kind") == "reply" && it.socialText("objectType") == "thread" &&
                            it.socialText("objectId") == intent.observedThreadId) ||
                            (it.socialText("kind") == "reaction" && it.socialText("objectType") == "post" &&
                                it.socialText("objectId") == intent.observedPostId)) }
                        ?.let { visit.change(notification = it) }
                }
                if (current() && intent.action == BlueprintConversationAction.MARK_READ && intent.context === view.context &&
                    intent.observedThroughCreatedAt != null && intent.observedThroughCreatedAt == state.notificationServerTime &&
                    view.intent(intent.action)?.observedThroughCreatedAt == intent.observedThroughCreatedAt)
                    read { prepareNotificationRead(null) }
            }, onBack = ::back,
                onMore = ::openMore, onNavigate = { destination ->
                    if (view.controls.navigate(destination) && current()) navigate(destination)
                }, modifier = modifier)
        }
        SocialReadScreen.HIDDEN -> Unit
    }
    if (visit.more && (moreCurrent() || recoveryMoreCurrent())) FeedMeTheme {
        AlertDialog(onDismissRequest = ::closeMore, title = { Text("This view") },
            text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Only current authorized information is shown. Private conversations have their own send review. Photos, reactions and recipe-sharing actions remain separately gated.")
                if (canAsk && state.screen == SocialReadScreen.POST) TextButton(onClick = {
                    fromMore { epoch -> dispatch({ underlayCurrent(epoch) }) { onAskRecipe?.invoke(state) } }
                }) { Text("Ask for recipe") }
                if (placementAction != null) TextButton(onClick = {
                    fromMore { epoch -> reviewPlacement(placementKeep == false) { underlayCurrent(epoch) } }
                }) { Text(if (placementKeep == true) "Remove from my Plate" else "Keep on my Plate") }
                if (onReaction != null && ownReaction != null) TextButton(onClick = {
                    fromMore { epoch -> reviewReaction(true) { underlayCurrent(epoch) } }
                }) { Text("Remove my reaction") }
                if (authorReactions != null) TextButton(onClick = {
                    if (moreCurrent()) visit.change(reactionActors = true)
                }) { Text("Reactions") }
                if (recoveryRoute() && onResumePostPlacement != null) TextButton(onClick = {
                    if (recoveryMoreCurrent()) {
                        val continuation = visit.change()
                        fun admitted() = recoveryCurrent(continuation)
                        dispatch(::admitted) { onResumePostPlacement?.invoke(state, ::admitted) }
                    }
                }) { Text("Recover Plate change") }
                if (recoveryRoute() && onResumeReaction != null) TextButton(onClick = {
                    if (recoveryMoreCurrent()) {
                        val continuation = visit.change()
                        fun admitted() = recoveryCurrent(continuation)
                        dispatch(::admitted) { onResumeReaction.invoke(state, ::admitted) }
                    }
                }) { Text("Recover reaction") }
                OutlinedButton(onClick = { fromMore { controller.refresh(state) } },
                    enabled = sourceCurrent() && state.phase != SocialReadPhase.LOADING &&
                        !(state.phase == SocialReadPhase.EXPIRED && state.screen in setOf(SocialReadScreen.POST, SocialReadScreen.STORY)),
                    modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
                if (state.phase == SocialReadPhase.EXPIRED && state.screen in setOf(SocialReadScreen.POST, SocialReadScreen.STORY))
                    Text("Go back and refresh the original feed before opening this post again.")
                if (state.hasMore) OutlinedButton(onClick = { fromMore { controller.loadMore(state) } },
                    enabled = visible, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
                if (state.pageLimitReached) Text("This view reached its page limit. Refresh starts a new bounded read; it does not mark anything as read.")
                if (state.screen == SocialReadScreen.INBOX && notificationReads != null)
                    OutlinedButton(onClick = { fromMore { notificationReads.openRecovery() } }) {
                        Text("Check retained read request")
                    }
                onSettings?.takeIf { profileAccountControls }?.let { action -> TextButton(onClick = {
                    fromMore { epoch -> dispatch({ underlayCurrent(epoch) && profileAccountControls }, action) }
                }, enabled = moreCurrent()) { Text("Settings") } }
                onCircles?.takeIf { profileAccountControls }?.let { action -> TextButton(onClick = {
                    fromMore { epoch -> dispatch({ underlayCurrent(epoch) && profileAccountControls }, action) }
                }, enabled = moreCurrent()) { Text("Circles") } }
            } }, confirmButton = { TextButton(onClick = ::closeMore) { Text("Close") } })
    }
    if (visit.reactionActors && localCurrent() && sourceCurrent() && authorReactions != null && readDialogHidden()) FeedMeTheme {
        fun closeReactions() { if (localCurrent() && visit.reactionActors) visit.change() }
        AlertDialog(onDismissRequest = ::closeReactions, title = { Text("Reactions") }, text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Visible only to you as this post's author. These are the reactions returned with the current post view.")
                if (authorReactions.counts.isEmpty()) Text("No reaction counts were supplied.")
                else authorReactions.counts.forEach { Text(it) }
                if (authorReactions.items.isEmpty()) Text("No reacting accounts were returned in this view.")
                else authorReactions.items.forEach { actor ->
                    Text("${actor.displayName}${actor.handle.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()} — ${reactionKindLabel(actor.reaction.kind)}")
                }
                if (authorReactions.hasMore) Text("More accounts have reacted. This bounded view shows up to 50; it is not the complete list.")
                Text("Close and explicitly refresh the post for current reactions. Opening this list sends nothing.")
            }
        }, confirmButton = { TextButton(onClick = ::closeReactions) { Text("Close") } })
    }
    val selectedActivity = visit.notification
    if (selectedActivity != null && sourceCurrent() && !readVisible) FeedMeTheme {
        val id = selectedActivity.socialText("id")
        val postActivity = selectedActivity.socialText("kind") == "reaction" && selectedActivity.socialText("objectType") == "post"
        fun choiceCurrent() = localCurrent() && sourceCurrent() && !visit.more &&
            visit.notification === selectedActivity && readDialogHidden()
        fun closeChoice() { if (localCurrent() && visit.notification === selectedActivity) visit.change() }
        fun choose(action: suspend (Any) -> Unit) {
            if (!choiceCurrent()) return
            val continuation = visit.change()
            scope.launch { if (underlayCurrent(continuation)) action(continuation) }
        }
        platformBackHandler(true, ::closeChoice)
        AlertDialog(onDismissRequest = ::closeChoice, title = { Text(if (postActivity) "Reaction notification" else "Message notification") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(selectedActivity.socialText("preview").orEmpty())
                Text(if (selectedActivity.field("readAt") == WireField.Missing) "Observed as unread. Opening it does not mark the notification read."
                    else "Observed as read. Refresh Inbox for the latest state.")
                Text(if (postActivity) "Post access is checked again when opened. Expired or removed posts may be unavailable."
                    else "Conversation access is checked again when opened.")
                if (postActivity && id != null && controller.canOpenNotificationPost(state, id)) OutlinedButton(onClick = {
                    choose { epoch -> controller.openNotificationPost(state, id) { underlayCurrent(epoch) } }
                }) { Text("Open post") }
                if (!postActivity && onOpenNotificationThread != null) OutlinedButton(onClick = {
                    if (id != null) choose { epoch ->
                        dispatch({ underlayCurrent(epoch) }) { onOpenNotificationThread(state, id) }
                    }
                }) { Text("Open conversation") }
                if (notificationReads != null && selectedActivity.field("readAt") == WireField.Missing)
                    OutlinedButton(onClick = { if (id != null) choose { epoch -> prepareNotificationRead(id, epoch) } }) {
                        Text("Review marking this notification read")
                    }
            }
        }, confirmButton = { TextButton(onClick = ::closeChoice) { Text("Close") } })
    }
    notificationReads?.let { FeedMeNotificationReadDialog(it, hostIsCurrent, platformBackHandler) }
}

/** RAM presentation lifetime only. Rotating on entry and departure retires queued callbacks,
 * including reopening the same notification object without a new server observation. */
private class SocialReadingVisit {
    var attached = true
        private set
    var epoch by mutableStateOf<Any>(Any())
        private set
    var more by mutableStateOf(false)
        private set
    var notification by mutableStateOf<WireDocument?>(null)
        private set
    var reactionActors by mutableStateOf(false)
        private set
    fun change(more: Boolean = false, notification: WireDocument? = null, reactionActors: Boolean = false): Any {
        this.more = more
        this.notification = notification
        this.reactionActors = reactionActors
        return Any().also { epoch = it }
    }
    fun retire() { attached = false; change() }
}

private fun socialNavigationActions(screen: SocialReadScreen, homeAvailable: Boolean): Set<BlueprintSocialAction> = (when (screen) {
    SocialReadScreen.TODAY -> setOf(BlueprintSocialAction.TODAY_BACK, BlueprintSocialAction.TODAY_TODAY,
        BlueprintSocialAction.TODAY_COOK, BlueprintSocialAction.TODAY_COOKBOOK, BlueprintSocialAction.TODAY_INBOX, BlueprintSocialAction.TODAY_PLATE)
    SocialReadScreen.PROFILE_PLATE -> setOf(BlueprintSocialAction.PROFILE_BACK, BlueprintSocialAction.PROFILE_TODAY,
        BlueprintSocialAction.PROFILE_COOK, BlueprintSocialAction.PROFILE_COOKBOOK, BlueprintSocialAction.PROFILE_INBOX, BlueprintSocialAction.PROFILE_PLATE)
    SocialReadScreen.POST -> setOf(BlueprintSocialAction.POST_BACK, BlueprintSocialAction.POST_TODAY,
        BlueprintSocialAction.POST_COOK, BlueprintSocialAction.POST_COOKBOOK, BlueprintSocialAction.POST_INBOX, BlueprintSocialAction.POST_PLATE)
    else -> emptySet()
}) - if (homeAvailable) emptySet() else setOf(BlueprintSocialAction.TODAY_COOK,
    BlueprintSocialAction.PROFILE_COOK, BlueprintSocialAction.POST_COOK)

private fun socialPost(snapshot: SocialPostSnapshot, story: Boolean, media: BlueprintSocialMedia? = null): BlueprintSocialPost? {
    val document = snapshot.document
    val version = snapshot.version.toLongOrNull()?.takeIf { it > 0 && it.toString() == snapshot.version } ?: return null
    val author = document.member("author") ?: return null
    val person = socialPerson(author.socialText("userId"), author.socialText("displayName")) ?: return null
    if (document.socialText("status") != "published" || document.members("capabilities").none { it.stringOrNull() == "view" }) return null
    val audience = document.member("audience")?.socialText("kind")?.let { when (it) {
        "self" -> "Private"; "circles" -> "Selected circles"; else -> null
    } }
    return BlueprintSocialPost(snapshot.id, version, person, title = "Shared plate", caption = document.socialText("caption").orEmpty(),
        current = true, storyCurrent = story, keptOnPlate = document.member("keepOnPlate")?.booleanOrNull() == true,
        audienceLabel = audience, timeLabel = document.socialText("publishedAt")?.let { "Published $it" },
        expiryLabel = if (story) document.socialText("expiresAt")?.let { "Today availability ends $it" } else null,
        // Neither a raw media ID nor a recipe attachment is a current media/copy grant.
        media = media, recipe = document.member("attachment")?.let { attachment ->
            val recipeId = attachment.socialText("recipeVersionId")
            val planId = attachment.socialText("planId")
            if (recipeId == null && planId == null) null else BlueprintSocialRecipe(snapshot.id, version,
                recipeId, "Attached recipe", "Open to check the recipe and its availability.", planId = planId)
        },
        allowedActions = setOf(BlueprintSocialAction.OPEN_PLATE, BlueprintSocialAction.OPEN_POST))
}

private fun socialOwnReaction(document: WireDocument, postId: String, viewerId: String?): BlueprintSocialOwnReaction? {
    val id = document.socialText("id") ?: return null
    val versionToken = document.member("version")?.numberTokenOrNull() ?: return null
    val version = versionToken.toLongOrNull()?.takeIf { it > 0 && it.toString() == versionToken } ?: return null
    val userId = document.socialText("userId") ?: return null
    val target = document.socialText("postId") ?: return null
    val kind = document.socialText("kind") ?: return null
    return BlueprintSocialOwnReaction(id, version, target, userId, kind).takeIf { it.matches(postId, viewerId) }
}

private class SocialReactionActor(val reaction: BlueprintSocialOwnReaction, val displayName: String, val handle: String)
private class SocialReactionActors(val items: List<SocialReactionActor>, val counts: List<String>, val hasMore: Boolean)

/** Owner-only server projection. Counts never manufacture an own reaction or actor. */
private fun socialReactionActors(post: SocialPostSnapshot, viewerId: String?): SocialReactionActors? {
    val document = post.document
    if (viewerId.isNullOrBlank() || document.member("author")?.socialText("userId") != viewerId) return null
    val rows = document.member("reactionActors")?.elementsOrNull() ?: return null
    val hasMore = document.member("reactionActorsHasMore")?.booleanOrNull() ?: return null
    if (rows.size > 50) return null
    val actors = rows.map { row ->
        val user = row.member("user") ?: return null
        val id = user.socialText("userId") ?: return null
        val name = user.socialText("displayName")?.takeIf { it.isNotBlank() } ?: return null
        val handle = user.socialText("handle") ?: return null
        val reaction = row.member("reaction")?.let { socialOwnReaction(it, post.id, id) } ?: return null
        SocialReactionActor(reaction, name, handle)
    }
    if (actors.map { it.reaction.id }.distinct().size != actors.size ||
        actors.map { it.reaction.userId }.distinct().size != actors.size) return null
    val counts = document.member("reactionCounts")?.elementsOrNull() ?: return null
    val kinds = mutableSetOf<String>()
    val labels = counts.map { count ->
        val kind = count.socialText("kind") ?: return null
        val label = reactionKindLabel(kind) ?: return null
        val token = count.member("count")?.numberTokenOrNull() ?: return null
        val value = token.toLongOrNull()?.takeIf { it >= 0 && it.toString() == token } ?: return null
        if (!kinds.add(kind)) return null
        "$label: $value"
    }
    return SocialReactionActors(actors, labels, hasMore)
}

private fun socialProfile(document: WireDocument): BlueprintSocialProfile? {
    val person = socialPerson(document.socialText("id"), document.socialText("displayName")) ?: return null
    return BlueprintSocialProfile(person, current = true, subtitle = document.socialText("bio"))
}

private fun socialPerson(id: String?, name: String?): BlueprintSocialPerson? =
    if (id.isNullOrBlank() || name.isNullOrBlank()) null else BlueprintSocialPerson(id, name)

private fun socialInbox(state: SocialReadState, visible: Boolean, active: Boolean, status: String,
    openThreads: Boolean, openNotificationSettings: Boolean, openNotificationThread: Boolean, readAcknowledgements: Boolean,
    homeAvailable: Boolean): BlueprintInboxState {
    val threads = if (!visible) emptyList() else state.threads.mapNotNull { row ->
        val id = row.socialText("id") ?: return@mapNotNull null
        val version = row.member("version")?.numberTokenOrNull() ?: return@mapNotNull null
        val label = when (row.socialText("kind")) { "direct" -> "Private conversation"; "pact" -> "Dinner Pact conversation"
            "potluck" -> "Potluck conversation"; else -> return@mapNotNull null }
        val participants = row.members("participantIds").size
        val unread = row.member("unreadCount")?.numberTokenOrNull() ?: return@mapNotNull null
        BlueprintConversationSummary(BlueprintCommunityRef(BlueprintCommunityKind.THREAD, id, version), person = null,
            preview = "$participants participants · $unread unread", timeLabel = row.socialText("lastMessageAt"),
            contextLabel = if (openThreads) "Open the current private conversation." else "Message details are unavailable here.",
            unread = unread.substringBefore('e').substringBefore('E').any { it in '1'..'9' }, neutralTitle = label)
    }
    val notifications = if (!visible) emptyList() else state.notifications.mapNotNull { row ->
        val id = row.socialText("id") ?: return@mapNotNull null
        val version = row.member("version")?.numberTokenOrNull() ?: return@mapNotNull null
        val threadActivity = row.socialText("kind") == "reply" && row.socialText("objectType") == "thread"
        val postActivity = row.socialText("kind") == "reaction" && row.socialText("objectType") == "post"
        BlueprintInboxActivity(BlueprintCommunityRef(BlueprintCommunityKind.NOTIFICATION, id, version), destination = null,
            title = if (threadActivity || postActivity) (if (postActivity) "Reaction · " else "Message · ") +
                (if (row.field("readAt") == WireField.Missing) "Unread" else "Read") else "Activity",
            description = row.socialText("preview") ?: return@mapNotNull null,
            observedThreadId = row.socialText("objectId").takeIf { threadActivity && (openNotificationThread || readAcknowledgements) },
            observedPostId = row.socialText("objectId").takeIf { postActivity })
    }
    val owner = state.selfProfile?.socialText("id")
    return BlueprintInboxState(context = if (visible && !owner.isNullOrBlank()) BlueprintCommunityContext(owner, state.revision) else null,
        conversations = threads, activity = notifications,
        controls = BlueprintCommunityControls(enabled = active, loading = state.phase == SocialReadPhase.LOADING,
            unavailable = !visible && state.phase != SocialReadPhase.LOADING,
            allowedActionIds = if (!visible) emptySet() else buildSet {
                if (openThreads) add(BlueprintConversationAction.OPEN_THREAD.id)
                if (openNotificationSettings) add(BlueprintConversationAction.NOTIFICATIONS.id)
                if (openNotificationThread || readAcknowledgements || notifications.any { it.observedPostId != null })
                    add(BlueprintConversationAction.OPEN_ACTIVITY.id)
                if (readAcknowledgements && state.notificationsLoaded && state.notificationsFailure == null && state.notificationServerTime != null)
                    add(BlueprintConversationAction.MARK_READ.id)
            },
            allowedNavigation = setOf(BlueprintScreenId.COOKBOOK, BlueprintScreenId.TODAY,
                BlueprintScreenId.INBOX, BlueprintScreenId.PROFILE_PLATE) +
                if (homeAvailable) setOf(BlueprintScreenId.HOME) else emptySet(), status = status +
                    if (visible && state.notificationsFailure != null) "\nActivity is unavailable. This does not mean there are no notifications." else ""),
        activityUnavailable = visible && state.notificationsFailure != null,
        observedThroughCreatedAt = state.notificationServerTime.takeIf { visible && readAcknowledgements })
}

private fun socialReadStatus(state: SocialReadState, active: Boolean, omitted: Boolean): String {
    if (!active) return "This account view is no longer current. No retained private content is shown."
    val phase = when (state.phase) {
        SocialReadPhase.IDLE -> "Use More → Refresh to request current information."
        SocialReadPhase.LOADING -> "Loading current information…"
        SocialReadPhase.READY -> when (state.screen) {
            SocialReadScreen.INBOX -> "Current conversation summaries. Opening a thread does not mark messages read."
            else -> "Current authorized posts. Private reply is available only when the current contact capability permits it."
        }
        SocialReadPhase.EMPTY -> when (state.screen) {
            SocialReadScreen.TODAY -> "No plates were returned for Today."
            SocialReadScreen.PROFILE_PLATE -> "No kept moments were returned for this Plate."
            SocialReadScreen.INBOX -> "No conversations or activity were returned."
            else -> "No content was returned for this view."
        }
        SocialReadPhase.OFFLINE -> "You’re offline. No private feed is cached here. Reconnect, then use More → Refresh."
        SocialReadPhase.ERROR -> "This view could not be loaded. Use More → Refresh to try again."
        SocialReadPhase.UNAVAILABLE -> "This private view is unavailable for the current account or service. No demo content is shown."
        SocialReadPhase.EXPIRED -> if (state.screen in setOf(SocialReadScreen.POST, SocialReadScreen.STORY))
            "This post needs a fresh check. Go back and refresh its feed; previously shown private content has been cleared."
            else "This view needs a fresh check. Use More → Refresh; previously shown private content has been cleared."
    }
    return buildList {
        add(phase)
        if (omitted) add("Some returned entries cannot be displayed by this view; they have not been replaced with examples.")
        if (state.pageLimitReached) add("More may exist beyond this view’s page limit.")
        if (state.hasMore) add("More entries are available from More → Load more.")
        state.retryAfterSeconds?.let { add("The service asks you to wait $it seconds before retrying.") }
    }.joinToString("\n\n")
}

private fun WireDocument.member(name: String): WireDocument? =
    if (kind == WireKind.OBJECT) (field(name) as? WireField.Value)?.value else null
private fun WireDocument.socialText(name: String): String? = member(name)?.stringOrNull()
private fun WireDocument.members(name: String): List<WireDocument> = member(name)?.elementsOrNull().orEmpty()
