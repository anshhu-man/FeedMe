package com.feedme.server.http

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.identity.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal val accountNotificationHttpOperations=setOf("getNotificationSettings","updateNotificationSettings")
internal suspend fun ApplicationCall.accountNotificationOperation(operation:String,configuration:AccountNotificationHttpConfiguration,validator:ContractBodyValidator) {
    response.headers.append(HttpHeaders.CacheControl,"no-store")
    try {
        currentCoroutineContext().ensureActive()
        if(operation !in accountNotificationHttpOperations||request.queryParameters.names().isNotEmpty()||parameters.names().isNotEmpty())invalidNotification()
        val writing=operation=="updateNotificationSettings"
        fun header(name:String)=request.headers.getAll(name)?.let{if(it.size!=1||it.single().any(Char::isISOControl))invalidNotification();it.single()}
        fun uuid(value:String):UUID{if(!CanonicalFormats.accepts("uuid",value))invalidNotification();return UUID.fromString(value)}
        val authorization=header(HttpHeaders.Authorization);val deviceText=header("X-Device-Session")
        if(authorization==null||authorization.length>16391||deviceText==null)throw NotificationHttpFailure(401,"UNAUTHENTICATED")
        val token=Regex("Bearer +([A-Za-z0-9._~+/-]+=*)",RegexOption.IGNORE_CASE).matchEntire(authorization)?.groupValues?.get(1)?.takeIf{it.length in 1..16384}?:throw NotificationHttpFailure(401,"UNAUTHENTICATED")
        val device=uuid(deviceText);val keyText=header("Idempotency-Key");val match=header(HttpHeaders.IfMatch)
        val key=if(writing)keyText?.let(::uuid)?:invalidNotification() else {if(keyText!=null||match!=null)invalidNotification();null}
        if(writing){if(match==null)throw NotificationHttpFailure(428,"PRECONDITION_REQUIRED");if(!match.matches(Regex("\"[1-9][0-9]{0,18}\""))||match.removeSurrounding("\"").toLongOrNull()==null)invalidNotification()}
        if(header(HttpHeaders.IfNoneMatch)!=null)invalidNotification()
        val length=header(HttpHeaders.ContentLength)?.let{if(!it.matches(Regex("[0-9]{1,5}")))invalidNotification();it.toLong().takeIf{n->n<=if(writing)4096L else 0L}?:invalidNotification()}
        val transfer=header(HttpHeaders.TransferEncoding)
        if(transfer!=null&&(!writing||length!=null||transfer.lowercase()!="chunked"))invalidNotification()
        if(header(HttpHeaders.ContentEncoding)?.lowercase()?.let{it!="identity"}==true)invalidNotification()
        val media=header(HttpHeaders.ContentType)
        if(writing){if(media==null||!Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?",RegexOption.IGNORE_CASE).matches(media))throw NotificationHttpFailure(400,"UNSUPPORTED_MEDIA")}
        else if(media!=null)invalidNotification()
        val bytes=readBoundedHttpBody(receiveChannel(),if(writing)4096 else 0,length,::invalidNotification)
        val body=try { if(!writing){if(bytes.isNotEmpty())invalidNotification();null}else{
            if(bytes.isEmpty())invalidNotification()
            val decoded=try{WireDocument.decode(bytes,WireLimits(4096,4))}catch(_:WireDecodingException){invalidNotification()}
            if(validator.validateRequest(operation,bytes,media)!=BodyValidationResult.Valid)throw NotificationHttpFailure(422,"INPUT_INVALID")
            Json.parseToJsonElement(decoded.encodeUtf8().decodeToString()).jsonObject
        }}finally{bytes.fill(0)}
        val verified=try{configuration.verifier.verify(SecretText(token))}catch(e:CancellationException){throw e}catch(_:Exception){throw NotificationHttpFailure(503,"AUTHENTICATION_UNAVAILABLE")}
        currentCoroutineContext().ensureActive()
        val subject=when(verified){is PortResult.Value->verified.value;is PortResult.Failure->if(verified.reason in setOf(FailureReason.UNAUTHENTICATED,FailureReason.STALE_SESSION,FailureReason.INVALID_DATA))throw NotificationHttpFailure(401,"UNAUTHENTICATED")else throw NotificationHttpFailure(503,"AUTHENTICATION_UNAVAILABLE")}
        val reply=runInterruptible(configuration.databaseDispatcher){if(writing)notificationReply(configuration.store.updateNotificationSettings(subject,device,key!!,match!!,body!!))else configuration.store.getNotificationSettings(subject,device)}
        currentCoroutineContext().ensureActive()
        val text=checkNotNull(reply.body).toString();val responseBytes=text.encodeToByteArray()
        check(reply.status==200&&responseBytes.size<=configuration.store.policy.maxResponseBytes&&reply.etag=="\"${reply.body!!.jsonObject.getValue("version").jsonPrimitive.content}\""&&validator.validateResponse(operation,200,responseBytes,"application/json")==BodyValidationResult.Valid)
        response.headers.append(HttpHeaders.ETag,checkNotNull(reply.etag));respondText(text,ContentType.Application.Json,HttpStatusCode.OK)
    }catch(e:CancellationException){throw e}
    catch(e:NotificationHttpFailure){currentCoroutineContext().ensureActive();problem(validator,HttpStatusCode.fromValue(e.status),e.code,"Notification preferences unavailable",operationId=operation)}
    catch(e:NotificationFailure){currentCoroutineContext().ensureActive();problem(validator,HttpStatusCode.fromValue(e.code.status),e.code.name,"Notification preferences unavailable",operationId=operation)}
    catch(e:AccountFailure){currentCoroutineContext().ensureActive();problem(validator,HttpStatusCode.fromValue(e.code.status),e.code.name,"Notification preferences unavailable",operationId=operation)}
    catch(_:CommitOutcomeUnknown){currentCoroutineContext().ensureActive();problem(validator,HttpStatusCode.ServiceUnavailable,"OUTCOME_UNKNOWN","Notification save requires reconciliation",operationId=operation)}
}
private fun notificationReply(result:CommandResult):StoredReply=when(result){is CommandResult.Applied->result.reply;is CommandResult.Replayed->result.reply;CommandResult.Mismatch->throw NotificationHttpFailure(409,"IDEMPOTENCY_MISMATCH");CommandResult.ReceiptExpired->throw NotificationHttpFailure(410,"IDEMPOTENCY_EXPIRED");CommandResult.IncompleteReceipt->throw NotificationHttpFailure(409,"COMMAND_INCOMPLETE")}
private class NotificationHttpFailure(val status:Int,val code:String):RuntimeException("Notification HTTP unavailable")
private fun invalidNotification():Nothing=throw NotificationHttpFailure(400,"INVALID_REQUEST")
