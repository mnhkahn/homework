package com.homeworkbuddy

import java.util.Locale

/** A copy of the last valid observation, taken before photography pauses the monitor. */
internal data class HeadPosePhotoSnapshot(val pitch: Float?, val yaw: Float?, val distance: Float?) {
    fun watermarkLines(): List<String> = listOf(
        "拍照前坐姿",
        "仰角 ${pitch?.let { String.format(Locale.CHINA, "%+.0f°", it) } ?: "—"}",
        "左右转角 ${yaw?.let { String.format(Locale.CHINA, "%+.0f°", it) } ?: "—"}",
        "相对距离 ${distance?.let { String.format(Locale.CHINA, "%.2f×", it) } ?: "—"}",
    )
}

internal class HeadPosePhotoReadings {
    @Volatile private var latest: HeadPoseReading? = null

    fun update(reading: HeadPoseReading?) { latest = reading }

    fun snapshot(now: Long, baseline: (String) -> Float?): HeadPosePhotoSnapshot {
        val reading = latest?.takeIf { now - it.atMillis in 0 until 2_000 }
        return HeadPosePhotoSnapshot(
            reading?.pitch?.takeIf { it.isFinite() },
            reading?.yaw?.takeIf { it.isFinite() },
            reading?.let { HeadPoseMetrics.distanceRatio(baseline(it.calibrationKey), it.eyeSpan) },
        )
    }
}
