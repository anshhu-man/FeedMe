package com.feedme.server.export

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.media.supabase.SupabaseStorageHttpConfiguration
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Ciphertext-only, exact-object transport. Never creates buckets, public URLs, signed
 * uploads, replacement writes or a physical-erasure/late-write settlement receipt. */
class SupabaseExportObjects private constructor(private val configuration:SupabaseStorageHttpConfiguration,
    private val client:OkHttpClient):AutoCloseable {
    val bucket:String get()=configuration.bucket
    private val closed=AtomicBoolean()
    private val admission=Semaphore(2)

    /** A real bucket metadata read, outside the database transaction and before consuming
     * the one-shot dispatch marker. Deployment must keep this dedicated bucket private. */
    internal fun requirePrivateBucket()=guarded {
        client.newCall(request("/bucket/$bucket").get().build()).execute().use { r ->
            headers(r);demand(r.code==200 && jsonMedia(single(r,"Content-Type")))
            val bytes=body(r,MAX_JSON)
            try { val value=json(bytes)
                demand(value["id"]==JsonPrimitive(bucket) && value["public"]==JsonPrimitive(false))
            } finally { bytes.fill(0) }
        }
    }

    /** Caller must have received known-committed markDispatched=true for this original.
     * Every exception, including an ambiguous response, consumes that attempt forever. */
    internal fun create(original:ExportArtifact,bytes:ByteArray)=guarded {
        validate(original);demand(original.writeAttempted && !original.ready)
        verify(original,bytes)
        val owned=bytes.copyOf()
        try { client.newCall(request("/object/$bucket/${original.objectKey}")
            .header("x-upsert","false").post(owned.toRequestBody("application/octet-stream".toMediaType())).build())
            .execute().use { r ->
                headers(r);demand(r.code==200 && jsonMedia(single(r,"Content-Type")))
                val reply=body(r,MAX_JSON)
                try { val value=json(reply)
                    demand(value.keys==setOf("Key") || value.keys==setOf("Id","Key"))
                    demand(value["Key"]==JsonPrimitive("$bucket/${original.objectKey}"))
                    value["Id"]?.let { demand(it is JsonPrimitive && it.isString && it.content.length in 1..128 && it.content.none(Char::isISOControl)) }
                } finally { reply.fill(0) }
            }
        } finally { owned.fill(0) }
    }

    /** Complete verified ciphertext; canonical NoSuchKey is only a current observation.
     * Absence never permits another POST for an attempted original. */
    internal fun readVerified(original:ExportArtifact):ByteArray?=guarded {
        validate(original)
        client.newCall(request("/object/authenticated/$bucket/${original.objectKey}")
            .header("Accept","application/octet-stream").get().build()).execute().use { r ->
            headers(r)
            if(r.code!=200) { if(missing(r))return@use null;fail() }
            demand(single(r,"Content-Type")=="application/octet-stream" && single(r,"Content-Range")==null)
            val bytes=body(r,original.cipherBytes)
            try { verify(original,bytes);current();bytes } catch(t:Throwable){bytes.fill(0);throw t}
        }
    }

    /** Best-effort expired ciphertext removal only, after DB key destruction. Success or
     * absence does not prove an earlier uncertain POST can never arrive later. */
    internal fun removeExpired(original:ExportArtifact):Boolean=guarded {
        validate(original)
        val key=original.wrappedKey
        try { demand(key==null) } finally { key?.fill(0) }
        client.newCall(request("/object/$bucket/${original.objectKey}").delete().build()).execute().use { r ->
            headers(r)
            if(r.code!=200) { if(missing(r))return@use true;fail() }
            demand(jsonMedia(single(r,"Content-Type")))
            val bytes=body(r,MAX_JSON)
            try { json(bytes)==buildJsonObject{put("message","Successfully deleted")} } finally { bytes.fill(0) }
        }
    }

    private fun validate(a:ExportArtifact) {
        demand(a.environment==configuration.environment && a.bucket==bucket &&
            a.objectKey=="exports/${a.environment}/${a.jobId}/${a.artifactId}" &&
            a.cipherBytes==a.plaintextBytes+16 && a.plaintextBytes in 1..AccountExportEncryption.MAX_PLAINTEXT_BYTES &&
            a.cipherBytes.toLong()<=configuration.maxObjectBytes && HASH.matches(a.cipherSha256))
    }
    private fun verify(a:ExportArtifact,b:ByteArray) {
        if(b.size!=a.cipherBytes || exportSha(b)!=a.cipherSha256) throw ExportWorkFailure(ExportWorkIssue.OBJECT_MISMATCH)
    }
    private fun request(path:String)=Request.Builder().url(configuration.projectOrigin+"/storage/v1"+path)
        .header("Accept","application/json").header("Accept-Encoding","identity").header("Cache-Control","no-cache, no-store").apply {
            configuration.apiKey.use{header("apikey",it)}
            configuration.bearer?.use{header("Authorization","Bearer $it")}
        }
    private fun missing(r:Response):Boolean {
        if(r.code !in setOf(400,404) || !jsonMedia(single(r,"Content-Type")))return false
        val bytes=body(r,MAX_JSON)
        return try { json(bytes)==buildJsonObject{put("statusCode","404");put("code","NoSuchKey");put("error","NoSuchKey");put("message","Object not found")} }
        finally { bytes.fill(0) }
    }
    private fun headers(r:Response) {
        demand(r.headers.size<=64 && r.headers.sumOf{it.first.length+it.second.length}<=16_384)
        demand(single(r,"Content-Encoding")?.lowercase() in setOf(null,"identity") && single(r,"Location")==null)
        current()
    }
    private fun single(r:Response,name:String):String? {
        val values=r.headers.values(name);demand(values.size<=1)
        return values.singleOrNull()?.also{demand(it.length in 1..1024 && it.none(Char::isISOControl))}
    }
    private fun body(r:Response,max:Int):ByteArray {
        val declared=single(r,"Content-Length")?.let{demand(LENGTH.matches(it));it.toLong()}
        demand(declared==null || declared in 1..max.toLong())
        val buffer=ByteArray(max+1)
        try { var count=0
            r.body.byteStream().use { input -> while(true) {
                current();val read=input.read(buffer,count,buffer.size-count)
                if(read<0)break
                demand(read>0);count+=read;demand(count<=max)
            } }
            demand(count>0 && (declared==null || declared==count.toLong()))
            return buffer.copyOf(count)
        } finally { buffer.fill(0) }
    }
    private fun<T>guarded(action:()->T):T {
        current();if(!admission.tryAcquire())throw ExportWorkFailure(ExportWorkIssue.OUTCOME_UNKNOWN)
        try { return action() }
        catch(e:CancellationException){throw e}
        catch(e:InterruptedException){Thread.currentThread().interrupt();throw e}
        catch(e:ExportWorkFailure){throw e}
        catch(_:Exception){fail()}
        finally { admission.release() }
    }
    private fun current() {
        if(Thread.currentThread().isInterrupted)throw InterruptedException("Export object operation interrupted")
        if(closed.get())throw ExportWorkFailure(ExportWorkIssue.NOT_CONFIGURED)
    }
    override fun close(){if(closed.compareAndSet(false,true)){client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}}
    override fun toString()="SupabaseExportObjects(<redacted>)"
    companion object {
        fun create(storage:SupabaseStorageHttpConfiguration):SupabaseExportObjects {
            require(storage.maxObjectBytes in 1040L..(AccountExportEncryption.MAX_PLAINTEXT_BYTES+16L))
            val client=OkHttpClient.Builder().connectTimeout(storage.connectTimeoutMillis,TimeUnit.MILLISECONDS)
                .readTimeout(storage.readTimeoutMillis,TimeUnit.MILLISECONDS).writeTimeout(storage.readTimeoutMillis,TimeUnit.MILLISECONDS)
                .callTimeout(storage.callTimeoutMillis,TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).fastFallback(false).proxy(Proxy.NO_PROXY)
                .socketFactory(ExportDirectSockets(storage.connectTimeoutMillis.toInt())).cookieJar(CookieJar.NO_COOKIES)
                .cache(null).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).eventListener(EventListener.NONE)
                .connectionPool(ConnectionPool()).addInterceptor{chain->chain.proceed(chain.request().newBuilder().tag(ExportAttempt::class.java,ExportAttempt()).build())}
                .addNetworkInterceptor { chain ->
                    val attempt=chain.request().tag(ExportAttempt::class.java)?:throw IOException("Export attempt unavailable")
                    if(!attempt.started.compareAndSet(false,true))throw IOException("Export follow-up refused")
                    chain.proceed(chain.request())
                }.build()
            return SupabaseExportObjects(storage,client)
        }
        private const val MAX_JSON=65_536
        private val HASH=Regex("[0-9a-f]{64}")
        private val LENGTH=Regex("0|[1-9][0-9]{0,18}")
        private val JSON_MEDIA=Regex("application/json(?: *; *charset *= *(?:utf-8|\"utf-8\"))?",RegexOption.IGNORE_CASE)
        private fun jsonMedia(value:String?)=value!=null && JSON_MEDIA.matches(value)
        private fun json(bytes:ByteArray)=Json.parseToJsonElement(WireDocument.decode(bytes,WireLimits(MAX_JSON,8,64)).encodeUtf8().decodeToString()) as? JsonObject?:fail()
        private fun demand(value:Boolean){if(!value)fail()}
        private fun fail():Nothing=throw ExportWorkFailure(ExportWorkIssue.OUTCOME_UNKNOWN)
    }
}
private class ExportAttempt{val started=AtomicBoolean()}
private class ExportDirectSockets(private val timeout:Int):SocketFactory() {
    override fun createSocket()=Socket(Proxy.NO_PROXY)
    override fun createSocket(host:String,port:Int)=connect(InetSocketAddress(host,port))
    override fun createSocket(host:String,port:Int,localHost:InetAddress?,localPort:Int)=connect(InetSocketAddress(host,port),InetSocketAddress(localHost,localPort))
    override fun createSocket(host:InetAddress,port:Int)=connect(InetSocketAddress(host,port))
    override fun createSocket(host:InetAddress,port:Int,localHost:InetAddress?,localPort:Int)=connect(InetSocketAddress(host,port),InetSocketAddress(localHost,localPort))
    private fun connect(remote:InetSocketAddress,local:InetSocketAddress?=null):Socket {
        val socket=createSocket()
        try { if(local!=null)socket.bind(local);socket.connect(remote,timeout);return socket }
        catch(t:Throwable){socket.close();throw t}
    }
}
