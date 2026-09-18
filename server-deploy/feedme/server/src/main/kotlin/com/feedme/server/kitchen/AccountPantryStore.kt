package com.feedme.server.kitchen

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.IngredientCatalogFailure
import com.feedme.server.catalog.IngredientCatalogFailureCode
import com.feedme.server.catalog.IngredientCatalogStore
import com.feedme.server.db.*
import com.feedme.server.identity.*
import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Account-only pantry adapter; never accepts a caller-supplied private principal.
 * Signature verification happens at ingress. Current provider/account/device/ready/terms
 * admission is repeated inside EVERY actual store transaction, including cached replies,
 * and again after domain work. The preliminary lookup is not an authorization cache.
 * Only fresh upserts require a currently reviewed, published, free ingredient. Existing
 * reports and their removal do not grant cooking rights or depend on continued publication.
 * The caller supplies the real account authority/database and persisted ingredient catalog;
 * there is no fallback, guest path, preference provisioning or eligibility transition.
 */
internal class AccountPantryStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val accounts: AccountProfileStore,
    private val catalog: IngredientCatalogStore,
    private val cursors: KitchenCursorCodec,
    val policy: KitchenServicePolicy,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(catalog.environment == environment)
    }

    fun listPantry(subject: VerifiedSupabaseSubject, device: UUID, cursor: String? = null,
        limit: Int = 20): StoredReply = use(subject, device) { actor, store ->
        store.listPantry(actor, cursor, limit)
    }

    fun upsertPantryItem(subject: VerifiedSupabaseSubject, device: UUID, key: UUID,
        body: JsonObject): CommandResult = use(subject, device) { actor, store ->
        store.upsertPantryItem(actor, key, body)
    }

    fun removePantryItem(subject: VerifiedSupabaseSubject, device: UUID, key: UUID,
        ingredientId: UUID, ifMatch: String): CommandResult = use(subject, device) { actor, store ->
        store.removePantryItem(actor, key, ingredientId, ifMatch)
    }

    private fun <T> use(subject: VerifiedSupabaseSubject, device: UUID,
        action: (VerifiedKitchenPrincipal, KitchenStore) -> T): T = safe {
        val original = transactions.run { accounts.lockPrivateAccount(it, subject, device) }
        if (original.environment != environment) denied()
        val actor = VerifiedKitchenPrincipal(environment, CommandActor.ACCOUNT, original.principalId, device)
        fun current(connection: Connection, principal: VerifiedKitchenPrincipal) {
            if (principal !== actor) denied()
            val actual = accounts.lockPrivateAccount(connection, subject, device)
            if (actual.environment != original.environment || actual.accountId != original.accountId ||
                actual.principalId != original.principalId || actual.deviceSessionId != original.deviceSessionId)
                denied()
        }
        val authority = object : KitchenAuthority, KitchenCompletionAuthority {
            override fun lockPrincipal(connection: Connection, principal: VerifiedKitchenPrincipal) = safe {
                current(connection, principal)
                catalog.checkCompatibility(connection)
                current(connection, principal)
            }
            override fun revalidatePrincipal(connection: Connection, principal: VerifiedKitchenPrincipal) = safe {
                current(connection, principal)
            }
            override fun validatePantryItem(connection: Connection, principal: VerifiedKitchenPrincipal,
                proposed: JsonObject) = safe {
                current(connection, principal)
                val ingredient = UUID.fromString(proposed.getValue("ingredientId").jsonPrimitive.content)
                val snapshot = catalog.current(connection)
                if (snapshot.original.items.none { it.id == ingredient && it.reviewed && it.published && it.freeAccess })
                    throw KitchenFailure(KitchenFailureCode.INGREDIENT_UNAVAILABLE)
                current(connection, principal)
            }
            override fun requireProvisioningAllowed(connection: Connection, principal: VerifiedKitchenPrincipal): Unit = unavailable()
            override fun validatePreferences(connection: Connection, principal: VerifiedKitchenPrincipal,
                proposed: JsonObject): Unit = unavailable()
        }
        action(actor, KitchenStore(environment, transactions, authority, cursors, policy))
    }

    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: AccountFailure) { throw KitchenFailure(when (failure.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> KitchenFailureCode.UNAUTHENTICATED
            AccountFailureCode.POLICY_BLOCKED -> KitchenFailureCode.POLICY_BLOCKED
            AccountFailureCode.NOT_CONFIGURED -> KitchenFailureCode.NOT_CONFIGURED
            else -> KitchenFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: IngredientCatalogFailure) { throw KitchenFailure(
            if (failure.code == IngredientCatalogFailureCode.NOT_CONFIGURED) KitchenFailureCode.NOT_CONFIGURED
            else KitchenFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: KitchenFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { throw KitchenFailure(KitchenFailureCode.STORAGE_UNAVAILABLE) }
    private fun denied(): Nothing = throw KitchenFailure(KitchenFailureCode.UNAUTHENTICATED)
    private fun unavailable(): Nothing = throw KitchenFailure(KitchenFailureCode.NOT_CONFIGURED)
    override fun toString() = "AccountPantryStore(<redacted>)"
}
