package com.homeworkbuddy

import android.Manifest
import android.content.SharedPreferences
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeadPosePanel(available: Boolean) {
    val context = LocalContext.current
    val store = remember { HeadPoseSettings(context) }
    var limits by remember { mutableStateOf(store.load()) }
    var calibrationRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            limits = store.load()
            if (key?.startsWith("baseline_") == true) calibrationRevision++
        }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    var permissionAsked by rememberSaveablePermission()
    val busy by HeadPoseCameraAccess.busy.collectAsState()
    var state by remember { mutableStateOf(HeadPoseState()) }
    var baseline by remember { mutableStateOf<Float?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed, available, permitted) {
        if (resumed && available && !permitted && !permissionAsked) {
            permissionAsked = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }
    LaunchedEffect(resumed, available, permitted, busy, limits) {
        state = HeadPoseState(when {
            !permitted -> "需要相机权限"
            busy || !available -> "相机用于拍照或共享，检测暂停"
            !resumed -> "检测暂停"
            else -> "正在启动前置相机"
        })
        baseline = null
        if (!resumed || !available || !permitted || busy) return@LaunchedEffect
        while (isActive) {
            val calibration = HeadPoseCalibration()
            val main = Handler(Looper.getMainLooper())
            var accepting = true
            @Suppress("DEPRECATION")
            val rotation = (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: 0
            val camera = HeadPoseCamera(context.applicationContext, rotation) { next ->
                main.post {
                    if (accepting) {
                        state = next
                        val reading = next.reading
                        val saved = reading?.let { store.baseline(it.calibrationKey) }
                        val suitable = reading?.takeIf { it.pitch in limits.pitchMin..limits.pitchMax && it.yaw in limits.yawMin..limits.yawMax }
                        baseline = saved ?: calibration.update(suitable)
                        if (saved == null && baseline != null && reading != null) store.saveBaseline(reading.calibrationKey, baseline!!)
                    }
                }
            }
            try {
                while (!HeadPoseCameraAccess.attach(camera)) delay(100)
                camera.start()
                runInterruptible(Dispatchers.IO) { camera.completion().get() }
            } finally {
                accepting = false
                withContext(NonCancellable + Dispatchers.IO) { runCatching { camera.stop().get(5, TimeUnit.SECONDS) } }
            }
            // Retry transient camera contention automatically, without a manual switch.
            delay(3_000)
        }
    }
    val reading = state.reading?.takeIf { now - it.atMillis < 2_000 && resumed && available && !busy }
    // Preference changes also refresh a persisted baseline when returning from parent settings.
    val savedBaseline = remember(reading?.calibrationKey, calibrationRevision) { reading?.let { store.baseline(it.calibrationKey) } }
    val distance = HeadPoseMetrics.distanceRatio(savedBaseline, reading?.eyeSpan)
    val engine = remember(limits) { HeadPoseAlertEngine(limits) }
    var warnings by remember { mutableStateOf(emptySet<PoseWarning>()) }
    LaunchedEffect(reading, distance, engine) {
        warnings = engine.update(reading, distance)
    }
    val visibleWarnings = if (reading != null) warnings else emptySet()
    val voicePrompts = if (limits.sound) headPoseVoicePrompts(visibleWarnings) else emptyList()
    DisposableEffect(voicePrompts) {
        val sound = if (voicePrompts.isNotEmpty()) HeadPoseReminderSound(context.applicationContext, voicePrompts) else null
        onDispose { sound?.close() }
    }
    val pitchWarning = visibleWarnings.any { it == PoseWarning.PITCH_HIGH || it == PoseWarning.PITCH_LOW }
    val yawWarning = visibleWarnings.any { it == PoseWarning.YAW_HIGH || it == PoseWarning.YAW_LOW }
    val distanceWarning = visibleWarnings.any { it == PoseWarning.TOO_CLOSE || it == PoseWarning.TOO_FAR }
    val warningColor = Color(0xFFB3261E)
    val message = if (state.reading != null && reading == null) "等待相机画面" else state.message
    Surface(color = if (visibleWarnings.isEmpty()) Color(0xFFF1F6FC) else Color(0xFFFFE8E6), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
        FlowRow(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(30.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.widthIn(min = 140.dp)) {
                Text("头部状态", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(message, fontSize = 12.sp, color = Color(0xFF668074))
                if (!permitted) TextButton(onClick = { permission.launch(Manifest.permission.CAMERA) }, contentPadding = PaddingValues(0.dp)) { Text("允许使用相机") }
            }
            PoseMetric("俯仰角", reading?.pitch?.let { String.format(Locale.CHINA, "%+.0f°", it) } ?: "—", "正值抬头 · 负值低头", pitchWarning)
            PoseMetric("左右转角", reading?.yaw?.let { String.format(Locale.CHINA, "%+.0f°", it) } ?: "—", "相对摄像头", yawWarning)
            PoseMetric("相对距离", distance?.let { String.format(Locale.CHINA, "%.2f×", it) } ?: "—", when {
                reading == null -> "等待人脸"
                reading.eyeSpan == null -> "转头或遮挡，暂无法估算"
                savedBaseline == null -> "请保持正常写字姿势校准"
                else -> "距平板 · 估算"
            }, distanceWarning)
            Column {
                Text("基准距离 = 1.00×", fontSize = 12.sp, color = Color(0xFF697789))
                Text("画面仅在本机处理", fontSize = 12.sp, color = Color(0xFF697789))
            }
        }
        if (visibleWarnings.isNotEmpty()) Text(
            visibleWarnings.map { it.message }.distinct().joinToString("；"),
            color = warningColor, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
        )
        }
    }
}

@Composable
private fun rememberSaveablePermission() = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

@Composable
private fun PoseMetric(label: String, value: String, hint: String, warning: Boolean = false) {
    Column(Modifier.widthIn(min = 120.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF6E7783))
        Text(value, fontSize = 23.sp, color = if (warning) Color(0xFFB3261E) else Color(0xFF263E67))
        Text(hint, fontSize = 11.sp, color = Color(0xFF697789))
    }
}
