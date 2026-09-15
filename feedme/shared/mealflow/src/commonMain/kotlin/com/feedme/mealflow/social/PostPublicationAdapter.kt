package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.mealFail
import com.feedme.transport.MobileRequestValidator
import kotlinx.serialization.json.*

/** Pure response correlation, NOT transport, authorization, queue receipt provenance or an ACK.
 * The caller must supply an actual publishPost reply and retain its original queue identity.
 * verifiedAccountUserId is comparison data supplied by the current retained-principal owner;
 * accepting its syntax here neither verifies a principal nor permits publication/replay.
 */
internal class PostPublicationAdapter(private val maxResponseBytes: Int) {
    init { require(maxResponseBytes in 1..262_144) }
    private val requests = MobileRequestValidator()
    private val responses = CanonicalResponseBinder()

    /** A detached expected selection, never a rewrite of the original request or a Post. */
    fun expectedSelection(original: ApiCall): WireDocument = WireDocument.parse(selection(original).toString())

    fun receipt(original: ApiCall, verifiedAccountUserId: String, reply: ApiReply): PostPublicationReceipt {
        val expected = selection(original)
        val expectedAuthor = publicationUuid(verifiedAccountUserId)
        val bytes = reply.body?.copyForCodec() ?: invalid()
        if (bytes.size > maxResponseBytes) mealFail(FailureReason.UNAVAILABLE)
        val etag = reply.etag ?: invalid()
        if (reply.status != 201 || etag != "\"1\"") invalid()
        val bound = responses.bind("publishPost", reply.status, bytes, reply.contentType, reply.traceId)
            as? ResponseBindingResult.Accepted ?: invalid()
        val document = (bound.response.body as? WireBody.Present)?.document ?: invalid()
        val post = Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
        if (numberKey(post.getValue("version").jsonPrimitive) != "1e0" ||
            post.getValue("status") != JsonPrimitive("published") ||
            publicationUuid(post.getValue("author").jsonObject.getValue("userId").jsonPrimitive.content) != expectedAuthor) invalid()
        for (field in listOf("caption", "altText", "mediaIds", "keepOnPlate", "attachment", "sourcePostId")) {
            if (!sameValue(expected[field], post[field])) invalid()
        }
        val expectedAudience = expected.getValue("audience").jsonObject
        val actualAudience = post.getValue("audience").jsonObject
        if (!sameValue(expectedAudience["kind"], actualAudience["kind"]) ||
            !sameValue(expectedAudience["circleIds"], actualAudience["circleIds"])) invalid()
        // Publication always stamps a complete server binding set. Client bindings are ignored;
        // this structural check does not establish current membership or compare generations.
        val bindings = actualAudience["bindings"] as? JsonArray ?: invalid()
        val boundCircles = bindings.map { it.jsonObject.getValue("circleId").jsonPrimitive.content }
        val selectedCircles = expectedAudience.getValue("circleIds").jsonArray.map { it.jsonPrimitive.content }
        if (boundCircles.distinct().size != boundCircles.size || boundCircles.toSet() != selectedCircles.toSet()) invalid()
        val savePolicy = post.getValue("savePolicy").jsonObject
        if (!sameValue(expected["allowRecipeSaves"], savePolicy["allowFutureSaves"]) ||
            !sameValue(expected["saveDisclosureVersion"], savePolicy["disclosureVersion"])) invalid()
        // Keep the actual complete response and all numeric lexemes, not the normalized projection.
        return PostPublicationReceipt(document, etag)
    }

    private fun selection(original: ApiCall): JsonObject {
        if (original.operationId != "publishPost" || original.ifMatch != null ||
            original.pathParameters.isNotEmpty() || original.queryParameters.isNotEmpty()) invalid()
        val bytes = original.body?.copyForCodec() ?: invalid()
        if (bytes.size > 65_536) mealFail(FailureReason.UNAVAILABLE)
        if (!requests.accepts(original, PrincipalClass.ACCOUNT)) invalid()
        val request = Json.parseToJsonElement(WireDocument.decode(bytes).encodeUtf8().decodeToString()).jsonObject
        if (("draftId" in request) != ("draftVersion" in request)) invalid()
        request["draftVersion"]?.let {
            // The service resolves this field as an exact positive PostgreSQL bigint, not Double.
            val key = numberKey(it.jsonPrimitive)
            val exponent = key.substringAfter('e').toInt()
            val digits = key.substringBefore('e')
            if (digits == "0" || digits.startsWith('-') || exponent < 0 || digits.length + exponent > 19) invalid()
            val integer = digits + "0".repeat(exponent)
            if (integer.length == 19 && integer > "9223372036854775807") invalid()
        }
        val fields = request.filterKeys { it !in setOf("clientDraftId", "draftId", "draftVersion") }.toMutableMap()
        val media = fields.getValue("mediaIds").jsonArray.map { publicationUuid(it.jsonPrimitive.content) }
        if (media.distinct().size != media.size) invalid()
        fields["mediaIds"] = JsonArray(media.map(::JsonPrimitive))
        val audience = fields.getValue("audience").jsonObject
        val circles = audience.getValue("circleIds").jsonArray.map { publicationUuid(it.jsonPrimitive.content) }
        if (circles.distinct().size != circles.size ||
            (audience.getValue("kind") == JsonPrimitive("self")) != circles.isEmpty()) invalid()
        fields["audience"] = JsonObject(audience.filterKeys { it != "bindings" } +
            ("circleIds" to JsonArray(circles.map(::JsonPrimitive))))
        fields["sourcePostId"]?.let { fields["sourcePostId"] = JsonPrimitive(publicationUuid(it.jsonPrimitive.content)) }
        fields["attachment"]?.jsonObject?.let { attachment ->
            if (listOf("recipeVersionId", "planId", "personalRecipe").count { it in attachment } != 1) invalid()
            val personal = "personalRecipe" in attachment
            if (attachment["reviewStatus"] != JsonPrimitive(if (personal) "personal" else "reviewed") ||
                attachment["rightsBasis"] != JsonPrimitive(if (personal) "creatorOriginal" else "catalogRedistributable")) invalid()
            fields["attachment"] = JsonObject(attachment.mapValues { (field, value) ->
                if (field in setOf("recipeVersionId", "planId")) JsonPrimitive(publicationUuid(value.jsonPrimitive.content)) else value
            })
        }
        if (fields["allowRecipeSaves"] == JsonPrimitive(true) && fields["attachment"] == null) invalid()
        return JsonObject(fields)
    }

    override fun toString() = "PostPublicationAdapter(<redacted>)"
}

/** Canonical correlated data only. No command archive, local application or delivery proof. */
internal class PostPublicationReceipt internal constructor(val document: WireDocument, val etag: String) {
    override fun toString() = "PostPublicationReceipt(<redacted>)"
}

private fun invalid(): Nothing = mealFail(FailureReason.INVALID_DATA)
private fun publicationUuid(value: String): String {
    if (!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)) invalid()
    return value.lowercase()
}

/** Inputs have already passed the complete canonical schema/numeric resource profile.
 * Compare mathematical JSON numbers exactly (including -0), without expanding large exponents.
 * Private helper: keys may contain content and must never enter a diagnostic.
 */
private fun numberKey(value: JsonPrimitive): String {
    if (value.isString || value.booleanOrNull != null || value == JsonNull) invalid()
    val parts = value.content.lowercase().split('e')
    val mantissa = parts[0]
    val exponent = if (parts.size == 1) 0 else parts[1].toIntOrNull() ?: invalid()
    val allDigits = mantissa.removePrefix("-").replace(".", "")
    val digits = allDigits.trimStart('0').trimEnd('0')
    if (digits.isEmpty()) return "0e0"
    val trailing = allDigits.length - allDigits.trimEnd('0').length
    val scale = exponent - mantissa.substringAfter('.', "").length + trailing
    return "${if (mantissa.startsWith('-')) "-" else ""}${digits}e$scale"
}

private fun sameValue(left: JsonElement?, right: JsonElement?): Boolean = when {
    left == null || right == null -> left == null && right == null
    left == JsonNull || right == JsonNull -> left == JsonNull && right == JsonNull
    left is JsonObject && right is JsonObject -> left.keys == right.keys && left.all { (key, value) -> sameValue(value, right[key]) }
    left is JsonArray && right is JsonArray -> left.size == right.size && left.indices.all { sameValue(left[it], right[it]) }
    left is JsonPrimitive && right is JsonPrimitive -> when {
        left.isString || right.isString -> left.isString && right.isString && left.content == right.content
        left.booleanOrNull != null || right.booleanOrNull != null -> left.booleanOrNull != null && left.booleanOrNull == right.booleanOrNull
        else -> numberKey(left) == numberKey(right)
    }
    else -> false
}
