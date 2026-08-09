package com.homeworkbuddy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes a timestamp into captured media before it leaves the tablet. */
object CaptureWatermark {
    private val formatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    }

    fun draw(source: Bitmap, capturedAt: Long = System.currentTimeMillis()): Bitmap {
        val bitmap = if (source.isMutable) source else source.copy(Bitmap.Config.ARGB_8888, true)
        val text = formatter.get().format(Date(capturedAt))
        val scale = (bitmap.width / 1280f).coerceIn(0.55f, 1.5f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f * scale
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }
        val padding = 10f * scale
        val margin = 16f * scale
        val textWidth = textPaint.measureText(text)
        val height = textPaint.fontMetrics.run { bottom - top }
        val left = bitmap.width - margin - textWidth - padding * 2
        val top = margin
        val right = bitmap.width - margin
        val bottom = top + height + padding * 2
        val brightness = averageBrightness(bitmap, left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        val textColor = if (brightness < 140) Color.WHITE else Color.rgb(20, 20, 20)
        val outlineColor = if (brightness < 140) Color.argb(190, 0, 0, 0) else Color.argb(190, 255, 255, 255)
        val baseline = top + padding - textPaint.fontMetrics.top
        Canvas(bitmap).apply {
            drawText(text, left + padding, baseline, textPaint.apply {
                color = outlineColor
                style = Paint.Style.STROKE
                strokeWidth = 3.5f * scale
            })
            drawText(text, left + padding, baseline, textPaint.apply {
                color = textColor
                style = Paint.Style.FILL
            })
        }
        return bitmap
    }

    private fun averageBrightness(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var total = 0L
        var count = 0
        val step = (bitmap.width / 80).coerceAtLeast(1)
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(bitmap.height) step step) {
            for (x in left.coerceAtLeast(0) until right.coerceAtMost(bitmap.width) step step) {
                val color = bitmap.getPixel(x, y)
                total += (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
                count++
            }
        }
        return if (count == 0) 0 else (total / count).toInt()
    }
}
