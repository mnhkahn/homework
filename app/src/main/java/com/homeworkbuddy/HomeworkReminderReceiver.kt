package com.homeworkbuddy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class HomeworkReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("homework", "作业提醒", NotificationManager.IMPORTANCE_HIGH))
        manager.notify(intent.getIntExtra("id", 0), NotificationCompat.Builder(context, "homework").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("作业小伙伴").setContentText(intent.getStringExtra("message") ?: "该做作业啦！").setAutoCancel(true).build())
    }
}
