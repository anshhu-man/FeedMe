package com.feedme.planning

import com.feedme.contracts.RecipeVersionWire
import com.feedme.contracts.WireDocument

enum class PlanningEnergy { ASSEMBLE, LITTLE, HAPPY }
enum class PlanningContentKind { MEAL, ADDITION }
enum class PlanningAvailability { CONFIRMED, UNCERTAIN, UNAVAILABLE }

/** Internal editorial read model, NOT a parallel transport DTO or self-certification API.
 * A trusted catalog adapter must bind this evidence to the exact supplied immutable body.
 * No constructor default certifies review, free access, operations, compatibility or scaling.
 */
class ReviewedPlanningEvidence(
    val reviewReference: String,
    val policyVersion: String,
    val kind: PlanningContentKind,
    val minimumEnergy: PlanningEnergy,
    val heatingRequired: Boolean,
    val substantialPreparation: Boolean,
    val freeCatalogEligible: Boolean,
    compatibleBaseTypes: Set<String>,
    val linearQuantityScalingReviewed: Boolean,
    val stepsValidForScalingRange: Boolean,
    val effortValidForScalingRange: Boolean,
    scalableUnits: Set<String>,
) {
    internal val baseTypes = compatibleBaseTypes.toSet()
    internal val units = scalableUnits.toSet()
    override fun toString() = "ReviewedPlanningEvidence(<redacted>)"
}

class PlanningCandidate(val recipe: RecipeVersionWire, val evidence: ReviewedPlanningEvidence) {
    override fun toString() = "PlanningCandidate(<redacted>)"
}

/** Null components means unknown composition; empty components means an explicitly known atom. */
class IngredientComposition(val ingredientId: String, componentIds: Set<String>?) {
    internal val components = componentIds?.toSet()
    override fun toString() = "IngredientComposition(<redacted>)"
}

/** Caller supplies the current authoritative, already-authorized free catalog. No I/O occurs. */
class PlanningCatalog(val revision: String, val taxonomyRevision: String,
    candidates: List<PlanningCandidate>, ingredients: List<IngredientComposition>) {
    internal val entries = candidates.toList()
    internal val taxonomy = ingredients.toList()
    override fun toString() = "PlanningCatalog(<redacted>)"
}

class ReportedIngredient(val ingredientId: String, val availability: PlanningAvailability) {
    override fun toString() = "ReportedIngredient(<redacted>)"
}

/** Exact current explicit preferences, never memory-inferred hard exclusions. */
class PlanningPreferences(val version: String, excluded: Set<String>, disliked: Set<String>,
    val personalizationEnabled: Boolean = true, memories: List<PlanningMemory> = emptyList()) {
    internal val exclusions = excluded.toSet()
    internal val dislikes = disliked.toSet()
    internal val memories = memories.toList()
    override fun toString() = "PlanningPreferences(<redacted>)"
}

/** Trusted owner read-model only, not feedback inference or permission to use a recipe.
 * Every supplied context dimension must match; an empty context has no ranking effect. */
data class PlanningMemory(val id: String, val version: String, val kind: String, val value: String,
    val recipeVersionId: String? = null, val ingredientId: String? = null,
    val tasteTag: String? = null, val effortAspect: String? = null) {
    override fun toString() = "PlanningMemory(<redacted>)"
}

/** Resolution from an explicit user confirmation, tied to the exact canonical baseMeal input.
 * A description/photo or nonempty ingredient list alone does not prove complete composition.
 */
class ConfirmedBaseMeal(val document: WireDocument, val catalogType: String, val compositionComplete: Boolean) {
    override fun toString() = "ConfirmedBaseMeal(<redacted>)"
}

/** Exact selected private copy, supplied only after the caller has checked ownership, current
 * retained rights and recall. Structural evidence, never a grant by itself. The recipe is the
 * saved material (including any previously authorized scaling), not the live catalog body. */
class PlanningSavedSource(val savedRecipeId: String, val recipe: RecipeVersionWire,
    val allowReviewedScaling: Boolean) {
    override fun toString() = "PlanningSavedSource(<redacted>)"
}

class PlanningContext(val preferences: PlanningPreferences, availability: List<ReportedIngredient>,
    val baseMeal: ConfirmedBaseMeal? = null, val savedSource: PlanningSavedSource? = null) {
    internal val pantry = availability.toList()
    override fun toString() = "PlanningContext(<redacted>)"
}

/** A versioned implementation policy, not a client request field. Heat/improve need release
 * approval AND reviewed coverage supplied by the trusted caller. Taste fallback is explicit.
 * Lexicographic policy: exact requested taste, fewer explicit dislikes, optional authorized
 * contextual memory preference, confirmed ingredients, lower active time, known cleanup,
 * then stable recipe/version IDs. With no memories the historical v1 ordering is unchanged.
 * Memories never become hard constraints or medical inference.
 */
class PlanningPolicy(val version: String, val heatEnabled: Boolean, val improveEnabled: Boolean,
    val relatedTasteExplicitlyRequested: Boolean = false) {
    override fun toString() = "PlanningPolicy(<redacted>)"
}

enum class PlanningStatus { READY, NEEDS_CONFIRMATION, NO_MATCH }
enum class PlanningIssue {
    INTERPRETATION_UNCONFIRMED, PREFERENCE_CHANGED, INPUT_CONFLICT, UNKNOWN_INGREDIENT,
    BASE_MEAL_UNCONFIRMED, MODE_CONFIRMATION, CONTENT_UNAVAILABLE, CONSTRAINT_UNVERIFIABLE,
    EXCLUSION, EQUIPMENT, TOTAL_TIME, ACTIVE_TIME, ENERGY, SERVINGS, INGREDIENT_UNAVAILABLE,
    INGREDIENT_UNCONFIRMED, TASTE, NO_COMPATIBLE_ADDITION, EXHAUSTED,
    CLEANUP_TIME,
    NO_SUPPORTED_SELECTION,
}

/** Immutable explanation facts belong to a private plan. No raw text enters diagnostics. */
class PlanningFact(val code: String, val label: String, val sourceMemoryId: String? = null) {
    override fun toString() = "PlanningFact(<redacted>)"
}

class PlanningDecision internal constructor(
    val status: PlanningStatus,
    val mode: String?,
    val constraints: WireDocument,
    /** Plan-local materialized snapshot only. Its version ID is reviewed source lineage (F01),
     * not authority to publish this scaled body as a catalog RecipeVersion or overwrite it. */
    val recipe: RecipeVersionWire?,
    missing: List<WireDocument>,
    issues: Set<PlanningIssue>,
    facts: List<PlanningFact>,
    val catalogRevision: String,
    val taxonomyRevision: String,
    val preferenceVersion: String,
    val policyVersion: String,
    val scaled: Boolean = false,
    /** Latest authoritative lifecycle check; catalogRevision remains the ranking snapshot. */
    val eligibilityCatalogRevision: String = catalogRevision,
) {
    private val missingValues = missing.toList()
    private val issueValues = issues.toSet()
    private val factValues = facts.toList()
    val missingIngredients: List<WireDocument> get() = missingValues.toList()
    val issues: Set<PlanningIssue> get() = issueValues.toSet()
    val facts: List<PlanningFact> get() = factValues.toList()
    override fun toString() = "PlanningDecision(status=$status, details=<redacted>)"
}

/** In-memory bounded navigation only. No serializable/signed server cursor or ownership claim.
 * Retrying the same continuation yields the same result against unchanged authoritative status.
 */
class PlanningContinuation internal constructor(
    internal val owner: Any, internal val request: WireDocument, internal val context: PlanningContext,
    internal val catalog: PlanningCatalog, internal val ordered: List<String>, internal val offset: Int,
) { override fun toString() = "PlanningContinuation(<redacted>)" }

class PlanningPage internal constructor(val decision: PlanningDecision, val next: PlanningContinuation?) {
    override fun toString() = "PlanningPage(<redacted>)"
}
