package com.feedme.server.cooking

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.db.*
import com.feedme.server.identity.*
import com.feedme.server.planning.*
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Current registered account cooking over its real finite Plan store. No caller-selected
 * principal, guest manifest, automatic save, feedback, pantry consumption or timer delivery. */
internal class AccountCookingStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val planning: AccountPlanningStore,
    val policy: CookingServicePolicy, private val newCookingEnabled: Boolean) {
    init { require(planning.isBoundTo(environment, transactions, accounts)) }
    fun createCookSession(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult =
        use(subject, device) { c, a, s -> s.createCookSession(c, a, key, body) }
    fun getCookSession(subject: VerifiedSupabaseSubject, device: UUID, id: UUID): StoredReply =
        use(subject, device) { c, a, s -> s.getCookSession(c, a, id) }
    fun updateCookSession(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID, ifMatch: String, body: JsonObject): CommandResult =
        use(subject, device) { c, a, s -> s.updateCookSession(c, a, key, id, ifMatch, body) }
    fun completeCookSession(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID, body: JsonObject): CommandResult =
        use(subject, device) { c, a, s -> s.completeCookSession(c, a, key, id, body) }
    private fun <T> use(subject: VerifiedSupabaseSubject, device: UUID,
        action: (Connection, VerifiedCookingPrincipal, CookingStore) -> CookingStore.Pending<T>): T = safe {
        transactions.run { c ->
            val access = AccountMealAccess(environment, c, accounts, subject, device)
            planning.withOwnedTransaction(c, subject, device) { planningActor, plans, checkSourceAt ->
                if (planningActor.principalId != access.principalId) fail(CookingFailureCode.UNAUTHENTICATED)
                val actor = VerifiedCookingPrincipal(environment, CommandActor.ACCOUNT, access.principalId, device)
                fun current(connection: Connection, principal: VerifiedCookingPrincipal): Unit = safe {
                    if (principal !== actor) fail(CookingFailureCode.UNAUTHENTICATED)
                    access.current(connection)
                }
                val authority = object : CookingAuthority {
                    override fun lockPrincipal(connection: Connection, principal: VerifiedCookingPrincipal) = current(connection, principal)
                    override fun requireNewCookingEnabled(connection: Connection, principal: VerifiedCookingPrincipal) {
                        current(connection, principal)
                        if (!newCookingEnabled) fail(CookingFailureCode.NOT_CONFIGURED)
                    }
                    override fun validatePersonalNotes(connection: Connection, principal: VerifiedCookingPrincipal, notes: JsonArray) {
                        current(connection, principal)
                        if (notes.any { it.jsonObject["label"] != JsonPrimitive("myNote") || it.jsonObject.containsKey("shortcutId") })
                            fail(CookingFailureCode.NOT_CONFIGURED)
                    }
                }
                val pins = linkedMapOf<Pair<UUID, UUID?>, Pin>()
                val reader = CookingPlanReader { connection, actual, id, use, session ->
                    current(connection, actual)
                    val image = plans.lockCookingPlan(connection, planningActor, id, use, session)
                    val deadline = if (use == CookingPlanUse.NEW_SELECTION) connection.prepareStatement(
                        "SELECT r.expires_at FROM planning.plans p JOIN planning.plan_requests r ON " +
                            "(r.environment,r.actor_kind,r.principal_id,r.id)=(p.environment,p.actor_kind,p.principal_id,p.request_id) " +
                            "WHERE p.environment=? AND p.actor_kind='account' AND p.principal_id=? AND p.id=?").use { s ->
                        s.setString(1, environment); s.setObject(2, actor.principalId); s.setObject(3, id)
                        s.executeQuery().use { r -> check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant().also { check(!r.next()) } }
                    } else null
                    val pin = Pin(id, use, session, image, deadline)
                    pins[id to session]?.let { old -> if (!same(old.image, image) || old.deadline != deadline || old.use != use)
                        fail(CookingFailureCode.STORAGE_UNAVAILABLE) }
                    pins[id to session] = pin
                    current(connection, actual)
                    image
                }
                val store = CookingStore(environment, transactions, authority, reader, policy)
                val pending = action(c, actor, store)
                access.current(c)
                // The kernel's Pending checks exact result/receipt rows, not source authority.
                // Recheck each real Plan binding after all writes; a fresh start must still
                // satisfy NEW_SELECTION, never manufacture a historical pin to waive expiry.
                pins.values.forEach { pin ->
                    val actual = plans.lockCookingPlan(c, planningActor, pin.id, pin.use, pin.session)
                    if (!same(pin.image, actual)) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
                }
                pending.revalidate(c, actor)
                access.current(c)
                val at = AccountMealAccess.now(c)
                checkSourceAt(at)
                if (pins.values.any { it.deadline?.isAfter(at) == false }) fail(CookingFailureCode.PLAN_EXPIRED)
                pending.checkAt(c, actor, at)
                access.checkAt(c, at)
                pending.result
            }
        }
    }
    private class Pin(val id: UUID, val use: CookingPlanUse, val session: UUID?, val image: CookingPlanSnapshot, val deadline: Instant?)
    private fun same(a: CookingPlanSnapshot, b: CookingPlanSnapshot) = a.snapshotText == b.snapshotText && a.snapshotHash == b.snapshotHash &&
        a.proofHash == b.proofHash && a.evidenceHash == b.evidenceHash
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (f: CookingFailure) { throw f }
        catch (f: AccountFailure) { fail(when (f.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> CookingFailureCode.UNAUTHENTICATED
            AccountFailureCode.POLICY_BLOCKED, AccountFailureCode.NOT_CONFIGURED -> CookingFailureCode.NOT_CONFIGURED
            else -> CookingFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (f: PlanningServiceFailure) { fail(when (f.code) {
            PlanningFailureCode.UNAUTHENTICATED -> CookingFailureCode.UNAUTHENTICATED
            PlanningFailureCode.PLAN_UNAVAILABLE -> CookingFailureCode.PLAN_UNAVAILABLE
            PlanningFailureCode.PLAN_EXPIRED -> CookingFailureCode.PLAN_EXPIRED
            PlanningFailureCode.RECIPE_RECALLED -> CookingFailureCode.RECIPE_RECALLED
            PlanningFailureCode.RECIPE_UNAVAILABLE -> CookingFailureCode.RECIPE_UNAVAILABLE
            PlanningFailureCode.PREFERENCE_CHANGED, PlanningFailureCode.INPUTS_CHANGED -> CookingFailureCode.INPUTS_CHANGED
            PlanningFailureCode.MODE_CONFIRMATION_REQUIRED -> CookingFailureCode.PLAN_NOT_READY
            PlanningFailureCode.NOT_CONFIGURED, PlanningFailureCode.POLICY_BLOCKED -> CookingFailureCode.NOT_CONFIGURED
            else -> CookingFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (f: CommitOutcomeUnknown) { throw f }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (_: Exception) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Account cooking interrupted")
            fail(CookingFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: CookingFailureCode): Nothing = throw CookingFailure(code)
    override fun toString() = "AccountCookingStore(<redacted>)"
}
