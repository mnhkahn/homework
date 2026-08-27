package com.homeworkbuddy

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Sky = Color(0xFFEAF4FF)
private val Sun = Color(0xFFFFF3CE)
private val Leaf = Color(0xFFE6F7E9)
private val Ink = Color(0xFF263238)
private val Primary = Color(0xFF3769D9)
private val TodoSurface = Color(0xFFEAF4FF)
private val OverdueSurface = Color(0xFFFFE8E6)
private val OverdueInk = Color(0xFFB3261E)
private const val MAX_HOMEWORK_PHOTOS = 4

private data class MelodyNote(val frequency: Double, val durationMs: Int)

private fun shortFanfare(vararg notes: Double) = notes.map { MelodyNote(it, 180) }

// Original C-major "all done" melody: a rising phrase, a small answer, then a bright resolution.
private val AllDoneMelody = listOf(
    MelodyNote(392.00, 220), MelodyNote(523.25, 220), MelodyNote(659.25, 260), MelodyNote(783.99, 420),
    MelodyNote(659.25, 180), MelodyNote(698.46, 180), MelodyNote(783.99, 260), MelodyNote(1_046.50, 520),
    MelodyNote(880.00, 180), MelodyNote(783.99, 180), MelodyNote(659.25, 220), MelodyNote(783.99, 220),
    MelodyNote(1_046.50, 700),
)

private data class CelebrationVariant(
    val headline: String,
    val encouragement: String,
    val melody: List<MelodyNote>,
    val colors: List<Color>,
)

private data class CelebrationEvent(val taskTitle: String, val allTasksComplete: Boolean)
private data class PhotoLoadState(val bitmap: Bitmap? = null, val finished: Boolean = false)

private val CelebrationVariants = listOf(
    CelebrationVariant("太棒啦！", "宝贝，你完成了《%s》！", shortFanfare(523.25, 659.25, 783.99, 1_046.50), listOf(Color(0xFFFFD166), Color(0xFFFF70A6), Color(0xFF70D6FF))),
    CelebrationVariant("闯关成功！", "《%s》完成，给你一颗闪亮小星星！", shortFanfare(659.25, 783.99, 1_046.50, 783.99), listOf(Color(0xFF9BEEA0), Color(0xFFFFB86B), Color(0xFFA0C4FF))),
    CelebrationVariant("你真厉害！", "宝贝，又完成一项，今天的你超有力量！", shortFanfare(392.00, 523.25, 659.25, 783.99), listOf(Color(0xFFFF9FB2), Color(0xFFFFD166), Color(0xFFCDB4DB))),
    CelebrationVariant("耶！完成啦！", "《%s》拿下！继续保持这个好状态！", shortFanfare(523.25, 783.99, 659.25, 1_046.50), listOf(Color(0xFF70D6FF), Color(0xFFFF70A6), Color(0xFFB9FBC0))),
    CelebrationVariant("掌声送给你！", "宝贝，认真完成《%s》的你最闪耀！", shortFanfare(440.00, 554.37, 659.25, 880.00), listOf(Color(0xFFFFC857), Color(0xFFBDB2FF), Color(0xFF8EECF5))),
)

private val AllDoneCelebrationVariants = listOf(
    CelebrationVariant("全部作业完成！", "宝贝，今天的作业全都写完啦！你太了不起了！", AllDoneMelody, listOf(Color(0xFFFFD166), Color(0xFFFF70A6), Color(0xFF70D6FF), Color(0xFFB9FBC0), Color(0xFFCDB4DB))),
    CelebrationVariant("今日小冠军！", "耶！全部闯关成功，快给自己一个大大的拥抱！", AllDoneMelody, listOf(Color(0xFFFFC857), Color(0xFF8EECF5), Color(0xFFFF9FB2), Color(0xFF9BEEA0), Color(0xFFBDB2FF))),
    CelebrationVariant("星星都为你鼓掌！", "宝贝，今天的努力圆满收官，真为你骄傲！", AllDoneMelody, listOf(Color(0xFFFFB86B), Color(0xFFA0C4FF), Color(0xFFFF70A6), Color(0xFFB9FBC0), Color(0xFFFFD166))),
)

/** A short, gentle fanfare generated locally so the celebration needs no media asset or network. */
private object CelebrationSound {
    private const val sampleRate = 44_100
    private var activeTrack: AudioTrack? = null

    @Synchronized
    fun play(melody: List<MelodyNote>) {
        activeTrack?.runCatching { stop(); release() }
        val noteSamples = melody.map { (sampleRate * it.durationMs / 1_000.0).toInt() }
        val samples = ByteArray(noteSamples.sum() * 2)
        var startSample = 0
        melody.forEachIndexed { noteIndex, note ->
            val samplesForNote = noteSamples[noteIndex]
            repeat(samplesForNote) { sampleIndex ->
                val envelope = (1f - sampleIndex.toFloat() / samplesForNote) * .22f
                val phase = 2.0 * Math.PI * note.frequency * sampleIndex / sampleRate
                val wave = (sin(phase) + .25 * sin(phase * 2.0)) * envelope
                val value = (wave * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                val offset = (startSample + sampleIndex) * 2
                samples[offset] = (value.toInt() and 0xff).toByte()
                samples[offset + 1] = (value.toInt() shr 8).toByte()
            }
            startSample += samplesForNote
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        track.play()
        activeTrack = track
        Handler(Looper.getMainLooper()).postDelayed({
            if (activeTrack === track) {
                track.runCatching { stop(); release() }
                activeTrack = null
            }
        }, melody.sumOf { it.durationMs }.toLong() + 150)
    }
}

class MainActivity : ComponentActivity() {
    private val screenTimeoutHandler = Handler(Looper.getMainLooper())
    private var allowNextUserLeaveHint = false
    private val clearKeepScreenOn = Runnable {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun allowManagedActivityLaunch() {
        allowNextUserLeaveHint = true
    }

    /**
     * Study mode is deliberately easier to read than the system default: every
     * touch keeps the screen awake for another three minutes.  Once that grace
     * period expires we clear the flag, letting MIUI dim and turn the screen off
     * using its ordinary user-selected timeout instead of changing it globally.
     */
    private fun extendStudyScreenTimeout() {
        if (KioskPolicy(this).mode() != KioskMode.STUDY) {
            clearStudyScreenTimeout()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screenTimeoutHandler.removeCallbacks(clearKeepScreenOn)
        screenTimeoutHandler.postDelayed(clearKeepScreenOn, STUDY_SCREEN_AWAKE_MS)
    }

    private fun clearStudyScreenTimeout() {
        screenTimeoutHandler.removeCallbacks(clearKeepScreenOn)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private val modeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == KioskPolicy.ACTION_MODE_CHANGED) {
                // We are the foreground activity, so this can reliably invoke
                // stopLockTask right when the scheduled study period ends.
                KioskPolicy(this@MainActivity).applyForCurrentTime(this@MainActivity)
                extendStudyScreenTimeout()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HomeworkApi.saveAuthorizationResult(this, intent?.data)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        KioskPolicy(this).scheduleNextTransitions()
        setContent { HomeworkBuddyApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        HomeworkApi.saveAuthorizationResult(this, intent.data)
        ensureXiaoliConnection()
    }

    override fun onStart() {
        super.onStart()
        // A foreground Activity is the most reliable and Android-compliant
        // opportunity to revive a foreground service that HyperOS may have
        // reclaimed while the tablet was idle.
        ensureXiaoliConnection()
        ContextCompat.registerReceiver(
            this,
            modeChangeReceiver,
            IntentFilter(KioskPolicy.ACTION_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun ensureXiaoliConnection() {
        // Clearing the binding removes the config, so an explicit user
        // disconnect is never undone by an Activity lifecycle callback.
        if (XiaoliDeviceStore.config(this) != null) XiaoliConnectionService.connect(this)
    }

    override fun onStop() {
        unregisterReceiver(modeChangeReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        KioskPolicy(this).apply {
            markManagedActivityForeground()
            applyForCurrentTime(this@MainActivity)
        }
        extendStudyScreenTimeout()
    }

    override fun onPause() {
        clearStudyScreenTimeout()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (allowNextUserLeaveHint) {
            allowNextUserLeaveHint = false
            return
        }
        val policy = KioskPolicy(this)
        if (policy.isDeviceOwner && policy.mode() == KioskMode.STUDY) {
            // MIUI's tablet window menu can issue a Home-like leave even while
            // Lock Task is active. Reassert the managed activity immediately.
            Handler(Looper.getMainLooper()).postDelayed({
                policy.applyForCurrentTime(this, navigate = true)
            }, 200)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) extendStudyScreenTimeout()
        return super.dispatchTouchEvent(event)
    }

    companion object {
        private const val STUDY_SCREEN_AWAKE_MS = 3 * 60 * 1_000L
    }
}

private class DeviceAwareTakePicture(private val preferFrontCamera: Boolean) : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, input)
            clipData = ClipData.newRawUri("作业照片", input)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

            // Android does not define a guaranteed camera-facing extra. These are the
            // conventions recognized by common system camera apps, including Xiaomi's.
            val facing = if (preferFrontCamera) 1 else 0
            putExtra("android.intent.extras.CAMERA_FACING", facing)
            putExtra("android.intent.extra.CAMERA_FACING", facing)
            putExtra("android.intent.extra.USE_FRONT_CAMERA", preferFrontCamera)
            putExtra("android.intent.extra.USE_REAR_CAMERA", !preferFrontCamera)
            putExtra("com.google.assistant.extra.USE_FRONT_CAMERA", preferFrontCamera)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
}

@Composable
private fun HomeworkBuddyApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? Activity
    val api = remember(context) { HomeworkApi(context) }
    val kioskPolicy = remember(context) { KioskPolicy(context) }
    val pendingStore = remember(context) { PendingSubmissionStore(context) }
    val remoteNoticeStore = remember(context) { RemoteNoticeStore(context) }
    val captureStatusStore = remember(context) { CaptureStatusStore(context) }
    val studyActivityStore = remember(context) { StudyActivityStore(context) }
    val pianoPracticeStore = remember(context) { PianoPracticeStore(context) }
    val taskCache = remember(context) { HomeworkCacheStore(context) }
    val scope = rememberCoroutineScope()
    var childName by remember { mutableStateOf(context.getSharedPreferences("profile", Context.MODE_PRIVATE).getString("child_name", "") ?: "") }
    // The home screen must only show work returned by the family service.  Keeping
    // preview tasks here made a fictional completed homework item survive a sync
    // with an otherwise empty board.
    var tasks by remember { mutableStateOf(taskCache.todayTasks()) }
    var weekTasks by remember { mutableStateOf(taskCache.weekTasks()) }
    var selectedId by remember { mutableStateOf("") }
    var remainingSeconds by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(childName.isBlank()) }
    var connected by remember { mutableStateOf(api.isConnected) }
    var showConnectionDialog by remember { mutableStateOf(childName.isNotBlank() && !api.isConnected) }
    var authorizationStarted by remember { mutableStateOf(api.hasAuthorization) }
    var trelloBoards by remember { mutableStateOf<List<TrelloOption>>(emptyList()) }
    var selectedBoardId by remember { mutableStateOf("") }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var authorizationExpired by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    var refreshRequest by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var showCameraConfirm by remember { mutableStateOf(false) }
    var capturePhoto by remember { mutableStateOf<Uri?>(null) }
    var pendingPhotos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var submittingTaskId by remember { mutableStateOf<String?>(null) }
    var retryingPendingSubmissions by remember { mutableStateOf(false) }
    var celebration by remember { mutableStateOf<CelebrationEvent?>(null) }
    var kioskMode by remember { mutableStateOf(kioskPolicy.mode()) }
    var remoteNotice by remember { mutableStateOf(remoteNoticeStore.current()) }
    var captureStatus by remember { mutableStateOf(captureStatusStore.current()) }
    var studyActivity by remember { mutableStateOf(studyActivityStore.today()) }
    var xiaoliConnection by remember { mutableStateOf(XiaoliConnectionState.snapshot()) }
    // Keep the header useful immediately; a network response only replaces this
    // default when it contains a non-blank slogan.
    var slogan by remember { mutableStateOf(DEFAULT_HOME_SLOGAN) }

    LaunchedEffect(captureStatusStore) {
        captureStatusStore.clearInterruptedCapture()
        captureStatus = captureStatusStore.current()
    }

    LaunchedEffect(Unit) {
        runCatching { SloganApi.fetch() }.getOrNull()?.let { slogan = it }
    }

    DisposableEffect(remoteNoticeStore) {
        val listener = remoteNoticeStore.addChangeListener { remoteNotice = remoteNoticeStore.current() }
        onDispose { remoteNoticeStore.removeChangeListener(listener) }
    }

    DisposableEffect(captureStatusStore) {
        val mainHandler = Handler(Looper.getMainLooper())
        val listener = captureStatusStore.addChangeListener {
            mainHandler.post { captureStatus = captureStatusStore.current() }
        }
        onDispose { captureStatusStore.removeChangeListener(listener) }
    }

    DisposableEffect(studyActivityStore) {
        val mainHandler = Handler(Looper.getMainLooper())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            mainHandler.post { studyActivity = studyActivityStore.today() }
        }
        studyActivityStore.addChangeListener(listener)
        onDispose { studyActivityStore.removeChangeListener(listener) }
    }

    DisposableEffect(Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        val listener: (XiaoliConnectionSnapshot) -> Unit = { snapshot ->
            mainHandler.post { xiaoliConnection = snapshot }
        }
        XiaoliConnectionState.addChangeListener(listener)
        onDispose { XiaoliConnectionState.removeChangeListener(listener) }
    }

    LaunchedEffect(captureStatus?.atMillis, captureStatus?.active) {
        val status = captureStatus ?: return@LaunchedEffect
        delay((status.atMillis + 15 * 60 * 1_000L - System.currentTimeMillis()).coerceAtLeast(1_000L))
        captureStatus = captureStatusStore.current()
    }

    // A message is intentionally only for the current day. Recheck at local midnight
    // even if the home screen stays open all night.
    LaunchedEffect(remoteNoticeStore) {
        while (true) {
            val now = java.time.ZonedDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            delay(java.time.Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L))
            remoteNotice = remoteNoticeStore.current()
            studyActivity = studyActivityStore.today()
        }
    }

    // Exact alarms handle the normal case. This keeps an already-open home
    // screen accurate too, including on devices that decline exact alarms.
    LaunchedEffect(kioskPolicy) {
        while (true) {
            val next = kioskPolicy.mode()
            if (next != kioskMode) {
                kioskMode = next
                // Exact alarms can be delayed by MIUI. This foreground fallback
                // must therefore both release Lock Task *and* leave the homework
                // activity when the scheduled study period ends.
                kioskPolicy.applyForCurrentTime(activity, navigate = true)
            }
            delay(30_000)
        }
    }

    LaunchedEffect(tasks, selectedId, remainingSeconds, running) {
        HomeworkStatusStore(context).save(tasks, selectedId, remainingSeconds, running)
    }

    var weekMarks by remember { mutableStateOf(FlowerCalendar(context).currentWeek()) }
    var historyRevision by remember { mutableIntStateOf(0) }
    var historicalWeekLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        CompletionHistoryStore(context).removeLegacyConfirmedMarks()
        historyRevision++
    }

    // The completion history is written by the submit/refresh paths before
    // tasks changes, so recomputing here always sees the latest records.
    LaunchedEffect(tasks, historyRevision) {
        weekMarks = FlowerCalendar(context).currentWeek()
    }

    // An expired Trello token (HTTP 401) fails every request. Guide the parent
    // straight back into the authorization dialog instead of only showing a
    // bottom-corner sync error nobody acts on.
    fun reportSyncError(error: Throwable, fallback: String) {
        if (error is AuthorizationExpiredException) {
            api.clearConnection()
            connected = false
            authorizationStarted = false
            authorizationExpired = true
            trelloBoards = emptyList(); selectedBoardId = ""
            showConnectionDialog = true
        }
        connectionError = error.message ?: fallback
    }

    fun advanceAfterCompletion(taskId: String, photoPath: String? = null) {
        val currentIndex = tasks.indexOfFirst { it.id == taskId }
        val updated = tasks.map { task ->
            if (task.id == taskId) task.copy(status = TaskStatus.COMPLETED, photoPath = photoPath ?: task.photoPath) else task
        }
        tasks = updated
        taskCache.saveToday(updated)
        val next = if (currentIndex >= 0) {
            updated.drop(currentIndex + 1).firstOrNull { it.status != TaskStatus.COMPLETED }
                ?: updated.take(currentIndex).firstOrNull { it.status != TaskStatus.COMPLETED }
        } else updated.firstOrNull { it.status != TaskStatus.COMPLETED }
        selectedId = next?.id.orEmpty()
        remainingSeconds = next?.estimatedMinutes?.times(60) ?: 0
        running = false
    }

    fun retryPendingSubmissions() {
        if (!connected || retryingPendingSubmissions) return
        retryingPendingSubmissions = true
        // This deliberately uses the screen scope instead of the refresh
        // LaunchedEffect. Starting another refresh must never cancel an upload.
        scope.launch {
            var uploadedAnything = false
            try {
                pendingStore.items().forEach { pending ->
                    if (pending.photoPaths.isEmpty()) {
                        Log.w("HomeworkSubmit", "drop_no_photo_retry task=${pending.taskId} submission=${pending.submissionId}")
                        pendingStore.remove(pending.taskId)
                    } else {
                        Log.i("HomeworkSubmit", "retry task=${pending.taskId} photos=${pending.photoPaths.size} submission=${pending.submissionId}")
                        runCatching {
                            api.submit(
                                pending.taskId,
                                pending.photoPaths.map(android.net.Uri::parse),
                                pending.isOvertime,
                                pending.submissionId,
                            )
                        }.onSuccess {
                            pendingStore.remove(pending.taskId)
                            uploadedAnything = true
                        }
                    }
                }
            } finally {
                retryingPendingSubmissions = false
            }
            if (uploadedAnything) refreshRequest++
        }
    }

    val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
    val takePhoto = rememberLauncherForActivityResult(remember(isTablet) { DeviceAwareTakePicture(isTablet) }) { captured ->
        val photo = capturePhoto
        Log.i("HomeworkSubmit", "camera_result captured=$captured task=$selectedId uri=${photo != null}")
        if (captured && photo != null) {
            pendingPhotos = (pendingPhotos + photo).take(MAX_HOMEWORK_PHOTOS)
            showCameraConfirm = true
        }
        capturePhoto = null
        KioskPolicy(context).revokeCameraCaptureAccess()
    }
    val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val photo = capturePhoto
        if (granted && photo != null) {
            KioskPolicy(context).allowCameraForCapture()
            (activity as? MainActivity)?.allowManagedActivityLaunch()
            runCatching { takePhoto.launch(photo) }
                .onFailure {
                    capturePhoto = null
                    KioskPolicy(context).revokeCameraCaptureAccess()
                    connectionError = "无法打开相机，请检查系统相机是否可用。"
                }
        } else {
            capturePhoto = null
            connectionError = "需要相机权限才能拍照记录作业。"
        }
    }
    val requestNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    foreground = true
                    captureStatus = captureStatusStore.current()
                    refreshRequest++
                }
                Lifecycle.Event.ON_STOP -> foreground = false
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    // The calendar's future/current schedules come from the compact 待完成
    // query. Do not make them wait for the much slower completed-card audit.
    LaunchedEffect(connected, foreground, refreshRequest) {
        if (!connected || !foreground) return@LaunchedEffect
        runCatching { api.weekScheduledTasks() }.onSuccess { scheduledCards ->
            weekTasks = scheduledCards
            taskCache.saveWeek(scheduledCards)
        }.onFailure { error ->
            if (error !is kotlinx.coroutines.CancellationException) {
                reportSyncError(error, "拉取本周作业安排失败")
            }
        }
    }

    // Historical import is independent from the live queue.  A temporary
    // failure while reading today's 待完成 list must never prevent 8/6 and
    // other completed days from being recovered from Trello.
    LaunchedEffect(connected, foreground, refreshRequest) {
        if (!connected || !foreground || historicalWeekLoaded) return@LaunchedEffect
        runCatching { api.weekTasks() }.onSuccess { weeklyCards ->
            val history = CompletionHistoryStore(context)
            val completedCards = weeklyCards.filter { it.status == TaskStatus.COMPLETED && it.completedAtEpochSeconds != null }
            val weekStart = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
            val weekDays = (0L..6L).map(weekStart::plusDays)
            val ids = completedCards.mapTo(mutableSetOf()) { it.id }
            weekDays.forEach { history.removeTasks(it, ids) }
            completedCards.groupBy { card ->
                Instant.ofEpochSecond(requireNotNull(card.completedAtEpochSeconds))
                    .atZone(ZoneId.systemDefault()).toLocalDate()
            }.forEach { (date, dailyCards) ->
                history.noteTasks(dailyCards, date)
                val records = history.day(date)
                if (records.isNotEmpty() && records.all { it.completedAtEpochSeconds != null && it.completedAtEpochSeconds <= it.deadlineEpochSeconds }) {
                    history.correctFinalMark(date, DayMark.FLOWER)
                }
            }
            historicalWeekLoaded = true
            historyRevision++
        }.onFailure { error ->
            // Leaving composition (sleeping, rotating, or opening another
            // screen) cancels this effect. It is never a child-facing error.
            if (error is kotlinx.coroutines.CancellationException || error.message == "The coroutine scope left composition.") return@onFailure
            Log.e("HomeworkHistory", "weekly import failed", error)
            reportSyncError(error, "拉取本周完成记录失败")
        }
    }

    LaunchedEffect(connected, foreground, refreshRequest) {
        if (!connected || !foreground) return@LaunchedEffect
        while (connected && foreground) {
            refreshing = true
            runCatching { api.activeTasks() }.onSuccess { remote ->
                val local = tasks.associateBy { it.id }
                val completionHistory = CompletionHistoryStore(context)
                val today = LocalDate.now()
                val merged = remote.map { fresh ->
                    local[fresh.id]?.let { old ->
                        if (fresh.status == TaskStatus.COMPLETED) {
                            // A task turning COMPLETED here means an offline
                            // retry finished the upload; the completion was not
                            // recorded on this device yet.
                            if (old.status != TaskStatus.COMPLETED) {
                                completionHistory.recordCompletion(fresh.id, System.currentTimeMillis() / 1_000, today.atTime(fresh.deadline).atZone(ZoneId.systemDefault()).toEpochSecond())
                            }
                            fresh.copy(photoPath = old.photoPath)
                        }
                        else fresh.copy(status = if (old.status == TaskStatus.RUNNING) TaskStatus.RUNNING else fresh.status, photoPath = old.photoPath)
                    } ?: fresh
                }
                tasks = merged
                taskCache.saveToday(merged)
                // Replace only today's live queue; settled day marks live in
                // their own ledger and are not affected by this cleanup.
                completionHistory.clearDay(today)
                completionHistory.noteTasks(merged, today)
                val selected = merged.firstOrNull { it.id == selectedId && it.status != TaskStatus.COMPLETED }
                    ?: merged.firstOrNull { it.status != TaskStatus.COMPLETED }
                if (selected == null) {
                    selectedId = ""; remainingSeconds = 0; running = false
                } else if (selected.id != selectedId) {
                    selectedId = selected.id; remainingSeconds = selected.estimatedMinutes * 60; running = false
                }
                connectionError = null
                HomeworkReminderScheduler.rescheduleFromTasks(context, merged)
            }.onFailure { error ->
                // LaunchedEffect is cancelled when this screen leaves composition (for
                // example while the tablet sleeps). That is normal lifecycle behavior,
                // not a sync failure, so do not surface it as an error to the child.
                if (error is kotlinx.coroutines.CancellationException) throw error
                reportSyncError(error, "同步当天作业失败")
            }
            retryPendingSubmissions()
            refreshing = false
            delay(60_000)
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Primary, background = Color(0xFFFFFBFF), onBackground = Ink)) {
        if (showNameDialog) NameDialog(
            onSaved = { name -> context.getSharedPreferences("profile", Context.MODE_PRIVATE).edit().putString("child_name", name).apply(); childName = name; showNameDialog = false; if (!connected) showConnectionDialog = true }
        )
        if (showConnectionDialog) ConnectionDialog(
            expired = authorizationExpired,
            authorizationStarted = authorizationStarted,
            boards = trelloBoards,
            selectedBoardId = selectedBoardId,
            error = connectionError,
            onConnect = {
                connectionError = null
                trelloBoards = emptyList(); selectedBoardId = ""
                authorizationStarted = true
                authorizationExpired = false
                // The browser is outside the Lock Task allowlist. Temporarily
                // open the device so the parent can reach Trello consent.
                if (kioskPolicy.isDeviceOwner && kioskPolicy.mode() == KioskMode.STUDY) {
                    kioskPolicy.pause(15, activity)
                    connectionError = "已临时开放 15 分钟，请在浏览器中完成授权。"
                }
                (activity as? MainActivity)?.allowManagedActivityLaunch()
                api.openAuthorization()
            },
            onCheck = {
                if (!api.hasAuthorization) {
                    connectionError = "还没有收到 Trello 授权，请在浏览器中点击允许后返回应用。"
                } else scope.launch {
                    runCatching { api.boards() }.onSuccess { values ->
                        trelloBoards = values; selectedBoardId = values.firstOrNull()?.id.orEmpty()
                        connectionError = if (values.isEmpty()) "这个 Trello 账户还没有看板，请先创建看板。" else null
                    }.onFailure { connectionError = it.message ?: "无法读取看板" }
                }
            },
            onSelectBoard = { selectedBoardId = it },
            onFinishSetup = {
                scope.launch {
                    runCatching { api.initialize(selectedBoardId) }
                        .onSuccess { connected = true; showConnectionDialog = false; authorizationExpired = false }
                        .onFailure { connectionError = it.message ?: "保存看板设置失败" }
                }
            },
        )
        val selected = tasks.firstOrNull { it.id == selectedId }
        val selectedIsPiano = selected?.title?.contains("钢琴") == true
        var pianoPractice by remember(selected?.id) {
            mutableStateOf(selected?.takeIf { it.title.contains("钢琴") }?.let { pianoPracticeStore.status(it.id) })
        }
        LaunchedEffect(selected?.id, selectedIsPiano) {
            val pianoTaskId = selected?.takeIf { it.title.contains("钢琴") }?.id ?: return@LaunchedEffect
            while (true) {
                pianoPractice = pianoPracticeStore.status(pianoTaskId)
                delay(1_000)
            }
        }
        HomeworkHome(
            slogan = slogan,
            tasks = tasks,
            selected = selected,
            remainingSeconds = remainingSeconds,
            running = running,
            pianoPractice = pianoPractice,
            submitting = selected?.id == submittingTaskId,
            refreshing = refreshing,
            weekMarks = weekMarks,
            weekTasks = weekTasks,
            captureStatus = captureStatus,
            studyActivity = studyActivity,
            xiaoliConnection = xiaoliConnection,
            studyLocked = kioskPolicy.isDeviceOwner && kioskMode == KioskMode.STUDY,
            hasStudyApps = kioskPolicy.studyPackages.isNotEmpty(),
            remoteNotice = remoteNotice,
            syncError = if (connected) connectionError else null,
            onRefresh = { refreshRequest++ },
            onParent = {
                (activity as? MainActivity)?.allowManagedActivityLaunch()
                context.startActivity(Intent(context, KioskSettingsActivity::class.java))
            },
            onStudyApps = {
                (activity as? MainActivity)?.allowManagedActivityLaunch()
                kioskPolicy.openStudyLauncher()
            },
            onSelect = { task -> selectedId = task.id; remainingSeconds = task.estimatedMinutes * 60; running = false },
            onStart = { running = true; tasks = tasks.map { if (it.id == selectedId) it.copy(status = TaskStatus.RUNNING) else it } },
            onPianoRecord = {
                selected?.takeIf { it.title.contains("钢琴") }?.let { pianoPractice = pianoPracticeStore.record(it.id) }
            },
            onFinish = {
                selected?.let { current ->
                    Log.i("HomeworkSubmit", "photo_mode task=${current.id}")
                    pendingStore.remove(current.id)
                    running = false
                    pendingPhotos = emptyList()
                    val file = File(context.cacheDir, "photos/${current.id}-${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }
                    capturePhoto = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        KioskPolicy(context).allowCameraForCapture()
                        (activity as? MainActivity)?.allowManagedActivityLaunch()
                        runCatching { takePhoto.launch(capturePhoto!!) }
                            .onFailure {
                                capturePhoto = null
                                KioskPolicy(context).revokeCameraCaptureAccess()
                                connectionError = "无法打开相机，请检查系统相机是否可用。"
                            }
                    } else {
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                    }
                }
            },
            onSubmit = {
                val photos = pendingPhotos
                val current = selected
                if (photos.isEmpty() || !connected || current == null) {
                    connectionError = "请先关联 Trello 后再提交作业。"
                } else if (submittingTaskId == null) {
                    submittingTaskId = current.id
                    val submissionId = java.util.UUID.randomUUID().toString()
                    scope.launch {
                        runCatching { api.submit(current.id, photos, current.status == TaskStatus.OVERTIME, submissionId) }
                            .onSuccess {
                                CompletionHistoryStore(context).recordCompletion(current.id, System.currentTimeMillis() / 1_000, LocalDate.now().atTime(current.deadline).atZone(ZoneId.systemDefault()).toEpochSecond())
                                advanceAfterCompletion(current.id, photos.first().toString())
                                celebration = CelebrationEvent(current.title, tasks.all { it.status == TaskStatus.COMPLETED })
                                refreshRequest++
                                showCameraConfirm = false; pendingPhotos = emptyList()
                            }
                            .onFailure { error ->
                                pendingStore.add(PendingSubmission(current.id, photos.map(Uri::toString), current.status == TaskStatus.OVERTIME, submissionId))
                                showCameraConfirm = false; pendingPhotos = emptyList()
                                if (error is AuthorizationExpiredException) reportSyncError(error, "")
                                else connectionError = "照片和完成状态尚未同步到 Trello，任务仍是待完成；联网后会自动重试。"
                            }
                        submittingTaskId = null
                    }
                }
            },
            showCameraConfirm = showCameraConfirm,
            photoCount = pendingPhotos.size,
            onAddPhoto = {
                selected?.takeIf { pendingPhotos.size < MAX_HOMEWORK_PHOTOS }?.let { current ->
                    val file = File(context.cacheDir, "photos/${current.id}-${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }
                    capturePhoto = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        KioskPolicy(context).allowCameraForCapture()
                        (activity as? MainActivity)?.allowManagedActivityLaunch()
                        takePhoto.launch(capturePhoto!!)
                    }
                    else requestCameraPermission.launch(Manifest.permission.CAMERA)
                }
            },
            onRetake = { showCameraConfirm = false; capturePhoto = null; pendingPhotos = emptyList() },
        )
        celebration?.let { event -> CelebrationDialog(event.taskTitle, event.allTasksComplete) { celebration = null } }
    }
}

@Composable
private fun CelebrationDialog(taskTitle: String, allTasksComplete: Boolean, onDismiss: () -> Unit) {
    val celebration = remember(allTasksComplete) { (if (allTasksComplete) AllDoneCelebrationVariants else CelebrationVariants).random() }
    LaunchedEffect(celebration) { CelebrationSound.play(celebration.melody) }
    val animation = rememberInfiniteTransition(label = "fireworks")
    val progress by animation.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_500, easing = LinearEasing), RepeatMode.Restart),
        label = "firework progress",
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(if (allTasksComplete) 36.dp else 30.dp), color = Color(0xFF18264B), contentColor = Color.White, tonalElevation = 8.dp) {
            Box(
                modifier = Modifier.widthIn(min = if (allTasksComplete) 360.dp else 300.dp, max = if (allTasksComplete) 660.dp else 520.dp).padding(horizontal = if (allTasksComplete) 40.dp else 28.dp, vertical = if (allTasksComplete) 42.dp else 30.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val positions = if (allTasksComplete) listOf(.12f to .23f, .38f to .13f, .72f to .2f, .88f to .5f, .24f to .67f) else listOf(.2f to .25f, .78f to .23f, .5f to .6f)
                    val bursts = positions.mapIndexed { index, (x, y) -> Triple(x, y, celebration.colors[index % celebration.colors.size]) }
                    bursts.forEachIndexed { burstIndex, (x, y, color) ->
                        val phase = (progress + burstIndex / 3f) % 1f
                        val centerX = size.width * x
                        val centerY = size.height * y
                        val radius = size.minDimension * (.05f + phase * .25f)
                        repeat(12) { index ->
                            val angle = index * (Math.PI * 2 / 12)
                            val start = radius * .25f
                            drawLine(
                                color = color.copy(alpha = 1f - phase),
                                start = androidx.compose.ui.geometry.Offset(centerX + cos(angle).toFloat() * start, centerY + sin(angle).toFloat() * start),
                                end = androidx.compose.ui.geometry.Offset(centerX + cos(angle).toFloat() * radius, centerY + sin(angle).toFloat() * radius),
                                strokeWidth = 5f,
                            )
                        }
                        drawCircle(color.copy(alpha = 1f - phase), radius = radius * .16f, center = androidx.compose.ui.geometry.Offset(centerX, centerY), style = Stroke(width = 3f))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (allTasksComplete) "🏆" else "🎉", fontSize = if (allTasksComplete) 92.sp else 68.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(celebration.headline, fontSize = if (allTasksComplete) 38.sp else 32.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(celebration.encouragement.format(taskTitle), fontSize = 18.sp, color = Color(0xFFE0E9FF), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC857), contentColor = Ink)) { Text(if (allTasksComplete) "今天真棒！" else "继续加油") }
                }
            }
        }
    }
}

@Composable private fun ConnectionDialog(
    expired: Boolean, authorizationStarted: Boolean, boards: List<TrelloOption>, selectedBoardId: String, error: String?,
    onConnect: () -> Unit, onCheck: () -> Unit, onSelectBoard: (String) -> Unit,
    onFinishSetup: () -> Unit,
) {
    val selectingBoard = boards.isNotEmpty()
    AlertDialog(onDismissRequest = {}, icon = { Text("🔗", fontSize = 38.sp) }, title = { Text(if (expired) "Trello 授权已过期" else "请家长关联 Trello") }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
            Text(when { expired && !selectingBoard -> "授权已过期，请家长重新授权 Trello，之后重新选择看板。授权 token 仅加密保存在平板。"; !authorizationStarted -> "家长授权后，这台平板会直接读取 Trello 作业。授权 token 仅加密保存在平板。"; selectingBoard -> "请选择这台平板使用的看板。系统固定使用“待完成”和“已完成”两个列表，缺少时会自动创建。"; else -> "浏览器只负责 Trello 授权；授权后会自动回到这里，再点击继续。" })
            if (selectingBoard) boards.forEach { item -> Row(Modifier.fillMaxWidth().clickable { onSelectBoard(item.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedBoardId == item.id, { onSelectBoard(item.id) }); Text(item.name) } }
            if (error != null) { Spacer(Modifier.height(10.dp)); Text(error, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(
        enabled = !selectingBoard || selectedBoardId.isNotEmpty(),
        onClick = when { !authorizationStarted -> onConnect; selectingBoard -> onFinishSetup; else -> onCheck }
    ) { Text(when { !authorizationStarted -> "授权 Trello"; selectingBoard -> "绑定看板"; else -> "继续" }) } },
        dismissButton = { if (authorizationStarted && !selectingBoard) TextButton(onClick = onConnect) { Text("重新授权") } })
}

@Composable private fun NameDialog(onSaved: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = {}, icon = { Text("👋", fontSize = 42.sp) }, title = { Text("你好！我怎么称呼你？") }, text = { OutlinedTextField(value = draft, onValueChange = { draft = it.take(12) }, singleLine = true, label = { Text("你的名字") }) }, confirmButton = { Button(enabled = draft.isNotBlank(), onClick = { onSaved(draft.trim()) }) { Text("开始今天的作业") } })
}

@Composable
private fun HomeworkHome(slogan: String, tasks: List<HomeworkTask>, selected: HomeworkTask?, remainingSeconds: Int, running: Boolean, pianoPractice: PianoPracticeStatus?, submitting: Boolean, refreshing: Boolean, weekMarks: List<Pair<LocalDate, DayMark>>, weekTasks: List<HomeworkTask>, captureStatus: CaptureStatus?, studyActivity: StudyActivity, xiaoliConnection: XiaoliConnectionSnapshot, studyLocked: Boolean, hasStudyApps: Boolean, remoteNotice: RemoteNotice?, syncError: String?, onRefresh: () -> Unit, onParent: () -> Unit, onStudyApps: () -> Unit, onSelect: (HomeworkTask) -> Unit, onStart: () -> Unit, onPianoRecord: () -> Unit, onFinish: () -> Unit, onSubmit: () -> Unit, showCameraConfirm: Boolean, photoCount: Int, onAddPhoto: () -> Unit, onRetake: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val complete = tasks.count { it.status == TaskStatus.COMPLETED }
    val todayEstimatedSeconds = tasks.sumOf { it.estimatedMinutes.coerceAtLeast(0) * 60 }
    val completedEstimatedSeconds = tasks.filter { it.status == TaskStatus.COMPLETED }
        .sumOf { it.estimatedMinutes.coerceAtLeast(0) * 60 }
    val activeEstimatedSeconds = selected?.estimatedMinutes?.coerceAtLeast(0)?.times(60) ?: 0
    val activeElapsedSeconds = if (running && selected?.status != TaskStatus.COMPLETED) {
        (activeEstimatedSeconds - remainingSeconds).coerceIn(0, activeEstimatedSeconds)
    } else 0
    val todayElapsedSeconds = (completedEstimatedSeconds + activeElapsedSeconds).coerceAtMost(todayEstimatedSeconds)
    val waiting = tasks.filter { it.status != TaskStatus.COMPLETED && it.id != selected?.id }
    val completedTasks = tasks.filter { it.status == TaskStatus.COMPLETED }
    val overdue = tasks.count { it.status == TaskStatus.OVERTIME }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(slogan, refreshing, captureStatus, studyActivity, xiaoliConnection, studyLocked, hasStudyApps, onRefresh, onParent, onStudyApps)
            if (remoteNotice != null) {
                Spacer(Modifier.height(14.dp))
                RemoteNoticeCard(remoteNotice)
            }
            Spacer(Modifier.height(14.dp))
            WeekCalendar(
                weekMarks,
                selectedDate = selectedDate,
                todayElapsedSeconds = todayElapsedSeconds,
                todayEstimatedSeconds = todayEstimatedSeconds,
                onSelectDate = { selectedDate = it },
            )
            Spacer(Modifier.height(20.dp))
            if (selectedDate != LocalDate.now()) {
                CalendarDayContent(
                    modifier = Modifier.fillMaxSize(),
                    date = selectedDate,
                    tasks = weekTasks.filter { it.dueDate == selectedDate },
                    records = CompletionHistoryStore(context).day(selectedDate),
                    mark = FlowerCalendar(context).markFor(selectedDate),
                )
            } else if (selected == null && tasks.isEmpty()) {
                EmptyTaskState(Modifier.fillMaxSize())
            } else if (this@BoxWithConstraints.maxWidth >= 700.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                if (selected != null) CurrentTask(Modifier.weight(1.45f).fillMaxHeight(), selected, running, pianoPractice, submitting, onStart, onPianoRecord, onFinish)
                else AllDoneState(Modifier.weight(1.45f).fillMaxHeight())
                TaskQueue(Modifier.weight(.8f).fillMaxHeight(), waiting, completedTasks, onSelect)
            } else {
                if (selected != null) CurrentTask(Modifier.fillMaxWidth(), selected, running, pianoPractice, submitting, onStart, onPianoRecord, onFinish)
                else AllDoneState(Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp)); TaskQueue(Modifier.fillMaxWidth(), waiting, completedTasks, onSelect)
            }
        }
        if (syncError != null) Surface(
            modifier = Modifier.align(Alignment.BottomEnd).widthIn(max = 420.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            tonalElevation = 5.dp,
            shadowElevation = 5.dp
        ) {
            Text(syncError, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (showCameraConfirm) AlertDialog(onDismissRequest = { if (!submitting) onRetake() }, icon = { Icon(Icons.Outlined.CameraAlt, null) }, title = { Text("上传作业照片") }, text = { Column { Text(if (submitting) "正在上传照片并完成任务…" else "已拍 $photoCount 张，将作为附件上传到 Trello。") ; if (!submitting && photoCount < MAX_HOMEWORK_PHOTOS) { Spacer(Modifier.height(12.dp)); OutlinedButton(onClick = onAddPhoto) { Icon(Icons.Outlined.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("再拍一张（最多 $MAX_HOMEWORK_PHOTOS 张）") } } } }, confirmButton = { Button(onClick = onSubmit, enabled = !submitting) { if (submitting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("正在上传…") } else Text("上传并完成") } }, dismissButton = { TextButton(onClick = onRetake, enabled = !submitting) { Text("取消") } })
}

@Composable
private fun RemoteNoticeCard(notice: RemoteNotice) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFE8F0FF),
        contentColor = Ink,
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
            Text("📩", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(notice.title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                if (notice.body.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(notice.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun WeekCalendar(week: List<Pair<LocalDate, DayMark>>, selectedDate: LocalDate, todayElapsedSeconds: Int, todayEstimatedSeconds: Int, onSelectDate: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Sky).padding(horizontal = 8.dp, vertical = 8.dp)) {
        week.forEach { (date, mark) ->
            val isToday = date == today
            val isSelected = date == selectedDate
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).then(if (isSelected) Modifier.background(Primary) else Modifier).clickable { onSelectDate(date) }.padding(vertical = 7.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("周${dayNames[date.dayOfWeek.value - 1]}", fontSize = 11.sp, color = if (isSelected) Color.White else if (isToday) Primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    Text("${date.dayOfMonth}日", fontSize = 13.sp, color = if (isSelected) Color.White else Ink, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(3.dp))
                when (mark) {
                    DayMark.FLOWER -> Text("🌸", fontSize = 28.sp)
                    DayMark.BLACK -> Text("🖤", fontSize = 23.sp)
                    else -> if (isToday && todayEstimatedSeconds > 0) {
                        CalendarProgressRing(
                            elapsedSeconds = todayElapsedSeconds,
                            estimatedSeconds = todayEstimatedSeconds,
                            color = if (isSelected) Color.White else Primary,
                            track = if (isSelected) Color.White.copy(alpha = .28f) else Color(0xFFBED0E8),
                        )
                    } else Text("·", fontSize = 26.sp, color = if (isSelected) Color.White else Color(0xFF8BA0AA))
                }
            }
        }
    }
}

@Composable private fun CalendarProgressRing(elapsedSeconds: Int, estimatedSeconds: Int, color: Color, track: Color) {
    val progress = (elapsedSeconds.toFloat() / estimatedSeconds.coerceAtLeast(1)).coerceIn(0f, 1f)
    Canvas(Modifier.size(27.dp)) {
        val stroke = 3.dp.toPx()
        drawArc(track, -90f, 360f, false, style = Stroke(stroke))
        drawArc(color, -90f, progress * 360f, false, style = Stroke(stroke))
    }
}

@Composable
private fun CalendarDayContent(modifier: Modifier, date: LocalDate, tasks: List<HomeworkTask>, records: List<CompletionRecord>, mark: DayMark) {
    if (tasks.isEmpty()) {
        HistoryDayContent(modifier, date, records, mark)
        return
    }
    val doneCount = tasks.count { it.status == TaskStatus.COMPLETED }
    BoxWithConstraints(modifier) {
        if (maxWidth >= 700.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.weight(1.45f).fillMaxHeight().clip(RoundedCornerShape(26.dp)).background(Sky).padding(28.dp)) {
                Text(date.format(DateTimeFormatter.ofPattern("M月d日")) + " 的作业", fontSize = 30.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Text(if (doneCount == 0) "已从 Trello 同步 ${tasks.size} 项安排" else "已完成 $doneCount / ${tasks.size} 项", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("📚", fontSize = 72.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.weight(1f))
            }
            ScheduledTaskList(Modifier.weight(.8f).fillMaxHeight(), tasks)
        } else Column(Modifier.fillMaxSize()) {
            Text(date.format(DateTimeFormatter.ofPattern("M月d日")) + " 的作业 · ${tasks.size} 项", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            ScheduledTaskList(Modifier.weight(1f), tasks)
        }
    }
}

@Composable
private fun ScheduledTaskList(modifier: Modifier, tasks: List<HomeworkTask>) {
    Column(modifier) {
        Text("作业清单 · ${tasks.size} 项", fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(tasks, key = { it.id }) { task ->
                val completed = task.status == TaskStatus.COMPLETED
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (completed) Leaf else TodoSurface).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(task.title, modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TaskStatusPill(task.status)
                    }
                    Text(if (completed) "已完成" else "截止 ${task.deadline.format(DateTimeFormatter.ofPattern("HH:mm"))}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable private fun HistoryDayContent(modifier: Modifier, date: LocalDate, records: List<CompletionRecord>, mark: DayMark) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val doneCount = records.count { it.completedAtEpochSeconds != null }
    BoxWithConstraints(modifier) {
        if (maxWidth >= 700.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.weight(1.45f).fillMaxHeight().clip(RoundedCornerShape(26.dp)).background(if (mark == DayMark.FLOWER) Leaf else Sky).padding(28.dp)) {
                Text(date.format(DateTimeFormatter.ofPattern("M月d日")) + " 的作业", fontSize = 30.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Text(when { mark == DayMark.FLOWER -> "全部完成 · 奖励已记录"; records.isEmpty() -> "这一天还没有作业记录"; else -> "已完成 $doneCount / ${records.size} 项" }, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(if (mark == DayMark.FLOWER) "🌸" else if (mark == DayMark.BLACK) "🖤" else "📚", fontSize = 72.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(10.dp))
                when (mark) {
                    DayMark.FLOWER -> Text("太棒了！这一天的作业全部完成，继续保持！", modifier = Modifier.align(Alignment.CenterHorizontally), color = Color(0xFF24733A), fontWeight = FontWeight.Medium)
                    DayMark.BLACK -> Text("还没完成：${records.filter { it.completedAtEpochSeconds == null && it.title.isNotBlank() }.joinToString("、") { it.title }}", color = OverdueInk, fontWeight = FontWeight.Medium)
                    else -> Unit
                }
                Spacer(Modifier.weight(1f))
            }
            HistoryTaskList(Modifier.weight(.8f).fillMaxHeight(), records, context)
        } else Column(Modifier.fillMaxSize()) {
            Text(date.format(DateTimeFormatter.ofPattern("M月d日")) + " 的作业 · 已完成 $doneCount / ${records.size} 项", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            HistoryTaskList(Modifier.weight(1f), records, context)
        }
    }
}

@Composable private fun HistoryTaskList(modifier: Modifier, records: List<CompletionRecord>, context: Context) {
    var photoUrl by remember { mutableStateOf<String?>(null) }
    Column(modifier) {
        Text("作业清单 · ${records.size} 项", fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        if (records.isEmpty()) Text("暂无作业记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(records, key = { it.taskId }) { record ->
                val done = record.completedAtEpochSeconds != null
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (done) Leaf else TodoSurface).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(record.title.ifBlank { "作业" }, modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TaskStatusPill(if (done) TaskStatus.COMPLETED else TaskStatus.TODO)
                    }
                    if (record.photoUrls.isNotEmpty()) {
                        TextButton(onClick = { photoUrl = record.photoUrls.first() }, contentPadding = PaddingValues(top = 5.dp, bottom = 0.dp)) {
                            Icon(Icons.Outlined.CameraAlt, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("查看图片（${record.photoUrls.size} 张）")
                        }
                    }
                }
            }
        }
    }
    photoUrl?.let { PhotoViewer(it, HomeworkApi(context)) { photoUrl = null } }
}

@Composable private fun PhotoViewer(url: String, api: HomeworkApi, onDismiss: () -> Unit) {
    val state by produceState(PhotoLoadState(), url) { value = PhotoLoadState(api.loadPhoto(url), finished = true) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(24.dp), shape = RoundedCornerShape(24.dp), color = Color.Black) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("关闭") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    state.bitmap?.let { Image(it.asImageBitmap(), "作业图片", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    if (!state.finished) CircularProgressIndicator(color = Color.White)
                    else if (state.bitmap == null) Text("图片加载失败，请稍后重试", color = Color.White)
                }
            }
        }
    }
}

@Composable private fun WeeklyReportCard(report: WeeklyReport) {
    val avg = report.avgEarlyMinutes
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = if (report.reward) Sun else Leaf, contentColor = Ink) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("本周周报", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    avg == null -> "本周还没有完成记录"
                    avg >= 0 -> "本周平均提前 ${avg.roundToInt()} 分钟完成"
                    else -> "本周平均晚了 ${(-avg).roundToInt()} 分钟"
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
            if (report.reward) {
                Spacer(Modifier.height(4.dp))
                Text("🏆 奖励一朵金花", fontSize = 15.sp, color = Color(0xFF24733A), fontWeight = FontWeight.Medium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun Header(slogan: String, refreshing: Boolean, captureStatus: CaptureStatus?, studyActivity: StudyActivity, xiaoliConnection: XiaoliConnectionSnapshot, studyLocked: Boolean, hasStudyApps: Boolean, onRefresh: () -> Unit, onParent: () -> Unit, onStudyApps: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = onParent)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今天的作业", fontSize = 30.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                XiaoliConnectionIcon(xiaoliConnection)
            }
            Text(slogan, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (studyLocked) Surface(shape = RoundedCornerShape(18.dp), color = Leaf, contentColor = Color(0xFF24733A)) {
                Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("学习锁定中", fontWeight = FontWeight.Medium)
                }
            }
            if (studyLocked && hasStudyApps) FilledTonalButton(onClick = onStudyApps, shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp)) {
                Icon(Icons.Outlined.Apps, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("学习应用")
            }
            FilledTonalButton(onClick = onRefresh, enabled = !refreshing, shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp)) {
                Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text(if (refreshing) "刷新中…" else "刷新")
            }
            StudyActivityBadges(studyActivity)
            CaptureStatusBadge(captureStatus)
        }
    }
}

@Composable private fun XiaoliConnectionIcon(connection: XiaoliConnectionSnapshot) {
    val (icon, tint, description) = when (connection.status) {
        "已连接" -> Triple(Icons.Outlined.CloudDone, Color(0xFF24733A), "已连接小李服务")
        "正在连接" -> Triple(Icons.Outlined.CloudSync, Color(0xFF765B11), "正在连接小李服务")
        else -> Triple(Icons.Outlined.CloudOff, MaterialTheme.colorScheme.error, "未连接小李服务")
    }
    Icon(icon, description, modifier = Modifier.size(24.dp), tint = tint)
}

@Composable private fun StudyActivityBadges(activity: StudyActivity) {
    if (activity.switches > 0) MetricBadge("切换 APP ${activity.switches} 次")
    if (activity.blockedSeconds > 0) MetricBadge("非允许 APP ${formatStudyDuration(activity.blockedSeconds)}")
}

@Composable private fun MetricBadge(label: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = Sun, contentColor = Color(0xFF765B11)) {
        Text(label, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private fun formatStudyDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remainder = seconds % 60
    return when {
        minutes > 0 && remainder > 0 -> "${minutes} 分 ${remainder} 秒"
        minutes > 0 -> "${minutes} 分"
        else -> "${remainder} 秒"
    }
}

@Composable private fun CaptureStatusBadge(status: CaptureStatus?) {
    if (status == null) return
    val label = status.label()
    val color = if (status.active) Color(0xFFFFE9EE) else Sun
    val content = if (status.active) Color(0xFFB3264A) else Color(0xFF765B11)
    Surface(shape = RoundedCornerShape(18.dp), color = color, contentColor = content) {
        Text(label, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable private fun EmptyTaskState(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(26.dp)).background(Leaf), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("🎉", fontSize = 58.sp); Spacer(Modifier.height(12.dp)); Text("今天还没有作业", fontSize = 26.sp, fontWeight = FontWeight.Medium); Text("新增到 Trello 的“待完成”列表后，最多一分钟会出现在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun AllDoneState(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(26.dp)).background(Leaf), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🏆", fontSize = 70.sp)
            Spacer(Modifier.height(12.dp))
            Text("今天的作业都完成啦！", fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Text("右边可以查看每一项完成情况。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun CurrentTask(modifier: Modifier, task: HomeworkTask, running: Boolean, pianoPractice: PianoPracticeStatus?, submitting: Boolean, onStart: () -> Unit, onPianoRecord: () -> Unit, onFinish: () -> Unit) {
    val overdue = task.status == TaskStatus.OVERTIME
    Column(modifier.clip(RoundedCornerShape(26.dp)).background(if (overdue) OverdueSurface else Sun).padding(28.dp), horizontalAlignment = Alignment.Start) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (task.subject != "作业") AssistChip(onClick = {}, label = { Text(task.subject) })
            TaskStatusPill(task.status)
        }
        Spacer(Modifier.height(14.dp)); Text(task.title, fontSize = 30.sp, fontWeight = FontWeight.Medium)
        Text(if (overdue) "已超过截止时间，请优先完成" else "截止 ${task.deadline.format(DateTimeFormatter.ofPattern("HH:mm"))}", color = if (overdue) OverdueInk else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (overdue) FontWeight.Medium else FontWeight.Normal)
        Spacer(Modifier.weight(1f)); Box(Modifier.size(166.dp).align(Alignment.CenterHorizontally).clip(CircleShape).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (overdue) "已超期" else "截止", fontSize = 15.sp, color = if (overdue) OverdueInk else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(task.deadline.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 31.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.weight(1f)); Button(onClick = onStart, modifier = Modifier.align(Alignment.CenterHorizontally), enabled = task.status != TaskStatus.COMPLETED && !submitting && !running) { Text(if (running) "正在做" else "开始做") }
        if (pianoPractice != null) {
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = onPianoRecord, modifier = Modifier.align(Alignment.CenterHorizontally), enabled = pianoPractice.cooldownSeconds == 0 && task.status != TaskStatus.COMPLETED && !submitting) {
                Text(if (pianoPractice.cooldownSeconds > 0) "已记 ${pianoPractice.count} 次 · ${pianoPractice.cooldownSeconds} 秒后可再记" else "🎹 练琴记一次（已记 ${pianoPractice.count} 次）")
            }
        }
        Spacer(Modifier.height(10.dp)); FilledTonalButton(onClick = onFinish, modifier = Modifier.align(Alignment.CenterHorizontally), enabled = task.status != TaskStatus.COMPLETED && !submitting) { Icon(Icons.Outlined.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("完成并拍照") }
    }
}

@Composable private fun TaskQueue(modifier: Modifier, tasks: List<HomeworkTask>, completedTasks: List<HomeworkTask>, onSelect: (HomeworkTask) -> Unit) {
    val allTasks = tasks + completedTasks
    val context = androidx.compose.ui.platform.LocalContext.current
    var photoUrl by remember { mutableStateOf<String?>(null) }
    Column(modifier) {
        Text("作业清单 · ${allTasks.size} 项", fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(allTasks, key = { it.id }) { task ->
                val overdue = task.status == TaskStatus.OVERTIME
                val completed = task.status == TaskStatus.COMPLETED
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (completed) Leaf else if (overdue) OverdueSurface else TodoSurface).clickable { onSelect(task) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (task.subject != "作业") Text(task.subject, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        TaskStatusPill(task.status)
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(task.title, modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Text(if (completed) "已完成" else "截止 ${task.deadline.format(DateTimeFormatter.ofPattern("HH:mm"))}", color = if (completed) Color(0xFF24733A) else if (overdue) OverdueInk else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    if (completed && task.photoUrls.isNotEmpty()) {
                        TextButton(onClick = { photoUrl = task.photoUrls.first() }, contentPadding = PaddingValues(top = 4.dp, bottom = 0.dp)) {
                            Icon(Icons.Outlined.CameraAlt, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("查看照片（${task.photoUrls.size} 张）")
                        }
                    }
                }
            }
        }
        if (completedTasks.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Leaf).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Star, null, tint = Primary)
                Spacer(Modifier.width(10.dp))
                Text("一步一步来，完成一项就离星星更近一点。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
    photoUrl?.let { PhotoViewer(it, HomeworkApi(context)) { photoUrl = null } }
}

@Composable private fun TaskStatusPill(status: TaskStatus) {
    val (label, surface, content) = when (status) {
        TaskStatus.OVERTIME -> Triple("⚠ 已超期", OverdueSurface, OverdueInk)
        TaskStatus.RUNNING -> Triple("正在完成", Color(0xFFFFF3CE), Ink)
        TaskStatus.COMPLETED -> Triple("✓ 已完成", Leaf, Color(0xFF24733A))
        TaskStatus.TODO -> Triple("待完成", TodoSurface, Primary)
    }
    Surface(shape = RoundedCornerShape(10.dp), color = surface, contentColor = content) {
        Text(label, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
