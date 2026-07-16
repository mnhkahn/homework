package com.homeworkbuddy

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

private const val serviceUrl = "https://www.cyeam.com"

data class Pairing(val id: String, val state: String, val secret: String, val authorizeUrl: String)
data class TrelloOption(val id: String, val name: String)
data class PairingStatus(val authorized: Boolean, val configured: Boolean)

class HomeworkApi(private val context: Context) {
    private val prefs = context.getSharedPreferences("homework_connection", Context.MODE_PRIVATE)
    val isConnected get() = prefs.contains("device_secret")

    suspend fun createPairing(): Pairing = request("POST", "/api/homework/pairings").let {
        Pairing(it.getString("pairing_id"), it.getString("pairing_state"), it.getString("pairing_secret"), it.getString("authorize_url"))
    }

    fun openAuthorization(pairing: Pairing) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pairing.authorizeUrl)))

    suspend fun pairingStatus(pairing: Pairing): PairingStatus {
        val connection = request("GET", "/api/homework/pairings/status?id=${Uri.encode(pairing.id)}", headers = mapOf("X-Pairing-Secret" to pairing.secret))
        return PairingStatus(connection.optBoolean("authorized"), connection.optBoolean("configured"))
    }

    fun finishPairing(pairing: Pairing) {
        prefs.edit().putString("device_secret", pairing.secret).apply()
    }

    suspend fun boards(pairing: Pairing): List<TrelloOption> = options("/api/homework/trello/boards?state=${Uri.encode(pairing.state)}")

    suspend fun initialize(pairing: Pairing, boardId: String) {
        postJson("/api/homework/trello/initialize", JSONObject().put("state", pairing.state).put("board_id", boardId))
        finishPairing(pairing)
    }

    private suspend fun options(path: String): List<TrelloOption> {
        val value = requestArray("GET", path)
        return (0 until value.length()).map { value.getJSONObject(it).let { item -> TrelloOption(item.getString("id"), item.getString("name")) } }
    }

    suspend fun todayTasks(): List<HomeworkTask> {
        val value = requestArray("GET", "/api/homework/tasks?date=${java.time.LocalDate.now()}&include_completed=true", auth = true)
        return (0 until value.length()).map { index ->
            val item = value.getJSONObject(index)
            HomeworkTask(
                id = item.getString("id"), subject = item.optString("subject", "作业"),
                title = item.getString("title"), estimatedMinutes = item.optInt("estimated_minutes", 20),
                deadline = OffsetDateTime.parse(item.getString("deadline")).atZoneSameInstant(ZoneId.systemDefault()).toLocalTime(),
                status = if (item.optBoolean("completed")) TaskStatus.COMPLETED else TaskStatus.TODO,
            )
        }
    }

    suspend fun submit(taskId: String, photo: Uri?, isOvertime: Boolean, submissionId: String) = withContext(Dispatchers.IO) {
        val boundary = "Homework-${UUID.randomUUID()}"
        val connection = (URL("$serviceUrl/api/homework/tasks/${Uri.encode(taskId)}/submit").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer ${prefs.getString("device_secret", "")}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        connection.outputStream.buffered().use { out ->
            fun field(name: String, value: String) { out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()) }
            field("submission_id", submissionId); field("is_overtime", isOvertime.toString())
            if (photo != null) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"homework.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                context.contentResolver.openInputStream(photo)?.use { input -> input.copyTo(out) } ?: error("无法读取作业照片")
            }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) throw IllegalStateException(JSONObject(text).optString("error", "提交失败"))
    }

    private suspend fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), auth: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requestText(method, path, headers, auth))
    }

    private suspend fun requestArray(method: String, path: String, auth: Boolean = false): JSONArray = withContext(Dispatchers.IO) {
        JSONArray(requestText(method, path, auth = auth))
    }

    private suspend fun postJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(serviceUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/json"); setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrDefault("连接服务失败"))
        JSONObject(text)
    }

    private fun requestText(method: String, path: String, headers: Map<String, String> = emptyMap(), auth: Boolean = false): String {
        val connection = (URL(serviceUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 10_000; readTimeout = 10_000; setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (auth) setRequestProperty("Authorization", "Bearer ${prefs.getString("device_secret", "")}")
        }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) throw IllegalStateException(JSONObject(text).optString("error", "连接服务失败"))
        return text
    }
}
