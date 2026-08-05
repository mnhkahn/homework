package com.homeworkbuddy

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** A visible activity: a remote MCP call must never capture the screen silently. */
object RemoteScreenshotCoordinator {
    private var pending: CompletableDeferred<JSONObject>? = null

    suspend fun capture(context: android.content.Context): JSONObject {
        val result = CompletableDeferred<JSONObject>()
        synchronized(this) {
            check(pending == null) { "已有截图请求正在进行" }
            pending = result
        }
        context.startActivity(Intent(context, RemoteScreenshotActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        return try { withTimeout(60_000) { result.await() } }
        finally { synchronized(this) { if (pending === result) pending = null } }
    }

    fun complete(result: JSONObject) = synchronized(this) { pending?.complete(result) }
    fun fail(message: String) = synchronized(this) { pending?.completeExceptionally(IllegalStateException(message)) }
}

class RemoteScreenshotActivity : ComponentActivity() {
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(this,
                Intent(this, ScreenshotService::class.java)
                    .putExtra(ScreenshotService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenshotService.EXTRA_RESULT_DATA, data))
        } else RemoteScreenshotCoordinator.fail("已取消截图授权")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "小李请求截取当前屏幕"
        captureConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}
