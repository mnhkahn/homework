package com.homeworkbuddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Remote capture has priority; wait for CameraDevice.onClosed before opening its camera. */
internal object HeadPoseCameraAccess {
    val photoReadings = HeadPosePhotoReadings()
    private var monitor: HeadPoseCamera? = null
    private var reservations = 0
    private val busyState = MutableStateFlow(false)
    val busy = busyState.asStateFlow()

    @Synchronized fun attach(camera: HeadPoseCamera): Boolean {
        if (reservations > 0 || monitor != null) return false
        monitor = camera
        return true
    }

    @Synchronized fun detached(camera: HeadPoseCamera) {
        if (monitor === camera) monitor = null
    }

    suspend fun reserve(): AutoCloseable {
        val camera = synchronized(this) {
            reservations++
            busyState.value = true
            monitor
        }
        val released = AtomicBoolean(false)
        val lease = AutoCloseable {
            if (released.compareAndSet(false, true)) synchronized(this) {
                reservations--
                busyState.value = reservations > 0
            }
        }
        try {
            if (camera != null) withContext(Dispatchers.IO) { camera.stop().get(5, TimeUnit.SECONDS) }
            return lease
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    suspend fun <T> withRemoteCamera(action: suspend () -> T): T = reserve().use { action() }
}
