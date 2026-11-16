package com.homeworkbuddy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Live and local-only dates share the same table and detail renderer. */
internal fun homeworkTasksForDate(
    today: List<HomeworkTask>, week: List<HomeworkTask>, records: List<CompletionRecord>, date: LocalDate,
): List<HomeworkTask> {
    if (date == LocalDate.now()) return today
    val remote = week.filter { it.dueDate == date }
    val knownIds = remote.map { it.id }.toSet()
    return remote + records.filterNot { it.taskId in knownIds }.map { it.asHomeworkTask(date) }
}

internal fun CompletionRecord.asHomeworkTask(date: LocalDate): HomeworkTask {
    val deadline = Instant.ofEpochSecond(deadlineEpochSeconds).atZone(ZoneId.systemDefault()).toLocalTime()
    val base = taskSnapshot ?: HomeworkTask(taskId, "作业", title.ifBlank { "作业" }, 0, deadline)
    return base.copy(
        dueDate = date,
        status = when {
            completedAtEpochSeconds != null -> if (base.status == TaskStatus.CORRECTED || base.isCorrection) TaskStatus.CORRECTED else TaskStatus.COMPLETED
            base.status.isSubmitted -> TaskStatus.TODO
            else -> base.status
        },
        isCorrection = if (completedAtEpochSeconds != null) false else base.isCorrection,
        startedAtEpochSeconds = startedAtEpochSeconds,
        completedAtEpochSeconds = completedAtEpochSeconds,
        photoPath = localAudioUri ?: base.photoPath,
        photoUrls = attachments.filterNot { it.isAudio || it.isPdf }.map { it.url },
        attachments = attachments,
    )
}
