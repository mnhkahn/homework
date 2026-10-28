package com.zynksoftware.documentscanner.common.utils

import android.graphics.Bitmap
import com.zynksoftware.documentscanner.common.extensions.toMat
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/** Maps the detected preview page onto the actual captured pixels using image features. */
internal object CapturedFrameAlignment {
    fun align(previewGray: Mat, previewCorners: Array<Point>, captured: Bitmap): FloatArray? {
        val rgba = captured.toMat()
        val capturedGray = Mat()
        val small = Mat()
        val previewKeys = MatOfKeyPoint()
        val captureKeys = MatOfKeyPoint()
        val previewDescriptors = Mat()
        val captureDescriptors = Mat()
        val emptyMask = Mat()
        val inlierMask = Mat()
        val matches = ArrayList<MatOfDMatch>()
        val orb = ORB.create(3500)
        try {
            Imgproc.cvtColor(rgba, capturedGray, Imgproc.COLOR_RGBA2GRAY)
            val scale = minOf(1.0, 1600.0 / max(capturedGray.cols(), capturedGray.rows()))
            Imgproc.resize(capturedGray, small, Size(capturedGray.cols() * scale, capturedGray.rows() * scale))
            orb.detectAndCompute(previewGray, emptyMask, previewKeys, previewDescriptors)
            orb.detectAndCompute(small, emptyMask, captureKeys, captureDescriptors)
            if (previewDescriptors.empty() || captureDescriptors.empty()) return null
            DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
                .knnMatch(previewDescriptors, captureDescriptors, matches, 2)
            val good = matches.mapNotNull { pair ->
                val candidates = pair.toArray()
                candidates.takeIf { it.size == 2 && it[0].distance < it[1].distance * 0.75f }?.get(0)
            }
            if (good.size < 25) return null
            val fromKeys = previewKeys.toArray()
            val toKeys = captureKeys.toArray()
            val from = MatOfPoint2f(*good.map { fromKeys[it.queryIdx].pt }.toTypedArray())
            val to = MatOfPoint2f(*good.map { toKeys[it.trainIdx].pt }.toTypedArray())
            try {
                val homography = Calib3d.findHomography(from, to, Calib3d.RANSAC, 5.0, inlierMask)
                try {
                    if (homography.empty() || Core.countNonZero(inlierMask) < max(18.0, good.size * 0.45).toInt()) return null
                    val source = MatOfPoint2f(*previewCorners)
                    val destination = MatOfPoint2f()
                    try {
                        Core.perspectiveTransform(source, destination, homography)
                        val corners = destination.toArray().map { Point(it.x / scale, it.y / scale) }.toTypedArray()
                        val ordered = DocumentCornerOrder.order(corners) ?: return null
                        val marginX = captured.width * 0.02
                        val marginY = captured.height * 0.02
                        if (ordered.any { it.x < -marginX || it.y < -marginY || it.x > captured.width + marginX || it.y > captured.height + marginY }) return null
                        val polygon = MatOfPoint2f(*ordered)
                        val area = try { kotlin.math.abs(Imgproc.contourArea(polygon)) / (captured.width.toDouble() * captured.height) }
                            finally { polygon.release() }
                        if (area !in 0.12..0.96) return null
                        return FloatArray(8) { i ->
                            if (i % 2 == 0) ordered[i / 2].x.toFloat().coerceIn(0f, captured.width.toFloat())
                            else ordered[i / 2].y.toFloat().coerceIn(0f, captured.height.toFloat())
                        }
                    } finally { source.release(); destination.release() }
                } finally { homography.release() }
            } finally { from.release(); to.release() }
        } finally {
            matches.forEach { it.release() }
            orb.clear()
            rgba.release(); capturedGray.release(); small.release()
            previewKeys.release(); captureKeys.release(); previewDescriptors.release(); captureDescriptors.release()
            emptyMask.release(); inlierMask.release()
        }
    }
}
