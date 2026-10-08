package com.homeworkbuddy

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

/** A visible activity: a remote MCP call must never record video silently. */
object RemoteVideoCoordinator {
    private var pending: CompletableDeferred<JSONObject>? = null

    suspend fun record(context: android.content.Context, maxSeconds: Int): JSONObject {
        val pose = HeadPoseCameraAccess.photoReadings.snapshot(
            android.os.SystemClock.elapsedRealtime(), HeadPoseSettings(context)::baseline,
        )
        return HeadPoseCameraAccess.withRemoteCamera { recordExclusive(context, maxSeconds, pose) }
    }

    private suspend fun recordExclusive(context: android.content.Context, maxSeconds: Int, pose: HeadPosePhotoSnapshot): JSONObject {
        val result = CompletableDeferred<JSONObject>()
        synchronized(this) {
            check(pending == null) { "已有录像请求正在进行" }
            pending = result
        }
        context.startActivity(
            Intent(context, RemoteVideoActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(RemoteVideoActivity.EXTRA_MAX_SECONDS, maxSeconds)
                .putStringArrayListExtra(RemoteVideoActivity.EXTRA_POSE_LINES, ArrayList(pose.compactWatermarkLines("录制前坐姿")))
        )
        return try { withTimeout(120_000) { result.await() } }
        finally { synchronized(this) { if (pending === result) pending = null } }
    }

    fun complete(result: JSONObject) = synchronized(this) { pending?.complete(result) }
    fun fail(message: String) = synchronized(this) { pending?.completeExceptionally(IllegalStateException(message)) }
}

class RemoteVideoActivity : ComponentActivity() {
    companion object {
        const val EXTRA_POSE_LINES = "pose_lines"
        const val EXTRA_MAX_SECONDS = "max_seconds"
        private const val MAX_VIDEO_BYTES = 8 * 1024 * 1024
    }

    private var maxSeconds = 15
    private var output: Uri? = null
    private var outputFile: File? = null
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            setContentView(android.widget.TextView(this).apply {
                text = "正在添加视频水印…"
                gravity = android.view.Gravity.CENTER
                textSize = 18f
            })
            lifecycleScope.launch {
                var exported: File? = null
                try {
                    val file = outputFile ?: error("无法读取视频")
                    val durationMs = withContext(Dispatchers.IO) {
                        val metadata = MediaMetadataRetriever()
                        try {
                            metadata.setDataSource(file.absolutePath)
                            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                                ?: error("无法读取视频时长")
                        } finally {
                            metadata.release()
                        }
                    }
                    val target = File(file.parentFile, "${file.nameWithoutExtension}-watermarked.mp4")
                    exported = target
                    val lines = intent.getStringArrayListExtra(EXTRA_POSE_LINES)
                        ?: HeadPosePhotoSnapshot(null, null, null).compactWatermarkLines("录制前坐姿")
                    // The system camera owns the shutter; estimate start from the completed file.
                    VideoWatermark.export(applicationContext, file, target, file.lastModified() - durationMs, lines)
                    val encoded = withContext(Dispatchers.IO) {
                        if (target.length() > MAX_VIDEO_BYTES) error("加水印后的视频超过 8MB，请缩短录制时长")
                        check(target.length() > 0) { "视频导出为空" }
                        Base64.encodeToString(target.readBytes(), Base64.NO_WRAP)
                    }
                    RemoteVideoCoordinator.complete(JSONObject()
                        .put("mime_type", "video/mp4")
                        .put("video_base64", encoded)
                        .put("duration_seconds", durationMs / 1000.0))
                    CaptureStatusStore(applicationContext).complete(CaptureKind.VIDEO)
                } catch (error: Exception) {
                    CaptureStatusStore(applicationContext).clear()
                    RemoteVideoCoordinator.fail(error.message ?: "视频水印处理失败")
                } finally {
                    exported?.delete()
                    outputFile?.delete()
                    finish()
                }
            }
        } else {
            CaptureStatusStore(applicationContext).clear()
            RemoteVideoCoordinator.fail("已取消录像")
            outputFile?.delete()
            finish()
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else { CaptureStatusStore(applicationContext).clear(); RemoteVideoCoordinator.fail("需要相机权限才能录像"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "小李请求录制学习视频"
        maxSeconds = intent.getIntExtra(EXTRA_MAX_SECONDS, 15).coerceIn(1, 30)
        outputFile = savedInstanceState?.getString("output_path")?.let(::File)
        if (savedInstanceState != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("output_path", outputFile?.absolutePath)
        super.onSaveInstanceState(outState)
    }

    private fun launchCamera() {
        CaptureStatusStore(applicationContext).begin(CaptureKind.VIDEO)
        val file = File(cacheDir, "mcp_videos/${System.currentTimeMillis()}.mp4").also { it.parentFile?.mkdirs() }
        outputFile = file
        output = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        capture.launch(Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, output)
            putExtra(MediaStore.EXTRA_DURATION_LIMIT, maxSeconds)
            putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
            clipData = ClipData.newRawUri("学习视频", output)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
