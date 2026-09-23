package com.feedme.app.onboarding

import com.feedme.core.ports.FailureReason
import com.feedme.session.OnboardingProfileFields
import com.feedme.session.OnboardingProfileIntent
import com.feedme.session.OnboardingProfileRejectionReason
import com.feedme.session.OnboardingProfileStatus

internal const val PROFILE_SCREEN_ID = "PROFILE_SETUP"
internal const val PROFILE_CONTINUE_ID = "PROFILE_SETUP.01"
internal const val PROFILE_BACK_ID = "PROFILE_SETUP.back"
internal const val PROFILE_TITLE = "Set up your profile"

/** RAM editing only. A null bio stays omitted until the user actually edits it. */
internal data class ProfileFormInput(val displayName: String, val handle: String, val bio: String?) {
    fun fields() = OnboardingProfileFields(displayName, handle, bio)
    override fun toString() = "ProfileFormInput(<redacted>)"
}

/** RAM-only editing. A new observation may replace a clean form, never newer typing. */
internal data class ProfileFormBuffer(val input: ProfileFormInput, val base: ProfileFormInput = input) {
    val dirty get() = input != base
    fun edit(next: ProfileFormInput) = copy(input = next)
    fun observe(next: ProfileFormInput) = if (dirty) copy(base = next) else ProfileFormBuffer(next)
    fun kept(captured: ProfileFormInput) = if (input == captured) ProfileFormBuffer(captured) else this
    override fun toString() = "ProfileFormBuffer(dirty=$dirty, fields=<redacted>)"
}

internal fun profileFormInput(draft: OnboardingProfileFields?, profile: OnboardingProfileFields?) = ProfileFormInput(
    draft?.displayName ?: profile?.displayName.orEmpty(),
    draft?.handle ?: profile?.handle.orEmpty(),
    draft?.bio ?: profile?.bio,
)

internal data class ProfileFieldErrors(val displayName: String?, val handle: String?, val bio: String?) {
    val valid get() = displayName == null && handle == null && bio == null
}

/** Mirror the canonical scalar limits without trimming, case-folding or replacing bad Unicode.
 * Local Keep accepts incomplete fields; the explicit server action additionally needs a name
 * and canonical handle. The real controller/server remain the validation authority. */
internal fun profileFieldErrors(input: ProfileFormInput, forSave: Boolean): ProfileFieldErrors {
    fun count(value: String): Int? {
        var index = 0; var size = 0
        while (index < value.length) {
            val c = value[index++]
            if (c.isHighSurrogate()) {
                if (index == value.length || !value[index].isLowSurrogate()) return null
                index++
            } else if (c.isLowSurrogate()) return null
            size++
        }
        return size
    }
    val name = count(input.displayName); val handle = count(input.handle)
    val bio = input.bio?.let(::count)
    return ProfileFieldErrors(
        when { name == null -> "Enter valid text."; name > 50 -> "Use 50 characters or fewer."
            forSave && name == 0 -> "Enter a display name."; else -> null },
        when { handle == null -> "Enter valid text."; handle > 24 -> "Use 24 characters or fewer."
            forSave && !input.handle.matches(Regex("[a-z0-9_]{3,24}")) -> "Use 3–24 lowercase letters, numbers or underscores."
            else -> null },
        when { input.bio != null && bio == null -> "Enter valid text."; bio != null && bio > 160 -> "Use 160 characters or fewer."; else -> null },
    )
}

internal enum class ProfileAction {
    KEEP, LOAD, SAVE, CONTINUE, RECOVER, REVIEW_ORIGINAL, CONFIRM_ORIGINAL, DISCARD_UNSENT, START_NEW, OPEN_PREFERENCES,
}

internal fun profilePrimary(status: OnboardingProfileStatus, recovery: Boolean, acknowledged: Boolean,
    fresh: Boolean, step: String?, preferencesConnected: Boolean): ProfileAction? = when {
    status == OnboardingProfileStatus.UNAVAILABLE -> null
    recovery || status == OnboardingProfileStatus.RETAINED_RESPONSE -> ProfileAction.RECOVER
    status in setOf(OnboardingProfileStatus.APPLIED, OnboardingProfileStatus.DISCARDED, OnboardingProfileStatus.REJECTED) && !acknowledged -> ProfileAction.RECOVER
    status == OnboardingProfileStatus.APPLIED && step == "preferences" -> if (preferencesConnected) ProfileAction.OPEN_PREFERENCES else null
    status in setOf(OnboardingProfileStatus.APPLIED, OnboardingProfileStatus.DISCARDED, OnboardingProfileStatus.REJECTED) ->
        if (fresh) ProfileAction.START_NEW else ProfileAction.LOAD
    status in setOf(OnboardingProfileStatus.PREPARED, OnboardingProfileStatus.UNCERTAIN) -> ProfileAction.REVIEW_ORIGINAL
    !fresh -> ProfileAction.LOAD
    step == "profile" -> ProfileAction.CONTINUE
    step == "preferences" -> ProfileAction.SAVE
    else -> null
}

internal fun profileActionLabel(action: ProfileAction) = when (action) {
    ProfileAction.KEEP -> "Keep on device"
    ProfileAction.LOAD -> "Load current profile"
    ProfileAction.SAVE -> "Save profile"
    ProfileAction.CONTINUE -> "Continue"
    ProfileAction.RECOVER -> "Recover saved work"
    ProfileAction.REVIEW_ORIGINAL -> "Review original save"
    ProfileAction.CONFIRM_ORIGINAL -> "Retry original save"
    ProfileAction.DISCARD_UNSENT -> "Discard unsent save"
    ProfileAction.START_NEW -> "Edit your draft"
    ProfileAction.OPEN_PREFERENCES -> "Continue to food preferences"
}

internal fun profileStatusTitle(status: OnboardingProfileStatus, recovery: Boolean, acknowledged: Boolean,
    rejection: OnboardingProfileRejectionReason?): String = when {
    status == OnboardingProfileStatus.UNAVAILABLE -> "Profile unavailable"
    recovery -> "Let’s recover your saved work"
    status == OnboardingProfileStatus.LOCAL -> "Your profile"
    status == OnboardingProfileStatus.PREPARED -> "This save hasn’t been sent"
    status == OnboardingProfileStatus.UNCERTAIN -> "This save isn’t confirmed yet"
    status == OnboardingProfileStatus.RETAINED_RESPONSE || !acknowledged -> "A saved result needs confirmation"
    status == OnboardingProfileStatus.APPLIED -> "Profile saved"
    status == OnboardingProfileStatus.DISCARDED -> "Unsent save discarded"
    rejection == OnboardingProfileRejectionReason.HANDLE_UNAVAILABLE -> "Choose another handle"
    else -> "Your profile changed elsewhere"
}

internal fun profileStatusBody(status: OnboardingProfileStatus, recovery: Boolean, acknowledged: Boolean,
    rejection: OnboardingProfileRejectionReason?): String = when {
    status == OnboardingProfileStatus.UNAVAILABLE -> "Go back and reopen profile setup to check your account and recover saved work. Nothing is sent automatically."
    recovery -> "Check the work kept on this device before doing anything else. Recovery does not send a save."
    status == OnboardingProfileStatus.LOCAL -> "Choose the display name and handle you want on your profile."
    status == OnboardingProfileStatus.PREPARED -> "You can review this exact save or discard only the unsent request. Your draft stays on this device."
    status == OnboardingProfileStatus.UNCERTAIN -> "The service may have received this save. Review the original before retrying; newer typing is separate."
    status == OnboardingProfileStatus.RETAINED_RESPONSE || !acknowledged -> "Recover the result kept on this device. Reading an earlier result does not confirm it again."
    status == OnboardingProfileStatus.APPLIED -> "The profile shown here was saved. Other setup steps still have their own requirements."
    status == OnboardingProfileStatus.DISCARDED -> "Only the unsent request was discarded. Your kept draft is still available."
    rejection == OnboardingProfileRejectionReason.HANDLE_UNAVAILABLE -> "That handle is unavailable. Keep a different handle, load the current profile, then edit your draft to make a new save."
    else -> "This save was not applied because the starting profile changed. Load the current profile and compare it with your draft before saving again."
}

internal fun profileFailureText(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.INVALID_DATA -> "Check the highlighted fields. Your text has not been trimmed or rewritten."
    FailureReason.OFFLINE -> "You’re offline. You can keep a draft on this device; an online save is not confirmed."
    FailureReason.CONFLICT -> "This view changed, or the original is not ready to retry. Review the current state before trying again."
    FailureReason.RATE_LIMITED -> "Please wait before reviewing this same save again."
    FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED, FailureReason.FORBIDDEN -> "This account connection is unavailable. Private profile details are hidden."
    else -> "We couldn’t confirm that action. Keep the original until its outcome is clear."
}

internal fun profileOriginalPurpose(intent: OnboardingProfileIntent?) =
    if (intent == OnboardingProfileIntent.ADVANCE_TO_PREFERENCES) "Save this profile and continue to food preferences."
    else "Save this profile without advancing setup."

internal fun profileRetryReady(now: Long, retryAt: Long?) = now >= 0 && (retryAt == null || now >= retryAt)

/** Local navigation admission only; the real owner must independently prove exact completion. */
internal fun profilePreferencesAdmission(attached: Boolean, leaving: Boolean, navigating: Boolean, dirty: Boolean) =
    attached && !leaving && !navigating && !dirty

/** UI-only duplicate/stale callback admission. This never replaces the actual owner's checks. */
internal class ProfileActionGate {
    internal class Ticket
    private var active: Ticket? = null
    private var executing: Ticket? = null
    private var latest: Ticket? = null
    private var retired = false
    fun claim(rendered: Any, current: Any): Ticket? {
        if (retired || active != null || rendered !== current) return null
        return Ticket().also { active = it; latest = it }
    }
    fun running(ticket: Ticket) = !retired && active === ticket
    fun begin(ticket: Ticket): Boolean {
        if (!running(ticket) || executing != null) return false
        executing = ticket
        return true
    }
    fun latest(ticket: Ticket) = !retired && latest === ticket
    fun finish(ticket: Ticket) { if (active === ticket) { active = null; executing = null } }
    fun retire() { retired = true; active = null; executing = null; latest = null }
}
