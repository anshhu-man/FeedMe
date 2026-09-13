package com.feedme.kitchen

/**
 * SHA-256 of the exact supplied bytes, with a fresh 32-byte result and no input mutation.
 * Local content integrity only: this does not authenticate a recipe or establish its review,
 * recall status, provenance, or the still-missing server bundle/contentHash contract.
 */
internal expect fun contentSha256(bytes: ByteArray): ByteArray
