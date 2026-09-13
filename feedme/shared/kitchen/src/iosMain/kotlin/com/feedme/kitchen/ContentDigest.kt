package com.feedme.kitchen

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/** Native CommonCrypto binding; iOS compilation and execution require full Xcode validation. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun contentSha256(bytes: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    // Empty arrays have no addressOf(0); a dummy byte supplies an address with a zero input length.
    val addressableInput = if (bytes.isEmpty()) ByteArray(1) else bytes
    addressableInput.usePinned { input ->
        digest.usePinned { output ->
            check(CC_SHA256(input.addressOf(0), bytes.size.convert(), output.addressOf(0).reinterpret()) != null) {
                "Content digest unavailable"
            }
        }
    }
    return digest
}
