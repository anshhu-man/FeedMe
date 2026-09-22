package com.feedme.server.planning

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.FailureReason
import com.feedme.planning.PlanningScanBudget
import com.feedme.server.catalog.*
import com.feedme.server.cooking.AccountMealAccess
import com.feedme.server.db.*
import java.sql.Connection
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.json.*

/** Catalog-origin account simplification/adaptation, in the SAME transaction as real admission.
 * A proposal is a separate immutable Plan: creation never accepts it, cooks it, saves it, or
 * moves the original lineage head. Durable replay does not rescan or spend another quota unit.
 * Saved/social derivation and direct source Make Mine still need separate source authority.
 */
internal class AccountDerivedPlanningStore(private val transactions: PgTransactions,
    private val operational: AccountPlanningPolicy, private val policy: PlanningServicePolicy) {
    private val commands = DurableCommands(transactions)
    private val kernel = DerivedPlanStore(OutboxStore(transactions))

    fun simplify(c: Connection, actor: VerifiedPlanningPrincipal, plans: PlansStore,
        authority: AccountDerivedPlanningAuthority, key: UUID, parentId: UUID,
        ifMatch: String, body: JsonObject): CommandResult = derive(c, actor, plans, authority, key, parentId, ifMatch, body, "simplifyPlan")

    fun adapt(c: Connection, actor: VerifiedPlanningPrincipal, plans: PlansStore,
        authority: AccountDerivedPlanningAuthority, key: UUID, parentId: UUID,
        ifMatch: String, body: JsonObject): CommandResult = derive(c, actor, plans, authority, key, parentId, ifMatch, body, "adaptPlan")

    private fun derive(c: Connection, actor: VerifiedPlanningPrincipal, plans: PlansStore,
        authority: AccountDerivedPlanningAuthority, key: UUID, parentId: UUID,
        ifMatch: String, body: JsonObject, operation: String): CommandResult {
        val command = try { DerivedPlanCommand(actor, operation, key, parentId, ifMatch, WireDocument.parse(body.toString())) }
            catch (_: DerivedPlanMaterialException) { fail(PlanningFailureCode.INPUT_INVALID) }
        var written: DerivedPlanStore.Written? = null
        var pending: DerivedPlanStore.Pending? = null
        val result = commands.executeInTransaction(c, command.identity,
            validatePrincipal = { authority.current(); operational.checkCompatibility(it) },
            authorizeNew = { operational.requireNew(it, actor); authority.current() },
            authorizeReplay = { same, reply ->
                pending = kernel.replay(same, command, reply)
                authority.authorizeStored(requireNotNull(pending).record, selection = true)
            },
            mutate = { same ->
                // This is the actual existing Plan selection authorization, including owner,
                // expiry, current inputs, recorded proof, source lifecycle and scaling.
                plans.lockCookingPlan(same, actor, parentId, CookingPlanUse.NEW_SELECTION)
                val locked = kernel.lockParent(same, command)
                val parent = locked.parent
                val original = json(parent.request)
                if (original.containsKey("savedRecipeId") || original.containsKey("sourcePostId")) fail(PlanningFailureCode.NOT_CONFIGURED)
                if (operation == "simplifyPlan" && canonical(original.getValue("constraints")) != canonical(body.getValue("constraints"))) fail(PlanningFailureCode.INPUTS_CHANGED)
                val effective = WireDocument.parse(JsonObject(original + mapOf(
                    "constraints" to body.getValue("constraints"), "sourceRecipeVersionId" to JsonPrimitive(parent.recipeVersionId.toString())) +
                    if (operation == "adaptPlan") mapOf("intent" to JsonPrimitive("makeMine")) else emptyMap()).toString())
                val inputs = authority.inputs()
                if (original.getValue("preferenceVersion").jsonPrimitive.content.toBigDecimal().stripTrailingZeros().toPlainString() != inputs.preferenceRevision)
                    fail(PlanningFailureCode.PREFERENCE_CHANGED)
                val edges = if (operation == "adaptPlan") authority.substitutionView() else null
                val view = edges?.recipes ?: authority.view()
                val anchor = view.verifyAnchor(view.releaseId, view.revision, view.requestSha256,
                    view.taxonomyRevision, view.taxonomySha256, view.versionCount)
                // Match the currently supported account catalog bound. Exhaustion is a full
                // rollback, never a prefix winner; expanding launch capacity is explicit work.
                fun receipt(): DerivedPlanReceipt {
                    val created = AccountMealAccess.now(same).truncatedTo(ChronoUnit.MILLIS)
                    return DerivedPlanReceipt(UUID.randomUUID(), created, created.plusSeconds(policy.planRetentionSeconds.toLong()))
                }
                val material = try {
                    if (edges == null) {
                        val scan = when (val selected = RecipeSimplificationCatalogScanner(view, authority.policy).scan(effective,
                            inputs.context(), parent.recipeVersionId, body.getValue("simplificationGoal").jsonPrimitive.content,
                            body["allowDifferentMeal"] == JsonPrimitive(true), PlanningScanBudget(128, 128), pageSize = 16)) {
                            is PortResult.Value -> selected.value
                            is PortResult.Failure -> fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
                        }
                        DerivedPlanMaterial.fromSimplification(command, parent, inputs, authority.policy, anchor, scan, receipt())
                    } else {
                        // Every exact pair page counts, including empty/withdrawn pairs. A
                        // budget refusal cannot become a prefix winner or fabricated noMatch.
                        val scan = when (val selected = RecipeSubstitutionCatalogScanner(edges, authority.policy).scan(effective,
                            inputs.context(), RecipeSubstitutionScanBudget(PlanningScanBudget(128, 128), 1024, 1024),
                            pageSize = 16, adaptation = command.body)) {
                            is PortResult.Value -> selected.value
                            is PortResult.Failure -> fail(when (selected.reason) {
                                FailureReason.INVALID_DATA -> PlanningFailureCode.INPUT_INVALID
                                FailureReason.NOT_CONFIGURED -> PlanningFailureCode.NOT_CONFIGURED
                                else -> PlanningFailureCode.RECIPE_UNAVAILABLE
                            })
                        }
                        DerivedPlanMaterial.fromAdaptation(command, parent, inputs, authority.policy, anchor, scan, receipt())
                    }
                }
                    catch (_: DerivedPlanMaterialException) { fail(PlanningFailureCode.INPUTS_CHANGED) }
                written = locked.insert(same, material)
                requireNotNull(written).reply
            })
        if (result is CommandResult.Applied) pending = requireNotNull(written).captureCompleted(c, actor)
        pending?.let {
            authority.authorizeStored(it.record, selection = true)
            it.revalidate(c, actor)
            // Provider locks can wait. Observe time only after all SQL and never re-admit
            // after this final fence; only the outer successful commit releases the reply.
            authority.current()
            val at = AccountMealAccess.now(c)
            it.checkAt(c, actor, at)
            authority.checkAt(at)
        }
        if (pending == null) { authority.current(); authority.checkAt(AccountMealAccess.now(c)) }
        return result
    }

    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonical(it.value)}" }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
            else value.content.toBigDecimal().stripTrailingZeros().toString()
    }
    private fun fail(code: PlanningFailureCode): Nothing = throw PlanningServiceFailure(code)
}
