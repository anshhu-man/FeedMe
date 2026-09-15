package com.feedme.development.progress

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Separate durable synthetic service; no client journal, automatic retry, migration or network. */
internal class ProgressPostServiceLedger(
    private val store: PrivateStateStore,
    private val scope: StorageScope,
    private val origin: String,
    private val clock: EpochClock,
    private val requireCurrent: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val codec = ProgressPostServiceCodec(scope, origin)
    private var openAttempted = false
    private var opened = false
    private var closed = false
    private var returnedCommitCheck: CommitCheck? = null // Only inside the mutex-owned operation.
    private var returnedReadCheck: ReadCheck? = null
    private var returnedNewCheck: NewPublicationCheck? = null
    /** Availability data, not authority. Call only on the wrapper's owning dispatcher. */
    var available = false
        private set

    /** True is supplied ONLY by actual fresh preview Start. Never initialize from Resume/read. */
    suspend fun open(allowInitialize: Boolean): PortResult<Unit> = guarded {
        if (openAttempted || closed) postFail(FailureReason.CONFLICT)
        openAttempted = true // Before the first storage await, including an unknown initialization.
        if (scope != ProgressIdentity.scope || postUuid(origin) != origin) postFail(FailureReason.UNAUTHENTICATED)
        val existing = read()
        if (existing != null) { codec.decode(existing); available = true }
        else if (allowInitialize) {
            val now = clock.nowMillis(); if (now < 0) postFail(FailureReason.CONFLICT)
            write(null, codec.fresh(now)); available = true
        }
        // Missing on Resume is a closed social capability, not a failure of cooking/cookbook.
        fence(); opened = true
    }

    suspend fun close(): PortResult<Unit> = mutex.withLock {
        closed = true; opened = false; available = false; PortResult.Value(Unit)
    }

    /** Concrete current observation, not a durable authorization ticket. No ID or write. */
    suspend fun requireNewPublication(exactPostWrite: PrivateBytes): PortResult<Unit> = guarded {
        if (!opened || !available || closed) postFail(FailureReason.NOT_CONFIGURED)
        val input = codec.publicationInput(exactPostWrite)
        val state = codec.decode(read() ?: postFail(FailureReason.STORAGE_FAILURE))
        val now = clock.nowMillis()
        val check = NewPublicationCheck(state, input, now)
        requireNewCurrent(check)
        returnedNewCheck = check
    }

    suspend fun execute(call: ApiCall): PortResult<ApiReply> = guarded {
        if (!opened || !available || closed) postFail(FailureReason.NOT_CONFIGURED)
        if (!codec.validateRequest(call)) postFail(FailureReason.INVALID_DATA)
        val record = read() ?: postFail(FailureReason.STORAGE_FAILURE)
        val state = codec.decode(record)
        val now = clock.nowMillis()
        if (now < state.postLong("lastMillis") || now < 0 || now > Long.MAX_VALUE - ProgressPostPreviewContract.lifetimeMillis)
            postFail(FailureReason.CONFLICT)
        val original = codec.original(call)
        val key = call.idempotencyKey?.use { postHash(postUuid(it)) }
        val receipts = state.postObject("receipts")
        if (key != null) {
            val retained = receipts[key]?.jsonObject
            if (retained != null) {
                if (retained.postObject("original") != original) return@guarded problem(409, conflictCode(call))
                val reply = codec.decodeReply(retained.postObject("reply"))
                if (reply.status in 200..299) {
                    try { requireReplay(state, call, retained, reply, now) }
                    catch (failure: ProgressPostProblem) { return@guarded problem(failure.status, mappedCode(call, failure.code)) }
                }
                // A fresh exact whole-row CAS/readback, not a matching read, acknowledges replay.
                write(record.revision, JsonObject(state + ("lastMillis" to JsonPrimitive(now))), CommitCheck(state, call, reply, now, replay = true))
                return@guarded reply
            }
            if (receipts.size >= ProgressPostPreviewContract.maxCommands) return@guarded problem(429, "RATE_LIMITED")
        }
        val outcome = try { apply(state, call, key, now) }
        catch (failure: ProgressPostProblem) { Outcome(state, problem(failure.status, mappedCode(call, failure.code)), null) }
        codec.validateReply(call.operationId, outcome.reply)
        if (key == null) {
            returnedReadCheck = ReadCheck(state, call, now)
            return@guarded outcome.reply
        }
        val receipt = buildJsonObject {
            put("original", original); put("reply", codec.encodeReply(outcome.reply)); put("root", outcome.root?.let(::JsonPrimitive) ?: JsonNull)
        }
        val candidate = JsonObject(outcome.state + mapOf("receipts" to JsonObject(receipts + (key to receipt)), "lastMillis" to JsonPrimitive(now)))
        write(record.revision, candidate, CommitCheck(state, call, outcome.reply, now, replay = false))
        outcome.reply
    }

    private data class Outcome(val state: JsonObject, val reply: ApiReply, val root: String?)
    private data class CommitCheck(val source: JsonObject, val call: ApiCall, val reply: ApiReply, val preparedAt: Long, val replay: Boolean)
    private data class ReadCheck(val source: JsonObject, val call: ApiCall, val preparedAt: Long)
    private data class NewPublicationCheck(val source: JsonObject, val input: JsonObject, val preparedAt: Long)

    private fun apply(state: JsonObject, call: ApiCall, key: String?, now: Long): Outcome {
        val roots = state.postObject("roots")
        fun changed(rootId: String, root: JsonObject, reply: ApiReply): Outcome {
            if (state.postLong("head") == Long.MAX_VALUE) throw ProgressPostProblem(503, "STORAGE_UNAVAILABLE")
            return Outcome(JsonObject(state + mapOf("roots" to JsonObject(roots + (rootId to root)),
                "head" to JsonPrimitive(state.postLong("head") + 1))), reply, rootId)
        }
        fun rootForDraft(): Pair<String, JsonObject> {
            val id = postUuid(call.pathParameters.getValue("draftId"))
            return roots.entries.firstOrNull { it.value.jsonObject.postOptionalObject("draft")?.postString("id") == id }
                ?.let { it.key to it.value.jsonObject } ?: throw ProgressPostProblem(404, "DRAFT_UNAVAILABLE")
        }
        when (call.operationId) {
            "getPostDraft" -> {
                val (_, root) = rootForDraft(); val draft = root.postObject("draft"); readable(draft, now)
                return Outcome(state, reply(draft, 200), null)
            }
            "listPostDrafts" -> return Outcome(state, page(state, call, now), null)
            "createPostDraft" -> {
                val input = body(call); val client = input["clientDraftId"]?.jsonPrimitive?.content?.let(::postUuid)
                    ?: throw ProgressPostProblem(422, "INPUT_INVALID")
                if (client in roots) throw ProgressPostProblem(409, "DRAFT_CONFLICT")
                if (roots.size >= ProgressPostPreviewContract.maxRoots) throw ProgressPostProblem(422, "RESPONSE_TOO_LARGE")
                val draft = JsonObject(codec.draftContent(null, input) + mapOf("id" to JsonPrimitive(newResourceId(roots)), "version" to JsonPrimitive(1),
                    "clientDraftId" to JsonPrimitive(client), "status" to JsonPrimitive("draft"), "createdAt" to timestamp(now),
                    "updatedAt" to timestamp(now), "expiresAt" to timestamp(now + ProgressPostPreviewContract.lifetimeMillis)))
                val root = buildJsonObject { put("draft", draft); put("post", JsonNull); put("createKey", checkNotNull(key)); put("deleteKey", JsonNull); put("publishKey", JsonNull) }
                return changed(client, root, reply(draft, 201))
            }
            "updatePostDraft", "deletePostDraft" -> {
                val (client, root) = rootForDraft(); val draft = root.postObject("draft")
                if (draft.postString("status") != "draft") throw ProgressPostProblem(409, "DRAFT_CONFLICT")
                if (call.operationId == "updatePostDraft") readable(draft, now)
                match(call, draft)
                val next = postVersion(draft) + BigInteger.ONE
                if (call.operationId == "deletePostDraft") {
                    // Private retained tombstone is never returned as readable content.
                    val tombstone = JsonObject(draft + mapOf("version" to JsonPrimitive(next), "status" to JsonPrimitive("discarded"), "updatedAt" to timestamp(now)))
                    return changed(client, JsonObject(root + mapOf("draft" to tombstone, "deleteKey" to JsonPrimitive(checkNotNull(key)))), ApiReply(204, null))
                }
                val input = body(call)
                if (input["clientDraftId"]?.jsonPrimitive?.content?.let(::postUuid)?.let { it != client } == true) throw ProgressPostProblem(422, "INPUT_INVALID")
                val updated = JsonObject(draft + codec.draftContent(codec.selection(draft), input) + mapOf("version" to JsonPrimitive(next),
                    "updatedAt" to timestamp(now), "expiresAt" to timestamp(now + ProgressPostPreviewContract.lifetimeMillis)))
                return changed(client, JsonObject(root + ("draft" to updated)), reply(updated, 200))
            }
            "publishPost" -> {
                val input = body(call); val client = postUuid(input.postString("clientDraftId")); val selected = codec.selection(input)
                val root = roots[client]?.jsonObject
                val draft = checkNewPublication(state, input, now)
                val postId = newResourceId(roots)
                val post = JsonObject(selected.filterKeys { it !in setOf("allowRecipeSaves", "saveDisclosureVersion", "audience") } + mapOf(
                    "id" to JsonPrimitive(postId), "version" to JsonPrimitive(1), "createdAt" to timestamp(now), "updatedAt" to timestamp(now),
                    "author" to ProgressPostServiceCodec.author(), "status" to JsonPrimitive("published"), "publishedAt" to timestamp(now),
                    "expiresAt" to timestamp(now + ProgressPostPreviewContract.lifetimeMillis), "savePolicy" to ProgressPostServiceCodec.savePolicy(),
                    "audience" to JsonObject(ProgressPostServiceCodec.audience() + ("bindings" to JsonArray(emptyList()))), "aclVersion" to JsonPrimitive(1),
                    "capabilities" to JsonArray(listOf(JsonPrimitive("delete"), JsonPrimitive("view"))), "reactionCounts" to JsonArray(emptyList())))
                val publishedDraft = draft?.let { JsonObject(it + mapOf("status" to JsonPrimitive("published"), "publishedPostId" to JsonPrimitive(postId),
                    "version" to JsonPrimitive(postVersion(it) + BigInteger.ONE), "updatedAt" to timestamp(now))) }
                val terminal = buildJsonObject { put("draft", publishedDraft ?: JsonNull); put("post", post)
                    put("createKey", root?.get("createKey") ?: JsonNull); put("deleteKey", JsonNull); put("publishKey", checkNotNull(key)) }
                return changed(client, terminal, reply(post, 201))
            }
            else -> postFail(FailureReason.NOT_CONFIGURED)
        }
    }

    /** Shared by actual execution and the explicit read-only prerequisite, never by replay. */
    private fun checkNewPublication(state: JsonObject, input: JsonObject, now: Long): JsonObject? {
        val roots = state.postObject("roots"); val client = postUuid(input.postString("clientDraftId")); val selected = codec.selection(input)
        codec.validateSelection(selected, true)
        if (("draftId" in input) != ("draftVersion" in input)) throw ProgressPostProblem(422, "INPUT_INVALID")
        val root = roots[client]?.jsonObject
        if (root?.postOptionalObject("post") != null) throw ProgressPostProblem(409, "PUBLICATION_CONFLICT")
        val draft = root?.postOptionalObject("draft")
        if ("draftId" in input) {
            if (draft == null || postUuid(input.postString("draftId")) != draft.postString("id")) throw ProgressPostProblem(409, "PUBLICATION_CONFLICT")
            readable(draft, now)
            if (draft.postString("status") != "draft" || postInteger(input.getValue("draftVersion")) != postVersion(draft) || selected != codec.selection(draft))
                throw ProgressPostProblem(409, "PUBLICATION_CONFLICT")
        } else if (root != null) throw ProgressPostProblem(409, "PUBLICATION_CONFLICT")
        if (root == null && roots.size >= ProgressPostPreviewContract.maxRoots) throw ProgressPostProblem(422, "RESPONSE_TOO_LARGE")
        return draft
    }

    private fun requireNewCurrent(check: NewPublicationCheck) {
        val now = clock.nowMillis()
        if (now < check.preparedAt || now < check.source.postLong("lastMillis") || now < 0 || now > Long.MAX_VALUE - ProgressPostPreviewContract.lifetimeMillis)
            postFail(FailureReason.CONFLICT)
        if (check.source.postObject("receipts").size >= ProgressPostPreviewContract.maxCommands) postFail(FailureReason.RATE_LIMITED)
        checkNewPublication(check.source, check.input, now)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> finishRead(result: T): T {
        val check = returnedReadCheck ?: return result
        kotlin.check(result is ApiReply && check.call.operationId in setOf("getPostDraft", "listPostDrafts"))
        val now = clock.nowMillis()
        if (now < check.preparedAt || now < check.source.postLong("lastMillis")) postFail(FailureReason.CONFLICT)
        // Rebuild the same validated immutable snapshot after the final awaited guard.
        // No store write, refresh, principal grant, hidden initialization or ID allocation.
        val reply = try { apply(check.source, check.call, null, now).reply }
            catch (failure: ProgressPostProblem) { problem(failure.status, failure.code) }
        codec.validateReply(check.call.operationId, reply)
        return reply as T
    }

    private fun requireReplay(state: JsonObject, call: ApiCall, receipt: JsonObject, reply: ApiReply, now: Long) {
        val root = state.postObject("roots").postObject(receipt.postString("root"))
        val originalResponse = reply.body?.copyForCodec()?.let { WireDocument.decode(it).postJson() }
        when (call.operationId) {
            "createPostDraft", "updatePostDraft" -> {
                val draft = root.postObject("draft"); readable(draft, now)
                // Actual original response must still be the unchanged, live draft.
                if (draft.postString("status") != "draft" || draft != originalResponse) throw ProgressPostProblem(409, "DRAFT_CONFLICT")
            }
            "deletePostDraft" -> {
                val draft = root.postObject("draft")
                if (draft.postString("status") != "discarded" || root.postString("deleteKey") != call.idempotencyKey?.use { postHash(postUuid(it)) } ||
                    postVersion(draft) != postMatchVersion(checkNotNull(call.ifMatch)) + BigInteger.ONE) throw ProgressPostProblem(409, "DRAFT_CONFLICT")
            }
            "publishPost" -> {
                val post = root.postObject("post")
                if (post != originalResponse || post.postString("status") != "published" || root.postString("publishKey") != call.idempotencyKey?.use { postHash(postUuid(it)) })
                    throw ProgressPostProblem(409, "DRAFT_CONFLICT")
                val input = body(call)
                root.postOptionalObject("draft")?.let { draft ->
                    if (draft.postString("status") != "published" || draft.postString("publishedPostId") != post.postString("id") ||
                        postUuid(input.postString("draftId")) != draft.postString("id") || postInteger(input.getValue("draftVersion")) + BigInteger.ONE != postVersion(draft))
                        throw ProgressPostProblem(409, "DRAFT_CONFLICT")
                }
                // Expiry does not fabricate physical deletion or rewrite the original receipt.
            }
        }
    }

    private fun page(state: JsonObject, call: ApiCall, now: Long): ApiReply {
        val limit = call.queryParameters["limit"]?.single()?.toInt() ?: 20
        if (limit !in 1..50) throw ProgressPostProblem(422, "INPUT_INVALID")
        val items = state.postObject("roots").values.mapNotNull { it.jsonObject.postOptionalObject("draft") }
            .filter { it.postString("status") == "draft" && postTime(it.postString("expiresAt")) > now }.sortedBy { it.postString("id") }
        var after: String? = null
        call.queryParameters["cursor"]?.single()?.let { cursor ->
            try {
                val parts = cursor.split('.'); if (parts.size != 2 || cursor.length > 2048) throw ProgressPostProblem(409, "CURSOR_INVALID")
                if (!MessageDigest.isEqual(signature(state, parts[0]).encodeToByteArray(), parts[1].encodeToByteArray())) throw ProgressPostProblem(409, "CURSOR_INVALID")
                val raw = Base64.getUrlDecoder().decode(parts[0]); val data = WireDocument.decode(raw).postJson()
                data.postKeys("head", "after", "issued", "limit")
                if (data.postLong("head") != state.postLong("head") || data.postLong("limit") != limit.toLong() || data.postLong("issued") > now) throw ProgressPostProblem(409, "CURSOR_INVALID")
                if (now - data.postLong("issued") >= ProgressPostPreviewContract.cursorLifetimeMillis) throw ProgressPostProblem(410, "CURSOR_EXPIRED")
                after = data.postString("after")
                if (items.none { it.postString("id") == after }) throw ProgressPostProblem(409, "CURSOR_INVALID")
            } catch (failure: ProgressPostProblem) { throw failure }
            catch (_: Exception) { throw ProgressPostProblem(409, "CURSOR_INVALID") }
        }
        val remaining = items.filter { after == null || it.postString("id") > checkNotNull(after) }
        val selected = remaining.take(limit)
        val next = if (remaining.size <= limit) JsonNull else {
            val payload = buildJsonObject { put("head", state.postLong("head")); put("after", selected.last().postString("id")); put("issued", now); put("limit", limit) }
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().encodeToByteArray())
            JsonPrimitive("$encoded.${signature(state, encoded)}")
        }
        return reply(buildJsonObject { put("items", JsonArray(selected)); put("nextCursor", next); put("serverTime", timestamp(now)) }, 200, etag = false)
            .copy(etag = "\"${state.postLong("head")}\"")
    }

    private fun signature(state: JsonObject, value: String): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(state.postString("cursorSecret").encodeToByteArray(), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.encodeToByteArray()))
    }
    private fun readable(draft: JsonObject, now: Long) {
        if (draft.postString("status") == "discarded") throw ProgressPostProblem(404, "DRAFT_UNAVAILABLE")
        if (draft.postString("status") != "published" && postTime(draft.postString("expiresAt")) <= now) throw ProgressPostProblem(410, "DRAFT_EXPIRED")
    }
    private fun match(call: ApiCall, draft: JsonObject) { if (postMatchVersion(checkNotNull(call.ifMatch)) != postVersion(draft)) throw ProgressPostProblem(412, "VERSION_CONFLICT") }
    private fun newResourceId(roots: JsonObject): String {
        val used = roots.values.flatMap { entry -> listOfNotNull(entry.jsonObject.postOptionalObject("draft")?.postString("id"), entry.jsonObject.postOptionalObject("post")?.postString("id")) }.toSet()
        return UUID.randomUUID().toString().also { if (it in used || it in roots) postFail(FailureReason.CONFLICT) }
    }
    private fun body(call: ApiCall) = WireDocument.decode(checkNotNull(call.body).copyForCodec()).postJson()
    private fun timestamp(now: Long) = JsonPrimitive(Instant.ofEpochMilli(now).toString())
    private fun reply(body: JsonObject, status: Int, etag: Boolean = true) = ApiReply(status, PrivateBytes(body.toString().encodeToByteArray()), if (etag) postEtag(body) else null, contentType = "application/json")
    private fun problem(status: Int, code: String): ApiReply {
        val trace = UUID.randomUUID().toString()
        val body = buildJsonObject { put("type", "https://example.invalid/feedme/progress/post-unavailable"); put("title", "Synthetic preview operation unavailable")
            put("status", status); put("code", code); put("traceId", trace) }
        return ApiReply(status, PrivateBytes(body.toString().encodeToByteArray()), traceId = trace, contentType = "application/problem+json")
    }
    private fun conflictCode(call: ApiCall) = if (call.operationId == "publishPost") "PUBLICATION_CONFLICT" else "DRAFT_CONFLICT"
    private fun mappedCode(call: ApiCall, code: String) = if (code == "DRAFT_CONFLICT") conflictCode(call) else code
    private suspend fun read(): PrivateRecord? { val value = previewValue(store.read(scope, KEY)); fence(); return value }
    private fun requireCommitCurrent(check: CommitCheck?) {
        if (check == null) return
        val now = clock.nowMillis()
        if (now < check.preparedAt || now < check.source.postLong("lastMillis")) postFail(FailureReason.CONFLICT)
        if (check.reply.status !in 200..299 || check.call.operationId == "deletePostDraft") return
        if (check.replay && check.call.operationId == "publishPost") return // Logical expiry is not physical deletion.
        val response = check.reply.body?.copyForCodec()?.let { WireDocument.decode(it).postJson() } ?: postFail(FailureReason.INVALID_DATA)
        if (postTime(response.postString("expiresAt")) <= now) postFail(FailureReason.CONFLICT)
        if (check.call.operationId == "updatePostDraft" || check.call.operationId == "publishPost") {
            val target = if (check.call.operationId == "updatePostDraft") check.call.pathParameters["draftId"]
                else body(check.call)["draftId"]?.jsonPrimitive?.content
            if (target != null) {
                val draft = check.source.postObject("roots").values.mapNotNull { it.jsonObject.postOptionalObject("draft") }
                    .singleOrNull { it.postString("id") == postUuid(target) } ?: postFail(FailureReason.CONFLICT)
                if (postTime(draft.postString("expiresAt")) <= now) postFail(FailureReason.CONFLICT)
            }
        }
    }
    private suspend fun write(expected: Long?, state: JsonObject, check: CommitCheck? = null) {
        val bytes = codec.encode(state)
        if (expected == Long.MAX_VALUE) postFail(FailureReason.UNAVAILABLE)
        fence(); requireCommitCurrent(check)
        val result = try { store.commit(scope, listOf(StoreMutation.Put(KEY, expected, 1, bytes))) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { postFail(FailureReason.OUTCOME_UNKNOWN) }
        fence()
        val ack = (result as? PortResult.Value)?.value ?: postFail(FailureReason.OUTCOME_UNKNOWN)
        val revision = ack[KEY]
        if (ack.keys != setOf(KEY) || revision == null || revision <= 0 || (expected != null && revision != expected + 1)) postFail(FailureReason.OUTCOME_UNKNOWN)
        val observed = try { previewValue(store.read(scope, KEY)) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { postFail(FailureReason.OUTCOME_UNKNOWN) }
        fence()
        if (observed == null || observed.revision != revision || observed.schemaVersion != 1 || !observed.payload.copyForCodec().contentEquals(bytes.copyForCodec()))
            postFail(FailureReason.OUTCOME_UNKNOWN)
        requireCommitCurrent(check)
        returnedCommitCheck = check
    }
    private suspend fun fence() { requireCurrent(); currentCoroutineContext().ensureActive(); if (closed) postFail(FailureReason.STALE_SESSION) }
    private suspend fun <T> guarded(block: suspend () -> T): PortResult<T> = mutex.withLock {
        try {
            fence(); val result = block(); fence(); requireCommitCurrent(returnedCommitCheck)
            returnedNewCheck?.let(::requireNewCurrent)
            PortResult.Value(finishRead(result))
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PreviewServiceFailure) { PortResult.Failure(failure.reason) }
        catch (failure: ProgressPostProblem) { PortResult.Failure(when (failure.status) {
            404 -> FailureReason.NOT_FOUND; 409, 410, 412 -> FailureReason.CONFLICT
            422 -> FailureReason.INVALID_DATA; 429 -> FailureReason.RATE_LIMITED; else -> FailureReason.UNAVAILABLE
        }) }
        catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
        finally { returnedCommitCheck = null; returnedReadCheck = null; returnedNewCheck = null }
    }
    companion object { val KEY = RecordKey("preview-post-service", "canonical-v1") }
}
