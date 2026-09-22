package com.homeworkbuddy

import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-update against the public GitHub Releases feed. Release CI always
 * uploads the signed APK under a fixed asset name so the app can find it
 * without hardcoding a versioned URL.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/mnhkahn/homework/releases/latest"
    private const val APK_ASSET_NAME = "HomeworkBuddy-release.apk"

    data class UpdateInfo(val versionName: String, val apkUrl: String, val notes: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Returns the newer release, or null when the installed build is current. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val release = JSONObject(response.body?.string() ?: return@use null)
                val latest = release.optString("tag_name").removePrefix("v")
                if (latest.isBlank() || !isNewer(latest, BuildConfig.VERSION_NAME)) return@use null
                val assets = release.optJSONArray("assets") ?: return@use null
                var apkUrl: String? = null
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    if (asset.optString("name") == APK_ASSET_NAME) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (apkUrl.isNullOrBlank()) null
                else UpdateInfo(latest, apkUrl, release.optString("body"))
            }
        }.onFailure { Log.w(TAG, "update check failed", it) }.getOrNull()
    }

    /**
     * Android's DownloadManager owns this task: it persists outside our
     * activity/process, shows notification-bar progress, and retries temporary
     * network failures. The system may resume an interrupted HTTP download.
     */
    fun enqueueDownload(context: Context, info: UpdateInfo): Long {
        val destination = updateFile(context).also { it.parentFile?.mkdirs(); it.delete() }
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("作业小伙伴更新 ${info.versionName}")
            .setDescription("正在后台下载更新")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/$APK_ASSET_NAME")
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    data class DownloadStatus(val complete: Boolean, val failed: Boolean, val progress: Float, val error: String? = null)

    fun downloadStatus(context: Context, id: Long): DownloadStatus {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return DownloadStatus(false, true, 0f, "系统下载任务不存在")
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val soFar = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val progress = if (total > 0) soFar.toFloat() / total else 0f
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus(true, false, 1f)
                DownloadManager.STATUS_FAILED -> DownloadStatus(false, true, progress, "系统下载失败（${cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}）")
                else -> DownloadStatus(false, false, progress)
            }
        }
    }

    fun downloadedFile(context: Context): File = updateFile(context)

    private fun updateFile(context: Context) = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates/$APK_ASSET_NAME")

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun isNewer(latest: String, current: String): Boolean {
        fun parts(version: String) = version.split(Regex("[.\\-+]")).map { it.toIntOrNull() ?: 0 }
        val latestParts = parts(latest)
        val currentParts = parts(current)
        for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
            val newPart = latestParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (newPart != currentPart) return newPart > currentPart
        }
        return false
    }
}

@Composable
fun UpdateDialog(
    info: AppUpdater.UpdateInfo,
    progress: Float?,
    error: String?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        icon = { Text("🚀", fontSize = 38.sp) },
        title = { Text("发现新版本 ${info.versionName}") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (progress != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("正在下载更新… ${(progress * 100).toInt()}%")
                    }
                } else {
                    Text("当前版本 ${BuildConfig.VERSION_NAME}")
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(info.notes)
                    }
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onUpdate, enabled = progress == null) {
                Text(if (error != null) "重试" else "立即更新")
            }
        },
        dismissButton = {
            if (progress == null) TextButton(onClick = onDismiss) { Text("以后再说") }
        },
    )
}
