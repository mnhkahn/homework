package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class VisionExplainUploaderTest {
    @Test fun includesBothImagesAndUnicodeQuestion() {
        val photo = byteArrayOf(0, 1, -1, 13, 10)
        val screen = byteArrayOf(2, -2, 3)
        val body = VisionExplainUploader.multipartBody("test-boundary", photo, "在看什么？", screen)
        val wire = body.toString(Charsets.ISO_8859_1)
        assertTrue(wire.contains("name=\"image\"; filename=\"photo.jpg\""))
        assertTrue(wire.contains(photo.toString(Charsets.ISO_8859_1)))
        assertTrue(wire.contains("name=\"screenshot\"; filename=\"screenshot.jpg\""))
        assertTrue(wire.contains(screen.toString(Charsets.ISO_8859_1)))
        assertTrue(body.toString(Charsets.UTF_8).contains("在看什么？"))
        assertTrue(wire.endsWith("--test-boundary--\r\n"))
    }

    @Test fun failedScreenshotKeepsPhotoAndReportsReason() {
        val wire = VisionExplainUploader.multipartBody("test-boundary", byteArrayOf(1), "检查", screenshotError = "服务未授权").toString(Charsets.UTF_8)
        assertTrue(wire.contains("filename=\"photo.jpg\""))
        assertFalse(wire.contains("filename=\"screenshot.jpg\""))
        assertTrue(wire.contains("name=\"screenshot_error\"\r\n\r\n服务未授权"))
    }
}
