package com.feedme.planning

import com.feedme.contracts.*
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import kotlinx.serialization.json.*

/** Explicit receipt metadata from the owning server transaction, not an ID/cursor issuer. */
class PlanningReceipt(val planId: String, val version: String, val createdAt: String, val updatedAt: String,
    val parentPlanId: String? = null, val nextAlternativeCursor: String? = null) {
    override fun toString() = "PlanningReceipt(<redacted>)"
}

/** Canonical Plan output only: F01's pinned materialized variant retains reviewed source lineage;
 * scaled recipeSnapshot is NOT a published catalog version and must never be catalog-upserted.
 * Ownership/idempotency, approved localization, lifecycle freshness,
 * lineage/cursor signing and persistence still belong to the eventual authorized server adapter.
 */
class CanonicalPlanningAdapter(private val validator: CanonicalBodyValidator = CanonicalBodyValidator.bundled()) {
    fun materialize(decision: PlanningDecision, receipt: PlanningReceipt): PortResult<PlanWire> {
        return try {
        val mode = decision.mode
        if (decision.status == PlanningStatus.READY && (mode == null || decision.recipe == null))
            return PortResult.Failure(FailureReason.INVALID_DATA)
        if ((decision.status != PlanningStatus.READY && receipt.nextAlternativeCursor != null) ||
            (receipt.nextAlternativeCursor?.length ?: 0) > 4096) return PortResult.Failure(FailureReason.INVALID_DATA)
        val document = buildJsonObject {
            put("id", receipt.planId); put("version", Json.parseToJsonElement(receipt.version))
            put("createdAt", receipt.createdAt); put("updatedAt", receipt.updatedAt)
            receipt.parentPlanId?.let { put("parentPlanId", it) }
            mode?.let { put("mode", it) }
            put("status", when (decision.status) { PlanningStatus.READY -> "ready"; PlanningStatus.NEEDS_CONFIRMATION -> "needsConfirmation"; PlanningStatus.NO_MATCH -> "noMatch" })
            put("constraints", json(decision.constraints))
            decision.recipe?.let { put("recipeVersionId", it.id.value); put("recipeSnapshot", json(it.document)) }
            put("missingIngredients", JsonArray(decision.missingIngredients.map(::json)))
            put("changes", buildJsonArray { if (decision.scaled && decision.status == PlanningStatus.READY)
                add(buildJsonObject { put("explanation", "Quantities scaled exactly within the reviewed serving range; reviewed steps and effort estimates retained.") }) })
            put("reasons", buildJsonArray { decision.facts.forEach { fact -> add(buildJsonObject { put("code", fact.code); put("label", fact.label) }) } })
            put("catalogRevision", decision.catalogRevision)
            put("nextAlternativeCursor", receipt.nextAlternativeCursor?.let(::JsonPrimitive) ?: JsonNull)
        }
        val wire = WireDocument.parse(document.toString())
        if (validator.validateSchema("Plan", wire.encodeUtf8()) != ContractValidationResult.Valid) PortResult.Failure(FailureReason.INVALID_DATA)
        else PortResult.Value(PlanWire.from(wire))
        } catch (_: Exception) { PortResult.Failure(FailureReason.INVALID_DATA) }
    }

    private fun json(document: WireDocument) = Json.parseToJsonElement(document.encodeUtf8().decodeToString())
}
