package com.feedme.storage

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import kotlin.test.*

/** Pure structural decoding: no database, native vault, cryptographic proof or session lease. */
class StateActivationPlanStorageCodecTest {
    @Test fun absentAndRetiredPredecessorsRoundTripWithoutChangingCanonicalBytes() {
        for (record in listOf(record(), record(42, OLD_KEY))) {
            val encoded = StateActivationPlanCodec.encode(record).copyForStorage()
            val plan = accepted(encoded)
            assertContentEquals(encoded, plan.copyForStorage())
            val decoded = StateActivationPlanCodec.decode(plan)
            assertEquals(record.ownerTag, decoded.ownerTag)
            assertEquals(record.priorGeneration, decoded.priorGeneration)
            assertEquals(record.priorKeyId, decoded.priorKeyId)
            assertEquals(record.keyId, decoded.keyId)
            assertContentEquals(record.authenticationMac, decoded.authenticationMac)
        }
    }

    @Test fun exactLengthIsRequiredBeforeStructuralDecoding() {
        val valid = encoded()
        for (size in listOf(0, 1, 137, 169, 171, 4096, 32_768)) {
            val bytes = valid.copyOf(size)
            rejected(bytes)
        }
    }

    @Test fun zeroVersionUnknownVersionAndUnknownPriorFlagsAreRejected() {
        for (value in listOf(0, 2, 127, 128, 255)) changed(0, value)
        for (value in listOf(2, 127, 128, 255)) changed(1, value)
        rejected(ByteArray(StateActivationPlan.ENCODED_SIZE))
    }

    @Test fun absentPredecessorRequiresZeroGenerationAndBinaryZeroPadding() {
        for (index in 66..105) changed(index, 1)
        val noncanonical = encoded()
        for (index in 74..105) noncanonical[index] = '0'.code.toByte()
        rejected(noncanonical)
        val presentFlagWithoutPredecessor = encoded()
        presentFlagWithoutPredecessor[1] = 1
        rejected(presentFlagWithoutPredecessor)
    }

    @Test fun retiredPredecessorRequiresPositiveGenerationAndItsExactLowercaseKey() {
        val zeroGeneration = encoded(1, OLD_KEY)
        for (index in 66..73) zeroGeneration[index] = 0
        rejected(zeroGeneration)
        for (index in 74..105) for (value in listOf(0, 'B'.code, 'g'.code, 255)) {
            val bytes = encoded(1, OLD_KEY)
            bytes[index] = value.toByte()
            rejected(bytes)
        }
        val sameKey = encoded(1, OLD_KEY)
        KEY.encodeToByteArray().copyInto(sameKey, 74)
        rejected(sameKey)
    }

    @Test fun negativeAndOverflowingGenerationsCannotConsumeUnreservedRevisions() {
        for (generation in listOf(Long.MIN_VALUE, -1L, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
            val bytes = encoded(1, OLD_KEY)
            for (index in 0..7) bytes[66 + index] = (generation ushr (56 - index * 8)).toByte()
            rejected(bytes)
        }
    }

    @Test fun everySupportedGenerationBoundaryRetainsBothFutureRevisions() {
        for (generation in listOf(1L, 255L, 256L, 65_536L, 0x0102030405060708L, Long.MAX_VALUE - 2)) {
            val bytes = encoded(generation, OLD_KEY)
            val plan = accepted(bytes)
            val record = StateActivationPlanCodec.decode(plan)
            assertEquals(generation + 1, record.selectedGeneration)
            assertEquals(generation + 2, record.consumedGeneration)
            assertContentEquals(bytes, plan.copyForStorage())
        }
    }

    @Test fun ownerAndCandidateFieldsRejectNoncanonicalCaseWhitespaceAndNonAscii() {
        for (index in listOf(2, 65, 106, 137)) {
            for (value in listOf(0, 32, 'A'.code, 'G'.code, 128, 255)) changed(index, value)
        }
    }

    @Test fun arbitraryMacBytesRemainStructuralDataAndAreNeverAuthenticatedHere() {
        for (mac in listOf(ByteArray(32), ByteArray(32) { 255.toByte() }, ByteArray(32) { it.toByte() })) {
            val bytes = StateActivationPlanCodec.encode(record().copy(authenticationMac = mac)).copyForStorage()
            val plan = accepted(bytes)
            assertContentEquals(bytes, plan.copyForStorage())
            assertContentEquals(mac, StateActivationPlanCodec.decode(plan).authenticationMac)
        }
    }

    @Test fun inputAndAllReturnedCopiesAreDetachedWithoutMutatingCallerBytes() {
        val input = encoded(4, OLD_KEY)
        val expected = input.copyOf()
        val plan = accepted(input)
        assertContentEquals(expected, input)
        input.fill(0)
        val first = plan.copyForStorage()
        first.fill(1)
        assertContentEquals(expected, plan.copyForStorage())
        val second = accepted(plan.copyForStorage())
        second.copyForStorage().fill(2)
        assertContentEquals(expected, plan.copyForStorage())
        assertContentEquals(expected, second.copyForStorage())
    }

    @Test fun failureIsTypedRedactedAndLeavesInvalidCallerBufferUnchanged() {
        val input = encoded()
        "private-canary".encodeToByteArray().copyInto(input, 2)
        val before = input.copyOf()
        val failure = assertIs<PortResult.Failure>(StateActivationPlan.fromStorage(input))
        assertEquals(FailureReason.INVALID_DATA, failure.reason)
        assertFalse(failure.toString().contains("private-canary"))
        assertContentEquals(before, input)
        assertEquals("StateActivationPlan(<redacted>)", accepted(encoded()).toString())
    }

    @Test fun legacyConstructorRemainsLengthOnlyWhileStorageFactoryRejectsItsInvalidPayload() {
        val invalid = ByteArray(StateActivationPlan.ENCODED_SIZE)
        val legacy = StateActivationPlan(invalid)
        assertContentEquals(invalid, legacy.copyForStorage())
        rejected(legacy.copyForStorage())
        assertFailsWith<IllegalArgumentException> { StateActivationPlan(ByteArray(169)) }
    }

    private fun accepted(bytes: ByteArray): StateActivationPlan =
        assertIs<PortResult.Value<StateActivationPlan>>(StateActivationPlan.fromStorage(bytes)).value

    private fun rejected(bytes: ByteArray) {
        val before = bytes.copyOf()
        assertEquals(FailureReason.INVALID_DATA,
            assertIs<PortResult.Failure>(StateActivationPlan.fromStorage(bytes)).reason)
        assertContentEquals(before, bytes)
    }

    private fun changed(index: Int, value: Int) {
        val bytes = encoded()
        bytes[index] = value.toByte()
        rejected(bytes)
    }

    private fun encoded(generation: Long = 0, priorKeyId: String? = null) =
        StateActivationPlanCodec.encode(record(generation, priorKeyId)).copyForStorage()

    private fun record(generation: Long = 0, priorKeyId: String? = null) =
        StateActivationPlanRecord("a".repeat(64), generation, priorKeyId, KEY, ByteArray(32) { it.toByte() })

    companion object {
        private val KEY = "c".repeat(32)
        private val OLD_KEY = "b".repeat(32)
    }
}
