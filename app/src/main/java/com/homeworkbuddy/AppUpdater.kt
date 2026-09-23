package com.homeworkbuddy

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Self-update against Pgyer's public distribution page. Pgyer's authenticated
 * APIs require a publisher API key, which must never be packaged in an APK.
 * The public page supplies the latest version and release notes; it also owns
 * the short-lived Android download flow.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val PGYER_DOWNLOAD_PAGE = "https://www.pgyer.com/zuoyexiaohuoban"
    private val versionPattern = Regex(
        """<div class="pull-left">版本</div>\s*<div[^>]*>\s*([^<\s]+)\s*</div>""",
    )
    private val notesPattern = Regex(
        """<div class="update-description">\s*(.*?)\s*</div>""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    data class UpdateInfo(val versionName: String, val downloadPageUrl: String, val notes: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Returns the newer Pgyer build, or null when the installed build is current. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(PGYER_DOWNLOAD_PAGE)
                .header("User-Agent", "HomeworkBuddy-Android/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val page = response.body?.string() ?: return@use null
                val latest = versionPattern.find(page)?.groupValues?.getOrNull(1) ?: return@use null
                if (latest.isBlank() || !isNewer(latest, BuildConfig.VERSION_NAME)) return@use null
                UpdateInfo(latest, PGYER_DOWNLOAD_PAGE, releaseNotes(page))
            }
        }.onFailure { Log.w(TAG, "update check failed", it) }.getOrNull()
    }

    /** Opens Pgyer's current public page, which creates its own install URL. */
    fun openDownloadPage(context: Context, info: UpdateInfo) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(info.downloadPageUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun releaseNotes(page: String): String {
        val notesHtml = notesPattern.find(page)?.groupValues?.getOrNull(1) ?: return ""
        return HtmlCompat.fromHtml(notesHtml, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
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
                Text(if (error != null) "重试" else "前往蒲公英更新")
            }
        },
        dismissButton = {
            if (progress == null) TextButton(onClick = onDismiss) { Text("以后再说") }
        },
    )
}
