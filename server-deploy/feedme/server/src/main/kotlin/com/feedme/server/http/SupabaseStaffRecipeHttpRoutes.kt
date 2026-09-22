package com.feedme.server.http

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.staff.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal val staffRecipeHttpOperations = setOf("adminListRecipes", "adminGetRecipe", "adminCreateRecipe", "adminUpdateRecipe",
    "adminSubmitRecipe", "adminReviewRecipe", "adminPublishRecipe", "adminRecallRecipe")

/** Canonical staff recipe lifecycle routes; no member device identity, cookies or
 * reuse of the short-lived HOME observation as authorization. */
internal suspend fun ApplicationCall.staffRecipeOperation(operation: String,
    configuration: SupabaseStaffHttpConfiguration, validator: ContractBodyValidator) {
    response.headers.append(HttpHeaders.CacheControl, "private, no-store")
    response.headers.append(HttpHeaders.Pragma, "no-cache")
    response.headers.append("X-Content-Type-Options", "nosniff")
    try {
        currentCoroutineContext().ensureActive()
        val store = configuration.recipes ?: throw StaffRecipeHttpFailure(503, "STAFF_NOT_CONFIGURED")
        if (operation !in staffRecipeHttpOperations) invalidStaffRecipe()
        val writing = operation !in setOf("adminListRecipes", "adminGetRecipe")
        val targeted = operation !in setOf("adminListRecipes", "adminCreateRecipe")
        if (parameters.names() != if (targeted) setOf("recipeId", "recipeVersionId") else emptySet()) invalidStaffRecipe()
        if (request.queryParameters.names().any { operation != "adminListRecipes" || it !in setOf("q", "cursor", "limit") }) invalidStaffRecipe()
        fun query(name: String): String? = request.queryParameters.getAll(name)?.let {
            if (it.size != 1 || it.single().any(Char::isISOControl)) invalidStaffRecipe(); it.single()
        }
        fun header(name: String): String? = request.headers.getAll(name)?.let {
            if (it.size != 1 || it.single().any(Char::isISOControl)) invalidStaffRecipe(); it.single()
        }
        fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidStaffRecipe()
            return UUID.fromString(value)
        }
        fun path(name: String): UUID? {
            if (!targeted) return null
            return parameters.getAll(name)?.let {
                if (it.size != 1) invalidStaffRecipe(); uuid(it.single())
            } ?: invalidStaffRecipe()
        }
        val recipe = path("recipeId"); val version = path("recipeVersionId")
        val q = query("q")?.also { if (it.length > 100) invalidStaffRecipe() }
        val cursor = query("cursor")?.also { if (it.length !in 1..2048) invalidStaffRecipe() }
        val limit = query("limit")?.let { if (!it.matches(Regex("[1-9][0-9]?"))) invalidStaffRecipe();
            it.toInt().also { value -> if (value !in 1..50) invalidStaffRecipe() } } ?: 20
        val authorization = header(HttpHeaders.Authorization)
        if (authorization == null || authorization.length > 16391) throw StaffRecipeHttpFailure(401, "STAFF_UNAUTHENTICATED")
        val token = Regex("Bearer ([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .matchEntire(authorization)?.groupValues?.get(1)?.takeIf { it.length in 1..16384 }
            ?: throw StaffRecipeHttpFailure(401, "STAFF_UNAUTHENTICATED")
        if (header(HttpHeaders.Cookie) != null || header("X-Device-Session") != null || header(HttpHeaders.IfNoneMatch) != null)
            invalidStaffRecipe()
        val keyText = header("Idempotency-Key"); val match = header(HttpHeaders.IfMatch)
        val key = if (writing) keyText?.let(::uuid) ?: invalidStaffRecipe() else {
            if (keyText != null || match != null) invalidStaffRecipe(); null
        }
        if (writing && targeted) {
            if (match == null) throw StaffRecipeHttpFailure(428, "PRECONDITION_REQUIRED")
            if (!match.matches(Regex("\"[1-9][0-9]{0,18}\"")) || match.removeSurrounding("\"").toLongOrNull() == null) invalidStaffRecipe()
        } else if (match != null) invalidStaffRecipe()
        val length = header(HttpHeaders.ContentLength)?.let {
            if (!it.matches(Regex("[0-9]{1,6}"))) invalidStaffRecipe()
            it.toLong().also { count -> if (count > if (writing) 65536L else 0L) invalidStaffRecipe() }
        }
        val transfer = header(HttpHeaders.TransferEncoding)
        if (transfer != null && (!writing || length != null || transfer.lowercase() != "chunked")) invalidStaffRecipe()
        if (header(HttpHeaders.ContentEncoding)?.lowercase()?.let { it != "identity" } == true) invalidStaffRecipe()
        val media = header(HttpHeaders.ContentType)
        if (writing) {
            if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                throw StaffRecipeHttpFailure(400, "UNSUPPORTED_MEDIA")
        } else if (media != null) invalidStaffRecipe()
        val bytes = readBoundedHttpBody(receiveChannel(), if (writing) 65536 else 0, length, ::invalidStaffRecipe)
        val body = try {
            if (!writing) { if (bytes.isNotEmpty()) invalidStaffRecipe(); null }
            else {
                val decoded = try { WireDocument.decode(bytes, WireLimits(65536, 32)) }
                    catch (_: WireDecodingException) { invalidStaffRecipe() }
                if (validator.validateRequest(operation, bytes, media) != BodyValidationResult.Valid)
                    throw StaffRecipeHttpFailure(422, "INPUT_INVALID")
                Json.parseToJsonElement(decoded.encodeUtf8().decodeToString()).jsonObject
            }
        } finally { bytes.fill(0) }
        val verified = try { configuration.verifier.verify(SecretText(token)) }
            catch (e: CancellationException) { throw e }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
            catch (_: Exception) { throw StaffRecipeHttpFailure(503, "STAFF_UNAVAILABLE") }
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> if (verified.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION))
                throw StaffRecipeHttpFailure(401, "STAFF_UNAUTHENTICATED") else throw StaffRecipeHttpFailure(503, "STAFF_UNAVAILABLE")
        }
        val observed = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "adminListRecipes" -> StaffRecipeResponse(StoredReply(200, store.adminListRecipes(subject, q, cursor, limit)))
                "adminGetRecipe" -> store.adminGetRecipeObservation(subject, recipe!!, version!!).let {
                    StaffRecipeResponse(StoredReply(200, it.body, "\"${it.body.getValue("version").jsonPrimitive.content}\""),
                        it.editable, it.submittable, it.reviewable, it.publishable, it.recallable) }
                "adminCreateRecipe" -> StaffRecipeResponse(staffRecipeReply(store.adminCreateRecipe(subject, key!!, body!!)), true)
                "adminUpdateRecipe" -> StaffRecipeResponse(staffRecipeReply(store.adminUpdateRecipe(subject, key!!, recipe!!, version!!, match!!, body!!)), true)
                "adminSubmitRecipe" -> StaffRecipeResponse(staffRecipeReply(store.adminSubmitRecipe(subject, key!!, recipe!!, version!!, match!!, body!!)), false)
                "adminReviewRecipe" -> StaffRecipeResponse(staffRecipeReply(store.adminReviewRecipe(subject, key!!, recipe!!, version!!, match!!, body!!)), false)
                "adminPublishRecipe" -> StaffRecipeResponse(staffRecipeReply(store.adminPublishRecipe(subject, key!!, recipe!!, version!!, match!!, body!!)), false)
                "adminRecallRecipe" -> StaffRecipeResponse(staffRecipeReply(store.adminRecallRecipe(subject, key!!, recipe!!, version!!, match!!, body!!)), false)
                else -> invalidStaffRecipe()
            }
        }
        currentCoroutineContext().ensureActive()
        val reply = observed.reply
        val text = checkNotNull(reply.body).toString()
        check(reply.status == 200 && text.encodeToByteArray().size <= 262144 &&
            validator.validateResponse(operation, reply.status, text.encodeToByteArray(), "application/json") == BodyValidationResult.Valid)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        observed.editable?.let { response.headers.append("X-FeedMe-Draft-Editable", it.toString()) }
        observed.submittable?.let { response.headers.append("X-FeedMe-Recipe-Submittable", it.toString()) }
        observed.reviewable?.let { response.headers.append("X-FeedMe-Recipe-Reviewable", it.toString()) }
        observed.publishable?.let { response.headers.append("X-FeedMe-Recipe-Publishable", it.toString()) }
        observed.recallable?.let { response.headers.append("X-FeedMe-Recipe-Recallable", it.toString()) }
        respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
    } catch (e: CancellationException) { throw e }
      catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
      catch (e: StaffRecipeHttpFailure) { currentCoroutineContext().ensureActive();
          problem(validator, HttpStatusCode.fromValue(e.status), e.code, "Staff draft action unavailable", operationId = operation) }
      catch (e: SupabaseStaffFailure) { currentCoroutineContext().ensureActive();
          problem(validator, HttpStatusCode.fromValue(e.code.status), e.code.name, "Staff draft access unavailable", operationId = operation) }
      catch (e: SupabaseStaffRecipeFailure) { currentCoroutineContext().ensureActive();
          problem(validator, HttpStatusCode.fromValue(e.code.status), e.code.name, "Staff draft action unavailable", operationId = operation) }
      catch (_: CommitOutcomeUnknown) { currentCoroutineContext().ensureActive();
          problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Retain the original draft command", operationId = operation) }
}

private fun staffRecipeReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw StaffRecipeHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw StaffRecipeHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw StaffRecipeHttpFailure(409, "COMMAND_INCOMPLETE")
}
private class StaffRecipeHttpFailure(val status: Int, val code: String) : RuntimeException("Staff draft HTTP unavailable")
private class StaffRecipeResponse(val reply: StoredReply, val editable: Boolean? = null,
    val submittable: Boolean? = null, val reviewable: Boolean? = null,
    val publishable: Boolean? = null, val recallable: Boolean? = null)
private fun invalidStaffRecipe(): Nothing = throw StaffRecipeHttpFailure(400, "INVALID_REQUEST")
