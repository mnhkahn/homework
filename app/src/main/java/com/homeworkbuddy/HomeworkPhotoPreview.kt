package com.homeworkbuddy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Only unsubmitted photos owned by this app may be removed during review. */
internal fun deletePendingHomeworkPhoto(context: Context, uri: Uri) {
    if (uri.authority == "${context.packageName}.fileprovider" &&
        uri.path?.startsWith("/homework_photos/") == true) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeworkPhotoPreviewDialog(
    photos: List<Uri>,
    initialPage: Int,
    maxPhotos: Int,
    submitting: Boolean,
    onRetakePhoto: (Int) -> Unit,
    onRemovePhoto: (Int) -> Unit,
    onAddPhoto: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    if (photos.isEmpty()) return
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(photos.indices), pageCount = { photos.size })
    val scope = rememberCoroutineScope()
    val current = photos.getOrNull(pager.currentPage)
    var scale by remember(current) { mutableFloatStateOf(1f) }
    var offset by remember(current) { mutableStateOf(Offset.Zero) }
    var readyPhotos by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    LaunchedEffect(photos.size) {
        if (pager.currentPage > photos.lastIndex) pager.scrollToPage(photos.lastIndex)
    }
    Dialog(
        onDismissRequest = { if (!submitting) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().systemBarsPadding()
                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 80.dp),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("检查作业照片", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onCancel, enabled = !submitting) { Text("取消提交") }
                }
                Text("先检查文字是否清楚、内容是否完整。双指可放大，放大后可拖动查看。")
                HorizontalPager(
                    state = pager,
                    key = { photos[it] },
                    modifier = Modifier.weight(1f).fillMaxWidth().clip(RectangleShape),
                    userScrollEnabled = scale <= 1f,
                ) { page ->
                    val uri = photos[page]
                    PendingPhotoPreview(
                        uri = uri,
                        description = "第 ${page + 1} 张作业照片，可双指放大查看",
                        imageModifier = Modifier.fillMaxSize()
                            .transformable(transform, canPan = { scale > 1f }, enabled = page == pager.currentPage)
                            .graphicsLayer {
                                scaleX = if (page == pager.currentPage) scale else 1f
                                scaleY = scaleX
                                translationX = if (page == pager.currentPage) offset.x else 0f
                                translationY = if (page == pager.currentPage) offset.y else 0f
                            },
                        onReady = { ready -> readyPhotos = if (ready) readyPhotos + uri else readyPhotos - uri },
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = { scope.launch { pager.scrollToPage(pager.currentPage - 1) } },
                        enabled = pager.currentPage > 0 && !pager.isScrollInProgress,
                    ) { Text("上一张") }
                    Text("第 ${(pager.currentPage + 1).coerceAtMost(photos.size)} / ${photos.size} 张")
                    TextButton(onClick = { scale = 1f; offset = Offset.Zero }, enabled = scale > 1f) { Text("还原") }
                    TextButton(
                        onClick = { scope.launch { pager.scrollToPage(pager.currentPage + 1) } },
                        enabled = pager.currentPage < photos.lastIndex && !pager.isScrollInProgress,
                    ) { Text("下一张") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onRetakePhoto(pager.currentPage) },
                        enabled = !submitting && current != null && !pager.isScrollInProgress,
                        modifier = Modifier.weight(1f),
                    ) { Text("重拍这张") }
                    OutlinedButton(
                        onClick = { onRemovePhoto(pager.currentPage) },
                        enabled = !submitting && current != null && !pager.isScrollInProgress,
                        modifier = Modifier.weight(1f),
                    ) { Text("删除这张") }
                    OutlinedButton(
                        onClick = onAddPhoto,
                        enabled = !submitting && photos.size < maxPhotos,
                        modifier = Modifier.weight(1f),
                    ) { Text("再拍一张") }
                }
                Button(
                    onClick = onSubmit,
                    enabled = !submitting && current != null && current in readyPhotos && !pager.isScrollInProgress,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在上传…")
                    } else Text("照片清楚，上传并完成")
                }
            }
        }
    }
}

private data class PendingPhotoPreviewState(val bitmap: Bitmap? = null, val finished: Boolean = false)

@Composable
private fun PendingPhotoPreview(uri: Uri, description: String, imageModifier: Modifier, onReady: (Boolean) -> Unit) {
    val context = LocalContext.current
    var reload by remember(uri) { mutableIntStateOf(0) }
    val state by produceState(PendingPhotoPreviewState(), uri, reload) {
        value = PendingPhotoPreviewState()
        val bitmap = withContext(Dispatchers.IO) {
            try { loadPendingPhoto(context, uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        value = PendingPhotoPreviewState(bitmap, finished = true)
    }
    LaunchedEffect(uri, state) { onReady(state.finished && state.bitmap != null) }
    Box(Modifier.fillMaxSize().clip(RectangleShape), contentAlignment = Alignment.Center) {
        state.bitmap?.let { bitmap ->
            Image(bitmap.asImageBitmap(), description, imageModifier, contentScale = ContentScale.Fit)
        }
        if (!state.finished) CircularProgressIndicator()
        else if (state.bitmap == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("照片预览失败，请重新加载或重拍。", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { onReady(false); reload++ }) { Text("重新加载") }
            }
        }
    }
}

/** Bound preview memory while retaining enough detail to inspect handwriting. */
private fun loadPendingPhoto(context: Context, uri: Uri): Bitmap? {
    val maxDimension = 2560
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > maxDimension) {
                val ratio = maxDimension.toFloat() / longest
                decoder.setTargetSize((info.size.width * ratio).roundToInt().coerceAtLeast(1),
                    (info.size.height * ratio).roundToInt().coerceAtLeast(1))
            }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > maxDimension) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}
