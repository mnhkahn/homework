package com.homeworkbuddy

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/** Keeps the managed tablet audible even outside a study session. */
class MediaVolumeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val volumeChecker = object : Runnable {
        override fun run() {
            val policy = KioskPolicy(this@MediaVolumeService)
            if (!policy.isDeviceOwner) {
                stopSelf()
                return
            }
            policy.enforceMediaVolume()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(HomeworkReminderReceiver.NOTIFICATION_ID, HomeworkReminderReceiver.foregroundNotification(this))
        handler.removeCallbacks(volumeChecker)
        handler.post(volumeChecker)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(volumeChecker)
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) = ContextCompat.startForegroundService(
            context,
            Intent(context, MediaVolumeService::class.java),
        )
    }
}
