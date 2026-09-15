package com.feedme.app.mealflow

import com.feedme.contracts.CanonicalBodyValidator
import com.feedme.contracts.ContractValidationResult
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/** Separate instrumentation-only SYNTHETIC service. Native session/store, canonical client,
 * queue and controllers are real. No HTTP/PG, media-upload or production-rights claim.
 * The old five-operation NativePostDraftTestTransport remains untouched. */
internal class NativeReviewedPostTestTransport(
    private val session: NativeMealFlowTestSession,
    private val now: () -> Long,
) : AccountTransport {
    val calls = mutableListOf<ApiCall>()
    val returnedReplies = mutableListOf<ApiReply>()
    val completedCalls = mutableListOf<ApiCall>()
    private val validator = CanonicalBodyValidator.bundled()
    private val drafts = linkedMapOf<String, JsonObject>()
    private val posts = linkedMapOf<String, JsonObject>()
    private val usedClients = mutableSetOf<String>()
    private val publishedClients = mutableSetOf<String>()
    private val receipts = mutableMapOf<String, Receipt>()
    private var listVersion = 0L
    var unknownNextPublication = false
    var unknownNextPrivateMutation = false
    var publicationGate: CompletableDeferred<Unit>? = null
    var privateMutationGate: CompletableDeferred<Unit>? = null
    var readGate: CompletableDeferred<Unit>? = null
    val publishedCount get() = posts.size

    override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
        check(session.boundary.isCurrent(lease) && lease.scope == session.scope)
        check(call.operationId in OPERATIONS)
        check(validator.validateRequest(call.operationId, call.body?.copyForCodec(),
            call.body?.let { "application/json" }) == ContractValidationResult.Valid)
        calls += call
        if (call.operationId in setOf("getPostDraft", "listPostDrafts")) {
            check(call.body == null && call.ifMatch == null && call.idempotencyKey == null)
            val reply = read(call)
            readGate?.let { withContext(NonCancellable) { it.await() } }
            return finish(call, reply)
        }
        check(call.queryParameters.isEmpty())
        val key = checkNotNull(call.idempotencyKey).use { it }
        val previous = receipts[key]
        val reply = if (previous != null) {
            check(previous.matches(call)) { "Synthetic replay must preserve the exact original envelope" }
            previous.reply
        } else {
            val actual = when (call.operationId) {
                "createPostDraft" -> create(call)
                "updatePostDraft" -> update(call)
                "deletePostDraft" -> delete(call)
                "publishPost" -> publish(call)
                else -> error("Unconfigured synthetic operation")
            }
            check(validator.validateResponse(call.operationId, actual.status, actual.body?.copyForCodec(), actual.contentType) ==
                ContractValidationResult.Valid)
            receipts[key] = Receipt(call, actual)
            actual
        }
        val publication = call.operationId == "publishPost"
        (if (publication) publicationGate else privateMutationGate)?.let { withContext(NonCancellable) { it.await() } }
        if (if (publication) unknownNextPublication else unknownNextPrivateMutation) {
            if (publication) unknownNextPublication = false else unknownNextPrivateMutation = false
            completedCalls += call
            return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
        }
        return finish(call, reply)
    }

    private fun finish(call: ApiCall, reply: ApiReply): PortResult<ApiReply> {
        check(validator.validateResponse(call.operationId, reply.status, reply.body?.copyForCodec(), reply.contentType) ==
            ContractValidationResult.Valid)
        returnedReplies += reply; completedCalls += call
        return PortResult.Value(reply)
    }

    private fun read(call: ApiCall): ApiReply = if (call.operationId == "getPostDraft") {
        check(call.pathParameters.keys == setOf("draftId") && call.queryParameters.isEmpty())
        document(checkNotNull(drafts[call.pathParameters.getValue("draftId")]))
    } else {
        check(call.pathParameters.isEmpty() && call.queryParameters.keys == setOf("limit"))
        val limit = call.queryParameters.getValue("limit").single().toInt()
        check(drafts.size <= limit) // One complete page in this focused fixture, not a fake cursor.
        document(buildJsonObject {
            put("items", JsonArray(drafts.values.toList())); put("nextCursor", JsonNull); put("serverTime", timestamp())
        }, etag = "\"" + listVersion + "\"")
    }

    private fun create(call: ApiCall): ApiReply {
        check(call.pathParameters.isEmpty() && call.ifMatch == null)
        val body = json(checkNotNull(call.body))
        val client = body.getValue("clientDraftId").jsonPrimitive.content
        check(client !in publishedClients && usedClients.add(client))
        val id = UUID.randomUUID().toString()
        val value = JsonObject(draftSelection(body) + mapOf(
            "id" to JsonPrimitive(id), "clientDraftId" to JsonPrimitive(client), "version" to JsonPrimitive(1),
            "createdAt" to JsonPrimitive(timestamp()), "updatedAt" to JsonPrimitive(timestamp()),
            "expiresAt" to JsonPrimitive(timestamp(86_400_000)), "status" to JsonPrimitive("draft")))
        drafts[id] = value; listVersion++
        return document(value, 201)
    }

    private fun update(call: ApiCall): ApiReply {
        check(call.pathParameters.keys == setOf("draftId"))
        val id = call.pathParameters.getValue("draftId"); val old = checkNotNull(drafts[id])
        check(old.getValue("status").jsonPrimitive.content == "draft")
        val version = old.getValue("version").jsonPrimitive.content
        check(call.ifMatch == "\"" + version + "\"")
        val patch = json(checkNotNull(call.body))
        check(!(patch["removeAttachment"] == JsonPrimitive(true) && "attachment" in patch))
        val merged = old.toMutableMap()
        if (patch["removeAttachment"] == JsonPrimitive(true)) merged.remove("attachment")
        for ((name, value) in patch) if (name != "removeAttachment") merged[name] = value
        val next = JsonObject(merged + draftSelection(JsonObject(merged)) + mapOf(
            "version" to Json.parseToJsonElement((BigInteger(version) + BigInteger.ONE).toString()),
            "updatedAt" to JsonPrimitive(timestamp()), "expiresAt" to JsonPrimitive(timestamp(86_400_000))))
        drafts[id] = next; listVersion++
        return document(next)
    }

    private fun delete(call: ApiCall): ApiReply {
        check(call.body == null && call.pathParameters.keys == setOf("draftId"))
        val id = call.pathParameters.getValue("draftId"); val old = checkNotNull(drafts[id])
        check(call.ifMatch == "\"" + old.getValue("version").jsonPrimitive.content + "\"")
        check(old.getValue("status").jsonPrimitive.content == "draft")
        drafts.remove(id); listVersion++
        return ApiReply(204, null)
    }

    private fun publish(call: ApiCall): ApiReply {
        check(call.pathParameters.isEmpty() && call.ifMatch == null)
        val body = json(checkNotNull(call.body)); val client = body.getValue("clientDraftId").jsonPrimitive.content
        val target = body["draftId"]?.jsonPrimitive?.content
        check((target == null) == (body["draftVersion"] == null))
        val selected = publicationSelection(body)
        // These focused native scenarios are explicitly self-only. No invented circle binding.
        check(selected.getValue("audience").jsonObject.getValue("kind") == JsonPrimitive("self"))
        check(selected.getValue("audience").jsonObject.getValue("circleIds") == JsonArray(emptyList()))
        if (target == null) check(client !in usedClients) // Never bypass a known saved root.
        else {
            val saved = checkNotNull(drafts[UUID.fromString(target).toString()])
            check(saved.getValue("clientDraftId").jsonPrimitive.content == client)
            check(saved.getValue("status").jsonPrimitive.content == "draft")
            check(saved.getValue("version").jsonPrimitive.content == body.getValue("draftVersion").jsonPrimitive.content)
            check(publicationSelection(saved) == selected) // No synthetic rebase or stale material.
        }
        check(publishedClients.add(client))
        val id = UUID.randomUUID().toString()
        val post = buildJsonObject {
            put("id", id); put("version", 1); put("createdAt", timestamp()); put("updatedAt", timestamp())
            put("author", buildJsonObject {
                put("userId", VERIFIED_USER); put("displayName", "Synthetic native cook"); put("handle", "synthetic_native")
                put("avatarMediaId", AVATAR)
            })
            for (name in listOf("caption", "altText", "mediaIds", "keepOnPlate", "attachment", "sourcePostId")) selected[name]?.let { put(name, it) }
            put("audience", JsonObject(selected.getValue("audience").jsonObject + ("bindings" to JsonArray(emptyList()))))
            put("status", "published"); put("publishedAt", timestamp()); put("expiresAt", timestamp(86_400_000))
            put("savePolicy", buildJsonObject {
                put("allowFutureSaves", selected.getValue("allowRecipeSaves")); put("policyVersion", 1)
                put("disclosureVersion", selected.getValue("saveDisclosureVersion"))
            })
            put("aclVersion", 1); put("capabilities", JsonArray(listOf(JsonPrimitive("delete"), JsonPrimitive("view"))))
            put("reactionCounts", JsonArray(emptyList()))
        }
        posts[id] = post
        if (target != null) {
            val canonical = UUID.fromString(target).toString(); val old = drafts.getValue(canonical)
            drafts[canonical] = JsonObject(old + mapOf("status" to JsonPrimitive("published"),
                "publishedPostId" to JsonPrimitive(id), "updatedAt" to JsonPrimitive(timestamp()),
                "version" to Json.parseToJsonElement((BigInteger(old.getValue("version").jsonPrimitive.content) + BigInteger.ONE).toString())))
            listVersion++
        }
        return document(post, 201)
    }

    /** Draft normalization only: media/circle UUIDs. Nested recipe/source spelling stays exact. */
    private fun draftSelection(value: JsonObject): JsonObject = buildJsonObject {
        for (name in FIELDS) value[name]?.let { put(name, it) }
        put("mediaIds", normalizedIds(value.getValue("mediaIds")))
        val audience = value.getValue("audience").jsonObject
        put("audience", JsonObject(audience.filterKeys { it != "bindings" } +
            ("circleIds" to normalizedIds(audience.getValue("circleIds")))))
    }
    /** Publication additionally normalizes source and catalog/plan attachment UUIDs. */
    private fun publicationSelection(value: JsonObject): JsonObject = JsonObject(draftSelection(value).toMutableMap().apply {
        value["sourcePostId"]?.let { put("sourcePostId", JsonPrimitive(UUID.fromString(it.jsonPrimitive.content).toString())) }
        value["attachment"]?.jsonObject?.let { attachment ->
            put("attachment", JsonObject(attachment.mapValues { (name, item) ->
                if (name in setOf("recipeVersionId", "planId")) JsonPrimitive(UUID.fromString(item.jsonPrimitive.content).toString()) else item
            }))
        }
    })
    private fun normalizedIds(value: JsonElement): JsonArray {
        val ids = value.jsonArray.map { JsonPrimitive(UUID.fromString(it.jsonPrimitive.content).toString()) }
        check(ids.distinct().size == ids.size)
        return JsonArray(ids)
    }
    private fun timestamp(offset: Long = 0) = Instant.ofEpochMilli(now() + offset).toString()
    private fun document(value: JsonObject, status: Int = 200,
        etag: String = "\"" + value.getValue("version").jsonPrimitive.content + "\""): ApiReply {
        val bytes = WireDocument.parse(value.toString()).encodeUtf8()
        // Actual synthetic reply bytes, including replies whose return is later gated/lost.
        // A future fixture expansion must fail here rather than silently exceed its policy.
        check(bytes.size <= MAX_RESPONSE_BYTES) { "Synthetic reply exceeds its explicit native fixture bound" }
        return ApiReply(status, PrivateBytes(bytes), etag = etag, contentType = "application/json")
    }

    private class Receipt(call: ApiCall, val reply: ApiReply) {
        private val operation = call.operationId
        private val path = call.pathParameters.toMap()
        private val query = call.queryParameters.mapValues { it.value.toList() }
        private val match = call.ifMatch
        private val body = call.body?.copyForCodec()
        fun matches(call: ApiCall) = operation == call.operationId && path == call.pathParameters &&
            query == call.queryParameters && match == call.ifMatch &&
            (if (body == null) call.body == null else call.body?.copyForCodec()?.contentEquals(body) == true)
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 65_536
        // DIFFERENT from opaque native storage actor ID; never inferred from it.
        const val VERIFIED_USER = "00000000-0000-4000-8000-00000000b201"
        const val AVATAR = "00000000-0000-4000-8000-00000000b202"
        val OPERATIONS = setOf("createPostDraft", "updatePostDraft", "deletePostDraft", "getPostDraft", "listPostDrafts", "publishPost")
        private val FIELDS = setOf("caption", "altText", "mediaIds", "audience", "keepOnPlate", "allowRecipeSaves", "attachment", "saveDisclosureVersion", "sourcePostId")
        fun json(bytes: PrivateBytes) = Json.parseToJsonElement(bytes.copyForCodec().decodeToString()).jsonObject
    }
}
