package com.feedme.server.social

import java.net.URI
import java.util.UUID
import kotlin.test.*

class CircleCapabilitiesTest {
    @Test fun allProducedEventNamesAreRegisteredInTheCanonicalEventCatalog() {
        val catalog = checkNotNull(javaClass.getResourceAsStream("/canonical-events.md")).use { it.readBytes().toString(Charsets.UTF_8) }
        for (name in listOf("circles.circle.changed.v1", "circles.membership.changed.v1", "circles.invitation.changed.v1", "circles.ownership.transferred.v1"))
            assertTrue(catalog.contains(name), "Produced event must be registered")
    }
    @Test fun configuredKeysAreRequiredAndNoHostOrSecretIsInvented() {
        assertFailsWith<IllegalArgumentException> { CircleCapabilities("v1", emptyMap(), ENDPOINT) }
        for (size in listOf(0, 31, 33)) assertFailsWith<IllegalArgumentException> { CircleCapabilities("v1", mapOf("v1" to ByteArray(size)), ENDPOINT) }
        assertFailsWith<IllegalArgumentException> { CircleCapabilities("missing", mapOf("v1" to KEY), ENDPOINT) }
    }
    @Test fun insecureOrAmbiguousInvitationEndpointsAreRejected() {
        for (uri in listOf("http://example.invalid/invite", "https://user@example.invalid/invite", "https://example.invalid/invite?existing=1", "https://example.invalid/invite#fragment"))
            assertFailsWith<IllegalArgumentException> { CircleCapabilities("v1", mapOf("v1" to KEY), URI(uri)) }
    }
    @Test fun invitationProofIsDeterministicBoundToEnvironmentIdAndKeyAndNeverPrinted() {
        val codec = codec(); val id = UUID.randomUUID(); val token = codec.invite("test", id, "v1")
        assertEquals(token, codec.invite("test", id, "v1")); assertNotEquals(token, codec.invite("other", id, "v1"))
        assertNotEquals(token, codec.invite("test", UUID.randomUUID(), "v1")); assertEquals(64, codec.tokenHash(token).length)
        assertFalse(codec.toString().contains(token)); assertFalse(codec.toString().contains("example.invalid"))
    }
    @Test fun configuredKeyBytesAndMapAreDetachedAndOldKeyIdsRemainUsableForReplay() {
        val bytes = KEY.copyOf(); val keys = mutableMapOf("v1" to bytes)
        val codec = CircleCapabilities("v1", keys, ENDPOINT); val id = UUID.randomUUID(); val original = codec.invite("test", id, "v1")
        bytes.fill(0); keys.clear(); assertEquals(original, codec.invite("test", id, "v1"))
        val rotated = CircleCapabilities("v2", mapOf("v1" to KEY, "v2" to ByteArray(32) { 3 }), ENDPOINT)
        assertEquals(original, rotated.invite("test", id, "v1")); assertNotEquals(original, rotated.invite("test", id, "v2"))
        assertEquals(SocialFailureCode.NOT_CONFIGURED, assertFailsWith<SocialFailure> { rotated.invite("test", id, "lost") }.code)
    }
    @Test fun cursorsBindPrincipalEnvironmentCollectionAndCircleRatherThanGrantingAccess() {
        val codec = codec(); val actor = actor(); val circle = UUID.randomUUID(); val last = UUID.randomUUID()
        val cursor = codec.cursor(actor, "members", circle, last)
        assertEquals(last, codec.parseCursor(actor, "members", circle, cursor)); assertNull(codec.parseCursor(actor, "members", circle, null))
        for ((other, kind, target) in listOf(Triple(actor(), "members", circle), Triple(actor, "circles", circle),
            Triple(actor, "members", UUID.randomUUID()), Triple(VerifiedSocialAccount("other", actor.accountId, actor.deviceSessionId), "members", circle)))
            assertEquals(SocialFailureCode.INPUT_INVALID, assertFailsWith<SocialFailure> { codec.parseCursor(other, kind, target, cursor) }.code)
    }
    @Test fun cursorTamperingMalformedAndUnboundedInputNeverFallsBackToFirstPage() {
        val codec = codec(); val actor = actor(); val original = codec.cursor(actor, "circles", null, UUID.randomUUID())
        for (value in listOf("", "x".repeat(2049), "$original.extra", original.dropLast(1) + "!", "lost." + original.substringAfter('.')))
            assertEquals(SocialFailureCode.INPUT_INVALID, assertFailsWith<SocialFailure> { codec.parseCursor(actor, "circles", null, value) }.code)
    }
    @Test fun invitationHashRejectsUnboundedOrControlBearingTokensWithoutEchoingThem() {
        val codec = codec()
        for (value in listOf("short-private-value", "a".repeat(513), "a".repeat(32) + "\n", "a".repeat(32) + "?")) {
            val failed = assertFailsWith<SocialFailure> { codec.tokenHash(value) }
            assertEquals(SocialFailureCode.INPUT_INVALID, failed.code); assertFalse(failed.toString().contains(value))
        }
    }
    @Test fun policyBoundsAreExplicitAndPrincipalDiagnosticsRemainRedacted() {
        for (limit in listOf(0, 1, 51)) assertFailsWith<IllegalArgumentException> { CircleLaunchPolicy(limit, 168) }
        for (hours in listOf(0, 169)) assertFailsWith<IllegalArgumentException> { CircleLaunchPolicy(50, hours) }
        val actor = actor(); assertFalse(actor.toString().contains(actor.accountId.toString()))
        assertFalse(actor.toString().contains(actor.deviceSessionId.toString()))
        assertFalse(SocialProfileSummary(actor.accountId, "private-label", "private-handle").toString().contains("private-label"))
    }
    companion object {
        private val KEY = ByteArray(32) { (it + 1).toByte() }
        private val ENDPOINT = URI("https://example.invalid/invite")
        private fun codec() = CircleCapabilities("v1", mapOf("v1" to KEY), ENDPOINT)
        private fun actor() = VerifiedSocialAccount("test", UUID.randomUUID(), UUID.randomUUID())
    }
}
