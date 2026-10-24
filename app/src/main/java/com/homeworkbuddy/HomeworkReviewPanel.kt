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
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun HomeworkReviewPanel(task: HomeworkTask, modifier: Modifier = Modifier, refreshing: Boolean) {
    val context = LocalContext.current
    val api = remember(context) { HomeworkApi(context) }
    var content by remember(task.id) { mutableStateOf<HomeworkReviewContent?>(null) }
    var error by remember(task.id) { mutableStateOf<String?>(null) }
    var loading by remember(task.id) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(task.id, refresh, refreshing) {
        if (refreshing) return@LaunchedEffect
        loading = true
        error = null
        try {
            content = api.reviews(task.id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "批改结果加载失败" }
        finally { loading = false }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(task.title, style = MaterialTheme.typography.titleLarge)
        Button(onClick = { refresh++ }, enabled = !loading && !refreshing) { Text("刷新批改") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) CircularProgressIndicator()
        val current = content
        if (current != null && current.reviews.isEmpty()) Text("还没有批改结果")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
