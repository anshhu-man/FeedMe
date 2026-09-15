package com.feedme.kitchen

import com.feedme.core.ports.*
import kotlinx.serialization.json.*

/** Detached private metadata observation, never an acknowledged scheduler receipt/capability. */
class CookingTimerMetadata(val record: PrivateRecord, val matchesTimers: Boolean) {
    override fun toString() = "CookingTimerMetadata(matchesTimers=$matchesTimers, private=<redacted>)"
}
internal object CookingTimerMetadataCodec {
    class Envelope(val origin: String, val timerHash: String, val payload: PrivateBytes)
    fun encode(origin: String, timerHash: String, payload: PrivateBytes): PrivateBytes {
        val copy = payload.copyForCodec()
        try {
            if (copy.size !in 1..16000) PrivateJson.invalid()
            return PrivateJson.encode(buildJsonObject {
                put("version", 1); put("origin", normalizedId(origin)); put("timerHash", timerHash)
                put("payload", copy.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
            }).also(::decode)
        } finally { copy.fill(0) }
    }
    fun decode(bytes: PrivateBytes): Envelope {
        val root = PrivateJson.decode(bytes, setOf("version", "origin", "timerHash", "payload"))
        if (PrivateJson.long(root.getValue("version")) != 1L) PrivateJson.invalid()
        val hex = PrivateJson.string(root.getValue("payload"))
        if (hex.length !in 2..32000 || hex.length % 2 != 0 || hex.any { it !in "0123456789abcdef" }) PrivateJson.invalid()
        val copy = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return try { Envelope(PrivateJson.uuid(root.getValue("origin")), PrivateJson.hash(root.getValue("timerHash")), PrivateBytes(copy)) }
        finally { copy.fill(0) }
    }
}
