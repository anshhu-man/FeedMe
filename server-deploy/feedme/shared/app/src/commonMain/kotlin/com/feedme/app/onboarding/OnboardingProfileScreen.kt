package com.feedme.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.feedme.app.*
import com.feedme.app.blueprint.*
import com.feedme.core.ports.EpochClock
import com.feedme.session.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** PROFILE_SETUP presentation for an already-open, caller-owned restricted child.
 * Mount/recreation performs no read, Keep, review, retry or save. The parent owns real entry
 * and the preferences destination; this component never constructs a runtime/private lease.
 * [clock] must be the configured owner's clock. Timing here is explanatory, not authority.
 */
@Composable
fun FeedMeOnboardingProfileFlow(
    controller: OnboardingProfileController,
    clock: EpochClock,
    onBack: () -> Unit,
    onPreferences: ((OnboardingProfileState) -> Unit)? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean = { true },
    onAccountSettings: (() -> Unit)? = null,
    onOptionalLater: ((OnboardingProfileState) -> Unit)? = null,
) {
    val hostCurrent by rememberUpdatedState(hostIsCurrent)
    val latestAccountSettings by rememberUpdatedState(onAccountSettings)
    val latestOptionalLater by rememberUpdatedState(onOptionalLater)
    val observedState by controller.states.collectAsState()
    val state = observedState // Retain this rendering, not a later StateFlow observation.
    val actions = remember(controller) { OnboardingProfileActions(controller) }
    val navigation = remember(controller) { ProfileActionGate() }
    val actionUi by actions.states.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var attached by remember(controller) { mutableStateOf(true) }
    var leaving by remember(controller) { mutableStateOf(false) }
    var navigating by remember(controller) { mutableStateOf(false) }
    var buffer by remember(controller) { mutableStateOf(ProfileFormBuffer(profileFormInput(state.draft, state.profile))) }
    var validate by remember(controller) { mutableStateOf(false) }
    var leaveQuestion by remember(controller) { mutableStateOf(false) }
    var discardQuestion by remember(controller) { mutableStateOf<OnboardingProfileState?>(null) }
    var showProfileTools by remember(controller) { mutableStateOf(false) }
    var accountSettingsAction by remember(controller, state) { mutableStateOf<AccountSettingsHostAction?>(null) }
    val renderedAccountSettingsAction = accountSettingsAction
    var now by remember(controller, clock) { mutableStateOf(profileDisplayTime(clock)) }
    val visible = hostCurrent() && state.screen != OnboardingProfileScreen.UNAVAILABLE && controller.isCurrentState(state)
    val busy = actionUi.busy || leaving || navigating
    val terminal = state.status in setOf(OnboardingProfileStatus.APPLIED, OnboardingProfileStatus.DISCARDED, OnboardingProfileStatus.REJECTED)
    val primary = profilePrimary(state.status, state.recoveryRequired, state.completionAcknowledged,
        state.baselineFresh, state.onboardingStep, onPreferences != null)
    val review = actionUi.review
    val reviewCurrent = review != null && controller.isCurrentReview(review)
    val renderedInput = buffer.input
    val errors = profileFieldErrors(renderedInput, forSave = true)
    val localErrors = profileFieldErrors(buffer.input, forSave = false)
    val editable = visible && !busy && !state.recoveryRequired

    DisposableEffect(actions) {
        onDispose { attached = false; actions.retire(); navigation.retire() }
    }
    // Only RAM presentation changes here. Never refresh or import a saved result as an ACK.
    SideEffect {
        buffer = if (!visible) ProfileFormBuffer(ProfileFormInput("", "", null))
            else buffer.observe(profileFormInput(state.draft, state.profile))
        if (!visible) { leaveQuestion = false; discardQuestion = null; showProfileTools = false }
        if (review != null && !reviewCurrent && !actionUi.busy) actions.dismissReview()
    }
    LaunchedEffect(controller, clock, state.retryAtMillis, review) {
        now = profileDisplayTime(clock)
        while ((review != null && controller.isCurrentReview(review)) ||
            (state.retryAtMillis?.let { now >= 0 && now < it } == true)) {
            delay(500)
            now = profileDisplayTime(clock)
        }
    }

    fun leave() {
        if (leaving || navigating || !attached || !hostCurrent()) return
        leaving = true; leaveQuestion = false; discardQuestion = null
        actions.retire() // A held operation cannot deliver back into this attachment.
        navigation.retire()
        focus.clearFocus()
        scope.launch {
            if (!hostCurrent()) return@launch
            controller.back() // Closes only this restricted child; never sends or clears a draft.
            if (attached && hostCurrent()) onBack()
        }
    }
    fun act(action: ProfileAction, keepThenBack: Boolean = false, optionalLater: Boolean = false) {
        if (!attached || !hostCurrent() || leaving || navigating || !visible || busy) return
        // This delivery choice belongs only to this explicit invocation. It is neither a
        // stored skip decision nor something a retry/recreated screen can replay.
        if (optionalLater && (action != ProfileAction.CONTINUE || primary != ProfileAction.CONTINUE ||
                onOptionalLater == null || latestOptionalLater == null || state.status != OnboardingProfileStatus.LOCAL ||
                state.recoveryRequired || buffer.input != renderedInput || !errors.valid || review != null ||
                leaveQuestion || discardQuestion != null)) return
        val continueTo = profileContinueCallback(optionalLater, onPreferences, onOptionalLater)
        if (action == ProfileAction.SAVE || action == ProfileAction.CONTINUE) validate = true
        if (action == ProfileAction.OPEN_PREFERENCES) {
            if (onPreferences != null && profilePreferencesAdmission(attached, leaving, navigating, buffer.dirty) && controller.isCurrentPreferencesCompletion(state)) {
                val ticket = navigation.claim(state, controller.states.value) ?: return
                navigating = true
                // Last synchronous check is adjacent to the real destination callback.
                if (attached && hostCurrent() && !leaving && !buffer.dirty && navigation.running(ticket) && controller.isCurrentPreferencesCompletion(state))
                    onPreferences(state) else navigating = false
            }
            return
        }
        val call = actions.claim(action, state, buffer.input) ?: return
        focus.clearFocus()
        scope.launch {
            if (!hostCurrent()) return@launch
            val result = actions.execute(call)
            if (result != null && attached && hostCurrent() && actions.canDeliver(call, result)) {
                if (action in setOf(ProfileAction.KEEP, ProfileAction.SAVE, ProfileAction.CONTINUE))
                    buffer = buffer.kept(checkNotNull(call.input))
                if (keepThenBack && action == ProfileAction.KEEP) leave()
                else if (action == ProfileAction.CONTINUE && continueTo != null && (!optionalLater || latestOptionalLater != null) &&
                    profilePreferencesAdmission(attached, leaving, navigating, buffer.dirty) &&
                    actions.canDeliver(call, result) && controller.isCurrentPreferencesCompletion(result)) {
                    val ticket = navigation.claim(result, controller.states.value) ?: return@launch
                    navigating = true
                    if (attached && hostCurrent() && !leaving && !buffer.dirty && navigation.running(ticket) &&
                        (!optionalLater || latestOptionalLater != null) && actions.canDeliver(call, result) && controller.isCurrentPreferencesCompletion(result))
                        continueTo(result) else navigating = false
                }
            }
        }
    }
    fun edit(input: ProfileFormInput) {
        if (attached && hostCurrent() && !leaving && !navigating && !actions.states.value.busy && editable && controller.isCurrentState(state))
            buffer = buffer.edit(input)
    }
    val back: () -> Unit = {
        if (hostCurrent()) when {
            leaveQuestion -> leaveQuestion = false
            discardQuestion != null -> discardQuestion = null
            review != null && !actionUi.busy -> actions.dismissReview()
            visible && buffer.dirty -> leaveQuestion = true
            else -> leave()
        }
    }
    platformBackHandler(attached && hostCurrent() && !navigating, back)

    FeedMeTheme {
        Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                BlueprintAppBar(title = "Your FeedMe", onBack = back, onMore = {
                    if (attached && hostCurrent() && !leaving && !navigating && visible && controller.isCurrentState(state)) {
                        showProfileTools = !showProfileTools
                        accountSettingsAction = if (showProfileTools) latestAccountSettings?.let { AccountSettingsHostAction(state, it) } else null
                    }
                })
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!visible) {
                    ProfileNotice("Account unavailable", "Private profile details are hidden. Go back to check your account connection. Saved work is not sent automatically.")
                } else {
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Working on your profile…") }
                    profileFailureText(actionUi.failure ?: state.failureReason)?.let { ProfileNotice("Couldn’t confirm that", it) }
                    if (state.status != OnboardingProfileStatus.LOCAL || state.recoveryRequired)
                        ProfileNotice(profileStatusTitle(state.status, state.recoveryRequired, state.completionAcknowledged, state.rejectionReason),
                            profileStatusBody(state.status, state.recoveryRequired, state.completionAcknowledged, state.rejectionReason))

                    if (state.status == OnboardingProfileStatus.LOCAL && !state.recoveryRequired) {
                        BlueprintAccountSetupContent(
                            state = profileBlueprintState(buffer.input, editable, !busy && primary == ProfileAction.CONTINUE,
                                busy, errors, validate || !localErrors.valid, optionalLaterAvailable = onOptionalLater != null),
                            onFieldsChange = { next -> edit(profileApplyBlueprintInput(buffer.input, next)) },
                            onAction = { selected ->
                                // No generic navigation or Save alias: this visual intent maps
                                // only to the same reviewed, current profile-advance operation.
                                if (selected == BlueprintAccountSetupAction.CONTINUE_PROFILE && primary == ProfileAction.CONTINUE)
                                    act(ProfileAction.CONTINUE)
                                else if (selected == BlueprintAccountSetupAction.SET_PREFERENCES_LATER)
                                    act(ProfileAction.CONTINUE, optionalLater = true)
                            },
                        )
                        if (onOptionalLater != null && primary == ProfileAction.CONTINUE)
                            Text("Both options save your name and handle. Food and equipment choices can be set later.",
                                style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                        if (showProfileTools) {
                            ProfileBioEditor(buffer.input, ::edit, editable, errors, validate || !localErrors.valid,
                                onDone = { focus.clearFocus() })
                        }
                        if ((validate || !localErrors.valid) && errors.bio != null)
                            Text("Bio: ${errors.bio} Open More options to correct it.", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        Text("Guest-save merging is not connected here. Complete your profile first; optional food preferences have their own next-step choice.",
                            style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                    } else {
                        if (terminal && state.status == OnboardingProfileStatus.APPLIED)
                            state.profile?.let { ProfileFields("Saved profile", it) }
                        state.original?.let { original ->
                            ProfileFields(if (state.status == OnboardingProfileStatus.APPLIED) "Changes in this save" else "Original save", original)
                            Text(profileOriginalPurpose(state.originalIntent), style = MaterialTheme.typography.bodyMedium)
                        }
                        FeedMeDetails("Your separate draft") {
                            Text("Editing or keeping this draft does not change the original request.")
                            ProfileEditor(buffer.input, ::edit, editable, errors, validate || !localErrors.valid,
                                onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { focus.clearFocus() })
                            ProfileSecondary("Keep on device", editable && localErrors.valid) { act(ProfileAction.KEEP) }
                        }
                    }

                    if (buffer.dirty) Text("Typing not kept yet.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                    else if (actionUi.keptAt === state) Text("Draft kept on this device.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                    if (state.status == OnboardingProfileStatus.LOCAL && !state.recoveryRequired)
                        ProfileSecondary("Keep on device", editable && localErrors.valid) { act(ProfileAction.KEEP) }

                    val retryReady = profileRetryReady(now, state.retryAtMillis)
                    if (primary == ProfileAction.REVIEW_ORIGINAL && !retryReady)
                        Text("Please wait before reviewing the same save again.", style = MaterialTheme.typography.bodyMedium)
                    primary?.takeUnless { state.status == OnboardingProfileStatus.LOCAL && !state.recoveryRequired && it == ProfileAction.CONTINUE }?.let { action ->
                        val requiresKept = action in setOf(ProfileAction.REVIEW_ORIGINAL, ProfileAction.START_NEW, ProfileAction.OPEN_PREFERENCES)
                        ProfilePrimary(profileActionLabel(action), !busy && (!requiresKept || !buffer.dirty) &&
                            (action != ProfileAction.REVIEW_ORIGINAL || retryReady)) { act(action) }
                        if (requiresKept && buffer.dirty) Text("Keep your typing before continuing. It stays separate from the original save.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.status == OnboardingProfileStatus.APPLIED && state.completionAcknowledged && state.onboardingStep == "preferences" && onPreferences == null)
                        Text("Your profile is saved. Food preferences are not connected in this preview yet.")
                    if (showProfileTools || state.status != OnboardingProfileStatus.LOCAL || state.recoveryRequired) FeedMeDetails("Profile details and options") {
                        if (showProfileTools && onAccountSettings != null && renderedAccountSettingsAction != null) {
                            ProfileSecondary("Account & privacy", !busy && !buffer.dirty) {
                                if (accountSettingsAction === renderedAccountSettingsAction) {
                                    val navigate = renderedAccountSettingsAction.claim(state, latestAccountSettings,
                                        attached && hostCurrent() && visible && controller.isCurrentState(state) &&
                                            !leaving && !navigating && !busy && !buffer.dirty && review == null &&
                                            !leaveQuestion && discardQuestion == null)
                                    if (navigate != null) {
                                        accountSettingsAction = null; showProfileTools = false
                                        focus.clearFocus(); navigate()
                                    }
                                }
                            }
                            if (buffer.dirty) Text("Keep your typing before opening account options. Nothing is saved automatically.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.profile != null) ProfileFields(if (state.baselineFresh) "Last loaded profile" else "Retained profile", checkNotNull(state.profile))
                        if (primary != ProfileAction.LOAD && !state.recoveryRequired)
                            ProfileSecondary("Load current profile", !busy) { act(ProfileAction.LOAD) }
                        if (terminal && state.completionAcknowledged && primary != ProfileAction.START_NEW)
                            ProfileSecondary("Edit your draft", !busy && state.baselineFresh && !buffer.dirty) { act(ProfileAction.START_NEW) }
                        if (state.status == OnboardingProfileStatus.PREPARED && !state.recoveryRequired)
                            ProfileSecondary("Discard unsent save", !busy && !buffer.dirty) { discardQuestion = state }
                        Text("Keep on device saves a local draft. Continue or Save profile sends the exact visible fields. Loading the current profile keeps newer typing in this form.")
                        Text("A profile photo and bringing guest saves into your account are not connected here yet.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            }
        }

        if (visible && review != null && reviewCurrent) AlertDialog(
            onDismissRequest = { if (!busy) actions.dismissReview() },
            title = { Text("Review original save") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Retry only the original fields below. Your newer draft will not be sent by this action.")
                    Text(profileOriginalPurpose(state.originalIntent))
                    ProfileFields("Original fields", review.fields)
                    state.draft?.takeIf { it != review.fields }?.let { ProfileFields("Separate kept draft", it) }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = { act(ProfileAction.CONFIRM_ORIGINAL) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry original save") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { actions.dismissReview() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep editing") } },
        )
        discardQuestion?.takeIf { visible && it === state }?.let {
            AlertDialog(onDismissRequest = { discardQuestion = null }, title = { Text("Discard only the unsent save?") },
                text = { Text("Your kept draft remains. This does not change your profile or discard a request that may already have reached the service.") },
                confirmButton = { TextButton(enabled = !busy, onClick = { discardQuestion = null; act(ProfileAction.DISCARD_UNSENT) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard unsent save") } },
                dismissButton = { TextButton(onClick = { discardQuestion = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep this save") } })
        }
        if (visible && leaveQuestion) AlertDialog(onDismissRequest = { leaveQuestion = false }, title = { Text("Keep your typing before leaving?") },
            text = { Text("Only typing not yet kept can be lost. Any original save stays available for recovery; leaving does not send or discard it.") },
            confirmButton = { TextButton(enabled = !busy && localErrors.valid, onClick = { act(ProfileAction.KEEP, keepThenBack = true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep and back") } },
            dismissButton = {
                Column {
                    TextButton(onClick = { leave() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Leave without keeping typing") }
                    TextButton(onClick = { leaveQuestion = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep editing") }
                }
            })
    }
}

private fun profileDisplayTime(clock: EpochClock): Long = try { clock.nowMillis() } catch (_: Exception) { -1L }

/** Presentation projection only. The optional-later port receives the same required-profile
 * save acknowledgement; it does not create a skipped checkpoint or bypass the actual owner. */
internal fun profileBlueprintState(input: ProfileFormInput, editable: Boolean, continueEnabled: Boolean, busy: Boolean,
    errors: ProfileFieldErrors, showErrors: Boolean, optionalLaterAvailable: Boolean = false) = BlueprintAccountSetupState(
    page = BlueprintAccountSetupPage.PROFILE_SETUP,
    fields = BlueprintAccountSetupFields(displayName = input.displayName, handle = input.handle),
    enabledActions = if (continueEnabled && !busy) buildSet {
        add(BlueprintAccountSetupAction.CONTINUE_PROFILE)
        if (optionalLaterAvailable && editable && errors.valid) add(BlueprintAccountSetupAction.SET_PREFERENCES_LATER)
    } else emptySet(),
    editableFields = if (editable && !busy) setOf(BlueprintAccountSetupField.DISPLAY_NAME, BlueprintAccountSetupField.HANDLE) else emptySet(),
    fieldErrors = if (showErrors) buildMap {
        errors.displayName?.let { put(BlueprintAccountSetupField.DISPLAY_NAME, it) }
        errors.handle?.let { put(BlueprintAccountSetupField.HANDLE, it) }
    } else emptyMap(),
    // The real host already renders its operation state above this form.
    busy = false,
)

/** One explicit save has one destination. A missing optional port never falls back to the
 * ordinary preferences route, and recovery calls carry no optional-later choice. */
internal fun profileContinueCallback(optionalLater: Boolean,
    preferences: ((OnboardingProfileState) -> Unit)?,
    later: ((OnboardingProfileState) -> Unit)?): ((OnboardingProfileState) -> Unit)? =
    if (optionalLater) later else preferences

internal fun profileApplyBlueprintInput(previous: ProfileFormInput, next: BlueprintAccountSetupFields) =
    previous.copy(displayName = next.displayName, handle = next.handle)

@Composable
private fun ProfileEditor(input: ProfileFormInput, onChange: (ProfileFormInput) -> Unit, enabled: Boolean,
    errors: ProfileFieldErrors, validate: Boolean, onNext: () -> Unit, onDone: () -> Unit) {
    OutlinedTextField(input.displayName, { onChange(input.copy(displayName = it)) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("Display name") }, isError = validate && errors.displayName != null,
        supportingText = { Text(if (validate) errors.displayName ?: "1–50 characters" else "1–50 characters") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { onNext() }))
    OutlinedTextField(input.handle, { onChange(input.copy(handle = it)) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("Handle") }, isError = validate && errors.handle != null,
        supportingText = { Text(if (validate) errors.handle ?: "3–24 lowercase letters, numbers or underscores" else "3–24 lowercase letters, numbers or underscores") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }))
    ProfileBioEditor(input, onChange, enabled, errors, validate, onDone)
}

@Composable
private fun ProfileBioEditor(input: ProfileFormInput, onChange: (ProfileFormInput) -> Unit, enabled: Boolean,
    errors: ProfileFieldErrors, validate: Boolean, onDone: () -> Unit) {
    FeedMeDetails("Bio (optional)") {
        OutlinedTextField(input.bio.orEmpty(), { onChange(input.copy(bio = it)) }, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text("Bio") }, isError = validate && errors.bio != null,
            supportingText = { Text(if (validate) errors.bio ?: "Up to 160 characters. Clearing this field saves a blank bio." else "Up to 160 characters. Clearing this field saves a blank bio.") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onDone() }))
    }
}

@Composable
private fun ProfileFields(title: String, fields: OnboardingProfileFields) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        fields.displayName?.let { Text("Display name: $it") }
        fields.handle?.let { Text("Handle: $it") }
        fields.bio?.let { Text(if (it.isEmpty()) "Bio: blank" else "Bio: $it") }
    }
}

@Composable
private fun ProfileNotice(title: String, body: String) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ProfilePrimary(label: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
}
@Composable
private fun ProfileSecondary(label: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}
