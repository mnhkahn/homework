package com.homeworkbuddy

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class HomeworkProgressTest {
    private val task = HomeworkTask("task", "数学", "口算", 10, LocalTime.NOON)

    @Test fun repeatedStartPreservesTheOriginalTime() {
        val started = task.startDoing(100)
        assertEquals(TaskStatus.RUNNING, started.status)
        assertEquals(100L, started.startDoing(200).startedAtEpochSeconds)
        assertEquals(90L, task.startDoing(200, previousStart = 90).startedAtEpochSeconds)
        assertEquals(task.afterSubmission(), task.afterSubmission().startDoing(200))
    }

    @Test fun correctionCanBeRunningWithoutLosingItsSubmissionKind() {
        val started = task.copy(isCorrection = true, status = TaskStatus.NEEDS_CORRECTION).startDoing(100)
        assertEquals(TaskStatus.RUNNING, started.status)
        assertTrue(started.submitsCorrection)
        assertEquals(TaskStatus.CORRECTED, started.afterSubmission().status)
        assertTrue(TaskProgressFilter.RUNNING.includes(started.status))
        assertFalse(TaskProgressFilter.RUNNING.includes(TaskStatus.TODO))
    }

    @Test fun legacyDatesUseTableTasksWithoutInventingMetadata() {
        val date = LocalDate.now().minusDays(7)
        val attachment = HomeworkAttachment("https://example.com/photo", "photo", "image/jpeg")
        val record = CompletionRecord("old", "旧作业", 1000, 100, 200, 100, null, listOf(attachment))
        val result = homeworkTasksForDate(emptyList(), emptyList(), listOf(record), date).single()
        assertEquals(TaskStatus.COMPLETED, result.status)
        assertEquals(200L, result.completedAtEpochSeconds)
        assertEquals(date, result.dueDate)
        assertNull(result.updatedAtEpochSeconds)
        assertEquals(listOf(attachment.url), result.photoUrls)
        val live = result.copy(title = "最新标题")
        assertEquals(listOf(live), homeworkTasksForDate(emptyList(), listOf(live), listOf(record), date))
    }

    @Test fun storedSnapshotRetainsGradeTypeAndCorrectionStatus() {
        val snapshot = task.copy(status = TaskStatus.CORRECTED, type = HomeworkTaskType.ARITHMETIC,
            gradeResult = HomeworkGradeResult.parse("100"), updatedAtEpochSeconds = 250)
        val record = CompletionRecord(task.id, task.title, 1000, 100, 200, 100, null, emptyList(), snapshot)
        val restored = record.asHomeworkTask(LocalDate.now().minusDays(1))
        assertEquals(snapshot.type, restored.type)
        assertEquals(snapshot.gradeResult, restored.gradeResult)
        assertEquals(TaskStatus.CORRECTED, restored.status)
        assertEquals(250L, restored.updatedAtEpochSeconds)
        val submittedCorrection = record.copy(taskSnapshot = snapshot.copy(status = TaskStatus.RUNNING, isCorrection = true))
            .asHomeworkTask(LocalDate.now().minusDays(1))
        assertEquals(TaskStatus.CORRECTED, submittedCorrection.status)
        assertFalse(submittedCorrection.isCorrection)
    }
}
