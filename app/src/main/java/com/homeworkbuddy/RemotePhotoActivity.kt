package com.homeworkbuddy

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Captures from the already-visible homework app; no camera activity is opened. */
object RemotePhotoCoordinator {
    private var pending: CompletableDeferred<JSONObject>? = null
    private var capture: InAppPhotoCapture? = null

    /** While active, the study-mode watchdog must not bring MainActivity to front. */
    val isCaptureInProgress: Boolean
        get() = synchronized(this) { pending?.isActive == true }

    suspend fun take(context: Context, resolution: String = "vga"): JSONObject {
        val pose = HeadPoseCameraAccess.photoReadings.snapshot(
            android.os.SystemClock.elapsedRealtime(), HeadPoseSettings(context)::baseline,
        )
        return HeadPoseCameraAccess.withRemoteCamera { takeExclusive(context, resolution, pose) }
    }

    private suspend fun takeExclusive(context: Context, resolution: String, pose: HeadPosePhotoSnapshot): JSONObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw McpException(-32001, "相机权限被拒绝")
        }
        val appContext = context.applicationContext
        val power = appContext.getSystemService(PowerManager::class.java)
        val cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homework:remote_photo")
        cpuLock.acquire(30_000L)
        // A locked tablet dozes the CPU and, on several OEM builds, the camera
        // pipeline stops producing frames while the display is off. Wake the
        // screen for the capture and lock it back afterwards; the device is
        // kiosk-managed, so relocking is a single DevicePolicyManager call.
        val wokeScreen = !power.isInteractive
        var screenLock: PowerManager.WakeLock? = null
        if (wokeScreen) {
            @Suppress("DEPRECATION")
            screenLock = power.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "homework:remote_photo_screen")
            screenLock.acquire(30_000L)
        }
        XiaoliConnectionService.beginCameraCapture(appContext)
        try {
            val result = CompletableDeferred<JSONObject>()
            synchronized(this) {
                check(pending == null) { "已有拍照请求正在进行" }
                pending = result
            }
            CaptureStatusStore(appContext).begin(CaptureKind.PHOTO)
            runCatching {
                InAppPhotoCapture(appContext, resolution, pose, ::complete, ::fail).also {
                    synchronized(this) { capture = it }
                    it.start()
                }
            }.onFailure { fail(it.message ?: "无法打开相机") }
            try {
                return withTimeout(20_000) { result.await() }
            } finally {
                synchronized(this) {
                    if (pending === result) {
                        pending = null
                        capture?.cancel()
                        capture = null
                    }
                }
            }
        } finally {
            XiaoliConnectionService.endCameraCapture(appContext)
            screenLock?.let { runCatching { if (it.isHeld) it.release() } }
            runCatching { if (cpuLock.isHeld) cpuLock.release() }
            if (wokeScreen) {
                // Relock immediately after the capture would cut off the
                // shutter click, so give the sound a moment to play.
                Handler(Looper.getMainLooper()).postDelayed({ relockScreen(appContext) }, 1_500)
            }
        }
    }

    private fun relockScreen(context: Context) {
        runCatching {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return@runCatching
            if (dpm.isDeviceOwnerApp(context.packageName)) dpm.lockNow()
        }
    }

    private fun complete(result: JSONObject) {
        synchronized(this) {
            if (pending?.complete(result) == true) {
                capture = null
            } else return
        }
    }

    private fun fail(message: String) {
        synchronized(this) {
            if (pending?.completeExceptionally(IllegalStateException(message)) == true) {
                capture = null
            } else return
        }
    }
}

private class InAppPhotoCapture(
    private val context: Context,
    private val resolution: String,
    private val pose: HeadPosePhotoSnapshot,
    private val onSuccess: (JSONObject) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var finished = false

    fun start() {
        Log.i("RemotePhoto", "in-app capture requested")
        val thread = HandlerThread("remote_photo").also { it.start() }
        worker = thread
        handler = Handler(thread.looper)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        runCatching {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull() ?: error("未找到可用相机")
            val sizes = manager.getCameraCharacteristics(cameraId)
                .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG) ?: error("相机不支持 JPEG 拍照")
            val target = when (resolution) {
                "qqvga" -> Size(160, 120)
                "qvga" -> Size(320, 240)
                "svga" -> Size(800, 600)
                else -> Size(640, 480)
            }
            // Pick a hardware size close to the requested resolution. The JPEG is
            // normalized again below because camera sensors are often landscape.
            val size = sizes.minByOrNull { kotlin.math.abs(it.width * it.height - target.width * target.height) }
                ?: error("未找到可用拍照尺寸")
            openCamera(manager, cameraId, size)
        }.onFailure { finishError(it.message ?: "无法打开相机") }
    }

    @Suppress("MissingPermission")
    private fun openCamera(manager: CameraManager, cameraId: String, size: Size) {
        val output = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        reader = output
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        previewTexture = texture
        val preview = Surface(texture)
        previewSurface = preview
        output.setOnImageAvailableListener({ source ->
            runCatching {
                val image = source.acquireLatestImage() ?: error("无法获取照片")
                val bytes = image.planes[0].buffer.let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
                image.close()
                Log.i("RemotePhoto", "received JPEG bytes=${bytes.size}")
                val encoded = encodePhoto(bytes)
                finishSuccess(JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("image_base64", encoded.base64)
                    .put("width", encoded.width)
                    .put("height", encoded.height)
                    .put("image_size", encoded.size))
            }.onFailure { finishError(it.message ?: "照片编码失败") }
        }, handler)
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                device.createCaptureSession(listOf(preview, output.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        runCatching {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(preview)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }.build()
                            captureSession.setRepeatingRequest(request, null, handler)
                            handler?.postDelayed({ captureStill(device, captureSession, output.surface) }, 900)
                        }.onFailure { finishError(it.message ?: "拍照失败") }
                    }
                    override fun onConfigureFailed(captureSession: CameraCaptureSession) = finishError("相机初始化失败")
                }, handler)
            }
            override fun onDisconnected(device: CameraDevice) = finishError("相机已断开")
            override fun onError(device: CameraDevice, error: Int) = finishError("无法打开相机")
        }, handler)
    }

    private fun captureStill(device: CameraDevice, captureSession: CameraCaptureSession, output: Surface) {
        if (finished) return
        runCatching {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(output)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()
            captureSession.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) = finishError("拍照失败")
            }, handler)
        }.onFailure { finishError(it.message ?: "拍照失败") }
    }

    fun cancel() = closeResources()

    private fun finishSuccess(result: JSONObject) {
        if (closeResources()) {
            CaptureStatusStore(context).complete(CaptureKind.PHOTO)
            // The sound confirms a completed capture, rather than merely an
            // attempt to open the camera (which can fail while the device sleeps).
            CameraShutterSound.play()
            onSuccess(result)
        }
    }

    private fun finishError(message: String) {
        if (closeResources()) {
            CaptureStatusStore(context).clear()
            onFailure(message)
        }
    }

    private fun closeResources(): Boolean {
        synchronized(this) {
            if (finished) return false
            finished = true
        }
        session?.close(); session = null
        camera?.close(); camera = null
        reader?.close(); reader = null
        previewSurface?.release(); previewSurface = null
        previewTexture?.release(); previewTexture = null
        worker?.quitSafely(); worker = null
        handler = null
        return true
    }

    private data class EncodedPhoto(val base64: String, val width: Int, val height: Int, val size: Int)

    private fun encodePhoto(bytes: ByteArray): EncodedPhoto {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("无法读取照片")
        val maxDimension = when (resolution) {
            "qqvga" -> 160
            "qvga" -> 320
            "svga" -> 800
            else -> 640
        }
        val scale = (maxOf(bitmap.width, bitmap.height) / maxDimension.toFloat()).coerceAtLeast(1f)
        val resized = if (scale == 1f) bitmap else Bitmap.createScaledBitmap(bitmap, (bitmap.width / scale).toInt(), (bitmap.height / scale).toInt(), true)
        val watermarked = CaptureWatermark.draw(resized, extraLines = pose.watermarkLines())
        return run {
            var quality = 80
            var encoded: ByteArray
            do {
                encoded = ByteArrayOutputStream().use { stream ->
                    watermarked.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                    stream.toByteArray()
                }
                quality -= 10
            } while (encoded.size > 2 * 1024 * 1024 && quality >= 40)
            if (encoded.size > 2 * 1024 * 1024) error("图片压缩后仍超过 2 MB")
            EncodedPhoto(Base64.encodeToString(encoded, Base64.NO_WRAP), watermarked.width, watermarked.height, encoded.size)
        }.also {
            if (watermarked !== resized) watermarked.recycle()
            if (resized !== bitmap) resized.recycle()
            bitmap.recycle()
        }
    }
}
