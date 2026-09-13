package com.feedme.storage

/**
 * Private snapshot of one requested owner, never an enumeration or a globally-empty assertion.
 * A non-null target names the exact active incarnation. hasRecords includes every owner row,
 * including deleted-record tombstones; no record payload is decrypted by this inspection.
 * Keep the authenticated target private. Retirement must independently recheck that it is empty.
 */
class StateOwnerInspection internal constructor(
    val target: StateRetirementTarget?,
    val hasRecords: Boolean,
) {
    override fun toString() = "StateOwnerInspection(<redacted>)"
}
