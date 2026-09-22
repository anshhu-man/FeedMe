package com.feedme.server.planning

import com.feedme.contracts.WireDocument
import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.*
import com.feedme.server.db.*
import com.feedme.server.identity.*
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import com.feedme.server.memory.SavedRecipeFailure
import com.feedme.server.memory.SavedRecipeFailureCode
import com.feedme.server.memory.AccountMemoryStore
import com.feedme.server.memory.MemoryFailure
import com.feedme.server.memory.MemoryFailureCode
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Concrete account-only planning bridge. Only verified provider subject + registered
 * device enter; neither JWT subject nor caller metadata can select a private principal.
 * The preliminary mapping is not an admission cache: every actual PlansStore transaction
 * locks/rechecks provider/account/device/ready/terms before receipts and again before commit.
 * Preference/pantry locks precede the actual immutable reviewed/free catalog head. All old
 * lifecycle records remain visible to the existing Plan reader; no fixture/source-copy grant,
 * paid access, dietary expansion or prepared-base inference is introduced. Format-3 account
 * simplification/adaptation and format-4 direct Recipe roots require the actual journals;
 * their purpose-specific readers share that concrete account/publication authority.
 */
internal class AccountPlanningStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val accounts: AccountProfileStore,
    private val catalog: RecipeCatalogStore,
    private val operational: AccountPlanningPolicy,
    private val policy: PlanningServicePolicy,
    private val cursors: PlanningCursors,
    private val savedRights: RecipeCopyRightsStore? = null,
    private val journal: RecipeCatalogJournal? = null,
    private val substitutions: RecipeSubstitutionJournal? = null,
    private val postRecipeSources: (() -> com.feedme.server.social.posts.AccountPostRecipeSourceStore)? = null,
    private val memoryRanking: AccountMemoryStore? = null,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(catalog.environment == environment)
        require(savedRights == null || savedRights.environment == environment)
        require(journal == null || journal.environment == environment)
        require(substitutions == null || journal != null && substitutions.environment == environment)
        require(memoryRanking == null || memoryRanking.isBoundTo(environment, transactions, accounts))
    }
    fun createPlan(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult =
        accountPlanningSafe {
            if (body["intent"] == JsonPrimitive("makeMine")) {
                if (journal == null || substitutions == null) throw PlanningServiceFailure(PlanningFailureCode.NOT_CONFIGURED)
                transactions.run { c -> use(subject, device, c) { actor, _, _, derived ->
                    AccountRootRecipePlanningStore(transactions, operational, policy).create(c, actor, derived(c), key, body)
                } }
            } else use(subject, device) { actor, store, _, _ -> store.createPlan(actor, key, body) }
        }
    fun nextPlan(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, parent: UUID, body: JsonObject): CommandResult =
        accountPlanningSafe { use(subject, device) { actor, store, _, _ -> store.nextPlan(actor, key, parent, body) } }
    fun simplifyPlan(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, parent: UUID, ifMatch: String,
        body: JsonObject): CommandResult = accountPlanningSafe {
        if (journal == null) throw PlanningServiceFailure(PlanningFailureCode.NOT_CONFIGURED)
        transactions.run { c -> use(subject, device, c) { actor, plans, _, derived ->
            AccountDerivedPlanningStore(transactions, operational, policy).simplify(c, actor, plans, derived(c), key, parent, ifMatch, body)
        } }
    }
    fun adaptPlan(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, parent: UUID, ifMatch: String,
        body: JsonObject): CommandResult = accountPlanningSafe {
        if (journal == null || substitutions == null) throw PlanningServiceFailure(PlanningFailureCode.NOT_CONFIGURED)
        transactions.run { c -> use(subject, device, c) { actor, plans, _, derived ->
            AccountDerivedPlanningStore(transactions, operational, policy).adapt(c, actor, plans, derived(c), key, parent, ifMatch, body)
        } }
    }
    fun getPlan(subject: VerifiedSupabaseSubject, device: UUID, planId: UUID): StoredReply =
        accountPlanningSafe { use(subject, device) { actor, store, _, _ -> store.getPlan(actor, planId) } }
    fun getPlanExplanation(subject: VerifiedSupabaseSubject, device: UUID, planId: UUID, cursor: String? = null, limit: Int = 20): StoredReply =
        accountPlanningSafe { use(subject, device) { actor, store, _, _ -> store.getPlanExplanation(actor, planId, cursor, limit) } }

    /** Internal caller-owned bridge. The real account mapping and every subsequent authority
     * callback run in this transaction; neither the principal nor store escapes as a grant.
     * The caller owns its domain failure mapping; do not normalize its action as planning. */
    internal fun <T> withOwnedTransaction(connection: Connection, subject: VerifiedSupabaseSubject, device: UUID,
        action: (VerifiedPlanningPrincipal, PlansStore, (Instant) -> Unit) -> T): T =
        use(subject, device, connection) { actor, plans, checkAt, _ -> action(actor, plans, checkAt) }

    internal fun isBoundTo(environment: String, transactions: PgTransactions, accounts: AccountProfileStore) =
        this.environment == environment && this.transactions === transactions && this.accounts === accounts

    private fun <T> use(subject: VerifiedSupabaseSubject, device: UUID,
        connection: Connection? = null,
        action: (VerifiedPlanningPrincipal, PlansStore, (Instant) -> Unit, (Connection) -> AccountDerivedPlanningAuthority) -> T): T {
        val original = if (connection == null) transactions.run { accounts.lockPrivateAccount(it, subject, device) }
            else accounts.lockPrivateAccount(connection, subject, device)
        if (original.environment != environment) denied()
        val actor = VerifiedPlanningPrincipal(environment, CommandActor.ACCOUNT, original.principalId, device)
        val inputOwner = VerifiedKitchenPrincipal(environment, CommandActor.ACCOUNT, original.principalId, device)
        val inputs = AccountPlanningInputs(environment)
        val memoryReads = java.util.IdentityHashMap<Connection, AccountMemoryStore.PlanningRead>()
        val thread = Thread.currentThread()
        var active = true
        val savedSources = java.util.IdentityHashMap<Connection, AccountSavedPlanningSource>()
        val derivedAuthorities = java.util.IdentityHashMap<Connection, AccountDerivedPlanningAuthority>()
        val derivedReaders = java.util.IdentityHashMap<Connection, AccountDerivedPlanReader>()
        fun current(c: Connection, principal: VerifiedPlanningPrincipal) {
            if (!active || Thread.currentThread() !== thread || principal !== actor || (connection != null && c !== connection)) denied()
            val actual = accounts.lockPrivateAccount(c, subject, device)
            if (actual.environment != original.environment || actual.accountId != original.accountId ||
                actual.principalId != original.principalId || actual.deviceSessionId != original.deviceSessionId) denied()
        }
        fun currentInputs(c: Connection): PlanningPrivateInputsSnapshot {
            current(c, actor)
            return inputs.lock(c, inputOwner, memoryRanking?.let { store -> {
                store.lockPlanningMemories(c, subject, device).also { memoryReads[c] = it }.memories
            } })
        }
        fun derived(c: Connection): AccountDerivedPlanningAuthority {
            current(c, actor)
            val actualJournal = journal ?: throw PlanningServiceFailure(PlanningFailureCode.NOT_CONFIGURED)
            return derivedAuthorities.getOrPut(c) {
                AccountDerivedPlanningAuthority(environment, c, actor, accounts, subject, device, actualJournal, policy, substitutions, savedRights, postRecipeSources,
                    planningInputs = { currentInputs(c) }, memoryRevalidate = { memoryReads[c]?.revalidate(c) },
                    memoryCheckAt = { at -> memoryReads[c]?.checkAt(c, at) })
            }
        }
        val authority = object : PlanningAuthority {
            override fun lockPrincipal(connection: Connection, principal: VerifiedPlanningPrincipal) = accountPlanningSafe {
                current(connection, principal)
                operational.checkCompatibility(connection)
                catalog.checkCompatibility(connection)
                savedSources[connection]?.revalidate()
                derivedReaders[connection]?.revalidate()
                memoryReads[connection]?.revalidate(connection)
                current(connection, principal)
                if (savedSources.containsKey(connection) || derivedReaders.containsKey(connection) || memoryReads.containsKey(connection)) {
                    val at = connection.createStatement().use { it.executeQuery("SELECT clock_timestamp()").use { rows ->
                        check(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant()
                    } }
                    savedSources[connection]?.checkAt(at)
                    derivedReaders[connection]?.checkAt(at)
                    memoryReads[connection]?.checkAt(connection, at)
                }
                Unit
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
                val privateInputs = json(currentInputs(connection).copyForStorage())
                val catalogDocument = journal?.openView(connection)?.planningCatalogDocument()
                    ?: catalog.lockCurrent(connection).planningCatalogDocument()
                val selected = body["savedRecipeId"]?.jsonPrimitive?.content?.let { id ->
                    val rights = savedRights ?: throw PlanningServiceFailure(PlanningFailureCode.NOT_CONFIGURED)
                    savedSources.getOrPut(connection) { AccountSavedPlanningSource(environment, connection, actor.principalId, rights) }
                        .load(UUID.fromString(id))
                }
                val evidence = PlanningEvidenceSnapshot.fromAuthoritativeDocument(wire(buildJsonObject {
                    put("version", if (selected == null) 3 else 4); put("preferences", privateInputs.getValue("preferences"))
                    put("pantry", privateInputs.getValue("pantry")); put("baseMeal", JsonNull)
                    put("catalog", if (selected == null) catalogDocument else
                        JsonObject(catalogDocument + ("candidates" to JsonArray(listOf(selected.second)))))
                    selected?.let { put("savedSource", it.first) }
                }))
                current(connection, principal)
                evidence
            }
        }
        // This rejection-only closure belongs to the exact owned attempt, not to a Plan or
        // caller-selected source. It must use the caller's FINAL database time, after its
        // pending receipt/provider waits. No SQL/re-admission occurs after that observation.
        val checkSourceAt: (Instant) -> Unit = { at -> accountPlanningSafe {
            if (!active || Thread.currentThread() !== thread || connection == null || connection.isClosed || connection.autoCommit) denied()
            savedSources[connection]?.checkAt(at)
            derivedReaders[connection]?.checkAt(at)
            derivedAuthorities[connection]?.checkAt(at)
            memoryReads[connection]?.checkAt(connection, at)
            Unit
        } }
        return try {
            val plans = if (journal == null) PlansStore(environment, transactions, authority, policy, cursors)
                else PlansStore(environment, transactions, authority, policy, cursors) { c, principal ->
                    if (principal !== actor) denied()
                    derivedReaders.getOrPut(c) { AccountDerivedPlanReader(c, actor, derived(c)) }
                }
            action(actor, plans, checkSourceAt, ::derived)
        } finally { active = false; derivedAuthorities.values.forEach { it.close() } }
    }
    override fun toString() = "AccountPlanningStore(<redacted>)"
    private fun denied(): Nothing = throw PlanningServiceFailure(PlanningFailureCode.UNAUTHENTICATED)
}

internal fun <T> accountPlanningSafe(action: () -> T): T = try { action() }
    catch (failure: MemoryFailure) { throw PlanningServiceFailure(when (failure.code) {
        MemoryFailureCode.UNAUTHENTICATED -> PlanningFailureCode.UNAUTHENTICATED
        MemoryFailureCode.FORBIDDEN -> PlanningFailureCode.POLICY_BLOCKED
        MemoryFailureCode.NOT_CONFIGURED -> PlanningFailureCode.NOT_CONFIGURED
        else -> PlanningFailureCode.STORAGE_UNAVAILABLE
    }) }
    catch (failure: AccountFailure) { throw PlanningServiceFailure(when (failure.code) {
        AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> PlanningFailureCode.UNAUTHENTICATED
        AccountFailureCode.POLICY_BLOCKED -> PlanningFailureCode.POLICY_BLOCKED
        AccountFailureCode.NOT_CONFIGURED -> PlanningFailureCode.NOT_CONFIGURED
        else -> PlanningFailureCode.STORAGE_UNAVAILABLE
    }) }
    catch (failure: RecipeCatalogFailure) { throw PlanningServiceFailure(if (failure.code == RecipeCatalogFailureCode.NOT_CONFIGURED)
        PlanningFailureCode.NOT_CONFIGURED else PlanningFailureCode.STORAGE_UNAVAILABLE) }
    catch (failure: RecipeSubstitutionFailure) { throw PlanningServiceFailure(if (failure.code == RecipeSubstitutionFailureCode.NOT_CONFIGURED)
        PlanningFailureCode.NOT_CONFIGURED else PlanningFailureCode.STORAGE_UNAVAILABLE) }
    catch (failure: RecipeCopyRightsFailure) { throw PlanningServiceFailure(when (failure.code) {
        RecipeCopyRightsFailureCode.NOT_CONFIGURED -> PlanningFailureCode.NOT_CONFIGURED
        RecipeCopyRightsFailureCode.RECALLED -> PlanningFailureCode.RECIPE_RECALLED
        RecipeCopyRightsFailureCode.NOT_ALLOWED, RecipeCopyRightsFailureCode.AUTHORITY_DENIED, RecipeCopyRightsFailureCode.EXPIRED -> PlanningFailureCode.RECIPE_UNAVAILABLE
        else -> PlanningFailureCode.STORAGE_UNAVAILABLE
    }) }
    catch (failure: SavedRecipeFailure) { throw PlanningServiceFailure(when (failure.code) {
        SavedRecipeFailureCode.RECIPE_RECALLED -> PlanningFailureCode.RECIPE_RECALLED
        SavedRecipeFailureCode.RECIPE_UNAVAILABLE, SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE -> PlanningFailureCode.RECIPE_UNAVAILABLE
        SavedRecipeFailureCode.NOT_CONFIGURED -> PlanningFailureCode.NOT_CONFIGURED
        else -> PlanningFailureCode.STORAGE_UNAVAILABLE
    }) }
    catch (failure: PlanningServiceFailure) { throw failure }
    catch (failure: CommitOutcomeUnknown) { throw failure }
    catch (failure: CancellationException) { throw failure }
    catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
    catch (_: Exception) { throw PlanningServiceFailure(PlanningFailureCode.STORAGE_UNAVAILABLE) }
