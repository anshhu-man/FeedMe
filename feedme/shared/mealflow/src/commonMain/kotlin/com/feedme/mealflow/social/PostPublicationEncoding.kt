package com.feedme.mealflow.social

import com.feedme.contracts.ContractRejectionReason
import com.feedme.contracts.ContractValidationResult
import com.feedme.contracts.WireDecodingException
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireFailure
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.mealFail
import kotlinx.serialization.json.*

/** Pure NEW-request encoding only. No ApiCall/key, I/O, principal, confirmation, queue or ACK.
 * Retrying an original MUST use its retained bytes instead of re-encoding current choices.
 * UUID spelling/optional presence/array order/nested numeric tokens remain as explicitly chosen;
 * the receipt adapter's server-normalized expectation is a separate operation. */
internal class PostPublicationEncoder(private val policy: PostPublicationClientPolicy) {
    fun encode(target: PublicationTarget, choices: ReviewedPostChoices): WireDocument {
        val media = choices.orderedMediaIds
        bound(media.size, policy.maxMediaSelections)
        val audience = when (val selected = choices.audience) {
            PublicationAudience.OnlyYou -> buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(emptyList())) }
            is PublicationAudience.Circles -> {
                val circles = selected.orderedCircleIds; bound(circles.size, policy.maxCircleSelections)
                buildJsonObject { put("kind", "circles"); put("circleIds", strings(circles)) }
            }
        }
        // Both actual strings are retained for display, but only version belongs to PostWrite.
        boundStrings(listOf(choices.disclosure.version, choices.disclosure.text), policy.maxDisclosureBytes)
        val disclosure = buildJsonObject { put("version", choices.disclosure.version); put("text", choices.disclosure.text) }
        boundedDocument(disclosure, policy.maxDisclosureBytes)
        val attachmentBody = when (val selected = choices.attachment) {
            OptionalValue.Absent -> null
            is OptionalValue.Present -> attachment(selected.value)
        }
        val body = buildJsonObject {
            put("caption", choices.caption)
            put("mediaIds", strings(media)); put("audience", audience)
            put("keepOnPlate", choices.keepOnPlate); put("allowRecipeSaves", choices.allowRecipeSaves)
            put("saveDisclosureVersion", choices.disclosure.version); put("clientDraftId", target.clientDraftId)
            if (choices.altText is OptionalValue.Present) put("altText", choices.altText.value)
            if (attachmentBody != null) put("attachment", attachmentBody)
            if (choices.sourcePostId is OptionalValue.Present) put("sourcePostId", choices.sourcePostId.value)
            when (target) {
                is PublicationTarget.DirectLocal -> Unit
                is PublicationTarget.SavedDraft -> { put("draftId", target.draftId); put("draftVersion", Json.parseToJsonElement(target.draftVersion.decimal)) }
            }
        }
        val document = boundedDocument(body, policy.maxOriginalBytes)
        when (val checked = publicationSchemas.validateRequest("publishPost", document.encodeUtf8(), "application/json")) {
            ContractValidationResult.Valid -> return document
            is ContractValidationResult.Rejected -> mealFail(if (checked.reason == ContractRejectionReason.RESOURCE_LIMIT)
                FailureReason.UNAVAILABLE else FailureReason.INVALID_DATA)
        }
    }

    private fun attachment(value: PublicationAttachment): JsonObject {
        val changes = value.confirmedChanges; bound(changes.size, policy.maxConfirmedChanges)
        // Reject a list of huge retained strings BEFORE JSON construction/escaping. The later
        // exact document check also counts syntax/escapes; this is a bounded allocation precheck.
        boundStrings(changes, policy.maxAttachmentBytes)
        val body = buildJsonObject {
            when (val source = value.source) {
                is PublicationAttachmentSource.RecipeVersion -> put("recipeVersionId", source.recipeVersionId)
                is PublicationAttachmentSource.Plan -> put("planId", source.planId)
                is PublicationAttachmentSource.Personal -> put("personalRecipe", Json.parseToJsonElement(source.recipeDraft.encodeUtf8().decodeToString()))
            }
            put("confirmedChanges", strings(changes))
            put("reviewStatus", if (value.reviewStatus == AttachmentReviewStatus.PERSONAL) "personal" else "reviewed")
            put("rightsBasis", if (value.rightsBasis == AttachmentRightsBasis.CREATOR_ORIGINAL) "creatorOriginal" else "catalogRedistributable")
        }
        boundedDocument(body, policy.maxAttachmentBytes)
        return body
    }
    private fun boundedDocument(value: JsonObject, maxBytes: Int): WireDocument = try {
        WireDocument.parse(value.toString(), WireLimits(maxBytes = maxBytes))
    } catch (failure: WireDecodingException) {
        mealFail(if (failure.reason in setOf(WireFailure.BYTE_LIMIT, WireFailure.DEPTH_LIMIT, WireFailure.NUMBER_LIMIT))
            FailureReason.UNAVAILABLE else FailureReason.INVALID_DATA)
    }
    private fun bound(actual: Int, maximum: Int) { if (actual > maximum) mealFail(FailureReason.UNAVAILABLE) }
    private fun boundStrings(values: List<String>, maximum: Int) {
        var remaining = maximum
        for (value in values) {
            val size = publicationStringBytes(value)
            if (size > remaining) mealFail(FailureReason.UNAVAILABLE)
            remaining -= size
        }
    }
    override fun toString() = "PostPublicationEncoder(<redacted>)"
}

private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
