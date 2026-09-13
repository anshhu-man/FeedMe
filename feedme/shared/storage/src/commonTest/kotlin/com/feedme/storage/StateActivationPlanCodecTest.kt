package com.feedme.storage

import kotlin.test.*

class StateActivationPlanCodecTest {
    @Test fun absentPredecessorHasOneCanonicalFixedEncoding() {
        val original = record()
        val plan = StateActivationPlanCodec.encode(original)
        val bytes = plan.copyForStorage()
        assertEquals(170, bytes.size)
        assertEquals(1.toByte(), bytes[0]); assertEquals(0.toByte(), bytes[1])
        assertTrue((66..105).all { bytes[it] == 0.toByte() })
        assertRecord(original, StateActivationPlanCodec.decode(plan))
        assertEquals(1L, StateActivationPlanCodec.decode(plan).selectedGeneration)
        assertEquals(2L, StateActivationPlanCodec.decode(plan).consumedGeneration)
    }

    @Test fun retiredPredecessorBindsBothGenerationAndExactOldKey() {
        val original = record(priorGeneration = 42, priorKeyId = OLD_KEY)
        val plan = StateActivationPlanCodec.encode(original)
        assertEquals(1.toByte(), plan.copyForStorage()[1])
        val decoded = StateActivationPlanCodec.decode(plan)
        assertRecord(original, decoded)
        assertEquals(43L, decoded.selectedGeneration); assertEquals(44L, decoded.consumedGeneration)
        assertFalse(plan.copyForStorage().contentEquals(StateActivationPlanCodec.encode(original.copy(priorKeyId = "d".repeat(32))).copyForStorage()))
    }

    @Test fun generationEncodingIsBigEndianAndReservesTwoIncrements() {
        for (generation in listOf(1L, 255L, 256L, 65_536L, 0x0102030405060708L, Long.MAX_VALUE - 2)) {
            val plan = StateActivationPlanCodec.encode(record(generation, OLD_KEY))
            val bytes = plan.copyForStorage()
            for (index in 0..7) assertEquals((generation ushr (56 - index * 8)).toByte(), bytes[66 + index])
            val decoded = StateActivationPlanCodec.decode(plan)
            assertEquals(generation, decoded.priorGeneration)
            assertEquals(generation + 1, decoded.selectedGeneration)
            assertEquals(generation + 2, decoded.consumedGeneration)
        }
    }

    @Test fun publicCapabilityDetachesConstructorAndEveryReturnedCopy() {
        val original = StateActivationPlanCodec.encode(record()).copyForStorage()
        val input = original.copyOf(); val plan = StateActivationPlan(input)
        input.fill(99)
        val first = plan.copyForStorage(); first.fill(88)
        assertContentEquals(original, plan.copyForStorage())
        assertRecord(record(), StateActivationPlanCodec.decode(plan))
    }

    @Test fun publicConstructorOnlyBoundsAndNeverClaimsAuthentication() {
        val structurallyInvalid = StateActivationPlan(ByteArray(170))
        malformed { StateActivationPlanCodec.decode(structurallyInvalid) }
        val forgedMac = record().copy(authenticationMac = ByteArray(32) { 0x5a })
        assertContentEquals(forgedMac.authenticationMac,
            StateActivationPlanCodec.decode(StateActivationPlanCodec.encode(forgedMac)).authenticationMac)
        // Only the owning database can distinguish an authentic MAC from these structural bytes.
    }

    @Test fun allNonExactCapabilityLengthsAreRejectedWithoutPayloadEcho() {
        for (size in listOf(0, 1, 137, 169, 171, 4096)) {
            val failure = assertFailsWith<IllegalArgumentException> { StateActivationPlan(ByteArray(size) { 0x41 }) }
            assertEquals("Invalid activation plan", failure.message)
        }
    }

    @Test fun unknownVersionOrPriorKindCannotBeDecodedAsCurrentFormat() {
        for (index in listOf(0, 1)) for (value in listOf(2, 127, 128, 255)) badByte(index, value)
        badByte(0, 0)
    }

    @Test fun absentPriorRequiresZeroGenerationAndCanonicalZeroKeyPadding() {
        for (index in 66..105) badByte(index, 1)
        val asciiZero = StateActivationPlanCodec.encode(record()).copyForStorage()
        for (index in 74..105) asciiZero[index] = '0'.code.toByte()
        malformed { StateActivationPlanCodec.decode(StateActivationPlan(asciiZero)) }
    }

    @Test fun retiredPriorRequiresPositiveGenerationAndValidKeyBytes() {
        val emptyGeneration = StateActivationPlanCodec.encode(record(1, OLD_KEY)).copyForStorage()
        for (index in 66..73) emptyGeneration[index] = 0
        malformed { StateActivationPlanCodec.decode(StateActivationPlan(emptyGeneration)) }
        for (index in 74..105) {
            val bytes = StateActivationPlanCodec.encode(record(2, OLD_KEY)).copyForStorage()
            bytes[index] = 0
            malformed { StateActivationPlanCodec.decode(StateActivationPlan(bytes)) }
        }
    }

    @Test fun nonLowercaseHexAndNonAsciiFieldsCannotAliasCanonicalIdentity() {
        for (index in listOf(2, 65, 106, 137)) for (value in listOf(0, 32, 65, 71, 128, 255)) badByte(index, value)
        val bytes = StateActivationPlanCodec.encode(record(2, OLD_KEY)).copyForStorage()
        bytes[74] = 'A'.code.toByte()
        malformed { StateActivationPlanCodec.decode(StateActivationPlan(bytes)) }
    }

    @Test fun signedNegativeAndUnrepresentableGenerationsAreRejected() {
        for (generation in listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
            val bytes = StateActivationPlanCodec.encode(record(2, OLD_KEY)).copyForStorage()
            for (index in 0..7) bytes[66 + index] = (generation ushr (56 - index * 8)).toByte()
            malformed { StateActivationPlanCodec.decode(StateActivationPlan(bytes)) }
            malformed { StateActivationPlanCodec.encode(record(generation, OLD_KEY)) }
        }
    }

    @Test fun encoderRejectsInvalidRecordFieldsRatherThanTruncatingOrNormalizing() {
        val original = record()
        val malformedRecords = listOf(original.copy(ownerTag = "a".repeat(63)), original.copy(ownerTag = "A".repeat(64)),
            original.copy(ownerTag = "a".repeat(63) + 0xD800.toChar()), original.copy(keyId = "c".repeat(33)),
            original.copy(keyId = "G".repeat(32)), original.copy(priorGeneration = 1), original.copy(priorKeyId = OLD_KEY),
            original.copy(priorGeneration = 1, priorKeyId = KEY), original.copy(priorGeneration = 1, priorKeyId = "x"),
            original.copy(authenticationMac = ByteArray(31)), original.copy(authenticationMac = ByteArray(33)))
        for (candidate in malformedRecords) {
            malformed { StateActivationPlanCodec.encode(candidate) }
            malformed { StateActivationPlanCodec.encodeUnsigned(candidate) }
        }
    }

    @Test fun unsignedEncodingExcludesMacAndContainsEveryOtherBoundField() {
        val original = record(4, OLD_KEY)
        val unsigned = StateActivationPlanCodec.encodeUnsigned(original)
        assertEquals(138, unsigned.size)
        assertContentEquals(unsigned, StateActivationPlanCodec.encode(original).copyForStorage().copyOfRange(0, 138))
        assertContentEquals(unsigned, StateActivationPlanCodec.encodeUnsigned(original.copy(authenticationMac = ByteArray(32))))
        for (changed in listOf(original.copy(ownerTag = "d".repeat(64)), original.copy(priorGeneration = 5),
            original.copy(priorKeyId = "d".repeat(32)), original.copy(keyId = "e".repeat(32))))
            assertFalse(unsigned.contentEquals(StateActivationPlanCodec.encodeUnsigned(changed)))
    }

    @Test fun encodeAndDecodeDetachMacBuffersWithoutMutatingTheCaller() {
        val original = record(); val before = original.authenticationMac.copyOf()
        val plan = StateActivationPlanCodec.encode(original)
        assertContentEquals(before, original.authenticationMac)
        original.authenticationMac.fill(0)
        val decoded = StateActivationPlanCodec.decode(plan)
        assertContentEquals(before, decoded.authenticationMac)
        decoded.authenticationMac.fill(1)
        assertContentEquals(before, StateActivationPlanCodec.decode(plan).authenticationMac)
    }

    @Test fun repeatedCanonicalEncodingIsDeterministicAndRoundTripsExactly() {
        for (original in listOf(record(), record(24, OLD_KEY))) {
            val expected = StateActivationPlanCodec.encode(original).copyForStorage()
            repeat(5) {
                val decoded = StateActivationPlanCodec.decode(StateActivationPlan(expected))
                assertContentEquals(expected, StateActivationPlanCodec.encode(decoded).copyForStorage())
                assertContentEquals(expected, StateActivationPlanCodec.encode(original).copyForStorage())
            }
        }
    }

    @Test fun capabilitiesAndFormatFailuresAreRedactedAndDistinctFromRetirement() {
        val original = record(2, OLD_KEY); val plan = StateActivationPlanCodec.encode(original)
        assertEquals("StateActivationPlan(<redacted>)", plan.toString())
        assertEquals("StateActivationPlanRecord(<redacted>)", original.toString())
        assertFailsWith<IllegalArgumentException> { StateRetirementTarget(plan.copyForStorage()) }
        malformed { StateActivationPlanCodec.encode(original.copy(ownerTag = "private-owner-canary")) }
    }

    private fun badByte(index: Int, value: Int) {
        val bytes = StateActivationPlanCodec.encode(record()).copyForStorage()
        bytes[index] = value.toByte()
        malformed { StateActivationPlanCodec.decode(StateActivationPlan(bytes)) }
    }
    private fun malformed(block: () -> Any?) {
        val error = assertFailsWith<StateActivationPlanFormatException> { block() }
        assertEquals("Invalid activation plan", error.message); assertNull(error.cause)
    }
    private fun assertRecord(expected: StateActivationPlanRecord, actual: StateActivationPlanRecord) {
        assertEquals(expected.ownerTag, actual.ownerTag); assertEquals(expected.priorGeneration, actual.priorGeneration)
        assertEquals(expected.priorKeyId, actual.priorKeyId); assertEquals(expected.keyId, actual.keyId)
        assertContentEquals(expected.authenticationMac, actual.authenticationMac)
    }
    private fun record(priorGeneration: Long = 0, priorKeyId: String? = null) =
        StateActivationPlanRecord("a".repeat(64), priorGeneration, priorKeyId, KEY, ByteArray(32) { it.toByte() })

    companion object {
        private val KEY = "c".repeat(32)
        private val OLD_KEY = "b".repeat(32)
    }
}
