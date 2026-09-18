package com.feedme.core

const val TODAY_DURATION_MILLIS: Long = 24L * 60L * 60L * 1000L

/** Local DEMO visibility only. Production authorization MUST be enforced by a server. */
fun canViewPlate(plate: Plate, session: Session?, nowMillis: Long, mode: OperatingMode): Boolean {
    if (mode != OperatingMode.DEMO || session?.isSimulated != true) return false
    val viewer = session.userId ?: return false
    if (plate.deleted || plate.createdAtMillis < 0 || nowMillis < plate.createdAtMillis) return false
    if (plate.authorId in session.blockedUserIds || viewer in plate.blockedViewerIds) return false
    if (viewer != plate.authorId && plate.circleId !in session.invitedCircleIds) return false
    return plate.keepOnPlate || nowMillis - plate.createdAtMillis < TODAY_DURATION_MILLIS
}

fun visibleTodayPlates(state: AppState, nowMillis: Long): List<Plate> = state.plates.filter {
    canViewPlate(it, state.session, nowMillis, state.mode) &&
        nowMillis - it.createdAtMillis < TODAY_DURATION_MILLIS
}

fun visibleMyPlatePosts(state: AppState, nowMillis: Long): List<Plate> = state.plates.filter {
    it.authorId == state.session?.userId && it.keepOnPlate &&
        canViewPlate(it, state.session, nowMillis, state.mode)
}
