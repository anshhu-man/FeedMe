package com.feedme.mealflow.social

import com.feedme.contracts.CanonicalBodyValidator
import com.feedme.contracts.ContractValidationResult
import com.feedme.contracts.WireDocument

/** Presence, not consent. Aggregates below snapshot supported collection payloads; a generic
 * wrapper alone does not make an arbitrary caller-owned T deeply immutable. Null is not allowed. */
sealed class OptionalValue<out T : Any> private constructor() {
    data object Absent : OptionalValue<Nothing>()
    class Present<T : Any>(val value: T) : OptionalValue<T>() {
        override fun toString() = "OptionalValue.Present(<redacted>)"
    }
}

/** Unchanged and Set(false/empty) differ. There is deliberately no Set(null) removal instruction. */
sealed class PatchValue<out T : Any> private constructor() {
    data object Unchanged : PatchValue<Nothing>()
    class Set<T : Any>(val value: T) : PatchValue<T>() {
        override fun toString() = "PatchValue.Set(<redacted>)"
    }
}

/** Fresh target value in canonical decimal notation, bounded by the actual service bigint.
 * Restored original JSON numeric lexemes belong to the immutable original, not this constructor. */
class ExactPostVersion(val decimal: String) {
    init {
        publicationValueRequire(decimal.length in 1..19 && decimal.matches(Regex("[1-9][0-9]*")))
        publicationValueRequire(decimal.length < 19 || decimal <= "9223372036854775807")
    }
    override fun equals(other: Any?) = other is ExactPostVersion && decimal == other.decimal
    override fun hashCode() = decimal.hashCode()
    override fun toString() = "ExactPostVersion(<redacted>)"
}

sealed class PublicationAudience private constructor() {
    data object OnlyYou : PublicationAudience()
    class Circles(orderedCircleIds: List<String>) : PublicationAudience() {
        private val ids = publicationIds(orderedCircleIds).also { publicationValueRequire(it.isNotEmpty()) }
        val orderedCircleIds: List<String> get() = ids.toList()
        override fun toString() = "PublicationAudience.Circles(<redacted>)"
    }
}

sealed class PublicationAttachmentSource private constructor() {
    class RecipeVersion(val recipeVersionId: String) : PublicationAttachmentSource() {
        init { publicationId(recipeVersionId) }
        override fun toString() = "PublicationAttachmentSource.RecipeVersion(<redacted>)"
    }
    class Plan(val planId: String) : PublicationAttachmentSource() {
        init { publicationId(planId) }
        override fun toString() = "PublicationAttachmentSource.Plan(<redacted>)"
    }
    /** Complete strict canonical RecipeDraft. No title-only surrogate or rights grant. */
    class Personal(val recipeDraft: WireDocument) : PublicationAttachmentSource() {
        init {
            val bytes = recipeDraft.encodeUtf8()
            publicationValueRequire(bytes.size <= 65_536)
            publicationValueRequire(publicationSchemas.validateSchema("RecipeDraft", bytes) == ContractValidationResult.Valid)
        }
        override fun toString() = "PublicationAttachmentSource.Personal(<redacted>)"
    }
}

enum class AttachmentReviewStatus { REVIEWED, PERSONAL }
enum class AttachmentRightsBasis { CATALOG_REDISTRIBUTABLE, CREATOR_ORIGINAL }

/** Submitted descriptors, never verified source ownership/redistribution/recall authority. */
class PublicationAttachment(val source: PublicationAttachmentSource, confirmedChanges: List<String>,
    val reviewStatus: AttachmentReviewStatus, val rightsBasis: AttachmentRightsBasis) {
    private val changes = publicationStrings(confirmedChanges)
    val confirmedChanges: List<String> get() = changes.toList()
    init {
        val personal = source is PublicationAttachmentSource.Personal
        publicationValueRequire(reviewStatus == if (personal) AttachmentReviewStatus.PERSONAL else AttachmentReviewStatus.REVIEWED)
        publicationValueRequire(rightsBasis == if (personal) AttachmentRightsBasis.CREATOR_ORIGINAL else AttachmentRightsBasis.CATALOG_REDISTRIBUTABLE)
    }
    override fun toString() = "PublicationAttachment(<redacted>)"
}

/** Actual supplied disclosure display data, not a configured-policy verification or live consent.
 * No default text/version, policy-fetch operation or publication capability is supplied here. */
class PublicationDisclosure(val version: String, val text: String) {
    init {
        publicationStringBytes(version); publicationStringBytes(text)
        publicationValueRequire(version.isNotBlank() && text.isNotBlank())
        publicationValueRequire(version.none(Char::isISOControl))
    }
    override fun toString() = "PublicationDisclosure(<redacted>)"
}

/** Complete detached choice values. The name describes content for a future review; creating
 * this object does NOT establish an actual review ticket, principal, media readiness or authority. */
class ReviewedPostChoices(val caption: String, val altText: OptionalValue<String>, orderedMediaIds: List<String>,
    val audience: PublicationAudience, val keepOnPlate: Boolean, val attachment: OptionalValue<PublicationAttachment>,
    val allowRecipeSaves: Boolean, val disclosure: PublicationDisclosure, val sourcePostId: OptionalValue<String>) {
    private val media = publicationIds(orderedMediaIds)
    val orderedMediaIds: List<String> get() = media.toList()
    init {
        publicationText(caption)
        if (altText is OptionalValue.Present) publicationText(altText.value)
        if (sourcePostId is OptionalValue.Present) publicationId(sourcePostId.value)
        publicationValueRequire(!allowRecipeSaves || attachment is OptionalValue.Present)
    }
    override fun toString() = "ReviewedPostChoices(<redacted>)"
}

/** A branch/value, never proof of root absence, server ownership or a live editable version. */
sealed class PublicationTarget private constructor(val clientDraftId: String, val localRevision: Long) {
    init { publicationId(clientDraftId); publicationValueRequire(localRevision > 0) }
    class DirectLocal(clientDraftId: String, localRevision: Long) : PublicationTarget(clientDraftId, localRevision) {
        override fun toString() = "PublicationTarget.DirectLocal(<redacted>)"
    }
    class SavedDraft(clientDraftId: String, localRevision: Long, val draftId: String,
        val draftVersion: ExactPostVersion, val etag: String) : PublicationTarget(clientDraftId, localRevision) {
        init { publicationId(draftId); publicationValueRequire(etag == "\"${draftVersion.decimal}\"") }
        override fun toString() = "PublicationTarget.SavedDraft(<redacted>)"
    }
}

/** Pure PATCH value vocabulary only. No encoder, transformation, store migration or live Save
 * ticket is introduced in this package. A target-bound coordinator must check a Set client ID. */
class ReviewedDraftPatch(val clientDraftId: PatchValue<String>, val caption: PatchValue<String>,
    val altText: PatchValue<String>, orderedMediaIds: PatchValue<List<String>>,
    val attachment: PatchValue<PublicationAttachment>, val removeAttachment: PatchValue<Boolean>,
    val audience: PatchValue<PublicationAudience>, val keepOnPlate: PatchValue<Boolean>,
    val allowRecipeSaves: PatchValue<Boolean>, val disclosure: PatchValue<PublicationDisclosure>,
    val sourcePostId: PatchValue<String>) {
    private val media: PatchValue<List<String>> = when (orderedMediaIds) {
        PatchValue.Unchanged -> PatchValue.Unchanged
        is PatchValue.Set -> PatchValue.Set(publicationIds(orderedMediaIds.value))
    }
    val orderedMediaIds: PatchValue<List<String>> get() = when (val snapshot = media) {
        PatchValue.Unchanged -> PatchValue.Unchanged
        is PatchValue.Set -> PatchValue.Set(snapshot.value.toList())
    }
    init {
        if (clientDraftId is PatchValue.Set) publicationId(clientDraftId.value)
        if (caption is PatchValue.Set) publicationText(caption.value)
        if (altText is PatchValue.Set) publicationText(altText.value)
        if (sourcePostId is PatchValue.Set) publicationId(sourcePostId.value)
        publicationValueRequire(!(attachment is PatchValue.Set && removeAttachment is PatchValue.Set && removeAttachment.value))
        // allowRecipeSaves may be Set(true) while attachment is Unchanged: only the real
        // baseline transformer can decide whether an attachment will exist after this PATCH.
    }
    override fun toString() = "ReviewedDraftPatch(<redacted>)"
}

/** Required device-resource bounds; no production quota/default or identity eviction policy.
 * Record/retained-count/lifetime bounds are configuration for future owners, not implemented
 * retention/expiry behavior. Encoder enforces only the relevant per-input/encoded byte bounds. */
class PostPublicationClientPolicy(val maxRecordBytes: Int, val maxOriginalBytes: Int, val maxResponseBytes: Int,
    val maxRetainedRoots: Int, val maxIssuedCommandIds: Int, val maxUnsubmittedRemainders: Int,
    val maxMediaSelections: Int, val maxCircleSelections: Int, val maxConfirmedChanges: Int,
    val maxAttachmentBytes: Int, val maxDisclosureBytes: Int, val reviewLifetimeMillis: Long) {
    init {
        publicationValueRequire(maxRecordBytes in 1..1_048_576 && maxOriginalBytes in 1..65_536 && maxResponseBytes in 1..262_144)
        publicationValueRequire(maxOriginalBytes <= maxRecordBytes && maxResponseBytes <= maxRecordBytes)
        publicationValueRequire(maxRetainedRoots in 1..4096 && maxIssuedCommandIds in 1..4096 && maxUnsubmittedRemainders in 1..4096)
        publicationValueRequire(maxMediaSelections in 1..4096 && maxCircleSelections in 1..4096 && maxConfirmedChanges in 1..4096)
        publicationValueRequire(maxAttachmentBytes in 1..maxOriginalBytes && maxDisclosureBytes in 1..maxRecordBytes)
        publicationValueRequire(reviewLifetimeMillis in 1..300_000)
    }
    override fun toString() = "PostPublicationClientPolicy(<redacted>)"
}

internal val publicationSchemas: CanonicalBodyValidator by lazy { CanonicalBodyValidator.bundled() }
internal fun publicationValueRequire(condition: Boolean) { require(condition) { "Invalid publication value" } }
internal fun publicationId(value: String) {
    publicationValueRequire(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
}
private fun publicationIds(values: List<String>): List<String> {
    publicationValueRequire(values.size <= 4096)
    val copy = values.toList()
    copy.forEach(::publicationId)
    publicationValueRequire(copy.map { it.lowercase() }.distinct().size == copy.size)
    return copy // UUID spelling and order are deliberately NOT normalized in a new request.
}
private fun publicationStrings(values: List<String>): List<String> {
    publicationValueRequire(values.size <= 4096)
    val copy = values.toList(); var remaining = 1_048_576
    for (value in copy) {
        val bytes = publicationStringBytes(value)
        publicationValueRequire(bytes <= remaining); remaining -= bytes
    }
    return copy
}
private fun publicationText(value: String) {
    publicationStringBytes(value)
    var scalars = 0; var at = 0
    while (at < value.length) { at += if (value[at].isHighSurrogate()) 2 else 1; scalars++ }
    publicationValueRequire(scalars <= 500)
}
/** Measures strict UTF-8 without allocating a second huge string/byte array. */
internal fun publicationStringBytes(value: String): Int {
    publicationValueRequire(value.length <= 1_048_576)
    var bytes = 0; var at = 0
    while (at < value.length) {
        val c = value[at++]
        bytes += when {
            c.isHighSurrogate() -> { publicationValueRequire(at < value.length && value[at].isLowSurrogate()); at++; 4 }
            c.isLowSurrogate() -> { publicationValueRequire(false); 0 }
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            else -> 3
        }
        publicationValueRequire(bytes <= 1_048_576)
    }
    return bytes
}
