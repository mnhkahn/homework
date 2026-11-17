package com.homeworkbuddy

import java.net.URI
import org.json.JSONObject

data class XiaoliConnectionConfig(
    val deviceId: String,
    val deviceName: String,
    val websocketUrl: String,
    val token: String,
    /** Optional explicit endpoint supplied by newer pairing servers. */
    val snapshotUrl: String? = null,
)


/** mr-li pairing uses the existing device protocol, with server addresses supplied by the QR. */
internal object TeacherPairing {
    data class Request(val url: String, val code: String)

    fun request(rawQr: String): Request {
        val qr = JSONObject(rawQr.trim())
        val url = qr.getString("pair_url").trim()
        endpoint(url, setOf("http", "https"), "配对地址无效，请扫描李老师生成的二维码")
        val code = qr.getString("code").trim()
        require(code.isNotEmpty()) { "二维码缺少配对码" }
        return Request(url, code)
    }

    fun response(result: JSONObject, deviceId: String, deviceName: String): XiaoliConnectionConfig {
        val device = result.optJSONObject("device")
        val websocket = result.getJSONObject("websocket")
        val url = websocket.getString("url").trim()
        endpoint(url, setOf("ws", "wss"), "服务端未返回有效的连接地址")
        val token = websocket.getString("token").trim()
        require(token.isNotEmpty()) { "服务端未返回设备凭证，请重新扫码" }
        val snapshot = result.optString("vision_snapshot_url").ifBlank { result.optString("snapshot_url") }
            .takeIf { it.isNotBlank() }
        snapshot?.let { endpoint(it, setOf("http", "https"), "服务端返回的照片上传地址无效") }
        return XiaoliConnectionConfig(
            result.optString("device_id").ifBlank { device?.optString("id").orEmpty() }.ifBlank { deviceId },
            device?.optString("name").orEmpty().ifBlank { deviceName },
            url, token, snapshot,
        )
    }

    private fun endpoint(value: String, schemes: Set<String>, message: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null && uri.scheme?.lowercase() in schemes && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { message }
    }
}
