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
    @Test fun parsesAuthoritativeModelFields() {
        val session = parseCardRows(JSONArray().put(row()).toString()).single()
        assertEquals("001245", session.cardId)
        assertEquals("hash", session.cardKey)
        assertEquals(1L, session.employeeId)
        assertEquals("LOCAL", session.confirmationSource)
        assertEquals(42L, session.serverRevision)
        assertEquals("confirmed", session.syncStatus)
    }
    @Test fun departureKeepsOtherConfirmedCardActive() {
        val departed = row().put("card_key", "a").put("employee_id", 2)
            .put("last_confirmed_action", "check_out").put("work_ended_at", 3000)
        val sessions = parseCardRows(JSONArray().put(departed).put(row()).toString())
        assertTrue(AttendanceAccess(sessions = sessions, state = "departure").allowed)
        assertFalse(AttendanceAccess(sessions = listOf(sessions.first())).allowed)
    }
    @Test fun malformedSecondCardRejectsWholeSnapshot() {
        val error = assertThrows(AttendanceContractException::class.java) {
            parseCardRows(JSONArray().put(row()).put(JSONObject()).toString())
        }
        assertNotNull(error.cause)
    }
}
