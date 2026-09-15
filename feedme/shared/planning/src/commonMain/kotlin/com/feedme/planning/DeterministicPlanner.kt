package com.feedme.planning

import com.feedme.contracts.*
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import kotlinx.serialization.json.*

/** Pure bounded filter-before-rank implementation. Caller authenticates catalog/preferences;
 * this does not fetch, authorize, sign a cursor, persist a plan, interpret language or mutate it.
 */
class DeterministicPlanner(private val policy: PlanningPolicy,
    private val validator: CanonicalBodyValidator = CanonicalBodyValidator.bundled()) {
    private val owner = Any()
    init { require(policy.version.isNotBlank() && policy.version.length <= 128) }

    fun plan(request: WireDocument, context: PlanningContext, catalog: PlanningCatalog): PortResult<PlanningPage> = protect {
        val input = input(request, context, catalog)
        input.confirmation?.let { return@protect PlanningPage(decision(input, catalog, it, PlanningStatus.NEEDS_CONFIRMATION), null) }
        val evaluated = catalog.entries.map { evaluate(it, input, catalog) }
        val eligible = evaluated.filter { it.issues.isEmpty() }.sortedWith(order)
        if (eligible.isNotEmpty()) return@protect page(eligible.first(), input, catalog,
            continuation(request, context, catalog, eligible.map { it.id }, 1))
        val uncertain = evaluated.filter { it.issues == setOf(PlanningIssue.INGREDIENT_UNCONFIRMED) }.sortedWith(order)
        if (uncertain.isNotEmpty()) return@protect page(uncertain.first(), input, catalog, null)
        val issues = evaluated.flatMap { it.issues }.toSet().ifEmpty { setOf(PlanningIssue.CONTENT_UNAVAILABLE) }
        PlanningPage(decision(input, catalog, issues, PlanningStatus.NO_MATCH,
            evaluated.filter { PlanningIssue.CONTENT_UNAVAILABLE !in it.issues && PlanningIssue.CONSTRAINT_UNVERIFIABLE !in it.issues }
                .sortedWith(order).firstOrNull()?.missing.orEmpty()), null)
    }

    /** An unchanged token is retryable, never mutable. Current source publication/recall is
     * rechecked; current taxonomy or same-ID immutable content changes invalidate traversal.
     * Current request/preferences/availability/base evidence is mandatory and must match exactly.
     * The original ranking catalogRevision is retained separately from eligibilityCatalogRevision.
     * This is not the HTTP lineage/cursor/idempotency or offline unseen-alternative service.
     */
    fun next(continuation: PlanningContinuation, currentRequest: WireDocument,
        currentContext: PlanningContext, currentCatalog: PlanningCatalog): PortResult<PlanningPage> = protect {
        if (continuation.owner !== owner) fail(FailureReason.INVALID_DATA)
        if (!continuation.request.encodeUtf8().contentEquals(currentRequest.encodeUtf8()) ||
            !sameContext(continuation.context, currentContext)) fail(FailureReason.CONFLICT)
        validateCatalog(currentCatalog)
        val original = continuation.catalog
        if (currentCatalog.taxonomyRevision != original.taxonomyRevision || !sameTaxonomy(original, currentCatalog))
            fail(FailureReason.CONFLICT)
        val input = input(continuation.request, continuation.context, original)
        for (position in continuation.offset until continuation.ordered.size) {
            val id = continuation.ordered[position]
            val previous = original.entries.single { normalized(it.recipe.id.value) == id }
            val current = currentCatalog.entries.find { normalized(it.recipe.id.value) == id } ?: continue
            if (current.recipe.reviewStatus != "published" || !current.evidence.freeCatalogEligible) continue
            if (!sameCandidate(previous, current)) fail(FailureReason.CONFLICT)
            val evaluated = evaluate(current, input, currentCatalog)
            if (evaluated.issues.isNotEmpty()) continue
            return@protect page(evaluated, input, original,
                continuation(continuation.request, continuation.context, original, continuation.ordered, position + 1),
                alternative = true, eligibilityRevision = currentCatalog.revision)
        }
        PlanningPage(decision(input, original, setOf(PlanningIssue.EXHAUSTED), PlanningStatus.NO_MATCH,
            eligibilityRevision = currentCatalog.revision), null)
    }

    private fun continuation(request: WireDocument, context: PlanningContext, catalog: PlanningCatalog,
        ids: List<String>, offset: Int): PlanningContinuation? = if (offset >= ids.size) null
        else PlanningContinuation(owner, request, context, catalog, ids.toList(), offset)

    private class Input(val raw: JsonObject, val constraints: WireDocument, val mode: String,
        val energy: PlanningEnergy, val servings: PlanningDecimal, val available: Set<String>,
        val pantry: Map<String, PlanningAvailability>, val excluded: Set<String>, val disliked: Set<String>,
        val equipment: Set<String>, val taste: Set<String>, val active: PlanningDecimal?, val total: PlanningDecimal?,
        val baseType: String?, val confirmation: Set<PlanningIssue>?, val preferenceVersion: String,
        val taxonomy: Taxonomy)

    private fun input(request: WireDocument, context: PlanningContext, catalog: PlanningCatalog): Input {
        if (validator.validateSchema("PlanRequest", request.encodeUtf8()) != ContractValidationResult.Valid) fail(FailureReason.INVALID_DATA)
        validateCatalog(catalog)
        val root = json(request); val c = root.getValue("constraints").jsonObject
        if (root["intent"]?.jsonPrimitive?.content == "tonight" || c.keys.any { it.startsWith("household") }) fail(FailureReason.NOT_CONFIGURED)
        val sources = listOf("sourceRecipeVersionId", "sourcePostId", "savedRecipeId").filter(root::containsKey)
        if (sources.size > 1) fail(FailureReason.INVALID_DATA)
        if (sources.any { it != "sourceRecipeVersionId" }) fail(FailureReason.NOT_CONFIGURED)
        if (!context.preferences.version.matches(Regex("[1-9][0-9]{0,127}"))) fail(FailureReason.INVALID_DATA)
        val ingredientIds = ids(c, "ingredientIds")
        val excluded = ids(c, "hardExcludedIngredientIds") + checkedIds(context.preferences.exclusions)
        val disliked = checkedIds(context.preferences.dislikes)
        val equipment = strings(c, "equipmentIds"); val taste = strings(c, "tasteTags")
        if (equipment.any { it.isBlank() || it.length > 128 }) fail(FailureReason.INVALID_DATA)
        if (context.pantry.size > 256) fail(FailureReason.INVALID_DATA)
        val pantry = context.pantry.associate { checkedId(it.ingredientId) to it.availability }
        if (pantry.size != context.pantry.size) fail(FailureReason.INVALID_DATA)
        val effective = JsonObject(c + ("hardExcludedIngredientIds" to JsonArray(excluded.sorted().map(::JsonPrimitive))))
        val energy = when (c.getValue("energy").jsonPrimitive.content) {
            "assemble" -> PlanningEnergy.ASSEMBLE; "little" -> PlanningEnergy.LITTLE; else -> PlanningEnergy.HAPPY
        }
        val mode = root.getValue("mode").jsonPrimitive.content
        val confirmation = linkedSetOf<PlanningIssue>()
        if (root["naturalLanguage"]?.jsonPrimitive?.content?.isNotBlank() == true &&
            root["confirmedInterpretation"]?.jsonPrimitive?.booleanOrNull != true) confirmation += PlanningIssue.INTERPRETATION_UNCONFIRMED
        if (!root.containsKey("preferenceVersion")) confirmation += PlanningIssue.PREFERENCE_CHANGED
        root["preferenceVersion"]?.jsonPrimitive?.content?.let {
            if (!sameInteger(it, context.preferences.version)) confirmation += PlanningIssue.PREFERENCE_CHANGED
        }
        val taxonomy = Taxonomy(catalog.taxonomy)
        if ((ingredientIds + excluded).any { closure(it, taxonomy) == null })
            confirmation += PlanningIssue.UNKNOWN_INGREDIENT
        if (ingredientIds.any { closure(it, taxonomy)?.any(excluded::contains) == true }) confirmation += PlanningIssue.INPUT_CONFLICT
        if (mode == "cook" && energy == PlanningEnergy.ASSEMBLE) confirmation += PlanningIssue.MODE_CONFIRMATION
        if ("heat" in taste && !policy.heatEnabled) fail(FailureReason.NOT_CONFIGURED)
        var baseType: String? = null
        if (mode == "improve" || (mode == "auto" && root.containsKey("baseMeal"))) {
            if (!policy.improveEnabled) fail(FailureReason.NOT_CONFIGURED)
            val base = root["baseMeal"] as? JsonObject
            val confirmed = context.baseMeal
            val baseIds = base?.let { ids(it, "ingredientIds") }.orEmpty()
            if (base == null || confirmed == null || json(confirmed.document) != base || !confirmed.compositionComplete ||
                confirmed.catalogType.isBlank() || confirmed.catalogType.length > 128 ||
                base["preparationState"]?.jsonPrimitive?.content != "alreadyPrepared" || baseIds.isEmpty())
                confirmation += PlanningIssue.BASE_MEAL_UNCONFIRMED
            else {
                baseType = confirmed.catalogType
                if (baseIds.any { closure(it, taxonomy) == null }) confirmation += PlanningIssue.BASE_MEAL_UNCONFIRMED
                if (baseIds.any { closure(it, taxonomy)?.any(excluded::contains) == true }) confirmation += PlanningIssue.INPUT_CONFLICT
            }
        }
        return Input(root, wire(effective), mode, energy, decimal(c.getValue("servings")), ingredientIds, pantry,
            excluded, disliked, equipment, taste, c["maxActiveMinutes"]?.let(::decimal), c["maxTotalMinutes"]?.let(::decimal),
            baseType, confirmation.takeIf { it.isNotEmpty() }, context.preferences.version, taxonomy)
    }

    private class Evaluation(val id: String, val recipe: RecipeVersionWire, val mode: String?,
        val issues: Set<PlanningIssue>, val missing: List<WireDocument>, val tasteMatches: Int,
        val confirmed: Int, val disliked: Int, val active: PlanningDecimal?, val cleanup: PlanningDecimal?, val scaled: Boolean)

    private fun evaluate(candidate: PlanningCandidate, input: Input, catalog: PlanningCatalog): Evaluation {
        val recipe = candidate.recipe; val evidence = candidate.evidence; val issues = linkedSetOf<PlanningIssue>()
        val r = json(recipe.document); var materialized = recipe; var scaled = false
        val taxonomy = input.taxonomy
        val tags = strings(r, "preparationTags"); val modes = strings(r, "modes")
        val ingredientIds = recipe.ingredients.map { normalized(it.ingredientId.value) }
        val equipment = strings(r, "equipmentIds")
        val active = numeric(r, "activeMinutes"); val total = numeric(r, "totalMinutes")
        val cleanup = numeric(r, "cleanupMinutes")
        val originalServings = numeric(r, "servings")
        val source = input.raw["sourceRecipeVersionId"]?.jsonPrimitive?.content
        if (source != null && normalized(source) != normalized(recipe.id.value)) issues += PlanningIssue.CONTENT_UNAVAILABLE
        if (recipe.reviewStatus != "published" || recipe.reviewedAt !is WireField.Value ||
            evidence.reviewReference.isBlank() || evidence.reviewReference.length > 256 || evidence.policyVersion != policy.version ||
            !evidence.freeCatalogEligible || r["estimateBasis"]?.jsonPrimitive?.content !in setOf("reviewerEstimate", "pilotObserved"))
            issues += PlanningIssue.CONTENT_UNAVAILABLE
        if (active == null || total == null || originalServings == null || active > total ||
            recipe.steps.isEmpty() || recipe.ingredients.isEmpty() || ingredientIds.toSet().size != ingredientIds.size ||
            recipe.steps.map { it.stepId.value }.toSet().size != recipe.steps.size ||
            recipe.steps.withIndex().any { (index, step) -> PlanningDecimal.parse(step.position.jsonToken)?.text() != (index + 1).toString() } ||
            recipe.steps.any { step -> step.stepId.value.isBlank() || step.instruction.isBlank() ||
                step.ingredientIds.any { normalized(it.value) !in ingredientIds } ||
                step.requiredEquipmentIds.any { it !in equipment } } ||
            !r.containsKey("preparationTags") || (evidence.heatingRequired && "noHeat" in tags) ||
            (!evidence.heatingRequired && "noHeat" !in tags) ||
            (evidence.minimumEnergy == PlanningEnergy.ASSEMBLE && (evidence.heatingRequired || evidence.substantialPreparation)))
            issues += PlanningIssue.CONSTRAINT_UNVERIFIABLE
        if (ingredientIds.any { closure(it, taxonomy) == null }) issues += PlanningIssue.CONSTRAINT_UNVERIFIABLE
        if (ingredientIds.any { closure(it, taxonomy)?.any(input.excluded::contains) == true }) issues += PlanningIssue.EXCLUSION
        if (!input.equipment.containsAll(equipment)) issues += PlanningIssue.EQUIPMENT
        if (input.active != null && (active == null || active > input.active)) issues += PlanningIssue.ACTIVE_TIME
        if (input.total != null && (total == null || total > input.total)) issues += PlanningIssue.TOTAL_TIME
        if (evidence.minimumEnergy > input.energy || (input.energy == PlanningEnergy.ASSEMBLE &&
            (evidence.heatingRequired || evidence.substantialPreparation || "noHeat" !in tags))) issues += PlanningIssue.ENERGY
        val improve = input.mode == "improve" || (input.mode == "auto" && input.raw.containsKey("baseMeal"))
        val mode = if (improve) {
            if (evidence.kind != PlanningContentKind.ADDITION || "improve" !in modes || input.baseType !in evidence.baseTypes)
                issues += PlanningIssue.NO_COMPATIBLE_ADDITION
            "improve"
        } else {
            if (evidence.kind != PlanningContentKind.MEAL) issues += PlanningIssue.CONTENT_UNAVAILABLE
            val selected = when (input.mode) {
                "cook", "assemble" -> input.mode
                else -> if (evidence.heatingRequired) "cook" else "assemble"
            }
            if (selected !in modes || (selected == "assemble" && (evidence.heatingRequired || evidence.substantialPreparation)))
                issues += PlanningIssue.ENERGY
            selected
        }
        if (originalServings == null) issues += PlanningIssue.SERVINGS
        else if (originalServings.compareTo(input.servings) != 0) {
            val minimum = numeric(r, "scalingMin"); val maximum = numeric(r, "scalingMax")
            if (minimum == null || maximum == null || minimum > maximum || originalServings < minimum || originalServings > maximum ||
                input.servings < minimum || input.servings > maximum || !evidence.linearQuantityScalingReviewed ||
                !evidence.stepsValidForScalingRange || !evidence.effortValidForScalingRange ||
                recipe.ingredients.any { it.unit !in evidence.units }) issues += PlanningIssue.SERVINGS
            else {
                val quantities = recipe.ingredients.map { ingredient ->
                    PlanningDecimal.parse(ingredient.quantity.jsonToken)?.let { input.servings.scaled(it, originalServings) }
                }
                if (quantities.any { it == null }) issues += PlanningIssue.SERVINGS
                else {
                    val amounts = r.getValue("ingredients").jsonArray.mapIndexed { index, item ->
                        JsonObject(item.jsonObject + ("quantity" to Json.parseToJsonElement(checkNotNull(quantities[index]))))
                    }
                    val updated = JsonObject(r + mapOf("servings" to Json.parseToJsonElement(input.servings.text()), "ingredients" to JsonArray(amounts)))
                    materialized = RecipeVersionWire.from(wire(updated)); scaled = true
                }
            }
        }
        // Optional ingredients are disclosed but never silently removed or treated as exclusion-safe.
        val missing = materialized.ingredients.filter { availability(normalized(it.ingredientId.value), input) != PlanningAvailability.CONFIRMED }
        val essential = missing.filterNot { it.optional }
        if (essential.any { availability(normalized(it.ingredientId.value), input) == PlanningAvailability.UNAVAILABLE })
            issues += PlanningIssue.INGREDIENT_UNAVAILABLE
        if (essential.any { availability(normalized(it.ingredientId.value), input) == PlanningAvailability.UNCERTAIN })
            issues += PlanningIssue.INGREDIENT_UNCONFIRMED
        val tasteMatches = input.taste.count { it in recipe.tasteTags }
        if (input.taste.isNotEmpty() && tasteMatches != input.taste.size &&
            (improve || !policy.relatedTasteExplicitlyRequested)) issues += PlanningIssue.TASTE
        return Evaluation(normalized(recipe.id.value), materialized, mode, issues, missing.map { it.document }, tasteMatches,
            recipe.ingredients.count { availability(normalized(it.ingredientId.value), input) == PlanningAvailability.CONFIRMED },
            ingredientIds.count { closure(it, taxonomy)?.any(input.disliked::contains) == true }, active, cleanup, scaled)
    }

    private val order = Comparator<Evaluation> { a, b ->
        compareValues(b.tasteMatches, a.tasteMatches).takeUnless { it == 0 }
            ?: compareValues(a.disliked, b.disliked).takeUnless { it == 0 }
            ?: compareValues(b.confirmed, a.confirmed).takeUnless { it == 0 }
            ?: compareValues(a.active, b.active).takeUnless { it == 0 }
            ?: compareValues(a.cleanup == null, b.cleanup == null).takeUnless { it == 0 }
            ?: compareValues(a.cleanup, b.cleanup).takeUnless { it == 0 }
            ?: normalized(a.recipe.recipeId.value).compareTo(normalized(b.recipe.recipeId.value)).takeUnless { it == 0 }
            ?: a.id.compareTo(b.id)
    }

    private fun page(evaluated: Evaluation, input: Input, catalog: PlanningCatalog, next: PlanningContinuation?,
        alternative: Boolean = false, eligibilityRevision: String = catalog.revision): PlanningPage {
        val ready = evaluated.issues.isEmpty()
        val facts = if (ready) buildList {
            if (evaluated.confirmed > 0) add(PlanningFact("ingredientFit", "Uses ingredients you confirmed; availability is not a freshness or quantity guarantee."))
            add(PlanningFact("effortFit", "Fits your selected preparation limits using the reviewed estimates."))
            if (input.taste.isNotEmpty()) add(PlanningFact("tasteFit", if (evaluated.tasteMatches == input.taste.size)
                "Matches your selected sensory tags." else "Related option: does not match every selected sensory tag."))
            if (alternative) add(PlanningFact("variety", "Another eligible recipe within the same confirmed choices."))
        } else listOf(PlanningFact("ingredientFit", "Confirm the missing essential ingredients before accepting this meal."))
        return PlanningPage(PlanningDecision(if (ready) PlanningStatus.READY else PlanningStatus.NEEDS_CONFIRMATION,
            evaluated.mode, input.constraints, if (ready) evaluated.recipe else null, evaluated.missing, evaluated.issues,
            facts, catalog.revision, catalog.taxonomyRevision, input.preferenceVersion, policy.version, evaluated.scaled, eligibilityRevision), next)
    }

    private fun decision(input: Input, catalog: PlanningCatalog, issues: Set<PlanningIssue>, status: PlanningStatus,
        missing: List<WireDocument> = emptyList(), eligibilityRevision: String = catalog.revision): PlanningDecision {
        val mode = when {
            PlanningIssue.MODE_CONFIRMATION in issues -> null
            input.mode != "auto" -> input.mode
            input.raw.containsKey("baseMeal") -> "improve"
            input.energy == PlanningEnergy.ASSEMBLE -> "assemble"
            else -> null // No arbitrary cooked/assembled mode is invented for an unresolved auto request.
        }
        val facts = issues.sortedBy { it.ordinal }.map { issue ->
            val code = when (issue) {
                PlanningIssue.TASTE -> "tasteFit"
                PlanningIssue.ENERGY, PlanningIssue.ACTIVE_TIME, PlanningIssue.TOTAL_TIME, PlanningIssue.EQUIPMENT,
                PlanningIssue.SERVINGS, PlanningIssue.MODE_CONFIRMATION -> "effortFit"
                PlanningIssue.EXHAUSTED -> "variety"
                else -> "ingredientFit"
            }
            val text = when (issue) {
                PlanningIssue.EXHAUSTED -> "No more matches for these choices."
                PlanningIssue.INTERPRETATION_UNCONFIRMED -> "Confirm the structured choices from your request."
                PlanningIssue.PREFERENCE_CHANGED -> "Refresh your preferences before matching."
                PlanningIssue.INPUT_CONFLICT -> "Resolve conflicting inputs; existing exclusions remain in force."
                PlanningIssue.UNKNOWN_INGREDIENT, PlanningIssue.CONSTRAINT_UNVERIFIABLE -> "Catalog composition cannot verify these constraints."
                PlanningIssue.BASE_MEAL_UNCONFIRMED -> "Confirm the prepared meal and its complete ingredients."
                PlanningIssue.MODE_CONFIRMATION -> "Confirm cooking or assembly intent without enabling heating."
                PlanningIssue.CONTENT_UNAVAILABLE -> "No currently published reviewed content is available for this request."
                PlanningIssue.EXCLUSION -> "A candidate conflicts with the ingredient exclusions."
                PlanningIssue.EQUIPMENT -> "Required equipment is not available."
                PlanningIssue.TOTAL_TIME -> "The reviewed total time exceeds the selected limit."
                PlanningIssue.ACTIVE_TIME -> "The reviewed active time exceeds the selected limit."
                PlanningIssue.ENERGY -> "The reviewed preparation exceeds the selected energy limit."
                PlanningIssue.SERVINGS -> "These servings lack a supported reviewed scaling result."
                PlanningIssue.INGREDIENT_UNAVAILABLE -> "An essential ingredient is reported unavailable."
                PlanningIssue.INGREDIENT_UNCONFIRMED -> "Confirm availability of the essential ingredients."
                PlanningIssue.TASTE -> "A candidate does not match all selected sensory tags."
                PlanningIssue.NO_COMPATIBLE_ADDITION -> "No reviewed compatible addition is available."
            }
            PlanningFact(code, text)
        }
        return PlanningDecision(status, mode, input.constraints, null, missing, issues, facts, catalog.revision,
            catalog.taxonomyRevision, input.preferenceVersion, policy.version, eligibilityCatalogRevision = eligibilityRevision)
    }

    private fun validateCatalog(catalog: PlanningCatalog) {
        if (catalog.revision.isBlank() || catalog.revision.length > 128 || catalog.taxonomyRevision.isBlank() ||
            catalog.taxonomyRevision.length > 128 || catalog.entries.size > 128 || catalog.taxonomy.size > 1024) fail(FailureReason.INVALID_DATA)
        val ids = catalog.entries.map { normalized(it.recipe.id.value) }
        if (ids.toSet().size != ids.size) fail(FailureReason.CONFLICT)
        val nodes = catalog.taxonomy.map { checkedId(it.ingredientId) }
        if (nodes.toSet().size != nodes.size) fail(FailureReason.CONFLICT)
        catalog.taxonomy.forEach { if ((it.components?.size ?: 0) > 128) fail(FailureReason.INVALID_DATA); it.components?.let(::checkedIds) }
        catalog.entries.forEach { entry ->
            if (entry.recipe.document.encodeUtf8().size > 65_536 ||
                validator.validateSchema("RecipeVersion", entry.recipe.document.encodeUtf8()) != ContractValidationResult.Valid ||
                entry.recipe.ingredients.size > 128 || entry.recipe.steps.size > 128 || entry.recipe.equipmentIds.size > 128 ||
                entry.evidence.baseTypes.size > 128 || entry.evidence.units.size > 128) fail(FailureReason.INVALID_DATA)
        }
    }
    private class Taxonomy(ingredients: List<IngredientComposition>) {
        private val nodes = ingredients.associate { it.ingredientId.lowercase() to it }
        private val cached = mutableMapOf<String, Set<String>?>()
        private var remainingEdges = 8192
        private var remainingUnions = 65_536
        init {
            // A bounded deterministic taxonomy pass, independent of request/candidate order.
            nodes.keys.sorted().forEach { closure(it) }
            require(remainingEdges >= 0 && remainingUnions >= 0) { "Planning taxonomy resource bound" }
        }
        fun closure(id: String, visiting: Set<String> = emptySet()): Set<String>? {
            if (cached.containsKey(id)) return cached[id]
            if (visiting.size >= 32 || id in visiting) return null
            val components = nodes[id]?.components ?: return null.also { cached[id] = null }
            val result = mutableSetOf(id)
            for (component in components.sorted()) {
                require(--remainingEdges >= 0) { "Planning taxonomy edge bound" }
                val expanded = closure(component.lowercase(), visiting + id)
                    ?: return null.also { cached[id] = null }
                remainingUnions -= expanded.size
                require(remainingUnions >= 0) { "Planning taxonomy union bound" }
                result += expanded
            }
            return result.toSet().also { cached[id] = it }
        }
    }
    private fun closure(id: String, taxonomy: Taxonomy) = taxonomy.closure(id)
    private fun availability(id: String, input: Input) = if (id in input.available) PlanningAvailability.CONFIRMED
        else input.pantry[id] ?: PlanningAvailability.UNCERTAIN
    private fun sameTaxonomy(a: PlanningCatalog, b: PlanningCatalog): Boolean =
        a.taxonomy.associate { normalized(it.ingredientId) to it.components?.map(::normalized)?.toSet() } ==
            b.taxonomy.associate { normalized(it.ingredientId) to it.components?.map(::normalized)?.toSet() }
    private fun sameContext(a: PlanningContext, b: PlanningContext): Boolean =
        a.preferences.version == b.preferences.version && a.preferences.exclusions == b.preferences.exclusions &&
            a.preferences.dislikes == b.preferences.dislikes &&
            a.pantry.map { it.ingredientId to it.availability } == b.pantry.map { it.ingredientId to it.availability } &&
            ((a.baseMeal == null && b.baseMeal == null) || (a.baseMeal != null && b.baseMeal != null &&
                a.baseMeal.catalogType == b.baseMeal.catalogType && a.baseMeal.compositionComplete == b.baseMeal.compositionComplete &&
                a.baseMeal.document.encodeUtf8().contentEquals(b.baseMeal.document.encodeUtf8())))
    private fun sameCandidate(a: PlanningCandidate, b: PlanningCandidate): Boolean =
        a.recipe.document.encodeUtf8().contentEquals(b.recipe.document.encodeUtf8()) &&
            a.evidence.let { x -> b.evidence.let { y -> x.reviewReference == y.reviewReference && x.policyVersion == y.policyVersion &&
                x.kind == y.kind && x.minimumEnergy == y.minimumEnergy && x.heatingRequired == y.heatingRequired &&
                x.substantialPreparation == y.substantialPreparation && x.freeCatalogEligible == y.freeCatalogEligible &&
                x.baseTypes == y.baseTypes && x.linearQuantityScalingReviewed == y.linearQuantityScalingReviewed &&
                x.stepsValidForScalingRange == y.stepsValidForScalingRange && x.effortValidForScalingRange == y.effortValidForScalingRange && x.units == y.units } }
    private fun strings(root: JsonObject, name: String): Set<String> {
        val values = root[name]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (values.size > 128 || values.toSet().size != values.size) fail(FailureReason.INVALID_DATA)
        return values.toSet()
    }
    private fun ids(root: JsonObject, name: String) = checkedIds(strings(root, name))
    private fun checkedIds(ids: Set<String>): Set<String> {
        if (ids.size > 256) fail(FailureReason.INVALID_DATA)
        val values = ids.map(::checkedId).toSet()
        if (values.size != ids.size) fail(FailureReason.INVALID_DATA)
        return values
    }
    private fun checkedId(value: String): String {
        try { IngredientId(value) } catch (_: Exception) { fail(FailureReason.INVALID_DATA) }
        return normalized(value)
    }
    private fun normalized(value: String) = value.lowercase()
    private fun numeric(root: JsonObject, name: String) = root[name]?.jsonPrimitive?.content?.let(PlanningDecimal::parse)
    private fun decimal(value: JsonElement) = PlanningDecimal.parse(value.jsonPrimitive.content) ?: fail(FailureReason.INVALID_DATA)
    private fun sameInteger(a: String, b: String): Boolean {
        // Preference revisions accept canonical schema integer spellings, without Double loss.
        val av = PlanningDecimal.parse(a); val bv = PlanningDecimal.parse(b)
        return if (av != null && bv != null) av.compareTo(bv) == 0 else a == b
    }
    private fun json(document: WireDocument) = Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    private fun wire(value: JsonElement) = WireDocument.parse(value.toString())
    private fun fail(reason: FailureReason): Nothing = throw PlanningFailure(reason)
    private inline fun <T> protect(action: () -> T): PortResult<T> = try { PortResult.Value(action()) }
        catch (failure: PlanningFailure) { PortResult.Failure(failure.reason) }
        catch (_: IllegalArgumentException) { PortResult.Failure(FailureReason.INVALID_DATA) }
    private class PlanningFailure(val reason: FailureReason) : Exception("Planning unavailable")
}
