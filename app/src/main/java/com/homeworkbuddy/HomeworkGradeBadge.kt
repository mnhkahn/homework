package com.homeworkbuddy

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeworkGradeBadge(result: HomeworkGradeResult, label: String = "最近评分") {
    val (background, foreground) = when (result.grade) {
        HomeworkGrade.EXCELLENT -> Color(0xFFFFE9A6) to Color(0xFF704600)
        HomeworkGrade.GOOD -> Color(0xFFD9EFDE) to Color(0xFF205A32)
        HomeworkGrade.FAIR -> Color(0xFFFFE1BC) to Color(0xFF7C4200)
        HomeworkGrade.NEEDS_PRACTICE -> Color(0xFFFFDAD6) to Color(0xFF8C2824)
    }
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(if (result.grade == HomeworkGrade.EXCELLENT) 8.dp else 18.dp)) {
        Text("${result.grade.symbol} $label · ${result.fieldValue}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
    }
}
