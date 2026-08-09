package com.homeworkbuddy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * A small read-through cache for the child-facing home screen. Trello remains
 * authoritative: cached cards are shown immediately, then replaced by the
 * next automatic network refresh.
 */
class HomeworkCacheStore(context: Context) {
    private val prefs = context.getSharedPreferences("homework_task_cache", Context.MODE_PRIVATE)

    fun todayTasks(): List<HomeworkTask> = tasksFor("today", LocalDate.now().toString())

    fun saveToday(tasks: List<HomeworkTask>) = save("today", LocalDate.now().toString(), tasks)

    fun weekTasks(): List<HomeworkTask> = tasksFor("week", LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString())

    fun saveWeek(tasks: List<HomeworkTask>) = save("week", LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString(), tasks)

    private fun tasksFor(prefix: String, expectedDate: String): List<HomeworkTask> {
        if (prefs.getString("${prefix}_date", null) != expectedDate) return emptyList()
        val stored = prefs.getString("${prefix}_tasks", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).map { index -> decode(array.getJSONObject(index)) }
        }.getOrElse { emptyList() }
    }

    private fun save(prefix: String, date: String, tasks: List<HomeworkTask>) {
        val values = JSONArray().also { array -> tasks.forEach { task -> array.put(encode(task)) } }
        prefs.edit().putString("${prefix}_date", date).putString("${prefix}_tasks", values.toString()).apply()
    }

    private fun encode(task: HomeworkTask) = JSONObject().apply {
        put("id", task.id)
        put("subject", task.subject)
        put("title", task.title)
        put("estimated_minutes", task.estimatedMinutes)
        put("deadline", task.deadline.toString())
        put("status", task.status.name)
        put("photo_path", task.photoPath)
        put("photo_urls", JSONArray(task.photoUrls))
        put("due_date", task.dueDate.toString())
        put("completed_at", task.completedAtEpochSeconds)
    }

    private fun decode(value: JSONObject): HomeworkTask = HomeworkTask(
        id = value.getString("id"),
        subject = value.optString("subject", "作业"),
        title = value.optString("title", "作业"),
        estimatedMinutes = value.optInt("estimated_minutes", 20),
        deadline = LocalTime.parse(value.getString("deadline")),
        status = value.optString("status").let { runCatching { TaskStatus.valueOf(it) }.getOrDefault(TaskStatus.TODO) },
        photoPath = value.optString("photo_path").ifBlank { null },
        photoUrls = value.optJSONArray("photo_urls")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optString(index).ifBlank { null } }
        } ?: emptyList(),
        dueDate = value.optString("due_date").let { runCatching { LocalDate.parse(it) }.getOrDefault(LocalDate.now()) },
        completedAtEpochSeconds = value.optLong("completed_at").takeIf { it > 0 },
    )
}
