package com.feedme.server.staff

import com.feedme.server.catalog.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.PgTransactions
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.*

/** Unpublished editorial findings only. Real ingredient/catalog reads are consumed on
 * the caller's owned connection. No publication/copy-right authority, nutrition
 * credentials, public catalog write or external source verification is synthesized. */
internal class SupabaseStaffRecipeReviewWorkflow(
    private val environment: String,
    private val transactions: PgTransactions,
    private val ingredients: IngredientCatalogStore?,
    private val catalog: RecipeCatalogJournal?,
) {
    init { require(ingredients == null || ingredients.environment == environment); require(catalog == null || catalog.environment == environment) }
    internal fun isBoundTo(env: String, tx: PgTransactions) = environment == env && transactions === tx

    internal fun input(operation: String, body: JsonObject): JsonObject {
        val schema = when (operation) { SUBMIT -> "RecipeSubmissionWrite"; REVIEW -> "ReviewWrite"; else -> invalid() }
        if (body.toString().encodeToByteArray().size > 65536 || canonical(body).encodeToByteArray().size > 65536 ||
            validator.validateSchema(schema, body.toString().encodeToByteArray()) != BodyValidationResult.Valid) invalid()
        if (operation == SUBMIT) {
            if (strings(body, "requiredReviewScopes").toSet() != SCOPES) invalid()
            val provenance = body.getValue("provenance").jsonObject
            reference(provenance.text("sourceReference"), 2048); reference(provenance.text("rightsEvidenceReference"), 256)
            provenance["attribution"]?.let { prose(it.jsonPrimitive.content, 1000, false) }
        } else {
            if (body.text("decision") !in setOf("approve", "changesRequested", "reject")) invalid()
            if (body.containsKey("recallReasonCode") || body.containsKey("professionalReview") && body.text("decision") != "approve") invalid()
            prose(body.text("notes"), 4000, true)
            val checks = strings(body, "checks")
            if (checks.size > 6 || checks.distinct().size != checks.size || checks.any { it !in SCOPES }) invalid()
            if (body.text("decision") == "approve" && (checks.toSet() != SCOPES || body["evidence"] !is JsonObject)) invalid()
            body["evidence"]?.jsonObject?.let { evidence ->
                reference(evidence.text("rightsEvidenceReference"), 256); reference(evidence.text("safetyEvidenceReference"), 256)
                reference(evidence.text("taxonomyRevision"), 128)
                val planning = evidence.getValue("planningReview").jsonObject
                reference(planning.text("reviewReference"), 256); reference(planning.text("policyVersion"), 128)
                for (field in listOf("compatibleBaseTypes", "scalableUnits")) strings(planning, field).forEach { reference(it, 128) }
            }
        }
        return body
    }

    internal fun submitted(c: Connection, source: JsonObject, author: UUID, input: JsonObject, at: Instant): JsonObject {
        if (source.text("reviewStatus") != "draft") conflict()
        ready(source)
        val id = UUID.randomUUID(); val sourceVersion = source.version(); val version = sourceVersion + 1
        val submission = submission(source, input, id, at)
        val text = canonical(submission)
        if (text.encodeToByteArray().size > 16384) invalid()
        c.prepareStatement("INSERT INTO staff.recipe_submissions(environment,draft_id,submission_id,source_version,submitted_version," +
            "author_id,material_sha256,submission_text,submission_sha256,submitted_at) VALUES(?,?,?,?,?,?,?,?,?,?)").use { s ->
            s.setString(1, environment); s.setObject(2, source.id()); s.setObject(3, id); s.setLong(4, sourceVersion); s.setLong(5, version)
            s.setObject(6, author); s.setString(7, submission.text("materialSha256")); s.setString(8, text); s.setString(9, sha(text))
            s.setObject(10, date(at)); if (s.executeUpdate() != 1) unavailable()
        }
        return submittedSnapshot(source, submission, at)
    }

    internal fun reviewed(c: Connection, source: JsonObject, author: UUID, reviewer: UUID, input: JsonObject, at: Instant): JsonObject {
        if (source.text("reviewStatus") != "inReview") conflict()
        if (author == reviewer) denied()
        if (input.text("decision") == "approve") approve(c, source, input)
        val review = review(source, input, UUID.randomUUID(), reviewer, at)
        val text = canonical(review)
        if (text.encodeToByteArray().size > 65536) invalid()
        c.prepareStatement("INSERT INTO staff.recipe_reviews(environment,draft_id,review_id,submission_id,submitted_version,result_version," +
            "author_id,reviewer_id,decision,material_sha256,review_text,review_sha256,reviewed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
            s.setString(1, environment); s.setObject(2, source.id()); s.setObject(3, review.id())
            s.setObject(4, UUID.fromString(review.text("submissionId"))); s.setLong(5, source.version()); s.setLong(6, review.version())
            s.setObject(7, author); s.setObject(8, reviewer); s.setString(9, input.text("decision"))
            s.setString(10, source.getValue("submission").jsonObject.text("materialSha256")); s.setString(11, text); s.setString(12, sha(text))
            s.setObject(13, date(at)); if (s.executeUpdate() != 1) unavailable()
        }
        return reviewedSnapshot(source, review, at)
    }

    /** Reconstruct the exact immutable result, never a new UUID/time or a projection
     * of the current head. Source is its verified immediate prior audit revision. */
    internal fun retained(c: Connection, operation: String, source: JsonObject, author: UUID, editor: UUID,
        input: JsonObject, at: Instant): JsonObject {
        if (operation == SUBMIT) {
            if (author != editor || source.text("reviewStatus") != "draft") unavailable()
            val actual = c.prepareStatement("SELECT submission_id,source_version,author_id,material_sha256,submission_text,submission_sha256,submitted_at " +
                "FROM ONLY staff.recipe_submissions WHERE environment=? AND draft_id=? AND submitted_version=? FOR SHARE").use { s ->
                s.setString(1, environment); s.setObject(2, source.id()); s.setLong(3, source.version() + 1)
                s.executeQuery().use { r ->
                    if (!r.next()) unavailable()
                    val id = r.getObject(1, UUID::class.java); val text = r.getString(5)
                    val expected = submission(source, input, id, at)
                    if (r.getLong(2) != source.version() || r.getObject(3, UUID::class.java) != author ||
                        r.getString(4) != expected.text("materialSha256") || r.getString(6) != sha(text) ||
                        r.getObject(7, OffsetDateTime::class.java).toInstant() != at || text != canonical(expected) || r.next()) unavailable()
                    expected
                }
            }
            ready(source)
            return submittedSnapshot(source, actual, at)
        }
        if (operation != REVIEW || source.text("reviewStatus") != "inReview" || author == editor) unavailable()
        val actual = c.prepareStatement("SELECT review_id,submission_id,submitted_version,author_id,reviewer_id,decision," +
            "material_sha256,review_text,review_sha256,reviewed_at FROM ONLY staff.recipe_reviews " +
            "WHERE environment=? AND draft_id=? AND result_version=? FOR SHARE").use { s ->
            s.setString(1, environment); s.setObject(2, source.id()); s.setLong(3, source.version() + 1)
            s.executeQuery().use { r ->
                if (!r.next()) unavailable()
                val id = r.getObject(1, UUID::class.java); val text = r.getString(8)
                val expected = review(source, input, id, editor, at)
                if (r.getObject(2, UUID::class.java).toString() != expected.text("submissionId") || r.getLong(3) != source.version() ||
                    r.getObject(4, UUID::class.java) != author || r.getObject(5, UUID::class.java) != editor || r.getString(6) != input.text("decision") ||
                    r.getString(7) != source.getValue("submission").jsonObject.text("materialSha256") || r.getString(9) != sha(text) ||
                    r.getObject(10, OffsetDateTime::class.java).toInstant() != at || text != canonical(expected) || r.next()) unavailable()
                expected
            }
        }
        return reviewedSnapshot(source, actual, at)
    }

    internal fun revalidate(c: Connection, operation: String, snapshot: JsonObject, input: JsonObject) {
        if (operation == REVIEW && input.text("decision") == "approve") approve(c, snapshot, input)
    }

    private fun submission(source: JsonObject, input: JsonObject, id: UUID, at: Instant) = buildJsonObject {
        put("id", id.toString()); put("sourceVersion", source.version()); put("submittedVersion", source.version() + 1)
        put("submittedAt", at.toString()); put("materialSha256", material(source, input))
        put("provenance", input.getValue("provenance")); put("requiredReviewScopes", input.getValue("requiredReviewScopes"))
        put("declaredExclusionIngredientIds", input["declaredExclusionIngredientIds"] ?: JsonArray(emptyList()))
    }.also { SupabaseStaffRecipeDraftCodec.response("RecipeSubmission", it) }
    private fun submittedSnapshot(source: JsonObject, submission: JsonObject, at: Instant) = JsonObject(source + mapOf(
        "version" to JsonPrimitive(source.version() + 1), "updatedAt" to JsonPrimitive(at.toString()),
        "reviewStatus" to JsonPrimitive("inReview"), "submission" to submission))
    private fun review(source: JsonObject, input: JsonObject, id: UUID, reviewer: UUID, at: Instant) = buildJsonObject {
        put("id", id.toString()); put("version", source.version() + 1); put("createdAt", at.toString()); put("updatedAt", at.toString())
        put("recipeVersionId", source.id().toString()); put("reviewerId", reviewer.toString())
        put("submissionId", source.getValue("submission").jsonObject.getValue("id"))
        put("submittedVersion", source.getValue("submission").jsonObject.getValue("submittedVersion"))
        put("decision", input.getValue("decision")); put("notes", input.getValue("notes")); put("checks", input.getValue("checks"))
        input["evidence"]?.let { put("evidence", it) }
        input["professionalReview"]?.let { put("professionalReview", it) }
    }.also { SupabaseStaffRecipeDraftCodec.response("Review", it) }
    private fun reviewedSnapshot(source: JsonObject, review: JsonObject, at: Instant) = JsonObject(source + mapOf(
        "version" to JsonPrimitive(source.version() + 1), "updatedAt" to JsonPrimitive(at.toString()),
        "reviewStatus" to JsonPrimitive(when (review.text("decision")) { "approve" -> "approved"; "changesRequested" -> "changesRequested"; else -> "rejected" }),
        "latestReview" to review))

    private fun material(source: JsonObject, input: JsonObject) = sha(canonical(buildJsonObject {
        put("recipe", JsonObject(source.filterKeys { it in MATERIAL_FIELDS }))
        put("submission", JsonObject(input + ("declaredExclusionIngredientIds" to (input["declaredExclusionIngredientIds"] ?: JsonArray(emptyList())))))
    }))
    private fun ready(recipe: JsonObject) {
        val amounts = recipe.getValue("ingredients").jsonArray.map { it.jsonObject }
        val ids = amounts.map { it.text("ingredientId") }; val equipment = strings(recipe, "equipmentIds").toSet()
        val steps = recipe.getValue("steps").jsonArray.map { it.jsonObject }
        if (amounts.isEmpty() || ids.distinct().size != ids.size || amounts.any { it.getValue("quantity").jsonPrimitive.content.toBigDecimal().signum() <= 0 } ||
            steps.isEmpty() || strings(recipe, "modes").isEmpty()) invalid()
        steps.forEachIndexed { index, step ->
            if (step.getValue("position").jsonPrimitive.int != index + 1 || !ids.containsAll(strings(step, "ingredientIds")) ||
                !equipment.containsAll(strings(step, "requiredEquipmentIds"))) invalid()
        }
    }

    private fun approve(c: Connection, recipe: JsonObject, input: JsonObject) {
        ready(recipe)
        val evidence = input.getValue("evidence").jsonObject
        val planning = evidence.getValue("planningReview").jsonObject
        val submission = recipe.getValue("submission").jsonObject
        if (strings(input, "checks").toSet() != strings(submission, "requiredReviewScopes").toSet() ||
            recipe["estimateBasis"]?.jsonPrimitive?.content !in setOf("reviewerEstimate", "pilotObserved") || recipe["preparationTags"] == null) invalid()
        val heating = planning.getValue("heatingRequired").jsonPrimitive.boolean
        if (("noHeat" in strings(recipe, "preparationTags")) == heating ||
            planning.text("minimumEnergy") == "ASSEMBLE" && (heating || planning.getValue("substantialPreparation").jsonPrimitive.boolean)) invalid()
        val raw = evidence.getValue("ingredientComposition").jsonArray.map { it.jsonObject }
        val composition = raw.associate { item ->
            val components = item.getValue("componentIds").takeUnless { it == JsonNull }?.jsonArray?.map { UUID.fromString(it.jsonPrimitive.content) } ?: invalid()
            UUID.fromString(item.text("ingredientId")) to components
        }
        if (composition.size != raw.size || composition.values.any { components -> components.any { it !in composition } }) invalid()
        // Reject cycles rather than treating unresolved recursion as atomic composition.
        for (id in composition.keys) {
            val pending = java.util.ArrayDeque<Pair<UUID, Set<UUID>>>()
            pending.add(id to emptySet()); val visited = mutableSetOf<UUID>()
            while (pending.isNotEmpty()) {
                val (current, ancestors) = pending.removeLast()
                if (current in ancestors) invalid()
                if (visited.add(current)) composition.getValue(current).forEach { pending.add(it to (ancestors + current)) }
            }
        }
        val excluded = strings(submission, "declaredExclusionIngredientIds").map(UUID::fromString).toSet()
        val recipeIds = recipe.getValue("ingredients").jsonArray.map { UUID.fromString(it.jsonObject.text("ingredientId")) }
        if (recipeIds.any { it !in composition }) invalid()
        val reachable = mutableSetOf<UUID>(); val pending = java.util.ArrayDeque<UUID>(recipeIds)
        while (pending.isNotEmpty()) { val id = pending.removeLast(); if (reachable.add(id)) pending.addAll(composition.getValue(id)) }
        if (reachable.any { it in excluded }) invalid()
        val actualIngredients = ingredients ?: notConfigured()
        actualIngredients.checkCompatibility(c)
        val current = actualIngredients.current(c).original.items.associateBy { it.id }
        if (composition.keys.any { id -> current[id]?.let { !it.reviewed || !it.published || !it.freeAccess } != false }) invalid()
        for (amount in recipe.getValue("ingredients").jsonArray) {
            val value = amount.jsonObject; val actual = current.getValue(UUID.fromString(value.text("ingredientId"))).ingredient
            if (value.text("unit") !in strings(actual, "supportedUnits")) invalid()
        }
        // A novel taxonomy may be independently proposed from real reviewed ingredient
        // identities. Reusing a known name never silently replaces its published meaning.
        catalog?.let { journal ->
            journal.checkCompatibility(c)
            val hasHead = c.prepareStatement("SELECT revision FROM catalog.recipe_heads WHERE environment=? FOR SHARE").use { s ->
                s.setString(1, environment); s.executeQuery().use { r -> r.next().also { if (it && r.getLong(1) < 1) notConfigured() } }
            }
            if (hasHead) {
                val view = journal.openView(c)
                if (view.taxonomyRevision == evidence.text("taxonomyRevision")) {
                    val known = view.ingredients.associate { it.ingredientId to it.componentIds?.toSet() }
                    if (known != composition.mapValues { it.value.toSet() }) invalid()
                } else {
                    c.prepareStatement("SELECT 1 FROM catalog.recipe_releases WHERE environment=? AND taxonomy_revision=? LIMIT 1").use { s ->
                        s.setString(1, environment); s.setString(2, evidence.text("taxonomyRevision")); s.executeQuery().use { r -> if (r.next()) conflict() }
                    }
                }
                view.checkCurrent()
            }
        }
        actualIngredients.current(c) // Same held head/projections, rechecked after catalog waits.
    }

    private fun strings(body: JsonObject, name: String) = body.getValue(name).jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.id() = UUID.fromString(text("id"))
    private fun JsonObject.version() = getValue("version").jsonPrimitive.long
    private fun canonical(value: JsonElement) = SupabaseStaffRecipeDraftCodec.canonical(value)
    private fun sha(text: String) = SupabaseStaffRecipeDraftCodec.sha(text)
    private fun date(at: Instant) = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
    private fun reference(value: String, maximum: Int) { if (value.isBlank() || value.length > maximum || value.any(Char::isISOControl)) invalid() }
    private fun prose(value: String, maximum: Int, required: Boolean) { if (required && value.isBlank() || value.length > maximum || value.any { it.isISOControl() && it !in "\n\r\t" }) invalid() }
    private fun invalid(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.INPUT_INVALID)
    private fun unavailable(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.STORAGE_UNAVAILABLE)
    private fun conflict(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.STATE_CONFLICT)
    private fun notConfigured(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.NOT_CONFIGURED)
    private fun denied(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.INDEPENDENT_REVIEW_REQUIRED)
    override fun toString() = "SupabaseStaffRecipeReviewWorkflow(<redacted>)"
    companion object {
        const val SUBMIT = "adminSubmitRecipe"
        const val REVIEW = "adminReviewRecipe"
        private val validator by lazy { ContractBodyValidator.bundled() }
        internal val SCOPES = setOf("recipeSafety", "ingredientIdentity", "allergenComposition", "instructions", "effortEstimates", "rights")
        private val MATERIAL_FIELDS = setOf("id", "recipeId", "title", "summary", "ingredients", "steps", "servings", "scalingMin", "scalingMax",
            "activeMinutes", "totalMinutes", "utensilCount", "equipmentIds", "modes", "tasteTags", "waitingMinutes", "cleanupMinutes", "preparationTags", "estimateBasis", "estimateNote")
    }
}
