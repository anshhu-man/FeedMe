package com.feedme.app.mealflow

import kotlin.test.*

class KitchenCommandTargetTest {
    @Test fun canonicalPantryTargetMatchesValidatedUuidRegardlessOfInputCase() {
        val id = "00000000-0000-4000-8000-0000000000ab"
        assertTrue(sameKitchenCommandTarget(id, id.uppercase()))
        assertTrue(sameKitchenCommandTarget(id.uppercase(), id))
        assertFalse(sameKitchenCommandTarget(id, "00000000-0000-4000-8000-0000000000cd"))
    }
    @Test fun PreferenceTargetNeverMatchesPantryIdentifier() {
        assertTrue(sameKitchenCommandTarget(null, null))
        assertFalse(sameKitchenCommandTarget(null, "00000000-0000-4000-8000-0000000000ab"))
        assertFalse(sameKitchenCommandTarget("00000000-0000-4000-8000-0000000000ab", null))
    }
}
