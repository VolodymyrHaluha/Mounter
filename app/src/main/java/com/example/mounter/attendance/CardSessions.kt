package com.example.mounter.attendance

/** Immutable snapshot. A saved NFC event suspends only its own card until LOCAL resolves it. */
data class CardWorkSession(
    val cardId: String,
    val eventId: String = "",
    val lastConfirmedAction: String = "",
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val pendingEventId: String = "",
    val pendingAt: Long = 0,
    val seenEventIds: List<String> = emptyList()
) {
    val active: Boolean get() = eventId.isNotBlank() && lastConfirmedAction == "check_in" &&
        startedAt > 0 && endedAt == 0L && pendingEventId.isBlank()
}

fun hasActiveAttendance(sessions: List<CardWorkSession>): Boolean = sessions.any { it.active }

fun saveCardEvent(sessions: List<CardWorkSession>, cardId: String, eventId: String, at: Long): List<CardWorkSession> {
    require(cardId.isNotBlank() && eventId.isNotBlank() && at > 0)
    if(sessions.any { it.eventId == eventId || it.pendingEventId == eventId || eventId in it.seenEventIds }) return sessions
    val previous = sessions.lastOrNull { it.cardId == cardId }
    val next = (previous ?: CardWorkSession(cardId)).copy(pendingEventId=eventId, pendingAt=at, seenEventIds=(previous?.seenEventIds.orEmpty() + eventId))
    return if(previous != null && previous.endedAt == 0L) sessions.map { if(it === previous) next else it }
        else sessions + next.copy(eventId="", lastConfirmedAction="", startedAt=0, endedAt=0)
}

fun confirmCardEvent(sessions: List<CardWorkSession>, eventId: String, action: String): List<CardWorkSession> {
    if(action !in setOf("check_in", "check_out")) return sessions
    return sessions.map { session ->
        if(session.pendingEventId != eventId || eventId.isBlank()) session
        else session.copy(eventId=eventId, lastConfirmedAction=action,
            startedAt=if(action == "check_in") session.pendingAt else session.startedAt,
            endedAt=if(action == "check_out") session.pendingAt else 0,
            pendingEventId="", pendingAt=0)
    }
}
