package com.feedme.server.http

import com.feedme.server.guest.GuestTokenMaterial
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.utils.io.ByteReadChannel

/** Guest-only framing. A canonical token shape is not proof of a current identity. */
internal class GuestCurrentSessionHttpInput private constructor(private val token: String) {
    fun <T> withToken(action: (String) -> T): T = action(token)
    override fun toString() = "GuestCurrentSessionHttpInput(<redacted>)"

    suspend fun requireEmptyBody(channel: ByteReadChannel) {
        readBoundedHttpBody(channel, 0, 0L, ::invalid).fill(0)
    }

    companion object {
        fun parse(headers: Headers, query: Parameters): GuestCurrentSessionHttpInput {
            if (query.names().isNotEmpty()) invalid()
            rejectGuestSearchUpgrade(headers)
            fun header(name: String): String? = headers.getAll(name)?.let { values ->
                if (values.size != 1 || values.single().any(Char::isISOControl)) invalid()
                values.single()
            }
            for (name in listOf("X-Device-Session", "Idempotency-Key", HttpHeaders.IfMatch, HttpHeaders.IfNoneMatch,
                HttpHeaders.IfModifiedSince, HttpHeaders.IfUnmodifiedSince, HttpHeaders.Range, HttpHeaders.IfRange,
                HttpHeaders.ContentType, HttpHeaders.ContentEncoding, HttpHeaders.TransferEncoding))
                if (header(name) != null) invalid()
            if (header(HttpHeaders.ContentLength)?.let { it != "0" } == true) invalid()
            val authorization = header(HttpHeaders.Authorization) ?: unauthenticated()
            if (',' in authorization) invalid()
            if (!authorization.startsWith("Bearer ", ignoreCase = true)) unauthenticated()
            val token = authorization.substring(7)
            try { GuestTokenMaterial.parse(token) }
            catch (_: IllegalArgumentException) { unauthenticated() }
            return GuestCurrentSessionHttpInput(token)
        }
        private fun invalid(): Nothing = throw GuestHttpFailure(400, "INVALID_REQUEST")
        private fun unauthenticated(): Nothing = throw GuestHttpFailure(401, "UNAUTHENTICATED")
    }
}
