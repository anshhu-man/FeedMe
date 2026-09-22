package com.feedme.server.http

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.export.*
import com.feedme.server.identity.AccountFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal val accountExportHttpOperations=setOf("requestAccountExport","getJob")
internal suspend fun ApplicationCall.accountExportOperation(operation:String,configuration:AccountExportHttpConfiguration,validator:ContractBodyValidator) {
    response.headers.append(HttpHeaders.CacheControl,"private, no-store")
    try {
        currentCoroutineContext().ensureActive()
        val writing=operation=="requestAccountExport"
        if(operation !in accountExportHttpOperations||request.queryParameters.names().isNotEmpty()||parameters.names()!=(if(writing)emptySet<String>()else setOf("jobId")))invalidExport()
        fun header(name:String)=request.headers.getAll(name)?.let{if(it.size!=1||it.single().any(Char::isISOControl))invalidExport();it.single()}
        fun uuid(value:String):UUID{if(!CanonicalFormats.accepts("uuid",value))invalidExport();return UUID.fromString(value)}
        val authorization=header(HttpHeaders.Authorization);val deviceText=header("X-Device-Session")
        if(authorization==null||authorization.length>16391||deviceText==null)throw ExportHttpFailure(401,"UNAUTHENTICATED")
        val token=Regex("Bearer +([A-Za-z0-9._~+/-]+=*)",RegexOption.IGNORE_CASE).matchEntire(authorization)?.groupValues?.get(1)?.takeIf{it.length in 1..16384}?:throw ExportHttpFailure(401,"UNAUTHENTICATED")
        val device=uuid(deviceText);val keyText=header("Idempotency-Key")
        val key=if(writing)keyText?.let(::uuid)?:invalidExport()else {if(keyText!=null)invalidExport();null}
        val job=if(writing)null else uuid(parameters.getAll("jobId")?.singleOrNull()?:invalidExport())
        if(header(HttpHeaders.IfMatch)!=null||header(HttpHeaders.IfNoneMatch)!=null)invalidExport()
        val length=header(HttpHeaders.ContentLength)?.let{if(!it.matches(Regex("[0-9]{1,5}")))invalidExport();it.toLong().takeIf{n->n<=if(writing)4096L else 0L}?:invalidExport()}
        val transfer=header(HttpHeaders.TransferEncoding)
        if(transfer!=null&&(!writing||length!=null||transfer.lowercase()!="chunked"))invalidExport()
        if(header(HttpHeaders.ContentEncoding)?.lowercase()?.let{it!="identity"}==true)invalidExport()
        val media=header(HttpHeaders.ContentType)
        if(writing){if(media==null||!Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?",RegexOption.IGNORE_CASE).matches(media))throw ExportHttpFailure(400,"UNSUPPORTED_MEDIA")}
        else if(media!=null)invalidExport()
        val bytes=readBoundedHttpBody(receiveChannel(),if(writing)4096 else 0,length,::invalidExport)
        val body=try {if(!writing){if(bytes.isNotEmpty())invalidExport();null}else{
            if(bytes.isEmpty())invalidExport()
            val parsed=try{WireDocument.decode(bytes,WireLimits(4096,4))}catch(_:WireDecodingException){invalidExport()}
            if(validator.validateRequest(operation,bytes,media)!=BodyValidationResult.Valid)throw ExportHttpFailure(422,"INPUT_INVALID")
            Json.parseToJsonElement(parsed.encodeUtf8().decodeToString()).jsonObject
        }}finally{bytes.fill(0)}
        val verified=try{configuration.verifier.verify(SecretText(token))}catch(e:CancellationException){throw e}catch(_:Exception){throw ExportHttpFailure(503,"AUTHENTICATION_UNAVAILABLE")}
        currentCoroutineContext().ensureActive()
        val subject=when(verified){is PortResult.Value->verified.value;is PortResult.Failure->if(verified.reason in setOf(FailureReason.INVALID_DATA,FailureReason.UNAUTHENTICATED,FailureReason.STALE_SESSION))throw ExportHttpFailure(401,"UNAUTHENTICATED")else throw ExportHttpFailure(503,"AUTHENTICATION_UNAVAILABLE")}
        val reply=runInterruptible(configuration.databaseDispatcher){if(writing)exportReply(configuration.store.requestAccountExport(subject,device,key!!,body!!))else configuration.store.getJob(subject,device,job!!)}
        currentCoroutineContext().ensureActive()
        val text=checkNotNull(reply.body).toString();val replyBytes=text.encodeToByteArray()
        check(reply.status==(if(writing)202 else 200))
        check(replyBytes.size<=configuration.store.policy.maxResponseBytes&&validator.validateResponse(operation,reply.status,replyBytes,"application/json")==BodyValidationResult.Valid)
        if(writing){check(reply.etag==null&&reply.body!!.jsonObject["status"]==JsonPrimitive("pending"))}
        else {check(reply.etag=="\"${reply.body!!.jsonObject.getValue("version").jsonPrimitive.content}\"");response.headers.append(HttpHeaders.ETag,checkNotNull(reply.etag))}
        respondText(text,ContentType.Application.Json,HttpStatusCode.fromValue(reply.status))
    }catch(e:CancellationException){throw e}
    catch(e:ExportHttpFailure){currentCoroutineContext().ensureActive();problem(validator,HttpStatusCode.fromValue(e.status),e.code,"Account export unavailable",operationId=operation)}
    catch(e:ExportFailure){currentCoroutineContext().ensureActive();problem(validator,HttpStatusCode.fromValue(e.code.status),e.code.name,"Account export unavailable",operationId=operation)}
    catch(e:AccountFailure){currentCoroutineContext().ensureActive();problem(validator,HttpStatusCode.fromValue(e.code.status),e.code.name,"Account export unavailable",operationId=operation)}
    catch(_:CommitOutcomeUnknown){currentCoroutineContext().ensureActive();problem(validator,HttpStatusCode.ServiceUnavailable,"OUTCOME_UNKNOWN","Export request requires reconciliation",operationId=operation)}
}
private fun exportReply(result:CommandResult):StoredReply=when(result){is CommandResult.Applied->result.reply;is CommandResult.Replayed->result.reply;CommandResult.Mismatch->throw ExportHttpFailure(409,"IDEMPOTENCY_MISMATCH");CommandResult.ReceiptExpired->throw ExportHttpFailure(410,"IDEMPOTENCY_EXPIRED");CommandResult.IncompleteReceipt->throw ExportHttpFailure(409,"COMMAND_INCOMPLETE")}
private class ExportHttpFailure(val status:Int,val code:String):RuntimeException("Export HTTP unavailable")
private fun invalidExport():Nothing=throw ExportHttpFailure(400,"INVALID_REQUEST")
