package com.feedme.development.progress

import com.feedme.contracts.CanonicalBodyValidator
import com.feedme.contracts.CanonicalResponseBinder
import com.feedme.contracts.ContractValidationResult
import com.feedme.contracts.ResponseBindingResult
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.AuthenticatedMealPlanningAccess
import com.feedme.mealflow.social.*
import com.feedme.session.PrivateSessionAccess
import com.feedme.session.PrivateSessionAccessMode
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Progress-variant only. The canonical author is the declared synthetic service author,
 * NEVER the storage actor, token, email or a live-provider mapping. Construction is read-only.
 * One actual native owner retains this object and its single delivery epoch owner. All methods
 * except the returned witness's documented atomic checks use the identity dispatcher.
 */
internal class ProgressReviewedPostIntegration(
    private val session: PrivateSessionAccess,
    actualAccess: AuthenticatedMealPlanningAccess,
    private val boundary: SessionBoundary,
    private val service: ProgressCanonicalService,
    private val nativeOwnerIsCurrent: () -> Boolean,
) : ReviewedKitchenIntegration {
    private var revocationStarted = false
    private var released = false
    private val source = PrincipalSource(actualAccess)
    private val deliveryOwner = DeliveryOwner()
    override val principals: PostPublicationPrincipalIntegration get() = source
    override val delivery: PostPublicationDeliveryIntegration get() = deliveryOwner
    private val invalidation = boundary.onInvalidated(session.lease) { revokeBeforeOwnerChange() }

    /** First block reentrant admission, then atomically revoke delivery, then clear mapping.
     * The native owner MUST call this before changing its own fields/lifetime. Boundary's
     * callback is only a backstop for invalidation initiated outside that owner.
     */
    fun revokeBeforeOwnerChange() {
        if (revocationStarted) return
        revocationStarted = true
        deliveryOwner.revoke()
        source.clearVerifiedMapping()
    }

    /** Borrower release only; never closes native access, service, stores or credentials. */
    fun close() {
        revokeBeforeOwnerChange()
        if (!released) { released = true; invalidation.close() }
    }

    override suspend fun disclosure(context: ReviewedPostPrerequisiteContext): PortResult<PublicationDisclosure> = guarded {
        currentContext(context, context.purpose)
        PublicationDisclosure(ProgressPostPreviewContract.disclosureVersion, ProgressPostPreviewContract.disclosureText)
    }

    override suspend fun requireNewPrivateSave(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveCheck): PortResult<Unit> = guarded {
        currentContext(context, ReviewedPostPurpose.PRIVATE_SAVE)
        ProgressReviewedPostChecks.newPrivateSave(context.clientDraftId, check)
        requireSavedBaseline(context, check.target, check.exactBaseline)
        currentContext(context, ReviewedPostPurpose.PRIVATE_SAVE)
    }

    override suspend fun requirePrivateSaveReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveReplayCheck): PortResult<Unit> = guarded {
        currentContext(context, ReviewedPostPurpose.PRIVATE_SAVE)
        ProgressReviewedPostChecks.privateSaveReplay(context.clientDraftId, check)
        // No latest GET or original-baseline expiry gate. The exact-key service replay checks
        // the actual successor and retained receipt; these are only local prerequisites.
        currentContext(context, ReviewedPostPurpose.PRIVATE_SAVE)
    }

    override suspend fun requireNewPublication(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationCheck): PortResult<Unit> = guarded {
        currentContext(context, ReviewedPostPurpose.PUBLICATION)
        ProgressReviewedPostChecks.newPublication(context.clientDraftId, check)
        // The service reads ALL permanent roots, not the active-only list endpoint. A missing
        // list item never grants direct-publication eligibility. Execution rechecks atomically.
        value(service.requireNewPublication(context.lease, PrivateBytes(check.exactPostWrite.encodeUtf8())))
        currentContext(context, ReviewedPostPurpose.PUBLICATION)
    }

    override suspend fun requirePublicationReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationReplayCheck): PortResult<Unit> = guarded {
        currentContext(context, ReviewedPostPurpose.PUBLICATION)
        ProgressReviewedPostChecks.publicationReplay(context.clientDraftId, check)
        currentContext(context, ReviewedPostPurpose.PUBLICATION)
    }

    private fun localCurrent(): Boolean = !revocationStarted && !released && nativeOwnerIsCurrent() &&
        service.matchesSession(session, boundary) && service.socialAvailable && session.scope == ProgressIdentity.scope &&
        session.mode == PrivateSessionAccessMode.ONLINE && boundary.isCurrent(session.lease)

    private suspend fun currentContext(context: ReviewedPostPrerequisiteContext, purpose: ReviewedPostPurpose) {
        currentCoroutineContext().ensureActive()
        if (context.purpose != purpose || context.lease !== session.lease || context.boundary !== boundary ||
            context.origin != session.originBinding || context.environment != ProgressIdentity.scope.environment ||
            !ProgressReviewedPostChecks.isUuid(context.clientDraftId)) reject(FailureReason.STALE_SESSION)
        val binding = source.bindingFor(context.principal) ?: reject(FailureReason.STALE_SESSION)
        value(source.requireCurrent(binding, context.principal))
        currentCoroutineContext().ensureActive()
        if (!source.isCurrent(binding, context.principal)) reject(FailureReason.STALE_SESSION)
    }

    private suspend fun requireSavedBaseline(context: ReviewedPostPrerequisiteContext, target: PublicationTarget.SavedDraft,
        exactBaseline: WireDocument) {
        val reply = value(service.execute(context.lease, ApiCall("getPostDraft", pathParameters = mapOf("draftId" to target.draftId))))
        currentContext(context, ReviewedPostPurpose.PRIVATE_SAVE)
        val bytes = reply.body?.copyForCodec() ?: reject(FailureReason.CONFLICT)
        if (bytes.size > ProgressPostPreviewContract.maxResponseBytes || reply.status != 200 || reply.etag != target.etag ||
            CanonicalResponseBinder().bind("getPostDraft", reply.status, bytes, reply.contentType, reply.traceId) !is ResponseBindingResult.Accepted ||
            Json.parseToJsonElement(bytes.decodeToString()) != Json.parseToJsonElement(exactBaseline.encodeUtf8().decodeToString()))
            reject(FailureReason.CONFLICT)
    }

    private inner class PrincipalSource(private val actual: AuthenticatedMealPlanningAccess) : PostPublicationPrincipalIntegration() {
        private val retainedOwner = Any()
        private val refreshGeneration = Any() // An integration is never revived after revoke.
        private val bindings = mutableListOf<PublicationSessionBinding>()
        private var verified = false
        fun clearVerifiedMapping() { verified = false }
        private fun bindingCurrent(binding: PublicationSessionBinding): Boolean = localCurrent() &&
            binding.lease === session.lease && binding.origin == session.originBinding &&
            binding.environment == session.scope.environment && matchesSession(binding, actual, boundary)
        private suspend fun verify(binding: PublicationSessionBinding) {
            currentCoroutineContext().ensureActive()
            if (!bindingCurrent(binding)) reject(FailureReason.STALE_SESSION)
            val credentials = value(session.credentials.read(session.scope))
            currentCoroutineContext().ensureActive()
            if (!bindingCurrent(binding)) reject(FailureReason.STALE_SESSION)
            if (!ProgressIdentity.matches(credentials)) {
                revokeBeforeOwnerChange()
                reject(FailureReason.UNAUTHENTICATED)
            }
            verified = true
        }
        override suspend fun resolve(binding: PublicationSessionBinding): PortResult<PublicationPrincipalSnapshot> = guarded {
            verify(binding)
            if (bindings.none { it === binding }) {
                // Exactly the actual draft/publication facades; replacement assemblies need
                // their own lifetime, not unbounded binding retention under this owner.
                if (bindings.size >= 2) reject(FailureReason.CONFLICT)
                bindings += binding
            }
            mappedPrincipal(binding, ProgressPostPreviewContract.authorId, retainedOwner, refreshGeneration)
        }
        override suspend fun requireCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): PortResult<Unit> = guarded {
            if (!isCurrent(binding, principal)) reject(FailureReason.STALE_SESSION)
            verify(binding)
            if (!isCurrent(binding, principal)) reject(FailureReason.STALE_SESSION)
        }
        override fun isCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): Boolean =
            verified && bindingCurrent(binding) && bindings.any { it === binding } &&
                principal.canonicalUserId == ProgressPostPreviewContract.authorId &&
                matchesCurrentPrincipal(binding, principal, retainedOwner, refreshGeneration)
        fun bindingFor(principal: PublicationPrincipalSnapshot): PublicationSessionBinding? =
            bindings.singleOrNull { isCurrent(it, principal) }
    }

    private inner class DeliveryOwner : PostPublicationDeliveryIntegration(source, maxPendingDeliveries = 4) {
        override fun capture(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): PortResult<PublicationDeliveryWitness> = try {
            if (!source.isCurrent(binding, principal)) reject(FailureReason.STALE_SESSION)
            PortResult.Value(witness(binding, principal, generationFor(principal)))
        } catch (failure: PrerequisiteFailure) { PortResult.Failure(failure.reason) }
        fun revoke() { revokeCurrent() }
    }

    private suspend fun <T> guarded(block: suspend () -> T): PortResult<T> = try {
        currentCoroutineContext().ensureActive()
        val result = block()
        currentCoroutineContext().ensureActive()
        if (!localCurrent()) reject(FailureReason.STALE_SESSION)
        PortResult.Value(result)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: PrerequisiteFailure) { PortResult.Failure(failure.reason) }
    catch (_: Exception) { PortResult.Failure(FailureReason.INVALID_DATA) }

    override fun toString() = "ProgressReviewedPostIntegration(<synthetic-on-device-only>)"
}

/** Data checks only. They create no IDs, requests, reviews, permissions or ACKs. Exact input
 * documents are never rewritten. Actual service execution remains the resource authority.
 */
internal object ProgressReviewedPostChecks {
    private val schemas = CanonicalBodyValidator.bundled()
    private val contentKeys = setOf("caption", "altText", "mediaIds", "audience", "keepOnPlate", "allowRecipeSaves", "saveDisclosureVersion", "attachment", "sourcePostId")
    fun isUuid(value: String) = value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))

    fun newPrivateSave(root: String, check: ReviewedPrivateSaveCheck) {
        val target = check.target
        requireSameRoot(root, target.clientDraftId)
        val before = baseline(root, target.draftId, target.draftVersion, target.etag, check.exactBaseline)
        val expected = patch(root, check.exactPatch, before, check.expectedFields)
        val original = observation(root, check.originalReviewedLocal)
        observation(root, check.separatelyObservedCurrentLocal)
        requireData(check.originalReviewedLocal.localRevision == target.localRevision && original == selection(expected))
        disclosure(check.displayedDisclosure, required = check.exactPatch.objectValue().containsKey("saveDisclosureVersion"))
    }

    fun privateSaveReplay(root: String, check: ReviewedPrivateSaveReplayCheck) {
        val original = check.original
        requireData(check.observedAttempts > 0 && original.localRevision > 0 && original.originalCreatedAtMillis >= 0 && isUuid(original.commandId))
        requireSameRoot(root, original.clientDraftId)
        val before = original.exactBaseline.objectValue()
        baseline(root, original.exactDraftPath, ExactPostVersion(version(before)), original.originalETag, original.exactBaseline)
        patch(root, original.exactPatch, before, original.expectedContent)
        disclosure(original.originalDisclosure, required = original.exactPatch.objectValue().containsKey("saveDisclosureVersion"))
        original.originalDisclosure?.let { requireData(original.historicalDisclosureText == it.text) }
    }

    fun newPublication(root: String, check: ReviewedPublicationCheck) {
        val request = postWrite(root, check.exactPostWrite)
        disclosure(check.displayedDisclosure, required = true)
        observation(root, check.separatelyObservedCurrentLocal)
        when (check.branch) {
            ReviewedPostBranch.DIRECT_LOCAL -> requireData(check.savedDraftId == null && check.savedDraftVersion == null &&
                check.savedDraftETag == null && check.exactSavedBaseline == null && "draftId" !in request && "draftVersion" !in request)
            ReviewedPostBranch.SAVED_DRAFT -> {
                val id = check.savedDraftId ?: reject(FailureReason.INVALID_DATA)
                val version = check.savedDraftVersion ?: reject(FailureReason.INVALID_DATA)
                val before = baseline(root, id, version, check.savedDraftETag ?: reject(FailureReason.INVALID_DATA),
                    check.exactSavedBaseline ?: reject(FailureReason.INVALID_DATA))
                requireData(request.string("draftId").equals(id, ignoreCase = true) && number(request.getValue("draftVersion")) == version.decimal &&
                    selection(request) == selection(before))
            }
        }
    }

    fun publicationReplay(root: String, check: ReviewedPublicationReplayCheck) {
        val original = check.original
        requireData(check.observedAttempts > 0 && original.originalCreatedAtMillis >= 0 && isUuid(original.commandId) &&
            original.originalCanonicalUserId == ProgressPostPreviewContract.authorId)
        requireSameRoot(root, original.clientDraftId)
        val request = postWrite(root, original.exactOriginalPostWrite)
        disclosure(original.displayedDisclosure, required = true)
        requireData(observation(root, original.originalReviewedLocal) == selection(request))
        requireSameRoot(root, check.originalTarget.clientDraftId)
        requireData(check.originalTarget.localRevision == original.originalReviewedLocal.localRevision)
        when (val target = check.originalTarget) {
            is PublicationTarget.DirectLocal -> requireData(check.exactSavedBaseline == null && "draftId" !in request && "draftVersion" !in request)
            is PublicationTarget.SavedDraft -> {
                val before = baseline(root, target.draftId, target.draftVersion, target.etag,
                    check.exactSavedBaseline ?: reject(FailureReason.INVALID_DATA))
                requireData(request.string("draftId").equals(target.draftId, ignoreCase = true) &&
                    number(request.getValue("draftVersion")) == target.draftVersion.decimal && selection(request) == selection(before))
            }
        }
    }

    private fun postWrite(root: String, document: WireDocument): JsonObject {
        schema("PostWrite", document, ProgressPostPreviewContract.maxRequestBytes)
        return document.objectValue().also {
            requireSameRoot(root, it.string("clientDraftId")); validateSelection(selection(it), requireDisclosure = true)
        }
    }
    private fun patch(root: String, document: WireDocument, before: JsonObject, expectedDocument: WireDocument): JsonObject {
        schema("PostDraftPatch", document, ProgressPostPreviewContract.maxRequestBytes)
        val patch = document.objectValue()
        patch["clientDraftId"]?.let { requireSameRoot(root, patch.string("clientDraftId")) }
        val projected = before.filterKeys { it !in setOf("updatedAt", "expiresAt") }.toMutableMap()
        patch.filterKeys { it !in setOf("clientDraftId", "removeAttachment") }.forEach { (key, value) -> projected[key] = value }
        if (patch["removeAttachment"] == JsonPrimitive(true)) projected.remove("attachment")
        projected["audience"] = selection(JsonObject(projected)).getValue("audience")
        projected["version"] = JsonPrimitive(BigInteger(version(before)) + BigInteger.ONE)
        val expected = expectedDocument.objectValue()
        requireData(expectedDocument.encodeUtf8().size <= ProgressPostPreviewContract.maxResponseBytes && JsonObject(projected) == expected)
        validateSelection(selection(expected), requireDisclosure = true)
        return expected
    }
    private fun baseline(root: String, id: String, expectedVersion: ExactPostVersion, etag: String, document: WireDocument): JsonObject {
        schema("PostDraft", document, ProgressPostPreviewContract.maxResponseBytes)
        return document.objectValue().also {
            requireSameRoot(root, it.string("clientDraftId"))
            requireData(isUuid(id) && it.string("id").equals(id, ignoreCase = true) && version(it) == expectedVersion.decimal &&
                etag == "\"${expectedVersion.decimal}\"" && it["status"] == JsonPrimitive("draft") && "publishedPostId" !in it)
            validateSelection(selection(it), requireDisclosure = false)
        }
    }
    private fun observation(root: String, value: PublicationLocalObservation): JsonObject {
        requireSameRoot(root, value.clientDraftId); requireData(value.localRevision > 0)
        val snapshot = value.exactHistoricalSnapshot.objectValue()
        requireSameRoot(root, snapshot.string("clientDraftId"))
        requireData(number(snapshot.getValue("localRevision")) == value.localRevision.toString())
        val content = snapshot.getValue("content").jsonObject
        requireData(content["kind"] == JsonPrimitive("composer-v2"))
        val selected = Json.parseToJsonElement(content.string("exactChoicesUtf8")).jsonObject
        validateSelection(selected, requireDisclosure = true)
        requireData(content["historicalDisclosureText"] == JsonPrimitive(ProgressPostPreviewContract.disclosureText))
        return selected
    }
    fun validateSelection(value: JsonObject, requireDisclosure: Boolean) {
        val caption = value["caption"] as? JsonPrimitive
        requireData(caption?.isString == true && caption.content.codePointCount(0, caption.content.length) <= 500)
        value["altText"]?.let {
            val alt = it as? JsonPrimitive
            requireData(alt?.isString == true && alt.content.codePointCount(0, alt.content.length) <= 500)
        }
        requireData(value.keys.all { it in contentKeys } && value["mediaIds"] == JsonArray(emptyList()) &&
            value["audience"] == buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(emptyList())) } &&
            value["allowRecipeSaves"] == JsonPrimitive(false) && value["keepOnPlate"] in setOf(JsonPrimitive(false), JsonPrimitive(true)) &&
            "attachment" !in value && "sourcePostId" !in value)
        if (requireDisclosure || "saveDisclosureVersion" in value)
            requireData(value["saveDisclosureVersion"] == JsonPrimitive(ProgressPostPreviewContract.disclosureVersion))
    }
    fun disclosure(value: PublicationDisclosure?, required: Boolean) {
        if (value == null) { requireData(!required); return }
        requireData(value.version == ProgressPostPreviewContract.disclosureVersion && value.text == ProgressPostPreviewContract.disclosureText)
    }
    private fun selection(value: JsonObject) = JsonObject(value.filterKeys { it in contentKeys }.mapValues { (key, element) ->
        if (key == "audience") JsonObject(element.jsonObject.filterKeys { it != "bindings" }) else element
    })
    private fun schema(name: String, value: WireDocument, limit: Int) {
        val bytes = value.encodeUtf8()
        requireData(bytes.size <= limit && schemas.validateSchema(name, bytes) == ContractValidationResult.Valid)
    }
    private fun requireSameRoot(root: String, other: String) { requireData(isUuid(root) && root.equals(other, ignoreCase = true)) }
    private fun version(value: JsonObject) = number(value.getValue("version"))
    private fun number(value: JsonElement): String {
        val primitive = value.jsonPrimitive
        requireData(!primitive.isString)
        return BigDecimal(primitive.content).toBigIntegerExact().toString()
    }
    private fun WireDocument.objectValue(): JsonObject = Json.parseToJsonElement(encodeUtf8().decodeToString()).jsonObject
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let { requireData(it.isString); it.content }
}

/** Explicit progress resource bounds, independent from service authorization and UI consent. */
internal object ProgressReviewedPostPolicies {
    val drafts = PostDraftClientPolicy(ProgressPostPreviewContract.maxRoots, 1_048_576,
        ProgressPostPreviewContract.maxPageBytes, 64, 16, 20, 100, 60_000)
    val publications = PostPublicationClientPolicy(1_048_576, ProgressPostPreviewContract.maxRequestBytes,
        ProgressPostPreviewContract.maxResponseBytes, ProgressPostPreviewContract.maxRoots,
        ProgressPostPreviewContract.maxCommands, ProgressPostPreviewContract.maxRoots,
        1, 1, 1, 4096, 4096, 60_000)
}

private class PrerequisiteFailure(val reason: FailureReason) : Exception("Progress prerequisite unavailable")
private fun reject(reason: FailureReason): Nothing = throw PrerequisiteFailure(reason)
private fun requireData(value: Boolean) { if (!value) reject(FailureReason.INVALID_DATA) }
private fun <T> value(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> reject(result.reason)
}
