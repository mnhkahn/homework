package com.homeworkbuddy

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class HomeworkSubmissionStateTest {
    private val task = HomeworkTask("original-card", "数学", "口算", 10, LocalTime.of(19, 0), task = "1 + 1 = ___")

    @Test fun initialAndCorrectedSubmissionsRemainDistinct() {
        val initial = task.afterSubmission()
        assertEquals(TaskStatus.COMPLETED, initial.status)
        val correction = initial.copy(status = TaskStatus.NEEDS_CORRECTION, isCorrection = true)
        val corrected = correction.afterSubmission()
        assertEquals(TaskStatus.CORRECTED, corrected.status)
        assertTrue(corrected.status.isSubmitted)
        assertFalse(corrected.isCorrection)
        assertEquals(initial.id, corrected.id)
        assertEquals(initial.task, corrected.task)
        val rejectedAgain = corrected.copy(status = TaskStatus.NEEDS_CORRECTION, isCorrection = true)
        assertFalse(rejectedAgain.status.isSubmitted)
        assertEquals(TaskStatus.CORRECTED, rejectedAgain.afterSubmission().status)
    }

    @Test fun offlineCorrectionRetryKeepsItsSubmissionKindAfterRefresh() {
        assertEquals(TaskStatus.CORRECTED, task.afterSubmission(isCorrection = true).status)
        assertTrue(TaskStatus.COMPLETED.isSubmitted)
        assertTrue(TaskStatus.CORRECTED.isSubmitted)
        listOf(TaskStatus.TODO, TaskStatus.NEEDS_CORRECTION, TaskStatus.RUNNING, TaskStatus.OVERTIME)
            .forEach { assertFalse(it.isSubmitted) }
    }

    @Test fun repeatedSupplementsKeepTheCompletedListAndOriginalCard() {
        for (status in listOf(TaskStatus.COMPLETED, TaskStatus.CORRECTED)) {
            var completed = task.copy(status = status)
            repeat(3) {
                val correctionUpload = completed.submitsCorrection
                assertEquals(status == TaskStatus.CORRECTED, correctionUpload)
                // The same flag is persisted for offline retries and sent to Trello.
                completed = completed.afterSubmission(correctionUpload)
                assertEquals(status, completed.status)
                assertEquals(task.id, completed.id)
                assertFalse(completed.isCorrection)
            }
            assertEquals(status, completed.afterSubmission().status)
        }
    }
}
