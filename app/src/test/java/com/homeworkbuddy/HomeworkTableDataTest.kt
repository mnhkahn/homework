package com.homeworkbuddy

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class HomeworkTableDataTest {
    private val day = LocalDate.of(2026, 11, 21)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun tableUsesOnlyTheDueDateAndKeepsUnfinishedCorrectionsOnThatDate() {
        val today = task("due-today", "待改错", "2026-11-20T16:00:00Z")
        val yesterday = task("old-correction", "待改错", "2026-11-20T15:59:59Z")
        val submitted = task("finished-on-another-day", "已改错", "2026-11-21T10:00:00Z",
            completed = "2026-11-22T00:00:00Z")
        val history = HomeworkTask("history-only", "语文", "历史", 20, LocalTime.NOON, dueDate = day)
        assertEquals(listOf(today, submitted), homeworkTableForDate(listOf(yesterday, history, submitted, today), day, zone))
        assertEquals(listOf(yesterday), homeworkTableForDate(listOf(yesterday, today), day.minusDays(1), zone))
        assertNull(JSONObject().put("closed", true).put("due", "2026-11-21T01:00:00Z").toHomeworkTableCard("待改错"))
        assertNull(JSONObject().toHomeworkTableCard("待改错"))
    }

    @Test fun todoPrecedesCorrectionsAndEachSectionUsesPlannedStartThenDue() {
        val correcting = task("correcting", "待改错", start = "2026-11-21T07:00:00Z").withTableActivity(HomeworkTableActivity(500, true, false))
        val started = task("started", "待完成", start = "2026-11-21T06:00:00Z").withTableActivity(HomeworkTableActivity(400, false, false))
        val pending = task("pending", "待完成", start = "2026-11-21T09:00:00Z")
        val estimated = task("estimated", "待完成").copy(explicitEstimatedMinutes = 90)
        val wrongAttempt = task("wrong-attempt", "待改错", start = "2026-11-21T08:00:00Z").withTableActivity(HomeworkTableActivity(600, false, false))
        val unrelated = task("custom", "老师复核")
        val doneB = task("done-b", "已完成", "2026-11-21T11:00:00Z", "2026-11-21T01:00:00Z", "2026-11-21T08:00:00Z")
        val doneA = task("done-a", "已完成", completed = "2026-11-21T09:59:00Z", start = "2026-11-21T08:00:00Z")
            .withTableActivity(HomeworkTableActivity(700, false, false))
        val doneMissingTime = task("done-missing", "已完成")
        val corrected = task("corrected", "已改错", start = "2026-11-21T07:00:00Z")
        val input = listOf(corrected, doneMissingTime, wrongAttempt, doneB, unrelated, doneA, started, pending, correcting, estimated)
        val sorted = homeworkTableForDate(input, day, zone)
        assertEquals(listOf(started, estimated, pending, unrelated, correcting, wrongAttempt, corrected, doneA, doneB, doneMissingTime), sorted)
        assertEquals(listOf(started, estimated, pending, unrelated, correcting, wrongAttempt),
            sorted.filter { TaskProgressFilter.UNFINISHED.includes(it.tableGroup) })
        assertEquals(listOf(started, correcting), sorted.filter { TaskProgressFilter.RUNNING.includes(it.tableGroup) })
        assertEquals(listOf(corrected, doneA, doneB, doneMissingTime),
            sorted.filter { TaskProgressFilter.SUBMITTED.includes(it.tableGroup) })
    }

    @Test fun tabletEventsUseActualTimeAndCompletionWinsOnATie() {
        fun event(id: String, action: String, at: Int) = JSONObject().put("type", "commentCard").put(
            "data", JSONObject().put("card", JSONObject().put("id", id)).put("text",
                "作业活动\n[homework-activity:v1]\n" + JSONObject()
                    .put("task_id", id).put("event", action).put("at", at).put("correction", true)),
        )
        val activity = parseHomeworkTableActivity(JSONArray(listOf(
            event("old", "completed", 300), event("old", "started", 300),
            event("new", "started", 400), event("new", "completed", 200),
        )))
        assertNull(task("old", "待改错").withTableActivity(activity["old"]).tableStartedAt)
        assertEquals(400L, task("new", "待改错").withTableActivity(activity["new"]).tableStartedAt)
    }

    @Test fun displayReadsCNoteGradeAndTimeOverridesWithoutGuessingMissingValues() {
        val card = JSONObject().put("due", "2026-11-21T10:00:00Z")
            .put("desc", "类型：普通作业\n 打分结果：待批改\n<!-- cnote dateCompleted=2026-11-21T05:00:00Z -->")
            .put("dateCompleted", "2026-11-21T10:00:00Z")
            .toHomeworkTableCard("待改错")!!
        assertEquals("普通作业", card.typeLabel)
        assertEquals("待批改", card.gradeLabel)
        assertEquals(java.time.Instant.parse("2026-11-21T05:00:00Z").toEpochMilli(), card.completedAtMillis)
        assertNull(card.updatedAtMillis)
    }

    private fun task(id: String, list: String, due: String = "2026-11-21T10:00:00Z", completed: String? = null, start: String? = null): HomeworkTask {
        val card = JSONObject().put("due", due).put("dateCompleted", completed).put("start", start).toHomeworkTableCard(list)!!
        return HomeworkTask(id, "作业", id, 20, LocalTime.NOON, card.listStatus,
            isCorrection = list == "待改错", tableCard = card)
    }
}
