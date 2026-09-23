package com.feedme.app.media

/** Completeness admission for the API 27 BitmapFactory path, which otherwise accepts partial
 * JPEGs. Parses baseline Huffman symbols, not pixels. No missing-bit recovery, raster allocation,
 * or metadata interpretation. The normal container/color/EXIF validator is still mandatory.
 * T.81 Annexes B/C/F: sequential scans, MCU coverage, restart markers and one-bit padding.
 * Progressive/extended/arithmetic JPEG is deliberately not claimed by this baseline parser. */
internal fun validateBaselineJpeg(bytes: ByteArray, policy: AndroidPhotoImportPolicy,
    checkCancellation: () -> Unit = {}) {
    BaselineJpegAdmission(bytes, policy, checkCancellation).validate()
}

private class BaselineJpegAdmission(private val b: ByteArray, private val policy: AndroidPhotoImportPolicy,
    private val checkCancellation: () -> Unit) {
    private var pos = 0
    private var parts = 0
    private var width = 0
    private var height = 0
    private var restartInterval = 0
    private var metadataBytes = 0L
    private val components = linkedMapOf<Int, Component>()
    private val scanned = mutableSetOf<Int>()
    private val quantization = BooleanArray(4)
    private val dc = arrayOfNulls<Huffman>(2)
    private val ac = arrayOfNulls<Huffman>(2)

    private data class Component(val id: Int, val horizontal: Int, val vertical: Int, val quantizer: Int)
    private data class ScanComponent(val component: Component, val dc: Huffman, val ac: Huffman)

    fun validate() {
        checkCancellation()
        photoLimit(b.size in 1..policy.maxSourceBytes)
        photoValid(byte() == 0xff && byte() == 0xd8)
        while (pos < b.size) {
            checkCancellation()
            photoLimit(++parts <= policy.maxContainerParts)
            when (val marker = marker()) {
                0xd9 -> {
                    photoValid(components.isNotEmpty() && scanned == components.keys && pos == b.size)
                    return
                }
                0xc0 -> segment(::frame)
                0xc4 -> segment(::huffman)
                0xdb -> segment(::quantizers)
                0xdd -> segment { end -> photoValid(end - pos == 2); restartInterval = short() }
                0xda -> segment { end -> scan(end) }
                in 0xe0..0xef, 0xfe -> segment { end ->
                    metadataBytes += end - pos + 4L
                    photoLimit(metadataBytes <= policy.maxMetadataBytes)
                    pos = end
                }
                0xc1, 0xc2, 0xc3, in 0xc5..0xcf, 0xdc, 0xde, 0xdf ->
                    throw PhotoValidationException(ContainerFailure.UNSUPPORTED)
                else -> throw PhotoValidationException(ContainerFailure.MALFORMED)
            }
        }
        throw PhotoValidationException(ContainerFailure.MALFORMED)
    }

    private fun frame(end: Int) {
        photoValid(components.isEmpty() && end - pos >= 6)
        photoSupported(byte() == 8)
        height = short(); width = short()
        val count = byte()
        photoSupported(count == 1 || count == 3)
        photoValid(end - pos == count * 3)
        policy.checkRaster(width, height, b.size)
        repeat(count) {
            val id = byte(); val sampling = byte(); val table = byte()
            val h = sampling shr 4; val v = sampling and 15
            photoValid(id !in components && h in 1..4 && v in 1..4 && table in 0..3)
            components[id] = Component(id, h, v, table)
        }
    }

    private fun quantizers(end: Int) {
        photoValid(pos < end)
        while (pos < end) {
            val info = byte(); val precision = info shr 4; val table = info and 15
            photoValid(table in 0..3 && precision in 0..1)
            photoSupported(precision == 0)
            photoValid(end - pos >= 64)
            repeat(64) { photoValid(byte() != 0) }
            quantization[table] = true
        }
    }

    private fun huffman(end: Int) {
        photoValid(pos < end)
        while (pos < end) {
            photoValid(end - pos >= 17)
            val info = byte(); val kind = info shr 4; val table = info and 15
            photoValid(kind in 0..1 && table in 0..1)
            val counts = IntArray(16) { byte() }
            val total = counts.sum()
            photoValid(total in 1..256 && end - pos >= total)
            val values = IntArray(total) { byte() }
            photoValid(values.distinct().size == total)
            values.forEach { symbol ->
                if (kind == 0) photoValid(symbol in 0..11)
                else photoValid(symbol == 0 || symbol == 0xf0 || (symbol and 15) in 1..10)
            }
            val parsed = Huffman(counts, values)
            if (kind == 0) dc[table] = parsed else ac[table] = parsed
        }
    }

    private fun scan(end: Int) {
        photoValid(components.isNotEmpty() && end - pos >= 6)
        val count = byte()
        photoValid(count in 1..components.size && end - pos == count * 2 + 3)
        val selected = mutableListOf<ScanComponent>()
        repeat(count) {
            val component = components[byte()] ?: throw PhotoValidationException(ContainerFailure.MALFORMED)
            photoValid(component.id !in scanned && selected.none { it.component.id == component.id })
            val tables = byte(); val dcIndex = tables shr 4; val acIndex = tables and 15
            photoValid(dcIndex in 0..1 && acIndex in 0..1 && quantization[component.quantizer])
            selected += ScanComponent(component, dc[dcIndex] ?: malformed(), ac[acIndex] ?: malformed())
        }
        photoValid(byte() == 0 && byte() == 63 && byte() == 0 && pos == end)
        val frameOrder = components.keys.toList()
        photoValid(selected.zipWithNext().all { (a, b) -> frameOrder.indexOf(a.component.id) < frameOrder.indexOf(b.component.id) })
        if (count > 1) photoValid(selected.sumOf { it.component.horizontal * it.component.vertical } <= 10)
        val maxH = components.values.maxOf { it.horizontal }
        val maxV = components.values.maxOf { it.vertical }
        val columns: Int
        val rows: Int
        if (count == 1) {
            val c = selected.single().component
            columns = ceilDivide(width * c.horizontal, 8 * maxH)
            rows = ceilDivide(height * c.vertical, 8 * maxV)
        } else {
            columns = ceilDivide(width, 8 * maxH)
            rows = ceilDivide(height, 8 * maxV)
        }
        val total = columns.toLong() * rows
        // Geometry is policy-bounded before this loop. At most 10 blocks/MCU and 64 symbols/block.
        val bits = Entropy()
        var restart = 0
        var mcu = 0L
        while (mcu < total) {
            if (mcu and 63L == 0L) checkCancellation()
            for (item in selected) {
                repeat(if (count == 1) 1 else item.component.horizontal * item.component.vertical) {
                    block(bits, item)
                }
            }
            mcu++
            if (restartInterval != 0 && mcu < total && mcu % restartInterval == 0L) {
                bits.finishByte()
                photoValid(marker() == 0xd0 + restart)
                restart = (restart + 1) and 7
            }
        }
        bits.finishByte()
        // Do not consume a following marker here. The outer parser verifies it and exact EOI.
        photoValid(pos < b.size && unsigned(b[pos]) == 0xff)
        selected.forEach { scanned += it.component.id }
    }

    private fun block(bits: Entropy, item: ScanComponent) {
        bits.skip(item.dc.symbol(bits))
        var coefficient = 1
        while (coefficient < 64) {
            val symbol = item.ac.symbol(bits)
            if (symbol == 0) return
            if (symbol == 0xf0) {
                coefficient += 16
                photoValid(coefficient <= 64)
            } else {
                coefficient += symbol shr 4
                photoValid(coefficient < 64)
                bits.skip(symbol and 15)
                coefficient++
            }
        }
    }

    private class Huffman(counts: IntArray, private val values: IntArray) {
        private val minimum = IntArray(17)
        private val maximum = IntArray(17) { -1 }
        private val offset = IntArray(17)
        init {
            var code = 0; var index = 0
            for (length in 1..16) {
                val count = counts[length - 1]
                // Never allocate the all-ones word: it is reserved for end-of-segment padding.
                photoValid(code + count < (1 shl length))
                if (count != 0) {
                    minimum[length] = code; maximum[length] = code + count - 1; offset[length] = index
                }
                code = (code + count) shl 1
                index += count
            }
        }
        fun symbol(bits: BaselineJpegAdmission.Entropy): Int {
            var code = 0
            for (length in 1..16) {
                code = (code shl 1) or bits.bit()
                if (code >= minimum[length] && code <= maximum[length]) return values[offset[length] + code - minimum[length]]
            }
            return malformed()
        }
    }

    private inner class Entropy {
        private var remaining = 0
        private var current = 0
        fun bit(): Int {
            if (remaining == 0) {
                current = byte()
                if (current == 0xff) photoValid(byte() == 0) // markers are not zero-filled data
                remaining = 8
            }
            remaining--
            return (current shr remaining) and 1
        }
        fun skip(count: Int) { repeat(count) { bit() } }
        fun finishByte() {
            val mask = (1 shl remaining) - 1
            photoValid(current and mask == mask)
            remaining = 0
        }
    }

    private fun segment(block: (Int) -> Unit) {
        val length = short()
        photoValid(length >= 2 && length - 2 <= b.size - pos)
        val end = pos + length - 2
        block(end)
        // SOS owns its following entropy, other segments must consume exactly their payload.
        photoValid(pos >= end)
    }
    private fun marker(): Int {
        photoValid(byte() == 0xff)
        var result = byte()
        while (result == 0xff) { checkCancellation(); result = byte() }
        photoValid(result != 0)
        return result
    }
    private fun byte(): Int { photoValid(pos < b.size); return unsigned(b[pos++]) }
    private fun short() = (byte() shl 8) or byte()
    private fun ceilDivide(n: Int, d: Int) = (n + d - 1) / d
    private fun unsigned(v: Byte) = v.toInt() and 255
}

private fun malformed(): Nothing = throw PhotoValidationException(ContainerFailure.MALFORMED)
