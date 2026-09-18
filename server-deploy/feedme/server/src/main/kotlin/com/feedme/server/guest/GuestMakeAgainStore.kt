package com.feedme.server.guest

import com.feedme.server.catalog.*
import com.feedme.server.cooking.*
import com.feedme.server.db.*
import com.feedme.server.memory.*
import com.feedme.server.planning.PlanningFailureCode
import com.feedme.server.planning.PlanningServiceFailure
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Per-attempt proofs, made only by the actual guest owner. No public authority adapter. */
internal class GuestMakeAgainAccess(val saving: GuestSavedRecipeAccess, val feedback: GuestFeedbackAccess,
    val cooking: GuestCookingPlanAccess?) {
    fun revalidate() { cooking?.revalidate(); saving.revalidate(); feedback.revalidate() }
}

/** The two existing explicit Make Again commands, composed in ONE actual guest transaction.
 * Both use positive copy rights, the exact original parent request and an explicit private
 * feedback child. Random child command IDs are persisted, never derived from or confused
 * with independently submitted parent keys. A replay only checks the original effects;
 * it cannot recreate feedback or a removed save. No HTTP/mobile activation, projection,
 * social sharing, additional response fields or pretend two-request Undo is supplied. */
internal class GuestMakeAgainStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val preparations: GuestPlanningStore,
    private val rights: RecipeCopyRightsStore,
    private val ingredients: IngredientCatalogStore,
    private val cursors: SavedRecipeCursors,
    private val savingPolicy: SavedRecipeServicePolicy,
    private val cookingPolicy: CookingServicePolicy,
    private val feedbackPolicy: FeedbackServicePolicy,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(preparations.isBoundTo(environment, transactions) && preparations.hasSavingRightsOwner(rights))
        require(ingredients.environment == environment)
    }

    fun isSavingBoundTo(env: String, tx: PgTransactions, owner: GuestPlanningStore,
        copyRights: RecipeCopyRightsStore, keys: SavedRecipeCursors, policy: SavedRecipeServicePolicy): Boolean =
        environment == env && transactions === tx && preparations === owner && rights === copyRights &&
            cursors === keys && savingPolicy === policy
    fun isCookingBoundTo(env: String, tx: PgTransactions, owner: GuestPlanningStore, policy: CookingServicePolicy): Boolean =
        environment == env && transactions === tx && preparations === owner && cookingPolicy === policy

    fun saveRecipe(token: String, key: UUID, body: JsonObject): CommandResult {
        if (!explicit(body, "markMakeAgain")) return GuestSavedRecipeStore(environment, transactions, preparations,
            rights, cursors, savingPolicy).saveRecipe(token, key, body)
        return use(token, "saveRecipe") { attempt ->
            val effect = object : SavedRecipeMakeAgainEffect {
                override fun applied(connection: Connection, actor: VerifiedSavedRecipePrincipal, key: UUID,
                    input: JsonObject, saved: JsonObject, generation: Long, created: Boolean) {
                    attempt.access.saving.requireBound(connection, actor)
                    val parent = attempt.identity("saveRecipe", key, input)
                    attempt.record(parent, key, input, saved, generation, created, saveFeedback(input), null)
                }
                override fun replayed(connection: Connection, actor: VerifiedSavedRecipePrincipal, key: UUID,
                    input: JsonObject, saved: JsonObject, generation: Long) {
                    attempt.access.saving.requireBound(connection, actor)
                    val parent = attempt.identity("saveRecipe", key, input)
                    val original = attempt.ledger.replay(parent, input, saveFeedback(input), null)
                    if (original.savedId != id(saved, "id") || original.generation != generation || original.saveKey != key)
                        fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
                }
            }
            attempt.saves += attempt.saving.saveRecipe(attempt.connection, attempt.access.saving.principal, key, body, effect)
            attempt.saves.single().result
        }
    }

    fun completeCookSession(token: String, key: UUID, sessionId: UUID, body: JsonObject): CommandResult {
        if (!explicit(body, "makeAgain")) return GuestCookingStore(environment, transactions, preparations, cookingPolicy)
            .completeCookSession(token, key, sessionId, body)
        return use(token, "completeCookSession") { attempt ->
            val access = requireNotNull(attempt.access.cooking)
            val authority = object : CookingAuthority {
                override fun lockPrincipal(connection: Connection, principal: VerifiedCookingPrincipal) = access.requireBound(connection, principal)
                override fun requireNewCookingEnabled(connection: Connection, principal: VerifiedCookingPrincipal): Unit =
                    throw CookingFailure(CookingFailureCode.NOT_CONFIGURED)
                override fun validatePersonalNotes(connection: Connection, principal: VerifiedCookingPrincipal, notes: JsonArray): Unit =
                    throw CookingFailure(CookingFailureCode.NOT_CONFIGURED)
            }
            val kernel = CookingStore(environment, transactions, authority, access, cookingPolicy)
            val effect = object : CookingMakeAgainEffect {
                override fun applied(connection: Connection, actor: VerifiedCookingPrincipal, key: UUID,
                    input: JsonObject, session: JsonObject) {
                    access.requireBound(connection, actor)
                    val parent = attempt.identity("completeCookSession", key, input, id(session, "id"))
                    val saveInput = cookSave(session)
                    val childKey = UUID.randomUUID()
                    val childEffect = object : SavedRecipeMakeAgainEffect {
                        override fun applied(connection: Connection, actor: VerifiedSavedRecipePrincipal, key: UUID,
                            input: JsonObject, saved: JsonObject, generation: Long, created: Boolean) {
                            attempt.access.saving.requireBound(connection, actor)
                            if (key != childKey || input != saveInput) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
                            attempt.record(parent, key, input, saved, generation, created, cookFeedback(session), id(session, "id"))
                        }
                        override fun replayed(connection: Connection, actor: VerifiedSavedRecipePrincipal, key: UUID,
                            input: JsonObject, saved: JsonObject, generation: Long): Unit = fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
                    }
                    val child = attempt.saving.saveRecipe(connection, attempt.access.saving.principal, childKey, saveInput, childEffect)
                    if (child.result !is CommandResult.Applied) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
                    attempt.saves += child
                }
                override fun replayed(connection: Connection, actor: VerifiedCookingPrincipal, key: UUID,
                    input: JsonObject, session: JsonObject) {
                    access.requireBound(connection, actor)
                    val parent = attempt.identity("completeCookSession", key, input, id(session, "id"))
                    val original = attempt.ledger.replay(parent, cookSave(session), cookFeedback(session), id(session, "id"))
                    // Existing positive copy rights, not a new-copy grant or original Plan TTL.
                    attempt.reads += attempt.saving.getSavedRecipe(connection, attempt.access.saving.principal, original.savedId)
                }
            }
            kernel.completeCookSession(attempt.connection, access.principal, key, sessionId, body, effect)
                .also { attempt.cook = it }.result
        }
    }

    private fun use(token: String, operation: String, action: (Attempt) -> CommandResult): CommandResult = safe {
        preparations.withMakeAgain(token, operation, rights, ingredients, consume = { c, access ->
            Attempt(c, access).also { attempt ->
                attempt.result = action(attempt)
                // Refused parent receipts never invoke effect hooks or fabricate children.
                if (attempt.result is CommandResult.Applied || attempt.result is CommandResult.Replayed) attempt.ledger.seal()
            }
        }, complete = { c, _, attempt -> attempt.revalidate(c) },
            completeAt = { c, _, attempt, at -> attempt.checkAt(c, at) }).result
    }

    private inner class Attempt(val connection: Connection, val access: GuestMakeAgainAccess) {
        val saving = SavedRecipeStore(environment, transactions, access.saving, cursors, savingPolicy)
        val feedback = FeedbackStore(environment, transactions, access.feedback, feedbackPolicy)
        val ledger = GuestMakeAgainActionStore(environment, connection, access.feedback)
        val saves = mutableListOf<SavedRecipeStore.Pending<CommandResult>>()
        val reads = mutableListOf<SavedRecipeStore.Pending<StoredReply>>()
        val signals = mutableListOf<FeedbackStore.Pending<CommandResult>>()
        var cook: CookingStore.Pending<CommandResult>? = null
        lateinit var result: CommandResult
        fun identity(operation: String, key: UUID, input: JsonObject, cookId: UUID? = null): CommandIdentity =
            CommandIdentity(PrincipalScope(environment, CommandActor.GUEST, access.feedback.principal.principalId), operation,
                key, cookId?.let { mapOf("sessionId" to it.toString()) } ?: emptyMap(), body = input)
        fun record(parent: CommandIdentity, saveKey: UUID, saveInput: JsonObject, saved: JsonObject,
            generation: Long, created: Boolean, input: JsonObject, cookId: UUID?) {
            if (signals.isNotEmpty()) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            val feedbackKey = UUID.randomUUID()
            val signal = feedback.createFeedback(connection, access.feedback.principal, feedbackKey, input)
            val applied = signal.result as? CommandResult.Applied ?: fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            signals += signal
            ledger.record(parent, saveKey, saveInput, saved, generation, created, feedbackKey,
                applied.reply.body!!.jsonObject, input, cookId)
        }
        fun revalidate(c: Connection) {
            cook?.revalidate(c, requireNotNull(access.cooking).principal)
            saves.forEach { it.revalidate(c, access.saving.principal) }
            reads.forEach { it.revalidate(c, access.saving.principal) }
            signals.forEach { it.revalidate(c, access.feedback.principal) }
            if (result is CommandResult.Applied || result is CommandResult.Replayed) ledger.revalidate()
        }
        fun checkAt(c: Connection, at: Instant) {
            cook?.checkAt(c, requireNotNull(access.cooking).principal, at)
            saves.forEach { it.checkAt(c, access.saving.principal, at) }
            reads.forEach { it.checkAt(c, access.saving.principal, at) }
            signals.forEach { it.checkAt(c, access.feedback.principal, at) }
            if (result is CommandResult.Applied || result is CommandResult.Replayed) ledger.checkAt(c, at)
        }
    }

    private fun saveFeedback(input: JsonObject) = buildJsonObject {
        put("target", buildJsonObject {
            val plan = input["planId"]
            put("kind", if (plan != null) "plan" else "recipeVersion")
            put("resourceId", plan ?: input.getValue("recipeVersionId"))
        })
        put("makeAgain", true)
    }
    private fun cookFeedback(session: JsonObject) = buildJsonObject {
        put("cookSessionId", session.getValue("id")); put("makeAgain", true)
    }
    private fun cookSave(session: JsonObject) = buildJsonObject {
        put("planId", session.getValue("planId")); put("markMakeAgain", true)
    }
    private fun explicit(body: JsonObject, field: String): Boolean = (body[field] as? JsonPrimitive)?.booleanOrNull == true
    private fun id(body: JsonObject, field: String): UUID = UUID.fromString(body.getValue(field).jsonPrimitive.content)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: GuestSessionFailure) { throw failure }
        catch (failure: FeedbackFailure) { throw failure }
        catch (failure: SavedRecipeFailure) { throw failure }
        catch (failure: CookingFailure) { throw failure }
        catch (failure: PlanningServiceFailure) { if (failure.code == PlanningFailureCode.NOT_CONFIGURED)
            fail(FeedbackFailureCode.NOT_CONFIGURED) else throw failure }
        catch (failure: RecipeCatalogFailure) { fail(if (failure.code == RecipeCatalogFailureCode.NOT_CONFIGURED)
            FeedbackFailureCode.NOT_CONFIGURED else FeedbackFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: IngredientCatalogFailure) { fail(if (failure.code == IngredientCatalogFailureCode.NOT_CONFIGURED)
            FeedbackFailureCode.NOT_CONFIGURED else FeedbackFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (failure: SQLException) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Make Again interrupted")
            if (failure.sqlState in setOf("40001", "40P01")) throw failure
            fail(FeedbackFailureCode.STORAGE_UNAVAILABLE) }
        catch (_: Exception) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Make Again interrupted")
            fail(FeedbackFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: FeedbackFailureCode): Nothing = throw FeedbackFailure(code)
    override fun toString() = "GuestMakeAgainStore(<redacted>)"
}
