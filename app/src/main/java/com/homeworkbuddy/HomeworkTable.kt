package com.homeworkbuddy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TableHeader = Color(0xFFE3F2E9)
private val TableBackground = Color(0xFFEFF8F3)
private val TableDivider = Color(0xFFC9DFD2)
private val TableInk = Color(0xFF28463B)
private val TableMuted = Color(0xFF4B6D5D)
private val TableDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private val tableHeaders = listOf(
    "标题", "作业类型", "进度", "打分结果", "计划开始时间", "实际完成时间", "预计完成时间", "更新时间",
)
private val standardProgressLists = setOf("待完成", "待改错", "已完成", "已改错")
private val columnWeights = listOf(18f, 9f, 10f, 7f, 14f, 14f, 14f, 14f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeworkTable(
    modifier: Modifier,
    tasks: List<HomeworkTask>,
    date: LocalDate,
    onSelect: (HomeworkTask) -> Unit,
) {
    var progress by rememberSaveable { mutableStateOf(TaskProgressFilter.ALL) }
    var subject by rememberSaveable { mutableStateOf<HomeworkSubject?>(null) }
    var progressExpanded by remember { mutableStateOf(false) }
    var subjectExpanded by remember { mutableStateOf(false) }
    val progressTasks = tasks.filter { progress.includes(it.tableGroup) }
    val visibleTasks = progressTasks.filter { subject == null || HomeworkSubject.fromTitle(it.title) == subject }
    val listState = rememberLazyListState()
    LaunchedEffect(date, progress, subject, visibleTasks.firstOrNull()?.id) { listState.scrollToItem(0) }

    Column(modifier) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "作业清单 · ${visibleTasks.size} 项",
                modifier = Modifier.align(Alignment.CenterVertically),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(Modifier.align(Alignment.CenterVertically)) {
                Box {
                    TextButton(onClick = { progressExpanded = true }) {
                        Text(progress.label)
                        Icon(Icons.Outlined.ArrowDropDown, "筛选作业进度")
                    }
                    DropdownMenu(expanded = progressExpanded, onDismissRequest = { progressExpanded = false }) {
                        TaskProgressFilter.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = { progress = option; progressExpanded = false },
                            )
                        }
                    }
                }
                Box {
                    TextButton(onClick = { subjectExpanded = true }) {
                        Text(subject?.label ?: "全部类目")
                        Icon(Icons.Outlined.ArrowDropDown, "筛选作业类目")
                    }
                    DropdownMenu(expanded = subjectExpanded, onDismissRequest = { subjectExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("全部类目（${progressTasks.size}）") },
                            onClick = { subject = null; subjectExpanded = false },
                        )
                        HomeworkSubject.entries.forEach { option ->
                            val count = progressTasks.count { HomeworkSubject.fromTitle(it.title) == option }
                            DropdownMenuItem(
                                text = { Text("${option.label}（$count）") },
                                onClick = { subject = option; subjectExpanded = false },
                            )
                        }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val viewportWidth = maxWidth
            val tableWidth = viewportWidth.coerceAtLeast(800.dp)
            Column {
                if (viewportWidth < tableWidth) {
                    Text("左右滑动查看完整表格", Modifier.padding(bottom = 6.dp), color = TableMuted, fontSize = 12.sp)
                }
                Column(Modifier.horizontalScroll(rememberScrollState()).width(tableWidth).weight(1f)) {
                    HomeworkTableRow()
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        if (visibleTasks.isEmpty()) item {
                            Text("暂无符合筛选条件的作业", Modifier.padding(16.dp), color = TableMuted)
                        }
                        visibleTasks.groupBy { it.tableGroup }.forEach { (group, rows) ->
                            item(key = "group-" + group.name) {
                                Text(
                                    group.label,
                                    Modifier.fillMaxWidth().background(TableHeader).padding(horizontal = 17.dp, vertical = 7.dp),
                                    color = TableMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            items(rows, key = { it.id }) { task ->
                                HomeworkTableRow(
                                    task = task,
                                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "打开作业详情") { onSelect(task) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeworkTableRow(
    task: HomeworkTask? = null,
    modifier: Modifier = Modifier,
) {
    val header = task == null
    Row(
        Modifier.fillMaxWidth()
            .background(if (header) TableHeader else TableBackground)
            .then(modifier)
            .heightIn(min = if (header) 52.dp else 64.dp)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val grade = HomeworkGradeResult.parse(task?.tableCard?.gradeLabel)
        (task?.tableCells() ?: tableHeaders).forEachIndexed { index, value ->
            Box(Modifier.weight(columnWeights[index]).padding(horizontal = 7.dp)) {
                when {
                    task?.tableStartedAt != null && index == 2 -> Column {
                        Text("已开始", color = TableInk, fontSize = 14.sp)
                        Text(task.tableStartedAt?.let(Instant::ofEpochSecond).tableTime().drop(5),
                            color = TableMuted, fontSize = 12.sp)
                    }
                    index == 2 && task != null && task.tableCard?.listName?.trim()?.let(standardProgressLists::contains) == true ->
                        TaskStatusPill(task.status)
                    index == 3 && grade != null -> HomeworkGradeBadge(grade, compact = true)
                    else -> {
                        val title = !header && index == 0
                        Text(
                            text = value,
                            color = if (title) TableInk else TableMuted,
                            fontSize = if (title) 16.sp else 14.sp,
                            lineHeight = 21.sp,
                            fontWeight = if (header || title) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(color = TableDivider)
}

private fun HomeworkTask.tableCells(): List<String> {
    val card = requireNotNull(tableCard)
    return listOf(
        title,
        card.typeLabel ?: "—",
        card.listName,
        card.gradeLabel ?: "—",
        card.startAtMillis?.let(Instant::ofEpochMilli).tableTime(),
        card.completedAtMillis?.let(Instant::ofEpochMilli).tableTime(),
        Instant.ofEpochMilli(card.dueAtMillis).tableTime(),
        card.updatedAtMillis?.let(Instant::ofEpochMilli).tableTime(),
    )
}

private fun Instant?.tableTime(): String =
    this?.atZone(ZoneId.systemDefault())?.format(TableDateFormat) ?: "—"
