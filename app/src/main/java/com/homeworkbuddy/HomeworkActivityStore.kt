package com.homeworkbuddy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Append-only task events, separate from the replaceable task/date caches. */
class HomeworkActivityStore(context: Context) {
    private val prefs = PlayDemo.preferences(context, "homework_activity")

    @Synchronized fun start(task: HomeworkTask, at: Long): HomeworkTask {
        if (task.status.isSubmitted) return task
        val events = events()
        val previous = latest(events, task.id)
        val started = task.startDoing(at, previous?.takeIf { it.optString("event") == "started" && it.optBoolean("correction") == task.isCorrection }?.optLong("at"))
        if (previous?.optString("event") != "started" || previous.optBoolean("correction") != task.isCorrection) {
            append(events, task, "started", started.startedAtEpochSeconds!!)
        }
        return started
    }

    @Synchronized fun complete(taskId: String, at: Long) {
        val events = events()
        val previous = latest(events, taskId) ?: return
        if (previous.optString("event") != "started") return
        val event = JSONObject(previous.toString()).put("id", UUID.randomUUID().toString())
            .put("event", "completed").put("at", at).put("synced", false)
            .put("started_at", previous.getLong("at"))
            .put("duration_seconds", (at - previous.getLong("at")).coerceAtLeast(0))
        events.put(event)
        save(events)
    }

    fun restore(task: HomeworkTask): HomeworkTask {
        val previous = latest(events(), task.id) ?: return task
        if (task.status.isSubmitted) return task.copy(startedAtEpochSeconds = task.startedAtEpochSeconds
            ?: previous.optLong("started_at", previous.optLong("at")))
        if (previous.optString("event") != "started" || previous.optBoolean("correction") != task.isCorrection) return task
        return task.startDoing(previous.getLong("at"))
    }

    fun pending(): List<JSONObject> = events().let { list ->
        (0 until list.length()).map { list.getJSONObject(it) }.filterNot { it.optBoolean("synced") }
    }

    @Synchronized fun markSynced(id: String) {
        val events = events()
        for (index in 0 until events.length()) {
            events.getJSONObject(index).takeIf { it.optString("id") == id }?.put("synced", true)
        }
        save(events)
    }

    private fun append(events: JSONArray, task: HomeworkTask, event: String, at: Long) {
        events.put(JSONObject().put("id", UUID.randomUUID().toString()).put("task_id", task.id)
            .put("title", task.title).put("date", task.dueDate.toString()).put("event", event)
            .put("at", at).put("correction", task.isCorrection).put("synced", false))
        save(events)
    }
    private fun latest(events: JSONArray, taskId: String): JSONObject? =
        (events.length() - 1 downTo 0).firstNotNullOfOrNull { events.getJSONObject(it).takeIf { event -> event.optString("task_id") == taskId } }
    private fun events() = JSONArray(prefs.getString("events", "[]"))
    // Commit before changing the visible state so a process exit cannot lose the start event.
    private fun save(events: JSONArray) { check(prefs.edit().putString("events", events.toString()).commit()) { "保存作业流水失败" } }
}

internal fun HomeworkTask.startDoing(at: Long, previousStart: Long? = null): HomeworkTask =
    if (status.isSubmitted) this else copy(status = TaskStatus.RUNNING,
        startedAtEpochSeconds = previousStart?.takeIf { it > 0 }
            ?: startedAtEpochSeconds?.takeIf { status == TaskStatus.RUNNING } ?: at)
