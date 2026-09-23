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
import com.feedme.contracts.WireField
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.kitchen.SavedRecipeSnapshot
import com.feedme.mealflow.collections.*

/** Original collection screens over current account reads and exact reviewed commands.
 * The parent owns suspension/lifetime; composition and local editor changes do no I/O. */
@Composable
internal fun BlueprintCollectionFlow(
    reads: CollectionReadController,
    state: CollectionReadState,
    commandState: CollectionCommandState,
    busy: Boolean,
    isCurrent: () -> Boolean,
    onAction: (suspend () -> Unit) -> Unit,
    onBack: () -> Unit,
    onOpenSaved: (CollectionReadState, CollectionSavedRecipeSelection) -> Unit,
    savedChoices: List<SavedRecipeSnapshot> = emptyList(),
    openSavedStatus: String? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
) {
    val commands = reads.commands
    val liveCurrent by rememberUpdatedState(isCurrent)
    var attached by remember(reads) { mutableStateOf(true) }
    DisposableEffect(reads) { onDispose { attached = false } }
    fun current() = attached && liveCurrent() && reads.isCurrent(state) && commands.isCurrent(commandState)
    fun act(work: suspend () -> Unit) {
        if (!busy && current()) onAction { if (current()) work() }
    }
    val loading = busy || state.phase == CollectionReadPhase.LOADING || commandState.phase == CollectionCommandPhase.WORKING
    val observed = state.phase in setOf(CollectionReadPhase.READY, CollectionReadPhase.EMPTY)
    val denied = state.phase == CollectionReadPhase.UNAVAILABLE || commandState.phase == CollectionCommandPhase.UNAVAILABLE
    var editor by remember(reads) { mutableStateOf<CollectionEditor?>(null) }
    var discardEditor by remember(reads) { mutableStateOf(false) }
    var tools by remember(reads) { mutableStateOf(false) }
    var chooser by remember(reads) { mutableStateOf<CollectionChooser?>(null) }
    var selectedAddition by remember(reads, state.routeCollectionId) { mutableStateOf<BlueprintSavedReference?>(null) }
    var selectedRemoval by remember(reads, state.routeCollectionId) { mutableStateOf<String?>(null) }
    var localNotice by remember(reads) { mutableStateOf<String?>(null) }
    var acknowledged by remember(reads) { mutableStateOf<CollectionCommandState?>(null) }
    var readAtAcknowledgement by remember(reads) { mutableStateOf<CollectionReadState?>(null) }
    SideEffect {
        if (current()) {
            if (denied) { editor = null; chooser = null; tools = false; discardEditor = false }
            if (commandState.phase == CollectionCommandPhase.COMPLETE && commandState.acknowledgedOperation != null &&
                acknowledged !== commandState) {
                acknowledged = commandState; readAtAcknowledgement = state
                editor = null; selectedAddition = null; selectedRemoval = null; discardEditor = false
            }
        }
    }
    val needsRefresh = readAtAcknowledgement === state || commandState.phase == CollectionCommandPhase.COMPLETE &&
        commandState.acknowledgedOperation != null && acknowledged !== commandState
    val manualCollection = state.selectedCollection?.let {
        (it.document.field("smartRule") as? WireField.Value)?.value?.stringOrNull() in setOf(null, "none")
    } ?: (state.screen == CollectionReadScreen.LIST)
    val canChange = current() && observed && !loading && !denied && !needsRefresh && commandState.inspected &&
        manualCollection && commandState.review == null && commandState.pending == null &&
        commandState.phase !in setOf(CollectionCommandPhase.EXPIRED, CollectionCommandPhase.UNAVAILABLE)
    val context = remember(state, commandState) { BlueprintLibraryContext("account-collections", state.revision + ":" + commandState.revision) }
    val entries = state.entries.map(::collectionSavedRow)
    val candidates = savedChoices.mapNotNull(::collectionCandidateRow).filter { candidate ->
        savedChoices.count { it.id == candidate.reference.id } == 1 && candidate.reference.id !in state.savedRecipeIds
    }
    val addition = candidates.singleOrNull { it.reference == selectedAddition }
    val selected = entries.singleOrNull { it.reference.id == selectedRemoval }?.reference
    val message = listOfNotNull(
        commandState.acknowledgedOperation?.let {
            "${collectionOperationLabel(it)} acknowledged." + if (needsRefresh) " Refresh to view the current collection." else ""
        },
        commandState.failureReason?.let(::collectionFailureMessage),
        collectionReadMessage(state), localNotice, openSavedStatus,
        if (!commandState.inspected) "Check retained changes in More before making a new change." else null,
    ).joinToString("\n\n")
    fun back() {
        if (!current() || commandState.phase == CollectionCommandPhase.WORKING) return
        if (loading) {
            // The parent retires this read/navigation attempt. An existing command is
            // neither discarded nor replaced; active consent and dirty edits stay here.
            if (commandState.review == null && editor?.dirty != true) onBack()
            return
        }
        when {
            commandState.review != null -> act { commands.back(commandState) }
            commandState.pending != null -> onBack()
            chooser != null -> chooser = null
            tools -> tools = false
            discardEditor -> discardEditor = false
            editor != null -> if (editor!!.dirty) discardEditor = true else editor = null
            else -> onBack()
        }
    }
    platformBackHandler(true, ::back)
    val review = commandState.review
    if (review != null) {
        val label = collectionOperationLabel(review.operationId, review.retryOriginal)
        val page = BlueprintConfirmationState(actionLabel = label,
            affectedSummary = collectionReviewSummary(review),
            consequences = collectionReviewConsequences(review.operationId, review.retryOriginal),
            canConfirm = current(), canCancel = current(), busy = loading,
            status = "Only this reviewed change will be sent. Back does not send it.")
        BlueprintConfirmationScreen(page, heading = "Your collection.\nYour call.", confirmLabel = label,
            onConfirm = { rendered -> if (rendered === page && page.confirmEnabled && commandState.review === review)
                act { commands.confirm(review) } },
            onCancel = { rendered -> if (rendered === page && page.cancelEnabled) back() })
        return
    }
    val pending = commandState.pending
    if (pending != null) {
        val page = BlueprintConfirmationState(actionLabel = "Original collection change",
            affectedSummary = listOfNotNull(collectionOperationLabel(pending.operationId),
                pending.collectionId?.let { "Collection $it" }, pending.version?.let { "Reviewed version $it" },
                pending.savedRecipeId?.let { "Saved meal $it" }).joinToString("\n"),
            consequences = listOf("The original request is retained. Its result may still be unresolved.",
                "Back leaves this view; it does not cancel a submitted change or replace its request."),
            canConfirm = current() && pending.canRetry && !pending.receiptReady, canCancel = current(), busy = loading,
            status = commandState.failureReason?.let(::collectionFailureMessage) ?: "Check the retained result before making another change.")
        BlueprintConfirmationScreen(page, heading = "Keep the\noriginal.", confirmLabel = "Review original retry",
            onConfirm = { rendered -> if (rendered === page && page.confirmEnabled) act { commands.prepareRetry(commandState) } },
            onCancel = { rendered -> if (rendered === page && page.cancelEnabled) back() },
            reviewLinks = { TextButton(enabled = current() && !loading, onClick = { act { commands.recoverReceipt(commandState) } }) {
                Text("Check retained result")
            } })
        return
    }
    if (discardEditor && editor != null && !denied) {
        val page = BlueprintConfirmationState(actionLabel = "Leave unsaved name",
            affectedSummary = "The name typed on this device has not been submitted.",
            consequences = listOf("Leaving discards only this in-memory edit. It does not change the collection or remove saved meals."),
            canConfirm = current(), canCancel = current(), busy = loading)
        BlueprintConfirmationScreen(page, heading = "Leave it\nfor now?", confirmLabel = "Leave editor",
            onConfirm = { rendered -> if (rendered === page && page.confirmEnabled && current()) { editor = null; discardEditor = false } },
            onCancel = { rendered -> if (rendered === page && page.cancelEnabled && current()) discardEditor = false })
        return
    }
    val edit = editor?.takeUnless { denied }
    if (edit != null) {
        val exactSource = if (edit.sourceId == null) state.screen == CollectionReadScreen.LIST else
            state.selectedCollection?.let { it.id == edit.sourceId && it.version == edit.sourceVersion } == true
        val editable = canChange && exactSource
        val page = BlueprintCollectionEditState(context,
            collection = edit.sourceId?.let { BlueprintCollectionReference(it, checkNotNull(edit.sourceVersion)) },
            draft = BlueprintCollectionDraft(edit.name, if (edit.sourceId != null) entries.map { it.reference } else emptyList()),
            availableSaves = if (edit.sourceId != null) entries else emptyList(),
            controls = BlueprintLibraryControls(enabled = current(), loading = loading,
                editableFields = if (!loading) setOf("name") else emptySet(),
                allowedActions = buildSet {
                    if (!loading) add(BlueprintLibraryAction.CANCEL_COLLECTION_EDIT)
                    if (editable && edit.valid) add(if (edit.sourceId == null) BlueprintLibraryAction.SAVE_COLLECTION else BlueprintLibraryAction.RENAME_COLLECTION)
                }, message = listOf(message,
                    if (edit.sourceId == null) "Create an empty collection, then add your saved meals." else "Rename only. Existing description and saved meals stay unchanged.",
                    "Ordering is not enabled in this version.",
                    if (!exactSource) "Refresh before reviewing this edit. If its version changed, leave this editor and open the current collection." else "",
                    if (!edit.valid) "Enter a collection name of 1–60 characters without control characters." else "").filter { it.isNotBlank() }.joinToString("\n\n")))
        BlueprintCollectionEditScreen(page,
            onName = { rendered, value -> if (rendered === context && current() && !loading) edit.name = value },
            onSavedChoice = { _, _ -> },
            onAction = { intent ->
                val exact = page.intent(intent.action)
                if (current() && intent.context === context && exact != null && exact.draft == intent.draft) when (intent.action) {
                    BlueprintLibraryAction.SAVE_COLLECTION -> if (editable && edit.valid) act { commands.prepareCreate(edit.name, null, state) }
                    BlueprintLibraryAction.RENAME_COLLECTION -> if (editable && edit.valid) act { commands.prepareUpdate(edit.name, edit.description, state) }
                    BlueprintLibraryAction.CANCEL_COLLECTION_EDIT -> back()
                    else -> Unit
                }
            }, onBack = ::back, onMore = { if (current()) tools = true }, onNavigate = {})
    } else if (state.screen == CollectionReadScreen.DETAIL) {
        val page = BlueprintCollectionState(context, state.selectedCollection?.let(::collectionLibraryRow), entries,
            selected = selected, addition = addition,
            controls = BlueprintLibraryControls(enabled = current(), loading = loading, contentUnavailable = !observed,
                allowedActions = buildSet {
                    if (observed && !loading && reads.canLeaveForSaved(state)) add(BlueprintLibraryAction.COLLECTION_RECIPE)
                    if (canChange) {
                        add(BlueprintLibraryAction.EDIT_COLLECTION); add(BlueprintLibraryAction.DELETE_COLLECTION)
                        if (addition != null) add(BlueprintLibraryAction.ADD_COLLECTION_SAVE)
                        if (selected != null) add(BlueprintLibraryAction.REMOVE_COLLECTION_SAVE)
                    }
                }, message = message + "\n\nUse More to choose a saved meal to add or remove."))
        BlueprintCollectionScreen(page, onAction = { intent ->
            val exact = page.intent(intent.action, intent.saved)
            if (current() && intent.context === context && exact != null && exact.saved == intent.saved && exact.collection == intent.collection) {
                when (intent.action) {
                    BlueprintLibraryAction.COLLECTION_RECIPE -> intent.saved?.let { saved ->
                        when (val selection = reads.selectSavedRecipe(saved.id, state)) {
                            is PortResult.Value -> if (current() && reads.isCurrent(selection.value)) onOpenSaved(state, selection.value)
                            is PortResult.Failure -> localNotice = "This saved meal observation changed. Refresh before opening it."
                        }
                    }
                    BlueprintLibraryAction.EDIT_COLLECTION -> state.selectedCollection?.let {
                        if (canChange) editor = CollectionEditor(it.id, it.version, it.name, it.description)
                    }
                    BlueprintLibraryAction.DELETE_COLLECTION -> if (canChange) act { commands.prepareDelete(state) }
                    BlueprintLibraryAction.ADD_COLLECTION_SAVE -> if (canChange) intent.saved?.let { act { commands.prepareAdd(it.id, state) } }
                    BlueprintLibraryAction.REMOVE_COLLECTION_SAVE -> if (canChange) intent.saved?.let { act { commands.prepareRemove(it.id, state) } }
                    else -> Unit
                }
            }
        }, onBack = ::back, onMore = { if (current()) tools = true }, onNavigate = {})
    } else {
        val page = BlueprintCookbookState(context, tab = BlueprintLibraryTab.COLLECTIONS,
            collections = state.collections.map(::collectionLibraryRow),
            controls = BlueprintLibraryControls(enabled = current(), loading = loading, contentUnavailable = denied,
                allowedActions = buildSet {
                    if (observed && !loading) add(BlueprintLibraryAction.OPEN_COLLECTION)
                    if (canChange) add(BlueprintLibraryAction.CREATE_COLLECTION)
                    if (!loading) add(BlueprintLibraryAction.LIBRARY_TOOLS)
                }, editableFields = if (!loading) setOf("tab") else emptySet(), message = message),
            availableTabs = setOf(BlueprintLibraryTab.ALL, BlueprintLibraryTab.COLLECTIONS))
        BlueprintCookbookScreen(page, onSearch = { _, _ -> },
            onTab = { rendered, tab -> if (rendered === context && current() && !loading && tab == BlueprintLibraryTab.ALL) back() },
            onAction = { intent ->
                val exact = page.intent(intent.action, collection = intent.collection)
                if (current() && intent.context === context && exact != null && exact.collection == intent.collection) when (intent.action) {
                    BlueprintLibraryAction.OPEN_COLLECTION -> intent.collection?.let { act { reads.openCollection(it.id, state) } }
                    BlueprintLibraryAction.CREATE_COLLECTION -> if (canChange) editor = CollectionEditor(null, null, "", null)
                    BlueprintLibraryAction.LIBRARY_TOOLS -> tools = true
                    else -> Unit
                }
            }, onBack = ::back, onMore = { if (current()) tools = true }, onNavigate = {})
    }
    if (tools && current() && !denied) FeedMeTheme {
        AlertDialog(onDismissRequest = { tools = false }, title = { Text("Collection options") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                TextButton(enabled = !loading, onClick = { act { reads.refresh(state) } }) { Text("Refresh this view") }
                if (state.hasMore) TextButton(enabled = !loading && observed, onClick = { act { reads.loadMore(state) } }) { Text("Load more") }
                if (!commandState.inspected || commandState.phase == CollectionCommandPhase.EXPIRED) TextButton(enabled = !loading,
                    onClick = { act { commands.open() } }) { Text("Check retained changes") }
                if (canChange && state.screen == CollectionReadScreen.DETAIL && edit == null) {
                    TextButton(onClick = { if (current()) { tools = false; chooser = CollectionChooser.ADD } }) { Text("Choose a saved meal to add") }
                    if (state.savedRecipeIds.isNotEmpty()) TextButton(onClick = { if (current()) { tools = false; chooser = CollectionChooser.REMOVE } }) { Text("Choose a saved meal to remove") }
                }
                Text("Reordering and smart collections are not enabled. Opening a meal checks its Saved resource separately.")
            }
        }, confirmButton = { TextButton(onClick = { tools = false }) { Text("Done") } })
    }
    if (chooser != null && current() && !denied) FeedMeTheme {
        val kind = chooser
        AlertDialog(onDismissRequest = { chooser = null }, title = { Text(if (kind == CollectionChooser.ADD) "Add one of your saves" else "Remove from this collection") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (kind == CollectionChooser.ADD) {
                    Text("Choose from your loaded saves. The current saved meal is checked again before review.")
                    if (candidates.isEmpty()) Text("No loaded saves are available to add. Return to All saves and load them first.")
                    candidates.forEach { candidate -> TextButton(enabled = canChange, onClick = {
                        if (current() && canChange) { selectedAddition = candidate.reference; chooser = null }
                    }) { Text(candidate.visibleTitle) } }
                } else {
                    Text("This removes collection membership only, not the saved recipe.")
                    state.entries.forEach { row ->
                        val presentation = collectionSavedRow(row)
                        TextButton(enabled = canChange, onClick = {
                            if (current() && canChange) {
                                selectedRemoval = row.savedRecipeId; chooser = null
                                // A missing Saved resource has no revision to fabricate. Removal
                                // still targets the exact observed collection membership.
                                if (row.version == null) act { commands.prepareRemove(row.savedRecipeId, state) }
                            }
                        }) { Text(presentation.visibleTitle + if (row.version == null) " · ${row.savedRecipeId}" else "") }
                    }
                }
            } }, confirmButton = { TextButton(onClick = { chooser = null }) { Text("Cancel") } })
    }
}

private enum class CollectionChooser { ADD, REMOVE }

private fun collectionReviewSummary(review: CollectionReview): String = listOfNotNull(
    review.name?.let { "Name: $it" }, review.description?.let { "Description: $it" },
    review.collectionId?.let { "Collection $it" }, review.version?.let { "Reviewed version $it" },
    review.itemTitle, review.savedRecipeId?.let { "Saved meal $it" },
).joinToString("\n").ifBlank { "Your new private collection" }

private fun collectionReviewConsequences(operation: String, retry: Boolean) = buildList {
    when (operation) {
        "createCollection" -> add("Create an empty private collection. Add saved meals afterward.")
        "updateCollection" -> add("Apply the reviewed name and description to this version of your collection.")
        "deleteCollection" -> add("Delete this collection and its membership. Your saved recipe copies are not deleted.")
        "addCollectionItem" -> add("Add this saved meal to the collection. It is not published or duplicated.")
        "removeCollectionItem" -> add("Remove this saved meal from the collection, without deleting your saved copy.")
        else -> add("Apply only this exact collection change.")
    }
    if (retry) add("Retry the same retained request, not a new action or version.")
    if (operation in setOf("updateCollection", "deleteCollection", "removeCollectionItem"))
        add("This uses the reviewed collection version. A conflict requires a fresh review, not an automatic rebase.")
    else add("The server checks this exact request. No existing recipe is changed.")
}

private class CollectionEditor(val sourceId: String?, val sourceVersion: String?,
    val originalName: String, val description: String?) {
    var name by mutableStateOf(originalName)
    val dirty get() = name != originalName
    val valid get() = name.isNotBlank() && name.length <= 60 && name.none(Char::isISOControl)
    override fun toString() = "CollectionEditor(<redacted>)"
}

private fun collectionLibraryRow(collection: CollectionSnapshot) = BlueprintLibraryCollection(
    BlueprintCollectionReference(collection.id, collection.version), collection.name,
    description = listOfNotNull(collection.description,
        "${collection.savedRecipeIds.size} listed save${if (collection.savedRecipeIds.size == 1) "" else "s"}" +
            if (collection.hasMoreItems) " · more available" else "").joinToString(" · "),
)

private fun collectionSavedRow(entry: CollectionSavedRecipeEntry): BlueprintSavedEntry {
    val saved = entry.savedRecipe
    val available = entry.unavailableReason == null && saved != null && !saved.recalled &&
        saved.snapshot.reviewStatus !in setOf("recalled", "retired")
    return BlueprintSavedEntry(
        // No made-up Saved revision for a canonical missing resource. The unavailable row
        // remains visible, while actions that require a Saved reference remain disabled.
        reference = BlueprintSavedReference(entry.savedRecipeId, entry.version.orEmpty()),
        title = if (available) saved!!.title else "Saved recipe unavailable",
        description = if (available) "${saved!!.snapshot.totalMinutes.jsonToken} min · ${saved.snapshot.servings.jsonToken} servings" else "",
        marker = if (available) BlueprintSavedMarker.AVAILABLE else BlueprintSavedMarker.UNAVAILABLE,
        badge = if (available) "Online observation" else null,
    )
}

private fun collectionCandidateRow(snapshot: SavedRecipeSnapshot): BlueprintSavedEntry? {
    val saved = snapshot.savedRecipe ?: return null
    val view = SavedRecipePresentation(snapshot)
    val recipe = view.recipe ?: return null
    return BlueprintSavedEntry(BlueprintSavedReference(snapshot.id, saved.version.jsonToken), view.title,
        "${recipe.totalMinutes.jsonToken} min · ${recipe.servings.jsonToken} servings",
        badge = if (view.downloaded) "Downloaded · historical" else "Loaded saved meal")
}

private fun collectionReadMessage(state: CollectionReadState): String = when (state.phase) {
    CollectionReadPhase.IDLE -> "Choose Refresh in More to load this view."
    CollectionReadPhase.LOADING -> "Loading your collections…"
    CollectionReadPhase.EMPTY -> if (state.screen == CollectionReadScreen.LIST) "Create a collection to organize your saved meals."
        else "This collection has no saved meals on this page."
    CollectionReadPhase.EXPIRED -> "This view has expired. Refresh in More before making changes."
    CollectionReadPhase.ERROR -> collectionFailureMessage(state.failureReason)
    CollectionReadPhase.UNAVAILABLE -> "Collections are unavailable. Back returns without changing your saves."
    else -> when {
        state.pageLimitReached -> "Only the loaded pages are shown. No complete count or order is implied."
        state.hasMore -> "More entries are available in More."
        else -> "Private collections organize your saves; they do not publish recipes."
    }
}

private fun collectionFailureMessage(reason: FailureReason?): String = when (reason) {
    FailureReason.OFFLINE -> "Connect to load or update your collections."
    FailureReason.CONFLICT -> "This collection changed. Refresh and review the current version."
    FailureReason.RATE_LIMITED -> "Please wait before explicitly trying again."
    FailureReason.OUTCOME_UNKNOWN -> "The change may have completed. Keep the original request and check its result."
    FailureReason.STORAGE_FAILURE -> "This device could not acknowledge the change. Its original request is retained."
    FailureReason.NOT_CONFIGURED -> "Collections are not connected in this configuration."
    FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.FORBIDDEN -> "Account access needs attention. Use Back to return."
    else -> "This action could not be completed. No success is shown without an acknowledged result."
}

private fun collectionOperationLabel(operation: String, retry: Boolean = false): String =
    (if (retry) "Retry original " else "") + when (operation) {
        "createCollection" -> if (retry) "creation" else "Create collection"
        "updateCollection" -> if (retry) "rename" else "Rename collection"
        "deleteCollection" -> if (retry) "deletion" else "Delete collection"
        "addCollectionItem" -> if (retry) "addition" else "Add saved meal"
        "removeCollectionItem" -> if (retry) "removal" else "Remove from collection"
        else -> if (retry) "change" else "Update collection"
    }
