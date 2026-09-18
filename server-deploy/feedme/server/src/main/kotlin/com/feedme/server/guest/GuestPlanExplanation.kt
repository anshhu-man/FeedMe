package com.feedme.server.guest

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.StoredReply
import com.feedme.server.planning.PlanningCursors
import com.feedme.server.planning.PlanningFailureCode
import com.feedme.server.planning.PlanningServiceFailure
import com.feedme.server.planning.digest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Bounded serialization of a verified, immutable first Plan's stored explanation facts.
 * This is not authentication or a Plan/cooking grant. The actual guest transaction owner
 * verifies ownership, retained evidence and read lifetime before and after this projection.
 * Explanation cursors select an offset only: they do not extend any lifetime, refresh the
 * stored alternative cursor, authorize later selection or replace those current checks.
 */
internal object GuestPlanExplanation {
    private val validator by lazy { ContractBodyValidator.bundled() }

    fun render(environment: String, principalId: UUID, planId: UUID, storedPlan: StoredReply,
        snapshotHash: String, serverTime: Instant, cursors: PlanningCursors, cursor: String?,
        limit: Int): StoredReply = safe {
        current()
        if (limit !in 1..50) fail(PlanningFailureCode.INPUT_INVALID)
        if (!environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) ||
            !snapshotHash.matches(Regex("[0-9a-f]{64}")) ||
            storedPlan.status != 201 || storedPlan.etag != "\"1\"") unavailable()
        val body = storedPlan.body as? JsonObject ?: unavailable()
        val bytes = body.toString().encodeToByteArray(throwOnInvalidSequence = true)
        if (digest(bytes) != snapshotHash ||
            validator.validateResponse("createPlan", 201, bytes, "application/json") != BodyValidationResult.Valid ||
            body["id"] != JsonPrimitive(planId.toString()) || body["version"] != JsonPrimitive(1) ||
            body.containsKey("parentPlanId")) unavailable()
        val reasons = body.getValue("reasons").jsonArray
        if (reasons.size > 128) unavailable()

        // Version, environment, actor kind and both exact owner/resource identities are
        // explicit. The environment grammar cannot inject this binding's delimiters.
        val binding = "feedme.guest.plan.explanation.v2:$environment:guest:$principalId:$planId:$snapshotHash"
        val offset = cursors.offset(binding, cursor)
        if (offset > reasons.size) fail(PlanningFailureCode.CURSOR_INVALID)
        val selected = reasons.drop(offset).take(limit)
        val end = offset + selected.size
        val page = buildJsonObject {
            put("items", JsonArray(selected))
            put("nextCursor", if (end < reasons.size) JsonPrimitive(cursors.explanation(binding, end)) else JsonNull)
            put("serverTime", serverTime.toString())
        }
        val encoded = page.toString().encodeToByteArray(throwOnInvalidSequence = true)
        if (validator.validateResponse("getPlanExplanation", 200, encoded, "application/json") != BodyValidationResult.Valid)
            unavailable()
        val result = StoredReply(200, page)
        current()
        result
    }

    private fun current() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest Plan explanation interrupted")
    }
    private fun fail(code: PlanningFailureCode): Nothing = throw PlanningServiceFailure(code)
    private fun unavailable(): Nothing = fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: PlanningServiceFailure) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { current(); unavailable() }
    override fun toString() = "GuestPlanExplanation(<redacted>)"
}
