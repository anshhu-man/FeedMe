package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.identity.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import java.util.UUID
import kotlinx.coroutines.*

internal val accountSessionHttpOperations=setOf("listSessions","revokeSession")
internal class AccountSessionHttpInput private constructor(val token:SecretText,val device:UUID,val key:UUID?,val target:UUID?,
    val ifMatch:String?,val cursor:String?,val limit:Int,val length:Long?) {
    override fun toString()="AccountSessionHttpInput(<redacted>)"
    companion object {
        fun parse(operation:String,headers:Headers,query:Parameters,paths:Parameters):AccountSessionHttpInput {
            if(operation !in accountSessionHttpOperations)invalidSessions()
            val listing=operation=="listSessions"
            fun header(name:String):String?=headers.getAll(name)?.let{if(it.size!=1||it.single().any(Char::isISOControl))invalidSessions();it.single()}
            fun uuid(value:String):UUID{if(!CanonicalFormats.accepts("uuid",value))invalidSessions();return UUID.fromString(value)}
            val auth=header(HttpHeaders.Authorization);val device=header("X-Device-Session")
            if(auth==null||auth.length>16391||device==null)throw SessionHttpFailure(401,"UNAUTHENTICATED")
            val token=Regex("Bearer +([A-Za-z0-9._~+/-]+=*)",RegexOption.IGNORE_CASE).matchEntire(auth)?.groupValues?.get(1)
                ?.takeIf{it.length in 1..16384}?:throw SessionHttpFailure(401,"UNAUTHENTICATED")
            val keyText=header("Idempotency-Key");val match=header(HttpHeaders.IfMatch)
            val key=if(listing){if(keyText!=null||match!=null)invalidSessions();null}else keyText?.let(::uuid)?:invalidSessions()
            if(!listing){if(match==null)throw SessionHttpFailure(428,"PRECONDITION_REQUIRED");if(!match.matches(Regex("\"[1-9][0-9]{0,18}\""))||match.drop(1).dropLast(1).toLongOrNull()==null)invalidSessions()}
            if(header(HttpHeaders.IfNoneMatch)!=null)invalidSessions()
            if(paths.names()!=if(listing)emptySet() else setOf("sessionId"))invalidSessions()
            val target=if(listing)null else paths.getAll("sessionId")?.let{if(it.size!=1)invalidSessions();uuid(it.single())}?:invalidSessions()
            if(query.names().any{it !in setOf("cursor","limit")}||(!listing&&query.names().isNotEmpty()))invalidSessions()
            fun parameter(name:String):String?=query.getAll(name)?.let{if(it.size!=1)invalidSessions();it.single()}
            val cursor=parameter("cursor")?.also{if(it.length !in 1..2048||it.any(Char::isISOControl))invalidSessions()}
            val limit=parameter("limit")?.let{if(!it.matches(Regex("[1-9][0-9]?")))invalidSessions();it.toInt().takeIf{n->n in 1..50}?:invalidSessions()}?:20
            val length=header(HttpHeaders.ContentLength)?.let{if(it!="0")invalidSessions();0L}
            if(header(HttpHeaders.ContentType)!=null||header(HttpHeaders.TransferEncoding)!=null||header(HttpHeaders.ContentEncoding)?.lowercase()?.let{it!="identity"}==true)invalidSessions()
            return AccountSessionHttpInput(SecretText(token),uuid(device),key,target,match,cursor,limit,length)
        }
    }
}
internal suspend fun ApplicationCall.accountSessionOperation(operation:String,configuration:AccountSessionHttpConfiguration,validator:ContractBodyValidator) {
    response.headers.append(HttpHeaders.CacheControl,"no-store")
    try {
        currentCoroutineContext().ensureActive()
        val input=AccountSessionHttpInput.parse(operation,request.headers,request.queryParameters,parameters)
        val bytes=readBoundedHttpBody(receiveChannel(),0,input.length,::invalidSessions)
        try{if(bytes.isNotEmpty())invalidSessions()}finally{bytes.fill(0)}
        val verified=try{configuration.verifier.verify(input.token)}catch(e:CancellationException){throw e}catch(_:Exception){throw SessionHttpFailure(503,"AUTHENTICATION_UNAVAILABLE")}
        currentCoroutineContext().ensureActive()
        val subject=when(verified){is PortResult.Value->verified.value;is PortResult.Failure->if(verified.reason in setOf(FailureReason.UNAUTHENTICATED,FailureReason.INVALID_DATA,FailureReason.STALE_SESSION))throw SessionHttpFailure(401,"UNAUTHENTICATED")else throw SessionHttpFailure(503,"AUTHENTICATION_UNAVAILABLE")}
        val reply=runInterruptible(configuration.databaseDispatcher){
            if(operation=="listSessions")configuration.store.listSessions(subject,input.device,input.cursor,input.limit)
            else sessionReply(configuration.store.revokeSession(subject,input.device,input.key!!,input.target!!,input.ifMatch!!))
        }
        currentCoroutineContext().ensureActive()
        val text=validateAccountSessionReply(operation,reply,validator,configuration.store.policy.maxResponseBytes)
        if(text==null)respond(HttpStatusCode.NoContent)else respondText(text,ContentType.Application.Json,HttpStatusCode.OK)
    }catch(e:CancellationException){throw e}
    catch(e:SessionHttpFailure){currentCoroutineContext().ensureActive();problem(validator,HttpStatusCode.fromValue(e.status),e.code,"Account sessions unavailable",operationId=operation)}
    catch(e:SessionFailure){currentCoroutineContext().ensureActive();problem(validator,HttpStatusCode.fromValue(e.code.status),e.code.name,"Account sessions unavailable",operationId=operation)}
    catch(e:AccountFailure){currentCoroutineContext().ensureActive();problem(validator,HttpStatusCode.fromValue(e.code.status),e.code.name,"Account sessions unavailable",operationId=operation)}
    catch(_:CommitOutcomeUnknown){currentCoroutineContext().ensureActive();problem(validator,HttpStatusCode.ServiceUnavailable,"OUTCOME_UNKNOWN","Session revocation outcome requires reconciliation",operationId=operation)}
}
internal fun validateAccountSessionReply(operation:String,reply:StoredReply,validator:ContractBodyValidator,maximumBytes:Int):String? {
    check(operation in accountSessionHttpOperations&&reply.etag==null)
    val text=reply.body?.toString();val bytes=text?.encodeToByteArray()
    check((bytes?.size?:0)<=maximumBytes&&validator.validateResponse(operation,reply.status,bytes,if(bytes==null)null else "application/json")==BodyValidationResult.Valid)
    check(if(operation=="listSessions")reply.status==200&&text!=null else reply.status==204&&text==null)
    return text
}
private fun sessionReply(result:CommandResult):StoredReply=when(result){is CommandResult.Applied->result.reply;is CommandResult.Replayed->result.reply
    CommandResult.Mismatch->throw SessionHttpFailure(409,"IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired->throw SessionHttpFailure(410,"IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt->throw SessionHttpFailure(409,"COMMAND_INCOMPLETE")}
internal class SessionHttpFailure(val status:Int,val code:String):RuntimeException("Account session HTTP unavailable")
private fun invalidSessions():Nothing=throw SessionHttpFailure(400,"INVALID_REQUEST")
