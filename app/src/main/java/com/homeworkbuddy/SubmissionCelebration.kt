package com.homeworkbuddy

internal enum class SubmissionCelebrationKind { NORMAL, ALL_DONE, EARLY, CORRECTION }

/** Correction encouragement acknowledges effort, without claiming the answer passed review. */
internal fun submissionCelebrationKind(isCorrection: Boolean, completedEarly: Boolean, allTasksComplete: Boolean): SubmissionCelebrationKind = when {
    isCorrection -> SubmissionCelebrationKind.CORRECTION
    completedEarly -> SubmissionCelebrationKind.EARLY
    allTasksComplete -> SubmissionCelebrationKind.ALL_DONE
    else -> SubmissionCelebrationKind.NORMAL
}
