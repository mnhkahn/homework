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

    @Test fun newlySyncedOverdueTasksAreReportedOncePerCard() {
        val overdue = task("new").copy(scheduledDueEpochSeconds = now / 1000 - 90)
        val old = task("known").copy(scheduledDueEpochSeconds = now / 1000 - 90)
        val completed = task("done").copy(status = TaskStatus.COMPLETED, scheduledDueEpochSeconds = now / 1000 - 90)
        val future = task("future")
        val noDeadline = task("undated").copy(scheduledDueEpochSeconds = null)
        val tasks = listOf(overdue, old, completed, future, noDeadline, overdue)
        assertEquals(listOf("new"), newlyObservedOverdue(tasks, setOf("known"), now).map { it.taskId })
        assertTrue(newlyObservedOverdue(tasks, setOf("known", "new"), now + 60_000).isEmpty())
    }

    @Test fun aPreviouslyObservedFutureTaskIsNotRealertedAsNewAfterDeadline() {
        val existing = task("seen")
        assertTrue(newlyObservedOverdue(listOf(existing), setOf("seen"), (due + 1) * 1000).isEmpty())
        assertEquals(listOf("seen"), newlyObservedOverdue(listOf(existing), emptySet(), (due + 1) * 1000).map { it.taskId })
    }

    private fun runningTask() = task().copy(
        status = TaskStatus.RUNNING,
        startedAtEpochSeconds = now / 1_000,
        tableCard = HomeworkTableCard("待完成", null, now + 3_600_000, false, null, null, null, null),
    )

    @Test fun effortRemindersFollowActualStartAndRepeatAtFiveMinuteBoundaries() {
        val started = runningTask().copy(scheduledStartEpochSeconds = (now - 180_000) / 1_000)
        val (warning, limit) = homeworkEffortReminderPlan(listOf(started), now)
        assertEquals(now + 960_000, warning.atMillis)
        assertNull(warning.nextOvertimeReminder(warning.atMillis))
        assertEquals(now + 1_200_000, limit.atMillis)
        val firstRepeat = requireNotNull(limit.nextOvertimeReminder(limit.atMillis))
        assertEquals(limit.atMillis + 300_000, firstRepeat.atMillis)
        assertTrue(firstRepeat.message.contains("5 分钟"))
        assertEquals(limit.atMillis + 900_000, firstRepeat.nextOvertimeReminder(limit.atMillis + 620_000)?.atMillis)
    }

    @Test fun effortRemindersDoNotBackfillOrContinueAfterSubmission() {
        val started = runningTask()
        val afterLimit = now + 1_320_000
        val remaining = homeworkEffortReminderPlan(listOf(started), afterLimit)
        assertEquals(listOf(now + 1_500_000), remaining.map { it.atMillis })
        assertTrue(homeworkEffortReminderPlan(listOf(started.copy(status = TaskStatus.COMPLETED)), afterLimit).isEmpty())
        assertTrue(homeworkEffortReminderPlan(listOf(task()), now).isEmpty())
    }

    @Test fun actualEffortTracksRunningTimeAndFreezesAtCompletion() {
        val started = runningTask()
        assertEquals(180, started.tableActualEffortSeconds(now / 1_000 + 180))
        val done = started.copy(status = TaskStatus.COMPLETED, completedAtEpochSeconds = now / 1_000 + 240)
        assertEquals(240, done.tableActualEffortSeconds(now / 1_000 + 360))
        assertNull(done.copy(startedAtEpochSeconds = null).tableActualEffortSeconds(now / 1_000))
    }

    @Test fun trelloDatesPreserveOffsetsAndRejectMissingValues() {
        assertEquals(1_700_000_000L, parseTrelloDate("2023-11-15T06:13:20+08:00"))
        assertNull(parseTrelloDate("null"))
        assertNull(parseTrelloDate(""))
        assertNull(parseTrelloDate("invalid"))
    }
}
