package com.feedme.server.media

import java.time.Instant

/**
 * Mandatory bounded LOCAL signer, without remote calls or accepting production fallback.
 * Sign exactly the supplied private key/byte/type/checksum constraints and expiry. A POST grants
 * quarantine upload only, never public access/ACL. Provider enforcement must be independently tested.
 * Called under the current authority/lifecycle transaction immediately before response construction.
 * Already issued POSTs can remain usable until expiry; deletion retains cleanup through that deadline.
 */
fun interface MediaUploadCapabilities { fun sign(authorization: MediaUploadAuthorization): MediaUploadCapability }

class MediaUploadCapability(val url: String, fields: Map<String, String>, val expiresAt: Instant) {
    val fields = fields.toMap()
    override fun toString() = "MediaUploadCapability(<redacted>)"
}
