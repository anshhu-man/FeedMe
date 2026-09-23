package com.feedme.android

import com.feedme.session.PrivateAccountSignOutPhase

/** Presentation fences only. Native source checks and exact retirement authority stay in
 * the controller/runtime. Recovery deliberately does not require a now-retired private lease. */
internal enum class EntryDeviceSignOutAction { PREPARE, CANCEL, CONFIRM, RETRY }

internal fun entryDeviceSignOutRouteCurrent(attached: Boolean, expected: Any, current: Any,
    screen: EntryScreen, hasOwner: Boolean, busy: Boolean, hasController: Boolean): Boolean =
    attached && expected === current && screen == EntryScreen.PRIVATE_SIGN_OUT && hasOwner && !busy && hasController

internal fun entryDeviceSignOutActionAvailable(exact: Boolean, busy: Boolean,
    phase: PrivateAccountSignOutPhase, action: EntryDeviceSignOutAction): Boolean = exact && !busy && when (action) {
    EntryDeviceSignOutAction.PREPARE -> phase == PrivateAccountSignOutPhase.IDLE
    EntryDeviceSignOutAction.CANCEL, EntryDeviceSignOutAction.CONFIRM -> phase == PrivateAccountSignOutPhase.REVIEW
    EntryDeviceSignOutAction.RETRY -> phase == PrivateAccountSignOutPhase.RECOVERY_REQUIRED
}
