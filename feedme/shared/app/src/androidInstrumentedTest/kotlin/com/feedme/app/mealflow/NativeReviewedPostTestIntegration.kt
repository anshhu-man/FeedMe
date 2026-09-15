package com.feedme.app.mealflow

import com.feedme.contracts.CanonicalBodyValidator
import com.feedme.contracts.ContractValidationResult
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.social.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Required instrumentation-only policy owner. Every callback checks the actual mapped native
 * session and the explicit fixture's self-only/no-media/no-recipe selection. This is NOT a
 * production provider, feed permission, upload readiness or authoritative backend decision. */
internal class NativeReviewedPostTestIntegration(private val identity: NativeReviewedPostTestIdentity) : ReviewedKitchenIntegration {
    override val principals get() = identity
    override val delivery get() = identity.delivery
    val disclosureValue = PublicationDisclosure("synthetic-native-disclosure-v1",
        "Synthetic test disclosure: this post is only for you. Recipe saving is off. Nothing is sent until you confirm.")
    val calls = mutableListOf<String>()
    var disclosureGate: CompletableDeferred<Unit>? = null
    var publicationCheckGate: CompletableDeferred<Unit>? = null
    private val validator = CanonicalBodyValidator.bundled()

    override suspend fun disclosure(context: ReviewedPostPrerequisiteContext): PortResult<PublicationDisclosure> {
        identity.requireSyntheticContext(context); calls += "disclosure:" + context.purpose.name
        disclosureGate?.await()
        currentCoroutineContext().ensureActive(); identity.requireSyntheticContext(context)
        return PortResult.Value(disclosureValue)
    }
    override suspend fun requireNewPrivateSave(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveCheck): PortResult<Unit> {
        identity.requireSyntheticContext(context); kotlin.check(context.purpose == ReviewedPostPurpose.PRIVATE_SAVE)
        kotlin.check(check.target.clientDraftId == context.clientDraftId)
        kotlin.check(check.target.etag == "\"" + check.target.draftVersion.decimal + "\"")
        schema("PostDraft", check.exactBaseline)
        request("updatePostDraft", check.exactPatch)
        val baseline = json(check.exactBaseline)
        kotlin.check(baseline.getValue("id").jsonPrimitive.content == check.target.draftId)
        kotlin.check(baseline.getValue("clientDraftId").jsonPrimitive.content == context.clientDraftId)
        kotlin.check(baseline.getValue("version").jsonPrimitive.content == check.target.draftVersion.decimal)
        kotlin.check(baseline.getValue("status") == JsonPrimitive("draft"))
        selection(json(check.expectedFields))
        check.displayedDisclosure?.let(::disclosureMatches)
        calls += "new-private-save"
        return PortResult.Value(Unit)
    }
    override suspend fun requirePrivateSaveReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveReplayCheck): PortResult<Unit> {
        identity.requireSyntheticContext(context); kotlin.check(context.purpose == ReviewedPostPurpose.PRIVATE_SAVE)
        kotlin.check(check.original.clientDraftId == context.clientDraftId && check.observedAttempts > 0)
        request("updatePostDraft", check.original.exactPatch); schema("PostDraft", check.original.exactBaseline)
        selection(json(check.original.expectedContent)); check.original.originalDisclosure?.let(::disclosureMatches)
        calls += "original-private-save"
        return PortResult.Value(Unit)
    }
    override suspend fun requireNewPublication(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationCheck): PortResult<Unit> {
        identity.requireSyntheticContext(context); kotlin.check(context.purpose == ReviewedPostPurpose.PUBLICATION)
        request("publishPost", check.exactPostWrite); val body = json(check.exactPostWrite)
        kotlin.check(body.getValue("clientDraftId").jsonPrimitive.content == context.clientDraftId)
        selection(body); disclosureMatches(check.displayedDisclosure)
        when (check.branch) {
            ReviewedPostBranch.DIRECT_LOCAL -> kotlin.check(check.savedDraftId == null && check.savedDraftVersion == null &&
                check.savedDraftETag == null && check.exactSavedBaseline == null && "draftId" !in body && "draftVersion" !in body)
            ReviewedPostBranch.SAVED_DRAFT -> {
                val baseline = kotlin.checkNotNull(check.exactSavedBaseline); schema("PostDraft", baseline)
                kotlin.check(body.getValue("draftId").jsonPrimitive.content == check.savedDraftId)
                kotlin.check(body.getValue("draftVersion").jsonPrimitive.content == check.savedDraftVersion?.decimal)
                kotlin.check(check.savedDraftETag == "\"" + check.savedDraftVersion?.decimal + "\"")
                kotlin.check(json(baseline).getValue("status") == JsonPrimitive("draft"))
            }
        }
        calls += "new-publication:" + check.branch.name
        publicationCheckGate?.await()
        currentCoroutineContext().ensureActive(); identity.requireSyntheticContext(context)
        return PortResult.Value(Unit)
    }
    override suspend fun requirePublicationReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationReplayCheck): PortResult<Unit> {
        identity.requireSyntheticContext(context); kotlin.check(context.purpose == ReviewedPostPurpose.PUBLICATION)
        kotlin.check(check.original.clientDraftId == context.clientDraftId && check.observedAttempts > 0)
        kotlin.check(check.original.originalCanonicalUserId == NativeReviewedPostTestTransport.VERIFIED_USER)
        request("publishPost", check.original.exactOriginalPostWrite); selection(json(check.original.exactOriginalPostWrite))
        disclosureMatches(check.original.displayedDisclosure)
        calls += "original-publication"
        return PortResult.Value(Unit)
    }
    private fun selection(body: JsonObject) {
        val audience = body.getValue("audience").jsonObject
        check(audience.getValue("kind") == JsonPrimitive("self") && audience.getValue("circleIds") == JsonArray(emptyList()))
        check(body.getValue("mediaIds") == JsonArray(emptyList()))
        check("attachment" !in body && "sourcePostId" !in body)
        check(body.getValue("allowRecipeSaves") == JsonPrimitive(false))
        check(body.getValue("saveDisclosureVersion") == JsonPrimitive(disclosureValue.version))
    }
    private fun disclosureMatches(value: PublicationDisclosure) = check(value.version == disclosureValue.version && value.text == disclosureValue.text)
    private fun schema(name: String, value: WireDocument) = check(validator.validateSchema(name, value.encodeUtf8()) == ContractValidationResult.Valid)
    private fun request(operation: String, value: WireDocument) = check(validator.validateRequest(operation, value.encodeUtf8(), "application/json") == ContractValidationResult.Valid)
    private fun json(value: WireDocument) = Json.parseToJsonElement(value.encodeUtf8().decodeToString()).jsonObject
}
