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
}
