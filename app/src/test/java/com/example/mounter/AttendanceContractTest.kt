package com.example.mounter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AttendanceContractTest {
    private fun row()=JSONObject().put("card_label","001245").put("card_key","hash").put("employee_id",1)
        .put("last_confirmed_event_id","uuid").put("last_confirmed_action","check_in").put("work_started_at",1000)
        .put("work_ended_at",0).put("confirmation_source","LOCAL").put("server_revision",42).put("sync_status","confirmed")
    @Test fun emptyCacheIsValidAndLocked() { assertFalse(AttendanceAccess(sessions=parseCardRows("[]")).allowed) }
    @Test fun validSnapshotOpensAndPendingDoesNot() {
        val rows=JSONArray().put(row()).toString()
        assertTrue(AttendanceAccess(sessions=parseCardRows(rows)).allowed)
        assertFalse(AttendanceAccess(sessions=parseCardRows(JSONArray().put(row().put("sync_status","pending_confirmation")).toString())).allowed)
    }
    @Test(expected=AttendanceContractException::class) fun missingFieldsAreFormatError() { parseCardRows("[{}]") }
    @Test fun unconfirmedSnapshotCannotOpen() {
        assertFalse(AttendanceAccess(sessions=parseCardRows(JSONArray().put(row().put("confirmation_source","").put("server_revision",0)).toString())).allowed)
    }
}
