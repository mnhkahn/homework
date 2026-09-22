package com.homeworkbuddy

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Sends a paired tablet's photo to the server-side vision model for MCP calls. */
object VisionExplainUploader {
    fun explain(context: Context, photo: JSONObject, question: String): String {
        val config = XiaoliDeviceStore.config(context)
            ?: throw McpException(-32010, "设备尚未配对")
        val jpeg = photo.optString("image_base64").takeIf { it.isNotBlank() }
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: throw McpException(-32004, "拍照失败：未取得 JPEG 数据")
        if (jpeg.size > 2 * 1024 * 1024) throw McpException(-32005, "拍照失败：图片压缩后仍超过 2 MB")

        val boundary = "----HomeworkBuddy${UUID.randomUUID()}"
        val body = multipartBody(boundary, jpeg, question)
        val endpoint = explainEndpoint(config.websocketUrl)
        val connection = try {
            (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 45_000
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Device-Id", config.deviceId)
                setRequestProperty("Authorization", "Bearer ${config.token}")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
            }
        } catch (error: Throwable) {
            throw McpException(-32005, "视觉分析上传失败：无效的服务端地址", error)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseBody).optString("error") }.getOrDefault("")
                    .ifBlank { responseBody.ifBlank { "HTTP $status" } }
                throw McpException(-32005, "视觉分析失败：$message")
            }
            JSONObject(responseBody).optString("response").ifBlank {
                throw McpException(-32005, "视觉分析失败：服务端未返回 response")
            }
        } catch (error: McpException) {
            throw error
        } catch (error: Throwable) {
            throw McpException(-32005, "视觉分析失败：${error.message ?: "网络错误"}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun multipartBody(boundary: String, jpeg: ByteArray, question: String): ByteArray = ByteArrayOutputStream().use { output ->
        fun text(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
        text("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
        output.write(jpeg)
        text("\r\n--$boundary\r\nContent-Disposition: form-data; name=\"question\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n")
        text(question)
        text("\r\n--$boundary--\r\n")
        output.toByteArray()
    }

    private fun explainEndpoint(websocketUrl: String): String {
        val websocket = URI(websocketUrl)
        val scheme = when (websocket.scheme?.lowercase()) {
            "wss" -> "https"
            "ws" -> "http"
            else -> throw IllegalArgumentException("WebSocket 地址无效")
        }
        require(!websocket.host.isNullOrBlank()) { "WebSocket 地址无效" }
        return URI(scheme, websocket.userInfo, websocket.host, websocket.port, "/mcp/vision/explain", null, null).toString()
    }
}
