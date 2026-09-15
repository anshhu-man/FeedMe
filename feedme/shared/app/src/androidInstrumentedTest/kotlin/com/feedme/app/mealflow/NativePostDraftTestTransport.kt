package com.feedme.app.mealflow

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Instrumentation-only SYNTHETIC service. Canonical client, shared queue and native encryption
 * are real; this is deliberately not HTTP, PostgreSQL, login, rights or production authority. */
internal class NativePostDraftTestTransport(private val session: NativeMealFlowTestSession) : AccountTransport {
    val calls = mutableListOf<ApiCall>()
    private val drafts = linkedMapOf<String, JsonObject>()
    private val clients = mutableSetOf<String>()
    private val receipts = mutableMapOf<String, Receipt>()
    private var head = 0L
    var unknownNextMutation = false
    var denyNextGet = false
    var mutationGate: CompletableDeferred<Unit>? = null
    var readGate: CompletableDeferred<Unit>? = null

    override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
        check(session.boundary.isCurrent(lease) && lease.scope == session.scope)
        check(call.operationId in setOf("createPostDraft", "updatePostDraft", "deletePostDraft", "getPostDraft", "listPostDrafts"))
        calls += call
        if (call.operationId in setOf("getPostDraft", "listPostDrafts")) {
            check(call.body == null && call.ifMatch == null && call.idempotencyKey == null)
            val result = if (call.operationId == "getPostDraft") {
                check(call.queryParameters.isEmpty() && call.pathParameters.keys == setOf("draftId"))
                if (denyNextGet) { denyNextGet = false; problem(410, "DRAFT_EXPIRED") }
                else drafts[call.pathParameters.getValue("draftId")]?.let { reply(it) } ?: problem(404, "DRAFT_UNAVAILABLE")
            } else {
                check(call.pathParameters.isEmpty() && call.queryParameters.keys == setOf("limit"))
                val limit = call.queryParameters.getValue("limit").single().toInt()
                // These host cases deliberately fit one page. No fake cursor or paging authority.
                check(drafts.size <= limit)
                reply(buildJsonObject {
                    put("items", JsonArray(drafts.values.toList())); put("nextCursor", JsonNull); put("serverTime", TIME)
                }, etag = "\"$head\"")
            }
            readGate?.let { withContext(NonCancellable) { it.await() } }
            return result
        }
        check(call.queryParameters.isEmpty())
        val key = checkNotNull(call.idempotencyKey).use { it }
        val previous = receipts[key]
        val result = if (previous != null) {
            check(previous.matches(call)) { "Synthetic replay must keep exact original request" }; previous.reply
        } else {
            val response = when (call.operationId) {
                "createPostDraft" -> {
                    check(call.ifMatch == null && call.pathParameters.isEmpty())
                    val body = json(checkNotNull(call.body)); val client = body.getValue("clientDraftId").jsonPrimitive.content
                    check(clients.add(client)); check(body.getValue("mediaIds") == JsonArray(emptyList()))
                    check(body.getValue("audience").jsonObject.getValue("kind") == JsonPrimitive("self"))
                    check(body.getValue("keepOnPlate") == JsonPrimitive(false) && body.getValue("allowRecipeSaves") == JsonPrimitive(false))
                    check(body.keys.all { it in setOf("clientDraftId", "caption", "altText", "mediaIds", "audience", "keepOnPlate", "allowRecipeSaves") })
                    val id = "00000000-0000-4000-8000-${clients.size.toString().padStart(12, '0')}"
                    val document = JsonObject(body + mapOf("id" to JsonPrimitive(id), "version" to JsonPrimitive(1),
                        "createdAt" to JsonPrimitive(TIME), "updatedAt" to JsonPrimitive(TIME),
                        "expiresAt" to JsonPrimitive("2030-01-01T00:00:00Z"), "status" to JsonPrimitive("draft")))
                    drafts[id] = document; head++; reply(document, 201)
                }
                "updatePostDraft" -> {
                    check(call.pathParameters.keys == setOf("draftId")); val id = call.pathParameters.getValue("draftId")
                    val old = checkNotNull(drafts[id]); val version = old.getValue("version").jsonPrimitive.long
                    check(call.ifMatch == "\"$version\"")
                    val patch = json(checkNotNull(call.body)); check(patch.keys == setOf("caption") || patch.keys == setOf("caption", "altText"))
                    val next = JsonObject(old + patch + ("version" to JsonPrimitive(version + 1)))
                    drafts[id] = next; head++; reply(next)
                }
                "deletePostDraft" -> {
                    check(call.body == null && call.pathParameters.keys == setOf("draftId"))
                    val id = call.pathParameters.getValue("draftId"); val old = checkNotNull(drafts[id])
                    check(call.ifMatch == "\"${old.getValue("version").jsonPrimitive.content}\"")
                    drafts.remove(id); head++; PortResult.Value(ApiReply(204, null))
                }
                else -> error("Only fixed synthetic draft operations are supported")
            }
            receipts[key] = Receipt(call, response); response
        }
        mutationGate?.let { withContext(NonCancellable) { it.await() } }
        if (unknownNextMutation) { unknownNextMutation = false; return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        return result
    }

    private class Receipt(call: ApiCall, val reply: PortResult<ApiReply>) {
        private val operation = call.operationId
        private val path = call.pathParameters.toMap()
        private val body = call.body?.copyForCodec()
        private val match = call.ifMatch
        fun matches(call: ApiCall) = operation == call.operationId && path == call.pathParameters && match == call.ifMatch &&
            if (body == null) call.body == null else call.body?.copyForCodec()?.contentEquals(body) == true
    }
    private fun reply(body: JsonObject, status: Int = 200, etag: String = "\"${body.getValue("version").jsonPrimitive.content}\""): PortResult<ApiReply> =
        PortResult.Value(ApiReply(status, PrivateBytes(WireDocument.parse(body.toString()).encodeUtf8()), etag = etag, contentType = "application/json"))
    private fun problem(status: Int, code: String): PortResult<ApiReply> = PortResult.Value(ApiReply(status,
        PrivateBytes(buildJsonObject {
            put("type", "https://feedme.invalid/problems/synthetic-draft"); put("title", "Synthetic draft denial")
            put("status", status); put("code", code); put("traceId", "synthetic-native-post-draft")
        }.toString().encodeToByteArray()), contentType = "application/problem+json"))
    companion object {
        const val TIME = "2026-09-14T00:00:00Z"
        fun json(bytes: PrivateBytes) = Json.parseToJsonElement(bytes.copyForCodec().decodeToString()).jsonObject
    }
}
