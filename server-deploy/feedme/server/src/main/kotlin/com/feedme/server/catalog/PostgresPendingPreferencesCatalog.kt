package com.feedme.server.catalog

import com.feedme.server.db.CommandActor
import com.feedme.server.kitchen.*
import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Real persisted catalog adapter, not account authentication. Use only inside the existing
 * pending-account assembly; its current provider/account/device proof remains mandatory.
 * The separately configured field policy must supply real diet/equipment/consent authority. */
class PostgresPendingPreferencesCatalog(private val store: IngredientCatalogStore,
    private val cursors: IngredientSearchCursor, private val governed: IngredientPreferencePolicy) : PendingPreferencesCatalog {
    private val search = PostgresIngredientSearch(store, cursors)
    override fun checkCompatibility(connection: Connection) = catalogSafe { store.checkCompatibility(connection) }

    override fun validatePreferences(connection: Connection, principal: VerifiedKitchenPrincipal,
        previousFields: JsonObject, proposed: JsonObject) = catalogSafe {
        checkPrincipal(connection, principal)
        val snapshot = store.current(connection)
        val selectable = snapshot.original.items.filter { it.reviewed && it.published && it.freeAccess }.map { it.id }.toSet()
        for (field in listOf("hardExcludedIngredientIds", "dislikedIngredientIds")) {
            val prior = previousFields.getValue(field).jsonArray.map { UUID.fromString(it.jsonPrimitive.content) }.toSet()
            val next = proposed.getValue(field).jsonArray.map { UUID.fromString(it.jsonPrimitive.content) }
            // Canonical arrays are not silently deduplicated/reordered; UUID comparison alone
            // identifies retained references, including their unchanged retired/absent IDs.
            if (next.any { it !in prior && it !in selectable }) throw KitchenFailure(KitchenFailureCode.INGREDIENT_UNAVAILABLE)
        }
        governed.validate(connection, principal, previousFields, proposed)
        // Head and immutable release locks remain held through the caller's final proof/commit.
    }

    override fun search(connection: Connection, principal: VerifiedKitchenPrincipal, query: String?, cursor: String?, limit: Int): JsonObject = catalogSafe {
        checkPrincipal(connection, principal)
        search.search(connection, principal, query, cursor, limit)
    }
    override fun lookup(connection: Connection, principal: VerifiedKitchenPrincipal, ids: List<UUID>): JsonObject = catalogSafe {
        checkPrincipal(connection, principal)
        search.lookup(connection, principal, ids)
    }
    private fun checkPrincipal(c: Connection, actor: VerifiedKitchenPrincipal) {
        require(!c.autoCommit)
        if (actor.environment != store.environment || actor.kind != CommandActor.ACCOUNT || actor.deviceSessionId == null)
            throw KitchenFailure(KitchenFailureCode.UNAUTHENTICATED)
    }
    private fun <T> catalogSafe(action: () -> T): T = try { action() }
        catch (f: KitchenFailure) { throw f }
        catch (f: IngredientCatalogFailure) { throw KitchenFailure(if (f.code == IngredientCatalogFailureCode.NOT_CONFIGURED) KitchenFailureCode.NOT_CONFIGURED else KitchenFailureCode.STORAGE_UNAVAILABLE) }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (_: Exception) { throw KitchenFailure(KitchenFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "PostgresPendingPreferencesCatalog(<redacted>)"
}
