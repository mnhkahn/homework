package com.homeworkbuddy

import android.content.Context
import java.time.LocalDate

enum class DayMark { FLOWER, BLACK, PENDING, NONE }

/**
 * Finishing every task earns a 🌸 immediately, including late submissions.
 * Named unfinished tasks turn a past day 🖤. A day without tasks is NONE.
 */
class FlowerCalendar(context: Context) {
    private val store = CompletionHistoryStore(context)

    fun markFor(date: LocalDate): DayMark {
        val records = store.day(date)
        val namedMissing = records.filter { it.completedAtEpochSeconds == null && it.title.isNotBlank() }
        store.savedMark(date)?.let { saved ->
            // A black mark without a named missing item is not explainable and
            // must never survive as a punishment.
            if (saved == DayMark.BLACK && namedMissing.isEmpty()) {
                store.correctFinalMark(date, DayMark.FLOWER)
                return DayMark.FLOWER
            }
            return saved
        }
        return calculateDayMark(records, date, LocalDate.now()).also { mark ->
            if (mark == DayMark.FLOWER || mark == DayMark.BLACK) store.saveFinalMark(date, mark)
        }
    }

    /** Monday through Sunday of the current week. */
    fun currentWeek(): List<Pair<LocalDate, DayMark>> = weekOf(LocalDate.now())

    fun weekOf(date: LocalDate): List<Pair<LocalDate, DayMark>> {
        val monday = calendarWeekStart(date)
        return (0L..6L).map { offset -> monday.plusDays(offset).let { it to markFor(it) } }
    }
}

internal fun allHomeworkCompleted(records: List<CompletionRecord>): Boolean =
    records.isNotEmpty() && records.all { it.completedAtEpochSeconds != null }

internal fun calculateDayMark(records: List<CompletionRecord>, date: LocalDate, today: LocalDate): DayMark = when {
    records.isEmpty() -> DayMark.NONE
    allHomeworkCompleted(records) -> DayMark.FLOWER
    date.isBefore(today) && records.any { it.completedAtEpochSeconds == null && it.title.isNotBlank() } -> DayMark.BLACK
    date.isBefore(today) -> DayMark.FLOWER
    else -> DayMark.PENDING
}
