package com.example.mounter

import com.example.mounter.attendance.*
import org.junit.Assert.*
import org.junit.Test

class CardSessionsTest {
    private fun active(label: String, employee: Long = 1) = CardWorkSession(label,"in-$label","check_in",1000,
        employeeId=employee,confirmationSource="LOCAL",serverRevision=1,syncStatus="confirmed")
    @Test fun zeroCardsLocked() = assertFalse(hasActiveAttendance(emptyList()))
    @Test fun oneConfirmedCardOpens() = assertTrue(hasActiveAttendance(listOf(active("A"))))
    @Test fun orderAndLastGlobalActionDoNotMatter() {
        val ended=active("A").copy(lastConfirmedAction="check_out",endedAt=5000)
        assertTrue(hasActiveAttendance(listOf(ended,active("B",2))))
        assertTrue(hasActiveAttendance(listOf(active("B",2),ended)))
    }
    @Test fun lastDepartureLocks() = assertFalse(hasActiveAttendance(listOf(active("A").copy(lastConfirmedAction="check_out",endedAt=5000))))
    @Test fun pendingSuspendsOnlyItsOwnCard() {
        val pending=active("A").copy(syncStatus="pending_confirmation",pendingEventId="out-A")
        assertFalse(hasActiveAttendance(listOf(pending)))
        assertTrue(hasActiveAttendance(listOf(pending,active("B",2))))
    }
    @Test fun localUnconfirmedOrGlobalAssumptionCannotOpen() {
        assertFalse(hasActiveAttendance(listOf(active("A").copy(serverRevision=0))))
        assertFalse(hasActiveAttendance(listOf(active("A").copy(confirmationSource="GLOBAL"))))
        assertFalse(hasActiveAttendance(listOf(active("A").copy(employeeId=null))))
    }
    @Test fun restartRestoresAccessFromAuthoritativeSnapshot() {
        val snapshot=listOf(active("A"),active("B",2).copy(lastConfirmedAction="check_out",endedAt=4000))
        assertEquals(hasActiveAttendance(snapshot),hasActiveAttendance(snapshot.map { it.copy() }))
    }
    @Test fun independentTimersRespectServerCorrectionsAndMidnight() {
        val first=active("A").copy(startedAt=86399000,endedAt=90000000,lastConfirmedAction="check_out")
        val other=active("B",2).copy(startedAt=86400000)
        assertEquals(3601000L,workDurationMillis(first.startedAt,first.endedAt,99999999))
        assertEquals(3600000L,workDurationMillis(other.startedAt,other.endedAt,90000000))
        assertEquals(3500000L,workDurationMillis(other.copy(startedAt=86500000).startedAt,0,90000000))
    }
    @Test fun onlyActiveHoursAreListedWithoutTruncation() {
        for(size in listOf(1,2,5,10,20)) {
            val cards=(1..size).map { n -> active("$n",n.toLong()).let { if(n%2==0) it.copy(lastConfirmedAction="check_out",endedAt=5000) else it } }
            val ordered=orderedCardSessions(cards)
            assertEquals(cards.count { it.active },ordered.size)
            assertTrue(ordered.all { it.active })
        }
    }
    @Test fun departedPendingAndUnconfirmedCardsAreHidden() {
        val running = active("B", 2)
        val departed = active("A").copy(lastConfirmedAction="check_out", endedAt=5000)
        val pending = active("C", 3).copy(syncStatus="pending_confirmation", pendingEventId="out-C")
        val unconfirmed = active("D", 4).copy(serverRevision=0)
        assertEquals(listOf(running), orderedCardSessions(listOf(departed, pending, running, unconfirmed)))
        assertTrue(orderedCardSessions(listOf(departed)).isEmpty())
    }
    @Test fun oneHoursBlockPerEmployeeUsesLatestActiveArrival() {
        val earlier = active("Іван", 1).copy(startedAt=1000)
        val newer = active("Іван — інша картка", 1).copy(startedAt=2000)
        val other = active("Олена", 2)
        assertEquals(listOf(newer, other), orderedCardSessions(listOf(earlier, other, newer)))
        assertEquals(listOf(newer, other), orderedCardSessions(listOf(newer, earlier, other)))
    }

}