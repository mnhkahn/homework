package com.homeworkbuddy

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Captures the current display without opening an Activity or changing the foreground app. */
class ScreenCaptureService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile private var instance: ScreenCaptureService? = null
        val available: Boolean get() = Build.VERSION.SDK_INT >= 30 && instance != null

        suspend fun capture(): JSONObject {
            if (Build.VERSION.SDK_INT < 30) throw McpException(-32006, "截图需要 Android 11 或更高版本")
            val service = instance ?: throw McpException(-32006, "请在家长设置中启用屏幕截图无障碍服务")
            if (!service.getSystemService(PowerManager::class.java).isInteractive) {
                throw McpException(-32006, "屏幕已关闭，未截取屏幕")
            }
            val result = CompletableDeferred<JSONObject>()
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    // The callback can arrive after timeout; always release the hardware buffer.
                    try {
                        if (!result.isActive) return
                        val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            ?: error("无法读取屏幕截图")
                        val bitmap = try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                        try {
                            val scale = minOf(1f, 1600f / maxOf(bitmap.width, bitmap.height))
                            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
                                (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                            try {
                                val bytes = ByteArrayOutputStream().use { output ->
                                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "截图编码失败" }
                                    output.toByteArray()
                                }
                                check(bytes.size <= 2 * 1024 * 1024) { "截图超过 2 MB" }
                                result.complete(JSONObject().put("image_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                                    .put("mime_type", "image/jpeg").put("width", scaled.width).put("height", scaled.height))
                            } finally { if (scaled !== bitmap) scaled.recycle() }
                        } finally { bitmap.recycle() }
                    } catch (error: Exception) {
                        result.completeExceptionally(error)
                    } finally { screenshot.hardwareBuffer.close() }
                }

                override fun onFailure(errorCode: Int) {
                    val message = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无障碍截图服务未授权"
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图调用过于频繁，请稍后重试"
                        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "当前显示屏不可用"
                        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前页面受系统安全保护，无法截图"
                        else -> "系统截图失败（$errorCode）"
                    }
                    result.completeExceptionally(McpException(-32006, message))
                }
            })
            return try { withTimeout(5_000) { result.await() } } finally { result.cancel() }
        }
    }
}
