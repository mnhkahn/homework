package com.homeworkbuddy

import kotlin.math.abs
import kotlin.math.cos

/** Geometry only; distance is a same-person, same-camera ratio, never centimetres. */
internal object HeadPoseMetrics {
    fun frontalEyeSpan(eyeSpan: Float, yaw: Float, pitch: Float): Float? {
        if (!eyeSpan.isFinite() || eyeSpan <= 0f || !yaw.isFinite() || !pitch.isFinite()) return null
        // Beyond this range, foreshortening/occlusion makes monocular distance unreliable.
        if (abs(yaw) > 35f || abs(pitch) > 35f) return null
        return eyeSpan / cos(Math.toRadians(yaw.toDouble())).toFloat()
    }

    fun distanceRatio(baselineSpan: Float?, currentSpan: Float?): Float? {
        if (baselineSpan == null || currentSpan == null || !baselineSpan.isFinite() ||
            !currentSpan.isFinite() || baselineSpan <= 0f || currentSpan <= 0f) return null
        return baselineSpan / currentSpan
    }
}

internal data class HeadPoseReading(
    val pitch: Float,
    val yaw: Float,
    val eyeSpan: Float?,
    val atMillis: Long,
)

internal data class HeadPoseState(
    val message: String = "正在启动前置相机",
    val reading: HeadPoseReading? = null,
)

/** Use five stable observations as the session's automatic reference distance. */
internal class HeadPoseCalibration {
    private val samples = ArrayDeque<Float>()
    private var baseline: Float? = null
    private var lastSeen = 0L

    fun update(reading: HeadPoseReading?): Float? {
        if (reading == null) {
            samples.clear()
            baseline = null
            lastSeen = 0
            return null
        }
        if (lastSeen != 0L && reading.atMillis - lastSeen > 2_000) {
            baseline = null
            samples.clear()
        }
        lastSeen = reading.atMillis
        if (baseline != null) return baseline
        val span = reading.eyeSpan ?: run { samples.clear(); return null }
        samples.addLast(span)
        if (samples.size > 5) samples.removeFirst()
        if (samples.size == 5 && samples.max() / samples.min() < 1.08f) {
            baseline = samples.sorted()[2]
        }
        return baseline
    }
}
