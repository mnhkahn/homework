package com.homeworkbuddy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun poseAngleLabel(pitch: Float?): String = pitch?.let { String.format(Locale.CHINA, "%+.1f°", it) } ?: "—"

@Composable
internal fun HeadPoseHistoryDialog(store: HeadPoseHistoryStore, today: LocalDate, onDismiss: () -> Unit) {
    val dates = (store.dates() + today).distinct().sortedDescending()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("坐姿提醒记录") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("同一次持续低头只计 1 次，重复语音不重复计数；关闭语音时仍记录屏幕提醒。检测中断后再次触发会另计一次。", style = MaterialTheme.typography.bodySmall)
                    Text("最低俯仰角取当天有效检测的最小值，数值越负表示头越低（如 −40° 比 −30° 更低），不是离桌面的高度。记录仅保存在本机。", style = MaterialTheme.typography.bodySmall)
                }
                dates.forEach { date ->
                    val day = store.day(date)
                    item(key = "day-$date") {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(if (date == today) "$date · 今天" else date.toString(), fontWeight = FontWeight.Bold)
                        Text("提醒 ${day.reminders.size} 次 · 最低俯仰角 ${poseAngleLabel(day.minimumPitch)}")
                        if (day.minimumPitch == null) Text("当天暂无检测记录")
                        else if (day.reminders.isEmpty()) Text("当天未触发低头提醒")
                    }
                    items(day.reminders.sortedByDescending { it.remindedAtMillis }, key = { "$date-${it.id}" }) { record ->
                        val time = Instant.ofEpochMilli(record.remindedAtMillis).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        Text("$time · 最低 ${poseAngleLabel(record.minimumPitch)}\n提醒阈值 ${poseAngleLabel(record.threshold)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun HeadPoseHistoryCard() {
    val context = LocalContext.current
    val store = remember(context) { HeadPoseHistoryStore(context) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var day by remember { mutableStateOf(store.day(today)) }
    var showHistory by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        while (true) {
            today = LocalDate.now()
            day = store.day(today)
            delay(1_000)
        }
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("坐姿提醒记录", style = MaterialTheme.typography.titleLarge)
            Text("今日提醒 ${day.reminders.size} 次 · 最低俯仰角 ${poseAngleLabel(day.minimumPitch)}")
            Text("同一次持续低头计一次；记录保存在本机，重启后仍可查看。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { showHistory = true }) { Text("查看历史记录") }
        }
    }
    if (showHistory) HeadPoseHistoryDialog(store, today) { showHistory = false }
}
