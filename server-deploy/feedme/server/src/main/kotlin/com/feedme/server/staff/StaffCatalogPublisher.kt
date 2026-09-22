package com.feedme.server.staff

import com.feedme.server.catalog.*
import com.feedme.server.db.PgTransactions
import java.sql.Connection
import java.util.UUID

/** Each invocation is bound to an already signature-verified workforce subject and one exact
 * independent approval. Database roles, policy, review, expiry and revocation are checked in
 * the SAME transaction as the existing catalog write and again after its locks/writes.
 * This is not installed in the consumer HTTP assembly and grants it no publishing privilege. */
internal class StaffCatalogPublisher(
    private val environment: String,
    private val transactions: PgTransactions,
    private val registry: StaffPublicationRegistry,
) {
    fun publish(subject: VerifiedWorkforceSubject, approvalId: UUID,
        original: StaffPublicationOriginal): StaffPublicationReceipt {
        val authority = ApprovedAuthority(subject, approvalId, original.intent)
        val journal = RecipeCatalogJournal(environment, transactions, authority)
        return when (original) {
            is StaffPublicationOriginal.Ingredients -> {
                val store = IngredientCatalogStore(environment, transactions, authority,
                    IngredientCatalogLimits(StaffPublicationIntent.MAX_BYTES, 1024, 300),
                    IngredientSearchMode.LITERAL_PREFIX_V1)
                val result = store.publish(original.value)
                StaffPublicationReceipt(result.releaseId, result.requestSha256, result.replayed, result.revision)
            }
            is StaffPublicationOriginal.Recipes -> {
                val result = journal.publish(original.value)
                StaffPublicationReceipt(result.releaseId, result.requestSha256, result.replayed, result.revision)
            }
            is StaffPublicationOriginal.CopyGrant -> {
                val result = RecipeCopyRightsStore(environment, transactions, journal, authority).publish(original.value)
                StaffPublicationReceipt(result.originalId, result.requestSha256, result.replayed, null)
            }
            is StaffPublicationOriginal.CopyRevocation -> {
                val result = RecipeCopyRightsStore(environment, transactions, journal, authority).revoke(original.value)
                StaffPublicationReceipt(result.originalId, result.requestSha256, result.replayed, null)
            }
        }
    }

    private inner class ApprovedAuthority(val subject: VerifiedWorkforceSubject, val approvalId: UUID,
        val intent: StaffPublicationIntent) : IngredientPublicationAuthority,
        RecipeChangesetPublicationAuthority, RecipeCopyRightsPublicationAuthority {
        private fun checkOriginal(actual: StaffPublicationIntent) {
            check(actual.kind == intent.kind && actual.exactDocument == intent.exactDocument &&
                actual.requestSha256 == intent.requestSha256 && actual.publisherId == intent.publisherId &&
                actual.reviewerId == intent.reviewerId) { "Publication original mismatch" }
        }
        private fun lock(c: Connection, selected: String, actual: StaffPublicationIntent) {
            check(selected == environment) { "Publication environment mismatch" }
            checkOriginal(actual)
            registry.lockApproved(c, selected, subject, approvalId, intent)
        }
        private fun revalidate(c: Connection, selected: String, actual: StaffPublicationIntent) {
            check(selected == environment) { "Publication environment mismatch" }
            checkOriginal(actual)
            registry.revalidateApproved(c, selected, subject, approvalId, intent)
        }
        override fun lockPublication(connection: Connection, environment: String, original: IngredientReleaseRequest) =
            lock(connection, environment, StaffPublicationOriginal.Ingredients(original).intent)
        override fun revalidatePublication(connection: Connection, environment: String, original: IngredientReleaseRequest) =
            revalidate(connection, environment, StaffPublicationOriginal.Ingredients(original).intent)
        override fun lockPublication(connection: Connection, environment: String, original: RecipeCatalogChangeset) =
            lock(connection, environment, StaffPublicationOriginal.Recipes(original).intent)
        override fun revalidatePublication(connection: Connection, environment: String, original: RecipeCatalogChangeset) =
            revalidate(connection, environment, StaffPublicationOriginal.Recipes(original).intent)
        override fun lockPublication(connection: Connection, environment: String, original: RecipeCopyGrant) =
            lock(connection, environment, StaffPublicationOriginal.CopyGrant(original).intent)
        override fun revalidatePublication(connection: Connection, environment: String, original: RecipeCopyGrant) =
            revalidate(connection, environment, StaffPublicationOriginal.CopyGrant(original).intent)
        override fun lockRevocation(connection: Connection, environment: String, original: RecipeCopyRevocation) =
            lock(connection, environment, StaffPublicationOriginal.CopyRevocation(original).intent)
        override fun revalidateRevocation(connection: Connection, environment: String, original: RecipeCopyRevocation) =
            revalidate(connection, environment, StaffPublicationOriginal.CopyRevocation(original).intent)
    }
}

internal class StaffPublicationReceipt(val originalId: UUID, val requestSha256: String,
    val replayed: Boolean, val revision: Long?) {
    override fun toString() = "StaffPublicationReceipt(<redacted>)"
}
