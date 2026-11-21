package com.homeworkbuddy

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

data class HomeworkTableCard(
    val listName: String,
    val startAtMillis: Long?,
    val dueAtMillis: Long,
    val dueComplete: Boolean,
    val typeLabel: String?,
    val gradeLabel: String?,
    val completedAtMillis: Long?,
    val updatedAtMillis: Long?,
) {
    val listStatus: TaskStatus
        get() = when (listName.trim()) {
            "待完成" -> TaskStatus.TODO
            "待改错" -> TaskStatus.NEEDS_CORRECTION
            "已完成" -> TaskStatus.COMPLETED
            "已改错" -> TaskStatus.CORRECTED
            else -> if (dueComplete) TaskStatus.COMPLETED else TaskStatus.TODO
        }

    val submitted: Boolean get() = listName.trim() in listOf("已完成", "已改错")
}

internal val HomeworkTask.tableStartedAt: Long?
    get() = startedAtEpochSeconds?.takeIf {
        status == TaskStatus.RUNNING && tableCard?.dueComplete == false && !tableCard.submitted
    }

internal enum class HomeworkTableGroup(val label: String) {
    RUNNING("正在做"), PENDING("还没做"), SUBMITTED("做完"),
}

internal enum class TaskProgressFilter(val label: String) {
    ALL("全部进度"), UNFINISHED("未完成"), RUNNING("正在做"), SUBMITTED("已提交");

    fun includes(group: HomeworkTableGroup): Boolean = when (this) {
        ALL -> true
        UNFINISHED -> group != HomeworkTableGroup.SUBMITTED
        RUNNING -> group == HomeworkTableGroup.RUNNING
        SUBMITTED -> group == HomeworkTableGroup.SUBMITTED
    }
}

internal val HomeworkTask.tableGroup: HomeworkTableGroup
    get() = when {
        tableStartedAt != null -> HomeworkTableGroup.RUNNING
        tableCard?.submitted == true -> HomeworkTableGroup.SUBMITTED
        else -> HomeworkTableGroup.PENDING
    }

internal fun homeworkTableForDate(
    tasks: List<HomeworkTask>,
    date: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): List<HomeworkTask> {
    return tasks.filter { task ->
        task.tableCard?.let { Instant.ofEpochMilli(it.dueAtMillis).atZone(zone).toLocalDate() } == date
    }.sortedWith(
        compareBy<HomeworkTask> { it.tableGroup }
            .thenBy { it.tableCard!!.let { card -> card.startAtMillis ?: card.dueAtMillis } }
            .thenBy { it.tableCard!!.dueAtMillis },
    )
}

private val typeLine = Regex("""(?im)^[ \t]*(?:作业类型|类型|type)[ \t]*[:：][ \t]*([^\r\n]*)""")
private val gradeLine = Regex("""(?m)^[ \t]*打分结果[ \t]*[:：][ \t]*([^\r\n]*)""")
private val timeLine = Regex("""(?m)^<!-- cnote (dateCompleted|dateLastActivity)=([^\r\n]+) -->(?:\r?\n|$)""")

internal fun homeworkDescriptionText(description: String): String =
    timeLine.replace(description) { if (trelloTableTime(it.groupValues[2]) == null) it.value else "" }

internal fun JSONObject.toHomeworkTableCard(listName: String): HomeworkTableCard? {
    if (optBoolean("closed")) return null
    val due = trelloTableTime(optString("due")) ?: return null
    val overrides = mutableMapOf<String, Long>()
    val desc = timeLine.replace(optString("desc")) { match ->
        val time = trelloTableTime(match.groupValues[2]) ?: return@replace match.value
        overrides[match.groupValues[1]] = time
        ""
    }
    val structured = desc.trimStart().takeIf { it.startsWith("{") }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun time(field: String) = trelloTableTime(structured?.opt("_cnote_$field") as? String)
        ?: overrides[field] ?: trelloTableTime(optString(field))
    fun label(regex: Regex) = regex.find(desc)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    return HomeworkTableCard(
        listName, trelloTableTime(optString("start")), due, optBoolean("dueComplete"),
        ((structured?.opt("type") as? String)?.trim() ?: label(typeLine))?.ifEmpty { null },
        label(gradeLine), time("dateCompleted"), time("dateLastActivity"),
    )
}

private fun trelloTableTime(value: String?): Long? =
    value?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

internal data class HomeworkTableActivity(val at: Long, val correction: Boolean, val completed: Boolean)

internal fun parseHomeworkTableActivity(actions: JSONArray): Map<String, HomeworkTableActivity> = buildMap {
    for (index in 0 until actions.length()) {
        val action = actions.optJSONObject(index) ?: continue
        if (action.optString("type") != "commentCard") continue
        val data = action.optJSONObject("data") ?: continue
        val card = data.optJSONObject("card")?.optString("id")?.takeIf(String::isNotEmpty) ?: continue
        val lines = data.optString("text").split('\n', limit = 3)
        if (lines.size != 3 || lines[1].trimEnd() != "[homework-activity:v1]") continue
        val event = runCatching { JSONObject(lines[2]) }.getOrNull() ?: continue
        if (event.optString("task_id") != card) continue
        val at = when (val timestamp = event.opt("at")) {
            is Int -> timestamp.toLong()
            is Long -> timestamp
            else -> continue
        }
        if (at <= 0 || runCatching { Instant.ofEpochSecond(at).toEpochMilli() }.isFailure) continue
        val correction = event.opt("correction") as? Boolean ?: continue
        val completed = when (event.optString("event")) {
            "started" -> false
            "completed" -> true
            else -> continue
        }
        val current = get(card)
        if (current == null || at > current.at || (at == current.at && completed && !current.completed)) {
            put(card, HomeworkTableActivity(at, correction, completed))
        }
    }
}

internal fun HomeworkTask.withTableActivity(event: HomeworkTableActivity?): HomeworkTask {
    if (event == null || event.completed || tableCard?.dueComplete != false ||
        status.isSubmitted || event.correction != isCorrection
    ) return this
    return copy(status = TaskStatus.RUNNING, startedAtEpochSeconds = event.at)
}
