package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class HeadPoseAlertsTest {
    private fun reading(at: Long, pitch: Float = -19f, yaw: Float = -8f) = HeadPoseReading(pitch, yaw, 100f, at)
    @Test fun defaultsMatchAgreedRangesAndRejectInvalidSettings() {
        val limits = HeadPoseLimits()
        assertTrue(limits.valid())
        assertEquals(-29f, limits.pitchMin, 0f); assertEquals(-9f, limits.pitchMax, 0f)
        assertEquals(-23f, limits.yawMin, 0f); assertEquals(7f, limits.yawMax, 0f)
        assertEquals(.85f, limits.distanceMin, 0f); assertEquals(1.2f, limits.distanceMax, 0f)
        assertFalse(limits.copy(pitchMin = 10f).valid())
        assertFalse(limits.copy(distanceMin = Float.NaN).valid())
        assertFalse(limits.copy(triggerSeconds = 0).valid())
        assertFalse(limits.copy(recoverySeconds = 31).valid())
    }
    @Test fun normalAndInclusiveBoundariesDoNotWarn() {
        val engine = HeadPoseAlertEngine(HeadPoseLimits())
        repeat(10) { i ->
            assertTrue(engine.update(reading(i * 500L, if (i % 2 == 0) -29f else -9f, if (i % 2 == 0) -23f else 7f), if (i % 2 == 0) .85f else 1.2f).isEmpty())
        }
    }
    @Test fun continuousThreeSecondsTriggersAndTwoNormalSecondsClears() {
        val engine = HeadPoseAlertEngine(HeadPoseLimits())
        repeat(6) { assertTrue(engine.update(reading(it * 500L, -40f), 1f).isEmpty()) }
        assertEquals(setOf(PoseWarning.PITCH_LOW), engine.update(reading(3000, -40f), 1f))
        repeat(4) { assertTrue(engine.update(reading(3500 + it * 500L), 1f).contains(PoseWarning.PITCH_LOW)) }
        assertTrue(engine.update(reading(5500), 1f).isEmpty())
    }
    @Test fun briefReturnToNormalResetsTriggerTimer() {
        val engine = HeadPoseAlertEngine(HeadPoseLimits())
        repeat(5) { engine.update(reading(it * 500L, -40f), 1f) }
        engine.update(reading(2500), 1f)
        repeat(6) { assertTrue(engine.update(reading(3000 + it * 500L, -40f), 1f).isEmpty()) }
        assertEquals(setOf(PoseWarning.PITCH_LOW), engine.update(reading(6000, -40f), 1f))
    }
    @Test fun staleDuplicateFramesCannotAccumulateTime() {
        val engine = HeadPoseAlertEngine(HeadPoseLimits())
        repeat(20) { assertTrue(engine.update(reading(1000, -40f), 1f).isEmpty()) }
        assertTrue(engine.update(reading(8000, -40f), 1f).isEmpty())
    }
    @Test fun missingFaceClearsWarningsAndRequiresNewFullDuration() {
        val engine = HeadPoseAlertEngine(HeadPoseLimits())
        repeat(7) { engine.update(reading(it * 500L, -40f), .7f) }
        assertTrue(engine.update(null, null).isEmpty())
        assertTrue(engine.update(reading(3500, -40f), .7f).isEmpty())
    }
    @Test fun unknownDistanceDoesNotDisableLowHeadWarning() {
        val engine = HeadPoseAlertEngine(HeadPoseLimits())
        repeat(7) { engine.update(reading(it * 500L, -40f), .7f) }
        assertEquals(setOf(PoseWarning.PITCH_LOW), engine.update(reading(3500, -40f), null))
    }
    @Test fun differentFaultsDoNotShareTriggerTime() {
        val engine = HeadPoseAlertEngine(HeadPoseLimits())
        repeat(12) { i ->
            val pitch = if (i % 2 == 0) 0f else -19f
            val yaw = if (i % 2 == 0) -8f else 30f
            assertTrue(engine.update(reading(i * 500L, pitch, yaw), 1f).isEmpty())
        }
    }
    @Test fun customDurationsAndLimitsApply() {
        val engine = HeadPoseAlertEngine(HeadPoseLimits(pitchMin = -10f, triggerSeconds = 1, recoverySeconds = 1))
        repeat(6) { assertTrue(engine.update(reading(it * 500L, 0f), 1f).isEmpty()) }
        engine.update(reading(3000, -20f), 1f)
        engine.update(reading(3500, -20f), 1f)
        assertTrue(engine.update(reading(4000, -20f), 1f).contains(PoseWarning.PITCH_LOW))
        engine.update(reading(4500, 0f), 1f)
        engine.update(reading(5000, 0f), 1f)
        assertTrue(engine.update(reading(5500, 0f), 1f).isEmpty())
    }
    @Test fun lookingUpTurningAndDistanceChangesNeverTriggerAttentionWarnings() {
        for (yaw in listOf(-60f, 60f)) {
            for (distance in listOf(.5f, 2f)) {
                val engine = HeadPoseAlertEngine(HeadPoseLimits())
                repeat(30) { assertTrue(engine.update(reading(it * 500L, 25f, yaw), distance).isEmpty()) }
            }
        }
    }

}
