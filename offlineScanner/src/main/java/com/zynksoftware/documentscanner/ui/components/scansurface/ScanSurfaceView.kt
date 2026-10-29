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


package com.zynksoftware.documentscanner.ui.components.scansurface

import android.content.Context
import android.os.CountDownTimer
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import android.os.SystemClock
import android.widget.Toast
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zynksoftware.documentscanner.R
import com.zynksoftware.documentscanner.common.utils.ImageDetectionProperties
import com.zynksoftware.documentscanner.common.utils.NeuralDocumentDetector
import com.zynksoftware.documentscanner.model.DocumentScannerErrorModel
import com.zynksoftware.documentscanner.model.DocumentScannerErrorModel.ErrorMessage
import kotlinx.android.synthetic.main.scan_surface_view.view.*
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class ScanSurfaceView : FrameLayout {

    companion object {
        private val TAG = ScanSurfaceView::class.simpleName

        private const val TIME_POST_PICTURE = 1500L
        private const val DEFAULT_TIME_POST_PICTURE = 1500L
        private const val IMAGE_ANALYSIS_SCALE_WIDTH = 800
        private val analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    lateinit var lifecycleOwner: LifecycleOwner
    lateinit var listener: ScanSurfaceListener
    lateinit var originalImageFile: File

    private var autoCaptureTimer: CountDownTimer? = null
    private var millisLeft = 0L
    private var isAutoCaptureScheduled = false
    @Volatile private var isCapturing = false
    private var lastValidFrameAt = 0L
    private var lastAnalyzedAt = 0L

    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var previewSize: android.util.Size

    var isAutoCaptureOn: Boolean = false
    private var isFlashEnabled: Boolean = false
    private var flashMode: Int = ImageCapture.FLASH_MODE_OFF

    init {
        LayoutInflater.from(context).inflate(R.layout.scan_surface_view, this, true)
    }

    fun start() {
        viewFinder.post {
            viewFinder.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            previewSize = android.util.Size(viewFinder.width, viewFinder.height)
            openCamera()
        }
    }

    private fun clearAndInvalidateCanvas() {
        scanCanvasView.clearShape()
    }

    private fun openCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            try {
                bindCamera()
                checkIfFlashIsPresent()
            } catch (exc: Exception) {
                Log.e(TAG, ErrorMessage.CAMERA_USE_CASE_BINDING_FAILED.error, exc)
                listener.onError(DocumentScannerErrorModel(ErrorMessage.CAMERA_USE_CASE_BINDING_FAILED, exc))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera() {
        cameraProvider?.unbindAll()
        camera = null
        setUseCases()
    }

    private fun setImageCapture() {
        if(imageCapture != null && cameraProvider?.isBound(imageCapture!!) == true) {
            cameraProvider?.unbind(imageCapture)
        }

        imageCapture = null
        imageCapture = ImageCapture.Builder()
            .setFlashMode(flashMode)
            .build()
    }

    fun unbindCamera() {
        cameraProvider?.unbind(imageAnalysis)
        clearLastFrame()
    }

    private fun clearLastFrame() {
        lastValidFrameAt = 0L
    }

    private fun setUseCases() {
        viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview = Preview.Builder()
            .setTargetResolution(previewSize)
            .build()
            .also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

        setImageCapture()

        val aspectRatio: Float = previewSize.width / previewSize.height.toFloat()
        val width = IMAGE_ANALYSIS_SCALE_WIDTH
        val height = (width / aspectRatio).roundToInt()

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(width, height))
            .build()

        imageAnalysis?.setAnalyzer(analysisExecutor) { image ->
            var pendingQuad: com.zynksoftware.documentscanner.ui.components.Quadrilateral? = null
            try {
                val now = SystemClock.elapsedRealtime()
                if (isCapturing || now - lastAnalyzedAt < 180L) return@setAnalyzer
                lastAnalyzedAt = now
                val frameSize = Size(image.width.toDouble(), image.height.toDouble())
                val quad = NeuralDocumentDetector.detect(context.applicationContext, image)
                pendingQuad = quad
                val source = ImageProxyTransformFactory().getOutputTransform(image)
                ContextCompat.getMainExecutor(context).execute {
                    try {
                        if (!isCapturing && isAttachedToWindow) {
                            if (quad == null || !drawLargestRect(quad.contour, quad.points, frameSize, source)) {
                                cancelAutoCapture()
                                // Tolerate a brief missed frame without flashing the border.
                                if (SystemClock.elapsedRealtime() - lastValidFrameAt > 350L) {
                                    clearLastFrame()
                                    clearAndInvalidateCanvas()
                                }
                            }
                        }
                    } finally {
                        quad?.contour?.release()
                    }
                }
                pendingQuad = null
            } catch (e: Exception) {
                Log.e(TAG, ErrorMessage.DETECT_LARGEST_QUADRILATERAL_FAILED.error, e)
                ContextCompat.getMainExecutor(context).execute {
                    if (!isCapturing) {
                        clearLastFrame()
                        cancelAutoCapture()
                        clearAndInvalidateCanvas()
                    }
                }
            } finally {
                pendingQuad?.contour?.release()
                image.close()
            }
        }

        val group = UseCaseGroup.Builder().addUseCase(preview!!).addUseCase(imageAnalysis!!).addUseCase(imageCapture!!)
        viewFinder.viewPort?.let { group.setViewPort(it) }
        camera = cameraProvider!!.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
    }

    private fun drawLargestRect(approx: MatOfPoint2f, points: Array<Point>, stdSize: Size, source: androidx.camera.view.transform.OutputTransform): Boolean {
        // Attention: axis are swapped
        val previewWidth = stdSize.height.toFloat()
        val previewHeight = stdSize.width.toFloat()

        val resultWidth = max(previewWidth - points[0].y.toFloat(), previewWidth - points[1].y.toFloat()) -
                min(previewWidth - points[2].y.toFloat(), previewWidth - points[3].y.toFloat())

        val resultHeight = max(points[1].x.toFloat(), points[2].x.toFloat()) - min(points[0].x.toFloat(), points[3].x.toFloat())

        val imgDetectionPropsObj = ImageDetectionProperties(previewWidth.toDouble(), previewHeight.toDouble(),
            points[0], points[1], points[2], points[3], resultWidth.toInt(), resultHeight.toInt())
        // Convert camera-buffer coordinates to the actual PreviewView, including
        // rotation and center crop. A desktop-like landscape tablet cannot use
        // the original hard-coded portrait rotation.
        val target = viewFinder.outputTransform
        val area = kotlin.math.abs(org.opencv.imgproc.Imgproc.contourArea(approx))
        val imageArea = stdSize.width * stdSize.height
        if (target == null || area < imageArea * 0.12 || area > imageArea * 0.97 || com.zynksoftware.documentscanner.common.utils.DocumentCornerOrder.order(points) == null) {
            clearAndInvalidateCanvas()
            cancelAutoCapture()
            return false
        }
        val coords = FloatArray(8) { i -> if (i % 2 == 0) points[i / 2].x.toFloat() else points[i / 2].y.toFloat() }
        CoordinateTransform(source, target).mapPoints(coords)
        if (coords.indices.any { !coords[it].isFinite() || coords[it] < -10f || coords[it] > (if (it % 2 == 0) viewFinder.width else viewFinder.height) + 10f }) {
            clearAndInvalidateCanvas()
            cancelAutoCapture()
            return false
        }
        scanCanvasView.showMappedShape(coords)
        lastValidFrameAt = SystemClock.elapsedRealtime()
        if (isAutoCaptureOn && !imgDetectionPropsObj.isNotValidImage(approx)) {
            if (!isAutoCaptureScheduled) scheduleAutoCapture()
        } else {
            cancelAutoCapture()
        }
        return true
    }

    private fun scheduleAutoCapture() {
        isAutoCaptureScheduled = true
        millisLeft = 0L
        autoCaptureTimer = object : CountDownTimer(DEFAULT_TIME_POST_PICTURE, 100) {
            override fun onTick(millisUntilFinished: Long) {
                if (millisUntilFinished != millisLeft) {
                    millisLeft = millisUntilFinished
                }
            }

            override fun onFinish() {
                isAutoCaptureScheduled = false
                autoCapture()
            }
        }
        autoCaptureTimer?.start()
    }

    private fun autoCapture() {
        if (isCapturing)
            return
        cancelAutoCapture()
        takePicture()
    }

    fun takePicture() {
        if (isCapturing) return
        // Detection only enables automatic cropping; manual capture must always work.
        cancelAutoCapture()
        Log.d(TAG, "ZDCtakePicture Starts ${System.currentTimeMillis()}")
        listener.scanSurfaceShowProgress()
        isCapturing = true

        val imageCapture = imageCapture ?: run { listener.scanSurfaceHideProgress(); isCapturing = false; return }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(originalImageFile).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    listener.scanSurfaceHideProgress()
                    isCapturing = false
                    Log.e(TAG, "${ErrorMessage.PHOTO_CAPTURE_FAILED.error}: ${exc.message}", exc)
                    listener.onError(DocumentScannerErrorModel(ErrorMessage.PHOTO_CAPTURE_FAILED, exc))
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        unbindCamera()
                        clearAndInvalidateCanvas()
                        listener.scanSurfacePictureTaken()
                    } catch (e: Exception) {
                        listener.scanSurfaceHideProgress()
                        isCapturing = false
                        Log.e(TAG, "Unable to process captured image", e)
                        Toast.makeText(context, "无法处理照片，请重试", Toast.LENGTH_LONG).show()
                    }
                    postDelayed({ isCapturing = false }, TIME_POST_PICTURE)
                    Log.d(TAG, "ZDCtakePicture ends ${System.currentTimeMillis()}")
                }
            })
    }

    private fun checkIfFlashIsPresent() {
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            listener.showFlash()
        } else {
            listener.hideFlash()
        }
    }

    private fun cancelAutoCapture() {
        if (isAutoCaptureScheduled) {
            isAutoCaptureScheduled = false
            autoCaptureTimer?.cancel()
        }
    }

    fun switchFlashState() {
        isFlashEnabled = !isFlashEnabled
        flashMode = if (isFlashEnabled) {
            listener.showFlashModeOn()
            ImageCapture.FLASH_MODE_ON
        } else {
            listener.showFlashModeOff()
            ImageCapture.FLASH_MODE_OFF
        }
        setImageCapture()
        camera = cameraProvider!!.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
    }
}
