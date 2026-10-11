package com.homeworkbuddy

import android.app.Application
import android.annotation.SuppressLint
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel

/** Survives Activity recreation while the external recorder is in front. */
class RecordingScreenLock(application: Application) : AndroidViewModel(application) {
    // A window flag stops applying when our Activity is behind the system recorder.
    // A screen wake lock covers that external capture session, including on battery.
    @Suppress("DEPRECATION")
    private val wakeLock = application.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "HomeworkBuddy:Recording")
        .apply { setReferenceCounted(false) }

    @SuppressLint("WakelockTimeout") // Released on recorder return, launch failure or owner destruction.
    fun acquire() {
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun onCleared() {
        release()
    }
}
