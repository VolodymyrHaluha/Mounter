package com.example.mounter

import com.example.mounter.attendance.*
import org.junit.Assert.*
import org.junit.Test

class AttendanceAccessTest {
    private val arrival = CardWorkSession("Іван", startedAt=2000)
    @Test fun arrivalNeedsOnlyReceivedTimes() {
        assertTrue(AttendanceAccess(sessions=listOf(arrival)).allowed)
    }
    @Test fun messageAloneDoesNotInventArrival() {
        assertFalse(AttendanceAccess(message="Прихід").allowed)
    }
    @Test fun departureStopsTimer() {
        assertFalse(AttendanceAccess(sessions=listOf(arrival.copy(endedAt=3000))).allowed)
    }
    @Test fun dataReadFailurePreservesReceivedTimes() {
        val access=AttendanceAccess(sessions=listOf(arrival))
        assertTrue(access.copy(readError="Немає зв’язку").allowed)
    }
}
