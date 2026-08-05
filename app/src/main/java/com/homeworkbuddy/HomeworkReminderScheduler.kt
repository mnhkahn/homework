package com.homeworkbuddy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Rings a sound alarm at the earliest unfinished task's deadline. The chosen
 * trigger is kept in SharedPreferences so the receiver and boot can reschedule
 * without asking Trello again.
 */
object HomeworkReminderScheduler {
    private const val PREFS = "homework_reminder"
    private const val TRIGGER_AT = "trigger_at"
    private const val MESSAGE = "message"
    private const val REQUEST_CODE = 7101

    fun rescheduleFromTasks(context: Context, tasks: List<HomeworkTask>) {
        val now = LocalDateTime.now()
        val next = tasks.filter { it.status != TaskStatus.COMPLETED }
            .map { LocalDate.now().atTime(it.deadline) to it.title }
            .filter { it.first.isAfter(now) }
            .minByOrNull { it.first }
        if (next == null) {
            cancel(context)
        } else {
            schedule(context, next.first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), "该做作业啦：《${next.second}》")
        }
    }

    fun rescheduleFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val triggerAt = prefs.getLong(TRIGGER_AT, 0L)
        val message = prefs.getString(MESSAGE, null)
        if (triggerAt <= System.currentTimeMillis() || message.isNullOrBlank()) {
            cancel(context)
        } else {
            schedule(context, triggerAt, message)
        }
    }

    private fun schedule(context: Context, triggerAt: Long, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(TRIGGER_AT, triggerAt).putString(MESSAGE, message).apply()
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context, message)
        runCatching { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
            .recoverCatching { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
    }

    private fun cancel(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context, ""))
    }

    private fun pendingIntent(context: Context, message: String) = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, HomeworkReminderReceiver::class.java).putExtra("message", message),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
