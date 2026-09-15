package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One controller-owned, one-use review. Constructed lookalikes convey no permission. */
class PreparedRestoredLocalRetention internal constructor() {
    override fun toString() = "PreparedRestoredLocalRetention(<redacted>)"
}

/** Complete immutable display data, not the private journal, original edit proof or consent.
 * Missing historical disclosure text remains missing; recovery supplies no new disclosure. */
class RestoredLocalRetentionSnapshot internal constructor(actual: DraftLocalSnapshotV1) {
    val clientDraftId: String = actual.clientDraftId
    val localRevision: Long = actual.localRevision
    val caption: String = actual.content.caption
    val altText: String? = actual.content.altText
    val exactChoices: WireDocument? = (actual.content as? DraftLocalContentV1.ComposerV2)?.exactChoices
    val historicalDisclosureText: String? = (actual.content as? DraftLocalContentV1.ComposerV2)?.historicalDisclosureText
    val savedDraft: PostDraftObservation? = (actual.serverAssociation as? DraftServerAssociationV1.Observed)?.let {
        PostDraftObservation(it.exactPostDraft, it.etag, true)
    }
    override fun toString() = "RestoredLocalRetentionSnapshot(<redacted>)"
}
class RestoredLocalRetentionPresentation internal constructor(val token: PreparedRestoredLocalRetention,
    val snapshot: RestoredLocalRetentionSnapshot, val preparedAtMillis: Long, val expiresAtMillis: Long,
    private val navigation: RestoredLocalRetentionNavigation) {
    /** Atomics-only display lifetime, not admission, expiry, stored-content validity or ACK.
     * Poll on render and explicit action; this getter alone does not emit UI invalidations. */
    val isCurrentForNavigation: Boolean get() = navigation.isCurrent()
    override fun toString() = "RestoredLocalRetentionPresentation(<redacted>)"
}

/** Exact controller-owned review identity; stale views cannot revive after same-root reopen. */
@OptIn(ExperimentalAtomicApi::class)
internal class RestoredLocalRetentionNavigation(private val current: AtomicReference<Any>, private val token: Any) {
    fun isCurrent(): Boolean = current.load() === token
}
