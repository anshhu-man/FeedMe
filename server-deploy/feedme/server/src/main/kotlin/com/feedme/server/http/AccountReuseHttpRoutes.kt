package com.feedme.server.http

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.reuse.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private class ReuseHttpInput(val token: SecretText, val device: UUID, val key: UUID,
    val query: Map<String,List<String>>, val length: Long?, val media: String) {
    override fun toString() = "ReuseHttpInput(<redacted>)"
}
private fun reuseHttpInput(headers: Headers, query: Parameters, parameters: Parameters): ReuseHttpInput {
    if (parameters.names() - query.names() != emptySet<String>() || !setOf("cursor","limit").containsAll(query.names())) invalidReuse()
    val exactQuery = query.names().associateWith { name ->
        val values = query.getAll(name) ?: invalidReuse()
        if (values.size != 1 || values.single().any(Char::isISOControl)) invalidReuse()
        val value = values.single()
        try { value.encodeToByteArray(throwOnInvalidSequence = true) } catch (_: Exception) { invalidReuse() }
        if (name == "cursor" && value.length !in 1..2048) invalidReuse()
        if (name == "limit" && (!value.matches(Regex("[1-9][0-9]?")) || value.toIntOrNull() !in 1..50)) invalidReuse()
        values.toList()
    }
    fun header(name: String): String? = headers.getAll(name)?.let {
        if (it.size != 1 || it.single().any(Char::isISOControl)) invalidReuse(); it.single()
    }
    if (header(HttpHeaders.Upgrade) != null || header("HTTP2-Settings") != null ||
        header(HttpHeaders.Connection)?.split(',')?.any { it.trim().equals("upgrade", true) } == true ||
        header(HttpHeaders.IfMatch) != null || header(HttpHeaders.IfNoneMatch) != null) invalidReuse()
    val authorization = header(HttpHeaders.Authorization) ?: unauthenticatedReuse()
    if (authorization.length > 16_391 || authorization.contains(',')) unauthenticatedReuse()
    val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE).matchEntire(authorization)?.groupValues?.get(1)
        ?: unauthenticatedReuse()
    if (token.length !in 1..16_384 || !token.matches(Regex("[A-Za-z0-9._~+/-]+=*"))) unauthenticatedReuse()
    fun id(text: String): UUID { if (!CanonicalFormats.accepts("uuid", text)) invalidReuse(); return UUID.fromString(text) }
    val device = header("X-Device-Session")?.let(::id) ?: unauthenticatedReuse()
    val key = header("Idempotency-Key")?.let(::id) ?: invalidReuse()
    val length = header(HttpHeaders.ContentLength)?.let {
        if (!it.matches(Regex("[0-9]{1,20}"))) invalidReuse()
        it.toLongOrNull()?.takeIf { n -> n in 1..AccountReuseHttpConfiguration.MAX_REQUEST_BYTES.toLong() } ?: invalidReuse()
    }
    header(HttpHeaders.TransferEncoding)?.let { if (!it.equals("chunked",true) || length != null) invalidReuse() }
    header(HttpHeaders.ContentEncoding)?.let { if (!it.equals("identity",true)) invalidReuse() }
    val media = header(HttpHeaders.ContentType) ?: invalidReuse()
    if (!media.matches(Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?",RegexOption.IGNORE_CASE)))
        throw ReuseHttpFailure(400,"UNSUPPORTED_MEDIA")
    return ReuseHttpInput(SecretText(token), device, key, exactQuery, length, media)
}

internal suspend fun ApplicationCall.accountReuseOperation(configuration: AccountReuseHttpConfiguration,
    validator: ContractBodyValidator) {
    response.headers.append(HttpHeaders.CacheControl,"no-store")
    try {
        val input = reuseHttpInput(request.headers, request.queryParameters, parameters)
        val bytes = readBoundedHttpBody(receiveChannel(), AccountReuseHttpConfiguration.MAX_REQUEST_BYTES, input.length, ::invalidReuse)
        val body = try {
            if (bytes.isEmpty()) invalidReuse()
            val wire = try { WireDocument.decode(bytes, WireLimits(AccountReuseHttpConfiguration.MAX_REQUEST_BYTES,32)) }
                catch (_: WireDecodingException) { invalidReuse() }
            if (validator.validateRequest("createReuseOptions",bytes,input.media) != BodyValidationResult.Valid)
                throw ReuseHttpFailure(422,"INPUT_INVALID")
            Json.parseToJsonElement(wire.encodeUtf8().decodeToString()).jsonObject
        } finally { bytes.fill(0) }
        currentCoroutineContext().ensureActive()
        // Provider I/O completes before any database transaction is acquired.
        val verified = try { configuration.verifier.verify(input.token) }
            catch (f: CancellationException) { throw f }
            catch (_: Exception) { throw ReuseHttpFailure(503,"AUTHENTICATION_UNAVAILABLE") }
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> if (verified.reason in setOf(FailureReason.UNAUTHENTICATED,FailureReason.INVALID_DATA,FailureReason.STALE_SESSION))
                unauthenticatedReuse() else throw ReuseHttpFailure(503,"AUTHENTICATION_UNAVAILABLE")
        }
        currentCoroutineContext().ensureActive()
        val result = runInterruptible(configuration.databaseDispatcher) {
            configuration.store.createReuseOptions(subject,input.device,input.key,body,input.query)
        }
        currentCoroutineContext().ensureActive()
        val reply = when (result) {
            is CommandResult.Applied -> result.reply
            is CommandResult.Replayed -> result.reply
            CommandResult.Mismatch -> throw ReuseHttpFailure(409,"IDEMPOTENCY_MISMATCH")
            CommandResult.ReceiptExpired -> throw ReuseHttpFailure(410,"IDEMPOTENCY_EXPIRED")
            CommandResult.IncompleteReceipt -> throw ReuseHttpFailure(409,"COMMAND_INCOMPLETE")
        }
        check(reply.status == 201 && reply.etag == null && reply.body != null)
        val text = reply.body.toString(); val responseBytes = text.encodeToByteArray(throwOnInvalidSequence = true)
        check(responseBytes.size <= configuration.store.policy.maxResponseBytes &&
            validator.validateResponse("createReuseOptions",201,responseBytes,"application/json") == BodyValidationResult.Valid)
        respondText(text,ContentType.Application.Json,HttpStatusCode.Created)
    } catch (f: CancellationException) { throw f }
    catch (f: ReuseHttpFailure) { reuseProblem(validator,f.status,f.code) }
    catch (f: ReuseFailure) { reuseProblem(validator,f.code.status,f.code.name) }
    catch (_: CommitOutcomeUnknown) { reuseProblem(validator,503,"OUTCOME_UNKNOWN") }
}
private suspend fun ApplicationCall.reuseProblem(validator: ContractBodyValidator,status: Int,code: String) {
    currentCoroutineContext().ensureActive()
    problem(validator,HttpStatusCode.fromValue(status),code,"Reuse options unavailable",operationId="createReuseOptions")
}
private class ReuseHttpFailure(val status: Int,val code: String): RuntimeException("Reuse request unavailable")
private fun invalidReuse(): Nothing = throw ReuseHttpFailure(400,"INPUT_INVALID")
private fun unauthenticatedReuse(): Nothing = throw ReuseHttpFailure(401,"UNAUTHENTICATED")
