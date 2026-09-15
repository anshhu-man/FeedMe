package com.feedme.app.mealflow

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class KitchenInputPresentationTest {
    private val ingredient = "00000000-0000-4000-8000-0000000000ab"
    private val other = "00000000-0000-4000-8000-0000000000cd"
    private fun doc(json: String) = WireDocument.parse(json)
    private fun current() = doc("""{"id":"$other","version":7,"createdAt":"2026-09-14T00:00:00Z",
        "updatedAt":"2026-09-14T00:00:00Z","hardExcludedIngredientIds":["$ingredient"],
        "dislikedIngredientIds":["$other"],"dietaryPatterns":["existing-reviewed-id"],
        "equipmentIds":["bowl"],"consentVersion":"not-editor-authority","defaultServings":1.230000}""")

    @Test fun draftOverlayDoesNotReplaceUneditedCurrentSelections() {
        val draft = doc("""{"equipmentIds":["pan"]}""")
        assertEquals(listOf("pan"), kitchenPreferenceSelection(current(), draft, "equipmentIds"))
        assertEquals(listOf(ingredient), kitchenPreferenceSelection(current(), draft, "hardExcludedIngredientIds"))
        assertEquals(listOf("existing-reviewed-id"), kitchenPreferenceSelection(current(), draft, "dietaryPatterns"))
    }

    @Test fun explicitEmptyArrayIsNotReplacedByMorePermissiveOrOlderValues() {
        val draft = doc("""{"hardExcludedIngredientIds":[]}""")
        assertEquals(emptyList(), kitchenPreferenceSelection(current(), draft, "hardExcludedIngredientIds"))
        assertEquals(listOf(other), kitchenPreferenceSelection(current(), draft, "dislikedIngredientIds"))
    }

    @Test fun togglingPreservesEveryOtherExistingPatchField() {
        val draft = doc("""{"equipmentIds":["pan"],"defaultServings":1.230000,"futureField":{"retained":true}}""")
        val original = kitchenObject(draft)
        val result = kitchenObject(kitchenTogglePreference(current(), draft, "hardExcludedIngredientIds", other))
        original.forEach { (key, value) -> assertEquals(value, result[key]) }
        assertEquals(listOf(ingredient, other), result.getValue("hardExcludedIngredientIds").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun currentDtoAndConsentNeverBecomeAnImplicitPatch() {
        val patch = kitchenObject(kitchenTogglePreference(current(), null, "equipmentIds", "pan"))
        assertEquals(setOf("equipmentIds"), patch.keys)
        assertFalse("consentVersion" in patch || "version" in patch || "id" in patch || "defaultServings" in patch)
    }

    @Test fun hardExclusionEditNeverTurnsDislikesIntoExclusions() {
        val patch = kitchenTogglePreference(current(), null, "hardExcludedIngredientIds", ingredient)
        assertEquals(emptyList(), kitchenPreferenceSelection(current(), patch, "hardExcludedIngredientIds"))
        assertEquals(listOf(other), kitchenPreferenceSelection(current(), patch, "dislikedIngredientIds"))
        assertFalse("dislikedIngredientIds" in kitchenObject(patch))
    }

    @Test fun sourceDocumentsRemainByteIdenticalAfterProjectionAndEditing() {
        val source = current(); val draft = doc("""{"preferredTasteTags":["crunch"]}""")
        val sourceBytes = source.encodeUtf8(); val draftBytes = draft.encodeUtf8()
        kitchenTogglePreference(source, draft, "equipmentIds", "bowl")
        kitchenPreferenceSelection(source, draft, "dietaryPatterns")
        assertContentEquals(sourceBytes, source.encodeUtf8()); assertContentEquals(draftBytes, draft.encodeUtf8())
    }

    @Test fun noGenericPreferenceFieldOrConsentEditingOracle() {
        listOf("consentVersion", "dietaryPatterns", "defaultServings", "inventedField").forEach { field ->
            assertFailsWith<IllegalArgumentException> { kitchenTogglePreference(current(), null, field, "invented") }
        }
    }

    @Test fun ingredientUuidCaseDoesNotCreateDuplicateOrHiddenSelections() {
        val source = doc("""{"hardExcludedIngredientIds":["${ingredient.uppercase()}"]}""")
        val patch = kitchenTogglePreference(source, null, "hardExcludedIngredientIds", ingredient)
        assertEquals(emptyList(), kitchenPreferenceSelection(source, patch, "hardExcludedIngredientIds"))
        val equipment = doc("""{"equipmentIds":["Bowl"]}""")
        assertEquals(listOf("Bowl", "bowl"), kitchenPreferenceSelection(equipment,
            kitchenTogglePreference(equipment, null, "equipmentIds", "bowl"), "equipmentIds"))
    }

    @Test fun missingIngredientLabelsAreExplicitIdsNotFabricatedNames() {
        val label = kitchenIngredientLabel(ingredient, emptyList())
        assertEquals("Ingredient label unavailable ($ingredient)", label)
        assertFalse(label.contains("tomato"))
    }

    @Test fun labelsComeOnlyFromExactControlledPickerIdentity() {
        val known = listOf(IngredientRow(ingredient.uppercase(), "Reviewed catalog label", true))
        assertEquals("Reviewed catalog label", kitchenIngredientLabel(ingredient, known))
        assertEquals("Ingredient label unavailable ($other)", kitchenIngredientLabel(other, known))
    }

    @Test fun roughPantryEditDoesNotEnableQuantityUnitOrCopyVersionFields() {
        val source = doc("""{"id":"$other","ingredientId":"$ingredient","version":8,"createdAt":"old","updatedAt":"old",
            "presence":"available","quantity":1.230000,"unit":"kg","expectedVersion":8,"staple":true}""")
        val result = kitchenObject(kitchenPantryDraft(source, null, ingredient, "low"))
        assertEquals(JsonPrimitive("low"), result["presence"])
        assertTrue(setOf("quantity", "unit", "expectedVersion", "id", "version", "createdAt", "updatedAt").none { it in result })
        assertEquals(JsonPrimitive(true), result["staple"])
    }

    @Test fun pantryPatchPreservesUnknownCurrentAndDraftFieldsWithoutOverwritingDraft() {
        val source = doc("""{"ingredientId":"$ingredient","presence":"uncertain","futureCurrent":{"a":1},"staple":false}""")
        val draft = doc("""{"ingredientId":"$ingredient","presence":"available","futureDraft":[1,2],"staple":true}""")
        val result = kitchenObject(kitchenPantryDraft(source, draft, ingredient, "out"))
        assertEquals(kitchenObject(source)["futureCurrent"], result["futureCurrent"])
        assertEquals(kitchenObject(draft)["futureDraft"], result["futureDraft"])
        assertEquals(JsonPrimitive(true), result["staple"])
    }

    @Test fun choosingRoughPresenceNeverCreatesOrReusesConfirmationTime() {
        val source = doc("""{"ingredientId":"$ingredient","presence":"available","confirmedAt":"2026-09-14T00:00:00Z","confirmationStatus":"confirmed"}""")
        listOf("available", "low", "uncertain", "usuallyHave", "out").forEach { presence ->
            val result = kitchenObject(kitchenPantryDraft(source, null, ingredient, presence))
            assertEquals(JsonNull, result["confirmedAt"])
            assertNotEquals(JsonPrimitive("confirmed"), result["confirmationStatus"])
        }
    }

    @Test fun newPantryReportHasNoInventedStockStapleOrMealSelection() {
        val result = kitchenObject(kitchenPantryDraft(null, null, ingredient, "usuallyHave"))
        assertEquals(setOf("ingredientId", "presence", "confirmationStatus", "confirmedAt"), result.keys)
        assertEquals(JsonPrimitive("usual"), result["confirmationStatus"])
        assertTrue(kitchenPresenceLabel("usuallyHave").contains("uncertain"))
    }

    @Test fun pantryDraftCannotMergeAnotherIngredientOrUnknownPresence() {
        val source = doc("""{"ingredientId":"$other","presence":"out"}""")
        assertFailsWith<IllegalArgumentException> { kitchenPantryDraft(source, null, ingredient, "available") }
        assertFailsWith<IllegalArgumentException> { kitchenPantryDraft(null, source, ingredient, "available") }
        assertFailsWith<IllegalArgumentException> { kitchenPantryDraft(null, null, ingredient, "safe-to-eat") }
        val uppercase = doc("""{"ingredientId":"${ingredient.uppercase()}","presence":"out"}""")
        assertEquals(ingredient, kitchenIngredientId(kitchenPantryDraft(uppercase, null, ingredient, "low")))
    }

    @Test fun pendingCopyDistinguishesRetainedReceiptFromAppliedAcknowledgement() {
        KitchenInputCommandPhase.entries.forEach { assertTrue(kitchenCommandMessage(it).isNotBlank()) }
        assertTrue(kitchenCommandMessage(KitchenInputCommandPhase.RECEIPT_READY).contains("still needs acknowledgement"))
        assertTrue(kitchenCommandMessage(KitchenInputCommandPhase.APPLIED).contains("acknowledged applied"))
        assertTrue(kitchenCommandMessage(KitchenInputCommandPhase.NEEDS_RESOLUTION).contains("No automatic overwrite"))
    }

    @Test fun visibleAppliedStateWithLostAckRequiresFinalizationWithoutClaimingSuccess() {
        val unknown = kitchenCommandMessage(KitchenInputCommandPhase.APPLIED, "OUTCOME_UNKNOWN")
        assertTrue(unknown.contains("Finalization required"))
        assertTrue(unknown.contains("local acknowledgement is still missing"))
        assertTrue(unknown.contains("original change"))
        val changed = kitchenCommandMessage(KitchenInputCommandPhase.APPLIED, "DOMAIN_RECHECK_REQUIRED")
        assertTrue(changed.contains("Finalization is blocked"))
        assertTrue(changed.contains("not a completed local acknowledgement"))
        val unavailable = kitchenCommandMessage(KitchenInputCommandPhase.APPLIED, "unrecognized-private-issue")
        assertTrue(unavailable.contains("acknowledgement remains unresolved"))
        assertFalse(unavailable.contains("unrecognized-private-issue"))
        listOf(unknown, changed, unavailable).forEach { assertFalse(it.contains("has an acknowledged applied receipt")) }
        assertEquals("This change has an acknowledged applied receipt.",
            kitchenCommandMessage(KitchenInputCommandPhase.APPLIED, "NONE"))
    }

    @Test fun unknownOutcomeCopyNeverInfersRollbackOrReplacement() {
        val message = kitchenPhaseMessage(KitchenInputPhase.PENDING, KitchenInputIssue.OUTCOME_UNKNOWN)
        assertEquals("Outcome unknown", message.first)
        assertTrue(message.second.contains("does not prove"))
        assertTrue(kitchenFailureMessage(FailureReason.OUTCOME_UNKNOWN)!!.contains("do not replace"))
    }

    @Test fun unavailableTakesPrecedenceOverAnyResidualIssue() {
        KitchenInputIssue.entries.forEach { issue ->
            val message = kitchenPhaseMessage(KitchenInputPhase.UNAVAILABLE, issue)
            assertEquals("This kitchen is unavailable", message.first)
            assertTrue(message.second.contains("Private values are hidden"))
        }
    }

    @Test fun offlineAndPartialPagesNeverClaimRemoteSuccessOrEmptyWholePantry() {
        assertTrue(kitchenPhaseMessage(KitchenInputPhase.OFFLINE, KitchenInputIssue.OFFLINE).second.contains("not a server acknowledgement"))
        assertTrue(kitchenIssueMessage(KitchenInputIssue.PAGE_LIMIT)!!.contains("not known to be absent"))
        assertTrue(kitchenFailureMessage(FailureReason.NOT_FOUND)!!.contains("does not prove"))
    }

    @Test fun everyTypedFailureHasSafeCopyAndNoRawCommandIdentityIsNeeded() {
        assertNull(kitchenFailureMessage(null)); assertNull(kitchenIssueMessage(KitchenInputIssue.NONE))
        FailureReason.entries.forEach { assertTrue(kitchenFailureMessage(it)!!.isNotBlank()) }
        KitchenInputIssue.entries.filterNot { it == KitchenInputIssue.NONE }.forEach { assertTrue(kitchenIssueMessage(it)!!.isNotBlank()) }
        assertEquals("unavailable operation", kitchenCommandTitle("private-unrecognized-operation"))
    }
}
