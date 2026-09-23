package com.feedme.app.media

import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.DataFormatException

/** Bounded container checks happen before Android raster allocation. No URL, plugin fallback,
 * source metadata decode, or recursive EXIF traversal. Unknown coding/color forms fail closed. */
internal class AndroidPhotoContainerValidator(private val policy: AndroidPhotoImportPolicy, private val checkCancellation: () -> Unit = {}) {
    fun inspect(bytes: ByteArray, contentType: String, derivative: Boolean = false): PhotoContainer {
        checkCancellation()
        photoLimit(bytes.isNotEmpty() && bytes.size <= if (derivative) policy.maxDerivativeBytes else policy.maxSourceBytes)
        return when (contentType) {
            "image/png" -> png(bytes, derivative)
            "image/jpeg" -> { photoSupported(!derivative); jpeg(bytes) }
            else -> throw PhotoValidationException(ContainerFailure.UNSUPPORTED)
        }.also { policy.checkRaster(it.width, it.height, bytes.size) }
    }

    private fun png(b: ByteArray, derivative: Boolean): PhotoContainer {
        photoValid(b.size >= 33 && b.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE))
        var pos = 8; var parts = 0; var metadataBytes = 0L; var width = 0; var height = 0
        var depth = 0; var color = 0; var interlace = 0; var orientation: Int? = null
        var palette = false; var transparency = false; var ended = false; var seenIdat = false; var idatEnded = false
        var paletteEntries = 0
        val seenSingleton = mutableSetOf<String>()
        val data = ArrayList<Pair<Int, Int>>()
        while (pos < b.size) {
            checkCancellation();
            photoLimit(++parts <= policy.maxContainerParts)
            photoValid(b.size - pos >= 12)
            val length = uint32(b, pos)
            photoValid(length <= b.size.toLong() - pos - 12)
            val n = length.toInt(); val start = pos + 8; val end = start + n
            val type = String(b, pos + 4, 4, Charsets.US_ASCII)
            photoValid(type.all { it in 'A'..'Z' || it in 'a'..'z' } && type[2] in 'A'..'Z')
            val crc = CRC32().apply { update(b, pos + 4, n + 4) }.value
            photoValid(crc == uint32(b, end))
            photoValid(!ended && (parts != 1 || type == "IHDR"))
            if (type != "IDAT" && seenIdat) idatEnded = true
            if (type !in setOf("IDAT", "tEXt", "zTXt", "iTXt")) photoValid(seenSingleton.add(type))
            if (type !in setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS")) {
                metadataBytes += n.toLong() + 12; photoLimit(metadataBytes <= policy.maxMetadataBytes)
            }
            if (derivative) photoValid(type in setOf("IHDR", "IDAT", "IEND"))
            when (type) {
                "IHDR" -> {
                    photoValid(parts == 1 && n == 13)
                    val w = uint32(b, start); val h = uint32(b, start + 4)
                    photoLimit(w in 1..policy.maxWidth.toLong() && h in 1..policy.maxHeight.toLong())
                    width = w.toInt(); height = h.toInt(); depth = u8(b, start + 8); color = u8(b, start + 9)
                    photoValid(u8(b, start + 10) == 0 && u8(b, start + 11) == 0)
                    interlace = u8(b, start + 12); photoValid(interlace in 0..1)
                    photoValid(when (color) {
                        0 -> depth in setOf(1, 2, 4, 8, 16)
                        2, 4, 6 -> depth in setOf(8, 16)
                        3 -> depth in setOf(1, 2, 4, 8)
                        else -> false
                    })
                    // Explicit current profile: no unverified 16-bit color conversion.
                    photoSupported(depth <= 8)
                    if (derivative) photoValid(depth == 8 && color in setOf(2, 6) && interlace == 0)
                    policy.checkRaster(width, height, b.size)
                }
                "PLTE" -> {
                    photoValid(!seenIdat && n in 3..768 && n % 3 == 0 && color !in setOf(0, 4))
                    palette = true; paletteEntries = n / 3
                    if (color == 3) photoValid(paletteEntries <= (1 shl depth))
                }
                "tRNS" -> {
                    photoValid(!seenIdat && when (color) { 0 -> n == 2; 2 -> n == 6; 3 -> palette && n in 1..paletteEntries; else -> false })
                    transparency = true
                }
                "IDAT" -> {
                    photoValid(!idatEnded && (color != 3 || palette))
                    seenIdat = true; data += start to n
                }
                "IEND" -> { photoValid(n == 0 && seenIdat && end + 4 == b.size); ended = true }
                "eXIf" -> { photoValid(!seenIdat && orientation == null); orientation = exif(b, start, n) }
                "iCCP", "acTL", "fcTL", "fdAT" -> throw PhotoValidationException(ContainerFailure.UNSUPPORTED)
                "sRGB" -> photoValid(!seenIdat && n == 1 && u8(b, start) in 0..3)
                "gAMA" -> { photoValid(!seenIdat && n == 4); photoSupported(uint32(b, start) == 45455L) }
                "cHRM" -> {
                    photoValid(!seenIdat && n == 32)
                    val srgb = listOf(31270L,32900L,64000L,33000L,30000L,60000L,15000L,6000L)
                    photoSupported(srgb.indices.all { uint32(b, start + it * 4) == srgb[it] })
                }
                "tEXt", "zTXt", "iTXt" -> {
                    val zero = (start until end).firstOrNull { b[it] == 0.toByte() }
                    photoValid(zero != null && zero - start in 1..79)
                    // Text is bounded and discarded, never inflated or passed to the decoder.
                    if (type == "zTXt") photoValid(zero!! + 2 < end && b[zero + 1] == 0.toByte())
                    if (type == "iTXt") photoValid(zero!! + 3 < end && u8(b, zero + 1) in 0..1 && b[zero + 2] == 0.toByte())
                }
                "pHYs" -> photoValid(n == 9 && !seenIdat && u8(b, start + 8) in 0..1)
                "tIME" -> photoValid(n == 7)
                "bKGD" -> photoValid(!seenIdat && n == when (color) { 0,4 -> 2; 2,6 -> 6; else -> 1 })
                "sBIT" -> photoValid(!seenIdat && n == when (color) { 0 -> 1; 2,3 -> 3; 4 -> 2; else -> 4 })
                "hIST" -> photoValid(!seenIdat && palette && n == paletteEntries * 2)
                else -> throw PhotoValidationException(ContainerFailure.UNSUPPORTED)
            }
            pos = end + 4
        }
        photoValid(ended && seenIdat)
        verifyPngInflation(b, data, width, height, depth, color, interlace)
        return PhotoContainer("png", width, height, orientation ?: 1, color in setOf(4,6) || transparency)
    }

    /** Exact scanline count and zlib termination, including Adam7; no decompressed image buffer. */
    private fun verifyPngInflation(b: ByteArray, chunks: List<Pair<Int, Int>>, w: Int, h: Int, depth: Int, color: Int, interlace: Int) {
        val channels = when (color) { 0,3 -> 1; 2 -> 3; 4 -> 2; else -> 4 }
        val passes = if (interlace == 0) listOf(intArrayOf(0,0,1,1)) else listOf(
            intArrayOf(0,0,8,8), intArrayOf(4,0,8,8), intArrayOf(0,4,4,8), intArrayOf(2,0,4,4),
            intArrayOf(0,2,2,4), intArrayOf(1,0,2,2), intArrayOf(0,1,1,2))
        val rows = passes.mapNotNull { p ->
            val pw = if (w <= p[0]) 0 else (w - p[0] + p[2] - 1) / p[2]
            val ph = if (h <= p[1]) 0 else (h - p[1] + p[3] - 1) / p[3]
            if (pw == 0 || ph == 0) null else ((pw.toLong() * channels * depth + 7) / 8 + 1) to ph
        }
        val expected = rows.sumOf { it.first * it.second }
        photoLimit(expected <= policy.maxWorkingBytes)
        val inflater = Inflater(); var chunk = 0; var total = 0L; var pass = 0; var row = 0; var offset = 0L
        val buffer = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                checkCancellation();
                if (inflater.needsInput()) {
                    while (chunk < chunks.size && chunks[chunk].second == 0) chunk++
                    photoValid(chunk < chunks.size)
                    val next = chunks[chunk++]; inflater.setInput(b, next.first, next.second)
                }
                val count = inflater.inflate(buffer)
                photoValid(!inflater.needsDictionary())
                if (count == 0) { photoValid(inflater.finished() || inflater.needsInput()); continue }
                photoValid(total + count <= expected)
                for (i in 0 until count) {
                    photoValid(pass < rows.size)
                    if (offset == 0L) photoValid(u8(buffer, i) in 0..4)
                    if (++offset == rows[pass].first) {
                        offset = 0; if (++row == rows[pass].second) { pass++; row = 0 }
                    }
                }
                total += count
            }
            photoValid(total == expected && inflater.remaining == 0 && chunks.drop(chunk).all { it.second == 0 })
        } catch (_: DataFormatException) { throw PhotoValidationException(ContainerFailure.MALFORMED) }
        finally { inflater.end() }
    }

    private fun jpeg(b: ByteArray): PhotoContainer {
        photoValid(b.size >= 4 && u8(b,0) == 255 && u8(b,1) == 216)
        var pos = 2; var width = 0; var height = 0; var parts = 0; var metadata = 0L
        var orientation: Int? = null; var sawFrame = false; var sawScan = false; var ended = false
        var entropy = false
        while (pos < b.size) {
            checkCancellation()
            if (entropy) {
                while (pos < b.size) {
                    if (pos and 8191 == 0) checkCancellation()
                    if (u8(b, pos++) != 255) continue
                    while (pos < b.size && u8(b, pos) == 255) pos++
                    photoValid(pos < b.size)
                    val marker = u8(b, pos)
                    if (marker == 0 || marker in 208..215) { pos++; continue }
                    pos--; entropy = false; break
                }
                photoValid(!entropy)
            }
            photoLimit(++parts <= policy.maxContainerParts)
            photoValid(pos < b.size && u8(b, pos++) == 255)
            while (pos < b.size && u8(b, pos) == 255) pos++
            photoValid(pos < b.size)
            val marker = u8(b, pos++)
            if (marker == 217) { photoValid(sawScan && pos == b.size); ended = true; break }
            photoValid(marker != 216 && marker != 0 && marker !in 208..215 && marker != 1)
            photoValid(b.size - pos >= 2)
            val length = uint16(b, pos); photoValid(length >= 2 && length <= b.size - pos)
            val start = pos + 2; val n = length - 2
            when (marker) {
                192,194 -> {
                    photoValid(!sawFrame && !sawScan && n >= 6)
                    photoSupported(u8(b,start) == 8)
                    height = uint16(b,start+1); width = uint16(b,start+3)
                    val components = u8(b,start+5); photoSupported(components in setOf(1,3))
                    photoValid(n == 6 + 3 * components)
                    policy.checkRaster(width,height,b.size); sawFrame = true
                }
                218 -> { photoValid(sawFrame && n >= 6 && n == 4 + 2 * u8(b,start)); sawScan = true; entropy = true }
                219,196 -> photoValid(n > 0)
                221 -> photoValid(n == 2)
                in 224..239,254 -> {
                    metadata += length.toLong() + 2; photoLimit(metadata <= policy.maxMetadataBytes)
                    if (marker == 225 && hasPrefix(b,start,n,EXIF_PREFIX)) {
                        photoValid(orientation == null); orientation = exif(b,start+6,n-6)
                    }
                    if (marker == 226 && hasPrefix(b,start,n,ICC_PREFIX)) photoSupported(false)
                    if (marker == 238 && hasPrefix(b,start,n,"Adobe".toByteArray())) photoSupported(false)
                }
                else -> throw PhotoValidationException(ContainerFailure.UNSUPPORTED)
            }
            pos += length
        }
        photoValid(ended && sawFrame && sawScan)
        return PhotoContainer("jpeg",width,height,orientation ?: 1,false)
    }

    private fun exif(b: ByteArray, start: Int, length: Int): Int {
        photoValid(length >= 8 && start >= 0 && length <= b.size-start)
        val little = b[start] == 'I'.code.toByte() && b[start+1] == 'I'.code.toByte()
        photoValid(little || (b[start] == 'M'.code.toByte() && b[start+1] == 'M'.code.toByte()))
        fun at(offset: Long, size: Int): Int { photoValid(offset >= 0 && offset <= length.toLong()-size); return start+offset.toInt() }
        fun short(offset: Long): Int { val p=at(offset,2); return if(little) u8(b,p) or (u8(b,p+1) shl 8) else uint16(b,p) }
        fun long(offset: Long): Long { val p=at(offset,4); return if(little) (0..3).sumOf { u8(b,p+it).toLong() shl (8*it) } else uint32(b,p) }
        photoValid(short(2) == 42)
        val ifd=long(4); photoValid(ifd >= 8); val count=short(ifd)
        photoLimit(count <= policy.maxExifEntries); at(ifd+2,count*12+4)
        var orientation: Int?=null
        for(i in 0 until count) {
            val entry=ifd+2+i*12
            val type=short(entry+2); val num=long(entry+4)
            val unit=when(type) { 1,2,6,7 -> 1; 3,8 -> 2; 4,9,11 -> 4; 5,10,12 -> 8; else -> 0 }
            photoValid(unit != 0 && num <= length.toLong()/unit)
            if(num*unit > 4) at(long(entry+8),(num*unit).toInt())
            if(short(entry) == 0x112) {
                photoValid(orientation == null && type == 3 && num == 1L)
                orientation=short(entry+8); photoValid(orientation in 1..8)
            }
        }
        // Other IFDs/thumbnail/GPS are never followed or copied. Validate a nonzero link's envelope.
        val next=long(ifd+2+count*12); if(next != 0L) at(next,2)
        return orientation ?: 1
    }

    companion object {
        internal val PNG_SIGNATURE=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
        private val EXIF_PREFIX="Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)
        private val ICC_PREFIX="ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)
        internal fun u8(b: ByteArray,p: Int)=b[p].toInt() and 255
        internal fun uint16(b: ByteArray,p: Int)=(u8(b,p) shl 8) or u8(b,p+1)
        internal fun uint32(b: ByteArray,p: Int)=(0..3).fold(0L) { n,i -> (n shl 8) or u8(b,p+i).toLong() }
        private fun hasPrefix(b:ByteArray,start:Int,length:Int,prefix:ByteArray)=length>=prefix.size && prefix.indices.all{b[start+it]==prefix[it]}
    }
}
