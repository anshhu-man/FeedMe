package com.feedme.mealflow.timers

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Internal fixed CookingFlowController admission for ONE explicit mutation, not a public gate
 * or delivery policy. Its lifetime does not extend into the adapter's retained Handler scope.
 * The synchronous fence checks the captured cooking operation/selected pin; beforeSchedule
 * rechecks persisted selection and (for Start/Resume) pending preferences without reacquiring
 * the cooking operation mutex. Independent repository/runtime checks are never replaced.
 */
internal class CookingTimerActionAdmission(
    val check: () -> Unit,
    val beforeSchedule: suspend () -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CookingTimerActionAdmission>
}

/** Independent retained timer-page lifetime. The cooking admission cannot replace this fence.
 * Marking an attempted mutation is conservative: failure afterwards is never classified as a
 * successful action, even if later reads show matching bytes. No long-lived delivery inherits it.
 */
internal class CookingTimerFlowLifetime(val check: () -> Unit) : AbstractCoroutineContextElement(Key) {
    var mutationAttempted = false
    companion object Key : CoroutineContext.Key<CookingTimerFlowLifetime>
}
