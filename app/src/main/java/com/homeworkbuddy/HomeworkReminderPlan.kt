package com.homeworkbuddy

/** A future planned event; actual start and completion timestamps are separate. */
internal data class HomeworkReminderEvent(val taskId: String, val title: String, val kind: String, val atMillis: Long) {
    val message: String get() = if (kind == "start") "该开始《$title》了" else "《$title》到截止时间了"
}

internal fun homeworkReminderPlan(tasks: List<HomeworkTask>, nowMillis: Long): List<HomeworkReminderEvent> =
    tasks.filterNot { it.status.isSubmitted }.flatMap { task ->
        val due = task.scheduledDueEpochSeconds
        val start = task.scheduledStartEpochSeconds ?: due?.let { deadline -> task.explicitEstimatedMinutes?.let { deadline - it * 60L } }
        buildList {
            if (task.startedAtEpochSeconds == null && task.status != TaskStatus.RUNNING && start != null && (due == null || start < due) && start * 1000L > nowMillis) {
                add(HomeworkReminderEvent(task.id, task.title, "start", start * 1000L))
            }
            if (due != null && due * 1000L > nowMillis) add(HomeworkReminderEvent(task.id, task.title, "due", due * 1000L))
        }
    }.distinctBy { Triple(it.taskId, it.kind, it.atMillis) }.sortedWith(compareBy<HomeworkReminderEvent> { it.atMillis }.thenBy { it.taskId }.thenBy { it.kind })
