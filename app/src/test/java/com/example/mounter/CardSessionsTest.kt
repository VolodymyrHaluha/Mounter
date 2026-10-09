package com.example.mounter

import com.example.mounter.attendance.*
import org.junit.Assert.*
import org.junit.Test

class CardSessionsTest {
    private fun arrival(label: String, employee: Long? = null)=CardWorkSession(label, startedAt=1000, employeeId=employee)
    @Test fun noArrivalDoesNotStartTimer() = assertFalse(hasActiveAttendance(emptyList()))
    @Test fun receivedArrivalStartsWithoutServerOrEmployeeId() = assertTrue(hasActiveAttendance(listOf(arrival("Іван"))))
    @Test fun departureCannotStopAnotherWorker() {
        val ended=arrival("Іван").copy(endedAt=5000)
        assertTrue(hasActiveAttendance(listOf(ended,arrival("Олена"))))
    }
    @Test fun lastDepartureStopsHours() = assertFalse(hasActiveAttendance(listOf(arrival("Іван").copy(endedAt=5000))))
    @Test fun noTimestampDoesNotCreateHours() = assertFalse(hasActiveAttendance(listOf(CardWorkSession("Іван"))))
    @Test fun independentTimersRespectReceivedTimeCorrectionsAndMidnight() {
        val first=arrival("Іван").copy(startedAt=86399000,endedAt=90000000)
        val other=arrival("Олена").copy(startedAt=86400000)
        assertEquals(3601000L,workDurationMillis(first.startedAt,first.endedAt,99999999))
        assertEquals(3600000L,workDurationMillis(other.startedAt,0,90000000))
        assertEquals(3500000L,workDurationMillis(other.copy(startedAt=86500000).startedAt,0,90000000))
    }
    @Test fun allActiveWorkersAreShownWithoutHistory() {
        for(size in listOf(1,2,5,10,20)) {
            val cards=(1..size).map { n -> arrival("$n").let { if(n%2==0) it.copy(endedAt=5000) else it } }
            assertEquals(cards.count { it.active },orderedCardSessions(cards).size)
        }
    }
    @Test fun oneBlockPerEmployeeUsesLatestArrival() {
        val earlier=arrival("Іван",1)
        val newer=arrival("Іван — інша картка",1).copy(startedAt=2000)
        val other=arrival("Олена",2)
        assertEquals(listOf(newer,other),orderedCardSessions(listOf(earlier,other,newer)))
        assertEquals(listOf(newer,other),orderedCardSessions(listOf(newer,earlier,other)))
    }
    @Test fun workersWithoutDatabaseIdsRemainIndependent() {
        assertEquals(2,orderedCardSessions(listOf(arrival("Іван"),arrival("Олена"))).size)
    }
}
