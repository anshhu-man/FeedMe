package com.feedme.server.kitchen

import com.feedme.server.db.CommandActor
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/** Construct only after actual account/device or bounded guest authentication. Not an identity verifier. */
class VerifiedKitchenPrincipal(val environment: String, val kind: CommandActor, val principalId: UUID,
    val deviceSessionId: UUID?) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(kind == CommandActor.ACCOUNT || kind == CommandActor.GUEST)
        require((kind == CommandActor.ACCOUNT) == (deviceSessionId != null))
    }
    override fun toString() = "VerifiedKitchenPrincipal(<redacted>)"
}

/**
 * Mandatory current authority; there is deliberately no accepting implementation/default.
 * All callbacks are DB-only in the supplied transaction, with no commit or network effects.
 * lockPrincipal must exclusively serialize this owner's writes (including absent rows), and
 * revalidate current eligibility, account/device binding/revocation or guest expiry/merge.
 * Lock order shared with PlanningAuthority: principal, command receipt, preference, pantry
 * (ingredient UUID order), then current catalog/taxonomy/equipment/consent-policy rows.
 * Production planning and lifecycle adapters must use the SAME principal/input locks; the
 * application does not provide those identity/catalog tables or pretend an arbitrary ID exists.
 * Validators receive the exact proposed resource, must acquire current eligibility/reference
 * locks, and may reject but never rewrite it or infer confirmation, exclusions, stock or consent.
 * Dietary preset expansion requires explicit reviewed policy/confirmation, not silent additions.
 * Validators apply only to new writes/provisioning. Owned read/replay is not catalog selection:
 * retired IDs remain readable/editable rough reports, not a fresh planning or cooking grant.
 * A real policy must distinguish retained exclusions from new unsupported selections when editing.
 * Throw KitchenFailure for a safe declared policy failure; other exceptions become unavailable.
 */
interface KitchenAuthority {
    fun lockPrincipal(connection: Connection, principal: VerifiedKitchenPrincipal)
    fun requireProvisioningAllowed(connection: Connection, principal: VerifiedKitchenPrincipal)
    fun validatePreferences(connection: Connection, principal: VerifiedKitchenPrincipal, proposed: JsonObject)
    fun validatePantryItem(connection: Connection, principal: VerifiedKitchenPrincipal, proposed: JsonObject)
}

/** Actual published/free catalog and current authorization must be supplied; a cursor grants no rights. */
fun interface KitchenIngredientSearch {
    fun search(connection: Connection, principal: VerifiedKitchenPrincipal, q: String?, cursor: String?, limit: Int): JsonObject
}

/** Explicit deployment bounds, enforced BEFORE domain/receipt/outbox commit. No hidden schema expansion. */
class KitchenServicePolicy(val maxResponseBytes: Int, val cursorLifetimeSeconds: Int) {
    init { require(maxResponseBytes in 1..262144); require(cursorLifetimeSeconds in 1..86400) }
    override fun toString() = "KitchenServicePolicy(<redacted>)"
}

enum class KitchenFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), PREFERENCES_UNAVAILABLE(404), PANTRY_ITEM_UNAVAILABLE(404),
    INGREDIENT_UNAVAILABLE(422), VERSION_CONFLICT(412), CURSOR_INVALID(422), CURSOR_EXPIRED(410),
    NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503), RESPONSE_TOO_LARGE(422),
}
class KitchenFailure(val code: KitchenFailureCode) : RuntimeException("Kitchen operation unavailable: ${code.name}")
