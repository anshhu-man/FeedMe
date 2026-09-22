package com.feedme.server.media

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Existing photo-delete manifest encoding, shared without changing historical bytes.
 * This is pure representation code, never object ownership or permission to erase. */
internal object MediaCleanupManifest {
    fun canonical(mediaId: UUID, quarantineKey: String, knownVersionId: String?,
        protocol: String, bucket: String?, sha256: String, derivatives: JsonObject?,
        finalSweepAfter: Instant): String = canonicalJson(buildJsonObject {
        put("mediaId", mediaId.toString()); put("quarantineKey", quarantineKey)
        put("knownVersionId", knownVersionId?.let(::JsonPrimitive) ?: JsonNull)
        if (protocol == SUPABASE_MEDIA_PROTOCOL) {
            put("protocol", protocol); put("bucket", checkNotNull(bucket)); put("sha256", sha256)
        }
        put("derivatives", derivatives ?: JsonNull); put("finalSweepAfter", finalSweepAfter.toString())
    })

    fun hash(canonical: String): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun canonicalJson(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (key, child) ->
            "${JsonPrimitive(key)}:${canonicalJson(child)}"
        }
        is JsonArray -> value.joinToString(",", "[", "]") { canonicalJson(it) }
        is JsonPrimitive -> value.toString()
    }
}
