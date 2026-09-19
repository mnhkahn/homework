package com.homeworkbuddy

import android.net.Uri
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Normalized type-specific content extracted from one Trello card description. */
data class HomeworkTaskDetails(
    val type: HomeworkTaskType,
    val task: String? = null,
    val link: String? = null,
) {
    companion object {
        private const val TRANSLATE_HOST = "www.cyeam.com"
        private const val TRANSLATE_PATH = "/ai/translate"
        private val typeLine = Regex("(?im)^\\s*(?:type|类型)\\s*[:：]\\s*(normal|word_memorization|english_reading)\\s*$")
        private val taskLine = Regex("(?im)^\\s*(?:task|作业内容)\\s*[:：]\\s*(.+?)\\s*$")
        private val durationLine = Regex("(?im)^\\s*预计用时\\s*[:：].*$")
        private val vocabulary = Regex("^[A-Za-z]+(?:['’-][A-Za-z]+)?(?:[\\s,，]+[A-Za-z]+(?:['’-][A-Za-z]+)?)*$")

        /**
         * Explicit type always wins. For Trello descriptions we accept either
         * the JSON payload used by the parent app or readable `类型:` lines.
         */
        fun fromDescription(description: String): HomeworkTaskDetails {
            val json = description.trim().takeIf(String::isNotBlank)?.let { runCatching(::JSONObject).getOrNull() }
            val explicit = json?.optString("type")?.toTaskType()
                ?: typeLine.find(description)?.groupValues?.getOrNull(1)?.toTaskType()
            val rawTask = json?.optString("task")?.ifBlank { null }
                ?: taskLine.find(description)?.groupValues?.getOrNull(1)?.trim()
                ?: description.withoutMetadata().ifBlank { null }
            val suppliedLink = json?.optString("link")?.ifBlank { null }

            return when (explicit) {
                HomeworkTaskType.WORD_MEMORIZATION -> (words(rawTask)
                    ?: suppliedLink?.wordsFromLink()
                    ?: rawTask?.wordsFromLink()).let { normalized ->
                    HomeworkTaskDetails(HomeworkTaskType.WORD_MEMORIZATION, normalized ?: rawTask, normalized?.wordLink())
                }
                HomeworkTaskType.ENGLISH_READING -> {
                    val readingLink = suppliedLink?.takeIf(::isEnglishReadingLink)
                        ?: rawTask?.takeIf(::isEnglishReadingLink)
                    HomeworkTaskDetails(HomeworkTaskType.ENGLISH_READING, rawTask, readingLink)
                }
                HomeworkTaskType.NORMAL -> HomeworkTaskDetails(HomeworkTaskType.NORMAL, rawTask)
                null -> infer(rawTask)
            }
        }

        private fun infer(rawTask: String?): HomeworkTaskDetails {
            val value = rawTask?.trim().orEmpty()
            if (isEnglishReadingLink(value)) return HomeworkTaskDetails(HomeworkTaskType.ENGLISH_READING, value, value)
            val normalizedWords = value.wordsFromLink() ?: words(value)
            return if (normalizedWords != null) HomeworkTaskDetails(HomeworkTaskType.WORD_MEMORIZATION, normalizedWords, normalizedWords.wordLink())
            else HomeworkTaskDetails(HomeworkTaskType.NORMAL, rawTask)
        }

        private fun String.withoutMetadata(): String = durationLine.replace(this, "")
            .let { typeLine.replace(it, "") }
            .trim()

        private fun words(value: String?): String? {
            val candidate = value?.trim()?.replace(Regex("[，,\\s]+"), ",")?.trim(',').orEmpty()
            return candidate.takeIf { it.isNotBlank() && vocabulary.matches(it.replace(',', ' ')) }
        }

        private fun String.wordLink(): String = "https://$TRANSLATE_HOST$TRANSLATE_PATH?words=" +
            URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("%2C", ",")

        /** Accepts the word-learning URL saved in a Trello description. */
        private fun String.wordsFromLink(): String? = runCatching {
            val uri = Uri.parse(this)
            if (uri.scheme != "https" || uri.host != TRANSLATE_HOST || uri.path != TRANSLATE_PATH) null
            else words(uri.getQueryParameter("words"))
        }.getOrNull()

        private fun isEnglishReadingLink(value: String): Boolean = runCatching {
            val uri = Uri.parse(value)
            uri.scheme == "https" && uri.host == TRANSLATE_HOST && uri.path == TRANSLATE_PATH &&
                uri.getQueryParameter("textbook")?.toIntOrNull() != null &&
                uri.getQueryParameter("article")?.toIntOrNull() != null
        }.getOrDefault(false)

        private fun String.toTaskType(): HomeworkTaskType? = when (lowercase()) {
            "normal" -> HomeworkTaskType.NORMAL
            "word_memorization" -> HomeworkTaskType.WORD_MEMORIZATION
            "english_reading" -> HomeworkTaskType.ENGLISH_READING
            else -> null
        }
    }
}
