package com.homeworkbuddy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Burns the compact watermark into the exported MP4, preserving the recorded audio. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object VideoWatermark {
    suspend fun export(context: Context, source: File, destination: File, startedAt: Long, lines: List<String>) =
        withContext(Dispatchers.Main) {
            val completion = CompletableDeferred<Unit>()
            val overlay = object : BitmapOverlay() {
                private var bitmap: Bitmap? = null
                private var lastSecond = Long.MIN_VALUE

                override fun configure(videoSize: Size) {
                    super.configure(videoSize)
                    bitmap?.recycle()
                    bitmap = Bitmap.createBitmap(videoSize.width, videoSize.height, Bitmap.Config.ARGB_8888)
                    lastSecond = Long.MIN_VALUE
                }

                override fun getBitmap(presentationTimeUs: Long): Bitmap {
                    val frame = checkNotNull(bitmap)
                    val second = presentationTimeUs / 1_000_000
                    if (second != lastSecond) {
                        frame.eraseColor(Color.TRANSPARENT)
                        CaptureWatermark.draw(frame, startedAt + second * 1_000, lines, compact = true)
                        lastSecond = second
                    }
                    return frame
                }

                override fun release() {
                    super.release()
                    bitmap?.recycle()
                    bitmap = null
                }
            }
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
                .build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        completion.complete(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        completion.completeExceptionally(exportException)
                    }
                }).build()
            try {
                transformer.start(item, destination.absolutePath)
                withTimeout(60_000) { completion.await() }
            } finally {
                transformer.cancel()
                if (!completion.isCompleted || completion.isCancelled) destination.delete()
            }
        }
}
