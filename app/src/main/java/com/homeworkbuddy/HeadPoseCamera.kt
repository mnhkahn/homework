package com.homeworkbuddy

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import kotlin.math.hypot

/** A foreground-only camera session. Frames stay in memory and are never saved or uploaded. */
internal class HeadPoseCamera(
    private val context: Context,
    private val displayRotation: Int,
    private val onState: (HeadPoseState) -> Unit,
) {
    private val thread = HandlerThread("head_pose").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = CompletableFuture<Unit>()
    fun completion(): CompletableFuture<Unit> = closed
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var detector: FaceDetector? = null
    private var opening = false
    private var stopping = false
    private var lastFrameAt = 0L
    private var filtered: HeadPoseReading? = null
    private val staleCheck = object : Runnable {
        override fun run() {
            if (stopping) return
            if (SystemClock.elapsedRealtime() - lastFrameAt > 2_000) {
                filtered = null
                onState(HeadPoseState("等待相机画面"))
            }
            handler.postDelayed(this, 1_000)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        handler.post {
            if (stopping) return@post
            try {
                detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setMinFaceSize(.15f)
                    .build())
                val manager = context.getSystemService(CameraManager::class.java)
                val id = manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                } ?: error("未找到前置相机")
                val characteristics = manager.getCameraCharacteristics(id)
                val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.YUV_420_888) ?: error("相机不支持人脸检测画面")
                val size = sizes.filter { it.width >= 640 && it.height >= 480 }
                    .minByOrNull { it.width * it.height } ?: sizes.minByOrNull { abs(it.width * it.height - 640 * 480) }
                    ?: error("相机没有可用分辨率")
                val degrees = when (displayRotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val rotation = ((characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) + degrees) % 360
                val output = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
                reader = output
                output.setOnImageAvailableListener({ source -> analyze(source, rotation, "$id:${size.width}:${size.height}:$rotation") }, handler)
                opening = true
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        opening = false
                        camera = device
                        if (stopping) { device.close(); return }
                        try {
                            device.createCaptureSession(listOf(output.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(value: CameraCaptureSession) {
                                    if (stopping) { value.close(); return }
                                    session = value
                                    try {
                                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(output.surface)
                                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        }.build()
                                        value.setRepeatingRequest(request, null, handler)
                                        handler.post(staleCheck)
                                    } catch (error: Exception) { fail(error) }
                                }
                                override fun onConfigureFailed(value: CameraCaptureSession) {
                                    value.close()
                                    fail(IllegalStateException("相机初始化失败"))
                                }
                            }, handler)
                        } catch (error: Exception) { fail(error) }
                    }
                    override fun onDisconnected(device: CameraDevice) {
                        opening = false
                        camera = device
                        fail(IllegalStateException("相机已被其他功能使用，请重试"))
                    }
                    override fun onError(device: CameraDevice, error: Int) {
                        opening = false
                        camera = device
                        fail(IllegalStateException("相机暂不可用（$error），请重试"))
                    }
                    override fun onClosed(device: CameraDevice) {
                        camera = null
                        finishClose()
                    }
                }, handler)
            } catch (error: Exception) {
                opening = false
                fail(error)
            }
        }
    }

    private fun analyze(source: ImageReader, rotation: Int, calibrationKey: String) {
        if (stopping) return
        val image = source.acquireLatestImage() ?: return
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 333) return
            lastFrameAt = now
            try {
                // Waiting on this dedicated camera thread also keeps the Image alive until inference completes.
                val faces = Tasks.await(checkNotNull(detector).process(InputImage.fromMediaImage(image, rotation)))
                if (faces.size != 1) {
                    filtered = null
                    onState(HeadPoseState(if (faces.isEmpty()) "未检测到人脸" else "检测到多人，请保持单人在画面内"))
                    return
                }
                val face = faces.single()
                val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                val span = if (left != null && right != null) {
                    HeadPoseMetrics.frontalEyeSpan(hypot(left.x - right.x, left.y - right.y), face.headEulerAngleY, face.headEulerAngleX)
                } else null
                val previous = filtered
                fun smooth(value: Float, old: Float?) = if (old == null) value else old * .6f + value * .4f
                val reading = HeadPoseReading(
                    smooth(face.headEulerAngleX, previous?.pitch),
                    smooth(face.headEulerAngleY, previous?.yaw),
                    span?.let { smooth(it, previous?.eyeSpan) },
                    SystemClock.elapsedRealtime(),
                    calibrationKey,
                )
                filtered = reading
                onState(HeadPoseState("本地检测中", reading))
            } catch (error: Exception) { fail(error) }
        }
    }

    fun stop(): CompletableFuture<Unit> {
        handler.post { closeOnWorker() }
        return closed
    }

    private fun fail(error: Exception) {
        Log.w("HeadPose", "Detection unavailable", error)
        onState(HeadPoseState(error.message ?: "检测失败，请关闭后重试"))
        closeOnWorker()
    }

    private fun closeOnWorker() {
        if (stopping) return
        stopping = true
        handler.removeCallbacks(staleCheck)
        runCatching { session?.close() }
        session = null
        if (camera != null) camera?.close()
        else if (!opening) finishClose()
    }

    private fun finishClose() {
        runCatching { reader?.close() }
        reader = null
        runCatching { detector?.close() }
        detector = null
        HeadPoseCameraAccess.detached(this)
        closed.complete(Unit)
        thread.quitSafely()
    }
}
