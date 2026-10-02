package com.homeworkbuddy

internal data class HeadPoseLimits(
    val pitchMin: Float = -29f, val pitchMax: Float = -9f,
    val yawMin: Float = -23f, val yawMax: Float = 7f,
    val distanceMin: Float = .85f, val distanceMax: Float = 1.20f,
    val triggerSeconds: Int = 3, val recoverySeconds: Int = 2,
    val sound: Boolean = true,
) {
    fun valid() = listOf(pitchMin, pitchMax, yawMin, yawMax, distanceMin, distanceMax).all { it.isFinite() } &&
        pitchMin >= -90 && pitchMax <= 90 && pitchMin < pitchMax &&
        yawMin >= -90 && yawMax <= 90 && yawMin < yawMax &&
        distanceMin >= .2f && distanceMax <= 5f && distanceMin < distanceMax &&
        triggerSeconds in 1..60 && recoverySeconds in 1..30
}

internal enum class PoseWarning(val message: String) {
    PITCH_HIGH("抬头了，请回到写字姿势"), PITCH_LOW("低头幅度较大，请调整姿势"),
    YAW_LOW("向一侧转头较多，请转回来"), YAW_HIGH("向一侧转头较多，请转回来"),
    TOO_CLOSE("离平板较近，请往后一点"), TOO_FAR("离平板较远，请回到原来的位置")
}

/** Each threshold has its own continuous timer; missing/stale observations break the sequence. */
internal class HeadPoseAlertEngine(private val limits: HeadPoseLimits) {
    private data class Timer(var outsideAt: Long? = null, var normalAt: Long? = null, var active: Boolean = false)
    private val timers = PoseWarning.entries.associateWith { Timer() }
    private var lastAt: Long? = null
    fun update(reading: HeadPoseReading?, distance: Float?): Set<PoseWarning> {
        if (reading == null) { reset(); return emptySet() }
        val at = reading.atMillis
        if (lastAt?.let { at < it || at - it > 1_500 } == true) reset()
        lastAt = at
        // Looking up/aside and changing distance do not establish inattention.
        // Keep those measurements visible, but only a low head triggers an alert.
        val conditions = mapOf(
            PoseWarning.PITCH_LOW to reading.pitch.takeIf { it.isFinite() }?.let { it < limits.pitchMin },
        )
        conditions.forEach { (reason, outside) ->
            val timer = timers.getValue(reason)
            when (outside) {
                null -> { timer.outsideAt = null; timer.normalAt = null; timer.active = false }
                true -> {
                    timer.normalAt = null
                    if (timer.outsideAt == null) timer.outsideAt = at
                    if (at - timer.outsideAt!! >= limits.triggerSeconds * 1_000L) timer.active = true
                }
                false -> {
                    timer.outsideAt = null
                    if (timer.normalAt == null) timer.normalAt = at
                    if (at - timer.normalAt!! >= limits.recoverySeconds * 1_000L) timer.active = false
                }
            }
        }
        return timers.filterValues { it.active }.keys.toSet()
    }
    private fun reset() {
        lastAt = null
        timers.values.forEach { it.outsideAt = null; it.normalAt = null; it.active = false }
    }
}
