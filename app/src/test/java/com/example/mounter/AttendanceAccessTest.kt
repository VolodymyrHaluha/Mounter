package com.example.mounter

import com.example.mounter.attendance.*
import org.junit.Assert.*
import org.junit.Test

class AttendanceAccessTest {
    private val active = CardWorkSession("B", "in-b", "check_in", 2000,)
    @Test fun lastGlobalDepartureCannotLockAnotherActiveCard() {
        assertTrue(AttendanceAccess(sessions=listOf(active), state="departure", eventId="out-a").allowed)
    }
    @Test fun oldSingleEventArrivalCannotGrantAccess() {
        assertFalse(AttendanceAccess(state="arrival", eventId="old").allowed)
    }
    @Test fun pendingOnlyCardLocksEverySectionThroughSharedGate() {
        assertFalse(AttendanceAccess(sessions=listOf(active.copy(pendingEventId="out-b", pendingAt=3000))).allowed)
    }
    @Test fun statusReadFailurePreservesConfirmedSnapshot() {
        val access = AttendanceAccess(sessions=listOf(active))
        assertTrue(access.copy(message="Немає зв’язку").allowed)
    }
}