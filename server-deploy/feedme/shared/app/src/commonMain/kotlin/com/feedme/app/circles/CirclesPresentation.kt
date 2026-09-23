package com.feedme.app.circles

import com.feedme.contracts.WireField
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.circles.CirclesPhase
import com.feedme.mealflow.circles.CirclesScreen

/** Display decisions only. Neither roles nor a successful read grant mutation permission. */
internal fun circlesContentVisible(screen: CirclesScreen, phase: CirclesPhase): Boolean =
    screen in setOf(CirclesScreen.LIST, CirclesScreen.DETAIL, CirclesScreen.MEMBERS) &&
        phase in setOf(CirclesPhase.READY, CirclesPhase.EMPTY)

internal fun circlesCanRead(screen: CirclesScreen, phase: CirclesPhase): Boolean =
    screen in setOf(CirclesScreen.LIST, CirclesScreen.DETAIL, CirclesScreen.MEMBERS) &&
        phase !in setOf(CirclesPhase.LOADING, CirclesPhase.UNAVAILABLE)

internal fun circlesCanLoadMore(screen: CirclesScreen, phase: CirclesPhase, hasMore: Boolean): Boolean =
    screen in setOf(CirclesScreen.LIST, CirclesScreen.MEMBERS) && phase == CirclesPhase.READY && hasMore

internal fun circlesTitle(screen: CirclesScreen): String = when (screen) {
    CirclesScreen.LIST -> "My kitchen circles"
    CirclesScreen.DETAIL -> "Your circle"
    CirclesScreen.MEMBERS -> "Circle members"
    CirclesScreen.HIDDEN -> "Kitchen circles"
    CirclesScreen.UNAVAILABLE -> "Circles unavailable"
}

internal fun circlesBackLabel(screen: CirclesScreen): String = when (screen) {
    CirclesScreen.DETAIL -> "Back to circles"
    CirclesScreen.MEMBERS -> "Back to circle"
    else -> "Back to my kitchen"
}

internal fun circlesRefreshLabel(screen: CirclesScreen): String = when (screen) {
    CirclesScreen.DETAIL -> "Refresh circle"
    CirclesScreen.MEMBERS -> "Refresh members"
    else -> "Refresh circles"
}

internal fun circleOpenLabel(name: String): String =
    "Open circle: " + if (name.isEmpty()) "Unnamed circle" else name

/** Preserve the canonical integer text, including values beyond machine integer ranges. */
internal fun circleMemberCountText(count: String): String =
    count + if (count == "1") " member" else " members"

internal fun circlesLoadedText(count: Int): String =
    "$count " + (if (count == 1) "circle" else "circles") + " loaded"

internal fun circleMembersLoadedText(count: Int): String =
    "$count " + (if (count == 1) "member" else "members") + " loaded"

/** Omit an absent/empty description in the UI; its complete field remains in the snapshot. */
internal fun circleDescription(value: WireField<String>): String? = when (value) {
    WireField.Missing, WireField.Null -> null
    is WireField.Value -> value.value.takeIf { it.isNotEmpty() }
}

internal fun circleRoleLabel(value: String): String = when (value) {
    "owner" -> "Owner"
    "admin" -> "Admin"
    "member" -> "Member"
    else -> value
}

internal fun circleStatusLabel(value: String): String = when (value) {
    "active" -> "Active"
    "archived" -> "Archived"
    "invited" -> "Invited"
    "removed" -> "Removed"
    else -> value
}

internal fun circlesStatusMessage(screen: CirclesScreen, phase: CirclesPhase, reason: FailureReason?): Pair<String, String>? =
    when {
        screen == CirclesScreen.UNAVAILABLE || phase == CirclesPhase.UNAVAILABLE ->
            "Access unavailable" to "Circle details are hidden. Go back and check your account connection."
        phase == CirclesPhase.LOADING ->
            "Loading…" to "Getting the latest details. You can go back while we check."
        phase == CirclesPhase.OFFLINE ->
            "You’re offline" to "Circles aren’t saved for offline browsing. Reconnect, then refresh to see them."
        phase == CirclesPhase.ERROR ->
            "Couldn’t load this view" to when (reason) {
                FailureReason.NOT_CONFIGURED -> "Circle browsing is not connected for this session. Your cooking and private drafts are unchanged."
                FailureReason.FORBIDDEN, FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION ->
                    "We couldn’t confirm access. Details are hidden. Go back or try refreshing."
                else -> "Try refreshing this view, or go back. Nothing has been changed."
            }
        phase == CirclesPhase.EMPTY ->
            if (screen == CirclesScreen.MEMBERS) "No members in this view" to
                "There are no members to show right now."
            else "No circles in this view" to
                "There are no circles to show right now. Your kitchen and cookbook are still here."
        phase == CirclesPhase.IDLE ->
            "Ready when you are" to "Refresh to see the latest details."
        else -> null
    }

internal const val CIRCLES_READ_NOTICE =
    "Browsing only. Sharing and member changes aren’t available here yet."
internal const val CIRCLES_PAGE_LIMIT_NOTICE =
    "You’ve reached the browsing limit for this view. There may be more results. Refresh to start again."
internal const val CIRCLES_NOT_CONNECTED =
    "Creating circles, inviting friends, joining or leaving, and managing members aren’t available here yet. Sharing circle posts is also coming."
