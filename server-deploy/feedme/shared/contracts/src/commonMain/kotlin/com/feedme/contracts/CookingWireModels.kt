package com.feedme.contracts

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val uuidSyntax = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private fun requireUuid(value: String) = require(uuidSyntax.matches(value)) { "Expected UUID syntax" }

/** Identifier roles are distinct; syntax checks do not establish existence or ownership. */
data class RecipeId(val value: String) {
    init { requireUuid(value) }
    override fun toString(): String = "RecipeId([redacted])"
}
data class RecipeVersionId(val value: String) {
    init { requireUuid(value) }
    override fun toString(): String = "RecipeVersionId([redacted])"
}
data class PlanId(val value: String) {
    init { requireUuid(value) }
    override fun toString(): String = "PlanId([redacted])"
}
data class CookSessionId(val value: String) {
    init { requireUuid(value) }
    override fun toString(): String = "CookSessionId([redacted])"
}
data class SavedRecipeId(val value: String) {
    init { requireUuid(value) }
    override fun toString(): String = "SavedRecipeId([redacted])"
}
data class CollectionId(val value: String) {
    init { requireUuid(value) }
    override fun toString(): String = "CollectionId([redacted])"
}
data class SourcePostId(val value: String) {
    init { requireUuid(value) }
    override fun toString(): String = "SourcePostId([redacted])"
}
data class GrantId(val value: String) {
    init { requireUuid(value) }
    override fun toString(): String = "GrantId([redacted])"
}
data class IngredientId(val value: String) {
    init { requireUuid(value) }
    override fun toString(): String = "IngredientId([redacted])"
}
data class TimerId(val value: String) {
    init { requireUuid(value) }
    override fun toString(): String = "TimerId([redacted])"
}
/** A canonical stable step identifier, never a display position or a UUID assumption. */
data class StepId(val value: String) {
    override fun toString(): String = "StepId([redacted])"
}

/**
 * Exact JSON numeric spelling. No conversion through Double, rounding, range reduction or claim
 * that the token meets a particular schema's integer/minimum constraints is made here.
 */
class ExactWireNumber private constructor(val jsonToken: String) {
    override fun toString(): String = "ExactWireNumber([redacted])"

    companion object {
        internal fun from(document: WireDocument): ExactWireNumber = ExactWireNumber(
            document.numberTokenOrNull() ?: projectionFailure(),
        )
    }
}

/**
 * Constructive CookStart convenience request. Cooking starts from a PlanId, never RecipeId.
 * The optional sequence builder supports nonnegative Long values; WireDocument retains larger
 * exact numbers when working directly with wire data. This is not general schema validation.
 */
class CookStart(val planId: PlanId, val deviceSequence: Long? = null) {
    init { require(deviceSequence == null || deviceSequence >= 0) { "Device sequence must be nonnegative" } }

    val document: WireDocument = WireDocument.fromElement(buildJsonObject {
        put("planId", planId.value)
        deviceSequence?.let { put("deviceSequence", it) }
    })

    override fun toString(): String = "CookStart([redacted])"
}

/**
 * Canonical SaveRecipeRequest's anyOf is nonexclusive: either selector or BOTH are valid.
 * Null constructor arguments mean absent; these request properties do not allow JSON null.
 * This builds the generic private-save request, not the separate savePostRecipe grant transaction.
 */
class SaveRecipeRequest(
    val planId: PlanId? = null,
    val recipeVersionId: RecipeVersionId? = null,
    val title: String? = null,
    val collectionId: CollectionId? = null,
    val markMakeAgain: Boolean? = null,
) {
    init {
        require(planId != null || recipeVersionId != null) { "At least one save selector is required" }
        require(title == null || title.unicodeCodePointCount() <= 120) { "Save title exceeds 120 characters" }
    }

    val document: WireDocument = WireDocument.fromElement(buildJsonObject {
        planId?.let { put("planId", it.value) }
        recipeVersionId?.let { put("recipeVersionId", it.value) }
        title?.let { put("title", it) }
        collectionId?.let { put("collectionId", it.value) }
        markMakeAgain?.let { put("markMakeAgain", it) }
    })

    override fun toString(): String = "SaveRecipeRequest([redacted])"
}

/**
 * Typed convenience projections for the production cooking slice, not the complete 188-schema
 * DTO model or an authoritative validator. Each owns the full immutable WireDocument, including
 * unprojected fields. Projected fields must have the expected JSON shape; constraints and enum
 * membership are not validated. Unknown states remain exact strings, never approved content.
 * Optional fields preserve Missing, Null and Value even when a schema disallows explicit null.
 */
class RecipeVersionWire private constructor(val document: WireDocument) {
    val id = RecipeVersionId(document.requiredString("id"))
    val recipeId = RecipeId(document.requiredString("recipeId"))
    val version = document.requiredNumber("version")
    val createdAt = document.requiredString("createdAt")
    val updatedAt = document.requiredString("updatedAt")
    val title = document.requiredString("title")
    val summary = document.stringField("summary")
    val reviewStatus = document.requiredString("reviewStatus")
    private val ingredientValues = document.requiredArray("ingredients").map(IngredientAmountWire::from)
    val ingredients: List<IngredientAmountWire> get() = ingredientValues.toList()
    private val stepValues = document.requiredArray("steps").map(RecipeStepWire::from)
    val steps: List<RecipeStepWire> get() = stepValues.toList()
    val servings = document.requiredNumber("servings")
    val activeMinutes = document.requiredNumber("activeMinutes")
    val totalMinutes = document.requiredNumber("totalMinutes")
    val utensilCount = document.requiredNumber("utensilCount")
    private val equipmentValues = document.requiredStrings("equipmentIds")
    val equipmentIds: List<String> get() = equipmentValues.toList()
    private val modeValues = document.requiredStrings("modes")
    val modes: List<String> get() = modeValues.toList()
    private val tasteTagValues = document.requiredStrings("tasteTags")
    val tasteTags: List<String> get() = tasteTagValues.toList()
    val scalingMin = document.numberField("scalingMin")
    val scalingMax = document.numberField("scalingMax")
    val waitingMinutes = document.numberField("waitingMinutes")
    val cleanupMinutes = document.numberField("cleanupMinutes")
    val reviewedAt = document.stringField("reviewedAt")
    val reviewerLabel = document.stringField("reviewerLabel")
    val recallReasonCode = document.stringField("recallReasonCode")
    val contentLicense = document.stringField("contentLicense")
    private val preparationTagValues = document.projectField("preparationTags") { it.requiredStringElements() }
    val preparationTags: WireField<List<String>> get() = preparationTagValues.detachedList()
    val estimateBasis = document.stringField("estimateBasis")
    val estimateNote = document.stringField("estimateNote")

    override fun toString(): String = "RecipeVersionWire([redacted])"
    companion object { fun from(document: WireDocument): RecipeVersionWire = RecipeVersionWire(document) }
}

class IngredientAmountWire private constructor(val document: WireDocument) {
    val ingredientId = IngredientId(document.requiredString("ingredientId"))
    val quantity = document.requiredNumber("quantity")
    val unit = document.requiredString("unit")
    val optional = document.requiredBoolean("optional")
    val preparation = document.stringField("preparation")

    override fun toString(): String = "IngredientAmountWire([redacted])"
    companion object { fun from(document: WireDocument): IngredientAmountWire = IngredientAmountWire(document) }
}

class RecipeStepWire private constructor(val document: WireDocument) {
    val stepId = StepId(document.requiredString("stepId"))
    val position = document.requiredNumber("position")
    val instruction = document.requiredString("instruction")
    private val ingredientValues = document.requiredStrings("ingredientIds").map(::IngredientId)
    val ingredientIds: List<IngredientId> get() = ingredientValues.toList()
    private val equipmentValues = document.requiredStrings("requiredEquipmentIds")
    val requiredEquipmentIds: List<String> get() = equipmentValues.toList()
    val mandatorySafetyStep = document.requiredBoolean("mandatorySafetyStep")
    val durationSeconds = document.numberField("durationSeconds")

    override fun toString(): String = "RecipeStepWire([redacted])"
    companion object { fun from(document: WireDocument): RecipeStepWire = RecipeStepWire(document) }
}

class PlanWire private constructor(val document: WireDocument) {
    val id = PlanId(document.requiredString("id"))
    val version = document.requiredNumber("version")
    val createdAt = document.requiredString("createdAt")
    val updatedAt = document.requiredString("updatedAt")
    val parentPlanId = document.projectField("parentPlanId") { PlanId(it.requiredStringValue()) }
    val recipeVersionId = document.projectField("recipeVersionId") { RecipeVersionId(it.requiredStringValue()) }
    val sourcePostId = document.projectField("sourcePostId") { SourcePostId(it.requiredStringValue()) }
    // A confirmation/no-match response may not yet have selected a concrete mode.
    // Canonical validation still requires it for ready/recalled and rejects explicit null.
    val mode = document.stringField("mode")
    val status = document.requiredString("status")
    val constraints = document.requiredObject("constraints")
    val recipeSnapshot = document.projectField("recipeSnapshot", RecipeVersionWire::from)
    private val missingIngredientValues = document.requiredArray("missingIngredients").map(IngredientAmountWire::from)
    val missingIngredients: List<IngredientAmountWire> get() = missingIngredientValues.toList()
    private val changeValues = document.requiredArray("changes")
    val changes: List<WireDocument> get() = changeValues.toList()
    private val reasonValues = document.requiredArray("reasons")
    val reasons: List<WireDocument> get() = reasonValues.toList()
    private val interpretationCandidateValues = document.projectField("interpretationCandidates") { it.requiredElements() }
    val interpretationCandidates: WireField<List<WireDocument>> get() = interpretationCandidateValues.detachedList()
    val catalogRevision = document.requiredString("catalogRevision")
    val nextAlternativeCursor = document.stringField("nextAlternativeCursor")

    override fun toString(): String = "PlanWire([redacted])"
    companion object { fun from(document: WireDocument): PlanWire = PlanWire(document) }
}

class CookSessionWire private constructor(val document: WireDocument) {
    val id = CookSessionId(document.requiredString("id"))
    val planId = PlanId(document.requiredString("planId"))
    val version = document.requiredNumber("version")
    val createdAt = document.requiredString("createdAt")
    val updatedAt = document.requiredString("updatedAt")
    val status = document.requiredString("status")
    val currentStepId = StepId(document.requiredString("currentStepId"))
    private val completedStepValues = document.requiredStrings("completedStepIds").map(::StepId)
    val completedStepIds: List<StepId> get() = completedStepValues.toList()
    val deviceSequence = document.requiredNumber("deviceSequence")
    private val timerValues = document.requiredArray("timers").map(TimerStateWire::from)
    val timers: List<TimerStateWire> get() = timerValues.toList()
    val completedAt = document.stringField("completedAt")
    private val personalNoteValues = document.projectField("personalNotes") { it.requiredElements() }
    val personalNotes: WireField<List<WireDocument>> get() = personalNoteValues.detachedList()

    override fun toString(): String = "CookSessionWire([redacted])"
    companion object { fun from(document: WireDocument): CookSessionWire = CookSessionWire(document) }
}

class TimerStateWire private constructor(val document: WireDocument) {
    val timerId = TimerId(document.requiredString("timerId"))
    val stepId = StepId(document.requiredString("stepId"))
    val status = document.requiredString("status")
    val durationSeconds = document.requiredNumber("durationSeconds")
    val endAt = document.stringField("endAt")
    val pausedRemainingSeconds = document.numberField("pausedRemainingSeconds")

    override fun toString(): String = "TimerStateWire([redacted])"
    companion object { fun from(document: WireDocument): TimerStateWire = TimerStateWire(document) }
}

/** The saved snapshot and source/grant/recall fields are retained; this grants no new rights. */
class SavedRecipeWire private constructor(val document: WireDocument) {
    val id = SavedRecipeId(document.requiredString("id"))
    val version = document.requiredNumber("version")
    val createdAt = document.requiredString("createdAt")
    val updatedAt = document.requiredString("updatedAt")
    val title = document.requiredString("title")
    val snapshot = RecipeVersionWire.from(document.requiredObject("snapshot"))
    val sourceType = document.requiredString("sourceType")
    val sourcePostId = document.projectField("sourcePostId") { SourcePostId(it.requiredStringValue()) }
    val grantId = document.projectField("grantId") { GrantId(it.requiredStringValue()) }
    val creatorLabel = document.stringField("creatorLabel")
    val recalled = document.requiredBoolean("recalled")
    val contentLicense = document.stringField("contentLicense")

    override fun toString(): String = "SavedRecipeWire([redacted])"
    companion object { fun from(document: WireDocument): SavedRecipeWire = SavedRecipeWire(document) }
}

private fun projectionFailure(): Nothing = throw IllegalArgumentException("Unsupported wire projection shape")
private fun WireDocument.requiredField(name: String): WireDocument = when (val field = field(name)) {
    is WireField.Value -> field.value
    else -> projectionFailure()
}
private fun WireDocument.requiredStringValue(): String = stringOrNull() ?: projectionFailure()
private fun WireDocument.requiredString(name: String): String = requiredField(name).requiredStringValue()
private fun WireDocument.requiredNumber(name: String): ExactWireNumber = ExactWireNumber.from(requiredField(name))
private fun WireDocument.requiredBoolean(name: String): Boolean = requiredField(name).booleanOrNull() ?: projectionFailure()
private fun WireDocument.requiredElements(): List<WireDocument> = elementsOrNull() ?: projectionFailure()
private fun WireDocument.requiredArray(name: String): List<WireDocument> = requiredField(name).requiredElements()
private fun WireDocument.requiredStringElements(): List<String> = requiredElements().map { it.requiredStringValue() }
private fun WireDocument.requiredStrings(name: String): List<String> = requiredField(name).requiredStringElements()
private fun WireDocument.requiredObject(name: String): WireDocument = requiredField(name).also {
    if (it.kind != WireKind.OBJECT) projectionFailure()
}
private fun WireDocument.stringField(name: String): WireField<String> = projectField(name) { it.requiredStringValue() }
private fun WireDocument.numberField(name: String): WireField<ExactWireNumber> = projectField(name, ExactWireNumber::from)
private fun <T> WireDocument.projectField(name: String, projection: (WireDocument) -> T): WireField<T> =
    when (val field = field(name)) {
        WireField.Missing -> WireField.Missing
        WireField.Null -> WireField.Null
        is WireField.Value -> WireField.Value(projection(field.value))
    }
private fun <T> WireField<List<T>>.detachedList(): WireField<List<T>> = when (this) {
    WireField.Missing -> WireField.Missing
    WireField.Null -> WireField.Null
    is WireField.Value -> WireField.Value(value.toList())
}

private fun String.unicodeCodePointCount(): Int {
    var index = 0
    var count = 0
    while (index < length) {
        val first = this[index++]
        if (first in '\uD800'..'\uDBFF' && index < length && this[index] in '\uDC00'..'\uDFFF') index++
        count++
    }
    return count
}
