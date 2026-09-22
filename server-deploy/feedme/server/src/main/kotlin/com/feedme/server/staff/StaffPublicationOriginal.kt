package com.feedme.server.staff

import com.feedme.server.catalog.*
import java.util.UUID

internal enum class StaffPublicationKind(val wire: String) {
    INGREDIENTS("ingredients"), RECIPES("recipes"), COPY_GRANT("copy-grant"), COPY_REVOCATION("copy-revocation")
}

/** Comparison material, not authenticated review. Only the original domain codecs produce
 * these values. Neither an operator-supplied digest nor arbitrary JSON can authorize a write. */
internal class StaffPublicationIntent private constructor(
    val kind: StaffPublicationKind,
    val exactDocument: String,
    val requestSha256: String,
    val publisherId: UUID,
    val reviewerId: UUID?,
    val publicationPolicyVersion: String? = null,
) {
    init {
        require(exactDocument.encodeToByteArray(throwOnInvalidSequence = true).size in 1..MAX_BYTES)
        require(catalogSha(exactDocument) == requestSha256)
        require(reviewerId == null || publisherId != reviewerId)
    }
    override fun toString() = "StaffPublicationIntent(<redacted>)"
    companion object {
        const val MAX_BYTES = 1_048_576
        fun from(value: IngredientReleaseRequest) = StaffPublicationIntent(StaffPublicationKind.INGREDIENTS,
            value.exactDocument, value.requestSha256, value.publisherId, value.reviewerId, value.publicationPolicyVersion)
        fun from(value: RecipeCatalogChangeset): StaffPublicationIntent {
            // This workforce command profile has no separate recall-actor authority.
            // Refuse the new form rather than substituting its original publisher.
            require(value.recallActorId == null)
            return StaffPublicationIntent(StaffPublicationKind.RECIPES,
                value.exactDocument, value.requestSha256, value.publisherId, value.reviewerId)
        }
        fun from(value: RecipeCopyGrant) = StaffPublicationIntent(StaffPublicationKind.COPY_GRANT,
            value.exactDocument, value.requestSha256, value.publisherId, value.reviewerId)
        fun from(value: RecipeCopyRevocation) = StaffPublicationIntent(StaffPublicationKind.COPY_REVOCATION,
            value.exactDocument, value.requestSha256, value.operatorId, null)
    }
}

/** Keep these original bytes and IDs after an unknown commit; never rebase a predecessor or
 * generate replacement IDs automatically. Format-1 recipe snapshots are intentionally not a
 * new publication option: production publishing uses the existing incremental journal. */
internal sealed class StaffPublicationOriginal {
    abstract val intent: StaffPublicationIntent
    class Ingredients(val value: IngredientReleaseRequest) : StaffPublicationOriginal() {
        override val intent = StaffPublicationIntent.from(value)
    }
    class Recipes(val value: RecipeCatalogChangeset) : StaffPublicationOriginal() {
        override val intent = StaffPublicationIntent.from(value)
    }
    class CopyGrant(val value: RecipeCopyGrant) : StaffPublicationOriginal() {
        override val intent = StaffPublicationIntent.from(value)
    }
    class CopyRevocation(val value: RecipeCopyRevocation) : StaffPublicationOriginal() {
        override val intent = StaffPublicationIntent.from(value)
    }
    final override fun toString() = "StaffPublicationOriginal(<redacted>)"

    companion object {
        fun decode(kind: StaffPublicationKind, bytes: ByteArray): StaffPublicationOriginal {
            require(bytes.size in 1..StaffPublicationIntent.MAX_BYTES)
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            return when (kind) {
                StaffPublicationKind.INGREDIENTS -> Ingredients(decodeRelease(text))
                StaffPublicationKind.RECIPES -> Recipes(decodeRecipeChangeset(text))
                StaffPublicationKind.COPY_GRANT -> CopyGrant(RecipeCopyGrant.decode(text))
                StaffPublicationKind.COPY_REVOCATION -> CopyRevocation(RecipeCopyRevocation.decode(text))
            }
        }
    }
}
