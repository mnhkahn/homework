package com.homeworkbuddy

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppUpdateActions(activity: Activity) {
    val updater = remember(activity) { AppUpdateViewModel.get(activity) }
    val state by updater.state.collectAsStateWithLifecycle()
    val policy = remember(activity) { KioskPolicy(activity) }
    var installError by remember(state.id) { mutableStateOf<String?>(null) }

    DisposableEffect(policy) {
        policy.revokeAppUpdateScreen()
        onDispose { }
    }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        policy.revokeAppUpdateScreen()
    }
    fun install() {
        runCatching {
            val intent = updater.installIntent()
            policy.allowAppUpdateScreen(intent)
            installer.launch(intent)
        }.onFailure {
            policy.revokeAppUpdateScreen()
            installError = it.message ?: "无法打开系统安装器，请稍后重试"
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        policy.revokeAppUpdateScreen()
        if (activity.packageManager.canRequestPackageInstalls() && updater.state.value.stage == UpdateStage.READY) install()
    }
    Button(
        enabled = state.stage != UpdateStage.CHECKING && state.stage != UpdateStage.DOWNLOADING,
        onClick = {
            installError = null
            if (state.stage != UpdateStage.READY) {
                updater.checkForUpdate()
            } else if (activity.packageManager.canRequestPackageInstalls()) {
                install()
            } else {
                runCatching {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    policy.allowAppUpdateScreen(intent)
                    permission.launch(intent)
                }.onFailure {
                    policy.revokeAppUpdateScreen()
                    installError = it.message ?: "无法打开安装权限设置，请稍后重试"
                }
            }
        },
    ) {
        Text(when (state.stage) {
            UpdateStage.CHECKING -> "正在检查…"
            UpdateStage.DOWNLOADING -> "正在下载…"
            UpdateStage.READY -> "安装更新"
            UpdateStage.FAILED -> "重试更新"
            UpdateStage.IDLE -> "版本更新"
        })
    }
    if (state.stage == UpdateStage.DOWNLOADING) {
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
    }
    if (state.message.isNotBlank()) {
        Text(if (state.version.isBlank()) state.message else "${state.version} · ${state.message}")
    }
    installError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
