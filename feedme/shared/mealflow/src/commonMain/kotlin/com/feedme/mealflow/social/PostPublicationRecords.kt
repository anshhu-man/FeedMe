package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.MealFailure
import com.feedme.mealflow.mealFail
import com.feedme.sync.CommandIntent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Pure retained comparison data, not a verified principal, session or writer capability. */
internal class PublicationJournalBindingData(val environment: String, val canonicalOrigin: String,
    val exactOriginBinding: String, val originalCanonicalUserId: String) {
    init {
        publicationRecordRequire(environment.isNotBlank() && environment.length <= 200 && environment.none(Char::isISOControl))
        publicationRecordId(canonicalOrigin); publicationRecordId(originalCanonicalUserId)
        publicationRecordRequire(publicationRecordUuid(exactOriginBinding) == canonicalOrigin)
    }
    override fun toString() = "PublicationJournalBindingData(<redacted>)"
}

internal sealed class PublicationReviewTargetV1 private constructor() {
    data object DirectLocal : PublicationReviewTargetV1()
    class SavedDraft(val draftId: String, val draftVersion: ExactPostVersion, val reviewedETag: String,
        val exactReviewedDraft: WireDocument) : PublicationReviewTargetV1() {
        override fun toString() = "PublicationReviewTargetV1.SavedDraft(<redacted>)"
    }
}
internal class PublicationReviewMaterialV1 internal constructor(val exactUtf8: PrivateBytes,
    val clientDraftId: String, val reviewedLocalRevision: Long, val exactPostWrite: WireDocument,
    val exactReviewedLocalSnapshot: DraftLocalSnapshotV1, val target: PublicationReviewTargetV1,
    val displayedDisclosure: PublicationDisclosure) {
    override fun toString() = "PublicationReviewMaterialV1(<redacted>)"
}
internal class PublicationOriginalLinkV1 internal constructor(val exactUtf8: PrivateBytes,
    val environment: String, val originBinding: String, val originalCanonicalUserId: String,
    val commandId: String, val clientDraftId: String, val reviewedLocalRevision: Long,
    val originalCreatedAtMillis: Long, val exactPostWrite: WireDocument,
    val historicalReview: PublicationReviewMaterialV1) {
    fun originalIntentForComparison() = CommandIntent(commandId, originBinding,
        ApiCall("publishPost", body = PrivateBytes(exactPostWrite.encodeUtf8()), idempotencyKey = SecretText(commandId)))
    override fun toString() = "PublicationOriginalLinkV1(<redacted>)"
}

/** Complete exact original + versioned historical review. No time/ID source, queue, permission
 * default, store or ACK. Encoded copies preserve every existing nested UTF-8 byte. */
internal class PublicationOriginalLinkCodecV1(private val policy: PostPublicationClientPolicy,
    private val snapshots: DraftLocalSnapshotCodecV1) {
    private val adapter = PostPublicationAdapter(policy.maxResponseBytes)

    fun create(binding: PublicationJournalBindingData, commandId: String, createdAtMillis: Long,
        original: ApiCall, snapshot: DraftLocalSnapshotV1, target: PublicationReviewTargetV1,
        disclosure: PublicationDisclosure): PublicationOriginalLinkV1 = publicationRecordGuard {
        publicationRecordRequire(createdAtMillis >= 0 && original.operationId == "publishPost" &&
            original.pathParameters.isEmpty() && original.queryParameters.isEmpty() && original.ifMatch == null &&
            original.idempotencyKey?.use { it == commandId } == true)
        publicationRecordId(commandId)
        val body = original.body ?: publicationRecordInvalid()
        val rawBody = publicationRecordDocument(body, policy.maxOriginalBytes)
        val checkedSnapshot = snapshots.decode(snapshots.encode(snapshot))
        val review = buildJsonObject {
            put("format", "publication-review-material-v1"); put("clientDraftId", checkedSnapshot.clientDraftId)
            put("reviewedLocalRevision", checkedSnapshot.localRevision); put("exactPostWriteUtf8", publicationRecordText(rawBody))
            put("exactReviewedLocalSnapshotUtf8", publicationRecordText(checkedSnapshot.exactUtf8)); put("target", targetJson(target))
            put("displayedDisclosure", buildJsonObject { put("version", disclosure.version); put("text", disclosure.text) })
        }
        decode(publicationRecordBytes(buildJsonObject {
            put("format", "publication-original-link-v1"); put("environment", binding.environment)
            put("originBinding", binding.exactOriginBinding); put("originalCanonicalUserId", binding.originalCanonicalUserId)
            put("commandId", commandId); put("clientDraftId", checkedSnapshot.clientDraftId)
            put("reviewedLocalRevision", checkedSnapshot.localRevision); put("originalCreatedAtMillis", createdAtMillis)
            put("operationId", "publishPost"); put("pathParameters", JsonObject(emptyMap())); put("queryParameters", JsonObject(emptyMap()))
            put("ifMatch", JsonNull); put("dependencyCommandIds", JsonArray(emptyList()))
            put("exactPostWriteUtf8", publicationRecordText(rawBody)); put("historicalReviewUtf8", review.toString())
        }, policy.maxRecordBytes))
    }

    fun encode(value: PublicationOriginalLinkV1): PrivateBytes = publicationRecordGuard {
        val parsed = decode(value.exactUtf8)
        publicationRecordRequire(value.environment == parsed.environment && value.originBinding == parsed.originBinding &&
            value.originalCanonicalUserId == parsed.originalCanonicalUserId && value.commandId == parsed.commandId &&
            value.clientDraftId == parsed.clientDraftId && value.reviewedLocalRevision == parsed.reviewedLocalRevision &&
            value.originalCreatedAtMillis == parsed.originalCreatedAtMillis &&
            value.exactPostWrite.encodeUtf8().contentEquals(parsed.exactPostWrite.encodeUtf8()))
        val a = value.historicalReview; val b = parsed.historicalReview
        publicationRecordRequire(a.exactUtf8.copyForCodec().contentEquals(b.exactUtf8.copyForCodec()) &&
            a.clientDraftId == b.clientDraftId && a.reviewedLocalRevision == b.reviewedLocalRevision &&
            a.exactPostWrite.encodeUtf8().contentEquals(b.exactPostWrite.encodeUtf8()) &&
            snapshots.encode(a.exactReviewedLocalSnapshot).copyForCodec().contentEquals(b.exactReviewedLocalSnapshot.exactUtf8.copyForCodec()) &&
            a.displayedDisclosure.version == b.displayedDisclosure.version && a.displayedDisclosure.text == b.displayedDisclosure.text &&
            targetJson(a.target) == targetJson(b.target))
        value.exactUtf8
    }
    fun requireSameExactLink(first: PublicationOriginalLinkV1, second: PublicationOriginalLinkV1) {
        publicationRecordRequire(encode(first).copyForCodec().contentEquals(encode(second).copyForCodec()))
    }
    fun decode(bytes: PrivateBytes): PublicationOriginalLinkV1 = publicationRecordGuard {
        val root = publicationRecordJson(publicationRecordDocument(bytes, policy.maxRecordBytes))
        publicationRecordKeys(root, "format", "environment", "originBinding", "originalCanonicalUserId", "commandId", "clientDraftId",
            "reviewedLocalRevision", "originalCreatedAtMillis", "operationId", "pathParameters", "queryParameters", "ifMatch",
            "dependencyCommandIds", "exactPostWriteUtf8", "historicalReviewUtf8")
        publicationRecordRequire(publicationRecordString(root, "format") == "publication-original-link-v1" &&
            publicationRecordString(root, "operationId") == "publishPost" && root["pathParameters"] == JsonObject(emptyMap()) &&
            root["queryParameters"] == JsonObject(emptyMap()) && root["ifMatch"] == JsonNull && root["dependencyCommandIds"] == JsonArray(emptyList()))
        val environment = publicationRecordString(root, "environment"); val origin = publicationRecordString(root, "originBinding")
        val account = publicationRecordId(publicationRecordString(root, "originalCanonicalUserId"))
        PublicationJournalBindingData(environment, publicationRecordUuid(origin), origin, account)
        val command = publicationRecordId(publicationRecordString(root, "commandId"))
        val client = publicationRecordId(publicationRecordString(root, "clientDraftId"))
        publicationRecordRequire(command != client)
        val revision = publicationRecordLong(root.getValue("reviewedLocalRevision"), positive = true)
        val created = publicationRecordLong(root.getValue("originalCreatedAtMillis"))
        val body = publicationRecordDocument(publicationRecordString(root, "exactPostWriteUtf8"), policy.maxOriginalBytes)
        val original = ApiCall("publishPost", body = PrivateBytes(body.encodeUtf8()), idempotencyKey = SecretText(command))
        val expected = publicationRecordJson(adapter.expectedSelection(original))
        val input = publicationRecordJson(body)
        publicationRecordRequire(publicationRecordUuid(publicationRecordString(input, "clientDraftId")) == client)
        selectionBounds(input)
        val reviewBytes = PrivateBytes(publicationRecordString(root, "historicalReviewUtf8").encodeToByteArray(throwOnInvalidSequence = true))
        val review = publicationRecordJson(publicationRecordDocument(reviewBytes, policy.maxRecordBytes))
        publicationRecordKeys(review, "format", "clientDraftId", "reviewedLocalRevision", "exactPostWriteUtf8",
            "exactReviewedLocalSnapshotUtf8", "target", "displayedDisclosure")
        publicationRecordRequire(publicationRecordString(review, "format") == "publication-review-material-v1" &&
            publicationRecordString(review, "clientDraftId") == client &&
            publicationRecordLong(review.getValue("reviewedLocalRevision"), positive = true) == revision &&
            publicationRecordString(review, "exactPostWriteUtf8") == publicationRecordText(body))
        val snapshot = snapshots.decode(PrivateBytes(publicationRecordString(review, "exactReviewedLocalSnapshotUtf8")
            .encodeToByteArray(throwOnInvalidSequence = true)))
        publicationRecordRequire(snapshot.clientDraftId == client && snapshot.localRevision == revision)
        bindLocal(input, snapshot)
        val display = review.getValue("displayedDisclosure").jsonObject
        publicationRecordKeys(display, "version", "text")
        publicationRecordBytes(display, policy.maxDisclosureBytes)
        val disclosure = PublicationDisclosure(publicationRecordString(display, "version"), publicationRecordString(display, "text"))
        publicationRecordRequire(input["saveDisclosureVersion"] == JsonPrimitive(disclosure.version))
        (snapshot.content as? DraftLocalContentV1.ComposerV2)?.historicalDisclosureText?.let {
            publicationRecordRequire(it == disclosure.text)
        }
        val target = review.getValue("target").jsonObject
        val decodedTarget = when (publicationRecordString(target, "kind")) {
            "direct-local-v1" -> {
                publicationRecordKeys(target, "kind")
                publicationRecordRequire("draftId" !in input && "draftVersion" !in input &&
                    snapshot.serverAssociation === DraftServerAssociationV1.NotObserved)
                PublicationReviewTargetV1.DirectLocal
            }
            "saved-draft-v1" -> {
                publicationRecordKeys(target, "kind", "draftId", "draftVersion", "reviewedETag", "exactReviewedDraftUtf8")
                val id = publicationRecordId(publicationRecordString(target, "draftId"))
                val version = ExactPostVersion(publicationRecordString(target, "draftVersion"))
                val tag = publicationRecordString(target, "reviewedETag")
                publicationRecordRequire(tag == "\"${version.decimal}\"" &&
                    publicationRecordUuid(publicationRecordString(input, "draftId")) == id &&
                    publicationRecordBigint(input.getValue("draftVersion")) == version.decimal)
                val draft = publicationRecordDocument(publicationRecordString(target, "exactReviewedDraftUtf8"), policy.maxResponseBytes)
                publicationRecordSchema("PostDraft", draft)
                val baseline = publicationRecordJson(draft)
                publicationRecordRequire(publicationRecordString(baseline, "id") == id &&
                    publicationRecordString(baseline, "clientDraftId") == client &&
                    publicationRecordBigint(baseline.getValue("version")) == version.decimal &&
                    baseline["status"] == JsonPrimitive("draft") && "publishedPostId" !in baseline)
                val association = snapshot.serverAssociation as? DraftServerAssociationV1.Observed ?: publicationRecordInvalid()
                publicationRecordRequire(association.etag == tag && association.exactPostDraft.encodeUtf8().contentEquals(draft.encodeUtf8()))
                // Compare only actual service content fields, never server metadata or invented defaults.
                val selected = JsonObject(baseline.filterKeys { it in publicationRecordSelectionFields })
                publicationRecordRequire(publicationRecordSame(expected, normalizedSelection(selected)))
                PublicationReviewTargetV1.SavedDraft(id, version, tag, draft)
            }
            else -> publicationRecordInvalid()
        }
        val material = PublicationReviewMaterialV1(reviewBytes, client, revision, body, snapshot, decodedTarget, disclosure)
        PublicationOriginalLinkV1(bytes, environment, origin, account, command, client, revision, created, body, material)
    }

    private fun selectionBounds(input: JsonObject) {
        publicationRecordBound(input.getValue("mediaIds").jsonArray.size, policy.maxMediaSelections)
        publicationRecordBound(input.getValue("audience").jsonObject.getValue("circleIds").jsonArray.size, policy.maxCircleSelections)
        input["attachment"]?.jsonObject?.let {
            publicationRecordBytes(it, policy.maxAttachmentBytes)
            publicationRecordBound(it.getValue("confirmedChanges").jsonArray.size, policy.maxConfirmedChanges)
        }
    }
    private fun bindLocal(input: JsonObject, snapshot: DraftLocalSnapshotV1) {
        when (val content = snapshot.content) {
            is DraftLocalContentV1.TextV1 -> {
                publicationRecordRequire(input["caption"] == JsonPrimitive(content.caption) &&
                    input["altText"] == content.altText?.let(::JsonPrimitive))
            }
            is DraftLocalContentV1.ComposerV2 -> {
                val choices = publicationRecordJson(content.exactChoices)
                publicationRecordRequire(publicationRecordSame(choices,
                    JsonObject(input.filterKeys { it !in setOf("clientDraftId", "draftId", "draftVersion") })))
                content.historicalDisclosureText?.let { text ->
                    // Text participates in the complete explicit review below, not in PostWrite.
                    publicationRecordRequire(text.isNotBlank())
                }
            }
        }
    }
    private fun targetJson(value: PublicationReviewTargetV1): JsonObject = when (value) {
        PublicationReviewTargetV1.DirectLocal -> buildJsonObject { put("kind", "direct-local-v1") }
        is PublicationReviewTargetV1.SavedDraft -> buildJsonObject {
            put("kind", "saved-draft-v1"); put("draftId", value.draftId); put("draftVersion", value.draftVersion.decimal)
            put("reviewedETag", value.reviewedETag); put("exactReviewedDraftUtf8", publicationRecordText(value.exactReviewedDraft))
        }
    }
    private fun normalizedSelection(value: JsonObject): JsonObject {
        val fields = value.toMutableMap()
        fields["mediaIds"]?.let { fields["mediaIds"] = JsonArray(it.jsonArray.map { id -> JsonPrimitive(publicationRecordUuid(id.jsonPrimitive.content)) }) }
        fields["audience"]?.jsonObject?.let { a -> fields["audience"] = JsonObject(a.filterKeys { it != "bindings" } +
            ("circleIds" to JsonArray(a.getValue("circleIds").jsonArray.map { JsonPrimitive(publicationRecordUuid(it.jsonPrimitive.content)) }))) }
        fields["sourcePostId"]?.let { fields["sourcePostId"] = JsonPrimitive(publicationRecordUuid(it.jsonPrimitive.content)) }
        fields["attachment"]?.jsonObject?.let { a -> fields["attachment"] = JsonObject(a.mapValues { (key, v) ->
            if (key in setOf("recipeVersionId", "planId")) JsonPrimitive(publicationRecordUuid(v.jsonPrimitive.content)) else v }) }
        return JsonObject(fields)
    }
    override fun toString() = "PublicationOriginalLinkCodecV1(<redacted>)"
}

/** Historical actual reply data only. Construction/correlation cannot mint queue provenance. */
internal class HistoricalPublicationReplyV1(val exactPost: WireDocument, val etag: String,
    val contentType: String, val traceId: String?, val retryAfterSeconds: Long?) {
    override fun toString() = "HistoricalPublicationReplyV1(<redacted>)"
}
internal sealed class PublicationHistoryEntryV1 private constructor(val original: PublicationOriginalLinkV1) {
    class PendingOriginal(original: PublicationOriginalLinkV1) : PublicationHistoryEntryV1(original)
    class PublishedHistorical(original: PublicationOriginalLinkV1, val reply: HistoricalPublicationReplyV1) : PublicationHistoryEntryV1(original)
    class CancelledUnsentHistorical(original: PublicationOriginalLinkV1) : PublicationHistoryEntryV1(original)
    final override fun toString() = "PublicationHistoryEntryV1(<redacted>)"
}
internal class PublicationJournalV1(val binding: PublicationJournalBindingData, val clock: Long,
    issuedCommandIds: List<String>, entries: List<PublicationHistoryEntryV1>) {
    private val ids = issuedCommandIds.also { publicationRecordBound(it.size, 4096) }.toList()
    private val history = entries.also { publicationRecordBound(it.size, 4096) }.toList()
    val issuedCommandIds: List<String> get() = ids.toList()
    val entries: List<PublicationHistoryEntryV1> get() = history.toList()
    override fun toString() = "PublicationJournalV1(<redacted>)"
}

internal class PostPublicationJournalCodecV1(private val binding: PublicationJournalBindingData,
    private val policy: PostPublicationClientPolicy, private val links: PublicationOriginalLinkCodecV1) {
    private val adapter = PostPublicationAdapter(policy.maxResponseBytes)
    fun encode(value: PublicationJournalV1): PrivateBytes = publicationRecordGuard {
        requireBinding(value.binding)
        publicationRecordBound(value.issuedCommandIds.size, policy.maxIssuedCommandIds)
        value.issuedCommandIds.forEach(::publicationRecordId)
        publicationRecordBound(value.entries.size, policy.maxIssuedCommandIds)
        publicationRecordBound(value.entries.map { it.original.clientDraftId }.distinct().size, policy.maxRetainedRoots)
        var rawMinimum = 0L
        for (entry in value.entries) {
            rawMinimum += entry.original.exactUtf8.copyForCodec().size
            if (entry is PublicationHistoryEntryV1.PublishedHistorical) {
                val size = entry.reply.exactPost.encodeUtf8().size
                publicationRecordBound(size, policy.maxResponseBytes); rawMinimum += size
                val reply = entry.reply
                publicationRecordRequire(reply.etag == "\"1\"" && (reply.retryAfterSeconds == null || reply.retryAfterSeconds >= 1))
                for (header in listOfNotNull(reply.contentType, reply.traceId))
                    publicationRecordRequire(header.length in 1..256 && header.isNotBlank() && header.none(Char::isISOControl))
            }
            if (rawMinimum > policy.maxRecordBytes) mealFail(FailureReason.UNAVAILABLE)
        }
        val bytes = publicationRecordBytes(envelope(value), policy.maxRecordBytes)
        decode(1, bytes); bytes
    }
    fun decode(schemaVersion: Int, payload: PrivateBytes): PublicationJournalV1 = publicationRecordGuard {
        publicationRecordRequire(schemaVersion == 1)
        val root = publicationRecordJson(publicationRecordDocument(payload, policy.maxRecordBytes))
        publicationRecordKeys(root, "schema", "origin", "environment", "originalCanonicalUserId", "clock", "issuedCommandIds", "entries")
        publicationRecordRequire(publicationRecordLong(root.getValue("schema")) == 1L &&
            publicationRecordString(root, "origin") == binding.canonicalOrigin &&
            publicationRecordString(root, "environment") == binding.environment &&
            publicationRecordString(root, "originalCanonicalUserId") == binding.originalCanonicalUserId)
        val clock = publicationRecordLong(root.getValue("clock"))
        val ids = root.getValue("issuedCommandIds").jsonArray.map { publicationRecordId(publicationRecordString(it)) }
        val entries = root.getValue("entries").jsonArray.map { item ->
            val entry = item.jsonObject; val kind = publicationRecordString(entry, "kind")
            publicationRecordKeys(entry, *if (kind == "published-historical-v1") arrayOf("kind", "originalLinkUtf8", "reply") else arrayOf("kind", "originalLinkUtf8"))
            val link = links.decode(PrivateBytes(publicationRecordString(entry, "originalLinkUtf8").encodeToByteArray(throwOnInvalidSequence = true)))
            publicationRecordRequire(link.environment == binding.environment && link.originBinding == binding.exactOriginBinding &&
                link.originalCanonicalUserId == binding.originalCanonicalUserId && link.originalCreatedAtMillis <= clock)
            when (kind) {
                "pending-original-v1" -> PublicationHistoryEntryV1.PendingOriginal(link)
                "cancelled-unsent-historical-v1" -> PublicationHistoryEntryV1.CancelledUnsentHistorical(link)
                "published-historical-v1" -> {
                    val r = entry.getValue("reply").jsonObject
                    publicationRecordKeys(r, "status", "exactPostUtf8", "etag", "contentType", "traceId", "retryAfterSeconds")
                    publicationRecordRequire(publicationRecordLong(r.getValue("status")) == 201L)
                    val doc = publicationRecordDocument(publicationRecordString(r, "exactPostUtf8"), policy.maxResponseBytes)
                    val tag = publicationRecordString(r, "etag"); val media = publicationRecordString(r, "contentType")
                    val trace = publicationRecordNullableString(r.getValue("traceId"))
                    val retry = r.getValue("retryAfterSeconds").takeUnless { it == JsonNull }?.let { publicationRecordLong(it, positive = true) }
                    // Pure historical validation only. This is never an ActualPublicationReceipt.
                    adapter.receipt(link.originalIntentForComparison().call, link.originalCanonicalUserId,
                        ApiReply(201, PrivateBytes(doc.encodeUtf8()), tag, trace, retry, media))
                    PublicationHistoryEntryV1.PublishedHistorical(link, HistoricalPublicationReplyV1(doc, tag, media, trace, retry))
                }
                else -> publicationRecordInvalid()
            }
        }
        publicationRecordBound(ids.size, policy.maxIssuedCommandIds)
        publicationRecordBound(entries.map { it.original.clientDraftId }.distinct().size, policy.maxRetainedRoots)
        publicationRecordRequire(ids.distinct().size == ids.size && ids == entries.map { it.original.commandId } &&
            entries.count { it is PublicationHistoryEntryV1.PendingOriginal } <= 1)
        publicationRecordRequire(entries.indexOfFirst { it is PublicationHistoryEntryV1.PendingOriginal }
            .let { it < 0 || it == entries.lastIndex })
        val roots = entries.map { it.original.clientDraftId }.toSet()
        publicationRecordRequire(ids.none { it in roots })
        val closed = mutableSetOf<String>(); val postIds = mutableSetOf<String>()
        for (entry in entries) {
            publicationRecordRequire(entry.original.clientDraftId !in closed)
            if (entry is PublicationHistoryEntryV1.PublishedHistorical) {
                closed += entry.original.clientDraftId
                publicationRecordRequire(postIds.add(publicationRecordString(publicationRecordJson(entry.reply.exactPost), "id")))
            }
        }
        PublicationJournalV1(binding, clock, ids, entries)
    }

    /** Pure upper bound for replacing the one pending entry with a largest allowed actual reply.
     * Includes JSON string escaping and bounded ApiReply metadata. Never fabricates a Post or ACK.
     * Caller/draft owner must additionally reserve its own terminal/link/remainder row/counts. */
    fun reservedPublishedBytes(value: PublicationJournalV1): Long = publicationRecordGuard {
        val encoded = encode(value).copyForCodec().size.toLong()
        val pending = value.entries.filterIsInstance<PublicationHistoryEntryV1.PendingOriginal>().singleOrNull()
            ?: publicationRecordInvalid()
        val prior = entryJson(pending).toString().encodeToByteArray().size.toLong()
        val skeleton = buildJsonObject {
            put("kind", "published-historical-v1"); put("originalLinkUtf8", publicationRecordText(pending.original.exactUtf8))
            put("reply", buildJsonObject {
                put("status", 201); put("exactPostUtf8", ""); put("etag", "\"1\""); put("contentType", "")
                put("traceId", ""); put("retryAfterSeconds", Long.MAX_VALUE)
            })
        }.toString().encodeToByteArray().size.toLong()
        // Header strings are <=256 UTF-16 units; <=1024 UTF-8 bytes is a conservative bound.
        val result = encoded - prior + skeleton + 6L * policy.maxResponseBytes + 6L * 1024 * 2
        publicationRecordRequire(result >= encoded - prior && result > 0)
        result
    }
    fun requireReservedPublishedCapacity(value: PublicationJournalV1) {
        if (reservedPublishedBytes(value) > policy.maxRecordBytes) mealFail(FailureReason.UNAVAILABLE)
    }
    private fun requireBinding(value: PublicationJournalBindingData) {
        publicationRecordRequire(value.environment == binding.environment && value.canonicalOrigin == binding.canonicalOrigin &&
            value.exactOriginBinding == binding.exactOriginBinding && value.originalCanonicalUserId == binding.originalCanonicalUserId)
    }
    private fun envelope(value: PublicationJournalV1) = buildJsonObject {
        put("schema", 1); put("origin", value.binding.canonicalOrigin); put("environment", value.binding.environment)
        put("originalCanonicalUserId", value.binding.originalCanonicalUserId); put("clock", value.clock)
        put("issuedCommandIds", JsonArray(value.issuedCommandIds.map(::JsonPrimitive))); put("entries", JsonArray(value.entries.map(::entryJson)))
    }
    private fun entryJson(value: PublicationHistoryEntryV1) = buildJsonObject {
        put("kind", when (value) {
            is PublicationHistoryEntryV1.PendingOriginal -> "pending-original-v1"
            is PublicationHistoryEntryV1.PublishedHistorical -> "published-historical-v1"
            is PublicationHistoryEntryV1.CancelledUnsentHistorical -> "cancelled-unsent-historical-v1"
        })
        put("originalLinkUtf8", publicationRecordText(links.encode(value.original)))
        if (value is PublicationHistoryEntryV1.PublishedHistorical) put("reply", buildJsonObject {
            val r = value.reply
            put("status", 201); put("exactPostUtf8", publicationRecordText(r.exactPost)); put("etag", r.etag); put("contentType", r.contentType)
            put("traceId", r.traceId?.let(::JsonPrimitive) ?: JsonNull); put("retryAfterSeconds", r.retryAfterSeconds?.let(::JsonPrimitive) ?: JsonNull)
        })
    }
    override fun toString() = "PostPublicationJournalCodecV1(<redacted>)"
}

internal val publicationRecordSelectionFields = setOf("caption", "altText", "mediaIds", "audience", "keepOnPlate", "attachment", "allowRecipeSaves", "saveDisclosureVersion", "sourcePostId")
internal fun publicationRecordInvalid(): Nothing = mealFail(FailureReason.INVALID_DATA)
internal fun publicationRecordRequire(value: Boolean) { if (!value) publicationRecordInvalid() }
internal fun publicationRecordBound(value: Int, max: Int) { if (value > max) mealFail(FailureReason.UNAVAILABLE) }
internal fun publicationRecordId(value: String): String = publicationRecordUuid(value).also { publicationRecordRequire(it == value) }
internal fun publicationRecordUuid(value: String): String {
    publicationRecordRequire(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
    return value.lowercase()
}
internal fun publicationRecordString(value: JsonElement): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: publicationRecordInvalid()
internal fun publicationRecordString(value: JsonObject, field: String) = publicationRecordString(value[field] ?: publicationRecordInvalid())
internal fun publicationRecordNullableString(value: JsonElement) = if (value == JsonNull) null else publicationRecordString(value)
internal fun publicationRecordLong(value: JsonElement, positive: Boolean = false): Long {
    val primitive = value as? JsonPrimitive ?: publicationRecordInvalid()
    publicationRecordRequire(!primitive.isString && primitive.content.matches(Regex(if (positive) "[1-9][0-9]*" else "0|[1-9][0-9]*")))
    return primitive.content.toLongOrNull() ?: publicationRecordInvalid()
}
internal fun publicationRecordKeys(value: JsonObject, vararg fields: String) { publicationRecordRequire(value.keys == fields.toSet()) }
internal fun publicationRecordJson(value: WireDocument): JsonObject = Json.parseToJsonElement(value.encodeUtf8().decodeToString()).jsonObject
internal fun publicationRecordText(value: WireDocument) = value.encodeUtf8().decodeToString(throwOnInvalidSequence = true)
internal fun publicationRecordText(value: PrivateBytes) = value.copyForCodec().decodeToString(throwOnInvalidSequence = true)
internal fun publicationRecordDocument(value: String, limit: Int) = publicationRecordDocument(PrivateBytes(value.encodeToByteArray(throwOnInvalidSequence = true)), limit)
internal fun publicationRecordDocument(value: PrivateBytes, limit: Int) = WireDocument.decode(value.copyForCodec(), WireLimits(limit, 64))
internal fun publicationRecordBytes(value: JsonObject, limit: Int): PrivateBytes = PrivateBytes(publicationRecordDocument(value.toString(), limit).encodeUtf8())
internal fun publicationRecordSchema(name: String, value: WireDocument) {
    when (val checked = publicationSchemas.validateSchema(name, value.encodeUtf8())) {
        ContractValidationResult.Valid -> Unit
        is ContractValidationResult.Rejected -> mealFail(if (checked.reason == ContractRejectionReason.RESOURCE_LIMIT) FailureReason.UNAVAILABLE else FailureReason.INVALID_DATA)
    }
}
internal inline fun <T> publicationRecordGuard(action: () -> T): T = try { action() }
catch (failure: CancellationException) { throw failure }
catch (failure: MealFailure) { throw failure }
catch (failure: WireDecodingException) { mealFail(if (failure.reason in setOf(WireFailure.BYTE_LIMIT, WireFailure.DEPTH_LIMIT, WireFailure.NUMBER_LIMIT)) FailureReason.UNAVAILABLE else FailureReason.INVALID_DATA) }
catch (_: Exception) { publicationRecordInvalid() }

/** Already canonical-schema-profiled numeric values only. No expansion, rounding or Float. */
internal fun publicationRecordNumberKey(value: JsonPrimitive): String {
    publicationRecordRequire(!value.isString && value != JsonNull && value.booleanOrNull == null)
    val parts = value.content.lowercase().split('e'); val mantissa = parts[0]
    val exponent = if (parts.size == 1) 0 else parts[1].toIntOrNull() ?: publicationRecordInvalid()
    val allDigits = mantissa.removePrefix("-").replace(".", "")
    val digits = allDigits.trimStart('0').trimEnd('0'); if (digits.isEmpty()) return "0e0"
    val trailing = allDigits.length - allDigits.trimEnd('0').length
    val scale = exponent - mantissa.substringAfter('.', "").length + trailing
    return "${if (mantissa.startsWith('-')) "-" else ""}${digits}e$scale"
}
internal fun publicationRecordBigint(value: JsonElement): String {
    val key = publicationRecordNumberKey(value.jsonPrimitive); val digits = key.substringBefore('e'); val exponent = key.substringAfter('e').toInt()
    publicationRecordRequire(digits != "0" && !digits.startsWith('-') && exponent >= 0 && digits.length.toLong() + exponent <= 19)
    val integer = digits + "0".repeat(exponent)
    publicationRecordRequire(integer.length < 19 || integer <= "9223372036854775807")
    return integer
}
internal fun publicationRecordSame(a: JsonElement?, b: JsonElement?): Boolean = when {
    a == null || b == null -> a == null && b == null
    a == JsonNull || b == JsonNull -> a == JsonNull && b == JsonNull
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key, v) -> publicationRecordSame(v, b[key]) }
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { publicationRecordSame(a[it], b[it]) }
    a is JsonPrimitive && b is JsonPrimitive -> when {
        a.isString || b.isString -> a.isString && b.isString && a.content == b.content
        a.booleanOrNull != null || b.booleanOrNull != null -> a.booleanOrNull != null && a.booleanOrNull == b.booleanOrNull
        else -> publicationRecordNumberKey(a) == publicationRecordNumberKey(b)
    }
    else -> false
}
