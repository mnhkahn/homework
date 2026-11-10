package com.homeworkbuddy

import android.net.Uri
import org.json.JSONObject
import java.net.URI

/** Normalized type-specific content extracted from one Trello card description. */
data class HomeworkTaskDetails(
    val type: HomeworkTaskType,
    val task: String? = null,
    val link: String? = null,
) {
    companion object {
        private const val TRANSLATE_HOST = "www.cyeam.com"
        private const val TRANSLATE_PATH = "/ai/translate"
        private val typeLine = Regex("(?im)^\\s*(?:type|类型|作业类型)\\s*[:：]\\s*(normal|word_memorization|english_reading|pdf_attachment|arithmetic|preview|口算|普通作业|背单词|阅读英语|附件PDF|预习)\\s*$")
        private val taskLine = Regex("(?im)^\\s*(?:task|作业内容)\\s*[:：]\\s*(.+?)\\s*$")
        private val previewRequirementsLine = Regex("(?im)^[\\t ]*(?:预习要求|preview_requirements)[\\t ]*[:：][\\t ]*")
        private val wordLine = Regex("(?im)^\\s*背单词\\s*[:：]\\s*(.+?)\\s*$")
        private val translationLinkLine = Regex("(?im)^[\\t ]*翻译链接[\\t ]*[:：][\\t ]*(\\S+)[\\t ]*$")
        private val homeworkLinkLine = Regex("(?im)^[\\t ]*(?:link|链接|作业链接)[\\t ]*[:：][\\t ]*(\\S+)[\\t ]*$")
        private val durationLine = Regex("(?im)^\\s*预计用时\\s*[:：].*$")
        private val vocabulary = Regex("^[A-Za-z]+(?:['’-][A-Za-z]+)*(?: +[A-Za-z]+(?:['’-][A-Za-z]+)*)*$")
        private val vocabularyAnnotation = Regex("\\(([^()]*)\\)|（([^（）]*)）")

        /**
         * Explicit type always wins. For Trello descriptions we accept either
         * the JSON payload used by the parent app or readable `类型:` lines.
         */
        fun fromDescription(description: String): HomeworkTaskDetails {
            val json = description.trim().takeIf(String::isNotBlank)?.let { value -> runCatching { JSONObject(value) }.getOrNull() }
            val explicit = json?.optString("type")?.toTaskType()
                ?: typeLine.find(description)?.groupValues?.getOrNull(1)?.toTaskType()
            if (explicit == HomeworkTaskType.ARITHMETIC) {
                // Arithmetic is multiline; the legacy taskLine only captures one line.
                val content = if (json != null) json.optString("task") else {
                    val body = description.withoutMetadata()
                    val marker = Regex("(?im)^[\\t ]*(?:task|作业内容)[\\t ]*[:：][\\t ]*").find(body)
                    (if (marker != null) body.substring(marker.range.last + 1) else body).trim()
                }
                return HomeworkTaskDetails(HomeworkTaskType.ARITHMETIC, content)
            }
            val rawTask = json?.optString("task")?.ifBlank { null }
                ?: wordLine.find(description)?.groupValues?.getOrNull(1)?.trim()
                ?: taskLine.find(description)?.groupValues?.getOrNull(1)?.trim()
                ?: description.withoutMetadata().ifBlank { null }
            val suppliedLink = json?.optString("link")?.ifBlank { null }
                ?: homeworkLinkLine.find(description)?.groupValues?.getOrNull(1)?.trim()
                ?: translationLinkLine.find(description)?.groupValues?.getOrNull(1)?.trim()

            return when (explicit) {
                HomeworkTaskType.WORD_MEMORIZATION -> HomeworkTaskDetails(
                    HomeworkTaskType.WORD_MEMORIZATION,
                    words(rawTask) ?: rawTask,
                    vocabularyLink(suppliedLink) ?: vocabularyLink(rawTask),
                )
                HomeworkTaskType.ENGLISH_READING -> {
                    val readingLink = suppliedLink?.takeIf(::isEnglishReadingLink)
                        ?: rawTask?.takeIf(::isEnglishReadingLink)
                    HomeworkTaskDetails(HomeworkTaskType.ENGLISH_READING, rawTask, readingLink)
                }
                HomeworkTaskType.PDF_ATTACHMENT -> HomeworkTaskDetails(HomeworkTaskType.PDF_ATTACHMENT, rawTask)
                HomeworkTaskType.ARITHMETIC -> HomeworkTaskDetails(HomeworkTaskType.ARITHMETIC, rawTask)
                HomeworkTaskType.PREVIEW -> {
                    val requirements = if (json != null) json.optString("preview_requirements").trim() else
                        previewRequirementsLine.find(description)?.let { description.substring(it.range.last + 1).trim() }.orEmpty()
                    HomeworkTaskDetails(HomeworkTaskType.PREVIEW, requirements.ifBlank { null })
                }
                HomeworkTaskType.NORMAL -> HomeworkTaskDetails(HomeworkTaskType.NORMAL, rawTask, homeworkLink(suppliedLink, rawTask))
                null -> infer(rawTask).let { inferred ->
                    when (inferred.type) {
                        HomeworkTaskType.NORMAL -> inferred.copy(link = homeworkLink(suppliedLink, rawTask))
                        HomeworkTaskType.WORD_MEMORIZATION -> inferred.copy(link = vocabularyLink(suppliedLink) ?: inferred.link)
                        else -> inferred
                    }
                }
            }
        }

        /** Keep every detail line; structured JSON is rendered as readable content. */
        fun displayText(description: String): String? {
            val json = runCatching { JSONObject(description.trim()) }.getOrNull()
            return (if (json == null) description else listOf(
                json.optString("task"), json.optString("link"),
                json.optString("preview_requirements").takeIf(String::isNotBlank)?.let { "预习要求：\n$it" }.orEmpty(),
            ).filter(String::isNotBlank).distinct().joinToString("\n")).trim().ifBlank { null }
        }

        internal fun withPreviewRequirements(description: String, requirements: String): String {
            val details = fromDescription(description)
            check(details.type == HomeworkTaskType.PREVIEW) { "Trello 卡片已改为其他作业类型" }
            val value = requirements.trim()
            if (details.task.orEmpty() == value) return description

            val json = runCatching { JSONObject(description.trim()) }.getOrNull()
            if (json != null) return json.put("preview_requirements", value).toString()

            val field = previewRequirementsLine.find(description)
            val prefix = if (field == null) "${description.trimEnd()}\n\n"
                else description.substring(0, field.range.first)
            return "${prefix}预习要求：\n$value"
        }

        private fun homeworkLink(suppliedLink: String?, rawTask: String?): String? =
            (suppliedLink ?: rawTask?.trim())?.takeIf(HomeworkLinks::isWebUrl)

        private fun infer(rawTask: String?): HomeworkTaskDetails {
            val value = rawTask?.trim().orEmpty()
            if (isEnglishReadingLink(value)) return HomeworkTaskDetails(HomeworkTaskType.ENGLISH_READING, value, value)
            if (isVocabularyPage(value)) return HomeworkTaskDetails(HomeworkTaskType.WORD_MEMORIZATION, link = value)
            val normalizedWords = words(value)
            return if (normalizedWords != null) HomeworkTaskDetails(HomeworkTaskType.WORD_MEMORIZATION, normalizedWords)
            else HomeworkTaskDetails(HomeworkTaskType.NORMAL, rawTask, homeworkLink(null, rawTask))
        }

        private fun String.withoutMetadata(): String = durationLine.replace(this, "")
            .let { typeLine.replace(it, "") }
            .let { wordLine.replace(it, "") }
            .let { translationLinkLine.replace(it, "") }
            .let { homeworkLinkLine.replace(it, "") }
            .trim()

        private fun words(value: String?): String? {
            // Cards may include English continuations and Chinese glosses, e.g.
            // post(office), cell(phone)(手机). Keep English continuations in one phrase.
            val expanded = value?.let { text ->
                vocabularyAnnotation.replace(text) { match ->
                    val annotation = match.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty().trim()
                    when {
                        vocabulary.matches(annotation) -> " $annotation "
                        annotation.matches(Regex("[\\p{IsHan}\\s、，。；：]+")) -> ""
                        else -> match.value
                    }
                }
            }
            val entries = expanded.orEmpty().split(',', '，')
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter(String::isNotEmpty)
            return entries.takeIf { it.isNotEmpty() && it.all(vocabulary::matches) }?.joinToString(",")
        }

        internal fun vocabularyWords(task: HomeworkTask): List<String> {
            if (task.type != HomeworkTaskType.WORD_MEMORIZATION || vocabularyLink(task.link) != null) return emptyList()
            return words(task.task)?.split(',')?.map { it.lowercase() }?.distinct().orEmpty()
        }

        // Older tablet versions cached a generated URL even when the card contained only words.
        internal fun restoreVocabularyLink(task: HomeworkTask): String? {
            val stored = vocabularyLink(task.link) ?: vocabularyLink(task.task)
            val description = task.description ?: return stored
            if (stored == null) return fromDescription(description).link
            if (!isVocabularyPage(stored) || words(task.task) == null) return stored
            return fromDescription(description).link ?: description.lineSequence()
                .map(String::trim)
                .firstOrNull(HomeworkLinks::isWebUrl)
        }

        private fun vocabularyLink(value: String?): String? {
            val text = value?.trim() ?: return null
            val url = if (text.startsWith('[') && text.endsWith(')')) text.substringAfter("](", "").dropLast(1) else text
            return url.takeIf(HomeworkLinks::isWebUrl)
        }

        private fun isVocabularyPage(value: String): Boolean {
            if (!HomeworkLinks.isWebUrl(value)) return false
            val uri = URI(value)
            return uri.host.equals(TRANSLATE_HOST, true) && uri.path == TRANSLATE_PATH &&
                uri.rawQuery.orEmpty().split('&').any { it.startsWith("word=") || it.startsWith("words=") }
        }

        private fun isEnglishReadingLink(value: String): Boolean = runCatching {
            val uri = Uri.parse(value)
            uri.scheme == "https" && uri.host == TRANSLATE_HOST && uri.path == TRANSLATE_PATH &&
                uri.getQueryParameter("textbook")?.toIntOrNull() != null &&
                uri.getQueryParameter("article")?.toIntOrNull() != null
        }.getOrDefault(false)

        private fun String.toTaskType(): HomeworkTaskType? = when (lowercase()) {
            "arithmetic", "口算" -> HomeworkTaskType.ARITHMETIC
            "preview", "预习" -> HomeworkTaskType.PREVIEW
            "normal" -> HomeworkTaskType.NORMAL
            "普通作业" -> HomeworkTaskType.NORMAL
            "word_memorization" -> HomeworkTaskType.WORD_MEMORIZATION
            "背单词" -> HomeworkTaskType.WORD_MEMORIZATION
            "pdf_attachment", "附件pdf" -> HomeworkTaskType.PDF_ATTACHMENT
            "english_reading" -> HomeworkTaskType.ENGLISH_READING
            "阅读英语" -> HomeworkTaskType.ENGLISH_READING
            else -> null
        }
    }
}
