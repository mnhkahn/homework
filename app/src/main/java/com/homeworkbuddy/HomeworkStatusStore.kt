package com.homeworkbuddy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Shared, persisted view used by the UI and the MCP background connection. */
class HomeworkStatusStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("homework_status", Context.MODE_PRIVATE)

    fun save(tasks: List<HomeworkTask>, selectedId: String, remainingSeconds: Int, running: Boolean) {
        val values = JSONArray().also { list -> tasks.forEach { task ->
            list.put(JSONObject().put("id", task.id).put("subject", task.subject).put("title", task.title).put("status", task.status.name))
        } }
        prefs.edit().putString("tasks", values.toString()).putString("selected_id", selectedId)
            .putInt("remaining_seconds", remainingSeconds).putBoolean("running", running).apply()
    }

    fun snapshot(): JSONObject {
        val tasks = JSONArray(prefs.getString("tasks", "[]"))
        var completed = 0
        for (index in 0 until tasks.length()) if (tasks.getJSONObject(index).optString("status") == TaskStatus.COMPLETED.name) completed++
        return JSONObject().put("task_count", tasks.length()).put("completed_count", completed)
            .put("current_task_id", prefs.getString("selected_id", ""))
            .put("remaining_seconds", prefs.getInt("remaining_seconds", 0))
            .put("timer_running", prefs.getBoolean("running", false))
            .put("piano_practice_count_today", PianoPracticeStore(context).todayTotal())
            .put("tasks", tasks)
    }
}
