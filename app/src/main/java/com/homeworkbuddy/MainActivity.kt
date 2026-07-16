package com.homeworkbuddy

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val Sky = Color(0xFFEAF4FF)
private val Sun = Color(0xFFFFF3CE)
private val Leaf = Color(0xFFE6F7E9)
private val Ink = Color(0xFF263238)
private val Primary = Color(0xFF3769D9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        KioskPolicy(this).scheduleNextTransitions()
        setContent { HomeworkBuddyApp() }
    }

    override fun onResume() {
        super.onResume()
        KioskPolicy(this).applyForCurrentTime(this)
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
    val api = remember(context) { HomeworkApi(context) }
    val kioskPolicy = remember(context) { KioskPolicy(context) }
    val pendingStore = remember(context) { PendingSubmissionStore(context) }
    val scope = rememberCoroutineScope()
    var childName by remember { mutableStateOf(context.getSharedPreferences("profile", Context.MODE_PRIVATE).getString("child_name", "") ?: "") }
    var tasks by remember { mutableStateOf(PreviewTaskSource().let { source -> listOf(
        HomeworkTask("chinese", "语文", "抄写生字第 1—3 课", 15, LocalTime.of(18, 30), TaskStatus.COMPLETED),
        HomeworkTask("math", "数学", "完成口算练习册第 12 页", 20, LocalTime.of(19, 0)),
        HomeworkTask("english", "英语", "朗读 Unit 3 单词", 25, LocalTime.of(20, 0)),
    ) } ) }
    var selectedId by remember { mutableStateOf(tasks.first { it.status != TaskStatus.COMPLETED }.id) }
    var remainingSeconds by remember { mutableIntStateOf(tasks.first { it.id == selectedId }.estimatedMinutes * 60) }
    var running by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(childName.isBlank()) }
    var connected by remember { mutableStateOf(api.isConnected) }
    var showConnectionDialog by remember { mutableStateOf(childName.isNotBlank() && !api.isConnected) }
    var pairing by remember { mutableStateOf<Pairing?>(null) }
    var trelloBoards by remember { mutableStateOf<List<TrelloOption>>(emptyList()) }
    var selectedBoardId by remember { mutableStateOf("") }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var foreground by remember { mutableStateOf(true) }
    var refreshRequest by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var showCameraConfirm by remember { mutableStateOf(false) }
    var pendingPhoto by remember { mutableStateOf<Uri?>(null) }
    var submittingTaskId by remember { mutableStateOf<String?>(null) }

    fun advanceAfterCompletion(taskId: String, photoPath: String? = null) {
        val currentIndex = tasks.indexOfFirst { it.id == taskId }
        val updated = tasks.map { task ->
            if (task.id == taskId) task.copy(status = TaskStatus.COMPLETED, photoPath = photoPath ?: task.photoPath) else task
        }
        tasks = updated
        val next = if (currentIndex >= 0) {
            updated.drop(currentIndex + 1).firstOrNull { it.status != TaskStatus.COMPLETED }
                ?: updated.take(currentIndex).firstOrNull { it.status != TaskStatus.COMPLETED }
        } else updated.firstOrNull { it.status != TaskStatus.COMPLETED }
        selectedId = next?.id.orEmpty()
        remainingSeconds = next?.estimatedMinutes?.times(60) ?: 0
        running = false
    }

    LaunchedEffect(running, selectedId) {
        while (running && remainingSeconds > 0) { delay(1_000); remainingSeconds-- }
        if (running && remainingSeconds == 0) {
            running = false
            tasks = tasks.map { if (it.id == selectedId) it.copy(status = TaskStatus.OVERTIME) else it }
        }
    }

    val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
    val takePhoto = rememberLauncherForActivityResult(remember(isTablet) { DeviceAwareTakePicture(isTablet) }) { captured ->
        if (captured) showCameraConfirm = true else pendingPhoto = null
    }
    val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val photo = pendingPhoto
        if (granted && photo != null) {
            runCatching { takePhoto.launch(photo) }
                .onFailure {
                    pendingPhoto = null
                    connectionError = "无法打开相机，请检查系统相机是否可用。"
                }
        } else {
            pendingPhoto = null
            connectionError = "需要相机权限才能拍照记录作业。"
        }
    }

    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { foreground = true; refreshRequest++ }
                Lifecycle.Event.ON_STOP -> foreground = false
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(connected, foreground, refreshRequest) {
        if (!connected || !foreground) return@LaunchedEffect
        while (connected && foreground) {
            refreshing = true
            runCatching { api.todayTasks() }.onSuccess { remote ->
                val local = tasks.associateBy { it.id }
                val remoteIds = remote.mapTo(mutableSetOf()) { it.id }
                val completedLocal = tasks.filter { it.status == TaskStatus.COMPLETED && it.id !in remoteIds }
                val merged = remote.map { fresh ->
                    local[fresh.id]?.let { old ->
                        if (fresh.status == TaskStatus.COMPLETED) fresh.copy(photoPath = old.photoPath)
                        else fresh.copy(status = old.status, photoPath = old.photoPath)
                    } ?: fresh
                } + completedLocal
                tasks = merged
                val selected = merged.firstOrNull { it.id == selectedId && it.status != TaskStatus.COMPLETED }
                    ?: merged.firstOrNull { it.status != TaskStatus.COMPLETED }
                if (selected == null) {
                    selectedId = ""; remainingSeconds = 0; running = false
                } else if (selected.id != selectedId) {
                    selectedId = selected.id; remainingSeconds = selected.estimatedMinutes * 60; running = false
                }
                connectionError = null
            }.onFailure { connectionError = it.message ?: "同步当天作业失败" }
            pendingStore.items().forEach { pending ->
                runCatching { api.submit(pending.taskId, pending.photoPath?.let(android.net.Uri::parse), pending.isOvertime, pending.submissionId) }
                    .onSuccess { pendingStore.remove(pending.taskId); refreshRequest++ }
            }
            refreshing = false
            delay(60_000)
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Primary, background = Color(0xFFFFFBFF), onBackground = Ink)) {
        if (showNameDialog) NameDialog(
            onSaved = { name -> context.getSharedPreferences("profile", Context.MODE_PRIVATE).edit().putString("child_name", name).apply(); childName = name; showNameDialog = false; if (!connected) showConnectionDialog = true }
        )
        if (showConnectionDialog) ConnectionDialog(
            pairing = pairing,
            boards = trelloBoards,
            selectedBoardId = selectedBoardId,
            error = connectionError,
            onConnect = {
                scope.launch {
                    connectionError = null
                    trelloBoards = emptyList(); selectedBoardId = ""
                    runCatching { api.createPairing() }.onSuccess { created -> pairing = created; api.openAuthorization(created) }.onFailure { connectionError = it.message ?: "无法连接服务" }
                }
            },
            onCheck = {
                pairing?.let { current ->
                    scope.launch {
                        runCatching { api.pairingStatus(current) }.onSuccess { status ->
                            when {
                                status.configured -> { api.finishPairing(current); connected = true; showConnectionDialog = false }
                                status.authorized -> runCatching { api.boards(current) }.onSuccess { values ->
                                    trelloBoards = values; selectedBoardId = values.firstOrNull()?.id.orEmpty()
                                    connectionError = if (values.isEmpty()) "这个 Trello 账户还没有看板，请先创建看板。" else null
                                }.onFailure { connectionError = it.message ?: "无法读取看板" }
                                else -> connectionError = "Trello 还没有授权成功，请完成授权后再试。"
                            }
                        }.onFailure { connectionError = it.message ?: "无法确认关联状态" }
                    }
                }
            },
            onSelectBoard = { selectedBoardId = it },
            onFinishSetup = {
                pairing?.let { current -> scope.launch {
                    runCatching { api.initialize(current, selectedBoardId) }
                        .onSuccess { connected = true; showConnectionDialog = false }
                        .onFailure { connectionError = it.message ?: "保存看板设置失败" }
                } }
            },
        )
        val selected = tasks.firstOrNull { it.id == selectedId }
        HomeworkHome(
            name = childName,
            tasks = tasks,
            selected = selected,
            remainingSeconds = remainingSeconds,
            running = running,
            submitting = selected?.id == submittingTaskId,
            refreshing = refreshing,
            studyLocked = kioskPolicy.isDeviceOwner && kioskPolicy.mode() == KioskMode.STUDY,
            hasStudyApps = kioskPolicy.studyPackages.isNotEmpty(),
            syncError = if (connected) connectionError else null,
            onRefresh = { refreshRequest++ },
            onParent = { context.startActivity(Intent(context, KioskSettingsActivity::class.java)) },
            onStudyApps = kioskPolicy::openStudyLauncher,
            onSelect = { task -> selectedId = task.id; remainingSeconds = task.estimatedMinutes * 60; running = false },
            onStart = { running = !running; tasks = tasks.map { if (it.id == selectedId) it.copy(status = TaskStatus.RUNNING) else it } },
            onComplete = {
                selected?.takeIf { submittingTaskId == null }?.let { current ->
                    running = false
                    submittingTaskId = current.id
                    val submissionId = java.util.UUID.randomUUID().toString()
                    scope.launch {
                        runCatching { api.submit(current.id, null, current.status == TaskStatus.OVERTIME, submissionId) }
                            .onSuccess {
                                advanceAfterCompletion(current.id)
                                refreshRequest++
                            }
                            .onFailure {
                                pendingStore.add(PendingSubmission(current.id, null, current.status == TaskStatus.OVERTIME, submissionId))
                                advanceAfterCompletion(current.id)
                                connectionError = "网络不可用，已保存，联网后会自动同步完成状态。"
                            }
                        submittingTaskId = null
                    }
                }
            },
            onFinish = {
                selected?.let { current ->
                    running = false
                    val file = File(context.cacheDir, "photos/${current.id}-${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }
                    pendingPhoto = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        runCatching { takePhoto.launch(pendingPhoto!!) }
                            .onFailure {
                                pendingPhoto = null
                                connectionError = "无法打开相机，请检查系统相机是否可用。"
                            }
                    } else {
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                    }
                }
            },
            onSubmit = {
                val photo = pendingPhoto
                val current = selected
                if (photo == null || !connected || current == null) {
                    connectionError = "请先关联 Trello 后再提交作业。"
                } else if (submittingTaskId == null) {
                    submittingTaskId = current.id
                    val submissionId = java.util.UUID.randomUUID().toString()
                    scope.launch {
                        runCatching { api.submit(current.id, photo, current.status == TaskStatus.OVERTIME, submissionId) }
                            .onSuccess {
                                advanceAfterCompletion(current.id, photo.toString())
                                refreshRequest++
                                showCameraConfirm = false; pendingPhoto = null
                            }
                            .onFailure {
                                pendingStore.add(PendingSubmission(current.id, photo.toString(), current.status == TaskStatus.OVERTIME, submissionId))
                                advanceAfterCompletion(current.id, photo.toString())
                                showCameraConfirm = false; pendingPhoto = null
                                connectionError = "网络不可用，已保存，联网后会自动提交。"
                            }
                        submittingTaskId = null
                    }
                }
            },
            showCameraConfirm = showCameraConfirm,
            onRetake = { showCameraConfirm = false; pendingPhoto = null },
        )
    }
}

@Composable private fun ConnectionDialog(
    pairing: Pairing?, boards: List<TrelloOption>, selectedBoardId: String, error: String?,
    onConnect: () -> Unit, onCheck: () -> Unit, onSelectBoard: (String) -> Unit,
    onFinishSetup: () -> Unit,
) {
    val selectingBoard = boards.isNotEmpty()
    AlertDialog(onDismissRequest = {}, icon = { Text("🔗", fontSize = 38.sp) }, title = { Text("请家长关联 Trello") }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
            Text(when { pairing == null -> "关联后可以同步今天的作业。"; selectingBoard -> "请选择这台平板使用的看板。系统固定使用“待完成”和“已完成”两个列表，缺少时会自动创建。"; else -> "浏览器只负责 Trello 授权；授权后回到这里继续选择看板。" })
            if (selectingBoard) boards.forEach { item -> Row(Modifier.fillMaxWidth().clickable { onSelectBoard(item.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedBoardId == item.id, { onSelectBoard(item.id) }); Text(item.name) } }
            if (error != null) { Spacer(Modifier.height(10.dp)); Text(error, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(
        enabled = !selectingBoard || selectedBoardId.isNotEmpty(),
        onClick = when { pairing == null -> onConnect; selectingBoard -> onFinishSetup; else -> onCheck }
    ) { Text(when { pairing == null -> "关联 Trello"; selectingBoard -> "绑定看板"; else -> "我已完成授权" }) } },
        dismissButton = { if (pairing != null && !selectingBoard) TextButton(onClick = onConnect) { Text("重新关联") } })
}

@Composable private fun NameDialog(onSaved: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = {}, icon = { Text("👋", fontSize = 42.sp) }, title = { Text("你好！我怎么称呼你？") }, text = { OutlinedTextField(value = draft, onValueChange = { draft = it.take(12) }, singleLine = true, label = { Text("你的名字") }) }, confirmButton = { Button(enabled = draft.isNotBlank(), onClick = { onSaved(draft.trim()) }) { Text("开始今天的作业") } })
}

@Composable
private fun HomeworkHome(name: String, tasks: List<HomeworkTask>, selected: HomeworkTask?, remainingSeconds: Int, running: Boolean, submitting: Boolean, refreshing: Boolean, studyLocked: Boolean, hasStudyApps: Boolean, syncError: String?, onRefresh: () -> Unit, onParent: () -> Unit, onStudyApps: () -> Unit, onSelect: (HomeworkTask) -> Unit, onStart: () -> Unit, onComplete: () -> Unit, onFinish: () -> Unit, onSubmit: () -> Unit, showCameraConfirm: Boolean, onRetake: () -> Unit) {
    val complete = tasks.count { it.status == TaskStatus.COMPLETED }
    val waiting = tasks.filter { it.status != TaskStatus.COMPLETED && it.id != selected?.id }
    val completedTasks = tasks.filter { it.status == TaskStatus.COMPLETED }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
        Column(Modifier.fillMaxSize()) {
            Header(name, complete, tasks.size, refreshing, studyLocked, hasStudyApps, onRefresh, onParent, onStudyApps)
            if (syncError != null) Text(syncError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            if (selected == null) {
                EmptyTaskState(Modifier.fillMaxSize())
            } else if (this@BoxWithConstraints.maxWidth >= 700.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                CurrentTask(Modifier.weight(1.45f).fillMaxHeight(), selected, remainingSeconds, running, submitting, onStart, onComplete, onFinish)
                TaskQueue(Modifier.weight(.8f).fillMaxHeight(), waiting, completedTasks, onSelect)
            } else {
                CurrentTask(Modifier.fillMaxWidth(), selected, remainingSeconds, running, submitting, onStart, onComplete, onFinish)
                Spacer(Modifier.height(16.dp)); TaskQueue(Modifier.fillMaxWidth(), waiting, completedTasks, onSelect)
            }
        }
    }
    if (showCameraConfirm) AlertDialog(onDismissRequest = { if (!submitting) onRetake() }, icon = { Icon(Icons.Outlined.CameraAlt, null) }, title = { Text("上传作业照片") }, text = { Text(if (submitting) "正在上传照片并完成任务…" else "将这张照片上传到 Trello，并把当前任务标记为完成。") }, confirmButton = { Button(onClick = onSubmit, enabled = !submitting) { if (submitting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("正在上传…") } else Text("上传并完成") } }, dismissButton = { TextButton(onClick = onRetake, enabled = !submitting) { Text("取消") } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun Header(name: String, complete: Int, total: Int, refreshing: Boolean, studyLocked: Boolean, hasStudyApps: Boolean, onRefresh: () -> Unit, onParent: () -> Unit, onStudyApps: () -> Unit) {
    val percent = if (total == 0) 0 else complete * 100 / total
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.combinedClickable(onClick = {}, onLongClick = onParent)) { Text("今天的作业", fontSize = 30.sp, fontWeight = FontWeight.Medium); Text(if (name.isBlank()) "按顺序完成就好！" else "$name，按顺序完成就好！", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            Column(Modifier.width(245.dp).clip(RoundedCornerShape(18.dp)).background(Sky).padding(horizontal = 14.dp, vertical = 11.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("已完成 $complete / $total"); Text("$percent%", fontWeight = FontWeight.Medium) }; Spacer(Modifier.height(7.dp)); LinearProgressIndicator(progress = { if (total == 0) 0f else complete.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape)) }
        }
    }
}

@Composable private fun EmptyTaskState(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(26.dp)).background(Leaf), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("🎉", fontSize = 58.sp); Spacer(Modifier.height(12.dp)); Text("今天还没有作业", fontSize = 26.sp, fontWeight = FontWeight.Medium); Text("新增到 Trello 的“待完成”列表后，最多一分钟会出现在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun CurrentTask(modifier: Modifier, task: HomeworkTask, seconds: Int, running: Boolean, submitting: Boolean, onStart: () -> Unit, onComplete: () -> Unit, onFinish: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(26.dp)).background(Sun).padding(28.dp), horizontalAlignment = Alignment.Start) {
        AssistChip(onClick = {}, label = { Text(task.subject) }); Spacer(Modifier.height(14.dp)); Text(task.title, fontSize = 30.sp, fontWeight = FontWeight.Medium); Text("预计 ${task.estimatedMinutes} 分钟 · 截止 ${task.deadline.format(DateTimeFormatter.ofPattern("HH:mm"))}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f)); Box(Modifier.size(166.dp).align(Alignment.CenterHorizontally).clip(CircleShape).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(progress = { (seconds.toFloat() / (task.estimatedMinutes * 60)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(), strokeWidth = 9.dp); Text("%02d:%02d".format(seconds / 60, seconds % 60), fontSize = 31.sp, fontWeight = FontWeight.Medium) }
        Spacer(Modifier.weight(1f)); Button(onClick = onStart, modifier = Modifier.align(Alignment.CenterHorizontally), enabled = task.status != TaskStatus.COMPLETED && !submitting) { Text(if (running) "暂停计时" else "开始做") }; Spacer(Modifier.height(10.dp)); Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) { FilledTonalButton(onClick = onComplete, enabled = task.status != TaskStatus.COMPLETED && !submitting) { if (submitting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("提交中…") } else Text("直接完成") }; FilledTonalButton(onClick = onFinish, enabled = task.status != TaskStatus.COMPLETED && !submitting) { Icon(Icons.Outlined.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("完成并拍照") } }
    }
}

@Composable private fun TaskQueue(modifier: Modifier, tasks: List<HomeworkTask>, completedTasks: List<HomeworkTask>, onSelect: (HomeworkTask) -> Unit) {
    Column(modifier) {
        Text("接下来 · ${tasks.size} 项", fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { task ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surface).clickable { onSelect(task) }.padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(task.subject, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Text(if (task.status == TaskStatus.OVERTIME) "已超时" else "下一项", color = if (task.status == TaskStatus.OVERTIME) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(task.title, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text("${task.estimatedMinutes} 分钟 · ${task.deadline.format(DateTimeFormatter.ofPattern("HH:mm"))} 前", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (completedTasks.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Leaf).padding(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Star, null, tint = Primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("今天已完成 · ${completedTasks.size} 项", fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(9.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(completedTasks, key = { "completed-${it.id}" }) { task ->
                        Surface(shape = RoundedCornerShape(14.dp), color = Color.White) {
                            Text("✓ ${task.title}", modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Primary)
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Leaf).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Star, null, tint = Primary)
                Spacer(Modifier.width(10.dp))
                Text("一步一步来，完成一项就离星星更近一点。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
}
