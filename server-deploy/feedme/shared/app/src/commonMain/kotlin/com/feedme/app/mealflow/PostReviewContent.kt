package com.feedme.app.mealflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeDetails
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.social.*
import kotlinx.serialization.json.*

/** Display only. No request encoding, token construction, resource lookup or default choices. */
internal class PostReviewRow(val label: String, val value: String) {
    override fun toString() = "PostReviewRow(<redacted>)"
}

internal fun exactReviewText(value: JsonElement?): String = when (value) {
    null -> "Not included"
    JsonNull -> "Explicit null"
    is JsonPrimitive -> if (value.isString && value.content.isEmpty()) "Empty text (included)" else value.content
    is JsonArray -> if (value.isEmpty()) "Empty list (included)" else value.toString()
    is JsonObject -> if (value.isEmpty()) "Empty object (included)" else value.toString()
}

/** Presentation only: only actual JSON booleans become Yes/No. Null, absent, strings and
 * unexpected containers never turn into a default choice. Exact typed data stays in Details. */
internal fun reviewSettingText(value: JsonElement?): String = when {
    value == null -> "Not included"
    value === JsonNull -> "Explicit null"
    value is JsonPrimitive && !value.isString && value.booleanOrNull != null ->
        if (value.boolean) "Yes" else "No"
    else -> "Unrecognized value: $value"
}

/** Proposals and returned Posts have different shapes. Display each supplied representation;
 * never let a flat null/malformed value silently fall back to a returned policy (or vice versa). */
internal fun reviewRecipePolicyRows(document: WireDocument): List<PostReviewRow> {
    val body = Json.parseToJsonElement(document.encodeUtf8().decodeToString()) as? JsonObject
        ?: return listOf(PostReviewRow("Recipe saves", "Not included"))
    val rows = mutableListOf<PostReviewRow>()
    if ("allowRecipeSaves" in body)
        rows += PostReviewRow("Allow recipe saves", reviewSettingText(body["allowRecipeSaves"]))
    if ("savePolicy" in body) {
        val policy = body["savePolicy"]
        if (policy is JsonObject)
            rows += PostReviewRow("Allow future recipe saves · server policy", reviewSettingText(policy["allowFutureSaves"]))
        else rows += PostReviewRow("Returned recipe-save policy", if (policy === JsonNull) "Explicit null" else "Unrecognized policy value: $policy")
    }
    return rows.ifEmpty { listOf(PostReviewRow("Recipe saves", "Not included")) }
}

internal fun reviewRecipePolicyNotice(document: WireDocument): String? {
    val body = Json.parseToJsonElement(document.encodeUtf8().decodeToString()) as? JsonObject ?: return null
    val policy = body["savePolicy"] as? JsonObject ?: return null
    val settingDiffers = "allowRecipeSaves" in body && "allowFutureSaves" in policy &&
        body["allowRecipeSaves"] != policy["allowFutureSaves"]
    val disclosureDiffers = "saveDisclosureVersion" in body && "disclosureVersion" in policy &&
        body["saveDisclosureVersion"] != policy["disclosureVersion"]
    return if (settingDiffers || disclosureDiffers)
        "The supplied choices and returned policy differ. Both are shown; neither replaces the other."
    else null
}

internal fun reviewDisclosureVersionRows(document: WireDocument): List<PostReviewRow> {
    val body = Json.parseToJsonElement(document.encodeUtf8().decodeToString()) as? JsonObject ?: return emptyList()
    val rows = mutableListOf<PostReviewRow>()
    if ("saveDisclosureVersion" in body)
        rows += PostReviewRow("Choices · disclosure version", exactReviewText(body["saveDisclosureVersion"]))
    (body["savePolicy"] as? JsonObject)?.let { policy ->
        if ("disclosureVersion" in policy)
            rows += PostReviewRow("Server policy · disclosure version", exactReviewText(policy["disclosureVersion"]))
    }
    return rows
}

/** Readable labels only. Field order, unknown field names, exact text and numeric lexemes stay. */
internal fun readableReviewRows(document: WireDocument, omitClientId: Boolean = false): List<PostReviewRow> {
    val labels = mapOf("caption" to "Caption", "altText" to "Image description", "mediaIds" to "Selected media",
        "audience" to "Audience", "kind" to "Kind", "circleIds" to "Selected circles", "keepOnPlate" to "Keep on My Plate",
        "allowRecipeSaves" to "Allow recipe saves", "saveDisclosureVersion" to "Disclosure version",
        "sourcePostId" to "Source post", "attachment" to "Recipe attachment", "removeAttachment" to "Remove recipe attachment",
        "personalRecipe" to "Personal recipe", "recipeVersionId" to "Recipe version", "planId" to "Meal plan",
        "confirmedChanges" to "Confirmed changes", "reviewStatus" to "Review status", "rightsBasis" to "Rights basis",
        "ingredients" to "Ingredients", "ingredientId" to "Ingredient", "quantity" to "Quantity", "unit" to "Unit",
        "optional" to "Optional", "steps" to "Steps", "stepId" to "Step", "instruction" to "Instruction",
        "mandatorySafetyStep" to "Required safety step", "requiredEquipmentIds" to "Required equipment")
    val rows = mutableListOf<PostReviewRow>()
    fun visit(value: JsonElement, label: String, root: Boolean = false) {
        when {
            value is JsonObject && value.isNotEmpty() -> value.forEach { (key, child) ->
                if (!(root && omitClientId && key == "clientDraftId" && child is JsonPrimitive && child.isString))
                    visit(child, listOf(label, labels[key] ?: key).filter { it.isNotEmpty() }.joinToString(" · "))
            }
            value is JsonArray && value.isNotEmpty() -> value.forEachIndexed { index, child -> visit(child, "$label · ${index + 1}") }
            else -> rows += PostReviewRow(label.ifEmpty { "Value" }, if (value is JsonPrimitive &&
                !value.isString && value.booleanOrNull != null) reviewSettingText(value) else exactReviewText(value))
        }
    }
    visit(Json.parseToJsonElement(document.encodeUtf8().decodeToString()), "", true)
    return rows
}

/** Full detailed representation distinguishes text 'false' from Boolean false and preserves
 * every container, empty collection, null and ordered member. Missing fields stay absent. */
internal fun technicalReviewRows(document: WireDocument): List<PostReviewRow> {
    val rows = mutableListOf<PostReviewRow>()
    fun visit(value: JsonElement, label: String) {
        val text = when (value) {
            JsonNull -> "Null · null"
            is JsonObject -> if (value.isEmpty()) "Object · {}" else "Object · ${value.size} fields"
            is JsonArray -> if (value.isEmpty()) "Array · []" else "Array · ${value.size} items"
            is JsonPrimitive -> when {
                value.isString -> "Text · $value"
                value.booleanOrNull != null -> "Boolean · ${value.content}"
                else -> "Number · ${value.content}"
            }
        }
        rows += PostReviewRow(label.ifEmpty { "Document" }, text)
        when (value) {
            is JsonObject -> value.forEach { (key, child) -> visit(child, listOf(label, key).filter { it.isNotEmpty() }.joinToString(" · ")) }
            is JsonArray -> value.forEachIndexed { index, child -> visit(child, "$label · ${index + 1}") }
            else -> Unit
        }
    }
    visit(Json.parseToJsonElement(document.encodeUtf8().decodeToString()), "")
    return rows
}

/** Unknown additional material remains in the always-visible review, not only folded JSON. */
internal fun additionalPostReviewRows(document: WireDocument): List<PostReviewRow> {
    val body = Json.parseToJsonElement(document.encodeUtf8().decodeToString()) as? JsonObject
        ?: return readableReviewRows(document)
    val content = setOf("caption", "altText", "mediaIds", "audience", "keepOnPlate", "allowRecipeSaves",
        "saveDisclosureVersion", "sourcePostId", "attachment", "savePolicy")
    val technical = setOf("id", "clientDraftId", "version", "status", "createdAt", "updatedAt", "expiresAt", "publishedAt", "publishedPostId", "aclVersion")
    val extra = linkedMapOf<String, JsonElement>()
    body.forEach { (key, value) ->
        when {
            key == "audience" && value is JsonObject -> value.filterKeys { it !in setOf("kind", "circleIds") }
                .takeIf { it.isNotEmpty() }?.let { extra[key] = JsonObject(it) }
            key == "savePolicy" && value is JsonObject -> value.filter { (name, child) ->
                name !in setOf("allowFutureSaves", "disclosureVersion", "policyVersion") ||
                    (name == "policyVersion" && (child is JsonObject || child is JsonArray))
            }.takeIf { it.isNotEmpty() }?.let { extra[key] = JsonObject(it) }
            key !in content && (key !in technical || value is JsonObject || value is JsonArray) -> extra[key] = value
        }
    }
    return if (extra.isEmpty()) emptyList() else readableReviewRows(WireDocument.parse(JsonObject(extra).toString()))
}

/** Full nested recipe/attachment evidence in source order; numeric lexemes never use Double. */
internal fun exactReviewRows(document: WireDocument): List<PostReviewRow> {
    val rows = mutableListOf<PostReviewRow>()
    fun visit(value: JsonElement, label: String) {
        when {
            value is JsonObject && value.isNotEmpty() -> value.forEach { (key, child) ->
                visit(child, if (label.isEmpty()) key else "$label · $key")
            }
            value is JsonArray && value.isNotEmpty() -> value.forEachIndexed { index, child ->
                visit(child, "$label · ${index + 1}")
            }
            else -> rows += PostReviewRow(label.ifEmpty { "Value" }, exactReviewText(value))
        }
    }
    visit(Json.parseToJsonElement(document.encodeUtf8().decodeToString()), "")
    return rows.toList()
}

internal fun reviewAudienceLabel(document: WireDocument): String {
    val value = Json.parseToJsonElement(document.encodeUtf8().decodeToString()) as? JsonObject
    val audience = value?.get("audience") as? JsonObject
    return when ((audience?.get("kind") as? JsonPrimitive)?.content) {
        "self" -> "Only you"
        "circles" -> "Selected circles"
        else -> "Audience unavailable — do not infer who can see this"
    }
}

internal enum class DraftSaveUiRoute { LEGACY_SAVE, REVIEWED_SAVE, REVIEW_NOT_CONNECTED }
internal fun draftSaveUiRoute(requiresReview: Boolean, hasReviewEntry: Boolean): DraftSaveUiRoute = when {
    !requiresReview -> DraftSaveUiRoute.LEGACY_SAVE
    hasReviewEntry -> DraftSaveUiRoute.REVIEWED_SAVE
    else -> DraftSaveUiRoute.REVIEW_NOT_CONNECTED
}
internal fun publicationUnsentReviewAvailable(phase: String?, attempts: Int?): Boolean =
    phase in setOf("READY", "AWAITING_CONFIRMATION", "RETRY_WAIT", "NEEDS_RESOLUTION") && attempts == 0

internal fun reviewedDraftAcknowledgementText(localAcknowledged: Boolean, serverAcknowledged: Boolean,
    finalizationRequired: Boolean): String = when {
    finalizationRequired -> "The original private Save needs local confirmation. A refreshed draft is not an acknowledgement."
    !localAcknowledged -> "Your latest local edit is not confirmed by storage. Keep this session open; it has not been treated as saved."
    serverAcknowledged -> "The original private Save is confirmed. Newer local choices, if any, are separate; this never confirms publication."
    else -> "Kept on this device. Matching caption text alone does not prove that all audience, attachment and recipe-save choices were saved."
}

internal fun publicationOutcomeText(phase: PostComposerPhase, acknowledged: Boolean): String = when {
    phase == PostComposerPhase.PUBLISHED && acknowledged -> "Published to the selected audience. This action is confirmed on this device."
    phase == PostComposerPhase.CANCELLED_UNSENT && acknowledged -> "The unsent publication was cancelled. Its local draft was retained."
    phase == PostComposerPhase.PUBLISHED -> "A publication result is retained, but this device has not confirmed the action."
    phase == PostComposerPhase.CANCELLED_UNSENT -> "An unsent cancellation result is retained, but this device has not confirmed the action."
    else -> "Stored history is not a new publication confirmation."
}

internal fun publicationFailureText(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.NOT_CONFIGURED -> "This action is not connected for this session. Nothing was submitted by opening this screen."
    FailureReason.OFFLINE -> "You are offline. Your original is retained; reconnecting does not publish it."
    FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION -> "This account session is unavailable. Private review content is hidden."
    FailureReason.FORBIDDEN, FailureReason.NOT_FOUND -> "This content or audience is unavailable. No new publication has been confirmed."
    FailureReason.CONFLICT -> "The draft, review or permissions changed. Review again; an earlier original is not replaced."
    FailureReason.RATE_LIMITED -> "Please wait before trying the original action again. There is no automatic retry."
    FailureReason.UNAVAILABLE -> "A required service or device resource is unavailable. Nothing was silently removed or replaced."
    FailureReason.INVALID_DATA -> "The result could not be verified. It is not treated as a confirmed publication."
    FailureReason.STORAGE_FAILURE -> "Storage could not confirm this action. Keep the original request for reconciliation."
    FailureReason.OUTCOME_UNKNOWN -> "The original may have reached the server. Its result is not confirmed; do not start a replacement."
}

internal fun reviewedEntryFailureText(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.NOT_CONFIGURED -> "This reviewed action is not connected for this session. No missing choices or permissions are assumed."
    FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION -> "This account session is unavailable. Private review content is hidden."
    FailureReason.FORBIDDEN, FailureReason.NOT_FOUND -> "The draft, content or audience is unavailable. No new server action is confirmed."
    FailureReason.CONFLICT -> "The selected draft, review or permissions changed. Inspect it again; the original request is not replaced."
    FailureReason.OFFLINE -> "You are offline. Opening a review never sends its server action automatically."
    FailureReason.OUTCOME_UNKNOWN -> "The original outcome is unknown. Keep that exact request; do not treat a missing row as rollback or create a replacement."
    FailureReason.INVALID_DATA -> "The data could not be verified. No new server action is confirmed."
    FailureReason.RATE_LIMITED -> "Please wait before explicitly retrying the original action. There is no automatic retry."
    FailureReason.UNAVAILABLE -> "A required service or device resource is unavailable. Nothing was silently removed or replaced."
    FailureReason.STORAGE_FAILURE -> "Storage could not confirm this action. Retained originals and newer local changes stay separate."
}

@Composable
internal fun PostReviewSection(title: String, detail: String? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    Surface(color = Color.White, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted) }
            content()
        }
    }
}

@Composable
internal fun PostReviewValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Muted)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun PostExactEvidence(title: String, document: WireDocument) {
    val rows = remember(document) { technicalReviewRows(document) }
    FeedMeDetails(title) {
        Text("Complete recorded data. Missing fields are not included; null, empty values and their types are shown separately.", style = MaterialTheme.typography.bodySmall)
        rows.forEach { PostReviewValue(it.label, it.value) }
        Text("Exact JSON", style = MaterialTheme.typography.labelMedium)
        Text(document.encodeUtf8().decodeToString(), style = MaterialTheme.typography.bodySmall)
    }
}

/** The essential choices are always visible; only supporting full raw-shaped evidence folds.
 * No selected media is replaced with a stock image or inferred upload/permission success. */
@Composable
internal fun PostContentReview(document: WireDocument, title: String) {
    val body = remember(document) { Json.parseToJsonElement(document.encodeUtf8().decodeToString()) as? JsonObject }
    val media = body?.get("mediaIds")
    val attachment = body?.get("attachment")
    PostReviewSection(title) {
        PostReviewValue("Caption", exactReviewText(body?.get("caption")))
        PostReviewValue("Image description", exactReviewText(body?.get("altText")))
        PostReviewValue("Audience", reviewAudienceLabel(document))
        val audience = body?.get("audience") as? JsonObject
        val circles = audience?.get("circleIds") as? JsonArray
        circles?.forEachIndexed { index, circle -> PostReviewValue("Circle ${index + 1}", exactReviewText(circle)) }
        if (circles?.isEmpty() == true) PostReviewValue("Circle selection", "No circles selected (empty list)")
        if (circles == null) PostReviewValue("Circle selection", exactReviewText(audience?.get("circleIds")))
        if (audience == null || (audience["kind"] as? JsonPrimitive)?.content !in setOf("self", "circles"))
            PostReviewValue("Supplied audience", exactReviewText(body?.get("audience")))
        PostReviewValue("Keep on My Plate", reviewSettingText(body?.get("keepOnPlate")))
        remember(document) { reviewRecipePolicyRows(document) }.forEach { PostReviewValue(it.label, it.value) }
        reviewRecipePolicyNotice(document)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        remember(document) { reviewDisclosureVersionRows(document) }.forEach {
            Text(it.label + ": " + it.value, style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        }
        PostReviewValue("Source post", exactReviewText(body?.get("sourcePostId")))
        if (media is JsonArray && media.isNotEmpty()) {
            PostReviewValue("Selected media", "${media.size} selected · in this order")
            media.forEachIndexed { index, item -> PostReviewValue("Media ${index + 1}", exactReviewText(item)) }
            Text("Media previews, uploads and processing are not connected here. These selections do not prove readiness.", style = MaterialTheme.typography.bodySmall)
        } else PostReviewValue("Selected media", if (media is JsonArray) "No media selected (empty list)." else exactReviewText(media))
        if (attachment == null) PostReviewValue("Recipe attachment", "Not included")
    }
    if (attachment != null) {
        PostReviewSection("Recipe attachment", "Everything included is shown below. This is not a new ownership or rights check.") {
            val rows = remember(attachment) { readableReviewRows(WireDocument.parse(attachment.toString())) }
            rows.forEach { PostReviewValue(it.label, it.value) }
        }
    }
    val additional = remember(document) { additionalPostReviewRows(document) }
    if (additional.isNotEmpty()) PostReviewSection("Additional recorded information", "These supplied fields are kept as recorded, not inferred choices.") {
        additional.forEach { PostReviewValue(it.label, it.value) }
    }
}

@Composable
internal fun PostDisclosure(disclosure: PublicationDisclosure) {
    PostReviewSection("Recipe-save disclosure") {
        Text(disclosure.text, style = MaterialTheme.typography.bodyLarge)
        Text("Version: " + disclosure.version, style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
    }
}

@Composable
internal fun PostReviewTarget(target: PublicationTarget) {
    PostReviewSection("Draft for this review", when (target) {
        is PublicationTarget.DirectLocal -> "Direct publication from this local draft. No private server Save is added."
        is PublicationTarget.SavedDraft -> "This exact saved-draft version. A different server version needs a new review."
    }) {
        FeedMeDetails("Draft version details") {
            PostReviewValue("Local draft", target.clientDraftId)
            PostReviewValue("Local revision", target.localRevision.toString())
            if (target is PublicationTarget.SavedDraft) {
                PostReviewValue("Saved draft", target.draftId)
                PostReviewValue("Saved version", target.draftVersion.decimal)
                PostReviewValue("Exact ETag", target.etag)
            }
        }
    }
}

@Composable
internal fun ReviewedPrivateSaveReview(review: ReviewedDraftSavePresentation, enabled: Boolean, confirm: () -> Unit) {
    val snapshot = review.snapshot
    PostReviewSection("Review private Save", "Only the explicit changes below will be saved privately. This does not publish a post.")
    PostReviewTarget(snapshot.target)
    PostContentReview(snapshot.resultingChoices, "After this private Save")
    PostExactEvidence("Proposed content details", snapshot.resultingChoices)
    snapshot.displayedDisclosure?.let { PostDisclosure(it) } ?: PostReviewSection("Disclosure unchanged") {
        PostReviewValue("Historical disclosure text", snapshot.historicalDisclosureText ?: "No disclosure text was supplied for this retained version.")
    }
    PostReviewSection("Changes in this Save", "Only the fields below change. Everything else stays as it was. Empty or off choices are deliberate changes too.") {
        remember(snapshot.exactProposedPatch) { readableReviewRows(snapshot.exactProposedPatch, omitClientId = true) }.forEach { PostReviewValue(it.label, it.value) }
    }
    FeedMeDetails("Save version details") {
        PostReviewValue("Expected saved version", snapshot.expectedSuccessorVersion.decimal)
    }
    Text("This is a proposed result, not a server receipt. Back does not Save or Publish.", style = MaterialTheme.typography.bodySmall)
    PostExactEvidence("All Save request details", snapshot.exactProposedPatch)
    PostExactEvidence("Previous saved draft details", snapshot.baseline)
    Primary("Confirm private Save", enabled, confirm)
}

@Composable
internal fun ReviewedPrivateOriginalReview(review: ReviewedDraftRetryPresentation, enabled: Boolean, confirm: () -> Unit) {
    val snapshot = review.snapshot
    val original = snapshot.original
    val detail = when (snapshot.kind) {
        ReviewedDraftRetryKind.REGISTRATION_RECONCILIATION -> "The original was allocated, but registration is unresolved. Keep this exact request; this is not proof it was sent."
        ReviewedDraftRetryKind.FIRST_DISPATCH_REVIEW -> "This original has no observed attempt. Confirming may send it for the first time."
        ReviewedDraftRetryKind.ATTEMPTED_ORIGINAL_REPLAY -> "This original may already have reached the server. Retry its unchanged request, not your newer edits."
        ReviewedDraftRetryKind.LOCAL_RECEIPT_RECONCILIATION -> "Confirm the retained original result on this device. A fresh observation is not an acknowledgement."
    }
    PostReviewSection("Review original Save", detail) {
        FeedMeDetails("Original Save details") {
            PostReviewValue("Original request", original.commandId)
            PostReviewValue("Original local revision", original.localRevision.toString())
            PostReviewValue("Saved draft path", original.exactDraftPath)
            PostReviewValue("Original ETag", original.originalETag)
        }
    }
    PostContentReview(original.expectedContent, "Original proposed content")
    PostExactEvidence("Original proposed content details", original.expectedContent)
    original.originalDisclosure?.let { PostDisclosure(it) } ?: PostReviewSection("Original disclosure unchanged",
        original.historicalDisclosureText ?: "No disclosure text was supplied for the retained original version.")
    PostReviewSection("Changes in the original Save") {
        remember(original.exactPatch) { readableReviewRows(original.exactPatch, omitClientId = true) }.forEach { PostReviewValue(it.label, it.value) }
    }
    PostExactEvidence("All original Save request details", original.exactPatch)
    PostContentReview(snapshot.currentLocalChoices, "Current local choices — not this retry")
    PostExactEvidence("Current local choice details", snapshot.currentLocalChoices)
    FeedMeDetails("Current local version details") { PostReviewValue("Current local revision", snapshot.currentLocalRevision.toString()) }
    Text("Retry does not replace the original with these current choices. Newer local work remains separate.", style = MaterialTheme.typography.bodyMedium)
    snapshot.currentSavedDraft?.let {
        PostExactEvidence("Separately observed server draft — not a Save receipt", it.document)
        FeedMeDetails("Observed server version details") { PostReviewValue("Observed ETag", it.etag) }
    }
    PostExactEvidence("Original saved draft details", original.exactBaseline)
    Primary("Retry this original Save", enabled, confirm)
}
