package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDecodingException
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.memory.SavedRecipeFailure
import com.feedme.server.memory.VerifiedSavedRecipePrincipal
import com.feedme.server.db.CommandActor
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

internal val savedRecipeHttpOperations = setOf("saveRecipe", "getSavedRecipe", "listSavedRecipes",
    "deleteSavedRecipe", "listCollections", "getCollection")

internal class SavedRecipeHttpInput private constructor(
    val operation: String, val bearer: SavedRecipeHttpBearer, val key: UUID?,
    val savedRecipeId: UUID?, val collectionId: UUID?, val ifMatch: String?, val contentLength: Long?, val mediaType: String?,
    val q: String?, val cursor: String?, val limit: Int,
) {
    val hasBody: Boolean get() = operation == "saveRecipe"
    override fun toString() = "SavedRecipeHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val buffer = ByteArray(if (hasBody) SavedRecipeHttpConfiguration.MAX_REQUEST_BYTES + 1 else 1)
        var size = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(buffer, size, buffer.size - size)
                if (read == -1) { channel.closedCause?.let { throw it }; break }
                size += read
                if (size == buffer.size) invalidSavedRecipe()
            }
            currentCoroutineContext().ensureActive()
            if (contentLength != null && contentLength != size.toLong()) invalidSavedRecipe()
            return body(buffer.copyOf(size), validator)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { currentCoroutineContext().ensureActive(); invalidSavedRecipe() }
        finally { buffer.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): JsonObject? {
        if (!hasBody) { if (bytes.isNotEmpty()) invalidSavedRecipe(); return null }
        if (bytes.isEmpty() || bytes.size > SavedRecipeHttpConfiguration.MAX_REQUEST_BYTES) invalidSavedRecipe()
        val document = try { WireDocument.decode(bytes, WireLimits(SavedRecipeHttpConfiguration.MAX_REQUEST_BYTES, 32)) }
            catch (_: WireDecodingException) { invalidSavedRecipe() }
        if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
            throw SavedRecipeHttpFailure(422, "INPUT_INVALID")
        // Validate original wire bytes first. Duplicate members, malformed Unicode, and exact
        // request member spelling must not be lost in a permissive JSON/Double projection.
        return Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    }

    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): SavedRecipeHttpInput {
            if (operation !in savedRecipeHttpOperations) invalidSavedRecipe()
            val allowedQuery = when (operation) { "listSavedRecipes" -> setOf("q", "cursor", "limit")
                "listCollections", "getCollection" -> setOf("cursor", "limit"); else -> emptySet() }
            if (!allowedQuery.containsAll(query.names())) invalidSavedRecipe()
            fun parameter(name: String): String? = query.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidSavedRecipe()
                val value = it.single()
                try { value.encodeToByteArray(throwOnInvalidSequence = true) } catch (_: Exception) { invalidSavedRecipe() }
                value
            }
            val q = parameter("q")?.also { if (it.codePointCount(0, it.length) > 100) invalidSavedRecipe() }
            val cursor = parameter("cursor")?.also { if (it.codePointCount(0, it.length) > 2048) invalidSavedRecipe() }
            val limit = parameter("limit")?.let { if (!Regex("[0-9]{1,2}").matches(it)) invalidSavedRecipe()
                it.toIntOrNull()?.takeIf { n -> n in 1..50 } ?: invalidSavedRecipe() } ?: 20
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidSavedRecipe()
                it.single()
            }
            val authorization = header(HttpHeaders.Authorization) ?: throw SavedRecipeHttpFailure(401, "UNAUTHENTICATED")
            if (authorization.contains(',')) invalidSavedRecipe()
            if (authorization.length > 16_391) throw SavedRecipeHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: throw SavedRecipeHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
                throw SavedRecipeHttpFailure(401, "UNAUTHENTICATED")
            val bearer = SavedRecipeHttpBearer(SecretText(token), header("X-Device-Session")?.let(::uuid))
            val hasBody = operation == "saveRecipe"
            val isMutation = hasBody || operation == "deleteSavedRecipe"
            val keyText = header("Idempotency-Key")
            val key = if (isMutation) keyText?.let(::uuid) ?: invalidSavedRecipe()
                else { if (keyText != null) invalidSavedRecipe(); null }
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation == "deleteSavedRecipe") {
                if (ifMatch == null) throw SavedRecipeHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!Regex("\"[0-9]{1,64}\"").matches(ifMatch)) invalidSavedRecipe()
            } else if (ifMatch != null) invalidSavedRecipe()
            if (header(HttpHeaders.IfNoneMatch) != null) invalidSavedRecipe()
            val pathName = when (operation) { "getSavedRecipe", "deleteSavedRecipe" -> "savedRecipeId"; "getCollection" -> "collectionId"; else -> null }
            // Ktor's call.parameters merges route and query parameters. Remove only the exact
            // query names already validated above; never allow a query to supply a path identity.
            if (paths.names() - query.names() != if (pathName != null) setOf(pathName) else emptySet()) invalidSavedRecipe()
            val id = if (pathName != null) paths.getAll(pathName)?.let {
                if (it.size != 1) invalidSavedRecipe()
                uuid(it.single())
            } ?: invalidSavedRecipe() else null
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalidSavedRecipe()
                it.toLongOrNull()?.takeIf { n -> n <= SavedRecipeHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidSavedRecipe()
            }
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null || !hasBody)) invalidSavedRecipe()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && encoding.lowercase() != "identity") invalidSavedRecipe()
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                    throw SavedRecipeHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || (length != null && length != 0L)) invalidSavedRecipe()
            return SavedRecipeHttpInput(operation, bearer, key, id.takeIf { pathName == "savedRecipeId" },
                id.takeIf { pathName == "collectionId" }, ifMatch, length, media, q, cursor, limit)
        }
        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidSavedRecipe()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun SavedRecipeHttpConfiguration.authenticate(input: SavedRecipeHttpInput): VerifiedSavedRecipePrincipal {
    currentCoroutineContext().ensureActive()
    val result = try { verifier.verify(input.bearer) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw SavedRecipeHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
    currentCoroutineContext().ensureActive()
    val principal = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> when (result.reason) {
            FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.INVALID_DATA,
            FailureReason.NOT_FOUND -> throw SavedRecipeHttpFailure(401, "UNAUTHENTICATED")
            FailureReason.FORBIDDEN -> throw SavedRecipeHttpFailure(403, "FORBIDDEN")
            FailureReason.RATE_LIMITED -> throw SavedRecipeHttpFailure(429, "RATE_LIMITED", result.retryAfterSeconds)
            else -> throw SavedRecipeHttpFailure(503, "AUTHENTICATION_UNAVAILABLE", result.retryAfterSeconds)
        }
    }
    if (principal.environment != environment || when (principal.kind) {
        CommandActor.ACCOUNT -> input.bearer.deviceSessionId == null ||
            principal.deviceSessionId != input.bearer.deviceSessionId || principal.guestSessionId != null
        CommandActor.GUEST -> input.bearer.deviceSessionId != null ||
            principal.deviceSessionId != null || principal.guestSessionId == null
        else -> true
    }) throw SavedRecipeHttpFailure(401, "UNAUTHENTICATED")
    return principal
}

internal suspend fun ApplicationCall.savedRecipeOperation(operation: String, configuration: SavedRecipeHttpConfiguration,
    validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = SavedRecipeHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val actor = configuration.authenticate(input)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val store = configuration.store
        // Interruption can occur after COMMIT. Preserve original key/body/precondition; never retry
        // automatically or claim rollback merely from a lost reply.
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "saveRecipe" -> savedRecipeReply(store.saveRecipe(actor, input.key!!, body!!))
                "getSavedRecipe" -> store.getSavedRecipe(actor, input.savedRecipeId!!)
                "listSavedRecipes" -> store.listSavedRecipes(actor, input.q, input.cursor, input.limit)
                "deleteSavedRecipe" -> savedRecipeReply(store.deleteSavedRecipe(actor, input.key!!, input.savedRecipeId!!, input.ifMatch!!))
                "listCollections" -> store.listCollections(actor, input.cursor, input.limit)
                "getCollection" -> store.getCollection(actor, input.collectionId!!, input.cursor, input.limit)
                else -> error("Unsupported savedRecipe operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateSavedRecipeReply(operation, reply, validator, store.policy.maxResponseBytes)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        if (reply.status == 204) respond(HttpStatusCode.NoContent)
        else respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SavedRecipeHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "SavedRecipe request unavailable",
            operationId = operation, retryAfterSeconds = failure.retryAfterSeconds)
    } catch (failure: SavedRecipeFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name,
            "SavedRecipe request unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN",
            "SavedRecipe outcome requires reconciliation", operationId = operation)
    }
}

internal fun validateSavedRecipeReply(operation: String, reply: StoredReply, validator: ContractBodyValidator, maxBytes: Int): String {
    check(operation in savedRecipeHttpOperations && maxBytes in 1..262_144)
    check(reply.status == when (operation) { "saveRecipe" -> 201; "deleteSavedRecipe" -> 204; else -> 200 })
    if (operation == "deleteSavedRecipe") { check(reply.body == null && reply.etag == null); return "" }
    val text = reply.body?.toString() ?: error("Missing savedRecipe response")
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maxBytes && validator.validateResponse(operation, reply.status, bytes, "application/json") == BodyValidationResult.Valid)
    if (operation in setOf("saveRecipe", "getSavedRecipe", "getCollection")) {
        val etag = reply.etag ?: error("Missing saved recipe version")
        check(Regex("\"[0-9]+\"").matches(etag) && etag.length <= 256)
        check(BigDecimal(etag.substring(1, etag.lastIndex)).compareTo(BigDecimal(reply.body.jsonObject.getValue("version").jsonPrimitive.content)) == 0)
    } else check(reply.etag == null)
    return text
}

private fun savedRecipeReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw SavedRecipeHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw SavedRecipeHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw SavedRecipeHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class SavedRecipeHttpFailure(val status: Int, val code: String, val retryAfterSeconds: Long? = null) :
    RuntimeException("SavedRecipe HTTP request unavailable")
private fun invalidSavedRecipe(): Nothing = throw SavedRecipeHttpFailure(400, "INVALID_REQUEST")
