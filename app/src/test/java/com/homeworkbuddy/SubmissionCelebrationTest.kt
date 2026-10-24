package com.homeworkbuddy

import org.junit.Assert.assertEquals
import org.junit.Test

class SubmissionCelebrationTest {
    @Test fun correctionAlwaysGetsCorrectionEncouragementEvenWhenItFinishesTheDay() {
        assertEquals(SubmissionCelebrationKind.CORRECTION, submissionCelebrationKind(true, false, true))
        assertEquals(SubmissionCelebrationKind.CORRECTION, submissionCelebrationKind(true, true, true))
    }

    @Test fun ordinarySubmissionKeepsItsExistingEncouragement() {
        assertEquals(SubmissionCelebrationKind.NORMAL, submissionCelebrationKind(false, false, false))
        assertEquals(SubmissionCelebrationKind.ALL_DONE, submissionCelebrationKind(false, false, true))
        assertEquals(SubmissionCelebrationKind.EARLY, submissionCelebrationKind(false, true, false))
    }
}
