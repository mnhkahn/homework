package com.homeworkbuddy

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
    }
}
