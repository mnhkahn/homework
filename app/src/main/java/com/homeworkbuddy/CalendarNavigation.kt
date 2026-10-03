package com.homeworkbuddy

import java.time.DayOfWeek
import java.time.LocalDate

internal fun calendarWeekStart(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

/** Rightward gestures show the next week; leftward gestures show the previous week. */
internal fun calendarSwipeDate(date: LocalDate, distance: Float, threshold: Float): LocalDate? = when {
    distance >= threshold -> date.plusWeeks(1)
    distance <= -threshold -> date.minusWeeks(1)
    else -> null
}
