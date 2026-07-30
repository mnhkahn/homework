package com.homeworkbuddy

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PendingSubmission(val taskId: String, val photoPaths: List<String>, val isOvertime: Boolean, val submissionId: String = UUID.randomUUID().toString())

class PendingSubmissionStore(context: Context) {
    private val prefs = context.getSharedPreferences("pending_submissions", Context.MODE_PRIVATE)
    fun add(item: PendingSubmission) {
        val items = items().filterNot { it.taskId == item.taskId }.toMutableList(); items += item; save(items)
    }
    fun items(): List<PendingSubmission> = runCatching {
        val json = JSONArray(prefs.getString("items", "[]")); (0 until json.length()).map { index -> json.getJSONObject(index).let {
            val taskId = it.getString("task_id")
            val photoPaths = it.optJSONArray("photo_paths")?.let { paths ->
                (0 until paths.length()).mapNotNull { index -> paths.optString(index).ifBlank { null } }
            } ?: listOfNotNull(if (it.isNull("photo_path")) null else it.optString("photo_path").ifBlank { null })
            val stableLegacyId = UUID.nameUUIDFromBytes("$taskId|${photoPaths.joinToString("|")}".toByteArray()).toString()
            PendingSubmission(taskId, photoPaths, it.getBoolean("overtime"), it.optString("submission_id", stableLegacyId))
        } }
    }.getOrDefault(emptyList())
    fun remove(taskId: String) = save(items().filterNot { it.taskId == taskId })
    private fun save(items: List<PendingSubmission>) { prefs.edit().putString("items", JSONArray().also { array -> items.forEach { item -> array.put(JSONObject().put("task_id", item.taskId).put("photo_paths", JSONArray(item.photoPaths)).put("overtime", item.isOvertime).put("submission_id", item.submissionId)) } }.toString()).apply() }
}

/**
 * Once a child chooses "完成并拍照", that task must not be completed by a
 * concurrent or previously queued no-photo submission.
 */
class PhotoCompletionGuard(context: Context) {
    private val prefs = context.getSharedPreferences("photo_completion_guard", Context.MODE_PRIVATE)

    fun requiredTaskIds(): Set<String> = prefs.getStringSet(REQUIRED_TASK_IDS, emptySet()).orEmpty().toSet()

    fun isRequired(taskId: String): Boolean = taskId in requiredTaskIds()

    fun require(taskId: String) {
        prefs.edit().putStringSet(REQUIRED_TASK_IDS, requiredTaskIds() + taskId).commit()
    }

    fun clear(taskId: String) {
        prefs.edit().putStringSet(REQUIRED_TASK_IDS, requiredTaskIds() - taskId).apply()
    }

    private companion object {
        const val REQUIRED_TASK_IDS = "required_task_ids"
    }
}
