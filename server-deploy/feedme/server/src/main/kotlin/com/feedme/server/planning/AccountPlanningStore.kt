package com.feedme.server.planning

import com.feedme.contracts.WireDocument
import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.*
import com.feedme.server.db.*
import com.feedme.server.identity.*
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Concrete account-only four-operation bridge. Only verified provider subject + registered
 * device enter; neither JWT subject nor caller metadata can select a private principal.
 * The preliminary mapping is not an admission cache: every actual PlansStore transaction
 * locks/rechecks provider/account/device/ready/terms before receipts and again before commit.
 * Preference/pantry locks precede the actual immutable reviewed/free catalog head. All old
 * lifecycle records remain visible to the existing Plan reader; no fixture/source-copy grant,
 * paid access, dietary expansion, prepared-base inference or format2/3 support is introduced.
 */
internal class AccountPlanningStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val accounts: AccountProfileStore,
    private val catalog: RecipeCatalogStore,
    private val operational: AccountPlanningPolicy,
    private val policy: PlanningServicePolicy,
    private val cursors: PlanningCursors,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(catalog.environment == environment)
    }
    fun createPlan(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult =
        accountPlanningSafe { use(subject, device) { actor, store -> store.createPlan(actor, key, body) } }
    fun nextPlan(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, parent: UUID, body: JsonObject): CommandResult =
        accountPlanningSafe { use(subject, device) { actor, store -> store.nextPlan(actor, key, parent, body) } }
    fun getPlan(subject: VerifiedSupabaseSubject, device: UUID, planId: UUID): StoredReply =
        accountPlanningSafe { use(subject, device) { actor, store -> store.getPlan(actor, planId) } }
    fun getPlanExplanation(subject: VerifiedSupabaseSubject, device: UUID, planId: UUID, cursor: String? = null, limit: Int = 20): StoredReply =
        accountPlanningSafe { use(subject, device) { actor, store -> store.getPlanExplanation(actor, planId, cursor, limit) } }

    /** Internal caller-owned bridge. The real account mapping and every subsequent authority
     * callback run in this transaction; neither the principal nor store escapes as a grant.
     * The caller owns its domain failure mapping; do not normalize its action as planning. */
    internal fun <T> withOwnedTransaction(connection: Connection, subject: VerifiedSupabaseSubject, device: UUID,
        action: (VerifiedPlanningPrincipal, PlansStore) -> T): T = use(subject, device, connection, action)

    internal fun isBoundTo(environment: String, transactions: PgTransactions, accounts: AccountProfileStore) =
        this.environment == environment && this.transactions === transactions && this.accounts === accounts

    private fun <T> use(subject: VerifiedSupabaseSubject, device: UUID,
        connection: Connection? = null,
        action: (VerifiedPlanningPrincipal, PlansStore) -> T): T {
        val original = if (connection == null) transactions.run { accounts.lockPrivateAccount(it, subject, device) }
            else accounts.lockPrivateAccount(connection, subject, device)
        if (original.environment != environment) denied()
        val actor = VerifiedPlanningPrincipal(environment, CommandActor.ACCOUNT, original.principalId, device)
        val inputOwner = VerifiedKitchenPrincipal(environment, CommandActor.ACCOUNT, original.principalId, device)
        val inputs = AccountPlanningInputs(environment)
        val thread = Thread.currentThread()
        var active = true
        fun current(c: Connection, principal: VerifiedPlanningPrincipal) {
            if (!active || Thread.currentThread() !== thread || principal !== actor || (connection != null && c !== connection)) denied()
            val actual = accounts.lockPrivateAccount(c, subject, device)
            if (actual.environment != original.environment || actual.accountId != original.accountId ||
                actual.principalId != original.principalId || actual.deviceSessionId != original.deviceSessionId) denied()
        }
        val authority = object : PlanningAuthority {
            override fun lockPrincipal(connection: Connection, principal: VerifiedPlanningPrincipal) = accountPlanningSafe {
                current(connection, principal)
                operational.checkCompatibility(connection)
                catalog.checkCompatibility(connection)
                current(connection, principal)
            }
            override fun requireNewPlanningEnabledAndQuota(connection: Connection, principal: VerifiedPlanningPrincipal) = accountPlanningSafe {
                current(connection, principal)
                operational.requireNew(connection, principal)
                current(connection, principal)
            }
            override fun lockCurrentSnapshot(connection: Connection, principal: VerifiedPlanningPrincipal, request: WireDocument): PlanningEvidenceSnapshot = accountPlanningSafe {
                current(connection, principal)
                val body = json(request)
                if (body.getValue("mode").jsonPrimitive.content == "improve" || body.containsKey("baseMeal"))
                    throw PlanningServiceFailure(PlanningFailureCode.NOT_CONFIGURED)
                val privateInputs = json(inputs.lock(connection, inputOwner).copyForStorage())
                val currentCatalog = catalog.lockCurrent(connection)
                val evidence = PlanningEvidenceSnapshot.fromAuthoritativeDocument(wire(buildJsonObject {
                    put("version", 1); put("preferences", privateInputs.getValue("preferences"))
                    put("pantry", privateInputs.getValue("pantry")); put("baseMeal", JsonNull)
                    put("catalog", currentCatalog.planningCatalogDocument())
                }))
                current(connection, principal)
                evidence
            }
        }
        return try { action(actor, PlansStore(environment, transactions, authority, policy, cursors)) }
        finally { active = false }
    }
    override fun toString() = "AccountPlanningStore(<redacted>)"
    private fun denied(): Nothing = throw PlanningServiceFailure(PlanningFailureCode.UNAUTHENTICATED)
}

internal fun <T> accountPlanningSafe(action: () -> T): T = try { action() }
    catch (failure: AccountFailure) { throw PlanningServiceFailure(when (failure.code) {
        AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> PlanningFailureCode.UNAUTHENTICATED
        AccountFailureCode.POLICY_BLOCKED -> PlanningFailureCode.POLICY_BLOCKED
        AccountFailureCode.NOT_CONFIGURED -> PlanningFailureCode.NOT_CONFIGURED
        else -> PlanningFailureCode.STORAGE_UNAVAILABLE
    }) }
    catch (failure: RecipeCatalogFailure) { throw PlanningServiceFailure(if (failure.code == RecipeCatalogFailureCode.NOT_CONFIGURED)
        PlanningFailureCode.NOT_CONFIGURED else PlanningFailureCode.STORAGE_UNAVAILABLE) }
    catch (failure: PlanningServiceFailure) { throw failure }
    catch (failure: CommitOutcomeUnknown) { throw failure }
    catch (failure: CancellationException) { throw failure }
    catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
    catch (_: Exception) { throw PlanningServiceFailure(PlanningFailureCode.STORAGE_UNAVAILABLE) }
