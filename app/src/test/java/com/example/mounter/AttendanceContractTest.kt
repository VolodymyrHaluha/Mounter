package com.example.mounter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AttendanceContractTest {
    private fun row()=JSONObject().put("card_label","Іван").put("card_key","card-a")
        .put("employee_id",1).put("work_started_at",1000).put("work_ended_at",0)

    @Test fun timesWithoutDatabaseMetadataOpenApp() {
        assertTrue(AttendanceAccess(sessions=parseCardRows(JSONArray().put(row()).toString())).allowed)
    }
    @Test fun globalArrivalNeedsNoLocalConfirmation() {
        val data=row().put("confirmation_source","GLOBAL").put("server_revision",0)
            .put("sync_status","pending_confirmation")
        assertTrue(AttendanceAccess(sessions=parseCardRows(JSONArray().put(data).toString())).allowed)
    }
    @Test fun legacySessionsWithoutEmployeeIdHaveTheirOwnTimers() {
        val data=JSONArray()
            .put(JSONObject().put("card_id","Іван").put("started_at",1000))
            .put(JSONObject().put("card_id","Олена").put("started_at",2000))
        assertEquals(2, orderedCardSessions(parseCardRows(data.toString())).size)
    }
    @Test fun employeeNameAndReceivedTimesArePreserved() {
        val session=parseCardRows(JSONArray().put(row().put("employee_name","Іван Петренко")).toString()).single()
        assertEquals("Іван Петренко", session.cardId)
        assertEquals("card-a", session.cardKey)
        assertEquals(1L, session.employeeId)
        assertEquals(1000L, session.startedAt)
    }
    @Test fun emptyRowsDoNotInventArrival() {
        assertTrue(parseCardRows("[{},{}]").isEmpty())
        assertFalse(AttendanceAccess(sessions=parseCardRows("[]")).allowed)
    }
    @Test fun departureClosesOnlyItsOwnTimer() {
        val departed=row().put("work_ended_at",3000)
        val other=row().put("employee_id",2).put("card_label","Олена")
        val sessions=parseCardRows(JSONArray().put(departed).put(other).toString())
        assertTrue(AttendanceAccess(sessions=sessions).allowed)
        assertEquals(listOf(sessions.last()), orderedCardSessions(sessions))
    }
    @Test(expected=org.json.JSONException::class) fun brokenJsonIsAReadError() {
        parseCardRows("broken")
    }
}
