package com.homeworkbuddy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext

class TeacherSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TeacherSettingsScreen(onBack = ::finish) } }
    }

    override fun onResume() {
        super.onResume()
        val policy = KioskPolicy(this)
        if (policy.isScreenshotSettingsAllowed) policy.revokeScreenshotSettings()
    }
}

@Composable
private fun TeacherSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeConfig by remember { mutableStateOf(TeacherDeviceStore.config(context)) }
    var connection by remember { mutableStateOf(TeacherConnectionState.snapshot()) }
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val listener: (TeacherConnectionSnapshot) -> Unit = { snapshot -> handler.post { connection = snapshot } }
        TeacherConnectionState.addChangeListener(listener)
        onDispose {
            TeacherConnectionState.removeChangeListener(listener)
            handler.removeCallbacksAndMessages(null)
        }
    }
    var name by remember { mutableStateOf(activeConfig?.deviceName ?: "学习平板") }
    var qrContent by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }
    // ZXing is bundled in the APK and decodes the camera image locally.  It does
    // not depend on Google Play services or download a scanner module at runtime.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents.isNullOrBlank()) {
            message = "未识别二维码，请对准二维码后重试"
        } else {
            qrContent = result.contents
            message = "二维码已识别，请点“保存并连接”"
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message = if (granted) "通知权限已开启" else "未授予通知权限，MCP 发送通知将无法显示"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("李老师连接", fontSize = 30.sp)
        Text("扫描李老师生成的一次性配对二维码。配对成功后会自动切换连接，无需先解除旧绑定。局域网连接时，请让平板与李老师所在电脑连接同一网络。")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("连接状态：${connection.status}")
                Text("设备 ID：${activeConfig?.deviceId ?: TeacherDeviceStore.defaultDeviceId(context)}")
                activeConfig?.let { Text("连接地址：${it.websocketUrl}") }
                connection.lastError?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
            }
        }
        OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("设备名称") }, singleLine = true, enabled = !pairing)
        OutlinedTextField(qrContent, { qrContent = it }, Modifier.fillMaxWidth(), label = { Text("二维码内容（扫描失败时可粘贴）") }, minLines = 3, enabled = !pairing)
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许李老师通知") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(enabled = !pairing, onClick = {
                scanner.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("对准李老师生成的配对二维码")
                    setBeepEnabled(false)
                    // Keep the parent settings activity intact on MIUI tablets.
                    // The QR scanner is happy in the current (landscape) orientation.
                    setOrientationLocked(true)
                })
            }) { Text("扫描二维码") }
            Button(enabled = !pairing && name.isNotBlank() && qrContent.isNotBlank(), onClick = {
                pairing = true
                scope.launch {
                    try {
                        val config = withContext(Dispatchers.IO) {
                            TeacherPairingClient.pair(context, qrContent, name.trim()).also {
                                TeacherDeviceStore.save(context, it)
                                TeacherConnectionService.reconnect(context.applicationContext)
                            }
                        }
                        activeConfig = config
                        qrContent = ""
                        message = "已配对，正在连接李老师"
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        message = error.message ?: "配对失败，请重新生成二维码后再试"
                    } finally {
                        pairing = false
                    }
                }
            }) { Text(if (pairing) "正在配对…" else "保存并连接") }
            OutlinedButton(enabled = !pairing && activeConfig != null, onClick = { TeacherConnectionService.disconnect(context); TeacherDeviceStore.clear(context); activeConfig = null; message = "已解除本机绑定" }) { Text("解除绑定") }
        }
        OutlinedButton(enabled = !pairing && android.os.Build.VERSION.SDK_INT >= 30, onClick = {
            val policy = KioskPolicy(context)
            policy.allowScreenshotSettings()
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure {
                if (policy.isScreenshotSettingsAllowed) policy.revokeScreenshotSettings()
                message = "无法打开屏幕截图权限设置：${it.message}"
            }
        }) { Text("配置屏幕截图权限") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(enabled = !pairing, onClick = onBack) { Text("返回家长设置") }
    }
}
