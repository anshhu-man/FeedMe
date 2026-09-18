package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.IngredientLabelSelection
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
import com.feedme.server.kitchen.KitchenFailure
import com.feedme.server.kitchen.KitchenIngredientSearch
import com.feedme.server.kitchen.KitchenStore
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.math.BigDecimal
import java.nio.charset.CharacterCodingException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

internal val kitchenHttpOperations = setOf("getPreferences", "updatePreferences", "listPantry",
    "upsertPantryItem", "removePantryItem", "searchIngredients")
internal val pantryHttpOperations = setOf("listPantry", "upsertPantryItem", "removePantryItem")
private val kitchenBodyOperations = setOf("updatePreferences", "upsertPantryItem")
private val kitchenMutationOperations = kitchenBodyOperations + "removePantryItem"
private val kitchenVersionOperations = setOf("updatePreferences", "removePantryItem")

/** Parsed controls only; never include raw URI, preferences, pantry or token text in diagnostics. */
internal class KitchenHttpInput private constructor(
    val operation: String, val bearer: KitchenHttpBearer, val key: UUID?, val ingredientId: UUID?,
    val ifMatch: String?, val cursor: String?, val query: String?, val limit: Int,
    val contentLength: Long?, val mediaType: String?,
    val ingredientIds: List<UUID>?,
) {
    val hasBody: Boolean get() = operation in kitchenBodyOperations
    override fun toString() = "KitchenHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val exact = readBoundedHttpBody(channel, if (hasBody) KitchenHttpConfiguration.MAX_REQUEST_BYTES else 0, contentLength, ::invalidKitchen)
        return try { body(exact, validator) } finally { exact.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): JsonObject? {
        if (!hasBody) { if (bytes.isNotEmpty()) invalidKitchen(); return null }
        if (bytes.isEmpty() || bytes.size > KitchenHttpConfiguration.MAX_REQUEST_BYTES) invalidKitchen()
        val document = try { WireDocument.decode(bytes, WireLimits(KitchenHttpConfiguration.MAX_REQUEST_BYTES, 32)) }
            catch (_: WireDecodingException) { invalidKitchen() }
        if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
            throw KitchenHttpFailure(422, "INPUT_INVALID")
        // Original bytes are validated before projection: duplicate/Unicode ambiguity cannot
        // disappear, and exact numeric lexemes never pass through Double before command hashing.
        return Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    }

    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): KitchenHttpInput {
            if (operation !in kitchenHttpOperations) invalidKitchen()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidKitchen()
                it.single()
            }
            val authorization = header(HttpHeaders.Authorization) ?: throw KitchenHttpFailure(401, "UNAUTHENTICATED")
            // HTTP engines may fold repeated field lines. This Authorization profile is not a list.
            if (authorization.contains(',')) invalidKitchen()
            if (authorization.length > 16_391) throw KitchenHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: throw KitchenHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
                throw KitchenHttpFailure(401, "UNAUTHENTICATED")
            val bearer = KitchenHttpBearer(SecretText(token), header("X-Device-Session")?.let(::uuid))
            val keyText = header("Idempotency-Key")
            val key = if (operation in kitchenMutationOperations) keyText?.let(::uuid) ?: invalidKitchen()
                else { if (keyText != null) invalidKitchen(); null }
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation in kitchenVersionOperations) {
                if (ifMatch == null) throw KitchenHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!Regex("\"[0-9]{1,64}\"").matches(ifMatch)) invalidKitchen()
            } else if (ifMatch != null) invalidKitchen()
            if (header(HttpHeaders.IfNoneMatch) != null) invalidKitchen() // No canonical304 response.
            val allowedQuery = when (operation) {
                "listPantry" -> setOf("cursor", "limit")
                "searchIngredients" -> setOf("q", "cursor", "limit", "ids")
                else -> emptySet()
            }
            if (!allowedQuery.containsAll(query.names())) invalidKitchen()
            fun parameter(name: String, maxLength: Int): String? = query.getAll(name)?.let {
                if (it.size != 1) invalidKitchen()
                val value = it.single()
                if (value.any(Char::isISOControl) || value.codePointCount(0, value.length) > maxLength) invalidKitchen()
                try { value.encodeToByteArray(throwOnInvalidSequence = true) }
                catch (_: CharacterCodingException) { invalidKitchen() }
                value
            }
            val ingredientIds = parameter("ids", 1849)?.let { value ->
                if (query.names().any { it in setOf("q", "cursor", "limit") }) invalidKitchen()
                (IngredientLabelSelection.parse(value) ?: invalidKitchen()).map(UUID::fromString)
            }
            val cursor = parameter("cursor", 2048)
            val search = parameter("q", 100) // Preserve absent versus explicitly empty search.
            val limit = parameter("limit", 2)?.let {
                if (!Regex("[1-9][0-9]?").matches(it)) invalidKitchen()
                it.toInt().also { n -> if (n !in 1..50) invalidKitchen() }
            } ?: 20
            val ingredient = if (operation == "removePantryItem") paths.getAll("ingredientId")?.let {
                if (it.size != 1) invalidKitchen(); uuid(it.single())
            } ?: invalidKitchen() else null
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalidKitchen()
                it.toLongOrNull()?.takeIf { n -> n <= KitchenHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidKitchen()
            }
            val hasBody = operation in kitchenBodyOperations
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null || !hasBody)) invalidKitchen()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && encoding.lowercase() != "identity") invalidKitchen()
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                    throw KitchenHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || (length != null && length != 0L)) invalidKitchen()
            return KitchenHttpInput(operation, bearer, key, ingredient, ifMatch, cursor, search, limit, length, media, ingredientIds)
        }
        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidKitchen()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun KitchenHttpConfiguration.authenticate(input: KitchenHttpInput): VerifiedKitchenPrincipal =
    authenticateKitchen(input, environment, verifier)

internal suspend fun PantryHttpConfiguration.authenticate(input: KitchenHttpInput): VerifiedKitchenPrincipal =
    authenticateKitchen(input, environment, verifier)

private suspend fun authenticateKitchen(input: KitchenHttpInput, environment: String,
    verifier: KitchenHttpVerifier): VerifiedKitchenPrincipal {
    currentCoroutineContext().ensureActive()
    val result = try { verifier.verify(input.bearer) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw KitchenHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
    currentCoroutineContext().ensureActive()
    val principal = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> when (result.reason) {
            FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.INVALID_DATA,
            FailureReason.NOT_FOUND -> throw KitchenHttpFailure(401, "UNAUTHENTICATED")
            FailureReason.FORBIDDEN -> throw KitchenHttpFailure(403, "FORBIDDEN")
            FailureReason.RATE_LIMITED -> throw KitchenHttpFailure(429, "RATE_LIMITED", result.retryAfterSeconds)
            else -> throw KitchenHttpFailure(503, "AUTHENTICATION_UNAVAILABLE", result.retryAfterSeconds)
        }
    }
    if (principal.environment != environment || when (principal.kind) {
        CommandActor.ACCOUNT -> input.bearer.deviceSessionId == null || principal.deviceSessionId != input.bearer.deviceSessionId
        CommandActor.GUEST -> input.bearer.deviceSessionId != null || principal.deviceSessionId != null
        else -> true
    }) throw KitchenHttpFailure(401, "UNAUTHENTICATED")
    return principal
}

internal suspend fun ApplicationCall.kitchenOperation(operation: String, configuration: KitchenHttpConfiguration,
    validator: ContractBodyValidator) = kitchenOperation(operation, configuration.environment, configuration.store,
        configuration.verifier, configuration.databaseDispatcher, configuration.ingredientSearch, validator)

/** This purpose-fixed entry cannot select preference/search authority by request or fallback. */
internal suspend fun ApplicationCall.pantryOperation(operation: String, configuration: PantryHttpConfiguration,
    validator: ContractBodyValidator) {
    require(operation in pantryHttpOperations)
    kitchenOperation(operation, configuration.environment, configuration.store, configuration.verifier,
        configuration.databaseDispatcher, null, validator)
}

private suspend fun ApplicationCall.kitchenOperation(operation: String, environment: String, store: KitchenStore,
    verifier: KitchenHttpVerifier, databaseDispatcher: CoroutineDispatcher,
    ingredientSearch: KitchenIngredientSearch?, validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = KitchenHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val actor = authenticateKitchen(input, environment, verifier)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        // An interrupted JDBC caller or lost response does not undo COMMIT. Never rotate a key,
        // rebase a body/version, automatically repeat a mutation or imply rollback at this boundary.
        val reply = runInterruptible(databaseDispatcher) {
            when (operation) {
                "getPreferences" -> store.getPreferences(actor)
                "listPantry" -> store.listPantry(actor, input.cursor, input.limit)
                "searchIngredients" -> input.ingredientIds?.let { store.lookupIngredients(actor, it, requireNotNull(ingredientSearch)) }
                    ?: store.searchIngredients(actor, input.query, input.cursor, input.limit, requireNotNull(ingredientSearch))
                "updatePreferences" -> kitchenReply(store.updatePreferences(actor, input.key!!, input.ifMatch!!, body!!))
                "upsertPantryItem" -> kitchenReply(store.upsertPantryItem(actor, input.key!!, body!!))
                "removePantryItem" -> kitchenReply(store.removePantryItem(actor, input.key!!, input.ingredientId!!, input.ifMatch!!))
                else -> error("Unsupported kitchen operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateKitchenReply(operation, reply, validator, store.policy.maxResponseBytes)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        if (text == null) respond(HttpStatusCode.NoContent)
        else respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: KitchenHttpFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Kitchen request unavailable", operationId = operation, retryAfterSeconds = failure.retryAfterSeconds) }
    catch (failure: KitchenFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Kitchen request unavailable", operationId = operation) }
    catch (_: CommitOutcomeUnknown) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Kitchen outcome requires reconciliation", operationId = operation) }
}

internal fun validateKitchenReply(operation: String, reply: StoredReply, validator: ContractBodyValidator, maxBytes: Int): String? {
    check(operation in kitchenHttpOperations && maxBytes in 1..262_144)
    val status = if (operation == "removePantryItem") 204 else 200
    check(reply.status == status)
    if (status == 204) {
        check(reply.body == null && reply.etag == null && validator.validateResponse(operation, status, null, null) == BodyValidationResult.Valid)
        return null
    }
    val text = reply.body?.toString() ?: error("Missing kitchen response")
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maxBytes && validator.validateResponse(operation, status, bytes, "application/json") == BodyValidationResult.Valid)
    if (operation in setOf("listPantry", "searchIngredients")) check(reply.etag == null)
    else {
        val etag = reply.etag ?: error("Missing kitchen version")
        check(Regex("\"[0-9]+\"").matches(etag) && etag.length <= 256)
        check(BigDecimal(etag.substring(1, etag.lastIndex)).compareTo(BigDecimal(reply.body.jsonObject.getValue("version").jsonPrimitive.content)) == 0)
    }
    return text
}

internal fun kitchenReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw KitchenHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw KitchenHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw KitchenHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class KitchenHttpFailure(val status: Int, val code: String, val retryAfterSeconds: Long? = null) : RuntimeException("Kitchen HTTP request unavailable")
private fun invalidKitchen(): Nothing = throw KitchenHttpFailure(400, "INVALID_REQUEST")
