package com.example.mounter

import org.junit.Assert.*
import org.junit.Test

class AttendanceClockTest {
    @Test fun firstScanStartsAndSecondStopsHours() {
        val first=AttendanceClock().toggle("a","Іван",1000)
        assertTrue(first.sessions.single().active)
        val second=first.toggle("a","Іван",6000)
        assertFalse(second.sessions.single().active)
        assertEquals(5000L,workDurationMillis(second.sessions.single().startedAt,second.sessions.single().endedAt,9999))
    }
    @Test fun eachCardRunsIndependently() {
        val both=AttendanceClock().toggle("a","Іван",1000).toggle("b","Олена",2000)
        val ended=both.toggle("a","Іван",3000)
        assertEquals(1,ended.sessions.count { it.active })
        assertTrue(ended.sessions.single { it.cardKey=="b" }.active)
    }
    @Test fun newShiftReplacesPreviousHoursWithoutHistory() {
        val restart=AttendanceClock().toggle("a","Іван",1000).toggle("a","Іван",2000).toggle("a","Іван",3000)
        assertEquals(1,restart.sessions.size)
        assertEquals(3000L,restart.sessions.single().startedAt)
    }
    @Test fun restartWithStoredTimesDoesNotResetRunningShift() {
        val before=AttendanceClock().toggle("a","Іван",1000)
        val restored=AttendanceClock(before.sessions.map { it.copy() })
        assertEquals(5000L,workDurationMillis(restored.sessions.single().startedAt,0,6000))
        assertFalse(restored.toggle("a","Іван",7000).sessions.single().active)
    }
    @Test fun nfcTextProvidesWorkerName() {
        assertEquals("Іван",attendanceName(byteArrayOf(2,101,110)+"Іван".toByteArray()))
        assertNull(attendanceName(byteArrayOf(5,101)))
    }
}
