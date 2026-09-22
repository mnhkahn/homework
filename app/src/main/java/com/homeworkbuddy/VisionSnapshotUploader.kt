package com.homeworkbuddy

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** Uploads a captured JPEG using the credentials issued during Android pairing. */
object VisionSnapshotUploader {
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun upload(context: Context, photo: JSONObject, requestedResolution: String): JSONObject {
        val config = XiaoliDeviceStore.config(context)
            ?: throw McpException(-32010, "设备尚未配对")
        val jpeg = photo.optString("image_base64").takeIf { it.isNotBlank() }
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: throw McpException(-32004, "拍照失败：未取得 JPEG 数据")
        if (jpeg.size > MAX_BYTES) throw McpException(-32005, "拍照失败：图片压缩后仍超过 2 MB")

        val endpoint = config.snapshotUrl ?: snapshotEndpoint(config.websocketUrl)
        val connection = try {
            (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 20_000
                setFixedLengthStreamingMode(jpeg.size)
                setRequestProperty("Device-Id", config.deviceId)
                setRequestProperty("Authorization", "Bearer ${config.token}")
                setRequestProperty("Content-Type", "image/jpeg")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Xiaoli-Resolution", requestedResolution)
                setRequestProperty("X-Xiaoli-Width", photo.optInt("width").toString())
                setRequestProperty("X-Xiaoli-Height", photo.optInt("height").toString())
            }
        } catch (error: Throwable) {
            throw McpException(-32005, "图片上传失败：无效的服务端地址", error)
        }

        return try {
            connection.outputStream.use { it.write(jpeg) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("error") }.getOrDefault("").ifBlank { "HTTP $status" }
                throw McpException(-32005, "图片上传失败：$message")
            }
            val server = JSONObject(body)
            JSONObject()
                .put("uploaded", true)
                .put("preview_url", server.optString("preview_url").ifBlank { server.optString("url") })
                .put("image_size", server.optLong("image_size", jpeg.size.toLong()))
                .put("width", server.optInt("width", photo.optInt("width")))
                .put("height", server.optInt("height", photo.optInt("height")))
                .put("resolution", server.optString("resolution", requestedResolution))
                .put("stream_event_id", server.optString("stream_event_id").ifBlank { server.optString("event_id") })
        } catch (error: McpException) {
            throw error
        } catch (error: Throwable) {
            throw McpException(-32005, "图片上传失败：${error.message ?: "网络错误"}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun snapshotEndpoint(websocketUrl: String): String {
        val websocket = URI(websocketUrl)
        val scheme = when (websocket.scheme?.lowercase()) {
            "wss" -> "https"
            "ws" -> "http"
            else -> throw IllegalArgumentException("WebSocket 地址无效")
        }
        require(!websocket.host.isNullOrBlank()) { "WebSocket 地址无效" }
        return URI(scheme, websocket.userInfo, websocket.host, websocket.port, "/mcp/vision/snapshot", null, null).toString()
    }
}

/** JSON-RPC errors are intentional protocol responses, rather than silent MCP timeouts. */
class McpException(val rpcCode: Int, override val message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
