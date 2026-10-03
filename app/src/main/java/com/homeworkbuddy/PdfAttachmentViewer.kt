package com.homeworkbuddy

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PdfAttachmentButtons(task: HomeworkTask) {
    if (task.type != HomeworkTaskType.PDF_ATTACHMENT) return
    val attachments = task.attachments.filter { it.isPdf }
    var selected by remember(task.id) { mutableStateOf<HomeworkAttachment?>(null) }
    if (attachments.isEmpty()) {
        Text("尚未上传 PDF 附件，请在 Trello 卡片中添加 PDF")
    }
    attachments.forEach { attachment ->
        FilledTonalButton(onClick = { selected = attachment }) {
            Text(if (attachments.size == 1) "预览 PDF" else "预览 PDF · ${attachment.name}")
        }
    }
    selected?.let { attachment ->
        key(attachment.url) { PdfAttachmentViewer(attachment) { selected = null } }
    }
}

@Composable
private fun PdfAttachmentViewer(attachment: HomeworkAttachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember(page) { mutableFloatStateOf(1f) }
    var offset by remember(page) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    LaunchedEffect(attachment.url, retry) {
        file = null
        error = null
        page = 0
        pageCount = 0
        bitmap = null
        val temporary = File.createTempFile("homework-preview-", ".pdf", context.cacheDir)
        try {
            HomeworkApi(context).downloadPdf(attachment.url, temporary)
            file = temporary
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "PDF 下载失败，请重试"
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { temporary.delete() }
        }
    }
    LaunchedEffect(file, page) {
        bitmap = null
        val source = file ?: return@LaunchedEffect
        try {
            val rendered = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val count = renderer.pageCount
                        check(count > 0) { "PDF 没有可显示的页面" }
                        renderer.openPage(page).use { pdfPage ->
                            // Bound memory even for unusually large PDF page dimensions.
                            val ratio = 2000f / maxOf(pdfPage.width, pdfPage.height)
                            val image = Bitmap.createBitmap(
                                (pdfPage.width * ratio).toInt().coerceAtLeast(1),
                                (pdfPage.height * ratio).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            image.eraseColor(android.graphics.Color.WHITE)
                            pdfPage.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            image to count
                        }
                    }
                }
            }
            bitmap = rendered.first
            pageCount = rendered.second
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "无法预览 PDF，请确认文件完整且未加密"
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(16.dp)) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(attachment.name.ifBlank { "PDF 作业" }, Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                    if (error != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error.orEmpty())
                            TextButton(onClick = { retry++ }) { Text("重试") }
                        }
                    } else {
                        bitmap?.let { image ->
                            Image(image.asImageBitmap(), "PDF 第 ${page + 1} 页，可双指缩放",
                                Modifier.fillMaxSize().transformable(transform).graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }, contentScale = ContentScale.Fit)
                        } ?: CircularProgressIndicator()
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { page-- }, enabled = page > 0 && bitmap != null && error == null) { Text("上一页") }
                    Text(if (pageCount > 0) "${page + 1} / $pageCount" else "加载中")
                    TextButton(onClick = { page++ }, enabled = page + 1 < pageCount && bitmap != null && error == null) { Text("下一页") }
                }
            }
        }
    }
}
