package com.feedme.android

import com.feedme.session.AccountDeletionScreen

/** Navigation rejection only. Actual native identity and receipt checks stay in the owner. */
internal fun entryAccountSettingsAvailable(configured: Boolean, attached: Boolean, exact: Boolean,
    busy: Boolean, hasOwner: Boolean, source: EntryScreen): Boolean = configured && attached && exact &&
    !busy && hasOwner && source in setOf(EntryScreen.EMAIL, EntryScreen.ACCOUNT_RECOVERY, EntryScreen.PROFILE,
        EntryScreen.FOOD_PREFERENCES, EntryScreen.EQUIPMENT, EntryScreen.OPTIONAL_CHECKPOINT,
        EntryScreen.ROUTE_SELECTION, EntryScreen.RECOVERY, EntryScreen.SETUP_REMAINING,
        EntryScreen.PRODUCT_REQUIRED, EntryScreen.PRIVATE_MEAL)

internal enum class EntryDeletionLocalAction { RECOVER_RECEIPT, FINISH_LOCAL_CLEANUP, CONTINUE_TO_SIGN_IN }

private val ACCOUNT_DELETION_RECEIPT_ID = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
)

/** External email is the deliberate post-revocation support transport. It neither polls the
 * protected job endpoint nor turns the receipt into account authority. */
internal fun entryDeletionSupportAvailable(exact: Boolean, busy: Boolean, hasReceipt: Boolean,
    screen: AccountDeletionScreen): Boolean = exact && !busy && hasReceipt && screen in setOf(
        AccountDeletionScreen.SUPPORT_REQUIRED, AccountDeletionScreen.ACCEPTED,
        AccountDeletionScreen.LOCAL_RETIRED,
    )

internal fun entryDeletionSupportMessage(receiptId: String): String? = receiptId
    .takeIf { ACCOUNT_DELETION_RECEIPT_ID.matches(it) }
    ?.let {
        "I need help with my FeedMe account deletion request.\n\n" +
            "Deletion receipt: $it\n\n" +
            "The app says remote deletion may still be pending. Please confirm the current status or tell me what information you need. " +
            "I have not included a password, sign-in token, or payment information."
    }

internal fun entryDeletionLocalActionAvailable(exact: Boolean, busy: Boolean, hasReceipt: Boolean,
    screen: AccountDeletionScreen, action: EntryDeletionLocalAction, archivePending: Boolean = false): Boolean = exact && !busy && hasReceipt && when (action) {
    EntryDeletionLocalAction.RECOVER_RECEIPT -> screen == AccountDeletionScreen.SUPPORT_REQUIRED && !archivePending
    EntryDeletionLocalAction.FINISH_LOCAL_CLEANUP -> screen == AccountDeletionScreen.ACCEPTED
    EntryDeletionLocalAction.CONTINUE_TO_SIGN_IN -> screen == AccountDeletionScreen.LOCAL_RETIRED ||
        (screen == AccountDeletionScreen.SUPPORT_REQUIRED && archivePending)
}

/** An unresolved/accepted original can never turn Back into restored private access. */
internal fun entryDeletionCanReturnToPrivacy(screen: AccountDeletionScreen, busy: Boolean): Boolean =
    !busy && screen == AccountDeletionScreen.REVIEW
