package com.feedme.app.circles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.feedme.mealflow.circles.CircleMemberSnapshot
import com.feedme.mealflow.circles.CirclesController
import com.feedme.mealflow.circles.CirclesPhase
import com.feedme.mealflow.circles.CirclesScreen
import com.feedme.mealflow.circles.CirclesState

/** A single-person F28 navigation result, not an invitation or messaging grant.
 * IDs come from the exact retained member response; labels are never parsed into IDs. */
class CircleDirectRecipientSelection internal constructor(
    private val owner: CircleDirectPeoplePicker,
    val source: CirclesState,
    val member: CircleMemberSnapshot,
) {
    val recipientUserId: String get() = member.userId
    val isCurrentForNavigation: Boolean get() = owner.accepts(this)
    override fun toString() = "CircleDirectRecipientSelection(<redacted>)"
}

/** RAM-only, purpose-fixed local picker. It borrows the already authorized current roster
 * without loading contacts, fetching another profile, creating a thread or changing a draft.
 * The parent must retain this owner through admission and close it on departure/disposal.
 * Contact preferences/blocks are independently rechecked by the real thread endpoint. */
class CircleDirectPeoplePicker private constructor(
    private val controller: CirclesController,
    val source: CirclesState,
    private val hostIsCurrent: () -> Boolean,
) {
    internal val observations get() = controller.states
    private var closed by mutableStateOf(false)
    private var selected by mutableStateOf<CircleMemberSnapshot?>(null)
    private var issued by mutableStateOf<CircleDirectRecipientSelection?>(null)

    val isCurrent: Boolean get() = !closed && hostIsCurrent() && controller.states.value === source &&
        source.screen == CirclesScreen.MEMBERS && source.phase == CirclesPhase.READY &&
        source.failureReason == null && source.selected?.status == "active"
    internal val awaitingHandoff: Boolean get() = issued != null
    internal val people: List<CircleMemberSnapshot> get() = if (!isCurrent) emptyList() else source.members.filter(::currentMember)
    internal val selectedMember: CircleMemberSnapshot? get() = selected?.takeIf(::currentMember)

    private fun currentMember(member: CircleMemberSnapshot): Boolean = isCurrent &&
        source.members.count { it.userId == member.userId } == 1 &&
        // This existing predicate checks the exact source/account lease, active row and
        // non-self identity only. It is not used as permission to report or message.
        controller.canReportMember(member, source)

    internal fun select(member: CircleMemberSnapshot) {
        if (issued == null && currentMember(member)) selected = member.takeUnless { selected === member }
    }

    internal fun confirm(): CircleDirectRecipientSelection? {
        if (issued != null) return null
        val member = selectedMember ?: return null
        return CircleDirectRecipientSelection(this, source, member).also { issued = it }
    }

    internal fun accepts(result: CircleDirectRecipientSelection): Boolean = issued === result &&
        result.source === source && selected === result.member && currentMember(result.member)

    /** Cancel/Back only clears this temporary selection; the calling roster/draft is unchanged. */
    fun close() { closed = true; selected = null; issued = null }

    override fun toString() = "CircleDirectPeoplePicker(<redacted>)"

    companion object {
        fun open(controller: CirclesController, expected: CirclesState,
            hostIsCurrent: () -> Boolean): CircleDirectPeoplePicker? =
            CircleDirectPeoplePicker(controller, expected, hostIsCurrent).takeIf { it.isCurrent }
    }
}
