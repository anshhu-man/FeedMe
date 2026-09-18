package com.feedme.server.media.processing.codec

import com.feedme.server.media.processing.*
import java.awt.RenderingHints
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageWriteParam
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi
import javax.imageio.spi.ImageWriterSpi
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/** Worker-only core. Production callers use PhotoDecodeProcess, not an in-process timeout.
 * Only pinned JDK readers/writer are used; unsupported color profiles are not guessed. */
internal class BoundedPhotoDecoder(private val policy: PhotoProcessingPolicy) : PhotoCodec {
    override val revision: String get() = policy.revision
    private val validator = PhotoContainerValidator(policy)

    override fun decode(source: ByteArray, contentType: String): PhotoDecodeResult = try {
        val bytes=source.copyOf()
        val descriptor=validator.inspect(bytes,contentType)
        val cleanSource=stripMetadata(bytes,descriptor.format)
        val decoded=read(cleanSource,descriptor)
        try {
            val oriented=orient(decoded,descriptor.orientation,descriptor.alpha)
            try {
                var total=0L
                val variants=listOf(PhotoVariant.THUMBNAIL to policy.thumbnailEdge, PhotoVariant.DISPLAY to policy.displayEdge).map { (variant,edge) ->
                    val image=scale(oriented,edge)
                    try {
                        val output=encode(image)
                        total+=output.size; photoLimit(total<=policy.maxCombinedDerivativeBytes)
                        val final=validator.inspect(output,"image/png",derivative=true)
                        photoValid(final.width==image.width && final.height==image.height && final.orientation==1)
                        // Re-read the emitted container using a new reader, without source metadata.
                        read(output,final).flush()
                        EncodedPhotoVariant(variant,"image/png",final.width,final.height,output)
                    } finally { image.flush() }
                }
                PhotoDecodeResult.Decoded(variants)
            } finally { oriented.flush() }
        } finally { decoded.flush() }
    } catch(e:PhotoValidationException) {
        PhotoDecodeResult.Rejected(when(e.reason) {
            ContainerFailure.MALFORMED -> PhotoRejection.MALFORMED_IMAGE
            ContainerFailure.UNSUPPORTED -> PhotoRejection.UNSUPPORTED_FORMAT
            ContainerFailure.LIMIT -> PhotoRejection.IMAGE_LIMIT_EXCEEDED
        })
    } catch(_:CodecUnavailable) {
        PhotoDecodeResult.Unavailable(PhotoCodecUnavailable.NOT_CONFIGURED)
    } catch(e:IOException) {
        // Writers sometimes wrap our capped-stream failure; do not turn it into malformed input.
        var reason:Throwable?=e; var limit=false
        repeat(8) { if((reason as? PhotoValidationException)?.reason==ContainerFailure.LIMIT)limit=true; reason=reason?.cause }
        PhotoDecodeResult.Rejected(if(limit) PhotoRejection.IMAGE_LIMIT_EXCEEDED else PhotoRejection.MALFORMED_IMAGE)
    }

    private fun read(bytes:ByteArray,descriptor:PhotoContainer):BufferedImage {
        val provider=readerProvider(descriptor.format)
        val reader=provider.createReaderInstance()
        try {
            var warning=false
            reader.addIIOReadWarningListener { _,_ -> warning=true }
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                reader.setInput(input,false,true)
                photoValid(reader.getNumImages(true)==1)
                photoValid(reader.getWidth(0)==descriptor.width && reader.getHeight(0)==descriptor.height)
                policy.checkRaster(descriptor.width,descriptor.height,bytes.size)
                val image=reader.read(0) ?: throw PhotoValidationException(ContainerFailure.MALFORMED)
                try {
                    photoValid(!warning && image.width==descriptor.width && image.height==descriptor.height)
                    photoSupported(image.colorModel.colorSpace.isCS_sRGB || image.colorModel.colorSpace.type==ColorSpace.TYPE_GRAY)
                    photoSupported(image.colorModel.numColorComponents in setOf(1,3))
                    photoValid(!image.colorModel.hasAlpha() || descriptor.alpha)
                    return image
                } catch(e:Throwable) { image.flush(); throw e }
            }
        } finally { reader.dispose() }
    }

    private fun orient(source:BufferedImage,orientation:Int,alpha:Boolean):BufferedImage {
        val swap=orientation>=5
        val output=BufferedImage(if(swap)source.height else source.width,if(swap)source.width else source.height,
            if(alpha)BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        for(y in 0 until source.height) for(x in 0 until source.width) {
            val point=when(orientation) {
                1 -> x to y
                2 -> source.width-1-x to y
                3 -> source.width-1-x to source.height-1-y
                4 -> x to source.height-1-y
                5 -> y to x
                6 -> source.height-1-y to x
                7 -> source.height-1-y to source.width-1-x
                8 -> y to source.width-1-x
                else -> throw PhotoValidationException(ContainerFailure.MALFORMED)
            }
            output.setRGB(point.first,point.second,source.getRGB(x,y))
        }
        return output
    }

    private fun scale(source:BufferedImage,edge:Int):BufferedImage {
        val denominator=maxOf(source.width,source.height)
        val numerator=minOf(edge,denominator)
        val width=maxOf(1,(source.width.toLong()*numerator/denominator).toInt())
        val height=maxOf(1,(source.height.toLong()*numerator/denominator).toInt())
        val result=BufferedImage(width,height,if(source.colorModel.hasAlpha())BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        if(width==source.width && height==source.height) {
            for(y in 0 until height)for(x in 0 until width)result.setRGB(x,y,source.getRGB(x,y))
            return result
        }
        val graphics=result.createGraphics()
        try {
            graphics.composite=java.awt.AlphaComposite.Src
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
            graphics.drawImage(source,0,0,width,height,null)
        } finally { graphics.dispose() }
        return result
    }

    private fun encode(image:BufferedImage):ByteArray {
        val writer=writerProvider().createWriterInstance()
        val capped=CappedOutput(policy.maxDerivativeBytes)
        try {
            // PNG writes forward and flushes; cap both the seekable cache and downstream bytes.
            object:MemoryCacheImageOutputStream(capped) {
                override fun write(value:Int) { photoLimit(streamPosition+1<=policy.maxDerivativeBytes); super.write(value) }
                override fun write(bytes:ByteArray,offset:Int,length:Int) { photoLimit(streamPosition+length<=policy.maxDerivativeBytes); super.write(bytes,offset,length) }
                override fun seek(pos:Long) { photoLimit(pos in 0..policy.maxDerivativeBytes.toLong()); super.seek(pos) }
            }.use { stream ->
                writer.output=stream
                val param=writer.defaultWriteParam
                if(param.canWriteProgressive())param.progressiveMode=ImageWriteParam.MODE_DISABLED
                if(param.canWriteCompressed()) {
                    param.compressionMode=ImageWriteParam.MODE_EXPLICIT
                    param.compressionType=param.compressionTypes.single()
                    // Fixed internal PNG compression profile, versioned by configured codec revision.
                    param.compressionQuality=0.75f
                }
                writer.write(null,IIOImage(image,null,null),param)
                stream.flush()
            }
            return capped.bytes()
        } finally { writer.dispose() }
    }

    private fun stripMetadata(source:ByteArray,format:String):ByteArray {
        val out=ByteArrayOutputStream(source.size)
        if(format=="png") {
            out.write(source,0,8); var pos=8
            while(pos<source.size) {
                val length=PhotoContainerValidator.uint32(source,pos).toInt()
                val type=String(source,pos+4,4,Charsets.US_ASCII)
                if(type=="tRNS" && PhotoContainerValidator.u8(source,25) in setOf(0,2)) {
                    // PNG requires ignoring unused high bits; JDK17's reader compares the full
                    // unsigned short. It also expands packed gray samples to 8 bits BEFORE the
                    // transparency comparison. Expand the masked gray key identically. For
                    // depths 1/2/4, bit replication preserves the original low bits, so this
                    // sanitized value also retains PNG's masked-sample meaning. Original bytes
                    // and compressed pixels are never changed.
                    val value=source.copyOfRange(pos+8,pos+8+length)
                    val depth=PhotoContainerValidator.u8(source,24)
                    val mask=(1 shl depth)-1
                    for(i in value.indices step 2) {
                        val masked=PhotoContainerValidator.uint16(value,i) and mask
                        val sample=if(PhotoContainerValidator.u8(source,25)==0 && depth<8) masked*255/mask else masked
                        value[i]=(sample ushr 8).toByte();value[i+1]=sample.toByte()
                    }
                    val name="tRNS".toByteArray(Charsets.US_ASCII)
                    val data=DataOutputStream(out)
                    data.writeInt(value.size);data.write(name);data.write(value)
                    data.writeInt(CRC32().apply{update(name);update(value)}.value.toInt())
                } else if(type in setOf("IHDR","PLTE","tRNS","IDAT","IEND"))out.write(source,pos,length+12)
                pos+=length+12
            }
        } else {
            out.write(source,0,2); var pos=2; var entropy=false
            while(pos<source.size) {
                if(entropy) {
                    val begin=pos
                    while(pos<source.size) {
                        if(PhotoContainerValidator.u8(source,pos++)!=255)continue
                        while(pos<source.size && PhotoContainerValidator.u8(source,pos)==255)pos++
                        val marker=PhotoContainerValidator.u8(source,pos)
                        if(marker==0 || marker in 208..215){pos++;continue}
                        pos--;entropy=false;break
                    }
                    out.write(source,begin,pos-begin)
                }
                val begin=pos++
                while(PhotoContainerValidator.u8(source,pos)==255)pos++
                val marker=PhotoContainerValidator.u8(source,pos++)
                if(marker==217){out.write(source,begin,pos-begin);break}
                val length=PhotoContainerValidator.uint16(source,pos)
                if(marker !in 224..239 && marker!=254)out.write(source,begin,pos+length-begin)
                pos+=length
                if(marker==218)entropy=true
            }
        }
        return out.toByteArray()
    }

    private class CappedOutput(private val cap:Int):OutputStream() {
        private val output=ByteArrayOutputStream(minOf(cap,8192))
        override fun write(value:Int) { photoLimit(output.size()<cap);output.write(value) }
        override fun write(b:ByteArray,off:Int,len:Int) { photoLimit(len>=0 && len<=cap-output.size());output.write(b,off,len) }
        fun bytes()=output.toByteArray()
    }
    private class CodecUnavailable:RuntimeException(null,null,false,false)
    private fun readerProvider(format:String):ImageReaderSpi {
        val name=when(format){"png"->"com.sun.imageio.plugins.png.PNGImageReaderSpi";"jpeg"->"com.sun.imageio.plugins.jpeg.JPEGImageReaderSpi";else->throw CodecUnavailable()}
        return IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi::class.java,false).asSequence()
            .singleOrNull { it.javaClass.name==name && it.javaClass.module.name=="java.desktop" } ?: throw CodecUnavailable()
    }
    private fun writerProvider():ImageWriterSpi = IIORegistry.getDefaultInstance().getServiceProviders(ImageWriterSpi::class.java,false).asSequence()
        .singleOrNull { it.javaClass.name=="com.sun.imageio.plugins.png.PNGImageWriterSpi" && it.javaClass.module.name=="java.desktop" } ?: throw CodecUnavailable()
}
