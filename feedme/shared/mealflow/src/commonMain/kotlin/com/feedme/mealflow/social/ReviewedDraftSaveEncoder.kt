package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.mealFail
import com.feedme.mealflow.json
import kotlinx.serialization.json.*

/** NEW explicit PATCH only. Never use this encoder to reconstruct a retained original retry. */
internal class ReviewedDraftSaveEncoder(private val policy: PostPublicationClientPolicy) {
    fun encode(patch: ReviewedDraftPatch): WireDocument {
        val media = patch.orderedMediaIds
        if (media is PatchValue.Set) bound(media.value.size, policy.maxMediaSelections)
        val attachment = (patch.attachment as? PatchValue.Set)?.value?.let(::attachment)
        val audience = (patch.audience as? PatchValue.Set)?.value?.let { value -> when (value) {
            PublicationAudience.OnlyYou -> postSelfAudience()
            is PublicationAudience.Circles -> {
                val ids = value.orderedCircleIds; bound(ids.size, policy.maxCircleSelections)
                buildJsonObject { put("kind", "circles"); put("circleIds", JsonArray(ids.map(::JsonPrimitive))) }
            }
        } }
        val disclosure = (patch.disclosure as? PatchValue.Set)?.value
        disclosure?.let { boundedStrings(listOf(it.version, it.text), policy.maxDisclosureBytes)
            document(buildJsonObject { put("version", it.version); put("text", it.text) }, policy.maxDisclosureBytes) }
        val body = buildJsonObject {
            (patch.clientDraftId as? PatchValue.Set)?.let { put("clientDraftId", it.value) }
            (patch.caption as? PatchValue.Set)?.let { put("caption", it.value) }
            (patch.altText as? PatchValue.Set)?.let { put("altText", it.value) }
            if (media is PatchValue.Set) put("mediaIds", JsonArray(media.value.map(::JsonPrimitive)))
            attachment?.let { put("attachment", it) }; audience?.let { put("audience", it) }
            (patch.removeAttachment as? PatchValue.Set)?.let { put("removeAttachment", it.value) }
            (patch.keepOnPlate as? PatchValue.Set)?.let { put("keepOnPlate", it.value) }
            (patch.allowRecipeSaves as? PatchValue.Set)?.let { put("allowRecipeSaves", it.value) }
            disclosure?.let { put("saveDisclosureVersion", it.version) }
            (patch.sourcePostId as? PatchValue.Set)?.let { put("sourcePostId", it.value) }
        }
        val value = document(body, policy.maxOriginalBytes)
        when (val result = publicationSchemas.validateRequest("updatePostDraft", value.encodeUtf8(), "application/json")) {
            ContractValidationResult.Valid -> return value
            is ContractValidationResult.Rejected -> mealFail(if (result.reason == ContractRejectionReason.RESOURCE_LIMIT)
                FailureReason.UNAVAILABLE else FailureReason.INVALID_DATA)
        }
    }
    private fun attachment(value: PublicationAttachment): JsonObject {
        val changes = value.confirmedChanges; bound(changes.size, policy.maxConfirmedChanges)
        boundedStrings(changes, policy.maxAttachmentBytes)
        val result = buildJsonObject {
            when (val source = value.source) {
                is PublicationAttachmentSource.RecipeVersion -> put("recipeVersionId", source.recipeVersionId)
                is PublicationAttachmentSource.Plan -> put("planId", source.planId)
                is PublicationAttachmentSource.Personal -> put("personalRecipe", source.recipeDraft.json())
            }
            put("confirmedChanges", JsonArray(changes.map(::JsonPrimitive)))
            put("reviewStatus", if (value.reviewStatus == AttachmentReviewStatus.PERSONAL) "personal" else "reviewed")
            put("rightsBasis", if (value.rightsBasis == AttachmentRightsBasis.CREATOR_ORIGINAL) "creatorOriginal" else "catalogRedistributable")
        }
        document(result, policy.maxAttachmentBytes); return result
    }
    private fun bound(size: Int, maximum: Int) { if (size > maximum) mealFail(FailureReason.UNAVAILABLE) }
    private fun boundedStrings(values: List<String>, maximum: Int) {
        var left = maximum
        for (value in values) { val size = publicationStringBytes(value); if (size > left) mealFail(FailureReason.UNAVAILABLE); left -= size }
    }
    private fun document(value: JsonObject, max: Int): WireDocument = try { WireDocument.parse(value.toString(), WireLimits(maxBytes = max)) }
    catch (failure: WireDecodingException) { mealFail(if (failure.reason in setOf(WireFailure.BYTE_LIMIT, WireFailure.DEPTH_LIMIT, WireFailure.NUMBER_LIMIT))
        FailureReason.UNAVAILABLE else FailureReason.INVALID_DATA) }
    override fun toString() = "ReviewedDraftSaveEncoder(<redacted>)"
}
