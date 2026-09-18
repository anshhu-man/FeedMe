package com.feedme.server.guest

import com.feedme.server.catalog.IngredientCatalogFailure
import com.feedme.server.catalog.IngredientCatalogFailureCode
import com.feedme.server.catalog.PostgresIngredientSearch
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.StoredReply
import com.feedme.server.kitchen.KitchenFailure
import com.feedme.server.kitchen.KitchenFailureCode
import com.feedme.server.kitchen.checkedIngredientIds
import java.nio.charset.CharacterCodingException
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/** Purpose-fixed guest ingredient search. Only the actual opaque guest credential enters;
 * callers cannot supply a principal, device, account token substitute or trusted callback.
 * The session owner authenticates and rechecks the current guest in ONE transaction. Catalog
 * head/release locks and canonical bounded response validation remain inside that transaction,
 * before its final authority check, idle update and commit. Never nest KitchenStore here.
 * This composition supplies no bootstrap admission policy, runtime key ring or HTTP route.
 */
internal class GuestIngredientSearchStore(
    private val sessions: GuestSessionStore,
    private val catalog: PostgresIngredientSearch,
    val maxResponseBytes: Int,
) {
    init { require(maxResponseBytes in 1..262_144) { "Invalid guest search response limit" } }

    internal fun isBoundTo(owner: GuestSessionStore): Boolean = sessions === owner

    fun search(token: String, query: String?, cursor: String?, limit: Int): StoredReply {
        validateGuestIngredientQuery(query, cursor, limit)
        return sessions.withCurrent(token, "searchIngredients") { connection, principal ->
            val body = try { catalog.search(connection, principal, query, cursor, limit) }
            catch (failure: IngredientCatalogFailure) {
                throw KitchenFailure(if (failure.code == IngredientCatalogFailureCode.NOT_CONFIGURED)
                    KitchenFailureCode.NOT_CONFIGURED else KitchenFailureCode.STORAGE_UNAVAILABLE)
            }
            checkedGuestIngredientReply(body, maxResponseBytes)
        }
    }

    fun lookup(token: String, ids: List<UUID>): StoredReply {
        val selected = checkedIngredientIds(ids)
        return sessions.withCurrent(token, "searchIngredients") { connection, principal ->
            val body = try { catalog.lookup(connection, principal, selected) }
            catch (failure: IngredientCatalogFailure) {
                throw KitchenFailure(if (failure.code == IngredientCatalogFailureCode.NOT_CONFIGURED)
                    KitchenFailureCode.NOT_CONFIGURED else KitchenFailureCode.STORAGE_UNAVAILABLE)
            }
            checkedGuestIngredientReply(body, maxResponseBytes)
        }
    }

    override fun toString() = "GuestIngredientSearchStore(<redacted>)"
}

/** Input bounds only, never normalization or guest authentication. */
internal fun validateGuestIngredientQuery(query: String?, cursor: String?, limit: Int) {
    fun invalid(): Nothing = throw KitchenFailure(KitchenFailureCode.INPUT_INVALID)
    if (limit !in 1..50) invalid()
    for ((value, maximum) in listOf(query to 100, cursor to 2048)) {
        if (value == null) continue
        if (value.codePointCount(0, value.length) > maximum || value.any(Char::isISOControl)) invalid()
        try { value.encodeToByteArray(throwOnInvalidSequence = true) }
        catch (_: CharacterCodingException) { invalid() }
    }
}

/** Run before transaction completion; failure must not refresh guest idle time. */
internal fun checkedGuestIngredientReply(body: JsonObject, maxResponseBytes: Int): StoredReply {
    require(maxResponseBytes in 1..262_144) { "Invalid guest search response limit" }
    val bytes = try { body.toString().encodeToByteArray(throwOnInvalidSequence = true) }
        catch (_: CharacterCodingException) { throw KitchenFailure(KitchenFailureCode.STORAGE_UNAVAILABLE) }
    if (bytes.size > maxResponseBytes) throw KitchenFailure(KitchenFailureCode.RESPONSE_TOO_LARGE)
    if (guestIngredientValidator.validateResponse("searchIngredients", 200, bytes, "application/json") != BodyValidationResult.Valid)
        throw KitchenFailure(KitchenFailureCode.STORAGE_UNAVAILABLE)
    return StoredReply(200, body)
}

private val guestIngredientValidator by lazy { ContractBodyValidator.bundled() }
