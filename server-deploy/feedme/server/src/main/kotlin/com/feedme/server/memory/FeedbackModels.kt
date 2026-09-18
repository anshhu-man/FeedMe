package com.feedme.server.memory

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.db.CommandActor
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

/** Constructed only by actual server identity verification; never a caller-selected owner. */
internal class VerifiedFeedbackPrincipal(val environment: String, val kind: CommandActor,
    val principalId: UUID, val deviceSessionId: UUID?, val guestSessionId: UUID? = null) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(kind in setOf(CommandActor.ACCOUNT, CommandActor.GUEST))
        require(if (kind == CommandActor.ACCOUNT) deviceSessionId != null && guestSessionId == null
            else deviceSessionId == null && guestSessionId != null)
    }
    override fun toString() = "VerifiedFeedbackPrincipal(<redacted>)"
}

/** One immutable normalized context. A cook is context, while ingredient/taste/preparation
 * may be the explicit focus. Structural normalization never verifies existence or ownership. */
internal class FeedbackTargetContext(cookSessionId: UUID?, target: JsonObject) {
    val kind: String
    val resourceId: UUID?
    val tag: String?
    val cookSessionId: UUID?
    val target: JsonObject
    val exactDocument: String
    val sha256: String
    init {
        kind = target.getValue("kind").jsonPrimitive.let { require(it.isString); it.content }
        require(kind in setOf("cookSession", "plan", "recipeVersion", "ingredient", "taste", "preparation"))
        if (kind in setOf("taste", "preparation")) {
            require(target.keys == setOf("kind", "tag"))
            tag = target.getValue("tag").jsonPrimitive.let { require(it.isString); it.content }
            require(tag in if (kind == "taste") setOf("crunch", "fresh", "creamy", "heat")
                else setOf("chopping", "activeCooking", "cleanup"))
            resourceId = null
        } else {
            require(target.keys == setOf("kind", "resourceId"))
            resourceId = target.getValue("resourceId").jsonPrimitive.let { require(it.isString); UUID.fromString(it.content) }
            tag = null
        }
        require(kind != "cookSession" || cookSessionId == null || cookSessionId == resourceId)
        this.cookSessionId = cookSessionId ?: resourceId.takeIf { kind == "cookSession" }
        this.target = buildJsonObject {
            put("kind", kind)
            resourceId?.let { put("resourceId", it.toString()) }
            tag?.let { put("tag", it) }
        }
        exactDocument = buildJsonObject {
            put("cookSessionId", this@FeedbackTargetContext.cookSessionId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("target", this@FeedbackTargetContext.target)
        }.toString()
        sha256 = feedbackSha(exactDocument)
    }
    override fun toString() = "FeedbackTargetContext(<redacted>)"
    companion object {
        fun fromInput(body: JsonObject): FeedbackTargetContext {
            val cook = body["cookSessionId"]?.jsonPrimitive?.let { require(it.isString); UUID.fromString(it.content) }
            val target = body["target"]?.jsonObject ?: buildJsonObject {
                put("kind", "cookSession"); put("resourceId", requireNotNull(cook).toString())
            }
            return FeedbackTargetContext(cook, target)
        }
        fun decode(text: String): FeedbackTargetContext {
            val root = Json.parseToJsonElement(WireDocument.decode(text.encodeToByteArray(throwOnInvalidSequence = true),
                WireLimits(2048, 8)).encodeUtf8().decodeToString()).jsonObject
            require(root.keys == setOf("cookSessionId", "target"))
            val cook = root.getValue("cookSessionId").takeUnless { it == JsonNull }?.jsonPrimitive?.let {
                require(it.isString); UUID.fromString(it.content)
            }
            return FeedbackTargetContext(cook, root.getValue("target").jsonObject).also { require(it.exactDocument == text) }
        }
    }
}

/** Mandatory actual target proof, bound by its implementation to the original connection,
 * principal, normalized context, thread and transaction. Snapshot is bounded metadata only:
 * no credentials, recipe copies, note text or invented authorization facts. */
internal interface FeedbackTargetEvidence {
    val snapshot: JsonObject
    fun revalidate(connection: Connection, actor: VerifiedFeedbackPrincipal, context: FeedbackTargetContext)
}

/** Same actual transaction/lifecycle roots as other private guest operations. There is no
 * accepting default. Only NEW feedback needs target authorization. Existing owned feedback
 * remains editable/retractable after the original target expires or disappears. */
internal interface FeedbackAuthority {
    fun lockPrincipal(connection: Connection, actor: VerifiedFeedbackPrincipal)
    fun authorizeTarget(connection: Connection, actor: VerifiedFeedbackPrincipal, context: FeedbackTargetContext): FeedbackTargetEvidence
}

internal class FeedbackServicePolicy(val maxResponseBytes: Int) {
    init { require(maxResponseBytes in 1..262_144) }
}
internal enum class FeedbackFailureCode { INPUT_INVALID, UNAUTHENTICATED, FORBIDDEN,
    FEEDBACK_UNAVAILABLE, TARGET_UNAVAILABLE, RECIPE_RECALLED, VERSION_CONFLICT, FEEDBACK_CONFLICT,
    NOT_CONFIGURED, STORAGE_UNAVAILABLE, RESPONSE_TOO_LARGE }
internal class FeedbackFailure(val code: FeedbackFailureCode) : RuntimeException("Feedback unavailable: ${code.name}")

internal fun feedbackCanonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (key, child) -> "${JsonPrimitive(key)}:${feedbackCanonical(child)}" }
    is JsonArray -> value.joinToString(",", "[", "]", transform = ::feedbackCanonical)
    is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
        else value.content.toBigDecimal().stripTrailingZeros().toString()
}
internal fun feedbackSha(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray(throwOnInvalidSequence = true)).joinToString("") { "%02x".format(it.toInt() and 255) }
