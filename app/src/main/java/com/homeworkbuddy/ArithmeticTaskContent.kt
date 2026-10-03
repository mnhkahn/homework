package com.homeworkbuddy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArithmeticTaskContent(modifier: Modifier, task: HomeworkTask, elapsedSeconds: Int, overdue: Boolean) {
    val questions = remember(task.task) { arithmeticQuestions(task.task) }
    Column(modifier) {
        Text(task.title, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (task.subject != "作业") Text(task.subject, fontSize = 13.sp)
            Text(if (overdue) "已超期" else "正在完成", fontSize = 13.sp,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("截止 ${task.deadline.format(DateTimeFormatter.ofPattern("HH:mm"))}", fontSize = 13.sp)
            Text("已用时 %02d:%02d".format(elapsedSeconds.coerceAtLeast(0) / 60, elapsedSeconds.coerceAtLeast(0) % 60), fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        if (questions.isEmpty()) {
            Text("暂无口算题目，请在 Trello 描述的“作业内容”下每行填写一道题。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ArithmeticQuestionGrid(questions, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun ArithmeticQuestionGrid(questions: List<String>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
    val labels = remember(questions) { questions.mapIndexed { index, question -> "${index + 1}.  $question" } }
    val sizes = remember(labels, measurer, density.density, density.fontScale, baseStyle) {
        listOf(26, 24, 22, 20).map { font ->
            val measurements = labels.map { label ->
                measurer.measure(AnnotatedString(label), style = baseStyle.merge(TextStyle(fontSize = font.sp, lineHeight = (font * 1.25f).sp)), softWrap = false).size
            }
            ArithmeticCellSize(font, measurements.maxOf { it.width }, measurements.maxOf { it.height })
        }
    }
    BoxWithConstraints(modifier) {
        val gap = with(density) { 10.dp.roundToPx() }
        val plan = arithmeticGridPlan(questions.size, constraints.maxWidth, constraints.maxHeight, gap, sizes)
        Column(Modifier.fillMaxSize()) {
            if (!plan.fits) Text("共 ${questions.size} 题 · 上下滑动查看", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyVerticalGrid(
                columns = GridCells.Fixed(plan.columns),
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(labels) { _, label ->
                    // Wrap unusually long expressions instead of truncating a question.
                    Text(label, style = baseStyle, fontSize = plan.fontSp.sp, lineHeight = (plan.fontSp * 1.25f).sp)
                }
            }
        }
    }
}
