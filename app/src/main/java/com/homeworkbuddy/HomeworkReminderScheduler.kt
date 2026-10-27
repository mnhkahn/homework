package com.homeworkbuddy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Persist all upcoming events so alarms continue without a foreground activity. */
object HomeworkReminderScheduler {
    private const val PREFS = "homework_reminder"
    private const val EVENTS = "events_v3"
    private const val LAST_MESSAGE = "last_message_v3"
    private const val REQUEST_CODE = 7101

    fun rescheduleFromTasks(context: Context, tasks: List<HomeworkTask>) {
        if (tasks.none { !it.status.isSubmitted }) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(LAST_MESSAGE).apply()
        }
        val now = System.currentTimeMillis()
        val future = homeworkReminderPlan(tasks, now)
        // A refresh may race an alarm at its boundary. Preserve an already
        // scheduled event briefly if it still matches the latest task data.
        val validRecent = homeworkReminderPlan(tasks, now - 120_000L).toSet()
        val due = load(context).filter { it.atMillis <= now && it in validRecent }
        save(context, (future + due).distinct().sortedBy { it.atMillis })
        if (due.isNotEmpty()) fireDue(context, now) else {
            scheduleNext(context)
            HomeworkReminderReceiver.showStatus(context, statusText(context), audible = false)
        }
    }

    fun taskStarted(context: Context, taskId: String) = removeEvents(context, taskId, onlyStart = true)
    fun taskCompleted(context: Context, taskId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(LAST_MESSAGE).apply()
        removeEvents(context, taskId, onlyStart = false)
    }

    private fun removeEvents(context: Context, taskId: String, onlyStart: Boolean) {
        save(context, load(context).filterNot { it.taskId == taskId && (!onlyStart || it.kind == "start") })
        scheduleNext(context)
        HomeworkReminderReceiver.showStatus(context, statusText(context), audible = false)
    }

    fun rescheduleFromPrefs(context: Context) {
        val now = System.currentTimeMillis()
        save(context, load(context).filter { it.atMillis > now })
        scheduleNext(context)
        HomeworkReminderReceiver.showStatus(context, statusText(context), audible = false)
    }

    /** A stale broadcast cannot revive a cancelled or updated event. */
    internal fun fireDue(context: Context, now: Long = System.currentTimeMillis()) {
        val events = load(context)
        val due = events.filter { it.atMillis <= now }
        if (due.isEmpty()) { scheduleNext(context); return }
        save(context, events.filter { it.atMillis > now })
        val message = due.joinToString("；") { it.message }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST_MESSAGE, message).apply()
        scheduleNext(context)
        HomeworkReminderReceiver.showStatus(context, message, audible = true)
    }

    internal fun statusText(context: Context): String {
        val next = load(context).firstOrNull()
        if (next != null) {
            val time = java.time.Instant.ofEpochMilli(next.atMillis).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
            return "下次提醒 $time · ${next.message}"
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_MESSAGE, null) ?: "当前没有待提醒的作业"
    }

    private fun scheduleNext(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(context, REQUEST_CODE, Intent(context, HomeworkReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        val next = load(context).firstOrNull() ?: return
        runCatching { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atMillis, pending) }
            .recoverCatching { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atMillis, pending) }
    }

    private fun load(context: Context): List<HomeworkReminderEvent> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EVENTS, "[]"))
        (0 until array.length()).map { index -> array.getJSONObject(index).let { value ->
            HomeworkReminderEvent(value.getString("id"), value.getString("title"), value.getString("kind"), value.getLong("at"))
        } }.sortedBy { it.atMillis }
    }.getOrDefault(emptyList())

    private fun save(context: Context, events: List<HomeworkReminderEvent>) {
        val array = JSONArray()
        events.forEach { array.put(JSONObject().put("id", it.taskId).put("title", it.title).put("kind", it.kind).put("at", it.atMillis)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(EVENTS, array.toString()).apply()
    }
}
