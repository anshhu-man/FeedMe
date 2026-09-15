package com.feedme.development.progress

/** Progress-variant fault-injection diagnostics only. No owner, identity, payload or native path
 * is supplied. The observer cannot replace native factories or acknowledge a skipped close. */
internal enum class ProgressNativeStage {
    CONTROL_ACQUIRED, WORK_ACQUIRED, DATA_ACQUIRED, CREDENTIALS_ACQUIRED, RUNTIME_ACQUIRED,
    BEFORE_RUNTIME_CLOSE, BEFORE_RECOVERY_CLOSE, BEFORE_PROBE_CLOSE, BEFORE_WORK_CLOSE,
    BEFORE_DATA_CLOSE, BEFORE_CREDENTIALS_CLOSE, BEFORE_CONTROL_CLOSE, BEFORE_RESERVATION_RELEASE,
}
