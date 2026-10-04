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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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

@Composable
internal fun HeadPosePanel(available: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { HeadPoseSettings(context) }
    val historyStore = remember(context) { HeadPoseHistoryStore(context) }
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
    val historyTracker = remember(limits) { HeadPoseHistoryTracker(limits) }
    var warnings by remember { mutableStateOf(emptySet<PoseWarning>()) }
    LaunchedEffect(reading, distance, engine) {
        warnings = engine.update(reading, distance)
        val record = historyTracker.update(reading, warnings, System.currentTimeMillis())
        if (reading != null) {
            val date = java.time.LocalDate.now()
            // Keep an episode crossing midnight on its original day, while each
            // day's overall minimum uses only that day's samples.
            val recordDate = record?.let {
                java.time.Instant.ofEpochMilli(it.remindedAtMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            }
            historyStore.observe(date, reading.pitch, record.takeIf { recordDate == date })
            if (record != null && recordDate != null && recordDate != date) {
                historyStore.observe(recordDate, null, record)
            }
        }
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
    Surface(
        color = if (visibleWarnings.isEmpty()) LocalHomeColors.current.sky else Color(0xFFFFE8E6),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.clickable(enabled = !permitted, onClickLabel = "允许使用相机") {
            permission.launch(Manifest.permission.CAMERA)
        },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PoseMetric("仰角", reading?.pitch?.let { String.format(Locale.CHINA, "%+.0f°", it) } ?: "—", pitchWarning)
            PoseMetric("左右转角", reading?.yaw?.let { String.format(Locale.CHINA, "%+.0f°", it) } ?: "—", yawWarning)
            PoseMetric("相对距离", distance?.let { String.format(Locale.CHINA, "%.2f×", it) } ?: "—", distanceWarning)
        }
    }
}

@Composable
private fun rememberSaveablePermission() = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

@Composable
private fun PoseMetric(label: String, value: String, warning: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = if (warning) Color(0xFFB3261E) else MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}
