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

/** Actual catalog publication/withdrawal, inside the staff command's one transaction.
 * No workforce identity is fabricated. The actual admitted actor and root-owned
 * current professional-review proof authorize this exact immutable journal original. */
internal class SupabaseStaffRecipePublicationWorkflow(
    private val environment: String,
    private val transactions: PgTransactions,
    private val auth: SupabaseStaffAdmissionStore,
    ingredients: IngredientCatalogStore?,
    private val catalog: RecipeCatalogJournal,
) {
    private val reviews = SupabaseStaffRecipeReviewWorkflow(environment, transactions, ingredients, catalog)
    init { require(auth.isBoundTo(environment, transactions) && catalog.environment == environment) }
    internal fun isBoundTo(env: String, tx: PgTransactions) = environment == env && transactions === tx

    internal fun input(operation: String, body: JsonObject): JsonObject {
        val schema = if (operation == PUBLISH) "Empty" else if (operation == RECALL) "ReviewWrite" else invalid()
        if (body.toString().encodeToByteArray().size > 65536 || canonical(body).encodeToByteArray().size > 65536 ||
            validator.validateSchema(schema, body.toString().encodeToByteArray()) != BodyValidationResult.Valid) invalid()
        if (operation == RECALL) {
            if (body.getValue("decision") != JsonPrimitive("recall") || body.containsKey("evidence") || body.containsKey("professionalReview")) invalid()
            val notes = body.text("notes"); val reason = body["recallReasonCode"]?.jsonPrimitive?.content ?: invalid()
            if (notes.isBlank() || notes.length > 4000 || notes.any { it.isISOControl() && it !in "\n\r\t" } ||
                reason.isBlank() || reason.length > 128 || reason.any(Char::isISOControl)) invalid()
            val checks = body.getValue("checks").jsonArray.map { it.jsonPrimitive.content }
            if (checks.size > 6 || checks.distinct().size != checks.size || checks.any { it !in SupabaseStaffRecipeReviewWorkflow.SCOPES }) invalid()
        }
        return body
    }

    internal fun mutate(c: Connection, actor: SupabaseStaffCatalogActor, operation: String, source: JsonObject,
        input: JsonObject, key: UUID, at: Instant): JsonObject {
        val recall = operation == RECALL
        if ((!recall && source.text("reviewStatus") != "approved") ||
            (recall && source.text("reviewStatus") !in setOf("published", "retired"))) conflict()
        val proof = proof(c, actor, source, recall)
        if (!recall) reviews.revalidate(c, SupabaseStaffRecipeReviewWorkflow.REVIEW, source, reviewInput(source))
        // The only empty-head creation is inside an admitted publication command;
        // any later error rolls it back together with the staff head and receipt.
        if (!recall) c.prepareStatement("INSERT INTO catalog.recipe_heads(environment,revision,release_id) VALUES(?,0,NULL) ON CONFLICT DO NOTHING").use { s ->
            s.setString(1, environment); s.executeUpdate()
        }
        val revision = c.prepareStatement("SELECT revision FROM catalog.recipe_heads WHERE environment=? FOR UPDATE").use { s ->
            s.setString(1, environment); s.executeQuery().use { r -> if (!r.next()) notConfigured(); r.getLong(1).also { if (it < 0 || r.next()) unavailable() } }
        }
        val view = if (revision > 0) catalog.openView(c) else null
        val prior = view?.lookupCurrent(source.id())
        val entry: RecipeCatalogEntry
        val publisher: UUID
        val reviewer: UUID
        val taxonomy: String
        val compositions: List<RecipeIngredientComposition>
        val snapshot: JsonObject
        if (recall) {
            val actual = prior?.entry ?: conflict()
            if (canonical(actual.recipe) != canonical(member(source)) || actual.status !in setOf("published", "retired")) conflict()
            val original = retained(c, source.id(), source.version())
            publisher = original.change.publisherId; reviewer = original.change.reviewerId
            val withdrawal = RecipeRecall(UUID.randomUUID(), input.text("recallReasonCode"), at)
            snapshot = JsonObject(source + mapOf("version" to JsonPrimitive(source.version() + 1), "updatedAt" to JsonPrimitive(at.toString()),
                "reviewStatus" to JsonPrimitive("recalled"), "recallReasonCode" to JsonPrimitive(withdrawal.reasonCode)))
            entry = RecipeCatalogEntry(member(snapshot), actual.review, actual.rightsReference, withdrawal)
            taxonomy = requireNotNull(view).taxonomyRevision; compositions = view.ingredients
        } else {
            if (prior != null) conflict()
            val review = source.getValue("latestReview").jsonObject; val evidence = review.getValue("evidence").jsonObject
            publisher = actor.actorId; reviewer = UUID.fromString(review.text("reviewerId"))
            if (publisher == reviewer) denied()
            snapshot = JsonObject(source + mapOf("version" to JsonPrimitive(source.version() + 1), "updatedAt" to JsonPrimitive(at.toString()),
                "reviewStatus" to JsonPrimitive("published"), "reviewedAt" to review.getValue("createdAt"),
                "contentLicense" to source.getValue("submission").jsonObject.getValue("provenance").jsonObject.getValue("requestedLicense")))
            entry = RecipeCatalogEntry(member(snapshot), evidence.getValue("planningReview").jsonObject, evidence.text("rightsEvidenceReference"))
            taxonomy = evidence.text("taxonomyRevision")
            compositions = evidence.getValue("ingredientComposition").jsonArray.map { item ->
                val value = item.jsonObject
                RecipeIngredientComposition(UUID.fromString(value.text("ingredientId")), value.getValue("componentIds").takeUnless { it == JsonNull }
                    ?.jsonArray?.map { UUID.fromString(it.jsonPrimitive.content) })
            }
        }
        val change = RecipeCatalogChangeset(UUID.randomUUID(), revision, publisher, reviewer, "staff-recipe-command:$key",
            taxonomy, compositions, listOf(entry), recallActorId = actor.actorId.takeIf { recall })
        val receipt = writer(c, actor, proof, change).publishInTransaction(c, change)
        if (receipt.replayed || receipt.releaseId != change.releaseId || receipt.requestSha256 != change.requestSha256 || receipt.revision != revision + 1) unavailable()
        val publicText = canonical(entry.recipe)
        c.prepareStatement("INSERT INTO staff.recipe_publication_commands(environment,draft_id,source_version,result_version,actor_id,publisher_id,reviewer_id," +
            "operation_id,release_id,catalog_revision,catalog_request_hash,public_recipe_text,public_recipe_sha256,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
            s.setString(1, environment); s.setObject(2, source.id()); s.setLong(3, source.version()); s.setLong(4, snapshot.version())
            s.setObject(5, actor.actorId); s.setObject(6, publisher); s.setObject(7, reviewer); s.setString(8, operation)
            s.setObject(9, change.releaseId); s.setLong(10, receipt.revision); s.setString(11, change.requestSha256)
            s.setString(12, publicText); s.setString(13, sha(publicText)); s.setObject(14, date(at)); if (s.executeUpdate() != 1) unavailable()
        }
        proof.revalidate(c)
        return snapshot
    }

    internal fun decode(c: Connection, operation: String, source: JsonObject, editor: UUID, input: JsonObject, key: UUID, at: Instant): JsonObject {
        val retained = retained(c, source.id(), source.version() + 1)
        val change = retained.change; val entry = change.entries.singleOrNull() ?: unavailable()
        val review = source.getValue("latestReview").jsonObject
        if (retained.actor != editor || retained.operation != operation || retained.at != at ||
            change.reviewerId.toString() != review.text("reviewerId") || change.publicationReference != "staff-recipe-command:$key") unavailable()
        val result = if (operation == PUBLISH) {
            if (source.text("reviewStatus") != "approved" || change.publisherId != editor || change.recallActorId != null || entry.recall != null) unavailable()
            val evidence = review.getValue("evidence").jsonObject
            if (entry.review != evidence.getValue("planningReview") || entry.rightsReference != evidence.text("rightsEvidenceReference") ||
                change.taxonomyRevision != evidence.text("taxonomyRevision")) unavailable()
            val recorded = change.ingredients.associate { it.ingredientId.toString() to it.componentIds?.map(UUID::toString)?.toSet() }
            val expected = evidence.getValue("ingredientComposition").jsonArray.associate { item ->
                val value = item.jsonObject; value.text("ingredientId") to value.getValue("componentIds").takeUnless { it == JsonNull }?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            }
            if (recorded != expected) unavailable()
            JsonObject(source + mapOf("version" to JsonPrimitive(source.version() + 1), "updatedAt" to JsonPrimitive(at.toString()),
                "reviewStatus" to JsonPrimitive("published"), "reviewedAt" to review.getValue("createdAt"),
                "contentLicense" to source.getValue("submission").jsonObject.getValue("provenance").jsonObject.getValue("requestedLicense")))
        } else {
            if (operation != RECALL || source.text("reviewStatus") !in setOf("published", "retired") || change.recallActorId != editor) unavailable()
            val original = retained(c, source.id(), source.version()).change
            if (change.publisherId != original.publisherId || change.reviewerId != original.reviewerId ||
                entry.review != original.entries.single().review || entry.rightsReference != original.entries.single().rightsReference ||
                entry.recall?.reasonCode != input.text("recallReasonCode") || entry.recall?.effectiveAt != at) unavailable()
            JsonObject(source + mapOf("version" to JsonPrimitive(source.version() + 1), "updatedAt" to JsonPrimitive(at.toString()),
                "reviewStatus" to JsonPrimitive("recalled"), "recallReasonCode" to JsonPrimitive(input.text("recallReasonCode"))))
        }
        if (canonical(entry.recipe) != canonical(member(result))) unavailable()
        return result
    }

    /** Replay the retained catalog original on the same transaction. The journal's
     * replay branch never moves a later head or emits another recall event. */
    internal fun revalidate(c: Connection, actor: SupabaseStaffCatalogActor, operation: String, snapshot: JsonObject) {
        val retained = retained(c, snapshot.id(), snapshot.version())
        if (retained.actor != actor.actorId || retained.operation != operation) unavailable()
        val proof = proof(c, actor, snapshot, operation == RECALL)
        if (operation == PUBLISH) reviews.revalidate(c, SupabaseStaffRecipeReviewWorkflow.REVIEW, snapshot, reviewInput(snapshot))
        val receipt = writer(c, actor, proof, retained.change).publishInTransaction(c, retained.change)
        if (!receipt.replayed || receipt.releaseId != retained.change.releaseId || receipt.revision != retained.change.expectedRevision + 1 ||
            receipt.requestSha256 != retained.change.requestSha256) unavailable()
        proof.revalidate(c)
    }

    private fun proof(c: Connection, actor: SupabaseStaffCatalogActor, snapshot: JsonObject, recall: Boolean): SupabaseStaffRecipePublicationProof {
        val review = snapshot.getValue("latestReview").jsonObject
        if (review.text("decision") != "approve") denied()
        return auth.lockRecipePublication(c, actor, UUID.fromString(review.text("reviewerId")), Instant.parse(review.text("createdAt")),
            review["professionalReview"]?.jsonObject, recall).also { actor.requireValidUntil(it.validUntil); it.revalidate(c) }
    }
    private fun writer(c: Connection, actor: SupabaseStaffCatalogActor, proof: SupabaseStaffRecipePublicationProof,
        original: RecipeCatalogChangeset) = RecipeCatalogJournal(environment, transactions, object : RecipeChangesetPublicationAuthority {
        private fun check(actual: Connection, env: String, change: RecipeCatalogChangeset) {
            if (actual !== c || env != environment || change.exactDocument != original.exactDocument || change.requestSha256 != original.requestSha256 ||
                (change.recallActorId ?: change.publisherId) != actor.actorId) denied()
            proof.revalidate(c)
        }
        override fun lockPublication(connection: Connection, environment: String, original: RecipeCatalogChangeset) = check(connection, environment, original)
        override fun revalidatePublication(connection: Connection, environment: String, original: RecipeCatalogChangeset) = check(connection, environment, original)
    })

    private fun retained(c: Connection, id: UUID, version: Long): Retained = c.prepareStatement(
        "SELECT p.source_version,p.actor_id,p.publisher_id,p.reviewer_id,p.operation_id,p.release_id,p.catalog_revision,p.catalog_request_hash," +
            "p.public_recipe_text,p.public_recipe_sha256,p.recorded_at,r.exact_document,r.request_sha256,r.revision " +
            "FROM ONLY staff.recipe_publication_commands p JOIN ONLY catalog.recipe_releases r ON r.environment=p.environment AND r.release_id=p.release_id " +
            "WHERE p.environment=? AND p.draft_id=? AND p.result_version=? FOR SHARE OF p,r").use { s ->
        s.setString(1, environment); s.setObject(2, id); s.setLong(3, version)
        s.executeQuery().use { r ->
            if (!r.next()) unavailable()
            val change = decodeRecipeChangeset(r.getString(12)); val publicText = r.getString(9)
            val entry = change.entries.singleOrNull() ?: unavailable(); val actor = r.getObject(2, UUID::class.java)
            if (r.getLong(1) != version - 1 || change.publisherId != r.getObject(3, UUID::class.java) || change.reviewerId != r.getObject(4, UUID::class.java) ||
                change.releaseId != r.getObject(6, UUID::class.java) || change.expectedRevision + 1 != r.getLong(7) || r.getLong(7) != r.getLong(14) ||
                change.requestSha256 != r.getString(8) || r.getString(8) != r.getString(13) || entry.recipeVersionId != id || entry.version != version ||
                publicText != canonical(entry.recipe) || sha(publicText) != r.getString(10) || (change.recallActorId ?: change.publisherId) != actor) unavailable()
            val at = r.getObject(11, OffsetDateTime::class.java).toInstant(); val operation = r.getString(5)
            if (Instant.parse(entry.recipe.text("updatedAt")) != at || r.next()) unavailable()
            val view = catalog.openView(c)
            val actual = view.lookupAt(id, change.expectedRevision + 1) ?: unavailable()
            if (actual.releaseId != change.releaseId || actual.requestSha256 != change.requestSha256 || actual.entry.document() != entry.document()) unavailable()
            Retained(actor, operation, at, change)
        }
    }
    private data class Retained(val actor: UUID, val operation: String, val at: Instant, val change: RecipeCatalogChangeset)
    private fun member(snapshot: JsonObject) = JsonObject(snapshot - setOf("submission", "latestReview"))
    private fun reviewInput(snapshot: JsonObject) = JsonObject(snapshot.getValue("latestReview").jsonObject.filterKeys {
        it in setOf("decision", "notes", "checks", "evidence", "professionalReview") })
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.id() = UUID.fromString(text("id"))
    private fun JsonObject.version() = getValue("version").jsonPrimitive.long
    private fun canonical(value: JsonElement) = SupabaseStaffRecipeDraftCodec.canonical(value)
    private fun sha(text: String) = SupabaseStaffRecipeDraftCodec.sha(text)
    private fun date(at: Instant) = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
    private fun invalid(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.INPUT_INVALID)
    private fun unavailable(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.STORAGE_UNAVAILABLE)
    private fun conflict(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.STATE_CONFLICT)
    private fun notConfigured(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.NOT_CONFIGURED)
    private fun denied(): Nothing = throw SupabaseStaffFailure(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
    override fun toString() = "SupabaseStaffRecipePublicationWorkflow(<redacted>)"
    companion object {
        const val PUBLISH = "adminPublishRecipe"; const val RECALL = "adminRecallRecipe"
        private val validator by lazy { ContractBodyValidator.bundled() }
    }
}
