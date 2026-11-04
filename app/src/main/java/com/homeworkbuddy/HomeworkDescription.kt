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
    val content = task.description ?: task.task ?: return
    HomeworkLinkedText(content)
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
