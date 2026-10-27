package com.homeworkbuddy

import org.json.JSONObject

/** One review comment publishes exactly one payload on the original homework card. */
data class HomeworkReview(
    val id: String,
    val date: String,
    val type: String,
    val text: String? = null,
    val attachmentId: String? = null,
    val hasErrors: Boolean? = null,
    val gradeResult: HomeworkGradeResult? = null,
) {
    companion object {
        const val PREFIX = "作业批改："

        fun parse(id: String, date: String, comment: String): HomeworkReview? {
            val content = comment.trim()
            if (!content.startsWith(PREFIX)) return null
            return runCatching {
                val json = JSONObject(content.removePrefix(PREFIX).trim())
                require(json.opt("version") == 1)
                val type = json.get("type") as String
                val text = (json.opt("text") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val attachmentId = (json.opt("attachment_id") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                when (type) {
                    "text" -> require(text != null && !json.has("attachment_id"))
                    "image", "audio" -> require(attachmentId != null && !json.has("text"))
                    else -> error("Unsupported review type")
                }
                val hasErrors = if (json.has("has_errors")) json.get("has_errors").also { require(it is Boolean) } as Boolean else null
                val grade = HomeworkGrade.parse(json.optString("grade"))
                val result = grade?.let { HomeworkGradeResult(it, json.optBoolean("provisional")) }
                HomeworkReview(id, date, type, text, attachmentId, hasErrors, result)
            }.getOrNull()
        }
    }
}

data class HomeworkReviewContent(val reviews: List<HomeworkReview>, val attachments: Map<String, HomeworkAttachment>)
