package com.feedme.server.guest

import com.feedme.core.ports.PortResult
import com.feedme.planning.CanonicalPlanningAdapter
import com.feedme.planning.PlanningDecision
import com.feedme.planning.PlanningReceipt
import com.feedme.planning.PlanningStatus
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.StoredReply
import com.feedme.server.planning.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Exact first-Plan serialization, not authentication, currentness, a cursor issuer or a copy
 * grant. The actual guest owner supplies its genuine verified scan decision and transaction's
 * receipt values, persists these bytes atomically and revalidates before commit/disclosure.
 * Recreating these bytes after restart never replaces verification of the stored original.
 */
internal object GuestPlanMaterializer {
    private val adapter = CanonicalPlanningAdapter()
    private val validator by lazy { ContractBodyValidator.bundled() }

    fun first(decision: PlanningDecision, header: PlanningManifestHeaderV2,
        retainedFirstDecision: PlanningManifestFirstDecision, eligibleCount: Long,
        currentEligibilityCatalogRevision: String, planId: UUID, createdAt: Instant,
        nextAlternativeCursor: String?): GuestMaterializedPlan = safe {
        current()
        val actual = PlanningManifestFirstDecision.fromDecision(decision)
        if (!actual.copyForStorage().encodeUtf8().contentEquals(retainedFirstDecision.copyForStorage().encodeUtf8())) unavailable()
        val anchor = json(header.catalogAnchorForStorage())
        val historicalRevision = revision(anchor.text("revision"))
        val eligibilityRevision = revision(currentEligibilityCatalogRevision)
        if (header.actorKind != "guest" || eligibilityRevision < historicalRevision ||
            decision.catalogRevision != anchor.text("revision") ||
            decision.eligibilityCatalogRevision != anchor.text("revision") ||
            decision.taxonomyRevision != anchor.text("taxonomyRevision") ||
            decision.preferenceVersion != header.inputs.preferenceRevision || decision.policyVersion != header.policy.version)
            unavailable()
        val count = anchor.text("versionCount").toLong()
        if (eligibleCount !in 0..count || (decision.status == PlanningStatus.READY) != (eligibleCount > 0)) unavailable()
        val needsCursor = decision.status == PlanningStatus.READY && eligibleCount > 1
        if ((nextAlternativeCursor != null) != needsCursor ||
            nextAlternativeCursor != null && !nextAlternativeCursor.matches(Regex("[A-Za-z0-9_-]{43}"))) unavailable()
        // Receipt time is historical, not a clock/TTL refresh. Millisecond precision avoids
        // silently rounding timestamp projections when these bytes are persisted to SQL.
        if (createdAt.nano % 1_000_000 != 0 || createdAt < header.createdAt ||
            !header.expiresAt.isAfter(createdAt) || !header.cursorExpiresAt.isAfter(createdAt)) unavailable()

        val plan = when (val materialized = adapter.materialize(decision,
            PlanningReceipt(planId.toString(), "1", createdAt.toString(), createdAt.toString(),
                parentPlanId = null, nextAlternativeCursor = nextAlternativeCursor))) {
            is PortResult.Value -> materialized.value
            is PortResult.Failure -> unavailable()
        }
        val snapshotBytes = plan.document.encodeUtf8()
        if (snapshotBytes.size > 262_144 ||
            validator.validateResponse("createPlan", 201, snapshotBytes, "application/json") != BodyValidationResult.Valid)
            unavailable()
        val snapshotText = snapshotBytes.decodeToString(throwOnInvalidSequence = true)
        val snapshotHash = digest(snapshotBytes)
        val body = json(plan.document)
        val proof = buildJsonObject {
            put("version", 2); put("manifestId", header.manifestId.toString()); put("headerSha256", header.sha256)
            put("planId", planId.toString()); put("snapshotHash", snapshotHash)
            put("requestHash", digest(header.request.encodeUtf8()))
            put("inputsHash", digest(header.inputs.copyForStorage().encodeUtf8()))
            put("firstDecisionSha256", retainedFirstDecision.sha256); put("eligibleCount", eligibleCount.toString())
            put("catalogRevision", decision.catalogRevision)
            put("historicalEligibilityCatalogRevision", decision.eligibilityCatalogRevision)
            put("eligibilityCatalogRevision", currentEligibilityCatalogRevision)
            put("preferenceVersion", decision.preferenceVersion); put("pantryRevision", header.inputs.pantryRevision)
            put("taxonomyRevision", decision.taxonomyRevision); put("rankingVersion", decision.policyVersion)
            put("scaled", decision.scaled)
            put("issues", JsonArray(decision.issues.sortedBy { it.ordinal }.map { JsonPrimitive(it.name) }))
            put("facts", body.getValue("reasons"))
        }
        val proofBytes = proof.toString().encodeToByteArray(throwOnInvalidSequence = true)
        if (proofBytes.size > 32_768) unavailable()
        val reply = StoredReply(201, body, "\"1\"")
        if (reply.body.toString() != snapshotText) unavailable()
        current()
        GuestMaterializedPlan(reply, snapshotText, snapshotHash, proofBytes.decodeToString(), digest(proofBytes),
            decision.recipe?.id?.value?.let(UUID::fromString), body.text("status"))
    }

    private fun revision(value: String): Long {
        if (!value.matches(Regex("[1-9][0-9]{0,18}"))) unavailable()
        return value.toLong()
    }
    private fun current() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest Plan materialization interrupted")
    }
    private fun unavailable(): Nothing = throw PlanningServiceFailure(PlanningFailureCode.STORAGE_UNAVAILABLE)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: PlanningServiceFailure) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { current(); unavailable() }
    override fun toString() = "GuestPlanMaterializer(<redacted>)"
}

/** Immutable storage material only. These hashes identify bytes; none authorizes a read,
 * new cooking selection, existing pin or saved copy. No private material enters diagnostics. */
internal class GuestMaterializedPlan internal constructor(val reply: StoredReply,
    val snapshotText: String, val snapshotHash: String, val proofText: String, val proofHash: String,
    val recipeVersionId: UUID?, val status: String) {
    override fun toString() = "GuestMaterializedPlan(<redacted>)"
}
