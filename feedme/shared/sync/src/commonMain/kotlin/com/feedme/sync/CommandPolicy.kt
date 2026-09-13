package com.feedme.sync

import com.feedme.contracts.ContractCatalog
import com.feedme.contracts.ContractSurface
import com.feedme.core.ports.ApiCall

/** Versioned client scheduling policy, not permission to perform the corresponding feature. */
internal class CommandPolicy(private val catalog: ContractCatalog) {
    fun resumption(operationId: String): CommandResumption? {
        val operation = catalog.operationFor(ContractSurface.MOBILE, operationId) ?: return null
        if (operation.method !in setOf("POST", "PATCH", "PUT", "DELETE") || !operation.idempotencyRequired ||
            operation.module == "identity" || operationId in separateWorkflows) return null
        return if (operationId in automatic) CommandResumption.AUTOMATIC_WITH_PREFLIGHT else CommandResumption.FRESH_CONFIRMATION
    }

    /** FIFO per aggregate/family; independent lanes may continue while another needs resolution. */
    fun lane(call: ApiCall): String {
        val module = checkNotNull(catalog.operation(call.operationId)).module
        val path = call.pathParameters
        return when {
            module == "cooking" && path["sessionId"] != null -> "cooking:${path.getValue("sessionId").lowercase()}"
            module == "conversations" && path["threadId"] != null -> "thread:${path.getValue("threadId").lowercase()}"
            module == "social" && path["postId"] != null -> "post:${path.getValue("postId").lowercase()}"
            else -> module
        }
    }

    companion object {
        // Each still needs its feature-specific gate: pinned cook, stricter local suppression,
        // owned save/grant/entitlement, latest reaction intent, message eligibility or read watermark.
        // See CLIENT_COMMAND_POLICY_NOTES.md; no operation-wide grant is inferred from idempotency.
        private val automatic = setOf(
            "updateCookSession", "completeCookSession", "updatePreferences", "upsertPantryItem", "removePantryItem",
            "createFeedback", "updateFeedback", "updateMemory", "deleteMemory", "addCollectionItem", "removeCollectionItem",
            "saveRecipe", "createCollection", "updateCollection", "reorderCollection", "markNotificationRead", "markNotificationsRead",
            "updateNotificationSettings", "updatePrivacySettings", "updateHouseholdPreferences", "setReaction", "removeReaction",
            "sendMessage", "savePostRecipe",
        )
        // Credential, signed-capability, native purchase and recent-reauth workflows have separate
        // lifecycles. Their exclusion from this journal does not remove their product features.
        private val separateWorkflows = setOf(
            "prepareMediaUpload", "completeMediaUpload", "getMediaAccess", "registerPushDevice",
            "reconcileEntitlements", "requestAccountExport",
        )
    }
}
