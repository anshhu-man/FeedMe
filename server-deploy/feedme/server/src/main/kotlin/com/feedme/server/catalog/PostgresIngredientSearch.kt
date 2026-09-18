package com.feedme.server.catalog

import com.feedme.server.kitchen.KitchenFailure
import com.feedme.server.kitchen.KitchenFailureCode
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import com.feedme.server.kitchen.KitchenIngredientSearch
import com.feedme.server.kitchen.checkedIngredientIds
import java.sql.Connection
import java.util.UUID
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.*

/** Persisted catalog search only, never principal authentication or a transaction owner.
 * The caller must admit the exact principal and retain its current proof through final
 * revalidation/commit. Both catalog locks and DB time use that same caller-owned connection.
 * Environment matching prevents cross-environment projection; it is not an access grant.
 * Catalog/currentness, cancellation and interruption failures remain the caller's concern.
 */
internal class PostgresIngredientSearch(private val store: IngredientCatalogStore,
    private val cursors: IngredientSearchCursor) : KitchenIngredientSearch {
    override fun search(connection: Connection, principal: VerifiedKitchenPrincipal,
        query: String?, cursor: String?, limit: Int): JsonObject {
        if (principal.environment != store.environment) throw KitchenFailure(KitchenFailureCode.UNAUTHENTICATED)
        validateIngredientSearchInput(query, limit)
        store.checkCompatibility(connection)
        val snapshot = store.current(connection)
        val at = connection.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
            check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant()
        } }
        return ingredientSearchPage(snapshot, principal, query, cursor, limit, at, cursors, store.limits)
    }
    override fun lookup(connection: Connection, principal: VerifiedKitchenPrincipal, ids: List<UUID>): JsonObject {
        if (principal.environment != store.environment) throw KitchenFailure(KitchenFailureCode.UNAUTHENTICATED)
        val selected = checkedIngredientIds(ids)
        store.checkCompatibility(connection)
        val snapshot = store.current(connection)
        val at = connection.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
            check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant()
        } }
        return ingredientLookupPage(snapshot, selected, at)
    }
    override fun toString() = "PostgresIngredientSearch(<redacted>)"
}

/** Targeted subset only. Missing or ineligible IDs stay unresolved, not historical grants. */
internal fun ingredientLookupPage(snapshot: IngredientCatalogStore.Snapshot, ids: List<UUID>, at: Instant): JsonObject {
    val selected = checkedIngredientIds(ids).toSet()
    return buildJsonObject {
        put("items", JsonArray(snapshot.original.items.asSequence()
            .filter { it.id in selected && it.reviewed && it.published && it.freeAccess }
            .sortedBy { it.id.toString() }.map { it.ingredient }.toList()))
        put("nextCursor", JsonNull)
        put("serverTime", at.toString())
    }
}

/** Pure bounded projection of an already verified immutable release. Synthetic callers may
 * test projection here, but neither this function nor its snapshot authenticates a principal.
 * The production path above always obtains its snapshot from the actual locked store.
 */
internal fun ingredientSearchPage(snapshot: IngredientCatalogStore.Snapshot, principal: VerifiedKitchenPrincipal,
    query: String?, cursor: String?, limit: Int, at: Instant, cursors: IngredientSearchCursor,
    limits: IngredientCatalogLimits): JsonObject {
    validateIngredientSearchInput(query, limit)
    val after = cursors.decode(principal, query, limit, snapshot.revision, snapshot.original.searchMode, cursor, at)
    // One finite verified release is already loaded with all derived alias rows checked.
    // Literal matching treats %, _, quotes and backslashes as text, never SQL syntax.
    val eligible = snapshot.original.items.asSequence().filter { it.reviewed && it.published && it.freeAccess }
        .filter { after == null || it.id.toString() > after.toString() }
        .filter { item -> query == null || query.isEmpty() ||
            (listOf(item.ingredient.getValue("name").jsonPrimitive.content) + item.ingredient.getValue("aliases").jsonArray.map { it.jsonPrimitive.content }).any { it.startsWith(query) } }
        .sortedBy { it.id.toString() }.take(limit + 1).toList()
    return buildJsonObject {
        put("items", JsonArray(eligible.take(limit).map { it.ingredient }))
        put("nextCursor", if (eligible.size > limit) JsonPrimitive(cursors.encode(principal, query, limit,
            snapshot.revision, snapshot.original.searchMode, eligible[limit - 1].id,
            at.plusSeconds(limits.cursorLifetimeSeconds.toLong()))) else JsonNull)
        put("serverTime", at.toString())
    }
}

private fun validateIngredientSearchInput(query: String?, limit: Int) {
    if (limit !in 1..50 || query?.let { it.codePointCount(0, it.length) > 100 || it.any(Char::isISOControl) } == true)
        throw KitchenFailure(KitchenFailureCode.INPUT_INVALID)
    query?.encodeToByteArray(throwOnInvalidSequence = true)
}
