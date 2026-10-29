package com.zynksoftware.documentscanner.common.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Replays local saved camera samples on the tablet, without opening its camera. */
@RunWith(AndroidJUnit4::class)
class DocumentModelReplayTest {
    @Test fun replaySavedA4AndNotebook() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val names = listOf("a4.jpg", "notebook.jpg")
        assumeTrue("Local photographic samples are optional and never committed", names.all { it in assets.list("").orEmpty() })
        System.loadLibrary("opencv_java4")
        val reports = JSONArray()
        for (name in names) {
            val bitmap = assets.open(name).use { BitmapFactory.decodeStream(it) }
            val started = SystemClock.elapsedRealtime()
            val quad = NeuralDocumentDetector.detect(context, bitmap)
            val elapsed = SystemClock.elapsedRealtime() - started
            assertNotNull("Missing document corners in $name", quad)
            val found = requireNotNull(quad)
            try {
                val corners = JSONArray(found.points.map { JSONArray(listOf(it.x, it.y)) })
                reports.put(JSONObject().put("image", name).put("milliseconds", elapsed).put("corners", corners))
                val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val path = Path().apply {
                    moveTo(found.points[0].x.toFloat(), found.points[0].y.toFloat())
                    found.points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
                    close()
                }
                Canvas(output).drawPath(path, Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 5f })
                File(context.cacheDir, "replay-$name").outputStream().use { output.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                output.recycle()
            } finally { found.contour.release(); bitmap.recycle() }
        }
        File(context.cacheDir, "document-replay.json").writeText(reports.toString(2))
    }

    @Test fun blankImageDoesNotInventAPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("opencv_java4")
        val image = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            val result = NeuralDocumentDetector.detect(context, image)
            try { assertNull(result) } finally { result?.contour?.release() }
        } finally { image.recycle() }
    }
}
