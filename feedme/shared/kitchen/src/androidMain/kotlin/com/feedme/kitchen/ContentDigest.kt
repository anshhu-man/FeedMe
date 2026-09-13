package com.feedme.kitchen

import java.security.MessageDigest

internal actual fun contentSha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes).also {
        check(it.size == 32) { "Content digest unavailable" }
    }
