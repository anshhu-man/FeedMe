package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.mealFail
import com.feedme.transport.MobileRequestValidator
import kotlinx.serialization.json.*

/** Pure reviewed-PATCH projection/correlation, not a review ticket, authority or queue ACK.
 * Does not widen the existing text-v1 codec/controller. The caller retains the actual original
 * command, exact baseline/ETag and operation-bound receipt/application provenance separately.
 */
internal class ReviewedPostDraftAdapter(private val maxResponseBytes: Int) {
    init { require(maxResponseBytes in 1..262_144) }
    private val requests = MobileRequestValidator()
    private val schemas = CanonicalBodyValidator.bundled()
    private val responses = CanonicalResponseBinder()

    /** Detached comparison/display fields, NOT a canonical PostDraft or server receipt.
     * Includes identity, createdAt and exact successor version. Deliberately omits unknown
     * server-managed updatedAt/expiresAt; never supplies placeholder timestamps or defaults.
     */
    fun expectedFields(original: ApiCall, baseline: WireDocument, baselineETag: String): WireDocument =
        projection(original, baseline, baselineETag).let { fields ->
            val bytes = fields.toString().encodeToByteArray()
            bounded(bytes)
            WireDocument.decode(bytes)
        }

    fun receipt(original: ApiCall, baseline: WireDocument, baselineETag: String, reply: ApiReply): ReviewedPostDraftReceipt {
        val expected = projection(original, baseline, baselineETag)
        val bytes = reply.body?.copyForCodec() ?: reviewedInvalid()
        bounded(bytes)
        if (reply.status != 200) reviewedInvalid()
        val bound = responses.bind("updatePostDraft", reply.status, bytes, reply.contentType, reply.traceId)
            as? ResponseBindingResult.Accepted ?: reviewedInvalid()
        val actual = (bound.response.body as? WireBody.Present)?.document ?: reviewedInvalid()
        val tag = reply.etag ?: reviewedInvalid()
        correlate(expected, actual, tag)
        // Retain complete actual UTF-8 including server timestamps/numeric lexemes/key order.
        return ReviewedPostDraftReceipt(actual, tag)
    }

    /** A matching GET is only a possible original result, never a receipt or permission to retry.
     * The caller must bind the real GET envelope independently. This method produces no object,
     * replaces no baseline/original bytes, and performs no persistence, dispatch or application.
     */
    fun possibleOriginalResult(original: ApiCall, baseline: WireDocument, baselineETag: String,
        observed: WireDocument, observedETag: String) {
        correlate(projection(original, baseline, baselineETag), observed, observedETag)
    }

    private fun projection(original: ApiCall, baseline: WireDocument, baselineETag: String): JsonObject {
        val before = draft(baseline)
        val version = reviewedVersion(before.getValue("version").jsonPrimitive)
        if (baselineETag != "\"$version\"" || original.ifMatch != baselineETag ||
            original.operationId != "updatePostDraft" || original.queryParameters.isNotEmpty() ||
            original.pathParameters.keys != setOf("draftId")) reviewedInvalid()
        val id = before.getValue("id").jsonPrimitive.content
        val client = before.getValue("clientDraftId").jsonPrimitive.content
        // Actual service-generated root identities are canonical lowercase. The original path
        // and optional client assertion may use UUID case aliases; their bytes remain untouched.
        if (reviewedUuid(id) != id || reviewedUuid(client) != client ||
            reviewedUuid(original.pathParameters.getValue("draftId")) != id) reviewedInvalid()
        val bytes = original.body?.copyForCodec() ?: reviewedInvalid()
        if (bytes.size > 65_536) mealFail(FailureReason.UNAVAILABLE)
        if (!requests.accepts(original, PrincipalClass.ACCOUNT)) reviewedInvalid()
        val patch = Json.parseToJsonElement(WireDocument.decode(bytes).encodeUtf8().decodeToString()).jsonObject
        patch["clientDraftId"]?.let { if (reviewedUuid(it.jsonPrimitive.content) != client) reviewedInvalid() }
        if (patch["removeAttachment"] == JsonPrimitive(true) && "attachment" in patch) reviewedInvalid()
        // This service always persists audience and allowRecipeSaves on create. A merely
        // schema-valid but incomplete baseline is not filled from invented product defaults.
        if ("audience" !in before || "allowRecipeSaves" !in before) reviewedInvalid()
        val fields = before.filterKeys { it !in setOf("updatedAt", "expiresAt") }.toMutableMap()
        patch.filterKeys { it !in setOf("clientDraftId", "removeAttachment") }.forEach { (name, value) -> fields[name] = value }
        if (patch["removeAttachment"] == JsonPrimitive(true)) fields.remove("attachment")
        val media = fields.getValue("mediaIds").jsonArray.map { reviewedUuid(it.jsonPrimitive.content) }
        if (media.distinct().size != media.size) reviewedInvalid()
        fields["mediaIds"] = JsonArray(media.map(::JsonPrimitive))
        val audience = fields.getValue("audience").jsonObject
        val circles = audience.getValue("circleIds").jsonArray.map { reviewedUuid(it.jsonPrimitive.content) }
        if (circles.distinct().size != circles.size ||
            (audience.getValue("kind") == JsonPrimitive("self")) != circles.isEmpty()) reviewedInvalid()
        fields["audience"] = JsonObject(audience.filterKeys { it != "bindings" } +
            ("circleIds" to JsonArray(circles.map(::JsonPrimitive))))
        // Deliberately NOT publication normalization: sourcePostId, attachment recipe/plan IDs
        // and nested personalRecipe material retain exact strings. The draft service does so.
        fields["version"] = Json.parseToJsonElement(reviewedSuccessor(version))
        return JsonObject(fields).also { bounded(it.toString().encodeToByteArray()) }
    }

    private fun correlate(expected: JsonObject, observed: WireDocument, etag: String) {
        val actual = draft(observed)
        val version = reviewedVersion(actual.getValue("version").jsonPrimitive)
        if (etag != "\"$version\"" || !reviewedSame(expected,
                JsonObject(actual.filterKeys { it !in setOf("updatedAt", "expiresAt") }))) reviewedInvalid()
    }

    private fun draft(document: WireDocument): JsonObject {
        val bytes = document.encodeUtf8()
        bounded(bytes)
        if (schemas.validateSchema("PostDraft", bytes) != ContractValidationResult.Valid) reviewedInvalid()
        return Json.parseToJsonElement(bytes.decodeToString()).jsonObject.also {
            if (it["status"] != JsonPrimitive("draft") || "publishedPostId" in it) reviewedInvalid()
        }
    }

    private fun bounded(bytes: ByteArray) { if (bytes.size > maxResponseBytes) mealFail(FailureReason.UNAVAILABLE) }
    override fun toString() = "ReviewedPostDraftAdapter(<redacted>)"
}

/** Complete canonical correlated reply data only; no queue provenance, delivery/apply proof. */
internal class ReviewedPostDraftReceipt internal constructor(val document: WireDocument, val etag: String) {
    override fun toString() = "ReviewedPostDraftReceipt(<redacted>)"
}

private fun reviewedInvalid(): Nothing = mealFail(FailureReason.INVALID_DATA)
private fun reviewedUuid(value: String): String {
    if (!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)) reviewedInvalid()
    return value.lowercase()
}

/** Canonical validators bound numeric tokens/scale before these exact, non-expanding helpers. */
private fun reviewedNumberKey(value: JsonPrimitive): String {
    if (value.isString || value.booleanOrNull != null || value == JsonNull) reviewedInvalid()
    val parts = value.content.lowercase().split('e')
    val mantissa = parts[0]
    val exponent = if (parts.size == 1) 0 else parts[1].toIntOrNull() ?: reviewedInvalid()
    val all = mantissa.removePrefix("-").replace(".", "")
    val digits = all.trimStart('0').trimEnd('0')
    if (digits.isEmpty()) return "0e0"
    val trailing = all.length - all.trimEnd('0').length
    val scale = exponent - mantissa.substringAfter('.', "").length + trailing
    return "${if (mantissa.startsWith('-')) "-" else ""}${digits}e$scale"
}
private fun reviewedVersion(value: JsonPrimitive): String {
    val key = reviewedNumberKey(value)
    val digits = key.substringBefore('e')
    val exponent = key.substringAfter('e').toInt()
    if (digits == "0" || digits.startsWith('-') || exponent < 0 || digits.length + exponent > 19) reviewedInvalid()
    val decimal = digits + "0".repeat(exponent)
    if (decimal.length == 19 && decimal > "9223372036854775807") reviewedInvalid()
    return decimal
}
private fun reviewedSuccessor(value: String): String {
    if (value == "9223372036854775807") reviewedInvalid()
    val digits = value.toCharArray()
    var index = digits.lastIndex
    while (index >= 0 && digits[index] == '9') { digits[index] = '0'; index-- }
    if (index < 0) return "1" + digits.concatToString()
    digits[index] = (digits[index].code + 1).toChar()
    return digits.concatToString()
}
private fun reviewedSame(left: JsonElement?, right: JsonElement?): Boolean = when {
    left == null || right == null -> left == null && right == null
    left == JsonNull || right == JsonNull -> left == JsonNull && right == JsonNull
    left is JsonObject && right is JsonObject -> left.keys == right.keys && left.all { (key, value) -> reviewedSame(value, right[key]) }
    left is JsonArray && right is JsonArray -> left.size == right.size && left.indices.all { reviewedSame(left[it], right[it]) }
    left is JsonPrimitive && right is JsonPrimitive -> when {
        left.isString || right.isString -> left.isString && right.isString && left.content == right.content
        left.booleanOrNull != null || right.booleanOrNull != null -> left.booleanOrNull != null && left.booleanOrNull == right.booleanOrNull
        else -> reviewedNumberKey(left) == reviewedNumberKey(right)
    }
    else -> false
}
