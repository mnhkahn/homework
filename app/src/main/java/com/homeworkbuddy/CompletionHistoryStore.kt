package com.homeworkbuddy

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** One homework item as known on a given day, with its completion time if any. */
data class CompletionRecord(
    val taskId: String,
    val title: String,
    val deadlineEpochSeconds: Long,
    val startedAtEpochSeconds: Long?,
    val completedAtEpochSeconds: Long?,
    val durationSeconds: Int?,
    val localAudioUri: String?,
    val attachments: List<HomeworkAttachment>,
) {
    val photoUrls: List<String> get() = attachments.filterNot(HomeworkAttachment::isAudio).map(HomeworkAttachment::url)
    val audioAttachments: List<HomeworkAttachment> get() = attachments.filter(HomeworkAttachment::isAudio)
}

/**
 * Per-day homework history: which tasks existed each day and when each was
 * finished. The flower calendar and the weekly report are pure readers of this
 * store, so every completion path (photo submit, offline retry) must write here.
 */
class CompletionHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("completion_history", Context.MODE_PRIVATE)

    /** Merges the day's tasks into the history. Existing completions are never dropped. */
    fun noteTasks(tasks: List<HomeworkTask>, date: LocalDate = LocalDate.now()) {
        val day = dayJson(date)
        tasks.forEach { task ->
            val deadline = date.atTime(task.deadline).atZone(ZoneId.systemDefault()).toEpochSecond()
            val entry = day.optJSONObject(task.id) ?: JSONObject()
            entry.put("title", task.title).put("deadline", deadline)
            task.startedAtEpochSeconds?.let { entry.put("started_at", it) }
            entry.put("photo_urls", org.json.JSONArray(task.photoUrls))
            entry.put("attachments", org.json.JSONArray().also { attachments ->
                task.attachments.forEach { attachment ->
                    attachments.put(JSONObject().put("url", attachment.url).put("name", attachment.name).put("mime_type", attachment.mimeType))
                }
            })
            task.localAudioUri?.let { entry.put("local_audio_uri", it) }
            // This also imports completed cards that existed before this feature
            // was installed, so their earned flower is not lost.
            if (task.status == TaskStatus.COMPLETED && !entry.has("completed_at")) {
                entry.put("completed_at", task.completedAtEpochSeconds ?: deadline)
            }
            day.put(task.id, entry)
        }
        save(date, day)
    }

    /** Records the first completion of a task; later calls for the same task are ignored. */
    fun recordCompletion(taskId: String, completedAtEpochSeconds: Long, deadlineEpochSeconds: Long, startedAtEpochSeconds: Long? = null, durationSeconds: Int? = null, localAudioUri: String? = null, date: LocalDate = LocalDate.now()) {
        val day = dayJson(date)
        val entry = day.optJSONObject(taskId) ?: JSONObject().put("title", "").put("deadline", deadlineEpochSeconds)
        if (entry.has("completed_at")) return
        startedAtEpochSeconds?.let { entry.put("started_at", it) }
        entry.put("completed_at", completedAtEpochSeconds)
        durationSeconds?.takeIf { it > 0 }?.let { entry.put("duration_seconds", it) }
        localAudioUri?.let { entry.put("local_audio_uri", it) }
        day.put(taskId, entry)
        save(date, day)
    }

    /** All tasks recorded for a date, each with its completion time (null = not finished). */
    fun day(date: LocalDate): List<CompletionRecord> {
        val json = dayJson(date)
        return json.keys().asSequence().map { id ->
            val entry = json.getJSONObject(id)
            CompletionRecord(
                id,
                entry.optString("title"),
                entry.optLong("deadline"),
                entry.optLong("started_at").takeIf { entry.has("started_at") },
                if (entry.has("completed_at")) entry.getLong("completed_at") else null,
                entry.optInt("duration_seconds").takeIf { entry.has("duration_seconds") },
                entry.optString("local_audio_uri").ifBlank { null },
                entry.optJSONArray("attachments")?.let { attachments ->
                    (0 until attachments.length()).mapNotNull { index -> attachments.optJSONObject(index)?.let { attachment ->
                        attachment.optString("url").ifBlank { null }?.let { url -> HomeworkAttachment(url, attachment.optString("name"), attachment.optString("mime_type")) }
                    } }
                } ?: entry.optJSONArray("photo_urls")?.let { photos ->
                    (0 until photos.length()).mapNotNull { index -> photos.optString(index).ifBlank { null }?.let { url -> HomeworkAttachment(url, "", "image/*") } }
                } ?: emptyList(),
            )
        }.toList()
    }

    /** Removes cards before a fresh weekly reconciliation relocates them. */
    fun removeTasks(date: LocalDate, taskIds: Set<String>) {
        val day = dayJson(date)
        taskIds.forEach(day::remove)
        save(date, day)
    }

    fun clearDay(date: LocalDate) = save(date, JSONObject())

    /** A final flower/black mark is an on-device ledger entry, not a view calculation. */
    fun savedMark(date: LocalDate): DayMark? = prefs.getString(markKey(date), null)
        ?.let { name -> runCatching { DayMark.valueOf(name) }.getOrNull() }

    fun saveFinalMark(date: LocalDate, mark: DayMark) {
        require(mark == DayMark.FLOWER || mark == DayMark.BLACK)
        // Never overwrite a settled day.  Its result belongs to the tablet.
        if (savedMark(date) == null) prefs.edit().putString(markKey(date), mark.name).apply()
    }

    /** Used only by a verified remote-history repair to replace a bad legacy mark. */
    fun correctFinalMark(date: LocalDate, mark: DayMark) {
        require(mark == DayMark.FLOWER || mark == DayMark.BLACK)
        prefs.edit().putString(markKey(date), mark.name).apply()
    }

    /** Removes legacy black marks that were inferred from an incomplete cache. */
    fun clearUnverifiedBlackMark(date: LocalDate) {
        if (savedMark(date) == DayMark.BLACK) prefs.edit().remove(markKey(date)).apply()
    }

    /** Removes the short-lived date-specific corrections from earlier builds. */
    fun removeLegacyConfirmedMarks() {
        val keys = prefs.all.keys.filter { it.startsWith("confirmed:") }
        if (keys.isEmpty()) return
        val editor = prefs.edit()
        keys.forEach { key ->
            val date = key.removePrefix("confirmed:").substringBeforeLast(":").let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (date != null) editor.remove(markKey(date))
            editor.remove(key)
        }
        editor.apply()
    }

    private fun dayJson(date: LocalDate) = runCatching { JSONObject(prefs.getString(key(date), "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun save(date: LocalDate, day: JSONObject) = prefs.edit().putString(key(date), day.toString()).apply()
    private fun key(date: LocalDate) = "day:$date"
    private fun markKey(date: LocalDate) = "mark:$date"
}
