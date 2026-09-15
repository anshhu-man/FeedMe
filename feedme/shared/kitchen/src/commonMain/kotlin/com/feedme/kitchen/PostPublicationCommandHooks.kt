package com.feedme.kitchen

import com.feedme.core.ports.ApiReply
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SessionLease
import com.feedme.sync.CommandIntent
import com.feedme.sync.ExecutionDecision

/**
 * Purpose-fixed checks for publishPost ONLY. Supplying this hook does not admit draft editing,
 * upload/signed capabilities, post editing/deletion, audience mutations or sibling commands.
 * Publication keeps the shared social lane and requires fresh explicit confirmation.
 *
 * The owner must bind the exact original request, ACCOUNT lease, scope/origin, reviewed root
 * and operation generation. New-publication eligibility and attempted-original replay checks
 * are distinct: a committed original may already have closed its root and attached its media.
 * The server independently checks current authority and exact original receipt disclosure.
 *
 * Queue callbacks must not re-enter the controller/composition operation mutex. A successful
 * observer permits receipt persistence only; it is not a publication/domain-application ACK.
 */
interface PostPublicationCommandHooks {
    suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision
    suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit>
}
