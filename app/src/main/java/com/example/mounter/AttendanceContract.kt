package com.example.mounter

import com.example.mounter.attendance.CardWorkSession as AttendanceCardSession
import org.json.JSONArray

internal fun parseCardRows(raw: String): List<AttendanceCardSession> {
    val rows = JSONArray(raw)
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.getJSONObject(index)
        val label = row.optionalText("employee_name").ifBlank {
            row.optionalText("card_label").ifBlank { row.optionalText("card_id") }
        }
        val startedAt = row.optLong("work_started_at", row.optLong("started_at"))
        if(label.isBlank() || startedAt <= 0) return@mapNotNull null
        AttendanceCardSession(
            cardId = label,
            cardKey = row.optionalText("card_key").ifBlank { label },
            employeeId = row.optLong("employee_id").takeIf { it > 0 },
            startedAt = startedAt,
            endedAt = row.optLong("work_ended_at", row.optLong("ended_at"))
        )
    }
}
