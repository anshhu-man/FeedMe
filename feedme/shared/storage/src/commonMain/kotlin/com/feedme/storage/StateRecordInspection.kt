package com.feedme.storage

import com.feedme.core.ports.PrivateRecord

/**
 * Private diagnostic snapshot of one requested owner/record, not authority to activate a session.
 * Null target means that exact owner was missing or inactive; it never means the database is empty.
 * A target with a null record means the active owner's requested record was absent or deleted.
 * Keep the authenticated retirement capability and decrypted record out of UI state and logs.
 */
class StateRecordInspection internal constructor(
    val target: StateRetirementTarget?,
    val record: PrivateRecord?,
) {
    override fun toString() = "StateRecordInspection(<redacted>)"
}
