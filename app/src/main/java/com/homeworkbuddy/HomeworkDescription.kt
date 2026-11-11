package com.homeworkbuddy

import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

@Composable
internal fun HomeworkDescription(task: HomeworkTask) {
    val isVocabulary = remember(task.type, task.task, task.link) {
        HomeworkTaskDetails.vocabularyWords(task).isNotEmpty()
    }
    if (isVocabulary) {
        Text(
            when {
                task.type == HomeworkTaskType.ENGLISH_READING -> "先跟读单词，再阅读课文。"
                task.status.isSubmitted -> "可以复习词卡和再听写。"
                else -> "请在背单词页面完成听写，再拍照提交。"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }
    val content = task.description ?: task.task ?: return
    val description = remember(content) { HomeworkTaskDetails.withoutRequirements(content) }
    if (description.isNotBlank()) HomeworkLinkedText(description)
}

/** Assignment and review links share the built-in learning browser. */
@Suppress("DEPRECATION")
@Composable
internal fun HomeworkLinkedText(content: String, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(content, linkColor) {
        val linked = SpannableString(content)
        Linkify.addLinks(linked, Linkify.WEB_URLS)
        buildAnnotatedString {
            append(content)
            linked.getSpans(0, linked.length, URLSpan::class.java).forEach { span ->
                val start = linked.getSpanStart(span)
                val end = linked.getSpanEnd(span)
                // Linkify also finds bare domains; only explicit HTTP(S) links are actionable.
                val url = content.substring(start, end)
                if (HomeworkLinks.isWebUrl(url)) {
                    addStringAnnotation("url", url, start, end)
                    addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), start, end)
                }
            }
        }
    }
    if (text.getStringAnnotations("url", 0, text.length).isEmpty()) {
        Text(content, style = style)
    } else {
        ClickableText(text, style = style.copy(color = MaterialTheme.colorScheme.onSurface)) { offset ->
            text.getStringAnnotations("url", offset, offset).firstOrNull()?.let {
                GameActivity.openHomeworkLink(context, it.item)
            }
        }
    }
}
