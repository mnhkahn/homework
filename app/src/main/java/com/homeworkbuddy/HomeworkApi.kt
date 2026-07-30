package com.homeworkbuddy

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

data class TrelloOption(val id: String, val name: String)

/**
 * Trello is the source of truth for homework.  This client talks to Trello from
 * the tablet; the user's token never passes through cyeam.com.
 */
class HomeworkApi(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val token: String? get() = prefs.getString(TOKEN, null)?.let(DeviceTokenCipher::decrypt)
    val hasAuthorization get() = token != null
    val isConnected get() = token != null && prefs.contains(TODO_LIST) && prefs.contains(DONE_LIST)

    fun openAuthorization() {
        val url = Uri.Builder()
            .scheme("https").authority("trello.com").appendPath("1").appendPath("authorize")
            .appendQueryParameter("expiration", "30days")
            .appendQueryParameter("scope", "read,write")
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("callback_method", "fragment")
            .appendQueryParameter("return_url", CALLBACK_URL)
            .appendQueryParameter("key", BuildConfig.TRELLO_API_KEY)
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, url))
    }

    suspend fun boards(): List<TrelloOption> = options("/members/me/boards?fields=id,name")

    suspend fun initialize(boardId: String) {
        val lists = options("/boards/${segment(boardId)}/lists?fields=id,name&filter=open").toMutableList()
        suspend fun listNamed(name: String): TrelloOption {
            return lists.firstOrNull { it.name == name } ?: createList(boardId, name).also(lists::add)
        }
        val todo = listNamed(TODO_LIST_NAME)
        val done = listNamed(DONE_LIST_NAME)
        prefs.edit().putString(TODO_LIST, todo.id).putString(DONE_LIST, done.id).apply()
    }

    fun clearConnection() = prefs.edit().clear().apply()

    suspend fun todayTasks(): List<HomeworkTask> {
        val todo = cards(prefs.getString(TODO_LIST, "") ?: "", TaskStatus.TODO)
        val done = cards(prefs.getString(DONE_LIST, "") ?: "", TaskStatus.COMPLETED)
        return (todo + done).sortedBy { it.deadline }
    }

    suspend fun submit(taskId: String, photos: List<Uri>, isOvertime: Boolean, submissionId: String) = withContext(Dispatchers.IO) {
        // Trello is the durable record. Submission IDs make attachment retries
        // idempotent: a retry after a partial upload never adds another photo.
        isOvertime.hashCode()
        val existingNames = attachmentNames(taskId)
        photos.take(MAX_PHOTOS).forEachIndexed { index, photo ->
            val name = "homework-$submissionId-${index + 1}.jpg"
            if (name !in existingNames) attachPhoto(taskId, photo, name)
        }
        check(photos.isNotEmpty() || !PhotoCompletionGuard(context).isRequired(taskId)) {
            "该作业已选择拍照完成，不能无照片提交"
        }
        request("PUT", "/cards/${segment(taskId)}", mapOf("idList" to requiredDoneList(), "dueComplete" to "true"))
    }

    private suspend fun cards(listId: String, status: TaskStatus): List<HomeworkTask> {
        require(listId.isNotBlank()) { "请由家长选择作业看板" }
        val value = requestArray("GET", "/lists/${segment(listId)}/cards?fields=id,name,desc,due,labels")
        val now = ZonedDateTime.now(ZoneOffset.ofHours(8))
        val today = now.toLocalDate()
        return (0 until value.length()).mapNotNull { index ->
            val item = value.getJSONObject(index)
            val due = item.optString("due").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val deadline = runCatching { OffsetDateTime.parse(due).atZoneSameInstant(ZoneOffset.ofHours(8)) }.getOrNull() ?: return@mapNotNull null
            if (deadline.toLocalDate() != today) return@mapNotNull null
            val labels = item.optJSONArray("labels")
            val subject = labels?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() } ?: "作业"
            val taskStatus = if (status == TaskStatus.TODO && !deadline.isAfter(now)) TaskStatus.OVERTIME else status
            HomeworkTask(item.getString("id"), subject, item.getString("name"), homeworkMinutes(item.optString("desc")), deadline.toLocalTime(), taskStatus)
        }
    }

    private suspend fun createList(boardId: String, name: String): TrelloOption {
        val value = request("POST", "/boards/${segment(boardId)}/lists", mapOf("name" to name, "pos" to "bottom"))
        return TrelloOption(value.getString("id"), value.getString("name"))
    }

    private suspend fun attachmentNames(taskId: String): Set<String> {
        val value = requestArray("GET", "/cards/${segment(taskId)}/attachments?fields=name")
        return (0 until value.length()).map { value.getJSONObject(it).optString("name") }.toSet()
    }

    private fun attachPhoto(taskId: String, photo: Uri, name: String) {
        val boundary = "Trello-${UUID.randomUUID()}"
        val connection = connection("POST", "/cards/${segment(taskId)}/attachments", emptyMap()).apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(connection.outputStream).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            field("name", name)
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: image/jpeg\r\n\r\n")
            context.contentResolver.openInputStream(photo)?.use { it.copyTo(out) } ?: error("无法读取作业照片")
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        read(connection)
    }

    private suspend fun options(path: String): List<TrelloOption> {
        val value = requestArray("GET", path)
        return (0 until value.length()).map { value.getJSONObject(it).let { item -> TrelloOption(item.getString("id"), item.getString("name")) } }
    }

    private suspend fun request(method: String, path: String, values: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(read(connection(method, path, values)))
    }

    private suspend fun requestArray(method: String, path: String): JSONArray = withContext(Dispatchers.IO) {
        JSONArray(read(connection(method, path, emptyMap())))
    }

    private fun connection(method: String, path: String, values: Map<String, String>): HttpURLConnection {
        val query = linkedMapOf("key" to BuildConfig.TRELLO_API_KEY, "token" to requireToken()).apply { putAll(values) }
            .entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val separator = if (path.contains("?")) "&" else "?"
        return (URL("$API_ROOT$path$separator$query").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "homework-buddy-android/0.1")
        }
    }

    private fun read(connection: HttpURLConnection): String {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
            throw IllegalStateException(message.ifBlank { "Trello 请求失败（$status）" })
        }
        return text
    }

    private fun requireToken() = token ?: error("请由家长重新授权 Trello")
    private fun requiredDoneList() = prefs.getString(DONE_LIST, null) ?: error("请由家长选择作业看板")
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun segment(value: String) = Uri.encode(value)

    companion object {
        private const val API_ROOT = "https://api.trello.com/1"
        // This HTTPS origin is already registered with the existing Trello
        // Power-Up. Android verifies ownership through assetlinks.json before
        // delivering the fragment to this signed application.
        private const val CALLBACK_URL = "https://www.cyeam.com/homework/trello/android/callback"
        private const val PREFS = "trello_connection"
        private const val TOKEN = "token"
        private const val TODO_LIST = "todo_list_id"
        private const val DONE_LIST = "done_list_id"
        private const val TODO_LIST_NAME = "待完成"
        private const val DONE_LIST_NAME = "已完成"
        private const val MAX_PHOTOS = 4

        /** Called by MainActivity for the app-link redirect after Trello consent. */
        fun saveAuthorizationResult(context: Context, uri: Uri?): Boolean {
            if (uri?.scheme != "https" || uri.host != "www.cyeam.com" || uri.path != "/homework/trello/android/callback") return false
            val fragment = uri.fragment ?: return false
            val token = Uri.parse("https://callback/?$fragment").getQueryParameter("token") ?: return false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(TOKEN, DeviceTokenCipher.encrypt(token))
                .remove(TODO_LIST).remove(DONE_LIST).apply()
            return true
        }
    }
}

private fun homeworkMinutes(desc: String): Int = desc.lineSequence().mapNotNull { line ->
    Regex("^\\s*预计用时:\\s*(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
}.firstOrNull { it in 1..180 } ?: 20
