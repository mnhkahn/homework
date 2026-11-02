package com.zynksoftware.documentscanner.common.utils

import android.view.Surface

/** OrientationEventListener angles run opposite to Surface rotation constants. */
internal object PhotoCaptureRotation {
    fun fromDeviceOrientation(degrees: Int): Int? = when (degrees) {
        in 0..44, in 315..359 -> Surface.ROTATION_0
        in 45..134 -> Surface.ROTATION_270
        in 135..224 -> Surface.ROTATION_180
        in 225..314 -> Surface.ROTATION_90
        else -> null // Unknown while flat: keep the last known direction.
    }
}
