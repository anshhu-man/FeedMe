package com.feedme.core

import com.feedme.core.ports.EpochClock
import kotlin.test.*

class KitchenRuntimeTest {
    @Test fun runtimeRequiresExplicitDemoStateAndInjectedClock() {
        val runtime = DemoKitchenRuntime(EpochClock { 100 })
        assertEquals(OperatingMode.DEMO, runtime.initialState().mode)
        assertFailsWith<IllegalArgumentException> { runtime.dispatch(AppState(), AppAction.ContinueAsGuest) }
        assertFailsWith<IllegalArgumentException> { DemoKitchenRuntime(EpochClock { -1 }).initialState() }
    }

    @Test fun featuredSourceUsesVisibleStateRatherThanHardcodedFixtureId() {
        val runtime = DemoKitchenRuntime(EpochClock { 100 })
        val home = runtime.dispatch(runtime.initialState(), AppAction.ContinueAsGuest)
        val custom = home.copy(plates = listOf(home.plates.first().copy(id = "new-visible-source")))
        assertEquals("new-visible-source", runtime.featuredPlateId(custom))
        assertNull(runtime.featuredPlateId(home.copy(plates = emptyList())))
        assertNull(runtime.featuredPlateId(custom.copy(plates = custom.plates.map { it.copy(allowMakeMine = false) })))
    }

    @Test fun featuredSourceRechecksExpiryAndBlockState() {
        var now = 100L
        val runtime = DemoKitchenRuntime(EpochClock { now })
        val home = runtime.dispatch(runtime.initialState(), AppAction.ContinueAsGuest)
        val todayOnly = home.copy(plates = listOf(home.plates.first()))
        assertNotNull(runtime.featuredPlateId(todayOnly))
        now += 24 * 60 * 60 * 1000L
        assertNull(runtime.featuredPlateId(todayOnly))
        now = 100
        assertNull(runtime.featuredPlateId(todayOnly.copy(session = todayOnly.session!!.copy(blockedUserIds = setOf(todayOnly.plates.first().authorId)))))
    }
}
