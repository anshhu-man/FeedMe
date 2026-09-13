package com.feedme.storage

/** Fixed canonical binary format. Structural decoding is not install-MAC authentication. */
internal object StateActivationPlanCodec {
    const val UNSIGNED_SIZE = 138
    private const val HEX = "0123456789abcdef"

    fun encode(record: StateActivationPlanRecord): StateActivationPlan {
        val unsigned = encodeUnsigned(record)
        val bytes = ByteArray(StateActivationPlan.ENCODED_SIZE)
        return try {
            unsigned.copyInto(bytes)
            record.authenticationMac.copyInto(bytes, UNSIGNED_SIZE)
            StateActivationPlan(bytes)
        } finally { unsigned.fill(0); bytes.fill(0) }
    }

    fun encodeUnsigned(record: StateActivationPlanRecord): ByteArray {
        validate(record)
        val bytes = ByteArray(UNSIGNED_SIZE)
        bytes[0] = 1
        bytes[1] = if (record.priorKeyId == null) 0 else 1
        hexInto(record.ownerTag, bytes, 2)
        for (index in 0..7) bytes[66 + index] = (record.priorGeneration ushr (56 - index * 8)).toByte()
        record.priorKeyId?.let { hexInto(it, bytes, 74) } // Absent predecessor uses 32 zero bytes.
        hexInto(record.keyId, bytes, 106)
        return bytes
    }

    fun decode(plan: StateActivationPlan): StateActivationPlanRecord {
        val bytes = plan.copyForStorage()
        try {
            if (bytes[0] != 1.toByte() || bytes[1] !in byteArrayOf(0, 1) || bytes[66] < 0) invalid()
            val owner = hexAt(bytes, 2, 64)
            var generation = 0L
            for (index in 66..73) generation = (generation shl 8) or (bytes[index].toLong() and 255L)
            val prior = if (bytes[1] == 0.toByte()) {
                if (generation != 0L || (74..105).any { bytes[it] != 0.toByte() }) invalid()
                null
            } else {
                if (generation == 0L) invalid()
                hexAt(bytes, 74, 32)
            }
            return StateActivationPlanRecord(owner, generation, prior, hexAt(bytes, 106, 32),
                bytes.copyOfRange(UNSIGNED_SIZE, StateActivationPlan.ENCODED_SIZE)).also(::validate)
        } finally { bytes.fill(0) }
    }

    private fun validate(record: StateActivationPlanRecord) {
        if (!hex(record.ownerTag, 64) || !hex(record.keyId, 32) || record.authenticationMac.size != 32 ||
            record.priorGeneration !in 0..Long.MAX_VALUE - 2 ||
            (record.priorGeneration == 0L) != (record.priorKeyId == null)) invalid()
        record.priorKeyId?.let { if (!hex(it, 32) || it == record.keyId) invalid() }
    }

    private fun hex(value: String, size: Int) = value.length == size && value.all { it in HEX }
    private fun hexInto(value: String, bytes: ByteArray, offset: Int) {
        value.forEachIndexed { index, character -> bytes[offset + index] = character.code.toByte() }
    }
    private fun hexAt(bytes: ByteArray, offset: Int, size: Int): String = buildString(size) {
        for (index in offset until offset + size) {
            val character = (bytes[index].toInt() and 255).toChar()
            if (character !in HEX) invalid()
            append(character)
        }
    }
    private fun invalid(): Nothing = throw StateActivationPlanFormatException()
}
