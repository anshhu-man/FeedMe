package com.feedme.app.mealflow

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent

class MealFormOwnerTest {
    private val boundary = SessionBoundary()
    private val scope = StorageScope("test.invalid", ActorKind.ACCOUNT, "synthetic-owner")
    private val lease = boundary.activate(scope)
    private fun owner() = MealFormOwner(boundary, lease)

    @Test fun partialNumericTextRemainsUnsavedAndExact() {
        val owner = owner(); owner.edit { it.copy(servings = "0.", totalMinutes = "") }
        assertEquals("0.", owner.states.value.values!!.servings)
        assertTrue(owner.states.value.dirty)
        assertNull(owner.states.value.values!!.draft().maxTotalMinutes)
    }
    @Test fun invalidationSynchronouslyClearsFormAndSearch() {
        val owner = owner(); owner.edit { it.copy(baseDescription = "private dinner") }; owner.searchText("private search")
        boundary.clear()
        assertNull(owner.states.value.values); assertEquals("", owner.states.value.searchText)
        assertEquals(FailureReason.STALE_SESSION, owner.states.value.failure); assertFalse(owner.states.value.dirty)
    }
    @Test fun sameAccountReplacementAlsoRedacts() {
        val owner = owner(); owner.edit { it.copy(servings = "2") }; boundary.activate(scope)
        assertNull(owner.states.value.values); assertFalse(owner.current())
    }
    @Test fun transformCannotRepublishAfterSynchronousIdentityChangeOrClose() {
        for (change in listOf<(MealFormOwner) -> Unit>({ boundary.clear() }, { boundary.activate(scope) }, { it.close() })) {
            val current = MealFormOwner(boundary, boundary.activate(scope))
            current.edit { it.copy(baseDescription = "private old meal") }
            current.edit { old -> change(current); old.copy(servings = "9") }
            assertNull(current.states.value.values); assertFalse(current.states.value.dirty)
            current.close()
        }
    }
    @Test fun reentrantNewerEditCannotBeOverwrittenByOuterTransform() {
        val owner = owner()
        owner.edit { old -> owner.edit { it.copy(servings = "3") }; old.copy(servings = "2") }
        assertEquals("3", owner.states.value.values!!.servings); assertTrue(owner.states.value.dirty)
    }
    @Test fun foreignBoundaryLeaseCannotRenderPrivateForm() {
        val owner = MealFormOwner(SessionBoundary(), lease)
        assertNull(owner.states.value.values); assertFalse(owner.current())
    }
    @Test fun closeRedactsWithoutClearingBorrowedIdentity() {
        val owner = owner(); owner.close(); owner.close()
        assertNull(owner.states.value.values); assertTrue(boundary.isCurrent(lease))
        owner.edit { it.copy(servings = "3") }; assertNull(owner.states.value.values)
    }
    @Test fun acknowledgementCannotReviveInvalidatedForm() {
        val owner = owner(); val ticket = owner.capture()!!.first
        boundary.clear(); assertFalse(owner.acknowledged(ticket)); owner.busy(false)
        assertNull(owner.states.value.values)
    }
    @Test fun laterEditCannotBeMarkedSavedByEarlierAcknowledgement() {
        val owner = owner(); owner.edit { it.copy(servings = "2") }; val first = owner.capture()!!
        owner.edit { it.copy(servings = "3") }
        assertFalse(owner.acknowledged(first.first)); assertTrue(owner.states.value.dirty)
        assertEquals("3", owner.states.value.values!!.servings)
    }
    @Test fun exactCurrentAcknowledgementMarksOnlyCurrentDraftSaved() {
        val owner = owner(); owner.edit { it.copy(servings = "1.000") }; val ticket = owner.capture()!!.first
        assertTrue(owner.acknowledged(ticket)); assertFalse(owner.states.value.dirty)
        assertEquals("1.000", owner.states.value.values!!.servings)
    }
    @Test fun exitCheckRejectsEditsOrInvalidationAfterSaveHasAlreadyReturned() = runTest {
        val owner = owner(); owner.edit { it.copy(servings = "2") }
        val ticket = owner.capture()!!.first
        assertIs<PortResult.Value<Unit>>(persistMealForm(owner) { PortResult.Value(Unit) })
        assertTrue(owner.savedCurrent(ticket))
        // Models a delayed caller-dispatcher continuation after the save's own acknowledgement.
        owner.edit { it.copy(servings = "3") }; assertFalse(owner.savedCurrent(ticket))
        val newer = owner.capture()!!.first
        assertIs<PortResult.Value<Unit>>(persistMealForm(owner) { PortResult.Value(Unit) })
        assertFalse(owner.savedCurrent(ticket)); assertTrue(owner.savedCurrent(newer))
        boundary.activate(scope); assertFalse(owner.savedCurrent(newer))
    }
    @Test fun restoreDoesNotOverwriteDirtyPartialText() {
        val owner = owner(); owner.edit { it.copy(servings = "0.") }
        owner.adopt(MealFormValues(servings = "5").draft())
        assertEquals("0.", owner.states.value.values!!.servings)
    }
    @Test fun explicitDiscardRestoresSavedDraftWithoutErasingSavedExclusions() {
        val owner = owner(); owner.edit { it.copy(servings = "0.") }
        val saved = MealFormValues(servings = "4.000", exclusions = listOf("00000000-0000-4000-8000-000000000001"))
        owner.discardEdits(saved.draft())
        assertEquals("4.000", owner.states.value.values!!.servings)
        assertEquals(saved.exclusions, owner.states.value.values!!.exclusions); assertFalse(owner.states.value.dirty)
    }
    @Test fun listInputsAndPublishedValuesAreDetached() {
        val owner = owner(); val input = mutableListOf("bowl", "pan")
        owner.edit { it.copy(equipmentIds = input) }; input.clear()
        assertEquals(listOf("bowl", "pan"), owner.states.value.values!!.equipmentIds)
        val value = owner.states.value.values!!.equipmentIds as MutableList<String>; value.clear()
        assertEquals(listOf("bowl", "pan"), owner.states.value.values!!.equipmentIds)
    }
    @Test fun resourceLimitRejectsWithoutReplacingPrivateInput() {
        val owner = owner(); owner.edit { it.copy(servings = "2") }
        owner.edit { it.copy(servings = "1".repeat(129)) }
        assertEquals("2", owner.states.value.values!!.servings); assertEquals(FailureReason.INVALID_DATA, owner.states.value.failure)
    }
    @Test fun defaultFormInventsNoEquipmentTasteExclusionsOrBaseComposition() {
        val values = owner().states.value.values!!
        assertTrue(values.ingredientIds.isEmpty()); assertTrue(values.equipmentIds.isEmpty())
        assertTrue(values.tasteTags.isEmpty()); assertTrue(values.exclusions.isEmpty()); assertNull(values.baseIngredientIds)
    }
    @Test fun improveAvailabilityDoesNotBecomeBaseComposition() {
        val values = MealFormValues(mode = MealMode.IMPROVE, ingredientIds = listOf("00000000-0000-4000-8000-000000000001"),
            baseDescription = "already prepared meal")
        assertNull(values.draft().baseMeal!!.ingredientIds)
        assertEquals(values.ingredientIds, values.draft().ingredientIds)
    }
    @Test fun modelsDoNotLogPrivateText() {
        val owner = owner(); owner.edit { it.copy(baseDescription = "sensitive-base") }; owner.searchText("sensitive-query")
        assertFalse(owner.states.value.toString().contains("sensitive"))
        assertFalse(owner.states.value.values.toString().contains("sensitive"))
        assertFalse(MealInputChoice("private-id", "private-label").toString().contains("private-"))
    }
    @Test fun choicesRejectAmbiguousDuplicateIdentities() {
        assertFailsWith<IllegalArgumentException> { MealInputChoices(listOf(MealInputChoice("same", "One"), MealInputChoice("same", "Two")), emptyList()) }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun suspendedSaveDoesNotAuthorizeExitAfterNewerEdits() = runTest {
        val owner = owner(); owner.edit { it.copy(servings = "2") }
        val acknowledgement = CompletableDeferred<Unit>()
        var savedServings: String? = null
        val saving = async { persistMealForm(owner) { draft ->
            savedServings = draft.servings; acknowledgement.await(); PortResult.Value(Unit)
        } }
        runCurrent(); owner.edit { it.copy(servings = "3") }; acknowledgement.complete(Unit)
        assertEquals("2", savedServings)
        assertEquals(FailureReason.CONFLICT, (saving.await() as PortResult.Failure).reason)
        assertEquals("3", owner.states.value.values!!.servings); assertTrue(owner.states.value.dirty)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun suspendedSaveCannotAcknowledgeAfterSessionReplacement() = runTest {
        val owner = owner(); val acknowledgement = CompletableDeferred<Unit>()
        val saving = async { persistMealForm(owner) { acknowledgement.await(); PortResult.Value(Unit) } }
        runCurrent(); boundary.activate(scope); acknowledgement.complete(Unit)
        assertEquals(FailureReason.STALE_SESSION, (saving.await() as PortResult.Failure).reason)
        assertNull(owner.states.value.values)
    }
    @Test fun failedSaveKeepsDraftDirtyWithoutAcknowledging() = runTest {
        val owner = owner(); owner.edit { it.copy(servings = "2") }
        val result = persistMealForm<Unit>(owner) { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        assertEquals(FailureReason.OUTCOME_UNKNOWN, (result as PortResult.Failure).reason)
        assertTrue(owner.states.value.dirty)
    }
}
