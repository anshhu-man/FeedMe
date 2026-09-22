package com.feedme.server.http

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.staff.SupabaseStaffFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import kotlinx.coroutines.*

/** Fixed GET /v1/staff/session. Admission only; no consumer bootstrap/device, Cookie,
 * request body, caller staff id, provider policy override or mutation is accepted. */
internal suspend fun ApplicationCall.supabaseStaffSession(
    configuration: SupabaseStaffHttpConfiguration,
    validator: ContractBodyValidator,
) {
    response.headers.append(HttpHeaders.CacheControl, "private, no-store")
    response.headers.append(HttpHeaders.Pragma, "no-cache")
    response.headers.append("X-Content-Type-Options", "nosniff")
    try {
        currentCoroutineContext().ensureActive()
        if (request.httpMethod != HttpMethod.Get || request.queryParameters.names().isNotEmpty() || parameters.names().isNotEmpty()) invalidStaff()
        fun header(name: String): String? = request.headers.getAll(name)?.let {
            if (it.size != 1 || it.single().any(Char::isISOControl)) invalidStaff()
            it.single()
        }
        val authorization = header(HttpHeaders.Authorization)
        if (authorization == null || authorization.length > 16391) throw StaffHttpFailure(401, "STAFF_UNAUTHENTICATED")
        val token = Regex("Bearer ([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .matchEntire(authorization)?.groupValues?.get(1)?.takeIf { it.length in 1..16384 }
            ?: throw StaffHttpFailure(401, "STAFF_UNAUTHENTICATED")
        for (name in listOf(HttpHeaders.Cookie, "X-Device-Session", "Idempotency-Key", HttpHeaders.IfMatch, HttpHeaders.IfNoneMatch,
            HttpHeaders.ContentType, HttpHeaders.TransferEncoding, HttpHeaders.ContentEncoding)) if (header(name) != null) invalidStaff()
        val length = header(HttpHeaders.ContentLength)?.let { if (it != "0") invalidStaff(); 0L }
        val body = readBoundedHttpBody(receiveChannel(), 0, length, ::invalidStaff)
        try { if (body.isNotEmpty()) invalidStaff() } finally { body.fill(0) }
        val result = try { configuration.verifier.verify(SecretText(token)) }
            catch (e: CancellationException) { throw e }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
            catch (_: Exception) { throw StaffHttpFailure(503, "STAFF_UNAVAILABLE") }
        currentCoroutineContext().ensureActive()
        val subject = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> if (result.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION))
                throw StaffHttpFailure(401, "STAFF_UNAUTHENTICATED") else throw StaffHttpFailure(503, "STAFF_UNAVAILABLE")
        }
        val responseBody = runInterruptible(configuration.databaseDispatcher) { configuration.store.session(subject) }
        currentCoroutineContext().ensureActive()
        val text = responseBody.toString()
        check(text.encodeToByteArray().size <= configuration.store.policy.maxResponseBytes)
        respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
    } catch (e: CancellationException) { throw e }
      catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
      catch (e: StaffHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(e.status), e.code, "Staff admission unavailable")
    } catch (e: SupabaseStaffFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(e.code.status), e.code.name, "Staff admission unavailable")
    }
}

private class StaffHttpFailure(val status: Int, val code: String) : RuntimeException("Staff HTTP unavailable")
private fun invalidStaff(): Nothing = throw StaffHttpFailure(400, "INVALID_REQUEST")
