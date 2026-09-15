package com.feedme.server.media.processing.codec

import com.feedme.server.media.processing.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*

/** Runtime choices are mandatory. This bounds one retained owner's processes, not a host-wide
 * cgroup/RSS/network sandbox. Configure one owner and separately verify deployment isolation. */
data class PhotoProcessPolicy(val heapMiB:Int,val maxConcurrentProcesses:Int,val timeoutMillis:Long,val killWaitMillis:Long) {
    init {
        require(heapMiB in 32..2048 && maxConcurrentProcesses in 1..16)
        require(timeoutMillis in 1..60_000 && killWaitMillis in 1..5000)
    }
}

/** No shells, source filenames, private temporary files, inherited secrets or logs. Private bytes
 * travel over anonymous pipes. The owned child is forcibly stopped on cancellation/deadline.
 * A permit is NOT released while a child remains alive, even if termination takes longer. */
class PhotoDecodeProcess internal constructor(
    private val javaExecutable:Path,
    private val classpath:String,
    private val policy:PhotoProcessingPolicy,
    private val processPolicy:PhotoProcessPolicy,
    private val workerMainClass:String,
) : PhotoCodec {
    constructor(javaExecutable:Path,classpath:String,policy:PhotoProcessingPolicy,processPolicy:PhotoProcessPolicy):
        this(javaExecutable,classpath,policy,processPolicy,PhotoDecodeWorker::class.java.name)
    init {
        require(javaExecutable.isAbsolute && Files.isRegularFile(javaExecutable) && Files.isExecutable(javaExecutable))
        require(classpath.isNotBlank() && classpath.length <= 65536 && '\u0000' !in classpath)
        require(classpath.split(java.io.File.pathSeparator).all { it.isNotBlank() && Path.of(it).isAbsolute && Files.exists(Path.of(it)) })
        require(policy.maxWorkingBytes <= processPolicy.heapMiB.toLong()*1024*1024)
    }
    private val slots=Semaphore(processPolicy.maxConcurrentProcesses)
    override val revision:String get()=policy.revision

    override fun decode(source:ByteArray,contentType:String):PhotoDecodeResult {
        if(contentType !in setOf("image/jpeg","image/png"))return PhotoDecodeResult.Rejected(PhotoRejection.UNSUPPORTED_FORMAT)
        if(source.isEmpty())return PhotoDecodeResult.Rejected(PhotoRejection.MALFORMED_IMAGE)
        if(source.size>policy.maxSourceBytes)return PhotoDecodeResult.Rejected(PhotoRejection.IMAGE_LIMIT_EXCEEDED)
        if(!slots.tryAcquire())return PhotoDecodeResult.Unavailable(PhotoCodecUnavailable.AT_CAPACITY)
        var child:Process?=null; var io:FutureTask<PhotoDecodeResult>?=null
        val started=System.nanoTime()
        try {
            val captured=source.copyOf()
            val builder=ProcessBuilder(javaExecutable.toString(),"-Xmx${processPolicy.heapMiB}m","-Djava.awt.headless=true",
                "-cp",classpath,workerMainClass).redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment().clear()
            child=builder.start()
            val process=child
            io=FutureTask {
                DataOutputStream(process.outputStream).use { out ->
                    out.writeInt(PROTOCOL); writePolicy(out,policy);out.writeUTF(contentType)
                    out.writeInt(captured.size);out.write(captured);out.flush()
                }
                DataInputStream(process.inputStream).use { input ->
                    val result=readResult(input,policy)
                    if(input.read()!=-1)throw IOException("Unexpected worker envelope")
                    result
                }
            }
            Thread(io,"feedme-photo-pipe").apply { isDaemon=true;start() }
            val budget=TimeUnit.MILLISECONDS.toNanos(processPolicy.timeoutMillis)
            val remaining=budget-(System.nanoTime()-started)
            if(remaining<=0)throw TimeoutException()
            val result=io.get(remaining,TimeUnit.NANOSECONDS)
            val exitBudget=budget-(System.nanoTime()-started)
            if(exitBudget<=0 || !process.waitFor(exitBudget,TimeUnit.NANOSECONDS))throw TimeoutException()
            return if(process.exitValue()==0)result else PhotoDecodeResult.Unavailable(PhotoCodecUnavailable.PROCESS_FAILED)
        } catch(_:TimeoutException) {
            return PhotoDecodeResult.Unavailable(PhotoCodecUnavailable.PROCESS_TIMEOUT)
        } catch(e:InterruptedException) {
            // Preserve cancellation; finally still owns termination before handing back control.
            throw e
        } catch(_:IOException) {
            return PhotoDecodeResult.Unavailable(PhotoCodecUnavailable.PROCESS_FAILED)
        } catch(_:ExecutionException) {
            return PhotoDecodeResult.Unavailable(PhotoCodecUnavailable.PROCESS_FAILED)
        } finally {
            io?.cancel(true)
            val process=child
            if(process==null)slots.release() else retirePhotoChild(process,processPolicy.killWaitMillis,slots::release)
        }
    }

    companion object {
        internal const val PROTOCOL=0x464d5001
        internal fun writePolicy(out:DataOutputStream,p:PhotoProcessingPolicy) {
            out.writeUTF(p.revision);out.writeInt(p.maxSourceBytes);out.writeInt(p.maxWidth);out.writeInt(p.maxHeight)
            out.writeLong(p.maxPixels);out.writeLong(p.maxWorkingBytes);out.writeInt(p.maxContainerParts)
            out.writeInt(p.maxMetadataBytes);out.writeInt(p.maxExifEntries);out.writeInt(p.thumbnailEdge);out.writeInt(p.displayEdge)
            out.writeInt(p.maxDerivativeBytes);out.writeInt(p.maxCombinedDerivativeBytes);out.writeUTF(p.outputContentType)
        }
        internal fun readPolicy(input:DataInputStream)=PhotoProcessingPolicy(input.readUTF(),input.readInt(),input.readInt(),input.readInt(),
            input.readLong(),input.readLong(),input.readInt(),input.readInt(),input.readInt(),input.readInt(),input.readInt(),input.readInt(),input.readInt(),input.readUTF())
        internal fun writeResult(out:DataOutputStream,result:PhotoDecodeResult) {
            out.writeInt(PROTOCOL)
            when(result) {
                is PhotoDecodeResult.Decoded -> {
                    out.writeByte(1);out.writeInt(result.variants.size)
                    for(v in result.variants) { val b=v.copyBytes();out.writeUTF(v.variant.name);out.writeUTF(v.contentType);out.writeInt(v.width);out.writeInt(v.height);out.writeInt(b.size);out.write(b) }
                }
                is PhotoDecodeResult.Rejected -> { out.writeByte(2);out.writeUTF(result.reason.name) }
                is PhotoDecodeResult.Unavailable -> { out.writeByte(3);out.writeUTF(result.reason.name) }
            }
        }
        internal fun readResult(input:DataInputStream,p:PhotoProcessingPolicy):PhotoDecodeResult {
            if(input.readInt()!=PROTOCOL)throw IOException("Worker protocol mismatch")
            return when(input.readUnsignedByte()) {
                1 -> {
                    if(input.readInt()!=2)throw IOException("Worker variant count")
                    var total=0L
                    val variants=PhotoVariant.entries.map { expected ->
                        if(input.readUTF()!=expected.name || input.readUTF()!=p.outputContentType)throw IOException("Worker variant profile")
                        val w=input.readInt();val h=input.readInt();val n=input.readInt()
                        val edge=if(expected==PhotoVariant.THUMBNAIL)p.thumbnailEdge else p.displayEdge
                        if(w !in 1..edge || h !in 1..edge || n !in 1..p.maxDerivativeBytes)throw IOException("Worker limits")
                        total+=n;if(total>p.maxCombinedDerivativeBytes)throw IOException("Worker combined limits")
                        val bytes=ByteArray(n);input.readFully(bytes)
                        // Parent accepts only a bounded, exact-profile envelope. Actual container
                        // validation/redecode runs twice within the deadline-bounded child.
                        if(n<33 || !bytes.copyOfRange(0,8).contentEquals(PhotoContainerValidator.PNG_SIGNATURE) ||
                            PhotoContainerValidator.uint32(bytes,16)!=w.toLong() || PhotoContainerValidator.uint32(bytes,20)!=h.toLong())throw IOException("Worker image envelope")
                        EncodedPhotoVariant(expected,p.outputContentType,w,h,bytes)
                    }
                    PhotoDecodeResult.Decoded(variants)
                }
                2 -> { val value=input.readUTF();PhotoDecodeResult.Rejected(PhotoRejection.entries.singleOrNull{it.name==value} ?: throw IOException("Worker rejection code")) }
                3 -> { val value=input.readUTF();PhotoDecodeResult.Unavailable(PhotoCodecUnavailable.entries.singleOrNull{it.name==value} ?: throw IOException("Worker failure code")) }
                else -> throw IOException("Worker result tag")
            }
        }
    }
}

/** Separately testable lifecycle branch. Even destroyForcibly can close a blocked pipe. Transfer
 * termination, actual-exit waiting, pipe cleanup and the permit BEFORE the caller's bounded wait. */
internal fun retirePhotoChild(process:Process,killWaitMillis:Long,release:()->Unit) {
    val wasInterrupted=Thread.interrupted()
    val completed=CountDownLatch(1)
    fun closePipes() {
        try { process.outputStream.close() } catch(_:IOException) { }
        try { process.inputStream.close() } catch(_:IOException) { }
        try { process.errorStream.close() } catch(_:IOException) { }
    }
    try {
        // JDK ProcessImpl.destroyForcibly itself may close/flush stdin synchronously. It therefore
        // belongs to the retained cleanup thread too, not the deadline-bound caller.
        Thread({
            try {
                try { if(process.isAlive)process.destroyForcibly() } catch(_:Exception) { /* keep ownership until actual exit */ }
                var exited=false
                while(!exited)try { process.waitFor();exited=true } catch(_:InterruptedException) { }
                closePipes();release()
            } finally { completed.countDown() }
        },"feedme-photo-reaper").apply { isDaemon=true;start() }
        try { completed.await(killWaitMillis,TimeUnit.MILLISECONDS) } catch(_:InterruptedException) { Thread.currentThread().interrupt() }
    } finally { if(wasInterrupted)Thread.currentThread().interrupt() }
}

/** Entry point of a private codec child, not an HTTP server or operator CLI. */
object PhotoDecodeWorker {
    @JvmStatic fun main(args:Array<String>) {
        if(args.isNotEmpty())return
        val input=DataInputStream(System.`in`)
        if(input.readInt()!=PhotoDecodeProcess.PROTOCOL)return
        val policy=PhotoDecodeProcess.readPolicy(input)
        val type=input.readUTF();val size=input.readInt()
        if(size !in 1..policy.maxSourceBytes)return
        val source=ByteArray(size);input.readFully(source)
        if(input.read()!=-1)return
        val result=BoundedPhotoDecoder(policy).decode(source,type)
        DataOutputStream(System.out).use { PhotoDecodeProcess.writeResult(it,result);it.flush() }
    }
}
