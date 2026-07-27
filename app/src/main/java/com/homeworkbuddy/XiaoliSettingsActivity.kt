package com.homeworkbuddy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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

class XiaoliSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { XiaoliSettingsScreen(onBack = ::finish) } }
    }
}

@Composable
private fun XiaoliSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeConfig by remember { mutableStateOf(XiaoliDeviceStore.config(context)) }
    var name by remember { mutableStateOf(activeConfig?.deviceName ?: "学习平板") }
    var qrContent by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
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

    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("小李连接", fontSize = 30.sp)
        Text("扫描已登录小李管理端生成的一次性二维码，将这台平板绑定到你的账号。")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("连接状态：${XiaoliConnectionState.status}")
                Text("设备 ID：${activeConfig?.deviceId ?: XiaoliDeviceStore.defaultDeviceId(context)}")
                XiaoliConnectionState.lastError?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
            }
        }
        OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("设备名称") }, singleLine = true)
        OutlinedTextField(qrContent, { qrContent = it }, Modifier.fillMaxWidth(), label = { Text("二维码内容（扫描失败时可粘贴）") }, minLines = 3)
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许小李通知") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                scanner.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("对准小李后台生成的二维码")
                    setBeepEnabled(false)
                    // Keep the parent settings activity intact on MIUI tablets.
                    // The QR scanner is happy in the current (landscape) orientation.
                    setOrientationLocked(true)
                })
            }) { Text("扫描二维码") }
            Button(enabled = name.isNotBlank() && qrContent.isNotBlank(), onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { XiaoliPairingClient.pair(context, qrContent, name.trim()) } }
                    result.onSuccess { config -> XiaoliDeviceStore.save(context, config); activeConfig = config; XiaoliConnectionService.connect(context); message = "已保存，正在连接小李" }
                        .onFailure { message = it.message ?: "配对失败" }
                }
            }) { Text("保存并连接") }
            OutlinedButton(enabled = activeConfig != null, onClick = { XiaoliConnectionService.disconnect(context); XiaoliDeviceStore.clear(context); activeConfig = null; message = "已解除本机绑定" }) { Text("解除绑定") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onBack) { Text("返回家长设置") }
    }
}
