package com.homeworkbuddy

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Clockwise page corners, in normalized image coordinates. */
internal data class PagePoint(val x: Float, val y: Float)

internal object DocumentPageGeometry {
    val fullPage = listOf(PagePoint(0f, 0f), PagePoint(1f, 0f), PagePoint(1f, 1f), PagePoint(0f, 1f))

    fun isValid(points: List<PagePoint>): Boolean {
        if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        // Reject crossed edges, inverted winding and near-zero selections.
        return points.indices.all { i ->
            val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 0.002f
        }
    }

    fun outputSize(points: List<PagePoint>, width: Int, height: Int): Pair<Int, Int> {
        require(isValid(points))
        fun distance(a: Int, b: Int) = hypot((points[a].x - points[b].x) * width, (points[a].y - points[b].y) * height)
        return max(distance(0, 1), distance(3, 2)).roundToInt().coerceAtLeast(1) to
            max(distance(0, 3), distance(1, 2)).roundToInt().coerceAtLeast(1)
    }
}
