package com.homeworkbuddy

/** A future planned event; actual start and completion timestamps are separate. */
internal data class HomeworkReminderEvent(
    val taskId: String,
    val title: String,
    val kind: String,
    val atMillis: Long,
    val overtimeSinceMillis: Long? = null,
) {
    val isEffortReminder: Boolean get() = kind == "effort-warning" || overtimeSinceMillis != null

    val message: String get() = when (kind) {
        "start" -> "该开始《$title》了"
        "effort-warning" -> "《$title》已用掉预计耗时的 80%"
        "effort-overtime" -> {
            val minutes = (atMillis - requireNotNull(overtimeSinceMillis)) / 60_000
            if (minutes == 0L) "《$title》已用完预计耗时" else "《$title》已超出预计耗时 ${minutes} 分钟"
        }
        else -> "《$title》到截止时间了"
    }

    fun nextOvertimeReminder(nowMillis: Long): HomeworkReminderEvent? =
        overtimeSinceMillis?.let { copy(atMillis = nextOvertimeAt(it, nowMillis)) }
}

private const val OVERTIME_INTERVAL_MILLIS = 300_000L

private fun nextOvertimeAt(limitMillis: Long, nowMillis: Long): Long =
    if (nowMillis < limitMillis) limitMillis
    else limitMillis + ((nowMillis - limitMillis) / OVERTIME_INTERVAL_MILLIS + 1) * OVERTIME_INTERVAL_MILLIS

internal val HomeworkTask.estimatedEffortSeconds: Long get() = estimatedMinutes.coerceAtLeast(1) * 60L
internal val HomeworkTask.effortWarningSeconds: Long get() = estimatedEffortSeconds * 4 / 5

internal fun homeworkEffortReminderPlan(tasks: List<HomeworkTask>, nowMillis: Long): List<HomeworkReminderEvent> =
    tasks.flatMap { task ->
        val start = task.tableStartedAt?.times(1_000L) ?: return@flatMap emptyList()
        val warningAt = start + task.effortWarningSeconds * 1_000
        val limit = start + task.estimatedEffortSeconds * 1_000
        buildList {
            if (warningAt > nowMillis) add(HomeworkReminderEvent(task.id, task.title, "effort-warning", warningAt))
            add(HomeworkReminderEvent(task.id, task.title, "effort-overtime", nextOvertimeAt(limit, nowMillis), limit))
        }
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

/** Only newly observed, unfinished cards get an overdue notice during sync. */
internal fun newlyObservedOverdue(tasks: List<HomeworkTask>, seenIds: Set<String>, nowMillis: Long): List<HomeworkReminderEvent> =
    tasks.asSequence().filter { !it.status.isSubmitted && it.id !in seenIds }
        .mapNotNull { task ->
            val at = task.scheduledDueEpochSeconds?.times(1000L) ?: return@mapNotNull null
            if (at <= nowMillis) HomeworkReminderEvent(task.id, task.title, "due", at) else null
        }.distinctBy { it.taskId }.sortedBy { it.atMillis }.toList()
