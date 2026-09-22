package com.feedme.server.social.posts

import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** Explicitly configured narrow Plate placement, not generic PostPatch editing. */
class AccountPostPlacementPolicy(val enabled: Boolean, val maxResponseBytes: Int) {
    init { require(maxResponseBytes in 4_096..262_144) }
    override fun toString() = "AccountPostPlacementPolicy(<redacted>)"
}
enum class PostPlacementFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), POST_UNAVAILABLE(404), VERSION_CONFLICT(412),
    NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
class PostPlacementFailure(val code: PostPlacementFailureCode) : RuntimeException("Post placement unavailable: ${code.name}")

/** Same-transaction current content proof. The receipt projection confers no new read,
 * media, recipe or audience capability, including when the last placement is removed. */
internal class PostPlacementObservation(val material: PostReadMaterial, val receipt: JsonObject,
    val validUntil: Instant) {
    override fun toString() = "PostPlacementObservation(<redacted>)"
}
