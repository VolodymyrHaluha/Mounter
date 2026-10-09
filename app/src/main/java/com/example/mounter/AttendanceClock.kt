package com.example.mounter

import android.content.Context
import com.example.mounter.attendance.CardWorkSession
import org.json.JSONArray
import org.json.JSONObject

internal data class AttendanceClock(val sessions: List<CardWorkSession> = emptyList()) {
    fun toggle(cardKey: String, name: String, now: Long): AttendanceClock {
        val previous=sessions.find { it.cardKey == cardKey }
        val updated=if(previous?.active == true) previous.copy(endedAt=now)
            else CardWorkSession(name, startedAt=now, cardKey=cardKey)
        return copy(sessions=sessions.filterNot { it.cardKey == cardKey } + updated)
    }
}

internal fun attendanceName(payload: ByteArray): String? {
    if(payload.isEmpty()) return null
    val status=payload[0].toInt() and 0xff
    val offset=1+(status and 0x3f)
    if(offset>payload.size) return null
    val charset=if(status and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
    return String(payload, offset, payload.size-offset, charset).trim().takeIf { it.isNotBlank() }
}

internal class AttendanceClockStore(context: Context) {
    private val preferences=context.getSharedPreferences("work_clock", Context.MODE_PRIVATE)
    fun load(): AttendanceClock {
        val data=JSONObject(preferences.getString("clock", "{}") ?: "{}")
        val rows=data.optJSONArray("sessions") ?: JSONArray()
        val sessions=(0 until rows.length()).map { i ->
            val row=rows.getJSONObject(i)
            CardWorkSession(row.getString("name"), row.getLong("started_at"),
                row.getLong("ended_at"), row.getString("card_key"))
        }
        return AttendanceClock(sessions)
    }
    fun save(clock: AttendanceClock) {
        val rows=JSONArray()
        clock.sessions.forEach { rows.put(JSONObject().put("name",it.cardId).put("card_key",it.cardKey)
            .put("started_at",it.startedAt).put("ended_at",it.endedAt)) }
        val data=JSONObject().put("sessions",rows)
        check(preferences.edit().putString("clock",data.toString()).commit()) { "Не вдалося зберегти робочий час." }
    }
}
