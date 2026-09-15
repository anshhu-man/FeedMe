package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.serialization.json.*

/** Exact retained content, NOT a live ReviewedPostChoices, current disclosure or permission. */
internal sealed class DraftLocalContentV1 {
    abstract val caption: String
    abstract val altText: String?
    class TextV1(override val caption: String, override val altText: String?) : DraftLocalContentV1()
    class ComposerV2(val exactChoices: WireDocument, val historicalDisclosureText: String?) : DraftLocalContentV1() {
        override val caption: String get() = postString(exactChoices, "caption")
        override val altText: String? get() = postAlt(exactChoices)
    }
    override fun toString() = "DraftLocalContentV1(<redacted>)"
}

/** NotObserved is only local history, never proof that no server draft/publication exists. */
internal sealed class DraftServerAssociationV1 {
    data object NotObserved : DraftServerAssociationV1()
    class Observed(val exactPostDraft: WireDocument, val etag: String) : DraftServerAssociationV1()
    override fun toString() = "DraftServerAssociationV1(<redacted>)"
}

/** Immutable historical pin material. Constructor/data/bytes alone grant no journal use,
 * actual row pin, review, replay or ACK. The sole owner retains that provenance separately. */
internal class DraftLocalSnapshotV1 internal constructor(
    val exactUtf8: PrivateBytes, val clientDraftId: String, val localRevision: Long,
    val content: DraftLocalContentV1, val serverAssociation: DraftServerAssociationV1,
) {
    override fun toString() = "DraftLocalSnapshotV1(<redacted>)"
}

/** Shared pure serializer/validator. Publication may validate exact snapshot bytes, never
 * acquire the draft namespace or forge an owner-created mutation from this value. */
internal class DraftLocalSnapshotCodecV1(
    private val draftPolicy: PostDraftClientPolicy,
    private val publicationPolicy: PostPublicationClientPolicy,
) {
    private val maxBytes = minOf(draftPolicy.maxRecordBytes, publicationPolicy.maxRecordBytes)
    private val maxResponse = minOf(draftPolicy.maxResponseBytes, publicationPolicy.maxResponseBytes)
    private val schemas = CanonicalBodyValidator.bundled()

    fun decode(bytes: PrivateBytes): DraftLocalSnapshotV1 {
        bounded(bytes.copyForCodec(), maxBytes)
        val root = WireDocument.decode(bytes.copyForCodec(), WireLimits(maxBytes, 32)).json().jsonObject
        keys(root, "format", "clientDraftId", "localRevision", "content", "serverAssociation")
        if (string(root.getValue("format")) != "draft-local-snapshot-v1") invalid()
        val client = strictId(string(root.getValue("clientDraftId")))
        val revision = long(root.getValue("localRevision")); if (revision == 0L) invalid()
        val c = root.getValue("content").jsonObject
        val content = when (string(c.getValue("kind"))) {
            "text-v1" -> {
                if (c.keys !in listOf(setOf("kind", "caption"), setOf("kind", "caption", "altText"))) invalid()
                DraftLocalContentV1.TextV1(string(c.getValue("caption")), c["altText"]?.let(::string))
            }
            "composer-v2" -> {
                if (c.keys !in listOf(setOf("kind", "exactChoicesUtf8"), setOf("kind", "exactChoicesUtf8", "historicalDisclosureText"))) invalid()
                DraftLocalContentV1.ComposerV2(WireDocument.parse(string(c.getValue("exactChoicesUtf8")),
                    WireLimits(minOf(publicationPolicy.maxOriginalBytes, maxBytes), 24)), c["historicalDisclosureText"]?.let(::string))
            }
            else -> invalid()
        }
        val a = root.getValue("serverAssociation").jsonObject
        val association = when (string(a.getValue("kind"))) {
            "not-observed" -> { keys(a, "kind"); DraftServerAssociationV1.NotObserved }
            "observed" -> {
                keys(a, "kind", "exactPostDraftUtf8", "etag")
                DraftServerAssociationV1.Observed(WireDocument.parse(string(a.getValue("exactPostDraftUtf8")),
                    WireLimits(maxResponse, 24)), string(a.getValue("etag")))
            }
            else -> invalid()
        }
        validate(client, revision, content, association)
        return DraftLocalSnapshotV1(bytes, client, revision, content, association)
    }

    /** Existing exact bytes survive whitespace, object order, optional presence and numbers.
     * Validate metadata agreement too: even an internal fabricated wrapper is only data. */
    fun encode(snapshot: DraftLocalSnapshotV1): PrivateBytes {
        val parsed = decode(snapshot.exactUtf8)
        if (snapshot.clientDraftId != parsed.clientDraftId || snapshot.localRevision != parsed.localRevision ||
            !sameContent(snapshot.content, parsed.content) || !sameAssociation(snapshot.serverAssociation, parsed.serverAssociation)) invalid()
        return snapshot.exactUtf8
    }

    fun create(clientDraftId: String, localRevision: Long, content: DraftLocalContentV1,
        serverAssociation: DraftServerAssociationV1): DraftLocalSnapshotV1 {
        validate(clientDraftId, localRevision, content, serverAssociation)
        val raw = buildJsonObject {
            put("format", "draft-local-snapshot-v1"); put("clientDraftId", clientDraftId); put("localRevision", localRevision)
            put("content", when (content) {
                is DraftLocalContentV1.TextV1 -> buildJsonObject {
                    put("kind", "text-v1"); put("caption", content.caption); content.altText?.let { put("altText", it) }
                }
                is DraftLocalContentV1.ComposerV2 -> buildJsonObject {
                    put("kind", "composer-v2"); put("exactChoicesUtf8", content.exactChoices.encodeUtf8().decodeToString())
                    content.historicalDisclosureText?.let { put("historicalDisclosureText", it) }
                }
            })
            put("serverAssociation", when (serverAssociation) {
                DraftServerAssociationV1.NotObserved -> buildJsonObject { put("kind", "not-observed") }
                is DraftServerAssociationV1.Observed -> buildJsonObject {
                    put("kind", "observed"); put("exactPostDraftUtf8", serverAssociation.exactPostDraft.encodeUtf8().decodeToString())
                    put("etag", serverAssociation.etag)
                }
            })
        }
        val bytes = PrivateBytes(raw.toString().encodeToByteArray())
        bounded(bytes.copyForCodec(), maxBytes)
        return decode(bytes)
    }

    /** Pure conversion only; schema1 row/proofs are not rewritten by calling this helper. */
    fun fromLegacy(local: PostLocal): DraftLocalSnapshotV1 {
        if ((local.server == null) != (local.etag == null)) invalid()
        return create(local.id, local.revision, DraftLocalContentV1.TextV1(local.caption, local.alt),
            local.server?.let { DraftServerAssociationV1.Observed(it, local.etag!!) } ?: DraftServerAssociationV1.NotObserved)
    }

    /** New local intent only, never a PATCH or retry re-encoder. Exact next local text is
     * supplied after current field-specific callback admission; other choices are retained. */
    fun withText(snapshot: DraftLocalSnapshotV1, caption: String, altText: String?, nextRevision: Long): DraftLocalSnapshotV1 {
        encode(snapshot)
        if (snapshot.localRevision == Long.MAX_VALUE || nextRevision != snapshot.localRevision + 1) invalid()
        textBytes(caption, maxBytes); altText?.let { textBytes(it, maxBytes) }; postText(caption, altText)
        val content = when (val before = snapshot.content) {
            is DraftLocalContentV1.TextV1 -> DraftLocalContentV1.TextV1(caption, altText)
            is DraftLocalContentV1.ComposerV2 -> {
                val fields = before.exactChoices.json().jsonObject.toMutableMap()
                fields["caption"] = JsonPrimitive(caption)
                if (altText == null) fields.remove("altText") else fields["altText"] = JsonPrimitive(altText)
                DraftLocalContentV1.ComposerV2(WireDocument.parse(JsonObject(fields).toString()), before.historicalDisclosureText)
            }
        }
        return create(snapshot.clientDraftId, nextRevision, content, snapshot.serverAssociation)
    }

    fun withServerAssociation(snapshot: DraftLocalSnapshotV1, association: DraftServerAssociationV1): DraftLocalSnapshotV1 {
        encode(snapshot)
        validate(snapshot.clientDraftId, snapshot.localRevision, snapshot.content, association)
        if (snapshot.serverAssociation is DraftServerAssociationV1.Observed) {
            val next = association as? DraftServerAssociationV1.Observed ?: invalid()
            // A current observation is not a replacement original baseline or branch. Preserve
            // known association, root identity and the established legacy monotone checks.
            PostDraftAdapter(draftPolicy).monotone(snapshot.serverAssociation.exactPostDraft, next.exactPostDraft)
        }
        return create(snapshot.clientDraftId, snapshot.localRevision, snapshot.content, association)
    }

    private fun validate(client: String, revision: Long, content: DraftLocalContentV1, association: DraftServerAssociationV1) {
        strictId(client); if (revision <= 0) invalid()
        var rawBytes = client.length.toLong()
        when (content) {
            is DraftLocalContentV1.TextV1 -> {
                rawBytes += textBytes(content.caption, maxBytes)
                content.altText?.let { rawBytes += textBytes(it, maxBytes) }
                postText(content.caption, content.altText)
            }
            is DraftLocalContentV1.ComposerV2 -> {
                val bytes = content.exactChoices.encodeUtf8(); bounded(bytes, minOf(publicationPolicy.maxOriginalBytes, maxBytes))
                rawBytes += bytes.size
                // Text can be missing historically, but never fabricated from a version string.
                content.historicalDisclosureText?.let { rawBytes += textBytes(it, publicationPolicy.maxDisclosureBytes) }
                schema("PostDraftPatch", content.exactChoices)
                val choices = content.exactChoices.json().jsonObject
                val required = setOf("caption", "mediaIds", "audience", "keepOnPlate", "allowRecipeSaves")
                val optional = setOf("altText", "attachment", "saveDisclosureVersion", "sourcePostId")
                if (!choices.keys.containsAll(required) || choices.keys.any { it !in required + optional }) invalid()
                ids(choices.getValue("mediaIds").jsonArray, publicationPolicy.maxMediaSelections)
                val audience = choices.getValue("audience").jsonObject
                keys(audience, "kind", "circleIds")
                val circles = audience.getValue("circleIds").jsonArray
                ids(circles, publicationPolicy.maxCircleSelections)
                if ((string(audience.getValue("kind")) == "self") != circles.isEmpty()) invalid()
                choices["attachment"]?.let {
                    bounded(it.toString().encodeToByteArray(), publicationPolicy.maxAttachmentBytes)
                    if (it.jsonObject.getValue("confirmedChanges").jsonArray.size > publicationPolicy.maxConfirmedChanges) unavailable()
                }
                val version = choices["saveDisclosureVersion"]?.let(::string)
                if (content.historicalDisclosureText != null && version == null) invalid()
                val disclosureBytes = (version?.let { textBytes(it, publicationPolicy.maxDisclosureBytes) } ?: 0) +
                    (content.historicalDisclosureText?.let { textBytes(it, publicationPolicy.maxDisclosureBytes) } ?: 0)
                if (disclosureBytes > publicationPolicy.maxDisclosureBytes) unavailable()
            }
        }
        if (association is DraftServerAssociationV1.Observed) {
            val bytes = association.exactPostDraft.encodeUtf8(); bounded(bytes, maxResponse); rawBytes += bytes.size
            rawBytes += textBytes(association.etag, maxBytes)
            schema("PostDraft", association.exactPostDraft)
            if (postString(association.exactPostDraft, "clientDraftId") != client) invalid()
            postEtag(association.exactPostDraft, association.etag)
        }
        if (rawBytes > maxBytes) unavailable()
    }

    private fun ids(values: JsonArray, limit: Int) {
        if (values.size > limit) unavailable()
        val normalized = values.map { uuid(string(it)) }
        if (normalized.distinct().size != normalized.size) invalid()
    }
    private fun schema(name: String, value: WireDocument) {
        when (val result = schemas.validateSchema(name, value.encodeUtf8())) {
            ContractValidationResult.Valid -> Unit
            is ContractValidationResult.Rejected -> if (result.reason == ContractRejectionReason.RESOURCE_LIMIT) unavailable() else invalid()
        }
    }
    private fun sameContent(a: DraftLocalContentV1, b: DraftLocalContentV1): Boolean = when {
        a is DraftLocalContentV1.TextV1 && b is DraftLocalContentV1.TextV1 -> a.caption == b.caption && a.altText == b.altText
        a is DraftLocalContentV1.ComposerV2 && b is DraftLocalContentV1.ComposerV2 ->
            a.historicalDisclosureText == b.historicalDisclosureText && a.exactChoices.encodeUtf8().contentEquals(b.exactChoices.encodeUtf8())
        else -> false
    }
    private fun sameAssociation(a: DraftServerAssociationV1, b: DraftServerAssociationV1): Boolean = when {
        a === DraftServerAssociationV1.NotObserved && b === DraftServerAssociationV1.NotObserved -> true
        a is DraftServerAssociationV1.Observed && b is DraftServerAssociationV1.Observed ->
            a.etag == b.etag && a.exactPostDraft.encodeUtf8().contentEquals(b.exactPostDraft.encodeUtf8())
        else -> false
    }
    private fun textBytes(value: String, limit: Int): Int {
        if (value.length > minOf(limit, maxBytes)) unavailable()
        val bytes = try { value.encodeToByteArray(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { invalid() }
        bounded(bytes, minOf(limit, maxBytes)); return bytes.size
    }
    private fun bounded(bytes: ByteArray, limit: Int) { if (bytes.size > limit) unavailable() }
    private fun invalid(): Nothing = mealFail(FailureReason.INVALID_DATA)
    private fun unavailable(): Nothing = mealFail(FailureReason.UNAVAILABLE)
    override fun toString() = "DraftLocalSnapshotCodecV1(<redacted>)"
}
