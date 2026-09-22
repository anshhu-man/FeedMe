package com.feedme.server.social.posts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class AccountPostReactionPolicy(val enabled: Boolean, val maxResponseBytes: Int) {
    init { require(maxResponseBytes in 4096..262144) }
    override fun toString() = "AccountPostReactionPolicy(<redacted>)"
}
enum class PostReactionFailureCode(val status: Int) {
    INPUT_INVALID(422), REACTION_UNSUPPORTED(422), UNAUTHENTICATED(401), POST_UNAVAILABLE(404),
    REACTION_UNAVAILABLE(404), VERSION_CONFLICT(412), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
class PostReactionFailure(val code: PostReactionFailureCode) : RuntimeException("Post reaction unavailable: ${code.name}")

internal class PostReactionProjection(val canSet: Boolean, val own: JsonObject?, val counts: JsonArray,
    val actors: JsonArray?, val hasMore: Boolean?) {
    override fun toString() = "PostReactionProjection(<redacted>)"
}
internal val POST_REACTION_KINDS = listOf("heart", "looksDoable", "makingThis", "yum")
