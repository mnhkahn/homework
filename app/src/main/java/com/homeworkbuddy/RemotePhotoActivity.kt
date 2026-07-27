package com.homeworkbuddy

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** A visible activity: a remote MCP call must never open the camera silently. */
object RemotePhotoCoordinator {
    private var pending: CompletableDeferred<JSONObject>? = null

    suspend fun take(context: android.content.Context): JSONObject {
        val result = CompletableDeferred<JSONObject>()
        synchronized(this) {
            check(pending == null) { "已有拍照请求正在进行" }
            pending = result
        }
        context.startActivity(Intent(context, RemotePhotoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        return try { withTimeout(120_000) { result.await() } }
        finally { synchronized(this) { if (pending === result) pending = null } }
    }

    fun complete(result: JSONObject) = synchronized(this) { pending?.complete(result) }
    fun fail(message: String) = synchronized(this) { pending?.completeExceptionally(IllegalStateException(message)) }
}

class RemotePhotoActivity : ComponentActivity() {
    private var output: Uri? = null
    private var outputFile: File? = null
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runCatching { RemotePhotoCoordinator.complete(JSONObject().put("mime_type", "image/jpeg").put("image_base64", encodePhoto())) }
                .onFailure { RemotePhotoCoordinator.fail(it.message ?: "照片编码失败") }
        } else RemotePhotoCoordinator.fail("已取消拍照")
        finish()
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else { RemotePhotoCoordinator.fail("需要相机权限才能拍照"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "小李请求拍摄学习照片"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val file = File(cacheDir, "mcp_photos/${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }
        outputFile = file
        output = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        capture.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, output)
            clipData = ClipData.newRawUri("学习照片", output)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun encodePhoto(): String {
        val bitmap = BitmapFactory.decodeFile(outputFile?.path) ?: error("无法读取照片")
        val scale = (maxOf(bitmap.width, bitmap.height) / 1280f).coerceAtLeast(1f)
        val resized = if (scale == 1f) bitmap else Bitmap.createScaledBitmap(bitmap, (bitmap.width / scale).toInt(), (bitmap.height / scale).toInt(), true)
        return ByteArrayOutputStream().use { bytes ->
            resized.compress(Bitmap.CompressFormat.JPEG, 75, bytes)
            Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        }.also { if (resized !== bitmap) resized.recycle(); bitmap.recycle() }
    }
}
