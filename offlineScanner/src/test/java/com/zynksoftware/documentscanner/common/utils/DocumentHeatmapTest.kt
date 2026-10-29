package com.zynksoftware.documentscanner.common.utils

import org.junit.Assert.*
import org.junit.Test

class DocumentHeatmapTest {
    private fun page() = Array(4) { Array(16) { FloatArray(16) } }.also { heat ->
        listOf(3 to 3, 12 to 3, 12 to 12, 3 to 12).forEachIndexed { i, (x, y) ->
            for (dy in 0..1) for (dx in 0..1) heat[i][y + dy][x + dx] = 0.9f
        }
    }

    @Test fun detectsFourCornersAndIgnoresIsolatedNoise() {
        val heat = page()
        heat[0][0][15] = 1f
        val corners = requireNotNull(DocumentHeatmap.decode(heat, 800, 600, 0))
        assertEquals(200.0, corners[0].x, 0.01)
        assertEquals(150.0, corners[0].y, 0.01)
        assertEquals(650.0, corners[2].x, 0.01)
    }

    @Test fun missingOrWeakCornerDoesNotProduceAFrame() {
        val heat = page()
        heat[3].forEach { row -> row.fill(0.29f) }
        assertNull(DocumentHeatmap.decode(heat, 800, 600, 0))
    }

    @Test fun rejectsDegenerateCornersAndNonFinitePredictions() {
        val heat = page()
        heat[1] = heat[0]
        assertNull(DocumentHeatmap.decode(heat, 800, 600, 0))
        assertNull(DocumentHeatmap.decode(Array(4) { Array(16) { FloatArray(16) { Float.NaN } } }, 800, 600, 0))
    }

    @Test fun mapsUprightPredictionBackToEverySensorRotation() {
        val expected = listOf(200.0 to 450.0, 600.0 to 450.0, 600.0 to 150.0, 200.0 to 150.0)
        listOf(0, 90, 180, 270).zip(expected).forEach { (rotation, point) ->
            val actual = DocumentHeatmap.toBuffer(0.25, 0.75, 800, 600, rotation)
            assertEquals(point.first, actual.x, 0.01)
            assertEquals(point.second, actual.y, 0.01)
            assertNotNull(DocumentHeatmap.decode(page(), 800, 600, rotation))
        }
    }
    @Test fun rotatingTensorKeepsBgrChannelsAndRoundTrips() {
        val source = FloatArray(12) { it.toFloat() }
        assertArrayEquals(floatArrayOf(2f, 0f, 3f, 1f, 6f, 4f, 7f, 5f, 10f, 8f, 11f, 9f),
            DocumentTensorRotation.clockwise(source, 1, 2), 0f)
        assertArrayEquals(source, DocumentTensorRotation.clockwise(
            DocumentTensorRotation.clockwise(source, 1, 2), 3, 2), 0f)
    }

}
