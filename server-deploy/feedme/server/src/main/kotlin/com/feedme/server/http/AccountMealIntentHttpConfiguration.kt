package com.feedme.server.http

import com.feedme.server.ai.AccountMealIntentService
import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.runtime.ServiceRequestAdmission
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/** Explicit, listener-owned optional account-core configuration. One concurrent inference,
 * with a minimum interval between admitted starts. No queue/retry and no per-account/daily
 * billing allowance: the provider's free-tier hard quota still applies independently. */
class AccountMealIntentHttpConfiguration internal constructor(
    internal val service: AccountMealIntentService,
    internal val verifier: SupabaseUserAccessVerifier,
    private val clock: Clock = Clock.systemUTC(),
    internal val minimumStartIntervalMillis: Long = 1_000,
) {
    private val admission = ServiceRequestAdmission(1)
    private val lastStart = AtomicLong(-1)
    init { require(minimumStartIntervalMillis in 1_000..60_000) { "Invalid interpretation start interval" } }

    internal fun tryStart(): ServiceRequestAdmission.Permit? {
        val permit = admission.tryAcquire() ?: return null
        try {
            val now = clock.millis()
            check(now >= 0) { "Interpretation clock unavailable" }
            val previous = lastStart.get()
            // A backwards clock cannot renew admission. Only the exclusive permit holder updates.
            if (previous >= 0 && (now < previous || now - previous < minimumStartIntervalMillis)) {
                permit.close(); return null
            }
            lastStart.set(now)
            return permit
        } catch (failure: Throwable) { permit.close(); throw failure }
    }

    override fun toString() = "AccountMealIntentHttpConfiguration(<redacted>)"
    internal companion object {
        const val MAX_REQUEST_BYTES = 8_192
        const val MAX_RESPONSE_BYTES = 65_536
    }
}
