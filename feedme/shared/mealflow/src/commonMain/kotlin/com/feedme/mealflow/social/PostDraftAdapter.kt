package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.serialization.json.*

/** Operation/status/schema/original-intent binding. No transport or permission is created here. */
internal class PostDraftAdapter(private val policy: PostDraftClientPolicy) {
    private val binder = CanonicalResponseBinder()
    fun bound(operation: String, reply: ApiReply): WireDocument? {
        val bytes = reply.body?.copyForCodec()
        if (bytes != null && bytes.size > policy.maxResponseBytes) mealFail(FailureReason.UNAVAILABLE)
        val result = binder.bind(operation, reply.status, bytes, reply.contentType, reply.traceId) as? ResponseBindingResult.Accepted
            ?: mealFail(FailureReason.INVALID_DATA)
        val body = (result.response.body as? WireBody.Present)?.document
        if (reply.status !in 200..299) mealFail(when (reply.status) {
            401 -> FailureReason.UNAUTHENTICATED; 403 -> FailureReason.FORBIDDEN; 404, 410 -> FailureReason.NOT_FOUND
            409, 412 -> FailureReason.CONFLICT; 429 -> FailureReason.RATE_LIMITED; 500, 503 -> FailureReason.UNAVAILABLE
            else -> FailureReason.INVALID_DATA
        })
        return body
    }
    fun observation(operation: String, reply: ApiReply, expectedId: String? = null): PostDraftObservation {
        if (operation !in setOf("getPostDraft", "createPostDraft", "updatePostDraft")) mealFail(FailureReason.INVALID_DATA)
        val expectedStatus = if (operation == "createPostDraft") 201 else 200
        val body = bound(operation, reply) ?: mealFail(FailureReason.INVALID_DATA)
        if (reply.status != expectedStatus || (expectedId != null && postString(body, "id") != expectedId) ||
            postString(body, "status") !in (if (operation == "getPostDraft") setOf("draft", "published") else setOf("draft"))) mealFail(FailureReason.INVALID_DATA)
        strictId(postString(body, "id")); strictId(postString(body, "clientDraftId"))
        val tag = reply.etag ?: mealFail(FailureReason.INVALID_DATA); postEtag(body, tag)
        return PostDraftObservation(body, tag, false)
    }
    fun receipt(original: PostOriginal, reply: ApiReply): PostDraftObservation? {
        if (original.operation == "deletePostDraft") {
            if (bound(original.operation, reply) != null || reply.status != 204 || reply.body != null || reply.etag != null) mealFail(FailureReason.INVALID_DATA)
            return null
        }
        val observed = observation(original.operation, reply, original.serverId)
        possibleOriginalResult(original, observed.document, observed.etag)
        return observed
    }
    /** Pure correlation, NOT a receipt or an ACK. A GET of this exact possible result can admit
     * a fresh ORIGINAL-key retry; only a real operation-bound queue receipt can apply success. */
    fun possibleOriginalResult(original: PostOriginal, document: WireDocument, etag: String) {
        if (original.operation !in setOf("createPostDraft", "updatePostDraft") || postString(document, "status") != "draft" ||
            postString(document, "clientDraftId") != original.clientId ||
            (original.serverId != null && postString(document, "id") != original.serverId)) mealFail(FailureReason.CONFLICT)
        postEtag(document, etag)
        val body = document.json().jsonObject; val request = original.body!!.json().jsonObject
        if (original.operation == "createPostDraft") {
            if (postVersion(document) != "1") mealFail(FailureReason.CONFLICT)
            for ((name, value) in request) if (body[name] != value) mealFail(FailureReason.CONFLICT)
            // Omitted, explicitly empty, and present alt text are distinct original intentions.
            if (body["altText"] != request["altText"]) mealFail(FailureReason.CONFLICT)
            if (body.keys.any { it in setOf("attachment", "sourcePostId", "publishedPostId", "saveDisclosureVersion") }) mealFail(FailureReason.CONFLICT)
        } else {
            val before = original.baseline!!.json().jsonObject
            if (postVersion(document) != postSuccessor(postVersion(original.baseline))) mealFail(FailureReason.CONFLICT)
            val changed = setOf("version", "updatedAt", "expiresAt") + request.keys
            if (before.filterKeys { it !in changed } != body.filterKeys { it !in changed }) mealFail(FailureReason.CONFLICT)
            for ((name, value) in request) if (body[name] != value) mealFail(FailureReason.CONFLICT)
        }
    }
    fun monotone(previous: WireDocument, next: WireDocument) {
        if (postString(previous, "id") != postString(next, "id") || postString(previous, "clientDraftId") != postString(next, "clientDraftId") ||
            postString(previous, "createdAt") != postString(next, "createdAt")) mealFail(FailureReason.CONFLICT)
        val version = postCompare(postVersion(next), postVersion(previous))
        if (version < 0 || (version == 0 && previous.json() != next.json())) mealFail(FailureReason.CONFLICT)
        if (postString(previous, "status") == "published" && postString(next, "status") != "published") mealFail(FailureReason.CONFLICT)
    }
}
