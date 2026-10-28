package com.zynksoftware.documentscanner.common.utils

import org.opencv.core.Point
import kotlin.math.atan2
import kotlin.math.hypot

/** Orders four distinct convex corners clockwise, starting at the upper-left corner. */
internal object DocumentCornerOrder {
    fun order(points: Array<Point>): Array<Point>? {
        if (points.size != 4) return null
        val centerX = points.sumOf { it.x } / 4.0
        val centerY = points.sumOf { it.y } / 4.0
        val clockwise = points.sortedBy { atan2(it.y - centerY, it.x - centerX) }
        val start = clockwise.indices.minByOrNull { clockwise[it].x + clockwise[it].y } ?: return null
        val ordered = Array(4) { clockwise[(start + it) % 4] }
        if ((0..3).any { i -> hypot(ordered[i].x - ordered[(i + 1) % 4].x, ordered[i].y - ordered[(i + 1) % 4].y) < 20.0 }) return null
        val crosses = (0..3).map { i ->
            val a = ordered[i]; val b = ordered[(i + 1) % 4]; val c = ordered[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        if (crosses.any { it <= 100.0 }) return null
        return ordered
    }
}
