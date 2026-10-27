package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class HomeworkReminderPlanTest {
    private val now = 1_700_000_000_000L
    private val due = now / 1000 + 7200
    private fun task(id: String = "one") = HomeworkTask(id, "数学", "口算", 20, LocalTime.NOON, scheduledDueEpochSeconds = due)

    @Test fun explicitStartAndDeadlineProduceBothEvents() {
        val events = homeworkReminderPlan(listOf(task().copy(scheduledStartEpochSeconds = due - 1800, explicitEstimatedMinutes = 10)), now)
        assertEquals(listOf("start", "due"), events.map { it.kind })
        assertEquals((due - 1800) * 1000, events.first().atMillis)
    }

    @Test fun fallbackUsesOnlyExplicitDuration() {
        assertEquals(listOf("due"), homeworkReminderPlan(listOf(task()), now).map { it.kind })
        val events = homeworkReminderPlan(listOf(task().copy(explicitEstimatedMinutes = 30)), now)
        assertEquals((due - 1800) * 1000, events.first().atMillis)
        assertEquals(30, explicitHomeworkMinutes("类型：口算\n预计用时：30分钟\n作业内容：1+1"))
        assertNull(explicitHomeworkMinutes("预计用时: 999\n作业内容：1+1"))
        assertNull(explicitHomeworkMinutes("没有预计用时"))
    }

    @Test fun startedAndCompletedTasksDoNotTriggerObsoleteEvents() {
        val started = task().copy(scheduledStartEpochSeconds = due - 1800, startedAtEpochSeconds = now / 1000)
        assertEquals(listOf("due"), homeworkReminderPlan(listOf(started), now).map { it.kind })
        assertTrue(homeworkReminderPlan(listOf(started.copy(status = TaskStatus.COMPLETED)), now).isEmpty())
        assertTrue(homeworkReminderPlan(listOf(task().copy(scheduledDueEpochSeconds = now / 1000 - 1)), now).isEmpty())
    }

    @Test fun allFutureTasksAndSimultaneousEventsAreRetained() {
        val a = task("a").copy(scheduledStartEpochSeconds = due - 600)
        val b = task("b").copy(scheduledStartEpochSeconds = due - 600)
        val events = homeworkReminderPlan(listOf(a, b, a), now)
        assertEquals(4, events.size)
        assertEquals(listOf("start", "start", "due", "due"), events.map { it.kind })
    }

    @Test fun trelloDatesPreserveOffsetsAndRejectMissingValues() {
        assertEquals(1_700_000_000L, parseTrelloDate("2023-11-15T06:13:20+08:00"))
        assertNull(parseTrelloDate("null"))
        assertNull(parseTrelloDate(""))
        assertNull(parseTrelloDate("invalid"))
    }
}
