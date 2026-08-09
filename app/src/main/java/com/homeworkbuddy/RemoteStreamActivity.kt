package com.homeworkbuddy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
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
import java.util.UUID
import kotlin.math.abs

/** Coordinates a time-bounded stream without opening a separate Activity. */
object RemoteStreamCoordinator {
    private var pending: CompletableDeferred<JSONObject>? = null
    private var stopAction: (() -> Unit)? = null

    suspend fun start(context: Context, fps: Int, durationSeconds: Int, resolution: String): JSONObject {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "需要相机权限才能共享学习画面"
        }
        val result = CompletableDeferred<JSONObject>()
        synchronized(this) {
            check(pending == null && stopAction == null) { "已有学习画面共享正在进行" }
            pending = result
        }
        CaptureStatusStore(context).begin(CaptureKind.STREAM)
        CameraShutterSound.play()
        runCatching {
            InAppRemoteStream(context.applicationContext, fps.coerceIn(1, 3), durationSeconds.coerceIn(1, 60), resolution, ::ready, ::fail)
                .start()
        }.onFailure { fail(it.message ?: "无法启动学习画面共享") }
        return try {
            withTimeout(15_000) { result.await() }
        } finally {
            synchronized(this) { if (pending === result) pending = null }
        }
    }

    private fun ready(result: JSONObject, stop: () -> Unit) = synchronized(this) {
        stopAction = stop
        pending?.complete(result)
    }

    private fun fail(message: String) = synchronized(this) {
        pending?.completeExceptionally(IllegalStateException(message))
        stopAction = null
        CaptureStatusStoreHolder.clear()
    }

    fun stopped() = synchronized(this) { stopAction = null }

    fun stop(): JSONObject {
        val action = synchronized(this) { stopAction } ?: throw IllegalStateException("当前没有正在共享的学习画面")
        action()
        return JSONObject().put("stopping", true)
    }
}

/** Keeps the status cleanup callable from the coordinator's background error path. */
private object CaptureStatusStoreHolder {
    private var context: Context? = null
    fun bind(context: Context) { this.context = context.applicationContext }
    fun clear() { context?.let { CaptureStatusStore(it).clear() } }
}

private class InAppRemoteStream(
    private val context: Context,
    private val fps: Int,
    private val durationSeconds: Int,
    private val resolution: String,
    private val onReady: (JSONObject, () -> Unit) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val streamID = "android-${UUID.randomUUID()}"
    private var sequence = 0L
    private var inFlight = false
    private var started = false
    private var stopped = false

    fun start() {
        CaptureStatusStoreHolder.bind(context)
        Log.i("RemoteStream", "in-app stream requested")
        val thread = HandlerThread("remote_stream").also { it.start() }
        worker = thread
        handler = Handler(thread.looper)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        runCatching {
            val cameraID = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull() ?: error("未找到可用相机")
            val sizes = manager.getCameraCharacteristics(cameraID)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG) ?: error("相机不支持 JPEG 视频帧")
            openCamera(manager, cameraID, selectSize(sizes))
        }.onFailure { failAndStop(it.message ?: "无法打开相机") }
    }

    private fun selectSize(sizes: Array<Size>): Size {
        val target = when (resolution.lowercase()) {
            "svga" -> Size(800, 600)
            "vga" -> Size(640, 480)
            "qvga" -> Size(320, 240)
            else -> Size(160, 120)
        }
        return sizes.minByOrNull { abs(it.width * it.height - target.width * target.height) } ?: error("未找到可用视频尺寸")
    }

    @Suppress("MissingPermission")
    private fun openCamera(manager: CameraManager, cameraID: String, size: Size) {
        val output = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        reader = output
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        previewTexture = texture
        val preview = Surface(texture)
        previewSurface = preview
        output.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage()
            if (image != null) {
                runCatching {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val watermarked = watermarkFrame(bytes)
                    check(XiaoliMcpNotifications.publish("xiaoli/vision_frame", JSONObject()
                        .put("stream_id", streamID)
                        .put("seq", ++sequence)
                        .put("timestamp_ms", System.currentTimeMillis())
                        .put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(watermarked, Base64.NO_WRAP)))) { "设备连接已断开" }
                }.onFailure { stopStream(it.message ?: "发送视频帧失败") }
                image.close()
            }
            inFlight = false
            if (!stopped) handler?.postDelayed({ captureNext() }, 1_000L / fps)
        }, handler)
        manager.openCamera(cameraID, object : CameraDevice.StateCallback() {
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
                            started = true
                            onReady(JSONObject().put("stream_id", streamID).put("fps", fps).put("duration_sec", durationSeconds)
                                .put("resolution", "jpeg").put("transport", "remote")) { stopStream("服务端已停止共享") }
                            handler?.postDelayed({ captureNext() }, 900)
                            handler?.postDelayed({ stopStream("共享时间已到") }, durationSeconds * 1_000L)
                        }.onFailure { failAndStop(it.message ?: "无法启动相机预览") }
                    }
                    override fun onConfigureFailed(captureSession: CameraCaptureSession) = failAndStop("相机初始化失败")
                }, handler)
            }
            override fun onDisconnected(device: CameraDevice) = failAndStop("相机已断开")
            override fun onError(device: CameraDevice, error: Int) = failAndStop("无法打开相机")
        }, handler)
    }

    private fun captureNext() {
        if (stopped || inFlight) return
        val currentCamera = camera ?: return
        val currentSession = session ?: return
        inFlight = true
        runCatching {
            val request = currentCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader?.surface ?: error("相机输出不可用"))
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()
            currentSession.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    inFlight = false
                    if (!stopped) handler?.postDelayed({ captureNext() }, 1_000L / fps)
                }
            }, handler)
        }.onFailure { stopStream(it.message ?: "拍摄视频帧失败") }
    }

    private fun watermarkFrame(bytes: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("无法读取视频帧")
        val watermarked = CaptureWatermark.draw(bitmap)
        return ByteArrayOutputStream().use { output ->
            watermarked.compress(Bitmap.CompressFormat.JPEG, 80, output)
            if (watermarked !== bitmap) watermarked.recycle()
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun failAndStop(message: String) {
        if (!started) onFailure(message)
        stopStream(message)
    }

    private fun stopStream(reason: String) {
        if (stopped) return
        stopped = true
        handler?.removeCallbacksAndMessages(null)
        closeResources()
        if (started) CaptureStatusStore(context).complete(CaptureKind.STREAM) else CaptureStatusStore(context).clear()
        RemoteStreamCoordinator.stopped()
        Log.i("RemoteStream", "stopped: $reason")
    }

    private fun closeResources() {
        session?.close(); session = null
        camera?.close(); camera = null
        reader?.close(); reader = null
        previewSurface?.release(); previewSurface = null
        previewTexture?.release(); previewTexture = null
        worker?.quitSafely(); worker = null
        handler = null
    }
}
