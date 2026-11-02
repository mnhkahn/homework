package com.zynksoftware.documentscanner.common.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.zelory.compressor.Compressor
import id.zelory.compressor.constraint.format
import id.zelory.compressor.constraint.quality
import id.zelory.compressor.determineImageRotation
import id.zelory.compressor.saveBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The uploaded JPEG must stay upright even in a viewer that ignores EXIF. */
@RunWith(AndroidJUnit4::class)
class PhotoOrientationTest {
    @Test fun savedScannerPhotosBakeInEveryCameraRotation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "orientation-${System.nanoTime()}").apply { mkdirs() }
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val rotations = listOf(
            ExifInterface.ORIENTATION_NORMAL to listOf(0, 1, 2, 3),
            ExifInterface.ORIENTATION_ROTATE_90 to listOf(3, 0, 1, 2),
            ExifInterface.ORIENTATION_ROTATE_180 to listOf(2, 3, 0, 1),
            ExifInterface.ORIENTATION_ROTATE_270 to listOf(1, 2, 3, 0),
        )
        try {
            for ((orientation, expected) in rotations) {
                val source = File(directory, "camera-$orientation.jpg")
                val sensor = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(sensor)
                val paint = Paint()
                listOf(0f to 0f, 60f to 0f, 60f to 40f, 0f to 40f).forEachIndexed { i, (x, y) ->
                    paint.color = colors[i]
                    canvas.drawRect(x, y, x + 60f, y + 40f, paint)
                }
                saveBitmap(sensor, source, Bitmap.CompressFormat.JPEG, 100)
                sensor.recycle()
                ExifInterface(source).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val decoded = requireNotNull(BitmapFactory.decodeFile(source.absolutePath))
                val upright = determineImageRotation(source, decoded)
                val saved = File(directory, "cropped-$orientation.jpg")
                saveBitmap(upright, saved, Bitmap.CompressFormat.JPEG, 100)
                if (upright !== decoded) upright.recycle()
                decoded.recycle()
                // Same final compression as InternalScanActivity, then decode raw
                // pixels like an attachment viewer that does not honor EXIF.
                val compressed = Compressor.compress(context, saved) {
                    quality(100)
                    format(Bitmap.CompressFormat.JPEG)
                }
                try {
                    val result = requireNotNull(BitmapFactory.decodeFile(compressed.absolutePath))
                    try {
                        val portrait = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                            orientation == ExifInterface.ORIENTATION_ROTATE_270
                        assertEquals(if (portrait) 80 else 120, result.width)
                        assertEquals(if (portrait) 120 else 80, result.height)
                        val samples = listOf(1 to 1, 3 to 1, 3 to 3, 1 to 3)
                        samples.forEachIndexed { i, (x, y) ->
                            val actual = result.getPixel(result.width * x / 4, result.height * y / 4)
                            val wanted = colors[expected[i]]
                            assertTrue("EXIF $orientation corner $i", listOf(
                                Color.red(actual) - Color.red(wanted),
                                Color.green(actual) - Color.green(wanted),
                                Color.blue(actual) - Color.blue(wanted),
                            ).all { kotlin.math.abs(it) < 25 })
                        }
                        assertTrue(ExifInterface(compressed).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                        ) in listOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED))
                    } finally { result.recycle() }
                } finally { compressed.delete() }
            }
        } finally { directory.deleteRecursively() }
    }
}
