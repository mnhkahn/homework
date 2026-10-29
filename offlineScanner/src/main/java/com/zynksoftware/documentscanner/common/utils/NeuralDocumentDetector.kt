package com.zynksoftware.documentscanner.common.utils

import android.content.Context
import androidx.camera.core.ImageProxy
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.zynksoftware.documentscanner.ui.components.Quadrilateral
import org.opencv.core.MatOfPoint2f
import java.nio.FloatBuffer

/** DocAligner FastViT-SA24 (FP32 compute, FP16 weight storage): bundled, offline four-corner prediction, BGR / 255 input. */
internal object NeuralDocumentDetector {
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private val preferredTurns = java.util.concurrent.atomic.AtomicInteger(0)

    // Called exclusively on ScanSurfaceView's shared analysis executor.
    fun detect(context: Context, image: ImageProxy): Quadrilateral? {
        val planes = image.planes
        val buffers = planes.map { it.buffer.duplicate() }
        val origins = buffers.map { it.position() }
        fun sample(plane: Int, x: Int, y: Int): Int = buffers[plane].get(
            origins[plane] + y * planes[plane].rowStride + x * planes[plane].pixelStride).toInt() and 255
        val data = FloatArray(3 * 256 * 256)
        val rotation = image.imageInfo.rotationDegrees
        for (y in 0 until 256) for (x in 0 until 256) {
            val u = (x + 0.5) / 256
            val v = (y + 0.5) / 256
            val rawX = when (rotation) { 90 -> v; 180 -> 1 - u; 270 -> 1 - v; else -> u }
            val rawY = when (rotation) { 90 -> 1 - u; 180 -> 1 - v; 270 -> u; else -> v }
            val px = (rawX * image.width).toInt().coerceIn(0, image.width - 1)
            val py = (rawY * image.height).toInt().coerceIn(0, image.height - 1)
            val luminance = sample(0, px, py)
            val index = y * 256 + x
            if (planes.size >= 3) {
                val c = (luminance - 16).coerceAtLeast(0)
                val d = sample(1, px / 2, py / 2) - 128
                val e = sample(2, px / 2, py / 2) - 128
                data[index] = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255) / 255f
                data[65536 + index] = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255) / 255f
                data[131072 + index] = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255) / 255f
            } else {
                for (channel in 0..2) data[channel * 65536 + index] = luminance / 255f
            }
        }
        return predict(context, data, image.width, image.height, rotation)
    }

    /** Detect again on the actual photo, avoiding preview-to-JPEG feature matching. */
    fun detect(context: Context, bitmap: android.graphics.Bitmap): Quadrilateral? {
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        val pixels = IntArray(65536)
        try { small.getPixels(pixels, 0, 256, 0, 0, 256, 256) }
        finally { if (small !== bitmap) small.recycle() }
        val data = FloatArray(196608)
        for (i in pixels.indices) {
            data[i] = (pixels[i] and 255) / 255f
            data[65536 + i] = (pixels[i] shr 8 and 255) / 255f
            data[131072 + i] = (pixels[i] shr 16 and 255) / 255f
        }
        return predict(context, data, bitmap.width, bitmap.height, 0, tryAllRotations = true)
    }

    @Synchronized private fun model(context: Context): OrtSession {
        return session ?: OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            environment.createSession(context.assets.open("document-corners.onnx").use { it.readBytes() }, options)
                .also { session = it }
        }
    }

    private fun predict(context: Context, data: FloatArray, width: Int, height: Int, rotation: Int,
                        tryAllRotations: Boolean = false): Quadrilateral? {
        val model = model(context)
        val preferred = preferredTurns.get()
        val attempts = if (tryAllRotations) listOf(preferred, 0, 1, 2, 3).distinct() else listOf(preferred)
        for (turns in attempts) {
            val rotated = DocumentTensorRotation.clockwise(data, turns)
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(rotated), longArrayOf(1, 3, 256, 256)).use { input ->
                model.run(mapOf("img" to input)).use { outputs ->
                    @Suppress("UNCHECKED_CAST")
                    val heatmaps = (outputs[0].value as Array<Array<Array<FloatArray>>>)[0]
                    val points = DocumentHeatmap.decode(heatmaps, width, height, (rotation + turns * 90) % 360)
                    if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                        val peaks = heatmaps.map { heat -> heat.maxOf { row -> row.maxOrNull() ?: 0f } }
                        android.util.Log.d("DocumentModel", "rotation=$rotation turns=$turns size=${width}x$height peaks=$peaks found=${points != null}")
                    }
                    if (points != null) {
                        preferredTurns.set(turns)
                        return Quadrilateral(MatOfPoint2f(*points), points)
                    }
                }
            }
        }
        // Preview tries one direction per frame and remembers the successful one.
        if (!tryAllRotations) preferredTurns.compareAndSet(preferred, (preferred + 1) % 4)
        return null
    }
}
