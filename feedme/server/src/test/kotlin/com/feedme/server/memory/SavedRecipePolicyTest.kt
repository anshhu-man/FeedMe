package com.feedme.server.memory

import com.feedme.server.db.CommandActor
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class SavedRecipePolicyTest {
    @Test fun principalRequiresVerifiedAccountDeviceOrInternalGuestButNeverBoth() {
        val id = UUID.randomUUID(); val session = UUID.randomUUID()
        assertFailsWith<IllegalArgumentException> { VerifiedSavedRecipePrincipal("test", CommandActor.ACCOUNT, id, null) }
        assertFailsWith<IllegalArgumentException> { VerifiedSavedRecipePrincipal("test", CommandActor.GUEST, id, session, session) }
        assertFailsWith<IllegalArgumentException> { VerifiedSavedRecipePrincipal("test", CommandActor.GUEST, id, null) }
        assertEquals(CommandActor.GUEST, VerifiedSavedRecipePrincipal("test", CommandActor.GUEST, id, null, session).kind)
    }
    @Test fun environmentAndOperationalBoundsAreExplicit() {
        for (size in listOf(0, 262145)) assertFailsWith<IllegalArgumentException> { SavedRecipeServicePolicy(size, 10, "Cookbook") }
        for (ttl in listOf(0, 86401)) assertFailsWith<IllegalArgumentException> { SavedRecipeServicePolicy(65536, ttl, "Cookbook") }
        assertFailsWith<IllegalArgumentException> { VerifiedSavedRecipePrincipal("UPPER", CommandActor.ACCOUNT, UUID.randomUUID(), UUID.randomUUID()) }
    }
    @Test fun defaultCollectionNameUsesUnicodeScalarsWithoutControlOrMalformedText() {
        assertEquals(120, SavedRecipeServicePolicy(65536, 600, "🍜".repeat(60)).defaultCollectionName.length)
        for (name in listOf("", " ", "line\n", "🍜".repeat(61))) assertFailsWith<IllegalArgumentException> { SavedRecipeServicePolicy(65536, 10, name) }
        assertFails { SavedRecipeServicePolicy(65536, 10, "\uD800") }
    }
    @Test fun copyDecisionDetachesEvidenceAndRequiresPositiveBoundedProvenance() {
        val map = mutableMapOf<String, JsonElement>("authority" to JsonPrimitive("reviewed")); val original = JsonObject(map)
        val decision = AuthorizedRecipeCopy(UUID.randomUUID(), original, "catalogRedistributable", original)
        map["authority"] = JsonPrimitive("changed"); assertEquals(JsonPrimitive("reviewed"), decision.recipe["authority"])
        assertFailsWith<IllegalArgumentException> { AuthorizedRecipeCopy(UUID.randomUUID(), original, "creatorOriginal", original) }
        assertFailsWith<IllegalArgumentException> { AuthorizedRecipeCopy(UUID.randomUUID(), original, "privateCopyOnly", JsonObject(emptyMap())) }
    }
    @Test fun identitiesProofsAndKeysHaveRedactedDiagnostics() {
        val a = actor(); val document = buildJsonObject { put("secret", "private-proof") }
        val values = listOf(a, SavedRecipeCursors("v1", mapOf("v1" to ByteArray(32))), AuthorizedRecipeCopy(UUID.randomUUID(), document, "privateCopyOnly", document),
            SavedRecipeCopyEvidence(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), "catalog", null, "privateCopyOnly", document))
        values.forEach { assertFalse(it.toString().contains("private-proof")); assertFalse(it.toString().contains(a.principalId.toString())) }
    }
    @Test fun cursorRoundTripIsExactAndDoesNotGrantOwnership() {
        val a = actor(); val id = UUID.randomUUID(); val codec = codec(); val now = Instant.parse("2026-09-14T00:00:00Z")
        val value = codec.encode(a, "saved", "absent", 7, id, now.plusSeconds(10))
        assertEquals(id, codec.decode(a, "saved", "absent", 7, value, now)); assertNull(codec.decode(a, "saved", "absent", 7, null, now))
        denied(SavedRecipeFailureCode.CURSOR_INVALID) { codec.decode(actor(), "saved", "absent", 7, value, now) }
    }
    @Test fun cursorRejectsPurposeFilterCollectionAndRevisionSubstitution() {
        val a = actor(); val codec = codec(); val now = Instant.now(); val value = codec.encode(a, "items", "collection", 3, UUID.randomUUID(), now.plusSeconds(60))
        for (changed in listOf(Triple("saved", "collection", 3L), Triple("items", "other", 3L), Triple("items", "collection", 4L)))
            denied(SavedRecipeFailureCode.CURSOR_INVALID) { codec.decode(a, changed.first, changed.second, changed.third, value, now) }
    }
    @Test fun cursorDistinguishesNullAndEmptyFiltersAndRejectsTampering() {
        val a = actor(); val codec = codec(); val now = Instant.now(); val value = codec.encode(a, "saved", "absent", 1, UUID.randomUUID(), now.plusSeconds(60))
        denied(SavedRecipeFailureCode.CURSOR_INVALID) { codec.decode(a, "saved", "text:", 1, value, now) }
        for (bad in listOf("", "$value.x", value.replaceFirst(".1.", ".01."), "x".repeat(2049), value.dropLast(1) + "!"))
            denied(SavedRecipeFailureCode.CURSOR_INVALID) { codec.decode(a, "saved", "absent", 1, bad, now) }
    }
    @Test fun expiredCursorNeverRestartsAtFirstPage() {
        val a = actor(); val codec = codec(); val now = Instant.now(); val value = codec.encode(a, "saved", "", 1, UUID.randomUUID(), now)
        denied(SavedRecipeFailureCode.CURSOR_EXPIRED) { codec.decode(a, "saved", "", 1, value, now) }
    }
    @Test fun cursorKeyRotationRetainsOnlyExplicitOldKeysAndCopiesMaterial() {
        val a = actor(); val key = ByteArray(32) { 4 }; val old = SavedRecipeCursors("old", mapOf("old" to key)); val now = Instant.now(); val id = UUID.randomUUID()
        val value = old.encode(a, "saved", "", 1, id, now.plusSeconds(60)); key.fill(0)
        val rotated = SavedRecipeCursors("new", mapOf("new" to ByteArray(32) { 9 }, "old" to ByteArray(32) { 4 }))
        assertEquals(id, rotated.decode(a, "saved", "", 1, value, now))
        denied(SavedRecipeFailureCode.CURSOR_INVALID) { codec().decode(a, "saved", "", 1, value, now) }
    }
    @Test fun noImplicitCursorSigningKeyOrUnboundedKeySetIsAccepted() {
        assertFailsWith<IllegalArgumentException> { SavedRecipeCursors("v1", emptyMap()) }
        assertFailsWith<IllegalArgumentException> { SavedRecipeCursors("v1", mapOf("v1" to ByteArray(31))) }
        assertFailsWith<IllegalArgumentException> { SavedRecipeCursors("x0", (0..8).associate { "x$it" to ByteArray(32) }) }
    }
    @Test fun failureMessagesExposeOnlyTypedCodeNotPrivateMaterial() {
        for (code in SavedRecipeFailureCode.entries) assertEquals("Saved recipe operation unavailable: ${code.name}", SavedRecipeFailure(code).message)
    }
    private fun actor() = VerifiedSavedRecipePrincipal("test", CommandActor.ACCOUNT, UUID.randomUUID(), UUID.randomUUID())
    private fun codec() = SavedRecipeCursors("v1", mapOf("v1" to ByteArray(32) { 8 }))
    private fun denied(code: SavedRecipeFailureCode, action: () -> Any?) = assertEquals(code, assertFailsWith<SavedRecipeFailure> { action() }.code)
}
