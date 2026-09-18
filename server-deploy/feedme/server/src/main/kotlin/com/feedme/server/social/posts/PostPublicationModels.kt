package com.feedme.server.social.posts

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Explicit bounded success profile. No provider, content quota, audience or consent default. */
class PostPublicationPolicy(val maxResponseBytes: Int) {
    init { require(maxResponseBytes in 1..262144) }
    override fun toString() = "PostPublicationPolicy(<redacted>)"
}

/** Current, detached authority result. Memberships are server-derived, never client bindings.
 * The adapter must validate the exact requested attachment, source and disclosure; it may not
 * silently rewrite the reviewed content. Expiry is checked again immediately before commit. */
class PostPublicationEvidence(author: JsonObject, memberships: Map<UUID, Long>,
    verifiedAttachment: JsonObject?, recipeSnapshot: JsonObject?, capabilities: Set<String>, val validUntil: Instant) {
    val author = Json.parseToJsonElement(author.toString()).jsonObject
    val memberships = memberships.toMap()
    val verifiedAttachment = verifiedAttachment?.let { Json.parseToJsonElement(it.toString()).jsonObject }
    val recipeSnapshot = recipeSnapshot?.let { Json.parseToJsonElement(it.toString()).jsonObject }
    val capabilities = capabilities.toSet()
    init {
        require(this.author.toString().encodeToByteArray().size <= 65536)
        require(this.verifiedAttachment?.toString()?.encodeToByteArray()?.size?.let { it <= 65536 } != false)
        require(this.recipeSnapshot?.toString()?.encodeToByteArray()?.size?.let { it <= 262144 } != false)
        require((this.verifiedAttachment == null) == (this.recipeSnapshot == null))
        require(this.memberships.values.all { it > 0 })
        require(this.capabilities.all { it in setOf("view","react","reply","askRecipe","makeMine","saveRecipe","edit","delete","report","remix") })
    }
    override fun toString() = "PostPublicationEvidence(<redacted>)"
}

enum class PostPublicationFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), FORBIDDEN(403), DRAFT_UNAVAILABLE(404),
    PUBLICATION_CONFLICT(409), DRAFT_EXPIRED(410), VERSION_CONFLICT(412),
    MEDIA_UNAVAILABLE(409), RESPONSE_TOO_LARGE(422), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
class PostPublicationFailure(val code: PostPublicationFailureCode) : RuntimeException("Post publication unavailable: ${code.name}")
