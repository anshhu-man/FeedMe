package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDecodingException
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.server.ai.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.runtime.ServiceRequestAdmission
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal const val ACCOUNT_MEAL_INTENT_OPERATION = "interpretMealRequest"

internal class AccountMealIntentHttpInput private constructor(
    val token: SecretText,
    val deviceSessionId: UUID,
    val contentLength: Long?,
    private val mediaType: String,
) {
    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): String {
        val bytes = readBoundedHttpBody(channel, AccountMealIntentHttpConfiguration.MAX_REQUEST_BYTES,
            contentLength, ::invalidMealIntentRequest)
        return try { body(bytes, validator) } finally { bytes.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): String {
        if (bytes.isEmpty() || bytes.size > AccountMealIntentHttpConfiguration.MAX_REQUEST_BYTES) invalidMealIntentRequest()
        val document = try { WireDocument.decode(bytes, WireLimits(AccountMealIntentHttpConfiguration.MAX_REQUEST_BYTES, 8)) }
            catch (_: WireDecodingException) { invalidMealIntentRequest() }
        if (validator.validateRequest(ACCOUNT_MEAL_INTENT_OPERATION, bytes, mediaType) != BodyValidationResult.Valid)
            throw AccountMealIntentHttpFailure(422, "INPUT_INVALID")
        val text = Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject.getValue("text").jsonPrimitive.content
        if (text.isBlank() || text.any { it.isISOControl() && it !in "\n\r\t" })
            throw AccountMealIntentHttpFailure(422, "INPUT_INVALID")
        return text
    }

    override fun toString() = "AccountMealIntentHttpInput(<redacted>)"

    companion object {
        fun parse(headers: Headers, query: Parameters, paths: Parameters): AccountMealIntentHttpInput {
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidMealIntentRequest()
                it.single()
            }
            val authorization = header(HttpHeaders.Authorization)
            if (authorization?.contains(',') == true) invalidMealIntentRequest()
            val device = header("X-Device-Session")
            if (authorization == null || authorization.length > 16_391 || device == null)
                throw AccountMealIntentHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: throw AccountMealIntentHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
                throw AccountMealIntentHttpFailure(401, "UNAUTHENTICATED")
            if (!CanonicalFormats.accepts("uuid", device)) invalidMealIntentRequest()
            if (query.names().isNotEmpty() || paths.names().isNotEmpty() || header("Idempotency-Key") != null ||
                header(HttpHeaders.IfMatch) != null || header(HttpHeaders.IfNoneMatch) != null) invalidMealIntentRequest()
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalidMealIntentRequest()
                it.toLongOrNull()?.takeIf { size -> size <= AccountMealIntentHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidMealIntentRequest()
            }
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null)) invalidMealIntentRequest()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && encoding.lowercase() != "identity") invalidMealIntentRequest()
            val media = header(HttpHeaders.ContentType)
            if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                throw AccountMealIntentHttpFailure(400, "UNSUPPORTED_MEDIA")
            return AccountMealIntentHttpInput(SecretText(token), UUID.fromString(device), length, media)
        }
    }
}

internal suspend fun ApplicationCall.accountMealIntentOperation(configuration: AccountMealIntentHttpConfiguration,
    validator: ContractBodyValidator) {
    var permit: ServiceRequestAdmission.Permit? = null
    try {
        currentCoroutineContext().ensureActive()
        val input = AccountMealIntentHttpInput.parse(request.headers, request.queryParameters, parameters)
        val verified = try { configuration.verifier.verify(input.token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw AccountMealIntentHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
        currentCoroutineContext().ensureActive()
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> when (verified.reason) {
                FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION ->
                    throw AccountMealIntentHttpFailure(401, "UNAUTHENTICATED")
                else -> throw AccountMealIntentHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
            }
        }
        // A verified bearer is still not current account/device/audience authority: the service
        // supplies those required checks. No unverified text is parsed or sent to that service.
        val originalText = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        permit = configuration.tryStart() ?: throw AccountMealIntentHttpFailure(429, "RATE_LIMITED")
        val result = configuration.service.interpret(subject, input.deviceSessionId, originalText)
        currentCoroutineContext().ensureActive()
        val proposal = when (result) {
            is AccountMealIntentResult.Value -> result.proposal
            is AccountMealIntentResult.Unavailable -> throw AccountMealIntentHttpFailure(result.reason.status(), result.reason.name)
        }
        val responseText = validateAccountMealIntentReply(proposal, originalText, validator)
        respondText(responseText, ContentType.Application.Json, HttpStatusCode.OK)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: AccountMealIntentHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Meal interpretation unavailable",
            operationId = ACCOUNT_MEAL_INTENT_OPERATION)
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "UNAVAILABLE", "Meal interpretation unavailable",
            operationId = ACCOUNT_MEAL_INTENT_OPERATION)
    } finally { permit?.close() }
}

/** Correlation and response validation only: no approval, confidence, availability or grant. */
internal fun validateAccountMealIntentReply(proposal: MealIntentProposal, originalText: String,
    validator: ContractBodyValidator): String {
    check(proposal.status == MealIntentProposalStatus.UNCONFIRMED && proposal.requiresUserConfirmation)
    val source = proposal.source
    check(source.originalText == originalText)
    val hash = MessageDigest.getInstance("SHA-256").digest(
        ("feedme.meal-intent-source.v1\u0000" + source.document()).toByteArray(StandardCharsets.UTF_8)
    ).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    check(source.sha256 == hash)
    val offered = source.ingredientOptions.map { it.id }.toSet()
    check(offered.size == source.ingredientOptions.size && proposal.suggestedIngredientIds.all { it in offered })
    check(proposal.unresolvedIngredients.all { originalText.contains(it.text, ignoreCase = true) })
    check(proposal.maxActiveMinutes == null || proposal.maxTotalMinutes == null || proposal.maxActiveMinutes <= proposal.maxTotalMinutes)
    val body = buildJsonObject {
        put("originalText", originalText); put("sourceSha256", source.sha256)
        put("ingredientOptions", source.document().getValue("ingredientOptions"))
        put("ingredientIds", JsonArray(proposal.suggestedIngredientIds.map { JsonPrimitive(it.toString()) }))
        putJsonArray("unresolvedIngredients") { proposal.unresolvedIngredients.forEach { item -> add(buildJsonObject {
            put("text", item.text); put("reason", item.reason.name.lowercase())
        }) } }
        put("energy", proposal.energy?.let { JsonPrimitive(it.wireValue) } ?: JsonNull)
        put("maxTotalMinutes", proposal.maxTotalMinutes?.let(::JsonPrimitive) ?: JsonNull)
        put("maxActiveMinutes", proposal.maxActiveMinutes?.let(::JsonPrimitive) ?: JsonNull)
        put("requiresUserConfirmation", true); put("status", "unconfirmed")
    }.toString()
    val bytes = body.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= AccountMealIntentHttpConfiguration.MAX_RESPONSE_BYTES &&
        validator.validateResponse(ACCOUNT_MEAL_INTENT_OPERATION, 200, bytes, "application/json") == BodyValidationResult.Valid)
    return body
}

private fun AccountMealIntentFailure.status(): Int = when (this) {
    AccountMealIntentFailure.UNAUTHENTICATED -> 401
    AccountMealIntentFailure.FORBIDDEN -> 403
    AccountMealIntentFailure.SOURCE_CHANGED -> 409
    AccountMealIntentFailure.INVALID_INPUT -> 422
    AccountMealIntentFailure.RATE_LIMITED -> 429
    else -> 503
}
internal class AccountMealIntentHttpFailure(val status: Int, val code: String) : RuntimeException("Account meal interpretation unavailable")
private fun invalidMealIntentRequest(): Nothing = throw AccountMealIntentHttpFailure(400, "INVALID_REQUEST")
