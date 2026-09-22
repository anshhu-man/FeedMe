package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDecodingException
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandActor
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import com.feedme.server.planning.PlanningServiceFailure
import com.feedme.server.planning.VerifiedPlanningPrincipal
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

internal val planningHttpOperations = setOf("createPlan", "getPlan", "getPlanExplanation", "nextPlan")
internal val accountPlanningHttpOperations = planningHttpOperations + setOf("simplifyPlan", "adaptPlan", "listRecipes", "getRecipeVersion")

/** Decoded HTTP metadata only. Arbitrary incoming trace IDs and URI text never become diagnostics. */
internal class PlanningHttpInput private constructor(
    val operation: String, val bearer: PlanningHttpBearer, val key: UUID?, val planId: UUID?,
    val cursor: String?, val limit: Int, val contentLength: Long?, val mediaType: String?, val ifMatch: String?,
    val query: String = "", val recipeId: UUID? = null, val recipeVersionId: UUID? = null,
) {
    val hasBody: Boolean get() = operation in setOf("createPlan", "nextPlan", "simplifyPlan", "adaptPlan")
    override fun toString() = "PlanningHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val exact = readBoundedHttpBody(channel, if (hasBody) PlanningHttpConfiguration.MAX_REQUEST_BYTES else 0, contentLength, ::invalid)
        return try { body(exact, validator) } finally { exact.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): JsonObject? {
        if (!hasBody) { if (bytes.isNotEmpty()) invalid(); return null }
        if (bytes.isEmpty() || bytes.size > PlanningHttpConfiguration.MAX_REQUEST_BYTES) invalid()
        val document = try { WireDocument.decode(bytes, WireLimits(PlanningHttpConfiguration.MAX_REQUEST_BYTES, 32)) }
            catch (_: WireDecodingException) { invalid() }
        if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
            throw PlanningHttpFailure(422, "INPUT_INVALID")
        // Validate the original bytes first: never normalize away duplicates/Unicode or coerce
        // numeric tokens through Double. The service's existing JSON API preserves their lexemes.
        return Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    }

    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): PlanningHttpInput =
            parseFor(operation, headers, query, paths, planningHttpOperations)

        /** The account composition owns derived proposals; this does not activate them on
         * the independent legacy/guest planning authority. */
        fun parseAccount(operation: String, headers: Headers, query: Parameters, paths: Parameters): PlanningHttpInput =
            parseFor(operation, headers, query, paths, accountPlanningHttpOperations)

        private fun parseFor(operation: String, headers: Headers, query: Parameters, paths: Parameters,
            supported: Set<String>): PlanningHttpInput {
            if (operation !in supported) invalid()
            fun header(name: String): String? = headers.getAll(name)?.let { values ->
                if (values.size != 1 || values.single().any(Char::isISOControl)) invalid()
                values.single()
            }
            val authorization = header(HttpHeaders.Authorization) ?: throw PlanningHttpFailure(401, "UNAUTHENTICATED")
            if (authorization.length > 16_391) throw PlanningHttpFailure(401, "UNAUTHENTICATED")
            val match = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE).matchEntire(authorization)
                ?: throw PlanningHttpFailure(401, "UNAUTHENTICATED")
            val token = match.groupValues[1]
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
                throw PlanningHttpFailure(401, "UNAUTHENTICATED")
            val device = header("X-Device-Session")?.let(::uuid)
            val body = operation in setOf("createPlan", "nextPlan", "simplifyPlan", "adaptPlan")
            val keyText = header("Idempotency-Key")
            val key = if (body) keyText?.let(::uuid) ?: invalid() else { if (keyText != null) invalid(); null }
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation in setOf("simplifyPlan", "adaptPlan")) {
                if (ifMatch == null) throw PlanningHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!Regex("\"[0-9]{1,64}\"").matches(ifMatch)) invalid()
            } else if (ifMatch != null) invalid()
            if (header(HttpHeaders.IfNoneMatch) != null) invalid()
            val allowedQuery = when (operation) {
                "getPlanExplanation" -> setOf("cursor", "limit")
                "listRecipes" -> setOf("cursor", "limit", "q")
                else -> emptySet()
            }
            if (!allowedQuery.containsAll(query.names())) invalid()
            fun parameter(name: String): String? = query.getAll(name)?.let { values ->
                if (values.size != 1 || values.single().any(Char::isISOControl)) invalid()
                values.single()
            }
            val cursor = parameter("cursor")?.also { if (it.length > 2048) invalid() }
            val limit = parameter("limit")?.let {
                if (!Regex("[1-9][0-9]?").matches(it)) invalid()
                it.toInt().also { number -> if (number !in 1..50) invalid() }
            } ?: 20
            val queryText = parameter("q") ?: ""
            if (queryText.codePointCount(0, queryText.length) > 100) invalid()
            val planId = if (operation in setOf("createPlan", "listRecipes", "getRecipeVersion")) null else {
                paths.getAll("planId")?.let { values ->
                    if (values.size != 1) invalid(); uuid(values.single())
                } ?: invalid()
            }
            fun pathId(name: String): UUID = paths.getAll(name)?.let { values ->
                if (values.size != 1) invalid(); uuid(values.single())
            } ?: invalid()
            val recipeId = if (operation == "getRecipeVersion") pathId("recipeId") else null
            val recipeVersionId = if (operation == "getRecipeVersion") pathId("recipeVersionId") else null
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalid()
                it.toLongOrNull()?.takeIf { number -> number <= PlanningHttpConfiguration.MAX_REQUEST_BYTES } ?: invalid()
            }
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null || !body)) invalid()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && encoding.lowercase() != "identity") invalid()
            val media = header(HttpHeaders.ContentType)
            if (body) {
                if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                    throw PlanningHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || (length != null && length != 0L)) invalid()
            return PlanningHttpInput(operation, PlanningHttpBearer(SecretText(token), device), key, planId, cursor, limit, length, media, ifMatch,
                queryText, recipeId, recipeVersionId)
        }
        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalid()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun PlanningHttpConfiguration.authenticate(input: PlanningHttpInput): VerifiedPlanningPrincipal {
    currentCoroutineContext().ensureActive()
    val result = try { verifier.verify(input.bearer) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw PlanningHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
    currentCoroutineContext().ensureActive()
    val principal = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> when (result.reason) {
            FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.INVALID_DATA,
            FailureReason.NOT_FOUND -> throw PlanningHttpFailure(401, "UNAUTHENTICATED")
            FailureReason.FORBIDDEN -> throw PlanningHttpFailure(403, "FORBIDDEN")
            FailureReason.RATE_LIMITED -> throw PlanningHttpFailure(429, "RATE_LIMITED", result.retryAfterSeconds)
            else -> throw PlanningHttpFailure(503, "AUTHENTICATION_UNAVAILABLE", result.retryAfterSeconds)
        }
    }
    if (principal.environment != environment || when (principal.kind) {
        CommandActor.ACCOUNT -> input.bearer.deviceSessionId == null || principal.deviceSessionId != input.bearer.deviceSessionId
        CommandActor.GUEST -> input.bearer.deviceSessionId != null || principal.deviceSessionId != null
        else -> true
    }) throw PlanningHttpFailure(401, "UNAUTHENTICATED")
    return principal
}

internal suspend fun ApplicationCall.planningOperation(operation: String, configuration: PlanningHttpConfiguration,
    validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = PlanningHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val principal = configuration.authenticate(input)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        // Cancellation may interrupt JDBC, but a successful COMMIT is not undone by a lost
        // HTTP response. Never rotate the command identity or automatically repeat here.
        val result = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "createPlan" -> reply(configuration.store.createPlan(principal, input.key!!, body!!))
                "nextPlan" -> reply(configuration.store.nextPlan(principal, input.key!!, input.planId!!, body!!))
                "getPlan" -> configuration.store.getPlan(principal, input.planId!!)
                "getPlanExplanation" -> configuration.store.getPlanExplanation(principal, input.planId!!, input.cursor, input.limit)
                else -> error("Unsupported planning operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validatePlanningReply(operation, result, validator)
        result.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(result.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: PlanningHttpFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Planning request unavailable", operationId = operation, retryAfterSeconds = failure.retryAfterSeconds) }
    catch (failure: PlanningServiceFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Planning request unavailable", operationId = operation) }
    catch (_: CommitOutcomeUnknown) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Planning outcome requires reconciliation", operationId = operation) }
}

internal fun validatePlanningReply(operation: String, reply: StoredReply, validator: ContractBodyValidator): String {
    val text = reply.body?.toString() ?: error("Missing planning response")
    val expectedStatus = if (operation == "createPlan") 201 else 200
    val maximum = if (operation == "listRecipes") 1_048_576 else 262_144
    check(reply.status == expectedStatus && text.toByteArray(Charsets.UTF_8).size <= maximum &&
        validator.validateResponse(operation, reply.status, text.toByteArray(Charsets.UTF_8), "application/json") == BodyValidationResult.Valid)
    if (operation in setOf("getPlanExplanation", "listRecipes")) check(reply.etag == null)
    else {
        val etag = reply.etag ?: error("Missing planning version")
        check(Regex("\"[0-9]+\"").matches(etag) && etag.length <= 256)
        check(BigDecimal(etag.substring(1, etag.lastIndex)).compareTo(BigDecimal(reply.body.jsonObject.getValue("version").jsonPrimitive.content)) == 0)
    }
    return text
}

private fun reply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw PlanningHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw PlanningHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw PlanningHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class PlanningHttpFailure(val status: Int, val code: String, val retryAfterSeconds: Long? = null) : RuntimeException("Planning HTTP request unavailable")
private fun invalid(): Nothing = throw PlanningHttpFailure(400, "INVALID_REQUEST")
