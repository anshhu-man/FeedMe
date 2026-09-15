package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.serialization.json.*

/** Complete stored choices only; absent values stay absent and arrays retain exact order.
 * This parser creates display/edit data, never policy rights or confirmation. */
internal fun reviewedChoiceValues(document: WireDocument, historicalText: String?,
    suppliedDisclosure: PublicationDisclosure? = null): ReviewedPostChoices? {
    if (historicalText == null && suppliedDisclosure == null) return null
    val body = document.json().jsonObject
    fun string(name: String) = body.getValue(name).jsonPrimitive.content
    fun optional(name: String): OptionalValue<String> = body[name]?.let { OptionalValue.Present(it.jsonPrimitive.content) } ?: OptionalValue.Absent
    val audience = body.getValue("audience").jsonObject.let {
        when (it.getValue("kind").jsonPrimitive.content) {
            "self" -> PublicationAudience.OnlyYou
            "circles" -> PublicationAudience.Circles(it.getValue("circleIds").jsonArray.map { id -> id.jsonPrimitive.content })
            else -> mealFail(FailureReason.INVALID_DATA)
        }
    }
    val attachment = body["attachment"]?.jsonObject?.let { a ->
        val source = when {
            a.containsKey("recipeVersionId") -> PublicationAttachmentSource.RecipeVersion(a.getValue("recipeVersionId").jsonPrimitive.content)
            a.containsKey("planId") -> PublicationAttachmentSource.Plan(a.getValue("planId").jsonPrimitive.content)
            a.containsKey("personalRecipe") -> PublicationAttachmentSource.Personal(WireDocument.parse(a.getValue("personalRecipe").toString()))
            else -> mealFail(FailureReason.INVALID_DATA)
        }
        OptionalValue.Present(PublicationAttachment(source, a.getValue("confirmedChanges").jsonArray.map { it.jsonPrimitive.content },
            when (a.getValue("reviewStatus").jsonPrimitive.content) { "personal" -> AttachmentReviewStatus.PERSONAL
                "reviewed" -> AttachmentReviewStatus.REVIEWED; else -> mealFail(FailureReason.INVALID_DATA) },
            when (a.getValue("rightsBasis").jsonPrimitive.content) { "creatorOriginal" -> AttachmentRightsBasis.CREATOR_ORIGINAL
                "catalogRedistributable" -> AttachmentRightsBasis.CATALOG_REDISTRIBUTABLE; else -> mealFail(FailureReason.INVALID_DATA) }))
    } ?: OptionalValue.Absent
    return ReviewedPostChoices(string("caption"), optional("altText"), body.getValue("mediaIds").jsonArray.map { it.jsonPrimitive.content },
        audience, body.getValue("keepOnPlate").jsonPrimitive.boolean, attachment, body.getValue("allowRecipeSaves").jsonPrimitive.boolean,
        suppliedDisclosure ?: PublicationDisclosure(string("saveDisclosureVersion"), historicalText!!), optional("sourcePostId"))
}
internal fun reviewedChoicesDocument(policy: PostPublicationClientPolicy, root: String, revision: Long, choices: ReviewedPostChoices): WireDocument {
    // Existing value constructors and encoder retain exact optionals, order and source descriptors.
    postText(choices.caption, (choices.altText as? OptionalValue.Present)?.value)
    val write = PostPublicationEncoder(policy).encode(PublicationTarget.DirectLocal(root, revision), choices).json().jsonObject
    return WireDocument.parse(JsonObject(write.filterKeys { it != "clientDraftId" }).toString())
}
internal fun reviewedCompletePatch(selection: ReviewedPostSelection, choices: ReviewedPostChoices, baseline: WireDocument): ReviewedDraftPatch {
    val body = baseline.json().jsonObject
    // Canonical PATCH has no null/removal operation for these optionals. Do not silently
    // preserve a baseline value the user explicitly removed from their full local choices.
    if (choices.altText is OptionalValue.Absent && body.containsKey("altText") ||
        choices.sourcePostId is OptionalValue.Absent && body.containsKey("sourcePostId")) mealFail(FailureReason.INVALID_DATA)
    return ReviewedDraftPatch(PatchValue.Set(selection.clientDraftId), PatchValue.Set(choices.caption),
        (choices.altText as? OptionalValue.Present)?.let { PatchValue.Set(it.value) } ?: PatchValue.Unchanged,
        PatchValue.Set(choices.orderedMediaIds),
        (choices.attachment as? OptionalValue.Present)?.let { PatchValue.Set(it.value) } ?: PatchValue.Unchanged,
        if (choices.attachment is OptionalValue.Absent && body.containsKey("attachment")) PatchValue.Set(true) else PatchValue.Unchanged,
        PatchValue.Set(choices.audience), PatchValue.Set(choices.keepOnPlate), PatchValue.Set(choices.allowRecipeSaves),
        PatchValue.Set(choices.disclosure), (choices.sourcePostId as? OptionalValue.Present)?.let { PatchValue.Set(it.value) } ?: PatchValue.Unchanged)
}
