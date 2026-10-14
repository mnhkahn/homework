package com.homeworkbuddy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "媒体音量保护", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("作业小伙伴")
                .setContentText("媒体音量最低限制已开启")
                .setOngoing(true)
                .build(),
        )
        handler.removeCallbacks(volumeChecker)
        handler.post(volumeChecker)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(volumeChecker)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "media_volume"
        private const val NOTIFICATION_ID = 7111

        fun start(context: Context) = ContextCompat.startForegroundService(
            context,
            Intent(context, MediaVolumeService::class.java),
        )
    }
}
