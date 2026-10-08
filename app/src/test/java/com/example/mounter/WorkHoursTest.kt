package com.example.mounter

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkHoursTest {
    @Test fun runningShiftIncludesTimeWhileAppWasClosed() {
        assertEquals("02:01:01", formatWorkHours(workDurationMillis(1000, 0, 7262000)))
    }
    @Test fun departureFreezesDuration() {
        assertEquals(60000L, workDurationMillis(1000, 61000, 999999))
    }
    @Test fun shiftCanCrossMidnightAndLastMoreThanADay() {
        assertEquals("25:00:00", formatWorkHours(workDurationMillis(1000, 0, 90001000)))
    }
    @Test fun missingArrivalOrBackwardsClockNeverShowsNegativeTime() {
        assertEquals(0L, workDurationMillis(0, 0, 1000))
        assertEquals("00:00:00", formatWorkHours(workDurationMillis(2000, 0, 1000)))
    }
}