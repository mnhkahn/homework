package com.homeworkbuddy

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { ParentGate(this) }
        }
    }
}

@Composable
private fun ParentGate(activity: KioskSettingsActivity) {
    val store = remember { ParentPinStore(activity) }
    var authenticated by remember { mutableStateOf(store.isSessionValid()) }
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
        if (hasPin && systemAuthAvailable) biometricPrompt.authenticate(promptInfo)
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
                Text(if (hasPin) "家长验证" else "设置家长 PIN", fontSize = 28.sp, fontWeight = FontWeight.Medium)
                if (hasPin && systemAuthAvailable) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = { biometricPrompt.authenticate(promptInfo) }) {
                        Icon(Icons.Outlined.Face, null)
                        Spacer(Modifier.size(8.dp))
                        Text("使用人脸或指纹")
                    }
                    Text("也可以使用锁屏验证或家长 PIN", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("4–8 位数字 PIN") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (!hasPin) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(confirm, { confirm = it.filter(Char::isDigit).take(8) }, label = { Text("再次输入 PIN") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
                Spacer(Modifier.height(18.dp))
                Button(onClick = {
                    when {
                        hasPin && store.verify(pin) -> { store.markAuthenticated(); authenticated = true }
                        hasPin -> error = "PIN 不正确"
                        pin.length !in 4..8 -> error = "请输入 4–8 位数字"
                        pin != confirm -> error = "两次输入不一致"
                        else -> { store.set(pin); store.markAuthenticated(); hasPin = true; authenticated = true }
                    }
                }) { Text(if (hasPin) "进入家长设置" else "保存并进入") }
            }
        }
    }
}

@Composable
private fun KioskSettingsScreen(activity: KioskSettingsActivity) {
    val policy = remember { KioskPolicy(activity) }
    var approved by remember { mutableStateOf(policy.studyPackages) }
    val apps = remember { policy.launchableApps() }
    var startHour by remember { mutableStateOf((policy.startMinutes / 60).toString()) }
    var startMinute by remember { mutableStateOf((policy.startMinutes % 60).toString()) }
    var endHour by remember { mutableStateOf((policy.endMinutes / 60).toString()) }
    var endMinute by remember { mutableStateOf((policy.endMinutes % 60).toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    val alarm = activity.getSystemService(AlarmManager::class.java)

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
                    Text(if (policy.isDeviceOwner) "设备管控已启用" else "尚未成为 Device Owner", color = if (policy.isDeviceOwner) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                }
            }
        }
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
                        Button(enabled = policy.isDeviceOwner, onClick = { policy.pause(15, activity); message = "已临时开放 15 分钟" }) { Icon(Icons.Outlined.LockOpen, null); Spacer(Modifier.size(7.dp)); Text("临时开放 15 分钟") }
                        Button(enabled = policy.isDeviceOwner, onClick = { policy.enterStudy(activity); message = "已立即进入守护模式" }) { Icon(Icons.Outlined.Lock, null); Spacer(Modifier.size(7.dp)); Text("立即进入守护") }
                        OutlinedButton(enabled = policy.isDeviceOwner, onClick = { policy.resume(activity) }) { Icon(Icons.Outlined.RestartAlt, null); Spacer(Modifier.size(7.dp)); Text("立即应用当前策略") }
                        OutlinedButton(enabled = policy.isDeviceOwner, onClick = policy::openStudyLauncher) { Icon(Icons.Outlined.Apps, null); Spacer(Modifier.size(7.dp)); Text("查看学习应用") }
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
                    runCatching { policy.saveSchedule(start, end); message = "时间已保存" }.onFailure { message = "时间格式不正确" }
                }) { Icon(Icons.Outlined.Save, null); Spacer(Modifier.size(7.dp)); Text("保存") }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }, modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Outlined.Alarm, null); Spacer(Modifier.size(7.dp)); Text("允许精确闹钟") }
            }
        }
        item {
            Text("学习时间允许的应用", fontSize = 21.sp, fontWeight = FontWeight.Medium)
            Text("作业小伙伴始终允许；未勾选的应用在学习时间无法打开。21:30 后恢复正常系统。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!policy.hasUsageAccess()) item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CE))) {
                Column(Modifier.padding(18.dp)) {
                    Text("开启“使用情况访问”后，学习时间才能拦截被绕过的未勾选应用。", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("去开启使用情况访问") }
                    Text("也可以使用电脑执行：adb shell appops set com.homeworkbuddy PACKAGE_USAGE_STATS allow", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        items(apps, key = { it.packageName }) { app ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = app.packageName in approved, onCheckedChange = { checked ->
                    policy.setStudyAllowed(app.packageName, checked)
                    approved = policy.studyPackages
                })
                Column { Text(app.label, fontWeight = FontWeight.Medium); Text(app.packageName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun SmallTimeField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.size(width = 74.dp, height = 62.dp))
}

class ChildLauncherActivity : ComponentActivity() {
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
        if (policy.mode() == KioskMode.STUDY) policy.applyForCurrentTime(this) else policy.exitStudyMode(this)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChildLauncher(activity: ChildLauncherActivity) {
    val policy = remember { KioskPolicy(activity) }
    val available = remember { policy.launchableApps().associateBy { it.packageName } }
    val apps = policy.studyPackages.mapNotNull(available::get).sortedBy { it.label }
    Surface(Modifier.fillMaxSize(), color = Color(0xFFFFFBFF)) {
        Column(Modifier.fillMaxSize().padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { activity.startActivity(Intent(activity, MainActivity::class.java)) },
                    modifier = Modifier.size(58.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回作业", modifier = Modifier.size(32.dp)) }
                Spacer(Modifier.size(16.dp))
                Text("学习应用", fontSize = 32.sp, fontWeight = FontWeight.Medium, modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                    activity.startActivity(Intent(activity, KioskSettingsActivity::class.java))
                }))
            }
            Text("学习时间只可以打开这里的应用 · 长按标题进入家长设置", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(22.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(180.dp), modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "homework") {
                    LauncherCard("作业小伙伴", icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary) }) { activity.startActivity(Intent(activity, MainActivity::class.java)) }
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
                        activity.startActivity(GameActivity.intent(activity, GameActivity.SUDOKU_URL))
                    }
                }
                items(apps, key = { it.packageName }) { app ->
                    val icon = remember(app.packageName) { activity.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }
                    LauncherCard(app.label, icon = { Image(icon, null, modifier = Modifier.size(48.dp)) }) {
                        policy.prepareStudyAppLaunch(app.packageName)
                        activity.packageManager.getLaunchIntentForPackage(app.packageName)?.let(activity::startActivity)
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
