package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarNavigationTest {
    @Test fun nextWeekIncludesOctoberFifth() {
        val selected = calendarSwipeDate(LocalDate.of(2026, 10, 3), 80f, 48f)!!
        assertEquals(LocalDate.of(2026, 10, 5), calendarWeekStart(selected))
    }
    @Test fun leftGoesBackAndRightReturnsToOriginalWeekday() {
        val today = LocalDate.of(2026, 10, 3)
        val previous = calendarSwipeDate(today, -80f, 48f)!!
        assertEquals(today.minusWeeks(1), previous)
        assertEquals(today, calendarSwipeDate(previous, 80f, 48f))
    }
    @Test fun tapOrSmallMovementDoesNotChangeWeek() {
        assertNull(calendarSwipeDate(LocalDate.of(2026, 10, 3), 12f, 48f))
    }
    @Test fun navigationCrossesYearBoundary() {
        assertEquals(LocalDate.of(2027, 1, 4), calendarWeekStart(calendarSwipeDate(LocalDate.of(2026, 12, 28), 80f, 48f)!!))
    }
}
