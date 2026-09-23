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
import com.feedme.core.ports.*
import com.feedme.mealflow.notifications.*
import kotlinx.coroutines.launch

/** Original NOTIFICATIONS. Additional canonical event types and timezone live in
 * the existing More tools, not invented Dinner Pact wire fields. The retained
 * controller owns every draft and original; disposal for OS permission loses none. */
@Composable
fun FeedMeNotificationsFlow(controller:NotificationSettingsController,hostIsCurrent:()->Boolean,onClose:()->Unit,
    permission:NotificationDevicePermission=NotificationDevicePermission.UNKNOWN,onOpenPermission:(()->Unit)?=null,
    onOpenSystemSettings:(()->Unit)?=null,platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val state by controller.states.collectAsState();val host by rememberUpdatedState(hostIsCurrent);val scope=rememberCoroutineScope()
    var attached by remember(controller){mutableStateOf(true)};DisposableEffect(controller){onDispose{attached=false}}
    var busy by remember(controller){mutableStateOf(false)};var more by remember(controller){mutableStateOf(false)}
    var discard by remember(controller){mutableStateOf(false)}
    val captured=state
    fun current()=attached&&host()&&controller.isCurrent(captured)
    val working=captured.phase==NotificationSettingsPhase.SAVING
    val loading=busy||captured.phase==NotificationSettingsPhase.LOADING
    val edit=current()&&!loading&&!working&&captured.pending==null&&captured.review==null&&captured.draft!=null&&captured.settings!=null
    fun act(block:suspend()->Unit){if(current()&&!busy){busy=true;scope.launch{try{if(current())block()}finally{busy=false}}}}
    fun back(){if(!attached||controller.states.value!==captured||working)return
        when{discard->discard=false;more->more=false;captured.review!=null&&current()->act{controller.backReview(captured)};else->onClose()}}
    platformBackHandler(true,::back)
    val review=captured.review.takeIf{current()}
    if(review!=null){val confirmation=BlueprintConfirmationState("Save notification choices",summary(review.draft),
        listOf("These are account preferences, not Android permission or confirmation that push delivery is active.",
            "Quiet hours use the selected IANA time zone: start inclusive, end exclusive; a later start crosses midnight. Empty quiet hours disable the interval.",
            if(review.retryOriginal)"Retry only the exact retained key, body and version; do not assume the previous save failed."else"Only Save sends these reviewed choices. Back leaves the draft unchanged."),
        canConfirm=current()&&!loading&&!working,canCancel=current()&&!loading&&!working,busy=loading||working)
        BlueprintConfirmationScreen(confirmation,onConfirm={if(it===confirmation&&it.confirmEnabled)act{controller.confirm(review)}},
            onCancel={if(it===confirmation&&it.cancelEnabled)act{controller.backReview(captured)}},heading="Your updates.\nYour choice.",confirmLabel=if(review.retryOriginal)"Retry original save" else "Save choices")
        return
    }
    val draft=captured.draft.takeIf{current()};val quiet=draft?.quietHours?.let{input->Regex("((?:[01][0-9]|2[0-3]):[0-5][0-9])[-–]((?:[01][0-9]|2[0-3]):[0-5][0-9])").matchEntire(input)?.let{BlueprintControlQuietHours(input,it.groupValues[1],it.groupValues[2],draft.timeZone)}}
    val model=BlueprintAccountControlsState(BlueprintAccountControlPage.NOTIFICATIONS,
        phase=when{working->BlueprintAccountControlPhase.WORKING;loading->BlueprintAccountControlPhase.LOADING;!current()||captured.phase in setOf(NotificationSettingsPhase.HIDDEN,NotificationSettingsPhase.UNAVAILABLE)->BlueprintAccountControlPhase.UNAVAILABLE;captured.pending!=null->BlueprintAccountControlPhase.UNKNOWN;else->BlueprintAccountControlPhase.READY},
        context=if(current()&&captured.accountId!=null&&captured.accountVersion!=null)BlueprintAccountControlContext(BlueprintControlReference(BlueprintControlReferenceKind.ACCOUNT,captured.accountId!!,captured.accountVersion!!),captured.revision)else null,
        enabledActionIds=buildSet{if(!working)add("NOTIFICATIONS.back");if(edit&&captured.dirty)add("NOTIFICATIONS.01");if(current()&&!loading&&!working){if(onOpenPermission!=null)add("NOTIFICATIONS.02");if(onOpenSystemSettings!=null)add("NOTIFICATIONS.03")}},
        editableFields=if(edit)setOf(BlueprintAccountControlField.REPLIES,BlueprintAccountControlField.INVITES,BlueprintAccountControlField.QUIET)else emptySet(),
        fields=BlueprintAccountControlFields(replies=draft?.replies,invites=draft?.invitations,quiet=draft?.quietHours),
        devicePermissionLabel=permission.label(),quietHours=quiet,notificationQuietLabel="Quiet hours · selected time zone",
        statusMessage=notice(captured))
    BlueprintAccountControlsScreen(model,onEvent={event->if(event.expected===model)when(event){
        is BlueprintAccountControlEvent.Action->if(model.admits(event.actionId))when(event.actionId){
            "NOTIFICATIONS.back"->back();"NOTIFICATIONS.01"->act{controller.prepareSave(captured)}
            "NOTIFICATIONS.02"->if(current())onOpenPermission?.invoke();"NOTIFICATIONS.03"->if(current())onOpenSystemSettings?.invoke();else->Unit}
        is BlueprintAccountControlEvent.FieldChanged->if(edit&&draft!=null&&model.accepts(event.field,event.value)){
            val changed=when(event.field){BlueprintAccountControlField.REPLIES->draft.copy(replies=(event.value as BlueprintAccountControlValue.Toggle).value)
                BlueprintAccountControlField.INVITES->draft.copy(invitations=(event.value as BlueprintAccountControlValue.Toggle).value)
                BlueprintAccountControlField.QUIET->draft.copy(quietHours=(event.value as BlueprintAccountControlValue.Text).value);else->draft}
            act{controller.edit(changed,captured)}
        }
    }},onMore={if(current())more=true})
    if(more&&current())FeedMeTheme{AlertDialog(onDismissRequest={more=false},title={Text("Notification choices")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(notice(captured))
            if(draft!=null){
                @Composable fun choice(label:String,value:Boolean,change:(Boolean)->NotificationSettingsDraft){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label);Switch(value,{if(edit)act{controller.edit(change(it),captured)}},enabled=edit)}}
                choice("Reactions",draft.reactions){draft.copy(reactions=it)}
                choice("Remixes",draft.remixes){draft.copy(remixes=it)}
                choice("Cooking reminders",draft.cookingReminders){draft.copy(cookingReminders=it)}
                OutlinedTextField(draft.timeZone,{if(edit)act{controller.edit(draft.copy(timeZone=it),captured)}},enabled=edit,label={Text("IANA time zone")},singleLine=true)
                Text("Use an IANA zone such as Asia/Kolkata, or UTC. Quiet hours: HH:mm–HH:mm; clear the field to disable. Equal start/end is invalid. The server validates the zone; no delivery scheduler is active here.")
            }
            Text("Dinner Pacts are not included in this release; that original toggle is unavailable. No private food details are sent by this settings screen.")
            captured.pending?.let{pending->Text("Original save: version ${pending.version} · ${pending.phase?.name?:"retained"} · ${pending.issue?.name?:"unconfirmed"}")
                if(pending.canRetry)TextButton(enabled=!loading&&!working,onClick={more=false;act{controller.prepareRetry(captured)}}){Text("Review original retry")}
                if(pending.receiptReady)TextButton(enabled=!loading&&!working,onClick={more=false;act{controller.applyReceipt(captured)}}){Text("Confirm retained receipt")}
                TextButton(enabled=!loading&&!working,onClick={more=false;act{controller.discardUnsent(captured)}}){Text("Cancel only if never sent")}
                if(!pending.canRetry&&!pending.receiptReady)Text("This original requires resolution. It will not be replaced or rebased; save again is disabled.")
            }
            if(captured.pending==null)TextButton(enabled=!loading&&!working,onClick={if(captured.dirty)discard=true else{more=false;act{controller.refresh(captured)}}}){Text(if(captured.dirty)"Discard draft and reload…" else "Reload current settings")}
        }
    },confirmButton={TextButton(onClick={more=false}){Text("Done")}})}
    if(discard&&current())FeedMeTheme{AlertDialog(onDismissRequest={discard=false},title={Text("Discard unsaved choices?")},text={Text("Only this unsaved draft will be replaced with current server settings. No retained attempted command is discarded.")},
        confirmButton={TextButton(onClick={discard=false;more=false;act{controller.discardDraftAndReload(captured)}}){Text("Discard and reload")}},dismissButton={TextButton(onClick={discard=false}){Text("Keep draft")}})}
}

@Composable
fun FeedMeNotificationPermissionScreen(permission:NotificationDevicePermission,hostIsCurrent:()->Boolean,onBack:()->Unit,
    onEnable:()->Unit,onMaybeLater:()->Unit,onOpenSystemSettings:()->Unit) {
    val host by rememberUpdatedState(hostIsCurrent);var attached by remember{mutableStateOf(true)}
    DisposableEffect(Unit){onDispose{attached=false}}
    fun current()=attached&&host()
    val model=BlueprintAccountSetupState(BlueprintAccountSetupPage.NOTIFICATION_PERMISSION,
        enabledActions=if(current())buildSet{add(BlueprintAccountSetupAction.BACK);add(BlueprintAccountSetupAction.MAYBE_LATER)
            if(permission==NotificationDevicePermission.NOT_REQUESTED)add(BlueprintAccountSetupAction.ENABLE_NOTIFICATIONS)
            if(permission!=NotificationDevicePermission.UNAVAILABLE)add(BlueprintAccountSetupAction.OPEN_DEVICE_SETTINGS)}else emptySet(),
        notice="Device permission: ${permission.label()}. The preview is illustrative. App choices remain unsaved until you explicitly save them. Permission alone does not register a push token or activate delivery.")
    BlueprintAccountSetupScreen(model,onFieldsChange={},onAction={action->if(current()&&model.admits(action))when(action){
        BlueprintAccountSetupAction.BACK->onBack();BlueprintAccountSetupAction.ENABLE_NOTIFICATIONS->onEnable()
        BlueprintAccountSetupAction.MAYBE_LATER->onMaybeLater();BlueprintAccountSetupAction.OPEN_DEVICE_SETTINGS->onOpenSystemSettings();else->Unit}})
}
private fun NotificationDevicePermission.label()=when(this){NotificationDevicePermission.UNKNOWN->"Check on device";NotificationDevicePermission.NOT_REQUESTED->"Not requested";NotificationDevicePermission.GRANTED->"Allowed on this device";NotificationDevicePermission.DENIED->"Not allowed";NotificationDevicePermission.UNAVAILABLE->"Unavailable"}
private fun summary(draft:NotificationSettingsDraft)="Replies: ${draft.replies}\nInvitations: ${draft.invitations}\nReactions: ${draft.reactions}\nRemixes: ${draft.remixes}\nCooking reminders: ${draft.cookingReminders}\nQuiet hours: ${draft.quietHours.ifEmpty{"off"}}\nTime zone: ${draft.timeZone}"
private fun notice(state:NotificationSettingsState):String=when {
    state.pending!=null->"A save is retained. Its original key, body and version will not be replaced. Review recovery in More."
    state.receipt!=null->"Saved choices confirmed by the original server receipt, version ${state.receipt!!.version}. This does not enable device permission or confirm push delivery."
    state.failureReason!=null->"Could not save or refresh (${state.failureReason}). Your draft is retained. Correct its fields or review More; no success is assumed."
    state.phase==NotificationSettingsPhase.LOADING->"Loading your actual private notification settings."
    state.phase in setOf(NotificationSettingsPhase.HIDDEN,NotificationSettingsPhase.UNAVAILABLE)->"Notification settings are unavailable. You can return to Settings."
    state.dirty->"Unsaved choices. Save opens a review first. More contains additional event types and the time zone."
    else->"These are your stored notification preferences. All event types start off. Device permission and push delivery are separate."
}
