package com.feedme.server.guest

import com.feedme.planning.PlanningPolicy
import com.feedme.planning.PlanningScanBudget
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Explicit local preparation policy, never deployment approval. No configured default is
 * supplied. A committed complete preparation (ready, confirmation or no-match) consumes one
 * create allowance on its UTC admission day. Reads/replays do not; failed scans roll back.
 * D6 must reuse that reservation, NOT charge a second unit when materializing its Plan.
 * Alternative accounting is not implemented or advertised by this create-only component. */
internal enum class GuestPlanWindow { UTC_ADMISSION_DAY }
internal enum class GuestPlanCharge { EVERY_COMMITTED_PREPARATION }
internal class GuestPlanningPolicy(
    val revision: String,
    val ranking: PlanningPolicy,
    val retentionSeconds: Int,
    val cursorLifetimeSeconds: Int,
    val budget: PlanningScanBudget,
    val pageSize: Int,
    val window: GuestPlanWindow,
    val charge: GuestPlanCharge,
) {
    val bindingSha256: String
    init {
        require(revision.isNotBlank() && revision.length <= 128 && revision.none(Char::isISOControl))
        require(ranking.version.isNotBlank() && ranking.version.length <= 128 && ranking.version.none(Char::isISOControl))
        revision.encodeToByteArray(throwOnInvalidSequence = true)
        ranking.version.encodeToByteArray(throwOnInvalidSequence = true)
        require(retentionSeconds in 60..2592000 && cursorLifetimeSeconds in 1..600 && cursorLifetimeSeconds <= retentionSeconds)
        require(budget.maxCandidates >= 0 && budget.maxPages >= 1 && pageSize in 1..32)
        val material = buildJsonArray {
            add("feedme.guest-planning-policy.v1"); add(revision); add(ranking.version)
            add(ranking.heatEnabled); add(ranking.improveEnabled); add(ranking.relatedTasteExplicitlyRequested)
            add(retentionSeconds); add(cursorLifetimeSeconds); add(budget.maxCandidates.toString())
            add(budget.maxPages.toString()); add(pageSize); add(window.name); add(charge.name)
        }.toString().encodeToByteArray(throwOnInvalidSequence = true)
        bindingSha256 = MessageDigest.getInstance("SHA-256").digest(material).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    override fun toString() = "GuestPlanningPolicy(<redacted>)"
}
