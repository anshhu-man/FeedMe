package com.feedme.server.planning

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.planning.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Context-only format; deliberately cannot pretend to be a complete v1 catalog snapshot.
 * Bytes/structure are evidence, not authorization or proof that these inputs are current.
 * No existing Plan, receipt or cooking-pin codec is changed by this additive format. */
internal class PlanningPrivateInputsSnapshot private constructor(
    private val document: WireDocument, private val root: JsonObject,
) {
    val preferenceRevision: String get() = root.getValue("preferences").jsonObject.text("revision")
    val pantryRevision: String get() = root.getValue("pantry").jsonObject.text("revision")
    fun copyForStorage(): WireDocument = document
    fun samePrivateInputs(other: PlanningPrivateInputsSnapshot): Boolean = root == other.root
    fun context(): PlanningContext {
        val p = root.getValue("preferences").jsonObject
        val pantry = root.getValue("pantry").jsonObject
        return PlanningContext(PlanningPreferences(p.text("revision"), p.strings("excludedIngredientIds").toSet(),
            p.strings("dislikedIngredientIds").toSet()), pantry.getValue("items").jsonArray.map {
            val item = it.jsonObject
            ReportedIngredient(item.text("ingredientId"), PlanningAvailability.valueOf(item.text("availability")))
        }, root.getValue("baseMeal").takeUnless { it == JsonNull }?.jsonObject?.let {
            ConfirmedBaseMeal(wire(it.getValue("document")), it.text("catalogType"), it.bool("compositionComplete"))
        })
    }
    override fun toString() = "PlanningPrivateInputsSnapshot(<redacted>)"

    companion object {
        const val MAX_BYTES = 131_072
        fun decode(bytes: ByteArray): PlanningPrivateInputsSnapshot = manifestFormat {
            val document = WireDocument.decode(bytes, WireLimits(MAX_BYTES, 16))
            val root = json(document)
            manifestExact(root, "version", "preferences", "pantry", "baseMeal")
            require(root["version"] == JsonPrimitive(2))
            val p = root.getValue("preferences").jsonObject
            manifestExact(p, "revision", "excludedIngredientIds", "dislikedIngredientIds")
            require(p.text("revision").matches(Regex("[1-9][0-9]{0,127}")))
            manifestIds(p, "excludedIngredientIds"); manifestIds(p, "dislikedIngredientIds")
            val pantry = root.getValue("pantry").jsonObject
            manifestExact(pantry, "revision", "items"); manifestBounded(pantry.text("revision"), 128)
            val items = pantry.getValue("items").jsonArray
            require(items.size <= 256)
            val ids = items.map {
                val item = it.jsonObject; manifestExact(item, "ingredientId", "availability")
                PlanningAvailability.valueOf(item.text("availability"))
                manifestUuid(item.text("ingredientId"))
            }
            require(ids.distinct().size == ids.size)
            if (root["baseMeal"] != JsonNull) {
                val base = root.getValue("baseMeal").jsonObject
                manifestExact(base, "document", "catalogType", "compositionComplete")
                manifestBounded(base.text("catalogType"), 128); base.bool("compositionComplete")
                val body = base.getValue("document").jsonObject
                require(body.keys.containsAll(setOf("description", "preparationState")) &&
                    body.keys.all { it in setOf("description", "preparationState", "ingredientIds") })
                require(body.text("description").length <= 500)
                require(body.text("preparationState") in setOf("alreadyPrepared", "partiallyPrepared", "unknown"))
                if (body.containsKey("ingredientIds")) manifestIds(body, "ingredientIds")
            }
            PlanningPrivateInputsSnapshot(document, root)
        }
    }
}

/** Immutable structural header for a future transaction-owned manifest. This has no writer,
 * seal, rank rows or authority. Decoding even an expired header is allowed; future consumers
 * must reauthorize owner/input/content access, recheck deadlines and verify the complete seal.
 * Exact nested UTF-8 strings are retained and hashed WITHOUT parsed-JSON reserialization. */
internal class PlanningManifestHeaderV2 private constructor(
    private val document: WireDocument,
    val environment: String, val actorKind: String, val principalId: UUID, val manifestId: UUID,
    val request: WireDocument, val inputs: PlanningPrivateInputsSnapshot, val policy: PlanningPolicy,
    val createdAt: Instant, val expiresAt: Instant, val cursorExpiresAt: Instant,
    private val anchor: JsonObject,
) {
    val sha256: String get() = digest(document.encodeUtf8())
    fun copyForStorage(): WireDocument = document

    /** Expected metadata only. The future owner-scoped consumer must establish the SAME
     * environment before calling the actual current view's six-field verifyAnchor method.
     * This codec intentionally cannot promote an arbitrary view into owned provenance. */
    fun catalogAnchorForStorage(): WireDocument = wire(anchor)
    override fun toString() = "PlanningManifestHeaderV2(<redacted>)"

    companion object {
        const val MAX_BYTES = 2_097_152 // Includes worst-case JSON escaping of both bounded documents.
        const val MAX_REQUEST_BYTES = 65_536
        const val COMPARATOR = "lexicographic-v1"
        private val validator by lazy { ContractBodyValidator.bundled() }

        fun decode(bytes: ByteArray): PlanningManifestHeaderV2 = manifestFormat {
            val document = WireDocument.decode(bytes, WireLimits(MAX_BYTES, 16))
            val root = json(document)
            manifestExact(root, "version", "owner", "requestText", "requestSha256", "inputsText", "inputsSha256",
                "policy", "comparator", "catalogAnchor", "createdAt", "expiresAt", "cursorExpiresAt")
            require(root["version"] == JsonPrimitive(2) && root.text("comparator") == COMPARATOR)
            val owner = root.getValue("owner").jsonObject
            manifestExact(owner, "environment", "actorKind", "principalId", "manifestId")
            require(owner.text("environment").matches(Regex("[a-z][a-z0-9-]{0,39}")))
            require(owner.text("actorKind") in setOf("account", "guest"))
            val principalId = manifestUuid(owner.text("principalId")); val manifestId = manifestUuid(owner.text("manifestId"))
            val requestText = root.text("requestText")
            val request = WireDocument.parse(requestText, WireLimits(MAX_REQUEST_BYTES, 32))
            manifestHash(root.text("requestSha256")); require(digest(request.encodeUtf8()) == root.text("requestSha256"))
            require(validator.validateRequest("createPlan", request.encodeUtf8(), "application/json") == BodyValidationResult.Valid)
            val inputsText = root.text("inputsText")
            require(inputsText.length <= PlanningPrivateInputsSnapshot.MAX_BYTES)
            val inputs = PlanningPrivateInputsSnapshot.decode(inputsText.encodeToByteArray(throwOnInvalidSequence = true))
            manifestHash(root.text("inputsSha256")); require(digest(inputs.copyForStorage().encodeUtf8()) == root.text("inputsSha256"))
            val policy = root.getValue("policy").jsonObject
            manifestExact(policy, "version", "heatEnabled", "improveEnabled", "relatedTasteExplicitlyRequested")
            manifestBounded(policy.text("version"), 128)
            val retainedPolicy = PlanningPolicy(policy.text("version"), policy.bool("heatEnabled"), policy.bool("improveEnabled"),
                policy.bool("relatedTasteExplicitlyRequested"))
            val anchor = root.getValue("catalogAnchor").jsonObject
            manifestExact(anchor, "releaseId", "revision", "requestSha256", "taxonomyRevision", "taxonomySha256", "versionCount")
            manifestUuid(anchor.text("releaseId")); manifestLong(anchor.text("revision"), false)
            manifestHash(anchor.text("requestSha256")); manifestBounded(anchor.text("taxonomyRevision"), 128)
            manifestHash(anchor.text("taxonomySha256")); manifestLong(anchor.text("versionCount"), true)
            val created = manifestInstant(root.text("createdAt")); val expires = manifestInstant(root.text("expiresAt"))
            val cursorExpires = manifestInstant(root.text("cursorExpiresAt"))
            require(created < expires && created < cursorExpires && cursorExpires <= expires)
            PlanningManifestHeaderV2(document, owner.text("environment"), owner.text("actorKind"), principalId, manifestId,
                request, inputs, retainedPolicy, created, expires, cursorExpires, anchor)
        }
    }
}

internal class PlanningManifestFormatException : IllegalArgumentException("Planning manifest unavailable")
private fun <T> manifestFormat(action: () -> T): T = try { action() }
catch (failure: CancellationException) { throw failure }
catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
catch (_: Exception) { throw PlanningManifestFormatException() }
private fun manifestExact(root: JsonObject, vararg fields: String) { require(root.keys == fields.toSet()) }
private fun manifestBounded(value: String, max: Int) { require(value.isNotBlank() && value.length <= max && value.none(Char::isISOControl)) }
private fun manifestUuid(value: String): UUID {
    require(CanonicalFormats.accepts("uuid", value) && value == value.lowercase())
    return UUID.fromString(value)
}
private fun manifestHash(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) }
private fun manifestLong(value: String, zeroAllowed: Boolean): Long {
    require(value.matches(Regex(if (zeroAllowed) "0|[1-9][0-9]{0,18}" else "[1-9][0-9]{0,18}")))
    return value.toLong()
}
private fun manifestInstant(value: String): Instant {
    require(value.length <= 40)
    return Instant.parse(value).also { require(it.toString() == value) }
}
private fun manifestIds(root: JsonObject, field: String) {
    val values = root.getValue(field).jsonArray; require(values.size <= 256)
    val ids = values.map { require(it.jsonPrimitive.isString); manifestUuid(it.jsonPrimitive.content) }
    require(ids.distinct().size == ids.size)
}
