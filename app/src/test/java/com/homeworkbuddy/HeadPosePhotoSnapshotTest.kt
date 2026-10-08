package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class HeadPosePhotoSnapshotTest {
    @Test fun snapshotSurvivesCameraPauseAndUsesMatchingCalibration() {
        val readings = HeadPosePhotoReadings()
        readings.update(HeadPoseReading(-19f, 8f, 80f, 1_000, "front"))
        val snapshot = readings.snapshot(1_500) { key ->
            assertEquals("front", key)
            100f
        }
        readings.update(null)
        assertEquals(listOf("拍照前坐姿", "仰角 -19°", "左右转角 +8°", "相对距离 1.25×"), snapshot.watermarkLines())
        assertNull(readings.snapshot(1_500) { 100f }.pitch)
    }

    @Test fun staleAndMissingReadingsDoNotAppearAsCurrentMeasurements() {
        val readings = HeadPosePhotoReadings()
        assertEquals(listOf("拍照前坐姿", "仰角 —", "左右转角 —", "相对距离 —"), readings.snapshot(1_000) { 100f }.watermarkLines())
        readings.update(HeadPoseReading(-19f, 8f, 80f, 1_000))
        assertNotNull(readings.snapshot(2_999) { 100f }.pitch)
        assertNull(readings.snapshot(3_000) { 100f }.pitch)
        assertNull(readings.snapshot(999) { 100f }.pitch)
    }

    @Test fun anglesRemainAvailableWithoutDistanceCalibrationOrEyeLandmarks() {
        val readings = HeadPosePhotoReadings()
        readings.update(HeadPoseReading(-19f, 8f, 80f, 1_000))
        assertEquals(listOf("拍照前坐姿", "仰角 -19°", "左右转角 +8°", "相对距离 —"), readings.snapshot(1_000) { null }.watermarkLines())
        readings.update(HeadPoseReading(-19f, 8f, null, 1_000))
        assertNull(readings.snapshot(1_000) { 100f }.distance)
    }
}
