package com.example.mounter

// Keep the authoritative model explicit even if an older same-named class exists in this package.
import com.example.mounter.attendance.CardWorkSession as AttendanceCardSession
import org.json.JSONArray

internal const val ATTENDANCE_CONTRACT_VERSION = 2

internal class AttendanceContractException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal fun parseCardRows(raw: String): List<AttendanceCardSession> = try {
    val rows = JSONArray(raw)
    (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        val syncStatus = row.getString("sync_status")
        AttendanceCardSession(
            cardId = row.getString("card_label"),
            cardKey = row.getString("card_key"),
            employeeId = row.optLong("employee_id").takeIf { it > 0 },
            eventId = row.getString("last_confirmed_event_id"),
            lastConfirmedAction = row.getString("last_confirmed_action"),
            startedAt = row.getLong("work_started_at"),
            endedAt = row.getLong("work_ended_at"),
            confirmationSource = row.getString("confirmation_source"),
            serverRevision = row.getLong("server_revision"),
            syncStatus = syncStatus,
            pendingEventId = if (syncStatus == "pending_confirmation") "pending" else ""
        )
    }
} catch(error: Exception) {
    throw AttendanceContractException(
        "APP-TEST повернув неповний стан карток v$ATTENDANCE_CONTRACT_VERSION: ${error.javaClass.simpleName}.",
        error
    )
}
