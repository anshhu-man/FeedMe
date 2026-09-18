package com.feedme.server.guest

import com.feedme.server.cooking.*
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.PgTransactions
import com.feedme.server.db.StoredReply
import com.feedme.server.planning.PlanningFailureCode
import com.feedme.server.planning.PlanningServiceFailure
import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Actual guest-token cooking over complete first-Plan manifests. The real guest owner
 * owns admission, principal/guest locks, retries, final authority and ONE commit containing
 * cooking state, exact pin, cursor, private step event, receipt and redacted outbox fact.
 * There is no account fallback, synthetic authority, new Plan charge, recipe copy, save,
 * feedback, timer delivery or social publication. No HTTP/runtime capability is activated.
 * Retention and response limits must be supplied explicitly by deployment configuration.
 */
internal class GuestCookingStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val preparations: GuestPlanningStore,
    val policy: CookingServicePolicy,
    private val makeAgain: GuestMakeAgainStore? = null,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(preparations.isBoundTo(environment, transactions)) { "Guest cooking requires its actual preparation owner" }
        require(makeAgain == null || makeAgain.isCookingBoundTo(environment, transactions, preparations, policy))
    }

    fun createCookSession(token: String, key: UUID, body: JsonObject): CommandResult =
        use(token, "createCookSession") { c, actor, store -> store.createCookSession(c, actor, key, body) }

    fun getCookSession(token: String, sessionId: UUID): StoredReply =
        use(token, "getCookSession") { c, actor, store -> store.getCookSession(c, actor, sessionId) }

    fun updateCookSession(token: String, key: UUID, sessionId: UUID, ifMatch: String, body: JsonObject): CommandResult =
        use(token, "updateCookSession") { c, actor, store -> store.updateCookSession(c, actor, key, sessionId, ifMatch, body) }

    fun completeCookSession(token: String, key: UUID, sessionId: UUID, body: JsonObject): CommandResult =
        if ((body["makeAgain"] as? JsonPrimitive)?.booleanOrNull == true && makeAgain != null)
            makeAgain.completeCookSession(token, key, sessionId, body)
        else use(token, "completeCookSession") { c, actor, store -> store.completeCookSession(c, actor, key, sessionId, body) }

    private fun <T> use(token: String, operation: String,
        action: (Connection, VerifiedCookingPrincipal, CookingStore) -> CookingStore.Pending<T>): T = safe {
        preparations.withCooking(token, operation, consume = { c, access ->
            val actor = access.principal
            val authority = object : CookingAuthority {
                override fun lockPrincipal(connection: Connection, principal: VerifiedCookingPrincipal) =
                    access.requireBound(connection, principal)

                override fun requireNewCookingEnabled(connection: Connection, principal: VerifiedCookingPrincipal) {
                    access.requireBound(connection, principal)
                    // The actual issued session's exact configured capability was checked
                    // by GuestSessionStore for this operation. This only scopes its use;
                    // it is not an accepting feature toggle or independent authentication.
                    if (operation != "createCookSession") fail(CookingFailureCode.NOT_CONFIGURED)
                }

                override fun validatePersonalNotes(connection: Connection, principal: VerifiedCookingPrincipal, notes: JsonArray) {
                    access.requireBound(connection, principal)
                    if (operation != "updateCookSession") fail(CookingFailureCode.NOT_CONFIGURED)
                    // Private plain notes are user-authored, not reviewed instructions.
                    // Referenced shortcuts/community tips require a separate actual rights
                    // integration and must never be relabeled or silently accepted here.
                    if (notes.any { it.jsonObject.getValue("label").jsonPrimitive.content != "myNote" ||
                            it.jsonObject.containsKey("shortcutId") }) fail(CookingFailureCode.NOT_CONFIGURED)
                    access.requireBound(connection, principal)
                }
            }
            val store = CookingStore(environment, transactions, authority, access, policy)
            access.requireBound(c, actor)
            Attempt(actor, action(c, actor, store)).also { access.requireBound(c, actor) }
        }, complete = { c, access, attempt ->
            access.requireBound(c, attempt.actor)
            // Real guest final policy/revocation/expiry checks already ran. The scoped
            // kernel now rejects changed result/receipt/event/cursor/outbox or pin expiry;
            // source revalidation runs on both sides in the actual owner wrapper.
            attempt.pending.revalidate(c, attempt.actor)
            access.requireBound(c, attempt.actor)
        }).pending.result
    }

    private class Attempt<T>(val actor: VerifiedCookingPrincipal, val pending: CookingStore.Pending<T>)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: GuestSessionFailure) { throw failure }
        catch (failure: CookingFailure) { throw failure }
        catch (failure: PlanningServiceFailure) { fail(when (failure.code) {
            PlanningFailureCode.UNAUTHENTICATED -> CookingFailureCode.UNAUTHENTICATED
            PlanningFailureCode.PLAN_UNAVAILABLE -> CookingFailureCode.PLAN_UNAVAILABLE
            PlanningFailureCode.PLAN_EXPIRED -> CookingFailureCode.PLAN_EXPIRED
            PlanningFailureCode.MODE_CONFIRMATION_REQUIRED -> CookingFailureCode.PLAN_NOT_READY
            PlanningFailureCode.PREFERENCE_CHANGED, PlanningFailureCode.INPUTS_CHANGED -> CookingFailureCode.INPUTS_CHANGED
            PlanningFailureCode.RECIPE_RECALLED -> CookingFailureCode.RECIPE_RECALLED
            PlanningFailureCode.RECIPE_UNAVAILABLE -> CookingFailureCode.RECIPE_UNAVAILABLE
            PlanningFailureCode.NOT_CONFIGURED -> CookingFailureCode.NOT_CONFIGURED
            else -> CookingFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest cooking interrupted")
            fail(CookingFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: CookingFailureCode): Nothing = throw CookingFailure(code)
    override fun toString() = "GuestCookingStore(<redacted>)"
}
