package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.media.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/** Actual Ktor/CIO and PostgreSQL; synthetic verified identity, terms, immutable metadata and signer only. */
class MediaHttpIntegrationTest {
    @Test fun allFourOperationsExposeOnlyCanonicalCapabilitiesAndVersions()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())}
        val created=client.prepare(f);success(created,201,"prepareMediaUpload");val media=json(created)
        assertEquals("POST",media.text("uploadMethod"));assertEquals("awaitingUpload",media.text("status"))
        val observed=client.get("/v1/media/${media.text("id")}"){auth(f)};success(observed,200,"getMediaStatus");assertEquals(core(media),json(observed))
        val completed=client.complete(f,media,f.test.upload(media).toString());success(completed,200,"completeMediaUpload")
        assertEquals("processing",json(completed).text("status"));assertFalse(json(completed).containsKey("uploadUrl"))
        success(client.remove(f,media,etag="\"2\""),204,"deleteDraftMedia")
        val deleted=client.get("/v1/media/${media.text("id")}"){auth(f)};success(deleted,200,"getMediaStatus");assertEquals("deleted",json(deleted).text("status"))
        assertEquals(1,f.test.count("platform.outbox"));assertEquals(1,f.test.count("platform.media_cleanup_jobs"))
    }
    @Test fun sameKeyKeepsCoreEtagAndOneReservationButReissuesOnlyEphemeralFields()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())};val key=UUID.randomUUID();val input=f.input()
        val first=client.prepare(f,key,input);val second=client.prepare(f,key,input);success(first,201,"prepareMediaUpload");success(second,201,"prepareMediaUpload")
        assertEquals(core(json(first)),core(json(second)));assertEquals(first.headers[HttpHeaders.ETag],second.headers[HttpHeaders.ETag]);assertNotEquals(json(first)["uploadFields"],json(second)["uploadFields"])
        val receipt=f.test.value("SELECT response_json::text FROM platform.idempotency");assertFalse(receipt.contains("upload"));assertFalse(receipt.contains("synthetic-secret"))
        assertEquals(1,f.test.count("platform.media_assets"));assertEquals(1,f.test.count("platform.idempotency"))
        problem(client.prepare(f,key,JsonObject(parse(input)+("bytes" to JsonPrimitive(129))).toString()),409,"IDEMPOTENCY_MISMATCH")
    }
    @Test fun processingPreventsOldPrepareAndCompletionReplaysWithoutNewVerification()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())};val key=UUID.randomUUID();val input=f.input();val media=json(client.prepare(f,key,input))
        val body=f.test.upload(media).toString();val completeKey=UUID.randomUUID();val first=client.complete(f,media,body,completeKey)
        val replay=client.complete(f,media,body,completeKey);success(replay,200,"completeMediaUpload");assertEquals(json(first),json(replay))
        problem(client.prepare(f,key,input),409,"MEDIA_CONFLICT");assertEquals(1,f.test.objects.requests.size);assertEquals(1,f.test.signer.authorizations.size)
    }
    @Test fun lostCompletionResponseReplaysExactProcessingReceiptAfterReadyOrRejected()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())}
        for(state in listOf("ready","rejected")) {
            val prepareKey=UUID.randomUUID();val input=f.input();val media=json(client.prepare(f,prepareKey,input))
            val body=f.test.upload(media).toString();val key=UUID.randomUUID()
            f.test.objects.beforeVerify={f.test.faults.loseCommit=true}
            problem(client.complete(f,media,body,key),503,"OUTCOME_UNKNOWN");f.test.objects.beforeVerify=null
            val original=parse(f.test.value("SELECT response_json::text FROM platform.idempotency WHERE key='$key'"))
            f.test.finishProcessing(media,state)
            val replay=client.complete(f,media,body,key);success(replay,200,"completeMediaUpload")
            assertEquals(original,json(replay));assertEquals("processing",json(replay).text("status"));assertEquals("\"2\"",replay.headers[HttpHeaders.ETag])
            for(privateField in listOf("errorCode","uploadUrl","uploadFields","derivative","completion_"))assertFalse(replay.bodyAsText().contains(privateField))
            val current=client.get("/v1/media/${media.text("id")}"){auth(f)};success(current,200,"getMediaStatus")
            assertEquals(state,json(current).text("status"));assertEquals("\"3\"",current.headers[HttpHeaders.ETag])
            problem(client.prepare(f,prepareKey,input),409,"MEDIA_CONFLICT")
            problem(client.complete(f,media,body),409,"MEDIA_CONFLICT")
            problem(client.complete(f,media,completion("changed-version"),key),409,"IDEMPOTENCY_MISMATCH")
            val other=f.test.principal()
            problem(client.post("/v1/media/${media.text("id")}/complete"){
                auth(f,other);header("Idempotency-Key",key);contentType(ContentType.Application.Json);setBody(body)
            },404,"MEDIA_UNAVAILABLE")
        }
        assertEquals(2,f.test.count("platform.media_assets"));assertEquals(4,f.test.count("platform.idempotency"));assertEquals(2,f.test.count("platform.outbox"))
        assertEquals(2,f.test.objects.requests.size);assertEquals(2,f.test.signer.authorizations.size)
    }
    @Test fun historicalCompletionReceiptOutlivesUploadDeadlineButNotCommandExpiry()=testApplication {
        val f=Fixture(reservationSeconds=3);application{feedMeLocalService(local,media=f.configuration())}
        val media=json(client.prepare(f));val body=f.test.upload(media).toString();val key=UUID.randomUUID()
        val accepted=client.complete(f,media,body,key);success(accepted,200,"completeMediaUpload")
        f.test.finishProcessing(media,"rejected")
        f.test.sql("SELECT pg_sleep(GREATEST(0,EXTRACT(EPOCH FROM reservation_expires_at-clock_timestamp()))+0.02) FROM platform.media_assets WHERE id='${id(media)}'")
        val expires=f.test.value("SELECT expires_at::text FROM platform.idempotency WHERE key='$key'")
        val replay=client.complete(f,media,body,key);success(replay,200,"completeMediaUpload")
        assertEquals(json(accepted),json(replay));assertEquals(accepted.headers[HttpHeaders.ETag],replay.headers[HttpHeaders.ETag])
        assertEquals(expires,f.test.value("SELECT expires_at::text FROM platform.idempotency WHERE key='$key'"))
        f.test.sql("UPDATE platform.idempotency SET expires_at=clock_timestamp()-interval '1 second' WHERE key='$key'")
        problem(client.complete(f,media,body,key),410,"IDEMPOTENCY_EXPIRED")
        problem(client.complete(f,media,body,key),410,"IDEMPOTENCY_EXPIRED")
        assertEquals(1,f.test.count("platform.outbox"));assertEquals(1,f.test.objects.requests.size);assertEquals(1,f.test.signer.authorizations.size)
    }
    @Test fun expiredReservationReturns410WithoutRenewalOrDuplicateAsset()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())};val key=UUID.randomUUID();val input=f.input();val media=json(client.prepare(f,key,input));f.test.expire(id(media))
        problem(client.prepare(f,key,input),410,"UPLOAD_EXPIRED");problem(client.complete(f,media,completion()),410,"UPLOAD_EXPIRED")
        success(client.get("/v1/media/${media.text("id")}"){auth(f)},200,"getMediaStatus");assertEquals(1,f.test.signer.authorizations.size)
    }
    @Test fun verifierMustMatchSuppliedDeviceEnvironmentAndNeverAcceptGuestFallback()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())}
        problem(client.post("/v1/media"){header(HttpHeaders.Authorization,"Bearer synthetic-guest");header("Idempotency-Key",UUID.randomUUID());contentType(ContentType.Application.Json);setBody(f.input())},401,"UNAUTHENTICATED")
        f.verify={PortResult.Value(VerifiedMediaAccount("foreign",f.test.account.accountId,f.test.account.deviceSessionId))};problem(client.prepare(f),401,"UNAUTHENTICATED")
        f.verify={PortResult.Value(f.test.secondDevice())};problem(client.prepare(f),401,"UNAUTHENTICATED")
        f.verify={PortResult.Failure(FailureReason.UNAUTHENTICATED)};problem(client.prepare(f),401,"UNAUTHENTICATED")
        assertEquals(0,f.test.count("platform.media_assets"))
    }
    @Test fun realCurrentDeviceRevocationTermsAndDraftTerminalStateFenceReissuance()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())};val key=UUID.randomUUID();val input=f.input();client.prepare(f,key,input)
        f.test.authority.termsAccepted=false;problem(client.prepare(f,key,input),403,"FORBIDDEN");f.test.authority.termsAccepted=true
        f.test.sql("UPDATE media_test.drafts SET eligible=false");problem(client.prepare(f,key,input),409,"DRAFT_UNAVAILABLE")
        f.test.sql("UPDATE cooking_test.sessions SET active=false");problem(client.prepare(f,key,input),401,"UNAUTHENTICATED")
        assertEquals(1,f.test.count("platform.media_assets"));assertEquals(1,f.test.signer.authorizations.size)
    }
    @Test fun foreignAndRandomMediaAreIndistinguishableBeforeExternalObjectRead()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())};val media=json(client.prepare(f));val other=f.test.principal()
        for(value in listOf(media.text("id"),UUID.randomUUID().toString())){
            problem(client.get("/v1/media/$value"){auth(f,other)},404,"MEDIA_UNAVAILABLE")
            problem(client.post("/v1/media/$value/complete"){auth(f,other);header("Idempotency-Key",UUID.randomUUID());contentType(ContentType.Application.Json);setBody(completion())},404,"MEDIA_UNAVAILABLE")
        };assertTrue(f.test.objects.requests.isEmpty())
    }
    @Test fun exactObjectVersionMismatchIsNotReadinessOrPublication()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())};val media=json(client.prepare(f));f.test.upload(media)
        problem(client.complete(f,media,completion("foreign-version")),409,"OBJECT_MISMATCH")
        assertEquals("awaitingUpload",json(client.get("/v1/media/${media.text("id")}"){auth(f)}).text("status"));assertEquals(0,f.test.count("platform.outbox"))
        success(client.complete(f,media,completion()),200,"completeMediaUpload");assertEquals("0",f.test.value("SELECT count(*) FROM platform.media_assets WHERE state='ready'"))
    }
    @Test fun deleteRequiresOriginalVersionAndRetainsExactLateUploadCleanup()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())};val media=json(client.prepare(f));val key=UUID.randomUUID()
        problem(client.delete("/v1/media/${media.text("id")}"){auth(f);header("Idempotency-Key",key)},428,"PRECONDITION_REQUIRED")
        problem(client.remove(f,media,key,"\"2\""),412,"VERSION_CONFLICT")
        success(client.remove(f,media,key),204,"deleteDraftMedia");success(client.remove(f,media,key),204,"deleteDraftMedia")
        f.test.upload(media,"late-object-version");problem(client.complete(f,media,completion("late-object-version")),409,"MEDIA_CONFLICT")
        assertEquals("1",f.test.value("SELECT CASE WHEN final_sweep_after>available_at AND completed_at IS NULL THEN 1 ELSE 0 END FROM platform.media_cleanup_jobs"))
    }
    @Test fun attachedMediaCannotBeRemovedThroughUnattachedCleanupRoute()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())};val media=json(client.prepare(f))
        f.test.sql("INSERT INTO media_test.attached VALUES('${f.test.account.accountId}','${media.text("id")}')")
        problem(client.remove(f,media),409,"MEDIA_ATTACHED");assertEquals(0,f.test.count("platform.media_cleanup_jobs"))
    }
    @Test fun signingFailureAfterCommitPreservesRecoverableCoreWithoutBearerReceipt()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())};val key=UUID.randomUUID();val input=f.input()
        f.test.signer.beforeSign={throw IllegalStateException("PRIVATE-provider-secret")};problem(client.prepare(f,key,input),503,"CAPABILITY_UNAVAILABLE")
        assertEquals(1,f.test.count("platform.idempotency"));assertEquals(1,f.test.count("platform.media_assets"));assertEquals(0,f.test.count("platform.media_cleanup_jobs"))
        f.test.signer.beforeSign=null;success(client.prepare(f,key,input),201,"prepareMediaUpload");assertEquals(1,f.test.count("platform.media_assets"))
    }
    @Test fun actualLostCommitReceiptReturnsUnknownThenSameKeyReconcilesWithoutNewIdentity()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())};val key=UUID.randomUUID();val input=f.input();f.test.faults.loseCommit=true
        problem(client.prepare(f,key,input),503,"OUTCOME_UNKNOWN");assertTrue(f.test.signer.authorizations.isEmpty())
        success(client.prepare(f,key,input),201,"prepareMediaUpload");assertEquals(1,f.test.count("platform.media_assets"))
    }
    @Test fun callerCancellationAfterCoreCommitNeverTurnsIntoDefiniteNoncommit()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())};val key=UUID.randomUUID();val input=f.input()
        val committed=CountDownLatch(1);val release=CountDownLatch(1);f.test.faults.afterCommit={committed.countDown();check(release.await(10,TimeUnit.SECONDS))}
        coroutineScope{val pending=async{client.prepare(f,key,input)}
            try{assertTrue(withContext(Dispatchers.IO){committed.await(10,TimeUnit.SECONDS)});pending.cancel();release.countDown();assertFailsWith<CancellationException>{pending.await()}}
            finally{release.countDown();pending.cancel()}}
        success(client.prepare(f,key,input),201,"prepareMediaUpload");assertEquals(1,f.test.count("platform.idempotency"));assertEquals(1,f.test.count("platform.media_assets"))
    }
    @Test fun malformedDuplicateUnicodeAndOversizedBodiesNeverReachStorage()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())};val valid=f.input()
        val malformed=listOf(valid.dropLast(1)+",\"kind\":\"photo\"}",valid.replace("photo","\\uD800")," ".repeat(65537))
        for(body in malformed)problem(client.prepare(f,body=body),400,"INVALID_REQUEST")
        problem(client.prepare(f,body=JsonObject(parse(valid)+("unknown" to JsonPrimitive(true))).toString()),422,"INPUT_INVALID")
        problem(client.prepare(f,body=JsonObject(parse(valid)+("bytes" to JsonPrimitive(10_000_001))).toString()),422,"MEDIA_TOO_LARGE")
        assertEquals(0,f.test.count("platform.idempotency"));assertEquals(0,f.test.count("platform.media_assets"))
    }
    @Test fun undeclaredQueryConditionalAndAmbiguousControlsCannotMutate()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())}
        problem(client.post("/v1/media?ownerId=${f.test.account.accountId}"){auth(f);header("Idempotency-Key",UUID.randomUUID());contentType(ContentType.Application.Json);setBody(f.input())},400,"INVALID_REQUEST")
        problem(client.prepare(f,extras={header(HttpHeaders.IfMatch,"\"1\"")}),400,"INVALID_REQUEST")
        problem(client.prepare(f,extras={header(HttpHeaders.Authorization,"Bearer other")}),400,"INVALID_REQUEST")
        problem(client.prepare(f,extras={header(HttpHeaders.ContentEncoding,"gzip")}),400,"INVALID_REQUEST")
        assertEquals(0,f.test.count("platform.media_assets"))
    }
    @Test fun responseBudgetIsAdmittedBeforeMutationAndSafeAdapterFailuresAreRedacted()=testApplication {
        val f=Fixture(1000);application{feedMeLocalService(local,media=f.configuration())}
        problem(client.prepare(f),422,"RESPONSE_TOO_LARGE");assertEquals(0,f.test.count("platform.media_assets"))
        f.verify={throw IllegalStateException("PRIVATE-token-provider")};problem(client.prepare(f),503,"AUTHENTICATION_UNAVAILABLE")
    }
    @Test fun actualCioRejectsDuplicateFramingAndExecutesCanonicalReservation()=runBlocking<Unit> {
        val f=Fixture();val server=embeddedServer(CIO,host="127.0.0.1",port=0){feedMeLocalService(local,media=f.configuration())}.start(wait=false)
        try{val port=server.engine.resolvedConnectors().single().port;val body=f.input()
            val raw="POST /v1/media HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer ${f.token(f.test.account)}\r\nX-Device-Session: ${f.test.account.deviceSessionId}\r\nIdempotency-Key: ${UUID.randomUUID()}\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
            val response=withContext(Dispatchers.IO){Socket("127.0.0.1",port).use{s->s.soTimeout=5000;s.getOutputStream().write(raw.toByteArray());s.getOutputStream().flush();s.getInputStream().readBytes().toString(Charsets.UTF_8)}}
            assertTrue(Regex("^HTTP/1\\.[01] 400 [^\\r\\n]+\\r\\n").containsMatchIn(response.take(100)),response.take(200));assertEquals(0,f.test.count("platform.media_assets"))
            HttpClient(io.ktor.client.engine.cio.CIO).use{http->val result=http.post("http://127.0.0.1:$port/v1/media"){auth(f);header("Idempotency-Key",UUID.randomUUID());contentType(ContentType.Application.Json);setBody(body)};success(result,201,"prepareMediaUpload")}
            assertEquals(1,f.test.count("platform.media_assets"))
        }finally{server.stop(gracePeriodMillis=100,timeoutMillis=1000)}
    }
    @Test fun defaultModuleKeepsMediaAndPostAccessClosedWithoutVerifierOrObjectCalls()=testApplication {
        val f=Fixture();application{feedMeLocalService(local)}
        problem(client.prepare(f),503,"OPERATION_NOT_IMPLEMENTED")
        problem(client.get("/v1/media/${UUID.randomUUID()}"),503,"OPERATION_NOT_IMPLEMENTED")
        problem(client.post("/v1/media/${UUID.randomUUID()}/complete"),503,"OPERATION_NOT_IMPLEMENTED")
        problem(client.delete("/v1/media/${UUID.randomUUID()}"),503,"OPERATION_NOT_IMPLEMENTED")
        assertEquals(0,f.verifications);assertEquals(0,f.test.count("platform.media_assets"))
    }
    @Test fun configuredOwnedMediaDoesNotEnableDeliveryDraftPublicationOrClips()=testApplication {
        val f=Fixture();application{feedMeLocalService(local,media=f.configuration())}
        for(path in listOf("/v1/media/${UUID.randomUUID()}/access","/v1/posts","/v1/post-drafts"))problem(client.post(path),503,"OPERATION_NOT_IMPLEMENTED")
        problem(client.prepare(f,body=JsonObject(parse(f.input())+("kind" to JsonPrimitive("clip"))).toString()),503,"NOT_CONFIGURED")
        assertEquals(0,f.test.count("platform.media_assets"))
    }
    private class Fixture(maxBytes:Int=65536,reservationSeconds:Int=3600){
        val test=MediaTestFixture(cluster.database());val store=if(maxBytes==65536&&reservationSeconds==3600)test.store else test.newStore(test.policy(
            maxResponseBytes=maxBytes,reservationLifetimeSeconds=reservationSeconds,capabilityLifetimeSeconds=minOf(60,reservationSeconds)))
        val accounts=linkedMapOf<String,VerifiedMediaAccount>();var verifications=0
        var verify:(suspend(MediaHttpBearer)->PortResult<VerifiedMediaAccount>)?=null
        fun token(actor:VerifiedMediaAccount)="synthetic-${actor.deviceSessionId}".also{accounts[it]=actor}
        fun configuration()=MediaHttpConfiguration("test",store,MediaHttpVerifier{bearer->verifications++;verify?.invoke(bearer)?:bearer.token.use{token->accounts[token]?.let{PortResult.Value(it)}?:PortResult.Failure(FailureReason.UNAUTHENTICATED)}},Dispatchers.IO)
        fun input()=test.prepareBody().toString()
    }
    companion object {
        @JvmField @ClassRule val timeout:Timeout=Timeout.seconds(300)
        private lateinit var cluster:PostgresTestCluster
        @JvmStatic @BeforeClass fun start(){cluster=PostgresTestCluster.start()}
        @JvmStatic @AfterClass fun stop(){cluster.close()}
        private val local=LocalServerConfig.fromEnvironment(emptyMap());private val validator=ContractBodyValidator.bundled()
        private fun parse(text:String)=Json.parseToJsonElement(text).jsonObject
        private fun JsonObject.text(name:String)=getValue(name).jsonPrimitive.content
        private fun id(body:JsonObject)=UUID.fromString(body.text("id"))
        private fun core(body:JsonObject)=JsonObject(body.filterKeys{!it.startsWith("upload")})
        private fun completion(version:String="object-version-1")=buildJsonObject{put("objectVersionId",version);put("sha256",MediaTestFixture.CHECKSUM)}.toString()
        private suspend fun json(response:HttpResponse)=parse(response.bodyAsText())
        private fun HttpRequestBuilder.auth(f:Fixture,actor:VerifiedMediaAccount=f.test.account){header(HttpHeaders.Authorization,"Bearer ${f.token(actor)}");header("X-Device-Session",actor.deviceSessionId)}
        private suspend fun HttpClient.prepare(f:Fixture,key:UUID=UUID.randomUUID(),body:String=f.input(),extras:HttpRequestBuilder.()->Unit={})=post("/v1/media"){
            auth(f);header("Idempotency-Key",key);contentType(ContentType.Application.Json);setBody(body);extras()}
        private suspend fun HttpClient.complete(f:Fixture,media:JsonObject,body:String,key:UUID=UUID.randomUUID())=post("/v1/media/${media.text("id")}/complete"){
            auth(f);header("Idempotency-Key",key);contentType(ContentType.Application.Json);setBody(body)}
        private suspend fun HttpClient.remove(f:Fixture,media:JsonObject,key:UUID=UUID.randomUUID(),etag:String="\"1\"")=delete("/v1/media/${media.text("id")}"){
            auth(f);header("Idempotency-Key",key);header(HttpHeaders.IfMatch,etag)}
        private suspend fun success(response:HttpResponse,status:Int,operation:String){
            val text=response.bodyAsText();assertEquals(status,response.status.value,text);headers(response)
            if(status==204){assertEquals("",text);assertNull(response.headers[HttpHeaders.ETag]);return}
            assertEquals(BodyValidationResult.Valid,validator.validateResponse(operation,status,text.encodeToByteArray(),"application/json"));assertEquals("\"${json(response).text("version")}\"",response.headers[HttpHeaders.ETag])
        }
        private suspend fun problem(response:HttpResponse,status:Int,code:String){
            val text=response.bodyAsText();assertEquals(status,response.status.value,text);headers(response);val body=parse(text)
            assertEquals(code,body.text("code"));assertEquals(status,body.getValue("status").jsonPrimitive.int);assertEquals(response.headers["X-Trace-Id"],body.text("traceId"))
            assertEquals(BodyValidationResult.Valid,validator.validateSchema("Problem",text.encodeToByteArray()));assertEquals("application/problem+json",response.headers[HttpHeaders.ContentType]?.substringBefore(';'));assertNull(response.headers[HttpHeaders.ETag])
            for(secret in listOf("synthetic-secret","PRIVATE-","quarantine/","object-version"))assertFalse(text.contains(secret))
        }
        private fun headers(response:HttpResponse){assertEquals("no-store",response.headers[HttpHeaders.CacheControl]);assertEquals("nosniff",response.headers["X-Content-Type-Options"]);assertNotNull(UUID.fromString(response.headers["X-Trace-Id"]));assertNull(response.headers[HttpHeaders.SetCookie])}
    }
}
