package com.homeworkbuddy

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** One homework item as known on a given day, with its completion time if any. */
data class CompletionRecord(
    val taskId: String,
    val title: String,
    val deadlineEpochSeconds: Long,
    val completedAtEpochSeconds: Long?,
)

/**
 * Per-day homework history: which tasks existed each day and when each was
 * finished. The flower calendar and the weekly report are pure readers of this
 * store, so every completion path (photo submit, offline retry) must write here.
 */
class CompletionHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("completion_history", Context.MODE_PRIVATE)

    /** Merges the day's tasks into the history. Existing completions are never dropped. */
    fun noteTasks(tasks: List<HomeworkTask>, date: LocalDate = LocalDate.now()) {
        val day = dayJson(date)
        tasks.forEach { task ->
            val deadline = date.atTime(task.deadline).atZone(ZoneId.systemDefault()).toEpochSecond()
            val entry = day.optJSONObject(task.id) ?: JSONObject()
            entry.put("title", task.title).put("deadline", deadline)
            day.put(task.id, entry)
        }
        save(date, day)
    }

    /** Records the first completion of a task; later calls for the same task are ignored. */
    fun recordCompletion(taskId: String, completedAtEpochSeconds: Long, deadlineEpochSeconds: Long, date: LocalDate = LocalDate.now()) {
        val day = dayJson(date)
        val entry = day.optJSONObject(taskId) ?: JSONObject().put("title", "").put("deadline", deadlineEpochSeconds)
        if (entry.has("completed_at")) return
        entry.put("completed_at", completedAtEpochSeconds)
        day.put(taskId, entry)
        save(date, day)
    }

    /** All tasks recorded for a date, each with its completion time (null = not finished). */
    fun day(date: LocalDate): List<CompletionRecord> {
        val json = dayJson(date)
        return json.keys().asSequence().map { id ->
            val entry = json.getJSONObject(id)
            CompletionRecord(
                id,
                entry.optString("title"),
                entry.optLong("deadline"),
                if (entry.has("completed_at")) entry.getLong("completed_at") else null,
            )
        }.toList()
    }

    private fun dayJson(date: LocalDate) = runCatching { JSONObject(prefs.getString(key(date), "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun save(date: LocalDate, day: JSONObject) = prefs.edit().putString(key(date), day.toString()).apply()
    private fun key(date: LocalDate) = "day:$date"
}
