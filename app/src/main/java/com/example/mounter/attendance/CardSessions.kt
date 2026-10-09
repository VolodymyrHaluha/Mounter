package com.example.mounter.attendance

/** Data received from APP-TEST. No database or server confirmation is required. */
data class CardWorkSession(
    val cardId: String,
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val cardKey: String = cardId,
    val employeeId: Long? = null
) {
    val workerKey: String get() = employeeId?.let { "employee:$it" } ?: "card:$cardKey"
    val active: Boolean get() = startedAt > 0 && endedAt == 0L
}

fun hasActiveAttendance(sessions: List<CardWorkSession>): Boolean = sessions.any { it.active }
