package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class HeadPoseMetricsTest {
    @Test fun distanceIncreasesWhenApparentEyesShrink() {
        assertEquals(1.25f, HeadPoseMetrics.distanceRatio(100f, 80f)!!, .001f)
        assertEquals(.8f, HeadPoseMetrics.distanceRatio(100f, 125f)!!, .001f)
    }

    @Test fun turningAloneDoesNotLookLikeMovingAway() {
        val projected = 100f * cos(Math.toRadians(30.0)).toFloat()
        val corrected = HeadPoseMetrics.frontalEyeSpan(projected, 30f, 0f)
        assertEquals(1f, HeadPoseMetrics.distanceRatio(100f, corrected)!!, .001f)
    }

    @Test fun unreliableAnglesAndMissingMeasurementsHaveNoDistance() {
        assertNull(HeadPoseMetrics.frontalEyeSpan(100f, 60f, 0f))
        assertNull(HeadPoseMetrics.frontalEyeSpan(100f, 0f, -50f))
        assertNull(HeadPoseMetrics.frontalEyeSpan(Float.NaN, 0f, 0f))
        assertNull(HeadPoseMetrics.distanceRatio(null, 100f))
        assertNull(HeadPoseMetrics.distanceRatio(100f, 0f))
    }

    @Test fun calibrationNeedsStableFramesAndResetsAfterFaceLoss() {
        val calibration = HeadPoseCalibration()
        repeat(4) { assertNull(calibration.update(HeadPoseReading(0f, 0f, 100f, 1000L + it * 333))) }
        assertEquals(100f, calibration.update(HeadPoseReading(0f, 0f, 100f, 2400))!!, 0f)
        assertEquals(100f, calibration.update(HeadPoseReading(0f, 0f, 80f, 2700))!!, 0f)
        assertNull(calibration.update(null))
        assertNull(calibration.update(HeadPoseReading(0f, 0f, 80f, 3000)))
    }

    @Test fun movementAndLongFrameGapsDoNotCalibrate() {
        val calibration = HeadPoseCalibration()
        repeat(8) { assertNull(calibration.update(HeadPoseReading(0f, 0f, if (it % 2 == 0) 80f else 120f, 1000L + it * 333))) }
        repeat(5) { calibration.update(HeadPoseReading(0f, 0f, 100f, 4000L + it * 333)) }
        assertNull(calibration.update(HeadPoseReading(0f, 0f, 100f, 9000)))
    }
}
