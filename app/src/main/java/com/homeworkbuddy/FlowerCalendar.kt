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
        if (records.isEmpty()) return DayMark.NONE
        val allOnTime = records.all { record -> record.completedAtEpochSeconds?.let { it <= record.deadlineEpochSeconds } == true }
        return when {
            allOnTime -> DayMark.FLOWER.also { store.saveFinalMark(date, it) }
            date.isBefore(LocalDate.now()) && namedMissing.isNotEmpty() -> DayMark.BLACK.also { store.saveFinalMark(date, it) }
            date.isBefore(LocalDate.now()) -> DayMark.FLOWER.also { store.saveFinalMark(date, it) }
            else -> DayMark.PENDING
        }
    }

    /** Monday through Sunday of the current week. */
    fun currentWeek(): List<Pair<LocalDate, DayMark>> {
        val monday = LocalDate.now().with(DayOfWeek.MONDAY)
        return (0L..6L).map { offset -> monday.plusDays(offset).let { it to markFor(it) } }
    }
}
