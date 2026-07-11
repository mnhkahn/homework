package com.homeworkbuddy

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PendingSubmission(val taskId: String, val photoPath: String?, val isOvertime: Boolean, val submissionId: String = UUID.randomUUID().toString())

class PendingSubmissionStore(context: Context) {
    private val prefs = context.getSharedPreferences("pending_submissions", Context.MODE_PRIVATE)
    fun add(item: PendingSubmission) {
        val items = items().filterNot { it.taskId == item.taskId }.toMutableList(); items += item; save(items)
    }
    fun items(): List<PendingSubmission> = runCatching {
        val json = JSONArray(prefs.getString("items", "[]")); (0 until json.length()).map { index -> json.getJSONObject(index).let {
            val taskId = it.getString("task_id")
            val photoPath = if (it.isNull("photo_path")) null else it.optString("photo_path").ifBlank { null }
            val stableLegacyId = UUID.nameUUIDFromBytes("$taskId|${photoPath.orEmpty()}".toByteArray()).toString()
            PendingSubmission(taskId, photoPath, it.getBoolean("overtime"), it.optString("submission_id", stableLegacyId))
        } }
    }.getOrDefault(emptyList())
    fun remove(taskId: String) = save(items().filterNot { it.taskId == taskId })
    private fun save(items: List<PendingSubmission>) { prefs.edit().putString("items", JSONArray().also { array -> items.forEach { item -> array.put(JSONObject().put("task_id", item.taskId).put("photo_path", item.photoPath).put("overtime", item.isOvertime).put("submission_id", item.submissionId)) } }.toString()).apply() }
}
