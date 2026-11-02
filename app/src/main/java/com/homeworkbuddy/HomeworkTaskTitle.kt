package com.homeworkbuddy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Show a mode only when the task has content usable by its built-in screen. */
internal val HomeworkTask.titleMode: HomeworkTaskType?
    get() = when (type) {
        HomeworkTaskType.WORD_MEMORIZATION, HomeworkTaskType.ENGLISH_READING -> type.takeIf { !link.isNullOrBlank() }
        HomeworkTaskType.ARITHMETIC -> type.takeIf { arithmeticQuestions(task).isNotEmpty() }
        HomeworkTaskType.PDF_ATTACHMENT -> type.takeIf { attachments.any { it.isPdf } }
        HomeworkTaskType.NORMAL -> null
    }

@Composable
internal fun HomeworkTaskTitle(
    task: HomeworkTask,
    modifier: Modifier = Modifier,
    fontSize: TextUnit,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val mode = task.titleMode
    val icon = when (mode) {
        HomeworkTaskType.WORD_MEMORIZATION -> Icons.Outlined.Translate to "背单词"
        HomeworkTaskType.ENGLISH_READING -> Icons.AutoMirrored.Outlined.MenuBook to "英语阅读"
        HomeworkTaskType.ARITHMETIC -> Icons.Outlined.Calculate to "口算"
        HomeworkTaskType.PDF_ATTACHMENT -> Icons.Outlined.PictureAsPdf to "PDF 作业"
        else -> null
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(
            imageVector = icon.first,
            contentDescription = icon.second,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(with(LocalDensity.current) { fontSize.toDp() }),
        )
        Text(task.title, Modifier.weight(1f, fill = false), fontSize = fontSize, fontWeight = fontWeight,
            maxLines = maxLines, overflow = overflow)
    }
}
