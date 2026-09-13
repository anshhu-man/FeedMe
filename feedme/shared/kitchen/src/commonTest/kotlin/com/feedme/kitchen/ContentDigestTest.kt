package com.feedme.kitchen

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class ContentDigestTest {
    @Test
    fun emptyInputMatchesSha256Vector() {
        assertDigest(
            byteArrayOf(),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
    }

    @Test
    fun abcMatchesSha256Vector() {
        assertDigest(
            "abc".encodeToByteArray(),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )
    }

    @Test
    fun nonAsciiUtf8MatchesSha256Vector() {
        // The explicit code points are "Café 🍲 東京"; encodeToByteArray uses UTF-8.
        assertDigest(
            "Caf\u00e9 \uD83C\uDF72 \u6771\u4EAC".encodeToByteArray(),
            "480f361bf9513e7fe8d3963b0e3ec0797a46cf5a80678997f2e5ea5510a6f673",
        )
    }

    @Test
    fun exactlyOneMebibyteMatchesSha256Vector() {
        assertDigest(
            ByteArray(1024 * 1024) { 0x61.toByte() },
            "9bc1b2a288b26af7257a36277ae3816a7d4f16e89c1e7e77d0a5c48bad62b360",
        )
    }

    @Test
    fun inputAndEveryReturnedDigestAreDetached() {
        // A 32-byte input also catches implementations that return the input array.
        val input = ByteArray(32) { it.toByte() }
        val originalInput = input.copyOf()
        val first = contentSha256(input)
        val expectedDigest = first.copyOf()
        val second = contentSha256(input)

        assertEquals(32, first.size)
        assertNotSame(input, first)
        assertNotSame(input, second)
        assertNotSame(first, second)
        assertContentEquals(originalInput, input)
        assertContentEquals(expectedDigest, second)

        first[0] = (first[0].toInt() xor 0xff).toByte()
        assertContentEquals(originalInput, input, "Mutating a digest must not change its input")
        assertContentEquals(expectedDigest, second, "Digests must not share mutable storage")

        val third = contentSha256(input)
        assertNotSame(first, third)
        assertNotSame(second, third)
        assertContentEquals(expectedDigest, third, "A previous result must not poison future hashes")
        assertContentEquals(expectedDigest, second)

        input.fill(0)
        assertContentEquals(expectedDigest, second, "Changing the input must not change a completed digest")
        assertContentEquals(expectedDigest, third)
    }

    private fun assertDigest(input: ByteArray, expectedHex: String) {
        val originalInput = input.copyOf()
        val actual = contentSha256(input)
        assertEquals(32, actual.size)
        assertEquals(expectedHex, actual.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
        assertContentEquals(originalInput, input, "Hashing must not mutate its input")
    }
}
