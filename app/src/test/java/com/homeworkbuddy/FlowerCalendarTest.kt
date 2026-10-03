package com.homeworkbuddy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FlowerCalendarTest {
    private val today = LocalDate.of(2026, 10, 3)
    private fun task(id: String, completed: Long?) = CompletionRecord(
        taskId = id, title = "作业$id", deadlineEpochSeconds = 100,
        startedAtEpochSeconds = null, completedAtEpochSeconds = completed,
        durationSeconds = null, localAudioUri = null, attachments = emptyList(),
    )

    @Test fun allLateCompletionsEarnFlowerTodayImmediately() {
        assertEquals(DayMark.FLOWER, calculateDayMark(listOf(task("1", 200), task("2", 300)), today, today))
    }

    @Test fun mixedOnTimeAndLateCompletionsEarnFlower() {
        assertEquals(DayMark.FLOWER, calculateDayMark(listOf(task("1", 90), task("2", 200)), today, today))
    }

    @Test fun unfinishedTaskKeepsTodayPending() {
        assertEquals(DayMark.PENDING, calculateDayMark(listOf(task("1", 200), task("2", null)), today, today))
    }

    @Test fun noTasksDoesNotEarnFlower() {
        assertEquals(DayMark.NONE, calculateDayMark(emptyList(), today, today))
    }

    @Test fun pastDayUsesSameCompletionRule() {
        assertEquals(DayMark.FLOWER, calculateDayMark(listOf(task("1", 200)), today.minusDays(1), today))
        assertEquals(DayMark.BLACK, calculateDayMark(listOf(task("1", null)), today.minusDays(1), today))
    }
}
