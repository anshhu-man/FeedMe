package com.feedme.server.planning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** EXISTING_PIN is backed by an actual owned unexpired cooking row, not the enum as authority. */
enum class CookingPlanUse { NEW_SELECTION, EXISTING_PIN }

/** Exact immutable database material. Not a signed offline manifest, lease or provider grant. */
class CookingPlanSnapshot internal constructor(val snapshotText: String, val snapshotHash: String,
    val proofHash: String, val evidenceHash: String) {
    val document: JsonObject get() = Json.parseToJsonElement(snapshotText).jsonObject
    override fun toString() = "CookingPlanSnapshot(<redacted>)"
}
