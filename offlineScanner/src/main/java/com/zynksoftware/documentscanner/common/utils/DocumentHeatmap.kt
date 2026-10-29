package com.zynksoftware.documentscanner.common.utils

import org.opencv.core.Point

/** Decode each corner's largest confident region; never invent a missing corner. */
internal object DocumentHeatmap {
    fun decode(heatmaps: Array<Array<FloatArray>>, width: Int, height: Int, rotation: Int): Array<Point>? {
        if (heatmaps.size != 4) return null
        val points = heatmaps.map { heat ->
            val rows = heat.size
            val cols = heat.firstOrNull()?.size ?: return null
            if (rows == 0 || cols == 0 || heat.any { it.size != cols }) return null
            val visited = BooleanArray(rows * cols)
            val queue = IntArray(rows * cols)
            var bestCount = 0
            var bestX = 0.0
            var bestY = 0.0
            for (y in 0 until rows) for (x in 0 until cols) {
                val start = y * cols + x
                if (visited[start] || !heat[y][x].isFinite() || heat[y][x] < 0.3f) continue
                var head = 0
                var tail = 1
                queue[0] = start
                visited[start] = true
                var sumX = 0.0
                var sumY = 0.0
                while (head < tail) {
                    val index = queue[head++]
                    val px = index % cols
                    val py = index / cols
                    sumX += px + 0.5
                    sumY += py + 0.5
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = px + dx
                        val ny = py + dy
                        if (nx !in 0 until cols || ny !in 0 until rows) continue
                        val next = ny * cols + nx
                        if (!visited[next] && heat[ny][nx].isFinite() && heat[ny][nx] >= 0.3f) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
                if (tail > bestCount) {
                    bestCount = tail
                    bestX = sumX / tail / cols
                    bestY = sumY / tail / rows
                }
            }
            if (bestCount == 0) return null
            toBuffer(bestX, bestY, width, height, rotation)
        }.toTypedArray()
        val ordered = DocumentCornerOrder.order(points) ?: return null
        val area = kotlin.math.abs(ordered.indices.sumOf { i ->
            val a = ordered[i]
            val b = ordered[(i + 1) % 4]
            a.x * b.y - a.y * b.x
        }) / 2 / (width.toDouble() * height)
        return ordered.takeIf { area in 0.08..0.98 }
    }

    fun toBuffer(u: Double, v: Double, width: Int, height: Int, rotation: Int): Point = when (rotation) {
        90 -> Point(v * width, (1 - u) * height)
        180 -> Point((1 - u) * width, (1 - v) * height)
        270 -> Point((1 - v) * width, u * height)
        else -> Point(u * width, v * height)
    }
}
