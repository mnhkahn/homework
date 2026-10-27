package com.homeworkbuddy

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private class ParentPinStore(context: Context) {
    private val prefs = context.getSharedPreferences("parent_pin", Context.MODE_PRIVATE)
    val hasPin: Boolean get() = prefs.contains("hash")

    fun set(pin: String) {
        val salt = ByteArray(24).also(SecureRandom()::nextBytes)
        prefs.edit()
            .putString("salt", Base64.getEncoder().encodeToString(salt))
            .putString("hash", hash(pin, salt))
            .apply()
    }

    fun verify(pin: String): Boolean {
        val salt = runCatching { Base64.getDecoder().decode(prefs.getString("salt", "")) }.getOrNull() ?: return false
        val expected = prefs.getString("hash", null) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), hash(pin, salt).toByteArray())
    }

    fun clearSession() { prefs.edit().remove("authenticated_until").apply() }

    fun isSessionValid(now: Long = System.currentTimeMillis()): Boolean =
        prefs.getLong("authenticated_until", 0L) > now

    fun markAuthenticated(now: Long = System.currentTimeMillis()) {
        // A short session survives an Activity recreation while the local QR
        // scanner owns the camera, but never turns the parent PIN into a
        // permanent bypass.
        prefs.edit().putLong("authenticated_until", now + 5 * 60_000L).apply()
    }

    private fun hash(pin: String, salt: ByteArray): String {
        var value = salt + pin.toByteArray()
        repeat(100_000) { value = MessageDigest.getInstance("SHA-256").digest(value) }
        return Base64.getEncoder().encodeToString(value)
    }
}

class KioskSettingsActivity : FragmentActivity() {
    var parentAuthRevision by mutableIntStateOf(0)
        private set

    override fun onStop() {
        if (ParentTotpStore(this).configured) { ParentPinStore(this).clearSession(); parentAuthRevision++ }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && ParentTotpStore(this).configured) ParentPinStore(this).clearSession()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val policy = KioskPolicy(this)
        if (policy.isScreenshotSettingsAllowed) policy.revokeScreenshotSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme { ParentGate(this) }
        }
    }
}

@Composable
private fun ParentGate(activity: KioskSettingsActivity) {
    val store = remember { ParentPinStore(activity) }
    val totp = remember { ParentTotpStore(activity) }
    var authenticated by remember { mutableStateOf(!totp.configured && store.isSessionValid()) }
    val authRevision = activity.parentAuthRevision
    LaunchedEffect(authRevision) { if (authRevision > 0 && !store.isSessionValid()) authenticated = false }
    var useRecovery by remember { mutableStateOf(false) }
    var hasPin by remember { mutableStateOf(store.hasPin) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Some vendors expose face unlock only through the device credential path,
    // rather than as BIOMETRIC_WEAK.  Supporting both lets the system present
    // the familiar face/fingerprint/PIN sheet instead of forcing our local PIN.
    val systemAuthAvailable = remember {
        BiometricManager.from(activity).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }
    val promptInfo = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("家长验证")
            .setSubtitle("请使用系统人脸、指纹或锁屏验证进入家长设置")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
    }
    val biometricPrompt = remember(activity) {
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                store.markAuthenticated(); authenticated = true
                error = null
            }

            override fun onAuthenticationFailed() {
                error = "未识别，请重试或使用家长 PIN"
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                    error = errString.toString()
                }
            }
        })
    }

    LaunchedEffect(hasPin, systemAuthAvailable) {
        if (hasPin && !totp.configured && systemAuthAvailable) biometricPrompt.authenticate(promptInfo)
    }

    if (authenticated) {
        KioskSettingsScreen(activity)
        return
    }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            FilledTonalIconButton(
                onClick = activity::finish,
                modifier = Modifier.align(Alignment.TopStart).size(58.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", modifier = Modifier.size(32.dp)) }
            Column(Modifier.fillMaxWidth(.55f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (totp.configured || hasPin) "家长验证" else "设置家长 PIN", fontSize = 28.sp, fontWeight = FontWeight.Medium)
                if (hasPin && !totp.configured && systemAuthAvailable) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = { biometricPrompt.authenticate(promptInfo) }) {
                        Icon(Icons.Outlined.Face, null)
                        Spacer(Modifier.size(8.dp))
                        Text("使用人脸或指纹")
                    }
                    Text("也可以使用锁屏验证或家长 PIN", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(pin, { pin = if (totp.configured && useRecovery) it.filter(Char::isLetterOrDigit).uppercase().take(16) else it.filter(Char::isDigit).take(if (totp.configured) 6 else 8) }, label = { Text(if (totp.configured) { if (useRecovery) "一次性恢复码" else "6 位动态验证码" } else "4–8 位数字 PIN") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (totp.configured) TextButton(onClick = { useRecovery = !useRecovery; pin = ""; error = null }) { Text(if (useRecovery) "使用动态验证码" else "使用恢复码") }
                if (!hasPin && !totp.configured) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(confirm, { confirm = it.filter(Char::isDigit).take(8) }, label = { Text("再次输入 PIN") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
                Spacer(Modifier.height(18.dp))
                Button(onClick = {
                    when {
                        totp.configured && totp.verify(pin, useRecovery) -> { store.markAuthenticated(); authenticated = true; error = null }
                        totp.configured -> error = if (System.currentTimeMillis() < totp.lockedUntil) "尝试次数过多，请等待 30 秒。" else "验证码无效或已使用，请检查设备时间后重试。"
                        hasPin && store.verify(pin) -> { store.markAuthenticated(); authenticated = true }
                        hasPin -> error = "PIN 不正确"
                        pin.length !in 4..8 -> error = "请输入 4–8 位数字"
                        pin != confirm -> error = "两次输入不一致"
                        else -> { store.set(pin); store.markAuthenticated(); hasPin = true; authenticated = true }
                    }
                }) { Text(if (hasPin || totp.configured) "进入家长设置" else "保存并进入") }
            }
        }
    }
}

@Composable
private fun KioskSettingsScreen(activity: KioskSettingsActivity) {
    val policy = remember { KioskPolicy(activity) }
    var showAppSelection by remember { mutableStateOf(false) }
    var showOpenVerification by remember { mutableStateOf(false) }
    var startHour by remember { mutableStateOf((policy.startMinutes / 60).toString()) }
    var startMinute by remember { mutableStateOf((policy.startMinutes % 60).toString()) }
    var endHour by remember { mutableStateOf((policy.endMinutes / 60).toString()) }
    var endMinute by remember { mutableStateOf((policy.endMinutes % 60).toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var openHistoryRevision by remember { mutableIntStateOf(0) }
    val openHistory = remember(openHistoryRevision) { TemporaryOpenHistory(activity).records() }
    val alarm = activity.getSystemService(AlarmManager::class.java)
    var screenshotAvailable by remember { mutableStateOf(ScreenCaptureService.available) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        screenshotAvailable = ScreenCaptureService.available
        openHistoryRevision++
        onPauseOrDispose { }
    }

    if (showOpenVerification) ParentCodeDialog(onDismiss = { showOpenVerification = false }, onVerified = {
        showOpenVerification = false
        policy.pause(15, activity, parentVerified = true)
        openHistoryRevision++
        message = "已临时进入普通模式 15 分钟（今日第 ${policy.temporaryOpenCountToday} 次）"
    })

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = activity::finish,
                    modifier = Modifier.size(58.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回作业", modifier = Modifier.size(32.dp)) }
                Spacer(Modifier.size(16.dp))
                Column {
                    Text("家长设置", fontSize = 30.sp, fontWeight = FontWeight.Medium)
                    Text(if (policy.isDeviceOwner) "设备管理权限已启用" else "尚未成为 Device Owner", color = if (policy.isDeviceOwner) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                    Text("当前：${if (policy.mode() == KioskMode.STUDY) "学习模式" else "普通模式"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { ParentTotpSettings(onChanged = { ParentPinStore(activity).clearSession() }) }
        item { AppUpdateSettingsCard(activity) }
        item { HeadPoseHistoryCard() }
        item { HeadPoseSettingsCard() }
        if (!policy.isDeviceOwner) item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CE))) {
                Column(Modifier.padding(18.dp)) {
                    Text("先安装当前 APK，再通过电脑执行：", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text("adb shell dpm set-device-owner --user 0 com.homeworkbuddy/.HomeworkDeviceAdminReceiver", fontSize = 13.sp)
                    Text("设置成功前，下面的锁定按钮不会生效。", modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF4FF)), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("管控与紧急出口", fontSize = 21.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                        Button(enabled = policy.isDeviceOwner, onClick = {
                            if (ParentTotpStore(activity).configured) showOpenVerification = true
                            else { policy.pause(15, activity); openHistoryRevision++; message = "已临时进入普通模式 15 分钟（今日第 ${policy.temporaryOpenCountToday} 次）" }
                        }) { Icon(Icons.Outlined.LockOpen, null); Spacer(Modifier.size(7.dp)); Text("临时开放 15 分钟") }
                        Button(enabled = policy.isDeviceOwner, onClick = { policy.guardFor(0, activity); openHistoryRevision++; message = "已进入学习模式，守护至今天结束" }) { Icon(Icons.Outlined.Lock, null); Spacer(Modifier.size(7.dp)); Text("守护至今天结束") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                        OutlinedButton(enabled = policy.isDeviceOwner, onClick = { policy.resume(activity); openHistoryRevision++ }) { Icon(Icons.Outlined.RestartAlt, null); Spacer(Modifier.size(7.dp)); Text("结束临时开放") }
                        OutlinedButton(enabled = policy.isDeviceOwner, onClick = policy::openStudyLauncher) { Icon(Icons.Outlined.Apps, null); Spacer(Modifier.size(7.dp)); Text("查看学习应用") }
                    }
                    Text("今日临时开放 ${policy.temporaryOpenCountToday} 次", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Text("最近临时开放记录", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
                    if (openHistory.isEmpty()) {
                        Text("暂无时间明细；更新前只记录了每日次数。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (policy.temporaryOpenUntil > 0) {
                            val lastEnd = java.time.Instant.ofEpochMilli(policy.temporaryOpenUntil).atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
                            Text("旧记录中最近保存的计划截止：$lastEnd（没有开始时间）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        val format = java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm")
                        openHistory.take(10).forEach { record ->
                            val start = java.time.Instant.ofEpochMilli(record.startedAt).atZone(java.time.ZoneId.systemDefault()).format(format)
                            val end = java.time.Instant.ofEpochMilli(record.endedAt ?: record.plannedEnd).atZone(java.time.ZoneId.systemDefault()).format(format)
                            val suffix = when {
                                record.endedAt != null -> "提前结束"
                                System.currentTimeMillis() < record.plannedEnd -> "进行中，预计结束"
                                else -> "计划截止"
                            }
                            Text("$start — $end · ${record.source} · $suffix", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp)) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF4FF)), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("小李连接", fontSize = 21.sp, fontWeight = FontWeight.Medium)
                    Text("扫码绑定这台平板，让小李可查询作业、发送通知、请求拍照和临时开放 15 分钟。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (screenshotAvailable) "屏幕截图服务已连接" else "屏幕截图服务未连接", modifier = Modifier.padding(top = 10.dp))
                    Text("远程拍照检查会同时上传当前屏幕，包括桌面及其他应用。需 Android 11 及以上，并在系统无障碍设置中启用“作业小伙伴屏幕截图”。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(enabled = Build.VERSION.SDK_INT >= 30, onClick = {
                        policy.allowScreenshotSettings()
                        runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            .onFailure {
                                if (policy.isScreenshotSettingsAllowed) policy.revokeScreenshotSettings()
                                message = "无法打开无障碍设置：${it.message}"
                            }
                    }) { Text("配置屏幕截图权限") }
                    Button(onClick = { activity.startActivity(Intent(activity, XiaoliSettingsActivity::class.java)) }, modifier = Modifier.padding(top = 10.dp)) { Text("配置小李连接") }
                }
            }
        }
        item {
            Text("学习时间", fontSize = 21.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallTimeField(startHour, { startHour = it.filter(Char::isDigit).take(2) }, "时")
                Text(":")
                SmallTimeField(startMinute, { startMinute = it.filter(Char::isDigit).take(2) }, "分")
                Text("至")
                SmallTimeField(endHour, { endHour = it.filter(Char::isDigit).take(2) }, "时")
                Text(":")
                SmallTimeField(endMinute, { endMinute = it.filter(Char::isDigit).take(2) }, "分")
                Button(onClick = {
                    val start = (startHour.toIntOrNull() ?: -1) * 60 + (startMinute.toIntOrNull() ?: -1)
                    val end = (endHour.toIntOrNull() ?: -1) * 60 + (endMinute.toIntOrNull() ?: -1)
                    runCatching { policy.saveSchedule(start, end, activity); message = "时间已保存并立即生效" }.onFailure { message = "时间格式不正确" }
                }) { Icon(Icons.Outlined.Save, null); Spacer(Modifier.size(7.dp)); Text("保存") }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }, modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Outlined.Alarm, null); Spacer(Modifier.size(7.dp)); Text("允许精确闹钟") }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("学习时间允许的应用", fontSize = 21.sp, fontWeight = FontWeight.Medium)
                    Text("点击选择学习时间可以打开的应用。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { showAppSelection = true }) {
                        Icon(Icons.Outlined.Apps, null)
                        Spacer(Modifier.size(7.dp))
                        Text("选择应用")
                    }
                }
            }
        }
        if (!policy.hasUsageAccess()) item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CE))) {
                Column(Modifier.padding(18.dp)) {
                    Text("开启“使用情况访问”后，学习时间才能拦截被绕过的未勾选应用。", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("去开启使用情况访问") }
                    Text("也可以使用电脑执行：adb shell appops set com.homeworkbuddy android:get_usage_stats allow", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
    if (showAppSelection) StudyAppSelectionDialog(policy) { showAppSelection = false }
}

@Composable
private fun StudyAppSelectionDialog(policy: KioskPolicy, onDismiss: () -> Unit) {
    val apps = remember(policy) { policy.launchableApps() }
    val nonLearningBrowsers = remember(policy) { policy.nonLearningBrowserPackages() }
    var approved by remember(policy) { mutableStateOf(policy.studyPackages) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择学习时间允许的应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("勾选后立即生效。作业小伙伴和 Chrome 始终允许；Chrome 仅可访问 cyeam.com、trello.com、pgyer.com 及其所有子域名，其他浏览器在学习时间不可用。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        val isOtherBrowser = app.packageName in nonLearningBrowsers
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = !isOtherBrowser && app.packageName in approved, enabled = app.packageName != KioskPolicy.LEARNING_BROWSER_PACKAGE && !isOtherBrowser, onCheckedChange = { checked ->
                                policy.setStudyAllowed(app.packageName, checked)
                                approved = policy.studyPackages
                            })
                            Column {
                                Text(app.label, fontWeight = FontWeight.Medium)
                                Text(app.packageName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (isOtherBrowser) Text("浏览器仅支持 Chrome，学习时间不可用", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun SmallTimeField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.size(width = 74.dp, height = 62.dp))
}

class ChildLauncherActivity : ComponentActivity() {
    // HyperOS fires onUserLeaveHint even for app-initiated activity starts.
    // Same escape hatch as MainActivity.allowManagedActivityLaunch: without it
    // the study-mode reassert below immediately covers every launched app.
    private var allowNextUserLeaveHint = false

    fun allowManagedActivityLaunch() {
        allowNextUserLeaveHint = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent { MaterialTheme { ChildLauncher(this) } }
        KioskPolicy(this).scheduleNextTransitions()
    }

    override fun onResume() {
        super.onResume()
        val policy = KioskPolicy(this)
        policy.markManagedActivityForeground()
        if (policy.mode() == KioskMode.STUDY) policy.applyForCurrentTime(this)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (allowNextUserLeaveHint) {
            allowNextUserLeaveHint = false
            return
        }
        val policy = KioskPolicy(this)
        if (policy.isDeviceOwner && policy.mode() == KioskMode.STUDY) {
            // Some tablet window managers expose a Home-like close command
            // even during Lock Task. Reclaim the managed task immediately.
            Handler(Looper.getMainLooper()).postDelayed({
                policy.applyForCurrentTime(this, navigate = true)
            }, 200)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChildLauncher(activity: ChildLauncherActivity) {
    val policy = remember { KioskPolicy(activity) }
    val available = remember { policy.launchableApps().associateBy { it.packageName } }
    val apps = policy.studyPackages.mapNotNull(available::get).sortedBy { it.label }
    var volume by remember { mutableStateOf(policy.currentStudyVolume) }
    val minimumVolume = policy.minimumStudyVolume
    val maximumVolume = policy.maximumStudyVolume
    Surface(Modifier.fillMaxSize(), color = Color(0xFFFFFBFF)) {
        Column(Modifier.fillMaxSize().padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = {
                        activity.allowManagedActivityLaunch()
                        activity.startActivity(Intent(activity, MainActivity::class.java))
                    },
                    modifier = Modifier.size(58.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回作业", modifier = Modifier.size(32.dp)) }
                Spacer(Modifier.size(16.dp))
                Text("学习应用", fontSize = 32.sp, fontWeight = FontWeight.Medium, modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                    activity.allowManagedActivityLaunch()
                    activity.startActivity(Intent(activity, KioskSettingsActivity::class.java))
                }))
            }
            Text("学习模式下只可打开这里的应用 · 长按标题进入家长设置", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("媒体音量 ${policy.studyVolumePercent(volume)}%", fontWeight = FontWeight.Medium)
                OutlinedButton(
                    enabled = volume > minimumVolume,
                    onClick = { volume = policy.setStudyVolume(volume - 1) },
                ) { Text("降低") }
                Button(
                    enabled = volume < maximumVolume,
                    onClick = { volume = policy.setStudyVolume(volume + 1) },
                ) { Text("提高") }
                Text("最低 ${policy.studyVolumePercent(minimumVolume)}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(22.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(180.dp), modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "homework") {
                    LauncherCard("作业小伙伴", icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary) }) {
                        activity.allowManagedActivityLaunch()
                        activity.startActivity(Intent(activity, MainActivity::class.java))
                    }
                }
                item(key = "cyeam-24") {
                    LauncherCard("24 点", icon = {
                        Image(
                            painterResource(R.drawable.cyeam_game_24),
                            null,
                            modifier = Modifier.size(56.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }) {
                        activity.allowManagedActivityLaunch()
                        activity.startActivity(GameActivity.intent(activity, GameActivity.GAME_24_URL))
                    }
                }
                item(key = "cyeam-sudoku") {
                    LauncherCard("数独", icon = {
                        Image(
                            painterResource(R.drawable.cyeam_game_sudoku),
                            null,
                            modifier = Modifier.size(56.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }) {
                        activity.allowManagedActivityLaunch()
                        activity.startActivity(GameActivity.intent(activity, GameActivity.SUDOKU_URL))
                    }
                }
                item(key = "cyeam-arithmetic") {
                    LauncherCard("口算", icon = {
                        Icon(Icons.Outlined.Calculate, null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                    }) {
                        activity.allowManagedActivityLaunch()
                        activity.startActivity(GameActivity.intent(activity, GameActivity.ARITHMETIC_URL))
                    }
                }
                item(key = "cyeam-english-reading") {
                    LauncherCard("英语跟读", icon = {
                        Icon(Icons.Outlined.RecordVoiceOver, null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                    }) {
                        activity.allowManagedActivityLaunch()
                        activity.startActivity(GameActivity.intent(activity, GameActivity.ENGLISH_READING_URL))
                    }
                }
                items(apps, key = { it.packageName }) { app ->
                    val icon = remember(app.packageName) { activity.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }
                    LauncherCard(app.label, icon = { Image(icon, null, modifier = Modifier.size(48.dp)) }) {
                        policy.prepareStudyAppLaunch(app.packageName)
                        activity.packageManager.getLaunchIntentForPackage(app.packageName)?.let { launch ->
                            // A new task keeps the external app out of this
                            // locked task; its package stays allowlisted.
                            activity.allowManagedActivityLaunch()
                            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LauncherCard(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(24.dp), modifier = Modifier.height(150.dp)) {
        Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            icon()
            Spacer(Modifier.height(8.dp))
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}
