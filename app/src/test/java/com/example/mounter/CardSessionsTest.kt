package com.example.mounter

import com.example.mounter.attendance.*
import org.junit.Assert.*
import org.junit.Test

class CardSessionsTest {
    private fun arrival(rows: List<CardWorkSession>, card: String, id: String, at: Long = 1000) =
        confirmCardEvent(saveCardEvent(rows, card, id, at), id, "check_in")
    private fun two() = arrival(arrival(emptyList(), "A", "in-a"), "B", "in-b", 2000)
    @Test fun zeroCardsLocked() = assertFalse(hasActiveAttendance(emptyList()))
    @Test fun confirmedArrivalOpens() = assertTrue(hasActiveAttendance(arrival(emptyList(), "A", "in-a")))
    @Test fun twoCardsOpenRegardlessOfOrder() {
        assertTrue(hasActiveAttendance(two())); assertTrue(hasActiveAttendance(two().reversed()))
    }
    @Test fun departureNeverEmitsLockedWhileOtherCardActive() {
        val snapshots = listOf(two(), saveCardEvent(two(), "A", "out-a", 3000)).let {
            it + listOf(confirmCardEvent(it.last(), "out-a", "check_out"))
        }
        assertTrue(snapshots.all(::hasActiveAttendance))
    }
    @Test fun lastDepartureLocks() {
        val rows = confirmCardEvent(saveCardEvent(two(), "A", "out-a", 3000), "out-a", "check_out")
        assertFalse(hasActiveAttendance(confirmCardEvent(saveCardEvent(rows, "B", "out-b", 4000), "out-b", "check_out")))
    }
    @Test fun pendingArrivalCannotOpen() = assertFalse(hasActiveAttendance(saveCardEvent(emptyList(), "A", "in-a", 1000)))
    @Test fun pendingDepartureOnlySuspendsItsOwnCard() {
        val rows = saveCardEvent(two(), "A", "out-a", 3000)
        assertFalse(rows.first().active); assertTrue(rows.last().active); assertTrue(hasActiveAttendance(rows))
    }
    @Test fun onlyPendingDepartureLocks() = assertFalse(hasActiveAttendance(saveCardEvent(arrival(emptyList(), "A", "in-a"), "A", "out-a", 3000)))
    @Test fun unconfirmedOrInvalidServerResultPreservesOtherCard() {
        val rows = saveCardEvent(two(), "A", "out-a", 3000)
        assertEquals(rows, confirmCardEvent(rows, "out-a", "GLOBAL"))
        assertTrue(rows.last().active)
    }
    @Test fun staleConfirmationCannotRestoreAccess() {
        var rows = saveCardEvent(emptyList(), "A", "old", 1000)
        rows = saveCardEvent(rows, "A", "new", 2000)
        assertEquals(rows, confirmCardEvent(rows, "old", "check_in"))
        rows = confirmCardEvent(rows, "new", "check_out")
        assertFalse(hasActiveAttendance(confirmCardEvent(rows, "old", "check_in")))
    }
    @Test fun reconstructedSnapshotHasSameAccessAndTimers() {
        val rows = two().map { it.copy() }
        assertTrue(hasActiveAttendance(rows)); assertEquals(2000L, rows.last().startedAt)
    }
    @Test fun repeatedSavedEventDoesNotSwitchOrSuspendAgain() {
        val rows = two()
        assertEquals(rows, saveCardEvent(rows, "A", "in-a", 9000))
        val ended = confirmCardEvent(saveCardEvent(rows, "A", "out-a", 3000), "out-a", "check_out")
        assertEquals(ended, saveCardEvent(ended, "A", "in-a", 9000))
    }
    @Test fun departureFreezesOnlyOwnTimerAndPreservesHistory() {
        val rows = confirmCardEvent(saveCardEvent(two(), "A", "out-a", 3000), "out-a", "check_out")
        assertEquals(2000L, workDurationMillis(rows.first().startedAt, rows.first().endedAt, 9000))
        assertEquals(7000L, workDurationMillis(rows.last().startedAt, rows.last().endedAt, 9000))
        val again = arrival(rows, "A", "in-a-2", 10000)
        assertEquals(3, again.size); assertEquals(rows.first(), again.first())
    }
}
