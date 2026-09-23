package com.feedme.app.mealflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.feedme.app.FeedMeDetails
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.social.PostDraftScreen
import com.feedme.mealflow.social.RestoredLocalRetentionPresentation
import com.feedme.mealflow.social.DraftRecipeSourceKind
import kotlinx.serialization.json.JsonPrimitive

/** Affordance only: the actual prepare operation decides whether this is restored storage,
 * not an unresolved current edit. No public display flag grants retention permission. */
internal fun restoredRetentionReviewOffered(connected: Boolean, screen: PostDraftScreen,
    localAcknowledged: Boolean): Boolean = connected && screen == PostDraftScreen.EDITOR && !localAcknowledged

internal fun restoredRetentionReviewCurrent(actualNavigationCurrent: Boolean, sameSelection: Boolean,
    localAcknowledged: Boolean): Boolean = actualNavigationCurrent && sameSelection && !localAcknowledged

internal fun restoredRetentionFailureText(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION -> "This account session is unavailable. Restored private content is hidden."
    FailureReason.CONFLICT -> "This local review is no longer current, or these changes are not eligible for restored-data recovery. Back and open a fresh review. An unresolved current edit may need its existing retry instead."
    FailureReason.NOT_CONFIGURED -> "Restored-data recovery is not connected for this session. Nothing was kept or sent by opening this review."
    FailureReason.OUTCOME_UNKNOWN -> "Local storage may have changed, but this action was not acknowledged here. Open a new explicit recovery review; do not infer that storage rolled back. No server request was made by retention."
    FailureReason.STORAGE_FAILURE -> "Storage could not confirm local retention. Open a fresh review when available. This is not a private Save or publication."
    FailureReason.INVALID_DATA -> "The retained data could not be verified. No local acknowledgement is claimed; the original and newer changes are not replaced."
    FailureReason.NOT_FOUND, FailureReason.FORBIDDEN -> "The retained changes are unavailable to this review. Nothing was silently removed, saved or published."
    FailureReason.UNAVAILABLE, FailureReason.OFFLINE, FailureReason.RATE_LIMITED -> "Local retention could not proceed. Keep the retained data and open a fresh review when available. There is no automatic retry or server submission."
}

@Composable
internal fun RestoredPostRetentionReview(review: RestoredLocalRetentionPresentation, enabled: Boolean,
    confirm: () -> Unit) {
    val snapshot = review.snapshot
    PostReviewSection("Your recovered draft",
        "Review everything below, then keep it on this device. Nothing will be published.") {
        PostReviewValue("Caption", exactReviewText(JsonPrimitive(snapshot.caption)))
        PostReviewValue("Image description", exactReviewText(snapshot.altText?.let(::JsonPrimitive)))
    }
    if (snapshot.localPhotos.isNotEmpty()) PostReviewSection("Photos kept on this device",
        "These local photos remain attached and are not uploaded. Keeping them does not enable server Save or publication.") {
        snapshot.localPhotos.forEachIndexed { index, photo ->
            PostReviewValue("Photo ${index + 1}", "${photo.width} × ${photo.height} · ${photo.byteCount} bytes · ${photo.mimeType}")
        }
    }
    snapshot.recipeSourceSuggestion?.let { source ->
        PostReviewSection(if (source.sourceKind == DraftRecipeSourceKind.SAVED_RECIPE)
            "Retained saved recipe reference" else "Retained recipe reference",
            "Historical source information only. Keeping it does not attach the recipe or grant sharing permission.") {
            FeedMeDetails("Recipe reference details") {
                when (source.sourceKind) {
                    DraftRecipeSourceKind.PLAN -> {
                        PostReviewValue("Plan", source.planId ?: "Unavailable")
                        PostReviewValue("Plan revision", source.planVersion?.toString() ?: "Unavailable")
                    }
                    DraftRecipeSourceKind.SAVED_RECIPE -> {
                        PostReviewValue("Saved recipe", source.savedRecipeId ?: "Unavailable")
                        PostReviewValue("Saved-copy revision", source.savedRecipeVersion?.toString() ?: "Unavailable")
                    }
                }
                PostReviewValue("Recipe", source.recipeId)
                PostReviewValue("Recipe version", source.recipeVersionId)
                PostReviewValue("Recipe revision", source.recipeRevision.toString())
            }
        }
    }
    snapshot.exactChoices?.let { exact ->
        PostContentReview(exact, "Your choices — kept unchanged")
        PostExactEvidence("Retained choices — full exact evidence", exact)
    } ?: PostReviewSection(if (snapshot.localPhotos.isEmpty()) "A text-only draft" else "Caption and local photos",
        "This copy has no full server audience, uploaded-media or recipe-save choices. Keeping it does not add any.")
    PostReviewSection("Earlier recipe-save disclosure") {
        PostReviewValue("Saved disclosure text", exactReviewText(snapshot.historicalDisclosureText?.let(::JsonPrimitive)))
        Text("Kept for reference, not new consent.", style = MaterialTheme.typography.bodySmall)
    }
    snapshot.savedDraft?.let { saved ->
        PostReviewSection("Last known server copy",
            "Shown separately from your recovered changes. This is not a new server check or Save confirmation.")
        PostContentReview(saved.document, "Earlier server content")
        PostExactEvidence("Retained server observation — full evidence", saved.document)
    } ?: PostReviewSection("No server copy is included",
        "An earlier uncertain request may still exist. Keeping this copy does not cancel or retry it.")
    FeedMeDetails("Recovery details") {
        PostReviewValue("Local draft", snapshot.clientDraftId)
        PostReviewValue("Unchanged local revision", snapshot.localRevision.toString())
        PostReviewValue("Prepared at (device epoch milliseconds)", review.preparedAtMillis.toString())
        PostReviewValue("Review expires at (device epoch milliseconds)", review.expiresAtMillis.toString())
        snapshot.savedDraft?.let { saved ->
            PostReviewValue("Saved draft", saved.id)
            PostReviewValue("Exact retained ETag", saved.etag)
            PostReviewValue("Observed status", saved.status)
        }
        Text("Confirmation creates a new local acknowledgement, not an earlier one. The complete content and logical revision stay unchanged. No ID or network request is sent.",
            style = MaterialTheme.typography.bodySmall)
    }
    Text("This does not Save to the server or retry an earlier request. Back leaves the recovered draft as it is.", style = MaterialTheme.typography.bodySmall)
    Primary("Keep on this device", enabled, confirm)
}
