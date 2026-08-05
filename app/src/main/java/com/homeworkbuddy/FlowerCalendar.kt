package com.homeworkbuddy

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate

enum class DayMark { FLOWER, BLACK, PENDING, NONE }

/**
 * Judges each day: finishing every task before its own deadline earns a 🌸,
 * any late or unfinished task turns a past day 🖤. Today stays PENDING until
 * everything is done on time; a day without tasks is NONE (no penalty).
 */
class FlowerCalendar(context: Context) {
    private val store = CompletionHistoryStore(context)

    fun markFor(date: LocalDate): DayMark {
        val records = store.day(date)
        if (records.isEmpty()) return DayMark.NONE
        val allOnTime = records.all { record -> record.completedAtEpochSeconds?.let { it <= record.deadlineEpochSeconds } == true }
        return when {
            allOnTime -> DayMark.FLOWER
            date.isBefore(LocalDate.now()) -> DayMark.BLACK
            else -> DayMark.PENDING
        }
    }

    /** Monday through Sunday of the current week. */
    fun currentWeek(): List<Pair<LocalDate, DayMark>> {
        val monday = LocalDate.now().with(DayOfWeek.MONDAY)
        return (0L..6L).map { offset -> monday.plusDays(offset).let { it to markFor(it) } }
    }
}
