package com.zynksoftware.documentscanner.common.utils

internal object DocumentTensorRotation {
    /** Rotate a planar BGR tensor without interchanging channels. */
    fun clockwise(data: FloatArray, turns: Int, side: Int = 256): FloatArray {
        if (turns == 0) return data
        val plane = side * side
        return FloatArray(data.size) { index ->
            val channel = index / plane
            val x = index % side
            val y = (index % plane) / side
            val sx = when (turns) { 1 -> y; 2 -> side - 1 - x; else -> side - 1 - y }
            val sy = when (turns) { 1 -> side - 1 - x; 2 -> side - 1 - y; else -> x }
            data[channel * plane + sy * side + sx]
        }
    }
}
