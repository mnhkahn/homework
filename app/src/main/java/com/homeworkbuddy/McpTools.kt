package com.homeworkbuddy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * MCP tool registry served over the Xiaoli websocket. Each tool is one
 * registration; definitions() and call() both walk this list, so adding a
 * tool is a single entry instead of touching two places.
 */
object McpTools {
    private data class ToolRegistration(
        val name: String,
        val description: String,
        val inputSchema: JSONObject,
        val handler: (Context, JSONObject) -> JSONObject,
    )

    private fun emptySchema() = JSONObject().put("type", "object").put("properties", JSONObject())

    private val tools = listOf(
        ToolRegistration("self.device.get_status", "查询平板与小李连接状态", emptySchema()) { context, _ ->
            JSONObject()
                .put("device_id", XiaoliDeviceStore.config(context)?.deviceId ?: XiaoliDeviceStore.defaultDeviceId(context))
                .put("connection", XiaoliConnectionState.status)
                .put("last_connected_at", XiaoliConnectionState.lastConnectedAt?.let { Instant.ofEpochMilli(it).toString() })
                .put("battery_percent", context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("kiosk_mode", KioskPolicy(context).mode().name.lowercase())
        },
        ToolRegistration("self.homework.get_status", "查询今天作业与当前学习状态", emptySchema()) { context, _ ->
            HomeworkStatusStore(context).snapshot()
        },
        ToolRegistration("self.homework.get_weekly_report", "查询本周作业完成周报", emptySchema()) { context, _ ->
            WeeklyReport(context).toJson()
        },
        ToolRegistration("self.notify.send", "在平板显示通知", JSONObject().put("type", "object").put("properties", JSONObject().put("title", JSONObject().put("type", "string")).put("body", JSONObject().put("type", "string"))).put("required", JSONArray().put("title").put("body"))) { context, arguments ->
            notify(context, arguments.getString("title"), arguments.getString("body"))
            JSONObject().put("delivered", true)
        },
        ToolRegistration("self.audio_speaker.play_ogg_url", "播放服务端提供的 Ogg/Opus 音频 URL", JSONObject().put("type", "object").put("properties", JSONObject().put("url", JSONObject().put("type", "string").put("format", "uri"))).put("required", JSONArray().put("url"))) { context, arguments ->
            runBlocking { RemoteAudioPlayer.play(context, arguments.getString("url")) }
        },
        ToolRegistration("self.audio_speaker.stop", "停止当前音频播放", emptySchema()) { context, _ ->
            RemoteAudioPlayer.stop(context)
        },
        ToolRegistration("self.camera.take_photo", "拍摄学习照片并由小李视觉模型回答检查问题", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("question", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("question"))) { context, arguments ->
            val question = arguments.optString("question").trim()
            if (question.isBlank()) throw McpException(-32602, "self.camera.take_photo 缺少 question")
            val photo = runBlocking { RemotePhotoCoordinator.take(context, "vga") }
            JSONObject().put("raw_response", VisionExplainUploader.explain(context, photo, question))
        },
        ToolRegistration("self.camera.snapshot", "拍摄学习照片并安全上传到小李服务端", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("resolution", JSONObject()
                .put("type", "string")
                .put("enum", JSONArray().put("qqvga").put("qvga").put("vga").put("svga"))))) { context, arguments ->
            val resolution = arguments.optString("resolution", "vga").lowercase()
            if (resolution !in setOf("qqvga", "qvga", "vga", "svga")) {
                throw McpException(-32602, "resolution 必须为 qqvga、qvga、vga 或 svga")
            }
            val photo = runBlocking { RemotePhotoCoordinator.take(context, resolution) }
            VisionSnapshotUploader.upload(context, photo, resolution)
        },
        ToolRegistration("self.camera.record_video", "请求在平板前台录制一段学习小视频", JSONObject().put("type", "object").put("properties", JSONObject().put("max_seconds", JSONObject().put("type", "integer").put("description", "最长录制秒数，默认 15，上限 30")))) { context, arguments ->
            val maxSeconds = arguments.optInt("max_seconds", 15).coerceIn(1, 30)
            runBlocking { RemoteVideoCoordinator.record(context, maxSeconds) }
        },
        ToolRegistration("self.camera.start_stream", "开始共享学习画面（平板会提示正在共享）", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("fps", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 3).put("description", "每秒帧数，范围 1-3"))
            .put("duration_sec", JSONObject().put("type", "integer").put("description", "最长共享秒数；未插电上限 60 秒，插电时上限 180 秒"))
            .put("resolution", JSONObject().put("type", "string").put("enum", JSONArray().put("qqvga").put("qvga").put("vga").put("svga")))
        )) { context, arguments ->
            runBlocking {
                RemoteStreamCoordinator.start(
                    context,
                    arguments.optInt("fps", 1).coerceIn(1, 3),
                    arguments.optInt("duration_sec", 0),
                    arguments.optString("resolution", "qqvga"),
                )
            }
        },
        ToolRegistration("self.camera.stop_stream", "停止共享学习画面", emptySchema()) { _, _ ->
            RemoteStreamCoordinator.stop()
        },
        ToolRegistration("self.kiosk.pause_15_minutes", "临时解除学习模式限制 15 分钟", emptySchema()) { context, _ ->
            val policy = KioskPolicy(context)
            if (!policy.isDeviceOwner) throw IllegalStateException("平板尚未配置为 Device Owner，无法解除学习限制")
            policy.pause(15)
            JSONObject().put("paused_minutes", 15).put("paused_until", Instant.ofEpochMilli(policy.pausedUntil).toString())
        },
    )

    fun definitions(): JSONArray = JSONArray().also { array ->
        tools.forEach { tool ->
            array.put(JSONObject().put("name", tool.name).put("description", tool.description).put("inputSchema", tool.inputSchema))
        }
    }

    fun call(context: Context, params: JSONObject): JSONObject {
        val name = params.optString("name")
        if (name.isBlank()) throw McpException(-32602, "tools/call 缺少工具名称")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val tool = tools.firstOrNull { it.name == name } ?: throw McpException(-32601, "未注册工具：$name")
        val result = tool.handler(context, arguments)
        // The Xiaoli admin preview reads image fields from the tool result.  Do
        // not hide a JPEG inside MCP's human-readable text content.
        if (result.has("image_base64")) {
            return JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "已拍摄一张照片")))
                .put("mime_type", result.optString("mime_type", "image/jpeg"))
                .put("image_base64", result.getString("image_base64"))
        }
        if (result.has("raw_response")) {
            return JSONObject().put("content", JSONArray().put(JSONObject()
                .put("type", "text").put("text", result.getString("raw_response"))))
        }
        // Keep structured snapshot metadata at the top level as well.  The
        // management console can render its preview without parsing text
        // content, while this remains a valid MCP tool result for other hosts.
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.toString())))
            .put("uploaded", result.optBoolean("uploaded", false))
            .put("preview_url", result.optString("preview_url"))
            .put("image_size", result.optLong("image_size", 0))
            .put("width", result.optInt("width", 0))
            .put("height", result.optInt("height", 0))
            .put("resolution", result.optString("resolution"))
            .put("stream_event_id", result.optString("stream_event_id"))
    }

    private fun notify(context: Context, title: String, body: String) {
        RemoteNoticeStore(context).save(title, body)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("xiaoli_requests", "小李通知", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), NotificationCompat.Builder(context, "xiaoli_requests")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setAutoCancel(true).build())
    }
}
