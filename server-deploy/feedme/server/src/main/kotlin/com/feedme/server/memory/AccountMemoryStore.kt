package com.feedme.server.memory

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.*
import com.feedme.server.cooking.AccountMealAccess
import com.feedme.server.db.*
import com.feedme.server.identity.*
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Bounded owner-only projection before the canonical read/edit/forget transaction.
 * A remaining dirty batch is explicit PROJECTION_PENDING, never stale successful memory. */
internal class AccountMemoryStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val catalog: RecipeCatalogJournal,
    private val ingredients: IngredientCatalogStore, private val cursors: MemoryCursors,
    val policy: MemoryServicePolicy) {
    init { require(catalog.environment == environment && ingredients.environment == environment) }
    fun listMemories(subject: VerifiedSupabaseSubject, device: UUID, cursor: String? = null, limit: Int = 20): StoredReply {
        prepare(subject, device); return use(subject, device) { c, a, s -> s.listMemories(c, a, cursor, limit) }
    }
    fun getMemory(subject: VerifiedSupabaseSubject, device: UUID, id: UUID): StoredReply {
        prepare(subject, device); return use(subject, device) { c, a, s -> s.getMemory(c, a, id) }
    }
    fun updateMemory(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID, ifMatch: String, body: JsonObject): CommandResult {
        prepare(subject, device); return use(subject, device) { c, a, s -> s.updateMemory(c, a, key, id, ifMatch, body) }
    }
    fun deleteMemory(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID, ifMatch: String): CommandResult {
        prepare(subject, device); return use(subject, device) { c, a, s -> s.deleteMemory(c, a, key, id, ifMatch) }
    }
    internal fun isBoundTo(env: String, tx: PgTransactions, accountStore: AccountProfileStore): Boolean =
        environment == env && transactions === tx && accounts === accountStore

    /** Planning borrows this transaction; it never runs projection or opens a second one.
     * A delayed/incomplete read contributes nothing rather than ranking a stale/prefix set.
     * The returned rejection-only proof must survive until the plan's final transaction fence. */
    internal fun lockPlanningMemories(c: Connection, subject: VerifiedSupabaseSubject, device: UUID): PlanningRead = safe {
        val account = AccountMealAccess(environment, c, accounts, subject, device)
        val access = AccountMemoryAccess(environment, c, account, device, catalog, ingredients)
        val pending = try {
            MemoryStore(environment, transactions, access, cursors, policy).listMemories(c, access.principal, limit = 50)
        } catch (failure: MemoryFailure) {
            if (failure.code !in setOf(MemoryFailureCode.PROJECTION_PENDING, MemoryFailureCode.RESPONSE_TOO_LARGE)) throw failure
            null
        }
        val page = pending?.result?.body?.jsonObject
        val complete = page != null && (page["nextCursor"] == null || page["nextCursor"] == JsonNull)
        val rows = if (complete) page!!.getValue("items").jsonArray.map { it.jsonObject }.filter {
            it.getValue("enabled").jsonPrimitive.boolean && it.getValue("value").jsonPrimitive.content != "neutral"
        } else emptyList()
        val result = PlanningRead(rows, { actual ->
            if (actual !== c) fail(MemoryFailureCode.UNAUTHENTICATED)
            account.current(actual); pending?.revalidate(actual, access.principal); access.revalidate(); account.current(actual)
        }, { actual, at ->
            if (actual !== c) fail(MemoryFailureCode.UNAUTHENTICATED)
            pending?.checkAt(actual, access.principal, at); account.checkAt(actual, at)
        })
        result.revalidate(c); result.checkAt(c, AccountMealAccess.now(c)); result
    }

    internal class PlanningRead internal constructor(memories: List<JsonObject>,
        private val check: (Connection) -> Unit, private val time: (Connection, Instant) -> Unit) {
        val memories = memories.toList()
        fun revalidate(c: Connection) = check(c)
        fun checkAt(c: Connection, at: Instant) = time(c, at)
        override fun toString() = "PlanningMemoryRead(<redacted>)"
    }
    private fun prepare(subject: VerifiedSupabaseSubject, device: UUID) {
        if (use(subject, device) { c, a, s -> s.reconcile(c, a) }.pending) fail(MemoryFailureCode.PROJECTION_PENDING)
    }
    private fun <T> use(subject: VerifiedSupabaseSubject, device: UUID,
        action: (Connection, VerifiedMemoryPrincipal, MemoryStore) -> MemoryStore.Pending<T>): T = safe {
        transactions.run { c ->
            val account = AccountMealAccess(environment, c, accounts, subject, device)
            val access = AccountMemoryAccess(environment, c, account, device, catalog, ingredients)
            val pending = action(c, access.principal, MemoryStore(environment, transactions, access, cursors, policy))
            account.current(c); pending.revalidate(c, access.principal); access.revalidate(); account.current(c)
            val at = AccountMealAccess.now(c)
            pending.checkAt(c, access.principal, at); account.checkAt(c, at)
            pending.result
        }
    }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (f: MemoryFailure) { throw f }
        catch (f: AccountFailure) { fail(when (f.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> MemoryFailureCode.UNAUTHENTICATED
            AccountFailureCode.POLICY_BLOCKED -> MemoryFailureCode.FORBIDDEN
            AccountFailureCode.NOT_CONFIGURED -> MemoryFailureCode.NOT_CONFIGURED
            else -> MemoryFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (f: FeedbackFailure) { fail(when (f.code) {
            FeedbackFailureCode.NOT_CONFIGURED -> MemoryFailureCode.NOT_CONFIGURED
            FeedbackFailureCode.TARGET_UNAVAILABLE -> MemoryFailureCode.CONTEXT_UNAVAILABLE
            else -> MemoryFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (f: RecipeCatalogFailure) { fail(if (f.code == RecipeCatalogFailureCode.NOT_CONFIGURED) MemoryFailureCode.NOT_CONFIGURED else MemoryFailureCode.STORAGE_UNAVAILABLE) }
        catch (f: IngredientCatalogFailure) { fail(if (f.code == IngredientCatalogFailureCode.NOT_CONFIGURED) MemoryFailureCode.NOT_CONFIGURED else MemoryFailureCode.STORAGE_UNAVAILABLE) }
        catch (f: CommitOutcomeUnknown) { throw f }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (f: SQLException) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Account memory interrupted")
            if (f.sqlState in setOf("40001", "40P01")) throw f; fail(MemoryFailureCode.STORAGE_UNAVAILABLE) }
        catch (_: Exception) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Account memory interrupted"); fail(MemoryFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: MemoryFailureCode): Nothing = throw MemoryFailure(code)
    override fun toString() = "AccountMemoryStore(<redacted>)"
}
