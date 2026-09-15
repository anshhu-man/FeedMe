package com.feedme.development.progress

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.transport.MobileRequestValidator
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/** Strict service data only. No decoded record is a client review, admission or delivery proof. */
internal class ProgressPostServiceCodec(private val scope: StorageScope, private val origin: String) {
    private val validator = CanonicalBodyValidator.bundled()
    private val requests = MobileRequestValidator()

    fun fresh(now: Long): JsonObject = buildJsonObject {
        put("format", "progress-post-service-v1"); put("configuration", ProgressIdentity.configurationBinding)
        put("environment", scope.environment); put("actorKind", scope.actorKind.name); put("actorId", scope.actorId)
        put("origin", origin); put("authorId", ProgressPostPreviewContract.authorId)
        put("disclosureVersion", ProgressPostPreviewContract.disclosureVersion)
        put("lastMillis", now); put("head", 0)
        put("cursorSecret", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
        put("roots", JsonObject(emptyMap())); put("receipts", JsonObject(emptyMap()))
    }

    fun decode(record: PrivateRecord): JsonObject {
        if (record.schemaVersion != 1 || record.revision <= 0) postFail(FailureReason.STORAGE_FAILURE)
        return decode(record.payload.copyForCodec())
    }

    fun encode(state: JsonObject): PrivateBytes {
        val bytes = state.toString().encodeToByteArray()
        if (bytes.size > ProgressPostPreviewContract.maxRecordBytes) postFail(FailureReason.UNAVAILABLE)
        decode(bytes)
        return PrivateBytes(bytes)
    }

    private fun decode(bytes: ByteArray): JsonObject {
        try {
            val state = WireDocument.decode(bytes, WireLimits(maxBytes = ProgressPostPreviewContract.maxRecordBytes, maxDepth = 32)).postJson()
            state.postKeys("format", "configuration", "environment", "actorKind", "actorId", "origin", "authorId", "disclosureVersion", "lastMillis", "head", "cursorSecret", "roots", "receipts")
            if (state.postString("format") != "progress-post-service-v1" || scope != ProgressIdentity.scope ||
                state.postString("configuration") != ProgressIdentity.configurationBinding || state.postString("origin") != origin ||
                postUuid(origin) != origin || state.postString("environment") != scope.environment ||
                state.postString("actorKind") != scope.actorKind.name || state.postString("actorId") != scope.actorId ||
                state.postString("authorId") != ProgressPostPreviewContract.authorId ||
                state.postString("disclosureVersion") != ProgressPostPreviewContract.disclosureVersion ||
                state.postLong("lastMillis") < 0 || state.postLong("head") < 0 ||
                !state.postString("cursorSecret").matches(Regex("[0-9a-f]{64}"))) postFail(FailureReason.STORAGE_FAILURE)
            val roots = state.postObject("roots"); val receipts = state.postObject("receipts")
            if (roots.size > ProgressPostPreviewContract.maxRoots || receipts.size > ProgressPostPreviewContract.maxCommands)
                postFail(FailureReason.STORAGE_FAILURE)
            if (state.postLong("head") != receipts.values.count { it.jsonObject.postObject("reply").postLong("status") in 200L..299L }.toLong())
                postFail(FailureReason.STORAGE_FAILURE)
            val resourceIds = mutableSetOf<String>()
            for ((rootId, entry) in roots) {
                if (postUuid(rootId) != rootId) postFail(FailureReason.STORAGE_FAILURE)
                val root = entry.jsonObject; root.postKeys("draft", "post", "createKey", "deleteKey", "publishKey")
                val draft = root.postOptionalObject("draft"); val post = root.postOptionalObject("post")
                if (draft == null && post == null) postFail(FailureReason.STORAGE_FAILURE)
                if (draft != null) {
                    schema("PostDraft", draft); boundedDocument(draft)
                    if (draft.postString("clientDraftId") != rootId || postUuid(draft.postString("id")) != draft.postString("id") ||
                        draft.postObject("audience") != audience() || !resourceIds.add(draft.postString("id"))) postFail(FailureReason.STORAGE_FAILURE)
                    validateTimes(draft, state.postLong("lastMillis")); postVersion(draft)
                    validateSelection(selection(draft), requireDisclosure = false)
                    if (draft.postString("status") !in setOf("draft", "discarded", "published")) postFail(FailureReason.STORAGE_FAILURE)
                    if ((draft.postString("status") == "published") != (post != null) ||
                        (draft.postString("status") == "discarded") != (root.postOptionalString("deleteKey") != null)) postFail(FailureReason.STORAGE_FAILURE)
                    if (post != null && draft.postString("publishedPostId") != post.postString("id")) postFail(FailureReason.STORAGE_FAILURE)
                    if (post == null && "publishedPostId" in draft) postFail(FailureReason.STORAGE_FAILURE)
                    requireReceipt(receipts, root.postString("createKey"), "createPostDraft", rootId)
                    root.postOptionalString("deleteKey")?.let { requireReceipt(receipts, it, "deletePostDraft", rootId) }
                    val changes = receipts.values.count { it.jsonObject.postOptionalString("root") == rootId &&
                        it.jsonObject.postObject("reply").postLong("status") in 200L..299L }
                    if (postVersion(draft) != BigInteger.valueOf(changes.toLong())) postFail(FailureReason.STORAGE_FAILURE)
                    val history = receipts.values.map { it.jsonObject }.filter {
                        it.postOptionalString("root") == rootId && it.postObject("reply").postLong("status") in 200L..299L &&
                            it.postObject("original").postString("operation") in setOf("createPostDraft", "updatePostDraft")
                    }.map { call(it.postObject("original")) to WireDocument.decode(checkNotNull(decodeReply(it.postObject("reply")).body).copyForCodec()).postJson() }
                        .sortedBy { postVersion(it.second) }
                    if (history.isEmpty() || history.map { postVersion(it.second) } != (1..history.size).map { BigInteger.valueOf(it.toLong()) })
                        postFail(FailureReason.STORAGE_FAILURE)
                    var previous: JsonObject? = null
                    for ((original, response) in history) {
                        validateTimes(response, state.postLong("lastMillis"))
                        val input = WireDocument.decode(checkNotNull(original.body).copyForCodec()).postJson()
                        val before = previous
                        val expectedResponse = if (before == null) {
                            if (original.operationId != "createPostDraft" || postUuid(input.postString("clientDraftId")) != rootId)
                                postFail(FailureReason.STORAGE_FAILURE)
                            JsonObject(draftContent(null, input) + mapOf("id" to response.getValue("id"), "version" to JsonPrimitive(1),
                                "clientDraftId" to JsonPrimitive(rootId), "status" to JsonPrimitive("draft"),
                                "createdAt" to response.getValue("createdAt"), "updatedAt" to response.getValue("createdAt"),
                                "expiresAt" to response.getValue("expiresAt")))
                        } else {
                            if (original.operationId != "updatePostDraft" ||
                                ("clientDraftId" in input && postUuid(input.postString("clientDraftId")) != rootId) ||
                                postTime(response.postString("updatedAt")) < postTime(before.postString("updatedAt")) ||
                                postTime(response.postString("updatedAt")) >= postTime(before.postString("expiresAt")))
                                postFail(FailureReason.STORAGE_FAILURE)
                            JsonObject(before + draftContent(selection(before), input) + mapOf(
                                "version" to JsonPrimitive(postVersion(before) + BigInteger.ONE),
                                "updatedAt" to response.getValue("updatedAt"), "expiresAt" to response.getValue("expiresAt")))
                        }
                        if (response != expectedResponse) postFail(FailureReason.STORAGE_FAILURE)
                        previous = response
                    }
                    val latest = history.last().second
                    val expected = when (draft.postString("status")) {
                        "draft" -> latest
                        "discarded" -> JsonObject(latest + mapOf("status" to JsonPrimitive("discarded"),
                            "version" to JsonPrimitive(postVersion(latest) + BigInteger.ONE), "updatedAt" to draft.getValue("updatedAt")))
                        "published" -> JsonObject(latest + mapOf("status" to JsonPrimitive("published"),
                            "version" to JsonPrimitive(postVersion(latest) + BigInteger.ONE), "updatedAt" to checkNotNull(post).getValue("publishedAt"),
                            "publishedPostId" to post.getValue("id")))
                        else -> postFail(FailureReason.STORAGE_FAILURE)
                    }
                    if (draft != expected || (post != null && selection(draft) != postSelection(post))) postFail(FailureReason.STORAGE_FAILURE)
                } else if (root["createKey"] != JsonNull || root["deleteKey"] != JsonNull) postFail(FailureReason.STORAGE_FAILURE)
                if (post != null) {
                    schema("Post", post); boundedDocument(post); validateTimes(post, state.postLong("lastMillis"))
                    if (postUuid(post.postString("id")) != post.postString("id") || !resourceIds.add(post.postString("id")) ||
                        post.postObject("audience") != JsonObject(audience() + ("bindings" to JsonArray(emptyList()))) ||
                        postVersion(post) != BigInteger.ONE || post.postString("status") != "published" ||
                        post.postObject("author") != author() || post["aclVersion"] != JsonPrimitive(1) ||
                        post["capabilities"] != JsonArray(listOf(JsonPrimitive("delete"), JsonPrimitive("view"))) ||
                        post["reactionCounts"] != JsonArray(emptyList()) || post["publishedAt"] != post["createdAt"] || post["updatedAt"] != post["createdAt"] ||
                        post.postObject("savePolicy") != savePolicy()) postFail(FailureReason.STORAGE_FAILURE)
                    val selected = selection(post) + mapOf("allowRecipeSaves" to JsonPrimitive(false), "saveDisclosureVersion" to JsonPrimitive(ProgressPostPreviewContract.disclosureVersion))
                    validateSelection(JsonObject(selected), true)
                    val receipt = requireReceipt(receipts, root.postString("publishKey"), "publishPost", rootId)
                    if (decodeReply(receipt.postObject("reply")).body?.copyForCodec()?.let { WireDocument.decode(it).postJson() } != post) postFail(FailureReason.STORAGE_FAILURE)
                } else if (root["publishKey"] != JsonNull) postFail(FailureReason.STORAGE_FAILURE)
            }
            for ((key, entry) in receipts) {
                if (!key.matches(Regex("[0-9a-f]{64}"))) postFail(FailureReason.STORAGE_FAILURE)
                val receipt = entry.jsonObject; receipt.postKeys("original", "reply", "root")
                val original = receipt.postObject("original"); val call = call(original)
                if (call.operationId !in ProgressPostPreviewContract.commands || !requests.accepts(call, PrincipalClass.ACCOUNT) ||
                    call.idempotencyKey?.use { postHash(postUuid(it)) } != key) postFail(FailureReason.STORAGE_FAILURE)
                val reply = decodeReply(receipt.postObject("reply")); validateReply(call.operationId, reply)
                if (reply.status in 200..299) {
                    val rootId = receipt.postString("root"); val root = roots[rootId]?.jsonObject ?: postFail(FailureReason.STORAGE_FAILURE)
                    val response = reply.body?.copyForCodec()?.let { WireDocument.decode(it).postJson() }
                    val input = call.body?.copyForCodec()?.let { WireDocument.decode(it).postJson() }
                    when (call.operationId) {
                        "createPostDraft", "updatePostDraft" -> {
                            val draft = root.postObject("draft")
                            if (response == null || response.postString("id") != draft.postString("id") || response.postString("clientDraftId") != rootId ||
                                response.postString("status") != "draft" || postVersion(response) > postVersion(draft) || reply.etag != postEtag(response)) postFail(FailureReason.STORAGE_FAILURE)
                            if (call.operationId == "createPostDraft" && (postVersion(response) != BigInteger.ONE || input?.postString("clientDraftId")?.let(::postUuid) != rootId)) postFail(FailureReason.STORAGE_FAILURE)
                            if (call.operationId == "updatePostDraft" && (postUuid(call.pathParameters.getValue("draftId")) != draft.postString("id") ||
                                    postMatchVersion(checkNotNull(call.ifMatch)) + BigInteger.ONE != postVersion(response))) postFail(FailureReason.STORAGE_FAILURE)
                        }
                        "deletePostDraft" -> if (reply.status != 204 || reply.body != null || root.postString("deleteKey") != key ||
                            root.postObject("draft").postString("status") != "discarded" ||
                            postMatchVersion(checkNotNull(call.ifMatch)) + BigInteger.ONE != postVersion(root.postObject("draft"))) postFail(FailureReason.STORAGE_FAILURE)
                        "publishPost" -> {
                            if (response == null || response != root.postObject("post") || reply.status != 201 || reply.etag != postEtag(response) ||
                                root.postString("publishKey") != key || input == null || postUuid(input.postString("clientDraftId")) != rootId ||
                                selection(input) != postSelection(response)) postFail(FailureReason.STORAGE_FAILURE)
                            val draft = root.postOptionalObject("draft")
                            if (draft == null) { if ("draftId" in input || "draftVersion" in input) postFail(FailureReason.STORAGE_FAILURE) }
                            else if (postUuid(input.postString("draftId")) != draft.postString("id") ||
                                postInteger(input.getValue("draftVersion")) + BigInteger.ONE != postVersion(draft)) postFail(FailureReason.STORAGE_FAILURE)
                        }
                    }
                } else if (receipt["root"] != JsonNull) postFail(FailureReason.STORAGE_FAILURE)
            }
            return state
        } catch (failure: PreviewServiceFailure) { throw failure }
        catch (_: Exception) { postFail(FailureReason.STORAGE_FAILURE) }
    }

    fun validateRequest(call: ApiCall): Boolean = call.operationId in ProgressPostPreviewContract.operations &&
        (call.body?.copyForCodec()?.size ?: 0) <= ProgressPostPreviewContract.maxRequestBytes && requests.accepts(call, PrincipalClass.ACCOUNT) &&
        validator.validateRequest(call.operationId, call.body?.copyForCodec(), if (call.body == null) null else "application/json") == ContractValidationResult.Valid

    /** Actual proposed body only. No dummy command/key is invented for a read-only prerequisite. */
    fun publicationInput(exact: PrivateBytes): JsonObject {
        val bytes = exact.copyForCodec()
        if (bytes.size > ProgressPostPreviewContract.maxRequestBytes || validator.validateSchema("PostWrite", bytes) != ContractValidationResult.Valid)
            postFail(FailureReason.INVALID_DATA)
        return WireDocument.decode(bytes).postJson()
    }

    fun validateReply(operation: String, reply: ApiReply) {
        val limit = if (operation == "listPostDrafts" && reply.status == 200) ProgressPostPreviewContract.maxPageBytes else ProgressPostPreviewContract.maxResponseBytes
        if ((reply.body?.copyForCodec()?.size ?: 0) > limit ||
            CanonicalResponseBinder(validator).bind(operation, reply.status, reply.body?.copyForCodec(), reply.contentType, reply.traceId) !is ResponseBindingResult.Accepted)
            postFail(FailureReason.INVALID_DATA)
    }

    fun selection(value: JsonObject): JsonObject = JsonObject(value.filterKeys { it in CONTENT_KEYS }.mapValues { (key, element) ->
        if (key == "audience") JsonObject(element.jsonObject.filterKeys { it != "bindings" }) else element
    })

    fun validateSelection(value: JsonObject, requireDisclosure: Boolean) {
        if (value.keys.any { it !in CONTENT_KEYS } || value.postObject("audience") != audience() ||
            value["mediaIds"] != JsonArray(emptyList()) || value["allowRecipeSaves"] != JsonPrimitive(false) ||
            value["keepOnPlate"]?.jsonPrimitive?.booleanOrNull == null || "attachment" in value || "sourcePostId" in value ||
            (requireDisclosure && value["saveDisclosureVersion"] != JsonPrimitive(ProgressPostPreviewContract.disclosureVersion)) ||
            ("saveDisclosureVersion" in value && value.postString("saveDisclosureVersion") != ProgressPostPreviewContract.disclosureVersion))
            throw ProgressPostProblem(422, "INPUT_INVALID")
    }

    /** Same pure merge for actual execution and strict retained original/response reconstruction. */
    fun draftContent(previous: JsonObject?, patch: JsonObject): JsonObject {
        if (patch["removeAttachment"] == JsonPrimitive(true) && "attachment" in patch) throw ProgressPostProblem(422, "INPUT_INVALID")
        val defaults = buildJsonObject {
            put("caption", ""); put("mediaIds", JsonArray(emptyList())); put("audience", audience())
            put("keepOnPlate", false); put("allowRecipeSaves", false)
        }
        val merged = JsonObject((previous ?: defaults) + selection(patch))
        validateSelection(merged, false)
        return merged
    }

    fun original(call: ApiCall): JsonObject = buildJsonObject {
        put("operation", call.operationId); put("origin", origin); put("device", ProgressIdentity.deviceSessionId)
        put("idempotencyKey", call.idempotencyKey?.use { JsonPrimitive(it) } ?: JsonNull)
        put("path", JsonObject(call.pathParameters.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        put("query", JsonObject(call.queryParameters.toSortedMap().mapValues { JsonArray(it.value.map(::JsonPrimitive)) }))
        put("ifMatch", call.ifMatch?.let(::JsonPrimitive) ?: JsonNull)
        put("body", call.body?.let { JsonPrimitive(postBase64(it.copyForCodec())) } ?: JsonNull)
    }

    private fun call(value: JsonObject): ApiCall {
        value.postKeys("operation", "origin", "device", "idempotencyKey", "path", "query", "ifMatch", "body")
        if (value.postString("origin") != origin || value.postString("device") != ProgressIdentity.deviceSessionId) postFail(FailureReason.STORAGE_FAILURE)
        val bytes = value.postOptionalString("body")?.let(::postUnbase64)
        if ((bytes?.size ?: 0) > ProgressPostPreviewContract.maxRequestBytes) postFail(FailureReason.STORAGE_FAILURE)
        return ApiCall(value.postString("operation"), value.postObject("path").mapValues { it.value.jsonPrimitive.content },
            value.postObject("query").mapValues { it.value.jsonArray.map { item -> item.jsonPrimitive.content } },
            bytes?.let(::PrivateBytes), SecretText(value.postString("idempotencyKey")), value.postOptionalString("ifMatch")).also {
            if (!validateRequest(it) || original(it) != value) postFail(FailureReason.STORAGE_FAILURE)
        }
    }

    fun encodeReply(value: ApiReply): JsonObject = buildJsonObject {
        put("status", value.status); put("body", value.body?.let { JsonPrimitive(postBase64(it.copyForCodec())) } ?: JsonNull)
        put("etag", value.etag?.let(::JsonPrimitive) ?: JsonNull); put("trace", value.traceId?.let(::JsonPrimitive) ?: JsonNull)
        put("media", value.contentType?.let(::JsonPrimitive) ?: JsonNull)
    }
    fun decodeReply(value: JsonObject): ApiReply {
        value.postKeys("status", "body", "etag", "trace", "media")
        val status = value.postLong("status")
        if (status !in 100L..599L) postFail(FailureReason.STORAGE_FAILURE)
        return ApiReply(status.toInt(), value.postOptionalString("body")?.let { PrivateBytes(postUnbase64(it)) },
            value.postOptionalString("etag"), traceId = value.postOptionalString("trace"), contentType = value.postOptionalString("media"))
    }
    private fun schema(name: String, value: JsonObject) {
        if (validator.validateSchema(name, value.toString().encodeToByteArray()) != ContractValidationResult.Valid) postFail(FailureReason.STORAGE_FAILURE)
    }
    private fun boundedDocument(value: JsonObject) {
        if (value.toString().encodeToByteArray().size > ProgressPostPreviewContract.maxResponseBytes) postFail(FailureReason.STORAGE_FAILURE)
    }
    private fun validateTimes(value: JsonObject, clock: Long) {
        val created = postTime(value.postString("createdAt")); val updated = postTime(value.postString("updatedAt")); val expiry = postTime(value.postString("expiresAt"))
        val terminalDraft = value["status"]?.jsonPrimitive?.content in setOf("discarded", "published") && "clientDraftId" in value
        if (created < 0 || updated < created || updated > clock || expiry < created ||
            (if (terminalDraft) expiry - updated > ProgressPostPreviewContract.lifetimeMillis else expiry - updated != ProgressPostPreviewContract.lifetimeMillis))
            postFail(FailureReason.STORAGE_FAILURE)
    }
    private fun requireReceipt(receipts: JsonObject, key: String, operation: String, root: String): JsonObject {
        val receipt = receipts[key]?.jsonObject ?: postFail(FailureReason.STORAGE_FAILURE)
        if (receipt.postObject("original").postString("operation") != operation || receipt.postString("root") != root ||
            receipt.postObject("reply").postLong("status") !in 200L..299L) postFail(FailureReason.STORAGE_FAILURE)
        return receipt
    }
    companion object {
        val CONTENT_KEYS = setOf("caption", "altText", "mediaIds", "audience", "keepOnPlate", "allowRecipeSaves", "saveDisclosureVersion", "attachment", "sourcePostId")
        fun audience() = buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(emptyList())) }
        fun author() = buildJsonObject {
            put("userId", ProgressPostPreviewContract.authorId); put("displayName", ProgressPostPreviewContract.displayName)
            put("handle", ProgressPostPreviewContract.handle); put("avatarMediaId", ProgressPostPreviewContract.avatarId)
        }
        fun savePolicy() = buildJsonObject {
            put("allowFutureSaves", false); put("policyVersion", 1); put("disclosureVersion", ProgressPostPreviewContract.disclosureVersion)
        }
        fun postSelection(value: JsonObject): JsonObject = JsonObject(value.filterKeys { it in CONTENT_KEYS && it != "audience" } + mapOf(
            "audience" to audience(), "allowRecipeSaves" to JsonPrimitive(false), "saveDisclosureVersion" to JsonPrimitive(ProgressPostPreviewContract.disclosureVersion)))
    }
}

internal class ProgressPostProblem(val status: Int, val code: String) : Exception(code)
internal fun WireDocument.postJson(): JsonObject = Json.parseToJsonElement(encodeUtf8().decodeToString()).jsonObject
internal fun postFail(reason: FailureReason): Nothing = previewFail(reason)
internal fun JsonObject.postString(key: String): String = getValue(key).jsonPrimitive.let { if (!it.isString) postFail(FailureReason.STORAGE_FAILURE); it.content }
internal fun JsonObject.postObject(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonObject.postOptionalObject(key: String): JsonObject? = getValue(key).takeUnless { it == JsonNull }?.jsonObject
internal fun JsonObject.postOptionalString(key: String): String? = getValue(key).takeUnless { it == JsonNull }?.jsonPrimitive?.let { if (!it.isString) postFail(FailureReason.STORAGE_FAILURE); it.content }
internal fun JsonObject.postLong(key: String): Long = getValue(key).jsonPrimitive.let { if (it.isString) postFail(FailureReason.STORAGE_FAILURE); it.long }
internal fun JsonObject.postKeys(vararg keys: String) { if (this.keys != keys.toSet()) postFail(FailureReason.STORAGE_FAILURE) }
internal fun postInteger(value: JsonElement): BigInteger = value.jsonPrimitive.let { if (it.isString) postFail(FailureReason.INVALID_DATA); BigDecimal(it.content).toBigIntegerExact() }
internal fun postVersion(value: JsonObject): BigInteger = postInteger(value.getValue("version")).also { if (it.signum() <= 0) postFail(FailureReason.STORAGE_FAILURE) }
internal fun postEtag(value: JsonObject): String = "\"${postVersion(value)}\""
internal fun postMatchVersion(value: String): BigInteger {
    if (!value.matches(Regex("\"[1-9][0-9]*\""))) throw ProgressPostProblem(412, "VERSION_CONFLICT")
    return BigInteger(value.drop(1).dropLast(1))
}
internal fun postUuid(value: String): String = UUID.fromString(value).toString().also { if (!it.equals(value, ignoreCase = true)) postFail(FailureReason.INVALID_DATA) }
internal fun postTime(value: String): Long = Instant.parse(value).toEpochMilli().also { if (Instant.ofEpochMilli(it).toString() != value) postFail(FailureReason.STORAGE_FAILURE) }
internal fun postBase64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)
internal fun postUnbase64(value: String): ByteArray = Base64.getDecoder().decode(value).also { if (postBase64(it) != value) postFail(FailureReason.STORAGE_FAILURE) }
internal fun postHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
