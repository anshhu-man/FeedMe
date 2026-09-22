package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import com.feedme.server.social.posts.PostDeletionFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import java.util.UUID
import kotlinx.coroutines.*

internal class AccountPostDeletionHttpInput private constructor(val token: SecretText, val device: UUID,
    val key: UUID, val post: UUID, val ifMatch: String) {
    override fun toString() = "AccountPostDeletionHttpInput(<redacted>)"
    companion object {
        fun parse(headers: Headers, query: Parameters, paths: Parameters): AccountPostDeletionHttpInput {
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalid(); it.single()
            }
            if (query.names().isNotEmpty() || paths.names() != setOf("postId")) invalid()
            val authorization = header(HttpHeaders.Authorization)
            val device = header("X-Device-Session")
            if (authorization == null || authorization.length > 16391 || device == null) throw PostDeletionHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE).matchEntire(authorization)?.groupValues?.get(1)
                ?: throw PostDeletionHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16384 || !token.matches(Regex("[A-Za-z0-9._~+/-]+=*"))) throw PostDeletionHttpFailure(401, "UNAUTHENTICATED")
            val key = header("Idempotency-Key")?.let(::uuid) ?: invalid()
            val ifMatch = header(HttpHeaders.IfMatch) ?: throw PostDeletionHttpFailure(428, "PRECONDITION_REQUIRED")
            if (!ifMatch.matches(Regex("\"[1-9][0-9]{0,18}\""))) invalid()
            if (header(HttpHeaders.IfNoneMatch) != null || header(HttpHeaders.ContentType) != null || header(HttpHeaders.TransferEncoding) != null ||
                header(HttpHeaders.ContentEncoding)?.lowercase()?.let { it != "identity" } == true) invalid()
            if (header(HttpHeaders.ContentLength)?.let { it != "0" } == true) invalid()
            val post = paths.getAll("postId")?.let { if (it.size != 1) invalid(); uuid(it.single()) } ?: invalid()
            return AccountPostDeletionHttpInput(SecretText(token), uuid(device), key, post, ifMatch)
        }
        private fun uuid(value: String): UUID { if (!CanonicalFormats.accepts("uuid", value)) invalid(); return UUID.fromString(value) }
        private fun invalid(): Nothing = throw PostDeletionHttpFailure(400, "INVALID_REQUEST")
    }
}

internal suspend fun ApplicationCall.accountPostDeletionOperation(configuration: AccountPostDeletionHttpConfiguration, validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = AccountPostDeletionHttpInput.parse(request.headers, request.queryParameters, parameters)
        val verified = try { configuration.verifier.verify(input.token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw PostDeletionHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> if (verified.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION))
                throw PostDeletionHttpFailure(401, "UNAUTHENTICATED") else throw PostDeletionHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
        }
        currentCoroutineContext().ensureActive()
        val bytes = readBoundedHttpBody(receiveChannel(), 0, null, invalid = { throw PostDeletionHttpFailure(400, "INVALID_REQUEST") })
        try { if (bytes.isNotEmpty()) throw PostDeletionHttpFailure(400, "INVALID_REQUEST") } finally { bytes.fill(0) }
        val result = runInterruptible(configuration.databaseDispatcher) {
            configuration.store.deletePost(subject, input.device, input.key, input.post, input.ifMatch)
        }
        currentCoroutineContext().ensureActive()
        val receipt = deletionReply(result)
        check(receipt.status == 204 && receipt.body == null && receipt.etag == null &&
            validator.validateResponse("deletePost", 204, null, null) == BodyValidationResult.Valid)
        respond(HttpStatusCode.NoContent)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: PostDeletionHttpFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Post deletion unavailable", operationId = "deletePost") }
    catch (failure: PostDeletionFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Post deletion unavailable", operationId = "deletePost") }
    catch (_: CommitOutcomeUnknown) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Post deletion outcome requires reconciliation", operationId = "deletePost") }
}
private fun deletionReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw PostDeletionHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw PostDeletionHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw PostDeletionHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class PostDeletionHttpFailure(val status: Int, val code: String) : RuntimeException("Post deletion HTTP unavailable")
