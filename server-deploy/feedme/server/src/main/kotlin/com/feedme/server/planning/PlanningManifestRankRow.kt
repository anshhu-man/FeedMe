package com.feedme.server.planning

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.planning.PlanningRank
import com.feedme.server.catalog.RecipeCatalogVersion
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Bounded structural rank/provenance record, not an eligible candidate or access grant.
 * The future writer must accept only its actual scanner callback; replay must independently
 * check latest-at-anchor, exact original material, policy/rank and current authorization.
 * Raw recipe/review hashes refer to the checked original projection, never JSONB text or a
 * scaled Plan body. Existing v1 ranking and persistence bytes are not changed. */
internal class PlanningManifestRankRow private constructor(
    val recipeVersionId: UUID, val recipeId: UUID, val sourceReleaseId: UUID, val sourceRevision: Long,
    val sourceRequestSha256: String, val recipeSha256: String, val reviewSha256: String,
    val tasteMatches: Int, val dislikedIngredients: Int, val confirmedIngredients: Int,
    val activeMinutes: String?, val cleanupMinutes: String?,
) : Comparable<PlanningManifestRankRow> {
    private val active = activeMinutes?.toBigDecimal()
    private val cleanup = cleanupMinutes?.toBigDecimal()
    override fun compareTo(other: PlanningManifestRankRow): Int =
        compareValues(other.tasteMatches, tasteMatches).takeUnless { it == 0 }
            ?: compareValues(dislikedIngredients, other.dislikedIngredients).takeUnless { it == 0 }
            ?: compareValues(other.confirmedIngredients, confirmedIngredients).takeUnless { it == 0 }
            ?: compareValues(active, other.active).takeUnless { it == 0 }
            ?: compareValues(cleanup == null, other.cleanup == null).takeUnless { it == 0 }
            ?: compareValues(cleanup, other.cleanup).takeUnless { it == 0 }
            ?: recipeId.toString().compareTo(other.recipeId.toString()).takeUnless { it == 0 }
            ?: recipeVersionId.toString().compareTo(other.recipeVersionId.toString())

    fun copyForStorage(): WireDocument = wire(buildJsonObject {
        put("version", 1); put("recipeVersionId", recipeVersionId.toString()); put("recipeId", recipeId.toString())
        put("sourceReleaseId", sourceReleaseId.toString()); put("sourceRevision", sourceRevision.toString())
        put("sourceRequestSha256", sourceRequestSha256); put("recipeSha256", recipeSha256); put("reviewSha256", reviewSha256)
        put("tasteMatches", tasteMatches); put("dislikedIngredients", dislikedIngredients); put("confirmedIngredients", confirmedIngredients)
        put("activeMinutes", activeMinutes?.let(::JsonPrimitive) ?: JsonNull)
        put("cleanupMinutes", cleanupMinutes?.let(::JsonPrimitive) ?: JsonNull)
    })

    /** Structural exact-source comparison only, never a lifecycle or copy-rights grant. */
    fun matchesSource(source: RecipeCatalogVersion): Boolean = recipeVersionId == source.entry.recipeVersionId &&
        recipeId == UUID.fromString(source.entry.recipe.getValue("recipeId").jsonPrimitive.content) &&
        sourceReleaseId == source.releaseId && sourceRevision == source.revision && sourceRequestSha256 == source.requestSha256 &&
        recipeSha256 == originalHash(source.entry.recipe) && reviewSha256 == originalHash(source.entry.review)

    // Fixed field order for length-framed digest format1; numeric and UUID strings canonical.
    internal fun framedFields(): List<String?> = listOf(recipeVersionId.toString(), recipeId.toString(), sourceReleaseId.toString(),
        sourceRevision.toString(), sourceRequestSha256, recipeSha256, reviewSha256, tasteMatches.toString(),
        dislikedIngredients.toString(), confirmedIngredients.toString(), activeMinutes, cleanupMinutes)
    override fun toString() = "PlanningManifestRankRow(<redacted>)"

    companion object {
        const val MAX_BYTES = 4096
        fun fromScan(source: RecipeCatalogVersion, rank: PlanningRank): PlanningManifestRankRow = rankFormat {
            require(source.entry.recipeVersionId.toString() == rank.recipeVersionId)
            require(UUID.fromString(source.entry.recipe.getValue("recipeId").jsonPrimitive.content).toString() == rank.recipeId)
            decode(wire(buildJsonObject {
                put("version", 1); put("recipeVersionId", rank.recipeVersionId); put("recipeId", rank.recipeId)
                put("sourceReleaseId", source.releaseId.toString()); put("sourceRevision", source.revision.toString())
                put("sourceRequestSha256", source.requestSha256); put("recipeSha256", originalHash(source.entry.recipe))
                put("reviewSha256", originalHash(source.entry.review)); put("tasteMatches", rank.tasteMatches)
                put("dislikedIngredients", rank.dislikedIngredients); put("confirmedIngredients", rank.confirmedIngredients)
                put("activeMinutes", rank.activeMinutes?.let(::JsonPrimitive) ?: JsonNull)
                put("cleanupMinutes", rank.cleanupMinutes?.let(::JsonPrimitive) ?: JsonNull)
            }).encodeUtf8())
        }

        fun decode(bytes: ByteArray): PlanningManifestRankRow = rankFormat {
            val root = json(WireDocument.decode(bytes, WireLimits(MAX_BYTES, 4)))
            require(root.keys == setOf("version", "recipeVersionId", "recipeId", "sourceReleaseId", "sourceRevision",
                "sourceRequestSha256", "recipeSha256", "reviewSha256", "tasteMatches", "dislikedIngredients", "confirmedIngredients",
                "activeMinutes", "cleanupMinutes"))
            require(root["version"] == JsonPrimitive(1))
            val revision = root.text("sourceRevision")
            require(revision.matches(Regex("[1-9][0-9]{0,18}")))
            PlanningManifestRankRow(rankUuid(root.text("recipeVersionId")), rankUuid(root.text("recipeId")),
                rankUuid(root.text("sourceReleaseId")), revision.toLong(), rankHash(root.text("sourceRequestSha256")),
                rankHash(root.text("recipeSha256")), rankHash(root.text("reviewSha256")), score(root, "tasteMatches", 4),
                score(root, "dislikedIngredients", 128), score(root, "confirmedIngredients", 128), minutes(root, "activeMinutes"),
                minutes(root, "cleanupMinutes"))
        }
        private fun score(root: JsonObject, field: String, max: Int): Int {
            val value = root.getValue(field).jsonPrimitive
            require(!value.isString && value.content.matches(Regex("0|[1-9][0-9]{0,2}")))
            return value.content.toInt().also { require(it in 0..max) }
        }
        private fun minutes(root: JsonObject, field: String): String? {
            val value = root.getValue(field)
            if (value == JsonNull) return null // Structural comparator supports null; scanner decides eligibility.
            require(value.jsonPrimitive.isString)
            val text = value.jsonPrimitive.content
            require(text.matches(Regex("0|[1-9][0-9]{0,14}")))
            val decimal = BigDecimal(text).stripTrailingZeros()
            // Actual RecipeVersion times are integral. Match the engine's normalized
            // coefficient/scale limits exactly; never silently round unsupported times.
            require(decimal.precision() <= 9 && decimal.scale() in -6..0)
            return text
        }
        private fun originalHash(value: JsonObject) = digest(value.toString().encodeToByteArray(throwOnInvalidSequence = true))
    }
}

/** One-shot, thread-confined streaming digest over SOURCE UUID order, not rank order.
 * Uses domain/version, header hash, per-row markers, length-framed fields (-1 for null),
 * and a terminal count. finish checks the supplied count but cannot certify actual EOF;
 * only the future owning writer may call it after its real scanner successfully completes.
 * Rejected owner-thread append/finish poisons the accumulator, preventing prefix recovery.
 * Foreign-thread calls fail before touching its state. */
internal class PlanningManifestRowsDigest(headerSha256: String) {
    private val owner = Thread.currentThread()
    private val hash = MessageDigest.getInstance("SHA-256")
    private var previous: String? = null
    private var count = 0L
    private var active = true
    init { rankFormat { frame("feedme.planning.manifest.rows.v1"); frame(rankHash(headerSha256)) } }
    fun append(row: PlanningManifestRankRow): Unit = guarded {
        val id = row.recipeVersionId.toString()
        require(previous == null || previous!! < id)
        check(count < Long.MAX_VALUE)
        frame("row"); row.framedFields().forEach(::frame)
        previous = id; count++
    }
    fun finish(expectedEligibleCount: Long): String = guarded {
        require(expectedEligibleCount >= 0 && expectedEligibleCount == count)
        frame("end"); frame(count.toString()); active = false
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private fun <T> guarded(action: () -> T): T = rankFormat {
        require(Thread.currentThread() === owner)
        try { check(active); action() } catch (failure: Throwable) { active = false; throw failure }
    }
    private fun frame(value: String?) {
        val bytes = value?.encodeToByteArray(throwOnInvalidSequence = true)
        hash.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
        if (bytes != null) hash.update(bytes)
    }
    override fun toString() = "PlanningManifestRowsDigest(<redacted>)"
}

private fun rankUuid(value: String): UUID {
    require(CanonicalFormats.accepts("uuid", value) && value == value.lowercase())
    return UUID.fromString(value)
}
private fun rankHash(value: String): String { require(value.matches(Regex("[0-9a-f]{64}"))); return value }
private fun <T> rankFormat(action: () -> T): T = try { action() }
catch (failure: CancellationException) { throw failure }
catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
catch (_: Exception) { throw PlanningManifestFormatException() }
