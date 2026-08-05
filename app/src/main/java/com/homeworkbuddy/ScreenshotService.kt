package com.homeworkbuddy

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * One-shot screen capture: holds the MediaProjection grant from
 * RemoteScreenshotActivity just long enough to read a single frame, then
 * releases everything. The next MCP call asks for consent again.
 */
class ScreenshotService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "mcp_screenshot"
        private const val FRAME_TIMEOUT_MS = 5_000L
    }

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            RemoteScreenshotCoordinator.fail("已取消截图授权")
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
            this.projection = projection
            captureOneFrame(projection)
        }.onFailure {
            RemoteScreenshotCoordinator.fail(it.message ?: "截图失败")
            cleanup()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "远程截图", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("正在截取屏幕画面")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun captureOneFrame(projection: MediaProjection) {
        val thread = HandlerThread("mcp_screenshot").also { it.start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        this.handler = handler

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        // API 34+ requires a registered callback before createVirtualDisplay.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() = cleanup()
        }
        projectionCallback = callback
        projection.registerCallback(callback, handler)
        virtualDisplay = projection.createVirtualDisplay(
            "mcp_screenshot", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
        )

        val timeout = Runnable {
            RemoteScreenshotCoordinator.fail("截图超时")
            cleanup()
        }
        reader.setOnImageAvailableListener({ r ->
            handler.removeCallbacks(timeout)
            runCatching {
                val image = r.acquireLatestImage() ?: error("无法获取屏幕帧")
                val bitmap = imageToBitmap(image, width, height)
                image.close()
                RemoteScreenshotCoordinator.complete(
                    JSONObject().put("mime_type", "image/jpeg").put("image_base64", encodeJpeg(bitmap))
                )
            }.onFailure { RemoteScreenshotCoordinator.fail(it.message ?: "截图编码失败") }
            cleanup()
        }, handler)
        handler.postDelayed(timeout, FRAME_TIMEOUT_MS)
    }

    /** The image row may be padded (rowStride > pixelStride * width), so copy the padded rows first and then crop. */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val padded = Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun encodeJpeg(bitmap: Bitmap): String {
        val scale = (maxOf(bitmap.width, bitmap.height) / 1280f).coerceAtLeast(1f)
        val resized = if (scale == 1f) bitmap else Bitmap.createScaledBitmap(bitmap, (bitmap.width / scale).toInt(), (bitmap.height / scale).toInt(), true)
        return ByteArrayOutputStream().use { bytes ->
            resized.compress(Bitmap.CompressFormat.JPEG, 75, bytes)
            Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        }.also { if (resized !== bitmap) resized.recycle(); bitmap.recycle() }
    }

    private fun cleanup() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projectionCallback = null
        projection?.stop()
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
