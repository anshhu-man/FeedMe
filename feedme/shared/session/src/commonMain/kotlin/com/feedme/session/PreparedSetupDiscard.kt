package com.feedme.session

import com.feedme.core.ports.SessionControlRecord
import com.feedme.core.ports.StorageScope
import com.feedme.storage.StateRetirementTarget

/**
 * Ephemeral confirmation proposal, not serializable cleanup authority or identity proof.
 * Show only the booleans to UI. Explicit confirmation discards selected EMPTY local setup
 * fragments; it neither deletes a cloud account nor signs in or merges a guest. Closing the
 * prompt is a no-op. Reopening/recovering the runtime requires a freshly prepared proposal.
 */
class PreparedSetupDiscard internal constructor(
    internal val owner: Any,
    internal val generation: Any,
    internal val configuration: String,
    internal val evidence: SetupDiscardEvidence,
) {
    val hasCredentials: Boolean get() = evidence.slot.owner != null
    val hasEmptyWork: Boolean get() = evidence.work.originBinding != null
    val hasEmptyPrivateStorage: Boolean get() = evidence.target != null
    override fun toString() = "PreparedSetupDiscard(<redacted>)"
}

internal class SetupDiscardEvidence(
    val control: SessionControlRecord,
    val scope: StorageScope,
    val slot: CredentialSlotState,
    val work: SessionWorkSnapshot,
    val target: StateRetirementTarget?,
) {
    override fun toString() = "SetupDiscardEvidence(<redacted>)"
}
