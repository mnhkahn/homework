package com.homeworkbuddy

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

data class TrelloOption(val id: String, val name: String)

/** Thrown when Trello rejects the stored token (HTTP 401); the parent must re-authorize. */
class AuthorizationExpiredException(message: String) : IllegalStateException(message)

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
        // The 待完成 list is the parent's current-day queue.  Do not use a
        // mutable Trello due date to discard its cards: changing a deadline
        // must never make today's homework disappear from the tablet.
        val todo = cards(prefs.getString(TODO_LIST, "") ?: "", TaskStatus.TODO)
        val done = cards(prefs.getString(DONE_LIST, "") ?: "", TaskStatus.COMPLETED)
            .filter { task -> task.completedAtEpochSeconds?.let(::dateAt) == LocalDate.now() }
        return (todo + done).sortedBy { it.deadline }
    }

    /** Fast path for the home screen: the 待完成 list is today's live queue. */
    suspend fun activeTasks(): List<HomeworkTask> {
        val today = LocalDate.now(HOMEWORK_ZONE)
        // Trello card IDs are independent random values, so they cannot
        // identify a "第 N 天" batch. The due date is the authoritative
        // school-day field.  Read today's completed cards too so their
        // attachments remain available from the home-screen task list.
        val todo = cards(prefs.getString(TODO_LIST, "") ?: "", TaskStatus.TODO, dueOn = today)
        val done = cards(prefs.getString(DONE_LIST, "") ?: "", TaskStatus.COMPLETED, dueOn = today)
            .filter { task -> task.completedAtEpochSeconds?.let(::dateAt) == today }
        return (todo + done)
            .sortedBy { it.deadline }
    }

    /** Current Mon–Sun, including completed cards so the calendar can show history. */
    suspend fun weekTasks(): List<HomeworkTask> {
        val todo = cards(prefs.getString(TODO_LIST, "") ?: "", TaskStatus.TODO)
        val done = cards(prefs.getString(DONE_LIST, "") ?: "", TaskStatus.COMPLETED)
        val monday = LocalDate.now(HOMEWORK_ZONE).with(DayOfWeek.MONDAY)
        val sunday = monday.plusDays(6)
        return (todo + done.filter { task -> task.completedAtEpochSeconds?.let(::dateAt) in monday..sunday })
            .sortedWith(compareBy<HomeworkTask> { it.dueDate }.thenBy { it.deadline })
    }

    /** Fast calendar source for scheduled work; it must not wait for historical actions. */
    suspend fun weekScheduledTasks(): List<HomeworkTask> {
        val monday = LocalDate.now(HOMEWORK_ZONE).with(DayOfWeek.MONDAY)
        val sunday = monday.plusDays(6)
        return cards(prefs.getString(TODO_LIST, "") ?: "", TaskStatus.TODO)
            .filter { it.dueDate in monday..sunday }
            .sortedWith(compareBy<HomeworkTask> { it.dueDate }.thenBy { it.deadline })
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
        check(photos.isNotEmpty()) { "需要先拍照再提交作业" }
        request("PUT", "/cards/${segment(taskId)}", mapOf("idList" to requiredDoneList(), "dueComplete" to "true"))
    }

    /** Downloads a Trello attachment through its authenticated download endpoint. */
    suspend fun loadPhoto(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val source = Uri.parse(url)
            val parts = source.pathSegments
            val cardIndex = parts.indexOf("cards")
            val attachmentIndex = parts.indexOf("attachments")
            val downloadIndex = parts.indexOf("download")
            require(cardIndex >= 0 && attachmentIndex > cardIndex && downloadIndex > attachmentIndex) { "无效的 Trello 附件地址" }
            val cardId = parts[cardIndex + 1]
            val attachmentId = parts[attachmentIndex + 1]
            val fileName = parts.drop(downloadIndex + 1).joinToString("/")
            require(fileName.isNotBlank()) { "Trello 附件缺少文件名" }
            val downloadUrl = "$API_ROOT/cards/${segment(cardId)}/attachments/${segment(attachmentId)}/download/${Uri.encode(fileName)}"
            (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "homework-buddy-android/0.1")
                setRequestProperty(
                    "Authorization",
                    "OAuth oauth_consumer_key=\"${oauthHeaderValue(BuildConfig.TRELLO_API_KEY)}\", oauth_token=\"${oauthHeaderValue(requireToken())}\"",
                )
            }.inputStream.use(BitmapFactory::decodeStream)
        }.getOrNull()
    }

    private suspend fun cards(listId: String, status: TaskStatus, dueOn: LocalDate? = null): List<HomeworkTask> {
        require(listId.isNotBlank()) { "请由家长选择作业看板" }
        val value = requestArray("GET", "/lists/${segment(listId)}/cards?fields=id,name,desc,due,labels,dateLastActivity&attachments=true&attachment_fields=url")
        val now = ZonedDateTime.now(HOMEWORK_ZONE)
        return (0 until value.length()).mapNotNull { index ->
            val item = value.getJSONObject(index)
            val due = item.optString("due").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val deadline = runCatching { OffsetDateTime.parse(due).atZoneSameInstant(HOMEWORK_ZONE) }.getOrNull() ?: return@mapNotNull null
            if (dueOn != null && deadline.toLocalDate() != dueOn) return@mapNotNull null
            val labels = item.optJSONArray("labels")
            val subject = labels?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() } ?: "作业"
            val taskStatus = if (status == TaskStatus.TODO && !deadline.isAfter(now)) TaskStatus.OVERTIME else status
            val attachmentUrls = item.optJSONArray("attachments")?.let { attachments ->
                (0 until attachments.length()).mapNotNull { attachment ->
                    attachments.optJSONObject(attachment)?.optString("url")?.ifBlank { null }
                }
            } ?: emptyList()
            val activityAt = if (status == TaskStatus.COMPLETED) {
                completionTime(item.getString("id"), listId)
                    ?: item.optString("dateLastActivity").takeIf { it.isNotBlank() }
                        ?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() }
            } else null
            HomeworkTask(
                item.getString("id"), subject, item.getString("name"), homeworkMinutes(item.optString("desc")),
                deadline.toLocalTime(), taskStatus, photoUrls = attachmentUrls,
                dueDate = deadline.toLocalDate(), completedAtEpochSeconds = activityAt,
            )
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

    /** The move into 已完成 is the durable completion timestamp in Trello. */
    private suspend fun completionTime(cardId: String, doneListId: String): Long? {
        val actions = requestArray("GET", "/cards/${segment(cardId)}/actions?filter=updateCard:idList&limit=50&fields=date,data")
        return (0 until actions.length()).firstNotNullOfOrNull { index ->
            val action = actions.optJSONObject(index) ?: return@firstNotNullOfOrNull null
            val movedToDone = action.optJSONObject("data")?.optJSONObject("listAfter")?.optString("id") == doneListId
            if (!movedToDone) null else action.optString("date").takeIf { it.isNotBlank() }
                ?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() }
        }
    }

    private fun dateAt(epochSeconds: Long) = java.time.Instant.ofEpochSecond(epochSeconds).atZone(HOMEWORK_ZONE).toLocalDate()

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
        // An expired or revoked token answers every request with 401. Surface it
        // as a dedicated type so the UI can restart the authorization flow
        // instead of showing a generic sync error the parent never acts on.
        if (status == 401) throw AuthorizationExpiredException("Trello 授权已过期，请家长重新授权")
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
    private fun oauthHeaderValue(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

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
        private val HOMEWORK_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

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
