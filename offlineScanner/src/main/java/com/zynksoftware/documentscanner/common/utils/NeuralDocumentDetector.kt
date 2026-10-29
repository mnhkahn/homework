package com.zynksoftware.documentscanner.common.utils

import android.content.Context
import androidx.camera.core.ImageProxy
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.zynksoftware.documentscanner.ui.components.Quadrilateral
import org.opencv.core.MatOfPoint2f
import java.nio.FloatBuffer

/** DocAligner FastViT-T8: bundled, offline four-corner prediction, BGR / 255 input. */
internal object NeuralDocumentDetector {
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null

    // Called exclusively on ScanSurfaceView's shared analysis executor.
    fun detect(context: Context, image: ImageProxy): Quadrilateral? {
        val model = session ?: OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            environment.createSession(context.assets.open("document-corners.onnx").use { it.readBytes() }, options)
                .also { session = it }
        }
        val planes = image.planes
        val buffers = planes.map { it.buffer.duplicate() }
        val origins = buffers.map { it.position() }
        fun sample(plane: Int, x: Int, y: Int): Int = buffers[plane].get(
            origins[plane] + y * planes[plane].rowStride + x * planes[plane].pixelStride).toInt() and 255
        val data = FloatArray(3 * 256 * 256)
        val rotation = image.imageInfo.rotationDegrees
        for (y in 0 until 256) for (x in 0 until 256) {
            val raw = DocumentHeatmap.toBuffer((x + 0.5) / 256, (y + 0.5) / 256, image.width, image.height, rotation)
            val px = raw.x.toInt().coerceIn(0, image.width - 1)
            val py = raw.y.toInt().coerceIn(0, image.height - 1)
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
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), longArrayOf(1, 3, 256, 256)).use { input ->
            model.run(mapOf("img" to input)).use { outputs ->
                @Suppress("UNCHECKED_CAST")
                val heatmaps = (outputs[0].value as Array<Array<Array<FloatArray>>>)[0]
                val points = DocumentHeatmap.decode(heatmaps, image.width, image.height, rotation) ?: return null
                return Quadrilateral(MatOfPoint2f(*points), points)
            }
        }
    }
}
