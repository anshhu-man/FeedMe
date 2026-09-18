package com.feedme.server.guest

import com.feedme.server.catalog.*
import com.feedme.server.db.*
import com.feedme.server.memory.*
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Owner-only memory inspection/control, using the four existing canonical operations.
 * A bounded projection transaction first consumes already committed private feedback.
 * It commits independently, like a read-model worker; if more work remains the canonical
 * request returns PROJECTION_PENDING, and a retry drains another bounded batch. The
 * subsequent read/edit/forget has a fresh actual guest admission and refuses a dirty head.
 * It never acknowledges stale ranking effects, truncates a source scan into clean status,
 * invents a transport field, enables personalization or starts an account/mobile session. */
internal class GuestMemoryStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val sessions: GuestSessionStore,
    private val catalog: RecipeCatalogJournal,
    private val ingredients: IngredientCatalogStore,
    private val cursors: MemoryCursors,
    val policy: MemoryServicePolicy,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(sessions.isBoundTo(environment, transactions))
        require(catalog.environment == environment && ingredients.environment == environment)
    }
    fun listMemories(token: String, cursor: String? = null, limit: Int = 20): StoredReply {
        prepare(token, "listMemories")
        return use(token, "listMemories") { c, actor, store -> store.listMemories(c, actor, cursor, limit) }
    }
    fun getMemory(token: String, id: UUID): StoredReply {
        prepare(token, "getMemory")
        return use(token, "getMemory") { c, actor, store -> store.getMemory(c, actor, id) }
    }
    fun updateMemory(token: String, key: UUID, id: UUID, ifMatch: String, body: JsonObject): CommandResult {
        prepare(token, "updateMemory")
        return use(token, "updateMemory") { c, actor, store -> store.updateMemory(c, actor, key, id, ifMatch, body) }
    }
    fun deleteMemory(token: String, key: UUID, id: UUID, ifMatch: String): CommandResult {
        prepare(token, "deleteMemory")
        return use(token, "deleteMemory") { c, actor, store -> store.deleteMemory(c, actor, key, id, ifMatch) }
    }
    /** Internal owner-bound maintenance, no invented HTTP endpoint or event-payload owner. */
    fun reconcile(token: String): MemoryProjectionProgress = project(token, "listMemories")
    fun requestRebuild(token: String): MemoryProjectionProgress =
        use(token, "listMemories") { c, actor, store -> store.requestRebuild(c, actor) }
    private fun prepare(token: String, operation: String) {
        if (project(token, operation).pending) throw MemoryFailure(MemoryFailureCode.PROJECTION_PENDING)
    }
    private fun project(token: String, operation: String): MemoryProjectionProgress =
        use(token, operation) { c, actor, store -> store.reconcile(c, actor) }

    private fun <T> use(token: String, operation: String,
        action: (Connection, VerifiedMemoryPrincipal, MemoryStore) -> MemoryStore.Pending<T>): T = safe {
        require(operation in setOf("listMemories", "getMemory", "updateMemory", "deleteMemory"))
        sessions.withCurrentCompletion(token, operation, action = { c, actual -> safe {
            GuestFeedbackAccess.compatibility(c, 1, "durable_platform")
            GuestFeedbackAccess.compatibility(c, 22, "private_feedback")
            GuestFeedbackAccess.compatibility(c, 24, "preference_memories")
            val access = GuestMemoryAccess(environment, c, actual, catalog, ingredients)
            val store = MemoryStore(environment, transactions, access, cursors, policy)
            Attempt(access, action(c, access.principal, store)).also { access.revalidate() }
        } }, complete = { c, _, attempt -> safe {
            attempt.access.revalidate()
            attempt.pending.revalidate(c, attempt.access.principal)
            attempt.access.revalidate()
        } }, completeAt = { c, _, attempt, at -> safe {
            // No SQL/source/identity getter may follow this actual last accepted DB time.
            attempt.pending.checkAt(c, attempt.access.principal, at)
        } }).pending.result
    }
    private class Attempt<T>(val access: GuestMemoryAccess, val pending: MemoryStore.Pending<T>)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: GuestSessionFailure) { throw failure }
        catch (failure: MemoryFailure) { throw failure }
        catch (failure: FeedbackFailure) { throw MemoryFailure(if (failure.code == FeedbackFailureCode.NOT_CONFIGURED)
            MemoryFailureCode.NOT_CONFIGURED else MemoryFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: RecipeCatalogFailure) { throw MemoryFailure(if (failure.code == RecipeCatalogFailureCode.NOT_CONFIGURED)
            MemoryFailureCode.NOT_CONFIGURED else MemoryFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: IngredientCatalogFailure) { throw MemoryFailure(if (failure.code == IngredientCatalogFailureCode.NOT_CONFIGURED)
            MemoryFailureCode.NOT_CONFIGURED else MemoryFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (failure: SQLException) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest memory interrupted")
            if (failure.sqlState in setOf("40001", "40P01")) throw failure
            throw MemoryFailure(MemoryFailureCode.STORAGE_UNAVAILABLE)
        }
        catch (_: Exception) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest memory interrupted")
            throw MemoryFailure(MemoryFailureCode.STORAGE_UNAVAILABLE)
        }
    override fun toString() = "GuestMemoryStore(<redacted>)"
}
