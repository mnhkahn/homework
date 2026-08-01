package com.homeworkbuddy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Runs only during a managed study session. A dynamic USER_PRESENT receiver is
 * reliable on modern Android, unlike a manifest-only implicit receiver.
 */
class StudySessionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scheduleChecker = object : Runnable {
        override fun run() {
            val policy = KioskPolicy(this@StudySessionService)
            if (!policy.isDeviceOwner || policy.mode() != KioskMode.STUDY) {
                // MIUI can defer exact alarms by almost an hour. The foreground
                // service remains alive for the study session, so it is the
                // reliable final authority for leaving study mode on time.
                policy.applyForCurrentTime(navigate = true)
                stopSelf()
                return
            }
            val importance = getSystemService(ActivityManager::class.java)
                .runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }
                ?.importance
            val isInteractive = getSystemService(PowerManager::class.java).isInteractive
            if (isInteractive && importance != null && importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && !policy.isExternalForegroundAllowed) {
                // HyperOS can close a locked task from its tablet window menu
                // while ActivityManager still reports LOCK_TASK_MODE_LOCKED.
                // The foreground service drops below foreground importance even
                // though the vendor leaves the lock-task state stuck at LOCKED.
                policy.restoreManagedTask()
            }
            handler.postDelayed(this, SCHEDULE_CHECK_INTERVAL_MS)
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            val policy = KioskPolicy(this@StudySessionService)
            if (policy.isDeviceOwner && policy.mode() == KioskMode.STUDY) {
                policy.applyForCurrentTime(navigate = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val policy = KioskPolicy(this)
        if (!policy.isDeviceOwner || policy.mode() != KioskMode.STUDY) {
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "学习时间管理", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("作业小伙伴")
                .setContentText("学习时间管理已开启")
                .setOngoing(true)
                .build(),
        )
        handler.removeCallbacks(scheduleChecker)
        handler.post(scheduleChecker)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(scheduleChecker)
        unregisterReceiver(unlockReceiver)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // The MIUI tablet window menu's close button removes the foreground
        // task. During study time it must not expose the ordinary launcher.
        val policy = KioskPolicy(this)
        if (policy.isDeviceOwner && policy.mode() == KioskMode.STUDY) {
            policy.restoreManagedTask()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "study_session"
        private const val NOTIFICATION_ID = 7110
        private const val SCHEDULE_CHECK_INTERVAL_MS = 1_000L

        fun start(context: Context) = ContextCompat.startForegroundService(
            context,
            Intent(context, StudySessionService::class.java),
        )

        fun stop(context: Context) {
            context.stopService(Intent(context, StudySessionService::class.java))
        }
    }
}
