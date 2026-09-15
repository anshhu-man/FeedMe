package com.feedme.server.planning

import com.feedme.contracts.WireDocument
import com.feedme.server.db.CommandActor
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

class PlanningPersistenceCodecTest {
    @Test fun snapshotRoundTripPreservesExactProvenanceWithoutClaimingAuthentication() {
        val document = minimal(); val snapshot = PlanningEvidenceSnapshot.fromAuthoritativeDocument(WireDocument.parse(document.toString()))
        assertEquals(document, json(snapshot.copyForStorage())); assertFalse(snapshot.toString().contains("pantry-private"))
    }
    @Test fun snapshotRejectsUnknownMissingAndIncorrectVersionFields() {
        for (input in listOf(JsonObject(minimal() + ("token" to JsonPrimitive("private"))), JsonObject(minimal() - "baseMeal"),
            JsonObject(minimal() + ("version" to JsonPrimitive(2))))) rejected(input)
    }
    @Test fun snapshotRejectsDuplicateKeysMalformedUnicodeAndOversizeBeforeProjection() {
        for (raw in listOf("{\"version\":1,\"version\":1}", "{\"version\":\"\\ud800\"}", " ".repeat(PlanningEvidenceSnapshot.MAX_BYTES + 1)))
            assertFailsWith<PlanningSnapshotFormatException> { PlanningEvidenceSnapshot.decode(raw.toByteArray()) }
    }
    @Test fun preferenceRevisionRequiresExactPositiveBoundedLocalIntegerString() {
        for (value in listOf("0", "01", "1e0", "-1", "1.0", "1".repeat(129)))
            rejected(withPreferences(buildJsonObject { put("revision", value); put("excludedIngredientIds", arr()); put("dislikedIngredientIds", arr()) }))
    }
    @Test fun preferenceIdsAreStrictAndCaseInsensitiveDuplicatesAreRejected() {
        for (values in listOf(arr("not-id"), arr(ID, ID.uppercase()))) rejected(withPreferences(buildJsonObject {
            put("revision", "1"); put("excludedIngredientIds", values); put("dislikedIngredientIds", arr())
        }))
    }
    @Test fun pantryUncertaintyIsExplicitAndNoUnknownEnumOrDuplicateCanBeCollapsed() {
        for (availability in listOf("confirmed", "STALE", "")) rejected(withPantry(buildJsonObject {
            put("revision", "p1"); put("items", buildJsonArray { add(buildJsonObject { put("ingredientId", ID); put("availability", availability) }) })
        }))
        val item = buildJsonObject { put("ingredientId", ID); put("availability", "UNCERTAIN") }
        rejected(withPantry(buildJsonObject { put("revision", "p1"); put("items", JsonArray(listOf(item, item))) }))
    }
    @Test fun unknownCompositionIsRetainedAsUnknownRatherThanAnEmptyAtom() {
        val input = withCatalog(buildJsonObject { put("revision", "c1"); put("taxonomyRevision", "t1"); put("candidates", arr())
            put("ingredients", buildJsonArray { add(buildJsonObject { put("ingredientId", ID); put("componentIds", JsonNull) }) }) })
        assertEquals(input, json(PlanningEvidenceSnapshot.fromAuthoritativeDocument(WireDocument.parse(input.toString())).copyForStorage()))
    }
    @Test fun baseEvidenceRequiresExplicitCompositionAndMatchingPreparationShape() {
        rejected(JsonObject(minimal() + ("baseMeal" to buildJsonObject { put("catalogType", "rice"); put("document", buildJsonObject {}) })))
        val base = buildJsonObject { put("catalogType", "rice"); put("compositionComplete", false)
            put("document", buildJsonObject { put("description", "prepared rice"); put("preparationState", "unknown"); put("ingredientIds", arr(ID)) }) }
        assertNotNull(PlanningEvidenceSnapshot.fromAuthoritativeDocument(WireDocument.parse(JsonObject(minimal() + ("baseMeal" to base)).toString())).context().baseMeal)
    }
    @Test fun catalogBoundAndMalformedRecipeRejectRatherThanSilentlyTruncate() {
        rejected(withCatalog(buildJsonObject { put("revision", "c1"); put("taxonomyRevision", "t1"); put("ingredients", arr())
            put("candidates", JsonArray(List(129) { buildJsonObject {} })) }))
        rejected(withCatalog(buildJsonObject { put("revision", "c1"); put("taxonomyRevision", "t1"); put("ingredients", arr())
            put("candidates", buildJsonArray { add(buildJsonObject { put("recipe", buildJsonObject {}); put("review", buildJsonObject {}) }) }) }))
    }
    @Test fun explanationCursorBindsOwnerPlanSnapshotAndExactOffset() {
        val codec = cursors(); val cursor = codec.explanation("owned-plan-hash", 2)
        assertEquals(2, codec.offset("owned-plan-hash", cursor)); assertEquals(0, codec.offset("owned-plan-hash", null))
        assertFailsWith<PlanningServiceFailure> { codec.offset("foreign-plan-hash", cursor) }
    }
    @Test fun explanationCursorNeverTreatsMalformedOrTamperedInputAsFirstPage() {
        val codec = cursors(); val valid = codec.explanation("binding", 1)
        for (input in listOf("", valid + ".extra", valid.dropLast(1) + "!", valid.replace(".1.", ".01."), "x".repeat(2049)))
            assertEquals(PlanningFailureCode.CURSOR_INVALID, assertFailsWith<PlanningServiceFailure> { codec.offset("binding", input) }.code)
    }
    @Test fun cursorKeysAreExplicitDefensivelyCopiedAndNeverPrinted() {
        val bytes = ByteArray(32) { 2 }; val keys = mutableMapOf("v1" to bytes); val codec = PlanningCursors("v1", keys)
        val original = codec.explanation("private", 1); bytes.fill(0); keys.clear(); assertEquals(original, codec.explanation("private", 1))
        assertFalse(codec.toString().contains("private")); assertFailsWith<IllegalArgumentException> { PlanningCursors("v1", emptyMap()) }
        assertFailsWith<IllegalArgumentException> { PlanningCursors("v1", mapOf("v1" to ByteArray(31))) }
    }
    @Test fun accountAndGuestPrincipalKindsCannotBeSwappedOrDefaulted() {
        val id = UUID.randomUUID()
        assertFailsWith<IllegalArgumentException> { VerifiedPlanningPrincipal("test", CommandActor.STAFF, id, id) }
        assertFailsWith<IllegalArgumentException> { VerifiedPlanningPrincipal("test", CommandActor.GUEST, id, id) }
        assertFailsWith<IllegalArgumentException> { VerifiedPlanningPrincipal("test", CommandActor.ACCOUNT, id, null) }
        assertFalse(VerifiedPlanningPrincipal("test", CommandActor.GUEST, id, null).toString().contains(id.toString()))
    }
    @Test fun retentionAndPolicyRequireExplicitBoundedConfiguration() {
        assertFailsWith<IllegalArgumentException> { PlanningServicePolicy("", false, false, 600, 60) }
        assertFailsWith<IllegalArgumentException> { PlanningServicePolicy("v1", false, false, 60, 61) }
        assertFailsWith<IllegalArgumentException> { PlanningServicePolicy("v1", false, false, 86400, 601) }
    }
    @Test fun planningEventIsInCanonicalCatalogAndFailuresNeverExposePrivateData() {
        val events = checkNotNull(javaClass.getResourceAsStream("/canonical-events.md")).use { it.readBytes().decodeToString() }
        assertTrue(events.contains("planning.plan.created.v1"))
        assertFalse(PlanningSnapshotFormatException().toString().contains(ID))
        assertFalse(PlanningServiceFailure(PlanningFailureCode.PREFERENCE_CHANGED).toString().contains(ID))
    }
    companion object {
        private const val ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private fun arr(vararg strings: String) = JsonArray(strings.map(::JsonPrimitive))
        private fun minimal() = buildJsonObject {
            put("version", 1); put("preferences", buildJsonObject { put("revision", "1"); put("excludedIngredientIds", arr()); put("dislikedIngredientIds", arr()) })
            put("pantry", buildJsonObject { put("revision", "pantry-private"); put("items", arr()) })
            put("catalog", buildJsonObject { put("revision", "c1"); put("taxonomyRevision", "t1"); put("ingredients", arr()); put("candidates", arr()) }); put("baseMeal", JsonNull)
        }
        private fun withPreferences(value: JsonObject) = JsonObject(minimal() + ("preferences" to value))
        private fun withPantry(value: JsonObject) = JsonObject(minimal() + ("pantry" to value))
        private fun withCatalog(value: JsonObject) = JsonObject(minimal() + ("catalog" to value))
        private fun rejected(value: JsonObject) { assertFailsWith<PlanningSnapshotFormatException> { PlanningEvidenceSnapshot.fromAuthoritativeDocument(WireDocument.parse(value.toString())) } }
        private fun cursors() = PlanningCursors("v1", mapOf("v1" to ByteArray(32) { 2 }))
    }
}
