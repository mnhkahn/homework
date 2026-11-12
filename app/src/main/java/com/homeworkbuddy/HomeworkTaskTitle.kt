package com.homeworkbuddy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Show a mode only when the task has content usable by its built-in screen. */
internal val HomeworkTask.titleMode: HomeworkTaskType?
    get() = when (type) {
        HomeworkTaskType.WORD_MEMORIZATION -> type.takeIf {
            link?.let(HomeworkLinks::isWebUrl) == true || HomeworkTaskDetails.vocabularyWords(this).isNotEmpty()
        }
        HomeworkTaskType.ENGLISH_READING -> type.takeIf { !link.isNullOrBlank() }
        HomeworkTaskType.ARITHMETIC -> type.takeIf { arithmeticQuestions(task).isNotEmpty() }
        HomeworkTaskType.PREVIEW -> type
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
    val subject = HomeworkSubject.fromTitle(task.title)
    val color = when (subject) {
        HomeworkSubject.CHINESE -> Color(0xFFB33D48)
        HomeworkSubject.MATH -> Color(0xFF245DB3)
        HomeworkSubject.ENGLISH -> Color(0xFF7542A3)
        HomeworkSubject.SPORTS -> Color(0xFF237044)
        null -> MaterialTheme.colorScheme.onSurface
    }
    val subjectIcon = when (subject) {
        HomeworkSubject.CHINESE -> Icons.AutoMirrored.Outlined.MenuBook
        HomeworkSubject.MATH -> Icons.Outlined.Calculate
        HomeworkSubject.ENGLISH -> Icons.Outlined.Translate
        HomeworkSubject.SPORTS -> Icons.Outlined.DirectionsRun
        null -> null
    }
    val icon = subjectIcon?.let { it to subject!!.label } ?: when (task.titleMode) {
        HomeworkTaskType.WORD_MEMORIZATION -> Icons.Outlined.Translate to "背单词"
        HomeworkTaskType.ENGLISH_READING -> Icons.AutoMirrored.Outlined.MenuBook to "英语阅读"
        HomeworkTaskType.ARITHMETIC -> Icons.Outlined.Calculate to "口算"
        HomeworkTaskType.PREVIEW -> Icons.AutoMirrored.Outlined.MenuBook to "预习"
        else -> null
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(
            imageVector = icon.first,
            contentDescription = icon.second,
            tint = if (subject != null) color else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(with(LocalDensity.current) { fontSize.toDp() }),
        )
        Text(task.title, Modifier.weight(1f, fill = false), color = color, fontSize = fontSize, fontWeight = fontWeight,
            maxLines = maxLines, overflow = overflow)
        if (task.type == HomeworkTaskType.PREVIEW) Text("预习", style = MaterialTheme.typography.labelMedium)
    }
}
