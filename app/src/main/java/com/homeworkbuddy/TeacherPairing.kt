package com.homeworkbuddy

import java.net.URI
import org.json.JSONObject

data class TeacherConnectionConfig(
    val deviceId: String,
    val deviceName: String,
    val websocketUrl: String,
    val token: String,
)

/** Only mr-li gateway v1 QR codes, credentials and handshakes are accepted. */
internal object TeacherPairing {
    const val SERVICE = "mr-li"
    const val PROTOCOL = 1
    data class Request(val url: String, val code: String)

    fun isGateway(value: JSONObject): Boolean =
        value.optString("service") == SERVICE && value.optInt("protocol", -1) == PROTOCOL

    fun request(rawQr: String): Request {
        val qr = JSONObject(rawQr.trim())
        require(isGateway(qr)) { "请扫描李老师新生成的配对二维码，旧版二维码已不再支持" }
        val url = qr.getString("pair_url").trim()
        endpoint(url, setOf("http", "https"), "/tablet/v1/pair", "李老师配对地址无效")
        val code = qr.getString("code").trim()
        require(code.isNotEmpty()) { "二维码缺少配对码" }
        return Request(url, code)
    }

    fun response(result: JSONObject): TeacherConnectionConfig {
        require(isGateway(result)) { "此服务端不是支持的李老师网关" }
        val device = result.getJSONObject("device")
        val connection = result.getJSONObject("connection")
        val url = connection.getString("url").trim()
        endpoint(url, setOf("ws", "wss"), "/tablet/v1/ws", "李老师连接地址无效")
        val id = device.getString("id").trim()
        val name = device.getString("name").trim()
        val token = connection.getString("token").trim()
        require(id.isNotEmpty() && name.isNotEmpty() && token.isNotEmpty()) { "服务端未返回完整的设备凭证，请重新扫码" }
        return TeacherConnectionConfig(id, name, url, token)
    }

    fun isConnectionUrl(url: String): Boolean = runCatching {
        endpoint(url, setOf("ws", "wss"), "/tablet/v1/ws", "李老师连接地址无效")
    }.isSuccess

    private fun endpoint(value: String, schemes: Set<String>, path: String, message: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null && uri.scheme?.lowercase() in schemes && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null && uri.query == null && uri.path == path) { message }
    }
}
