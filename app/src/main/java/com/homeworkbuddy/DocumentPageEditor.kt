package com.homeworkbuddy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** Offline page selection. Keep handwriting and diagrams inside the page intact. */
@Composable
internal fun DocumentPageEditor(source: Uri, onUse: (Uri) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember(source) { mutableStateOf<Bitmap?>(null) }
    var preview by remember(source) { mutableStateOf<Bitmap?>(null) }
    var corners by remember(source) { mutableStateOf(DocumentPageGeometry.fullPage) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        runCatching { withContext(Dispatchers.IO) { readDocumentPhoto(context, source) } }
            .onSuccess { original = it }
            .onFailure { error = "无法读取照片，请重拍。" }
    }
    Dialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (preview == null) "文本相机 · 选择作业页面" else "确认作业页面", style = MaterialTheme.typography.titleLarge)
                Text(if (preview == null) "拖动四个角框住纸张，去掉桌面等背景。请保留完整题目、手写答案和配图。" else "检查页面是否完整、清楚，确认后加入本次提交。")
                val image = preview ?: original
                if (image != null) {
                    if (preview != null) {
                        Image(image.asImageBitmap(), "裁切后的作业页面", Modifier.weight(1f).fillMaxWidth())
                    } else {
                        PageSelection(image, corners, !busy, { corners = it }, Modifier.weight(1f).fillMaxWidth())
                    }
                } else Box(Modifier.weight(1f).fillMaxWidth()) { if (error == null) CircularProgressIndicator() }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel, enabled = !busy) { Text("放弃此照片") }
                    if (preview != null) {
                        OutlinedButton(onClick = { preview = null; error = null }, enabled = !busy) { Text("重新框选") }
                    } else {
                        OutlinedButton(onClick = {
                            val bitmap = original ?: return@OutlinedButton
                            original = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, true)
                            corners = DocumentPageGeometry.fullPage
                        }, enabled = original != null && !busy) { Text("旋转") }
                    }
                }
                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    val bitmap = original ?: return@Button
                    busy = true; error = null
                    scope.launch {
                        try {
                            val checked = preview
                            if (checked == null) {
                                preview = withContext(Dispatchers.Default) { cropDocumentPage(bitmap, corners) }
                            } else {
                                val saved = withContext(Dispatchers.IO) { saveDocumentPage(context, checked) }
                                onUse(saved)
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "页面处理失败，请重新框选或重拍。"
                        } finally { busy = false }
                    }
                }, enabled = image != null && !busy) { Text(if (busy) "处理中…" else if (preview == null) "裁切并预览" else "使用此页面") }
            }
        }
    }
}

@Composable
private fun PageSelection(bitmap: Bitmap, corners: List<PagePoint>, enabled: Boolean, onChange: (List<PagePoint>) -> Unit, modifier: Modifier) {
    val latestCorners by rememberUpdatedState(corners)
    val latestChange by rememberUpdatedState(onChange)
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier.pointerInput(bitmap, enabled) {
        var selected = -1
        fun bounds(): Pair<Float, Offset> {
            val scale = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
            return scale to Offset((size.width - bitmap.width * scale) / 2, (size.height - bitmap.height * scale) / 2)
        }
        detectDragGestures(onDragStart = { touch ->
            val (scale, origin) = bounds()
            selected = if (enabled) latestCorners.indices.minByOrNull { index ->
                val p = latestCorners[index]
                (touch - (origin + Offset(p.x * bitmap.width * scale, p.y * bitmap.height * scale))).getDistance()
            }?.takeIf { index ->
                val p = latestCorners[index]
                (touch - (origin + Offset(p.x * bitmap.width * scale, p.y * bitmap.height * scale))).getDistance() <= 48.dp.toPx()
            } ?: -1 else -1
        }, onDragEnd = { selected = -1 }, onDragCancel = { selected = -1 }) { change, _ ->
            if (selected >= 0) {
                change.consume()
                val (scale, origin) = bounds()
                val p = change.position - origin
                val updated = latestCorners.toMutableList().apply {
                    this[selected] = PagePoint((p.x / (bitmap.width * scale)).coerceIn(0f, 1f), (p.y / (bitmap.height * scale)).coerceIn(0f, 1f))
                }
                if (DocumentPageGeometry.isValid(updated)) latestChange(updated)
            }
        }
    }) {
        val scale = min(size.width / bitmap.width, size.height / bitmap.height)
        val width = bitmap.width * scale; val height = bitmap.height * scale
        val origin = Offset((size.width - width) / 2, (size.height - height) / 2)
        drawImage(image, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), dstSize = IntSize(width.roundToInt(), height.roundToInt()))
        val points = corners.map { origin + Offset(it.x * width, it.y * height) }
        val outline = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        val shade = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(origin.x, origin.y, origin.x + width, origin.y + height))
            addPath(outline)
        }
        drawPath(shade, Color.Black.copy(alpha = 0.6f))
        drawPath(outline, Color(0xFF008577), style = Stroke(3.dp.toPx()))
        points.forEach { drawCircle(Color.White, 12.dp.toPx(), it); drawCircle(Color(0xFF008577), 8.dp.toPx(), it) }
    }
}

private fun readDocumentPhoto(context: Context, uri: Uri): Bitmap {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, options) }
    // Bound memory while retaining enough detail for handwriting review.
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 3200) options.inSampleSize *= 2
    options.inJustDecodeBounds = false
    val bitmap = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, options) } ?: error("Invalid image")
    val orientation = context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { bitmap.recycle() }
}

private fun cropDocumentPage(bitmap: Bitmap, corners: List<PagePoint>): Bitmap {
    val (width, height) = DocumentPageGeometry.outputSize(corners, bitmap.width, bitmap.height)
    val source = corners.flatMap { listOf(it.x * bitmap.width, it.y * bitmap.height) }.toFloatArray()
    val destination = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
    val matrix = Matrix()
    check(matrix.setPolyToPoly(source, 0, destination, 0, 4))
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        android.graphics.Canvas(this).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }
}

private fun saveDocumentPage(context: Context, bitmap: Bitmap): Uri {
    val directory = File(context.cacheDir, "photos").apply { mkdirs() }
    val file = File.createTempFile("page-", ".jpg", directory)
    try {
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (error: Exception) { file.delete(); throw error }
}
