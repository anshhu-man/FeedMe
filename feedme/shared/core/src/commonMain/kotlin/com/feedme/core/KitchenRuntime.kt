package com.feedme.core

import com.feedme.core.ports.EpochClock

/** The native composition root explicitly chooses this development runtime. No implicit fixtures. */
class DemoKitchenRuntime(private val clock: EpochClock) {
    fun nowMillis(): Long = clock.nowMillis().also { require(it >= 0) }
    fun initialState(): AppState = DemoFixtures.initialState(nowMillis())
    fun dispatch(state: AppState, action: AppAction): AppState {
        require(state.mode == OperatingMode.DEMO) { "Demo runtime cannot operate on production state." }
        return reduce(state, action, nowMillis())
    }

    /** Pick a visible source from state, not a fixture ID hard-coded in the screen. */
    fun featuredPlateId(state: AppState): String? = visibleTodayPlates(state, nowMillis())
        .firstOrNull { it.allowMakeMine && state.recipes.any { recipe -> recipe.id == it.recipeId } }?.id
}
