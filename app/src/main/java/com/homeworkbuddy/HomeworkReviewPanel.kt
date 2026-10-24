package com.homeworkbuddy

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun HomeworkReviewPanel(modifier: Modifier = Modifier, refreshing: Boolean, onResubmit: (String) -> Unit) {
    val context = LocalContext.current
    val api = remember(context) { HomeworkApi(context) }
    var cards by remember { mutableStateOf<List<HomeworkReviewCard>>(emptyList()) }
    var selected by remember { mutableStateOf<HomeworkReviewCard?>(null) }
    var content by remember { mutableStateOf<HomeworkReviewContent?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var listLoaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    // This mode includes historical cards instead of inheriting the current-day filter.
    LaunchedEffect(Unit) {
        while (true) { delay(60_000); refresh++ }
    }
    LaunchedEffect(refresh, refreshing) {
        try {
            cards = api.reviewCards()
            listLoaded = true
            selected?.let { previous -> selected = cards.firstOrNull { it.id == previous.id } }
            listError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { listError = failure.message ?: "批改作业列表加载失败" }
    }
    LaunchedEffect(selected?.id, refresh, refreshing) {
        val card = selected ?: return@LaunchedEffect
        loading = true
        detailError = null
        try {
            content = api.reviews(card.id)
            detailError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { detailError = failure.message ?: "批改结果加载失败" }
        finally { loading = false }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected != null) OutlinedButton(onClick = { selected = null; content = null }) { Text("返回作业列表") }
            Button(onClick = { refresh++ }) { Text("刷新批改") }
        }
        val error = if (selected == null) listError else detailError
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val card = selected
        if (card == null) {
            Text("选择作业查看批改（包含历史作业）", style = MaterialTheme.typography.titleLarge)
            if (cards.isEmpty() && error == null) Text(if (listLoaded) "暂无作业" else "正在加载作业…")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cards, key = { it.id }) { item ->
                    OutlinedCard(onClick = { selected = item; content = null }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            if (item.due.isNotBlank()) Text("作业日期：${runCatching { java.time.OffsetDateTime.parse(item.due).atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString() }.getOrDefault(item.due.take(10))}")
                            Text(if (item.needsCorrection) "待改错 · 可重新提交" else "查看批改 →")
                        }
                    }
                }
            }
        } else {
            Text(card.title, style = MaterialTheme.typography.titleLarge)
            if (card.needsCorrection) {
                Button(onClick = { onResubmit(card.id) }) { Text("改好了，提交订正") }
                Text("拍照、录音或输入文字，新内容会追加到原作业。")
            }
            if (loading) CircularProgressIndicator()
            val current = content
            if (current != null && current.reviews.isEmpty()) Text("还没有批改结果")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(current?.reviews.orEmpty(), key = { it.id }) { review ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val date = runCatching { java.time.OffsetDateTime.parse(review.date).atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm")) }.getOrDefault(review.date)
                            Text("${if (review.id == current?.reviews?.firstOrNull()?.id) "最新批改 · " else "历史批改 · "}$date")
                            review.hasErrors?.let { Text(if (it) "本次批改有错，请查看订正要求" else "本次批改通过") }
                            if (review.type == "text") Text(review.text.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                            else {
                                val attachment = current?.attachments?.get(review.attachmentId)
                                when {
                                    attachment == null -> Text("批改附件已删除或尚未上传，请联系家长重新提交")
                                    review.type == "image" && attachment.isReviewImage -> ReviewImage(attachment, api)
                                    review.type == "audio" && attachment.isReviewAudio -> ReviewAudio(attachment, api)
                                    else -> Text("批改附件格式与声明不符，请联系家长检查")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal val HomeworkAttachment.isReviewImage: Boolean get() =
    mimeType.startsWith("image/", true) || name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif")
internal val HomeworkAttachment.isReviewAudio: Boolean get() =
    mimeType.startsWith("audio/", true) || name.substringAfterLast('.').lowercase() in setOf("m4a", "mp3", "wav", "aac", "ogg", "opus", "amr", "3gp")

@Composable
private fun ReviewImage(attachment: HomeworkAttachment, api: HomeworkApi) {
    var expanded by remember(attachment.url) { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, attachment.url) { value = api.loadPhoto(attachment.url) }
    bitmap?.let {
        Image(it.asImageBitmap(), "批改图片，点击放大", Modifier.fillMaxWidth().heightIn(max = 320.dp).clickable { expanded = true }, contentScale = ContentScale.Fit)
    }
    TextButton(onClick = { expanded = true }) { Text("查看批改图片（可缩放）") }
    if (expanded) PhotoViewer(listOf(attachment.url), api) { expanded = false }
}

@Composable
private fun ReviewAudio(attachment: HomeworkAttachment, api: HomeworkApi) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val file = remember(attachment.url) { File.createTempFile("review-audio-", ".audio", context.cacheDir) }
    val player = remember(attachment.url) { MediaPlayer() }
    val manager = remember { context.getSystemService(AudioManager::class.java) }
    val attributes = remember { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build() }
    var playing by remember(attachment.url) { mutableStateOf(false) }
    val focus = remember(player) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change < 0) { runCatching { player.pause() }; playing = false } }
            .build()
    }
    var prepared by remember(attachment.url) { mutableStateOf(false) }
    var loading by remember(attachment.url) { mutableStateOf(false) }
    var error by remember(attachment.url) { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        player.setAudioAttributes(attributes)
        player.setOnCompletionListener { playing = false; manager.abandonAudioFocusRequest(focus) }
        player.setOnErrorListener { _, _, _ ->
            error = "语音播放失败，请重试"; loading = false; playing = false; prepared = false
            manager.abandonAudioFocusRequest(focus)
            true
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                runCatching { player.pause() }; playing = false
                manager.abandonAudioFocusRequest(focus)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
            manager.abandonAudioFocusRequest(focus)
            file.delete()
        }
    }
    Button(enabled = !loading, onClick = {
        if (playing) {
            player.pause(); playing = false; manager.abandonAudioFocusRequest(focus)
        } else scope.launch {
            loading = true; error = null
            try {
                if (!prepared) {
                    api.downloadAttachment(attachment.url, file)
                    player.reset()
                    player.setAudioAttributes(attributes)
                    player.setDataSource(file.absolutePath)
                    val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
                    player.setOnPreparedListener { ready.complete(Unit) }
                    player.setOnErrorListener { _, _, _ ->
                        ready.completeExceptionally(IllegalStateException("语音格式不支持或播放失败"))
                        error = "语音格式不支持或播放失败"; playing = false; prepared = false
                        manager.abandonAudioFocusRequest(focus)
                        true
                    }
                    player.prepareAsync()
                    kotlinx.coroutines.withTimeout(15_000) { ready.await() }
                    prepared = true
                }
                check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "请返回前台后重试播放" }
                check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "无法取得语音播放焦点" }
                player.start(); playing = true
            } catch (cancelled: CancellationException) { file.delete(); throw cancelled }
            catch (failure: Exception) {
                error = failure.message ?: "语音加载失败，请重试"; prepared = false
                manager.abandonAudioFocusRequest(focus)
            }
            finally { loading = false }
        }
    }) { Text(if (loading) "正在加载语音…" else if (playing) "暂停批改语音" else "播放批改语音") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
