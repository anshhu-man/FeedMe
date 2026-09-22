package com.feedme.server.planning

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.PortResult
import com.feedme.planning.*
import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.*
import com.feedme.server.cooking.AccountMealAccess
import com.feedme.server.db.CommandActor
import com.feedme.server.identity.AccountProfileStore
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Actual account/transaction owner of derived-Plan authorization. Serialized context is
 * evidence only: every use verifies its originals in the real publication journal, and
 * selection additionally requires unchanged current private inputs and policy/taxonomy.
 * It does not grant Saved-copy rights, authorize guest work, or publish any recipe. */
internal class AccountDerivedPlanningAuthority(
    private val environment: String,
    private val connection: Connection,
    private val actor: VerifiedPlanningPrincipal,
    accounts: AccountProfileStore,
    private val subject: VerifiedSupabaseSubject,
    private val device: UUID,
    private val journal: RecipeCatalogJournal,
    servicePolicy: PlanningServicePolicy,
    private val substitutions: RecipeSubstitutionJournal? = null,
    private val savedRights: RecipeCopyRightsStore? = null,
    private val postRecipeSources: (() -> com.feedme.server.social.posts.AccountPostRecipeSourceStore)? = null,
    private val planningInputs: (() -> PlanningPrivateInputsSnapshot)? = null,
    private val memoryRevalidate: (() -> Unit)? = null,
    private val memoryCheckAt: ((Instant) -> Unit)? = null,
) {
    private val thread = Thread.currentThread()
    private var active = true
    private val access = AccountMealAccess(environment, connection, accounts, subject, device)
    private val inputOwner = VerifiedKitchenPrincipal(environment, CommandActor.ACCOUNT, access.principalId, device)
    private var catalog: RecipeCatalogReadView? = null
    private var edges: RecipeSubstitutionReadView? = null
    private var observedAt: Instant? = null
    private var savedSources: AccountSavedPlanningSource? = null
    private val postSources = linkedMapOf<UUID, com.feedme.server.social.posts.AccountPostRecipeSource>()
    val policy = PlanningPolicy(servicePolicy.rankingVersion, servicePolicy.heatEnabled, servicePolicy.improveEnabled)

    init {
        if (actor.environment != environment || actor.kind != CommandActor.ACCOUNT ||
            actor.principalId != access.principalId || actor.deviceSessionId != device || journal.environment != environment ||
            substitutions?.environment?.let { it != environment } == true)
            fail(PlanningFailureCode.UNAUTHENTICATED)
    }

    fun current() {
        local()
        savedSources?.revalidate()
        catalog?.checkCurrent()
        edges?.checkCurrent()
        access.current(connection)
        memoryRevalidate?.invoke()
        postSources.values.toList().forEach { previous ->
            val actual = readPost(previous.postId)
            if (actual.evidence() != previous.evidence()) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
            postSources[previous.postId] = actual
        }
    }

    fun inputs(): PlanningPrivateInputsSnapshot {
        current()
        return planningInputs?.invoke() ?: AccountPlanningInputs(environment).lock(connection, inputOwner)
    }

    fun view(): RecipeCatalogReadView {
        local()
        return catalog ?: journal.openView(connection).also { catalog = it }
    }

    fun substitutionView(): RecipeSubstitutionReadView {
        local()
        return edges ?: (substitutions ?: fail(PlanningFailureCode.NOT_CONFIGURED)).openView(connection).also {
            // Both catalogs must be actual same-transaction heads; matching IDs by
            // themselves do not join a caller-supplied recipe list to an edge registry.
            val recipes = view()
            if (it.recipes.revision != recipes.revision || it.recipes.releaseId != recipes.releaseId ||
                it.recipes.requestSha256 != recipes.requestSha256 || it.recipes.taxonomySha256 != recipes.taxonomySha256)
                fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            edges = it
        }
    }

    /** Rejection only, using the caller's LAST database clock after all SQL/provider waits. */
    fun checkAt(at: Instant) {
        local()
        if (observedAt?.let { at < it } == true) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        observedAt = at
        savedSources?.checkAt(at)
        access.checkAt(connection, at)
        memoryCheckAt?.invoke(at)
        if (postSources.values.any { at >= it.validUntil }) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
    }

    fun close() { active = false }

    fun postMaterial(id: UUID, version: Long): com.feedme.server.social.posts.AccountPostRecipeSource {
        current()
        val actual = readPost(id)
        if (actual.postVersion != version) fail(PlanningFailureCode.VERSION_CONFLICT)
        postSources[id]?.let { if (it.evidence() != actual.evidence()) fail(PlanningFailureCode.RECIPE_UNAVAILABLE) }
        postSources[id] = actual
        return actual
    }

    private fun readPost(id: UUID): com.feedme.server.social.posts.AccountPostRecipeSource = try {
        (postRecipeSources ?: fail(PlanningFailureCode.NOT_CONFIGURED)).invoke().requireSource(connection, subject, device, id)
    } catch (failure: com.feedme.server.social.posts.PostReadFailure) {
        throw PlanningServiceFailure(when (failure.code) {
            com.feedme.server.social.posts.PostReadFailureCode.NOT_CONFIGURED -> PlanningFailureCode.NOT_CONFIGURED
            com.feedme.server.social.posts.PostReadFailureCode.STORAGE_UNAVAILABLE -> PlanningFailureCode.STORAGE_UNAVAILABLE
            com.feedme.server.social.posts.PostReadFailureCode.UNAUTHENTICATED -> PlanningFailureCode.UNAUTHENTICATED
            else -> PlanningFailureCode.RECIPE_UNAVAILABLE
        }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
    }

    fun savedMaterial(id: UUID): AccountSavedPlanningMaterial {
        current()
        val owner = savedSources ?: AccountSavedPlanningSource(environment, connection, actor.principalId,
            savedRights ?: fail(PlanningFailureCode.NOT_CONFIGURED)).also { savedSources = it }
        return owner.material(id)
    }

    fun savedSelection(id: UUID, material: AccountSavedPlanningMaterial): RecipeSubstitutionSavedSource {
        current()
        if (material.identity != savedMaterial(id).identity) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        val actual = view().lookupCurrent(UUID.fromString(material.identity.text("recipeVersionId")))
            ?: fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        if (actual.entry.status == "recalled") fail(PlanningFailureCode.RECIPE_RECALLED)
        if (actual.entry.status != "published" || recipeCopySourceSha256(actual.entry) != recipeCopySourceSha256(material.original.entry))
            fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        return RecipeSubstitutionSavedSource(id, actual)
    }

    /** Genuine no-parent catalog source. A copied source document/UUID is never a grant;
     * historical source, target and edge originals are read from the actual owned journals. */
    fun authorizeRoot(record: RootRecipePlanRecord, selection: Boolean) {
        current()
        val context = parse(record.contextText)
        if (context["owner"] != dpOwner(actor)) fail(PlanningFailureCode.PLAN_UNAVAILABLE)
        val edges = substitutionView()
        val p = context.getValue("policy").jsonObject
        val recordedPolicy = PlanningPolicy(p.text("version"), p.getValue("heatEnabled").jsonPrimitive.boolean,
            p.getValue("improveEnabled").jsonPrimitive.boolean, p.getValue("relatedTasteExplicitlyRequested").jsonPrimitive.boolean)
        val recordedInputs = PlanningPrivateInputsSnapshot.decode(context.text("inputsText").encodeToByteArray())
        if (selection) {
            if (!samePolicy(recordedPolicy, policy)) fail(PlanningFailureCode.INPUTS_CHANGED)
            val currentInputs = inputs()
            if (currentInputs.preferenceRevision != recordedInputs.preferenceRevision) fail(PlanningFailureCode.PREFERENCE_CHANGED)
            if (!currentInputs.samePrivateInputs(recordedInputs)) fail(PlanningFailureCode.INPUTS_CHANGED)
        }
        val view = view(); val binding = context.getValue("catalogAnchor").jsonObject
        val anchor = view.verifyAnchor(UUID.fromString(binding.text("releaseId")), binding.text("revision").toLong(),
            binding.text("requestSha256"), binding.text("taxonomyRevision"), binding.text("taxonomySha256"), binding.text("versionCount").toLong())
        if (selection && (view.taxonomyRevision != anchor.taxonomyRevision || view.taxonomySha256 != anchor.taxonomySha256)) fail(PlanningFailureCode.INPUTS_CHANGED)
        val provenance = context.getValue("provenance").jsonObject
        val saved = provenance["savedSource"]?.jsonObject
        val post = provenance["postSource"]?.jsonObject
        if (post != null && selection) {
            val actual = postMaterial(UUID.fromString(post.text("postId")), post.getValue("postVersion").jsonPrimitive.long)
            if (actual.evidence() != post) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        }
        val savedMaterial = saved?.let {
            val id = UUID.fromString(parse(context.text("requestText")).text("savedRecipeId"))
            savedMaterial(id).also { actual -> if (actual.evidence() != it) fail(PlanningFailureCode.RECIPE_UNAVAILABLE) }
        }
        val source = original(view, provenance.getValue("source").jsonObject, anchor, selection, saved != null)
        val request = WireDocument.parse(context.text("requestText"))
        val sourceBinding = if (savedMaterial != null) {
            if (source.entry.recipeVersionId != savedMaterial.original.entry.recipeVersionId ||
                recipeCopySourceSha256(source.entry) != recipeCopySourceSha256(savedMaterial.original.entry)) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
            RecipeSubstitutionSavedSource(UUID.fromString(savedMaterial.identity.text("savedRecipeId")), source)
        } else if (post == null) {
            if (dpJson(request)["sourceRecipeVersionId"] != JsonPrimitive(source.entry.recipeVersionId.toString())) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
            null
        } else null
        val postBinding = post?.let { RecipeSubstitutionPostSource(UUID.fromString(it.text("postId")), it.getValue("postVersion").jsonPrimitive.long, source) }
        provenance["target"]?.jsonObject?.let {
            val target = original(view, it, anchor, selection)
            val replacement = authorizeSubstitution(edges, provenance.getValue("edge").jsonObject, source, target, request, null, recordedPolicy, sourceBinding, postBinding)
            material(if (saved != null || post != null) WireDocument.parse(JsonObject(dpJson(request) - setOf("savedRecipeId", "sourcePostId", "sourcePostVersion")).toString()) else request, recordedInputs, recordedPolicy, anchor, target,
                parse(record.snapshotText).getValue("recipeSnapshot").jsonObject, setOf(replacement.toString()))
        }
        current()
    }

    fun authorizeStored(record: DerivedPlanStoredRecord, selection: Boolean) {
        current()
        val context = parse(record.contextText)
        val owner = context.getValue("owner").jsonObject
        if (owner.text("environment") != environment || owner.text("actorKind") != "account" ||
            owner.text("principalId") != actor.principalId.toString()) fail(PlanningFailureCode.PLAN_UNAVAILABLE)
        val operation = context.text("operationId")
        if (operation !in setOf("simplifyPlan", "adaptPlan")) fail(PlanningFailureCode.NOT_CONFIGURED)
        val substitutionView = if (operation == "adaptPlan") substitutionView() else null
        val recordedPolicy = context.getValue("policy").jsonObject.let {
            PlanningPolicy(it.text("version"), it.getValue("heatEnabled").jsonPrimitive.boolean,
                it.getValue("improveEnabled").jsonPrimitive.boolean, it.getValue("relatedTasteExplicitlyRequested").jsonPrimitive.boolean)
        }
        val recordedInputs = PlanningPrivateInputsSnapshot.decode(context.text("inputsText").toByteArray(Charsets.UTF_8))
        if (selection) {
            if (!samePolicy(recordedPolicy, policy)) fail(PlanningFailureCode.INPUTS_CHANGED)
            val currentInputs = inputs()
            if (currentInputs.preferenceRevision != recordedInputs.preferenceRevision) fail(PlanningFailureCode.PREFERENCE_CHANGED)
            if (!currentInputs.samePrivateInputs(recordedInputs)) fail(PlanningFailureCode.INPUTS_CHANGED)
        }
        val view = view()
        val binding = context.getValue("catalogAnchor").jsonObject
        val anchor = view.verifyAnchor(UUID.fromString(binding.text("releaseId")), binding.text("revision").toLong(),
            binding.text("requestSha256"), binding.text("taxonomyRevision"), binding.text("taxonomySha256"), binding.text("versionCount").toLong())
        if (selection && (view.taxonomyRevision != anchor.taxonomyRevision || view.taxonomySha256 != anchor.taxonomySha256))
            fail(PlanningFailureCode.INPUTS_CHANGED)
        val provenance = context.getValue("provenance").jsonObject
        val source = original(view, provenance.getValue("source").jsonObject, anchor, selection)
        val target = provenance["target"]?.jsonObject?.let { original(view, it, anchor, selection) }
        val parent = parse(context.text("parentSnapshotText"))
        val parentRequest = WireDocument.parse(context.text("parentPlanningRequestText"))
        // Check the actual reviewed materialization, including servings and scaling. An ID,
        // catalog hash, reviewed relationship, or copy grant alone cannot prove this body.
        material(parentRequest, recordedInputs, recordedPolicy, anchor, source, parent.getValue("recipeSnapshot").jsonObject)
        if (target != null) {
            val effective = parse(context.text("effectiveRequestText"))
            val replacement = substitutionView?.let {
                authorizeSubstitution(it, provenance.getValue("edge").jsonObject, source, target,
                    WireDocument.parse(context.text("effectiveRequestText")), WireDocument.parse(context.text("requestText")), recordedPolicy)
            }
            material(WireDocument.parse(JsonObject(effective - "sourceRecipeVersionId").toString()), recordedInputs,
                recordedPolicy, anchor, target, parse(record.snapshotText).getValue("recipeSnapshot").jsonObject,
                replacement?.let { setOf(it.toString()) } ?: emptySet())
            if (operation == "simplifyPlan") {
                val body = parse(context.text("requestText"))
                val servings = body.getValue("constraints").jsonObject.getValue("servings").jsonPrimitive.content.toBigDecimal()
                val goal = body.text("simplificationGoal")
                if (!compareRecipeEffort(source.entry, target.entry, servings).improves(goal)) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
                when (provenance.text("selectionKind")) {
                    "REVIEWED_VARIANT" -> {
                        val evidence = target.entry.simplificationSources.singleOrNull { it.sourceRecipeVersionId == source.entry.recipeVersionId }
                            ?: fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
                        if (goal !in evidence.goals || evidence.comparisonServings.compareTo(servings) != 0 ||
                            evidence.reviewReference != provenance.text("reviewReference")) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
                        validateRecipeSimplificationPair(source.entry, target.entry, evidence)
                    }
                    "DIFFERENT_MEAL" -> if (body["allowDifferentMeal"] != JsonPrimitive(true) ||
                        source.entry.recipe.getValue("recipeId") == target.entry.recipe.getValue("recipeId")) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
                    else -> fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                }
            }
        }
        current()
    }

    private fun authorizeSubstitution(view: RecipeSubstitutionReadView, evidence: JsonObject,
        source: RecipeCatalogVersion, target: RecipeCatalogVersion, effective: WireDocument,
        request: WireDocument?, policy: PlanningPolicy, savedSource: RecipeSubstitutionSavedSource? = null,
        postSource: RecipeSubstitutionPostSource? = null): UUID {
        val encoded = decodeRecipeSubstitutionRecord(evidence.text("recordText"))
        val old = view.lookupAt(encoded.definition.id, evidence.text("revision").toLong())
            ?: fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        if (old.publicationId.toString() != evidence.text("publicationId") || old.revision.toString() != evidence.text("revision") ||
            old.requestSha256 != evidence.text("requestSha256") || old.record.document.toString() != evidence.text("recordText") ||
            old.record.sha256 != evidence.text("recordSha256") || old.record.definition.sha256 != evidence.text("definitionSha256") ||
            old.catalogRevision.toString() != evidence.text("catalogRevision") || old.catalogReleaseId.toString() != evidence.text("catalogReleaseId") ||
            old.catalogRequestSha256 != evidence.text("catalogRequestSha256") || old.record.status != "reviewed")
            fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        val actual = view.lookupCurrent(encoded.definition.id) ?: fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        // The current model has only draft/reviewed/recalled. A recalled edge is NOT an
        // ordinary future-only withdrawal license; every dependent Plan purpose refuses it.
        // Existing independent Saved copies keep their separate retained-rights lifecycle.
        if (actual.record.status == "recalled" || actual.record.recall != null) fail(PlanningFailureCode.RECIPE_RECALLED)
        if (actual.record.status != "reviewed" || actual.record.definition.document != old.record.definition.document ||
            actual.record.version < old.record.version) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        val definition = old.record.definition
        validateRecipeSubstitutionPair(source.entry, target.entry, definition)
        val input = when (val parsed = substitutionSelectionInput(effective, request, savedSource, postSource)) {
            is PortResult.Value -> parsed.value
            is PortResult.Failure -> fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        }
        if (definition.policyVersion != policy.version || definition.comparisonServings.compareTo(input.servings) != 0 ||
            input.sourceId != source.entry.recipeVersionId || target.entry.recipeVersionId in input.excludedTargets ||
            input.replaceIngredientId?.let { it != definition.fromIngredientId } == true ||
            input.requestedReplacementId?.let { it != definition.toIngredientId } == true ||
            input.retainTasteTag?.let { it !in definition.preservedTags } == true ||
            input.retainTasteTag == "heat" && !policy.heatEnabled) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        return definition.toIngredientId
    }

    private fun original(view: RecipeCatalogReadView, evidence: JsonObject, anchor: RecipeCatalogAnchor,
        selection: Boolean, retainedSource: Boolean = false): RecipeCatalogVersion {
        val id = UUID.fromString(evidence.text("recipeVersionId"))
        val historical = view.lookupAt(id, anchor.revision) ?: fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        if (historical.releaseId.toString() != evidence.text("releaseId") || historical.revision.toString() != evidence.text("revision") ||
            historical.requestSha256 != evidence.text("requestSha256") || historical.entry.materialSha256 != evidence.text("materialSha256") ||
            historical.entry.recipe.toString() != evidence.text("recipeText") || historical.entry.review.toString() != evidence.text("reviewText") ||
            historical.entry.rightsReference != evidence.text("rightsReference")) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        val current = view.lookupCurrent(id) ?: fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        if (current.entry.status == "recalled") fail(PlanningFailureCode.RECIPE_RECALLED)
        if (current.entry.status !in (if (selection) setOf("published") else setOf("published", "retired")) ||
            (!retainedSource && current.entry.review.getValue("freeCatalogEligible") != JsonPrimitive(true)) ||
            current.entry.review != historical.entry.review || current.entry.rightsReference != historical.entry.rightsReference ||
            current.entry.materialSha256 != historical.entry.materialSha256) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        val old = historical.entry.recipe; val fresh = current.entry.recipe
        val version = fresh.getValue("version").jsonPrimitive.content.toBigDecimal().compareTo(old.getValue("version").jsonPrimitive.content.toBigDecimal())
        if (version < 0 || (version == 0 && old != fresh) || semantic(JsonObject(old - LIFECYCLE)) != semantic(JsonObject(fresh - LIFECYCLE)))
            fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        return historical
    }

    private fun material(request: WireDocument, inputs: PlanningPrivateInputsSnapshot, policy: PlanningPolicy,
        anchor: RecipeCatalogAnchor, source: RecipeCatalogVersion, snapshot: JsonObject, requiredAvailable: Set<String> = emptySet()) {
        val body = json(request)
        if (body.containsKey("savedRecipeId") || body.containsKey("sourcePostId")) fail(PlanningFailureCode.NOT_CONFIGURED)
        val effective = WireDocument.parse(JsonObject(body - "sourceRecipeVersionId").toString())
        val ingredients = anchor.ingredients.map { IngredientComposition(it.ingredientId.toString(), it.componentIds?.map(UUID::toString)?.toSet()) }
        val candidate = recipePlanningCandidate(source.entry)
        val decision = if (requiredAvailable.isEmpty()) {
            val selected = DeterministicPlanner(policy).plan(effective, inputs.context(),
                PlanningCatalog(anchor.revision.toString(), anchor.taxonomyRevision, listOf(candidate), ingredients))
            (selected as? PortResult.Value)?.value?.decision
        } else {
            // One actual already-authorized historical target, not a replay rescan or
            // fabricated whole-catalog witness. Preserve optional material while requiring
            // the real replacement to be available under the recorded explicit inputs.
            val selected = StreamingPlanner(policy).scanWithRequiredAvailability(effective, inputs.context(),
                PlanningCatalogHeader(anchor.revision.toString(), anchor.taxonomyRevision, 1, ingredients),
                PlanningCandidateSource { after, _ ->
                    if (after != null) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                    PlanningCandidateBatch(anchor.revision.toString(), anchor.taxonomyRevision, null, listOf(candidate), null)
                }, PlanningScanBudget(1, 1), pageSize = 1, classify = { PlanningScanPreference.PRIMARY }, requiredAvailable = { requiredAvailable })
            (selected as? PortResult.Value)?.value?.decision
        }
        if (decision?.status != PlanningStatus.READY || decision.recipe?.let { semantic(json(it.document)) } != semantic(snapshot))
            fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
    }

    private fun local() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Account derived planning interrupted")
        if (!active || Thread.currentThread() !== thread || connection.isClosed || connection.autoCommit) fail(PlanningFailureCode.UNAUTHENTICATED)
    }
    private fun samePolicy(a: PlanningPolicy, b: PlanningPolicy) = a.version == b.version && a.heatEnabled == b.heatEnabled &&
        a.improveEnabled == b.improveEnabled && a.relatedTasteExplicitlyRequested == b.relatedTasteExplicitlyRequested
    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun semantic(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${semantic(it.value)}" }
        is JsonArray -> value.joinToString(",", "[", "]") { semantic(it) }
        is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
            else value.content.toBigDecimal().stripTrailingZeros().toString()
    }
    private fun fail(code: PlanningFailureCode): Nothing = throw PlanningServiceFailure(code)
    override fun toString() = "AccountDerivedPlanningAuthority(<redacted>)"
    private companion object {
        val LIFECYCLE = setOf("version", "updatedAt", "reviewStatus", "recallReasonCode", "reviewedAt", "reviewerLabel")
    }
}
