/**
    Copyright 2020 ZynkSoftware SRL

    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
    associated documentation files (the "Software"), to deal in the Software without restriction,
    including without limitation the rights to use, copy, modify, merge, publish, distribute,
    sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or
    substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
    INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
    NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
    DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.zynksoftware.documentscanner.common.utils

import android.graphics.Bitmap
import android.graphics.PointF
import com.zynksoftware.documentscanner.common.extensions.scaleRectangle
import com.zynksoftware.documentscanner.common.extensions.toBitmap
import com.zynksoftware.documentscanner.common.extensions.toMat
import com.zynksoftware.documentscanner.ui.components.Quadrilateral
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.*


internal class OpenCvNativeBridge {

    companion object {
        private const val ANGLES_NUMBER = 4
        private const val EPSILON_CONSTANT = 0.02
        private const val CLOSE_KERNEL_SIZE = 10.0
        private const val CANNY_THRESHOLD_LOW = 75.0
        private const val CANNY_THRESHOLD_HIGH = 200.0
        private const val CUTOFF_THRESHOLD = 155.0
        private const val TRUNCATE_THRESHOLD = 150.0
        private const val NORMALIZATION_MIN_VALUE = 0.0
        private const val NORMALIZATION_MAX_VALUE = 255.0
        private const val BLURRING_KERNEL_SIZE = 5.0
        private const val DOWNSCALE_IMAGE_SIZE = 800.0
        private const val FIRST_MAX_CONTOURS = 10
    }

    fun getScannedBitmap(bitmap: Bitmap, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float): Bitmap {
        val rectangle = MatOfPoint2f()
        rectangle.fromArray(
            Point(x1.toDouble(), y1.toDouble()),
            Point(x2.toDouble(), y2.toDouble()),
            Point(x3.toDouble(), y3.toDouble()),
            Point(x4.toDouble(), y4.toDouble())
        )
        val source = bitmap.toMat()
        val blurred = Mat()
        var cropped: Mat? = null
        try {
            val result = PerspectiveTransformation.transform(source, rectangle)
            cropped = result
            // A small unsharp mask makes pencil and pen strokes clearer while
            // keeping the original color information for later OCR/review.
            Imgproc.GaussianBlur(result, blurred, Size(0.0, 0.0), 1.0)
            Core.addWeighted(result, 1.35, blurred, -0.35, 0.0, result)
            return result.toBitmap()
        } finally {
            source.release(); rectangle.release(); cropped?.release(); blurred.release()
        }
    }

    fun getContourEdgePoints(tempBitmap: Bitmap): List<PointF> {
        var point2f = getPoint(tempBitmap)
        if (point2f == null) point2f = MatOfPoint2f()
        val points: List<Point> = point2f.toArray().toList()
        val result: MutableList<PointF> = ArrayList()
        for (i in points.indices) {
            result.add(PointF(points[i].x.toFloat(), points[i].y.toFloat()))
        }

        return result
    }

    fun getPoint(bitmap: Bitmap): MatOfPoint2f? {
        val src = bitmap.toMat()

        val ratio = DOWNSCALE_IMAGE_SIZE / max(src.width(), src.height())
        val downscaledSize = Size(src.width() * ratio, src.height() * ratio)
        val downscaled = Mat(downscaledSize, src.type())
        Imgproc.resize(src, downscaled, downscaledSize)
        val gray = Mat()
        Imgproc.cvtColor(downscaled, gray, Imgproc.COLOR_RGBA2GRAY)
        val largestRectangle = findPaperRegion(gray)
        val result = largestRectangle?.contour?.scaleRectangle(1f / ratio)
        largestRectangle?.contour?.release()
        gray.release(); downscaled.release(); src.release()
        return result
    }

    // patch from Udayraj123 (https://github.com/Udayraj123/LiveEdgeDetection)
    fun detectLargestQuadrilateral(src: Mat): Quadrilateral? {
        return if (src.channels() == 1) findPaperRegion(src) else null
    }

    // Score quadrilateral edges by how much brighter the interior is than the
    // immediate exterior, together with size and distance from the centre.
    private fun findPaperRegion(src: Mat): Quadrilateral? {
        val gray = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        Imgproc.GaussianBlur(src, gray, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(gray, edges, 15.0, 37.5)
        val imageArea = src.cols().toDouble() * src.rows()
        val scale = max(src.cols(), src.rows()).toDouble() / 800.0
        val sampleDistance = max(3.0, 7.0 * scale)
        var best: Quadrilateral? = null
        var bestScore = 0.0
        try {
            for (kernelSize in intArrayOf(5, 9)) {
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kernelSize.toDouble(), kernelSize.toDouble()))
                val mask = Mat()
                val contours = ArrayList<MatOfPoint>()
                try {
                    Imgproc.morphologyEx(edges, mask, Imgproc.MORPH_CLOSE, kernel)
                    Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    contours.sortByDescending { Imgproc.contourArea(it) }
                    for (contour in contours.take(15)) {
                        val indices = MatOfInt()
                        Imgproc.convexHull(contour, indices)
                        val hull = hull2Points(indices, contour)
                        val raw = MatOfPoint2f(*hull.toArray())
                        indices.release(); hull.release()
                        try {
                            val perimeter = Imgproc.arcLength(raw, true)
                            if (perimeter <= 0.0) continue
                            for (epsilon in doubleArrayOf(0.01, 0.02, 0.03, 0.04)) {
                                val polygon = MatOfPoint2f()
                                Imgproc.approxPolyDP(raw, polygon, epsilon * perimeter, true)
                                val points = polygon.toArray()
                                val area = abs(Imgproc.contourArea(polygon)) / imageArea
                                if (points.size != 4 || area <= 0.25 || area >= 0.90) { polygon.release(); continue }
                                // A page needs four visible edges. Contours clipped by the
                                // camera buffer are usually a desk, keyboard, or partial page.
                                val border = max(3.0, minOf(src.cols(), src.rows()) * 0.005)
                                if (points.any { it.x <= border || it.y <= border || it.x >= src.cols() - border || it.y >= src.rows() - border }) {
                                    polygon.release(); continue
                                }
                                val centerClearance = Imgproc.pointPolygonTest(
                                    polygon, Point(src.cols() / 2.0, src.rows() / 2.0), true)
                                if (centerClearance < minOf(src.cols(), src.rows()) * 0.10) {
                                    polygon.release(); continue
                                }
                                val cx = points.sumOf { it.x } / 4.0
                                val cy = points.sumOf { it.y } / 4.0
                                val offset = ((cx - src.cols() / 2.0) / src.cols()).pow(2) + ((cy - src.rows() / 2.0) / src.rows()).pow(2)
                                if (offset > 0.07) { polygon.release(); continue }
                                var contrast = 0.0
                                var count = 0
                                for (i in 0..3) {
                                    val a = points[i]; val b = points[(i + 1) % 4]
                                    val dx = b.x - a.x; val dy = b.y - a.y
                                    val length = hypot(dx, dy)
                                    if (length < 20.0 * scale) continue
                                    var nx = dy / length; var ny = -dx / length
                                    val mx = (a.x + b.x) / 2; val my = (a.y + b.y) / 2
                                    if (nx * (cx - mx) + ny * (cy - my) < 0) { nx = -nx; ny = -ny }
                                    for (j in 0..6) {
                                        val t = 0.2 + j * 0.1
                                        val x = a.x * (1 - t) + b.x * t
                                        val y = a.y * (1 - t) + b.y * t
                                        val ix = (x + nx * sampleDistance).toInt(); val iy = (y + ny * sampleDistance).toInt()
                                        val ox = (x - nx * sampleDistance).toInt(); val oy = (y - ny * sampleDistance).toInt()
                                        if (ix in 0 until src.cols() && ox in 0 until src.cols() && iy in 0 until src.rows() && oy in 0 until src.rows()) {
                                            contrast += gray.get(iy, ix)[0] - gray.get(oy, ox)[0]; count++
                                        }
                                    }
                                }
                                val score = if (count > 0) contrast / count + area * 15.0 - offset * 50.0 else -1.0
                                val ordered = DocumentCornerOrder.order(points)
                                // Weak edge contrast often selects a large background patch.
                                // The previous camera sample scored 5 for that false page,
                                // while the two centered-paper samples scored 28 and 82.
                                if (score >= 12.0 && score > bestScore && ordered != null) {
                                    best?.contour?.release()
                                    best = Quadrilateral(polygon, ordered)
                                    bestScore = score
                                } else polygon.release()
                            }
                        } finally { raw.release() }
                    }
                } finally { contours.forEach { it.release() }; mask.release(); kernel.release() }
            }
            return best
        } finally { gray.release(); edges.release(); hierarchy.release() }
    }

    private fun findQuadrilateral(mContourList: List<MatOfPoint>): Quadrilateral? {
        for (c in mContourList) {
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, EPSILON_CONSTANT * peri, true)
            val points = approx.toArray()
            // select biggest 4 angles polygon
            if (approx.rows() == ANGLES_NUMBER) {
                val foundPoints: Array<Point> = sortPoints(points)
                return Quadrilateral(approx, foundPoints)
            }
        }
        return null
    }

    private fun distance(p1: Point, p2: Point): Double {
        return sqrt((p1.x - p2.x).pow(2.0) + (p1.y - p2.y).pow(2.0))
    }

    private fun sortPoints(src: Array<Point>): Array<Point> {
        val srcPoints: ArrayList<Point> = ArrayList(src.toList())
        val result = arrayOf<Point?>(null, null, null, null)
        val sumComparator: Comparator<Point> = Comparator<Point> { lhs, rhs -> (lhs.y + lhs.x).compareTo(rhs.y + rhs.x) }
        val diffComparator: Comparator<Point> = Comparator<Point> { lhs, rhs -> (lhs.y - lhs.x).compareTo(rhs.y - rhs.x) }

        // top-left corner = minimal sum
        result[0] = Collections.min(srcPoints, sumComparator)
        // bottom-right corner = maximal sum
        result[2] = Collections.max(srcPoints, sumComparator)
        // top-right corner = minimal difference
        result[1] = Collections.min(srcPoints, diffComparator)
        // bottom-left corner = maximal difference
        result[3] = Collections.max(srcPoints, diffComparator)
        return result.map {
            it!!
        }.toTypedArray()
    }

    private fun findLargestContours(inputMat: Mat): List<MatOfPoint>? {
        val mHierarchy = Mat()
        val mContourList: List<MatOfPoint> = ArrayList()
        //finding contours - as we are sorting by area anyway, we can use RETR_LIST - faster than RETR_EXTERNAL.
        Imgproc.findContours(inputMat, mContourList, mHierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        // Convert the contours to their Convex Hulls i.e. removes minor nuances in the contour
        val mHullList: MutableList<MatOfPoint> = ArrayList()
        val tempHullIndices = MatOfInt()
        for (i in mContourList.indices) {
            Imgproc.convexHull(mContourList[i], tempHullIndices)
            mHullList.add(hull2Points(tempHullIndices, mContourList[i]))
        }
        // Release mContourList as its job is done
        for (c in mContourList) {
            c.release()
        }
        tempHullIndices.release()
        mHierarchy.release()
        if (mHullList.size != 0) {
            mHullList.sortWith { lhs, rhs ->
                Imgproc.contourArea(rhs).compareTo(Imgproc.contourArea(lhs))
            }
            return mHullList.subList(0, min(mHullList.size, FIRST_MAX_CONTOURS))
        }
        return null
    }

    private fun hull2Points(hull: MatOfInt, contour: MatOfPoint): MatOfPoint {
        val indexes = hull.toList()
        val points: MutableList<Point> = ArrayList()
        val ctrList = contour.toList()
        for (index in indexes) {
            points.add(ctrList[index])
        }
        val point = MatOfPoint()
        point.fromList(points)
        return point
    }

    fun contourArea(approx: MatOfPoint2f): Double {
        return Imgproc.contourArea(approx)
    }


}
