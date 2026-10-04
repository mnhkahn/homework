package com.homeworkbuddy

import java.util.UUID

internal data class HeadPoseReminderRecord(
    val id: String,
    val remindedAtMillis: Long,
    val minimumPitch: Float,
    val threshold: Float,
)

internal data class HeadPoseDayHistory(
    val minimumPitch: Float? = null,
    val reminders: List<HeadPoseReminderRecord> = emptyList(),
) {
    fun observe(pitch: Float?, reminder: HeadPoseReminderRecord?): HeadPoseDayHistory {
        if (pitch != null && (!pitch.isFinite() || pitch !in -90f..90f)) return this
        val updated = if (reminder == null) reminders else {
            val index = reminders.indexOfFirst { it.id == reminder.id }
            if (index < 0) reminders + reminder
            else reminders.toMutableList().apply { this[index] = reminder }
        }
        return copy(minimumPitch = if (pitch == null) minimumPitch else minOf(minimumPitch ?: pitch, pitch), reminders = updated)
    }
}

/** One record per warning episode, including the low samples before the warning triggers. */
internal class HeadPoseHistoryTracker(private val limits: HeadPoseLimits) {
    private var lastAt: Long? = null
    private var candidateMinimum: Float? = null
    private var active: HeadPoseReminderRecord? = null

    fun update(reading: HeadPoseReading?, warnings: Set<PoseWarning>, wallTimeMillis: Long): HeadPoseReminderRecord? {
        if (reading == null || !reading.pitch.isFinite() || reading.pitch !in -90f..90f) {
            reset()
            return null
        }
        if (lastAt?.let { reading.atMillis < it || reading.atMillis - it > 1_500 } == true) reset()
        lastAt = reading.atMillis
        val low = reading.pitch < limits.pitchMin
        if (low) candidateMinimum = minOf(candidateMinimum ?: reading.pitch, reading.pitch)
        if (PoseWarning.PITCH_LOW !in warnings) {
            active = null
            if (!low) candidateMinimum = null
            return null
        }
        val previous = active
        val record = if (previous == null) {
            HeadPoseReminderRecord(UUID.randomUUID().toString(), wallTimeMillis,
                minOf(candidateMinimum ?: reading.pitch, reading.pitch), limits.pitchMin)
        } else previous.copy(minimumPitch = minOf(previous.minimumPitch, reading.pitch))
        active = record
        return record
    }

    private fun reset() {
        lastAt = null
        candidateMinimum = null
        active = null
    }
}
