package com.feedme.server.identity

import com.feedme.server.db.CommandActor
import com.feedme.server.db.PrincipalScope
import java.util.UUID

/** Pure namespace attribution, NOT deletion authority, retention policy, or proof of a complete
 * personal-data inventory. Construct only from the actual locked accepted deletion job. The
 * caller still owns receipt locking, original-evidence checks, dependencies, and replay policy.
 * In particular OWNED_CORE alone does not authorize erasure. V044 may remove the generic
 * requestAccountDeletion cache row only while retaining V041 acceptance/device originals
 * and atomically committing its separate immutable core-erased checkpoint.
 *
 * The existing account UUID and private-principal UUID scopes share the same "account" tag.
 * A reviewed operation family distinguishes them; UUID or response JSON alone cannot do so.
 * Unknown operations in either matching scope and malformed scopes require an explicit hold.
 * OTHER_OWNER means outside this job's two declared namespaces, not necessarily unrelated to
 * the person: merged-guest, staff-issuer/subject, and references in others' receipts need their
 * separate inventories. No guest/staff identity is inferred by UUID equality.
 */
internal class AccountErasureReceiptOwnership(environment: String, accountId: UUID, principalId: UUID) {
    private val accountScope = PrincipalScope(environment, CommandActor.ACCOUNT, accountId).storageKey
    private val privateScope = PrincipalScope(environment, CommandActor.ACCOUNT, principalId).storageKey

    enum class Classification { OWNED_CORE, RELATED_UNSUPPORTED, OTHER_OWNER, UNKNOWN }
    enum class OperationFamily { ACCOUNT_CORE, PRIVATE_CORE, ACCOUNT_UNSUPPORTED }

    fun classify(principalScope: String, operationId: String): Classification {
        // Never trim, case-fold, parse a permissive UUID, or rewrite an existing receipt key.
        if (!canonicalScope.matches(principalScope)) return Classification.UNKNOWN
        val account = principalScope == accountScope
        val privatePrincipal = principalScope == privateScope
        if (!account && !privatePrincipal) return Classification.OTHER_OWNER
        return when (operationFamilies[operationId]) {
            OperationFamily.ACCOUNT_CORE -> if (account) Classification.OWNED_CORE else Classification.OTHER_OWNER
            OperationFamily.PRIVATE_CORE -> if (privatePrincipal) Classification.OWNED_CORE else Classification.OTHER_OWNER
            OperationFamily.ACCOUNT_UNSUPPORTED -> if (account) Classification.RELATED_UNSUPPORTED else Classification.OTHER_OWNER
            null -> Classification.UNKNOWN
        }
    }

    override fun toString() = "AccountErasureReceiptOwnership(<redacted>)"

    companion object {
        private val canonicalScope = Regex(
            "[a-z][a-z0-9-]{0,39}:(?:account|guest|staff):" +
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        )

        /** Closed source-reviewed writers through V088; canonical API existence alone is not
         * evidence of a deployed receipt producer or its account/private ID namespace.
         * Guest reconstructions/MakeAgain use the same private operation names with the guest
         * tag. No production STAFF CommandIdentity writer was found; staff uses separate ledgers.
         */
        private val operationFamilies: Map<String, OperationFamily> = buildMap {
            // identity/AccountProfileStore.kt and identity/AccountDeletionStore.kt.
            for (operation in listOf("bootstrapAccount", "logoutSession", "revokeSession", "acceptAccountTerms", "updateMe", "requestAccountDeletion"))
                put(operation, OperationFamily.ACCOUNT_CORE)

            // Exact-owned unpublished, media-free drafts are admitted only after V048's
            // full dependency checks. Classification does not bypass any inventory hold.
            for (operation in listOf("createPostDraft", "updatePostDraft", "deletePostDraft"))
                put(operation, OperationFamily.ACCOUNT_CORE)

            // social/conversations/AccountConversationStore: exact private account settings.
            put("updatePrivacySettings", OperationFamily.ACCOUNT_CORE)
            put("updateNotificationSettings", OperationFamily.ACCOUNT_CORE)

            // identity/AccountNotificationInboxStore and social/posts/AccountPostReactionStore.
            // V076/V088 admit only the exact deleting account's read markers/reaction commands
            // after their full row/event dependency inventory passes.
            for (operation in listOf("markNotificationRead", "markNotificationsRead", "setReaction", "removeReaction"))
                put(operation, OperationFamily.ACCOUNT_CORE)

            // kitchen/KitchenStore.kt; planning/PlansStore.kt, DerivedPlanMaterial.kt,
            // RootRecipePlanMaterial.kt; cooking/CookingStore.kt; memory/SavedRecipeStore.kt,
            // FeedbackStore.kt, MemoryStore.kt. AccountDerived/RootRecipe and GuestPlans stores
            // execute those same identities; guest preparation/verifier/MakeAgain reconstruct
            // only createPlan, completeCookSession, saveRecipe, and createFeedback identities.
            for (operation in listOf(
                "updatePreferences", "upsertPantryItem", "removePantryItem",
                "createPlan", "nextPlan", "adaptPlan", "simplifyPlan",
                "createCookSession", "updateCookSession", "completeCookSession",
                "saveRecipe", "savePostRecipe", "deleteSavedRecipe", "createFeedback", "updateFeedback", "deleteFeedback",
                "updateMemory", "deleteMemory", "createReuseOptions",
            )) put(operation, OperationFamily.PRIVATE_CORE)

            // social/CirclesStore.kt (including its leave/remove helper), media/MediaStore.kt,
            // social/drafts/PostDraftStore.kt, social/posts/PostPublicationStore.kt,
            // social/AccountBlockStore.kt, social/reports/AccountReportStore.kt,
            // social/reciperequests/AccountRecipeRequestStore.kt. Shared request/response
            // history remains unsupported; cancellation is not an erasure grant.
            for (operation in listOf(
                "createCircle", "updateCircle", "updateCircleMember", "leaveCircle", "removeCircleMember",
                "transferCircleOwnership", "deleteCircle", "createInvitation", "acceptInvitation", "revokeInvitation",
                "prepareMediaUpload", "completeMediaUpload", "deleteDraftMedia",
                "publishPost", "updatePost", "deletePost",
                "blockUser", "unblockUser", "createReport", "createThread", "sendMessage", "markThreadRead",
                "requestRecipe", "respondToRecipeRequest", "cancelRecipeRequest", "requestAccountExport",
            )) put(operation, OperationFamily.ACCOUNT_UNSUPPORTED)
        }

        // A detached review/test view: callers cannot mutate the classifier's closed mapping.
        fun reviewedOperations(): Map<String, OperationFamily> = operationFamilies.toMap()
    }
}
