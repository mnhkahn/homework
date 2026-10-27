package com.homeworkbuddy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat

class HomeworkReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HomeworkReminderScheduler.fireDue(context)
    }

    companion object {
        private const val CHANNEL_ID = "homework_schedule_v3"
        internal const val NOTIFICATION_ID = 7112

        private fun ensureChannel(context: Context): NotificationManager {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "作业开始和截止提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
            // Remove notices left by older versions after their services have
            // restarted with the shared notification ID.
            val prefs = context.getSharedPreferences("homework_notifications", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("unified_notification_migrated", false)) {
                listOf(7101, 7110, 7111).forEach(manager::cancel)
                prefs.edit().putBoolean("unified_notification_migrated", true).apply()
            }
            return manager
        }

        /** The same silent, ongoing notification backs all three foreground services. */
        fun foregroundNotification(context: Context): Notification =
            build(context, HomeworkReminderScheduler.statusText(context), audible = false)

        private fun build(context: Context, message: String, audible: Boolean): Notification {
            ensureChannel(context)
            val open = PendingIntent.getActivity(context, 7101, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("作业提醒")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(!audible)
                .build()
        }

        fun showStatus(context: Context, message: String, audible: Boolean): Boolean {
            val notification = build(context, message, audible)
            val manager = ensureChannel(context)
            if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() ||
                manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            return try { manager.notify(NOTIFICATION_ID, notification); true } catch (_: SecurityException) { false }
        }
    }
}
