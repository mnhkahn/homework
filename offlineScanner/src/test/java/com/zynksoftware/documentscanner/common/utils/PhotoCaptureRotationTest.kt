package com.zynksoftware.documentscanner.common.utils

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoCaptureRotationTest {
    @Test fun physicalQuarterTurnsWorkEvenWhenDisplayRotationIsLocked() {
        assertEquals(Surface.ROTATION_0, PhotoCaptureRotation.fromDeviceOrientation(0))
        assertEquals(Surface.ROTATION_270, PhotoCaptureRotation.fromDeviceOrientation(90))
        assertEquals(Surface.ROTATION_180, PhotoCaptureRotation.fromDeviceOrientation(180))
        assertEquals(Surface.ROTATION_90, PhotoCaptureRotation.fromDeviceOrientation(270))
    }

    @Test fun usesNearestQuarterTurnAcrossTheZeroBoundary() {
        assertEquals(Surface.ROTATION_0, PhotoCaptureRotation.fromDeviceOrientation(359))
        assertEquals(Surface.ROTATION_0, PhotoCaptureRotation.fromDeviceOrientation(44))
        assertEquals(Surface.ROTATION_270, PhotoCaptureRotation.fromDeviceOrientation(45))
        assertEquals(Surface.ROTATION_270, PhotoCaptureRotation.fromDeviceOrientation(134))
        assertEquals(Surface.ROTATION_180, PhotoCaptureRotation.fromDeviceOrientation(135))
        assertEquals(Surface.ROTATION_180, PhotoCaptureRotation.fromDeviceOrientation(224))
        assertEquals(Surface.ROTATION_90, PhotoCaptureRotation.fromDeviceOrientation(225))
        assertEquals(Surface.ROTATION_90, PhotoCaptureRotation.fromDeviceOrientation(314))
        assertEquals(Surface.ROTATION_0, PhotoCaptureRotation.fromDeviceOrientation(315))
    }

    @Test fun unknownOrientationDoesNotReplaceTheLastKnownDirection() {
        assertNull(PhotoCaptureRotation.fromDeviceOrientation(OrientationUnknown))
    }

    private companion object { const val OrientationUnknown = -1 }
}
