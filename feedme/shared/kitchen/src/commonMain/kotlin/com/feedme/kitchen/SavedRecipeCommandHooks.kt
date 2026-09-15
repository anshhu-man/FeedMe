package com.feedme.kitchen

import com.feedme.core.ports.ApiReply
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SessionLease
import com.feedme.sync.CommandIntent
import com.feedme.sync.ExecutionDecision

/**
 * Required domain checks for the two basic cookbook mutations only. The session calls these
 * hooks only for saveRecipe/deleteSavedRecipe; supplying them never admits cooking, collection,
 * feedback or sharing operations. Queue policy still decides explicit confirmation and replay.
 * Implementations must correlate the original intent and retain learned negative evidence;
 * a successful observation is not a receipt application or a copy-rights grant.
 */
interface SavedRecipeCommandHooks {
    suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision
    suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit>
}
