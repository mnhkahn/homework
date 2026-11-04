package com.homeworkbuddy

import java.time.DayOfWeek
import java.time.LocalDate

internal fun calendarWeekStart(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

/** Swipe left for the next week and right for the previous week. */
internal fun calendarSwipeDate(date: LocalDate, distance: Float, threshold: Float): LocalDate? = when {
    distance >= threshold -> date.minusWeeks(1)
    distance <= -threshold -> date.plusWeeks(1)
    else -> null
}
