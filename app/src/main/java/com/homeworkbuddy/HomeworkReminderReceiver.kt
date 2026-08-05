package com.homeworkbuddy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat

class HomeworkReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // A channel keeps the sound it was created with, so a new id is required
        // to turn the alarm sound on for existing installs.
        val channel = NotificationChannel(CHANNEL_ID, "作业提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
        manager.notify(
            intent.getIntExtra("id", 0),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("作业小伙伴")
                .setContentText(intent.getStringExtra("message") ?: "该做作业啦！")
                .setAutoCancel(true)
                .build(),
        )
        HomeworkReminderScheduler.rescheduleFromPrefs(context)
    }

    private companion object {
        const val CHANNEL_ID = "homework_reminder_v2"
    }
}
