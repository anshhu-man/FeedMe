package com.feedme.server.reuse

import com.feedme.server.catalog.*
import com.feedme.server.db.PgTransactions
import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

/** Strict original editorial evidence, never proof merely because a caller constructed it.
 * Portion relationships are deliberately quantity-independent: the public request contains
 * no remaining amount, storage history or permission to alter today's recipe. */
internal class ReviewedReuseRelationship private constructor(val document: JsonObject) {
    val id = document.reuseId("id")
    val source = document.getValue("source").jsonObject
    val sourceKind = source.reuseText("kind")
    val target = document.getValue("target").jsonObject
    val targetId = target.reuseId("recipeVersionId")
    val targetMaterial = target.reuseText("materialSha256")
    val servings = target.getValue("servings").jsonPrimitive.content.toBigDecimal()
    val shared = document.getValue("sharedIngredientIds").jsonArray.map { reuseUuid(it.jsonPrimitive.content) }.toSet()
    val extraMinutes = document.getValue("extraPreparationMinutes").jsonPrimitive.int
    val policyRevision = document.reuseText("policyRevision")
    val exact = reuseCanonical(document)
    val sha256 = reuseSha(exact)
    override fun toString() = "ReviewedReuseRelationship(<redacted>)"
    companion object {
        fun decode(text: String): ReviewedReuseRelationship = try {
            val d = reuseJson(text, 16384)
            require(d.keys == setOf("formatVersion","id","source","target","sharedIngredientIds","extraPreparationMinutes",
                "extraPreparationDescription","policyRevision","publisherId","reviewerId","reviewId","reviewReference"))
            require(d["formatVersion"] == JsonPrimitive(1))
            listOf("id","publisherId","reviewerId","reviewId").forEach { d.reuseId(it) }
            require(d["publisherId"] != d["reviewerId"])
            for (field in listOf("policyRevision","reviewReference","extraPreparationDescription")) {
                val value = d.reuseText(field); require(value.isNotBlank() && value.length <= if (field == "extraPreparationDescription") 1000 else 256)
                require(value.none(Char::isISOControl))
            }
            val source = d.getValue("source").jsonObject
            when (source.reuseText("kind")) {
                "ingredient" -> { require(source.keys == setOf("kind","ingredientId")); source.reuseId("ingredientId") }
                "portion" -> { require(source.keys == setOf("kind","recipeVersionId","materialSha256","quantityIndependent"))
                    source.reuseId("recipeVersionId"); require(source.reuseText("materialSha256").matches(Regex("[0-9a-f]{64}")))
                    require(source["quantityIndependent"] == JsonPrimitive(true)) }
                else -> error("Unsupported reuse source")
            }
            val target = d.getValue("target").jsonObject
            require(target.keys == setOf("recipeVersionId","materialSha256","servings")); target.reuseId("recipeVersionId")
            require(target.reuseText("materialSha256").matches(Regex("[0-9a-f]{64}")))
            val servings = target.getValue("servings").jsonPrimitive
            require(!servings.isString && servings.content.toBigDecimal() > BigDecimal.ZERO && servings.content.toBigDecimal() <= BigDecimal(1000))
            val shared = d.getValue("sharedIngredientIds").jsonArray
            require(shared.size in 1..128 && shared.map { reuseUuid(it.jsonPrimitive.content) }.distinct().size == shared.size)
            val minutes = d.getValue("extraPreparationMinutes").jsonPrimitive
            require(!minutes.isString && minutes.int in 0..1440)
            if (source.reuseText("kind") == "ingredient") require(shared.any { it == source["ingredientId"] })
            ReviewedReuseRelationship(d).also { require(it.exact == text) }
        } catch (f: ReuseFailure) { throw f }
          catch (_: Exception) { reuseFail(ReuseFailureCode.INPUT_INVALID) }
    }
}

/** Mandatory independent editorial/rights authorization on the same transaction. No default
 * authority and no serving/publication route are supplied. Explicit publisher integration is
 * a separate gate; constructing an ID, status or reviewed definition cannot approve it. */
internal interface ReusePublicationAuthority {
    fun lockPublication(connection: Connection, environment: String, relationship: ReviewedReuseRelationship)
    fun revalidatePublication(connection: Connection, environment: String, relationship: ReviewedReuseRelationship)
}

internal class ReuseRelationshipPublisher(private val environment: String, private val transactions: PgTransactions,
    private val catalog: RecipeCatalogJournal, private val authority: ReusePublicationAuthority) {
    init { require(catalog.environment == environment) }
    fun publish(relationship: ReviewedReuseRelationship) = reuseSafe {
        transactions.run { c ->
            authority.lockPublication(c, environment, relationship)
            val view = catalog.openView(c)
            // Reader and publisher share catalog -> relationship-table lock order, including
            // absent rows. These conservative table locks are bounded by PgTransactions.
            c.createStatement().use { it.execute("LOCK TABLE catalog.reuse_relationships, catalog.reuse_revocations IN SHARE ROW EXCLUSIVE MODE NOWAIT") }
            validateReusePair(view, relationship)
            c.prepareStatement("INSERT INTO catalog.reuse_relationships(environment,id,definition_text,definition_sha256) VALUES(?,?,?,?) ON CONFLICT DO NOTHING").use {
                it.setString(1, environment); it.setObject(2, relationship.id); it.setString(3, relationship.exact); it.setString(4, relationship.sha256); it.executeUpdate()
            }
            fun exact() { c.prepareStatement("SELECT definition_text,definition_sha256 FROM catalog.reuse_relationships WHERE environment=? AND id=? FOR SHARE").use {
                it.setString(1, environment); it.setObject(2, relationship.id); it.executeQuery().use { r ->
                    if (!r.next() || r.getString(1) != relationship.exact || r.getString(2) != relationship.sha256 || r.next()) reuseFail()
                }
            } }
            exact(); authority.revalidatePublication(c, environment, relationship); view.checkCurrent(); validateReusePair(view, relationship); exact()
        }
    }
    override fun toString() = "ReuseRelationshipPublisher(<redacted>)"
}

/** Serving never writes or infers approvals. Locking both complete relationship tables
 * makes a bounded empty set authoritative and blocks concurrent revoke/publish until commit. */
internal class ReuseRelationshipView(private val c: Connection, private val environment: String,
    private val recipes: RecipeCatalogReadView, maximum: Int) {
    val relationships: List<ReviewedReuseRelationship>
    init {
        recipes.checkCurrent()
        c.createStatement().use { it.execute("LOCK TABLE catalog.reuse_relationships, catalog.reuse_revocations IN SHARE MODE NOWAIT") }
        relationships = c.prepareStatement("SELECT r.id,r.definition_text,r.definition_sha256 FROM catalog.reuse_relationships r " +
            "WHERE r.environment=? AND NOT EXISTS(SELECT 1 FROM catalog.reuse_revocations x WHERE x.environment=r.environment AND x.relationship_id=r.id) ORDER BY r.id LIMIT ?").use {
            it.setString(1, environment); it.setInt(2, maximum + 1)
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) { if (size == maximum) reuseFail(ReuseFailureCode.NOT_CONFIGURED)
                    val edge = ReviewedReuseRelationship.decode(rows.getString(2))
                    if (edge.id != rows.getObject(1, UUID::class.java) || edge.sha256 != rows.getString(3)) reuseFail()
                    add(edge)
                }
            } }
        }
        recipes.checkCurrent()
    }
    fun requireRetained(id: UUID, sha: String): ReviewedReuseRelationship = relationships.singleOrNull { it.id == id && it.sha256 == sha }
        ?: reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
    fun current() = recipes.checkCurrent()
}

internal fun validateReusePair(view: RecipeCatalogReadView, relationship: ReviewedReuseRelationship) {
    val target = view.lookupCurrent(relationship.targetId) ?: reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
    requireReuseTarget(target, relationship)
    if (relationship.sourceKind == "portion") {
        val source = view.lookupCurrent(relationship.source.reuseId("recipeVersionId")) ?: reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
        if (!reuseVisible(source.entry) || source.entry.materialSha256 != relationship.source.reuseText("materialSha256") ||
            !recipeIngredients(source.entry.recipe).containsAll(relationship.shared)) reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
    } else if (view.ingredients.none { it.ingredientId == relationship.source.reuseId("ingredientId") }) reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
    view.checkCurrent()
}
internal fun requireReuseTarget(value: RecipeCatalogVersion, edge: ReviewedReuseRelationship) {
    if (!reuseVisible(value.entry) || value.entry.materialSha256 != edge.targetMaterial ||
        !recipeIngredients(value.entry.recipe).containsAll(edge.shared)) reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
}
internal fun reuseVisible(entry: RecipeCatalogEntry) = entry.recipe["reviewStatus"] == JsonPrimitive("published") &&
    entry.review["freeCatalogEligible"] == JsonPrimitive(true) && entry.recipe["contentLicense"] == JsonPrimitive("catalogRedistributable")
internal fun recipeIngredients(recipe: JsonObject) = recipe.getValue("ingredients").jsonArray.map { it.jsonObject.reuseId("ingredientId") }.toSet()
