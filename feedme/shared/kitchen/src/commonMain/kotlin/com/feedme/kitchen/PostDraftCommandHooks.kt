package com.feedme.kitchen

import com.feedme.core.ports.ApiReply
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SessionLease
import com.feedme.sync.CommandIntent
import com.feedme.sync.ExecutionDecision

/**
 * Required domain checks for createPostDraft/updatePostDraft/deletePostDraft ONLY. Supplying
 * these hooks never admits publication, attachment/media capabilities, audiences or another
 * feature's commands. All three mutations still require the queue's fresh-confirmation policy.
 *
 * The retained controller must revalidate the exact original request, owner/origin and local
 * revision. The server remains responsible for current account/device and content authority.
 * Observations run before outcome persistence; failure retains the sent attempt as unknown.
 * Neither Ready nor an observed reply is consent to publish or a domain-application ACK.
 */
interface PostDraftCommandHooks {
    suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision
    suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit>
}
