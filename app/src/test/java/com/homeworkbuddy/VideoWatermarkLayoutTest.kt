package com.homeworkbuddy

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoWatermarkLayoutTest {
    @Test fun lowResolutionCaptureGetsReadableTextWithoutChangingAspectRatio() {
        assertEquals(640 to 480, readableStreamFrameSize(160, 120))
        assertEquals(640 to 480, readableStreamFrameSize(320, 240))
        assertEquals(640 to 480, readableStreamFrameSize(640, 480))
        assertEquals(800 to 600, readableStreamFrameSize(800, 600))
        assertEquals(1280 to 720, readableStreamFrameSize(1280, 720))
        val (width, height) = readableStreamFrameSize(120, 160)
        assertEquals(120f / 160, width.toFloat() / height, .001f)
        org.junit.Assert.assertTrue(28f * videoWatermarkScale(width, height) >= 15f)
    }

    @Test fun relativeFontSizeAndMarginsStayConstantAcrossResolutions() {
        for ((width, height) in listOf(160 to 120, 320 to 240, 640 to 480, 800 to 600, 1280 to 960, 2560 to 1920)) {
            val scale = videoWatermarkScale(width, height)
            assertEquals(28f * .55f / 480, 28f * scale / height, .00001f)
            assertEquals(16f * .55f / 640, 16f * scale / width, .00001f)
        }
    }

    @Test fun compactPoseIncludesAllMetricsAndIdentifiesSnapshot() {
        assertEquals(listOf("录制前坐姿 · 相对距离 1.25×", "仰角 -19°  左右转角 +8°"),
            HeadPosePhotoSnapshot(-19f, 8f, 1.25f).compactWatermarkLines("录制前坐姿"))
        assertEquals(listOf("共享前坐姿 · 相对距离 —", "仰角 —  左右转角 —"),
            HeadPosePhotoSnapshot(null, null, null).compactWatermarkLines("共享前坐姿"))
    }
}
