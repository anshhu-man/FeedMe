package com.feedme.mealflow.timers

import android.os.SystemClock
import com.feedme.kitchen.CookingTimerClock
import com.feedme.kitchen.CookingTimerClockReading
import java.util.UUID

/**
 * Local estimates, not a trusted server clock. elapsedRealtime includes deep sleep; the wall
 * clock can change. Continuity is deliberately limited to this process, not inferred from a
 * persisted boot ID, PID, deadline or a previous installation. A new process cannot resume an
 * old monotonic anchor. Instances in this process share the same non-secret continuity marker.
 */
class AndroidProcessCookingTimerClock : CookingTimerClock {
    override fun read(): CookingTimerClockReading = CookingTimerClockReading(
        System.currentTimeMillis(), SystemClock.elapsedRealtime(), processContinuity,
    )

    override fun toString() = "AndroidProcessCookingTimerClock(<redacted>)"

    private companion object {
        val processContinuity = UUID.randomUUID().toString()
    }
}
