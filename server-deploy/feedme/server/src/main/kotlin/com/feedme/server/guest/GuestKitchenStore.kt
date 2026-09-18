package com.feedme.server.guest

import com.feedme.server.catalog.IngredientCatalogFailure
import com.feedme.server.catalog.IngredientCatalogFailureCode
import com.feedme.server.catalog.IngredientCatalogStore
import com.feedme.server.catalog.IngredientPreferencePolicy
import com.feedme.server.db.CommandActor
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.PgTransactions
import com.feedme.server.db.StoredReply
import com.feedme.server.kitchen.KitchenAuthority
import com.feedme.server.kitchen.KitchenCompletionAuthority
import com.feedme.server.kitchen.KitchenCursorCodec
import com.feedme.server.kitchen.KitchenFailure
import com.feedme.server.kitchen.KitchenFailureCode
import com.feedme.server.kitchen.KitchenServicePolicy
import com.feedme.server.kitchen.KitchenStore
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Guest-only private inputs. Only the actual opaque credential enters each fixed operation;
 * no caller supplies a principal, device, current-authority callback or account fallback.
 * The session owner authenticates and serializes installation -> principal -> guest, then
 * owns the ONE transaction containing the kernel's receipt, input rows and catalog locks.
 * Its mandatory final authority/current checks precede idle renewal and commit. This local
 * kernel authority only binds that already-admitted invocation and validates new selections;
 * it is not an independent identity verifier or reusable authorization grant.
 *
 * Construction requires the exact session transaction owner and explicit policies/catalog.
 * There is no preference provisioning, inferred answer, catalog seed or runtime activation.
 */
internal class GuestKitchenStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val sessions: GuestSessionStore,
    private val catalog: IngredientCatalogStore,
    private val governed: IngredientPreferencePolicy,
    private val cursors: KitchenCursorCodec,
    val policy: KitchenServicePolicy,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) { "Invalid guest kitchen environment" }
        require(sessions.isBoundTo(environment, transactions)) { "Guest kitchen requires its session transaction owner" }
        require(catalog.environment == environment) { "Guest kitchen catalog environment differs" }
    }

    internal fun isBoundTo(owner: GuestSessionStore): Boolean = sessions === owner

    fun getPreferences(token: String): StoredReply = use(token, "getPreferences") { c, actor, store ->
        store.getPreferences(c, actor)
    }

    fun updatePreferences(token: String, key: UUID, ifMatch: String, body: JsonObject): CommandResult =
        use(token, "updatePreferences") { c, actor, store ->
            store.updatePreferences(c, actor, key, ifMatch, body)
        }

    fun listPantry(token: String, cursor: String? = null, limit: Int = 20): StoredReply =
        use(token, "listPantry") { c, actor, store -> store.listPantry(c, actor, cursor, limit) }

    fun upsertPantryItem(token: String, key: UUID, body: JsonObject): CommandResult =
        use(token, "upsertPantryItem") { c, actor, store -> store.upsertPantryItem(c, actor, key, body) }

    fun removePantryItem(token: String, key: UUID, ingredientId: UUID, ifMatch: String): CommandResult =
        use(token, "removePantryItem") { c, actor, store ->
            store.removePantryItem(c, actor, key, ingredientId, ifMatch)
        }

    private fun <T> use(token: String, operation: String,
        action: (Connection, VerifiedKitchenPrincipal, KitchenStore) -> T): T = safe {
        check(operation in operations)
        sessions.withCurrent(token, operation) { connection, actor ->
            val thread = Thread.currentThread()
            val transaction = transactionId(connection)
            var active = true
            fun bound(c: Connection, principal: VerifiedKitchenPrincipal, expectedOperation: String = operation) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest kitchen interrupted")
                if (!active || Thread.currentThread() !== thread || c !== connection || principal !== actor ||
                    operation != expectedOperation || principal.environment != environment ||
                    principal.kind != CommandActor.GUEST || principal.deviceSessionId != null ||
                    c.isClosed || c.autoCommit || transactionId(c) != transaction)
                    fail(KitchenFailureCode.UNAUTHENTICATED)
            }
            val authority = object : KitchenAuthority, KitchenCompletionAuthority {
                override fun lockPrincipal(c: Connection, principal: VerifiedKitchenPrincipal) = safe {
                    bound(c, principal)
                    // Schema admission only: selection locks belong after receipt/input rows.
                    catalog.checkCompatibility(c)
                    bound(c, principal)
                }

                override fun revalidatePrincipal(c: Connection, principal: VerifiedKitchenPrincipal) {
                    bound(c, principal)
                    // The outer session owner performs actual current/expiry revalidation.
                }

                override fun requireProvisioningAllowed(c: Connection, principal: VerifiedKitchenPrincipal) {
                    bound(c, principal)
                    fail(KitchenFailureCode.NOT_CONFIGURED)
                }

                override fun validatePreferences(c: Connection, principal: VerifiedKitchenPrincipal,
                    proposed: JsonObject) = safe {
                    bound(c, principal, "updatePreferences")
                    val previous = previousPreferences(c, principal)
                    val snapshot = catalog.current(c)
                    val selectable = snapshot.original.items.filter { it.reviewed && it.published && it.freeAccess }
                        .map { it.id }.toSet()
                    for (field in listOf("hardExcludedIngredientIds", "dislikedIngredientIds")) {
                        val prior = previous.getValue(field).jsonArray.map { UUID.fromString(it.jsonPrimitive.content) }.toSet()
                        val next = proposed.getValue(field).jsonArray.map { UUID.fromString(it.jsonPrimitive.content) }
                        // Preserve exact arrays and retained retired references. A retained
                        // exclusion is not a fresh published ingredient selection.
                        if (next.any { it !in prior && it !in selectable }) fail(KitchenFailureCode.INGREDIENT_UNAVAILABLE)
                    }
                    bound(c, principal, "updatePreferences")
                    governed.validate(c, principal, previous, proposed)
                    bound(c, principal, "updatePreferences")
                }

                override fun validatePantryItem(c: Connection, principal: VerifiedKitchenPrincipal,
                    proposed: JsonObject) = safe {
                    bound(c, principal, "upsertPantryItem")
                    val ingredient = UUID.fromString(proposed.getValue("ingredientId").jsonPrimitive.content)
                    val snapshot = catalog.current(c)
                    if (snapshot.original.items.none { it.id == ingredient && it.reviewed && it.published && it.freeAccess })
                        fail(KitchenFailureCode.INGREDIENT_UNAVAILABLE)
                    bound(c, principal, "upsertPantryItem")
                }
            }
            val store = KitchenStore(environment, transactions, authority, cursors, policy)
            try {
                bound(connection, actor)
                action(connection, actor, store).also { bound(connection, actor) }
            } finally { active = false }
        }
    }

    private fun previousPreferences(c: Connection, actor: VerifiedKitchenPrincipal): JsonObject =
        c.prepareStatement("SELECT fields FROM profile.preferences WHERE environment=? AND actor_kind='guest' AND principal_id=? FOR UPDATE").use { s ->
            s.setString(1, environment); s.setObject(2, actor.principalId)
            s.executeQuery().use { rows ->
                if (!rows.next()) fail(KitchenFailureCode.PREFERENCES_UNAVAILABLE)
                val fields = Json.parseToJsonElement(rows.getString(1)).jsonObject
                if (rows.next()) fail(KitchenFailureCode.STORAGE_UNAVAILABLE)
                fields
            }
        }

    private fun transactionId(c: Connection): Long {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest kitchen interrupted")
        return c.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { rows ->
            if (!rows.next()) fail(KitchenFailureCode.STORAGE_UNAVAILABLE)
            val id = rows.getLong(1)
            if (rows.next()) fail(KitchenFailureCode.STORAGE_UNAVAILABLE)
            id
        } }
    }

    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: GuestSessionFailure) { throw failure }
        catch (failure: IngredientCatalogFailure) { throw KitchenFailure(
            if (failure.code == IngredientCatalogFailureCode.NOT_CONFIGURED) KitchenFailureCode.NOT_CONFIGURED
            else KitchenFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: KitchenFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(KitchenFailureCode.STORAGE_UNAVAILABLE) }

    private fun fail(code: KitchenFailureCode): Nothing = throw KitchenFailure(code)
    override fun toString() = "GuestKitchenStore(<redacted>)"

    private companion object {
        val operations = setOf("getPreferences", "updatePreferences", "listPantry", "upsertPantryItem", "removePantryItem")
    }
}
