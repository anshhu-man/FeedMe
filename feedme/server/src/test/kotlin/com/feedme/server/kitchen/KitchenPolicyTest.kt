package com.feedme.server.kitchen

import com.feedme.server.db.CommandActor
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class KitchenPolicyTest {
    @Test fun principalRequiresExplicitAccountDeviceOrGuestButNeverStaff() {
        assertFailsWith<IllegalArgumentException> { VerifiedKitchenPrincipal("test", CommandActor.ACCOUNT, UUID.randomUUID(), null) }
        assertFailsWith<IllegalArgumentException> { VerifiedKitchenPrincipal("test", CommandActor.GUEST, UUID.randomUUID(), UUID.randomUUID()) }
        assertFailsWith<IllegalArgumentException> { VerifiedKitchenPrincipal("test", CommandActor.STAFF, UUID.randomUUID(), UUID.randomUUID()) }
        for (env in listOf("", "Test", "x\n", "x".repeat(41)))
            assertFailsWith<IllegalArgumentException> { VerifiedKitchenPrincipal(env, CommandActor.GUEST, UUID.randomUUID(), null) }
    }
    @Test fun principalAndPolicyDiagnosticsNeverExposePrivateIdentityOrKeyMaterial() {
        val actor = actor(); assertFalse(actor.toString().contains(actor.principalId.toString()))
        assertFalse(actor.toString().contains(actor.deviceSessionId.toString()))
        assertEquals("KitchenCursorCodec(<redacted>)", codec().toString())
        assertEquals("KitchenServicePolicy(<redacted>)", KitchenServicePolicy(65536, 600).toString())
        assertFalse(KitchenFailure(KitchenFailureCode.CURSOR_INVALID).toString().contains(actor.principalId.toString()))
    }
    @Test fun responseAndLifetimeBoundsAreExplicitAndRejectUnsafeConfigurations() {
        for (size in listOf(0, 262145)) assertFailsWith<IllegalArgumentException> { KitchenServicePolicy(size, 600) }
        for (life in listOf(0, 86401)) assertFailsWith<IllegalArgumentException> { KitchenServicePolicy(65536, life) }
        assertEquals(65536, KitchenServicePolicy(65536, 600).maxResponseBytes)
        KitchenServicePolicy(262144, 86400)
    }
    @Test fun cursorKeysMustBeExplicitValidAndBounded() {
        assertFailsWith<IllegalArgumentException> { KitchenCursorCodec("v1", emptyMap()) }
        assertFailsWith<IllegalArgumentException> { KitchenCursorCodec("v2", mapOf("v1" to KEY)) }
        for (n in listOf(0, 31, 33)) assertFailsWith<IllegalArgumentException> { KitchenCursorCodec("v1", mapOf("v1" to ByteArray(n))) }
        for (id in listOf("", "private.key", "a".repeat(33))) assertFailsWith<IllegalArgumentException> { KitchenCursorCodec(id, mapOf(id to KEY)) }
        assertFailsWith<IllegalArgumentException> { KitchenCursorCodec("k0", (0..8).associate { "k$it" to KEY }) }
    }
    @Test fun cursorRoundtripIsExactAndNullIsDistinctFromEmpty() {
        val actor = actor(); val id = UUID.randomUUID(); val cursor = codec().encode(actor, id, NOW.plusSeconds(30))
        assertEquals(id, codec().decode(actor, cursor, NOW)); assertNull(codec().decode(actor, null, NOW))
        denied(KitchenFailureCode.CURSOR_INVALID) { codec().decode(actor, "", NOW) }
    }
    @Test fun cursorsBindEnvironmentPrincipalAndActorKindNotDeviceRotation() {
        val actor = actor(); val cursor = codec().encode(actor, UUID.randomUUID(), NOW.plusSeconds(30))
        for (other in listOf(actor(), VerifiedKitchenPrincipal("other", actor.kind, actor.principalId, actor.deviceSessionId),
            VerifiedKitchenPrincipal("test", CommandActor.GUEST, actor.principalId, null)))
            denied(KitchenFailureCode.CURSOR_INVALID) { codec().decode(other, cursor, NOW) }
        assertNotNull(codec().decode(VerifiedKitchenPrincipal("test", actor.kind, actor.principalId, UUID.randomUUID()), cursor, NOW))
    }
    @Test fun cursorsExpireAtExactDatabaseSecondAndCannotExtendExpiryWithoutMac() {
        val actor = actor(); val cursor = codec().encode(actor, UUID.randomUUID(), NOW.plusSeconds(1))
        assertNotNull(codec().decode(actor, cursor, NOW))
        denied(KitchenFailureCode.CURSOR_EXPIRED) { codec().decode(actor, cursor, NOW.plusSeconds(1)) }
        val parts = cursor.split('.').toMutableList(); parts[2] = NOW.plusSeconds(999).epochSecond.toString()
        denied(KitchenFailureCode.CURSOR_INVALID) { codec().decode(actor, parts.joinToString("."), NOW) }
    }
    @Test fun malformedTamperedOversizedAndNoncanonicalCursorsNeverBecomeFirstPage() {
        val actor = actor(); val original = codec().encode(actor, UUID.randomUUID(), NOW.plusSeconds(30))
        for (cursor in listOf("x".repeat(2049), original + ".x", original.dropLast(1) + "!", "lost." + original.substringAfter('.'),
            original.replaceFirst("v1.", "v1.00000000-0000-0000-0000-000000000000."), "v1.x.1." + "a".repeat(43)))
            denied(KitchenFailureCode.CURSOR_INVALID) { codec().decode(actor, cursor, NOW) }
    }
    @Test fun cursorConfigurationDetachesInputArraysAndSupportsExplicitKeyRotation() {
        val bytes = KEY.copyOf(); val map = mutableMapOf("v1" to bytes); val configured = KitchenCursorCodec("v1", map)
        val actor = actor(); val id = UUID.randomUUID(); val cursor = configured.encode(actor, id, NOW.plusSeconds(30))
        bytes.fill(0); map.clear(); assertEquals(id, configured.decode(actor, cursor, NOW))
        val rotated = KitchenCursorCodec("v2", mapOf("v1" to KEY, "v2" to ByteArray(32) { 3 }))
        assertEquals(id, rotated.decode(actor, cursor, NOW)); assertNotEquals(cursor, rotated.encode(actor, id, NOW.plusSeconds(30)))
    }
    @Test fun onlyCanonicalKitchenEventTypesAreProducedAndNoPrivateBodiesAreInRegistryContract() {
        val events = checkNotNull(javaClass.getResourceAsStream("/canonical-events.md")).use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue(events.contains("profile.preferences.changed.v1")); assertTrue(events.contains("pantry.item.changed.v1"))
    }
    companion object {
        private val KEY = ByteArray(32) { (it + 1).toByte() }
        private val NOW = Instant.parse("2026-09-14T00:00:00Z")
        private fun codec() = KitchenCursorCodec("v1", mapOf("v1" to KEY))
        private fun actor() = VerifiedKitchenPrincipal("test", CommandActor.ACCOUNT, UUID.randomUUID(), UUID.randomUUID())
        private fun denied(code: KitchenFailureCode, action: () -> Any?) = assertEquals(code, assertFailsWith<KitchenFailure> { action() }.code)
    }
}
