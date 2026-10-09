package com.example.mounter

import com.example.mounter.attendance.CardWorkSession
import org.json.JSONArray

internal class AttendanceContractException(message: String) : IllegalStateException(message)

internal fun parseCardRows(raw: String): List<CardWorkSession> = try {
    val rows = JSONArray(raw)
    (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        CardWorkSession(cardId=row.getString("card_label"), cardKey=row.getString("card_key"),
            employeeId=row.optLong("employee_id").takeIf { it > 0 }, eventId=row.getString("last_confirmed_event_id"),
            lastConfirmedAction=row.getString("last_confirmed_action"), startedAt=row.getLong("work_started_at"),
            endedAt=row.getLong("work_ended_at"), confirmationSource=row.getString("confirmation_source"),
            serverRevision=row.getLong("server_revision"), syncStatus=row.getString("sync_status"),
            pendingEventId=if(row.getString("sync_status") == "pending_confirmation") "pending" else "")
    }
} catch(error: Exception) {
    throw AttendanceContractException("APP-TEST повернув неповний стан карток v2: ${error.javaClass.simpleName}.")
}
