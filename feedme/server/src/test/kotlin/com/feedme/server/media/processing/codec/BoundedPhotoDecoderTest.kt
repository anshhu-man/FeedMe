package com.feedme.server.media.processing.codec

import com.feedme.server.media.processing.*
import java.awt.image.BufferedImage
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.test.*

class BoundedPhotoDecoderTest {
    private fun policy()=PhotoProcessingPolicy("synthetic-codec-test-v1",1_000_000,2048,2048,4_194_304,200_000_000,
        1024,100_000,128,32,256,1_000_000,2_000_000,"image/png")
    private fun png(image:BufferedImage)=ByteArrayOutputStream().also { assertTrue(ImageIO.write(image,"png",it)) }.toByteArray()
    private fun raster(width:Int=3,height:Int=2,alpha:Boolean=false):BufferedImage {
        val image=BufferedImage(width,height,if(alpha)BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        for(y in 0 until height)for(x in 0 until width)image.setRGB(x,y,(if(alpha)(64+x*50).coerceAtMost(255) else 255) shl 24 or
            (((x+1)*61 and 255) shl 16) or (((y+1)*93 and 255) shl 8) or ((x*43+y*17) and 255))
        return image
    }
    private fun jpeg(image:BufferedImage,progressive:Boolean=false):ByteArray {
        val out=ByteArrayOutputStream();val writer=ImageIO.getImageWritersByFormatName("jpeg").next()
        try { MemoryCacheImageOutputStream(out).use { stream ->
            writer.output=stream;val param=writer.defaultWriteParam
            param.progressiveMode=if(progressive)ImageWriteParam.MODE_DEFAULT else ImageWriteParam.MODE_DISABLED
            writer.write(null,IIOImage(image,null,null),param);stream.flush()
        } } finally { writer.dispose() }
        return out.toByteArray()
    }
    private fun decoded(bytes:ByteArray,type:String="image/png",p:PhotoProcessingPolicy=policy()):PhotoDecodeResult.Decoded =
        assertIs<PhotoDecodeResult.Decoded>(BoundedPhotoDecoder(p).decode(bytes,type))
    private fun rejected(bytes:ByteArray,type:String="image/png",p:PhotoProcessingPolicy=policy())=
        assertIs<PhotoDecodeResult.Rejected>(BoundedPhotoDecoder(p).decode(bytes,type)).reason
    private fun image(result:PhotoDecodeResult.Decoded)=ImageIO.read(ByteArrayInputStream(result.variants.single{it.variant==PhotoVariant.DISPLAY}.copyBytes()))

    @Test fun pngProducesExactTwoFreshBoundedVariantsWithoutEnlarging() {
        val source=raster(300,150);val result=decoded(png(source))
        assertEquals(listOf(PhotoVariant.THUMBNAIL,PhotoVariant.DISPLAY),result.variants.map{it.variant})
        assertEquals(listOf(32 to 16,256 to 128),result.variants.map{it.width to it.height})
        result.variants.forEach { assertEquals("image/png",it.contentType);assertEquals(setOf("IHDR","IDAT","IEND"),chunks(it.copyBytes()).map{c->c.first}.toSet()) }
        val small=decoded(png(raster()));assertEquals(3 to 2,small.variants.first().let{it.width to it.height})
    }
    @Test fun baselineAndProgressiveJpegUseRealReaderAndFreshPngWriter() {
        for(progressive in listOf(false,true)) {
            val output=decoded(jpeg(raster(40,20),progressive),"image/jpeg")
            assertEquals(40 to 20,output.variants.last().let{it.width to it.height})
            assertNotNull(image(output));assertEquals("image/png",output.variants.last().contentType)
        }
    }
    @Test fun allEightExifOrientationsAndBothByteOrdersAreAppliedToPixels() {
        val source=raster()
        for(little in listOf(false,true))for(orientation in 1..8) {
            val bytes=insertPngChunk(png(source),"eXIf",exif(orientation,little))
            val output=image(decoded(bytes))
            assertEquals(if(orientation>=5)2 else 3,output.width)
            assertEquals(if(orientation>=5)3 else 2,output.height)
            for(y in 0..1)for(x in 0..2) {
                val expected=when(orientation){1->x to y;2->2-x to y;3->2-x to 1-y;4->x to 1-y;5->y to x;6->1-y to x;7->1-y to 2-x;else->y to 2-x}
                assertEquals(source.getRGB(x,y),output.getRGB(expected.first,expected.second),"orientation=$orientation little=$little")
            }
        }
    }
    @Test fun jpegExifOrientationAndPrivateAppCommentsAreRemoved() {
        val raw=jpeg(raster(30,20))
        var source=jpegSegment(raw,225,"Exif\u0000\u0000".toByteArray()+exif(6,true))
        source=jpegSegment(source,254,"GPS PRIVATE-CAMERA-SERIAL private://synthetic".toByteArray())
        source=jpegSegment(source,225,"http://ns.adobe.com/xap/1.0/\u0000PRIVATE-XMP".toByteArray())
        val output=decoded(source,"image/jpeg").variants.last()
        assertEquals(20 to 30,output.width to output.height)
        assertEquals(setOf("IHDR","IDAT","IEND"),chunks(output.copyBytes()).map{it.first}.toSet())
        assertFalse(output.copyBytes().toString(Charsets.ISO_8859_1).contains("PRIVATE"))
    }
    @Test fun pngTextExifGpsAndThumbnailSentinelsAreNotCopied() {
        val raw=png(raster())
        val text="Description\u0000PRIVATE GPS camera serial thumbnail private://location".toByteArray()
        val withText=insertPngChunk(raw,"tEXt",text)
        val output=decoded(insertPngChunk(withText,"eXIf",exif(1,false)+"PRIVATE-GPS-thumbnail".toByteArray()))
        for(v in output.variants) {
            assertEquals(setOf("IHDR","IDAT","IEND"),chunks(v.copyBytes()).map{it.first}.toSet())
            assertFalse(v.copyBytes().toString(Charsets.ISO_8859_1).contains("PRIVATE"))
        }
    }
    @Test fun actualExifGpsRationalsCameraSerialAndJpegThumbnailAreStripped() {
        val thumb=jpeg(raster(1,1));val meta=ByteBuffer.allocate(256+thumb.size).order(ByteOrder.LITTLE_ENDIAN)
        meta.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        meta.position(8);meta.putShort(3)
        meta.putShort(0x112).putShort(3).putInt(1).putShort(1).putShort(0)
        meta.putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(80)
        meta.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(128)
        meta.putInt(160)
        val serial="PRIVATE-SERIAL\u0000".toByteArray()
        meta.position(80);meta.putShort(1).putShort(0xA431.toShort()).putShort(2).putInt(serial.size).putInt(104).putInt(0)
        meta.position(104);meta.put(serial)
        meta.position(128);meta.putShort(2)
        meta.putShort(1).putShort(2).putInt(2).put('N'.code.toByte()).put(0).putShort(0)
        meta.putShort(2).putShort(5).putInt(3).putInt(200).putInt(0)
        meta.position(160);meta.putShort(2)
        meta.putShort(0x201).putShort(4).putInt(1).putInt(256)
        meta.putShort(0x202).putShort(4).putInt(1).putInt(thumb.size).putInt(0)
        meta.position(200);for(value in listOf(12,34,56))meta.putInt(value).putInt(1)
        meta.position(256);meta.put(thumb)
        val source=jpegSegment(jpeg(raster()),225,"Exif\u0000\u0000".toByteArray()+meta.array())
        val result=decoded(source,"image/jpeg")
        for(v in result.variants) {
            assertEquals(setOf("IHDR","IDAT","IEND"),chunks(v.copyBytes()).map{it.first}.toSet())
            assertFalse(v.copyBytes().toString(Charsets.ISO_8859_1).contains("PRIVATE-SERIAL"))
        }
    }
    @Test fun adam7PngIsValidatedAcrossAllPassesAndReencodedNoninterlaced() {
        val source=raster(19,13);val out=ByteArrayOutputStream();val writer=ImageIO.getImageWritersByFormatName("png").next()
        try { MemoryCacheImageOutputStream(out).use { stream ->
            writer.output=stream;val p=writer.defaultWriteParam;p.progressiveMode=ImageWriteParam.MODE_DEFAULT
            writer.write(null,IIOImage(source,null,null),p);stream.flush()
        } } finally { writer.dispose() }
        assertEquals(1,out.toByteArray()[28].toInt())
        val result=decoded(out.toByteArray());val output=image(result)
        assertEquals(0,result.variants.last().copyBytes()[28].toInt())
        for(x in 0 until 19)for(y in 0 until 13)assertEquals(source.getRGB(x,y),output.getRGB(x,y))
    }
    @Test fun grayscaleAndOpaqueTransparencyPaletteUseExplicitSupportedPaths() {
        val gray=BufferedImage(4,3,BufferedImage.TYPE_BYTE_GRAY)
        gray.raster.setSample(1,1,0,128)
        assertNotNull(image(decoded(png(gray))))
        val palette=BufferedImage(4,3,BufferedImage.TYPE_BYTE_INDEXED)
        val withOpaque=insertBeforeIdat(png(palette),"tRNS",byteArrayOf(255.toByte()))
        assertNotNull(image(decoded(withOpaque)))
    }
    @Test fun grayEightBitTransparencyMasksUnusedHighBitsBeforeNativePixelDecode() {
        val source=BufferedImage(2,1,BufferedImage.TYPE_BYTE_GRAY)
        source.raster.setSample(0,0,0,128);source.raster.setSample(1,0,0,64)
        val raw=png(source)
        assertEquals(8,raw[24].toInt());assertEquals(0,raw[25].toInt())
        val input=insertBeforeIdat(raw,"tRNS",byteArrayOf(1,128.toByte()))
        val retained=input.copyOf()
        val result=decoded(input);val output=image(result)
        val reference=image(decoded(insertBeforeIdat(raw,"tRNS",byteArrayOf(0,128.toByte()))))
        assertEquals(0,output.getRGB(0,0) ushr 24)
        assertEquals(255,output.getRGB(1,0) ushr 24)
        for(x in 0..1)assertEquals(reference.getRGB(x,0),output.getRGB(x,0))
        assertContentEquals(retained,input,"Only the sanitized decoding copy may change")
        result.variants.forEach { assertEquals(setOf("IHDR","IDAT","IEND"),chunks(it.copyBytes()).map{c->c.first}.toSet()) }
    }
    @Test fun rgbEightBitTransparencyMasksAllThreeUnusedHighBytesWithoutLosingOpaqueSibling() {
        val source=BufferedImage(2,1,BufferedImage.TYPE_INT_RGB)
        source.setRGB(0,0,0x123456);source.setRGB(1,0,0x123457)
        val raw=png(source)
        assertEquals(8,raw[24].toInt());assertEquals(2,raw[25].toInt())
        val input=insertBeforeIdat(raw,"tRNS",byteArrayOf(0xAB.toByte(),0x12,0xCD.toByte(),0x34,0xEF.toByte(),0x56))
        val retained=input.copyOf()
        val output=image(decoded(input))
        assertEquals(0,output.getRGB(0,0) ushr 24)
        assertEquals(0xFF123457.toInt(),output.getRGB(1,0))
        val reference=image(decoded(insertBeforeIdat(raw,"tRNS",byteArrayOf(0,0x12,0,0x34,0,0x56))))
        for(x in 0..1)assertEquals(reference.getRGB(x,0),output.getRGB(x,0))
        assertContentEquals(retained,input)
    }
    @Test fun lowDepthGrayTransparencyMasksUnusedBitsForOneTwoAndFourBitSamples() {
        for(depth in listOf(1,2,4)) {
            val mask=(1 shl depth)-1
            // Exhaust every packed source value, including transparent black. The second sample
            // must remain opaque: transparency compares source samples, not rounded display RGB.
            for(transparent in 0..mask) {
                val opaque=if(transparent==0)mask else 0
                val header=ByteBuffer.allocate(13).putInt(2).putInt(1).put(depth.toByte()).put(0).put(0).put(0).put(0).array()
                val row=byteArrayOf(0,((transparent shl (8-depth)) or (opaque shl (8-2*depth))).toByte())
                val compressed=ByteArrayOutputStream().also { out -> java.util.zip.DeflaterOutputStream(out).use{it.write(row)} }.toByteArray()
                val raw=PhotoContainerValidator.PNG_SIGNATURE+chunk("IHDR",header)+chunk("IDAT",compressed)+chunk("IEND",byteArrayOf())
                val sample=(0xFFFF xor mask) or transparent
                val input=insertBeforeIdat(raw,"tRNS",byteArrayOf((sample ushr 8).toByte(),sample.toByte()))
                val retained=input.copyOf()
                val result=decoded(input)
                val reference=image(decoded(insertBeforeIdat(raw,"tRNS",byteArrayOf(0,transparent.toByte()))))
                for(variant in result.variants) {
                    val output=ImageIO.read(ByteArrayInputStream(variant.copyBytes()))
                    assertEquals(0,output.getRGB(0,0) ushr 24,"depth=$depth transparent=$transparent")
                    assertEquals(if(opaque==0)0xFF000000.toInt() else 0xFFFFFFFF.toInt(),output.getRGB(1,0),
                        "depth=$depth transparent=$transparent opaque=$opaque")
                    for(x in 0..1)assertEquals(reference.getRGB(x,0),output.getRGB(x,0),"depth=$depth transparent=$transparent x=$x")
                }
                assertContentEquals(retained,input)
            }
        }
    }
    @Test fun truncatedImageEntropyCannotBeRecoveredByWarningTolerantReader() {
        val raw=jpeg(raster(40,30));val short=raw.copyOf(raw.size-20)+byteArrayOf(255.toByte(),217.toByte())
        assertEquals(PhotoRejection.MALFORMED_IMAGE,rejected(short,"image/jpeg"))
    }
    @Test fun sixteenBitPngAndAdobeCmykProfilesRemainExplicitlyUnsupported() {
        val image=BufferedImage(2,2,BufferedImage.TYPE_USHORT_GRAY)
        assertEquals(PhotoRejection.UNSUPPORTED_FORMAT,rejected(png(image)))
        val jpeg=jpegSegment(jpeg(raster()),238,"Adobe\u0000d\u0000\u0000\u0000\u0000\u0002".toByteArray())
        assertEquals(PhotoRejection.UNSUPPORTED_FORMAT,rejected(jpeg,"image/jpeg"))
    }
    @Test fun alphaAndPaletteInputsRetainPixelsOnSmallImages() {
        for(source in listOf(raster(alpha=true),BufferedImage(3,2,BufferedImage.TYPE_BYTE_INDEXED).also { im->for(x in 0..2)for(y in 0..1)im.setRGB(x,y,raster().getRGB(x,y)) })) {
            val output=image(decoded(png(source)))
            for(x in 0..2)for(y in 0..1)assertEquals(source.getRGB(x,y),output.getRGB(x,y))
        }
    }
    @Test fun metadataBudgetPartsAndSourceByteBoundsAreEnforcedBeforeDecoding() {
        val bytes=insertPngChunk(png(raster()),"tEXt","x\u0000value".toByteArray())
        assertEquals(PhotoRejection.IMAGE_LIMIT_EXCEEDED,rejected(bytes,p=policy().copy(maxMetadataBytes=1)))
        assertEquals(PhotoRejection.IMAGE_LIMIT_EXCEEDED,rejected(bytes,p=policy().copy(maxContainerParts=2)))
        val sourcePolicy=policy().copy(maxSourceBytes=bytes.size-1,maxMetadataBytes=0)
        assertEquals(PhotoRejection.IMAGE_LIMIT_EXCEEDED,rejected(bytes,p=sourcePolicy))
    }
    @Test fun dimensionsPixelsAndWorkingMemoryAreCheckedBeforeRasterAllocation() {
        val bytes=png(raster(40,20))
        assertEquals(PhotoRejection.IMAGE_LIMIT_EXCEEDED,rejected(bytes,p=policy().copy(maxPixels=10)))
        assertEquals(PhotoRejection.IMAGE_LIMIT_EXCEEDED,rejected(bytes,p=policy().copy(maxWorkingBytes=1)))
        val header=chunks(bytes).first().second.copyOf().also { ByteBuffer.wrap(it).putInt(65535) }
        val huge=replacePngChunk(bytes,"IHDR",header)
        assertEquals(PhotoRejection.IMAGE_LIMIT_EXCEEDED,rejected(huge))
    }
    @Test fun outputAndCombinedByteBudgetsStopEncoding() {
        val source=png(raster(40,30))
        assertEquals(PhotoRejection.IMAGE_LIMIT_EXCEEDED,rejected(source,p=policy().copy(maxDerivativeBytes=32,maxCombinedDerivativeBytes=64)))
        val ordinary=decoded(source);val max=ordinary.variants.maxOf{it.copyBytes().size}
        assertEquals(PhotoRejection.IMAGE_LIMIT_EXCEEDED,rejected(source,p=policy().copy(maxDerivativeBytes=max,maxCombinedDerivativeBytes=max)))
    }
    @Test fun pngBadCrcTruncationTrailingPayloadAndUnexpectedCriticalChunkAreRejected() {
        val bytes=png(raster())
        assertEquals(PhotoRejection.MALFORMED_IMAGE,rejected(bytes.copyOf().also{it[29]=(it[29].toInt() xor 1).toByte()}))
        assertEquals(PhotoRejection.MALFORMED_IMAGE,rejected(bytes.copyOf(bytes.size-1)))
        assertEquals(PhotoRejection.MALFORMED_IMAGE,rejected(bytes+byteArrayOf(0)))
        assertEquals(PhotoRejection.UNSUPPORTED_FORMAT,rejected(insertPngChunk(bytes,"ABCD",byteArrayOf())))
    }
    @Test fun pngExtraCompressedDataAndInvalidFilterAreRejected() {
        val raw=png(raster());val idat=chunks(raw).first{it.first=="IDAT"}.second
        assertEquals(PhotoRejection.MALFORMED_IMAGE,rejected(replacePngChunk(raw,"IDAT",idat+byteArrayOf(1))))
        val scanlines=ByteArrayOutputStream().also { java.util.zip.InflaterInputStream(ByteArrayInputStream(idat)).use { s->s.copyTo(it) } }.toByteArray()
        scanlines[0]=5
        val bad=ByteArrayOutputStream().also{ java.util.zip.DeflaterOutputStream(it).use { s->s.write(scanlines) } }.toByteArray()
        assertEquals(PhotoRejection.MALFORMED_IMAGE,rejected(replacePngChunk(raw,"IDAT",bad)))
    }
    @Test fun animatedPngAndUnverifiedColorProfilesAreNotSilentlyDecoded() {
        val raw=png(raster())
        assertEquals(PhotoRejection.UNSUPPORTED_FORMAT,rejected(insertPngChunk(raw,"acTL",ByteArray(8))))
        assertEquals(PhotoRejection.UNSUPPORTED_FORMAT,rejected(insertPngChunk(raw,"iCCP","profile\u0000\u0000bytes".toByteArray())))
        val jpg=jpegSegment(jpeg(raster()),226,"ICC_PROFILE\u0000\u0001\u0001PRIVATE".toByteArray())
        assertEquals(PhotoRejection.UNSUPPORTED_FORMAT,rejected(jpg,"image/jpeg"))
    }
    @Test fun malformedAndAmbiguousExifOffsetsAndOrientationsFailClosed() {
        val raw=png(raster())
        assertEquals(PhotoRejection.MALFORMED_IMAGE,rejected(insertPngChunk(raw,"eXIf",exif(9,true))))
        val corrupt=exif(1,true).also{ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4,Int.MAX_VALUE)}
        assertEquals(PhotoRejection.MALFORMED_IMAGE,rejected(insertPngChunk(raw,"eXIf",corrupt)))
        val duplicate=insertPngChunk(insertPngChunk(raw,"eXIf",exif(1,true)),"eXIf",exif(1,true))
        assertEquals(PhotoRejection.MALFORMED_IMAGE,rejected(duplicate))
    }
    @Test fun mimeMismatchHeicMalformedJpegAndTrailingJpegBytesNeverSucceed() {
        assertEquals(PhotoRejection.UNSUPPORTED_FORMAT,rejected(byteArrayOf(1),"image/heic"))
        assertEquals(PhotoRejection.MALFORMED_IMAGE,rejected(png(raster()),"image/jpeg"))
        val jpg=jpeg(raster())
        assertEquals(PhotoRejection.MALFORMED_IMAGE,rejected(jpg.copyOf(jpg.size-2),"image/jpeg"))
        assertEquals(PhotoRejection.MALFORMED_IMAGE,rejected(jpg+byteArrayOf(0),"image/jpeg"))
    }
    @Test fun outputBuffersAndDebugStringsDoNotExposeOrAliasPrivateBytes() {
        val result=decoded(png(raster()));val v=result.variants.first();val original=v.copyBytes()
        v.copyBytes().fill(0);assertContentEquals(original,v.copyBytes())
        assertEquals("Decoded(<redacted>)",result.toString());assertEquals("EncodedPhotoVariant(<redacted>)",v.toString())
    }
    @Test fun explicitProcessRunsActualCodecAndReturnsBoundedNativeBytes() {
        val result=assertIs<PhotoDecodeResult.Decoded>(process().decode(png(raster()),"image/png"))
        assertEquals(2,result.variants.size);assertEquals(3,image(result).width)
    }
    @Test fun processFailureAndDeadlineAreUnavailableNotContentRejection() {
        assertEquals(PhotoCodecUnavailable.PROCESS_FAILED,assertIs<PhotoDecodeResult.Unavailable>(process(main="com.feedme.missing.Codec").decode(png(raster()),"image/png")).reason)
        val before=children()
        val started=System.nanoTime()
        assertEquals(PhotoCodecUnavailable.PROCESS_TIMEOUT,assertIs<PhotoDecodeResult.Unavailable>(process(timeout=300,main=HangingPhotoWorker::class.java.name).decode(png(raster()),"image/png")).reason)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<5000)
        assertEquals(before,children(),"Owned hanging child must actually be gone")
    }
    @Test fun processCapacityAndCancellationRetainOwnershipUntilChildExits() {
        val codec=process(timeout=30_000,main=HangingPhotoWorker::class.java.name)
        val executor=Executors.newSingleThreadExecutor();val before=children();val entered=java.util.concurrent.CountDownLatch(1)
        val future=executor.submit<PhotoDecodeResult> { entered.countDown();codec.decode(png(raster()),"image/png") }
        try {
            assertTrue(entered.await(1,TimeUnit.SECONDS))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(children()==before && System.nanoTime()<deadline)Thread.yield()
            assertTrue(children()!=before,"Test must witness the actual live child before cancellation")
            assertEquals(PhotoCodecUnavailable.AT_CAPACITY,assertIs<PhotoDecodeResult.Unavailable>(codec.decode(png(raster()),"image/png")).reason)
            future.cancel(true);executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))
            assertEquals(before,children())
        } finally { future.cancel(true);executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS) }
    }
    @Test fun blockedLargeInputPipeTimeoutReapsChildAndPipeThread() {
        val before=children();val threads=Thread.getAllStackTraces().keys.filter{it.name=="feedme-photo-pipe" && it.isAlive}.map{it.id}.toSet()
        val result=process(timeout=300,main=HangingPhotoWorker::class.java.name).decode(ByteArray(900_000){17},"image/png")
        assertEquals(PhotoCodecUnavailable.PROCESS_TIMEOUT,assertIs<PhotoDecodeResult.Unavailable>(result).reason)
        assertEquals(before,children())
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        fun pipes()=Thread.getAllStackTraces().keys.filter{it.name=="feedme-photo-pipe" && it.isAlive}.map{it.id}.toSet()
        while(pipes()!=threads && System.nanoTime()<deadline)Thread.yield()
        assertEquals(threads,pipes(),"No blocked owned writer is abandoned after child exit")
    }
    @Test fun malformedWorkerEnvelopeIsUnavailableAndNeverAllocatesOversizeOutput() {
        val bytes=ByteArrayOutputStream().also{DataOutputStream(it).use{d->
            d.writeInt(PhotoDecodeProcess.PROTOCOL);d.writeByte(1);d.writeInt(2)
            d.writeUTF("THUMBNAIL");d.writeUTF("image/png");d.writeInt(1);d.writeInt(1);d.writeInt(Int.MAX_VALUE)
        }}.toByteArray()
        assertFailsWith<IOException>{PhotoDecodeProcess.readResult(DataInputStream(ByteArrayInputStream(bytes)),policy())}
        val result=process(main=MalformedPhotoWorker::class.java.name).decode(png(raster()),"image/png")
        assertEquals(PhotoCodecUnavailable.PROCESS_FAILED,assertIs<PhotoDecodeResult.Unavailable>(result).reason)
    }
    @Test fun processPolicyRequiresExplicitWorkingMemoryAndExecutableConfiguration() {
        assertFailsWith<IllegalArgumentException>{PhotoProcessPolicy(31,1,1000,100)}
        assertFailsWith<IllegalArgumentException>{PhotoProcessPolicy(256,0,1000,100)}
        assertFailsWith<IllegalArgumentException>{PhotoProcessPolicy(256,1,0,100)}
        assertFailsWith<IllegalArgumentException>{PhotoDecodeProcess(Path.of("java"),".",policy(),PhotoProcessPolicy(256,1,1000,100))}
        assertFailsWith<IllegalArgumentException>{policy().copy(outputContentType="image/jpeg")}
    }
    @Test fun deterministicMalformedContainerCorpusNeverEscapesTypedOutcome() {
        val decoder=BoundedPhotoDecoder(policy())
        for((type,source) in listOf("image/png" to png(raster()),"image/jpeg" to jpeg(raster()))) {
            val cases=mutableListOf<ByteArray>()
            for(n in 0 until source.size step 7)cases+=source.copyOf(n)
            for(n in source.indices step 5)cases+=source.copyOf().also{it[n]=(it[n].toInt() xor 255).toByte()}
            for(bytes in cases)when(val result=decoder.decode(bytes,type)) {
                is PhotoDecodeResult.Decoded -> result.variants.forEach{PhotoContainerValidator(policy()).inspect(it.copyBytes(),"image/png",true)}
                is PhotoDecodeResult.Rejected -> Unit
                is PhotoDecodeResult.Unavailable -> fail("Configured codec became unavailable on malformed bytes: ${result.reason}")
            }
        }
    }
    @Test fun delayedExitTransfersBlockedPipeAndPermitToReaperWithoutBlockingCaller() {
        // Deterministic Process contract double for a delayed OS exit; actual process kill is
        // separately covered above. Do not claim this branch test is a real delayed kernel exit.
        val exit=java.util.concurrent.CountDownLatch(1);val closed=java.util.concurrent.CountDownLatch(1)
        val destroying=java.util.concurrent.CountDownLatch(1);val destroyReturn=java.util.concurrent.CountDownLatch(1)
        val closing=java.util.concurrent.CountDownLatch(1);val closeReturn=java.util.concurrent.CountDownLatch(1)
        val released=java.util.concurrent.CountDownLatch(1);val closeCalls=java.util.concurrent.atomic.AtomicInteger()
        val releaseCalls=java.util.concurrent.atomic.AtomicInteger()
        val process=object:Process() {
            override fun getOutputStream():OutputStream=object:OutputStream(){
                override fun write(b:Int)=Unit
                override fun close(){
                    closeCalls.incrementAndGet();closing.countDown()
                    check(exit.await(5,TimeUnit.SECONDS));check(closeReturn.await(5,TimeUnit.SECONDS));closed.countDown()
                }
            }
            override fun getInputStream():InputStream=ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream():InputStream=ByteArrayInputStream(byteArrayOf())
            override fun waitFor():Int{exit.await();return 0}
            override fun waitFor(timeout:Long,unit:TimeUnit)=exit.await(timeout,unit)
            override fun exitValue():Int{if(isAlive)throw IllegalThreadStateException();return 0}
            override fun isAlive()=exit.count!=0L
            override fun destroy(){error("Exact forceful termination path required")}
            override fun destroyForcibly():Process{
                // JDK17 ProcessImpl itself closes buffered stdin here. Model that blocking
                // operation too, not merely a delayed waitFor after an instantaneous kill call.
                destroying.countDown();check(destroyReturn.await(5,TimeUnit.SECONDS));return this
            }
        }
        try {
            val started=System.nanoTime();retirePhotoChild(process,10) { releaseCalls.incrementAndGet();released.countDown() }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1000)
            assertTrue(destroying.await(2,TimeUnit.SECONDS));assertEquals(0,closeCalls.get());assertEquals(1,released.count)
            exit.countDown();assertEquals(1,released.count,"An exit does not acknowledge unfinished native termination cleanup")
            destroyReturn.countDown();assertTrue(closing.await(2,TimeUnit.SECONDS))
            assertEquals(1,released.count,"The owned blocked close retains the permit")
            closeReturn.countDown();assertTrue(closed.await(2,TimeUnit.SECONDS));assertTrue(released.await(2,TimeUnit.SECONDS))
            assertEquals(1,closeCalls.get());assertEquals(1,releaseCalls.get())
        }finally{
            exit.countDown();destroyReturn.countDown();closeReturn.countDown()
            assertTrue(released.await(2,TimeUnit.SECONDS))
        }
    }

    private fun children()=ProcessHandle.current().children().use{it.filter{p->p.isAlive}.map{p->p.pid()}.toList().toSet()}
    private fun process(timeout:Long=10_000,main:String=PhotoDecodeWorker::class.java.name):PhotoDecodeProcess {
        val cp=listOf(PhotoDecodeWorker::class.java,PhotoCodec::class.java,kotlin.Unit::class.java,HangingPhotoWorker::class.java)
            .map{Path.of(it.protectionDomain.codeSource.location.toURI()).toString()}.distinct().joinToString(File.pathSeparator)
        return PhotoDecodeProcess(Path.of(System.getProperty("java.home"),"bin","java"),cp,policy(),PhotoProcessPolicy(256,1,timeout,1000),main)
    }
    private fun exif(orientation:Int,little:Boolean):ByteArray = ByteBuffer.allocate(26).order(if(little)ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).apply {
        put(if(little)'I'.code.toByte() else 'M'.code.toByte());put(if(little)'I'.code.toByte() else 'M'.code.toByte())
        putShort(42);putInt(8);putShort(1);putShort(0x112);putShort(3);putInt(1);putShort(orientation.toShort());putShort(0);putInt(0)
    }.array()
    private fun chunk(type:String,data:ByteArray):ByteArray=ByteArrayOutputStream().also { out->DataOutputStream(out).use { d ->
        val name=type.toByteArray(Charsets.US_ASCII);d.writeInt(data.size);d.write(name);d.write(data)
        d.writeInt(CRC32().apply{update(name);update(data)}.value.toInt())
    } }.toByteArray()
    private fun insertPngChunk(bytes:ByteArray,type:String,data:ByteArray)=bytes.copyOfRange(0,33)+chunk(type,data)+bytes.copyOfRange(33,bytes.size)
    private fun insertBeforeIdat(bytes:ByteArray,type:String,data:ByteArray):ByteArray {
        var inserted=false
        return PhotoContainerValidator.PNG_SIGNATURE+chunks(bytes).fold(byteArrayOf()){out,c->
            val addition=if(c.first=="IDAT" && !inserted){inserted=true;chunk(type,data)}else byteArrayOf()
            out+addition+chunk(c.first,c.second)
        }
    }
    private fun chunks(bytes:ByteArray):List<Pair<String,ByteArray>> {
        val result=mutableListOf<Pair<String,ByteArray>>();var pos=8
        while(pos<bytes.size){val size=ByteBuffer.wrap(bytes,pos,4).int;result+=String(bytes,pos+4,4,Charsets.US_ASCII) to bytes.copyOfRange(pos+8,pos+8+size);pos+=size+12}
        return result
    }
    private fun replacePngChunk(bytes:ByteArray,type:String,data:ByteArray)=PhotoContainerValidator.PNG_SIGNATURE+chunks(bytes).fold(byteArrayOf()){out,c->out+chunk(c.first,if(c.first==type)data else c.second)}
    private fun jpegSegment(bytes:ByteArray,marker:Int,data:ByteArray)=bytes.copyOfRange(0,2)+byteArrayOf(255.toByte(),marker.toByte(),((data.size+2) ushr 8).toByte(),(data.size+2).toByte())+data+bytes.copyOfRange(2,bytes.size)
}

/** Test fixture only: actually blocked native process, not a mocked future timeout. */
object HangingPhotoWorker { @JvmStatic fun main(args:Array<String>) { while(true)Thread.sleep(1000) } }
object MalformedPhotoWorker { @JvmStatic fun main(args:Array<String>) { System.`in`.readBytes();System.out.write(byteArrayOf(1,2,3,4));System.out.flush() } }
