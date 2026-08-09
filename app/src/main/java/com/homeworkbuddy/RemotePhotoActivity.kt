package com.homeworkbuddy

import android.Manifest
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

    suspend fun take(context: Context): JSONObject {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "需要相机权限才能拍照"
        }
        val result = CompletableDeferred<JSONObject>()
        synchronized(this) {
            check(pending == null) { "已有拍照请求正在进行" }
            pending = result
        }
        CaptureStatusStore(context).begin(CaptureKind.PHOTO)
        CameraShutterSound.play()
        runCatching {
            InAppPhotoCapture(context.applicationContext, ::complete, ::fail).also {
                synchronized(this) { capture = it }
                it.start()
            }
        }.onFailure { fail(it.message ?: "无法打开相机") }
        return try {
            withTimeout(20_000) { result.await() }
        } finally {
            synchronized(this) {
                if (pending === result) {
                    pending = null
                    capture?.cancel()
                    capture = null
                }
            }
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
            val size = sizes.filter { maxOf(it.width, it.height) <= 1280 }.maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height } ?: error("未找到可用拍照尺寸")
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
                finishSuccess(JSONObject().put("mime_type", "image/jpeg").put("image_base64", encodePhoto(bytes)))
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

    private fun encodePhoto(bytes: ByteArray): String {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("无法读取照片")
        val scale = (maxOf(bitmap.width, bitmap.height) / 1280f).coerceAtLeast(1f)
        val resized = if (scale == 1f) bitmap else Bitmap.createScaledBitmap(bitmap, (bitmap.width / scale).toInt(), (bitmap.height / scale).toInt(), true)
        val watermarked = CaptureWatermark.draw(resized)
        return ByteArrayOutputStream().use { stream ->
            watermarked.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }.also {
            if (watermarked !== resized) watermarked.recycle()
            if (resized !== bitmap) resized.recycle()
            bitmap.recycle()
        }
    }
}
