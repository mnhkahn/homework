package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class HeadPoseHistoryTest {
    private val limits = HeadPoseLimits()
    private fun reading(at: Long, pitch: Float) = HeadPoseReading(pitch, 0f, null, at)

    @Test fun countsEpisodesInsteadOfRepeatedWarningsAndIncludesPreTriggerMinimum() {
        val engine = HeadPoseAlertEngine(limits)
        val tracker = HeadPoseHistoryTracker(limits)
        var day = HeadPoseDayHistory()
        fun sample(at: Long, pitch: Float) {
            val reading = reading(at, pitch)
            val record = tracker.update(reading, engine.update(reading, null), 10_000 + at)
            day = day.observe(pitch, record)
        }
        sample(0, -55f)
        repeat(12) { sample((it + 1) * 500L, -40f) }
        assertEquals(1, day.reminders.size)
        assertEquals(-55f, day.reminders.single().minimumPitch, 0f)
        assertEquals(13_000L, day.reminders.single().remindedAtMillis)
        sample(6500, -60f)
        assertEquals(-60f, day.reminders.single().minimumPitch, 0f)
        // A short recovery is still the same episode.
        sample(7000, -20f)
        sample(7500, -40f)
        assertEquals(1, day.reminders.size)
        repeat(5) { sample(8000 + it * 500L, -20f) }
        repeat(7) { sample(10500 + it * 500L, -35f) }
        assertEquals(2, day.reminders.size)
        assertEquals(-35f, day.reminders.last().minimumPitch, 0f)
        assertEquals(-60f, day.minimumPitch!!, 0f)
    }

    @Test fun interruptionRequiresANewTriggerAndDoesNotCarryOldMinimum() {
        val tracker = HeadPoseHistoryTracker(limits)
        val warnings = setOf(PoseWarning.PITCH_LOW)
        val first = tracker.update(reading(0, -50f), warnings, 1000)!!
        tracker.update(null, emptySet(), 1500)
        assertNull(tracker.update(reading(500, -35f), emptySet(), 2000))
        val second = tracker.update(reading(1000, -35f), warnings, 2500)!!
        assertNotEquals(first.id, second.id)
        assertEquals(-35f, second.minimumPitch, 0f)
        val afterGap = tracker.update(reading(4000, -32f), warnings, 5500)!!
        assertNotEquals(second.id, afterGap.id)
        assertEquals(-32f, afterGap.minimumPitch, 0f)
    }

    @Test fun dailyMinimumExistsWithoutWarningsAndRejectsInvalidSamples() {
        val day = HeadPoseDayHistory().observe(-20f, null).observe(-25f, null)
        assertEquals(-25f, day.minimumPitch!!, 0f)
        assertTrue(day.reminders.isEmpty())
        assertEquals(day, day.observe(Float.NaN, null))
        assertEquals(day, day.observe(-100f, null))
    }

    @Test fun midnightEpisodeUpdateDoesNotChangePreviousDaysMeasuredMinimum() {
        val first = HeadPoseReminderRecord("one", 1000, -35f, -29f)
        val yesterday = HeadPoseDayHistory().observe(-35f, first)
        val updated = yesterday.observe(null, first.copy(minimumPitch = -50f))
        val today = HeadPoseDayHistory().observe(-50f, null)
        assertEquals(1, updated.reminders.size)
        assertEquals(-35f, updated.minimumPitch!!, 0f)
        assertEquals(-50f, updated.reminders.single().minimumPitch, 0f)
        assertTrue(today.reminders.isEmpty())
        assertEquals(-50f, today.minimumPitch!!, 0f)
    }

    @Test fun localSerializationRestoresCountsTimestampsAndAngles() {
        val day = HeadPoseDayHistory(-53.5f, listOf(
            HeadPoseReminderRecord("a", 123456, -53.5f, -29f),
            HeadPoseReminderRecord("b", 234567, -35.2f, -30f),
        ))
        assertEquals(day, decodeHeadPoseHistory(encodeHeadPoseHistory(day)))
        assertEquals(HeadPoseDayHistory(), decodeHeadPoseHistory(null))
        assertEquals(HeadPoseDayHistory(), decodeHeadPoseHistory("bad json"))
    }
}
