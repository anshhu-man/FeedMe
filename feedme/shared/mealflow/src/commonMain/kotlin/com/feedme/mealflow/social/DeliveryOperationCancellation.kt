package com.feedme.mealflow.social

import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job

/** Proposed stable-public-API cancellation bridge, not a replacement caller Job.
 * This private owned Job has no running body and no child work is launched in it. Its original
 * caller parent therefore cancels it promptly even while other work is noncooperative. Normal
 * parent completion instead waits until this call's finally explicitly completes the child.
 * The completion handler is atomics-only, nonthrowing, nonblocking and idempotent at its target.
 *
 * Parent's private cancellation state and our gate CAS remain distinct. The protocol still
 * requires final cooperative ensureActive and exact owned StateFlow publication before ACK.
 * No Job is invented for a context that was originally uncancellable. Every terminal call must
 * dispose the handler then complete this empty child, releasing the structured-parent link.
 */
internal object DeliveryOperationCancellation {
    fun register(originalCallerJob: Job?, cancel: () -> Unit): DisposableHandle? {
        if (originalCallerJob == null) return null
        val child = Job(originalCallerJob)
        val handler = child.invokeOnCompletion { cancel() }
        return DisposableHandle {
            // Disposal is not callback joining. A racing callback remains harmless because the
            // target atomically cancels only pending authorization, never historical delivery.
            handler.dispose()
            child.complete()
        }
    }
}
