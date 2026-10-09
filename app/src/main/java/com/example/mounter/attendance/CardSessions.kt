package com.example.mounter.attendance

/** Read-only projection of APP-TEST's authoritative LOCAL snapshot. Mounter never toggles a card. */
data class CardWorkSession(
    val cardId: String,
    val eventId: String = "",
    val lastConfirmedAction: String = "",
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val pendingEventId: String = "",
    val pendingAt: Long = 0,
    val cardKey: String = cardId,
    val employeeId: Long? = null,
    val confirmationSource: String = "",
    val serverRevision: Long = 0,
    val syncStatus: String = "unconfirmed"
) {
    val active: Boolean
        get() = confirmationSource == "LOCAL" &&
                serverRevision > 0 &&
                (employeeId ?: 0L) > 0 &&
                syncStatus == "confirmed" &&
                eventId.isNotBlank() &&
                lastConfirmedAction == "check_in" &&
                startedAt > 0 &&
                endedAt == 0L &&
                pendingEventId.isBlank()
}

fun hasActiveAttendance(sessions: List<CardWorkSession>): Boolean = sessions.any { it.active }