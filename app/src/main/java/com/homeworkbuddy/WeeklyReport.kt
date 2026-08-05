package com.homeworkbuddy

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/** Aggregates the current week (Monday to Sunday) from the completion history. */
class WeeklyReport(context: Context) {
    private val store = CompletionHistoryStore(context)
    private val calendar = FlowerCalendar(context)

    val weekStart: LocalDate = LocalDate.now().with(DayOfWeek.MONDAY)
    val weekEnd: LocalDate = weekStart.plusDays(6)
    private val records: List<CompletionRecord> = (0L..6L).flatMap { store.day(weekStart.plusDays(it)) }
    private val completions: List<Pair<Long, Long>> = records.mapNotNull { record ->
        record.completedAtEpochSeconds?.let { record.deadlineEpochSeconds to it }
    }

    val taskCount: Int = records.size
    val completedCount: Int = completions.size
    val completionRate: Double = if (taskCount == 0) 0.0 else completedCount.toDouble() / taskCount
    /** Positive means finished ahead of the deadline on average; null when nothing was completed yet. */
    val avgEarlyMinutes: Double? = completions.takeIf { it.isNotEmpty() }
        ?.map { (deadline, completedAt) -> (deadline - completedAt) / 60.0 }?.average()
    val flowerCount: Int
    val blackCount: Int
    val reward: Boolean get() = avgEarlyMinutes?.let { it > 0 } == true

    init {
        val marks = (0L..6L).map { calendar.markFor(weekStart.plusDays(it)) }
        flowerCount = marks.count { it == DayMark.FLOWER }
        blackCount = marks.count { it == DayMark.BLACK }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("week_start", weekStart.toString())
        .put("week_end", weekEnd.toString())
        .put("task_count", taskCount)
        .put("completed_count", completedCount)
        .put("completion_rate", completionRate)
        .put("flower_count", flowerCount)
        .put("black_count", blackCount)
        .put("avg_early_minutes", avgEarlyMinutes ?: JSONObject.NULL)
        .put("reward", reward)
}
