package com.homeworkbuddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class AppUpdate(val versionName: String, val versionCode: Long, val notes: String, val downloadUrl: String, val sizeBytes: Long)

class AppUpdateCheckException(message: String) : Exception(message)

object AppUpdateClient {
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val endpoint = URL(BuildConfig.APP_UPDATE_URL + "?versionCode=" + BuildConfig.VERSION_CODE)
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            if (status != 200) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw AppUpdateCheckException(errorMessage(status, body))
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body, endpoint, BuildConfig.VERSION_CODE.toLong(), BuildConfig.APPLICATION_ID)
        } finally { connection.disconnect() }
    }

    internal fun errorMessage(status: Int, body: String): String {
        val code = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        return when {
            status == 404 && code == "app_not_found" -> "检查更新失败：服务端尚未配置本应用，请联系维护者。"
            status == 502 && code == "update_provider_unavailable" -> "检查更新失败：更新服务暂不可用，请稍后重试。"
            else -> "检查更新失败（HTTP $status），请稍后重试。"
        }
    }

    internal fun parse(body: String, endpoint: URL, installed: Long, packageName: String): AppUpdate? {
        val json = JSONObject(body)
        if (!json.getBoolean("hasUpdate")) return null
        val appPath = endpoint.path.removeSuffix("/update")
        require(json.getString("appId") == appPath.substringAfterLast('/')) { "更新应用不匹配" }
        val versionCode = json.getLong("versionCode")
        require(versionCode > installed && json.getString("packageName") == packageName) { "更新信息与当前应用不匹配" }
        val download = URL(endpoint, json.getString("downloadUrl"))
        require(download.protocol == "https" && download.host == endpoint.host && download.port == endpoint.port && download.userInfo == null && download.path == "$appPath/download" && download.ref == null) { "更新下载地址无效" }
        require(Instant.parse(json.getString("expiresAt")).isAfter(Instant.now())) { "下载地址已过期，请重试" }
        val size = json.getLong("sizeBytes")
        require(size > 0) { "安装包大小无效" }
        return AppUpdate(json.getString("versionName"), versionCode, json.optString("releaseNotes"), download.toString(), size)
    }
}
